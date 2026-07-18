"""
Single source of truth for what we ask the VLM to do.

TactileSight turns this into speech on the phone a couple seconds after the
photo is taken, so the description has to be short, spoken-out-loud English,
and front-load the thing that matters most for someone who can't see the
frame (an obstacle, a doorway, a person, a curb, stairs...).

Both real backends (openai_compat, qefficient) import SYSTEM_PROMPT and
USER_PROMPT from here so the wording only has to be tuned in one place.
"""

SYSTEM_PROMPT = (
    "You are the vision system for a blind or low-vision user's assistive "
    "device. You will be shown one photo taken from their point of view. "
    "Reply with ONE short spoken sentence, at most 20 words. "
    "Say the single most important thing first: an obstacle, a step or "
    "curb, a door, a person, or a clear path. Give direction (left / right "
    "/ ahead) and rough distance when you can. "
    "Do not describe colors, aesthetics, or anything not useful for moving "
    "safely. No preamble like 'I see' or 'The image shows'. No markdown. "
    "No hedging ('it appears', 'possibly'). Speak plainly, as if saying it "
    "out loud right now."
)

USER_PROMPT = "Describe this scene for someone who cannot see it."

# Upper bound we ask real backends to respect. Keep it tight — this is
# spoken back to the user, not read.
MAX_DESCRIPTION_TOKENS = 60
