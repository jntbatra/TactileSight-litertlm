# Scene memory (10-min, cross-scene)

Status: ready-for-agent

## What to build

On every capture, store **{downscaled frame + the VLM's rich description}** in a **~10-min rolling buffer** (auto-evicting). Pass recent scenes to the VLM so the user can query **earlier** scenes ("what colour was the door we just saw?"), re-looking at a buffered frame; past-scene answers are marked temporally. `SceneMemory` is a plain, directly-tested component.

## Acceptance criteria

- [ ] Describe scene A, then scene B, then ask about A → a correct answer referencing the earlier scene.
- [ ] Entries older than ~10 min are evicted.
- [ ] Nothing persists beyond the buffer except an explicit Save; on-device only.
- [ ] `SceneMemory` eviction logic is unit-tested (no hardware).

## Blocked by

- Real VLM Describe
- Query answered (VLM)
