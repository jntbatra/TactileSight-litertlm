# Semantic Module: RGB camera on the band, wireless, one frame per query

> **Updated 2026-07-11 — see `CONTEXT.md` (Scene Frame, FrameSource seam).**
> The band RGB camera + on-device inference still hold, but the transport is refined: the band streams a **persistent live feed over WebRTC** (not a single Wi-Fi frame per query); "capture" is the frame **retained on button-press**, behind a **`FrameSource` seam**. For the hackathon the source is the **phone camera** (guaranteed demo); the band-RGB-over-WebRTC feed is a **stretch swap-in during the event**, behind the same seam. WebRTC pairing/signaling is bootstrapped by an **NFC tap** (band ↔ phone).

The Semantic Module's camera is a normal RGB camera mounted on the band at head level (not the phone camera, which is dead in a pocket, and not a chest lanyard, which has a worse viewpoint and loses pocket privacy). Head-level mounting gives the same forward viewpoint as the depth sensing, so "what" and "where" describe the same scene.

The camera feeds the phone wirelessly over Wi-Fi (the band's Arduino UNO Q relays), not over a cable. To keep the wireless link cheap enough to be viable on battery, the module captures **one RGB frame per user Query** rather than streaming continuously — semantics are on-demand, so continuous video is unnecessary. All model inference runs on the phone on-device, so no imagery leaves the user's person (privacy).

Consequences: per-query latency (~1–2s: capture → send → infer → speak) is accepted as the cost of going wire-free and on-device. The UNO Q must run a Wi-Fi AP or join the phone's hotspot.
