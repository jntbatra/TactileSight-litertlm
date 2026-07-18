package com.tactilesight.brain

import android.content.Context
import java.io.File

/**
 * What is sideloaded on this device.
 *
 * Models live in the app's **external** files dir and are scanned at startup —
 * adding a model is pushing a folder, no rebuild (ADR-0010). External matters:
 * an uninstall/reinstall wipes app data but leaves multi-gigabyte models alone,
 * and reinstalling is something we do constantly.
 *
 * ```
 * adb push <bundle>/. /sdcard/Android/data/com.tactilesight/files/models/geniex/
 * ```
 *
 * Note the trailing `/.` — push the bundle's *contents*, not the folder. See
 * [available] for why the difference decides whether the app can read them.
 *
 * Models are never bundled in the APK and never downloaded at the venue — the
 * hall network measured 0.3–15 MB/s with repeated drops, and a multi-GB pull
 * was killed repeatedly (TEAM.md).
 */
class ModelStore(private val context: Context) {

    /** `…/files/models/<engine>/`, created if absent so adb push has a target. */
    fun directoryFor(engine: Engine): File =
        File(context.getExternalFilesDir(null), "models/${engine.dirName}")
            .apply { mkdirs() }

    /**
     * Model bundles present for [engine], newest first. A bundle is a directory
     * of weights plus a config, not a single file, so entries are directories
     * that actually [looksLikeABundle] — `sample_inputs/` sits inside a QAIRT
     * bundle and is not one itself.
     *
     * The engine directory *itself* counts as a bundle when the weights were
     * pushed into it directly. That is the supported layout for GenieX, and the
     * reason is ownership, not taste: `adb push <dir> <target>` creates the new
     * subdirectory as `shell:ext_data_rw` mode 770, which the app's uid cannot
     * traverse — so the bundle becomes invisible to the very app that needs it,
     * and the QAIRT plugin falls back to scanning the parent and reports
     * "No .bin LLM shards found". Pushing the *contents* into the directory the
     * app created keeps the directory app-owned and sidesteps it entirely:
     *
     * ```
     * adb push <bundle>/. /sdcard/Android/data/com.tactilesight/files/models/geniex/
     * ```
     */
    fun available(engine: Engine): List<File> {
        val root = directoryFor(engine)
        val nested = root.listFiles { f -> f.isDirectory && looksLikeABundle(f) }
            ?.sortedByDescending { it.lastModified() }
            .orEmpty()
        return if (looksLikeABundle(root)) listOf(root) + nested else nested
    }

    /**
     * A bundle carries its pipeline config beside its weight shards — or, for
     * the llama_cpp backend, is simply a directory with GGUF weights in it.
     */
    private fun looksLikeABundle(dir: File): Boolean =
        File(dir, BUNDLE_MARKER).exists() ||
            dir.listFiles { f -> f.extension in WEIGHT_EXTENSIONS }?.isNotEmpty() == true

    enum class Engine(val dirName: String, val displayName: String) {
        GENIEX("geniex", "GenieX"),

        /**
         * GenieX's other backend. Same runtime, different weights: `llama_cpp`
         * eats community GGUF plus an `mmproj-*.gguf` projector for vision, and
         * reaches Adreno/Hexagon through ggml rather than QAIRT.
         *
         * It gets its own directory because the two backends cannot share one —
         * a bundle lives flat in its engine directory (see [available]), so one
         * directory holds one bundle.
         */
        GENIEX_GGUF("geniex-gguf", "GenieX (GGUF)"),
        LITERT("litertlm", "LiteRT-LM"),
        EXECUTORCH("executorch", "ExecuTorch"),
    }

    companion object {
        /** The QAIRT pipeline config, and the anchor GenieX must be handed. */
        const val BUNDLE_MARKER = "genie_config.json"

        /** QAIRT ships sharded `.bin`; llama_cpp ships `.gguf`. */
        val WEIGHT_EXTENSIONS = setOf("bin", "gguf")
    }
}
