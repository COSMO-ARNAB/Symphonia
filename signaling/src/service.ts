import { randomBytes, randomUUID } from "node:crypto";
import type { ClientRequest, ServerMessage } from "./protocol.js";
import { ProtocolFailure } from "./protocol.js";
import type { RoomSfu, SfuFactory } from "./sfu.js";

export type Role = "HOST" | "SPEAKER" | "LISTENER";
export interface ClientPeer { readonly connectionId: string; send(message: ServerMessage): void }

interface Participant { id: string; role: Role; displayName?: string; peer: ClientPeer; consumers: Map<string, number>; sendTransport?: { id: string; generation: number }; recvTransport?: { id: string; generation: number }; pendingTransport?: "send" | "recv"; consumerPending?: boolean }
interface Room {
  id: string;
  sfu: RoomSfu;
  participants: Map<string, Participant>;
  hostId: string;
  producer?: { id: string; participantId: string; generation: number };
  producing: boolean;
  state: { state: "LIVE" | "PAUSED" | "RECONNECTING" | "STOPPED"; appLabel?: string | null; reason?: string | null };
  pendingConsumers: number;
}

const MAX_LISTENERS = 30;
const MAX_ROOMS = 16;
const DEFAULT_SFU_OPERATION_TIMEOUT_MS = 10_000;

export class SignalingService {
  private readonly rooms = new Map<string, Room>();
  private readonly connectionRooms = new Map<string, string>();
  private readonly disconnected = new WeakSet<ClientPeer>();
  private pendingRooms = 0;

  constructor(private readonly sfuFactory: SfuFactory, private readonly sfuOperationTimeoutMs = DEFAULT_SFU_OPERATION_TIMEOUT_MS) {}

  async handle(peer: ClientPeer, request: ClientRequest): Promise<unknown> {
    switch (request.type) {
      case "CREATE_ROOM": return this.createRoom(peer, request.data?.displayName);
      case "JOIN_ROOM": return this.joinRoom(peer, request.data.roomId, request.data.displayName);
      case "LEAVE_ROOM": this.leave(peer); return {};
      case "PING": peer.send({ type: "LATENCY_PONG", data: { timestamp: request.data?.timestamp ?? Date.now() } }); return { pong: true };
      case "PONG": return { acknowledged: true };
      case "GET_ROUTER_RTP_CAPABILITIES": return { rtpCapabilities: this.membership(peer).room.sfu.rtpCapabilities };
      case "CREATE_WEBRTC_TRANSPORT": return this.createTransport(peer, request.data.direction);
      case "CONNECT_WEBRTC_TRANSPORT": return this.withSfuFailure(() => { const { room, participant } = this.membership(peer); return room.sfu.connectTransport(participant.id, request.data.transportId, request.data.dtlsParameters); });
      case "PRODUCE": return this.produce(peer, request.data.transportId, request.data.rtpParameters, request.data.appData);
      case "CONSUME": return this.consume(peer, request.data.transportId, request.data.producerId, request.data.rtpCapabilities);
      case "RESUME_CONSUMER": return this.resumeConsumer(peer, request.data.consumerId);
      case "RESTART_ICE": return this.restartIce(peer, request.data.transportId);
      case "STATE_UPDATE": return this.updateState(peer, {
        state: request.data.state,
        ...(request.data.appLabel === undefined ? {} : { appLabel: request.data.appLabel }),
        ...(request.data.reason === undefined ? {} : { reason: request.data.reason })
      });
    }
  }

  disconnect(peer: ClientPeer): void { this.disconnected.add(peer); this.leave(peer); }

  async close(): Promise<void> {
    for (const room of this.rooms.values()) room.sfu.close();
    this.rooms.clear();
    this.connectionRooms.clear();
    await this.sfuFactory.close();
  }

  private async createRoom(peer: ClientPeer, displayName?: string) {
    this.ensureNotJoined(peer);
    this.ensureConnected(peer);
    if (this.rooms.size + this.pendingRooms >= MAX_ROOMS) throw new ProtocolFailure("N-ROOM_FULL", `Prototype room limit of ${MAX_ROOMS} reached`);
    this.pendingRooms++;
    const id = randomBytes(32).toString("base64url");
    let sfu: RoomSfu;
    try { sfu = await this.withSfuFailure((signal) => this.sfuFactory.createRoom(id, signal)); }
    finally { this.pendingRooms--; }
    if (this.disconnected.has(peer)) { sfu.close(); throw new ProtocolFailure("N-NOT_IN_ROOM", "Connection closed before room creation completed"); }
    const participant = this.participant(peer, "HOST", displayName);
    const room: Room = { id, sfu, participants: new Map([[participant.id, participant]]), hostId: participant.id, state: { state: "STOPPED" }, producing: false, pendingConsumers: 0 };
    this.rooms.set(id, room);
    sfu.onProducerClosed((producerId, generation) => this.onProducerClosed(room, producerId, generation));
    sfu.onTransportClosed((participantId, transportId, direction, generation) => this.onTransportClosed(room, participantId, transportId, direction, generation));
    sfu.onConsumerClosed((participantId, consumerId, generation) => this.onConsumerClosed(room, participantId, consumerId, generation));
    this.connectionRooms.set(peer.connectionId, id);
    return { roomId: id, participantId: participant.id, role: participant.role, localPrototypeOnly: true };
  }

  private joinRoom(peer: ClientPeer, roomId: string, displayName?: string) {
    this.ensureNotJoined(peer);
    this.ensureConnected(peer);
    const room = this.rooms.get(roomId);
    if (!room) throw new ProtocolFailure("N-ROOM_NOT_FOUND", "Room does not exist");
    if (this.listenerCount(room) >= MAX_LISTENERS) throw new ProtocolFailure("N-ROOM_FULL", `Room listener limit of ${MAX_LISTENERS} reached`);
    const participant = this.participant(peer, "LISTENER", displayName);
    room.participants.set(participant.id, participant);
    this.connectionRooms.set(peer.connectionId, room.id);
    this.broadcast(room, { type: "PARTICIPANT_CONNECTED", data: this.publicParticipant(participant) }, participant.id);
    if (room.producer) peer.send({ type: "PRODUCER_AVAILABLE", data: { producerId: room.producer.id } });
    peer.send({ type: "STATE_UPDATE", data: room.state });
    return { roomId, participantId: participant.id, role: participant.role, participants: [...room.participants.values()].map((p) => this.publicParticipant(p)), producerId: room.producer?.id ?? null, state: room.state };
  }

  private leave(peer: ClientPeer): void {
    const roomId = this.connectionRooms.get(peer.connectionId);
    if (!roomId) return;
    this.connectionRooms.delete(peer.connectionId);
    const room = this.rooms.get(roomId);
    if (!room) return;
    const participant = [...room.participants.values()].find((item) => item.peer.connectionId === peer.connectionId);
    if (!participant) return;
    room.sfu.closeParticipant(participant.id);
    room.participants.delete(participant.id);
    this.broadcast(room, { type: "PARTICIPANT_DISCONNECTED", data: this.publicParticipant(participant) });
    if (participant.id === room.hostId) {
      this.broadcast(room, { type: "ROOM_CLOSED", data: { roomId: room.id, reason: "host_ended" } }, participant.id);
      for (const remaining of room.participants.values()) this.connectionRooms.delete(remaining.peer.connectionId);
      room.sfu.close();
      this.rooms.delete(room.id);
    }
  }

  private async createTransport(peer: ClientPeer, direction: "send" | "recv") {
    const { room, participant } = this.membership(peer);
    if (direction === "send" && !this.canPublish(participant)) throw new ProtocolFailure("N-FORBIDDEN", "Only the Host or Speaker may create a send transport");
    if (direction === "recv" && participant.role !== "LISTENER") throw new ProtocolFailure("N-FORBIDDEN", "Only Listeners may create a receive transport");
    const current = direction === "send" ? participant.sendTransport : participant.recvTransport;
    if (current || participant.pendingTransport === direction) throw new ProtocolFailure("N-CONFLICT", `Participant already has a ${direction} transport`);
    participant.pendingTransport = direction;
    try {
      const transport = await this.withSfuFailure((signal) => room.sfu.createTransport(participant.id, direction, signal));
      if (!this.isCurrentMembership(peer, room, participant)) { room.sfu.closeParticipant(participant.id); throw new ProtocolFailure("N-NOT_IN_ROOM", "Connection closed before transport creation completed"); }
      if (transport.closed) throw new ProtocolFailure("N-SFU_FAILURE", "SFU resource closed during creation");
      const lifecycle = { id: transport.id, generation: transport.generation };
      if (direction === "send") participant.sendTransport = lifecycle; else participant.recvTransport = lifecycle;
      return transport;
    } finally {
      if (participant.pendingTransport === direction) delete participant.pendingTransport;
    }
  }

  private async produce(peer: ClientPeer, transportId: string, rtpParameters: Record<string, unknown>, appData?: Record<string, unknown>) {
    const { room, participant } = this.membership(peer);
    if (!this.canPublish(participant)) throw new ProtocolFailure("N-FORBIDDEN", "Only the Host or Speaker may produce audio");
    if (room.producer || room.producing) throw new ProtocolFailure("N-CONFLICT", "The room already has an active or pending producer");
    room.producing = true;
    try {
      const producer = await this.withSfuFailure((signal) => room.sfu.produce(participant.id, transportId, rtpParameters, appData, signal));
      if (!this.isCurrentMembership(peer, room, participant)) { room.sfu.closeParticipant(participant.id); throw new ProtocolFailure("N-NOT_IN_ROOM", "Connection closed before producer creation completed"); }
      if (producer.closed) throw new ProtocolFailure("N-SFU_FAILURE", "SFU resource closed during creation");
      room.producer = { id: producer.id, participantId: participant.id, generation: producer.generation };
      this.broadcast(room, { type: "PRODUCER_AVAILABLE", data: { producerId: producer.id, participantId: participant.id } }, participant.id);
      return producer;
    } finally {
      room.producing = false;
    }
  }

  private async consume(peer: ClientPeer, transportId: string, producerId: string, rtpCapabilities: Record<string, unknown>) {
    const { room, participant } = this.membership(peer);
    if (participant.role !== "LISTENER") throw new ProtocolFailure("N-FORBIDDEN", "Only Listeners may consume audio");
    if (!room.producer || room.producer.id !== producerId) throw new ProtocolFailure("N-NOT_FOUND", "Producer not found in this room");
    if (participant.consumers.size > 0 || participant.consumerPending) throw new ProtocolFailure("N-CONFLICT", "Listener already has an active or pending consumer");
    if (this.consumerCount(room) + room.pendingConsumers >= MAX_LISTENERS) throw new ProtocolFailure("N-ROOM_FULL", `SFU consumer limit of ${MAX_LISTENERS} reached`);
    if (!room.sfu.canConsume(producerId, rtpCapabilities)) throw new ProtocolFailure("N-CANNOT_CONSUME", "RTP capabilities cannot consume this producer");
    room.pendingConsumers++;
    participant.consumerPending = true;
    try {
      const consumer = await this.withSfuFailure((signal) => room.sfu.consume(participant.id, transportId, producerId, rtpCapabilities, signal));
      if (!this.isCurrentMembership(peer, room, participant)) { room.sfu.closeParticipant(participant.id); throw new ProtocolFailure("N-NOT_IN_ROOM", "Connection closed before consumer creation completed"); }
      if (consumer.closed) throw new ProtocolFailure("N-SFU_FAILURE", "SFU resource closed during creation");
      participant.consumers.set(consumer.id, consumer.generation);
      return consumer;
    } finally {
      room.pendingConsumers--;
      delete participant.consumerPending;
    }
  }

  private async resumeConsumer(peer: ClientPeer, consumerId: string) {
    const { room, participant } = this.membership(peer);
    if (!participant.consumers.has(consumerId)) throw new ProtocolFailure("N-NOT_FOUND", "Consumer not found for participant");
    await this.withSfuFailure(() => room.sfu.resumeConsumer(participant.id, consumerId));
    return {};
  }

  private async restartIce(peer: ClientPeer, transportId: string) {
    const { room, participant } = this.membership(peer);
    return { iceParameters: await this.withSfuFailure(() => room.sfu.restartIce(participant.id, transportId)) };
  }

  private updateState(peer: ClientPeer, state: Room["state"]) {
    const { room, participant } = this.membership(peer);
    if (!this.canPublish(participant)) throw new ProtocolFailure("N-FORBIDDEN", "Only the Host or Speaker may update sharing state");
    room.state = state;
    this.broadcast(room, { type: "STATE_UPDATE", data: state });
    return state;
  }

  private membership(peer: ClientPeer) {
    const roomId = this.connectionRooms.get(peer.connectionId);
    const room = roomId ? this.rooms.get(roomId) : undefined;
    const participant = room ? [...room.participants.values()].find((item) => item.peer.connectionId === peer.connectionId) : undefined;
    if (!room || !participant) throw new ProtocolFailure("N-NOT_IN_ROOM", "Connection is not in a room");
    return { room, participant };
  }

  private ensureNotJoined(peer: ClientPeer) { if (this.connectionRooms.has(peer.connectionId)) throw new ProtocolFailure("N-ALREADY_IN_ROOM", "Connection is already in a room"); }
  private ensureConnected(peer: ClientPeer) { if (this.disconnected.has(peer)) throw new ProtocolFailure("N-NOT_IN_ROOM", "Connection is closed"); }
  private participant(peer: ClientPeer, role: Role, displayName?: string): Participant { return { id: randomUUID(), role, ...(displayName === undefined ? {} : { displayName }), peer, consumers: new Map() }; }
  private publicParticipant(participant: Participant) { return { participantId: participant.id, role: participant.role, displayName: participant.displayName ?? null }; }
  private canPublish(participant: Participant) { return participant.role === "HOST" || participant.role === "SPEAKER"; }
  private listenerCount(room: Room) { return [...room.participants.values()].filter((p) => p.role === "LISTENER").length; }
  private consumerCount(room: Room) { return [...room.participants.values()].reduce((count, p) => count + p.consumers.size, 0); }
  private broadcast(room: Room, message: ServerMessage, exceptId?: string) { for (const participant of room.participants.values()) if (participant.id !== exceptId) participant.peer.send(message); }
  private onProducerClosed(room: Room, producerId: string, generation: number) {
    if (room.producer?.id !== producerId || room.producer.generation !== generation) return;
    delete room.producer;
    for (const participant of room.participants.values()) participant.consumers.clear();
    this.broadcast(room, { type: "PRODUCER_CLOSED", data: { producerId } });
    room.state = { state: "STOPPED" };
    this.broadcast(room, { type: "STATE_UPDATE", data: room.state });
  }
  private onTransportClosed(room: Room, participantId: string, transportId: string, direction: "send" | "recv", generation: number) {
    const participant = room.participants.get(participantId);
    if (!participant) return;
    const current = direction === "send" ? participant.sendTransport : participant.recvTransport;
    if (current?.id !== transportId || current.generation !== generation) return;
    if (direction === "send") delete participant.sendTransport; else delete participant.recvTransport;
    participant.peer.send({ type: "TRANSPORT_CLOSED", data: { transportId, direction } });
  }
  private onConsumerClosed(room: Room, participantId: string, consumerId: string, generation: number) {
    const participant = room.participants.get(participantId);
    if (!participant || participant.consumers.get(consumerId) !== generation) return;
    participant.consumers.delete(consumerId);
    participant.peer.send({ type: "CONSUMER_CLOSED", data: { consumerId } });
  }
  private isCurrentMembership(peer: ClientPeer, room: Room, participant: Participant) { return !this.disconnected.has(peer) && this.rooms.get(room.id) === room && room.participants.get(participant.id) === participant; }
  private async withSfuFailure<T>(operation: (signal: AbortSignal) => Promise<T>): Promise<T> {
    const controller = new AbortController();
    let timer: NodeJS.Timeout | undefined;
    try {
      return await Promise.race([
        operation(controller.signal),
        new Promise<T>((_, reject) => { timer = setTimeout(() => { controller.abort(new Error("SFU operation timed out")); reject(controller.signal.reason); }, this.sfuOperationTimeoutMs); })
      ]);
    } catch (error) {
      if (error instanceof ProtocolFailure) throw error;
      throw new ProtocolFailure("N-SFU_FAILURE", "SFU operation failed");
    } finally {
      if (timer) clearTimeout(timer);
    }
  }
}
