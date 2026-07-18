# Query answered (VLM)

Status: ready-for-agent

## What to build

Feed the transcribed question + the current frame **+ the band depth zones** to the VLM and speak the answer — completing the hold-to-Query loop: hold, ask "is the door open?", hear a real answer. Same **depth-in-prompt fusion** as Describe (#1). The answer restates the subject (implicit echo); an explicit "did you mean…?" fires only on low ASR confidence. Cross-scene questions ("what did that sign earlier say?") pull from **Scene Memory** (#7). See `docs/phone-module.md`.

## Acceptance criteria

- [ ] Hold, ask a question about the scene → a relevant spoken answer.
- [ ] The answer restates the subject asked about; uses depth zones where relevant.
- [ ] Cross-scene questions are answered from Scene Memory (#7).
- [ ] Never states distance in metres; hedges when unsure; on-device latency budget → spoken fallback.

## Blocked by

- Hold-to-record + ASR (echo back)
- Real VLM Describe
