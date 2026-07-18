# Semantic Module runs a Perception stage + Narrator LLM, not a single VLM

> **⚠️ SUPERSEDED 2026-07-11 — see `CONTEXT.md` → "Semantic Module — settled design".**
> The detector + Narrator split below is retired. The "what" pipeline is now a **single vision-language model (Qwen3-VL-4B)** that *sees* the Scene Frame and produces speech for both Describe and interactive Query; **OCR (PaddleOCR)** survives only as an optional grounding stage. The reason 0003 rejected a VLM ("heavier, slower, flakier in ~1–2s") no longer applies — the Band owns the instant reflex, so the on-demand what-layer tolerates 2–4s. Hallucination is now bounded by prompt constraints + OCR grounding instead of keeping the model away from pixels. Original decision preserved below for history.

The "what" pipeline is two on-device stages: a Perception stage (object detector + OCR, e.g. a YOLO-class model on the Snapdragon NPU) produces structured facts (labels, positions, read text), then a small Narrator LLM turns those facts into a spoken sentence and handles follow-up queries. We chose this over a single vision-language model (heavier, slower, flakier to fit in the ~1–2s Query budget on-device) and over a detector-only template approach (no natural-language follow-up). We rejected a cloud LLM because it would send imagery or scene data off the user's device, breaking the privacy premise.

Both stages run on the phone; no data leaves the device. The Narrator never sees the raw image — only the Perception stage's structured output — which also bounds what the LLM can hallucinate.
