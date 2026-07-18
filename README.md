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

- **On-device vision-language models** on the Snapdragon NPU, via three runtimes we are measuring against each other (**LiteRT-LM**, **Qualcomm GenieX**, and **ExecuTorch + QNN**), because which one truly reaches the Hexagon NPU is an empirical question, not a marketing one.
- **Object detection** (YOLOv11, Qualcomm AI Hub) on the NPU, for the fast spatial lane.
- **Depth sensing** via Orbbec Astra Pro Plus; haptics driven on-band.
- **Speech** via Sarvam for Indic translation, text-to-speech and speech recognition.
- **Qualcomm silicon end to end**: Snapdragon 8 Elite in the phone, Dragonwing in the band, Snapdragon X Elite serving the fallback tier.

---

## Status

An honest snapshot of a hackathon build in progress.

**Validated:**
- On-device vision-language inference **running on the Snapdragon 8 Elite** (measured: 71 tok/s prefill, 11 tok/s decode on a 4B-class model via the GPU path)
- **21 real captures** from the band's camera, verified as metric depth (0.45–9.94 m) with the IR/depth alignment confirmed
- The cloud fallback tier, proven end to end

**In progress:** the phone application, rebuilt around the three-engine architecture. Work is tracked as [19 issues](../../issues), each a vertical slice with acceptance criteria.

**Not yet proven:** which runtime actually reaches the NPU (that is [#16](../../issues/16)), and live band integration. The phone-camera path stands on its own without it.

---

## Design documentation

Every significant decision is written down, with the alternatives considered and the reason for the choice:

- [`CONTEXT.md`](CONTEXT.md): the domain glossary and settled design
- [`docs/adr/`](docs/adr/): architecture decision records, including why distance is measured rather than inferred ([0013](docs/adr/0013-ir-aligned-calibration-free-distance.md)), why there are three inference engines ([0010](docs/adr/0010-three-engine-on-device-inference.md)), and the interaction model ([0011](docs/adr/0011-interaction-model-and-modes.md))
- [`TEAM.md`](TEAM.md): how to build and contribute

---

## Who this is for

Roughly 2.2 billion people live with vision impairment; around 43 million are blind, and the majority live in low- and middle-income countries where a flagship phone is not a given and English is not the first language. That is why the design insists on three things a demo could easily skip: **it must work on cheaper phones**, **it must speak Indian languages properly**, and **it must never state a distance it did not measure**.
