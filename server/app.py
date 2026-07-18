"""
TactileSight cloud VLM server.

This is the "everything else is fixed" part described in the README: the
phone always talks to this exact API. Only backends.get_backend() changes,
driven by TS_VLM_BACKEND (mock / openai / qefficient).

The request/response shape is **frozen** (TEAM.md, "The server contract"):

    POST /v1/describe  {mode, question, language, image_b64} -> {spoken, rich, confident}
    GET  /health                                             -> {status, backend}

base64 JSON, not multipart. Both tracks build against this independently, so
changing it here silently breaks the phone — the two are only ever tested
together at the venue, which is the worst possible place to find out.

Run:  TS_VLM_BACKEND=mock uvicorn app:app --host 0.0.0.0 --port 8000
"""
import base64
import binascii
import logging

from fastapi import FastAPI, HTTPException
from pydantic import BaseModel

from backends import get_backend, get_backend_name
from prompt import build_prompt

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger("tactilesight")

app = FastAPI(title="TactileSight cloud VLM server", version="0.1")

# Phones send JPEG frames from the band camera. 10 MB is generous headroom
# for a single frame at typical compression.
MAX_IMAGE_BYTES = 10 * 1024 * 1024


class DescribeRequest(BaseModel):
    mode: str  # "DESCRIBE" | "QUERY"
    question: str | None = None
    language: str = "en"
    image_b64: str


class DescribeResponse(BaseModel):
    spoken: str
    rich: str | None = None
    confident: bool = True


class HealthResponse(BaseModel):
    status: str
    backend: str


@app.get("/health", response_model=HealthResponse)
async def health() -> HealthResponse:
    """Lets the phone (or you) confirm which backend is live before demoing."""
    return HealthResponse(status="ok", backend=get_backend_name())


@app.post("/v1/describe", response_model=DescribeResponse)
async def describe(request: DescribeRequest) -> DescribeResponse:
    """
    The one seam: phone POSTs a single frame plus mode/question/language,
    and gets back one short spoken-style sentence. What runs behind this
    endpoint is decided entirely by TS_VLM_BACKEND.
    """
    try:
        image_bytes = base64.b64decode(request.image_b64, validate=True)
    except (binascii.Error, ValueError) as exc:
        raise HTTPException(status_code=400, detail="image_b64 is not valid base64.") from exc

    if not image_bytes:
        raise HTTPException(status_code=400, detail="Empty image.")
    if len(image_bytes) > MAX_IMAGE_BYTES:
        raise HTTPException(status_code=413, detail="Image too large.")

    name, backend = get_backend()
    prompt = build_prompt(request.mode, request.question, request.language)

    try:
        spoken = await backend.describe_image(image_bytes, prompt)
    except Exception as exc:  # noqa: BLE001 - surface backend failures as 502s
        logger.exception("Backend %r failed to describe image", name)
        raise HTTPException(
            status_code=502, detail=f"Backend {name!r} failed: {exc}"
        ) from exc

    # An empty answer is a failure the phone must hear about rather than a
    # silent press: it degrades to spoken fallback on `confident=False`.
    return DescribeResponse(spoken=spoken, rich=None, confident=bool(spoken.strip()))
