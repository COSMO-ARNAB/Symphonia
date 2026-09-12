import * as mediasoup from "mediasoup";
import { appendFileSync } from "node:fs";
import type { Consumer, DtlsParameters, MediaKind, Producer, Router, RtpCapabilities, RtpParameters, WebRtcServer, WebRtcTransport, Worker } from "mediasoup/types";
import type { ConsumerInfo, JsonObject, ProducerInfo, RoomSfu, SfuFactory, TransportInfo } from "./sfu.js";

const mediaCodecs = [{
  kind: "audio" as MediaKind,
  mimeType: "audio/opus",
  clockRate: 48_000,
  channels: 2,
  parameters: { useinbandfec: 1, minptime: 10 }
}];

export interface MediasoupSfuOptions { listenIp: string; announcedAddress?: string; port: number }

export class MediasoupSfuFactory implements SfuFactory {
  private constructor(private readonly worker: Worker, private readonly webRtcServer: WebRtcServer) {}

  static async create(options: MediasoupSfuOptions): Promise<MediasoupSfuFactory> {
    const worker = await mediasoup.createWorker({ logLevel: "warn" });
    worker.on("died", (error) => {
      console.error(JSON.stringify({ level: "error", event: "mediasoup_worker_died", message: error.message }));
      setTimeout(() => process.exit(1), 2_000).unref();
    });
    const webRtcServer = await worker.createWebRtcServer({
      listenInfos: ["udp", "tcp"].map((protocol) => ({
        protocol: protocol as "udp" | "tcp",
        ip: options.listenIp,
        port: options.port,
        ...(options.announcedAddress === undefined ? {} : { announcedAddress: options.announcedAddress })
      }))
    });
    return new MediasoupSfuFactory(worker, webRtcServer);
  }

  async createRoom(_roomId: string, signal?: AbortSignal): Promise<RoomSfu> {
    const router = await this.worker.createRouter({ mediaCodecs });
    if (signal?.aborted) { router.close(); throw signal.reason; }
    return new MediasoupRoomSfu(router, this.webRtcServer);
  }

  async close(): Promise<void> { this.webRtcServer.close(); this.worker.close(); }
}

class MediasoupRoomSfu implements RoomSfu {
  private readonly transports = new Map<string, { owner: string; direction: "send" | "recv"; value: WebRtcTransport }>();
  private readonly producers = new Map<string, { owner: string; value: Producer }>();
  private readonly consumers = new Map<string, { owner: string; value: Consumer }>();
  private producerClosedHandler: (producerId: string, generation: number) => void = () => {};
  private transportClosedHandler: (participantId: string, transportId: string, direction: "send" | "recv", generation: number) => void = () => {};
  private consumerClosedHandler: (participantId: string, consumerId: string, generation: number) => void = () => {};
  private readonly pendingTransports = new Set<string>();
  private pendingConsumers = 0;
  private generation = 0;

  constructor(private readonly router: Router, private readonly webRtcServer: WebRtcServer) {}
  get rtpCapabilities(): unknown { return this.router.rtpCapabilities; }
  onProducerClosed(handler: (producerId: string, generation: number) => void): void { this.producerClosedHandler = handler; }
  onTransportClosed(handler: (participantId: string, transportId: string, direction: "send" | "recv", generation: number) => void): void { this.transportClosedHandler = handler; }
  onConsumerClosed(handler: (participantId: string, consumerId: string, generation: number) => void): void { this.consumerClosedHandler = handler; }

  async createTransport(participantId: string, direction: "send" | "recv", signal?: AbortSignal): Promise<TransportInfo> {
    const reservation = `${participantId}:${direction}`;
    if (this.pendingTransports.has(reservation) || [...this.transports.values()].some((item) => item.owner === participantId && item.direction === direction)) throw new Error("transport limit reached");
    if (this.transports.size + this.pendingTransports.size >= 31) throw new Error("room transport limit reached");
    this.pendingTransports.add(reservation);
    let transport: WebRtcTransport;
    try { transport = await this.router.createWebRtcTransport({ webRtcServer: this.webRtcServer, enableUdp: true, enableTcp: true, preferUdp: true }); }
    finally { this.pendingTransports.delete(reservation); }
    const result: TransportInfo = { id: transport.id, generation: ++this.generation, closed: false, iceParameters: transport.iceParameters, iceCandidates: transport.iceCandidates, dtlsParameters: transport.dtlsParameters };
    if (signal?.aborted) { transport.close(); throw signal.reason; }
    this.transports.set(transport.id, { owner: participantId, direction, value: transport });
    transport.on("dtlsstatechange", (state) => { if (state === "closed") transport.close(); });
    transport.observer.once("close", () => {
      result.closed = true;
      this.transports.delete(transport.id);
      this.transportClosedHandler(participantId, transport.id, direction, result.generation);
    });
    return result;
  }

  async connectTransport(participantId: string, transportId: string, dtlsParameters: JsonObject): Promise<void> {
    const transport = this.transport(participantId, transportId);
    await transport.value.connect({ dtlsParameters: dtlsParameters as unknown as DtlsParameters });
  }

  async produce(participantId: string, transportId: string, rtpParameters: JsonObject, appData: JsonObject = {}, signal?: AbortSignal): Promise<ProducerInfo> {
    const transport = this.transport(participantId, transportId, "send");
    const producer = await transport.value.produce({ kind: "audio", rtpParameters: rtpParameters as unknown as RtpParameters, appData });
    const result: ProducerInfo = { id: producer.id, kind: "audio", generation: ++this.generation, closed: false };
    if (signal?.aborted) { producer.close(); throw signal.reason; }
    this.producers.set(producer.id, { owner: participantId, value: producer });
    producer.on("transportclose", () => this.producers.delete(producer.id));
    producer.observer.once("close", () => {
      this.producers.delete(producer.id);
      result.closed = true;
      this.producerClosedHandler(producer.id, result.generation);
    });
    if (producer.closed) { result.closed = true; this.producers.delete(producer.id); }
    return result;
  }

  canConsume(producerId: string, rtpCapabilities: JsonObject): boolean {
    const ok = this.router.canConsume({ producerId, rtpCapabilities: rtpCapabilities as unknown as RtpCapabilities });
    if (!ok) {
      // THROWAWAY spike diagnostic: dump producer codecs vs consumer caps to a file
      // (stdout of this daemon is unreliable on this Windows box - orphaned wrappers)
      const producer = this.producers.get(producerId)?.value;
      const producerCodecs = producer ? JSON.stringify((producer as unknown as { rtpParameters: { codecs?: unknown[] } }).rtpParameters.codecs) : "producer not found";
      const line = JSON.stringify({ level: "warn", event: "canConsume_mismatch", producerId, producerCodecs, consumerCaps: JSON.stringify(rtpCapabilities).slice(0, 800) });
      try { appendFileSync("C:/Users/arnab/AppData/Local/Temp/spike-canconsume-diag.log", line + "\n"); } catch { /* best effort */ }
      console.warn(line);
    }
    return ok;
  }

  async consume(participantId: string, transportId: string, producerId: string, rtpCapabilities: JsonObject, signal?: AbortSignal): Promise<ConsumerInfo> {
    const transport = this.transport(participantId, transportId, "recv");
    if (this.consumers.size + this.pendingConsumers >= 30) throw new Error("room consumer limit reached");
    this.pendingConsumers++;
    let consumer: Consumer;
    try { consumer = await transport.value.consume({ producerId, rtpCapabilities: rtpCapabilities as unknown as RtpCapabilities, paused: true }); }
    finally { this.pendingConsumers--; }
    const result: ConsumerInfo = { id: consumer.id, producerId: consumer.producerId, kind: "audio", rtpParameters: consumer.rtpParameters, type: consumer.type, producerPaused: consumer.producerPaused, generation: ++this.generation, closed: false };
    if (signal?.aborted) { consumer.close(); throw signal.reason; }
    this.consumers.set(consumer.id, { owner: participantId, value: consumer });
    consumer.on("producerclose", () => { consumer.close(); this.consumers.delete(consumer.id); });
    consumer.observer.once("close", () => {
      result.closed = true;
      this.consumers.delete(consumer.id);
      this.consumerClosedHandler(participantId, consumer.id, result.generation);
    });
    if (consumer.closed) { result.closed = true; this.consumers.delete(consumer.id); }
    return result;
  }

  async resumeConsumer(participantId: string, consumerId: string): Promise<void> {
    const consumer = this.consumers.get(consumerId);
    if (!consumer || consumer.owner !== participantId) throw new Error("consumer not found");
    await consumer.value.resume();
  }

  async restartIce(participantId: string, transportId: string): Promise<unknown> {
    return this.transport(participantId, transportId).value.restartIce();
  }

  closeParticipant(participantId: string): void {
    for (const [id, consumer] of this.consumers) if (consumer.owner === participantId) { consumer.value.close(); this.consumers.delete(id); }
    for (const [id, producer] of this.producers) if (producer.owner === participantId) { producer.value.close(); this.producers.delete(id); }
    for (const [id, transport] of this.transports) if (transport.owner === participantId) { transport.value.close(); this.transports.delete(id); }
  }

  close(): void { this.router.close(); this.pendingTransports.clear(); this.transports.clear(); this.producers.clear(); this.consumers.clear(); }

  private transport(participantId: string, id: string, direction?: "send" | "recv") {
    const transport = this.transports.get(id);
    if (!transport || transport.owner !== participantId || (direction && transport.direction !== direction)) throw new Error("transport not found or invalid direction");
    return transport;
  }
}
