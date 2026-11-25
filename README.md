# Symphonia

Symphonia shares one selected Android app's audio live. The first milestone is laptop-free local sharing. The Speaker taps **Start Sharing**, and a Listener on the same Wi-Fi or hotspot scans a QR to listen in Chrome without installing an app or entering an IP. The milestone includes hotspots without internet access.

The ARM64 Gate 2 debug build can host signaling, the browser player, and the SFU on the Speaker phone. Selected-app audio reached Chrome on shared Wi-Fi during the initial hardware check. On 2026-11-25, the user confirmed that a second phone scanned the QR and played shared audio on the Speaker’s offline hotspot with the laptop off.

See the [product requirements](docs/PRD.md), [roadmap](docs/MVP_ROADMAP.md), [technical architecture](docs/TECHNICAL_ARCHITECTURE.md), and [decision record](docs/DECISION.md).

## Gate 2 on-phone relay test

An ARM64 debug build can include the synthetic-tone probe in the Gate 2 Harness. Build with `-Pgate2RelayProbeBundle=<absolute bundle directory>`. The bundle contains `assets/probe.zip`, `assets/phone-relay.zip` and `jniLibs/arm64-v8a/` with embedded Node, the Android mediasoup worker, the matching C++ runtime, and the JNI bridge built from the harness's debug probe source.

On Windows, [build-gate2-relay-bundle.ps1](scripts/build-gate2-relay-bundle.ps1) prepares these files from an extracted ARM64 Node SDK, an Android-built mediasoup worker, the Android NDK, and FFmpeg. Pass the output directory to the Gradle property above.

Open **Test on-phone relay** in Speaker mode, then open the displayed test link on a second device on the same Wi-Fi. The probe plays a generated tone. With the phone relay bundle installed, **Start Sharing** hosts selected-app audio on the phone and shows a signed invite QR after the producer is ready. Builds without the bundle use the configured signaling server.
