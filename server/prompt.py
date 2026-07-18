"""The spoken-answer contract for the cloud tier.

This mirrors GenieXBrain.buildPrompt on the phone (identity + horizontal position,
never distance, terse, hedge when unclear). It is deliberately duplicated rather than
shared: the on-device model is Qwen3-VL via GenieX, while the cloud model is InternVL/
Molmo via QEfficient, and the two runtimes want their prompts tuned independently. What
must stay identical is the *behaviour* the user hears, not the prompt string.
"""

# Kept short and directive on purpose. Gemma 4 E4B is a reasoning model: an elaborate
# multi-rule prompt makes it narrate its thinking (and echo any example phrases) instead of
# just answering. One plain instruction gets a clean terse sentence. The spoken contract
# (identity + left/center/right, no distance, terse) is preserved, just phrased minimally.


def build_prompt(mode: str, question: str | None, language: str) -> str:
    if mode.upper() == "QUERY":
        return (
            f"Look at the image and answer in one short sentence, in {language}. "
            f"Do not mention distance. Question: {question or ''}"
        )
    # DESCRIBE. "including any objects or animals on the floor" is load-bearing: without it the
    # model fixates on large background objects (a window, a bookshelf) and skips a small subject
    # in the foreground — e.g. a cat sitting in the path, exactly what a walking user needs to hear.
    # "only describe what is really there" + low temperature (see openai backend) keep it from
    # inventing people/animals when the scene has none.
    return (
        "In one short sentence, say what is ahead, including any objects or animals on the floor, "
        "and whether each is on the left, center, or right. Name the things directly, no preamble. "
        f"Only describe what is really there. Do not mention distance. Answer in {language}."
    )
