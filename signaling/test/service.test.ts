import assert from "node:assert/strict";
import { describe, it } from "node:test";
import type { ClientRequest, ServerMessage } from "../src/protocol.js";
import { ProtocolFailure } from "../src/protocol.js";
import { SignalingService, type ClientPeer } from "../src/service.js";
import { FakeSfuFactory } from "./fake-sfu.js";

class Peer implements ClientPeer {
  readonly messages: ServerMessage[] = [];
  constructor(readonly connectionId: string) {}
  send(message: ServerMessage) { this.messages.push(message); }
}

const request = (id: string, type: ClientRequest["type"], data?: unknown) => ({ id, type, ...(data === undefined ? {} : { data }) }) as ClientRequest;
const failure = async (operation: () => Promise<unknown>, code: string) => assert.rejects(operation, (error: unknown) => error instanceof ProtocolFailure && error.code === code);

describe("SignalingService", () => {
  it("creates an unguessable local-only room and notifies joins and leaves", async () => {
    const service = new SignalingService(new FakeSfuFactory());
    const host = new Peer("host");
    const listener = new Peer("listener");
    const created = await service.handle(host, request("1", "CREATE_ROOM", { displayName: "Host" })) as { roomId: string; localPrototypeOnly: boolean };
    assert.match(created.roomId, /^[A-Za-z0-9_-]{43}$/);
    assert.equal(created.localPrototypeOnly, true);
    await service.handle(listener, request("2", "JOIN_ROOM", { roomId: created.roomId, displayName: "Listener" }));
    assert.equal(host.messages.at(-1)?.type, "PARTICIPANT_CONNECTED");
    await service.handle(listener, request("3", "LEAVE_ROOM"));
    assert.equal(host.messages.at(-1)?.type, "PARTICIPANT_DISCONNECTED");
  });

  it("enforces 30 listeners at room join", async () => {
    const service = new SignalingService(new FakeSfuFactory());
    const created = await service.handle(new Peer("host"), request("1", "CREATE_ROOM")) as { roomId: string };
    for (let index = 0; index < 30; index++) await service.handle(new Peer(`listener-${index}`), request("j", "JOIN_ROOM", { roomId: created.roomId }));
    await failure(() => service.handle(new Peer("overflow"), request("j", "JOIN_ROOM", { roomId: created.roomId })), "N-ROOM_FULL");
  });

  it("restricts publishing to host, creates one Opus producer, and notifies listeners", async () => {
    const service = new SignalingService(new FakeSfuFactory());
    const host = new Peer("host");
    const listener = new Peer("listener");
    const { roomId } = await service.handle(host, request("1", "CREATE_ROOM")) as { roomId: string };
    await service.handle(listener, request("2", "JOIN_ROOM", { roomId }));
    await failure(() => service.handle(listener, request("3", "CREATE_WEBRTC_TRANSPORT", { direction: "send" })), "N-FORBIDDEN");
    const transport = await service.handle(host, request("4", "CREATE_WEBRTC_TRANSPORT", { direction: "send" })) as { id: string };
    const producer = await service.handle(host, request("5", "PRODUCE", { transportId: transport.id, kind: "audio", rtpParameters: {} })) as { id: string };
    assert.equal(listener.messages.at(-1)?.type, "PRODUCER_AVAILABLE");
    await failure(() => service.handle(host, request("6", "PRODUCE", { transportId: transport.id, kind: "audio", rtpParameters: {} })), "N-CONFLICT");
    assert.match(producer.id, /^producer-/);
  });

  it("restricts consuming to listeners and validates RTP compatibility", async () => {
    const service = new SignalingService(new FakeSfuFactory());
    const host = new Peer("host");
    const listener = new Peer("listener");
    const { roomId } = await service.handle(host, request("1", "CREATE_ROOM")) as { roomId: string };
    await service.handle(listener, request("2", "JOIN_ROOM", { roomId }));
    const send = await service.handle(host, request("3", "CREATE_WEBRTC_TRANSPORT", { direction: "send" })) as { id: string };
    const producer = await service.handle(host, request("4", "PRODUCE", { transportId: send.id, kind: "audio", rtpParameters: {} })) as { id: string };
    const receive = await service.handle(listener, request("5", "CREATE_WEBRTC_TRANSPORT", { direction: "recv" })) as { id: string };
    await failure(() => service.handle(listener, request("6", "CONSUME", { transportId: receive.id, producerId: producer.id, rtpCapabilities: { compatible: false } })), "N-CANNOT_CONSUME");
    const consumer = await service.handle(listener, request("7", "CONSUME", { transportId: receive.id, producerId: producer.id, rtpCapabilities: {} })) as { id: string };
    await service.handle(listener, request("8", "RESUME_CONSUMER", { consumerId: consumer.id }));
    await failure(() => service.handle(host, request("9", "CONSUME", { transportId: send.id, producerId: producer.id, rtpCapabilities: {} })), "N-FORBIDDEN");
  });

  it("only lets publisher roles update state and closes a room when its host disconnects", async () => {
    const factory = new FakeSfuFactory();
    const service = new SignalingService(factory);
    const host = new Peer("host");
    const listener = new Peer("listener");
    const { roomId } = await service.handle(host, request("1", "CREATE_ROOM")) as { roomId: string };
    await service.handle(listener, request("2", "JOIN_ROOM", { roomId }));
    await failure(() => service.handle(listener, request("3", "STATE_UPDATE", { state: "LIVE", appLabel: "Music" })), "N-FORBIDDEN");
    await service.handle(host, request("4", "STATE_UPDATE", { state: "PAUSED", appLabel: "Music", reason: "app_ended" }));
    assert.equal(listener.messages.at(-1)?.type, "STATE_UPDATE");
    service.disconnect(host);
    assert.equal(listener.messages.at(-1)?.type, "ROOM_CLOSED");
    assert.equal(factory.rooms[0]?.closed, true);
    await failure(() => service.handle(listener, request("5", "GET_ROUTER_RTP_CAPABILITIES")), "N-NOT_IN_ROOM");
  });

  it("bounds rooms and allows only one transport in each permitted direction", async () => {
    const service = new SignalingService(new FakeSfuFactory());
    for (let index = 0; index < 16; index++) await service.handle(new Peer(`host-${index}`), request("c", "CREATE_ROOM"));
    await failure(() => service.handle(new Peer("overflow"), request("c", "CREATE_ROOM")), "N-ROOM_FULL");

    const factory = new FakeSfuFactory();
    const transportService = new SignalingService(factory);
    const host = new Peer("transport-host");
    const listener = new Peer("transport-listener");
    const { roomId } = await transportService.handle(host, request("1", "CREATE_ROOM")) as { roomId: string };
    await transportService.handle(listener, request("2", "JOIN_ROOM", { roomId }));
    await transportService.handle(host, request("3", "CREATE_WEBRTC_TRANSPORT", { direction: "send" }));
    await failure(() => transportService.handle(host, request("4", "CREATE_WEBRTC_TRANSPORT", { direction: "send" })), "N-CONFLICT");
    await transportService.handle(listener, request("5", "CREATE_WEBRTC_TRANSPORT", { direction: "recv" }));
    await failure(() => transportService.handle(listener, request("6", "CREATE_WEBRTC_TRANSPORT", { direction: "recv" })), "N-CONFLICT");
  });

  it("reserves concurrent resources and rejects stale async completion after disconnect", async () => {
    let releaseTransport!: () => void;
    const gate = new Promise<void>((resolve) => { releaseTransport = resolve; });
    const factory = new FakeSfuFactory();
    const service = new SignalingService(factory);
    const host = new Peer("host");
    await service.handle(host, request("1", "CREATE_ROOM"));
    factory.rooms[0]!.createTransportGate = gate;
    const pending = service.handle(host, request("2", "CREATE_WEBRTC_TRANSPORT", { direction: "send" }));
    await failure(() => service.handle(host, request("3", "CREATE_WEBRTC_TRANSPORT", { direction: "send" })), "N-CONFLICT");
    service.disconnect(host);
    releaseTransport();
    await failure(() => pending, "N-NOT_IN_ROOM");
    assert.equal(factory.rooms[0]!.closedParticipants.length > 0, true);
  });

  it("redacts SFU errors and forces STOPPED when the producer closes", async () => {
    const factory = new FakeSfuFactory();
    const service = new SignalingService(factory);
    const host = new Peer("host");
    const listener = new Peer("listener");
    const { roomId } = await service.handle(host, request("1", "CREATE_ROOM")) as { roomId: string };
    await service.handle(listener, request("2", "JOIN_ROOM", { roomId }));
    factory.rooms[0]!.transportError = new Error("secret native stack path");
    await assert.rejects(() => service.handle(host, request("3", "CREATE_WEBRTC_TRANSPORT", { direction: "send" })), (error: unknown) => error instanceof ProtocolFailure && error.code === "N-SFU_FAILURE" && error.details === undefined && !error.message.includes("secret"));
    delete factory.rooms[0]!.transportError;
    const transport = await service.handle(host, request("4", "CREATE_WEBRTC_TRANSPORT", { direction: "send" })) as { id: string };
    const producer = await service.handle(host, request("5", "PRODUCE", { transportId: transport.id, kind: "audio", rtpParameters: {} })) as { id: string };
    await service.handle(host, request("6", "STATE_UPDATE", { state: "LIVE" }));
    factory.rooms[0]!.emitProducerClosed(producer.id);
    assert.equal(listener.messages.at(-2)?.type, "PRODUCER_CLOSED");
    assert.deepEqual(listener.messages.at(-1), { type: "STATE_UPDATE", data: { state: "STOPPED" } });
  });

  it("reserves one consumer per listener while creation is pending", async () => {
    let release!: () => void;
    const gate = new Promise<void>((resolve) => { release = resolve; });
    const factory = new FakeSfuFactory();
    const service = new SignalingService(factory);
    const host = new Peer("host");
    const listener = new Peer("listener");
    const { roomId } = await service.handle(host, request("1", "CREATE_ROOM")) as { roomId: string };
    await service.handle(listener, request("2", "JOIN_ROOM", { roomId }));
    const send = await service.handle(host, request("3", "CREATE_WEBRTC_TRANSPORT", { direction: "send" })) as { id: string };
    const producer = await service.handle(host, request("4", "PRODUCE", { transportId: send.id, kind: "audio", rtpParameters: {} })) as { id: string };
    const receive = await service.handle(listener, request("5", "CREATE_WEBRTC_TRANSPORT", { direction: "recv" })) as { id: string };
    factory.rooms[0]!.consumeGate = gate;
    const pending = service.handle(listener, request("6", "CONSUME", { transportId: receive.id, producerId: producer.id, rtpCapabilities: {} }));
    await failure(() => service.handle(listener, request("7", "CONSUME", { transportId: receive.id, producerId: producer.id, rtpCapabilities: {} })), "N-CONFLICT");
    release();
    await pending;
  });

  it("allows deterministic transport and consumer replacement after native closure", async () => {
    const factory = new FakeSfuFactory();
    const service = new SignalingService(factory);
    const host = new Peer("host");
    const listener = new Peer("listener");
    const { roomId } = await service.handle(host, request("1", "CREATE_ROOM")) as { roomId: string };
    await service.handle(listener, request("2", "JOIN_ROOM", { roomId }));
    const send = await service.handle(host, request("3", "CREATE_WEBRTC_TRANSPORT", { direction: "send" })) as { id: string };
    const producer = await service.handle(host, request("4", "PRODUCE", { transportId: send.id, kind: "audio", rtpParameters: {} })) as { id: string };
    const receive = await service.handle(listener, request("5", "CREATE_WEBRTC_TRANSPORT", { direction: "recv" })) as { id: string };
    const consumer = await service.handle(listener, request("6", "CONSUME", { transportId: receive.id, producerId: producer.id, rtpCapabilities: {} })) as { id: string };

    factory.rooms[0]!.emitConsumerClosed(consumer.id);
    assert.deepEqual(listener.messages.at(-1), { type: "CONSUMER_CLOSED", data: { consumerId: consumer.id } });
    const replacementConsumer = await service.handle(listener, request("7", "CONSUME", { transportId: receive.id, producerId: producer.id, rtpCapabilities: {} })) as { id: string };
    assert.notEqual(replacementConsumer.id, consumer.id);

    factory.rooms[0]!.emitTransportClosed(receive.id);
    assert.deepEqual(listener.messages.at(-1), { type: "TRANSPORT_CLOSED", data: { transportId: receive.id, direction: "recv" } });
    const replacementTransport = await service.handle(listener, request("8", "CREATE_WEBRTC_TRANSPORT", { direction: "recv" })) as { id: string };
    assert.notEqual(replacementTransport.id, receive.id);

    factory.rooms[0]!.emitTransportClosed(send.id);
    const replacementSend = await service.handle(host, request("9", "CREATE_WEBRTC_TRANSPORT", { direction: "send" })) as { id: string };
    assert.notEqual(replacementSend.id, send.id);
  });

  it("does not register a producer that closes during native creation", async () => {
    const factory = new FakeSfuFactory();
    const service = new SignalingService(factory);
    const host = new Peer("host");
    await service.handle(host, request("1", "CREATE_ROOM"));
    const send = await service.handle(host, request("2", "CREATE_WEBRTC_TRANSPORT", { direction: "send" })) as { id: string };
    factory.rooms[0]!.closeProducerDuringCreation = true;
    await failure(() => service.handle(host, request("3", "PRODUCE", { transportId: send.id, kind: "audio", rtpParameters: {} })), "N-SFU_FAILURE");
    factory.rooms[0]!.closeProducerDuringCreation = false;
    const replacement = await service.handle(host, request("4", "PRODUCE", { transportId: send.id, kind: "audio", rtpParameters: {} })) as { id: string };
    assert.match(replacement.id, /^producer-/);
  });

  it("times out SFU creation and prevents late resource registration", async () => {
    let release!: () => void;
    const gate = new Promise<void>((resolve) => { release = resolve; });
    const factory = new FakeSfuFactory();
    const service = new SignalingService(factory, 10);
    const host = new Peer("host");
    await service.handle(host, request("1", "CREATE_ROOM"));
    factory.rooms[0]!.createTransportGate = gate;
    await failure(() => service.handle(host, request("2", "CREATE_WEBRTC_TRANSPORT", { direction: "send" })), "N-SFU_FAILURE");
    release();
    await new Promise((resolve) => setTimeout(resolve, 0));
    delete factory.rooms[0]!.createTransportGate;
    const transport = await service.handle(host, request("3", "CREATE_WEBRTC_TRANSPORT", { direction: "send" })) as { id: string };
    assert.match(transport.id, /^transport-/);
  });
});
