package com.cubetimetracker

import android.content.Intent
import android.os.Bundle
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

    // Observable UI state: name of the currently active project, if any
    private val activeProjectName = mutableStateOf<String?>(null)
    private val lastEventMessage = mutableStateOf("Waiting for a tag...")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        nfcHelper = NfcHelper(this)
        db = AppDatabase.getInstance(this)

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

    /**
     * Core logic: reads the tag's UID, looks it up in the local mapping,
     * and toggles start/stop on the matching project. If the tag has
     * never been seen before, it prompts the user to assign it to a
     * project (setup step).
     */
    private fun handleIntentIfTag(intent: Intent) {
        val uid = nfcHelper.extractUid(intent) ?: return

        lifecycleScope.launch {
            val mapping = db.tagMappingDao().findByUid(uid)

            if (mapping == null) {
                lastEventMessage.value = "Unknown tag ($uid): assign it to a project"
                // TODO: open TagSetupScreen pre-filled with this UID
                return@launch
            }

            val project = db.projectDao().getById(mapping.projectId)
            val openSession = db.timeSessionDao().getOpenSession()
            val now = System.currentTimeMillis()

            when {
                // Same tag tapped again while active -> stop the timer
                openSession != null && openSession.projectId == mapping.projectId -> {
                    db.timeSessionDao().closeSession(openSession.id, now)
                    activeProjectName.value = null
                    lastEventMessage.value = "Timer stopped: ${project?.name}"
                }
                // A different project was active -> close it and start the new one
                openSession != null -> {
                    db.timeSessionDao().closeSession(openSession.id, now)
                    db.timeSessionDao().insert(TimeSession(projectId = mapping.projectId, startEpochMillis = now))
                    activeProjectName.value = project?.name
                    lastEventMessage.value = "Switched to: ${project?.name}"
                }
                // No timer active -> start a new one
                else -> {
                    db.timeSessionDao().insert(TimeSession(projectId = mapping.projectId, startEpochMillis = now))
                    activeProjectName.value = project?.name
                    lastEventMessage.value = "Timer started: ${project?.name}"
                }
            }
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
        Spacer(Modifier.height(16.dp))

        if (!nfcAvailable) {
            Text("This device has no NFC.")
        } else if (!nfcEnabled) {
            Text("NFC is disabled: enable it in Settings.")
        } else {
            Text(
                if (activeProjectName != null) "Active: $activeProjectName" else "No timer running"
            )
            Spacer(Modifier.height(8.dp))
            Text(statusMessage, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
