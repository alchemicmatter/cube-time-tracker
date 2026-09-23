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
import com.cubetimetracker.ui.*
import kotlinx.coroutines.launch

sealed class AppScreen {
    object Main : AppScreen()
    object ProjectList : AppScreen()
    data class TagSetup(val uid: String) : AppScreen()
}

class MainActivity : ComponentActivity() {

    private lateinit var nfcHelper: NfcHelper
    private lateinit var db: AppDatabase
    private lateinit var vibrator: Vibrator

    private val currentScreen = mutableStateOf<AppScreen>(AppScreen.Main)
    private val activeProjectName = mutableStateOf<String?>(null)
    private val activeSessionStart = mutableStateOf<Long?>(null)
    private val lastEventMessage = mutableStateOf("Waiting for a tag...")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        nfcHelper = NfcHelper(this)
        db = AppDatabase.getInstance(this)
        vibrator = getSystemService(VIBRATOR_SERVICE) as Vibrator

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    when (val screen = currentScreen.value) {
                        is AppScreen.Main -> MainScreen(
                            nfcAvailable = nfcHelper.isNfcAvailable(),
                            nfcEnabled = nfcHelper.isNfcEnabled(),
                            activeProjectName = activeProjectName.value,
                            statusMessage = lastEventMessage.value,
                            onOpenProjects = { currentScreen.value = AppScreen.ProjectList }
                        )
                        is AppScreen.ProjectList -> ProjectListScreen(
                            onNavigateBack = { currentScreen.value = AppScreen.Main },
                            onProjectSelected = { projectId ->
                                lifecycleScope.launch {
                                    val project = db.projectDao().getById(projectId)
                                    activeProjectName.value = project?.name
                                    lastEventMessage.value = "Selected: ${project?.name}"
                                }
                                currentScreen.value = AppScreen.Main
                            },
                            onCreateProject = { name ->
                                lifecycleScope.launch {
                                    db.projectDao().insert(Project(name = name))
                                }
                            }
                        )
                        is AppScreen.TagSetup -> TagSetupScreen(
                            tagUid = screen.uid,
                            projects = emptyList(), // TODO: load from DB
                            onProjectSelected = { projectId ->
                                lifecycleScope.launch {
                                    db.tagMappingDao().upsert(TagMapping(tagUid = screen.uid, projectId = projectId))
                                    val project = db.projectDao().getById(projectId)
                                    activeProjectName.value = project?.name
                                    lastEventMessage.value = "Tag assigned to: ${project?.name}"
                                    vibrateSuccess()
                                }
                                currentScreen.value = AppScreen.Main
                            },
                            onCreateProjectAndSelect = { name ->
                                lifecycleScope.launch {
                                    val newProject = Project(name = name)
                                    val newId = db.projectDao().insert(newProject)
                                    db.tagMappingDao().upsert(TagMapping(tagUid = screen.uid, projectId = newId))
                                    activeProjectName.value = name
                                    lastEventMessage.value = "Created project and assigned tag: $name"
                                    vibrateSuccess()
                                }
                                currentScreen.value = AppScreen.Main
                            },
                            onSkip = {
                                lastEventMessage.value = "Tag skipped (unassigned)"
                                currentScreen.value = AppScreen.Main
                            }
                        )
                    }
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
                // Unknown tag: show setup screen
                currentScreen.value = AppScreen.TagSetup(uid)
                vibrateWarning()
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
                    activeSessionStart.value = null
                    lastEventMessage.value = "Timer stopped: ${project?.name}"
                    vibrateSuccess()
                }
                // A different project was active -> close it and start the new one
                openSession != null -> {
                    db.timeSessionDao().closeSession(openSession.id, now)
                    val newSession = TimeSession(projectId = mapping.projectId, startEpochMillis = now)
                    val newId = db.timeSessionDao().insert(newSession)
                    activeProjectName.value = project?.name
                    activeSessionStart.value = now
                    lastEventMessage.value = "Switched to: ${project?.name}"
                    vibrateSuccess()
                }
                // No timer active -> start a new one
                else -> {
                    val newSession = TimeSession(projectId = mapping.projectId, startEpochMillis = now)
                    db.timeSessionDao().insert(newSession)
                    activeProjectName.value = project?.name
                    activeSessionStart.value = now
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
    statusMessage: String,
    onOpenProjects: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Text("Cube Time Tracker", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(24.dp))

        TimerDisplay(
            startTimeMillis = null, // TODO: pass actual start time
            modifier = Modifier.fillMaxWidth()
        )

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

        Spacer(Modifier.height(24.dp))

        Button(
            onClick = onOpenProjects,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Manage projects")
        }
    }
}
