"""
Run with: TS_VLM_BACKEND=mock python -m pytest -q

Only exercises the fixed part of the pipeline (app.py + the mock backend).
openai_compat / qefficient need a live model server / real Cloud AI 100
hardware respectively, so they're deliberately not covered here — smoke
test those by hand with `curl` once each is actually running.
"""
import io
import os

os.environ.setdefault("TS_VLM_BACKEND", "mock")

from fastapi.testclient import TestClient  # noqa: E402  (env must be set first)

from app import app  # noqa: E402

client = TestClient(app)


def _tiny_png_bytes() -> bytes:
    """1x1 PNG so tests don't need Pillow or a real photo fixture."""
    return bytes.fromhex(
        "89504e470d0a1a0a0000000d49484452000000010000000108060000001f15c4"
        "890000000a49444154789c6360000002000100ffff03000006000557bfabd400"
        "0000004945454e44ae426082"
    )


def test_health():
    resp = client.get("/health")
    assert resp.status_code == 200
    body = resp.json()
    assert body["status"] == "ok"
    assert body["backend"] == "mock"


def test_describe_returns_mock_description():
    files = {"image": ("frame.png", io.BytesIO(_tiny_png_bytes()), "image/png")}
    resp = client.post("/describe", files=files)
    assert resp.status_code == 200
    body = resp.json()
    assert body["backend"] == "mock"
    assert isinstance(body["description"], str)
    assert body["description"].startswith("Mock description:")


def test_describe_rejects_empty_upload():
    files = {"image": ("empty.png", io.BytesIO(b""), "image/png")}
    resp = client.post("/describe", files=files)
    assert resp.status_code == 400


def test_describe_rejects_oversized_upload(monkeypatch):
    import app as app_module

    monkeypatch.setattr(app_module, "MAX_IMAGE_BYTES", 10)
    files = {"image": ("frame.png", io.BytesIO(_tiny_png_bytes()), "image/png")}
    resp = client.post("/describe", files=files)
    assert resp.status_code == 413
