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
import com.tactilesight.frame.PhoneCameraSource
import com.tactilesight.speech.MicRecorder
import com.tactilesight.speech.SarvamAsr
import com.tactilesight.speech.SarvamSpeechIO
import com.tactilesight.speech.SpokenSetup
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

    /** Whichever source the camera picker currently points at. */
    private lateinit var frames: FrameSource

    /** The band's captures. Kept because the dev scene picker browses them. */
    private lateinit var bundled: BundledCaptureSource
    private val phoneCamera by lazy { PhoneCameraSource(this, this) }
    private lateinit var orchestrator: Orchestrator
    private val recorder = MicRecorder()
    private val detector by lazy { ObjectDetector(applicationContext) }
    private val asr = SarvamAsr()
    private val speech by lazy { SarvamSpeechIO(cacheDir) }
    private val setup by lazy { SpokenSetup(speech, recorder, asr) }

    /** The scene captured at press-down, answered about at release (#9). */
    private var heldFrame: Frame? = null

    /** True only between pressing "write tag" and a tag arriving. */
    private var armedForTagWrite = false

    /** When the action button went down — tap or hold is decided at release. */
    private var pressedAtMillis = 0L

    /** True once a press has put an answer on screen, so warm-up cannot erase it. */
    private var hasAnswered = false
    private val pages = FramePagerAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        bundled = BundledCaptureSource(assets)
        frames = bundled
        orchestrator = Orchestrator(
            // Resolved per press, so switching between the band's captures and
            // the phone camera takes effect immediately and without rebuilding
            // the pipeline around it.
            frames = { frames },
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
        setUpCameraPicker()
        setUpBrainPicker()
        setUpLanguagePicker()
        setUpScenePicker()
        setUpModeSwitch()

        if (BuildConfig.SARVAM_API_KEY.isBlank()) {
            binding.status.setText(R.string.status_no_key)
        }

        // Load the model now, not on the first press. Until this lands, the
        // screen used to claim "Ready" while nothing was mapped, and the first
        // press came back "Sorry, I could not see that" - the user had done
        // nothing wrong and the screen had told them nothing true.
        warmUpModel()

        // First launch asks by ear, so a blind user is never required to find
        // a spinner to be understood. Once only - see Settings.isConfigured.
        //
        // Never while a dev hook is driving: setup speaks and records, and it
        // raced a press sweep into a failed press before this guard existed.
        val app = application as TactileSightApp
        val drivenByHook = intent?.extras?.keySet().orEmpty().any { it in HOOK_EXTRAS }
        if (!app.settings.isConfigured && hasMicPermission() && !drivenByHook) runSpokenSetup()

        // adb shell am start -n <pkg>/.MainActivity --ez writetag true
        if (intent?.getBooleanExtra(EXTRA_WRITE_TAG, false) == true) armTagWriting()

        // Arriving by tap is worth announcing: the user cannot see that the
        // app opened, and silence after touching the band is indistinguishable
        // from nothing having happened.
        if (BandTag.launchedByTap(intent)) {
            Log.i(TAG, "launched by NFC tap")
            lifecycleScope.launch { orchestrator.speakReady() }
        }
        setUpActionButton()

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
     * Arm tag writing:
     * `adb shell am start -n <pkg>/.MainActivity --ez writetag true`
     *
     * Provisioning, not a user control — a band is tagged once, by a sighted
     * teammate, and a button for it sits on the demo screen forever afterwards
     * inviting a mis-tap. The capability stays because writing a second band
     * should not need a rebuild; only the button is gone.
     */
    private fun armTagWriting() {
        if (BandTag.adapterFor(this) == null) {
            binding.status.setText(R.string.tag_no_nfc)
            return
        }
        armedForTagWrite = true
        BandTag.startWriting(this)
        Log.i(TAG, "armed for tag write")
        binding.status.setText(R.string.tag_hold)
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

    /**
     * Ask which language, by voice, in English and Hindi.
     *
     * Recording is time-boxed rather than button-held: setup happens before the
     * user knows there is a button, and "speak after the tone" is the only
     * instruction that needs no prior knowledge of the device.
     */
    private fun runSpokenSetup() {
        val app = application as TactileSightApp
        if (!hasMicPermission()) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_MIC)
            return
        }

        binding.status.setText(R.string.status_setup_listening)
        lifecycleScope.launch {
            try {
                val outcome = setup.run {
                    if (!recorder.start()) return@run null
                    delay(SETUP_LISTEN_MS)
                    recorder.stop()
                }
                outcome.language?.let { app.settings.language = it }
                // Marked configured even when nothing was understood: asking
                // again on every launch is worse than staying in English, and
                // the button is there for a second try.
                app.settings.isConfigured = true
                setUpLanguagePicker()
                binding.status.text = outcome.spokenBack
                // translate = false when we recognised the language: the
                // confirmation is already authored in it. A failure message
                // stays English, because we do not know what else they read.
                speech.speak(
                    outcome.spokenBack,
                    app.settings.language,
                    translate = outcome.language == null,
                )
            } catch (e: Exception) {
                Log.w(TAG, "spoken setup failed", e)
                app.settings.isConfigured = true
                binding.status.setText(R.string.status_ready)
            }
        }
    }

    /**
     * One button, both gestures — the band's button 1 on glass.
     *
     * **Tap** (under [HOLD_THRESHOLD_MS]) describes. **Hold** captures at
     * press-down, records while held, and asks at release.
     *
     * The mic opens on *every* press-down and the buffer is thrown away on a
     * tap. Waiting until the threshold to start recording would be tidier and
     * would eat the first word of every question — a user who says "what's on
     * the table?" would be transcribed as "on the table?". A third of a second
     * of discarded audio is the cheaper mistake.
     *
     * The frame is taken at press-down in both cases: by the time a question
     * ends the user may have turned their head, or the person they were asking
     * about may have walked on.
     */
    private fun setUpActionButton() {
        binding.actionButton.setOnTouchListener { view, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    view.performClick()
                    pressedAtMillis = System.currentTimeMillis()
                    startAsking()
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val heldFor = System.currentTimeMillis() - pressedAtMillis
                    if (heldFor < HOLD_THRESHOLD_MS) tapToDescribe() else finishAsking()
                    true
                }
                else -> false
            }
        }
    }

    /**
     * A tap: drop the audio, keep the frame, describe it.
     *
     * The recording is discarded without being sent — a third of a second of
     * room noise is not a question, and asking Sarvam to transcribe it would
     * add a network round trip to the gesture that is meant to be the fast one.
     */
    private fun tapToDescribe() {
        recorder.stop()
        binding.status.setText(R.string.status_working)
        lifecycleScope.launch {
            try {
                // The frame captured at press-down, or a fresh one if that is
                // still in flight - a tap can outrun the capture.
                binding.status.text = heldFrame
                    ?.let { orchestrator.answerAbout(it) }
                    ?: orchestrator.onPress()
                hasAnswered = true
            } catch (e: Exception) {
                Log.e(TAG, "describe failed", e)
                binding.status.setText(R.string.status_speech_failed)
            }
            heldFrame = null
        }
    }

    private fun startAsking() {
        // Capture first: the mic can wait a few milliseconds, the scene cannot.
        heldFrame = null
        lifecycleScope.launch { heldFrame = orchestrator.captureNow() }

        if (!hasMicPermission()) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_MIC)
            return
        }
        if (recorder.start()) {
            binding.status.setText(R.string.status_listening)
        } else {
            binding.status.setText(R.string.status_no_mic)
        }
    }

    private fun finishAsking() {
        if (!recorder.isRecording) {
            // No mic, but the press still has to answer: fall back to a
            // description rather than a dead hold (hard rule #4).
            tapToDescribe()
            return
        }
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
                hasAnswered = true
            } catch (e: Exception) {
                Log.e(TAG, "ask failed", e)
                binding.status.setText(R.string.status_speech_failed)
            }
            heldFrame = null
        }
    }

    /**
     * Map the model at startup and report what actually happened.
     *
     * A press during the load is not blocked: the engines load once under their
     * own lock, so a press simply waits for the same load this started rather
     * than kicking off a second one.
     */
    private fun warmUpModel() {
        val app = application as TactileSightApp
        binding.status.setText(R.string.model_loading)
        lifecycleScope.launch {
            val state = app.prepareBrain()
            // Never overwrite an answer. A press can complete while the model
            // is still warming - the brain loads under its own lock, so the
            // press simply waits for this same load - and replacing what the
            // user just heard with "Ready" would erase the thing they pressed
            // the button for.
            if (!hasAnswered) showBrain()
            if (state == TactileSightApp.ModelState.FAILED) {
                Log.e(TAG, "model failed to load — presses will speak the fallback")
            }
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
     * Every language Sarvam speaks, behind the smallest control on the screen.
     *
     * A two-letter button rather than a labelled spinner because language is
     * chosen once — by voice, at setup — and after that it is a correction, not
     * a setting anyone visits. It sits in the header rather than in the dev
     * panel for the same reason: user mode hides that panel, and the user's own
     * language must not become unreachable when it does.
     *
     * Switching takes effect on the next press with no model reload — the VLM
     * always answers in English and the language is applied at speech time
     * (ADR-0012), which is what makes 23 languages cost the same as one.
     */
    private fun setUpLanguagePicker() {
        showLanguage((application as TactileSightApp).settings.language)
        binding.languageButton.setOnClickListener { runSpokenSetup() }
    }

    /**
     * The globe never changes; only what it announces does.
     *
     * A symbol rather than the language's name because the name would have to
     * be written in *some* language, and this control exists precisely for
     * someone who cannot read the one currently set. The current language lives
     * on the content description, where a screen reader will say it aloud in
     * full — which is the only form of it that reaches the intended user.
     */
    private fun showLanguage(language: Language) {
        binding.languageButton.contentDescription =
            getString(R.string.language_content_description, language.displayName)
    }

    /**
     * Dev mode shows the machinery; user mode shows the app.
     *
     * What survives the switch is deliberate rather than minimal. Language and
     * the engine picker stay: the first is the user's own setting, and the
     * second decides whether a frame leaves the phone and is the one control
     * worth reaching for when the venue network dies mid-demo. Everything else
     * — frame source, server address, model name, prompt, capture picker — is
     * scaffolding for building this, and a blind user has no use for any of it.
     *
     * The switch itself is always visible. A mode you cannot leave without
     * clearing app data is a trap, not a mode.
     */
    private fun setUpModeSwitch() {
        val app = application as TactileSightApp
        binding.modeSwitch.isChecked = app.settings.devMode
        showMode()
        binding.modeSwitch.setOnCheckedChangeListener { _, checked ->
            app.settings.devMode = checked
            showMode()
        }
    }

    private fun showMode() {
        val dev = (application as TactileSightApp).settings.devMode
        binding.devPanel.visibility = if (dev) View.VISIBLE else View.GONE
        // The preview goes with it. It shows a bundled capture the user cannot
        // change once the picker is hidden, and it is there to check what the
        // model was given - which is a developer's question. When the band's
        // own stream lands it is still not a user's control: someone who cannot
        // see the screen is not served by a picture on it.
        binding.previewCard.visibility = if (dev) View.VISIBLE else View.GONE
        binding.modeSwitch.contentDescription = getString(
            if (dev) R.string.mode_on_content_description
            else R.string.mode_off_content_description,
        )
        binding.subtitle.setText(if (dev) R.string.subtitle else R.string.subtitle_user)
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

    /**
     * Name the resident brain **and** say whether its model is actually there.
     *
     * The old version printed `Ready · GenieX (qairt/npu)` from the picker
     * alone, which described a choice rather than a state — so a press during
     * the load came back "Sorry, I could not see that" under a screen that said
     * Ready. The engine name is the same either way; what changed is that the
     * word "Ready" now has to be earned.
     */
    private fun showBrain() {
        val app = application as TactileSightApp
        binding.status.text = when (app.modelState) {
            TactileSightApp.ModelState.LOADING -> getString(R.string.model_loading)
            TactileSightApp.ModelState.READY -> getString(R.string.status_brain, app.brain.name)
            TactileSightApp.ModelState.FAILED -> getString(R.string.model_failed)
        }
    }

    /**
     * Band or phone — the one control that changes what the app can tell you.
     *
     * Visible in user mode, unlike the rest of the plumbing, because the two
     * are not interchangeable: the band measures distance and the phone camera
     * cannot. Switching to the phone therefore says so out loud once. Silently
     * dropping every distance would read as the feature having broken.
     */
    private fun setUpCameraPicker() {
        val kinds = FrameSourceKind.entries
        binding.cameraSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            kinds.map { if (it.available) it.displayName else "${it.displayName} — not yet" },
        )

        binding.cameraSpinner.onSelect { position ->
            val kind = kinds[position]
            if (!kind.available) {
                toast(getString(R.string.source_unavailable, kind.displayName))
                binding.cameraSpinner.setSelection(kinds.indexOf(FrameSourceKind.BUNDLED))
                return@onSelect
            }
            selectSource(kind)
        }
    }

    private fun selectSource(kind: FrameSourceKind) {
        when (kind) {
            FrameSourceKind.PHONE_CAMERA -> {
                if (!hasCameraPermission()) {
                    requestPermissions(arrayOf(Manifest.permission.CAMERA), REQUEST_CAMERA)
                    binding.cameraSpinner.setSelection(FrameSourceKind.BUNDLED.ordinal)
                    binding.status.setText(R.string.camera_permission_needed)
                    return
                }
                frames = phoneCamera
                // Bind now rather than inside the first press: binding takes a
                // beat, and that beat would otherwise land between the button
                // and the answer.
                lifecycleScope.launch {
                    try {
                        phoneCamera.start()
                    } catch (e: Exception) {
                        Log.e(TAG, "camera would not start", e)
                    }
                }
                // Said aloud, not just shown: the user this is for cannot read
                // the difference between the two modes off the screen.
                lifecycleScope.launch {
                    val notice = getString(R.string.camera_no_distance)
                    binding.status.text = notice
                    try {
                        speech.speak(notice, (application as TactileSightApp).settings.language)
                    } catch (e: Exception) {
                        Log.w(TAG, "could not speak the phone-camera notice", e)
                    }
                }
            }

            else -> {
                frames = bundled
                phoneCamera.stop()
                showBrain()
            }
        }
        setUpScenePicker()
        Log.i(TAG, "frame source = $kind")
    }

    private fun hasCameraPermission(): Boolean =
        checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

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

        // skipInitial = false: this callback is what draws the first preview.
        binding.sceneSpinner.onSelect(skipInitial = false) { position ->
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
        Log.i(TAG, "showFrame(${frame.sourceId})")
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

    /**
     * [skipInitial] governs whether the callback Android fires when the adapter
     * is first attached is treated as a user choice.
     *
     * It is not one, and for a spinner that *writes* something it is actively
     * harmful: the language picker overwrote the saved language with position 0
     * on every launch until this was skipped.
     *
     * But a spinner that only *reads* needs that first callback, and skipping
     * it everywhere caused the opposite bug — the scene picker never rendered
     * capture 1, so the app opened to a blank preview and only came alive when
     * you changed scene. Hence a parameter rather than a rule.
     */
    private fun Spinner.onSelect(skipInitial: Boolean = true, action: (position: Int) -> Unit) {
        var seenInitialCallback = false
        onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, position: Int, id: Long) {
                if (skipInitial && !seenInitialCallback) {
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
        const val EXTRA_WRITE_TAG = "writetag"
        const val EXTRA_FROM = "from"
        const val REQUEST_MIC = 1001
        const val REQUEST_CAMERA = 1002

        /**
         * Above this, a press is a question rather than a request to describe.
         *
         * 400 ms is comfortably longer than a deliberate tap and shorter than
         * anyone can begin a sentence in, so neither gesture can be mistaken
         * for the other by someone who is not watching the screen.
         */
        const val HOLD_THRESHOLD_MS = 400L

        /** Long enough to say a language, short enough not to feel stuck. */
        const val SETUP_LISTEN_MS = 4_000L

        /** Any of these means a test is driving; leave the microphone alone. */
        val HOOK_EXTRAS = setOf(EXTRA_SWEEP, EXTRA_HEXAGON, EXTRA_COMPARE, EXTRA_PRESS, EXTRA_WRITE_TAG)
        const val EXTRA_BUNDLES = "bundles"
        const val EXTRA_SCENES = "scenes"

        /** Both QAIRT bundles staged on the device: the 8B upgrade and the 4B incumbent. */
        const val DEFAULT_COMPARE_BUNDLES = "geniex,geniex-4b"
    }
}
