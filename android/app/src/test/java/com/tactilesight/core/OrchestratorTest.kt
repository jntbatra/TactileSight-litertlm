package com.tactilesight.core

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The walking skeleton's contract (issue #1). Fakes at every seam: no camera,
 * no model, no network.
 */
class OrchestratorTest {

    /**
     * Depth is deliberately all-invalid here. These tests are about the press
     * pipeline - capture, brain resolution, speech - and a frame with usable
     * depth would prepend a measured distance to every expected string,
     * coupling tests that have nothing to do with distance to the wording of
     * DistanceSpeech. Distance has its own tests below and its own fixture.
     */
    private val frame = Frame(
        rgbJpeg = byteArrayOf(1, 2, 3),
        depthMillimetres = DepthMap(2, 2, shortArrayOf(0, 0, 0, 0)),
        capturedAtMillis = 1000L,
        sourceId = "scene_1_id001",
    )

    /** Same frame, but every pixel a valid two-metre reading. */
    private val measurableFrame = frame.copy(
        depthMillimetres = DepthMap(8, 8, ShortArray(64) { 2000 }),
    )

    private class FakeFrameSource(
        private val frame: Frame? = null,
        private val failWith: Exception? = null,
    ) : FrameSource {
        var captures = 0
        override suspend fun capture(): Frame {
            captures++
            failWith?.let { throw it }
            return frame!!
        }
    }

    private class FakeBrain(
        private val answer: String = "a sentence",
        private val failWith: Exception? = null,
    ) : SemanticBrain {
        override val name = "Fake"
        var lastQuestion: String? = null
        var describes = 0
        override suspend fun describe(frame: Frame, question: String?): Answer {
            describes++
            lastQuestion = question
            failWith?.let { throw it }
            return Answer(answer)
        }
    }

    private class FakeSpeech : SpeechIO {
        val spoken = mutableListOf<Pair<String, Language>>()
        override suspend fun speak(text: String, language: Language) {
            spoken += text to language
        }
    }

    @Test
    fun `the brain is resolved per press, so switching destination takes effect`() = runTest {
        // Switching to on-device (or having privacy mode force it) must stop
        // the very next press from reaching the previous brain. A reference
        // captured at construction would keep sending frames to the cloud.
        val cloud = FakeBrain("from the cloud")
        val onDevice = FakeBrain("from the phone")
        var current: SemanticBrain = cloud
        val speech = FakeSpeech()
        val orchestrator = Orchestrator(FakeFrameSource(frame), { current }, speech)

        assertEquals("from the cloud", orchestrator.onPress())
        current = onDevice
        assertEquals("from the phone", orchestrator.onPress())

        assertEquals(1, cloud.describes)
        assertEquals(1, onDevice.describes)
    }

    @Test
    fun `a press captures a frame, describes it, and speaks the result`() = runTest {
        val frames = FakeFrameSource(frame)
        val brain = FakeBrain("A clear path ahead.")
        val speech = FakeSpeech()

        val spokenText = Orchestrator(frames, brain, speech).onPress()

        assertEquals(1, frames.captures)
        assertEquals(1, brain.describes)
        assertEquals("A clear path ahead.", spokenText)
        assertEquals(listOf("A clear path ahead." to Language.ENGLISH), speech.spoken)
    }

    @Test
    fun `a press still speaks when capture fails`() = runTest {
        val speech = FakeSpeech()
        val orchestrator = Orchestrator(
            FakeFrameSource(failWith = IllegalStateException("camera unavailable")),
            FakeBrain(),
            speech,
        )

        val spokenText = orchestrator.onPress()

        assertEquals(Orchestrator.FALLBACK, spokenText)
        assertEquals(listOf(Orchestrator.FALLBACK to Language.ENGLISH), speech.spoken)
    }

    @Test
    fun `a press still speaks when the brain fails`() = runTest {
        val speech = FakeSpeech()
        val orchestrator = Orchestrator(
            FakeFrameSource(frame),
            FakeBrain(failWith = OutOfMemoryError("model load failed").let { RuntimeException(it) }),
            speech,
        )

        val spokenText = orchestrator.onPress()

        assertEquals(Orchestrator.FALLBACK, spokenText)
        assertEquals(1, speech.spoken.size)
    }

    @Test
    fun `never a dead press — every outcome is audible`() = runTest {
        val scenarios: List<Pair<String, () -> Orchestrator>> = listOf(
            "everything works" to
                { Orchestrator(FakeFrameSource(frame), FakeBrain(), FakeSpeech()) },
            "capture fails" to
                { Orchestrator(FakeFrameSource(failWith = IllegalStateException("camera")), FakeBrain(), FakeSpeech()) },
            "brain fails" to
                { Orchestrator(FakeFrameSource(frame), FakeBrain(failWith = RuntimeException("model")), FakeSpeech()) },
            "brain returns an empty sentence" to
                { Orchestrator(FakeFrameSource(frame), FakeBrain(answer = ""), FakeSpeech()) },
        )

        for ((description, build) in scenarios) {
            val orchestrator = build()
            val spokenText = orchestrator.onPress()
            assertTrue("dead press when $description", spokenText.isNotBlank())
        }
    }

    @Test
    fun `the spoken language is the one configured`() = runTest {
        val speech = FakeSpeech()

        Orchestrator(FakeFrameSource(frame), FakeBrain(), speech, Language.PUNJABI).onPress()

        assertEquals(Language.PUNJABI, speech.spoken.single().second)
    }

    @Test
    fun `a question reaches the brain, and describe passes none`() = runTest {
        val brain = FakeBrain()
        val orchestrator = Orchestrator(FakeFrameSource(frame), brain, FakeSpeech())

        orchestrator.onPress(question = "What is on the table?")
        assertEquals("What is on the table?", brain.lastQuestion)

        orchestrator.onPress()
        assertEquals(null, brain.lastQuestion)
    }

    /**
     * Acceptance criterion: "swapping any seam implementation requires no change
     * downstream." Two entirely different brains drive the same orchestrator.
     */
    @Test
    fun `swapping the brain changes nothing downstream`() = runTest {
        val speech = FakeSpeech()
        val frames = FakeFrameSource(frame)

        Orchestrator(frames, FakeBrain("first engine"), speech).onPress()
        Orchestrator(frames, FakeBrain("second engine"), speech).onPress()

        assertEquals(
            listOf("first engine", "second engine"),
            speech.spoken.map { it.first },
        )
    }

    @Test
    fun `a measured distance is spoken before the description`() = runTest {
        // ADR-0011's two-stage answer: how far, then what. The description is
        // carried through unchanged - the distance is added to it, never
        // replaces or edits it.
        val speech = FakeSpeech()
        Orchestrator(FakeFrameSource(measurableFrame), FakeBrain("a doorway"), speech).onPress()

        val spoken = speech.spoken.single().first
        assertTrue(spoken, spoken.endsWith("a doorway"))
        assertTrue(spoken, spoken.contains("two metres"))
    }

    @Test
    fun `no distance is spoken when depth cannot measure the scene`() = runTest {
        // The description still reaches the user, carrying no number. This is
        // the case the VLM prompt's "never state a distance" rule depends on:
        // an object the camera sees but depth cannot reach is still named.
        val speech = FakeSpeech()
        Orchestrator(FakeFrameSource(frame), FakeBrain("a doorway"), speech).onPress()

        assertEquals("a doorway", speech.spoken.single().first)
    }

    @Test
    fun `the fallback is never given a distance`() = runTest {
        // "Sorry, I could not see that" with "two metres ahead" bolted on
        // front would be the device confidently measuring a scene it just
        // admitted it could not see.
        val speech = FakeSpeech()
        Orchestrator(
            FakeFrameSource(measurableFrame),
            FakeBrain(failWith = IllegalStateException("model died")),
            speech,
        ).onPress()

        assertEquals(Orchestrator.FALLBACK, speech.spoken.single().first)
    }
}