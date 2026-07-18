# Band ↔ Phone Interface (handoff spec for the band team)

**Status:** Rewritten 2026-07-18 for the tri-stream pipeline. Supersedes the 2026-07-11 version,
which specified RGB-only streaming and a `near/mid/far` telemetry contract — **both have changed.**

This is the contract the band (Arduino UNO Q) and the phone integrate against. Derived from
[ADR-0009](adr/0009-multi-sensor-band-pipeline.md) (pipeline, resolutions, transport) and
[ADR-0013](adr/0013-ir-aligned-calibration-free-distance.md) (how distance is actually measured).
Owner of this doc: phone team. Questions → phone team.

---

## 1. Division of labour

| | **Band (UNO Q)** — teammate's scope | **Phone (8 Elite)** — our scope |
|---|---|---|
| Owns | Everything **real-time + safety-critical** | Everything **slow + semantic** |
| Does | Capture RGB + depth + IR; depth → zones → **haptics on-band, no phone needed** | Detection, distance measurement, VLM description, speech |
| ML | **None.** Integer depth-zoning only. | All of it |

**Key guarantee:** the haptics run entirely on the UNO Q. **If the phone dies or disconnects, the
user still walks safely.** The phone only adds the semantic layer on top.

**Why no ML on the band:** the QRB2210 throttles under a real detector, and its labels would be
weaker than the phone's. The band is a smart sensor and a dumb pipe — that is the whole job.

---

## 2. Transport

**UNO Q hosts its own 5 GHz AP** (`hostapd`); the phone joins it. Private, low-latency, and **no
dependency on venue Wi-Fi** — the hall network was unusable on 2026-07-18, which is why this is not
optional.

**USB-C fallback:** UNO Q also comes up as a **USB-gadget ethernet** device (RNDIS/CDC-NCM), giving
a second IP to the same band.

**One WebRTC PeerConnection** carries everything. Offer **both** the AP address and the USB address
as **ICE candidates** — ICE then fails over automatically and neither side needs hand-written
reconnect logic. This is the single most important detail in this section: do not build two
connections and a switch.

Signaling is a tiny WebSocket on the UNO Q's known IP (AP tried first, USB second).

**One data channel carries everything** — image triplets, telemetry, button events. There is **no
video track**: nothing in this system streams continuously (see §3).

Optionally — **and only if the UNO Q has the headroom** — a low-rate preview track for the demo
dashboard. That is the band team's call; see §3b.

---

## 3. What the BAND sends the phone

### 3a. Image triplets — on demand. Nothing streams by default.

**This is the core of the contract, and it changed on 2026-07-18** (the 2026-07-11 version said
continuous RGB video). The band does **not** stream video. On request, it captures **RGB + depth +
IR in one shot** and sends all three together over the data channel.

Why: no consumer reads more than one frame per button press — tap uses depth+IR, hold uses one
triplet, and continuous guidance is *periodic*, not video. More importantly, splitting RGB onto a
separate continuous path would force the phone to timestamp-match streams to recover a coherent
triplet — something a single capture gives for free.

On `capture_request` (§4), grab all three from the **same capture instant** and send:

- **RGB 1280×720, native MJPG — do not re-encode.** The camera's own output, and the VLM's sweet
  spot (Gemma warms at 768², Qwen3-VL tiles ~1024). 1080p is pixels the model discards at extra cost.
- **Depth 640×480, `uint16`, millimetres, raw.** Do not colorize, do not scale, do not fill holes.
  **Invalid pixels must stay 0** — the phone's honesty rule depends on distinguishing "no reading"
  from "a reading of zero". Colorized depth is for humans; we need the raw array. (Drop to 320×240
  only if bandwidth genuinely bites.)
- **IR 640×480** — the near-infrared frame from the depth illuminator. **Not thermal.**

**Same-instant is a hard requirement, not a nicety.** A skewed pair silently corrupts every distance
the system speaks. One capture, three buffers, one message.

Latency budget: the round trip on the band's own AP is tens of milliseconds, against a button hold
lasting a second or more. There is room.

### 3b. Optional dashboard preview — your call

A low-rate RGB preview (**≈320×240, a few fps, H.264**, re-encoding is fine here) so the judge-facing
dashboard isn't a frozen still. **Purely cosmetic — nothing in the pipeline reads it.**

**Whether we do this is the band team's decision**, based on whether the UNO Q has the headroom.
We expect the binding constraint to be **sustained encode + radio competing with the STM32 haptic
loop**, not memory — the QRB2210 runs Linux, so WebRTC's own footprint is modest. **The haptic loop
is safety-critical and must never be starved by a cosmetic preview.** If it's even close, say no and
we drop it — the app is fully functional without it.

### 3c. Why IR matters more than it looks

The phone detects objects **on the IR frame**, then reads depth at exactly those pixels — IR and
depth come off the **same sensor on the same 640×480 grid**, so this needs **no calibration at all**.
That is what makes per-object distance work today rather than after a calibration session.

Two consequences for the band:

- **The IR stream must be the depth sensor's own IR**, not a separate camera. If it is anything
  else, tell us immediately — the whole distance path rests on this.
- **IR and depth must be the same frame instant.** A skewed pair silently corrupts every distance.

IR is also what lets the system work in the dark: when RGB is black, the phone describes the IR
frame instead.

### 3d. Telemetry — JSON on the data channel, ~5–10 Hz

```json
{
  "tMs": 172938,
  "zones":  { "left": "near", "center": "mid", "right": "far" },
  "hazard": { "curb": false, "step": false, "dropoff": false },
  "haptic": { "active": true, "pattern": "steady" },
  "battery": 0.82,
  "status": "ok"
}
```

- `zones` — coarse per-zone proximity driving the haptics. **This is for the dashboard and context
  only. Spoken distances do NOT come from here** — they come from the depth keyframe. (In the old
  version of this doc, these buckets were the only distance source. That has changed.)
- `hazard` — flags the band already detects.
- `haptic` — what the band is buzzing right now (`"steady" | "caution" | "double"`), so the phone
  can mirror it live for judges.
- `battery`, `status` — dashboard.

### 3e. Calibration — once at session start

Send the Astra's calibration blob when the session opens. The IR distance path does not need it,
but it lets us do RGB↔depth projection later as an upgrade.

⚠️ The `calib.json` we have has **wrong 1280×720 colour intrinsics** — `fy = 818.11` assumes an
anisotropic stretch from 4:3, but a 16:9 mode is a vertical **crop**, so `fy` should equal
`fx = 1090.81` (and the file's own `cy ≈ 364` agrees with the crop, contradicting its `fy`). Depth
intrinsics, 640×480 colour intrinsics, and the extrinsics are all sane. Nothing is blocked by this —
just don't build projection on those numbers without a proper checkerboard calibration at 720p.

### 3f. Button events

Send **raw down/up events with timestamps** — do **not** classify gestures on the band. The phone
recognises tap vs hold.

```json
{ "event": "button_down", "button": 1, "tMs": 172938 }
{ "event": "button_up",   "button": 1, "tMs": 173100 }
```

Three buttons, so include which one. Phone-side mapping, for reference only:
**tap = "where"** (object + measured distance + direction), **hold = "what"** (mic opens; release
with speech → Query, release in silence → Describe). **There is no double-press gesture** — it is
unreliable for users with tremor, and the band should not try to detect one.

---

## 4. What the PHONE sends the band

- **Keepalive / ack** on the data channel.
- **`capture_request`** — "capture RGB + depth + IR now and send the triplet". This is the only
  trigger; the band sends imagery in response to it and at no other time.
- **(Optional) semantic hazard hint** the depth sensor might miss:
  ```json
  { "cue": "hazard", "zone": "center", "reason": "glass door" }
  ```
  The band **may ignore this** — its obstacle haptics never depend on the phone.

---

## 5. Fallback (phone-side — informational)

The phone keeps a **phone-camera + bundled-test-scene fallback** for development and demo insurance.
If the band link is down, the phone runs the full semantic stack on its own camera, and the 21
recorded captures drive the whole distance pipeline with no hardware at all.

**This changes nothing the band team needs to build.** It means the phone team isn't blocked waiting
for the band, and the on-stage demo can't be killed by a WebRTC failure.

---

## 6. Band team checklist

- [ ] Astra Pro Plus captures **all three streams** — RGB 1280×720 MJPG, depth 640×480, IR 640×480
- [ ] On `capture_request`, sends an **RGB + depth + IR triplet from one capture instant** over the data channel — RGB as native MJPG **not re-encoded**, depth as **raw `uint16` mm with invalid pixels left at 0**
- [ ] **No continuous imagery** — the band sends frames only in response to a request
- [ ] Calibration blob sent once at session start
- [ ] Telemetry JSON at ~5–10 Hz
- [ ] Three buttons emit raw `button_down` / `button_up` + `tMs` — no gesture classification on-band
- [ ] `hostapd` 5 GHz AP **and** USB-gadget ethernet, both offered as ICE candidates on **one** PeerConnection
- [ ] WebSocket signaling on the UNO Q
- [ ] Haptic obstacle avoidance runs **fully on-band** — verified working with the phone switched off

## 7. What we still need from you

Not blocking, but each one converts an assumption into a fact:

- [ ] **Tape-measured ground-truth distances** for a few scenes — to prove our numbers are *correct*, not merely plausible.
- [ ] **A dark scene** (lights off) — to validate the IR-in-the-dark claim.
- [ ] **A glass / reflective scene** — the worst case for depth holes, and we want to see the "distance unknown" path fire on real data.
- [ ] **Confirmation the IR stream is the depth sensor's raw IR** (see §3c).
- [ ] **A decision on the optional dashboard preview (§3b)** — can the UNO Q sustain a ~320×240
      few-fps WebRTC encode **without the STM32 haptic loop missing deadlines**? Memory, CPU and
      thermal all count. **"No" is a perfectly good answer** and costs us nothing but a livelier
      demo screen — the haptic loop wins every time.
