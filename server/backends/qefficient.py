"""
qefficient backend — runs a VLM on Qualcomm Cloud AI 100 via QEfficient
(https://github.com/quic/efficient-transformers). This is the demo target.

*** This is the one file the README says must be verified against real
*** hardware. QEfficient's VLM API has moved between releases; treat the
*** calls below as the best-effort shape, and fix them up against whatever
*** version is installed on the Cloud AI 100 instance (check
*** `python -c "import QEfficient; print(QEfficient.__version__)"` and the
*** docs at https://quic.github.io/efficient-transformers/).

Only imported lazily (inside _load_pipeline) so that `mock` and `openai`
backends keep working on a laptop with no QEfficient / Cloud AI 100 SDK
installed at all.
"""
import asyncio
import io
import os
import threading

from PIL import Image

from prompt import MAX_DESCRIPTION_TOKENS, SYSTEM_PROMPT

MODEL_ID = os.environ.get("TS_QEFF_MODEL", "OpenGVLab/InternVL2_5-1B")
NUM_CORES = int(os.environ.get("TS_QEFF_NUM_CORES", "16"))

_lock = threading.Lock()
_pipeline = None  # populated on first request; compilation is slow (minutes)


def _load_pipeline():
    """
    Load + compile the model once per process. First call is slow (AIC
    compile step); every call after reuses the compiled QPC.
    """
    global _pipeline
    with _lock:
        if _pipeline is not None:
            return _pipeline

        # InternVL / Molmo are loaded through QEFFAutoModelForCausalLM with
        # trust_remote_code=True (they're VLMs but ported through the
        # CausalLM class to stay HF-compatible — see QEfficient's
        # validated-models list).
        from transformers import AutoTokenizer
        from QEfficient import QEFFAutoModelForCausalLM

        tokenizer = AutoTokenizer.from_pretrained(
            MODEL_ID, trust_remote_code=True, use_fast=False
        )
        model = QEFFAutoModelForCausalLM.from_pretrained(
            MODEL_ID, trust_remote_code=True
        )
        # Compiles to a QPC for the AIC backend. num_cores is a starting
        # point for a single Cloud AI 100 card — tune against whatever the
        # instance actually has.
        model.compile(num_cores=NUM_CORES)

        _pipeline = (tokenizer, model)
        return _pipeline


def _run_sync(image_bytes: bytes, prompt: str) -> str:
    tokenizer, model = _load_pipeline()

    image = Image.open(io.BytesIO(image_bytes)).convert("RGB")

    # NOTE: this mirrors InternVL's own `model.chat(tokenizer, pixel_values,
    # question, generation_config)` HF interface. QEfficient's compiled
    # wrapper generally exposes an equivalent `.generate(...)` — confirm the
    # exact signature (and how it wants the image preprocessed: raw PIL vs.
    # a CLIPImageProcessor pixel_values tensor) against the installed
    # QEfficient version on the Cloud AI 100 box before the demo.
    question = f"<image>\n{SYSTEM_PROMPT}\n{prompt}"
    generation_config = dict(max_new_tokens=MAX_DESCRIPTION_TOKENS, do_sample=False)

    response = model.generate(
        tokenizer=tokenizer,
        images=[image],
        prompt=question,
        generation_config=generation_config,
    )

    # QEfficient's generate() has returned both a bare string and a
    # dict/object with a .generated_texts / .sequences field across
    # versions — normalize defensively rather than assume one shape.
    if isinstance(response, str):
        text = response
    elif isinstance(response, dict) and "generated_texts" in response:
        text = response["generated_texts"][0]
    else:
        text = str(response)

    return text.strip()


async def describe_image(image_bytes: bytes, prompt: str) -> str:
    # QEfficient's calls are blocking/synchronous (CPU + AIC driver calls),
    # so keep them off the asyncio event loop.
    loop = asyncio.get_event_loop()
    return await loop.run_in_executor(None, _run_sync, image_bytes, prompt)
