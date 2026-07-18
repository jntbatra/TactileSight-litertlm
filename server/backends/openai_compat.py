"""
openai backend — talks to any OpenAI-vision-compatible /chat/completions
endpoint: llama-server (llama.cpp), vLLM, LM Studio, or the real OpenAI API.

Tonight's demo target is run-gemma.sh (Gemma 4 E4B via llama.cpp on the
laptop GPU), pointed to by TS_OPENAI_BASE_URL. Nothing here is Gemma-
specific — any OpenAI-vision server works.
"""
import base64
import os

import httpx

from prompt import MAX_DESCRIPTION_TOKENS, SYSTEM_PROMPT

BASE_URL = os.environ.get("TS_OPENAI_BASE_URL", "http://localhost:8080/v1").rstrip("/")
API_KEY = os.environ.get("TS_OPENAI_API_KEY", "not-needed")
MODEL = os.environ.get("TS_OPENAI_MODEL", "gemma-4-e4b")
TIMEOUT_S = float(os.environ.get("TS_OPENAI_TIMEOUT_S", "30"))


def _guess_mime(image_bytes: bytes) -> str:
    # Phones almost always send JPEG; sniff the magic bytes so PNG test
    # fixtures (like the one in test_app.py) still work.
    if image_bytes[:8] == b"\x89PNG\r\n\x1a\n":
        return "image/png"
    return "image/jpeg"


async def describe_image(image_bytes: bytes, prompt: str) -> str:
    """`prompt` is built per-request by prompt.build_prompt — it carries the
    mode (DESCRIBE/QUERY), the question, and the spoken language, none of
    which a baked-in constant could express."""
    mime = _guess_mime(image_bytes)
    b64 = base64.b64encode(image_bytes).decode("ascii")
    data_url = f"data:{mime};base64,{b64}"

    payload = {
        "model": MODEL,
        "messages": [
            {"role": "system", "content": SYSTEM_PROMPT},
            {
                "role": "user",
                "content": [
                    {"type": "text", "text": prompt},
                    {"type": "image_url", "image_url": {"url": data_url}},
                ],
            },
        ],
        "max_tokens": MAX_DESCRIPTION_TOKENS,
        "temperature": 0.2,
    }
    headers = {"Authorization": f"Bearer {API_KEY}"}

    async with httpx.AsyncClient(timeout=TIMEOUT_S) as client:
        resp = await client.post(
            f"{BASE_URL}/chat/completions", json=payload, headers=headers
        )
        resp.raise_for_status()
        data = resp.json()

    try:
        text = data["choices"][0]["message"]["content"]
    except (KeyError, IndexError) as exc:
        raise RuntimeError(f"Unexpected response shape from {BASE_URL}: {data}") from exc

    return text.strip()
