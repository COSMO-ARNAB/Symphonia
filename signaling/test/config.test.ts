import assert from "node:assert/strict";
import { describe, it } from "node:test";
import { parseConfig } from "../src/config.js";

describe("configuration safety", () => {
  it("defaults to loopback and rejects accidental remote binding", () => {
    assert.deepEqual([parseConfig({}).HOST, parseConfig({}).WEBRTC_LISTEN_IP], ["127.0.0.1", "127.0.0.1"]);
    assert.throws(() => parseConfig({ HOST: "0.0.0.0" }), /loopback/);
    assert.throws(() => parseConfig({ HOST: "0.0.0.0", UNSAFE_ALLOW_REMOTE: "true" }), /ANNOUNCED_ADDRESS is required/);
    assert.throws(() => parseConfig({ HOST: "0.0.0.0", UNSAFE_ALLOW_REMOTE: "true", ANNOUNCED_ADDRESS: "0.0.0.0" }), /reachable/);
    assert.throws(() => parseConfig({ HOST: "0.0.0.0", UNSAFE_ALLOW_REMOTE: "true", ANNOUNCED_ADDRESS: "127.0.0.1" }), /reachable/);
    assert.throws(() => parseConfig({ HOST: "0.0.0.0", UNSAFE_ALLOW_REMOTE: "true", ANNOUNCED_ADDRESS: "192.168.1.10" }), /WEBRTC_LISTEN_IP/);
    assert.equal(parseConfig({ HOST: "0.0.0.0", WEBRTC_LISTEN_IP: "0.0.0.0", UNSAFE_ALLOW_REMOTE: "true", ANNOUNCED_ADDRESS: "192.168.1.10" }).ANNOUNCED_ADDRESS, "192.168.1.10");
    assert.throws(() => parseConfig({ WEBRTC_LISTEN_IP: "0.0.0.0" }), /WEBRTC_LISTEN_IP/);
  });

  it("parses only canonical browser origins", () => {
    assert.deepEqual(parseConfig({ ALLOWED_ORIGINS: "http://localhost:3000,https://example.test" }).allowedOrigins, ["http://localhost:3000", "https://example.test"]);
    assert.throws(() => parseConfig({ ALLOWED_ORIGINS: "https://example.test/path" }), /Invalid ALLOWED_ORIGINS/);
  });
});
