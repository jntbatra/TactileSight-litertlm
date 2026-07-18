package com.tactilesight

import android.app.Application
import android.util.Log
import com.tactilesight.brain.GenieXBrain
import com.tactilesight.brain.ModelStore
import com.tactilesight.brain.StubBrain
import com.tactilesight.core.SemanticBrain

/**
 * Owns the loaded model for the life of the process.
 *
 * This is deliberate and it is a hard rule (ADR-0010): the brain must **not**
 * belong to an Activity. Android destroys and recreates Activities on every
 * rotation, so an Activity-owned brain reloads its model each time — a 30–60 s
 * stall for a multi-gigabyte VLM. The previous app did exactly that, leaked the
 * native allocation, drove available RAM to ~1 GB and was OOM-killed six times.
 */
class TactileSightApp : Application() {

    private val modelStore by lazy { ModelStore(this) }

    /**
     * Exactly one brain resident at a time. Chosen at startup from what is
     * actually sideloaded, so a device with no model still runs and still
     * speaks — it just speaks the stub sentence rather than a dead press.
     */
    @Volatile
    var brain: SemanticBrain = StubBrain()
        private set

    private val brainLock = Any()

    override fun onCreate() {
        super.onCreate()
        brain = resolveBrain()
        Log.i(TAG, "brain = ${brain.name}")
    }

    /**
     * Note this only *constructs* the brain — GenieXBrain loads its model lazily
     * on first use, under its own lock. Startup stays fast and a missing model
     * surfaces as a spoken fallback rather than a crash at launch.
     */
    private fun resolveBrain(): SemanticBrain {
        val bundles = modelStore.available(ModelStore.Engine.GENIEX)

        if (bundles.isEmpty()) {
            Log.w(
                TAG,
                "no GenieX model in ${modelStore.directoryFor(ModelStore.Engine.GENIEX)} " +
                    "— falling back to the stub brain",
            )
            return StubBrain()
        }

        return GenieXBrain(context = this, modelDir = bundles.first())
    }

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

    private companion object {
        const val TAG = "TactileSightApp"
    }
}
