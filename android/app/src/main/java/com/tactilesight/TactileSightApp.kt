package com.tactilesight

import android.app.Application
import com.tactilesight.brain.StubBrain
import com.tactilesight.core.SemanticBrain

/**
 * Owns the loaded model for the life of the process.
 *
 * This is deliberate and it is a hard rule (ADR-0010): the brain must **not**
 * belong to an Activity. Android destroys and recreates Activities on every
 * rotation, so an Activity-owned brain reloads its model each time — a 30–60 s
 * stall for a 2.6 GB LiteRT model. The previous app did exactly that, leaked
 * the native allocation, drove available RAM to ~1 GB and was OOM-killed six
 * times.
 *
 * The brain here is a cheap stub (#1), so nothing visibly breaks either way —
 * which is precisely why the structure is worth establishing now, while it
 * costs nothing, rather than under a real model in #2.
 */
class TactileSightApp : Application() {

    /**
     * Exactly one brain resident at a time. Swapping engines (#7) replaces this
     * under a lock and closes the outgoing one; nothing else ever releases it.
     */
    @Volatile
    var brain: SemanticBrain = StubBrain()
        private set

    private val brainLock = Any()

    /**
     * Replace the resident brain, releasing the old one. The only place a model
     * is ever released.
     */
    fun switchBrain(next: SemanticBrain) {
        synchronized(brainLock) {
            if (next === brain) return
            val previous = brain
            brain = next
            previous.close()
        }
    }
}
