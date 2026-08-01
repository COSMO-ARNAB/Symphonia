import type { ConsumerInfo, JsonObject, RoomSfu, SfuFactory, TransportInfo } from "../src/sfu.js";

export class FakeSfuFactory implements SfuFactory {
  rooms: FakeRoomSfu[] = [];
  createRoomGate?: Promise<void>;
  async createRoom(_roomId?: string, signal?: AbortSignal): Promise<RoomSfu> { await this.createRoomGate; const room = new FakeRoomSfu(); if (signal?.aborted) { room.close(); throw signal.reason; } this.rooms.push(room); return room; }
  async close(): Promise<void> {}
}

export class FakeRoomSfu implements RoomSfu {
  readonly rtpCapabilities = { codecs: [{ mimeType: "audio/opus", clockRate: 48000, channels: 2 }] };
  readonly closedParticipants: string[] = [];
  closed = false;
  createTransportGate?: Promise<void>;
  consumeGate?: Promise<void>;
  produceGate?: Promise<void>;
  transportError?: Error;
  closeProducerDuringCreation = false;
  private producerClosedHandler: (producerId: string, generation: number) => void = () => {};
  private transportClosedHandler: (participantId: string, transportId: string, direction: "send" | "recv", generation: number) => void = () => {};
  private consumerClosedHandler: (participantId: string, consumerId: string, generation: number) => void = () => {};
  private sequence = 0;
  private readonly transports = new Map<string, { owner: string; direction: "send" | "recv" }>();
  private readonly consumers = new Map<string, { owner: string; generation: number }>();
  private readonly producers = new Map<string, { owner: string; generation: number }>();
  onProducerClosed(handler: (producerId: string, generation: number) => void): void { this.producerClosedHandler = handler; }
  onTransportClosed(handler: (participantId: string, transportId: string, direction: "send" | "recv", generation: number) => void): void { this.transportClosedHandler = handler; }
  onConsumerClosed(handler: (participantId: string, consumerId: string, generation: number) => void): void { this.consumerClosedHandler = handler; }
  async createTransport(participantId: string, direction: "send" | "recv", signal?: AbortSignal): Promise<TransportInfo> { await this.createTransportGate; if (this.transportError) throw this.transportError; if (signal?.aborted) throw signal.reason; const generation = ++this.sequence; const id = `transport-${generation}`; this.transports.set(id, { owner: participantId, direction }); return { id, generation, closed: false, iceParameters: {}, iceCandidates: [], dtlsParameters: {} }; }
  connectTransport(participantId: string, transportId: string): Promise<void> { this.assertTransport(participantId, transportId); return Promise.resolve(); }
  async produce(participantId: string, transportId: string, _rtp?: JsonObject, _appData?: JsonObject, signal?: AbortSignal): Promise<{ id: string; kind: "audio"; generation: number; closed: boolean }> { this.assertTransport(participantId, transportId, "send"); await this.produceGate; if (signal?.aborted) throw signal.reason; const generation = ++this.sequence; const id = `producer-${generation}`; const result = { id, kind: "audio" as const, generation, closed: false }; this.producers.set(id, { owner: participantId, generation }); if (this.closeProducerDuringCreation) { result.closed = true; this.producers.delete(id); this.producerClosedHandler(id, generation); } return result; }
  canConsume(_producerId: string, rtpCapabilities: JsonObject): boolean { return rtpCapabilities.compatible !== false; }
  async consume(participantId: string, transportId: string, producerId: string, _rtp?: JsonObject, signal?: AbortSignal): Promise<ConsumerInfo> { this.assertTransport(participantId, transportId, "recv"); await this.consumeGate; if (signal?.aborted) throw signal.reason; const generation = ++this.sequence; const id = `consumer-${generation}`; this.consumers.set(id, { owner: participantId, generation }); return { id, producerId, kind: "audio", rtpParameters: {}, type: "simple", producerPaused: false, generation, closed: false }; }
  resumeConsumer(participantId: string, consumerId: string): Promise<void> { if (this.consumers.get(consumerId)?.owner !== participantId) throw new Error("consumer not found"); return Promise.resolve(); }
  restartIce(participantId: string, transportId: string): Promise<unknown> { this.assertTransport(participantId, transportId); return Promise.resolve({ usernameFragment: "ice" }); }
  closeParticipant(participantId: string): void { this.closedParticipants.push(participantId); for (const [id, item] of this.consumers) if (item.owner === participantId) { this.consumers.delete(id); this.consumerClosedHandler(participantId, id, item.generation); } for (const [id, transport] of this.transports) if (transport.owner === participantId) { this.transports.delete(id); this.transportClosedHandler(participantId, id, transport.direction, Number(id.split("-")[1])); } for (const [id, item] of this.producers) if (item.owner === participantId) { this.producers.delete(id); this.producerClosedHandler(id, item.generation); } }
  close(): void { this.closed = true; }
  emitProducerClosed(producerId: string): void { const item = this.producers.get(producerId); if (!item) return; this.producers.delete(producerId); this.producerClosedHandler(producerId, item.generation); }
  emitTransportClosed(transportId: string): void { const item = this.transports.get(transportId); if (!item) return; this.transports.delete(transportId); this.transportClosedHandler(item.owner, transportId, item.direction, Number(transportId.split("-")[1])); }
  emitConsumerClosed(consumerId: string): void { const item = this.consumers.get(consumerId); if (!item) return; this.consumers.delete(consumerId); this.consumerClosedHandler(item.owner, consumerId, item.generation); }
  private assertTransport(owner: string, id: string, direction?: "send" | "recv") { const transport = this.transports.get(id); if (!transport || transport.owner !== owner || (direction && direction !== transport.direction)) throw new Error("bad transport"); }
}
