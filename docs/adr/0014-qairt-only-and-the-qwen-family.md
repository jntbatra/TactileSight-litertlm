# QAIRT only — which means the Qwen family, and nothing else

> **Decided 2026-07-19 (day 2, ~00:40).** Supersedes ADR-0010's three-engine plan. The engine picker built there did its job — it answered the empirical question ADR-0010 was written to ask — and this ADR retires it on the strength of that answer.

## Context

ADR-0010 said the choice between LiteRT-LM, GenieX and ExecuTorch could not be made from documentation: *"nobody can currently say which of LiteRT/GenieX/ExecuTorch actually reaches the NPU on this chip. That's the empirical question, hence three engines."*

It has now been measured on the demo device (OnePlus 15, SM8850 = 8 Elite Gen 5):

| Path | TTFT | Decode | Load |
|---|---|---|---|
| **GenieX `qairt` / NPU** | **260 ms** | **1287 tok/s** | 7.2 s |
| GenieX `llama_cpp` / NPU | 2688 ms | 142 tok/s | 29.2 s |
| GenieX `llama_cpp` / GPU | 3417 ms | 93 tok/s | 29.0 s |
| GenieX `llama_cpp` / HYBRID | 4087 ms | 94 tok/s | 31.2 s |

Two findings matter more than the ranking:

1. **`llama_cpp` genuinely reaches Hexagon** — this was open until #16. It is not a GPU path mislabelled. It is simply 10× slower than QAIRT at the same target, because ggml-Hexagon dispatches op-by-op while QAIRT runs a graph compiled ahead of time for this exact chipset.
2. **The gap is not tunable.** 260 ms vs 2688 ms is a runtime-architecture difference, not a configuration one. No amount of layer-offload tuning closes it.

## Decision

**Ship the QAIRT/NPU path only.** The GGUF paths are removed from the picker and retained as an automatic fallback when no QAIRT bundle is staged, so a device without one still speaks (hard rule #4) and reports which engine answered.

**This has a consequence that was not obvious until we checked: QAIRT restricts us to the Qwen VLM family.**

QAIRT bundles are not a format we can convert into — they are graphs Qualcomm compiled per chipset and published. So the question "can model X run on QAIRT" is answered by AI Hub's manifest for X, not by X's architecture. Checked on 2026-07-19:

| Model | Published QAIRT bundle for 8-elite-gen5? |
|---|---|
| Qwen3-VL-4B-Instruct | ✅ `geniex_qairt` w4a16, 2.9 GB zip — **running today** |
| Qwen3-VL-8B-Instruct | ✅ `geniex_qairt` w4a16, 5.0 GB zip |
| Qwen2.5-VL-7B-Instruct | ✅ `geniex_qairt` w4a16, 4.7 GB zip |
| **Gemma-4-E4B / E2B** | ❌ **none — `geniex_llamacpp` q4_0 only** |

`qualcomm/Gemma-4-E4B-it`'s `release_assets.json` is four lines long: one precision (`q4_0`), one runtime (`geniex_llamacpp`), no `chipset_assets` key at all. **Qualcomm never compiled a QAIRT Gemma-4.** Our `gemma4-qat-q4_0` staging *is* that GGUF, so its 2688 ms is the best it can structurally do — it was never a QAIRT candidate that needed tuning.

The other staged models are also not QAIRT, for separate reasons:

- `gemma4_2b_SM8850.litertlm` — LiteRT-LM format, a different runtime. Also text-only (ADR-0010's correction).
- `SM8850_precompiled/` (SmolVLM-500M, InternVL3-1B, Qwen3-1.7B) — `.pte` + QNN delegate. These **do** reach the Hexagon NPU, but through **ExecuTorch**, which we no longer carry a runner for. ADR-0010 called InternVL3 "our only guaranteed-NPU VLM"; QAIRT overtook it and is 5× faster.

**Model choice: Qwen3-VL-8B-Instruct, pending a load test.** Same architecture family as the working 4B, so `geniex::qwen3_vl::makeModel` in GenieX 0.3.12 already handles it and the swap is a bundle path, not a code change. 2× the parameters should help the two things the demo is judged on: sign reading and not inventing detail.

**This part is provisional and must not be treated as settled.** ~6.8 GB unpacked against 9.8 GB `MemAvailable` measured at the time of writing. We have already been OOM-killed once at 3.5 GB available. **The 4B bundle stays on disk until the 8B is proven describing on device.**

## Rationale / alternatives considered

- **Keep the picker as a demo of breadth.** Rejected. A picker entry that is 13× slower is a way to make the demo worse by accident — one wrong tap in front of a judge and the headline number is gone. Breadth belongs in the README, where it is a measurement, not a live hazard.
- **Qwen2.5-VL-7B instead of 8B.** Viable — 300 MB smaller, and `qwen2_5_vl` is the factory GenieX 0.3.1 shipped. Rejected as the primary because it is a generation behind the model we have already validated end-to-end; kept as the fallback if 8B will not fit.
- **Convert Gemma-4 to QAIRT ourselves.** Not attempted, and not attemptable on day 2 — AI Hub compilation is a submit-and-wait pipeline, and a failed export would cost hours we do not have.
- **Keep ExecuTorch for InternVL3.** Rejected. It was insurance against "nothing reaches the NPU". QAIRT reaches the NPU at 260 ms; the insurance is no longer worth a second runtime integration.

## Consequences

- **Our model choice is bounded by Qualcomm's publication schedule.** If a better VLM appears without a QAIRT bundle, we cannot use it on-device — it goes to the private-server tier instead. Check `release_assets.json` before getting attached to any model.
- **The engine-comparison work is not wasted, but it is now history.** The numbers live in the README; the code paths do not. #16 is closed by this ADR, not reopened by it.
- **~11.7 GB of staged models have no path into an NPU-only app** (`gemma4-qat-q4_0`, `gemma4_2b_SM8850.litertlm`, `SM8850_precompiled/`). They are deletable. They are also re-downloadable only over a venue network, so the deletion happens when the team says so, not automatically.
- **RAM is now the binding constraint on model choice**, where it used to be format compatibility. Every future upgrade question is "does it fit", and the answer must be measured on the device, not estimated from the file size.
