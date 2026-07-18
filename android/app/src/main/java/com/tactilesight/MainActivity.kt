package com.tactilesight

import android.graphics.BitmapFactory
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.tactilesight.core.Orchestrator
import com.tactilesight.databinding.ActivityMainBinding
import com.tactilesight.frame.BundledCaptureSource
import com.tactilesight.frame.FrameSourceKind
import com.tactilesight.speech.SarvamSpeechIO
import kotlinx.coroutines.launch

/**
 * The walking skeleton (#1): pick a bundled band capture, press, hear a
 * sentence.
 *
 * There is no phone camera anywhere in this app — frames are real Astra Pro
 * Plus captures. The band has three physical buttons (ADR-0011); until the band
 * is wired, this screen carries button 1 only. Buttons 2 and 3 arrive with #17,
 * the engine picker with #7.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var frames: BundledCaptureSource
    private lateinit var orchestrator: Orchestrator

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        frames = BundledCaptureSource(assets)
        orchestrator = Orchestrator(
            frames = frames,
            // Owned by the Application, so rotation never drops the model.
            brain = (application as TactileSightApp).brain,
            speech = SarvamSpeechIO(cacheDir),
        )

        setUpSourcePicker()
        setUpScenePicker()

        binding.describeButton.setOnClickListener { onPress() }

        if (BuildConfig.SARVAM_API_KEY.isBlank()) {
            binding.status.setText(R.string.status_no_key)
        }
    }

    private fun setUpSourcePicker() {
        val kinds = FrameSourceKind.entries
        binding.sourceSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            kinds.map { if (it.available) it.displayName else "${it.displayName} — not yet" },
        )

        binding.sourceSpinner.onItemSelectedListener =
            object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: View?, position: Int, id: Long) {
                    val kind = kinds[position]
                    if (!kind.available) {
                        // Visible but not selectable: honest about what exists.
                        Toast.makeText(
                            this@MainActivity,
                            getString(R.string.source_unavailable, kind.displayName),
                            Toast.LENGTH_SHORT,
                        ).show()
                        binding.sourceSpinner.setSelection(kinds.indexOf(FrameSourceKind.BUNDLED))
                    }
                }

                override fun onNothingSelected(p: AdapterView<*>?) = Unit
            }
    }

    private fun setUpScenePicker() {
        val labels = frames.sceneIds.indices.map {
            getString(R.string.capture_label, it + 1, frames.sceneIds.size)
        }
        binding.sceneSpinner.adapter =
            ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)

        binding.sceneSpinner.onItemSelectedListener =
            object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: View?, position: Int, id: Long) {
                    frames.selectedIndex = position
                    showPreview()
                }

                override fun onNothingSelected(p: AdapterView<*>?) = Unit
            }
    }

    /** Show the triplet, so it reads as band data rather than a stock photo. */
    private fun showPreview() {
        lifecycleScope.launch {
            try {
                val sceneId = frames.selectedSceneId
                val frame = frames.load(sceneId)
                binding.rgbPreview.setImageBitmap(frame.rgbJpeg.toBitmap())
                binding.irPreview.setImageBitmap(frame.irJpeg.toBitmap())
                binding.depthPreview.setImageBitmap(frames.depthPreview(sceneId).toBitmap())
            } catch (e: Exception) {
                Log.e(TAG, "preview failed", e)
            }
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

    private fun ByteArray.toBitmap() = BitmapFactory.decodeByteArray(this, 0, size)

    private companion object {
        const val TAG = "MainActivity"
    }
}
