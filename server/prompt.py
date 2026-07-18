"""
Single source of truth for what we ask the VLM to do.

TactileSight turns this into speech on the phone a couple seconds after the
photo is taken, so the description has to be short, spoken-out-loud, and
front-load the thing that matters most for someone who can't see the frame
(an obstacle, a doorway, a person, a curb, stairs...).

Both real backends (openai_compat, qefficient) build their prompt from here
so the wording only has to be tuned in one place. The phone's VlmPrompt.kt
deliberately mirrors it — see TEAM.md, "the prompt lives server-side".

Three phrases below are load-bearing. They read like ordinary wording and
are not; each was added to fix a failure we watched happen:

1. "including any objects or animals on the floor" — without it the model
   fixates on large background objects (a window, a bookshelf) and skips a
   small subject in the foreground. The case that found this was a cat
   sitting in the walking path — exactly what a walking user needs to hear.
2. "Only describe what is really there" — paired with a low temperature,
   this is what stops it inventing people and animals in an empty corridor.
3. "Read out any sign, board or written text" — a description that names a
   door but not the "WASHROOM" written on it is not navigation, it is
   scenery. Measured on real captures: Gemma read the sign as "washroom",
   Qwen3-VL said only "a sign", so this is worth asking for explicitly and
   is worth re-checking per model. It also interacts with #20 (on-device
   OCR): if the VLM reads signs well enough, #20 shrinks; if it only spots
   them, #20 stays a separate capability.
4. "Do not mention distance" — hard rule #3. The VLM cannot measure, and a
   guessed "about two metres" sounds, when spoken aloud, identical to one
   the depth sensor actually measured. Distance is appended by the phone
   from depth, or the phone says "distance unknown". A model that guesses
   distance silently destroys that guarantee.
"""

SYSTEM_PROMPT = (
    "You are the vision system for a blind or low-vision user's assistive "
    "device. You will be shown one photo taken from their point of view. "
    "Reply with ONE short spoken sentence, at most 20 words. "
    "Say the single most important thing first: an obstacle, a step or "
    "curb, a door, a person, or a clear path. Give direction (left / right "
    "/ ahead). "
    "Do not describe colors, aesthetics, or anything not useful for moving "
    "safely. No preamble like 'I see' or 'The image shows'. No markdown. "
    "If there is a sign, board, door number or other written text, read it "
    "out — that is how the user finds a room, a platform or an exit. "
    "Never state a distance — you cannot measure one, and the device adds "
    "distance itself from its depth sensor. "
    "If something is genuinely unclear, say so plainly rather than guessing. "
    "Speak plainly, as if saying it out loud right now."
)

# Upper bound we ask real backends to respect. Keep it tight — this is
# spoken back to the user, not read.
MAX_DESCRIPTION_TOKENS = 60


def build_prompt(mode: str, question: str | None, language: str) -> str:
    """The user-turn text for one request.

    Kept short and directive on purpose. Gemma 4 E4B is a reasoning model:
    an elaborate multi-rule prompt makes it narrate its thinking (and echo
    any example phrases) instead of just answering. One plain instruction
    gets a clean terse sentence.

    `language` is why this is a function rather than a constant — the same
    server answers in English, Hindi or Punjabi depending on what the phone
    asks for (ADR-0012).
    """
    if mode.upper() == "QUERY":
        return (
            f"Look at the image and answer in one short sentence, in {language}. "
            f"Do not mention distance. Question: {question or ''}"
        )

    # DESCRIBE
    return (
        "In one short sentence, say what is ahead, including any objects or animals "
        "on the floor, and whether each is on the left, center, or right. "
        "Read out any sign, board or written text you can see. "
        "Name the things directly, no preamble. "
        f"Only describe what is really there. Do not mention distance. Answer in {language}."
    )
