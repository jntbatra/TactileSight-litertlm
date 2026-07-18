package com.tactilesight.brain

import com.tactilesight.core.Answer
import com.tactilesight.core.Frame
import com.tactilesight.core.SemanticBrain

/**
 * The walking skeleton's brain (issue #1): a fixed sentence, so the path from
 * press to audible speech can be proved end to end before any model exists.
 * Replaced by LiteRTBrain in issue #2 with no change downstream.
 */
class StubBrain : SemanticBrain {

    override val name: String = "Stub"

    override suspend fun describe(frame: Frame, question: String?): Answer =
        Answer("A clear path ahead, with a doorway on the right.")
}
