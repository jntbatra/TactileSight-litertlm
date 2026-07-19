package com.tactilesight.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
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

    /** The phone camera: no depth sensor at all, not a sensor that read zero. */
    private val depthlessFrame = frame.copy(
        depthMillimetres = DepthMap.NONE,
        sourceId = "phone-camera",
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
        override suspend fun describe(frame: Frame, question: String?, surfaceIsFlat: Boolean): Answer {
            describes++
            lastQuestion = question
            failWith?.let { throw it }
            return Answer(answer)
        }
    }

    private class FakeSpeech : SpeechIO {
        val spoken = mutableListOf<Pair<String, Language>>()
        override suspend fun speak(text: String, language: Language, translate: Boolean) {
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
        val source = FakeFrameSource(frame)
        val orchestrator = Orchestrator({ source }, { current }, speech)

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

    /**
     * A named capture failure must be heard as itself, not as the generic
     * apology.
     *
     * "Sorry, I could not see that" sounds like the description failed and
     * invites pressing again against a fault that will repeat. The band having
     * no picture to send is something the user can act on, and they cannot see
     * that the image was missing — so the sentence has to say it.
     */
    @Test
    fun `a capture failure the source can name is spoken in its own words`() = runTest {
        val speech = FakeSpeech()
        val orchestrator = Orchestrator(
            FakeFrameSource(
                failWith = CaptureUnavailable(
                    spokenMessage = "The band is not sending pictures.",
                    detail = "rgb_b64 empty twice",
                ),
            ),
            FakeBrain(),
            speech,
        )

        val spokenText = orchestrator.onPress()

        assertEquals("The band is not sending pictures.", spokenText)
        assertEquals(
            listOf("The band is not sending pictures." to Language.ENGLISH),
            speech.spoken,
        )
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
    fun `a phone-camera frame is described but never measured`() = runTest {
        // The standalone path. A phone has no depth sensor, so the answer
        // carries no number - not an estimated one. Letting the model guess
        // would put a wrong metre in front of someone who cannot see the thing
        // they are about to walk into, which is the failure the whole depth
        // pipeline exists to prevent (ADR-0013).
        val speech = FakeSpeech()
        Orchestrator(FakeFrameSource(depthlessFrame), FakeBrain("a doorway"), speech).onPress()

        assertEquals("a doorway", speech.spoken.single().first)
    }

    @Test
    fun `a source with no depth sensor is not the same as depth that read nothing`() {
        // Both speak no distance, but only one is worth investigating: an
        // all-invalid band frame is a measurement failure, while a phone
        // camera was never going to measure anything.
        assertTrue(measurableFrame.hasDepth)
        assertTrue(frame.hasDepth)
        assertTrue(!depthlessFrame.hasDepth)
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

    /**
     * Parks inside [describe] until released, and records whether anyone ever
     * got in alongside it. [maxConcurrent] is the assertion that matters: the
     * on-device brain holds one model, and two describes overlapping is what
     * interleaves two token streams into one garbled sentence.
     */
    private class GatedBrain : SemanticBrain {
        override val name = "Gated"
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var describes = 0
        private var inFlight = 0
        var maxConcurrent = 0

        override suspend fun describe(frame: Frame, question: String?, surfaceIsFlat: Boolean): Answer {
            describes++
            inFlight++
            maxConcurrent = maxOf(maxConcurrent, inFlight)
            entered.complete(Unit)
            release.await()
            inFlight--
            return Answer("a sentence")
        }
    }

    @Test
    fun `a press arriving while one is in flight is dropped, not interleaved`() = runTest {
        // The bug: volume-up pressed twice in quick succession ran the whole
        // pipeline twice at once. Against the one resident VLM that reset the
        // model mid-decode and spliced two token streams together, and the
        // user heard a sentence made of fragments of both.
        val brain = GatedBrain()
        val speech = FakeSpeech()
        val source = FakeFrameSource(frame)
        val orchestrator = Orchestrator({ source }, { brain }, speech)

        val first = async { orchestrator.onPress() }
        brain.entered.await()
        assertTrue(orchestrator.isBusy)

        assertEquals(Orchestrator.BUSY, orchestrator.onPress())

        brain.release.complete(Unit)
        assertEquals("a sentence", first.await())

        // One describe, never two at once, one thing spoken.
        assertEquals(1, brain.describes)
        assertEquals(1, brain.maxConcurrent)
        assertEquals(listOf("a sentence"), speech.spoken.map { it.first })
        // And the dropped press cost nothing — it never even reached the camera.
        assertEquals(1, source.captures)
    }

    @Test
    fun `a dropped press says nothing, because the answer it interrupts is the one being spoken`() =
        runTest {
            // The marker must never reach speech or the screen: it is not a
            // sentence, and speaking over an in-flight answer would cost the
            // user the reply they actually pressed for.
            val brain = GatedBrain()
            val speech = FakeSpeech()
            val orchestrator = Orchestrator({ FakeFrameSource(frame) }, { brain }, speech)

            val first = async { orchestrator.onPress() }
            brain.entered.await()
            orchestrator.onPress()
            brain.release.complete(Unit)
            first.await()

            assertTrue(speech.spoken.none { it.first == Orchestrator.BUSY })
            assertEquals(1, speech.spoken.size)
        }

    @Test
    fun `the guard releases, so the next press is answered normally`() = runTest {
        // Dropping must not be sticky. A press that fails to release the lock
        // would leave the device permanently deaf, which is far worse than the
        // bug being fixed.
        val speech = FakeSpeech()
        val source = FakeFrameSource(frame)
        val orchestrator = Orchestrator({ source }, { FakeBrain("a sentence") }, speech)

        assertEquals("a sentence", orchestrator.onPress())
        assertTrue(!orchestrator.isBusy)
        assertEquals("a sentence", orchestrator.onPress())
        assertEquals(2, speech.spoken.size)
    }

    @Test
    fun `a press that failed still releases the guard`() = runTest {
        // The fallback path returns early, before the pipeline. If that exit
        // skipped the unlock, one failed capture would deafen the device for
        // the rest of the session.
        val speech = FakeSpeech()
        val orchestrator = Orchestrator(
            FakeFrameSource(failWith = IllegalStateException("camera gone")),
            FakeBrain(),
            speech,
        )

        assertEquals(Orchestrator.FALLBACK, orchestrator.onPress())
        assertTrue(!orchestrator.isBusy)
        assertEquals(Orchestrator.FALLBACK, orchestrator.onPress())
    }

    @Test
    fun `answerAbout is guarded too, so a hold cannot overlap a tap`() = runTest {
        // Both gestures reach the brain, but by different doors: a tap goes
        // through onPress, a hold captures at press-down and calls answerAbout
        // at release. Guarding only one door leaves the model re-entrant.
        val brain = GatedBrain()
        val speech = FakeSpeech()
        val orchestrator = Orchestrator({ FakeFrameSource(frame) }, { brain }, speech)

        val first = async { orchestrator.onPress() }
        brain.entered.await()

        assertEquals(Orchestrator.BUSY, orchestrator.answerAbout(frame))

        brain.release.complete(Unit)
        first.await()

        assertEquals(1, brain.describes)
        assertEquals(1, brain.maxConcurrent)
    }
}