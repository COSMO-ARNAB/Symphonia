import { parseConfig } from "./config.js";
import { MediasoupSfuFactory } from "./mediasoup-sfu.js";
import { SignalingServer } from "./server.js";
import { SignalingService } from "./service.js";

const env = parseConfig(process.env);
const factory = await MediasoupSfuFactory.create({ listenIp: env.WEBRTC_LISTEN_IP, ...(env.ANNOUNCED_ADDRESS === undefined ? {} : { announcedAddress: env.ANNOUNCED_ADDRESS }), port: env.WEBRTC_PORT });
const server = new SignalingServer(new SignalingService(factory), { host: env.HOST, port: env.PORT, unsafeAllowRemote: env.UNSAFE_ALLOW_REMOTE, allowedOrigins: env.allowedOrigins, heartbeatIntervalMs: env.HEARTBEAT_INTERVAL_MS, heartbeatTimeoutMs: env.HEARTBEAT_TIMEOUT_MS });
await server.listen();
console.log(JSON.stringify({ level: "info", event: "server_started", host: env.HOST, port: env.PORT, localPrototypeOnly: true }));

let closing = false;
const shutdown = async (signal: string) => {
  if (closing) return;
  closing = true;
  console.log(JSON.stringify({ level: "info", event: "server_stopping", signal }));
  await server.close();
};
process.on("SIGINT", () => { void shutdown("SIGINT"); });
process.on("SIGTERM", () => { void shutdown("SIGTERM"); });
