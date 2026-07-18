# PRD: Semantic Module (the "what" layer)

> **⚠️ HISTORICAL — superseded 2026-07-18.** Written against ADRs 0001–0007; ADRs 0009–0013 have
> since changed the engine strategy, the speech stack, the interaction model, and the distance rule.
> The **problem statement and user needs below still hold** — the solution shape does not.
> Current design: `CONTEXT.md` → `REBUILD — settled design`. Tickets: [GitHub Issues](../../../../issues).

Status: ready-for-agent

Owner: mobile team · Target: Qualcomm Snapdragon Multiverse Hackathon, Noida, 2026-07-18/19
Domain: see `CONTEXT.md`; respects ADRs 0001–0007 (0002/0003/0005 as updated 2026-07-11).

## Problem Statement

A blind or visually-impaired user knows *where* obstacles are from the Haptic Band, but not *what* they are. Standing at a doorway, a shelf, or a sign, they have no eyes-free, private way to ask "what's in front of me?" and get a trustworthy spoken answer — in their own language — without pulling out a phone, staring at a screen, or sending their camera feed to a cloud service. Audio-based aids also occupy the hearing they rely on, and existing tools either can't read a scene, can't be trusted (confident wrong answers), or leak imagery off-device.

## Solution

An on-device phone module — the **Semantic Module** — that the user drives entirely from a single button and their voice, phone in pocket:

- **Single-press = Describe**: captures one **Scene Frame** and speaks a short description ("chair on your left, door ahead").
- **Hold = Query**: hold-to-talk; the user asks a question about that frame ("is the door open?", "read the sign") and the **Semantic brain** answers, with interactive follow-ups over the same frame.
- **Double-press = Save**: keeps the current frame to the phone gallery (the only persistence; everything else is ephemeral).

Everything runs on-device (privacy). Answers are terse, give position but never distance (distance is the Band's haptic job), speak in the user's own Indian language, and **hedge rather than guess** — a device you can trust because it never confidently lies about what it can't verify.

## User Stories

1. As a blind user, I want to single-press one button and hear what's in front of me, so that I can understand a scene without sight.
2. As a blind user, I want the description to be short (2–3 most prominent things), so that I'm not overwhelmed and can act quickly.
3. As a blind user, I want to hear *where* things are (left/center/right), so that I can orient toward or away from them.
4. As a blind user, I do NOT want the module to state distances, so that I'm never misled — distance comes reliably through the Band's vibration instead.
5. As a blind user, I want to hold the button and ask a spoken question about the scene, so that I can get specifics the description didn't cover.
6. As a blind user, I want to ask questions about scenes from the last ~10 minutes — even after capturing new ones ("what colour was the door we just saw?") — so that I can drill down or recall naturally.
7. As a blind user, I want an answer that makes clear when it's about an *earlier* scene vs what's in front of me now ("the door you passed earlier was blue"), so that I'm never confused about which scene is being described.
8. As a blind user, I want the module to say "I'm not sure" or "nothing clear ahead" when it can't tell, so that I never act on a confident wrong answer.
9. As a blind user, I want an instant tone the moment I press, so that I know my press was heard even before the answer comes.
10. As a blind user, I want a soft processing tone while it thinks, so that I'm never left in silence wondering.
11. As a blind user, I want a spoken result on every press — even "couldn't see well, try again" — so that the device never just goes silent on me.
12. As a blind user, I want the answer to repeat the subject I asked about ("the chair is on your left"), so that I can tell it understood my question correctly.
13. As a blind user, I want to be asked "did you mean…?" only when it's genuinely unsure it heard me, so that I get confirmation without friction on every request.
14. As a blind user, I want to interact in my own Indian language (e.g. Hindi, Tamil, Telugu, Bengali, Marathi), so that the device is actually usable for me.
15. As a blind user, I want my language to default to my phone's system language (changeable in settings), so that I don't have to configure it and it just works in my language. (On-device audio language-detection is unsolved, so a chosen/locale language selects the per-language ASR model, TTS locale, and VLM answer language.)
16. As a blind user, I want the spoken answer in the same language I asked in, so that the interaction feels natural.
17. As a blind user, I want to double-press to save an important scene to my phone, so that I can share it with a sighted person later.
18. As a blind user, I want a distinct "captured" tone on Save, so that I know the photo was kept.
19. As a blind user, I want nothing kept *permanently* without my say — only the last ~10 minutes are held on-device and then auto-erased, and only an explicit Save keeps anything — so that my privacy is protected if my phone is lost.
20. As a blind user, I want all processing on my phone with nothing sent to a cloud, so that my camera never exposes me or the people around me.
21. As a blind user, I want to operate everything from a button with the phone in my pocket, so that I never have to find or look at a screen.
22. As a blind user, I want the app to already be running whenever I press, so that there is no "open the app" step.
23. As a blind user, I want to tap my phone to the band to launch/set up the app, so that setup is a tactile, eyes-free action.
24. As a blind user, I want first-time setup guided by voice, so that I can complete it without a sighted helper where possible.
25. As a low-vision user, I want high-contrast, large-text screens for any settings, so that I can use the app with residual vision.
26. As a blind user, I want an earbud to hear answers, so that I can use it discreetly; I want ambient sound preserved (bone-conduction) in the production device, so that my hearing stays free for the world.
27. As a demo presenter, I want the same gesture set on the phone's volume button as the future band button, so that the demo maps exactly to the product.
28. As a maintainer, I want the interaction logic testable without real models or hardware, so that behavior stays correct as we build.
29. As a maintainer, I want the camera source swappable (phone camera now, band-WebRTC later) without touching the pipeline, so that the band integration is a drop-in.
30. As a maintainer, I want the speech stack swappable (AI4Bharat now, Sarvam Edge later) without touching the pipeline, so that quality upgrades are a drop-in.
31. As a maintainer, I want the vision-language brain swappable (GenieX/NPU primary, llama.cpp fallback) without touching the pipeline, so that a failed day-one runtime doesn't block the demo.
32. As a demo presenter, I want a working phone-camera demo that stands entirely on its own, so that the band/WebRTC stretch failing can't sink the demo.

## Implementation Decisions

**Platform & runtime**
- Native **Android (Kotlin)**; on-device throughout. Runs as an **always-on foreground service** (auto-start on boot) so the button works anytime without opening the app.
- **Semantic brain (VLM) = Qwen3-VL-4B** on **GenieX**, backend selected **by device**: `llama_cpp` (GGUF Q4_0, GPU/CPU) on 8 Gen 1/2 (incl. the S22 test device — no NPU accel there); `qairt` (NPU) only on 8-Elite-class demo hardware (the AI Hub NPU bundle is 8-Elite-only). Direct `llama-mtmd-cli` is the drop-in fallback if GenieX's Android VLM path is fiddly. Use **Qwen3-VL-2B** if the 4B is memory-tight on 8 GB (ADR-0005 updated).
- **OCR (PaddleOCR)** and **English/fallback Whisper** on QAIRT/QNN directly. All models resident, VLM kept warm; thermal bounded by the on-demand duty cycle + NPU.

**Three seams (2 existing, 1 new) — tested by faking, real impls ship**
- **`FrameSource`** *(existing)* — `latestFrame()`. Real impls: `PhoneCameraSource` (CameraX persistent preview, retain-latest-on-press) now; `WebRtcFrameSource` (band) later. Capture = frame *retained* on press (ADR-0002 updated, ADR-0007 holds).
- **`SemanticBrain`** *(new; highest seam over the models)* — `interpret(frame, mode)` where mode is a Describe prompt or a transcribed spoken question; returns a short answer. Wraps **VLM + OCR grounding + runtime** behind one interface (OCR is folded in, not its own seam). Prompt rules: identity + horizontal position, **never distance**; hedge when unclear; terse (top 2–3, central/prominent first); answer in the detected language.
- **`SpeechIO`** *(existing)* — `transcribe(audio, lang)` + `speak(text, lang)`. **Language is an explicit setting (default device locale), not audio auto-detect** (on-device spoken-language ID is unsolved). Real impls now: **ASR = AI4Bharat IndicConformer** (CTC branch, sherpa-onnx AAR, per-language monolingual model, 16 kHz mono), **TTS = Android on-device** (offline voices, forced offline). **Android STT rejected** (cloud/privacy); **AI4Bharat neural TTS rejected** (too slow on device). Upgrade path: **Sarvam Edge** (or Piper via sherpa-onnx for neural TTS) behind the same seam.

**Query orchestrator (the deep module under test)**
- Owns gesture → capture → interpret → speak. Depends only on the three seams.
- **Gestures**: single = Describe, hold = Query (hold-to-talk, mic open only while held — push-to-talk per turn), double = Save. Instant **earcon on press-down** ("heard"), soft processing earcon, "captured" earcon on Save. Every press yields spoken output.
- **Multi-turn Query**: latches to the last captured frame; follow-ups reason over the same frame; **~45s inactivity timeout** releases the session; a single-press Describe captures fresh and resets it. **Distinct "new look" vs "same look" earcons** mark which frame an answer refers to. (Single-turn ships first = Stage 1; multi-turn = Stage 2.)
- **Uncertainty/failure**: implicit echo (restate the subject); explicit "did you mean…?" only on low ASR confidence; hedge-never-guess; **~6s Query timeout** → "couldn't see well, try again".
- **Scene memory (10-min rolling — in scope):** every press captures a fresh frame; the brain writes a **rich description** (colours, states, text, positions) and **{downscaled frame + description}** is kept in a **~10-min rolling buffer** (auto-clears; on-device). The user hears a *terse* summary but can **query across scenes** in the window — the brain reasons over the description log and can **re-look at a buffered frame** for any detail, marking past-scene answers temporally. This replaces the single-frame 45 s latch and the "independent Queries" default. Explicit **Save** is the only permanence. A plain, directly-tested `SceneMemory` component (time-based eviction) holds the buffer; the `SemanticBrain.interpret` call receives the recent scenes as context.

**Accessibility & launch**
- Mobile-free; **NFC tap-to-launch** (hackathon) and **NFC tap-to-pair / WebRTC-signaling bootstrap** (with the band stretch). **Voice-guided onboarding**; TalkBack + high-contrast baseline for setup/settings.

**Staging (build order)**
- **Stage 1**: phone camera → single-press Describe → VLM → Android TTS, English, single-turn. Demoable alone.
- **Stage 2**: hold-to-Query + IndicConformer ASR + multilingual + OCR grounding + **10-min Scene memory (rich descriptions + downscaled frame buffer, cross-scene queries)** + Save.
- **Stage 3 (stretch, during event)**: band RGB over WebRTC behind `FrameSource`, NFC tap-to-pair.
- Do not start a stage until the previous one demos (plan §7).

## Testing Decisions

- **Test external behavior, not implementation.** The unit under test is the **Query orchestrator**; the three seams (`FrameSource`, `SemanticBrain`, `SpeechIO`) are replaced with fakes so tests run in milliseconds with no models/hardware. The fakes are test-only; the real impls ship.
- **Behaviors to cover** (observable outcomes only): Describe vs Query prompt selection; hold opens mic only while held; Save persists exactly one frame and fires the "captured" earcon; multi-turn latches to the last frame and resets on a fresh Describe; ~45s session expiry; ~6s Query timeout → spoken fallback; hedge output passed through as spoken; low-ASR-confidence → confirm path; detected language routed to both `SemanticBrain` and `SpeechIO.speak`; press-down always emits the "heard" earcon; every press yields some `speak` call.
- **Contract tests** for each real seam impl (thin): `PhoneCameraSource` returns a frame; `SpeechIO` round-trips a canned clip; `SemanticBrain` returns text for a canned frame — kept minimal and run separately from the fast orchestrator suite.
- **Prior art**: none — greenfield module. Establish the fake-at-seam pattern here as the reference for later features.
- **On-device verification** (not unit tests): the three pre-event gate-spikes (GenieX/Qwen3-VL, AI4Bharat speech incl. TTS latency, WebRTC/FrameSource) and a sustained-session thermal test.

## Out of Scope

- Band hardware, the band's depth sensing, and band-RGB-over-WebRTC (Stage-3 stretch / band track).
- Navigation / Google Maps Directions API → band haptics (roadmap, ADR-0006).
- **Automatic / button-free capture** (full **Explore mode**) — the 10-min cross-scene *memory* is now in scope, but *auto*-capture (motion/timer-triggered) is not.
- Indoor "take me back to that door" backtracking (visual SLAM — far roadmap).
- **Sarvam Edge** (quality upgrade behind the `SpeechIO` seam, needs SDK access).
- Danger/threat detection and a custom SOS (use the OS's native SOS); the module does not own "danger."
- Bystander face-blurring, camera-active LED (roadmap; handled by on-device + ephemeral framing).
- Depth-fused spoken distance (calibration-gated roadmap, ADR-0004 note).
- Corrections / federated personalization (roadmap).

## Further Notes

- **Pre-event gates (must pass, running now as spikes):** GenieX loads Qwen3-VL-4B on the provided Snapdragon (else llama.cpp); AI4Bharat IndicConformer + on-device TTS work and **TTS latency is acceptable**; a frame arrives via `FrameSource` (phone camera guaranteed; WebRTC stretch). Plus a 10–15 min sustained-session thermal test.
- **Target-language set is a placeholder** (Hindi/Tamil/Telugu/Bengali/Marathi) — confirm before locking.
- **Right-sized, not cheapest**: NPU spent on the 4B VLM; free/CPU where already good enough (30M ASR, Android TTS); paid Sarvam Edge upgrade behind the seam. Only real quality trade-off is TTS naturalness.
- Demo presents mostly in English with one live Hindi Query to show multilingual; the phone-camera path must stand 100% on its own.
