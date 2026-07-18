# Multilingual (device-locale)

Status: ready-for-agent

## What to build

Language = a **user setting defaulting to the device locale** (no audio auto-detect). The chosen language selects the per-language IndicConformer ASR model, the Android TTS locale, and instructs the VLM to answer in that language. Target set: **to confirm** (placeholder Hindi/Tamil/Telugu/Bengali/Marathi).

## Acceptance criteria

- [ ] Set the phone to a supported Indian language → ask in that language → hear the answer in that language.
- [ ] Switching language reloads the matching ASR model.
- [ ] Everything on-device (Android STT not used).

## Blocked by

- Query answered (VLM)
