package com.tactilesight

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.util.Log
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
import com.tactilesight.core.BrainMode
import com.tactilesight.core.BrowsableFrameSource
import com.tactilesight.core.Frame
import com.tactilesight.core.FrameSource
import com.tactilesight.core.Orchestrator
import com.tactilesight.databinding.ActivityMainBinding
import com.tactilesight.frame.BundledCaptureSource
import com.tactilesight.frame.DepthCoverage
import com.tactilesight.frame.DepthRenderer
import com.tactilesight.frame.FramePage
import com.tactilesight.frame.FramePagerAdapter
import com.tactilesight.frame.FrameSourceKind
import com.tactilesight.speech.SarvamSpeechIO
import kotlinx.coroutines.launch

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
        )

        setUpCarousel()
        setUpSourcePicker()
        setUpBrainPicker()
        setUpScenePicker()

        if (BuildConfig.SARVAM_API_KEY.isBlank()) {
            binding.status.setText(R.string.status_no_key)
        }

        binding.describeButton.setOnClickListener { onPress() }
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
     * Where the frame gets described: on the phone, on our server, or in the
     * cloud. The endpoint field appears only for the two that need an address.
     *
     * The privacy rule is *not* enforced here. This screen only reflects it —
     * [TactileSightApp.applyMode] resolves the mode that actually runs, so a
     * blocked destination cannot be reached by any path through the UI.
     */
    private fun setUpBrainPicker() {
        val app = application as TactileSightApp
        val modes = BrainMode.entries

        binding.brainSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            modes.map { it.displayName },
        )
        binding.brainSpinner.setSelection(modes.indexOf(app.settings.effectiveMode))
        binding.privacySwitch.isChecked = app.settings.privacyMode

        binding.brainSpinner.onSelect { position ->
            val requested = modes[position]
            app.applyMode(requested)
            showEndpointFor(app.settings.effectiveMode)

            if (app.settings.effectiveMode != requested) {
                toast(getString(R.string.privacy_blocked, requested.displayName))
                binding.brainSpinner.setSelection(modes.indexOf(app.settings.effectiveMode))
            } else if (requested.sendsImageryOffDevice && app.settings.urlFor(requested).isBlank()) {
                toast(getString(R.string.endpoint_missing))
            }
            showBrain()
        }

        binding.privacySwitch.setOnCheckedChangeListener { _, isChecked ->
            app.settings.privacyMode = isChecked
            // Re-resolve immediately: switching privacy on while a cloud brain
            // is resident must drop it now, not at the next press.
            app.applyMode(app.settings.mode)
            binding.brainSpinner.setSelection(modes.indexOf(app.settings.effectiveMode))
            showEndpointFor(app.settings.effectiveMode)
            showBrain()
        }

        // The address is saved as it is typed, so a tunnel URL survives the
        // app being killed — which ColorOS does aggressively.
        binding.endpointField.doAfterTextChanged { text ->
            val mode = app.settings.effectiveMode
            if (mode.sendsImageryOffDevice) {
                app.settings.setUrlFor(mode, text?.toString().orEmpty())
            }
        }

        showEndpointFor(app.settings.effectiveMode)
        showBrain()
    }

    private fun showEndpointFor(mode: BrainMode) {
        val app = application as TactileSightApp
        binding.endpointField.visibility =
            if (mode.sendsImageryOffDevice) View.VISIBLE else View.GONE
        val saved = app.settings.urlFor(mode)
        if (binding.endpointField.text.toString() != saved) {
            binding.endpointField.setText(saved)
        }
    }

    /** Name the resident brain, so which engine answered is never a guess. */
    private fun showBrain() {
        val app = application as TactileSightApp
        binding.status.text = getString(R.string.status_brain, app.brain.name)
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
        // Trimmed to what depth can measure — the same crop the VLM is given,
        // so the preview shows exactly what the brain sees.
        val colour = DepthCoverage.cropToMeasurableRegion(frame.rgbJpeg).toBitmap()
        val infrared = frame.irJpeg.toBitmap()
        val depth = DepthRenderer.render(frame.depthMillimetres)

        pages.submit(
            listOf(
                FramePage(label(R.string.stream_rgb, colour), getString(R.string.preview_rgb), colour),
                FramePage(label(R.string.stream_ir, infrared), getString(R.string.preview_ir), infrared),
                FramePage(label(R.string.stream_depth, depth), getString(R.string.preview_depth), depth),
            ),
        )
        buildDots(count = 3, selected = binding.framePager.currentItem)
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

    /** Spinner selection without the two-method anonymous-listener boilerplate. */
    private fun Spinner.onSelect(action: (position: Int) -> Unit) {
        onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, position: Int, id: Long) =
                action(position)

            override fun onNothingSelected(p: AdapterView<*>?) = Unit
        }
    }

    private fun ByteArray.toBitmap() = BitmapFactory.decodeByteArray(this, 0, size)

    private val dotSize by lazy { (8 * resources.displayMetrics.density).toInt() }

    private companion object {
        const val TAG = "MainActivity"
    }
}
