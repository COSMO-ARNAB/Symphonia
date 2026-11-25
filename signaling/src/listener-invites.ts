import { createHmac, timingSafeEqual } from "node:crypto";

export interface ListenerInvite { url: string; expiresAt: number }

export function createListenerInvites(origin: string, key: Uint8Array) {
  const signature = (body: string, scope: string) =>
    createHmac("sha256", key).update(body + "." + scope).digest("hex");
  return {
    issue(roomId: string, scope: string, expiresAt: number): ListenerInvite {
      const body = `${roomId}.${expiresAt}`;
      const url = new URL("/listener.html", origin);
      url.searchParams.set("roomId", roomId);
      url.hash = new URLSearchParams({ invite: `${body}.${signature(body, scope)}` }).toString();
      return { url: url.href, expiresAt };
    },
    verify(token: string | null, currentScope: (roomId: string) => string | undefined) {
      const [roomId = "", expires = "", mac = "", ...extra] = (token ?? "").split(".");
      if (extra.length || !/^[0-9A-Z]{6}$/.test(roomId) ||
          !/^\d{13}$/.test(expires) || Date.now() >= Number(expires) || !/^[a-f0-9]{64}$/.test(mac)) return undefined;
      const scope = currentScope(roomId);
      if (!scope || !timingSafeEqual(Buffer.from(mac), Buffer.from(signature(`${roomId}.${expires}`, scope)))) return undefined;
      return { roomId, scope, expiresAt: Number(expires) };
    },
  };
}
