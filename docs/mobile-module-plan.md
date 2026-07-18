# TactileSight — Semantic Module (Mobile Track) Plan

> **⚠️ HISTORICAL — superseded 2026-07-18. Do not build from this document.**
> Current plan of record: **`CONTEXT.md` → `REBUILD — settled design`**, ADRs 0009–0013, and the
> **[GitHub Issues](../../issues)**. The 2026-07-18 note below predates the rebuild decision made
> later the same day.

**Audience:** the whole team (mobile track + band track).
**Owner:** Mobile track.
**Event:** Qualcomm Snapdragon Multiverse Hackathon, Noida — July 18–19, 2026.
**Status:** design locked (see `CONTEXT.md` glossary + `docs/adr/0001–0008`). **Note (2026-07-18):** the brain is now **two-tier** — on-device hero + cloud fallback (ADR-0008); §3's Perception+Narrator diagram is superseded by the single **VLM** (per CONTEXT.md), kept for history.

---

## 1. The one-paragraph pitch

TactileSight helps blind and visually impaired users understand their surroundings. It has **two layers**:

- **Haptic Band (band track)** — tells the user *WHERE* things are. Depth sensing rendered as vibration on the head. Continuous, silent, hands-free.
- **Semantic Module (mobile track — this doc)** — tells the user *WHAT* things are. A phone runs on-device AI to identify objects and read text, spoken on demand into one earbud.

The band is your spatial sense; the module is your naming sense. They never do each other's job.

**Reach (2026-07-18):** the Semantic Module runs its VLM **on-device** on capable Snapdragon phones (privacy, the hero) and **falls back to a cloud server** on phones that can't — auto-routed by device capability, so *any* phone can run it, with the user told at setup which tier is active. See ADR-0008.

---

## 2. Why two tracks

The two layers are near-independent builds and can be developed in parallel:

| Track | Owns | Key risk |
|---|---|---|
| **Band** | Depth camera → downsample → haptic motor matrix on an Arduino UNO Q | Depth-camera driver on UNO Q's Qualcomm Linux (see ADR-0004) |
| **Mobile** | Android app: camera → detect/read → LLM → speech | Fitting the model pipeline in a comfortable per-query latency |

**The mobile track is built to stand alone** — it does not depend on the band being ready (see §6). Integration is a later swap-in, not a dependency.

---

## 3. What the Mobile Module does (the Query loop)

The user carries the phone in a pocket and wears one earbud (other ear stays open to the environment). One interaction = one **Query**:

```
  press button ─────────────► capture ONE photo (at the instant of press)
     │                              │
  earcon: click (heard you)         ▼
     │                    ┌─────────────────────────────┐
  soft tick while working │  Perception (on the NPU):   │
     │                    │   • object detector (YOLO)  │  ← pre-made models,
     │                    │   • OCR (reads text/signs)  │     downloaded, NOT trained
     ▼                    └─────────────────────────────┘
  short press = "describe"          │  facts: [chair, person, door, "EXIT"]
  long  press = hold & talk         ▼
    (mic on only while held,  ┌─────────────────────────────┐
     speech → text on-device) │  Narrator (the LLM):        │
     │                        │   turns facts into a        │
     ▼                        │   spoken sentence + answers │
   your question              │   the user's question       │
                              └─────────────────────────────┘
                                       │
                                       ▼
                        speak via phone TTS → earbud (one ear)
                        ALWAYS says something — even
                        "nothing clear ahead" / "couldn't see, try again"
```

**Two gestures, same start (photo snaps at press):**
- **Quick click** → auto-describes what's ahead. No talking.
- **Press & hold** → you speak a specific question ("is there text? is the door open?"), release → the LLM answers about that photo.

---

## 4. Locked decisions

| Area | Decision | Ref |
|---|---|---|
| Module job | Semantics (WHAT), never geometry (WHERE) | ADR-0001 |
| Camera | Phone camera for hackathon; band's wireless RGB cam later | ADR-0002 |
| Frames | One still per Query, not a video stream | ADR-0002 |
| Pipeline | Perception (detector + OCR) → Narrator LLM, not a single VLM | ADR-0003 |
| Runtime | Perception on Hexagon NPU via **Qualcomm AI Hub**; LLM via **llama.cpp** | ADR-0005 |
| Models | **Precompiled YOLO + OCR + Whisper from AI Hub** — downloaded, never trained | ADR-0005 |
| LLM | 1–3B (Llama 3.2 3B / Phi-3.5-mini), GGUF | ADR-0005 |
| STT / TTS | On-device Whisper (AI Hub) / Android built-in offline TTS | ADR-0005 |
| Trigger | Phone volume button now; band button later | CONTEXT.md |
| Output | One in-ear earbud, other ear open; on-demand only | CONTEXT.md |
| Feedback | Earcon on press + processing; always speak a result | CONTEXT.md |
| Privacy | Everything on-device — no photo or question ever leaves the phone | ADR-0003/0005 |
| Federated learning | Roadmap only; ship a "was this right?" feedback stub | CONTEXT.md |

**We build the APP, not the models.** The models are ready-made Lego blocks from AI Hub. The 24h work is the Android app that wires them together: button handling, camera, passing data model-to-model, timing, earcons, spoken output.

---

## 5. Tech stack & devices

**Stack:** Android (Kotlin) app · Qualcomm AI Hub (QNN) for NPU perception · llama.cpp (GGUF) for the LLM · Android offline TTS · ML Kit kept only as a last-resort escape hatch if AI Hub integration jams.

**Devices:**

| Device | Chip | Role |
|---|---|---|
| **Samsung S23** (ours) | Snapdragon 8 Gen 2 for Galaxy | **Primary dev + test** — full NPU pipeline |
| Loaner OnePlus (given at event) | Snapdragon 8-series | Final demo — same APK |
| Pixel 7 | Google Tensor (NOT Snapdragon) | ❌ can't run the Qualcomm NPU path — ignore |

Since the S23 is Snapdragon, an APK tested on it runs the same on the loaner.

**Test ladder:**
```
1. Python prototype on PC      → prove the pipeline logic
2. llama.cpp on PC             → tune the Narrator's answers
3. S23 (USB → Android Studio)  → full NPU pipeline, real testing   ← main loop
4. AI Hub cloud devices        → optional benchmark on other Snapdragons
5. Loaner OnePlus at event     → deploy same APK, demo
```
Emulator note: Android Studio's emulator has **no NPU** — good for UI/flow only, useless for the NPU models. Prefer running on the S23 directly.

---

## 6. How the Mobile Module combines with the Band

Today the module is standalone (phone camera + volume button). Integration with the band is a **clean swap of two inputs** — nothing in the pipeline changes:

| Input | Standalone (hackathon) | Integrated (later) |
|---|---|---|
| **Frame source** | Phone camera | Band's head-mounted RGB camera |
| **Trigger** | Phone volume button | Band-mounted button |

### The integration contract (what each track must agree on)

For the band to feed the module, the band track exposes two things over the local wireless link:

1. **Trigger signal** — band button press → tells the phone app "a Query started" (which gesture: short vs hold).
2. **Scene Frame** — on that press, the band's Arduino UNO Q captures one RGB still and sends it to the phone as a single JPEG over Wi-Fi (UNO Q runs a Wi-Fi AP or joins the phone's hotspot). One frame per Query — no continuous streaming, to keep the link cheap.

The phone app treats "where the frame and trigger come from" as pluggable. Standalone = phone camera + volume key. Integrated = the band's Wi-Fi frame + button. **The Perception → Narrator → speech pipeline is identical either way.**

### The combined user experience

```
   HEAD:  [ Haptic Band ]  ── continuous vibration ──►  WHERE things are (silent)
                │
                │ (later: RGB cam + button on band, Wi-Fi to phone)
                ▼
 POCKET:  [ Phone / Semantic Module ]  ── on button press ──►  WHAT things are
                │
                ▼
    EAR:  [ one earbud ]  ── spoken answer ──►  identity + text, on demand
          (other ear open to the world)
```

The two channels never collide: the band's *where* is continuous and silent (touch), the module's *what* is on-demand and spoken. Hearing stays free by default; the user pulls semantics only when they want them.

### Integration order (do NOT block the mobile demo on this)

1. Mobile track ships the standalone module first (phone camera + volume button).
2. Band track ships depth→haptic first.
3. **Only after both stand alone**, wire the band's frame + button into the phone app via the contract above. This is the stretch/integration step, not a hackathon-day-one dependency.

---

## 7. Build plan (mobile track, 24h)

Staged so there's always a working demo:

- **Stage 1 — core loop (must-have):** Android app + volume-button trigger + phone-camera capture + one detector (YOLO) on NPU + Android TTS → speaks "chair ahead, person on the right." Demoable alone.
- **Stage 2 — add language:** wire the Narrator LLM (llama.cpp) so output is a natural sentence, not raw labels. Add OCR so it reads signs/text.
- **Stage 3 — add Q&A:** hold-to-talk → Whisper STT → LLM answers a spoken question about the frame.
- **Stage 4 — polish (stretch):** earcons, "was this right?" feedback stub (federated-learning teaser), band integration if the band track is ready.

**Rule:** don't start a stage until the previous one runs. A working narrow demo beats a broken ambitious one.

---

## 8. Later modules (roadmap — not hackathon scope)

These extend the module after the core loop works. Mention in the pitch as vision; do not build for July 18–19.

- **Multilingual** — let the user get answers in their own language (Hindi, Tamil, etc.), not just English. Swap the STT / Narrator / TTS for language-specific on-device models — e.g. **Sarvam AI's Indian-language models** (or similar). Kept on-device, so the privacy premise holds. Big accessibility win for Indian users.
- **Federated learning** — personalize from user Corrections ("that's a mug, not a cup"); share the *lessons* (gradients), never the photos, across users. Ship a "was this right?" feedback stub at the hackathon to make the concept demoable.
- **Explore mode (rolling context)** — opt-in mode (off by default) that captures on scene-change/motion, not a blind timer, to build a short memory of recent observations so the Narrator can answer "what did I just pass?". Default stays strictly on-demand (only a button press captures) — no always-on camera, for battery and bystander privacy. See ADR-0007.
- **Navigation — Google Maps "last-10m" fusion** — Maps routes the user to the destination *block* (GPS, ~5m); the camera + OCR then find the actual *door* and confirm arrival by reading the sign ("Apollo Pharmacy entrance, glass door, 3 steps ahead"). Map to the block, vision to the door — the gap GPS can't close for a blind user. This is a third altitude of awareness on top of the band (immediate obstacles) and the module (what's in front). Boundary: routing needs the internet (destination/route go to Google, like any nav app), but **vision stays on-device** — photos never leave the phone. See ADR-0006.
- **Band integration** — replace phone camera + volume button with the band's RGB camera + button over Wi-Fi (see §6). Same pipeline.

---

*Context captured from the design grilling session. Source of truth: `CONTEXT.md` (glossary) and `docs/adr/0001–0005`. Update those, not just this doc, if a decision changes.*
