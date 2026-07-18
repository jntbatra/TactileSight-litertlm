# Hold-to-record + ASR (echo back)

Status: ready-for-agent

## What to build

Hold-to-talk: while the button is held the mic records; on release the audio is transcribed **on-device** (AI4Bharat IndicConformer via sherpa-onnx; English first) and the phone **speaks back what it heard** ("you asked: is the door open"). Proves the mic + ASR path and doubles as the implicit-echo feature.

## Acceptance criteria

- [ ] Hold, speak, release → the phone speaks the transcribed text back.
- [ ] Mic is open only while held; nothing recorded otherwise.
- [ ] Audio is 16 kHz mono; transcription is on-device (no cloud, Android STT not used).
- [ ] Recording + transcription live behind the `SpeechIO` seam.

## Blocked by

- One-button gesture disambiguation
