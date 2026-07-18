package com.tactilesight

import android.app.Application
import android.util.Log
import com.tactilesight.brain.CloudBrain
import com.tactilesight.brain.GenieXBrain
import com.tactilesight.brain.ImagineBrain
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
        BrainMode.ON_DEVICE_NPU -> onDeviceBrain(ModelStore.Engine.GENIEX)
        BrainMode.ON_DEVICE_GPU -> onDeviceBrain(ModelStore.Engine.GENIEX_GGUF)

        // Our own server, speaking our frozen /v1/describe contract.
        BrainMode.PRIVATE_SERVER -> settings.privateServerUrl.ifBlank {
            Log.w(TAG, "no URL set for $mode — falling back to the stub brain")
            return StubBrain()
        }.let { CloudBrain(baseUrl = it, name = mode.displayName) }

        // Cirrascale's Imagine API, reached directly. Deliberately not routed
        // through our server: the three destinations must fail independently.
        BrainMode.CLOUD -> {
            val model = settings.cloudModel
            if (model.isBlank()) {
                Log.w(TAG, "no cloud model set — falling back to the stub brain")
                StubBrain()
            } else {
                ImagineBrain(baseUrl = settings.cloudUrl, model = model)
            }
        }
    }

    /**
     * The brain for one specific engine, so the picker means what it says.
     *
     * If that engine has nothing staged we fall back to the *other* on-device
     * engine rather than to silence — hard rule #4, every press yields speech.
     * That is not a lie to the user: [SemanticBrain.name] reports the engine
     * that actually answered, and the screen shows it.
     */
    private fun onDeviceBrain(preferred: ModelStore.Engine): SemanticBrain {
        val order = listOf(preferred) + ENGINE_PREFERENCE.filterNot { it == preferred }
        val engine = order.firstOrNull { modelStore.available(it).isNotEmpty() }

        if (engine == null) {
            Log.w(
                TAG,
                "no model in " +
                    order.joinToString { modelStore.directoryFor(it).path } +
                    " — falling back to the stub brain",
            )
            return StubBrain()
        }

        if (engine != preferred) {
            Log.w(TAG, "no bundle for $preferred — using $engine instead")
        }

        return GenieXBrain(context = this, modelDir = modelStore.available(engine).first())
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
