# Per-object distance via the IR stream (calibration-free)

> **Decided 2026-07-18 (grilled, validated against real captures) — see `CONTEXT.md` ("Depth-fused distance").** Refines ADR-0009: the distance path no longer depends on RGB↔depth calibration.

## Context

ADR-0009 specified per-object distance as: detect on **RGB (1280×720)** → map the box into the **depth (640×480)** frame → robust percentile → metres. That mapping needs the Astra's **colour↔depth calibration**, which made calibration a hard blocker.

We then received **21 real captures** (`captures.zip`) and a `calib.json`, and validated against them:

- Per scene: `rgb.jpg` **1280×720 MJPG**, `depth_raw.npy` **640×480 `uint16` millimetres**, `ir.jpg` **640×480**, plus a colorized depth and `metadata.json`. Format is exactly right.
- Depth is genuinely metric: readings **0.45 m – 9.94 m** across the set.
- **Valid depth coverage averages 62.6%, ranging 36%–98%.** Holes are severe and local — one scene's centre 80×80 region had **1% valid pixels**.
- **IR and depth share the same sensor and the same 640×480 grid** — confirmed by `calib.json` (*"IR sensor shares the same optics as depth — pixel-aligned"*) **and** empirically: IR reads **185 where depth is invalid** vs **55 where valid** (3.36×), the signature of IR **saturation** blowing out the structured-light pattern. That systematic relationship would wash out if the grids were misaligned.
- `calib.json`'s **1280×720 colour intrinsics are unreliable**: `fy = 818.11` assumes an anisotropic *stretch* from 4:3, but a 16:9 mode is a **vertical crop** — `fy` should equal `fx` (1090.81). The file's own `cy ≈ 364` is crop-consistent, contradicting its `fy`. (Depth intrinsics, colour 640×480 intrinsics, and the extrinsics — near-identity rotation, −24.9 mm baseline — are all sane.)

## Decision

**Detect on the IR frame and read depth on the same grid. No calibration required.**

```
YOLO on ir.jpg (640×480) → box (x,y,w,h)
        ↓ same sensor, same pixel grid, 1:1
depth_raw[y:y+h, x:x+w] → robust percentile of VALID pixels → distance in mm
```

**Mandatory safeguards** (both forced by the capture data, both safety-relevant):
- **Robust percentile (~10th) of *valid* pixels only** — never raw `min`, which one hole/edge pixel would corrupt.
- **Minimum-valid-pixel threshold.** If a box has too few valid depth pixels (the observed 1%-valid case), report **"distance unknown"** — never a confident number derived from ~60 pixels.
- **IR brightness as a confidence signal.** Saturated IR predicts a depth hole, so we can pre-emptively flag "distance unknown" rather than discovering it after the fact.

**Stream roles:** **IR** → detection + exact distance (calibration-free) · **RGB 1280×720** → the VLM's rich description · **depth** → metric truth.

**Associating "the chair" (named by the VLM in RGB) with "1.83 m" (measured in IR/depth)**, cheapest first:
1. **Report straight from IR detection** — YOLO's own label + distance: *"chair, 1.8 m, centre."* Works today.
2. **Match by horizontal position** — VLM says "chair on the left" → take the left-region distance. Approximate, no calibration.
3. **Full RGB↔depth projection** — exact, needs trustworthy 720p intrinsics.

## Rationale / alternatives considered

- **Wait for calibration:** unnecessary — the IR path removes the dependency entirely and unblocks Phase 2 today.
- **Fix the 720p intrinsics first:** the correct fix is an **OpenCV checkerboard calibration at 1280×720** (as `calib.json` itself recommends), or simply **work at 640×480** where colour intrinsics are ground truth and the FOV matches depth. Neither is on the critical path.
- **Detect on RGB anyway:** better detector accuracy (YOLO is trained on RGB), but reintroduces the calibration dependency. Kept as the upgrade once intrinsics are trustworthy.

## Consequences

- **Phase 2 is unblocked without calibration.** Nothing needs re-capturing.
- **Open empirical risk:** YOLO is trained on RGB, so accuracy on **grayscale IR is unproven** — it must be measured on the 21 IR frames we have. If IR detection is too weak, fall back to RGB detection + horizontal-position matching (option 2).
- **"Distance unknown" is a common path, not an edge case** — average 62.6% valid coverage guarantees it fires regularly. The speech layer must handle it gracefully and never fabricate a number.
- Near-field works better than feared: readings down to **0.45 m**, so counter/arm's-length distances are measurable — relevant if task guidance ("make coffee") is scoped in.
- Still worth requesting from the band team: **ground-truth tape-measured distances** for a few scenes (to prove correctness, not just plausibility), a **dark scene** (to validate the IR-in-the-dark claim), and a **glass/reflective scene** (worst case for holes).
