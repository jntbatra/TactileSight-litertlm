"""The inference seam.

Everything above this line (HTTP, prompt, request/response shapes) is fixed tonight.
The *only* thing that changes when we move from a laptop to Cirrascale / Qualcomm Cloud
AI 100 is which VlmBackend `load_backend` returns. Pick it with the TS_VLM_BACKEND env var:

    mock       -> deterministic canned text; proves phone -> server -> speech end to end
    openai     -> any OpenAI-vision compatible endpoint (local llama.cpp / vLLM) for real
                  descriptions tonight without special hardware
    qefficient -> Qualcomm Cloud AI 100 via QEfficient (InternVL / Molmo). The demo target.
"""

from __future__ import annotations

import os
from typing import Protocol


class VlmBackend(Protocol):
    """Turn one image + one prompt into one answer string. The whole seam."""

    def generate(self, image_bytes: bytes, prompt: str) -> str: ...


def load_backend(name: str | None = None) -> VlmBackend:
    name = (name or os.environ.get("TS_VLM_BACKEND", "mock")).strip().lower()
    if name == "mock":
        from .mock import MockBackend

        return MockBackend()
    if name == "openai":
        from .openai_compat import OpenAiCompatBackend

        return OpenAiCompatBackend()
    if name == "qefficient":
        from .qefficient import QEfficientBackend

        return QEfficientBackend()
    raise ValueError(f"unknown TS_VLM_BACKEND {name!r}; expected mock|openai|qefficient")
