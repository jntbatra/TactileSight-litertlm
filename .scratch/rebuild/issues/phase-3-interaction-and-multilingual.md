# Phase 3 — Full interaction model + multilingual Query

Status: ready-for-agent

## What to build

The complete three-button interaction from `docs/adr/0011-interaction-model-and-modes.md`, plus spoken questions via Sarvam ASR.

- **Button 1 hold** → capture the frame **at press-down**, open the mic; **on release**: speech captured → **Query**; silence → **Describe**. ASR failure **degrades to Describe** — never a dead press.
- **Speech only after release** (avoids TTS feeding back into the open mic).
- **Two-stage speaking**: the already-computed fast-lane distance first, the VLM description ~3–4 s later.
- **Sarvam ASR** → English → VLM; answer → Sarvam Translate → Sarvam TTS.
- **Language picker**: demo path Punjabi + Hindi + English, but **expose every Sarvam-supported language** so a judge can switch live.
- **Button 2**: tap = toggle continuous guidance (**on-device fast lane**, not cloud), hold = repeat last.
- **Button 3**: tap = toggle **privacy mode** (spoken on switch), hold = Save.
- Phone-only dev build maps the three buttons to on-screen controls.

## Acceptance criteria

- [ ] Hold + spoken question → answered about the frame captured at press-down.
- [ ] Hold + silence → Describe. ASR garbage/failure → Describe. **Every press yields speech.**
- [ ] No TTS audio leaks into ASR (nothing spoken while the mic is open).
- [ ] Switching language mid-session works without reloading the VLM (VLM stays English).
- [ ] Privacy mode audibly announced and actually blocks the cloud tier.
- [ ] **No double-press gesture exists anywhere.**

## Blocked by

Phase 1 (speech + VLM) and Phase 2 (fast lane, for the two-stage speak). Band buttons are teammate scope; on-screen stand-ins unblock development.
