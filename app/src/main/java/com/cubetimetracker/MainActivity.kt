package com.cubetimetracker

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.cubetimetracker.data.AppDatabase
import com.cubetimetracker.data.Project
import com.cubetimetracker.data.TagMapping
import com.cubetimetracker.data.TimeSession
import com.cubetimetracker.nfc.NfcHelper
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val RetroBackground = Color(0xFF080B0B)
private val RetroPanel = Color(0xFF102C2C)
private val RetroMint = Color(0xFF5ED1B2)
private val RetroText = Color(0xFFE8D7C4)
private val RetroOrange = Color(0xFFFF9D70)
private val RetroRed = Color(0xFFFF6B5E)
private val RetroMuted = Color(0xFF8AA5A0)
private val RetroFont = FontFamily.Monospace

class MainActivity : ComponentActivity() {
    private lateinit var nfcHelper: NfcHelper
    private lateinit var db: AppDatabase
    private lateinit var vibrator: Vibrator

    private val activeProjectName = mutableStateOf<String?>(null)
    private val activeSessionStart = mutableStateOf<Long?>(null)
    private val lastEventMessage = mutableStateOf("WAITING FOR TAG...")
    private val currentScreen = mutableStateOf("main")
    private val selectedProjectId = mutableStateOf<Long?>(null)
    private val projects = mutableStateOf<List<Project>>(emptyList())
    private val pendingTagUid = mutableStateOf<String?>(null)
    private val showNewProjectDialog = mutableStateOf(false)
    private val newProjectName = mutableStateOf("")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        nfcHelper = NfcHelper(this)
        db = AppDatabase.getInstance(this)
        vibrator = getSystemService(VIBRATOR_SERVICE) as Vibrator

        lifecycleScope.launch {
            db.projectDao().getActiveProjects().collect { projectList ->
                projects.value = projectList
            }
        }

        setContent {
            MaterialTheme(colorScheme = darkColorScheme(
                primary = RetroMint,
                secondary = RetroOrange,
                background = RetroBackground,
                surface = RetroPanel,
                onPrimary = RetroBackground,
                onSecondary = RetroBackground,
                onBackground = RetroText,
                onSurface = RetroText
            )) {
                Surface(Modifier.fillMaxSize(), color = RetroBackground) {
                    when (currentScreen.value) {
                        "main" -> MainScreen(
                            nfcAvailable = nfcHelper.isNfcAvailable(),
                            nfcEnabled = nfcHelper.isNfcEnabled(),
                            activeProjectName = activeProjectName.value,
                            activeSessionStart = activeSessionStart.value,
                            statusMessage = lastEventMessage.value,
                            onOpenProjects = { currentScreen.value = "projects" },
                            onViewReports = { currentScreen.value = "reports" }
                        )
                        "projects" -> ProjectListScreen(
                            projects = projects.value,
                            onNavigateBack = { currentScreen.value = "main" },
                            onCreateProject = { name ->
                                lifecycleScope.launch { db.projectDao().insert(Project(name = name)) }
                            }
                        )
                        "reports" -> ReportsScreen(
                            projects = projects.value,
                            onNavigateBack = { currentScreen.value = "main" },
                            onProjectSelected = { id ->
                                selectedProjectId.value = id
                                currentScreen.value = "sessions"
                            }
                        )
                        "sessions" -> selectedProjectId.value?.let { id ->
                            SessionsListScreen(
                                projectId = id,
                                onNavigateBack = { currentScreen.value = "reports" }
                            )
                        }
                    }

                    pendingTagUid.value?.let { uid ->
                        TagAssignmentDialog(
                            tagUid = uid,
                            projects = projects.value,
                            onDismiss = { pendingTagUid.value = null },
                            onProjectSelected = { projectId -> assignTagAndStart(uid, projectId) },
                            onCreateNewProject = { showNewProjectDialog.value = true }
                        )
                    }

                    if (showNewProjectDialog.value) {
                        AlertDialog(
                            containerColor = RetroPanel,
                            onDismissRequest = { showNewProjectDialog.value = false },
                            title = { Text("NEW PROJECT", fontFamily = RetroFont, color = RetroMint) },
                            text = {
                                OutlinedTextField(
                                    value = newProjectName.value,
                                    onValueChange = { newProjectName.value = it },
                                    label = { Text("NAME", fontFamily = RetroFont) },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            },
                            confirmButton = {
                                TextButton(
                                    enabled = newProjectName.value.isNotBlank(),
                                    onClick = {
                                        val uid = pendingTagUid.value ?: return@TextButton
                                        lifecycleScope.launch {
                                            val name = newProjectName.value.trim()
                                            val projectId = db.projectDao().insert(Project(name = name))
                                            db.tagMappingDao().upsert(TagMapping(tagUid = uid, projectId = projectId))
                                            startSession(projectId, name)
                                            pendingTagUid.value = null
                                            newProjectName.value = ""
                                            showNewProjectDialog.value = false
                                        }
                                    }
                                ) {
                                    Text("CREATE", fontFamily = RetroFont, color = RetroMint)
                                }
                            },
                            dismissButton = {
                                TextButton(onClick = { showNewProjectDialog.value = false }) {
                                    Text("CANCEL", fontFamily = RetroFont, color = RetroText)
                                }
                            }
                        )
                    }
                }
            }
        }
        handleIntentIfTag(intent)
    }

    private fun assignTagAndStart(uid: String, projectId: Long) {
        lifecycleScope.launch {
            db.tagMappingDao().upsert(TagMapping(tagUid = uid, projectId = projectId))
            startSession(projectId, db.projectDao().getById(projectId)?.name)
            pendingTagUid.value = null
        }
    }

    private suspend fun startSession(projectId: Long, name: String?) {
        val now = System.currentTimeMillis()
        db.timeSessionDao().getOpenSession()?.let { open ->
            db.timeSessionDao().closeSession(open.id, now)
        }
        db.timeSessionDao().insert(
            TimeSession(projectId = projectId, startEpochMillis = now)
        )
        activeProjectName.value = name
        activeSessionStart.value = now
        lastEventMessage.value = "STARTED: ${name ?: "PROJECT"}"
        vibrateSuccess()
    }

    private fun handleIntentIfTag(intent: Intent) {
        val uid = runCatching { nfcHelper.extractUid(intent) }.getOrNull() ?: return
        lifecycleScope.launch {
            try {
                val mapping = db.tagMappingDao().findByUid(uid)
                if (mapping == null) {
                    pendingTagUid.value = uid
                    vibrateWarning()
                    return@launch
                }

                val project = db.projectDao().getById(mapping.projectId)
                val open = db.timeSessionDao().getOpenSession()
                val now = System.currentTimeMillis()

                if (open != null && open.projectId == mapping.projectId) {
                    db.timeSessionDao().closeSession(open.id, now)
                    activeProjectName.value = null
                    activeSessionStart.value = null
                    lastEventMessage.value = "STOPPED: ${project?.name}"
                } else {
                    open?.let { db.timeSessionDao().closeSession(it.id, now) }
                    db.timeSessionDao().insert(
                        TimeSession(projectId = mapping.projectId, startEpochMillis = now)
                    )
                    activeProjectName.value = project?.name
                    activeSessionStart.value = now
                    lastEventMessage.value = "STARTED: ${project?.name}"
                }
                vibrateSuccess()
            } catch (e: Exception) {
                Log.e("CubeTimeTracker", "NFC handling error", e)
                lastEventMessage.value = "ERROR READING TAG"
            }
        }
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

    private fun vibrateSuccess() {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION") vibrator.vibrate(50)
            }
        }
    }

    private fun vibrateWarning() {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(150, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION") vibrator.vibrate(150)
            }
        }
    }
}

@Composable
private fun MainScreen(
    nfcAvailable: Boolean,
    nfcEnabled: Boolean,
    activeProjectName: String?,
    activeSessionStart: Long?,
    statusMessage: String,
    onOpenProjects: () -> Unit,
    onViewReports: () -> Unit
) {
    Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.SpaceBetween) {
        Column {
            Text("CUBE TIME", fontFamily = RetroFont, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = RetroMint)
            Text("LOCAL TIME TRACKER", fontFamily = RetroFont, fontSize = 11.sp, color = RetroMuted)
            Spacer(Modifier.height(24.dp))
            TimerDisplay(activeSessionStart)
            Spacer(Modifier.height(18.dp))
            Text(
                if (activeProjectName != null) "ACTIVE / ${activeProjectName.uppercase()}" else "NO ACTIVE PROJECT",
                fontFamily = RetroFont,
                fontSize = 13.sp,
                color = RetroText
            )
            Spacer(Modifier.height(6.dp))
            Text(statusMessage, fontFamily = RetroFont, fontSize = 11.sp, color = RetroOrange)
        }
        if (!nfcAvailable || !nfcEnabled) {
            Text(
                if (!nfcAvailable) "NFC NOT AVAILABLE" else "NFC DISABLED",
                fontFamily = RetroFont,
                color = RetroRed,
                fontSize = 11.sp
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            RetroButton("PROJECTS", onOpenProjects)
            RetroButton("REPORTS", onViewReports)
        }
    }
}

@Composable
private fun RetroButton(text: String, onClick: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().height(48.dp).border(1.dp, RetroMint).background(RetroPanel).clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(text, fontFamily = RetroFont, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = RetroMint)
    }
}

@Composable
private fun TimerDisplay(startTimeMillis: Long?) {
    var elapsed by remember { mutableStateOf(0L) }
    LaunchedEffect(startTimeMillis) {
        if (startTimeMillis == null) {
            elapsed = 0
        } else {
            while (true) {
                elapsed = (System.currentTimeMillis() - startTimeMillis) / 1000
                delay(1000)
            }
        }
    }
    val h = elapsed / 3600
    val m = (elapsed % 3600) / 60
    val s = elapsed % 60
    Column(
        Modifier.fillMaxWidth().border(2.dp, RetroMint).background(RetroPanel).padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(if (startTimeMillis == null) "STOPPED" else "RUNNING", fontFamily = RetroFont, fontSize = 11.sp, color = RetroOrange)
        Text(String.format("%02d:%02d:%02d", h, m, s), fontFamily = RetroFont, fontSize = 42.sp, fontWeight = FontWeight.Bold, color = RetroMint)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProjectListScreen(projects: List<Project>, onNavigateBack: () -> Unit, onCreateProject: (String) -> Unit) {
    var dialog by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    Scaffold(containerColor = RetroBackground, topBar = { RetroTopBar("PROJECTS", onNavigateBack) }) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(projects, key = { it.id }) { project -> RetroListItem(project.name) }
            item { RetroButton("+ NEW PROJECT") { dialog = true } }
        }
    }
    if (dialog) {
        RetroTextDialog("NEW PROJECT", "NAME", name, { name = it }, {
            if (name.isNotBlank()) {
                onCreateProject(name.trim())
                name = ""
                dialog = false
            }
        }, { dialog = false })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReportsScreen(projects: List<Project>, onNavigateBack: () -> Unit, onProjectSelected: (Long) -> Unit) {
    Scaffold(containerColor = RetroBackground, topBar = { RetroTopBar("REPORTS", onNavigateBack) }) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(projects, key = { it.id }) { project ->
                RetroListItem(project.name) { onProjectSelected(project.id) }
            }
        }
    }
}

@Composable
private fun RetroListItem(text: String, onClick: () -> Unit = {}) {
    Box(
        Modifier.fillMaxWidth().height(56.dp).border(1.dp, RetroMint).background(RetroPanel).clickable(onClick = onClick).padding(horizontal = 16.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        Text(text.uppercase(), fontFamily = RetroFont, fontSize = 13.sp, color = RetroText)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RetroTopBar(title: String, onBack: () -> Unit) {
    TopAppBar(
        title = { Text(title, fontFamily = RetroFont, fontWeight = FontWeight.Bold) },
        navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back") } },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = RetroBackground,
            titleContentColor = RetroMint,
            navigationIconContentColor = RetroMint
        )
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SessionsListScreen(projectId: Long, onNavigateBack: () -> Unit) {
    val viewModel: com.cubetimetracker.ui.SessionsViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
    val sessions by viewModel.sessionsForProject(projectId).collectAsState(initial = emptyList())
    val project by viewModel.project(projectId).collectAsState(initial = null)
    val scope = rememberCoroutineScope()
    val format = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US) }

    Scaffold(containerColor = RetroBackground, topBar = { RetroTopBar(project?.name?.uppercase() ?: "SESSIONS", onNavigateBack) }) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(sessions, key = { it.id }) { session ->
                val end = session.endEpochMillis
                val duration = ((end ?: System.currentTimeMillis()) - session.startEpochMillis) / 60000
                Box(Modifier.fillMaxWidth().border(1.dp, RetroMint).background(RetroPanel).padding(14.dp)) {
                    Column {
                        Text(format.format(Date(session.startEpochMillis)), fontFamily = RetroFont, fontSize = 11.sp, color = RetroMuted)
                        Text(if (end == null) "ONGOING" else "END ${format.format(Date(end))}", fontFamily = RetroFont, fontSize = 11.sp, color = RetroOrange)
                        Text("DURATION ${duration} MIN", fontFamily = RetroFont, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = RetroText)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            IconButton(onClick = { scope.launch { viewModel.deleteSession(session.id) } }) {
                                Icon(Icons.Default.Delete, "Delete", tint = RetroRed)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TagAssignmentDialog(
    tagUid: String,
    projects: List<Project>,
    onDismiss: () -> Unit,
    onProjectSelected: (Long) -> Unit,
    onCreateNewProject: () -> Unit
) {
    AlertDialog(
        containerColor = RetroPanel,
        onDismissRequest = onDismiss,
        title = { Text("TAG DETECTED", fontFamily = RetroFont, color = RetroMint) },
        text = {
            Column {
                Text("UID: $tagUid", fontFamily = RetroFont, fontSize = 10.sp, color = RetroMuted)
                Spacer(Modifier.height(12.dp))
                Text("ASSIGN TO:", fontFamily = RetroFont, color = RetroText)
            }
        },
        confirmButton = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                projects.forEach { project ->
                    TextButton(onClick = { onProjectSelected(project.id) }, modifier = Modifier.fillMaxWidth()) {
                        Text(project.name.uppercase(), fontFamily = RetroFont, color = RetroMint)
                    }
                }
                OutlinedButton(onClick = onCreateNewProject, modifier = Modifier.fillMaxWidth()) {
                    Text("+ NEW", fontFamily = RetroFont, color = RetroMint)
                }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("SKIP", fontFamily = RetroFont, color = RetroText) } }
    )
}

@Composable
private fun RetroTextDialog(
    title: String,
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        containerColor = RetroPanel,
        onDismissRequest = onDismiss,
        title = { Text(title, fontFamily = RetroFont, color = RetroMint) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                label = { Text(label, fontFamily = RetroFont) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = { TextButton(onClick = onConfirm, enabled = value.isNotBlank()) { Text("CREATE", fontFamily = RetroFont, color = RetroMint) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("CANCEL", fontFamily = RetroFont, color = RetroText) } }
    )
}
