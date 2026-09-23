package com.cubetimetracker

import android.content.Intent
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.Build
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        nfcHelper = NfcHelper(this)
        db = AppDatabase.getInstance(this)
        vibrator = getSystemService(VIBRATOR_SERVICE) as Vibrator

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

        handleIntentIfTag(intent)
    }

    override fun onResume() {
        super.onResume()
        nfcHelper.enableForegroundDispatch()
    }

    override fun onPause() {
        super.onPause()
        nfcHelper.disableForegroundDispatch()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntentIfTag(intent)
    }

    private fun handleIntentIfTag(intent: Intent) {
        val uid = nfcHelper.extractUid(intent) ?: return

        lifecycleScope.launch {
            val mapping = db.tagMappingDao().findByUid(uid)

            if (mapping == null) {
                lastEventMessage.value = "Unknown tag ($uid): assign via GitHub issue or PR"
                vibrateWarning()
                return@launch
            }

            val project = db.projectDao().getById(mapping.projectId)
            val openSession = db.timeSessionDao().getOpenSession()
            val now = System.currentTimeMillis()

            when {
                openSession != null && openSession.projectId == mapping.projectId -> {
                    db.timeSessionDao().closeSession(openSession.id, now)
                    activeProjectName.value = null
                    lastEventMessage.value = "Timer stopped: ${project?.name}"
                    vibrateSuccess()
                }
                openSession != null -> {
                    db.timeSessionDao().closeSession(openSession.id, now)
                    db.timeSessionDao().insert(TimeSession(projectId = mapping.projectId, startEpochMillis = now))
                    activeProjectName.value = project?.name
                    lastEventMessage.value = "Switched to: ${project?.name}"
                    vibrateSuccess()
                }
                else -> {
                    db.timeSessionDao().insert(TimeSession(projectId = mapping.projectId, startEpochMillis = now))
                    activeProjectName.value = project?.name
                    lastEventMessage.value = "Timer started: ${project?.name}"
                    vibrateSuccess()
                }
            }
        }
    }

    private fun vibrateSuccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(50)
        }
    }

    private fun vibrateWarning() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(150, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(150)
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
