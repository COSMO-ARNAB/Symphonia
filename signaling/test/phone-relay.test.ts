import assert from "node:assert/strict";
import { test } from "node:test";
import { createPhoneRelayPolicies } from "../src/phone-relay.js";
import { ProtocolFailure } from "../src/protocol.js";
import { SignalingServer } from "../src/server.js";
import { SignalingService } from "../src/service.js";
import { FakeSfuFactory } from "./fake-sfu.js";

test("phone relay issues signed, expiring invites for ready rooms and blocks listener room creation", async context => {
  const roomId = "ABC123";
  const hostKey = "ab".repeat(32);
  const now = Date.now();
  context.mock.timers.enable({ apis: ["Date"], now });
  let ready = false;
  const policies = createPhoneRelayPolicies(hostKey, "http://192.0.2.1:18766", id => id === roomId && ready);
  const host = new SignalingServer(new SignalingService(new FakeSfuFactory()), {
    host: "127.0.0.1", port: 0, unsafeAllowRemote: false, allowedOrigins: [],
    heartbeatIntervalMs: 15_000, heartbeatTimeoutMs: 45_000,
  }, policies.host);
  await host.listen();
  try {
    const issueUrl = new URL(`http://127.0.0.1:${host.port}/invite?roomId=${roomId}`);
    assert.equal((await fetch(issueUrl)).status, 403);
    issueUrl.searchParams.set("hostKey", hostKey);
    assert.equal((await fetch(issueUrl)).status, 404);

    ready = true;
    const response = await fetch(issueUrl);
    assert.equal(response.status, 200);
    const invite = new URL(await response.text());
    assert.equal(invite.origin, "http://192.0.2.1:18766");
    assert.equal(invite.searchParams.get("roomId"), roomId);
    const token = new URLSearchParams(invite.hash.slice(1)).get("invite");
    assert.ok(token);

    const listenerUrl = new URL("/gate2", "http://192.0.2.1:18766");
    assert.equal(policies.listener.authorizeUpgrade(listenerUrl), undefined);
    listenerUrl.searchParams.set("invite", token + "0");
    assert.equal(policies.listener.authorizeUpgrade(listenerUrl), undefined);
    listenerUrl.searchParams.set("invite", token);
    const authorize = policies.listener.authorizeUpgrade(listenerUrl);
    assert.ok(authorize);
    const forbidden = (error: unknown) => error instanceof ProtocolFailure && error.code === "N-FORBIDDEN";
    assert.doesNotThrow(() => authorize({ id: "join", type: "JOIN_ROOM", data: { roomId } }));
    assert.throws(() => authorize({ id: "other", type: "JOIN_ROOM", data: { roomId: "XYZ789" } }), forbidden);
    assert.throws(() => authorize({ id: "create", type: "CREATE_ROOM" }), forbidden);

    ready = false;
    assert.equal(policies.listener.authorizeUpgrade(listenerUrl), undefined);
    ready = true;
    context.mock.timers.tick(600_000);
    assert.equal(policies.listener.authorizeUpgrade(listenerUrl), undefined);
    assert.throws(() => authorize({ id: "late", type: "JOIN_ROOM", data: { roomId } }), forbidden);
  } finally {
    await host.close();
  }
});
