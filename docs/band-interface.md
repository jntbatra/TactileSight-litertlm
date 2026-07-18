# Band ↔ Phone Interface (handoff spec for the band team)

**Status:** Confirmed 2026-07-11. This is the contract the band (UNO Q) and the phone
(Semantic Module) integrate against. Owner of this doc: phone team. Questions → phone team.

---

## 1. Division of labor

| | **Band (UNO Q)** — teammate's scope | **Phone (Semantic Module)** — our scope |
|---|---|---|
| Owns | Everything **real-time + safety-critical** | Everything **slow + semantic** |
| Does | Depth sensing (depth cameras) → **haptic obstacle avoidance, on-band, no phone needed** | VLM describe/query, multilingual voice (ASR+TTS), OCR, always-on detection, dashboard |
| Camera | Captures RGB + depth | Consumes the RGB video + depth telemetry |

**Key guarantee:** the band's haptics run entirely on the UNO Q. **If the phone dies or
disconnects, the user still walks safely.** The phone only adds the rich semantic layer on top.

---

## 2. Transport

**One WebRTC peer connection**, phone ↔ UNO Q, carrying:
- **1 video track** — the RGB camera
- **1 data-channel named `telemetry`** — depth + status JSON

No BLE. No second socket for media. (A WebSocket is used only for signaling — see §5.)

---

## 3. What the BAND sends the phone

### 3a. Video track
- Codec **H.264**, **720p, 15 fps** target. If the UNO Q struggles, drop to **480p / 10 fps** —
  we optimize for **latency, not sharpness**.
- **Always streaming** while connected (continuous feed; the phone decides when to sample a frame).
- Source: the **normal RGB camera** (head-mounted, front-of-user view).

### 3b. `telemetry` data-channel — JSON, ~5–10 Hz
```json
{
  "tMs": 172938,
  "depth":  { "left": "near", "center": "mid", "right": "far" },
  "hazard": { "curb": false, "step": false, "dropoff": false },
  "haptic": { "active": true, "pattern": "steady" },
  "battery": 0.82,
  "status": "ok"
}
```
- `depth` — per-zone proximity bucket from the depth cameras: `"near" | "mid" | "far"`.
  Exact metres not required. **The phone fuses this into the VLM prompt** (e.g. "person close on
  your left"), so accurate L/C/R zoning matters more than precision.
- `hazard` — boolean flags the band already detects (curb/step/dropoff).
- `haptic` — what the band is buzzing **right now** (`pattern`: `"steady" | "caution" | "double"`),
  so the phone dashboard can mirror it live for judges.
- `battery`, `status` — for the dashboard.

### 3c. Button events (the band's physical button)
Send **raw down/up events with timestamps** — do NOT classify gestures on the band. The phone's
existing (unit-tested) `GestureRecognizer` turns these into single / hold / double:
```json
{ "event": "button_down", "tMs": 172938 }
{ "event": "button_up",   "tMs": 173100 }
```
Phone-side mapping (for reference — band doesn't need to know): single-click = Describe,
hold = Query (voice), double-click = Save.

---

## 4. What the PHONE sends the band (minimal)

- **Keepalive / ack** on the data-channel.
- **(Optional) semantic hazard hint** the depth sensor might miss (e.g. glass door, sign):
```json
{ "cue": "hazard", "zone": "center", "reason": "glass door" }
```
The band **may ignore this** — its obstacle haptics do not depend on the phone.

---

## 5. Signaling (how the WebRTC connection is established)

- Phone and UNO Q on the **same Wi-Fi / hotspot**.
- A **tiny WebSocket signaling exchange** to swap SDP offer/answer + ICE candidates.
  Simplest: phone hosts the signaling WebSocket, band connects to `ws://PHONE_IP:<port>`; they
  exchange offer/answer, then the media/telemetry flow over the direct WebRTC connection.
- ICE over local network (host candidates); no TURN server needed on a shared LAN/hotspot.

---

## 6. Fallback (phone-team side — informational)

The phone keeps a **phone-camera + mock-telemetry fallback** for development and demo insurance.
If the band link is down, the phone runs the full semantic stack on its own camera with canned
depth telemetry. **This does not change anything the band team needs to build** — it just means
the phone team isn't blocked waiting for the band, and the on-stage demo can't be killed by a
WebRTC failure.

---

## 7. Band team checklist

- [ ] UNO Q streams **H.264 720p@15fps** RGB video over a WebRTC video track
- [ ] UNO Q opens a `telemetry` data-channel and pushes the §3b JSON at ~5–10 Hz
- [ ] Physical button emits raw `button_down` / `button_up` + `tMs` over the data-channel
- [ ] Joins the phone's **same-network WebSocket signaling** to establish the connection
- [ ] Haptic obstacle avoidance runs **fully on-band** (works with the phone off)
