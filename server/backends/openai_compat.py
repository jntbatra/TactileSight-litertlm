"""Backend for any OpenAI vision-chat compatible server.

This is the "real descriptions tonight, no special hardware" option: run llama.cpp's
server (`llama-server --mmproj ...`) or vLLM locally and point this at it. It is also how
a hosted VLM would be reached if we ever wanted one. Configure with:

    TS_OPENAI_BASE_URL  default http://localhost:8080/v1
    TS_OPENAI_MODEL     default "local-vlm"
    TS_OPENAI_API_KEY   optional bearer token
"""

from __future__ import annotations

import base64
import os

import requests


class OpenAiCompatBackend:
    def __init__(self) -> None:
        self.base_url = os.environ.get("TS_OPENAI_BASE_URL", "http://localhost:8080/v1").rstrip("/")
        self.model = os.environ.get("TS_OPENAI_MODEL", "local-vlm")
        self.api_key = os.environ.get("TS_OPENAI_API_KEY")
        # Low by design: at the model's default (~0.8) the description is unstable and often drops
        # the salient foreground subject (a cat on the floor) in favour of big background objects.
        # 0.2 makes it focused and repeatable. Override with TS_OPENAI_TEMPERATURE if needed.
        self.temperature = float(os.environ.get("TS_OPENAI_TEMPERATURE", "0.2"))

    def generate(self, image_bytes: bytes, prompt: str) -> str:
        b64 = base64.b64encode(image_bytes).decode("ascii")
        headers = {"Content-Type": "application/json"}
        if self.api_key:
            headers["Authorization"] = f"Bearer {self.api_key}"
        payload = {
            "model": self.model,
            "max_tokens": 256,
            "temperature": self.temperature,
            "messages": [
                {
                    "role": "user",
                    "content": [
                        {"type": "text", "text": prompt},
                        {
                            "type": "image_url",
                            "image_url": {"url": f"data:image/jpeg;base64,{b64}"},
                        },
                    ],
                }
            ],
        }
        resp = requests.post(
            f"{self.base_url}/chat/completions", json=payload, headers=headers, timeout=120
        )
        resp.raise_for_status()
        return resp.json()["choices"][0]["message"]["content"].strip()
