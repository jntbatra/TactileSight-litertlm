---
name: implement-tactilesight
description: "Implement the TactileSight phone-side module one GitHub issue at a time, in dependency-graph order."
disable-model-invocation: true
---

Implement the TactileSight **phone-side** module (this repo) one GitHub issue at a time.

All design work is already done — do NOT redesign. Read, then build.

## Before touching code
- Read the design: `docs/phone-module.md` (what each feature is + the locked decisions),
  `docs/band-interface.md` (the band↔phone contract), and the ticket in
  `.scratch/semantic-module/issues/NN-*.md`.
- Pick the **next open issue in dependency-graph order** (see the README graph). Skip closed ones.
  Order (done ones in parens):
  ```
  Level 0:  #1 verify-on-device  ·  (#2 ✅)  ·  #9  ·  #11
  Level 1:  #3 ASR  ·  #5 (folds into #1)  ·  (#8 ✅)  ·  #10
  Level 2:  #4 Query
  Level 3:  #6 Multilingual  ·  (#7 ✅)
  ```
  Critical path first: **#1 → #3 → #4 → #6**.

## For each issue
1. **TDD at the seams** (`/tdd`). The seams are `FrameSource`, `SemanticBrain`, `SpeechIO`
   (in `seam/`). Put fakes in tests; the real impl ships. Pure logic (`GestureRecognizer`,
   `SceneMemory`, prompt/fusion building) is unit-tested with an injected clock — no hardware.
2. **Typecheck + run the affected tests often.** Builds go through Docker (AGP 8.5.2 requires
   JDK 17; a newer host JDK is rejected). From the repo root — the `--user` flag is required, or
   the container writes `app/build/` as root and the next build fails:
   ```bash
   docker run --rm -v "$(pwd)/android:/workspace" -w /workspace \
     --user "$(id -u):$(id -g)" tactilesight-android-build ./gradlew test
   ```
   (`cd android && ./gradlew test` works if the host has JDK 17 + the Android SDK.)
   Baseline is **16 tests, all green** — keep them that way. Run the full suite once at the end.
3. **Verify on-device** anything with a runtime surface (VLM via GenieX, ASR via IndicConformer):
   install on the S22 / 8-Elite, drive the real flow, observe the output. Code compiling is NOT done.
4. **`/code-review`** the change.
5. **Commit** to the current branch referencing the issue (e.g. `Implement #3: hold-to-record + ASR`).
6. **Close the issue** only after on-device verification passes (for runtime features) — otherwise
   leave it open with a status comment.

## Guardrails
- Each ticket is a **tracer bullet**: independently shippable end-to-end. Keep the demo runnable
  after every issue.
- Two runtimes only (GenieX + sherpa-onnx) — don't add more without a design decision.
- Keep the phone-camera + mock-telemetry fallback working (demo insurance).
