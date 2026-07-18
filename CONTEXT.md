# TactileSight

Wearable spatial-awareness system for blind and visually impaired users. Two layers: a haptic band that conveys *where* things are, and a phone module that conveys *what* they are.

## Language

**Haptic Band**:
The headband layer. Renders live depth as touch (vibration matrix) — conveys the *geometry* of the environment: where obstacles are and how far.
_Avoid_: headset, sensor band

**Semantic Module**:
The phone-based layer. Runs on-device vision + language models to tell the user *what* things are (identity/labels), not where. Extension to the Haptic Band, not a replacement.
_Avoid_: AI module, description engine

**Where-layer / What-layer**:
The deliberate division of labour. The Band owns *where* (spatial geometry, continuous, haptic). The Module owns *what* (semantic identity, on demand). Neither re-derives the other's job.

**Query**:
A single user-initiated request for semantics ("what's in front of me?"). Triggers one RGB frame capture → on-device model → spoken answer. The Module acts only on a Query; it does not narrate continuously.

**Scene Frame**:
One RGB still captured at Query time and fed to the phone. Not a continuous video stream — one frame per Query keeps things cheap. The phone receives frames through a **FrameSource** seam so the producer can change without touching the pipeline: a phone-camera source for the hackathon build/demo now, and the band's camera **over WebRTC** as a later swap-in. Nothing downstream knows which source it is. *(Updated 2026-07-18: the band swap-in delivers an **on-demand RGB+depth+IR triplet from one capture instant** over the WebRTC data channel — not the persistent live video stream described here originally. See ADR-0009 and `docs/band-interface.md`.)*

**Perception stage**:
The on-device object detector + OCR that turns a Scene Frame into structured facts: object labels, positions, and any read text. Deterministic vision, no language yet.
_Superseded (2026-07-10):_ the detector is retired and folded into the **Semantic brain (VLM)** — one vision-language model now does perception + language in a single pass. OCR survives as an optional grounding stage (see Semantic brain below). Kept here for history.

**Narrator**:
The small on-device LLM that turns the Perception stage's structured facts into a short spoken sentence, and answers a Spoken Query about the scene. Does language only — it never sees the raw image.
_Superseded (2026-07-10):_ replaced by the **Semantic brain (VLM)**, which *does* see the frame (that's what makes interactive Query answer visual questions like "is the door open?"). Hallucination is now bounded by prompt constraints + OCR grounding rather than by keeping the model away from pixels.

**Query (button semantics)**:
The Scene Frame is captured the instant the button is pressed (both gestures), so it reflects the scene the user faced at press time. Short press = Describe (canned "what's ahead"). Long press = Spoken Query (hold-to-talk): after the press-time capture, the mic stays open while the button is held, the question is transcribed on-device, and the Semantic brain answers about that frame. **Double press = Save**: retains the current Scene Frame to the phone's photo storage (earcon says "captured") — the one deliberate, explicit-consent exception to ephemeral-by-default memory. Mic is off otherwise. All input is on one button so the gesture set survives the swap from phone volume button (hackathon) to a band-mounted button (integrated version); the demo mirrors the band's single-button interaction exactly.

**Earcon**:
A short non-speech tone. Played instantly on button-press (confirms the Query was heard) and softly during processing, so a blind user is never left guessing. A result is always spoken — including "nothing clear ahead" or "couldn't see well, try again".

**Correction**:
User feedback on a Narrator answer (right / wrong / actual label). Captured on-device; the training signal for future personalization and federated learning. Roadmap, not built for the hackathon.

**Last-10m fusion**:
The navigation idea where Google Maps routes the user to the destination block (GPS, ~5m accuracy) and the camera + OCR then finds the actual entrance and confirms arrival by reading signs. Maps gets you to the block; vision gets you to the door. Roadmap module. Directions come from the **Google Maps Directions API** used as a routing *engine* only — the phone computes the route, the **Band renders turn cues as haptics** (never Maps' voice, which would occupy hearing). Routing is the one narrow **cloud** exception: GPS coordinates + destination go to Google (like any nav app), but **camera imagery never leaves the device**. "Take me back to a door I just passed" indoors is a different, far harder problem (visual SLAM / indoor localization) — roadmap-of-roadmap.

**Explore mode**:
An opt-in mode (off by default) where the phone captures periodically — triggered by scene-change/motion, not a blind fixed timer — to build a short rolling memory of recent observations, so the Narrator can answer "what did I just pass?". The default is strictly on-demand (only a button press captures); Explore mode is the user's choice to trade battery/privacy for continuous context. Roadmap. Note: the cross-scene *memory* that answers "what did I just pass?" is now **in scope** via the 10-min **Scene memory** over on-demand captures — Explore mode adds only the *automatic / button-free* capture on top, which stays roadmap.

**Semantic brain (VLM)**:
The single on-device vision-language model (**Qwen3-VL-4B**) that is the whole "what" pipeline — it *sees* the Scene Frame and produces speech. Describe = the VLM writes a *rich* description (stored in Scene memory) and the user hears a terse summary; Query = the fresh frame plus the transcribed question plus the 10-min Scene memory (it may re-look at a buffered frame). Replaces the old Perception-stage + Narrator two-model split. Hard rule in its prompt: give identity + horizontal position (left/center/right), **never distance** — distance belongs to the Band's haptic Where-layer and an RGB feed can't back it. "Describe only what you clearly see; if unsure, say nothing clear ahead." _(2026-07-18, ADR-0009: the **VLM prompt still forbids distance** — it can't measure — but the **phone now appends a real distance from the band's depth camera**. See **Depth-fused distance**. So the *product* speaks distance; the *model* still doesn't guess it.)_

**Two-tier Semantic brain — on-device + cloud fallback** _(added 2026-07-18)_:
The Semantic brain runs in one of two tiers, chosen **automatically per device** so *every* phone can run TactileSight, not only Snapdragon-NPU ones:
- **On-device tier (privacy-first, the hero):** the VLM runs on the phone via GenieX (NPU on 8-Elite-class; llama.cpp GPU/CPU elsewhere). **No frame leaves the device.** Default whenever the device can run it.
- **Cloud fallback tier (reach):** on devices that can't run the VLM locally, the Scene Frame is sent over HTTP to a server that runs the VLM and returns the spoken answer. Serves non-Qualcomm / low-RAM phones.

**Routing is automatic and capability-driven** — the blind user never picks: GenieX's own chipset detection (`detectChipset`/`listChipsets`) **and** a **total-RAM floor (provisional 12 GB, tune on the 8 Elite)** decide on-device-eligibility; anything below — or where the on-device model fails/OOMs at load — **falls back to cloud** at runtime. The visual model-picker is a **dev/demo override only**. **The user is told once at setup which tier is active** (spoken): on-device → "running on your device, private"; cloud → "processing happens in the cloud." (A *notice*, deliberately not a consent gate — the user's explicit call, 2026-07-18. Residual risk noted: a frame from a camera the user can't see is sensitive; notice→consent is a small upgrade if ever needed.)

**Behind the `SemanticBrain` seam:** on-device = `GenieXBrain`; cloud = `CloudBrain` → HTTP → a server whose VLM backend is swappable (`mock` | `openai`-compatible | future Cloud AI 100 via QEfficient). For the hackathon the cloud tier is served by **Gemma 4 E4B (llama.cpp, CUDA/GPU)** on a **laptop the team controls** — honest framing: *on-device on Snapdragon (hero), cloud fallback on a server* — **not** "on Cloud AI 100." (The free Cirrascale Cloud AI 100 Playground was evaluated 2026-07-18 and exposes **no vision model** — only text Llama-3.1-8B + SDXL — so it can't power scene description; a real Cloud AI 100 VLM would be a one-line backend swap.)

_Model choice:_ cloud tier = **Gemma 4 E4B** (GGUF + mmproj, vision-capable). **On-device model is OPEN** — Gemma 4 vs the incumbent Qwen3-VL — decided on the actual 8 Elite (Gemma 4 is Qualcomm-co-optimized for edge, which favours it, but GenieX/NPU behaviour is unconfirmed until we hold the device).

**Band↔phone sensor pipeline** _(added 2026-07-18, ADR-0009)_:
How the band's **Orbbec Astra Pro Plus** (RGB + depth + IR — *not* thermal) feeds the phone. The **Arduino UNO Q** (Dragonwing QRB2210 + STM32) is a modest SoC, so it runs **no vision ML** — it only **captures**, runs the **local safety haptic loop** (depth → zones → haptics on the STM32, always-on, network-independent), and acts as a **dumb pipe**: forwards the camera's native **MJPG RGB** (no re-encode) + **depth/IR keyframes on demand**, plus the Astra's **calibration once** at session start. *All intelligence is on the phone:* depth↔RGB **alignment**, object **detection** (boxes), the **VLM** description, and per-box **depth lookup**. Capture resolutions: **RGB 1280×720 MJPG**, **depth 640×480**, **IR 640×480** (dark-scene keyframe). Transport: the UNO Q's own **5 GHz AP** primary + **USB-gadget** fallback, carried by **one WebRTC PeerConnection** (RGB track + telemetry data-channel) whose AP and USB addresses are both ICE candidates, so failover is automatic. RGB↔depth FOV mismatch (16:9 vs 4:3) means objects at the frame's extreme top/bottom get "distance unknown" — graceful, never a false number.

**Depth-fused distance** _(added 2026-07-18, ADR-0009 — reverses "never distance")_:
The system now **speaks real distances** — but they come from the band's **depth camera** (a measurement), never from the VLM (which still must not guess). On Describe, the phone detects an object, looks up a **robust percentile (~10th) of valid depth pixels** in its box (never raw `min` — depth holes would report a spurious 0.2 m), and fuses: *"person on your left, about two metres."* Identity/side from the image, distance from depth. This un-gates ADR-0004's "calibration-gated roadmap": the Astra's factory registration supplies the color↔depth calibration that was previously missing. IR feeds the VLM in low light (the band picks RGB-or-IR by brightness) so the frame is usable in the dark. Coarse depth zones still drive **haptics on-band** (30 Hz, safety); precise per-object metres are the **phone's spoken layer** (on demand).

**FrameSource seam**:
The one interface the pipeline reads frames from ("give me the latest frame"). Two implementations: `PhoneCameraSource` (now) and `WebRtcFrameSource` (band, later). Lets the camera producer change without the Query pipeline noticing. _(2026-07-18, ADR-0009: the band producer also carries a **depth/IR keyframe + calibration** alongside RGB, so the phone can fuse measured distance; the RGB-only phone-camera source simply reports "distance unknown".)_

**SpeechIO seam**:
The one interface for speech-in (ASR) and speech-out (TTS), so the speech stack swaps without touching the pipeline. Build-time primary (spike-verified 2026-07-11): **ASR = AI4Bharat IndicConformer** (CTC branch exported to ONNX, run via **sherpa-onnx** prebuilt Android AAR; 30M / INT8-120M **monolingual** model per language — the multilingual 600M is server-only; 16 kHz mono mandatory) and **TTS = Android's on-device engine** (offline voices, forced offline via `Voice.isNetworkConnectionRequired()==false` — measured tens-to-low-hundreds of ms). **Android STT is deliberately rejected** (default is *cloud* — ships the user's voice to Google). **AI4Bharat neural TTS (Indic Parler-TTS, 880M) is rejected on-device** (multiple seconds/sentence). Upgrades via the seam: **Sarvam Edge** (natural ASR+TTS, sub-130ms, needs SDK access) is best; **Piper via sherpa-onnx** (RTF<1) is the neural-TTS fallback if more naturalness is wanted. **Language is a user setting (defaults to the device locale), not audio auto-detect** — on-device spoken-language ID is unsolved, and the ASR model is per-language anyway, so the chosen language selects which IndicConformer model to load, sets the TTS locale, and tells the VLM which language to answer in.

**Scene memory (10-min rolling)**:
Every button press captures a fresh Scene Frame; the Semantic brain writes a *rich* description of it (colours, states, any text, positions), and the pair **{downscaled frame + rich description}** is kept in a **~10-minute rolling buffer**. The user only *hears* a terse summary, but can **query across scenes** within the window — "what colour was the door we just saw?" — because the brain can **re-look at the buffered frame** and reason over the description log. The buffer **auto-clears at ~10 min** (never persistent) and stays on-device — privacy comes from auto-expiry + on-device, not from keeping nothing. Explicit **Save** is the only path to permanence. (This replaces the old single-frame 45 s latch and the "independent Queries" default.)

**Mobile-free**:
The target experience: phone stays in the pocket, all interaction is on the band (button) + audio (earbud now, bone-conduction as production intent). The phone is a compute brick, not a screen. The volume-button demo is a stand-in for the band button.

**Query & follow-ups**:
Every press captures a fresh frame and adds it to the **Scene memory**. A Query (hold-to-talk, mic open only while held — no open-mic, no ambient capture) is answered by the brain over **{the fresh frame + the 10-min scene memory}**, so follow-ups *and* questions about earlier scenes both work without a separate "session" concept — the memory *is* the context. When an answer refers to a past scene the brain marks it in speech ("the door you passed earlier was blue") so a blind user knows current-vs-remembered. Single-turn over the current frame ships first (Stage 1); the scene-memory context is Stage 2/3.

**Uncertainty & failure UX**:
The rules that keep a device a blind user *trusts*. **Implicit echo**: the answer restates the subject ("the chair is on your left") so the user hears whether ASR understood, with no extra step; an explicit "did you mean…?" fires only on *low ASR confidence*. **Hedge, never guess**: an unclear scene yields "I'm not sure, but…" or "nothing clear ahead" — never a confident wrong answer (the same honesty rule that killed distance and weapon claims). **Timeout**: a Query past ~6s stops and speaks "couldn't see well, try again". Every press yields *some* speech. Answers are **terse** by default — top 2–3 things, central/prominent first (the RGB-only proxy for "important", since there's no distance), no preamble.

**NFC launch & pairing**:
An NFC tag on the band is the eyes-free entry point. Tapping phone-to-band launches the app (or the Play Store if not installed); with setup incomplete it starts **voice-guided onboarding**. The tag also carries the band's connection credentials, so a tap **pairs the band and bootstraps the WebRTC signaling** — replacing the misery of TalkBack-through-a-Bluetooth-menu. Tap-to-launch is hackathon scope; tap-to-pair rides with the band/WebRTC stretch.

**Always-on service**:
The app runs as a persistent background/foreground service that auto-starts on boot, so the band button works anytime without "opening the app." Daily use touches no screen. The app needs accessibility only for one-time setup — TalkBack + high-contrast/large-text baseline (also serves low-vision users); the NFC tap + voice-guided onboarding make setup largely **solo**, so a sighted helper is optional, not assumed.

---

## REBUILD — settled design (grilled 2026-07-18)

> The app was **rebuilt from scratch** in this repo (the old `android/` project was deliberately **not** salvaged — team call). Where this section conflicts with the older text below, **this wins**. Detail in ADR-0010 … ADR-0013.

**Inference — three engines, one seam.** The `SemanticBrain` seam now has **three** on-device implementations, **user-selectable**, so we can find out which actually reaches the Hexagon NPU: **LiteRT-LM** (sideloaded multimodal Gemma-2b `.litertlm`), **GenieX** (downloads GGUF / AI-Hub QAIRT), **ExecuTorch** (sideloaded `.pte` + QNN — InternVL3-1B, SmolVLM-500M; the only *guaranteed*-NPU VLM we hold). **Model lifecycle is a hard rule:** load once, stay resident, lock-guarded, release **only** on engine/model switch, one VLM resident at a time — the old app dropped its model on every rotation, leaked it, and was OOM-killed six times. Models are **sideloaded to the external files dir** and scanned at startup (venue downloads proved unusable); **test scenes ship in the APK**. → ADR-0010

**Interaction — three buttons, no double-press.** `tap` = **"where"** (object distances + directions from detector + depth, **no VLM**, ~0.5 s — the safety-relevant and most frequent action). `hold` = **"what"** (mic opens; on release: spoke → Query, silent → Describe — so there is no mode to choose, and ASR failure degrades to Describe rather than a dead press). Speech happens **only after release** (else our TTS feeds back into the open mic), and is **two-stage**: fast distance first, VLM description after. Button 2 = continuous guidance / repeat-last; button 3 = **privacy toggle** (a *physical* toggle a blind user can feel) / Save. Continuous guidance runs on the **on-device fast lane, not the cloud** — reflexes cannot sit behind a network hop, and the band's haptics already run continuously and locally. → ADR-0011

**Speech & language — Sarvam cloud.** The **VLM describes in English** (small 1–2B VLMs are markedly weak in Indic languages), then **Sarvam Translate → Sarvam TTS**; Query is **Sarvam ASR** → English → VLM. Demo path **Punjabi + Hindi + English**, but **every Sarvam language is exposed** so a judge can switch live — adding a language is a parameter, not an integration. **Camera imagery still never leaves the device**; only *speech* touches Sarvam — state that distinction honestly. **No offline TTS fallback in the MVP** (accepted risk: losing the network leaves the app *silent*, including the safety-relevant fast lane). → ADR-0012

**Distance — measured, via IR, calibration-free.** Detect on the **IR** frame and read `depth_raw` on the **same 640×480 grid** (same sensor — confirmed by `calib.json` and empirically), so **no RGB↔depth calibration is needed**. Distance = **robust ~10th percentile of *valid* depth pixels** in the box, with a **minimum-valid-pixel threshold** → otherwise **"distance unknown"**. Validated against 21 real captures: depth is metric **0.45–9.94 m**, but valid coverage averages only **62.6%** (one box was 1% valid), so "distance unknown" is a **common path, not an edge case** — never fabricate a number. IR saturation doubles as a depth-confidence signal. → ADR-0013

**Phases:** 1 LiteRT end-to-end (the guaranteed prototype) → 2 IR distance + quick-tap fast lane → 3 full interaction + multilingual → 4 cloud fallback tier → 5 GenieX + ExecuTorch + NPU benchmark. Tickets in `.scratch/rebuild/issues/`.

**Parked:** live band WebRTC, replacing the button entirely, task guidance ("help me make coffee" — scope unresolved), reading/find modes, scene memory, always-on service, NFC, Save.

---

## Semantic Module — settled design (grilled 2026-07-10/11)

> ⚠️ Superseded in part by the rebuild section above (engines, interaction, speech, distance). Retained for the reasoning and for everything the rebuild did not touch.

On-device **first** — privacy premise intact **for capable devices**; a **cloud fallback tier** (added 2026-07-18) extends reach to phones that can't run the VLM locally, with the user told at setup (see *Two-tier Semantic brain*). Native **Android (Kotlin)**. Standing principle: **on-device primary everywhere, cloud / CPU / open fallback always behind a seam.**

**Pipeline:** `FrameSource → (Describe prompt | ASR-transcribed question) → VLM (+OCR grounding, Stage 2) → TTS`, spoken out.

**Runtime & resources:** the VLM (GenieX runs **LLMs/VLMs only**) sits on **GenieX**, which has two backends under one API — **QAIRT/QNN on the Hexagon NPU** and **llama.cpp (GGUF)** — selected **by device** (spike-verified 2026-07-11): AI Hub's Qwen3-VL-4B NPU bundle is **8-Elite-only** (QAIRT bundles are per-SoC), so on **8 Gen 1/2 the `llama_cpp` backend (GGUF Q4_0, GPU/CPU) is primary** (a few-seconds on-demand answer is achievable, but *no NPU acceleration for the VLM there*); the **`qairt` NPU backend is the upgrade only on 8-Elite-class** demo hardware. If GenieX's Android VLM-via-llama_cpp (image input) proves fiddly, **direct llama.cpp (`llama-mtmd-cli`)** is the drop-in fallback behind the `SemanticBrain` seam. On an **8 GB** device (the S22 test phone), if the 4B is memory-tight, **Qwen3-VL-2B** is the lighter option. **Test device = Galaxy S22 (SM-S901E, Snapdragon 8 Gen 1, 8 GB, Android 16)**; pursue an **8 Elite** for the NPU demo story. **OCR (PaddleOCR) and English/fallback Whisper run on QAIRT/QNN directly** (not GenieX). **IndicConformer ASR + Android TTS are off the Qualcomm stack, on CPU.** All models stay **resident, VLM kept warm** (flagship 8 Gen 2/3 RAM allows it; the pipeline is sequential per Query, so weights co-reside but don't all compute at once — and ASR-on-CPU never contends with the VLM-on-NPU). Thermal is bounded by the **on-demand duty cycle** (VLM fires only on press, not continuously — the same ADR-0007 decision buys privacy *and* thermal headroom) plus NPU efficiency.

**Model sourcing (Qualcomm AI Hub vs off-hub):**
- *From AI Hub:* **Qwen3-VL-4B** (VLM), **PaddleOCR** (OCR), **Whisper** (English/fallback STT). AI Hub has STT (Whisper/Wav2Vec2/HuBERT) but **no TTS** — speech-*out* cannot come from it.
- *Off-hub:* **AI4Bharat IndicConformer** (Indian-language STT), **Android on-device TTS** (speech-out), **Sarvam Edge** (upgrade). **GenieX** is a runtime, not a model.
- *Rationale — right-sized, not cheapest:* spend the NPU on the 4B VLM (it needs it); use free/CPU where it's already good enough (30M ASR, Android TTS); keep the paid **Sarvam Edge** quality upgrade behind the seam. It's risk-minimization, not cost-minimization — the only real quality tradeoff is TTS *naturalness* (Android voices), which the upgrade path addresses.

**Decisions by area:**
- **Frames/capture:** phone camera (guaranteed demo) → band RGB over WebRTC (stretch, during event) behind the `FrameSource` seam; persistent preview, capture = frame *retained* on press.
- **Gestures:** one button — single = Describe, hold = Query (hold-to-talk), double = Save; earcon on press. Volume button (demo) → band button (integrated); NFC tap-to-launch on top.
- **Brain:** single VLM for both paths; Describe = fixed caption prompt; always-on **OCR grounding** (PaddleOCR/NPU, multilingual scripts, Stage 2). On-device model **open** (Gemma 4 vs Qwen3-VL, decide on the 8 Elite).
- **Tier / routing (added 2026-07-18):** two-tier brain behind the `SemanticBrain` seam — **on-device** (`GenieXBrain`, privacy, hero) vs **cloud fallback** (`CloudBrain` → HTTP → server VLM, reach). **Auto-routed** by GenieX chipset detection + a **12 GB total-RAM floor** (provisional), with **runtime fallback to cloud** if on-device load/OOMs; picker is dev-only. **Spoken tier notice at setup** (on-device "private" / cloud "processing in the cloud"), a notice not a gate. Hackathon cloud backend = **Gemma 4 E4B on a team laptop** (llama.cpp/CUDA), framed honestly as "a server," not Cloud AI 100.
- **Honesty:** identity + horizontal position; the **VLM never states distance** (it can't measure — must not guess). _(2026-07-18, ADR-0009: the earlier "depth-fused-speech is a calibration-gated roadmap" is **un-gated** — the **Orbbec Astra Pro Plus supplies the factory color↔depth registration** we lacked, so the **phone now speaks a measured distance from depth** on top of the VLM's identity/side. See **Depth-fused distance**.)_ Implicit echo + confirm-on-low-ASR-confidence + hedge-never-guess + ~6s spoken timeout. Answers terse, central/prominent first.
- **Query lifecycle:** multi-turn over one frame (single-turn = Stage 1); push-to-talk per turn; latch-to-last-frame, ~45s timeout; distinct capture/continue earcons.
- **Memory/privacy:** **Scene memory** — a ~10-min rolling buffer of {downscaled frame + rich description} per capture, queryable across scenes (the brain re-looks at buffered frames); auto-clears; persist only on Save. In scope (Stage 2/3). Only *automatic/button-free* capture (full Explore mode) stays roadmap. Bystander privacy handled by framing (on-device + ephemeral + assistive = sensory substitution, not surveillance) — no camera LED, no face-blur (face-blur = roadmap).
- **Multilingual (in scope):** a handful of major Indian languages (placeholder set: Hindi, Tamil, Telugu, Bengali, Marathi — confirm). **Language = user setting (default device locale), NOT audio auto-detect** (on-device spoken-language ID is unsolved; ASR is per-language). **ASR = AI4Bharat IndicConformer** via sherpa-onnx (CPU); **TTS = Android on-device** (offline voices, spike-verified fast). **Android STT rejected** (cloud); **AI4Bharat neural TTS rejected** (too slow on device). Upgrades via `SpeechIO` seam: **Sarvam Edge** (best, needs SDK); **Piper/sherpa-onnx** neural-TTS fallback. VLM answers in the chosen language.
- **Accessibility/launch:** mobile-free; always-on background service (auto-start); NFC tap-to-launch (hackathon) + NFC tap-to-pair / WebRTC-bootstrap (with band stretch); voice-guided solo onboarding; TalkBack + high-contrast baseline.
- **Navigation:** roadmap — Google Maps **Directions API** as routing engine → band haptics (phone computes, band renders); cloud-routing = narrow accepted exception (coords, not imagery). Indoor backtrack = SLAM, far roadmap.
- **Output:** earbud (demo) / bone-conduction (production intent).

**Explicitly cut:** danger/threat classification (unreliable, safety-liability) and a custom SOS (the OS's native SOS is better) — the Semantic Module does not own "danger."

**ADRs to revise / supersede to match (not yet rewritten):**
- **ADR-0002** (band RGB on-demand) → **persistent WebRTC** stream, capture = retained frame, behind the FrameSource seam.
- **ADR-0003** (Perception + Narrator split) → single **VLM** that sees the frame; OCR survives as grounding.
- **ADR-0004** (band depth sensor) → **un-gated by ADR-0009** (2026-07-18): the Astra Pro Plus supplies the color↔depth registration that made depth-fused-speech "calibration-gated." Distance is now measured by the band and spoken by the phone.
- **ADR-0005** (model/runtime stack) → **GenieX + llama.cpp** for a **VLM**; multilingual **AI4Bharat / Sarvam Edge** speech stack; all-resident + VLM-warm.
- **ADR-0006** (Maps fusion) → routing via **Directions API → band haptics**; phone computes / band renders; cloud-routing exception.
- **ADR-0007** (on-demand capture) → still holds, and as of the 2026-07-18 correction to ADR-0009 it now holds **on the wire too**: the band sends imagery only in response to a `capture_request`, so nothing streams continuously. (Its "double-press Save" reference is stale — no double-press gesture survives ADR-0011.)
- **ADR-0008** (cloud fallback tier) → **new** (2026-07-18): the two-tier on-device/cloud brain, capability-based auto-routing, and the honest "cloud fallback on a server" framing. See `docs/adr/0008-cloud-fallback-tier.md`.
- **ADR-0009** (multi-sensor band pipeline) → **new** (2026-07-18): Astra Pro Plus RGB+depth+IR, band = capture+haptics+dumb-pipe / phone = align+detect+depth-lookup+VLM+fusion, **real distances spoken from depth** (reverses "never distance"), IR for low-light, 5 GHz AP + USB-gadget over one WebRTC PeerConnection. **Corrected same day:** the band sends **on-demand RGB+depth+IR triplets from one capture instant** over the data channel — **no continuous video track**. A low-rate dashboard preview is **optional and OPEN**, pending the band team's answer on UNO Q headroom. See `docs/adr/0009-multi-sensor-band-pipeline.md`.

### Rebuild ADRs (2026-07-18, this repo)

- **ADR-0010** (three-engine on-device inference) → **LiteRT-LM / GenieX / ExecuTorch** behind one seam, selectable; sideload-vs-download per engine; **load-once / lock-guarded / release-on-switch** lifecycle; RAM budgets. Supersedes ADR-0005's single runtime and ADR-0008's on-device half. `docs/adr/0010-three-engine-on-device-inference.md`
- **ADR-0011** (interaction model & modes) → **three buttons × tap/hold, no double-press**; tap = "where", hold = "what" with optional question; speech only after release; two-stage speaking; continuous/privacy/task modes. `docs/adr/0011-interaction-model-and-modes.md`
- **ADR-0012** (speech & language) → **VLM in English → Sarvam Translate → Sarvam TTS**, Query via **Sarvam ASR**; all languages exposed; **no offline TTS fallback in MVP** (accepted risk). Supersedes the on-device IndicConformer/Android-TTS stack. `docs/adr/0012-speech-and-language-via-sarvam.md`
- **ADR-0013** (IR-aligned distance) → detect on **IR**, read depth on the **same grid** → **calibration-free** per-object distance; robust percentile + **min-valid-pixel → "distance unknown"**; validated on 21 real captures. Refines ADR-0009's distance path. `docs/adr/0013-ir-aligned-calibration-free-distance.md`

**Pre-event action items:**
- Email **Sarvam** for Edge SDK access + Snapdragon support (upgrade, not a blocker — AI4Bharat is the guaranteed path).
- Verify **AI4Bharat** IndicConformer + Indic-TTS on the provided device; confirm the target-language TTS voices are installed.
- Verify **GenieX** loads Qwen3-VL-4B on the provided Snapdragon; keep llama.cpp ready.
- Run a **sustained demo-length session** (10–15 min of repeated Queries) on real hardware and watch for thermal throttling.
- Band track: WebRTC band-RGB is a **stretch swap-in during the event** — the phone-camera demo must stand 100% on its own.
- Confirm the **target-language set** (the list above is a placeholder).
