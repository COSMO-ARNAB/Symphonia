import assert from "node:assert/strict";
import { describe, it } from "node:test";
import { requestSchema } from "../src/protocol.js";

describe("request protocol", () => {
  it("accepts compatibility and mediasoup operation names", () => {
    const samples = [
      { id: "1", type: "CREATE_ROOM" },
      { id: "2", type: "LEAVE_ROOM" },
      { id: "3", type: "PING" },
      { id: "4", type: "PONG" },
      { id: "5", type: "GET_ROUTER_RTP_CAPABILITIES" },
      { id: "6", type: "RESTART_ICE", data: { transportId: "transport" } }
    ];
    for (const sample of samples) assert.equal(requestSchema.safeParse(sample).success, true);
  });

  it("rejects video production and malformed envelopes", () => {
    assert.equal(requestSchema.safeParse({ id: "1", type: "PRODUCE", data: { transportId: "t", kind: "video", rtpParameters: {} } }).success, false);
    assert.equal(requestSchema.safeParse({ type: "PING" }).success, false);
    assert.equal(requestSchema.safeParse({ id: "1", type: "UNKNOWN" }).success, false);
    assert.equal(requestSchema.safeParse({ id: "1", type: "JOIN_ROOM", data: { roomId: "a".repeat(42) } }).success, false);
    assert.equal(requestSchema.safeParse({ id: "1", type: "JOIN_ROOM", data: { roomId: `${"a".repeat(42)}=` } }).success, false);
    assert.equal(requestSchema.safeParse({ id: "1", type: "CONNECT_WEBRTC_TRANSPORT", data: { transportId: "t", dtlsParameters: { fingerprints: [], secret: "raw" } } }).success, false);
    assert.equal(requestSchema.safeParse({ id: "1", type: "CONSUME", data: { transportId: "t", producerId: "p", rtpCapabilities: { codecs: [{ mimeType: "video/VP8", clockRate: 90000 }] } } }).success, false);
  });

  it("accepts bounded audio-only mediasoup payloads", () => {
    assert.equal(requestSchema.safeParse({ id: "1", type: "CONNECT_WEBRTC_TRANSPORT", data: { transportId: "t", dtlsParameters: { role: "client", fingerprints: [{ algorithm: "sha-256", value: "AA:BB" }] } } }).success, true);
    assert.equal(requestSchema.safeParse({ id: "2", type: "PRODUCE", data: { transportId: "t", kind: "audio", rtpParameters: { codecs: [{ mimeType: "audio/opus", payloadType: 111, clockRate: 48000, channels: 2 }], encodings: [{ ssrc: 1234 }] } } }).success, true);
    assert.equal(requestSchema.safeParse({ id: "3", type: "CONSUME", data: { transportId: "t", producerId: "p", rtpCapabilities: { codecs: [{ mimeType: "audio/opus", preferredPayloadType: 111, clockRate: 48000, channels: 2 }] } } }).success, true);
    const libwebrtcOpus = { id: "4", type: "PRODUCE", data: { transportId: "t", kind: "audio", rtpParameters: {
      mid: "0", msid: "stream-id audio-track-id",
      codecs: [{ mimeType: "audio/opus", payloadType: 111, clockRate: 48000, channels: 2, parameters: { minptime: 10, maxptime: 60, useinbandfec: 1, usedtx: 1, stereo: 1, "sprop-stereo": 1, cbr: 0, maxaveragebitrate: 96000, maxplaybackrate: 48000, "sprop-maxcapturerate": 48000 }, rtcpFeedback: [{ type: "transport-cc" }] }],
      headerExtensions: [{ uri: "urn:ietf:params:rtp-hdrext:sdes:mid", id: 1, encrypt: false, parameters: {} }],
      encodings: [{ ssrc: 1234, rtx: { ssrc: 5678 }, dtx: true, active: true, maxBitrate: 96000 }],
      rtcp: { cname: "bounded-cname", reducedSize: true, mux: true }
    } } };
    assert.equal(requestSchema.safeParse(libwebrtcOpus).success, true);
    assert.equal(requestSchema.safeParse({ ...libwebrtcOpus, data: { ...libwebrtcOpus.data, rtpParameters: { ...libwebrtcOpus.data.rtpParameters, codecs: [{ ...libwebrtcOpus.data.rtpParameters.codecs[0], parameters: { maxaveragebitrate: 999999 } }] } } }).success, false);
  });
});
