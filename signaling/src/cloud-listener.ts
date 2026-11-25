import { isIPv4 } from "node:net";
import { MediasoupSfuFactory } from "./mediasoup-sfu.js";
import { createPhoneRelayPolicies } from "./phone-relay.js";
import { SignalingServer, type ServerOptions } from "./server.js";
import { SignalingService } from "./service.js";

const privateIp = "10.77.0.1";
const loopback = "127.0.0.1";
const { CLOUD_PRIVATE_IPV4: privateAddress, CLOUD_PUBLIC_IPV4: publicAddress, CLOUD_INVITE_KEY: inviteKey } = process.env;
if (!privateAddress || !isIPv4(privateAddress) || !publicAddress || !isIPv4(publicAddress) ||
    !inviteKey || !/^[a-f0-9]{64}$/.test(inviteKey)) {
  throw new Error("Cloud listener requires EC2 interface addresses and a private invite key");
}

const origin = `https://${publicAddress}`;
const factory = await MediasoupSfuFactory.create({
  listenIp: privateIp, announcedAddress: privateIp, port: 44444,
  additionalEndpoint: { listenIp: privateAddress, announcedAddress: publicAddress, port: 44445 },
});
const service = new SignalingService(factory);
const policies = createPhoneRelayPolicies(
  inviteKey, origin, roomId => service.debugRoomList().some(room => room.roomId === roomId && room.hasProducer)
);
const options = (host: string, port: number, allowedOrigins: string[]): ServerOptions => ({
  host, port, allowedOrigins, unsafeAllowRemote: host !== loopback,
  heartbeatIntervalMs: 15_000, heartbeatTimeoutMs: 45_000,
});

// WireGuard and the host firewall restrict the Speaker endpoint to enrolled peers.
const speaker = new SignalingServer(service, options(privateIp, 8080, []), {
  authorizeUpgrade: () => () => undefined,
});
// The HTTPS proxy only forwards the player, asset and WebSocket paths.
const listener = new SignalingServer(service, options(loopback, 8081, [origin]), {
  ...policies.listener,
  ...(policies.host.handleHttp ? { handleHttp: policies.host.handleHttp } : {}),
});

let closing = false;
async function close() {
  if (closing) return;
  closing = true;
  await Promise.allSettled([speaker.close(false), listener.close(false)]);
  await service.close();
}
try {
  await speaker.listen();
  await listener.listen();
} catch (error) {
  await close();
  throw error;
}
process.once("SIGINT", () => { void close(); });
process.once("SIGTERM", () => { void close(); });
