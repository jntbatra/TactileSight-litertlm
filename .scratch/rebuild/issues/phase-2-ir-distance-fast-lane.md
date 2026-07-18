# Phase 2 — IR-based distance + the quick-tap fast lane

Status: ready-for-agent

## What to build

The **differentiator**: real per-object distance, spoken instantly, with **no VLM and no network**. Detect on the **IR** frame and read depth on the same pixel grid — **no calibration needed** (see `docs/adr/0013-ir-aligned-calibration-free-distance.md`).

- **`TestSceneSource`** (a `FrameSource`): replays the bundled captures — `rgb.jpg` 1280×720, `depth_raw.npy` 640×480 `uint16` mm, `ir.jpg` 640×480. **Bundle the scenes in the APK.** This lets the whole pipeline be built and tested with **no band hardware**.
- **YOLOv11-Detection** (Qualcomm AI Hub) on the **IR** frame → `{label, box}`.
  **Runtime: TFLite/LiteRT *or* ONNX Runtime + QNN EP — whichever lands YOLOv11 on the Hexagon NPU with less friction.** AI Hub publishes it in both formats, and QNN EP is mature for CNNs (which is what YOLO is), so either is viable. Record which one worked and whether it actually hit the NPU.
- **Distance:** robust **~10th percentile of *valid* depth pixels** in the box → millimetres.
- **Quick tap (button 1)** → speak *"chair, two metres, centre"* via Sarvam TTS (with the cached templated vocabulary).

## Mandatory safeguards (forced by the real capture data)

- **Never raw `min`** — one hole/edge pixel corrupts it.
- **Minimum-valid-pixel threshold** → otherwise **"distance unknown"**. Valid coverage averages **62.6%** and one observed box was **1% valid**; this path fires regularly and must never fabricate a number.
- **IR saturation as a confidence signal** (IR ≈185 at holes vs ≈55 at valid) — predict the hole before trusting the box.

## Acceptance criteria

- [ ] Quick tap on a bundled scene speaks a **measured** distance for a detected object.
- [ ] Boxes with too few valid depth pixels speak **"distance unknown"** — verified on a reflective/hole-heavy scene.
- [ ] End-to-end fast lane is **~0.5 s** and uses **no VLM**.
- [ ] **Measure YOLO's accuracy on grayscale IR** across the 21 frames — this is the open risk. If too weak, fall back to RGB detection + horizontal-position matching (ADR-0013 option 2) and record the decision.
- [ ] Distances validated against **tape-measured ground truth** (request from the band team) — plausible is not the same as correct.

## Blocked by

Bundled captures (have them: 21 scenes). **Not** blocked by calibration — that is the point of ADR-0013. Ground-truth distances still wanted for validation.
