# TactileSight — Haptic Point-Cloud Navigation for the Visually Impaired
### Full Implementation Plan — Qualcomm Snapdragon Multiverse Hackathon, Noida (July 18–19, 2026)

> **⚠️ HISTORICAL — this is the original vision document, not the current plan.**
> It still explains **what TactileSight is and why**, and that part holds. But the implementation
> detail below predates every architecture decision made on 2026-07-18.
> **Plan of record:** `CONTEXT.md` → `REBUILD — settled design` · [`docs/adr/0009`–`0013`](docs/adr/)
> · the [GitHub Issues](../../issues) · [`README.md`](README.md) for the current overview.

---

## 1. Overview

TactileSight is a wearable headband that captures live depth data from the environment and renders it directly onto the forehead as touch — a matrix of vibration motors where pulse rate and intensity encode distance. It is a real-time, hearing-free, hands-free spatial awareness device for blind and visually impaired users.

**One-line pitch:** *Feel the room before you touch it.*

---

## 2. Problem Statement

- Over 40 million people worldwide are blind (WHO / Vision Loss Expert Group estimates put the range at roughly 39–49 million globally, 2020 data).
- Canes only sense what they physically hit.
- Guide dogs are expensive and inaccessible to most.
- Audio-based navigation aids occupy hearing — the sense blind users rely on most for environmental awareness.
- Existing haptic belts use 8–16 coarse zones (direction only, not shape).
- Camera-only (monocular) depth estimation — the kind a plain phone camera does — degrades badly in low light and carries roughly 25–40% relative error in published evaluations, and gets worse with distance. **Not safe as a primary sensing layer for a device someone trusts to walk with.**

---

## 3. Solution Summary

A headband with:
1. **Two depth cameras** (front-facing + rear-facing) capturing real depth data, not camera-only estimation.
2. **Arduino UNO Q** as the single compute + control board — ingests both depth streams, downsamples them, and drives the motor array in real time.
3. **A motor matrix** worn on the head that renders the downsampled depth map as touch — closer objects pulse faster/stronger, distant ones fade.

This is sensory substitution: touch replacing sight for spatial awareness, the same principle behind braille, applied to 3D space instead of text.

---

## 4. System Architecture

```
[Front Depth Camera] ──┐
                        ├──► [Arduino UNO Q — Qualcomm QRB2210 / Linux side]
[Rear Depth Camera]  ──┘         │  - ingest both depth streams
                                  │  - downsample each to grid
                                  │  - fuse into combined map
                                  ▼
                         [Arduino UNO Q — STM32U585 / real-time side]
                                  │  - PWM drive per motor
                                  ▼
                         [Motor Matrix Headband]
                          (5 rows × 10 cols per side,
                           staged build — see Section 7)
```

**Why the Arduino UNO Q fits this role (verified spec):**
- Qualcomm Dragonwing QRB2210: quad-core Arm Cortex-A53 @ 2.0GHz, Adreno 702 GPU, **dual ISPs supporting up to 25MP @ 30fps** — confirms native dual-camera capability on one board.
- Runs full Debian Linux — can run Python/OpenCV-style depth processing directly.
- STM32U585 (Cortex-M33, real-time, Zephyr) handles PWM motor driving with low jitter, independent of the Linux side's load — this is what keeps haptic feedback responsive even if the Linux side is busy.
- The two sides talk over an internal RPC/Bridge library — no external wiring needed between "compute" and "control."

*(Source: Arduino's own UNO Q documentation and multiple independent hardware reviews, cross-checked.)*

---

## 5. Hardware Components

| Component | Role | Notes / Risk |
|---|---|---|
| Arduino UNO Q | Core compute + real-time control | Confirmed capable — see Section 4 |
| Depth camera ×2 (front + rear) | Primary sensing | **[Unverified]** Confirm the specific model has a working driver on UNO Q's Qualcomm Linux image *before* the event — most depth camera modules found in research (e.g. Arducam ToF) are documented for Raspberry Pi / Jetson, not confirmed for UNO Q. This is the single biggest technical risk in the build. Test it in advance if at all possible. |
| Vibration motors (coin/ERM type) | Haptic output | Need current-draw budget once final motor count is fixed |
| PWM driver expander board(s) (e.g. I2C 16-channel driver) | Motor addressing | Needed once motor count exceeds UNO Q's direct GPIO pin count — daisy-chain multiple boards via I2C |
| Flexible headband substrate | Physical mount | Needs to be wide enough (~5cm+) to give 5 rows enough physical spacing (see Section 6) |

---

## 6. Perceptual Design Rationale (why the grid is sized this way)

Two-point discrimination threshold (the minimum distance between two touch points a person can tell apart) varies by published study but generally falls in the **5–9mm range on the forehead** — one of the most tactilely sensitive non-glabrous (hairy) skin regions on the body, more sensitive than the forearm or back of the hand.

Implication for the motor grid:
- **Columns:** with ~10 motors spanning the front half of a typical head circumference (~55–58cm total, ~27–29cm front arc), spacing works out to roughly 27–29mm between motors — well above the discrimination threshold. Good headroom.
- **Rows:** 5 rows requires the band to have enough physical height for row spacing to stay above ~5–9mm. A band roughly 5cm tall gives ~10mm spacing — borderline but workable. A narrower band will blur rows together and waste resolution.

**Design takeaway:** don't build the band narrower than ~4–5cm if you want 5 distinguishable rows. If the fabricated band ends up narrower, drop to 3–4 rows rather than keeping 5 rows too close together to feel distinct.

---

## 7. Staged Build Plan (24-hour scope)

Building the full 5×20 (100-motor) circular band in 24 hours carries real fabrication risk (wiring, driver ICs, current budget, comfort). Build in stages so you always have a working demo:

**Stage 1 — Core pipeline (must-have, build first):**
- One depth camera (front) → Arduino UNO Q → downsample to a small grid (start with 5×5 or 5×10) → drive that portion of the motor matrix.
- Validate end-to-end: does a real obstacle in front of the camera produce the correct tactile pattern?
- This alone is a demoable product if nothing else gets built.

**Stage 2 — Add rear camera + fusion:**
- Bring in the second depth camera, extend the downsampling and grid-driving logic to the rear half of the band.
- Only attempt once Stage 1 is fully working and tested.

**Stage 3 (stretch, only if time remains):**
- Extend from a partial grid toward the full 5×10-per-side (100 motor) target.
- Add any Snapdragon phone / AI PC scene-classification layer (optional enhancement, not required for the core pipeline).

**Do not start Stage 2 or 3 until Stage 1 is demoed and working.** A working narrow-scope demo beats a broken ambitious one.

---

## 8. Software Pipeline (high level)

1. Capture depth frame(s) from camera(s) via UNO Q's Linux/ISP pipeline.
2. Downsample each depth image into an N×M grid (grid size matched to your actual built motor count — start smaller than 5×10 and scale up only if Stage 1 works).
3. Map each grid cell's distance value to a motor's PWM intensity/pulse-rate (closer = faster/stronger).
4. Send the grid values from the Linux side to the STM32 real-time side over the internal RPC/Bridge library.
5. STM32 side drives the motor matrix via PWM with consistent timing.

**[Unverified]** No specific mapping function (linear vs. exponential distance-to-intensity curve) has been tested or validated yet — this is a tuning decision to make during the build, ideally validated with a blindfolded test subject rather than assumed.

---

## 9. Devices Requested (hackathon form)

Since the form only allows one selection, choose **"Other - Specify"** and list everything in the text field:

> Primary hardware: Arduino UNO Q (core compute + real-time motor control) and our own dual depth camera modules, front-facing and rear-facing (depth-sensing input) — requesting confirmation the depth cameras are permitted as supplementary hardware.

---

## 10. Key Risks & Open Questions (check before/at the event)

- **[Unverified]** Depth camera driver compatibility with UNO Q's Qualcomm Linux image — test in advance if possible.
- **[Unverified]** Exact motor count vs. available PWM/driver channels — finalize motor count before ordering/wiring driver boards.
- **[Unverified]** Power budget for simultaneous motor activation — not yet calculated; depends on final motor count and type.
- **[Unverified]** Whether hackathon organizers permit participant-supplied hardware (depth cameras) — confirm with organizers ahead of time.
- Physical band height must be ≥ ~4–5cm to keep 5 rows perceptually distinguishable (see Section 6) — a design constraint for whoever fabricates the physical band.

---

## 11. Roadmap Beyond the Hackathon

- Solenoid-based pressure actuation for finer resolution, once vibration-motor encoding is validated for safety and usability.
- Full 5×20 circular band (front + rear, complete perimeter awareness) as the long-term target design.
- Federated/cloud-based calibration (e.g. via Qualcomm AI Cloud 100) to adapt the scene-to-haptic encoding model across users over time, since tactile sensitivity and learning curves vary person to person.

---

## 12. Demo Script

1. Volunteer wears the headband, blindfolded.
2. Volunteer navigates a short obstacle course using tactile feedback alone.
3. Judges are invited to wear the headband themselves and feel the effect directly.

---

*This document reflects the design decisions made through iterative feasibility review, including two-point discrimination research and Arduino UNO Q hardware specifications verified via Arduino's own documentation and independent hardware reviews. Items marked [Unverified] are flagged because they require testing, confirmation with organizers, or further specification before the event — they are not settled facts.*
