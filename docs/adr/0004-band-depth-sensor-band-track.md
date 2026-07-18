# Band track: USB depth camera primary, ultrasonic fallback

> **Note added 2026-07-11 — see `CONTEXT.md`.** Decision unchanged. Depth stays a **haptic** channel; the Semantic Module speaks **position, never distance** (an RGB feed can't measure it). Fusing the band's depth into *spoken* answers ("~3m ahead") is a **calibration-gated roadmap** item — it needs the band RGB + depth sensors co-located and registered, which the hackathon build doesn't have.

(Band track decision — recorded here for handoff; the mobile/Semantic-Module team does not own this.)

The Haptic Band's depth sensing commits to a USB/UVC depth camera (RealSense-class) as primary, because it enumerates over standard USB and carries the lightest driver risk on the UNO Q's Qualcomm Linux image. A cheap ultrasonic sensor array is the demo-day fallback, giving coarse distance so the haptic demo still works if the depth-camera driver fails. Both are to be verified on real UNO Q hardware in the ~9 days before the event (event: 2026-07-18/19). This closes the plan's flagged #1 risk (previously "no confirmed driver, no test rig").
