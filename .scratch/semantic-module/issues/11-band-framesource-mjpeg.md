# Band FrameSource (WebRTC single-image + depth) — stretch

Status: ready-for-agent

## What to build

A `WebRtcFrameSource` that, on each gesture, pulls **one JPEG + the depth-zone telemetry** from the band (Qualcomm **UNO Q**) over a single **WebRTC** connection, behind the existing `FrameSource` seam, so the pipeline describes **the band's head-mounted view** instead of the phone's — and feeds the depth zones into the VLM (see #1). Signaling = same-network WebSocket handshake. Full contract in `docs/band-interface.md`.

> Supersedes the original spike (MJPEG `/snapshot` from an ESP32-CAM): the band now runs a **UNO Q with RGB + depth cameras**, and the transport is WebRTC single-image + depth, not MJPEG.

## Acceptance criteria

- [ ] With the band connected, a press describes the band's view **and uses its depth zones**, via the same pipeline.
- [ ] Swapping the frame source requires no change to the orchestrator or anything downstream.
- [ ] The phone-camera + mock-telemetry fallback still works (demo insurance intact).

## Blocked by

None for the plumbing (`FrameSource` seam exists); sequence after **Real VLM Describe** for a meaningful demo. Band firmware is a teammate's scope — see `docs/band-interface.md`.
