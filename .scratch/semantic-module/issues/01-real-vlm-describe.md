# Real VLM Describe (on-device tier)

Status: ready-for-agent

> **Now the on-device tier of a two-tier brain (2026-07-18).** This ticket = the **on-device** `GenieXBrain` (hero, privacy). The **cloud fallback** for non-capable phones is **#12**, and which tier runs is auto-routed by `selectBrain()` (see #12, `docs/adr/0008-cloud-fallback-tier.md`). **On-device model is OPEN** — Gemma 4 vs Qwen3-VL, decided on the 8 Elite.

## What to build

Replace `StubSemanticBrain` with the real on-device VLM so a single-press **Describe** speaks a real description of the actual scene, via **GenieX** (GGUF; NPU on 8-Elite, `llama_cpp` GPU/CPU on S22/S23). Model choice is open (Gemma 4 E4B / Qwen3-VL-4B — the 4B OOMs an 8 GB S23, so on 8 GB use the smaller variant or route to cloud per #12). The frame is the **on-demand single image from the band** over WebRTC — with a phone-camera fallback for dev/testing (see `docs/band-interface.md`). **Fuse the band's depth zones into the prompt** (`left/center/right = near/mid/far`) so the answer gives identity + proximity, e.g. *"person, left, close."* Prompt rules: identity + horizontal position, **never distance in metres**, terse (top 2–3, prominent first), hedge when unclear. Full detail: `docs/phone-module.md` §2–4.

## Acceptance criteria

- [ ] Single-press → the spoken description matches the real scene (not canned).
- [ ] Band depth zones are injected into the prompt and reflected in the answer (near/mid/far → "close/…"); works with **mock depth** when the band is absent.
- [ ] Positions are left/center/right only; no distance in metres.
- [ ] Unclear scene → "nothing clear ahead" (hedge), never a confident guess.
- [ ] Returns within budget (**measure latency on-device**) or times out to the spoken fallback.
- [ ] Lives behind the `SemanticBrain` seam — orchestrator unchanged; existing unit tests stay green.

## Blocked by

None — can start immediately. Verify on-device on **any Snapdragon 8-series phone** (S22/S23/S24); final demo on the 8 Elite Gen 5.
