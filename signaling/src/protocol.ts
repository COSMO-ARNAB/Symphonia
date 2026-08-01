import { z } from "zod";

const identifier = z.string().min(1).max(128).regex(/^[A-Za-z0-9_.:-]+$/);
export const roomIdSchema = z.string().length(43).regex(/^[A-Za-z0-9_-]{43}$/);
const requestBase = z.object({ id: identifier }).strict();
const fingerprintSchema = z.object({
  algorithm: z.enum(["sha-1", "sha-224", "sha-256", "sha-384", "sha-512"]),
  value: z.string().min(1).max(256).regex(/^[A-Fa-f0-9:]+$/)
}).strict();
const dtlsParametersSchema = z.object({
  role: z.enum(["auto", "client", "server"]).optional(),
  fingerprints: z.array(fingerprintSchema).min(1).max(8)
}).strict();
const rtcpFeedbackSchema = z.object({ type: z.string().min(1).max(32), parameter: z.string().max(64).optional() }).strict();
const codecParametersSchema = z.object({
  minptime: z.number().int().min(0).max(1_000).optional(),
  maxptime: z.number().int().min(0).max(1_000).optional(),
  useinbandfec: z.union([z.literal(0), z.literal(1)]).optional(),
  usedtx: z.union([z.literal(0), z.literal(1)]).optional(),
  cbr: z.union([z.literal(0), z.literal(1)]).optional(),
  stereo: z.union([z.literal(0), z.literal(1)]).optional(),
  "sprop-stereo": z.union([z.literal(0), z.literal(1)]).optional(),
  maxaveragebitrate: z.number().int().min(6_000).max(510_000).optional(),
  maxplaybackrate: z.number().int().min(8_000).max(48_000).optional(),
  "sprop-maxcapturerate": z.number().int().min(8_000).max(48_000).optional()
}).strict();
const rtpCodecSchema = z.object({
  mimeType: z.string().toLowerCase().pipe(z.literal("audio/opus")),
  payloadType: z.number().int().min(0).max(127).optional(),
  preferredPayloadType: z.number().int().min(0).max(127).optional(),
  clockRate: z.literal(48_000),
  channels: z.union([z.literal(1), z.literal(2)]).optional(),
  parameters: codecParametersSchema.optional(),
  rtcpFeedback: z.array(rtcpFeedbackSchema).max(16).optional()
}).strict();
const headerExtensionSchema = z.object({
  kind: z.literal("audio").optional(),
  uri: z.string().url().max(256),
  preferredId: z.number().int().min(1).max(255).optional(),
  preferredEncrypt: z.boolean().optional(),
  direction: z.enum(["sendrecv", "sendonly", "recvonly", "inactive"]).optional(),
  id: z.number().int().min(1).max(255).optional(),
  encrypt: z.boolean().optional(),
  parameters: z.object({}).strict().optional()
}).strict();
const rtpCapabilitiesSchema = z.object({
  codecs: z.array(rtpCodecSchema).min(1).max(16),
  headerExtensions: z.array(headerExtensionSchema).max(32).optional()
}).strict();
const rtpParametersSchema = z.object({
  mid: z.string().min(1).max(32).optional(),
  msid: z.string().min(1).max(256).optional(),
  codecs: z.array(rtpCodecSchema.extend({ payloadType: z.number().int().min(0).max(127) }).omit({ preferredPayloadType: true })).min(1).max(8),
  headerExtensions: z.array(headerExtensionSchema.pick({ uri: true, id: true, encrypt: true, parameters: true }).extend({ id: z.number().int().min(1).max(255) })).max(32).optional(),
  encodings: z.array(z.object({
    ssrc: z.number().int().min(1).max(0xffffffff).optional(),
    rid: z.string().min(1).max(16).optional(),
    codecPayloadType: z.number().int().min(0).max(127).optional(),
    dtx: z.boolean().optional(),
    scalabilityMode: z.string().min(1).max(32).optional(),
    maxBitrate: z.number().int().positive().max(512_000).optional(),
    active: z.boolean().optional(),
    rtx: z.object({ ssrc: z.number().int().min(1).max(0xffffffff) }).strict().optional()
  }).strict()).min(1).max(4).optional(),
  rtcp: z.object({ cname: z.string().min(1).max(256).optional(), reducedSize: z.boolean().optional(), mux: z.boolean().optional() }).strict().optional()
}).strict();

export const requestSchema = z.discriminatedUnion("type", [
  requestBase.extend({ type: z.literal("CREATE_ROOM"), data: z.object({ displayName: z.string().min(1).max(80).optional() }).strict().optional() }).strict(),
  requestBase.extend({ type: z.literal("JOIN_ROOM"), data: z.object({ roomId: roomIdSchema, displayName: z.string().min(1).max(80).optional() }).strict() }).strict(),
  requestBase.extend({ type: z.literal("LEAVE_ROOM"), data: z.object({}).strict().optional() }).strict(),
  requestBase.extend({ type: z.literal("PING"), data: z.object({ timestamp: z.number().finite().optional() }).strict().optional() }).strict(),
  requestBase.extend({ type: z.literal("PONG"), data: z.object({ timestamp: z.number().finite().optional() }).strict().optional() }).strict(),
  requestBase.extend({ type: z.literal("STATE_UPDATE"), data: z.object({ state: z.enum(["LIVE", "PAUSED", "RECONNECTING", "STOPPED"]), appLabel: z.string().min(1).max(120).nullable().optional(), reason: z.enum(["app_ended", "permission_required", "host_ended"]).nullable().optional() }).strict() }).strict(),
  requestBase.extend({ type: z.literal("GET_ROUTER_RTP_CAPABILITIES"), data: z.object({}).strict().optional() }).strict(),
  requestBase.extend({ type: z.literal("CREATE_WEBRTC_TRANSPORT"), data: z.object({ direction: z.enum(["send", "recv"]) }).strict() }).strict(),
  requestBase.extend({ type: z.literal("CONNECT_WEBRTC_TRANSPORT"), data: z.object({ transportId: identifier, dtlsParameters: dtlsParametersSchema }).strict() }).strict(),
  requestBase.extend({ type: z.literal("PRODUCE"), data: z.object({ transportId: identifier, kind: z.literal("audio"), rtpParameters: rtpParametersSchema, appData: z.object({ appLabel: z.string().min(1).max(120).optional() }).strict().optional() }).strict() }).strict(),
  requestBase.extend({ type: z.literal("CONSUME"), data: z.object({ transportId: identifier, producerId: identifier, rtpCapabilities: rtpCapabilitiesSchema }).strict() }).strict(),
  requestBase.extend({ type: z.literal("RESUME_CONSUMER"), data: z.object({ consumerId: identifier }).strict() }).strict(),
  requestBase.extend({ type: z.literal("RESTART_ICE"), data: z.object({ transportId: identifier }).strict() }).strict()
]);

export type ClientRequest = z.infer<typeof requestSchema>;

export interface ProtocolErrorBody {
  code: FailureCode;
  message: string;
  details?: unknown;
}

export type FailureCode =
  | "N-BAD_REQUEST"
  | "N-NOT_IN_ROOM"
  | "N-ALREADY_IN_ROOM"
  | "N-ROOM_NOT_FOUND"
  | "N-ROOM_FULL"
  | "N-FORBIDDEN"
  | "N-CONFLICT"
  | "N-NOT_FOUND"
  | "N-CANNOT_CONSUME"
  | "N-SFU_FAILURE"
  | "N-INTERNAL";

export type ServerMessage =
  | { id: string; type: "RESPONSE"; ok: true; data: unknown }
  | { id: string | null; type: "RESPONSE"; ok: false; error: ProtocolErrorBody }
  | { type: "PARTICIPANT_CONNECTED" | "PARTICIPANT_DISCONNECTED" | "PRODUCER_AVAILABLE" | "PRODUCER_CLOSED" | "TRANSPORT_CLOSED" | "CONSUMER_CLOSED" | "ROOM_CLOSED" | "STATE_UPDATE" | "LATENCY_PONG"; data: unknown };

export class ProtocolFailure extends Error {
  constructor(public readonly code: FailureCode, message: string, public readonly details?: unknown) {
    super(message);
  }
}
