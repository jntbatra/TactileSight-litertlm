# AGENTS.md — TactileSight (phone-side)

Instructions for coding agents (OpenAI **Codex**, Cursor, etc.). Claude Code users have the same
workflow as the `/implement-tactilesight` skill; this file makes it work for any agent.

## What this repo is
The **phone half** of TactileSight — on-device, on-demand AI vision + multilingual voice for blind
users. Full context: [`README.md`](README.md) and [`docs/phone-module.md`](docs/phone-module.md).
**All design is done — do not redesign; read the docs, then build.**

## How to implement (follow this exactly)
The source-of-truth workflow is
[`.claude/skills/implement-tactilesight/SKILL.md`](.claude/skills/implement-tactilesight/SKILL.md).
Follow it. In short — **build one GitHub issue at a time, in dependency-graph order** (see the graph
in the README). Skip closed issues (#2, #7, #8 are done). Order, critical path first:

```
#1 (verify on-device) → #3 (ASR) → #4 (Query) → #6 (Multilingual)
#5 folds into #1.  #9 / #10 / #11 are stretch.
```

For **each** issue:
1. Read the ticket (`.scratch/semantic-module/issues/NN-*.md`) + the matching part of `docs/phone-module.md`.
2. **Test-first at the seams** (`FrameSource` / `SemanticBrain` / `SpeechIO` in `seam/`) — fakes in
   tests, real impl ships. Pure logic uses an injected clock (no hardware).
3. Typecheck + run tests (see **Build / test** below for the containerized command).
4. **Verify on-device** anything with a runtime (VLM via GenieX, ASR via IndicConformer) — install on
   the S22 / 8-Elite and drive the real flow. Compiling is NOT "done."
5. Review the diff, then **commit** referencing the issue (e.g. `Implement #3: hold-to-record + ASR`).
6. Close the issue only after on-device verification passes.

## Build / test

Builds run in Docker (AGP 8.5.2 needs **JDK 17** — a newer host JDK is rejected). Run from the repo
root, and always pass `--user` or the container writes `app/build/` as root and breaks the next build.

```bash
docker build -t tactilesight-android-build android/docker   # one-time

docker run --rm -v "$(pwd)/android:/workspace" -w /workspace \
  --user "$(id -u):$(id -g)" tactilesight-android-build ./gradlew test            # unit tests (16, all green)

docker run --rm -v "$(pwd)/android:/workspace" -w /workspace \
  --user "$(id -u):$(id -g)" tactilesight-android-build ./gradlew assembleDebug   # APK
```
With JDK 17 + the Android SDK on the host, `cd android && ./gradlew test` works directly.
See [`android/README.md`](android/README.md).

## Guardrails
- Two inference runtimes only (**GenieX** + **sherpa-onnx**). Don't add more without a design decision.
- Keep the **phone-camera + mock-telemetry fallback** working (demo insurance).
- Each ticket is a tracer bullet — the demo must stay runnable after every issue.
