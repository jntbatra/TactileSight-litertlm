# Phase 4 — Cloud fallback tier ("every phone can run it")

Status: ready-for-human

## What to build

The **reach** story: phones that can't run a VLM on-device still work, via the existing `server/`. This was the team's original core framing — *"NPU on a device that can run it for privacy, otherwise cloud so all devices can run"* — kept **in MVP scope but sequenced after on-device**, because the demo phone (8 Elite) always runs on-device and the cloud tier never fires on stage.

See `docs/adr/0008-cloud-fallback-tier.md` (carried over — the two-tier decision still stands; only its *on-device* half was replaced by ADR-0010's three engines).

- **`CloudBrain`** implementing `SemanticBrain` → HTTP POST (frame + mode/question/language) → `server/`.
- **`server/`** is already in this repo (FastAPI, swappable VLM backend: `mock` | `openai`-compatible). Model blobs were **not** copied — re-copy or re-point when wiring this up.
- **Capability routing:** capable device → on-device engine; otherwise → cloud. **Runtime fallback** if the on-device model fails to load.
- **Respect privacy mode** (ADR-0011): privacy mode **must block** the cloud tier outright.
- **Spoken tier notice** once at setup — on-device "runs on your device" / cloud "processing happens in the cloud". A notice, not a consent gate.

## Acceptance criteria

- [ ] A device that can't run on-device lands on `CloudBrain` and gets a real description.
- [ ] On-device load failure **falls back to cloud at runtime** and announces the switch (the "private" promise must not be silently broken).
- [ ] **Privacy mode blocks cloud** — verified.
- [ ] The phone-camera on-device path is unaffected.

## Blocked by

Phases 1–3. Needs venue networking (phone → server) rehearsed — the hall network was unreliable (0.3–15 MB/s, repeated drops), so prefer the band's own AP or a hotspot. `server/` currently has **no auth** — do not expose it beyond a trusted LAN.
