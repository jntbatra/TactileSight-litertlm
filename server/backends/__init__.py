"""
backends/ — every backend exposes the same tiny contract:

    async def describe_image(image_bytes: bytes) -> str

That's the one seam app.py talks to. Swapping TS_VLM_BACKEND swaps which
module answers that call; nothing else in the request path changes.
"""
import os

from . import mock, openai_compat, qefficient

_BACKENDS = {
    "mock": mock,
    "openai": openai_compat,
    "qefficient": qefficient,
}


def get_backend_name() -> str:
    return os.environ.get("TS_VLM_BACKEND", "mock").strip().lower()


def get_backend():
    """Return (name, module) for the backend selected via TS_VLM_BACKEND."""
    name = get_backend_name()
    if name not in _BACKENDS:
        raise ValueError(
            f"Unknown TS_VLM_BACKEND={name!r}. Choices: {sorted(_BACKENDS)}"
        )
    return name, _BACKENDS[name]
