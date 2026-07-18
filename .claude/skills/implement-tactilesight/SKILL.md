---
name: implement-tactilesight
description: "Implement TactileSight one GitHub issue at a time, working the dependency frontier."
disable-model-invocation: true
---

Implement TactileSight one **GitHub issue** at a time.

The design is settled — do NOT redesign. Read, then build.

## Before touching code

Read, in this order:

- `CONTEXT.md` — **start at the `REBUILD — settled design` section**; it takes precedence over
  everything above it.
- `docs/adr/0009`–`0013` — the current decisions (band pipeline, three engines, interaction model,
  speech, distance). Earlier ADRs are partly superseded; each carries a header note.
- `TEAM.md` — build setup, the frozen server contract, and the hard rules.
- The issue itself, on **GitHub Issues** (`gh issue view <n>`). Tickets are no longer local files;
  `.scratch/` holds PRDs, spikes and research notes only.

Pick the next issue on the **frontier** — any open issue whose "Blocked by" list is fully closed.
`gh issue list` and read the blocking edges; do not assume a linear order.

## For each issue

1. **TDD at the seams** (`/tdd`). The primary seam is `SemanticBrain` — one interface, three engine
   implementations (LiteRT-LM, GenieX, ExecuTorch+QNN). Frame capture and speech are seams too.
   Fakes in tests, real impl ships. Pure logic (gesture recognition, depth fusion, distance
   percentile) is unit-tested with injected inputs — no hardware, no model.
2. **Typecheck + run the affected tests often**; full suite once at the end.
   ```bash
   cd android && ./gradlew test          # needs JDK 21 — see TEAM.md, JDK 25 fails
   cd server  && TS_VLM_BACKEND=mock python -m pytest
   ```
   There is no baseline test count — the app is being rebuilt. Add tests as you add behaviour.
3. **Verify on-device** anything with a runtime surface (any VLM engine, the detector, speech).
   Install on the 8 Elite and drive the real flow. Compiling is NOT "done".
4. **`/code-review`** the change.
5. **Commit** to the current branch referencing the issue (e.g. `Implement #3: bundled test scenes`).
6. **Close the issue** only after on-device verification passes; otherwise leave it open with a
   status comment saying what remains.

## Guardrails

These are invariants from the ADRs, not preferences — breaking them reintroduces paid-for bugs.

- **Load the model once and keep it.** Lock-guarded; release only on engine/model switch, never on
  Activity recreation. One VLM resident at a time. (ADR-0010)
- **Never state a distance you didn't measure.** Robust percentile of valid depth pixels, minimum
  valid-pixel threshold, else "distance unknown". (ADR-0013)
- **The VLM never states distance** — it cannot measure. The phone appends distance from depth.
- **Every press yields speech.** No dead press, ever.
- **No double-press gesture**; three buttons × tap/hold is the whole vocabulary. (ADR-0011)
- **Speech only after the mic closes**, or TTS feeds back into ASR.
- **The prompt lives server-side** in `server/prompt.py`. Never write a second one.
- **Secrets in env/secure config.** This repo is public.
- Each ticket is a **tracer bullet**: independently demoable. Keep the demo runnable after every one.
