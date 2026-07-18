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

**Languages:** demo path is **Punjabi + Hindi + English**, but **every language Sarvam can actually speak is exposed** in the picker — so a Tamil- or Telugu-speaking judge can switch and converse live. This is nearly free: the VLM always works in English, so **adding a language is a parameter, not an integration**.

> ### ⚠️ Measured against the live API, 2026-07-18 — read before quoting a language count
>
> The docs 404 and are JavaScript-rendered. Everything below came from probing the API with a deliberately invalid value and reading the validation error, which enumerates what is valid:
>
> ```bash
> curl -s -X POST https://api.sarvam.ai/text-to-speech \
>   -H "api-subscription-key: $KEY" -H "Content-Type: application/json" \
>   -d '{"text":"t","target_language_code":"zz-ZZ","model":"bulbul:v3"}'
> ```
>
> **1. TTS synthesises; it does not translate.** Sending English text with `target_language_code: pa-IN` returns English words in a Punjabi voice. The language is passed, the call succeeds, audio plays — and the user does not understand a word. Nothing in the logs looks wrong. The translate step this ADR specifies is **not optional**, and its absence is invisible.
>
> **2. Translate and speech have different coverage, and speech is the ceiling:**
>
> | stage | model | languages |
> |---|---|---|
> | translate | `mayura:v1` (the default) | 10 |
> | translate | **`sarvam-translate:v1`** | **22** |
> | speech | `bulbul:v3` | **11** |
>
> The 12 that translate but cannot be spoken (Assamese, Bodo, Dogri, Kashmiri, Konkani, Maithili, Manipuri, Nepali, Sanskrit, Santali, Sindhi, Urdu) fail with *"please request beta access"* — an **account** limit, not a model choice. **Offering a language we can translate but not speak is worse than not offering it**: it translates, then dies at the last step, and the user hears an error instead of their answer. The picker therefore lists only `Language.speakable`.
>
> **Speakable today (11):** English, हिन्दी, ਪੰਜਾਬੀ, বাংলা, ગુજરાતી, ಕನ್ನಡ, മലയാളം, मराठी, ଓଡ଼ିଆ, தமிழ், తెలుగు
>
> **3. Speakers are not shared between TTS models.** `bulbul:v2`'s `anushka` is rejected outright by `bulbul:v3`. Model and speaker move together — we run `bulbul:v3` + `ritu`.
>
> Requesting beta access for the remaining 12 is a support-ticket away and would need no code change beyond flipping `isSpeakable`.

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
- Sarvam credentials are a **secret** — never in the repo. ⚠️ **Reality differs from this and the difference is deliberate:** the Sarvam key *is* compiled into the APK (from `local.properties`), as is the Cirrascale key. On-device speech and the cloud tier both need to work without our laptop being up, and an MVP only the team installs is a different risk profile from a shipped app. **Both keys are extractable by anyone holding the APK and must be rotated after the event.** Do not ship this arrangement to real users.
