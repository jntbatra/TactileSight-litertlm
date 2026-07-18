"""TactileSight cloud VLM server.

The phone's CloudBrain POSTs one JPEG + the mode/question/language to /v1/describe and
gets back the spoken answer. The model runs behind a swappable VlmBackend (see
backends/__init__.py) so the same server runs on a laptop tonight and on Qualcomm Cloud
AI 100 at the demo — only TS_VLM_BACKEND changes.

Run:  TS_VLM_BACKEND=mock uvicorn app:app --host 0.0.0.0 --port 8000
"""

from __future__ import annotations

import base64
import binascii

from fastapi import FastAPI, HTTPException
from pydantic import BaseModel

from backends import load_backend
from prompt import build_prompt

app = FastAPI(title="TactileSight Cloud VLM", version="0.1")

# Chosen once at startup from TS_VLM_BACKEND. A real (qefficient) backend compiles lazily
# on the first request, so startup stays fast regardless of backend.
_backend = load_backend()


class DescribeRequest(BaseModel):
    mode: str  # "DESCRIBE" | "QUERY"
    question: str | None = None
    language: str = "en"
    image_b64: str


class DescribeResponse(BaseModel):
    spoken: str
    rich: str | None = None
    confident: bool = True


@app.get("/health")
def health() -> dict:
    return {"status": "ok", "backend": type(_backend).__name__}


@app.post("/v1/describe", response_model=DescribeResponse)
def describe(req: DescribeRequest) -> DescribeResponse:
    try:
        image_bytes = base64.b64decode(req.image_b64, validate=True)
    except (binascii.Error, ValueError):
        raise HTTPException(status_code=400, detail="image_b64 is not valid base64")
    if not image_bytes:
        raise HTTPException(status_code=400, detail="image_b64 is empty")

    prompt = build_prompt(req.mode, req.question, req.language)
    text = _backend.generate(image_bytes, prompt).strip()
    if not text:
        return DescribeResponse(spoken="Nothing clear ahead.", rich=None, confident=False)
    return DescribeResponse(spoken=text, rich=text, confident=True)
