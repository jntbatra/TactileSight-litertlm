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

    /**
     * What the model is actually doing, so the screen can stop guessing.
     *
     * The status line used to be written by the picker: choosing an engine
     * printed `Ready · GenieX (qairt/npu)` while the model had not been mapped
     * yet, and the first press then came back "Sorry, I could not see that."
     * The picker knows which engine was *chosen*; only this knows whether it is
     * actually *there*.
     */
    enum class ModelState { LOADING, READY, FAILED }

    @Volatile
    var modelState: ModelState = ModelState.LOADING
        private set

    /**
     * Load the resident model and record how it went.
     *
     * Driven from the Activity rather than [onCreate] because it suspends and
     * takes tens of seconds for a multi-gigabyte VLM — blocking process start
     * on it hands Android a frozen app to kill before the first frame is drawn.
     */
    suspend fun prepareBrain(): ModelState {
        // Loop rather than a single pass: switching engines mid-load is normal
        // at a demo, and the first version simply *dropped* the result when the
        // brain had changed underneath it. That left the state stale — a model
        // that had loaded in 7 s while the screen still reported the outcome of
        // the brain before it. Whoever is resident when a load finishes is the
        // one the status has to describe, so prepare that one too.
        while (true) {
            val loading = brain
            modelState = ModelState.LOADING
            val ready = try {
                loading.prepare()
            } catch (e: Exception) {
                Log.w(TAG, "preparing ${loading.name} failed", e)
                false
            }

            if (loading !== brain) {
                Log.i(TAG, "brain changed while ${loading.name} was loading — preparing the new one")
                continue
            }
            modelState = if (ready) ModelState.READY else ModelState.FAILED
            Log.i(TAG, "${loading.name} is $modelState")
            return modelState
        }
    }

    /** The configuration the resident brain was built for — see [applyMode]. */
    @Volatile
    private var loadedConfiguration: String? = null

    val settings by lazy { Settings(this) }

    override fun onCreate() {
        super.onCreate()
        applyMode(settings.mode)
    }

    /**
     * The one on-device brain, built once and never rebuilt.
     *
     * Switching to the private server used to *close* this — four gigabytes of
     * mapped weights evicted to make room for an HTTP client, and a 7 s reload
     * (or a wedged DSP) waiting on the way back. The one-model-resident rule
     * it was obeying is about never holding two **models** at once; a server
     * brain is not one. So the local brain is parked, not released, and coming
     * back to it is instant.
     *
     * Released only in [releaseLocalBrain] — when the engine or the staged
     * model itself changes, which is the case the rule was actually written
     * for.
     */
    @Volatile
    private var localBrain: SemanticBrain? = null

    /**
     * Switch to [mode], releasing whatever model was resident.
     *
     * There is no privacy re-resolution any more: with the cloud tier gone the
     * picker offers on-device or our own laptop, and the selection itself is
     * the statement about whether the frame leaves the phone. [BrainMode]
     * still carries `sendsImageryOffDevice`, which is what the UI uses to
     * decide an endpoint is needed — and what a future third-party tier would
     * have to declare.
     */
    fun applyMode(requested: BrainMode) {
        settings.mode = requested
        val mode = requested

        // Rebuild only when the configuration actually changed. Without this,
        // anything that calls applyMode — including a text-field callback that
        // fires on setText — drops the resident model and forces a 30–60 s
        // reload of several gigabytes. Hard rule #1: load once and keep it.
        val key = configurationKey(mode)
        if (key == loadedConfiguration) return
        loadedConfiguration = key

        switchBrain(brainFor(mode))
        Log.i(TAG, "brain = ${brain.name} (mode=$mode)")
    }

    /** Everything that decides which brain we need. Same key, same brain. */
    private fun configurationKey(mode: BrainMode): String = when (mode) {
        BrainMode.ON_DEVICE_NPU -> mode.name
        BrainMode.PRIVATE_SERVER ->
            "${mode.name}|${settings.privateServerUrl}|${settings.privateServerIsOpenAi}|" +
                settings.privateServerModel
    }

    /**
     * Note this only *constructs* the brain — GenieXBrain loads its model lazily
     * on first use, under its own lock. Startup stays fast and a missing model
     * surfaces as a spoken fallback rather than a crash at launch.
     */
    private fun brainFor(mode: BrainMode): SemanticBrain = when (mode) {
        // Built at most once per process. Reused across every switch to the
        // server and back, so the model is mapped exactly one time.
        BrainMode.ON_DEVICE_NPU -> localBrain
            ?: onDeviceBrain(ModelStore.Engine.GENIEX).also { localBrain = it }

        // Our own machine. Two kinds of server land here and they speak
        // different wires, so the Check button probes and remembers which:
        //   server/app.py  -> POST /v1/describe   (the frozen contract)
        //   LM Studio etc. -> POST /v1/chat/completions
        // Talking to LM Studio directly is the simpler setup and needs no
        // FastAPI layer, which matters when the server is not ours to change.
        BrainMode.PRIVATE_SERVER -> {
            val url = settings.privateServerUrl
            when {
                url.isBlank() -> {
                    Log.w(TAG, "no URL set for $mode — falling back to the stub brain")
                    StubBrain()
                }

                settings.privateServerIsOpenAi -> ImagineBrain(
                    baseUrl = url,
                    model = settings.privateServerModel,
                    // A local server wants no auth; an empty bearer token is a
                    // 401 waiting to happen.
                    apiKey = "",
                    promptOverride = { settings.customPrompt },
                    label = mode.displayName,
                )

                else -> CloudBrain(baseUrl = url, name = mode.displayName)
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

        return GenieXBrain(
            context = this,
            modelDir = modelStore.available(engine).first(),
            promptOverride = { settings.customPrompt },
        )
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
            // The new brain has not been prepared yet, whatever the old one's
            // state was. Leaving READY here is how the screen came to claim a
            // model was loaded that had never been touched.
            modelState = ModelState.LOADING
            // The local brain is parked, not closed: it is the only thing here
            // that owns gigabytes, and dropping it to talk to a server means
            // paying to map it all over again the moment the network wobbles
            // and you switch back.
            if (previous === localBrain) {
                Log.i(TAG, "parking ${previous.name} — model stays resident")
            } else {
                previous.close()
            }
        }
    }

    /**
     * Actually release the on-device model.
     *
     * The one place the parked brain dies. Not called on a mode switch — that
     * is the whole point — but kept because a genuine engine or model change
     * has to be able to reclaim the memory, and the dev comparison hook loads
     * bundles in turn and must never hold two at once.
     */
    fun releaseLocalBrain() {
        synchronized(brainLock) {
            val parked = localBrain ?: return
            localBrain = null
            if (parked !== brain) {
                parked.close()
                Log.i(TAG, "released the parked ${parked.name}")
            }
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
