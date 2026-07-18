# One-button gesture disambiguation

Status: ready-for-agent

## What to build

Disambiguate **single-press / hold / double-press** on the volume button and route to Describe / Query / Save, with the instant press-down **"heard" earcon**. Prefactor that both Query and Save build on. (Same gesture set the band button will use.)

## Acceptance criteria

- [ ] Single-press → Describe; hold → Query (mic-open placeholder ok); double-press → Save (placeholder ok).
- [ ] Instant earcon on press-down, before the action resolves.
- [ ] Single-press responsiveness isn't perceptibly degraded (the earcon covers disambiguation).
- [ ] Gesture routing is unit-tested with no hardware.

## Blocked by

None — can start immediately.
