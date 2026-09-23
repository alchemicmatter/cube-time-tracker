package com.cubetimetracker.nfc

import android.app.Activity
import android.app.PendingIntent
import android.content.Intent
import android.content.IntentFilter
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Build

/**
 * Wrapper minimale sull'API NFC di Android.
 * Usa il "foreground dispatch": mentre MainActivity è in primo piano,
 * qualsiasi tag letto viene indirizzato qui, invece che al sistema
 * di intent-filter dichiarativi del manifest (più affidabile).
 *
 * Legge SOLO l'UID hardware del tag: nessuna scrittura, nessuna
 * dipendenza dal contenuto/formato del tag (funziona quindi anche
 * con tag NDEF, MIFARE Classic, bobine filamento riciclate, ecc.).
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

    /** Estrae l'UID del tag come stringa esadecimale, es. "04A23F91B280" */
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
