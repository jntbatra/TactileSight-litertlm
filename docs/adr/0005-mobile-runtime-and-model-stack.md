# Mobile module: Qualcomm AI Hub (NPU) for perception, llama.cpp for the Narrator

> **⚠️ SUPERSEDED AGAIN 2026-07-18 by [ADR-0010](0010-three-engine-on-device-inference.md).** The single-GenieX runtime below is replaced by **three interchangeable engines** (LiteRT-LM, GenieX, ExecuTorch+QNN) behind one `SemanticBrain` seam, because which of them actually reaches the Hexagon NPU is an open empirical question. Speech is no longer AI4Bharat/Whisper on QAIRT but **Sarvam cloud** ([ADR-0012](0012-speech-and-language-via-sarvam.md)). The test device is the **8 Elite (SM8850)**, not the S22. The 2026-07-11 note below is itself historical.

> **⚠️ SUPERSEDED 2026-07-11 — see `CONTEXT.md` → "Runtime & resources" + "Model sourcing".**
> Reworked around a **single VLM** (ADR-0003 superseded):
> - **VLM (Qwen3-VL-4B)** runs on **GenieX**, backend **by device** (spike-verified 2026-07-11): the AI Hub NPU (QAIRT) bundle is **8-Elite-only**, so on **8 Gen 1/2 the `llama_cpp` GGUF backend (GPU/CPU) is primary** (no NPU accel for the VLM there); the **`qairt` NPU backend is the 8-Elite-only upgrade**. Direct `llama-mtmd-cli` is the drop-in fallback; use **Qwen3-VL-2B** if 4B is tight on 8 GB. Test device: **Galaxy S22 (8 Gen 1, 8 GB)**.
> - **OCR (PaddleOCR)** and **English/fallback Whisper** run on **QAIRT/QNN** directly (not GenieX).
> - **Multilingual speech (off-Qualcomm, CPU):** ASR = **AI4Bharat IndicConformer** via sherpa-onnx (per-language monolingual, 16 kHz mono); TTS = **Android on-device** (offline voices — **AI4Bharat neural TTS rejected, too slow**); **Android STT rejected** (cloud → privacy). **Language = user setting / device locale, not audio auto-detect** (on-device spoken-language ID is unsolved). Upgrade = **Sarvam Edge** / Piper-sherpa-onnx, behind the `SpeechIO` seam.
> - All models **resident, VLM kept warm**; thermal covered by the on-demand duty cycle + NPU efficiency.
> - Note: AI Hub has STT (Whisper) but **no TTS** — speech-out is necessarily off-hub.
> Original decision preserved below for history.

Target device is an assumed flagship Snapdragon (8 Gen 2/3, provided by Qualcomm — exact chip + sideload/NPU access to be confirmed day-one). The module runs a hybrid runtime so no single setup risk can block the demo:

- **Perception on the Hexagon NPU via Qualcomm AI Hub / QNN** — the object detector (Qualcomm AI Hub's **precompiled YOLO**, e.g. YOLOv8/YOLOv11 Detection), OCR, and STT (Whisper) use AI-Hub-optimised builds downloaded ready-to-run. No model is trained or built — pretrained, pre-optimised weights only. This showcases the Snapdragon NPU (a scoring criterion at a Qualcomm hackathon). Chosen AI-Hub-only (no ML Kit primary) for the strongest NPU story; **Google ML Kit is retained only as an emergency escape hatch** (drop-in Object Detection + Text Recognition, ~1h to wire) if AI Hub integration blocks the demo.
- **Narrator LLM via llama.cpp (GGUF), 1–3B** (e.g. Llama 3.2 3B or Phi-3.5-mini) — the guaranteed-working path on an 8 Gen 2/3. Porting the LLM onto the NPU (Genie/QNN) is a stretch, done only if time remains.
- **TTS = Android's built-in offline engine** — no model work, on-device.

All inference is on-device (privacy). We rejected pure-NPU-everything (risk of burning the 24h on LLM conversion) and pure-CPU/llama.cpp-everything (leaves the NPU idle, weak Snapdragon story). Fallback if the provided device blocks NPU access: ONNX Runtime / NNAPI or CPU for perception too. All models must be pre-converted before the event — AI Hub compilation has a learning curve.
