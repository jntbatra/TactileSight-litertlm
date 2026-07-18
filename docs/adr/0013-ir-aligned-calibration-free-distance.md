# Per-object distance via the IR stream (calibration-free)

> **Decided 2026-07-18 (grilled, validated against real captures) — see `CONTEXT.md` ("Depth-fused distance").** Refines ADR-0009: the distance path no longer depends on RGB↔depth calibration.
>
> ⚠️ **Amended 2026-07-18, same day.** The core decision stands — IR and depth do share a grid, and detecting on IR still removes the calibration dependency. But **two supporting claims below are wrong**, and one of them is load-bearing for a feature we have not built yet:
> 1. **The IR-saturation confidence signal does not reproduce on the captures that ship in the APK.** See "IR brightness as a confidence signal" below.
> 2. **The 1280×720 colour intrinsics analysis is wrong** — `fy` should equal `fx`, but `fx` is not 1090.81 either. Both are ≈966.5–967.3, measured. See TEAM.md.

## Context

ADR-0009 specified per-object distance as: detect on **RGB (1280×720)** → map the box into the **depth (640×480)** frame → robust percentile → metres. That mapping needs the Astra's **colour↔depth calibration**, which made calibration a hard blocker.

We then received **21 real captures** (`captures.zip`) and a `calib.json`, and validated against them:

- Per scene: `rgb.jpg` **1280×720 MJPG**, `depth_raw.npy` **640×480 `uint16` millimetres**, `ir.jpg` **640×480**, plus a colorized depth and `metadata.json`. Format is exactly right.
- Depth is genuinely metric: readings **0.45 m – 9.94 m** across the set.
- **Valid depth coverage averages 62.6%, ranging 36%–98%.** Holes are severe and local — one scene's centre 80×80 region had **1% valid pixels**.
- **IR and depth share the same sensor and the same 640×480 grid** — confirmed by `calib.json` (*"IR sensor shares the same optics as depth — pixel-aligned"*) and by a live-camera check ("Same grid: True"). This part holds.
  - ⚠️ The supporting figure — IR **185 where depth is invalid** vs **55 where valid** (3.36×) — came from a **live session** and **does not reproduce on the 20 shipped captures**: across all of them mean IR is **3.17/255** (essentially black; brightest pixel averages 39.5, and 0.9% of pixels exceed 30), and the contrast is *inverted* at 0.855. Exposure differed. The grid-sharing conclusion does not depend on this figure, but the confidence signal below does.
- `calib.json`'s **1280×720 colour intrinsics are unreliable** — correct conclusion, wrong reasoning. ⚠️ **Amended:** this said `fy` should equal `fx` **= 1090.81**, on the theory that 16:9 is a vertical crop of 4:3. Measurement says 720p is *neither* a crop *nor* a stretch — it is a horizontally wider, vertically narrower readout — and the true values are **fx 966.5, fy 967.3** (so `fy == fx` was right, and 1090.81 was wrong by 13%). See TEAM.md for the method. (Depth intrinsics, colour 640×480 intrinsics, and the extrinsics — near-identity rotation, −24.9 mm baseline — are all sane and unaffected.)

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
- **IR brightness as a confidence signal.** ⚠️ **Unproven — do not build on this yet.** The idea is that saturated IR predicts a depth hole, letting us flag "distance unknown" pre-emptively. It was measured once on a live camera (3.4× contrast) but **fails to reproduce on our own bundled captures**, where the IR frames are nearly black and the contrast inverts. Any code relying on it would work in a live demo and silently fail against the test data — the worst possible split. **Re-measure on captures taken with the exposure we will actually ship before this becomes a dependency.** Falling back to "measure the depth and see if it is valid" costs little and always works.

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
