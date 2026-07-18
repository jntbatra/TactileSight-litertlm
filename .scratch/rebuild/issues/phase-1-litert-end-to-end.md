# Phase 1 — LiteRT-LM end to end (the guaranteed prototype)

Status: ready-for-agent

## What to build

The **first working end-to-end path**: phone camera → LiteRT-LM (sideloaded multimodal Gemma) → English description → Sarvam translate → Sarvam TTS speaks it. This is the "at least one working prototype" commitment and is **unblocked by anything external** — start here.

See `docs/adr/0010-three-engine-on-device-inference.md` and `docs/adr/0012-speech-and-language-via-sarvam.md`.

- Fresh Kotlin app + the seams: **`SemanticBrain`**, **`FrameSource`**, **`SpeechIO`** (re-implemented from scratch — the old app is not being salvaged, team call).
- **Engine registry** + model **scan of the external files dir** (`…/files/models/<engine>/<model>/`) to populate the picker.
- **`LiteRtBrain`** — MediaPipe `LlmInference` pointed at the sideloaded `gemma4_2b_SM8850.litertlm`, with **image input**.
- **Model lifecycle rules from ADR-0010 are mandatory**: load once, stay resident, **lock-guarded**, release only on engine/model switch, one VLM resident at a time.
- **`SarvamSpeech`** implementing `SpeechIO`: Translate (EN→target) + TTS. Credentials from env/secure config — **never in the APK or repo**.

## Acceptance criteria

- [ ] Sideloaded Gemma loads via LiteRT-LM and describes a live camera frame in English.
- [ ] The description is translated and **spoken** in the selected language (Punjabi / Hindi / English).
- [ ] **Repeated Describes do not reload the model** (verify: no second load in logs; second response markedly faster).
- [ ] Rotating the device / recreating the Activity does **not** drop or reload the model.
- [ ] Switching model or engine **frees** the previous one (watch `MemAvailable` — no downward ratchet).
- [ ] **Verify whether LiteRT-LM actually reaches the NPU or runs on GPU** on SM8850, and record the finding — it decides how much ADR-0010's benchmark matters.

## Blocked by

Nothing. Requires the `.litertlm` pushed to the device and Sarvam credentials.
