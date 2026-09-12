import assert from "node:assert/strict";
import { describe, it } from "node:test";
import WebSocket from "ws";
import type { ServerMessage } from "../src/protocol.js";
import { SignalingServer } from "../src/server.js";
import { SignalingService } from "../src/service.js";
import { FakeSfuFactory } from "./fake-sfu.js";

const options = { host: "127.0.0.1", port: 0, unsafeAllowRemote: false, allowedOrigins: ["http://localhost:3000"], heartbeatIntervalMs: 20, heartbeatTimeoutMs: 100 };

async function started(factory = new FakeSfuFactory()) {
  const server = new SignalingServer(new SignalingService(factory), options);
  await server.listen();
  return { server, factory, url: `ws://127.0.0.1:${server.port}` };
}

function connect(url: string, path = "/gate2", origin?: string): Promise<WebSocket> {
  return new Promise((resolve, reject) => {
    const socket = new WebSocket(`${url}${path}`, origin === undefined ? {} : { origin });
    socket.once("open", () => resolve(socket));
    socket.once("error", reject);
  });
}

function nextMessage(socket: WebSocket): Promise<ServerMessage> {
  return new Promise((resolve) => socket.once("message", (data) => resolve(JSON.parse(data.toString()) as ServerMessage)));
}

function nextMessages(socket: WebSocket, count: number): Promise<ServerMessage[]> {
  return new Promise((resolve) => {
    const messages: ServerMessage[] = [];
    const handler = (data: WebSocket.RawData) => {
      messages.push(JSON.parse(data.toString()) as ServerMessage);
      if (messages.length === count) { socket.off("message", handler); resolve(messages); }
    };
    socket.on("message", handler);
  });
}

describe("SignalingServer", () => {
  it("accepts only /gate2 and approved or non-browser origins", async () => {
    const { server, url } = await started();
    try {
      await assert.rejects(connect(url, "/wrong"));
      await assert.rejects(connect(url, "/gate2", "https://evil.test"));
      const browser = await connect(url, "/gate2", "http://localhost:3000");
      browser.close();
      const native = await connect(url);
      native.close();
    } finally { await server.close(); }
  });

  it("enforces the connection capacity", async () => {
    const { server, url } = await started();
    const sockets: WebSocket[] = [];
    try {
      for (let index = 0; index < 64; index++) sockets.push(await connect(url));
      await assert.rejects(connect(url));
    } finally {
      for (const socket of sockets) socket.close();
      await server.close();
    }
  });

  it("serializes bounded work per socket", async () => {
    let release!: () => void;
    const gate = new Promise<void>((resolve) => { release = resolve; });
    const factory = new FakeSfuFactory();
    factory.createRoomGate = gate;
    const { server, url } = await started(factory);
    const socket = await connect(url);
    try {
      const responses: ServerMessage[] = [];
      socket.on("message", (data) => responses.push(JSON.parse(data.toString()) as ServerMessage));
      socket.send(JSON.stringify({ id: "1", type: "CREATE_ROOM" }));
      socket.send(JSON.stringify({ id: "2", type: "CREATE_ROOM" }));
      await new Promise((resolve) => setTimeout(resolve, 10));
      assert.equal(responses.length, 0);
      release();
      await new Promise((resolve) => setTimeout(resolve, 20));
      assert.equal(responses.length, 2);
      assert.equal(responses[0]?.type, "RESPONSE");
      assert.deepEqual(responses[1], { id: "2", type: "RESPONSE", ok: false, error: { code: "N-ALREADY_IN_ROOM", message: "Connection is already in a room" } });
    } finally { socket.close(); await server.close(); }
  });

  it("closes a socket whose serialized request queue exceeds its bound", async () => {
    let release!: () => void;
    const gate = new Promise<void>((resolve) => { release = resolve; });
    const factory = new FakeSfuFactory();
    factory.createRoomGate = gate;
    const { server, url } = await started(factory);
    const socket = await connect(url);
    try {
      const closed = new Promise<number>((resolve) => socket.once("close", resolve));
      for (let index = 0; index < 33; index++) socket.send(JSON.stringify({ id: String(index), type: "CREATE_ROOM" }));
      assert.equal(await closed, 1008);
    } finally { release(); await server.close(); }
  });

  it("does not execute queued resource work after the socket closes", async () => {
    let release!: () => void;
    const gate = new Promise<void>((resolve) => { release = resolve; });
    const factory = new FakeSfuFactory();
    factory.createRoomGate = gate;
    const { server, url } = await started(factory);
    const socket = await connect(url);
    try {
      socket.send(JSON.stringify({ id: "1", type: "CREATE_ROOM" }));
      socket.send(JSON.stringify({ id: "2", type: "CREATE_ROOM" }));
      const closed = new Promise<void>((resolve) => socket.once("close", () => resolve()));
      socket.close();
      await closed;
      release();
      await new Promise((resolve) => setTimeout(resolve, 20));
      assert.equal(factory.rooms.length, 1);
      assert.equal(factory.rooms[0]?.closed, true);
    } finally { release(); await server.close(); }
  });

  it("redacts validation details and separates latency probes from native heartbeat", async () => {
    const { server, url } = await started();
    const socket = await connect(url);
    try {
      let nativePing = false;
      socket.once("ping", () => { nativePing = true; });
      socket.send(JSON.stringify({ id: "bad", type: "JOIN_ROOM", data: { roomId: "not-canonical" } }));
      const failure = await nextMessage(socket);
      assert.deepEqual(failure, { id: "bad", type: "RESPONSE", ok: false, error: { code: "N-BAD_REQUEST", message: "Request envelope or payload is invalid" } });
      const latencyMessages = nextMessages(socket, 2);
      socket.send(JSON.stringify({ id: "latency", type: "PING", data: { timestamp: 42 } }));
      assert.deepEqual(await latencyMessages, [
        { type: "LATENCY_PONG", data: { timestamp: 42 } },
        { id: "latency", type: "RESPONSE", ok: true, data: { pong: true } }
      ]);
      await new Promise((resolve) => setTimeout(resolve, 30));
      assert.equal(nativePing, true);
    } finally { socket.close(); await server.close(); }
  });
});
