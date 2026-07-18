# TactileSight — Test Guide

How testing works on this project, how to run it, and — because this is an on-device app — the
**manual on-device checks** that unit tests can't cover.

> **Status (2026-07-18):** the Android app is being rebuilt, so `android/` does not exist yet and
> there is no app test suite to report. This document is the **strategy** to build against, not a
> record of passing tests. The server suite is real and green today.

## The two halves of testing

1. **Unit tests (automatic, no device)** — the *logic*: gesture disambiguation, the distance
   percentile and validity threshold, mode routing, prompt/fusion assembly. Fast, deterministic.
2. **On-device checks (manual, needs the 8 Elite)** — anything you must *press, hear, or see*:
   camera, TTS, ASR, gesture feel, and whether a runtime actually loads and generates. These can't
   run in CI; use the checklists below.

The line between them is the **seam**. Logic sits behind seams and is unit-tested; the thin adapters
over camera, speech and the inference runtimes are verified on the device.

## Testing philosophy

- **Test behaviour, not implementation.** Assert what a unit *does* — its outputs, the calls it
  makes — never its internals.
- **Inject time and dependencies.** Anything time-dependent (gesture recognition, timeouts) takes
  the current time as a *parameter* and never calls the clock inside, so tests drive time
  deterministically. Orchestration depends on the seams, so tests pass fakes.
- **One behaviour per test; name the test after the behaviour**
  (e.g. `a_region_below_the_validity_threshold_reports_distance_unknown`).

To add a test: find (or introduce) the seam, write a fake or pass a known value, assert the
observable outcome.

### The seams

- **`SemanticBrain`** — one interface, three engine implementations (LiteRT-LM, GenieX,
  ExecuTorch+QNN). The primary seam: everything above it is engine-agnostic and unit-testable with
  a fake brain that returns canned text.
- **Frame capture** — phone camera or band stream or a bundled test scene, behind one interface.
  This is what makes issue #3 (bundled scenes) so valuable: it turns the *whole* pipeline into
  something runnable with no hardware at all.
- **Speech** — ASR/TTS/translate, so language routing is testable without hitting Sarvam.

### Pure logic worth testing hardest

- **Distance from depth** — the robust percentile over valid pixels, and the minimum-valid-pixel
  threshold that produces *"distance unknown"*. Test it against the real captures: mean valid
  coverage is 62.6% and one region measured **1% valid**, so the unknown path is not an edge case,
  it is a main path. See [ADR-0013](docs/adr/0013-ir-aligned-calibration-free-distance.md).
- **Gesture recognition** — three buttons × tap/hold. **There is no double-press** (ADR-0011); a
  test asserting one is testing a removed feature.
- **Mode routing** — hold-then-speak → Query, hold-then-silence → Describe, tap → spatial only.
- **Every press yields speech** — including the failure paths: no frame, model error, ASR silence,
  timeout. Assert *something* is always spoken.

## Run the tests

```bash
# server — real and green today, hermetic (no model, no GPU, no network)
cd server && TS_VLM_BACKEND=mock python -m pytest

# app — once issue #1 has landed android/. JDK 21; JDK 25 fails, see TEAM.md
cd android && ./gradlew testDebugUnitTest
```

`server/test_app.py` green **is** the compatibility check between the app and server tracks — it is
the only automated guard on the frozen HTTP contract, so run it before changing anything in
`server/`.

CI (`Jenkinsfile`) runs the server suite now and picks up the Android stages automatically once
`android/gradlew` exists. No CI edit is needed when the app lands.

## Build + install the APK

```bash
cd android && ./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

If you hit `INSTALL_FAILED_UPDATE_INCOMPATIBLE`, the build is signed differently — uninstall first.
That wipes app data, but **models live in the external files dir and survive**.

## On-device verification checklists

> Unit tests prove the *logic*. These prove the *experience*. Nothing below is auto-verified.
> Fill these in per issue as slices land — each issue's acceptance criteria are the source.

### Engine bring-up (per engine)

- [ ] The sideloaded model is discovered by the startup directory scan — no rebuild needed to add one.
- [ ] The model loads once and **stays resident**: describe twice, and the second is fast (no 30–60 s reload).
- [ ] Rotating the device does **not** drop or reload the model. *(This is the exact bug that
      OOM-killed the previous app six times — check it explicitly.)*
- [ ] Switching engine/model releases the previous one; `MemAvailable` recovers.
- [ ] Only one VLM is resident at a time.

### Interaction

- [ ] **Tap** → spatial answer (object + measured distance + direction), no VLM, no network.
- [ ] **Hold, say nothing, release** → full description.
- [ ] **Hold, ask a question, release** → an answer to that question.
- [ ] Nothing is spoken until the mic closes — no TTS feeding back into ASR.
- [ ] No press is ever silent, including on failure.

### Distance honesty

- [ ] A glass / dark / reflective scene reports **"distance unknown"** rather than a number.
- [ ] Spoken distances match a tape measure within tolerance.
- [ ] The VLM's own text never contains a distance.

### Language

- [ ] The description is spoken correctly in Hindi, Punjabi, and at least one of Tamil/Telugu.
- [ ] A question asked in that language is answered in the same language.

## What is intentionally NOT unit-tested (and why)

Camera, TTS, ASR, translation, the band transport, and the inference runtimes themselves — these are
the OS/hardware/network boundary. They are exercised by the manual checklists. That is by design: the
seams keep the *logic* testable, and the thin adapters over the platform are verified on the device.
