package com.cubetimetracker

import android.content.Intent
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.Build
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.cubetimetracker.data.*
import com.cubetimetracker.nfc.NfcHelper
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private lateinit var nfcHelper: NfcHelper
    private lateinit var db: AppDatabase
    private lateinit var vibrator: Vibrator

    private val activeProjectName = mutableStateOf<String?>(null)
    private val activeSessionStart = mutableStateOf<Long?>(null)
    private val lastEventMessage = mutableStateOf("Waiting for a tag...")
    private val showProjectList = mutableStateOf(false)
    private val projects = mutableStateOf<List<Project>>(emptyList())
    private val pendingTagUid = mutableStateOf<String?>(null)
    private val showNewProjectDialog = mutableStateOf(false)
    private val newProjectName = mutableStateOf("")

    companion object {
        private const val TAG = "CubeTimeTracker"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "onCreate called")

        nfcHelper = NfcHelper(this)
        db = AppDatabase.getInstance(this)
        vibrator = getSystemService(VIBRATOR_SERVICE) as Vibrator

        lifecycleScope.launch {
            db.projectDao().getActiveProjects().collect { projectList ->
                projects.value = projectList
                Log.d(TAG, "Loaded ${projectList.size} projects")
            }
        }

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    if (showProjectList.value) {
                        ProjectListScreen(
                            projects = projects.value,
                            onNavigateBack = { showProjectList.value = false },
                            onCreateProject = { name ->
                                lifecycleScope.launch {
                                    db.projectDao().insert(Project(name = name))
                                    Log.i(TAG, "Created project: $name")
                                }
                            }
                        )
                    } else {
                        MainScreen(
                            nfcAvailable = nfcHelper.isNfcAvailable(),
                            nfcEnabled = nfcHelper.isNfcEnabled(),
                            activeProjectName = activeProjectName.value,
                            activeSessionStart = activeSessionStart.value,
                            statusMessage = lastEventMessage.value,
                            onOpenProjects = { showProjectList.value = true }
                        )
                    }

                    if (pendingTagUid.value != null) {
                        TagAssignmentDialog(
                            tagUid = pendingTagUid.value!!,
                            projects = projects.value,
                            onDismiss = { pendingTagUid.value = null },
                            onProjectSelected = { projectId ->
                                lifecycleScope.launch {
                                    db.tagMappingDao().upsert(
                                        TagMapping(tagUid = pendingTagUid.value!!, projectId = projectId)
                                    )
                                    val project = db.projectDao().getById(projectId)
                                    activeProjectName.value = project?.name
                                    activeSessionStart.value = System.currentTimeMillis()
                                    db.timeSessionDao().insert(
                                        TimeSession(projectId = projectId, startEpochMillis = activeSessionStart.value!!)
                                    )
                                    lastEventMessage.value = "Tag assigned and timer started: ${project?.name}"
                                    Log.i(TAG, "Tag ${pendingTagUid.value} assigned to project $projectId")
                                    pendingTagUid.value = null
                                    vibrateSuccess()
                                }
                            },
                            onCreateNewProject = { showNewProjectDialog.value = true }
                        )
                    }

                    if (showNewProjectDialog.value) {
                        AlertDialog(
                            onDismissRequest = { showNewProjectDialog.value = false },
                            title = { Text("New project") },
                            text = {
                                Column {
                                    Text("Create a new project and assign the detected tag:")
                                    Spacer(Modifier.height(16.dp))
                                    OutlinedTextField(
                                        value = newProjectName.value,
                                        onValueChange = { newProjectName.value = it },
                                        label = { Text("Project name") },
                                        singleLine = true
                                    )
                                }
                            },
                            confirmButton = {
                                TextButton(
                                    onClick = {
                                        if (newProjectName.value.isNotBlank() && pendingTagUid.value != null) {
                                            lifecycleScope.launch {
                                                val newProject = Project(name = newProjectName.value)
                                                val newId = db.projectDao().insert(newProject)
                                                db.tagMappingDao().upsert(
                                                    TagMapping(tagUid = pendingTagUid.value!!, projectId = newId)
                                                )
                                                activeProjectName.value = newProject.name
                                                activeSessionStart.value = System.currentTimeMillis()
                                                db.timeSessionDao().insert(
                                                    TimeSession(projectId = newId, startEpochMillis = activeSessionStart.value!!)
                                                )
                                                lastEventMessage.value = "Created project and started timer: ${newProject.name}"
                                                Log.i(TAG, "Created project and assigned tag: ${newProject.name}")
                                                newProjectName.value = ""
                                                pendingTagUid.value = null
                                                showNewProjectDialog.value = false
                                                vibrateSuccess()
                                            }
                                        }
                                    },
                                    enabled = newProjectName.value.isNotBlank()
                                ) {
                                    Text("Create & start")
                                }
                            },
                            dismissButton = {
                                TextButton(onClick = { showNewProjectDialog.value = false }) {
                                    Text("Cancel")
                                }
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
        Log.d(TAG, "handleIntentIfTag started")
        
        val uid: String? = try {
            nfcHelper.extractUid(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Error extracting UID", e)
            lastEventMessage.value = "NFC read error: ${e.message}"
            return
        }

        if (uid == null) {
            Log.w(TAG, "UID was null")
            return
        }

        Log.d(TAG, "Extracted UID: $uid")

        lifecycleScope.launch {
            try {
                val mapping = db.tagMappingDao().findByUid(uid)

                if (mapping == null) {
                    Log.w(TAG, "Unknown tag detected: $uid")
                    pendingTagUid.value = uid
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
                        activeSessionStart.value = null
                        lastEventMessage.value = "Timer stopped: ${project?.name}"
                        Log.i(TAG, "Timer stopped: ${project?.name}")
                        vibrateSuccess()
                    }
                    openSession != null -> {
                        db.timeSessionDao().closeSession(openSession.id, now)
                        db.timeSessionDao().insert(TimeSession(projectId = mapping.projectId, startEpochMillis = now))
                        activeProjectName.value = project?.name
                        activeSessionStart.value = now
                        lastEventMessage.value = "Switched to: ${project?.name}"
                        Log.i(TAG, "Switched to: ${project?.name}")
                        vibrateSuccess()
                    }
                    else -> {
                        db.timeSessionDao().insert(TimeSession(projectId = mapping.projectId, startEpochMillis = now))
                        activeProjectName.value = project?.name
                        activeSessionStart.value = now
                        lastEventMessage.value = "Timer started: ${project?.name}"
                        Log.i(TAG, "Timer started: ${project?.name}")
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectListScreen(
    projects: List<Project>,
    onNavigateBack: () -> Unit,
    onCreateProject: (String) -> Unit
) {
    var showCreateDialog by remember { mutableStateOf(false) }
    var newProjectName by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Projects") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Text("←")
                    }
                }
            )
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(projects, key = { it.id }) { project ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = project.name,
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }
                }
            }

            item {
                Button(
                    onClick = { showCreateDialog = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Add, contentDescription = "New project")
                    Spacer(Modifier.width(8.dp))
                    Text("New project")
                }
            }
        }
    }

    if (showCreateDialog) {
        AlertDialog(
            onDismissRequest = { showCreateDialog = false },
            title = { Text("New project") },
            text = {
                OutlinedTextField(
                    value = newProjectName,
                    onValueChange = { newProjectName = it },
                    label = { Text("Project name") },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (newProjectName.isNotBlank()) {
                            onCreateProject(newProjectName)
                            newProjectName = ""
                            showCreateDialog = false
                        }
                    },
                    enabled = newProjectName.isNotBlank()
                ) {
                    Text("Create")
                }
            },
            dismissButton = {
                TextButton(onClick = { showCreateDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
fun MainScreen(
    nfcAvailable: Boolean,
    nfcEnabled: Boolean,
    activeProjectName: String?,
    activeSessionStart: Long?,
    statusMessage: String,
    onOpenProjects: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Text("Cube Time Tracker", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(24.dp))

        TimerDisplay(startTimeMillis = activeSessionStart)

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

@Composable
fun TimerDisplay(startTimeMillis: Long?, modifier: Modifier = Modifier) {
    var elapsedSeconds by remember { mutableStateOf(0L) }
    val isRunning = startTimeMillis != null

    LaunchedEffect(startTimeMillis) {
        if (isRunning) {
            while (true) {
                elapsedSeconds = (System.currentTimeMillis() - startTimeMillis!!) / 1000
                delay(1000)
            }
        } else {
            elapsedSeconds = 0
        }
    }

    val hours = elapsedSeconds / 3600
    val minutes = (elapsedSeconds % 3600) / 60
    val seconds = elapsedSeconds % 60

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = if (isRunning) "Timer running" else "No timer active",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = String.format("%02d:%02d:%02d", hours, minutes, seconds),
            style = MaterialTheme.typography.displayLarge,
            color = if (isRunning) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
fun TagAssignmentDialog(
    tagUid: String,
    projects: List<Project>,
    onDismiss: () -> Unit,
    onProjectSelected: (Long) -> Unit,
    onCreateNewProject: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Unknown tag detected") },
        text = {
            Column {
                Text("UID: $tagUid", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(16.dp))
                Text("Assign to existing project:")
            }
        },
        confirmButton = {
            Column {
                projects.forEach { project ->
                    TextButton(
                        onClick = { onProjectSelected(project.id) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(project.name)
                    }
                }
                OutlinedButton(
                    onClick = onCreateNewProject,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Create new project")
                }
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Skip")
            }
        }
    )
}
