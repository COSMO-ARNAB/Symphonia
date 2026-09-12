# Gate 2 Signaling Prototype

Minimal Node/TypeScript WebSocket signaling and mediasoup SFU prototype for one audio publisher and up to 30 listeners per room.

> **Local prototype only:** authentication and signed room tokens are intentionally not implemented for this Gate 2 exercise. Rooms use cryptographically random 256-bit, URL-safe IDs to avoid guessable identifiers, but that is not authentication. Do not expose this service to the public internet or treat it as satisfying the production room-token requirements.

## Guarantees and scope

- Audio-only mediasoup router: Opus, 48 kHz, stereo, in-band FEC.
- WebRTC media transport uses DTLS-SRTP as provided by mediasoup. Audio never traverses WebSocket signaling.
- One active Host/Speaker producer per room; Listeners cannot publish.
- One send transport per publisher and one receive transport and consumer per Listener.
- Maximum 16 rooms, 64 WebSocket connections, 30 Listeners per room, 31 transports per room, and 30 independently reserved SFU consumers per room.
- Per-socket requests are processed serially with a 32-request queue, 64 KiB inbound payload limit, and bounded outbound buffering.
- In-memory ephemeral state only. Host departure closes the room in this prototype.
- No authentication, persistence, reconnect grace period, TURN configuration, or horizontal scaling.
- Logs contain lifecycle/error metadata only. No RTP or audio payload is logged or persisted.

## Run

Requires Node.js 22 or newer and a host supported by mediasoup.

```bash
npm install
npm run lint
npm test
npm run build
npm start
```

Environment variables are documented in `.env.example`. Node does not load that file automatically. Set variables in the process environment before `npm start`.

The signaling server refuses a non-loopback `HOST` unless `UNSAFE_ALLOW_REMOTE=true`. This explicit opt-in exists only so a physical Android device on the same trusted LAN can reach the feasibility prototype. For LAN testing, set `HOST` to the development machine's LAN address (or `0.0.0.0`), set `UNSAFE_ALLOW_REMOTE=true`, and set `ANNOUNCED_ADDRESS` to that reachable LAN address. Restrict the host firewall to the trusted LAN and open both the signaling `PORT` and UDP/TCP `WEBRTC_PORT`. Disable the opt-in immediately after testing. Never port-forward or expose this no-auth prototype to the internet.

Plain `ws://` is generally usable only for native clients or secure-context exceptions such as loopback. Browser testing from another device normally requires a trusted TLS terminator and `wss://`; a self-signed certificate may not be accepted. WSS protects signaling in transit but does not add authentication, so it does not make public exposure safe. Browser clients must send an origin listed exactly in comma-separated `ALLOWED_ORIGINS`; native clients that omit `Origin` are allowed.

HTTP health check: `GET /healthz`. WebSocket upgrades are accepted only at `/gate2` on the same host and port.

## Spike rig (phone Speaker to laptop Listener, verified end to end)

The `android/gate2/spike-app` APK streams app audio; the laptop Chrome page (`public/listener.html`) plays it back. Verified on shared Wi-Fi with music playing on the phone, audible on both speakers.

### Prerequisites

- Phone and laptop on the same LAN. USB tethering and shared Wi-Fi both work, but tether subnets can shift between runs, so confirm both addresses every run.
- Server environment: `HOST=0.0.0.0`, `UNSAFE_ALLOW_REMOTE=true`, `ANNOUNCED_ADDRESS=<laptop LAN IP>`, `WEBRTC_LISTEN_IP=0.0.0.0`, and `ALLOWED_ORIGINS` listing both the LAN page origin and `http://127.0.0.1:8080` (Chrome reaches the page over loopback while the phone uses the LAN address).
- Host firewall: allow inbound TCP on `PORT` (signaling) and TCP plus UDP on `WEBRTC_PORT` (media) for the LAN profile.
- Spike APK installed on the phone. Grant `RECORD_AUDIO` (`adb shell pm grant com.symphonia.gate2.spikeapp android.permission.RECORD_AUDIO`): the PCM swap needs the record path to start even though microphone audio is never streamed; without the grant the session goes live but carries zero frames (fail closed). Grant the MediaProjection consent when sharing starts.

### Run

1. Start the server and note the laptop LAN IP.
2. Phone app: set `ws://<laptop-ip>:8080`, tap START SHARING, grant the capture consent, then COPY ROOM ID.
3. Laptop: open the listener page, paste the room ID, JOIN and LISTEN, then click the page once (Chrome autoplay policy requires a real user gesture; scripted clicks do not count).
4. Play audio on the phone. PASS: the page log shows climbing `bytesReceived` with an unmuted track, and music is audible on both speakers.

### Listener client notes

- `CONSUME` follows server-side semantics: send `{transportId, producerId, rtpCapabilities}`, then pass the returned `{id, producerId, kind, rtpParameters}` into `transport.consume()`. Calling `consume()` with `{producerId, rtpCapabilities}` fails with `missing id` on this mediasoup-client build.
- Caps must satisfy two validators at once: the strict zod schema (no `codec.kind`, audio-only header extensions) and mediasoup itself (header extensions REQUIRE `kind: 'audio'`; stripping it surfaces as `N-CANNOT_CONSUME` via `invalid ext.kind`).
- Codec `channels` must match the producer exactly (stereo producer needs stereo caps).

### Diagnostics (local rig only, never expose)

- `GET /debug/rooms`: live rooms with participant count and producer presence. Metadata only, no audio.

### Troubleshooting

- `N-CANNOT_CONSUME`: channels mismatch between producer and caps, or `kind` missing from header extensions.
- Phone transport stuck at `checking` then `failed`: UDP blocked to `WEBRTC_PORT`, or a stale `ANNOUNCED_ADDRESS` after the tether subnet shifted.
- `autoplay blocked` on the page: click the page once with a real pointer.
- Join fails with `N-ROOM_NOT_FOUND`: the host socket dropped (host departure closes the room), so re-share from the phone for a fresh room.

## Protocol

Requests use a correlated envelope:

```json
{"id":"req-1","type":"CREATE_ROOM","data":{"displayName":"Host"}}
```

Success and failure responses:

```json
{"id":"req-1","type":"RESPONSE","ok":true,"data":{"roomId":"..."}}
{"id":"req-2","type":"RESPONSE","ok":false,"error":{"code":"N-FORBIDDEN","message":"..."}}
```

Server notifications omit `id` and use `PARTICIPANT_CONNECTED`, `PARTICIPANT_DISCONNECTED`, `PRODUCER_AVAILABLE`, `PRODUCER_CLOSED`, `TRANSPORT_CLOSED`, `CONSUMER_CLOSED`, `ROOM_CLOSED`, `STATE_UPDATE`, or `LATENCY_PONG`.

Supported requests:

- `CREATE_ROOM`, `JOIN_ROOM`, `LEAVE_ROOM`
- `PING`, `PONG`, `STATE_UPDATE`
- `GET_ROUTER_RTP_CAPABILITIES`
- `CREATE_WEBRTC_TRANSPORT`, `CONNECT_WEBRTC_TRANSPORT`, `RESTART_ICE`
- `PRODUCE`, `CONSUME`, `RESUME_CONSUMER`

Consumers are created paused to avoid RTP arriving before the client installs its local consumer. Call `RESUME_CONSUMER` after local setup. Application-level `PING` requests produce a `LATENCY_PONG` notification plus the correlated success response; the accepted legacy `PONG` request only acknowledges the application message. These JSON messages are distinct from WebSocket native ping/pong control frames, which enforce connection liveness using the configured heartbeat timeout and are handled automatically by normal WebSocket clients.

Structured failures use the `N-*` namespace: `N-BAD_REQUEST`, `N-NOT_IN_ROOM`, `N-ALREADY_IN_ROOM`, `N-ROOM_NOT_FOUND`, `N-ROOM_FULL`, `N-FORBIDDEN`, `N-CONFLICT`, `N-NOT_FOUND`, `N-CANNOT_CONSUME`, `N-SFU_FAILURE`, and `N-INTERNAL`.
