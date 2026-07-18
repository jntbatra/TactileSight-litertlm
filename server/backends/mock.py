"""Zero-dependency backend that proves the wire without any model.

It never looks at the image. Its job is to let the phone exercise the full round trip
(capture -> HTTP -> answer -> TTS) so that when we swap in a real backend the only new
variable is the model. The reply echoes the image size and prompt so a human can confirm
the bytes and prompt arrived intact.
"""

from __future__ import annotations


class MockBackend:
    def generate(self, image_bytes: bytes, prompt: str) -> str:
        kb = len(image_bytes) / 1024.0
        return (
            "A doorway is ahead in the center and a chair is on the left. "
            f"(mock backend: received {kb:.0f} KB image, {len(prompt)} char prompt)"
        )
