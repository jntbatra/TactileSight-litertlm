# Capture policy: on-demand by default, no blind always-on capture

> **Note 2026-07-18 — the *policy* still holds, one *gesture* reference is stale.** On-demand capture
> and the auto-expiring scene buffer are current. But **there is no double-press gesture any more**
> ([ADR-0011](0011-interaction-model-and-modes.md): three buttons × tap/hold only), so "double-press
> Save" below no longer names a real control. Save is unassigned — if explicit persistence is needed,
> it gets a button, not a double-press.

> **Clarified 2026-07-11 — see `CONTEXT.md`.** Still holds. With the persistent WebRTC feed (ADR-0002 updated), the *wire* may carry a continuous stream, but the policy applies to what is **retained / processed / spoken**: only the button-press frame is kept. Preview frames are discarded; explicit **double-press Save** is the one path to persistence. So on-demand holds at the layer that matters.
>
> **Updated 2026-07-11 (Scene memory).** Capture stays **on-demand** — only a button press captures, and *automatic* capture (Explore mode) remains rejected/roadmap. But retention is no longer "each Query independent": every capture adds **{downscaled frame + rich VLM description}** to a **~10-min rolling buffer** so the user can query *earlier* scenes ("what colour was the door we just saw?"). The buffer auto-clears (privacy) and only Save persists. The old "no rolling log / independent Queries" framing is superseded by this bounded, auto-expiring, on-device Scene memory.

The module captures a Scene Frame only on a user button press (on-demand). We deliberately reject continuous always-on capture (e.g. a photo every 30s) as a default: it drains battery, heats the phone (NPU thermal throttling), floods the pipeline with blurry/stale frames while walking, and — most importantly — a wearable that photographs constantly is far more privacy-invasive to bystanders and a harder ethical sell than one that snaps only when asked. On-demand also keeps the module's job clean: the band gives *continuous* awareness (where), the module gives *on-demand* awareness (what).

Continuous context is offered instead as an opt-in **Explore mode** (roadmap): off by default, and when on it captures on scene-change/motion rather than a fixed timer, building a short rolling memory so the Narrator can answer "what did I just pass?". Battery and privacy stay under the user's explicit control. Not built for the hackathon.
