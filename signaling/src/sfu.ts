export type JsonObject = Record<string, unknown>;

export interface TransportInfo {
  id: string;
  generation: number;
  closed: boolean;
  iceParameters: unknown;
  iceCandidates: unknown;
  dtlsParameters: unknown;
  sctpParameters?: unknown;
}

export interface ProducerInfo { id: string; kind: "audio"; generation: number; closed: boolean }
export interface ConsumerInfo { id: string; producerId: string; kind: "audio"; rtpParameters: unknown; type: string; producerPaused: boolean; generation: number; closed: boolean }

export interface RoomSfu {
  readonly rtpCapabilities: unknown;
  onProducerClosed(handler: (producerId: string, generation: number) => void): void;
  onTransportClosed(handler: (participantId: string, transportId: string, direction: "send" | "recv", generation: number) => void): void;
  onConsumerClosed(handler: (participantId: string, consumerId: string, generation: number) => void): void;
  createTransport(participantId: string, direction: "send" | "recv", signal?: AbortSignal): Promise<TransportInfo>;
  connectTransport(participantId: string, transportId: string, dtlsParameters: JsonObject): Promise<void>;
  produce(participantId: string, transportId: string, rtpParameters: JsonObject, appData?: JsonObject, signal?: AbortSignal): Promise<ProducerInfo>;
  canConsume(producerId: string, rtpCapabilities: JsonObject): boolean;
  consume(participantId: string, transportId: string, producerId: string, rtpCapabilities: JsonObject, signal?: AbortSignal): Promise<ConsumerInfo>;
  resumeConsumer(participantId: string, consumerId: string): Promise<void>;
  restartIce(participantId: string, transportId: string): Promise<unknown>;
  closeParticipant(participantId: string): void;
  close(): void;
}

export interface SfuFactory { createRoom(roomId: string, signal?: AbortSignal): Promise<RoomSfu>; close(): Promise<void> }
