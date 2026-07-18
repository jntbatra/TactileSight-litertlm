"""
Run with: TS_VLM_BACKEND=mock python -m pytest -q

Only exercises the fixed part of the pipeline (app.py + the mock backend).
openai_compat / qefficient need a live model server / real Cloud AI 100
hardware respectively, so they're deliberately not covered here — smoke
test those by hand with `curl` once each is actually running.

These tests are the **compatibility check between the app and server tracks**
(TEAM.md). They assert the frozen wire shape — base64 JSON in, {spoken, rich,
confident} out — precisely because the phone is built against it in another
language, in another directory, by someone who cannot see this file. If a
change here needs these assertions relaxed, the phone is already broken.
"""
import base64
import os

os.environ.setdefault("TS_VLM_BACKEND", "mock")

from fastapi.testclient import TestClient  # noqa: E402  (env must be set first)

from app import app  # noqa: E402
from prompt import build_prompt  # noqa: E402

client = TestClient(app)


def _tiny_png_b64() -> str:
    """1x1 PNG so tests don't need Pillow or a real photo fixture."""
    raw = bytes.fromhex(
        "89504e470d0a1a0a0000000d49484452000000010000000108060000001f15c4"
        "890000000a49444154789c6360000002000100ffff03000006000557bfabd400"
        "0000004945454e44ae426082"
    )
    return base64.b64encode(raw).decode("ascii")


def _describe_body(**overrides) -> dict:
    body = {
        "mode": "DESCRIBE",
        "question": None,
        "language": "en",
        "image_b64": _tiny_png_b64(),
    }
    body.update(overrides)
    return body


def test_health():
    resp = client.get("/health")
    assert resp.status_code == 200
    body = resp.json()
    assert body["status"] == "ok"
    assert body["backend"] == "mock"


def test_describe_returns_spoken_answer():
    resp = client.post("/v1/describe", json=_describe_body())
    assert resp.status_code == 200
    body = resp.json()
    # The frozen response shape. The phone reads `spoken`.
    assert set(body) == {"spoken", "rich", "confident"}
    assert body["spoken"].startswith("Mock description:")
    assert body["confident"] is True


def test_describe_accepts_a_query_with_a_question():
    resp = client.post(
        "/v1/describe", json=_describe_body(mode="QUERY", question="Where is the door?")
    )
    assert resp.status_code == 200
    assert resp.json()["spoken"]


def test_describe_rejects_invalid_base64():
    resp = client.post("/v1/describe", json=_describe_body(image_b64="not!base64!"))
    assert resp.status_code == 400


def test_describe_rejects_empty_image():
    resp = client.post("/v1/describe", json=_describe_body(image_b64=""))
    assert resp.status_code == 400


def test_describe_rejects_oversized_image(monkeypatch):
    import app as app_module

    monkeypatch.setattr(app_module, "MAX_IMAGE_BYTES", 10)
    resp = client.post("/v1/describe", json=_describe_body())
    assert resp.status_code == 413


def test_prompt_carries_the_requested_language():
    """Language is per-request, not baked in — the picker at setup depends
    on this reaching the model (ADR-0012)."""
    assert "Hindi" in build_prompt("DESCRIBE", None, "Hindi")
    assert "Punjabi" in build_prompt("QUERY", "What is ahead?", "Punjabi")


def test_prompt_never_asks_for_distance():
    """Hard rule #3: the VLM cannot measure, so it must never be asked to
    guess. Distance is appended by the phone from depth."""
    for mode in ("DESCRIBE", "QUERY"):
        text = build_prompt(mode, "What is ahead?", "en").lower()
        assert "do not mention distance" in text
        assert "rough distance" not in text


def test_describe_prompt_keeps_its_load_bearing_phrases():
    """Each of these was added to fix an observed failure — the floor clause
    for a cat missed in the foreground, the reality clause for invented
    people, the sign clause so a door with 'WASHROOM' on it is navigable.
    Losing them silently regresses behaviour nobody would think to re-test."""
    text = build_prompt("DESCRIBE", None, "en").lower()
    assert "objects or animals on the floor" in text
    assert "only describe what is really there" in text
    assert "sign, board or written text" in text
