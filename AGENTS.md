# AGENTS.md — TactileSight

Instructions for coding agents (OpenAI **Codex**, Cursor, etc.). Claude Code follows the same
workflow via the `/implement-tactilesight` skill; this file makes it work for any agent.

This file is deliberately thin — it points at the real documents rather than copying them, because
duplicated instructions drift apart and we have already lost time to exactly that.

## What this repo is

TactileSight: a haptic band that answers **where** and a phone module that answers **what**, for
blind and visually-impaired users. Product overview: [`README.md`](README.md).

The Android app is being **rebuilt from scratch** (decided 2026-07-18) around three interchangeable
on-device inference engines. `android/` does not exist yet — issue #1 creates it.

## Read before building

1. [`CONTEXT.md`](CONTEXT.md) — **start at the `REBUILD — settled design` section.** It takes
   precedence over everything above it in that file.
2. [`docs/adr/0009`–`0013`](docs/adr/) — the current decisions. Earlier ADRs are partly superseded;
   each one carries a header note saying so.
3. [`TEAM.md`](TEAM.md) — build setup, the frozen server contract, and the **hard rules**. The hard
   rules are invariants; read them before writing code, not after.

**The design is settled — do not redesign. Read, then build.**

## How to work

Tickets are **GitHub Issues** on this repo (`gh issue list`). Each has a **Blocked by** section.
Work the **frontier**: any open issue whose blockers are all closed. Do not assume a linear order —
read the blocking edges.

For each issue:

1. Read the issue and the ADRs covering the area you're touching.
2. **Test-first at the seams.** The primary seam is `SemanticBrain` (one interface, three engines).
   Fakes in tests, real impl ships. Pure logic — gesture recognition, depth fusion, distance
   percentile — is unit-tested with injected inputs, no hardware.
3. Typecheck and run the affected tests often; the full suite once at the end.
4. **Verify on-device** anything with a runtime surface. Compiling is NOT "done".
5. Review the diff, then **commit** referencing the issue.
6. Close the issue only after on-device verification passes.

## Build / test

```bash
cd android && ./gradlew test          # JDK 21 required — see TEAM.md
cd server  && TS_VLM_BACKEND=mock python -m pytest
```

**JDK 25 will not work** — Gradle's Kotlin compiler fails parsing the version. Use Android Studio's
bundled JBR (21) via `org.gradle.java.home` in `local.properties`. There is no Docker build any more;
the old containerized flow belonged to the deleted app.

## Guardrails

- The **hard rules in [`TEAM.md`](TEAM.md)** are binding. Chief among them: load the model once and
  keep it; never state a distance you didn't measure; every press yields speech.
- Three VLM engines only (LiteRT-LM, GenieX, ExecuTorch+QNN). Adding a fourth needs an ADR —
  ONNX Runtime + QNN EP was already considered and rejected for this role (ADR-0010).
- The prompt lives server-side in `server/prompt.py`. Never write a second one.
- Secrets go in env/secure config. **This repo is public.**
- Each ticket is a tracer bullet — the demo must stay runnable after every issue.
