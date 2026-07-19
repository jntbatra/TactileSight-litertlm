package com.tactilesight

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import androidx.viewpager2.widget.ViewPager2
import com.geniex.sdk.bean.ComputeUnitValue
import com.geniex.sdk.bean.RuntimeIdValue
import com.tactilesight.brain.GenieXBrain
import com.tactilesight.brain.ModelStore
import com.tactilesight.brain.PromptBenchmark
import com.tactilesight.brain.ServerCheck
import com.tactilesight.brain.VlmPrompt
import com.tactilesight.core.BrainMode
import com.tactilesight.core.BrowsableFrameSource
import com.tactilesight.core.Frame
import com.tactilesight.core.FrameSource
import com.tactilesight.core.Language
import com.tactilesight.core.Orchestrator
import com.tactilesight.databinding.ActivityMainBinding
import com.tactilesight.frame.BundledCaptureSource
import com.tactilesight.frame.DepthCoverage
import com.tactilesight.frame.DepthRenderer
import com.tactilesight.frame.FramePage
import com.tactilesight.frame.FramePagerAdapter
import android.content.Intent
import android.nfc.NfcAdapter
import android.nfc.Tag
import com.tactilesight.frame.FrameSourceKind
import com.tactilesight.nfc.BandTag
import com.tactilesight.frame.ObjectDetector
import com.tactilesight.speech.MicRecorder
import com.tactilesight.speech.SarvamAsr
import com.tactilesight.speech.SarvamSpeechIO
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

/**
 * The walking skeleton (#1): pick a bundled band capture, press, hear a
 * sentence.
 *
 * There is no phone camera in this app — frames are real Astra Pro Plus
 * captures. The band has three physical buttons (ADR-0011); until the band is
 * wired, this screen carries button 1 only. Buttons 2 and 3 arrive with #17,
 * the engine picker with #7.
 *
 * The screen depends on [FrameSource] and nothing narrower. Browsing is offered
 * only when the source says it is browsable, so the live WebRTC source (#19)
 * drops in without this file changing — which is the point of the seam.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var frames: FrameSource
    private lateinit var orchestrator: Orchestrator
    private val recorder = MicRecorder()
    private val detector by lazy { ObjectDetector(applicationContext) }
    private val asr = SarvamAsr()

    /** The scene captured at press-down, answered about at release (#9). */
    private var heldFrame: Frame? = null

    /** True only between pressing "write tag" and a tag arriving. */
    private var armedForTagWrite = false
    private val pages = FramePagerAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        frames = BundledCaptureSource(assets)
        orchestrator = Orchestrator(
            frames = frames,
            // Owned by the Application, so rotation never drops the model.
            brain = { (application as TactileSightApp).brain },
            speech = SarvamSpeechIO(cacheDir),
            // Named objects get their own distance; everything else falls back
            // to the per-direction reading. Constructed once - the interpreter
            // maps a 10 MB model and must not be rebuilt per press.
            detect = detector.takeIf { it.isAvailable }?.let { { jpeg: ByteArray -> it.detect(jpeg) } },
            // Resolved per press, like the brain: changing language must not
            // need a restart, and must not reload a multi-gigabyte model.
            language = { (application as TactileSightApp).settings.language },
        )

        setUpCarousel()
        setUpSourcePicker()
        setUpBrainPicker()
        setUpLanguagePicker()
        setUpScenePicker()

        if (BuildConfig.SARVAM_API_KEY.isBlank()) {
            binding.status.setText(R.string.status_no_key)
        }

        binding.describeButton.setOnClickListener { onPress() }
        setUpTagWriting()

        // Arriving by tap is worth announcing: the user cannot see that the
        // app opened, and silence after touching the band is indistinguishable
        // from nothing having happened.
        if (BandTag.launchedByTap(intent)) {
            Log.i(TAG, "launched by NFC tap")
            lifecycleScope.launch { orchestrator.speakReady() }
        }
        setUpAskButton()

        // Dev affordance, not a feature: adb shell am start -n <pkg>/.MainActivity --ez sweep true
        if (intent?.getBooleanExtra(EXTRA_SWEEP, false) == true) runPromptSweep()

        // adb shell am start -n <pkg>/.MainActivity --ez hexagon true
        // Answers one question: will llama_cpp drive a GGUF on the Hexagon NPU?
        // --es unit npu|gpu|hybrid|cpu picks which one to drive.
        if (intent?.getBooleanExtra(EXTRA_HEXAGON, false) == true) {
            runHexagonProbe(intent?.getStringExtra(EXTRA_UNIT) ?: "npu")
        }

        // adb shell am start -n <pkg>/.MainActivity --ez press true [--ei scenes N]
        //
        // Drives real presses through the Orchestrator, which is the only way
        // to hear what the user hears. The compare hook calls the brain
        // directly, so it never exercises the depth-fused distance clause or
        // Sarvam - a distance bug would pass every check compare can make.
        // This speaks aloud on purpose: it is the end-to-end path, not a probe.
        if (intent?.getBooleanExtra(EXTRA_PRESS, false) == true) {
            runPressSweep(
                scenes = intent?.getIntExtra(EXTRA_SCENES, 3) ?: 3,
                from = intent?.getIntExtra(EXTRA_FROM, 0) ?: 0,
            )
        }

        // adb shell am start -n <pkg>/.MainActivity --ez compare true
        // --es bundles geniex,geniex-4b   --ei scenes 3
        // Runs every named QAIRT bundle over the same scenes so model choice is
        // decided by output, not by parameter count.
        if (intent?.getBooleanExtra(EXTRA_COMPARE, false) == true) {
            runModelComparison(
                names = (intent?.getStringExtra(EXTRA_BUNDLES) ?: DEFAULT_COMPARE_BUNDLES)
                    .split(",").map { it.trim() }.filter { it.isNotEmpty() },
                scenes = intent?.getIntExtra(EXTRA_SCENES, 3) ?: 3,
            )
        }
    }

    /**
     * Hold to ask (#9). Down captures the frame and opens the mic; up closes
     * it, transcribes, and answers about the frame captured at *down*.
     *
     * Why the frame is taken at press-down and not at release: by the time the
     * question ends, the user may have turned their head, or the person they
     * were asking about may have walked on. The scene they were asking about
     * is the one that was in front of them when they decided to ask.
     *
     * Nothing is spoken while the mic is open. Text-to-speech into an open
     * microphone records the device answering itself, and the transcript comes
     * back as our own last sentence.
     */
    /**
     * Writing a tag is a provisioning action, not a user control - a sighted
     * teammate does it once per band. Foreground dispatch is only enabled while
     * armed, because a blank tag matches no intent filter and would otherwise
     * never reach us.
     */
    private fun setUpTagWriting() {
        binding.writeTagButton.setOnClickListener {
            if (BandTag.adapterFor(this) == null) {
                binding.status.setText(R.string.tag_no_nfc)
                return@setOnClickListener
            }
            armedForTagWrite = true
            BandTag.startWriting(this)
            Log.i(TAG, "armed for tag write")
            binding.status.setText(R.string.tag_hold)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        Log.i(TAG, "onNewIntent: action=${intent.action} armed=$armedForTagWrite")
        if (!armedForTagWrite) return
        val tag: Tag? = if (android.os.Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(NfcAdapter.EXTRA_TAG, Tag::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(NfcAdapter.EXTRA_TAG)
        }
        val outcome = BandTag.write(tag)
        armedForTagWrite = false
        BandTag.stopWriting(this)
        binding.status.text = outcome
        Log.i(TAG, "tag write: $outcome")
    }

    /**
     * Foreground dispatch has to be re-armed here, not only where the button is
     * pressed.
     *
     * Delivering a tag takes the activity through pause and resume, so dispatch
     * enabled in a click handler is torn down by the very tap it was waiting
     * for. The first version also cleared the armed flag in onPause, and the
     * log said exactly that: `TAG_DISCOVERED armed=false` — the tag arrived and
     * we had just stopped listening for it.
     */
    override fun onResume() {
        super.onResume()
        if (armedForTagWrite) BandTag.startWriting(this)
    }

    /** Android requires dispatch to be disabled here. The flag survives. */
    override fun onPause() {
        super.onPause()
        BandTag.stopWriting(this)
    }

    private fun setUpAskButton() {
        binding.askButton.setOnTouchListener { view, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    view.performClick()
                    startAsking()
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    finishAsking()
                    true
                }
                else -> false
            }
        }
    }

    private fun startAsking() {
        if (!hasMicPermission()) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_MIC)
            binding.status.setText(R.string.status_no_mic)
            return
        }
        // Capture first: the mic can wait a few milliseconds, the scene cannot.
        heldFrame = null
        lifecycleScope.launch { heldFrame = orchestrator.captureNow() }

        if (recorder.start()) {
            binding.status.setText(R.string.status_listening)
        } else {
            binding.status.setText(R.string.status_no_mic)
        }
    }

    private fun finishAsking() {
        if (!recorder.isRecording) return
        val wav = recorder.stop()
        binding.status.setText(R.string.status_working)

        lifecycleScope.launch {
            // A hold that produced no usable transcript falls through to a
            // description rather than an apology (#11): someone who was not
            // understood is better served by hearing what is in front of them.
            val question = wav?.let { asr.transcribe(it) }
            Log.i(TAG, "asked: ${question ?: "(nothing heard — describing instead)"}")
            try {
                binding.status.text = orchestrator.answerAbout(heldFrame, question)
            } catch (e: Exception) {
                Log.e(TAG, "ask failed", e)
                binding.status.setText(R.string.status_speech_failed)
            }
            heldFrame = null
        }
    }

    private fun hasMicPermission(): Boolean =
        checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    /**
     * Press the button for real, once per scene, and log what was spoken.
     *
     * Everything the user experiences runs here and nowhere else: capture,
     * describe, the measured distance clause, translation and speech. Each
     * press is timed end to end, because the distance work reads ~300k depth
     * pixels and the claim that this is free next to the VLM should be a number
     * rather than an assumption.
     */
    private fun runPressSweep(scenes: Int, from: Int = 0) {
        val browsable = frames as? BrowsableFrameSource ?: return
        binding.status.text = getString(R.string.status_working)

        lifecycleScope.launch {
            delay(SETTLE_BEFORE_SWEEP_MS)
            val chosen = browsable.sceneIds.drop(from).take(scenes)
            for ((offset, id) in chosen.withIndex()) {
                val index = from + offset
                // Drive the real selection, so capture() returns this scene -
                // onPress() must be reached exactly as a button press reaches it.
                browsable.selectedIndex = index
                val startedAt = System.currentTimeMillis()
                try {
                    val spoken = orchestrator.onPress()
                    Log.i(TAG, "press[$id] ${System.currentTimeMillis() - startedAt}ms: $spoken")
                } catch (e: Exception) {
                    Log.e(TAG, "press[$id] FAILED", e)
                }
            }
            Log.i(TAG, "press sweep: done")
            binding.status.text = "Press sweep done — see logcat"
        }
    }

    /**
     * Load each QAIRT bundle in turn and describe the same scenes with each.
     *
     * Deliberately sequential with a settle between models: two multi-GB VLMs
     * must never be resident at once, and the previous process's mapping is not
     * reclaimed the instant it is closed — a comparison that OOMs the second
     * model has measured nothing except its own impatience.
     *
     * Everything goes to logcat rather than the screen because the point is to
     * read the answers side by side afterwards. Failures are logged and the run
     * continues: "this bundle does not load" is a result worth having, and it is
     * the most likely result for a bundle that is too large for the device.
     */
    private fun runModelComparison(names: List<String>, scenes: Int) {
        val app = application as TactileSightApp
        binding.status.text = getString(R.string.status_working)

        lifecycleScope.launch {
            val browsable = frames as BrowsableFrameSource
            val sceneIds = browsable.sceneIds.take(scenes)
            Log.i(TAG, "compare: ${names.size} bundles x ${sceneIds.size} scenes")

            for (name in names) {
                val dir = File(getExternalFilesDir(null), "models/$name")
                if (!dir.isDirectory) {
                    Log.w(TAG, "compare[$name]: no such bundle — skipped")
                    continue
                }

                // Let the previous model's pages go back before mapping the next.
                delay(SETTLE_BEFORE_SWEEP_MS)
                Log.i(TAG, "compare[$name]: available=${memAvailableMb()} MB before load")

                val brain = GenieXBrain(
                    context = applicationContext,
                    modelDir = dir,
                    runtime = RuntimeIdValue.QAIRT,
                    computeUnit = ComputeUnitValue.NPU,
                )
                try {
                    app.switchBrain(brain)
                    for (id in sceneIds) {
                        val answer = brain.describe(browsable.load(id))
                        Log.i(TAG, "compare[$name] $id: ${answer.spoken}")
                    }
                    Log.i(TAG, "compare[$name]: available=${memAvailableMb()} MB after")
                } catch (e: Exception) {
                    Log.e(TAG, "compare[$name]: FAILED — ${e.message}", e)
                }
            }
            Log.i(TAG, "compare: done")
            binding.status.text = "Comparison done — see logcat"
        }
    }

    /** Read straight from the kernel; Runtime.maxMemory reports the heap cap. */
    private fun memAvailableMb(): Long = try {
        File("/proc/meminfo").readLines()
            .first { it.startsWith("MemAvailable:") }
            .filter { it.isDigit() }.toLong() / 1024
    } catch (e: Exception) {
        -1
    }

    private fun setUpSourcePicker() {
        val kinds = FrameSourceKind.entries
        binding.sourceSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            kinds.map { if (it.available) it.displayName else "${it.displayName} — not yet" },
        )

        binding.sourceSpinner.onSelect { position ->
            val kind = kinds[position]
            if (!kind.available) {
                Toast.makeText(
                    this,
                    getString(R.string.source_unavailable, kind.displayName),
                    Toast.LENGTH_SHORT,
                ).show()
                binding.sourceSpinner.setSelection(kinds.indexOf(FrameSourceKind.BUNDLED))
            }
        }
    }

    /**
     * Where the frame gets described: on the phone, or on our own server. The
     * endpoint field appears only for the one that needs an address.
     *
     * There is no privacy toggle. With the cloud tier gone the choice is
     * on-device or our own laptop, and which one is selected already says
     * whether the frame leaves the phone — a switch on top of that would be a
     * second control for a decision the picker has already made.
     */
    private fun setUpBrainPicker() {
        val app = application as TactileSightApp
        val modes = BrainMode.entries

        binding.brainSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            modes.map { it.displayName },
        )
        binding.brainSpinner.setSelection(modes.indexOf(app.settings.mode))

        binding.brainSpinner.onSelect { position ->
            val requested = modes[position]
            app.applyMode(requested)
            showEndpointFor(app.settings.mode)

            if (requested.sendsImageryOffDevice && app.settings.urlFor(requested).isBlank()) {
                toast(getString(R.string.endpoint_missing))
            }
            showBrain()
        }

        // The address is saved as it is typed, so a tunnel URL survives the
        // app being killed — which ColorOS does aggressively.
        binding.endpointField.doAfterTextChanged { text ->
            val mode = app.settings.mode
            if (mode.sendsImageryOffDevice) {
                app.settings.setUrlFor(mode, text?.toString().orEmpty())
                app.applyMode(mode)
                showBrain()
            }
        }

        binding.modelField.doAfterTextChanged { text ->
            val typed = text?.toString().orEmpty()
            when (app.settings.mode) {
                BrainMode.PRIVATE_SERVER -> app.settings.privateServerModel = typed
                else -> return@doAfterTextChanged
            }
            app.applyMode(app.settings.mode)
            showBrain()
        }

        // Show the prompt that will actually be sent, so it can be read and
        // edited rather than guessed at. Stored blank whenever it matches the
        // built-in one — that way a later improvement to VlmPrompt still
        // reaches anyone who never customised it, instead of being frozen out
        // by a copy of today's wording sitting in their preferences.
        binding.promptField.setText(app.settings.customPrompt.ifBlank { VlmPrompt.describe() })
        binding.promptField.doAfterTextChanged { text ->
            val typed = text?.toString().orEmpty()
            app.settings.customPrompt = if (typed.trim() == VlmPrompt.describe()) "" else typed
        }

        binding.checkServerButton.setOnClickListener { checkServer() }

        binding.resetPromptButton.setOnClickListener {
            app.settings.customPrompt = ""
            binding.promptField.setText(VlmPrompt.describe())
            toast(getString(R.string.reset_prompt))
        }

        showEndpointFor(app.settings.mode)
        showBrain()
    }

    /**
     * Every language Sarvam speaks. Switching takes effect on the next press
     * with no model reload — the VLM always answers in English and the language
     * is applied at speech time (ADR-0012), which is what makes 23 languages
     * cost the same as one.
     */
    private fun setUpLanguagePicker() {
        val app = application as TactileSightApp
        val languages = Language.speakable

        binding.languageSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            languages.map { it.displayName },
        )
        binding.languageSpinner.setSelection(languages.indexOf(app.settings.language))

        binding.languageSpinner.onSelect { position ->
            app.settings.language = languages[position]
        }
    }

    /**
     * Probe the endpoint from the phone, where it is typed and where it goes
     * wrong. Distinguishes "our server" from "an OpenAI-compatible server that
     * will 404 on every press" — the latter looks alive and is not.
     */
    private fun checkServer() {
        val app = application as TactileSightApp
        val url = app.settings.urlFor(app.settings.mode)

        binding.status.setText(R.string.checking_server)
        lifecycleScope.launch {
            binding.status.text = when (val result = ServerCheck.probe(url)) {
                is ServerCheck.Result.Ready -> {
                    app.settings.privateServerIsOpenAi = false
                    "Server ready · backend: ${result.backend}"
                }

                // Not an error: an OpenAI-compatible server is a supported
                // private tier. Remember it so Describe uses that wire, and
                // preselect a model so the next press works without typing.
                is ServerCheck.Result.OpenAiCompatible -> {
                    app.settings.privateServerIsOpenAi = true
                    if (app.settings.privateServerModel !in result.models) {
                        app.settings.privateServerModel = result.models.first()
                        binding.modelField.setText(result.models.first())
                    }
                    "OpenAI-compatible server · using ${app.settings.privateServerModel}" +
                        "\nAvailable: ${result.models.joinToString()}"
                }

                is ServerCheck.Result.Unreachable ->
                    "Cannot reach it — ${result.detail}"
            }
            app.applyMode(app.settings.mode)
            Log.i(TAG, binding.status.text.toString())
        }
    }

    private fun showEndpointFor(mode: BrainMode) {
        val app = application as TactileSightApp
        binding.endpointField.visibility =
            if (mode.sendsImageryOffDevice) View.VISIBLE else View.GONE
        binding.checkServerButton.visibility =
            if (mode.sendsImageryOffDevice) View.VISIBLE else View.GONE
        // Only the cloud needs a model named: the private server picks its own
        // via TS_VLM_BACKEND, and on-device uses whatever is staged.
        // Both server modes need a model named; on-device uses what is staged.
        binding.modelField.visibility =
            if (mode.sendsImageryOffDevice) View.VISIBLE else View.GONE

        val savedUrl = app.settings.urlFor(mode)
        if (binding.endpointField.text.toString() != savedUrl) {
            binding.endpointField.setText(savedUrl)
        }
        val savedModel = app.settings.privateServerModel
        if (binding.modelField.text.toString() != savedModel) {
            binding.modelField.setText(savedModel)
        }
    }

    /** Name the resident brain, so which engine answered is never a guess. */
    private fun showBrain() {
        val app = application as TactileSightApp
        binding.status.text = getString(R.string.status_brain, app.brain.name)
    }

    /** Score two prompt wordings over the bundled captures — see PromptBenchmark. */
    private fun runPromptSweep() {
        val brain = (application as TactileSightApp).brain as? GenieXBrain ?: run {
            Log.w(TAG, "prompt sweep needs an on-device brain, got ${(application as TactileSightApp).brain.name}")
            return
        }
        val browsable = frames as? BrowsableFrameSource ?: return

        binding.status.text = getString(R.string.status_working)
        lifecycleScope.launch {
            try {
                // The previous process's multi-gigabyte mapping is still being
                // reclaimed for a few seconds after a force-stop, and loading
                // into that window fails with a bare "Model loading failed".
                // Nothing to do but let the kernel catch up.
                delay(SETTLE_BEFORE_SWEEP_MS)
                PromptBenchmark.run(brain, browsable)
                binding.status.text = "Prompt sweep done — see logcat"
            } catch (e: Exception) {
                Log.e(TAG, "prompt sweep failed", e)
            }
        }
    }

    /**
     * Can llama_cpp run a GGUF on Hexagon rather than Adreno?
     *
     * The plugin ships `libggml-hexagon.so` and logs "Reacquiring HTP sessions
     * before llama.cpp load", so the path exists; whether it loads a 5 GB q4_0
     * VLM is another matter. Worth knowing, because it is the only route that
     * puts a community GGUF on the NPU — QAIRT will only take architectures it
     * has a compiled factory for.
     */
    private fun runHexagonProbe(unit: String) {
        val app = application as TactileSightApp
        val bundle = ModelStore(this)
            .available(ModelStore.Engine.GENIEX_GGUF)
            .firstOrNull() ?: run {
            Log.w(TAG, "hexagon probe: no GGUF staged")
            return
        }

        binding.status.text = getString(R.string.status_working)
        lifecycleScope.launch {
            delay(SETTLE_BEFORE_SWEEP_MS)
            val computeUnit = ComputeUnitValue.entries
                .firstOrNull { it.value.equals(unit, ignoreCase = true) }
                ?: ComputeUnitValue.NPU
            Log.i(TAG, "probe: llama_cpp on ${computeUnit.value}")

            val brain = GenieXBrain(
                context = applicationContext,
                modelDir = bundle,
                runtime = RuntimeIdValue.LLAMA_CPP,
                computeUnit = computeUnit,
            )
            try {
                // Replace the resident brain so we never hold two models.
                app.switchBrain(brain)
                val browsable = frames as BrowsableFrameSource
                val frame = browsable.load(browsable.sceneIds.first())
                val answer = brain.describe(frame)
                Log.i(TAG, "hexagon probe OK: ${answer.spoken}")
                binding.status.text = "Hexagon probe: ${answer.spoken}"
            } catch (e: Exception) {
                Log.e(TAG, "hexagon probe FAILED", e)
                binding.status.text = "Hexagon probe failed — see logcat"
            }
        }
    }

    private fun toast(message: String) =
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    /**
     * A capture picker, but only for a source that has captures to pick from.
     * A live source has no list, so the row disappears and the carousel waits
     * for a press instead.
     */
    private fun setUpScenePicker() {
        val browsable = frames as? BrowsableFrameSource

        if (browsable == null) {
            binding.captureLabel.visibility = View.GONE
            binding.sceneSpinner.visibility = View.GONE
            binding.status.setText(R.string.status_press_to_capture)
            return
        }

        val labels = browsable.sceneIds.indices.map {
            getString(R.string.capture_label, it + 1, browsable.sceneIds.size)
        }
        binding.sceneSpinner.adapter =
            ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)

        binding.sceneSpinner.onSelect { position ->
            browsable.selectedIndex = position
            lifecycleScope.launch {
                try {
                    showFrame(browsable.load(browsable.sceneIds[position]))
                } catch (e: Exception) {
                    Log.e(TAG, "preview failed", e)
                }
            }
        }
    }

    private fun setUpCarousel() {
        binding.framePager.adapter = pages
        binding.framePager.registerOnPageChangeCallback(
            object : ViewPager2.OnPageChangeCallback() {
                override fun onPageSelected(position: Int) {
                    buildDots(count = pages.itemCount, selected = position)
                }
            },
        )
    }

    /** Show a frame's three streams. Works for any source — see [DepthRenderer]. */
    private fun showFrame(frame: Frame) {
        // All three previews are trimmed to the one region both sensors share,
        // so flicking through the carousel compares like with like — and the
        // colour page is byte-identical to what the VLM was given.
        //
        // The two crops are not the same rectangle because the sensors do not
        // have the same field of view: colour loses its edges (depth cannot
        // reach them), depth and IR lose their top and bottom (colour cannot
        // see them). See DepthCoverage.COLOUR / DepthCoverage.DEPTH.
        val colour = DepthCoverage.cropToMeasurableRegion(frame.rgbJpeg).toBitmap()
        val depth = DepthCoverage.crop(
            DepthRenderer.render(frame.depthMillimetres),
            DepthCoverage.DEPTH,
        )

        pages.submit(
            listOf(
                FramePage(label(R.string.stream_rgb, colour), getString(R.string.preview_rgb), colour),
                FramePage(label(R.string.stream_depth, depth), getString(R.string.preview_depth), depth),
            ),
        )
        buildDots(count = 2, selected = binding.framePager.currentItem)
    }

    /** Name plus the dimensions actually on screen — so a crop is visible as a number. */
    private fun label(nameRes: Int, bitmap: Bitmap?): String =
        if (bitmap == null) getString(nameRes)
        else "${getString(nameRes)} · ${bitmap.width}×${bitmap.height}"

    /** Three dots under the carousel; the current page is Qualcomm Blue. */
    private fun buildDots(count: Int, selected: Int) {
        val dots = binding.dots
        if (dots.childCount != count) {
            dots.removeAllViews()
            repeat(count) { index ->
                dots.addView(
                    View(this).apply {
                        setBackgroundResource(R.drawable.dot_indicator)
                        layoutParams = LinearLayout.LayoutParams(dotSize, dotSize).apply {
                            marginStart = if (index == 0) 0 else dotSize
                        }
                    },
                )
            }
        }
        for (index in 0 until dots.childCount) {
            dots.getChildAt(index).isSelected = index == selected
        }
    }

    private fun onPress() {
        binding.describeButton.isEnabled = false
        binding.status.setText(R.string.status_working)

        lifecycleScope.launch {
            try {
                binding.status.text = orchestrator.onPress()
            } catch (e: Exception) {
                // Speech itself failed. With no offline TTS fallback in the MVP
                // (ADR-0012) there is nothing left to say aloud, so say it on
                // screen rather than fail silently.
                Log.e(TAG, "speech failed", e)
                binding.status.setText(R.string.status_speech_failed)
            } finally {
                binding.describeButton.isEnabled = true
            }
        }
    }

    /**
     * Spinner selection without the two-method anonymous-listener boilerplate.
     *
     * Skips the **initial** callback. A Spinner fires one selection event as it
     * lays out, before the user has touched anything, and treating that as a
     * choice overwrites the persisted one with whatever sits at position 0 —
     * so a saved language of हिन्दी silently became English on every launch.
     */
    private fun Spinner.onSelect(action: (position: Int) -> Unit) {
        var seenInitialCallback = false
        onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, position: Int, id: Long) {
                if (!seenInitialCallback) {
                    seenInitialCallback = true
                    return
                }
                action(position)
            }

            override fun onNothingSelected(p: AdapterView<*>?) = Unit
        }
    }

    private fun ByteArray.toBitmap() = BitmapFactory.decodeByteArray(this, 0, size)

    private val dotSize by lazy { (8 * resources.displayMetrics.density).toInt() }

    private companion object {
        const val TAG = "MainActivity"
        const val EXTRA_SWEEP = "sweep"
        const val SETTLE_BEFORE_SWEEP_MS = 8_000L
        const val EXTRA_HEXAGON = "hexagon"
        const val EXTRA_UNIT = "unit"
        const val EXTRA_COMPARE = "compare"
        const val EXTRA_PRESS = "press"
        const val EXTRA_FROM = "from"
        const val REQUEST_MIC = 1001
        const val EXTRA_BUNDLES = "bundles"
        const val EXTRA_SCENES = "scenes"

        /** Both QAIRT bundles staged on the device: the 8B upgrade and the 4B incumbent. */
        const val DEFAULT_COMPARE_BUNDLES = "geniex,geniex-4b"
    }
}
