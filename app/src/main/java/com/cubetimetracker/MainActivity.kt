package com.cubetimetracker

import android.content.Intent
import android.graphics.Color as AndroidColor
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
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import com.cubetimetracker.data.AppDatabase
import com.cubetimetracker.data.Project
import com.cubetimetracker.data.ProjectSummary
import com.cubetimetracker.data.TagMapping
import com.cubetimetracker.data.TimeSession
import com.cubetimetracker.nfc.NfcHelper
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val AppBlack = Color(0xFF080808)
private val PanelLevel1 = Color(0xFF234057)
private val PanelLevel2 = Color(0xFF1D3548)
private val PanelLevel3 = Color(0xFF172A39)
private val BorderLevel1 = Color(0xFF2B4D66)
private val BorderLevel2 = Color(0xFF243F54)
private val BorderLevel3 = Color(0xFF1D3445)
private val DevicePrimary = Color(0xFF83B8D7)
private val DeviceText = Color(0xFFF0E6D9)
private val DeviceOrange = Color(0xFFFFA876)
private val DeviceRed = Color(0xFFFF705F)
private val DeviceMuted = Color(0xFF9EB0BC)
private val DeviceShape = RoundedCornerShape(3.dp)
private val Exo2 = FontFamily(
    Font(R.font.exo2_light, FontWeight.Light),
    Font(R.font.exo2_regular, FontWeight.Normal),
    Font(R.font.exo2_black, FontWeight.Black)
)

class MainActivity : ComponentActivity() {
    private lateinit var nfcHelper: NfcHelper
    private lateinit var db: AppDatabase
    private lateinit var vibrator: Vibrator
    private val activeProjectName = mutableStateOf<String?>(null)
    private val activeProjectId = mutableStateOf<Long?>(null)
    private val activeSessionStart = mutableStateOf<Long?>(null)
    private val currentScreen = mutableStateOf("home")
    private val selectedProjectId = mutableStateOf<Long?>(null)
    private val pendingTagUid = mutableStateOf<String?>(null)
    private val showNewProjectDialog = mutableStateOf(false)
    private val newProjectName = mutableStateOf("")
    private var lastTagUid: String? = null
    private var lastTagReadAt = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Keep the Android status/navigation bars black. Do not access
        // WindowInsetsController here: on some Android builds DecorView is
        // not initialized yet and that causes an app-launch crash.
        window.statusBarColor = AndroidColor.BLACK
        window.navigationBarColor = AndroidColor.BLACK
        WindowCompat.setDecorFitsSystemWindows(window, true)

        nfcHelper = NfcHelper(this)
        db = AppDatabase.getInstance(this)
        vibrator = getSystemService(VIBRATOR_SERVICE) as Vibrator
        restoreOpenSession()

        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = DevicePrimary,
                    secondary = DeviceOrange,
                    background = AppBlack,
                    surface = PanelLevel1,
                    onPrimary = AppBlack,
                    onSecondary = AppBlack,
                    onBackground = DeviceText,
                    onSurface = DeviceText
                )
            ) {
                Surface(Modifier.fillMaxSize(), color = AppBlack) {
                    when (currentScreen.value) {
                        "home" -> HomeScreen(
                            activeProjectId = activeProjectId.value,
                            activeProjectName = activeProjectName.value,
                            activeSessionStart = activeSessionStart.value,
                            onProjectSelected = { id ->
                                selectedProjectId.value = id
                                currentScreen.value = "project_detail"
                            },
                            onCreateProject = { name ->
                                lifecycleScope.launch { db.projectDao().insert(Project(name = name)) }
                            }
                        )
                        "project_detail" -> selectedProjectId.value?.let { id ->
                            ProjectDetailScreen(
                                projectId = id,
                                activeProjectId = activeProjectId.value,
                                onNavigateBack = { currentScreen.value = "home" },
                                onStartProject = { startProjectManually(id) },
                                onStopProject = { stopActiveSession() }
                            )
                        }
                    }

                    pendingTagUid.value?.let { uid ->
                        TagAssignmentDialog(
                            tagUid = uid,
                            onDismiss = { pendingTagUid.value = null },
                            onProjectSelected = { id -> assignTagAndStart(uid, id) },
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
                                if (name.isNotBlank()) {
                                    lifecycleScope.launch {
                                        val id = db.projectDao().insert(Project(name = name))
                                        if (uid != null) {
                                            db.tagMappingDao().upsert(TagMapping(tagUid = uid, projectId = id))
                                            startSession(id, name)
                                            pendingTagUid.value = null
                                        }
                                        newProjectName.value = ""
                                        showNewProjectDialog.value = false
                                    }
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

    private fun restoreOpenSession() {
        lifecycleScope.launch {
            db.timeSessionDao().getOpenSession()?.let { session ->
                activeProjectId.value = session.projectId
                activeSessionStart.value = session.startEpochMillis
                activeProjectName.value = db.projectDao().getById(session.projectId)?.name
            }
        }
    }

    private fun assignTagAndStart(uid: String, projectId: Long) {
        lifecycleScope.launch {
            db.tagMappingDao().upsert(TagMapping(tagUid = uid, projectId = projectId))
            startProject(projectId)
            pendingTagUid.value = null
        }
    }

    private fun startProjectManually(projectId: Long) {
        lifecycleScope.launch { startProject(projectId) }
    }

    private suspend fun startProject(projectId: Long) {
        startSession(projectId, db.projectDao().getById(projectId)?.name)
    }

    private suspend fun startSession(projectId: Long, name: String?) {
        val now = System.currentTimeMillis()
        val previous = db.timeSessionDao().getOpenSession()
        previous?.let { db.timeSessionDao().closeSession(it.id, now) }
        db.timeSessionDao().insert(TimeSession(projectId = projectId, startEpochMillis = now))
        activeProjectId.value = projectId
        activeProjectName.value = name
        activeSessionStart.value = now
        if (previous == null) vibrateStart() else vibrateSwitch()
    }

    private fun stopActiveSession() {
        lifecycleScope.launch {
            val open = db.timeSessionDao().getOpenSession() ?: return@launch
            db.timeSessionDao().closeSession(open.id, System.currentTimeMillis())
            activeProjectId.value = null
            activeProjectName.value = null
            activeSessionStart.value = null
            vibrateStop()
        }
    }

    private fun handleIntentIfTag(intent: Intent) {
        val uid = runCatching { nfcHelper.extractUid(intent) }.getOrNull() ?: return
        val now = System.currentTimeMillis()
        if (uid == lastTagUid && now - lastTagReadAt < 1500L) return
        lastTagUid = uid
        lastTagReadAt = now
        lifecycleScope.launch {
            try {
                val mapping = db.tagMappingDao().findByUid(uid)
                if (mapping == null) {
                    pendingTagUid.value = uid
                    vibrateUnknown()
                    return@launch
                }
                val open = db.timeSessionDao().getOpenSession()
                if (open != null && open.projectId == mapping.projectId) {
                    stopActiveSession()
                } else {
                    startProject(mapping.projectId)
                }
            } catch (error: Exception) {
                Log.e("CubeTimeTracker", "NFC handling error", error)
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

    private fun vibratePattern(pattern: LongArray) {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
            } else {
                @Suppress("DEPRECATION") vibrator.vibrate(pattern, -1)
            }
        }
    }

    private fun vibrateStart() = vibratePattern(longArrayOf(0, 45))
    private fun vibrateStop() = vibratePattern(longArrayOf(0, 140))
    private fun vibrateSwitch() = vibratePattern(longArrayOf(0, 35, 65, 35))
    private fun vibrateUnknown() = vibratePattern(longArrayOf(0, 30, 45, 30, 45, 30))
}

@Composable
private fun HomeScreen(
    activeProjectId: Long?,
    activeProjectName: String?,
    activeSessionStart: Long?,
    onProjectSelected: (Long) -> Unit,
    onCreateProject: (String) -> Unit
) {
    val vm: com.cubetimetracker.ui.SessionsViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
    val projects by vm.projectsWithTotals().collectAsState(initial = emptyList<ProjectSummary>())
    var showCreateDialog by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Spacer(Modifier.height(14.dp))
        Text(
            "CUBE TIME",
            Modifier.fillMaxWidth(),
            fontFamily = Exo2,
            fontWeight = FontWeight.Light,
            fontSize = 10.sp,
            color = Color(0xFF151515),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
        Spacer(Modifier.height(18.dp))
        TimePanel(activeProjectName, activeSessionStart)
        Spacer(Modifier.height(16.dp))
        Text("PROJECTS", fontFamily = Exo2, fontWeight = FontWeight.Black, fontSize = 11.sp, color = DeviceMuted)
        Spacer(Modifier.height(7.dp))
        LazyColumn(
            Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(7.dp),
            contentPadding = PaddingValues(bottom = 14.dp)
        ) {
            items(projects, key = { it.project.id }) { summary ->
                ProjectSummaryItem(
                    summary = summary,
                    isActive = summary.project.id == activeProjectId,
                    onClick = { onProjectSelected(summary.project.id) }
                )
            }
            item { DeviceButton(text = "+ ADD PROJECT", onClick = { showCreateDialog = true }) }
        }
    }

    if (showCreateDialog) {
        DeviceTextDialog(
            title = "NEW PROJECT",
            label = "PROJECT NAME",
            value = newName,
            onValueChange = { newName = it },
            onConfirm = {
                if (newName.isNotBlank()) {
                    onCreateProject(newName.trim())
                    newName = ""
                    showCreateDialog = false
                }
            },
            onDismiss = { showCreateDialog = false }
        )
    }
}

@Composable
private fun DeviceButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .height(46.dp)
            .clip(DeviceShape)
            .border(1.dp, BorderLevel2, DeviceShape)
            .background(PanelLevel3)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(text, fontFamily = Exo2, fontWeight = FontWeight.Black, fontSize = 12.sp, color = DevicePrimary)
    }
}

@Composable
private fun TimePanel(activeProjectName: String?, startTimeMillis: Long?) {
    var elapsed by remember { mutableStateOf(0L) }
    LaunchedEffect(startTimeMillis) {
        if (startTimeMillis == null) {
            elapsed = 0L
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
        Modifier.fillMaxWidth().height(225.dp).clip(DeviceShape).border(1.dp, BorderLevel1, DeviceShape).background(PanelLevel1).padding(22.dp),
        verticalArrangement = Arrangement.SpaceBetween,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(activeProjectName?.uppercase() ?: "NO ACTIVE PROJECT", fontFamily = Exo2, fontWeight = FontWeight.Black, fontSize = 16.sp, color = if (activeProjectName == null) DeviceMuted else DeviceText, maxLines = 1)
        Text(String.format("%02d:%02d:%02d", h, m, s), fontFamily = Exo2, fontWeight = FontWeight.Black, fontSize = 48.sp, color = DevicePrimary)
        Text(if (startTimeMillis == null) "TAP A TAG TO START" else "TRACKING", fontFamily = Exo2, fontWeight = FontWeight.Normal, fontSize = 10.sp, color = if (startTimeMillis == null) DeviceMuted else DeviceOrange)
    }
}

@Composable
private fun ProjectSummaryItem(summary: ProjectSummary, isActive: Boolean, onClick: () -> Unit) {
    val panel = if (isActive) PanelLevel2 else PanelLevel3
    val border = if (isActive) DeviceOrange else BorderLevel3
    Box(
        Modifier.fillMaxWidth().clip(DeviceShape).border(1.dp, border, DeviceShape).background(panel).clickable(onClick = onClick).padding(15.dp)
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(summary.project.name.uppercase(), fontFamily = Exo2, fontWeight = FontWeight.Black, fontSize = 14.sp, color = if (isActive) DeviceOrange else DeviceText)
                Spacer(Modifier.height(4.dp))
                Text(if (isActive) "ACTIVE / ${summary.sessionCount} SESSIONS" else "${summary.sessionCount} SESSION${if (summary.sessionCount == 1) "" else "S"}", fontFamily = Exo2, fontWeight = FontWeight.Normal, fontSize = 10.sp, color = if (isActive) DeviceOrange else DeviceMuted)
            }
            Text(formatDuration(summary.totalMillis), fontFamily = Exo2, fontWeight = FontWeight.Black, fontSize = 16.sp, color = if (isActive) DeviceOrange else DevicePrimary)
        }
    }
}

@Composable
private fun ProjectDetailScreen(
    projectId: Long,
    activeProjectId: Long?,
    onNavigateBack: () -> Unit,
    onStartProject: () -> Unit,
    onStopProject: () -> Unit
) {
    val vm: com.cubetimetracker.ui.SessionsViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
    val project by vm.project(projectId).collectAsState(initial = null)
    val sessions by vm.sessionsForProject(projectId).collectAsState(initial = emptyList())
    val total = sessions.sumOf { (it.endEpochMillis ?: System.currentTimeMillis()) - it.startEpochMillis }
    val scope = rememberCoroutineScope()
    val formatter = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US) }
    var editingSession by remember { mutableStateOf<TimeSession?>(null) }
    val isCurrentProject = activeProjectId == projectId

    Scaffold(
        containerColor = AppBlack,
        topBar = { DeviceTopBar(project?.name?.uppercase() ?: "PROJECT", onNavigateBack) }
    ) { paddingValues ->
        LazyColumn(
            Modifier.fillMaxSize().padding(paddingValues).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                Row(
                    Modifier.fillMaxWidth().clip(DeviceShape).border(1.dp, BorderLevel1, DeviceShape).background(PanelLevel1).padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("TOTAL TRACKED", fontFamily = Exo2, fontWeight = FontWeight.Black, fontSize = 10.sp, color = DeviceMuted)
                        Text(formatDuration(total), fontFamily = Exo2, fontWeight = FontWeight.Black, fontSize = 34.sp, color = DevicePrimary)
                        Text("${sessions.size} SESSION${if (sessions.size == 1) "" else "S"}", fontFamily = Exo2, fontWeight = FontWeight.Normal, fontSize = 11.sp, color = DeviceOrange)
                    }
                    ManualTimerButton(
                        isRunning = isCurrentProject,
                        onClick = { if (isCurrentProject) onStopProject() else onStartProject() }
                    )
                }
            }
            item {
                Text("SESSIONS", fontFamily = Exo2, fontWeight = FontWeight.Black, fontSize = 11.sp, color = DeviceMuted, modifier = Modifier.padding(top = 8.dp, bottom = 2.dp))
            }
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
                scope.launch {
                    vm.updateSession(session.copy(startEpochMillis = start, endEpochMillis = end))
                    editingSession = null
                }
            }
        )
    }
}

@Composable
private fun ManualTimerButton(isRunning: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(52.dp).clip(DeviceShape).border(1.dp, if (isRunning) DeviceOrange else BorderLevel2, DeviceShape).background(if (isRunning) PanelLevel2 else PanelLevel3).clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        if (isRunning) {
            Text("■", fontFamily = Exo2, fontWeight = FontWeight.Black, fontSize = 18.sp, color = DeviceOrange)
        } else {
            Icon(Icons.Default.PlayArrow, "Start timer", tint = DevicePrimary)
        }
    }
}

@Composable
private fun SessionItem(session: TimeSession, formatter: SimpleDateFormat, onEdit: () -> Unit, onDelete: () -> Unit) {
    val end = session.endEpochMillis
    val duration = (end ?: System.currentTimeMillis()) - session.startEpochMillis
    Row(
        Modifier.fillMaxWidth().clip(DeviceShape).border(1.dp, BorderLevel3, DeviceShape).background(PanelLevel3).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(formatter.format(Date(session.startEpochMillis)), fontFamily = Exo2, fontWeight = FontWeight.Normal, fontSize = 11.sp, color = DeviceText)
            Text(if (end == null) "ONGOING" else "END ${formatter.format(Date(end))}", fontFamily = Exo2, fontWeight = FontWeight.Normal, fontSize = 10.sp, color = DeviceMuted)
            Spacer(Modifier.height(4.dp))
            Text(formatDuration(duration), fontFamily = Exo2, fontWeight = FontWeight.Black, fontSize = 15.sp, color = DevicePrimary)
        }
        IconButton(onClick = onEdit) { Icon(Icons.Default.Edit, "Edit session", tint = DeviceOrange) }
        IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, "Delete session", tint = DeviceRed) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DeviceTopBar(title: String, onBack: () -> Unit) {
    TopAppBar(
        title = { Text(title, fontFamily = Exo2, fontWeight = FontWeight.Black, fontSize = 15.sp) },
        navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back") } },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = AppBlack, titleContentColor = DevicePrimary, navigationIconContentColor = DevicePrimary)
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
            Text("UID: $tagUid", fontFamily = Exo2, fontWeight = FontWeight.Normal, fontSize = 10.sp, color = DeviceMuted)
            Spacer(Modifier.height(12.dp))
            Text("ASSIGN TO:", fontFamily = Exo2, fontWeight = FontWeight.Black, fontSize = 11.sp, color = DeviceText)
            Spacer(Modifier.height(4.dp))
            projects.forEach { project -> DeviceTextAction(project.name.uppercase()) { onProjectSelected(project.id) } }
            DeviceOutlineAction("+ NEW PROJECT", onCreateNewProject)
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
            Text("USE UNIX TIME IN MILLISECONDS", fontFamily = Exo2, fontWeight = FontWeight.Normal, fontSize = 10.sp, color = DeviceMuted)
            Spacer(Modifier.height(10.dp))
            DeviceField(startText, { startText = it }, "START")
            Spacer(Modifier.height(8.dp))
            DeviceField(endText, { endText = it }, "END / BLANK = ONGOING")
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
    DeviceDialog(title, onDismiss, { DeviceField(value, onValueChange, label) }, "CREATE", onConfirm, "CANCEL", onDismiss)
}

@Composable
private fun DeviceField(value: String, onValueChange: (String) -> Unit, label: String) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label, fontFamily = Exo2, fontWeight = FontWeight.Normal, fontSize = 11.sp) },
        textStyle = LocalTextStyle.current.copy(fontFamily = Exo2, fontWeight = FontWeight.Normal, color = DeviceText),
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
        shape = DeviceShape,
        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = BorderLevel1, unfocusedBorderColor = BorderLevel2, focusedLabelColor = DevicePrimary, unfocusedLabelColor = DeviceMuted, cursorColor = DevicePrimary)
    )
}

@Composable
private fun DeviceTextAction(text: String, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.fillMaxWidth().clip(DeviceShape), shape = DeviceShape, colors = ButtonDefaults.textButtonColors(contentColor = DevicePrimary)) {
        Text(text, fontFamily = Exo2, fontWeight = FontWeight.Black, fontSize = 12.sp)
    }
}

@Composable
private fun DeviceOutlineAction(text: String, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth(), shape = DeviceShape, border = androidx.compose.foundation.BorderStroke(1.dp, BorderLevel2), colors = ButtonDefaults.outlinedButtonColors(contentColor = DevicePrimary)) {
        Text(text, fontFamily = Exo2, fontWeight = FontWeight.Black, fontSize = 12.sp)
    }
}

@Composable
private fun DeviceDialog(title: String, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit, confirmText: String, onConfirm: () -> Unit, dismissText: String? = null, onDismissAction: (() -> Unit)? = null) {
    AlertDialog(
        modifier = Modifier.clip(DeviceShape),
        shape = DeviceShape,
        containerColor = PanelLevel2,
        tonalElevation = 0.dp,
        onDismissRequest = onDismiss,
        title = { Text(title, fontFamily = Exo2, fontWeight = FontWeight.Black, color = DevicePrimary) },
        text = { Column(content = content) },
        confirmButton = { DeviceTextAction(confirmText, onConfirm) },
        dismissButton = if (dismissText == null) null else { { DeviceTextAction(dismissText, onDismissAction ?: onDismiss) } }
    )
}

private fun formatDuration(milliseconds: Long): String {
    val minutes = milliseconds.coerceAtLeast(0L) / 60_000
    val hours = minutes / 60
    return if (hours > 0) "%dh %02dm".format(hours, minutes % 60) else "%dm".format(minutes)
}
