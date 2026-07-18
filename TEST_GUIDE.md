# TactileSight — Test Guide

How testing works on this project, how to run it, and — because this is an on-device app —
the **manual on-device checks** that unit tests can't cover.

## The two halves of testing

1. **Unit tests (automatic, no device)** — the *logic*: gesture disambiguation, scene-memory
   eviction, the Query orchestrator's behaviour. Fast, deterministic, run in the container.
2. **On-device checks (manual, needs the S22)** — anything you must *press, hear, or see*:
   camera, TTS, gesture feel, saving to the gallery. These can't run in CI; use the checklists
   below.

## Testing philosophy (how to write more)

- **Test behaviour, not implementation.** Assert what a unit *does* (its outputs / the calls it
  makes), never its internals.
- **Inject time and dependencies.** `GestureRecognizer` and `SceneMemory` take the current time
  as a *parameter* (never call the clock inside) — so tests drive time deterministically. The
  `QueryOrchestrator` depends on the three **seams** (`FrameSource` / `SemanticBrain` /
  `SpeechIO`) plus an injected `clock`, so tests pass **fakes** and known values.
- **One behaviour per test; name the test after the behaviour** (e.g.
  `a_described_scene_is_stored_in_memory`).

To add a test: find (or introduce) the seam/param, write a fake or pass a known value, then
assert the observable outcome. See `QueryOrchestratorTest` for the fake-at-seam pattern.

## Run the unit tests

From the repo root (uses the containerized toolchain — nothing installed on the host):

```bash
docker run --rm -v "$PWD/android:/workspace" -v ts-gradle:/root/.gradle \
  tactilesight-android-build gradle testDebugUnitTest --no-daemon --console=plain
```

`BUILD SUCCESSFUL` = all green. HTML report: `android/app/build/reports/tests/testDebugUnitTest/index.html`.

### What the unit tests cover (16 tests)

| Test class | Verifies |
|---|---|
| `GestureRecognizerTest` | single→Describe, double→Save, hold→QueryStart/End, HEARD fires once on first down, deadline scheduling |
| `SceneMemoryTest` | recent scene returned, >10-min eviction, oldest-first order, eviction on add |
| `QueryOrchestratorTest` | Describe/Query prompt selection, language routing, HEARD-first, no-frame fallback, 6s timeout fallback, question passthrough, **scene stored on describe**, **earlier scene passed as context** |

## Build + install the APK

```bash
# build
docker run --rm -v "$PWD/android:/workspace" -v ts-gradle:/root/.gradle \
  tactilesight-android-build gradle assembleDebug --no-daemon --console=plain
# install (host adb; phone plugged in + authorized)
adb install -r android/app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.tactilesight.semantic/.MainActivity
```

## On-device verification checklists (manual, on the S22)

> The unit tests prove the *logic*. These prove the *experience*. Nothing below is auto-verified.

### Walking skeleton + Describe (Stage 1, done)
- [ ] Launch app → grant the camera permission.
- [ ] Point the back camera at a scene, **single-press Volume-Down**.
- [ ] You hear a *"heard"* beep, then the phone **speaks** a description.
  *(Text is still the stub "a chair on your left and a doorway ahead" until the real VLM — ticket #1 — is wired.)*

### #2 — Gesture disambiguation
- [ ] **Single-press** → Describe (speaks).
- [ ] **Hold** (past ~0.4 s) → a processing tone at press, then the Query stub speaks on release.
- [ ] **Double-press** (two quick taps) → a "captured" tone (Save).
- [ ] Every press fires the instant *"heard"* beep first.
- [ ] Single-press still feels responsive (not laggy from double-tap disambiguation).

### #8 — Save to gallery
- [ ] **Double-press** → open the Photos/Gallery app → **Pictures/TactileSight** contains a new JPEG of the scene.
- [ ] A "captured" tone played.

### Scene memory (wired; fully testable once the real VLM lands, #1)
- [ ] After the real VLM: Describe scene A, move, Describe scene B, then Query "what was in the first room?" → the answer references scene A.
- [ ] Wait >10 min → the oldest scene is no longer recalled.

## What is intentionally NOT unit-tested (and why)

- **CameraX / TTS / ASR / MediaStore / NFC / the VLM runtime** — these are the OS/hardware
  boundary; they're exercised by the manual checklists, not unit tests. That's by design: the
  seams keep the *logic* testable, and the thin adapters over the platform are verified on the
  device.
</content>
