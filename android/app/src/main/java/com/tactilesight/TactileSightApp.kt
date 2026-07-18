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
        // GGUF first: GenieX 0.3.1's QAIRT plugin carries exactly one VLM
        // factory (qwen2_5_vl), so a QAIRT bundle for any other architecture
        // fails to dispatch — our Qwen3-VL-4B bundle does, with
        //   "no VLM factory matches model_id 'qwen3_vl_4b_instruct'".
        // llama_cpp has no such registry and is the path that has actually
        // produced tokens on this device. Reaching the NPU needs a Qwen2.5-VL
        // QAIRT bundle; until one is staged, GGUF is the working brain.
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
