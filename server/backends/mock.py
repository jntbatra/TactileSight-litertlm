"""
mock backend — proves the wire (phone -> server -> speech), zero model,
zero GPU, runs anywhere. This is the default backend.
"""
import asyncio
import itertools

# A tiny rotation so repeated taps in a demo don't sound like a stuck record,
# while staying fully deterministic and offline.
#
# None of these state a distance, deliberately. The VLM never does (hard rule
# #3) — the phone appends distance from depth, or says "distance unknown".
# A mock that speaks distances teaches the wrong shape to anyone reading it
# for an example of a good answer, and makes a rule violation look normal.
_RESPONSES = [
    "Mock description: clear path ahead, a doorway on your left.",
    "Mock description: a chair directly in front of you.",
    "Mock description: open hallway ahead, no obstacles.",
]
_cycle = itertools.cycle(_RESPONSES)


async def describe_image(image_bytes: bytes, prompt: str) -> str:
    # Small sleep so the mock feels like a real network+inference round trip
    # when demoing the end-to-end pipeline, without actually being slow.
    await asyncio.sleep(0.05)
    return next(_cycle)
