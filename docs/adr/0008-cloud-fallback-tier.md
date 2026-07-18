# Two-tier Semantic brain: on-device hero + cloud fallback

> **Decided 2026-07-18 — see `CONTEXT.md` ("Two-tier Semantic brain").** Extends, does not replace, the on-device premise: on-device stays the default and the hero; cloud is a fallback for devices that can't run the VLM locally.

## Context

The Semantic brain (ADR-0003, ADR-0005) was specified as **on-device throughout** — the VLM runs on the phone (GenieX: NPU on 8-Elite-class, llama.cpp GPU/CPU elsewhere) and no camera frame leaves the device. That premise is the product's privacy differentiator (and the thing that beats the SixthSense competitor). But it also means the app only works on capable Snapdragon phones: a non-Qualcomm or low-RAM device gets nothing. Our own testing bears out the limit — an 8 GB Galaxy S23 (8 Gen 2) OOM-killed Qwen3-VL-4B and hung even on the 2B via the llama.cpp GPU path.

We want "every phone can run TactileSight," without giving up "on-device on the phones that can."

## Decision

Run the brain in **two tiers behind the existing `SemanticBrain` seam**, chosen **automatically per device**:

- **On-device tier (hero, privacy-first):** `GenieXBrain`. The default whenever the device can run it. No frame leaves the device.
- **Cloud fallback tier (reach):** `CloudBrain` → HTTP POST (JPEG + mode/question/language) → a server that runs the VLM and returns the spoken answer. For devices that can't run the VLM locally.

**Routing** is capability-driven and invisible to the (blind) user:
- On-device-eligible = **GenieX chipset detection** (`detectChipset`/`listChipsets`) reports a supported chip **AND** total RAM ≥ a **floor (provisional 12 GB, tune on the 8 Elite)**.
- **Runtime fallback:** if the on-device model fails to load or OOMs, drop to cloud so a wrong guess never bricks the app.
- The visual model-picker is a **dev/demo override only** — never the user's path.

**Transparency:** the app **says once at setup** which tier is active — on-device → "running on your device, private"; cloud → "processing happens in the cloud." This is a **notice, not a consent gate** (explicit team call, 2026-07-18). Residual risk acknowledged: a frame from a camera the user can't see is sensitive; upgrading notice → consent is a small change if ever warranted.

**Server backend** is swappable behind one interface: `mock` (wire test) | `openai`-compatible (any local/hosted VLM) | future **Cloud AI 100** via QEfficient. For the hackathon the cloud tier is served by **Gemma 4 E4B** (GGUF + mmproj, vision-capable) on **llama.cpp with CUDA** on a **team laptop (RTX 5060)**.

## Rationale / alternatives considered

- **Qualcomm Cloud AI 100 (the on-brand choice):** evaluated the free **Cirrascale Cloud AI 100 Developer Playground** (2026-07-18). Its Imagine API exposes **only text models (Llama-3.1-8B) and SDXL image-gen — no vision-language model** — so it cannot describe a scene. A raw DL2q VM + QEfficient (which *does* support VLMs like Qwen2.5-VL 3B, Gemma 3 4B, Llama-3.2-Vision) remains a future option, but is paid and not needed for the demo. A real Cloud AI 100 VLM is a **one-line backend swap** if we get one.
- **A team laptop as the cloud backend:** chosen because the **hero is on-device on the 8 Elite** — the cloud tier only needs to *work* and prove "any phone can run it." A laptop we control beats a flaky free tier (the Playground returned "models busy/unavailable"). **Honest framing:** *on-device on Snapdragon (hero), cloud fallback on a server* — **we do not claim the cloud tier runs on Cloud AI 100.**
- **Generic hosted APIs (e.g. Bedrock):** possible via the swappable backend, but a paid, non-Qualcomm dependency that drifts from the story; kept only as an emergency fallback.

## Consequences

- The "nothing leaves the device" claim now holds **for capable devices**; on the cloud tier the frame goes to a server, disclosed at setup. CONTEXT.md's premise updated from "on-device throughout" to "on-device first, cloud fallback with notice."
- New moving parts: the `server/` VLM service, cleartext HTTP for the LAN dev/demo server, and venue networking (phone → laptop over the hall WiFi) as a **demo risk to rehearse** (LAN IP config, AP isolation, no server auth yet).
- On-device model is left **open** (Gemma 4 vs Qwen3-VL) pending the real 8 Elite; the cloud tier is fixed to **Gemma 4 E4B**.
