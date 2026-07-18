"""
mock backend — proves the wire (phone -> server -> speech), zero model,
zero GPU, runs anywhere. This is the default backend.
"""
import asyncio
import itertools

# A tiny rotation so repeated taps in a demo don't sound like a stuck record,
# while staying fully deterministic and offline.
_RESPONSES = [
    "Mock description: clear path ahead, a doorway about two meters to your left.",
    "Mock description: a chair directly in front of you, roughly one meter away.",
    "Mock description: open hallway ahead, no obstacles detected.",
]
_cycle = itertools.cycle(_RESPONSES)


async def describe_image(image_bytes: bytes) -> str:
    # Small sleep so the mock feels like a real network+inference round trip
    # when demoing the end-to-end pipeline, without actually being slow.
    await asyncio.sleep(0.05)
    return next(_cycle)
