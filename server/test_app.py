"""Tonight's server tests — the mock path, end to end, no model or hardware needed.

Run:  cd server && TS_VLM_BACKEND=mock python -m pytest -q
"""

import base64

from fastapi.testclient import TestClient

import app as app_module
from prompt import build_prompt


def _client() -> TestClient:
    return TestClient(app_module.app)


def test_health_reports_backend():
    r = _client().get("/health")
    assert r.status_code == 200
    assert r.json()["status"] == "ok"


def test_describe_round_trips_an_image():
    img = base64.b64encode(b"\xff\xd8\xff\xd9pretend-jpeg").decode()
    r = _client().post("/v1/describe", json={"mode": "DESCRIBE", "image_b64": img})
    assert r.status_code == 200
    body = r.json()
    assert body["spoken"]
    assert body["rich"] == body["spoken"]
    assert body["confident"] is True


def test_bad_base64_is_a_400():
    r = _client().post("/v1/describe", json={"mode": "DESCRIBE", "image_b64": "not base64!!"})
    assert r.status_code == 400


def test_prompt_obeys_the_spoken_contract():
    describe = build_prompt("DESCRIBE", None, "hi")
    assert "left, center, or right" in describe  # horizontal position, the spoken contract
    assert "distance" in describe.lower()         # ... and the no-distance rule
    assert "objects or animals on the floor" in describe  # foreground salience (the cat fix)
    assert "hi" in describe                        # language threaded through

    query = build_prompt("QUERY", "where is the door", "en")
    assert "Question: where is the door" in query
    assert "distance" in query.lower()
