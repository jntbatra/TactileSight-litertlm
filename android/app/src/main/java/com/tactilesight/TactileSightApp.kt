package com.tactilesight

import android.app.Application
import android.util.Log
import com.tactilesight.brain.CloudBrain
import com.tactilesight.brain.GenieXBrain
import com.tactilesight.brain.ModelStore
import com.tactilesight.brain.StubBrain
import com.tactilesight.core.BrainMode
import com.tactilesight.core.SemanticBrain
import com.tactilesight.core.Settings

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

    val settings by lazy { Settings(this) }

    override fun onCreate() {
        super.onCreate()
        applyMode(settings.effectiveMode)
    }

    /**
     * Switch to [mode], releasing whatever model was resident.
     *
     * Goes through [Settings.effectiveMode], so privacy mode cannot be talked
     * around: asking for [BrainMode.CLOUD] with privacy on lands on-device
     * instead of off-device. Hard rule #7 says privacy must actually block the
     * cloud, and a check that lives only in a spinner listener is one refactor
     * away from not existing.
     */
    fun applyMode(requested: BrainMode) {
        settings.mode = requested
        val mode = settings.effectiveMode
        if (mode != requested) {
            Log.w(TAG, "$requested blocked by privacy mode — using $mode")
        }
        switchBrain(brainFor(mode))
        Log.i(TAG, "brain = ${brain.name} (mode=$mode)")
    }

    /**
     * Note this only *constructs* the brain — GenieXBrain loads its model lazily
     * on first use, under its own lock. Startup stays fast and a missing model
     * surfaces as a spoken fallback rather than a crash at launch.
     */
    private fun brainFor(mode: BrainMode): SemanticBrain = when (mode) {
        BrainMode.ON_DEVICE -> onDeviceBrain()
        BrainMode.PRIVATE_SERVER, BrainMode.CLOUD -> {
            val url = settings.urlFor(mode)
            if (url.isBlank()) {
                Log.w(TAG, "no URL set for $mode — falling back to the stub brain")
                StubBrain()
            } else {
                CloudBrain(baseUrl = url, name = mode.displayName)
            }
        }
    }

    private fun onDeviceBrain(): SemanticBrain {
        val bundle = ENGINE_PREFERENCE
            .firstNotNullOfOrNull { modelStore.available(it).firstOrNull() }

        if (bundle == null) {
            Log.w(
                TAG,
                "no model in " +
                    ENGINE_PREFERENCE.joinToString { modelStore.directoryFor(it).path } +
                    " — falling back to the stub brain",
            )
            return StubBrain()
        }

        return GenieXBrain(context = this, modelDir = bundle)
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

        /**
         * First engine with a bundle staged wins — NPU before GPU.
         *
         * Measured on the same capture (2026-07-18, #2): QAIRT/NPU answers in
         * 260 ms against llama_cpp/GPU's 3417 ms, and answers *better* — the
         * GPU run placed a centre sign on the right and padded with "a central
         * area". GGUF stays as the fallback for a device with no QAIRT bundle.
         */
        val ENGINE_PREFERENCE = listOf(
            ModelStore.Engine.GENIEX,
            ModelStore.Engine.GENIEX_GGUF,
        )
    }
}
