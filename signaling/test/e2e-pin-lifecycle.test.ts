import assert from "node:assert/strict";
import { describe, it } from "node:test";
import WebSocket from "ws";
import type { ServerMessage } from "../src/protocol.js";
import { SignalingServer } from "../src/server.js";
import { SignalingService } from "../src/service.js";
import { FakeSfuFactory } from "./fake-sfu.js";

const serverOptions = {
  host: "127.0.0.1",
  port: 0,
  unsafeAllowRemote: false,
  allowedOrigins: ["http://localhost:3000"],
  heartbeatIntervalMs: 50,
  heartbeatTimeoutMs: 200,
};

async function createServer() {
  const factory = new FakeSfuFactory();
  const service = new SignalingService(factory);
  const server = new SignalingServer(service, serverOptions);
  await server.listen();
  return { server, service, factory, url: `ws://127.0.0.1:${server.port}/gate2` };
}

function connect(url: string): Promise<WebSocket> {
  return new Promise((resolve, reject) => {
    const socket = new WebSocket(url);
    socket.once("open", () => resolve(socket));
    socket.once("error", reject);
  });
}

function sendRequest(socket: WebSocket, id: string, type: string, data?: unknown): Promise<any> {
  return new Promise((resolve, reject) => {
    const handler = (rawData: WebSocket.RawData) => {
      const msg = JSON.parse(rawData.toString());
      if (msg.type === "RESPONSE" && msg.id === id) {
        socket.off("message", handler);
        resolve(msg);
      }
    };
    socket.on("message", handler);
    socket.send(JSON.stringify({ id, type, ...(data ? { data } : {}) }));
  });
}

describe("E2E 6-Character Alphanumeric PIN Lifecycle", () => {
  it("creates valid 6-character uppercase alphanumeric PIN and permits valid listener join", async () => {
    const { server, url } = await createServer();
    const hostSocket = await connect(url);
    const listenerSocket = await connect(url);

    try {
      // 1. Host creates room
      const createRes = await sendRequest(hostSocket, "c1", "CREATE_ROOM", { displayName: "HostUser" });
      assert.equal(createRes.ok, true);
      const roomId = createRes.data.roomId;
      assert.equal(typeof roomId, "string");
      assert.equal(roomId.length, 6, "Room ID must be exactly 6 characters");
      assert.match(roomId, /^[0-9A-Z]{6}$/, "Room ID must be uppercase alphanumeric [0-9A-Z]");

      // 2. Listener joins non-existent valid 6-char PIN
      const notFoundRes = await sendRequest(listenerSocket, "j1", "JOIN_ROOM", { roomId: "999ZZZ" });
      assert.equal(notFoundRes.ok, false);
      assert.equal(notFoundRes.error.code, "N-ROOM_NOT_FOUND");

      // 3. Listener joins with invalid formats (should fail with N-BAD_REQUEST)
      const invalidPins = [
        "12345",         // length 5
        "1234567",       // length 7
        "abcdef",       // lowercase
        "ABC-12",       // hyphen
        "ABC 12",       // space
        "AB!@#$",       // symbols
      ];

      for (let i = 0; i < invalidPins.length; i++) {
        const pin = invalidPins[i];
        const badRes = await sendRequest(listenerSocket, `bad-${i}`, "JOIN_ROOM", { roomId: pin });
        assert.equal(badRes.ok, false, `Expected rejection for pin '${pin}'`);
        assert.equal(badRes.error.code, "N-BAD_REQUEST");
      }

      // 4. Listener joins with actual generated 6-char PIN
      const joinRes = await sendRequest(listenerSocket, "j2", "JOIN_ROOM", { roomId, displayName: "ValidListener" });
      assert.equal(joinRes.ok, true);
      assert.equal(joinRes.data.roomId, roomId);
      assert.equal(joinRes.data.role, "LISTENER");

      // 5. Host disconnects -> room closes
      const roomClosedPromise = new Promise<any>((resolve) => {
        listenerSocket.on("message", (raw) => {
          const msg = JSON.parse(raw.toString());
          if (msg.type === "ROOM_CLOSED") resolve(msg);
        });
      });

      hostSocket.close();
      const closedEvent = await roomClosedPromise;
      assert.equal(closedEvent.data.roomId, roomId);
      assert.equal(closedEvent.data.reason, "host_ended");
    } finally {
      hostSocket.close();
      listenerSocket.close();
      await server.close();
    }
  });

  it("rapidly generates distinct 6-character PINs with zero collisions", async () => {
    const { server, url } = await createServer();
    const sockets: WebSocket[] = [];
    const generatedPins = new Set<string>();

    try {
      // Create 10 rooms concurrently
      for (let i = 0; i < 10; i++) {
        const ws = await connect(url);
        sockets.push(ws);
      }

      const results = await Promise.all(
        sockets.map((ws, index) => sendRequest(ws, `req-${index}`, "CREATE_ROOM"))
      );

      for (const res of results) {
        assert.equal(res.ok, true);
        const pin = res.data.roomId;
        assert.match(pin, /^[0-9A-Z]{6}$/);
        assert.equal(generatedPins.has(pin), false, `Collision detected for PIN: ${pin}`);
        generatedPins.add(pin);
      }

      assert.equal(generatedPins.size, 10);
    } finally {
      for (const s of sockets) s.close();
      await server.close();
    }
  });
});
