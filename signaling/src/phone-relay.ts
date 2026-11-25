import { randomBytes, timingSafeEqual } from "node:crypto";
import { isIPv4 } from "node:net";
import { networkInterfaces } from "node:os";
import { MediasoupSfuFactory } from "./mediasoup-sfu.js";
import { ProtocolFailure } from "./protocol.js";
import { SignalingServer, type ConnectionPolicy, type ServerOptions } from "./server.js";
import { SignalingService } from "./service.js";
import { createListenerInvites } from "./listener-invites.js";

const inviteLifetimeMs = 10 * 60_000;
const roomPattern = /^[0-9A-Z]{6}$/;
const localhost = "127.0.0.1";

export async function startPhoneRelay(ip: string, hostKey: string, signalPort = 18766, mediaPort = 18767) {
  const addressIsAvailable = () => Object.entries(networkInterfaces()).some(([name, entries]) =>
    /^(wlan|ap|swlan|softap)[0-9]+$/.test(name) &&
    entries?.some(entry => entry.address === ip && entry.family === "IPv4" && !entry.internal));
  if (!isIPv4(ip) || !addressIsAvailable() || !/^[a-f0-9]{64}$/.test(hostKey)) {
    throw new Error("Invalid local relay address or host credentials");
  }

  const origin = `http://${ip}:${signalPort}`;
  const factory = await MediasoupSfuFactory.create({ listenIp: ip, announcedAddress: ip, port: mediaPort });
  const service = new SignalingService(factory);
  const options = (host: string, allowedOrigins: string[]): ServerOptions => ({
    host, port: signalPort, allowedOrigins, unsafeAllowRemote: host !== localhost,
    heartbeatIntervalMs: 15_000, heartbeatTimeoutMs: 45_000,
  });
  const policies = createPhoneRelayPolicies(
    hostKey, origin, roomId => service.listenerInviteScope(roomId)
  );
  const host = new SignalingServer(service, options(localhost, []), policies.host);
  const listener = new SignalingServer(service, options(ip, [origin]), policies.listener);
  try {
    await host.listen();
    await listener.listen();
  } catch (error) {
    await Promise.allSettled([host.close(false), listener.close(false)]);
    await service.close();
    throw error;
  }

  let stopped = false;
  const close = async () => {
    if (stopped) return;
    stopped = true;
    clearInterval(networkWatch);
    await Promise.allSettled([host.close(false), listener.close(false)]);
    await service.close();
  };
  const networkWatch = setInterval(() => { if (!addressIsAvailable()) void close(); }, 2_000);
  networkWatch.unref();
  return close;
}

export function createPhoneRelayPolicies(
  hostKey: string,
  origin: string,
  currentScope: (roomId: string) => string | undefined,
  invites = createListenerInvites(origin, randomBytes(32)),
) {
  const deny = () => { throw new ProtocolFailure("N-FORBIDDEN", "Room invite required"); };

  const host: ConnectionPolicy = {
    authorizeUpgrade(url) {
      return matches(url.searchParams.get("hostKey"), hostKey) ? () => undefined : undefined;
    },
    handleHttp(url, request, response) {
      if (url.pathname !== "/invite") return false;
      const roomId = url.searchParams.get("roomId") ?? "";
      const scope = roomPattern.test(roomId) ? currentScope(roomId) : undefined;
      if (request.method !== "GET" || !matches(url.searchParams.get("hostKey"), hostKey)) {
        response.writeHead(403).end();
      } else if (!scope) {
        response.writeHead(404).end();
      } else {
        const invite = invites.issue(roomId, scope, Date.now() + inviteLifetimeMs);
        response.writeHead(200, { "content-type": "text/plain", "cache-control": "no-store" }).end(invite.url);
      }
      return true;
    },
  };
  const listener: ConnectionPolicy = {
    authorizeUpgrade(url) {
      const invite = invites.verify(url.searchParams.get("invite"), currentScope);
      if (!invite) return undefined;
      return request => {
        if (request.type === "CREATE_ROOM" || request.type === "GET_LISTENER_INVITE" ||
          (request.type === "JOIN_ROOM" && (request.data.roomId !== invite.roomId ||
            Date.now() >= invite.expiresAt || currentScope(invite.roomId) !== invite.scope))) deny();
      };
    },
  };
  return { host, listener };
}

function matches(value: string | null, expected: string): boolean {
  return value !== null && /^[a-f0-9]+$/.test(value) && value.length === expected.length &&
    timingSafeEqual(Buffer.from(value), Buffer.from(expected));
}
