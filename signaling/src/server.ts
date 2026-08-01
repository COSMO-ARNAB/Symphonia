import { createServer, type Server as HttpServer } from "node:http";
import type { AddressInfo } from "node:net";
import { WebSocket, WebSocketServer } from "ws";
import { ZodError } from "zod";
import { ProtocolFailure, requestSchema, type ServerMessage } from "./protocol.js";
import { SignalingService, type ClientPeer } from "./service.js";
import { assertSafeHost } from "./config.js";

interface LiveSocket extends WebSocket { lastPongAt: number; pendingRequests: number; requestQueue: Promise<void> }
export interface ServerOptions { host: string; port: number; unsafeAllowRemote: boolean; allowedOrigins: string[]; heartbeatIntervalMs: number; heartbeatTimeoutMs: number }

const MAX_CONNECTIONS = 64;
const MAX_PENDING_REQUESTS = 32;
const MAX_PAYLOAD_BYTES = 64 * 1024;
const MAX_BUFFERED_BYTES = 256 * 1024;

export class SignalingServer {
  private readonly http: HttpServer;
  private readonly wss: WebSocketServer;
  private heartbeat?: NodeJS.Timeout;

  constructor(private readonly service: SignalingService, private readonly options: ServerOptions) {
    assertSafeHost(options.host, options.unsafeAllowRemote);
    this.http = createServer((request, response) => {
      if (request.url === "/healthz") { response.writeHead(200, { "content-type": "application/json" }); response.end('{"status":"ok"}'); return; }
      response.writeHead(404).end();
    });
    this.wss = new WebSocketServer({ noServer: true, maxPayload: MAX_PAYLOAD_BYTES, perMessageDeflate: false });
    this.http.on("upgrade", (request, socket, head) => {
      const origin = request.headers.origin;
      if (request.url !== "/gate2" || (origin !== undefined && !this.options.allowedOrigins.includes(origin)) || this.wss.clients.size >= MAX_CONNECTIONS) {
        socket.write("HTTP/1.1 403 Forbidden\r\nConnection: close\r\n\r\n");
        socket.destroy();
        return;
      }
      this.wss.handleUpgrade(request, socket, head, (client) => this.wss.emit("connection", client, request));
    });
    this.wss.on("connection", (socket: LiveSocket) => this.onConnection(socket));
  }

  async listen(): Promise<void> {
    await new Promise<void>((resolve, reject) => { this.http.once("error", reject); this.http.listen(this.options.port, this.options.host, resolve); });
    this.heartbeat = setInterval(() => this.checkHeartbeats(), this.options.heartbeatIntervalMs);
    this.heartbeat.unref();
  }

  async close(): Promise<void> {
    if (this.heartbeat) clearInterval(this.heartbeat);
    for (const client of this.wss.clients) client.terminate();
    await new Promise<void>((resolve) => this.wss.close(() => resolve()));
    await new Promise<void>((resolve) => this.http.close(() => resolve()));
    await this.service.close();
  }

  get port(): number { return (this.http.address() as AddressInfo | null)?.port ?? this.options.port; }

  private onConnection(socket: LiveSocket): void {
    socket.lastPongAt = Date.now();
    socket.pendingRequests = 0;
    socket.requestQueue = Promise.resolve();
    const peer: ClientPeer = {
      connectionId: crypto.randomUUID(),
      send: (message) => this.send(socket, message)
    };
    socket.on("pong", () => { socket.lastPongAt = Date.now(); });
    socket.on("message", (data, isBinary) => {
      if (++socket.pendingRequests > MAX_PENDING_REQUESTS) { socket.close(1008, "request queue limit exceeded"); return; }
      const text = isBinary ? null : data.toString();
      socket.requestQueue = socket.requestQueue.then(() => this.onMessage(socket, peer, text)).catch((error: unknown) => {
        console.error(JSON.stringify({ level: "error", event: "request_queue_failure", message: error instanceof Error ? error.message : "unknown" }));
      }).finally(() => { socket.pendingRequests--; });
    });
    socket.on("close", () => this.service.disconnect(peer));
    socket.on("error", (error) => console.warn(JSON.stringify({ level: "warn", event: "websocket_error", message: error.message })));
  }

  private async onMessage(socket: LiveSocket, peer: ClientPeer, text: string | null): Promise<void> {
    let raw: unknown;
    try { raw = text === null ? null : JSON.parse(text); } catch { this.failure(socket, null, new ProtocolFailure("N-BAD_REQUEST", "Message must be valid JSON text")); return; }
    const requestId = typeof raw === "object" && raw !== null && "id" in raw && typeof raw.id === "string" ? raw.id : null;
    try {
      const request = requestSchema.parse(raw);
      const data = await this.service.handle(peer, request);
      this.send(socket, { id: request.id, type: "RESPONSE", ok: true, data });
    } catch (error) {
      if (error instanceof ZodError) this.failure(socket, requestId, new ProtocolFailure("N-BAD_REQUEST", "Request envelope or payload is invalid"));
      else if (error instanceof ProtocolFailure) this.failure(socket, requestId, error);
      else { console.error(JSON.stringify({ level: "error", event: "request_failure", message: error instanceof Error ? error.message : "unknown" })); this.failure(socket, requestId, new ProtocolFailure("N-INTERNAL", "Unexpected server error")); }
    }
  }

  private failure(socket: WebSocket, id: string | null, error: ProtocolFailure) { this.send(socket, { id, type: "RESPONSE", ok: false, error: { code: error.code, message: error.message } }); }
  private send(socket: WebSocket, message: ServerMessage) {
    if (socket.readyState !== WebSocket.OPEN) return;
    const payload = JSON.stringify(message);
    if (socket.bufferedAmount + Buffer.byteLength(payload) > MAX_BUFFERED_BYTES) { socket.close(1008, "outbound buffer limit exceeded"); return; }
    socket.send(payload);
  }
  private checkHeartbeats() { const now = Date.now(); for (const socket of this.wss.clients as Set<LiveSocket>) { if (now - socket.lastPongAt > this.options.heartbeatTimeoutMs) socket.terminate(); else socket.ping(); } }
}
