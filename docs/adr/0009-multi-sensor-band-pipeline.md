# Multi-sensor band pipeline: RGB + depth + IR, phone-side fusion, spoken real distances

> **Corrected 2026-07-18 (same day): the band sends on-demand image triplets, not a continuous RGB video track.** See "Transport" below — the original text specified a continuous WebRTC video track carrying un-re-encoded MJPG, which (a) has no consumer, (b) can't work as written (WebRTC video tracks carry H.264/VP8/VP9, not MJPG, so "no re-encode" and "video track" contradict), and (c) split RGB from depth/IR across two delivery paths, forcing timestamp-matching for something a single capture gives for free. Refined further by [ADR-0013](0013-ir-aligned-calibration-free-distance.md).

> **Decided 2026-07-18 — see `CONTEXT.md` ("Band↔phone sensor pipeline", "Depth-fused distance").** Supersedes the "never state distance" honesty rule for the *distance* clause only, and un-gates the depth-fused-speech roadmap noted in ADR-0004. The where/what split (ADR-0001) still holds: the band owns real-time safety; the phone owns semantics — distance is now *measured by the band, spoken by the phone*, not guessed by the VLM.

## Context

The band's camera is an **Orbbec Astra Pro Plus** (verified 2026-07-18): three registered streams — **RGB**, **depth** (distance), **IR** (near-infrared, from the depth illuminator — *not* thermal). On-band compute is an **Arduino UNO Q** (Qualcomm Dragonwing QRB2210 + STM32), a modest IoT SoC that throttles under any real vision ML. The phone is the **Snapdragon 8 Elite** (SM8850).

Two prior specs are unblocked by this hardware:

- CONTEXT.md's **"never distance"** rule and ADR-0004's **"depth-fused-speech is calibration-gated roadmap"** both existed because *"an RGB feed can't back distance"* and *"sensor registration we don't have."* The Astra **provides factory registration** (color↔depth intrinsics + extrinsics) — so distance is no longer a guess, it is a **measurement**. The gate is gone.
- The **FrameSource** seam already anticipated a band producer over WebRTC; this ADR fills in *what* the band sends.

## Decision

**A tri-stream band pipeline with all intelligence on the phone.** Only two jobs are physically stuck on the band; everything smart runs on the 8 Elite.

### Division of labour
- **Band (UNO Q) — eyes + reflexes, no ML:**
  - **Capture** RGB + depth + IR from the Astra.
  - **Safety haptic loop, always-on, local:** depth → zones (near/mid/far × L/C/R) + hazard flags → **haptics driven by the STM32**. Runs with **zero network dependency** — if the phone or link dies, the user still walks safely (ADR-0001, ADR-0004).
  - **Send to the phone** — a dumb pipe, **on demand, nothing continuous**: on request, capture **RGB + depth + IR in one shot** and send all three as a **triplet over the data channel** (RGB as the camera's native **MJPG**, no re-encode). Send the Astra's **calibration once at session start**.
- **Phone (8 Elite) — the brain:**
  - **Align** depth↔RGB from the calibration (matrix math).
  - **Detect** objects (boxes + labels) and **describe** (VLM), on the retained keyframe.
  - **Measure** per-object distance: for each box, a **robust percentile (≈10th) of *valid* depth pixels** inside it (never raw `min` — depth holes would report a spurious 0.2 m).
  - **Fuse + speak:** *"person on your left, about two metres."* Identity/side from the image; distance from depth.

### Spoken distance (reverses "never distance")
The **VLM still says no distance** — it can't measure, so it must not guess (prompt keeps "Do not mention distance"). The **phone appends the measured distance** from the depth lookup. What the user hears is **ground truth**, never a hallucination — a stronger, safer claim than any RGB-only system (beats SixthSense structurally).

### Low-light via IR
The band measures RGB brightness (cheap) and, when the scene is dark, hands the **IR keyframe** to the phone instead of RGB. The VLM then "sees in the dark" where RGB is black — a real differentiator. Still one keyframe per Describe.

### Both distance layers coexist
- **Haptics (band, 30 Hz, no ML):** coarse depth zones → buzz. Instant, offline, safety.
- **Speech (phone, on Describe):** detection + per-box depth → precise per-object metres. Rich, on-demand.

### Transport: 5 GHz AP primary, USB fallback, one connection
- **UNO Q runs its own 5 GHz AP** (`hostapd`); phone joins it — private, low-latency, **no venue-WiFi dependency** (the hall hotspot was unusable, 2026-07-18).
- **USB-C fallback:** UNO Q as a **USB-gadget ethernet** (RNDIS/CDC-NCM) → a second IP to the same band.
- **One WebRTC PeerConnection**, used for its **data channel** — which carries the on-demand RGB+depth+IR triplets, telemetry, and button events. Both the AP and USB addresses are offered as **ICE candidates**, so ICE **fails over automatically** — no hand-written reconnect logic. Signaling = a tiny WebSocket on the UNO Q's known IP (AP tried first, USB second).
- **Nothing streams continuously.** Every consumer in the design is event-driven: button-1 tap needs depth+IR, button-1 hold needs one triplet, and continuous guidance (ADR-0011) is *periodic* narration off the on-device fast lane — periodic triplets, not video. A continuous track would burn encode + radio on a QRB2210 to deliver frames nobody reads.
- **Optional dashboard preview — OPEN, pending the band team's resource answer.** A low-rate RGB preview (≈320×240, a few fps, H.264 — re-encoded, which is fine because it is not the pipeline's input) would exist purely so the judge-facing UI isn't a frozen still. **Whether we do it at all is the band team's call**, on whether the UNO Q has the headroom to sustain a WebRTC stream. The binding constraint is expected to be **sustained encode + radio against the STM32 haptic loop's deadlines**, not memory — the QRB2210 runs Linux, so WebRTC's own footprint is small. **The haptic loop is safety-critical and must never be starved by a cosmetic preview**; if it is even close, we drop the preview.
  Regardless of the answer: **nothing depends on it.** The app must be fully functional with the preview off, and it must be cuttable without touching the contract. The on-demand triplet above is the contract either way — it is a correctness requirement (same capture instant), not a bandwidth compromise.

### Capture resolutions (Astra Pro Plus profiles)
- **RGB 1280×720, MJPG** — the VLM sweet spot (Gemma warms at 768², Qwen3-VL tiles ~1024); 1080p is pixels the model discards at extra encode/bandwidth cost. Forwarded as-is, **not re-encoded**.
- **Depth 640×480** (native) — haptics on-band; keyframe to phone per Describe (down to 320×240 if bandwidth ever bites).
- **IR 640×480 @30** — dark-scene keyframe (1280×720 @7 if more detail wanted; on-demand, so fps is moot).
- **Known FOV mismatch:** RGB 720p is 16:9, depth/IR are 4:3, so depth covers the **central** region; an object at the extreme top/bottom of the color frame gets **"distance unknown"** (graceful). Chosen over dropping RGB to 640×480/4:3 — recognition detail matters more.

## Rationale / alternatives considered

- **Detection on the band:** rejected — the QRB2210 throttles under a real detector, and its labels would be weaker than the phone's VLM. The band runs *no* ML beyond integer depth-zoning.
- **Alignment on the band (my first instinct):** unnecessary — alignment is math, and shipping raw depth keyframes (event-driven, ~tens of KB) lets the far stronger phone do it. Keeps band firmware simple.
- **Continuous depth to the phone:** not needed — continuous depth drives *haptics*, which live on-band; the phone needs depth only per Describe.
- **Continuous RGB video track to the phone** (the original text here): **rejected on correction.** No consumer reads more than one frame per press; it would force an H.264 re-encode on the QRB2210 we said must stay dumb; and it would deliver RGB on a different path from depth/IR, so the phone would have to timestamp-match streams to recover the same-instant triplet that a single on-demand capture provides for free. Latency was the argument for it — but the round trip over the band's own AP is tens of milliseconds against a hold that lasts a second or more, so there is ample budget. On-demand also makes ADR-0007's capture policy true **on the wire**, not merely at the retention layer.
- **Column-nearest distance (no detection):** simpler but coarse ("nearest thing left is 1.8 m" vs. per-object). Kept as a fallback if per-box association proves flaky.
- **VLM's own grounding (Qwen3-VL emits boxes):** attractive (one model for identity+location) but **unproven through GenieX** for us — treated as a stretch upgrade; baseline is a small on-phone detector for boxes + VLM for description.
- **Venue WiFi as transport:** rejected after the hotspot pain — the band's own AP is the reliable, private path.

## Consequences

- **Honesty rule updated:** "identity + horizontal position, **never distance**" → distance **is** now spoken, sourced from the band's depth (not the VLM). CONTEXT.md and ADR-0004's roadmap note updated; ADR-0001's where/what split intact (band measures, phone speaks).
- **New phone-side work:** depth↔RGB alignment, a box detector, per-box robust-percentile depth lookup, and distance-fusion into the spoken answer — behind the existing pipeline seams. Tracked as a new ticket.
- **New band-side work (teammate scope):** Astra capture of all three streams + calibration export, the STM32 haptic loop, **on-demand RGB+depth+IR triplets over the data channel**, `hostapd` AP + USB-gadget net, and the WebRTC producer/signaling. **Simpler than the original spec** — no video encoder, no streaming pipeline, no A/V sync.
- **Depth holes & FOV gaps** must degrade gracefully to "distance unknown," never a false number — this is safety-relevant.
- **Band integration is still a stretch during the event** — the phone-camera demo (no distance) must stand on its own; distance-fused speech lands when the band link is up.
