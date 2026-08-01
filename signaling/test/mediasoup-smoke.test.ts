import assert from "node:assert/strict";
import { it } from "node:test";
import { MediasoupSfuFactory } from "../src/mediasoup-sfu.js";

it("native default transport never advertises a wildcard candidate", async () => {
  const port = 45_000 + Math.floor(Math.random() * 10_000);
  const factory = await MediasoupSfuFactory.create({ listenIp: "127.0.0.1", port });
  try {
    const room = await factory.createRoom("smoke");
    const transport = await room.createTransport("participant", "send");
    const candidates = transport.iceCandidates as Array<{ ip?: string; address?: string }>;
    assert.ok(candidates.length > 0);
    assert.equal(candidates.some((candidate) => candidate.ip === "0.0.0.0" || candidate.address === "0.0.0.0"), false);
    room.close();
  } finally {
    await factory.close();
  }
});
