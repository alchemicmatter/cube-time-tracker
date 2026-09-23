package com.cubetimetracker

import android.content.Intent
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.Build
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.cubetimetracker.data.*
import com.cubetimetracker.nfc.NfcHelper
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private lateinit var nfcHelper: NfcHelper
    private lateinit var db: AppDatabase
    private lateinit var vibrator: Vibrator

    private val activeProjectName = mutableStateOf<String?>(null)
    private val lastEventMessage = mutableStateOf("Waiting for a tag...")

    companion object {
        private const val TAG = "CubeTimeTracker"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "onCreate called")

        try {
            nfcHelper = NfcHelper(this)
            db = AppDatabase.getInstance(this)
            vibrator = getSystemService(VIBRATOR_SERVICE) as Vibrator
            Log.d(TAG, "NfcHelper, db, vibrator initialized")
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing components", e)
            lastEventMessage.value = "Init error: ${e.message}"
        }

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    MainScreen(
                        nfcAvailable = nfcHelper.isNfcAvailable(),
                        nfcEnabled = nfcHelper.isNfcEnabled(),
                        activeProjectName = activeProjectName.value,
                        statusMessage = lastEventMessage.value
                    )
                }
            }
        }

        Log.d(TAG, "setContent completed")
    }

    override fun onResume() {
        super.onResume()
        Log.d(TAG, "onResume: enabling foreground dispatch")
        try {
            nfcHelper.enableForegroundDispatch()
        } catch (e: Exception) {
            Log.e(TAG, "Error enabling NFC dispatch", e)
        }
    }

    override fun onPause() {
        super.onPause()
        Log.d(TAG, "onPause: disabling foreground dispatch")
        try {
            nfcHelper.disableForegroundDispatch()
        } catch (e: Exception) {
            Log.e(TAG, "Error disabling NFC dispatch", e)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        Log.d(TAG, "onNewIntent received")
        handleIntentIfTag(intent)
    }

    private fun handleIntentIfTag(intent: Intent) {
        Log.d(TAG, "handleIntentIfTag started")
        
        val uid: String?
        try {
            uid = nfcHelper.extractUid(intent)
            Log.d(TAG, "Extracted UID: $uid")
        } catch (e: Exception) {
            Log.e(TAG, "Error extracting UID", e)
            lastEventMessage.value = "NFC read error: ${e.message}"
            return
        }

        if (uid == null) {
            Log.w(TAG, "UID was null, aborting")
            return
        }

        lifecycleScope.launch {
            try {
                Log.d(TAG, "Looking up tag mapping for UID: $uid")
                val mapping = db.tagMappingDao().findByUid(uid)

                if (mapping == null) {
                    Log.w(TAG, "No mapping found for UID: $uid")
                    lastEventMessage.value = "Unknown tag ($uid)"
                    vibrateWarning()
                    return@launch
                }

                Log.d(TAG, "Found mapping: projectId=${mapping.projectId}")
                val project = db.projectDao().getById(mapping.projectId)
                val openSession = db.timeSessionDao().getOpenSession()
                val now = System.currentTimeMillis()

                when {
                    openSession != null && openSession.projectId == mapping.projectId -> {
                        db.timeSessionDao().closeSession(openSession.id, now)
                        activeProjectName.value = null
                        lastEventMessage.value = "Timer stopped: ${project?.name}"
                        Log.i(TAG, "Timer stopped for project: ${project?.name}")
                        vibrateSuccess()
                    }
                    openSession != null -> {
                        db.timeSessionDao().closeSession(openSession.id, now)
                        db.timeSessionDao().insert(TimeSession(projectId = mapping.projectId, startEpochMillis = now))
                        activeProjectName.value = project?.name
                        lastEventMessage.value = "Switched to: ${project?.name}"
                        Log.i(TAG, "Switched to project: ${project?.name}")
                        vibrateSuccess()
                    }
                    else -> {
                        db.timeSessionDao().insert(TimeSession(projectId = mapping.projectId, startEpochMillis = now))
                        activeProjectName.value = project?.name
                        lastEventMessage.value = "Timer started: ${project?.name}"
                        Log.i(TAG, "Timer started for project: ${project?.name}")
                        vibrateSuccess()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in handleIntentIfTag", e)
                lastEventMessage.value = "Error: ${e.message}"
            }
        }
    }

    private fun vibrateSuccess() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(50)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Vibration error", e)
        }
    }

    private fun vibrateWarning() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(150, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(150)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Vibration error", e)
        }
    }
}

@Composable
fun MainScreen(
    nfcAvailable: Boolean,
    nfcEnabled: Boolean,
    activeProjectName: String?,
    statusMessage: String
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Text("Cube Time Tracker", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(24.dp))

        if (!nfcAvailable) {
            Text("This device has no NFC.")
        } else if (!nfcEnabled) {
            Text("NFC is disabled: enable it in Settings.")
        } else {
            Text(
                if (activeProjectName != null) "Active: $activeProjectName" else "No timer running",
                style = MaterialTheme.typography.bodyLarge
            )
            Spacer(Modifier.height(8.dp))
            Text(statusMessage, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
