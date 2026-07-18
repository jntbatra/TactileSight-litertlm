# TactileSight cloud VLM server

The **cloud tier** of TactileSight's two-tier design:

- **On device (privacy):** Snapdragon NPU via GenieX, on the phone. No image leaves the device.
- **Cloud (reach):** any phone POSTs its frame here; the VLM runs on **Qualcomm Cloud AI 100**.

Both tiers run on Qualcomm silicon. The phone talks to exactly one seam — `SemanticBrain` —
so switching tiers never touches the pipeline, only which brain is selected.

## The one thing that changes: the backend

Everything here is fixed. Only the **inference backend** moves from laptop to Cloud AI 100,
chosen by `TS_VLM_BACKEND`:

| `TS_VLM_BACKEND` | Runs on | Use |
| --- | --- | --- |
| `mock` (default) | anything | Prove phone → server → speech end to end. No model. |
| `openai` | any OpenAI-vision endpoint (local llama.cpp / vLLM) | Real descriptions tonight, no special hardware. |
| `qefficient` | **Qualcomm Cloud AI 100** (Cirrascale / AWS DL2q) | The demo target (InternVL / Molmo via QEfficient). |

## Run it tonight (mock)

```bash
cd server
python -m venv .venv && source .venv/bin/activate
pip install -r requirements.txt
TS_VLM_BACKEND=mock uvicorn app:app --host 0.0.0.0 --port 8000
```

Point the phone's `CloudBrain` at `http://<this-machine-ip>:8000`. Pick **☁ Cloud** in the
app's model picker, tap Describe — you should hear the mock answer. That proves the wire.

Tests: `TS_VLM_BACKEND=mock python -m pytest -q`

## The hackathon demo backend: Gemma 4 E4B on the laptop GPU (openai)

This is the real cloud-tier backend (2026-07-18): **Gemma 4 E4B** on **llama.cpp + CUDA**.
Two terminals:

```bash
# 1. VLM server (Gemma 4 E4B on the GPU, OpenAI-compatible on :8080)
#    Needs the GGUF + mmproj in server/models/ (gitignored) and a CUDA-built llama-server.
./run-gemma.sh

# 2. Our FastAPI server, pointed at it
TS_VLM_BACKEND=openai TS_OPENAI_BASE_URL=http://localhost:8080/v1 \
  uvicorn app:app --host 0.0.0.0 --port 8000
```

`run-gemma.sh` carries the non-obvious flags Gemma 4 needs (`--chat-template-file
gemma-vision-terse.jinja` to dodge the embedded-template crash *and* the reasoning-mode trap —
see the script's header). Any other OpenAI-vision server works too; just point
`TS_OPENAI_BASE_URL` at it.

## Move to Qualcomm Cloud AI 100 (qefficient) — the demo

1. Get an instance: **Cirrascale Cloud AI Developer Playground** (fastest self-serve),
   AWS EC2 **DL2q**, or credits from the hackathon organizers.
2. On the instance, install QEfficient — see https://github.com/quic/efficient-transformers.
3. `TS_VLM_BACKEND=qefficient TS_QEFF_MODEL=OpenGVLab/InternVL2_5-1B uvicorn app:app --port 8000`

First request compiles the model to the AIC backend (minutes); subsequent requests are fast.
`backends/qefficient.py` is the only file to verify against real hardware.
