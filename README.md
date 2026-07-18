# TactileSight

**A wearable that tells a blind person what is around them, and how far away it is, in their own language.**

Built for the Qualcomm Snapdragon Multiverse Hackathon (Noida, 18–19 July 2026).

---

## The problem

A white cane reaches about a metre and tells you only that *something* is there. It cannot tell you the doorway is on your left, that the thing ahead is a person rather than a pillar, or what the sign above the counter says.

Existing AI assistants describe a photo, but they **guess at distance** from a flat image. A confident wrong distance is worse than no distance at all when you are walking.

## What TactileSight does

Two layers, deliberately split by what each is good at:

| Layer | Answers | How | Speed |
|---|---|---|---|
| **Haptic band** | **Where**: obstacle geometry | depth camera → vibration, on the band itself | milliseconds, works with the phone off |
| **Phone module** | **What**: identity, text, context | camera → vision-language model → speech | on demand |

The band handles reflexes. The phone handles understanding. Neither re-derives the other's job.

### The interaction

- **Tap** → *"Chair, two metres, centre."* Instant spatial awareness. No AI model involved, no network.
- **Hold** → ask a question, or say nothing and get a full description. *"There's a doorway on the left with a sign reading Reception."*

One button, question optional. Say nothing and you still get something useful. There is no wrong way to press it.

---

## What makes it different

**Distance is measured, not guessed.** The band carries a real depth camera, so when the system says "two metres," a sensor measured two metres. The vision model is explicitly forbidden from stating distance: it cannot measure, so it must not guess. Identity comes from the camera, distance comes from the depth sensor, and the phone fuses them.

**It works in the dark.** The depth sensor's infrared stream produces a usable image with the lights off, where an ordinary camera sees nothing. A blind user has no reason to turn a light on, so the system reads the IR frame when the room is dark.

**It says "I don't know."** Depth sensors fail on glass, dark, and reflective surfaces. Across our 21 real captures, only **62.6% of depth pixels are valid on average**, and one region measured **1% valid**. So the system requires a minimum of trustworthy pixels before it will state a distance, and otherwise says *"distance unknown."* For a navigation aid, an honest gap beats a confident fabrication.

**Your camera stays on your phone.** The vision model runs **on-device** on the Snapdragon NPU. Images are never uploaded. (Speech uses a cloud API for translation and voice. We say so plainly rather than claiming more privacy than we have.)

**It speaks your language.** The vision model works in English, then translation and speech happen in the user's language, so the system speaks fluent Punjabi, Hindi, Tamil or Telugu instead of a small model's broken approximation. Adding a language is a configuration change, not a new model.

**It runs on any phone.** Flagship phones run the model locally. Everything else falls back to a server, so the product is not limited to people who can afford a flagship.

---

## How it works

```
   BAND (Arduino UNO Q + Orbbec Astra Pro Plus)        PHONE (Snapdragon 8 Elite)
   ┌────────────────────────────────────┐              ┌──────────────────────────────┐
   │  RGB  ·  depth  ·  infrared        │              │  detector → distance         │
   │                                    │──── RGB ────▶│  vision-language model → text│
   │  depth → zones → HAPTICS           │   depth/IR   │  translate → speech          │
   │  (local, continuous, phone-free)   │              │                              │
   └────────────────────────────────────┘              └──────────────────────────────┘
        safety, milliseconds                                understanding, on demand
```

The band captures and buzzes; it runs no AI. The phone does all the thinking. They talk over one connection: the band's own Wi-Fi network, with USB as an automatic fallback.

**Measuring distance without calibration.** The infrared image and the depth map come from the *same sensor* on the *same pixel grid*. So we detect objects in the IR frame and read depth at exactly those pixels. No camera-to-camera calibration is required, and the number is a direct measurement.

---

## Technology

- **On-device vision-language models** on the Snapdragon NPU. Which runtime truly reaches the Hexagon NPU was an empirical question, not a marketing one — so we measured all of them (see below) and shipped the winner: **Qwen3-VL-4B on Qualcomm GenieX/QAIRT**, describing a scene in **260 ms**.
- **Object detection** (YOLOv11, Qualcomm AI Hub) on the NPU, for the fast spatial lane.
- **Depth sensing** via Orbbec Astra Pro Plus; haptics driven on-band.
- **Speech** via Sarvam for Indic translation, text-to-speech and speech recognition.
- **Qualcomm silicon end to end**: Snapdragon 8 Elite in the phone, Dragonwing in the band, Snapdragon X Elite serving the fallback tier.

---

## Measured performance

Every number below is from the OnePlus 15 (Snapdragon 8 Elite Gen 5 / SM8850), same capture, same prompt, taken with the runtime's own profiler. Nothing here is estimated.

### On-device execution paths

| runtime / compute unit | model | model load (once) | time to first token | prefill | decode |
|---|---|---|---|---|---|
| **GenieX QAIRT / NPU** ← shipped | Qwen3-VL-4B w4a16 | **6.7–7.2 s** | **260 ms** | **1287 tok/s** | 24–28 tok/s |
| GenieX llama.cpp / NPU (Hexagon) | Gemma-4-E4B q4_0 QAT | 29.2 s | 2688 ms | 142 tok/s | 13.5 tok/s |
| GenieX llama.cpp / GPU (Adreno) | Gemma-4-E4B q4_0 QAT | 27–32 s | 3417 ms | 93 tok/s | 11.5–16.7 tok/s |
| GenieX llama.cpp / HYBRID | Gemma-4-E4B q4_0 QAT | 31.2 s | 4087 ms | 94 tok/s | 13.3 tok/s |

**QAIRT on the NPU is an order of magnitude ahead** — 13× faster to first token than the GPU, 9× the prefill throughput, and it loads in a quarter of the time. That is the path the app ships on.

Two findings worth stating plainly, because both were open questions:

- **llama.cpp genuinely does reach Hexagon.** A community GGUF runs on the NPU with no QAIRT bundle, ~27% better first-token latency than the GPU. It matters because QAIRT only loads architectures it has a compiled factory for — currently two — so this is the route for everything else.
- **HYBRID is the slowest option.** It matches the GPU on throughput and adds ~20% latency: split-execution overhead with no benefit here.

### End-to-end, one button press

| stage | time |
|---|---|
| describe (on-device, NPU) | 0.26 s |
| translate to the user's language | ~0.7 s |
| speech synthesis and playback | ~0.5 s |

Vision never leaves the phone. Only the answer *text* goes to Sarvam for translation and speech.

---

## Status

An honest snapshot of a hackathon build in progress.

**Validated:**
- **On-device VLM on the Hexagon NPU**, 260 ms to first token — the central claim, measured (see above)
- **All four compute paths benchmarked**: QAIRT/NPU, llama.cpp on NPU, GPU and HYBRID
- **Sign reading**: the model reads text off doors and signage — *"a sign that says \"ELEVATOR\""*, *"MAJOR HAZARD — ELECTRICITY & ROTATING PARTS"* — which is what makes a corridor navigable rather than merely described
- **21 real captures** from the band's camera, verified as metric depth (0.45–9.94 m)
- **RGB↔depth geometry solved**: the colour frame is cropped to the region depth can actually measure, from measured intrinsics rather than the vendor's calibration file, which is wrong above 640×480
- **Speech in 11 Indian languages**, translated then spoken, on Sarvam
- **Three inference destinations** — on-device, a private server, and Qualcomm Cloud AI 100 — with privacy mode enforced where the brain is chosen, not in the UI

**In progress:** live band integration; per-object distance from depth (#6, #8).

**Not yet proven:** ExecuTorch as a fourth runtime (#14); on-device OCR (#20) — the VLM reads signs well, but not reliably enough on every engine to call it solved.

---

## Design documentation

Every significant decision is written down, with the alternatives considered and the reason for the choice:

- [`CONTEXT.md`](CONTEXT.md): the domain glossary and settled design
- [`docs/adr/`](docs/adr/): architecture decision records, including why distance is measured rather than inferred ([0013](docs/adr/0013-ir-aligned-calibration-free-distance.md)), why there are three inference engines ([0010](docs/adr/0010-three-engine-on-device-inference.md)), and the interaction model ([0011](docs/adr/0011-interaction-model-and-modes.md))
- [`TEAM.md`](TEAM.md): how to build and contribute

---

## Who this is for

Roughly 2.2 billion people live with vision impairment; around 43 million are blind, and the majority live in low- and middle-income countries where a flagship phone is not a given and English is not the first language. That is why the design insists on three things a demo could easily skip: **it must work on cheaper phones**, **it must speak Indian languages properly**, and **it must never state a distance it did not measure**.
