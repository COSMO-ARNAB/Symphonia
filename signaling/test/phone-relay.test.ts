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
  const policies = createPhoneRelayPolicies(hostKey, "http://192.0.2.1:18766", id => id === roomId && ready ? "producer-1" : undefined);
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
    assert.throws(() => authorize({ id: "invite", type: "GET_LISTENER_INVITE" }), forbidden);

    ready = false;
    assert.equal(policies.listener.authorizeUpgrade(listenerUrl), undefined);
    assert.throws(() => authorize({ id: "stopped", type: "JOIN_ROOM", data: { roomId } }), forbidden);
    ready = true;
    context.mock.timers.tick(600_000);
    assert.equal(policies.listener.authorizeUpgrade(listenerUrl), undefined);
    assert.throws(() => authorize({ id: "late", type: "JOIN_ROOM", data: { roomId } }), forbidden);
  } finally {
    await host.close();
  }
});

test("cloud listener invites are stable within a share, bound to active producer, and expire at 24 hours", async context => {
  const { createListenerInvites } = await import("../src/listener-invites.js");
  const now = Date.now();
  context.mock.timers.enable({ apis: ["Date"], now });

  const origin = "https://203.0.113.10";
  const key = Buffer.from("cd".repeat(32), "hex");
  const invites = createListenerInvites(origin, key);
  const factory = new FakeSfuFactory();
  const service = new SignalingService(factory, undefined, invites.issue);
  const policies = createPhoneRelayPolicies("ef".repeat(32), origin, id => service.listenerInviteScope(id), invites);

  const host = { connectionId: "speaker-1", send() {} };
  const other = { connectionId: "other-1", send() {} };
  const { roomId } = await service.handle(host, { id: "1", type: "CREATE_ROOM" }) as { roomId: string };
  await service.handle(other, { id: "2", type: "JOIN_ROOM", data: { roomId } });

  const forbidden = (error: unknown) => error instanceof ProtocolFailure && error.code === "N-FORBIDDEN";
  await assert.rejects(() => service.handle(host, { id: "3", type: "GET_LISTENER_INVITE" }), forbidden);

  const rtpParameters = { codecs: [{ mimeType: "audio/opus" as const, payloadType: 111, clockRate: 48_000 as const }] };
  const send = await service.handle(host, { id: "4", type: "CREATE_WEBRTC_TRANSPORT", data: { direction: "send" } }) as { id: string };
  const producer = await service.handle(host, { id: "5", type: "PRODUCE", data: { transportId: send.id, kind: "audio", rtpParameters } }) as { id: string };
  await service.handle(host, { id: "6", type: "STATE_UPDATE", data: { state: "LIVE", appLabel: "Music" } });

  await assert.rejects(() => service.handle(other, { id: "7", type: "GET_LISTENER_INVITE" }), forbidden);

  const first = await service.handle(host, { id: "8", type: "GET_LISTENER_INVITE" }) as { url: string; expiresAt: number };
  assert.equal(first.expiresAt, now + 24 * 60 * 60_000);
  const parsed = new URL(first.url);
  assert.equal(parsed.origin, origin);
  assert.equal(parsed.searchParams.get("roomId"), roomId);
  const token = new URLSearchParams(parsed.hash.slice(1)).get("invite");
  assert.ok(token);

  context.mock.timers.tick(60_000);
  const second = await service.handle(host, { id: "9", type: "GET_LISTENER_INVITE" }) as { url: string; expiresAt: number };
  assert.deepEqual(second, first);

  const upgradeUrl = new URL(`/gate2?invite=${encodeURIComponent(token)}`, origin);
  const authorize = policies.listener.authorizeUpgrade(upgradeUrl);
  assert.ok(authorize);
  assert.doesNotThrow(() => authorize({ id: "j1", type: "JOIN_ROOM", data: { roomId } }));

  // Producer replacement invalidates previous invite
  factory.rooms[0]!.emitProducerClosed(producer.id);
  assert.equal(policies.listener.authorizeUpgrade(upgradeUrl), undefined);
  assert.throws(() => authorize({ id: "j2", type: "JOIN_ROOM", data: { roomId } }), forbidden);

  await service.handle(host, { id: "10", type: "PRODUCE", data: { transportId: send.id, kind: "audio", rtpParameters } });
  await service.handle(host, { id: "11", type: "STATE_UPDATE", data: { state: "LIVE", appLabel: "Music" } });
  assert.equal(policies.listener.authorizeUpgrade(upgradeUrl), undefined);
  const replacementInvite = await service.handle(host, { id: "12", type: "GET_LISTENER_INVITE" }) as { url: string; expiresAt: number };
  assert.notEqual(replacementInvite.url, first.url);
  const replacementToken = new URLSearchParams(new URL(replacementInvite.url).hash.slice(1)).get("invite")!;
  const replacementUrl = new URL(`/gate2?invite=${encodeURIComponent(replacementToken)}`, origin);
  const replacementAuth = policies.listener.authorizeUpgrade(replacementUrl);
  assert.ok(replacementAuth);

  // Stop Sharing invalidates immediately
  await service.handle(host, { id: "13", type: "STATE_UPDATE", data: { state: "STOPPED", reason: "host_ended" } });
  assert.equal(policies.listener.authorizeUpgrade(replacementUrl), undefined);
  assert.throws(() => replacementAuth({ id: "j3", type: "JOIN_ROOM", data: { roomId } }), forbidden);

  // Resume + expiry after 24h from producer start rejects GET_LISTENER_INVITE and upgrades
  await service.handle(host, { id: "14", type: "STATE_UPDATE", data: { state: "LIVE", appLabel: "Music" } });
  const freshAfterResume = await service.handle(host, { id: "15", type: "GET_LISTENER_INVITE" }) as { url: string; expiresAt: number };
  const freshToken = new URLSearchParams(new URL(freshAfterResume.url).hash.slice(1)).get("invite")!;
  const freshUrl = new URL(`/gate2?invite=${encodeURIComponent(freshToken)}`, origin);
  const freshAuth = policies.listener.authorizeUpgrade(freshUrl);
  assert.ok(freshAuth);
  context.mock.timers.tick(24 * 60 * 60_000);
  await assert.rejects(() => service.handle(host, { id: "16", type: "GET_LISTENER_INVITE" }), forbidden);
  assert.equal(policies.listener.authorizeUpgrade(freshUrl), undefined);
  assert.throws(() => freshAuth({ id: "j4", type: "JOIN_ROOM", data: { roomId } }), forbidden);

  await service.close();
});
