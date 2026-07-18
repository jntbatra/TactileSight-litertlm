# Three-engine on-device inference: LiteRT-LM, GenieX, ExecuTorch

> **Decided 2026-07-18 (grilled) — see `CONTEXT.md` ("Inference engine").** Supersedes ADR-0005's single GenieX runtime and ADR-0008's *on-device tier* implementation (the two-tier cloud fallback itself survives — see ADR-0008 and Phase 4). The app is rebuilt from scratch in a new repo; the `SemanticBrain` seam is re-implemented, not inherited.

## Context

The GenieX-only stack worked but topped out on the **GPU** (`llama_cpp`, GGUF): measured on the 8 Elite (SM8850) at **71 tok/s prefill / 11 tok/s decode** for Gemma-4-E4B and **20/13** for Qwen3-VL-4B — usable, not snappy, and **not the NPU**. Meanwhile we have, already compiled for SM8850 and sitting on disk:

- `gemma4_2b_SM8850.litertlm` (2.6 GB) — **LiteRT-LM** format, multimodal (tokenizer carries `<|image|>` **and** `<|audio|>`, Gemma-3n-E2B class).
- `SM8850_precompiled/` — **ExecuTorch** `.pte` + QNN (header `ET12`): **InternVL3-1B** (VLM, has `vision_encoder_qnn.pte`), **SmolVLM-500M** (VLM), Qwen3-1.7B (text-only, not useful here).

These are **three different runtimes** — GenieX eats GGUF / AI-Hub QAIRT bundles; LiteRT-LM eats `.litertlm`; ExecuTorch eats `.pte`. None loads another's format.

## Decision

Support **all three engines behind one `SemanticBrain` seam**, **user-selectable**, so we can benchmark which actually reaches the Hexagon NPU and which describes scenes best.

| Engine | Model(s) | Delivery | NPU status |
|---|---|---|---|
| **LiteRT-LM** | Gemma-2b `.litertlm` (multimodal) | **sideload** | GPU by default; NPU newer/unverified |
| **GenieX** | GGUF (Qwen3-VL / Gemma) or AI-Hub QAIRT bundle | **downloads** (its own ModelManager) | GPU via `llama_cpp`; NPU via `qairt` |
| **ExecuTorch** | InternVL3-1B, SmolVLM-500M `.pte` | **sideload** | **QNN delegate — definitely NPU** |

**Delivery:** GenieX keeps its internet pull; LiteRT-LM and ExecuTorch models are **sideloaded** to the app's **external files dir** (`…/files/models/<engine>/<model>/`) and the app **scans that directory at startup** to populate the picker. Adding a model = pushing a folder. (Venue downloads proved unusable: a 6 GB pull swung between 0.3 and 15 MB/s and the process was OOM-killed six times.) **Test scenes ship in the APK** (small).

**Model lifecycle — hard rules** (these are the bugs that cost us a morning):
- **Load once, stay resident.** If the model is loaded, never reload — a hot reload is 30–60 s.
- **Lock-guarded load**, so concurrent requests can't trigger two loads.
- **Release only on engine/model switch** (`close()`), never on Activity recreation. The old app dropped its model on every rotation, leaked the native allocation, drove `MemAvailable` to ~1 GB and got OOM-killed repeatedly.
- **Exactly one VLM resident at a time.**

**RAM budget** (VLM + detector + app): SmolVLM-500M ≈ 1.7 GB · InternVL3-1B ≈ 2.5–3 GB · LiteRT Gemma-2b ≈ 3.7–4.2 GB · GenieX Qwen-4B ≈ 4 GB · GenieX Gemma-4-E4B ≈ 6.7 GB. All fit the 16 GB device; the discipline is **release-on-switch**, not fit-them-all.

## Rationale / alternatives considered

- **Stay GenieX-only + AI-Hub QAIRT bundle:** cheapest change (`HubSource.AIHUB` + `runtime_id="qairt"` — both already present in the SDK we ship), but the bundle's availability is unproven ("not supported on any Mobile chipset" on the AI Hub page) and it must be downloaded at the venue. Kept as the GenieX NPU path, not relied on.
- **Pick one runtime up front:** we can't — nobody can currently say which of LiteRT/GenieX/ExecuTorch actually reaches the NPU on this chip. That's the empirical question, hence three engines.
- **ONNX Runtime + QNN EP as a fourth VLM engine:** **rejected as a VLM engine, adopted elsewhere.** QNN EP is mature for **CNN** graphs but a poor fit for multimodal LLM graphs (dynamic shapes, KV cache, vision encoder + decoder) — which is exactly why the precompiled VLMs Qualcomm ships for this chip are `.pte` and `.litertlm`, not ONNX. Its real homes are (a) the **YOLOv11 detector** in Phase 2 — AI Hub publishes it in both TFLite and ONNX/QNN, so either runtime may carry it to the NPU — and (b) the **X Elite server** (teammate scope), where ORT-QNN would put the *cloud fallback tier* on Qualcomm silicon too, at zero cost to the app. Three VLM engines is already ambitious; a fourth would cost the most and yield the least.
- **Salvage the old app's seams** (explicitly considered and **rejected by the team**): the old `SemanticBrain`/`FrameSource`/orchestrator/tests were runtime-agnostic and would have made this a brain-only swap. The team chose a genuine from-scratch rebuild; the cost (re-implementing camera/gesture/speech/tests) is accepted.

## Consequences

- Three runtime integrations to build and keep alive; the **benchmark is sequenced last (Phase 5)** so it can be cut without sinking the demo.
- The engine picker is a **dev/demo control**, not a blind-user surface.
- Models are **not in the APK** — a fresh device needs an `adb push` step; document it in the runbook or the demo dies on a wiped phone.
- ExecuTorch's InternVL3-1B is our only **guaranteed-NPU** VLM; if LiteRT and GenieX both turn out GPU-only, it carries the "runs on Hexagon" claim.
