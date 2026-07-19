package com.tactilesight.speech

import com.tactilesight.core.Language
import com.tactilesight.core.SpeechIO
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Resolving a spoken language.
 *
 * The case that drove this: **"Bangali" was refused while "Bengali" worked** —
 * and Bangla is what a Bengali speaker calls their own language. Setup is the
 * first thing the device ever does, so a user who names their language
 * correctly and hears "I did not catch that" learns, in the first ten seconds,
 * that this device does not know their language.
 */
class SpokenSetupTest {

    private class FakeSpeech : SpeechIO {
        override suspend fun speak(text: String, language: Language, translate: Boolean) = Unit
    }

    private val setup = SpokenSetup(FakeSpeech(), MicRecorder(), SarvamAsr(apiKey = ""))

    private fun resolve(transcript: String, detected: String? = null): Language? =
        setup.resolve(SarvamAsr.Heard(transcript, detected))

    @Test
    fun `accepts what people actually call their language`() {
        assertEquals(Language.BENGALI, resolve("Bangali"))
        assertEquals(Language.BENGALI, resolve("bangla"))
        assertEquals(Language.BENGALI, resolve("Bengali"))
        assertEquals(Language.ODIA, resolve("Oriya please"))
        assertEquals(Language.PUNJABI, resolve("panjabi"))
        assertEquals(Language.TAMIL, resolve("Tamizh"))
    }

    @Test
    fun `accepts a language named in its own script`() {
        assertEquals(Language.HINDI, resolve("हिन्दी"))
        assertEquals(Language.PUNJABI, resolve("ਪੰਜਾਬੀ"))
    }

    @Test
    fun `falls back to the language they spoke in`() {
        // They said something unrelated, in Bengali. The words carry no name;
        // the language they used does. This is what makes setup work for
        // someone who does not know their language's English name at all.
        assertEquals(Language.BENGALI, resolve("আমি জানি না", detected = "bn-IN"))
    }

    @Test
    fun `a named language beats the one it was said in`() {
        // "Punjabi" said in English means Punjabi. Taking en-IN here would
        // override an explicit instruction with an inference.
        assertEquals(Language.PUNJABI, resolve("Punjabi", detected = "en-IN"))
    }

    @Test
    fun `an unrecognisable answer changes nothing`() {
        // Silence is the safe outcome: English stays and the retry button is
        // there. Guessing would strand a user in a language they cannot read.
        assertNull(resolve("mmm what"))
    }

    @Test
    fun `a language we cannot speak is never selected`() {
        // bulbul:v3 refuses 12 of the 23 without beta access. Setting one
        // would translate correctly and then fail at the last step, and the
        // user would hear a network error instead of their answer.
        assertNull(resolve("Dogri"))
        assertNull(resolve("Santali"))
    }
}
