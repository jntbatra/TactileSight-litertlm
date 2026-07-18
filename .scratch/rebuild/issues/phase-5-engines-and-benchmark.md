# Phase 5 — GenieX + ExecuTorch engines, and the NPU benchmark

Status: ready-for-human

## What to build

Bring the other two engines online behind the same `SemanticBrain` seam and **answer the empirical question**: which runtime actually reaches the Hexagon NPU on SM8850, and which describes scenes best? See `docs/adr/0010-three-engine-on-device-inference.md`.

Deliberately **sequenced last** — it is engineering exploration, so if time runs out this is what gets cut, not a product feature.

- **`GenieXBrain`** — GenieX SDK (`com.qualcomm.qti:geniex-android`), **downloads** its own models. Two configs worth measuring: `llama_cpp` (GGUF, GPU — the known-working baseline) and **`qairt` + `HubSource.AIHUB`** (the true NPU path; both enum values exist in the SDK, but the AI Hub bundle's availability is unproven).
- **`ExecuTorchBrain`** — ExecuTorch runtime + **QNN delegate**, loading the **sideloaded** `.pte` bundles: **InternVL3-1B** (VLM, primary) and **SmolVLM-500M** (fast smoke test). Reference: `qualcomm/ai-hub-apps`.
- **Engine picker** already exists from Phase 1 — these slot in.

## Benchmark to produce

For each engine/model, on the **same bundled test scenes** so results are comparable:
- **NPU vs GPU** — which accelerator is actually used (this is the headline question)
- **prefill / decode tok/s**, **time to first token**, total latency
- **peak RAM**
- **description quality** on the 21 scenes (subjective but recorded)

Baselines already measured on the old GenieX/GPU stack: Gemma-4-E4B **71 tok/s prefill / 11 decode**; Qwen3-VL-4B **20 / 13**.

## Acceptance criteria

- [ ] All three engines run the same scene and produce a description.
- [ ] **NPU-vs-GPU determined and recorded per engine** — the reason this phase exists.
- [ ] Switching engines **frees the previous model** (no memory ratchet — the failure that OOM-killed the old app six times).
- [ ] A written comparison lands in the repo, and the demo default is chosen from evidence.

## Blocked by

Phases 1–3. ExecuTorch `.pte` models must be sideloaded; GenieX needs network for its pull. **InternVL3-1B is our only guaranteed-NPU VLM** — if LiteRT and GenieX both turn out GPU-only, it carries the "runs on Hexagon" claim.
