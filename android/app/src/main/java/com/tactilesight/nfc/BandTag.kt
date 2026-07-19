package com.tactilesight.nfc

import android.app.Activity
import android.app.PendingIntent
import android.content.Intent
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.Ndef
import android.nfc.tech.NdefFormatable
import android.util.Log

/**
 * Tap the phone to the band and the app opens (#10).
 *
 * ### Why this is worth having rather than a shortcut
 *
 * Everything else in this app is reachable without sight — one big button,
 * spoken answers, a physical band. *Launching* it is the exception: finding an
 * icon on a home screen is the one step a screen reader makes tedious and a
 * blind user cannot shortcut. A tag on the band removes it: touch the phone to
 * the thing you are already wearing.
 *
 * ### Why a custom MIME type, not an Android Application Record
 *
 * An AAR launches the app when installed and otherwise sends the user to the
 * Play Store — useless for a sideloaded build, and worse than useless for a
 * blind user, who ends up in a store page they did not ask for. A MIME type we
 * own is matched by us alone and does nothing when we are absent, which is the
 * honest behaviour for a tag glued to hardware.
 *
 * ### Writing
 *
 * The app writes its own tags. That is deliberate: provisioning a band should
 * not require a second app and a laptop, and "hold a blank tag to the phone"
 * is something a sighted teammate can do in ten seconds at a desk.
 */
object BandTag {

    const val MIME_TYPE = "application/vnd.tactilesight.band"

    /** Payload is the band id, so one day a tag can say *which* band it is. */
    private const val DEFAULT_BAND_ID = "band-1"

    fun adapterFor(activity: Activity): NfcAdapter? =
        NfcAdapter.getDefaultAdapter(activity)

    /** True when this launch came from a tap rather than the launcher icon. */
    fun launchedByTap(intent: Intent?): Boolean =
        intent?.action == NfcAdapter.ACTION_NDEF_DISCOVERED

    /**
     * Route tag discoveries to [activity] while it is in front, so writing does
     * not depend on the tag already carrying our record — a blank tag never
     * matches an intent filter, and a blank tag is exactly what you write.
     */
    fun startWriting(activity: Activity) {
        val adapter = adapterFor(activity) ?: return
        val intent = Intent(activity, activity.javaClass)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pending = PendingIntent.getActivity(
            activity,
            0,
            intent,
            PendingIntent.FLAG_MUTABLE,
        )
        adapter.enableForegroundDispatch(activity, pending, null, null)
    }

    fun stopWriting(activity: Activity) {
        adapterFor(activity)?.disableForegroundDispatch(activity)
    }

    /**
     * Write our record to [tag]. Returns a sentence to speak — success or the
     * reason, because whoever is provisioning a band may not be looking at the
     * screen either.
     */
    fun write(tag: Tag?, bandId: String = DEFAULT_BAND_ID): String {
        if (tag == null) return "No tag found. Hold the tag against the back of the phone."

        val message = NdefMessage(
            arrayOf(
                NdefRecord.createMime(MIME_TYPE, bandId.toByteArray()),
            ),
        )

        return try {
            val ndef = Ndef.get(tag)
            if (ndef != null) {
                ndef.use {
                    it.connect()
                    when {
                        !it.isWritable -> "This tag is write protected."
                        it.maxSize < message.byteArrayLength ->
                            "This tag is too small — it holds ${it.maxSize} bytes."
                        else -> {
                            it.writeNdefMessage(message)
                            "Tag written. Tap it to open TactileSight."
                        }
                    }
                }
            } else {
                // A tag straight out of the packet has no NDEF structure yet.
                NdefFormatable.get(tag)?.use {
                    it.connect()
                    it.format(message)
                    "Tag formatted and written. Tap it to open TactileSight."
                } ?: "This tag cannot store the record."
            }
        } catch (e: Exception) {
            Log.w(TAG, "tag write failed", e)
            "Could not write the tag. Hold it still against the phone and try again."
        }
    }

    private const val TAG = "BandTag"
}
