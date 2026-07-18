# OCR-grounded reading

Status: folded-into-#1 (stretch fallback only)

## What to build

**Design pivot:** text reading is now handled by **Qwen3-VL-4B itself** — it reads signs/labels in the frame natively as part of Describe/Query. There is **no separate OCR model in the MVP**, so this is **not a standalone build task**. It remains only as a **stretch fallback**: *if* on-device testing shows the VLM misreads text, add a dedicated OCR pass (e.g. PaddleOCR) on the frame and inject the recognized text into the prompt as trusted ground truth. See `docs/phone-module.md` (OCR folded into the VLM).

## Acceptance criteria (only if the stretch fallback is pursued)

- [ ] Point at a sign → the spoken output includes the sign's text, read correctly.
- [ ] No text in frame → no regression to plain Describe.
- [ ] OCR runs on-device.

## Blocked by

- Real VLM Describe — **first verify the VLM's own text-reading on-device; only build this if it proves insufficient.**
