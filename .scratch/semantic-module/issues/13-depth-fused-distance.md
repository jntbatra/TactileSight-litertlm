# Depth-fused spoken distance (phone-side fusion) — stretch

Status: ready-for-human

## What to build

The **phone-side** half of ADR-0009: turn the band's depth keyframe into a **measured, spoken distance** per object, fused with the VLM's identity/side — *"person on your left, about two metres."* Distance is a **measurement from the band's Astra depth camera**, never a VLM guess. Builds on **#11** (band `FrameSource` carrying RGB + depth/IR keyframe + calibration) and **#01** (real VLM Describe). See `CONTEXT.md` ("Band↔phone sensor pipeline", "Depth-fused distance") and `docs/adr/0009-multi-sensor-band-pipeline.md`.

The band does **no ML** — it only captures, runs its own haptic loop, and forwards frames + calibration. All of the below is on the phone.

- **Align** depth↔RGB from the Astra calibration (color/depth intrinsics + extrinsics), sent once at session start — so a color pixel/box maps to a depth value. (RGB 1280×720 16:9 vs depth 640×480 4:3 → depth covers the central region only.)
- **Detect** object boxes on the keyframe. **Baseline:** a small on-phone detector (ML Kit / a lightweight YOLO) for boxes + the VLM for the rich description. **Stretch:** Qwen3-VL's own grounding (boxes from the VLM) — unproven through GenieX, so not the baseline.
- **Measure** per box: a **robust percentile (~10th) of *valid* depth pixels** inside it. **Never raw `min`** — a single hole/edge pixel would report a spurious 0.2 m (safety-relevant). Too few valid pixels, or a box outside the depth FOV → **"distance unknown"**, never a false number.
- **Fuse + speak:** append the measured distance to the VLM's identity/side. The **VLM prompt still forbids distance** (it can't measure); the phone adds it deterministically.
- **Fallback:** if per-box association is flaky, fall back to **column-nearest** distance (near/mid/far × L/C/R) — coarser but robust.

## Acceptance criteria

- [ ] With the band connected, a Describe speaks **identity + side (VLM) + a measured distance (depth)** for the salient object(s).
- [ ] Distance comes from the depth lookup, not the VLM; the VLM prompt still contains no distance.
- [ ] Depth holes / out-of-FOV objects degrade to **"distance unknown"** — never a fabricated metre value (verify with a reflective/edge scene).
- [ ] Alignment uses calibration sent from the band; no hard-coded intrinsics.
- [ ] Phone-camera fallback (no depth) still works and simply omits distance — demo insurance intact.

## Blocked by

**#11** (band `FrameSource` must carry the depth/IR keyframe + calibration) and **#01** (real VLM Describe). Band-side capture/calibration-export/haptics/transport are a **teammate's scope** — see `docs/band-interface.md` (needs the ADR-0009 tri-stream + calibration contract added). Depth-fused distance only lights up when the band link is up; the phone-camera demo (no distance) must stand on its own.
