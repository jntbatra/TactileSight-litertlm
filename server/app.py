"""
TactileSight cloud VLM server.

This is the "everything else is fixed" part described in the README: the
phone always talks to this exact API. Only backends.get_backend() changes,
driven by TS_VLM_BACKEND (mock / openai / qefficient).
"""
import logging

from fastapi import FastAPI, File, HTTPException, UploadFile
from pydantic import BaseModel

from backends import get_backend, get_backend_name

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger("tactilesight")

app = FastAPI(title="TactileSight cloud VLM server")

# Phones send JPEG/PNG frames from the camera. 10 MB is generous headroom
# for a single frame at typical phone-camera compression.
MAX_IMAGE_BYTES = 10 * 1024 * 1024


class DescribeResponse(BaseModel):
    description: str
    backend: str


class HealthResponse(BaseModel):
    status: str
    backend: str


@app.get("/health", response_model=HealthResponse)
async def health() -> HealthResponse:
    """Lets the phone (or you) confirm which backend is live before demoing."""
    return HealthResponse(status="ok", backend=get_backend_name())


@app.post("/describe", response_model=DescribeResponse)
async def describe(image: UploadFile = File(...)) -> DescribeResponse:
    """
    The one seam: phone POSTs a single frame here (multipart field `image`),
    gets back one short spoken-style description. What runs behind this
    endpoint is decided entirely by TS_VLM_BACKEND.
    """
    image_bytes = await image.read()

    if not image_bytes:
        raise HTTPException(status_code=400, detail="Empty image upload.")
    if len(image_bytes) > MAX_IMAGE_BYTES:
        raise HTTPException(status_code=413, detail="Image too large.")

    name, backend = get_backend()

    try:
        description = await backend.describe_image(image_bytes)
    except Exception as exc:  # noqa: BLE001 - surface backend failures as 502s
        logger.exception("Backend %r failed to describe image", name)
        raise HTTPException(
            status_code=502, detail=f"Backend {name!r} failed: {exc}"
        ) from exc

    return DescribeResponse(description=description, backend=name)
