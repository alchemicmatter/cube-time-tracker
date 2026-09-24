package com.cubetimetracker

import android.app.DatePickerDialog
import android.app.TimePickerDialog
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
import androidx.compose.ui.platform.LocalContext
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
import java.util.Calendar
import java.util.Date
import java.util.Locale

private val AppBlack = Color(0xFF080808)
private val PanelLevel1 = Color(0xFF234057)
private val PanelLevel2 = Color(0xFF1D3548)
private val PanelLevel3 = Color(0xFF172A39)
private val PanelLevel4 = Color(0xFF12212D)
private val PanelActive = Color(0xFF854A32)
private val BorderLevel1 = Color(0xFF2B4D66)
private val BorderLevel2 = Color(0xFF243F54)
private val BorderLevel3 = Color(0xFF1D3445)
private val BorderLevel4 = Color(0xFF182B39)
private val BorderActive = Color(0xFF9E5A3B)
private val DevicePrimary = Color(0xFF83B8D7)
private val DeviceText = Color(0xFFF0E6D9)
private val DeviceOrange = Color(0xFFFFA876)
private val DeviceRed = Color(0xFFFF705F)
private val DeviceMuted = Color(0xFF9EB0BC)
private val DeviceShape = RoundedCornerShape(3.dp)
private val AppPadding = 12.dp
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
                                lifecycleScope.launch {
                                    db.projectDao().insert(Project(name = name))
                                }
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
                                        val projectId = db.projectDao().insert(Project(name = name))
                                        if (uid != null) {
                                            db.tagMappingDao().upsert(
                                                TagMapping(tagUid = uid, projectId = projectId)
                                            )
                                            startSession(projectId, name)
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

    Column(Modifier.fillMaxSize().padding(horizontal = AppPadding)) {
        Spacer(Modifier.height(AppPadding))
        Text(
            text = "CUBE TIME",
            modifier = Modifier.fillMaxWidth(),
            fontFamily = Exo2,
            fontWeight = FontWeight.Light,
            fontSize = 10.sp,
            color = Color(0xFF151515),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
        Spacer(Modifier.height(AppPadding))
        TimePanel(activeProjectName, activeSessionStart)
        Spacer(Modifier.height(AppPadding))
        Text("PROJECTS", fontFamily = Exo2, fontWeight = FontWeight.Black, fontSize = 11.sp, color = DeviceMuted)
        Spacer(Modifier.height(6.dp))
        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            contentPadding = PaddingValues(bottom = AppPadding)
        ) {
            items(projects, key = { it.project.id }) { summary ->
                ProjectSummaryItem(
                    summary = summary,
                    isActive = summary.project.id == activeProjectId,
                    onClick = { onProjectSelected(summary.project.id) }
                )
            }
            item {
                DeviceButton(text = "+ ADD PROJECT", onClick = { showCreateDialog = true })
            }
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
private fun DeviceButton(text: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(44.dp)
            .clip(DeviceShape)
            .border(1.dp, BorderLevel4, DeviceShape)
            .background(PanelLevel4)
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
        modifier = Modifier
            .fillMaxWidth()
            .height(220.dp)
            .clip(DeviceShape)
            .border(1.dp, BorderLevel1, DeviceShape)
            .background(PanelLevel1)
            .padding(AppPadding),
        verticalArrangement = Arrangement.SpaceBetween,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = activeProjectName?.uppercase() ?: "NO ACTIVE PROJECT",
            fontFamily = Exo2,
            fontWeight = FontWeight.Black,
            fontSize = 16.sp,
            color = if (activeProjectName == null) DeviceMuted else DeviceText,
            maxLines = 1
        )
        Text(
            text = String.format("%02d:%02d:%02d", h, m, s),
            fontFamily = Exo2,
            fontWeight = FontWeight.Black,
            fontSize = 48.sp,
            color = DevicePrimary
        )
        Text(
            text = if (startTimeMillis == null) "TAP A TAG TO START" else "TRACKING",
            fontFamily = Exo2,
            fontWeight = FontWeight.Normal,
            fontSize = 10.sp,
            color = if (startTimeMillis == null) DeviceMuted else DeviceOrange
        )
    }
}

@Composable
private fun ProjectSummaryItem(summary: ProjectSummary, isActive: Boolean, onClick: () -> Unit) {
    val panel = if (isActive) PanelActive else PanelLevel2
    val border = if (isActive) BorderActive else BorderLevel2
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(DeviceShape)
            .border(1.dp, border, DeviceShape)
            .background(panel)
            .clickable(onClick = onClick)
            .padding(AppPadding)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(summary.project.name.uppercase(), fontFamily = Exo2, fontWeight = FontWeight.Black, fontSize = 14.sp, color = DeviceText)
                Spacer(Modifier.height(3.dp))
                Text(
                    text = if (isActive) "ACTIVE / ${summary.sessionCount} SESSIONS" else "${summary.sessionCount} SESSION${if (summary.sessionCount == 1) "" else "S"}",
                    fontFamily = Exo2,
                    fontWeight = FontWeight.Normal,
                    fontSize = 10.sp,
                    color = if (isActive) DeviceText else DeviceMuted
                )
            }
            Text(
                text = formatDuration(summary.totalMillis),
                fontFamily = Exo2,
                fontWeight = FontWeight.Black,
                fontSize = 16.sp,
                color = if (isActive) DeviceText else DevicePrimary
            )
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
    val tags by vm.tagsForProject(projectId).collectAsState(initial = emptyList())
    val total = sessions.sumOf { (it.endEpochMillis ?: System.currentTimeMillis()) - it.startEpochMillis }
    val scope = rememberCoroutineScope()
    val formatter = remember { SimpleDateFormat("dd MMM yyyy · HH:mm", Locale.ITALY) }
    var editingSession by remember { mutableStateOf<TimeSession?>(null) }
    var renameDialog by remember { mutableStateOf(false) }
    var archiveConfirm by remember { mutableStateOf(false) }
    var deleteConfirm by remember { mutableStateOf(false) }
    var projectName by remember(project?.name) { mutableStateOf(project?.name ?: "") }
    val isCurrentProject = activeProjectId == projectId

    Scaffold(
        containerColor = AppBlack,
        topBar = { DeviceTopBar(project?.name?.uppercase() ?: "PROJECT", onNavigateBack) }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = AppPadding),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            contentPadding = PaddingValues(bottom = AppPadding)
        ) {
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(DeviceShape)
                        .border(1.dp, BorderLevel1, DeviceShape)
                        .background(PanelLevel1)
                        .padding(AppPadding),
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
                ProjectManagementPanel(
                    onRename = { renameDialog = true },
                    onArchive = { archiveConfirm = true },
                    onDelete = { deleteConfirm = true }
                )
            }
            if (tags.isNotEmpty()) {
                item { SectionLabel("ASSOCIATED TAGS") }
                items(tags, key = { it.tagUid }) { tag ->
                    TagItem(tag = tag, onRemove = { scope.launch { vm.removeTag(tag.tagUid) } })
                }
            }
            item { SectionLabel("SESSIONS") }
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

    if (renameDialog) {
        DeviceTextDialog(
            title = "RENAME PROJECT",
            label = "PROJECT NAME",
            value = projectName,
            onValueChange = { projectName = it },
            onConfirm = {
                if (projectName.isNotBlank() && project != null) {
                    scope.launch {
                        vm.renameProject(project!!, projectName)
                        renameDialog = false
                    }
                }
            },
            onDismiss = { renameDialog = false }
        )
    }

    if (archiveConfirm) {
        ConfirmDialog(
            title = "ARCHIVE PROJECT",
            message = "The project and its sessions remain saved, but it will no longer appear on the home screen.",
            confirmText = "ARCHIVE",
            onConfirm = {
                if (project != null) {
                    scope.launch {
                        vm.archiveProject(project!!)
                        onNavigateBack()
                    }
                }
            },
            onDismiss = { archiveConfirm = false }
        )
    }

    if (deleteConfirm) {
        ConfirmDialog(
            title = "DELETE PROJECT",
            message = "This permanently deletes the project, all tracked sessions, and tag associations.",
            confirmText = "DELETE",
            onConfirm = {
                scope.launch {
                    vm.deleteProjectPermanently(projectId)
                    onNavigateBack()
                }
            },
            onDismiss = { deleteConfirm = false },
            destructive = true
        )
    }

    editingSession?.let { session ->
        HumanSessionEditor(
            session = session,
            onDismiss = { editingSession = null },
            onSave = { updated ->
                scope.launch {
                    vm.updateSession(updated)
                    editingSession = null
                }
            }
        )
    }
}

@Composable
private fun ProjectManagementPanel(onRename: () -> Unit, onArchive: () -> Unit, onDelete: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(DeviceShape)
            .border(1.dp, BorderLevel2, DeviceShape)
            .background(PanelLevel2)
            .padding(4.dp),
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        CompactAction("RENAME", DevicePrimary, onRename)
        CompactAction("ARCHIVE", DeviceOrange, onArchive)
        CompactAction("DELETE", DeviceRed, onDelete)
    }
}

@Composable
private fun CompactAction(text: String, color: Color, onClick: () -> Unit) {
    TextButton(onClick = onClick, shape = DeviceShape) {
        Text(text, fontFamily = Exo2, fontWeight = FontWeight.Black, fontSize = 10.sp, color = color)
    }
}

@Composable
private fun TagItem(tag: TagMapping, onRemove: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(DeviceShape)
            .border(1.dp, BorderLevel3, DeviceShape)
            .background(PanelLevel3)
            .padding(AppPadding),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(tag.tagUid, modifier = Modifier.weight(1f), fontFamily = Exo2, fontWeight = FontWeight.Normal, fontSize = 11.sp, color = DeviceMuted)
        IconButton(onClick = onRemove) {
            Icon(Icons.Default.Delete, "Remove tag", tint = DeviceRed)
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        fontFamily = Exo2,
        fontWeight = FontWeight.Black,
        fontSize = 11.sp,
        color = DeviceMuted,
        modifier = Modifier.padding(top = 6.dp, bottom = 1.dp)
    )
}

@Composable
private fun ManualTimerButton(isRunning: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(52.dp)
            .clip(DeviceShape)
            .border(1.dp, if (isRunning) BorderActive else BorderLevel2, DeviceShape)
            .background(if (isRunning) PanelActive else PanelLevel2)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        if (isRunning) {
            Text("■", fontFamily = Exo2, fontWeight = FontWeight.Black, fontSize = 18.sp, color = DeviceText)
        } else {
            Icon(Icons.Default.PlayArrow, "Start timer", tint = DevicePrimary)
        }
    }
}

@Composable
private fun SessionItem(
    session: TimeSession,
    formatter: SimpleDateFormat,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val end = session.endEpochMillis
    val duration = (end ?: System.currentTimeMillis()) - session.startEpochMillis
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(DeviceShape)
            .border(1.dp, BorderLevel4, DeviceShape)
            .background(PanelLevel4)
            .padding(AppPadding),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = formatter.format(Date(session.startEpochMillis)),
                fontFamily = Exo2,
                fontWeight = FontWeight.Normal,
                fontSize = 11.sp,
                color = DeviceText
            )
            Text(
                text = if (end == null) "ONGOING" else "END ${formatter.format(Date(end))}",
                fontFamily = Exo2,
                fontWeight = FontWeight.Normal,
                fontSize = 10.sp,
                color = DeviceMuted
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text = formatDuration(duration),
                fontFamily = Exo2,
                fontWeight = FontWeight.Black,
                fontSize = 15.sp,
                color = DevicePrimary
            )
        }
        IconButton(onClick = onEdit) {
            Icon(Icons.Default.Edit, "Edit session", tint = DeviceOrange)
        }
        IconButton(onClick = onDelete) {
            Icon(Icons.Default.Delete, "Delete session", tint = DeviceRed)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DeviceTopBar(title: String, onBack: () -> Unit) {
    TopAppBar(
        title = { Text(title, fontFamily = Exo2, fontWeight = FontWeight.Black, fontSize = 15.sp) },
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, "Back")
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = AppBlack,
            titleContentColor = DevicePrimary,
            navigationIconContentColor = DevicePrimary
        )
    )
}

@Composable
private fun TagAssignmentDialog(
    tagUid: String,
    onDismiss: () -> Unit,
    onProjectSelected: (Long) -> Unit,
    onCreateNewProject: () -> Unit
) {
    val vm: com.cubetimetracker.ui.SessionsViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
    val projects by vm.activeProjects().collectAsState(initial = emptyList())
    DeviceDialog(
        title = "TAG DETECTED",
        onDismiss = onDismiss,
        content = {
            Text("UID: $tagUid", fontFamily = Exo2, fontWeight = FontWeight.Normal, fontSize = 10.sp, color = DeviceMuted)
            Spacer(Modifier.height(AppPadding))
            Text("ASSIGN TO:", fontFamily = Exo2, fontWeight = FontWeight.Black, fontSize = 11.sp, color = DeviceText)
            Spacer(Modifier.height(4.dp))
            projects.forEach { project ->
                DeviceTextAction(project.name.uppercase()) {
                    onProjectSelected(project.id)
                }
            }
            DeviceOutlineAction("+ NEW PROJECT", onCreateNewProject)
        },
        confirmText = "SKIP",
        onConfirm = onDismiss
    )
}

@Composable
private fun HumanSessionEditor(
    session: TimeSession,
    onDismiss: () -> Unit,
    onSave: (TimeSession) -> Unit
) {
    val context = LocalContext.current
    var startMillis by remember { mutableStateOf(session.startEpochMillis) }
    var endMillis by remember { mutableStateOf(session.endEpochMillis) }
    val dateFormat = remember { SimpleDateFormat("dd MMM yyyy", Locale.ITALY) }
    val timeFormat = remember { SimpleDateFormat("HH:mm", Locale.ITALY) }
    var validationError by remember { mutableStateOf<String?>(null) }

    fun pickDate(current: Long, onPicked: (Long) -> Unit) {
        val calendar = Calendar.getInstance().apply { timeInMillis = current }
        DatePickerDialog(
            context,
            { _, year, month, day ->
                calendar.set(Calendar.YEAR, year)
                calendar.set(Calendar.MONTH, month)
                calendar.set(Calendar.DAY_OF_MONTH, day)
                onPicked(calendar.timeInMillis)
            },
            calendar.get(Calendar.YEAR),
            calendar.get(Calendar.MONTH),
            calendar.get(Calendar.DAY_OF_MONTH)
        ).show()
    }

    fun pickTime(current: Long, onPicked: (Long) -> Unit) {
        val calendar = Calendar.getInstance().apply { timeInMillis = current }
        TimePickerDialog(
            context,
            { _, hour, minute ->
                calendar.set(Calendar.HOUR_OF_DAY, hour)
                calendar.set(Calendar.MINUTE, minute)
                calendar.set(Calendar.SECOND, 0)
                calendar.set(Calendar.MILLISECOND, 0)
                onPicked(calendar.timeInMillis)
            },
            calendar.get(Calendar.HOUR_OF_DAY),
            calendar.get(Calendar.MINUTE),
            true
        ).show()
    }

    DeviceDialog(
        title = "EDIT SESSION",
        onDismiss = onDismiss,
        content = {
            Text("START", fontFamily = Exo2, fontWeight = FontWeight.Black, fontSize = 10.sp, color = DeviceMuted)
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                DevicePickerButton(
                    text = dateFormat.format(Date(startMillis)),
                    modifier = Modifier.weight(1f),
                    onClick = { pickDate(startMillis) { startMillis = it } }
                )
                DevicePickerButton(
                    text = timeFormat.format(Date(startMillis)),
                    modifier = Modifier.weight(1f),
                    onClick = { pickTime(startMillis) { startMillis = it } }
                )
            }
            Spacer(Modifier.height(AppPadding))
            Text("END", fontFamily = Exo2, fontWeight = FontWeight.Black, fontSize = 10.sp, color = DeviceMuted)
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                DevicePickerButton(
                    text = if (endMillis == null) "ONGOING" else dateFormat.format(Date(endMillis!!)),
                    modifier = Modifier.weight(1f),
                    onClick = {
                        val value = endMillis ?: System.currentTimeMillis()
                        pickDate(value) { endMillis = it }
                    }
                )
                DevicePickerButton(
                    text = if (endMillis == null) "SET TIME" else timeFormat.format(Date(endMillis!!)),
                    modifier = Modifier.weight(1f),
                    onClick = {
                        val value = endMillis ?: System.currentTimeMillis()
                        pickTime(value) { endMillis = it }
                    }
                )
            }
            TextButton(onClick = { endMillis = null }) {
                Text("MARK AS ONGOING", fontFamily = Exo2, fontWeight = FontWeight.Black, fontSize = 10.sp, color = DeviceOrange)
            }
            validationError?.let {
                Text(it, fontFamily = Exo2, fontWeight = FontWeight.Normal, fontSize = 10.sp, color = DeviceRed)
            }
        },
        confirmText = "SAVE",
        onConfirm = {
            if (endMillis != null && endMillis!! < startMillis) {
                validationError = "END MUST BE AFTER START"
            } else {
                onSave(session.copy(startEpochMillis = startMillis, endEpochMillis = endMillis))
            }
        },
        dismissText = "CANCEL",
        onDismissAction = onDismiss
    )
}

@Composable
private fun DevicePickerButton(text: String, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier = modifier
            .height(40.dp)
            .clip(DeviceShape)
            .border(1.dp, BorderLevel3, DeviceShape)
            .background(PanelLevel3)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(text, fontFamily = Exo2, fontWeight = FontWeight.Black, fontSize = 10.sp, color = DevicePrimary)
    }
}

@Composable
private fun DeviceTextDialog(
    title: String,
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    DeviceDialog(
        title = title,
        onDismiss = onDismiss,
        content = { DeviceField(value, onValueChange, label) },
        confirmText = "CREATE",
        onConfirm = onConfirm,
        dismissText = "CANCEL",
        onDismissAction = onDismiss
    )
}

@Composable
private fun ConfirmDialog(
    title: String,
    message: String,
    confirmText: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    destructive: Boolean = false
) {
    DeviceDialog(
        title = title,
        onDismiss = onDismiss,
        content = {
            Text(message, fontFamily = Exo2, fontWeight = FontWeight.Normal, fontSize = 12.sp, color = DeviceText)
        },
        confirmText = confirmText,
        onConfirm = onConfirm,
        dismissText = "CANCEL",
        onDismissAction = onDismiss,
        destructive = destructive
    )
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
        colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
            focusedBorderColor = BorderLevel1,
            unfocusedBorderColor = BorderLevel2,
            focusedLabelColor = DevicePrimary,
            unfocusedLabelColor = DeviceMuted,
            cursorColor = DevicePrimary
        )
    )
}

@Composable
private fun DeviceTextAction(text: String, onClick: () -> Unit, destructive: Boolean = false) {
    TextButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().clip(DeviceShape),
        shape = DeviceShape,
        colors = ButtonDefaults.textButtonColors(contentColor = if (destructive) DeviceRed else DevicePrimary)
    ) {
        Text(text, fontFamily = Exo2, fontWeight = FontWeight.Black, fontSize = 12.sp)
    }
}

@Composable
private fun DeviceOutlineAction(text: String, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = DeviceShape,
        border = androidx.compose.foundation.BorderStroke(1.dp, BorderLevel3),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = DevicePrimary)
    ) {
        Text(text, fontFamily = Exo2, fontWeight = FontWeight.Black, fontSize = 12.sp)
    }
}

@Composable
private fun DeviceDialog(
    title: String,
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
    confirmText: String,
    onConfirm: () -> Unit,
    dismissText: String? = null,
    onDismissAction: (() -> Unit)? = null,
    destructive: Boolean = false
) {
    AlertDialog(
        modifier = Modifier.clip(DeviceShape),
        shape = DeviceShape,
        containerColor = PanelLevel2,
        tonalElevation = 0.dp,
        onDismissRequest = onDismiss,
        title = { Text(title, fontFamily = Exo2, fontWeight = FontWeight.Black, color = DevicePrimary) },
        text = { Column(content = content) },
        confirmButton = { DeviceTextAction(confirmText, onConfirm, destructive) },
        dismissButton = if (dismissText == null) null else {
            { DeviceTextAction(dismissText, onDismissAction ?: onDismiss) }
        }
    )
}

private fun formatDuration(milliseconds: Long): String {
    val minutes = milliseconds.coerceAtLeast(0L) / 60_000
    val hours = minutes / 60
    return if (hours > 0) "%dh %02dm".format(hours, minutes % 60) else "%dm".format(minutes)
}
