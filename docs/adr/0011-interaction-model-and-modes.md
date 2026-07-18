# Interaction model: three buttons, tap = where, hold = what

> **Decided 2026-07-18 (grilled) — see `CONTEXT.md` ("Interaction model").** Supersedes the single-button tap/hold/double-press scheme and retires the double-press gesture entirely. Realises ADR-0001's where/what split *in the interaction itself*.

## Context

The band has **three physical buttons** (new information). The old scheme crammed everything onto one: single = Describe, hold = Query, **double = Save** — and double-press is precisely the timing-ambiguous gesture that the old "gesture disambiguation" ticket existed to fix (hard for users with tremor, easy to mis-fire).

Separately, the two things a blind user asks for have **wildly different costs**: "what's in my path and how far" is cheap (detector + depth, ~0.5 s, no network), while "describe this / answer my question" is expensive (VLM 3–5 s + translation + TTS).

## Decision

**Three buttons × (tap, hold) = six unambiguous actions. No double-press.**

| Button | Tap | Hold |
|---|---|---|
| **1 (primary)** | **"Where"** — object distances + directions (detector + depth, **no VLM**) | **"What"** — mic opens; on release: **spoke → Query**, **silent → Describe** |
| **2** | toggle **continuous guidance** | repeat last utterance |
| **3** | toggle **privacy mode** (on-device only ↔ cloud allowed) | Save |

**Button 1 tap → the fast lane.** *"Chair, two metres, centre."* No VLM, no network, ~0.5 s. This is the frequent, walking-along action, and it is also the **safety-relevant** one — so the most important information is the cheapest to obtain.

**Button 1 hold → the rich lane, question optional.** Press-down captures the frame **immediately** (the scene faced at press time) and opens the mic. On release we branch on whether speech was actually captured: a question → Query; silence → Describe. This means **no mode to choose**, it is **forgiving** (hold and say nothing → still a useful description), and **ASR failure degrades to Describe** rather than a dead press. Every press yields speech.

**Speech only after release.** Speaking while the mic is open would feed our own TTS back into ASR. So the fast-lane result is computed *during* the hold and spoken *immediately on release* — the instant-feedback feel is preserved, measured from release.

**Two-stage speaking** (for the hold path): the fast distance first (already computed, ~0 s), then the VLM description ~3–4 s later. The user gets actionable spatial info immediately and descriptive colour after.

**A physical privacy toggle** (button 3 tap) is deliberate accessibility: a blind user can *feel* which mode they are in, and the mode is spoken on switch — more trustworthy than a buried setting.

## Modes

- **On-demand** (default) — the table above.
- **Continuous guidance** — periodic spatial narration. **Driven by the on-device fast lane, not the cloud**: reaction-speed guidance cannot sit behind a 300 ms–1 s network hop, and the **band's haptics already provide continuous obstacle feedback locally at zero cost to the phone**. Cloud may add *periodic richness*, never the reflex layer.
- **Privacy mode** — on-device only; blocks the cloud tier. Spoken on entry/exit.
- **Task guidance** ("help me make coffee") — multi-step, **stateful**, needs repeated large-model calls; a genuine cloud case. **Scope (MVP mode vs. one rehearsed scripted demo) is still open.**
- *Flagged, not scoped:* **Reading mode** (OCR) and **Find mode** ("where's the door?").

## Rationale / alternatives considered

- **Keep one button + double-press:** rejected — three buttons remove the need, and double-press is the least reliable gesture for this user group.
- **Separate Describe and Query gestures:** rejected — the hold-with-optional-question collapses them and eliminates the "wrong mode" failure entirely.
- **Continuous mode via cloud** (proposed on thermal grounds — sustained on-device VLM *would* throttle): **partly accepted, partly not.** Sustained *rich* understanding does belong in the cloud, but (a) continuous cloud is not thermally free either — camera + encoder + radio running nonstop are themselves major heat/battery sources, and (b) continuous *navigation* should not touch the phone at all, since the band already does it locally.

## Consequences

- Requires the band's three buttons; the phone-only build must map these to on-screen controls for development.
- Continuous cloud mode is a **real privacy escalation** (streaming everything the user sees, bystanders included) — it must be explicit opt-in and spoken on entry, not a silent setting.
- "Repeat last" needs the last utterance retained; Save needs the frame retained.
- Task-guidance scope is **unresolved** and must not be assumed by implementers.
