import { isIP } from "node:net";
import { z } from "zod";

const envSchema = z.object({
  HOST: z.string().default("127.0.0.1"),
  PORT: z.coerce.number().int().min(1).max(65535).default(8080),
  UNSAFE_ALLOW_REMOTE: z.stringbool().default(false),
  ALLOWED_ORIGINS: z.string().default(""),
  ANNOUNCED_ADDRESS: z.string().min(1).optional(),
  WEBRTC_LISTEN_IP: z.string().default("127.0.0.1"),
  WEBRTC_PORT: z.coerce.number().int().min(1).max(65535).default(44444),
  HEARTBEAT_INTERVAL_MS: z.coerce.number().int().positive().max(60_000).default(15_000),
  HEARTBEAT_TIMEOUT_MS: z.coerce.number().int().positive().max(180_000).default(45_000)
});

export function parseConfig(environment: NodeJS.ProcessEnv) {
  const env = envSchema.parse(environment);
  if (env.HEARTBEAT_TIMEOUT_MS <= env.HEARTBEAT_INTERVAL_MS) throw new Error("HEARTBEAT_TIMEOUT_MS must exceed HEARTBEAT_INTERVAL_MS");
  assertSafeHost(env.HOST, env.UNSAFE_ALLOW_REMOTE);
  assertSafeMediaConfig(env.WEBRTC_LISTEN_IP, env.ANNOUNCED_ADDRESS, env.UNSAFE_ALLOW_REMOTE);
  const allowedOrigins = env.ALLOWED_ORIGINS === "" ? [] : env.ALLOWED_ORIGINS.split(",").map((origin) => {
    const value = origin.trim();
    const parsed = new URL(value);
    if (parsed.origin !== value || !["http:", "https:"].includes(parsed.protocol)) throw new Error(`Invalid ALLOWED_ORIGINS entry: ${value}`);
    return value;
  });
  return { ...env, allowedOrigins };
}

export function assertSafeMediaConfig(listenIp: string, announcedAddress: string | undefined, unsafeAllowRemote: boolean): void {
  if (!unsafeAllowRemote) {
    if (!isLoopback(listenIp)) throw new Error("WEBRTC_LISTEN_IP must be loopback unless UNSAFE_ALLOW_REMOTE=true");
    if (announcedAddress !== undefined && !isLoopback(announcedAddress)) throw new Error("ANNOUNCED_ADDRESS must be loopback in safe mode");
    return;
  }
  if (announcedAddress === undefined) throw new Error("ANNOUNCED_ADDRESS is required when UNSAFE_ALLOW_REMOTE=true");
  if (isWildcard(announcedAddress) || isLoopback(announcedAddress)) throw new Error("ANNOUNCED_ADDRESS must be reachable by remote clients, not wildcard or loopback");
  if (isLoopback(listenIp)) throw new Error("WEBRTC_LISTEN_IP must accept remote traffic when UNSAFE_ALLOW_REMOTE=true");
}

export function assertSafeHost(host: string, unsafeAllowRemote: boolean): void {
  if (!unsafeAllowRemote && !isLoopback(host)) throw new Error("HOST must be loopback unless UNSAFE_ALLOW_REMOTE=true");
}

function isLoopback(host: string): boolean {
  const normalized = host.toLowerCase();
  if (normalized === "localhost" || normalized === "localhost." || normalized.endsWith(".localhost") || normalized === "::1" || normalized === "0:0:0:0:0:0:0:1" || normalized.startsWith("::ffff:127.")) return true;
  return isIP(host) === 4 && host.startsWith("127.");
}

function isWildcard(host: string): boolean { return ["0.0.0.0", "::", "::0", "0:0:0:0:0:0:0:0"].includes(host.toLowerCase()); }
