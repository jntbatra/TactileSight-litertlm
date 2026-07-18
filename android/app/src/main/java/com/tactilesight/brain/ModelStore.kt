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
 * adb push <dir> /sdcard/Android/data/com.tactilesight/files/models/geniex/
 * ```
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
     * Model folders present for [engine], newest first. Each entry is a
     * directory — a QAIRT bundle is a folder of weights plus a config, not a
     * single file.
     */
    fun available(engine: Engine): List<File> =
        directoryFor(engine)
            .listFiles { f -> f.isDirectory }
            ?.sortedByDescending { it.lastModified() }
            .orEmpty()

    enum class Engine(val dirName: String, val displayName: String) {
        GENIEX("geniex", "GenieX"),
        LITERT("litertlm", "LiteRT-LM"),
        EXECUTORCH("executorch", "ExecuTorch"),
    }
}
