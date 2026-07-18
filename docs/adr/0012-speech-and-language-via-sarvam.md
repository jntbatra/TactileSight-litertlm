# Speech & language: VLM in English, Sarvam cloud for translate / TTS / ASR

> **Decided 2026-07-18 (grilled) — see `CONTEXT.md` ("Speech & language").** Supersedes the on-device speech stack (AI4Bharat IndicConformer + Android TTS) and the "VLM answers in the chosen language" rule from ADR-0005.

## Context

The old stack kept speech **on-device** for privacy: AI4Bharat IndicConformer (ASR, CPU) + Android on-device TTS, with **Sarvam Edge** as a private upgrade pending SDK access. Android STT was explicitly **rejected because it is cloud**.

Two things changed:
1. Our on-device VLMs are now **small** (Gemma-2b, InternVL3-1B, SmolVLM-500M). Small VLMs are **markedly weak in Indic languages** — forcing Hindi/Punjabi/Tamil out of a 1–2B model produces clumsy or wrong output.
2. The team has **Sarvam cloud API** access now; Sarvam Edge (on-device) is still gated on an SDK we do not have.

## Decision

**Each component does what it is good at:**

1. **VLM describes in English** — its strongest language; terse and accurate.
2. **Sarvam Translate** → the user's language.
3. **Sarvam TTS** speaks it.
4. **Query:** **Sarvam ASR** transcribes the spoken question → English → VLM.

**Languages:** demo path is **Punjabi + Hindi + English**, but **every Sarvam-supported language is exposed** in the picker — so a Tamil- or Telugu-speaking judge can switch and converse live. This is nearly free: the VLM always works in English, so **adding a language is a parameter, not an integration**.

**Sarvam TTS speaks both lanes** (fast spatial *and* rich description) for voice consistency. The fast lane's vocabulary is tiny and templated (`"<object>, <N> metres, <direction>"`), so common tokens are **pre-synthesised and cached** per language — repeat taps are effectively instant and survive a network blip.

**No offline TTS fallback in the MVP** (explicit team call). Accepted risk, recorded below.

## Rationale / alternatives considered

- **VLM answers directly in the target language:** saves a hop and works without network, but at 1–2B parameters the Indic output quality is visibly poor. Rejected.
- **Sarvam Edge (on-device):** preserves the full privacy story and is the eventual right answer, but the SDK is not in hand. Kept as the documented upgrade behind the `SpeechIO` seam.
- **Android on-device TTS as an automatic fallback** when Sarvam is unreachable: **proposed and rejected for the MVP.** See the risk below.

## Consequences

- **The camera imagery still never leaves the device** — the VLM is local. Only **speech** (the user's voice in, the answer text out) touches Sarvam. The core privacy claim survives; the pitch must state the distinction honestly: *"vision is 100% on-device; speech uses a cloud API."*
- **Accepted risk — the network is a single point of failure for *all* speech.** With no offline fallback, losing connectivity leaves the app **silent, not degraded** — including the safety-relevant fast lane. The venue network already proved unreliable (0.3–15 MB/s, repeated drops). Mitigations available if it bites: the band's own AP / phone hotspot, and adding Android TTS as a fallback behind the `SpeechIO` seam (a small change, deliberately deferred).
- Latency gains ~0.3 s (translate) + ~0.5 s (TTS) per utterance on top of VLM time; the two-stage speaking in ADR-0011 is what keeps this from feeling dead.
- Sarvam credentials are a **secret** — server-side/env only, never in the APK or the repo.
