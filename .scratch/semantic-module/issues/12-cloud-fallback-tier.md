# Cloud fallback tier (any-phone reach)

Status: ready-for-human

## What to build

A **second Semantic-brain tier** so phones that can't run the VLM on-device still work, behind the existing `SemanticBrain` seam. See `CONTEXT.md` ("Two-tier Semantic brain") and `docs/adr/0008-cloud-fallback-tier.md`.

- **`CloudBrain`** (phone): implements `SemanticBrain`; POSTs the Scene Frame (JPEG) + mode/question/language over HTTP to the server, maps the reply to an `Answer`, hedges on failure. *(Built 2026-07-18 — `CloudBrain` + `HttpCloudClient`, mock-tested green.)*
- **`server/`** (FastAPI): `POST /v1/describe` builds the prompt server-side and calls a **swappable `VlmBackend`** — `mock` | `openai`-compatible | future Cloud AI 100 (QEfficient). *(Built 2026-07-18.)*
- **Hackathon backend:** **Gemma 4 E4B** (GGUF + mmproj) on **llama.cpp + CUDA** on the team laptop (RTX 5060); `openai` backend points at it.
- **Auto-routing (to build):** replace `selectBrain()`'s `Qualcomm ? GenieX : Stub` with **capable → `GenieXBrain`, else → `CloudBrain`**; capable = GenieX chipset detect **AND** total RAM ≥ **12 GB** (provisional). Add **runtime fallback to cloud** if on-device load/OOMs. Retire `StubSemanticBrain` as the non-Qualcomm default. Picker stays as a **dev-only** override.
- **Spoken tier notice (to build):** say once at setup which tier is active — on-device "running on your device, private" / cloud "processing happens in the cloud." A notice, **not** a consent gate.

## Acceptance criteria

- [x] `CloudBrain` → server → real answer proven end-to-end (emulator, mock backend, 2026-07-18).
- [x] `openai` backend serves **real Gemma 4 E4B** descriptions from the laptop; verified on the emulator (auto-routed cloud → Describe → terse spoken answer).
- [x] `selectBrain()` auto-routes by capability (`BrainRouter`: chip + 12 GB floor); non-capable device lands on `CloudBrain`, `Stub` deleted. Verified: emulator self-routes to cloud, no manual pick.
- [x] On-device load failure / OOM **falls back to cloud** at runtime (`FallbackBrain`, unit-tested incl. the OOM path; announces the switch so the "private" promise isn't broken). *Runtime path on real Qualcomm hardware pending the 8 Elite.*
- [x] Setup speaks the active tier once; blind-operable (spoken, no visual dependency).
- [x] Orchestrator + existing unit tests unchanged/green; `BrainRouterTest` + `FallbackBrainTest` + `CloudBrainTest` cover it.

**Left for the venue (needs the 8 Elite):** verify the on-device tier itself (GenieX loads on the 8 Elite; the fallback only triggers on failure), tune the 12 GB floor, and the venue networking (phone → laptop over hall WiFi; server has no auth). See ADR-0008 consequences.

## Framing / honesty

Cloud backend is **a team laptop, not Cloud AI 100** — pitch as "on-device on Snapdragon (hero), cloud fallback on a server." The free Cirrascale Cloud AI 100 Playground has **no vision model** (text + image-gen only), so it can't power this; a real Cloud AI 100 VLM is a one-line backend swap.

## Blocked by

None. Related: #01 (on-device Describe = the *other* tier). Venue networking (phone → laptop over hall WiFi) is a **demo risk to rehearse**; server has **no auth** yet.
