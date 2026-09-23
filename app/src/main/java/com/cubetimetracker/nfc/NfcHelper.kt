package com.cubetimetracker.nfc

import android.app.Activity
import android.app.PendingIntent
import android.content.Intent
import android.content.IntentFilter
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Build

/**
 * Minimal wrapper around Android's NFC API.
 * Uses "foreground dispatch": while MainActivity is in the foreground,
 * any tag read is routed here instead of through the manifest's
 * declarative intent filters (more reliable).
 *
 * Reads ONLY the tag's hardware UID: no writing, no dependency on
 * the tag's content or format (this works with NDEF tags, MIFARE
 * Classic, recycled filament spool chips, etc.).
 */
class NfcHelper(private val activity: Activity) {

    private val nfcAdapter: NfcAdapter? = NfcAdapter.getDefaultAdapter(activity)

    fun isNfcAvailable(): Boolean = nfcAdapter != null
    fun isNfcEnabled(): Boolean = nfcAdapter?.isEnabled == true

    fun enableForegroundDispatch() {
        val intent = Intent(activity, activity.javaClass).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntentFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_MUTABLE
        } else 0

        val pendingIntent = PendingIntent.getActivity(activity, 0, intent, pendingIntentFlags)
        val filters = arrayOf(IntentFilter(NfcAdapter.ACTION_TAG_DISCOVERED))

        nfcAdapter?.enableForegroundDispatch(activity, pendingIntent, filters, null)
    }

    fun disableForegroundDispatch() {
        nfcAdapter?.disableForegroundDispatch(activity)
    }

    /** Extracts the tag UID as a hex string, e.g. "04A23F91B280" */
    fun extractUid(intent: Intent): String? {
        val tag: Tag? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(NfcAdapter.EXTRA_TAG, Tag::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(NfcAdapter.EXTRA_TAG)
        }
        return tag?.id?.joinToString("") { "%02X".format(it) }
    }
}
