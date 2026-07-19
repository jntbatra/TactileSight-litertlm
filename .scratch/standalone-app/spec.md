# Spec: one button, one phone — TactileSight as a standalone app

Status: ready-for-agent
Raised: 2026-07-19, day 2 of the hackathon.

## Why

Five changes, one theme: **the screen should stop being a dev console and start
being the app.** Four of them are interaction; the fifth (phone camera) is the
one that changes what the product *is*.

## 1. One button, not two

Today: `Describe` and `Hold to ask` are separate buttons. That was a deliberate
choice — a long-press on glass has no edge a finger can feel — but it is the
wrong one, because the band's button 1 carries **both** gestures and the screen
should teach the gesture the hardware uses.

Note this is a **return to the original PRD**, not a departure from it:

> - **Single-press = Describe**: captures one Scene Frame and speaks a short description
> - **Hold = Query**: hold-to-talk; the user asks a question about that frame

- Tap (< 400 ms) → describe.
- Hold → capture at press-down, record while held, ask at release.
- The mic opens on **every** press-down and the buffer is discarded on a tap.
  Starting it at the 400 ms mark instead would eat the first word of every
  question, which is worse than a wasted third of a second of audio.

## 2. Dev / user mode

A real switch, top right. Dev shows the machinery (frame source, server
address, model name, prompt, capture picker, preview). User mode shows the app.

Persisted, defaulting to **dev** — this build is still a development build, and
a demo that has to re-tidy itself after every ColorOS kill is a demo that
fumbles on stage.

**Always visible.** A mode you cannot leave without clearing app data is a trap.

## 3. Language: 🌐, and nothing else

The `Choose language by voice` button goes away; the 🌐 in the header takes its
place and runs the same spoken setup. One control, and the one that needs no
sight to operate.

## 4. Model state must be on screen

Today the status reads `Ready · GenieX (qairt/npu)` **before the model is
loaded**, and the first press after launch comes back *"Sorry, I could not see
that."* The status was not describing the model; it was describing the picker.

- Warm the model at startup rather than on first press.
- Report what is true: `Loading model…` → `Ready · GenieX (qairt/npu)` →
  `Model failed to load`.
- A press while loading waits for the model instead of failing.

## 5. Phone camera — the standalone path

A `FrameSource` backed by the phone's own camera, selectable at the top of the
screen in both modes.

**It speaks no distances.** ADR-0013's rule is that the VLM never states a
distance, so that every number the user hears is a measurement from depth. A
phone has no depth sensor, so in this mode there are no numbers — not estimated
ones. A blind user stepping off a guessed metre is the exact failure the depth
work exists to prevent.

So the two modes are honestly different products:

| | band | phone camera |
|---|---|---|
| answers | what **and** how far | what |
| distance | measured, from depth | none, ever |
| needs | the band | nothing |

Object detection still runs — YOLO reads colour — so detections arrive
unmeasured and flow into the sentence without a number. That path already
exists for glass and out-of-range objects; this reuses it rather than adding a
branch.

### Acceptance

- [ ] One button: tap describes, hold asks.
- [ ] Dev/user switch, persisted, always reachable.
- [ ] 🌐 runs spoken setup; the old button is gone.
- [ ] Status tracks the model, and a press during load waits rather than fails.
- [ ] Phone camera describes a live scene with no distance in the sentence.
- [ ] Band path unchanged: measured distances still spoken.
