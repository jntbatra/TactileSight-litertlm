# NFC tap-to-launch

Status: ready-for-agent

## What to build

An **NFC tag launches the app** (or the Play Store if not installed); with setup incomplete it starts **voice-guided onboarding**. NFC tap-to-pair / WebRTC-signaling bootstrap is out of scope here (rides with the band stretch).

## Acceptance criteria

- [ ] Tapping the phone to the NFC tag launches the app.
- [ ] First launch with no setup → voice-guided onboarding begins.

## Blocked by

- Always-on foreground service
