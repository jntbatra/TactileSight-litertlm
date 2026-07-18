# Navigation: Google Maps + vision "last-10m" fusion (roadmap)

> **Updated 2026-07-11 — see `CONTEXT.md` (Last-10m fusion).**
> Routing uses the **Google Maps Directions API as an engine only**: the **phone computes the route, the Band renders turn cues as haptics** (never Maps' voice, which would occupy hearing). "Perception stage / camera + OCR" below now means the **VLM + OCR** (ADR-0003 superseded). "Take me back to a door I just passed" indoors is a separate, far-harder problem (visual SLAM / indoor localization) — roadmap-of-roadmap. The privacy boundary below still holds (coords to Google, imagery never leaves the device).

A future navigation module uses Google Maps for macro routing (get to the destination block) and the existing Perception stage (camera + OCR) for the last ~10 metres (find the actual entrance, confirm arrival by reading signs). The value is the bridge: GPS alone lands a blind user on the right block but can't find the door (~5m accuracy); vision closes that gap. Chosen over plain turn-by-turn (not novel) as the flagship, commendable angle.

Privacy boundary (deliberate, and a deviation from the module's on-device rule): **routing requires the internet** — the destination and route go to Google Maps like any nav app. Vision stays on-device: photos and the Perception/Narrator pipeline never leave the phone. So the privacy promise is scoped to imagery, not to routing. State this explicitly in the pitch so it isn't read as contradicting the on-device premise.

Roadmap — not built for the July 18–19 hackathon.
