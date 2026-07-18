"""Qualcomm Cloud AI 100 backend via QEfficient — the demo target.

This is the ONE file that turns the laptop pipeline into a Cloud AI 100 pipeline. It is
written but not runnable here: QEfficient and the AIC compiler only exist on a Cloud AI
100 host (Cirrascale Developer Playground, AWS DL2q, or a card in a server). Everything
else in this server is exercised tonight with the mock/openai backends; when we get an
instance we set TS_VLM_BACKEND=qefficient and verify this file against real hardware.

QEfficient validates InternVL and Molmo as VLMs (Qwen3-VL is NOT on that list, which is
why the cloud tier's model differs from the on-device GenieX model). Configure with:

    TS_QEFF_MODEL      HF id of a QEfficient-validated VLM (default an InternVL)
    TS_QEFF_CORES      AIC cores to compile for (default 16)
    TS_QEFF_CTX_LEN    max context length to compile (default 4096)

Refs: https://github.com/quic/efficient-transformers
      https://quic.github.io/efficient-transformers/source/validate.html
"""

from __future__ import annotations

import io
import os


class QEfficientBackend:
    def __init__(self) -> None:
        self.model_id = os.environ.get("TS_QEFF_MODEL", "OpenGVLab/InternVL2_5-1B")
        self.cores = int(os.environ.get("TS_QEFF_CORES", "16"))
        self.ctx_len = int(os.environ.get("TS_QEFF_CTX_LEN", "4096"))
        self._model = None
        self._processor = None

    def _ensure_compiled(self) -> None:
        """Load + compile-to-AIC once. Compilation is minutes; generation is fast."""
        if self._model is not None:
            return
        # Imported lazily so the rest of the server runs on a machine without QEfficient.
        from QEfficient import QEFFAutoModelForImageTextToText  # type: ignore
        from transformers import AutoProcessor  # type: ignore

        self._processor = AutoProcessor.from_pretrained(self.model_id, trust_remote_code=True)
        model = QEFFAutoModelForImageTextToText.from_pretrained(
            self.model_id, trust_remote_code=True
        )
        # Compiles the model to the Cloud AI 100 (AIC) backend. Exact knobs (num_cores,
        # batch/prefill/ctx split, img size) are model-specific — confirm against the
        # instance the first time; these are conservative defaults for a 1B-class VLM.
        model.compile(num_cores=self.cores, ctx_len=self.ctx_len)
        self._model = model

    def generate(self, image_bytes: bytes, prompt: str) -> str:
        self._ensure_compiled()
        from PIL import Image  # type: ignore

        image = Image.open(io.BytesIO(image_bytes)).convert("RGB")
        messages = [
            {
                "role": "user",
                "content": [{"type": "image"}, {"type": "text", "text": prompt}],
            }
        ]
        chat = self._processor.apply_chat_template(  # type: ignore[union-attr]
            messages, add_generation_prompt=True
        )
        inputs = self._processor(images=image, text=chat, return_tensors="pt")  # type: ignore[union-attr]
        out = self._model.generate(inputs=inputs, generation_len=256)  # type: ignore[union-attr]
        # QEfficient returns generated ids; decode with the processor's tokenizer.
        text = self._processor.batch_decode(  # type: ignore[union-attr]
            out.generated_ids, skip_special_tokens=True
        )[0]
        return text.strip()
