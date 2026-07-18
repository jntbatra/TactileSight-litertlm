# Always-on foreground service

Status: ready-for-agent

## What to build

Run capture + orchestration as a persistent **foreground service** that **auto-starts on boot**, so the button works anytime without opening the app (mobile-free).

## Acceptance criteria

- [ ] After a reboot, pressing the button works without opening the app.
- [ ] A foreground-service notification is shown (as required by the OS).
- [ ] Camera/mic are acquired and released responsibly.

## Blocked by

None — can start immediately.
