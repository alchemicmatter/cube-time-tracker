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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val DeviceBackground = Color(0xFF090C0A)
private val DevicePanel = Color(0xFF183B32)
private val DevicePanelRaised = Color(0xFF204B3D)
private val DeviceBorder = Color(0xFF112E26)
private val DeviceMint = Color(0xFF71C596)
private val DeviceMintDark = Color(0xFF4D9B71)
private val DeviceText = Color(0xFFF0DEC7)
private val DeviceOrange = Color(0xFFFFA876)
private val DeviceRed = Color(0xFFFF705F)
private val DeviceMuted = Color(0xFFA5B7A5)
private val DeviceShape = RoundedCornerShape(3.dp)
private val DeviceFont = FontFamily.SansSerif

private data class ProjectSummary(
    val project: Project,
    val totalMillis: Long,
    val sessionCount: Int
)

class MainActivity : ComponentActivity() {
    private lateinit var nfcHelper: NfcHelper
    private lateinit var db: AppDatabase
    private lateinit var vibrator: Vibrator

    private val activeProjectName = mutableStateOf<String?>(null)
    private val activeSessionStart = mutableStateOf<Long?>(null)
    private val lastEventMessage = mutableStateOf("WAITING FOR TAG...")
    private val currentScreen = mutableStateOf("main")
    private val selectedProjectId = mutableStateOf<Long?>(null)
    private val pendingTagUid = mutableStateOf<String?>(null)
    private val showNewProjectDialog = mutableStateOf(false)
    private val newProjectName = mutableStateOf("")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        nfcHelper = NfcHelper(this)
        db = AppDatabase.getInstance(this)
        vibrator = getSystemService(VIBRATOR_SERVICE) as Vibrator

        setContent {
            MaterialTheme(colorScheme = darkColorScheme(
                primary = DeviceMint,
                secondary = DeviceOrange,
                background = DeviceBackground,
                surface = DevicePanel,
                onPrimary = DeviceBackground,
                onSecondary = DeviceBackground,
                onBackground = DeviceText,
                onSurface = DeviceText
            )) {
                Surface(Modifier.fillMaxSize(), color = DeviceBackground) {
                    when (currentScreen.value) {
                        "main" -> MainScreen(
                            nfcAvailable = nfcHelper.isNfcAvailable(),
                            nfcEnabled = nfcHelper.isNfcEnabled(),
                            activeProjectName = activeProjectName.value,
                            activeSessionStart = activeSessionStart.value,
                            statusMessage = lastEventMessage.value,
                            onOpenProjects = { currentScreen.value = "projects" }
                        )
                        "projects" -> ProjectsScreen(
                            onNavigateBack = { currentScreen.value = "main" },
                            onProjectSelected = { projectId ->
                                selectedProjectId.value = projectId
                                currentScreen.value = "project_detail"
                            },
                            onCreateProject = { name ->
                                lifecycleScope.launch { db.projectDao().insert(Project(name = name)) }
                            }
                        )
                        "project_detail" -> selectedProjectId.value?.let { projectId ->
                            ProjectDetailScreen(
                                projectId = projectId,
                                onNavigateBack = { currentScreen.value = "projects" }
                            )
                        }
                    }

                    pendingTagUid.value?.let { uid ->
                        TagAssignmentDialog(
                            tagUid = uid,
                            onDismiss = { pendingTagUid.value = null },
                            onProjectSelected = { projectId -> assignTagAndStart(uid, projectId) },
                            onCreateNewProject = { showNewProjectDialog.value = true }
                        )
                    }

                    if (showNewProjectDialog.value) {
                        DeviceTextDialog(
                            title = "NEW PROJECT",
                            label = "PROJECT NAME",
                            value = newProjectName.value,
                            onValueChange = { newProjectName.value = it },
                            onConfirm = {
                                val uid = pendingTagUid.value
                                val name = newProjectName.value.trim()
                                if (name.isBlank()) return@DeviceTextDialog
                                lifecycleScope.launch {
                                    val projectId = db.projectDao().insert(Project(name = name))
                                    if (uid != null) {
                                        db.tagMappingDao().upsert(TagMapping(tagUid = uid, projectId = projectId))
                                        startSession(projectId, name)
                                        pendingTagUid.value = null
                                    }
                                    newProjectName.value = ""
                                    showNewProjectDialog.value = false
                                }
                            },
                            onDismiss = { showNewProjectDialog.value = false }
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
        db.timeSessionDao().getOpenSession()?.let { open -> db.timeSessionDao().closeSession(open.id, now) }
        db.timeSessionDao().insert(TimeSession(projectId = projectId, startEpochMillis = now))
        activeProjectName.value = name
        activeSessionStart.value = now
        lastEventMessage.value = "STARTED / ${name ?: "PROJECT"}"
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
                    lastEventMessage.value = "STOPPED / ${project?.name}"
                } else {
                    open?.let { db.timeSessionDao().closeSession(it.id, now) }
                    db.timeSessionDao().insert(TimeSession(projectId = mapping.projectId, startEpochMillis = now))
                    activeProjectName.value = project?.name
                    activeSessionStart.value = now
                    lastEventMessage.value = "STARTED / ${project?.name}"
                }
                vibrateSuccess()
            } catch (error: Exception) {
                Log.e("CubeTimeTracker", "NFC handling error", error)
                lastEventMessage.value = "TAG READ ERROR"
            }
        }
    }

    override fun onResume() { super.onResume(); nfcHelper.enableForegroundDispatch() }
    override fun onPause() { super.onPause(); nfcHelper.disableForegroundDispatch() }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); handleIntentIfTag(intent) }

    private fun vibrateSuccess() = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) vibrator.vibrate(VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE))
        else @Suppress("DEPRECATION") vibrator.vibrate(50)
    }

    private fun vibrateWarning() = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) vibrator.vibrate(VibrationEffect.createOneShot(150, VibrationEffect.DEFAULT_AMPLITUDE))
        else @Suppress("DEPRECATION") vibrator.vibrate(150)
    }
}

@Composable
private fun MainScreen(
    nfcAvailable: Boolean,
    nfcEnabled: Boolean,
    activeProjectName: String?,
    activeSessionStart: Long?,
    statusMessage: String,
    onOpenProjects: () -> Unit
) {
    Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.SpaceBetween) {
        Column {
            Text("CUBE TIME", fontFamily = DeviceFont, fontSize = 23.sp, fontWeight = FontWeight.Black, color = DeviceMint)
            Text("LOCAL TIME TRACKER", fontFamily = DeviceFont, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = DeviceMuted)
            Spacer(Modifier.height(24.dp))
            TimerDisplay(activeSessionStart)
            Spacer(Modifier.height(18.dp))
            Text(
                if (activeProjectName == null) "NO ACTIVE PROJECT" else "ACTIVE / ${activeProjectName.uppercase()}",
                fontFamily = DeviceFont,
                fontSize = 13.sp,
                fontWeight = FontWeight.Black,
                color = DeviceText
            )
            Spacer(Modifier.height(6.dp))
            Text(statusMessage, fontFamily = DeviceFont, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = DeviceOrange)
        }
        if (!nfcAvailable || !nfcEnabled) {
            Text(if (!nfcAvailable) "NFC NOT AVAILABLE" else "NFC DISABLED", fontFamily = DeviceFont, fontWeight = FontWeight.Bold, color = DeviceRed, fontSize = 11.sp)
        }
        DeviceButton("PROJECTS", onOpenProjects)
    }
}

@Composable
private fun DeviceButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .height(48.dp)
            .clip(DeviceShape)
            .border(1.dp, DeviceBorder, DeviceShape)
            .background(DevicePanelRaised)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(text, fontFamily = DeviceFont, fontWeight = FontWeight.Black, fontSize = 13.sp, color = DeviceMint)
    }
}

@Composable
private fun TimerDisplay(startTimeMillis: Long?) {
    var elapsed by remember { mutableStateOf(0L) }
    LaunchedEffect(startTimeMillis) {
        if (startTimeMillis == null) elapsed = 0L
        else while (true) {
            elapsed = (System.currentTimeMillis() - startTimeMillis) / 1000
            delay(1000)
        }
    }
    val h = elapsed / 3600
    val m = (elapsed % 3600) / 60
    val s = elapsed % 60
    Column(
        Modifier.fillMaxWidth().clip(DeviceShape).border(1.dp, DeviceBorder, DeviceShape).background(DevicePanel).padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(if (startTimeMillis == null) "STOPPED" else "RUNNING", fontFamily = DeviceFont, fontSize = 11.sp, fontWeight = FontWeight.Black, color = DeviceOrange)
        Text(String.format("%02d:%02d:%02d", h, m, s), fontFamily = DeviceFont, fontSize = 42.sp, fontWeight = FontWeight.Black, color = DeviceMint)
    }
}

@Composable
private fun ProjectsScreen(
    onNavigateBack: () -> Unit,
    onProjectSelected: (Long) -> Unit,
    onCreateProject: (String) -> Unit
) {
    val vm: com.cubetimetracker.ui.SessionsViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
    val projects by vm.projectsWithTotals().collectAsState(initial = emptyList())
    var showCreateDialog by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }

    Scaffold(containerColor = DeviceBackground, topBar = { DeviceTopBar("PROJECTS", onNavigateBack) }) { pad ->
        LazyColumn(
            Modifier.fillMaxSize().padding(pad).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(projects, key = { it.project.id }) { summary ->
                ProjectSummaryItem(summary) { onProjectSelected(summary.project.id) }
            }
            item { Spacer(Modifier.height(4.dp)); DeviceButton("+ NEW PROJECT") { showCreateDialog = true } }
        }
    }

    if (showCreateDialog) {
        DeviceTextDialog("NEW PROJECT", "PROJECT NAME", name, { name = it }, {
            if (name.isNotBlank()) {
                onCreateProject(name.trim())
                name = ""
                showCreateDialog = false
            }
        }, { showCreateDialog = false })
    }
}

@Composable
private fun ProjectSummaryItem(summary: ProjectSummary, onClick: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().clip(DeviceShape).border(1.dp, DeviceBorder, DeviceShape).background(DevicePanel).clickable(onClick = onClick).padding(15.dp)
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(summary.project.name.uppercase(), fontFamily = DeviceFont, fontWeight = FontWeight.Black, fontSize = 14.sp, color = DeviceText)
                Spacer(Modifier.height(4.dp))
                Text("${summary.sessionCount} SESSION${if (summary.sessionCount == 1) "" else "S"}", fontFamily = DeviceFont, fontWeight = FontWeight.Bold, fontSize = 10.sp, color = DeviceMuted)
            }
            Text(formatDuration(summary.totalMillis), fontFamily = DeviceFont, fontWeight = FontWeight.Black, fontSize = 16.sp, color = DeviceMint)
        }
    }
}

@Composable
private fun ProjectDetailScreen(projectId: Long, onNavigateBack: () -> Unit) {
    val vm: com.cubetimetracker.ui.SessionsViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
    val project by vm.project(projectId).collectAsState(initial = null)
    val sessions by vm.sessionsForProject(projectId).collectAsState(initial = emptyList())
    val total = sessions.sumOf { (it.endEpochMillis ?: System.currentTimeMillis()) - it.startEpochMillis }
    val scope = rememberCoroutineScope()
    val formatter = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US) }
    var editingSession by remember { mutableStateOf<TimeSession?>(null) }

    Scaffold(containerColor = DeviceBackground, topBar = { DeviceTopBar(project?.name?.uppercase() ?: "PROJECT", onNavigateBack) }) { pad ->
        LazyColumn(
            Modifier.fillMaxSize().padding(pad).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                Column(Modifier.fillMaxWidth().clip(DeviceShape).border(1.dp, DeviceBorder, DeviceShape).background(DevicePanel).padding(16.dp)) {
                    Text("TOTAL TRACKED", fontFamily = DeviceFont, fontWeight = FontWeight.Black, fontSize = 10.sp, color = DeviceMuted)
                    Text(formatDuration(total), fontFamily = DeviceFont, fontWeight = FontWeight.Black, fontSize = 34.sp, color = DeviceMint)
                    Text("${sessions.size} SESSION${if (sessions.size == 1) "" else "S"}", fontFamily = DeviceFont, fontWeight = FontWeight.Bold, fontSize = 11.sp, color = DeviceOrange)
                }
            }
            item { Text("SESSIONS", fontFamily = DeviceFont, fontWeight = FontWeight.Black, fontSize = 11.sp, color = DeviceMuted, modifier = Modifier.padding(top = 8.dp, bottom = 2.dp)) }
            items(sessions, key = { it.id }) { session ->
                SessionItem(
                    session = session,
                    formatter = formatter,
                    onEdit = { editingSession = session },
                    onDelete = { scope.launch { vm.deleteSession(session.id) } }
                )
            }
        }
    }

    editingSession?.let { session ->
        SessionEditDialog(
            session = session,
            onDismiss = { editingSession = null },
            onSave = { start, end ->
                scope.launch { vm.updateSession(session.copy(startEpochMillis = start, endEpochMillis = end)); editingSession = null }
            }
        )
    }
}

@Composable
private fun SessionItem(session: TimeSession, formatter: SimpleDateFormat, onEdit: () -> Unit, onDelete: () -> Unit) {
    val end = session.endEpochMillis
    val duration = (end ?: System.currentTimeMillis()) - session.startEpochMillis
    Row(
        Modifier.fillMaxWidth().clip(DeviceShape).border(1.dp, DeviceBorder, DeviceShape).background(DevicePanel).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(formatter.format(Date(session.startEpochMillis)), fontFamily = DeviceFont, fontWeight = FontWeight.Bold, fontSize = 11.sp, color = DeviceText)
            Text(if (end == null) "ONGOING" else "END ${formatter.format(Date(end))}", fontFamily = DeviceFont, fontWeight = FontWeight.Bold, fontSize = 10.sp, color = DeviceMuted)
            Spacer(Modifier.height(4.dp))
            Text(formatDuration(duration), fontFamily = DeviceFont, fontWeight = FontWeight.Black, fontSize = 15.sp, color = DeviceMint)
        }
        IconButton(onClick = onEdit) { Icon(Icons.Default.Edit, "Edit session", tint = DeviceOrange) }
        IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, "Delete session", tint = DeviceRed) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DeviceTopBar(title: String, onBack: () -> Unit) {
    TopAppBar(
        title = { Text(title, fontFamily = DeviceFont, fontWeight = FontWeight.Black, fontSize = 15.sp) },
        navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back") } },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = DeviceBackground, titleContentColor = DeviceMint, navigationIconContentColor = DeviceMint)
    )
}

@Composable
private fun TagAssignmentDialog(tagUid: String, onDismiss: () -> Unit, onProjectSelected: (Long) -> Unit, onCreateNewProject: () -> Unit) {
    val vm: com.cubetimetracker.ui.SessionsViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
    val projects by vm.activeProjects().collectAsState(initial = emptyList())
    DeviceDialog(
        title = "TAG DETECTED",
        onDismiss = onDismiss,
        content = {
            Text("UID: $tagUid", fontFamily = DeviceFont, fontWeight = FontWeight.Bold, fontSize = 10.sp, color = DeviceMuted)
            Spacer(Modifier.height(12.dp))
            Text("ASSIGN TO:", fontFamily = DeviceFont, fontWeight = FontWeight.Black, fontSize = 11.sp, color = DeviceText)
            Spacer(Modifier.height(4.dp))
            projects.forEach { project ->
                TextButton(onClick = { onProjectSelected(project.id) }, modifier = Modifier.fillMaxWidth()) {
                    Text(project.name.uppercase(), fontFamily = DeviceFont, fontWeight = FontWeight.Black, color = DeviceMint)
                }
            }
            OutlinedButton(onClick = onCreateNewProject, modifier = Modifier.fillMaxWidth()) {
                Text("+ NEW PROJECT", fontFamily = DeviceFont, fontWeight = FontWeight.Black, color = DeviceMint)
            }
        },
        confirmText = "SKIP",
        onConfirm = onDismiss
    )
}

@Composable
private fun SessionEditDialog(session: TimeSession, onDismiss: () -> Unit, onSave: (Long, Long?) -> Unit) {
    var startText by remember { mutableStateOf(session.startEpochMillis.toString()) }
    var endText by remember { mutableStateOf(session.endEpochMillis?.toString() ?: "") }
    DeviceDialog(
        title = "EDIT SESSION",
        onDismiss = onDismiss,
        content = {
            Text("USE UNIX TIME IN MILLISECONDS", fontFamily = DeviceFont, fontWeight = FontWeight.Bold, fontSize = 10.sp, color = DeviceMuted)
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(startText, { startText = it }, label = { Text("START", fontFamily = DeviceFont) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(endText, { endText = it }, label = { Text("END / BLANK = ONGOING", fontFamily = DeviceFont) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        },
        confirmText = "SAVE",
        onConfirm = {
            val start = startText.toLongOrNull() ?: return@DeviceDialog
            val end = endText.trim().ifEmpty { null }?.toLongOrNull()
            onSave(start, end)
        },
        dismissText = "CANCEL",
        onDismissAction = onDismiss
    )
}

@Composable
private fun DeviceTextDialog(title: String, label: String, value: String, onValueChange: (String) -> Unit, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    DeviceDialog(
        title = title,
        onDismiss = onDismiss,
        content = { OutlinedTextField(value, onValueChange, label = { Text(label, fontFamily = DeviceFont) }, singleLine = true, modifier = Modifier.fillMaxWidth()) },
        confirmText = "CREATE",
        onConfirm = onConfirm,
        dismissText = "CANCEL",
        onDismissAction = onDismiss
    )
}

@Composable
private fun DeviceDialog(
    title: String,
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
    confirmText: String,
    onConfirm: () -> Unit,
    dismissText: String? = null,
    onDismissAction: (() -> Unit)? = null
) {
    AlertDialog(
        modifier = Modifier.clip(DeviceShape),
        shape = DeviceShape,
        containerColor = DevicePanel,
        tonalElevation = 0.dp,
        onDismissRequest = onDismiss,
        title = { Text(title, fontFamily = DeviceFont, fontWeight = FontWeight.Black, color = DeviceMint) },
        text = { Column(content = content) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirmText, fontFamily = DeviceFont, fontWeight = FontWeight.Black, color = DeviceMint) } },
        dismissButton = if (dismissText == null) null else {
            { TextButton(onClick = onDismissAction ?: onDismiss) { Text(dismissText, fontFamily = DeviceFont, fontWeight = FontWeight.Black, color = DeviceText) } }
        }
    )
}

private fun formatDuration(milliseconds: Long): String {
    val safe = milliseconds.coerceAtLeast(0L)
    val totalMinutes = safe / 60_000
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    return if (hours > 0) "%dh %02dm".format(hours, minutes) else "%dm".format(minutes)
}
