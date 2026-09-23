package com.cubetimetracker

import android.content.Intent
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.Build
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
import androidx.compose.material.icons.filled.Add
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
import com.cubetimetracker.data.*
import com.cubetimetracker.nfc.NfcHelper
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : ComponentActivity() {

    private lateinit var nfcHelper: NfcHelper
    private lateinit var db: AppDatabase
    private lateinit var vibrator: Vibrator

    private val activeProjectName = mutableStateOf<String?>(null)
    private val activeSessionStart = mutableStateOf<Long?>(null)
    private val lastEventMessage = mutableStateOf("Waiting for a tag...")
    private val currentScreen = mutableStateOf<String>("main")
    private val selectedProjectId = mutableStateOf<Long?>(null)
    private val projects = mutableStateOf<List<Project>>(emptyList())
    private val pendingTagUid = mutableStateOf<String?>(null)
    private val showNewProjectDialog = mutableStateOf(false)
    private val newProjectName = mutableStateOf("")

    companion object {
        private const val TAG = "CubeTimeTracker"
        val retroFont = FontFamily.Monospace
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
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Color(0xFF00FF00),
                    secondary = Color(0xFF00CC00),
                    background = Color(0xFF0A0A0A),
                    surface = Color(0xFF111111),
                    onPrimary = Color.Black,
                    onSecondary = Color.Black,
                    onBackground = Color(0xFF00FF00),
                    onSurface = Color(0xFF00FF00)
                )
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize().background(Color(0xFF0A0A0A)),
                    color = Color(0xFF0A0A0A)
                ) {
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
                                lifecycleScope.launch {
                                    db.projectDao().insert(Project(name = name))
                                    Log.i(TAG, "Created project: $name")
                                }
                            }
                        )
                        "reports" -> ReportsScreen(
                            projects = projects.value,
                            onNavigateBack = { currentScreen.value = "main" },
                            onProjectSelected = { projectId ->
                                selectedProjectId.value = projectId
                                currentScreen.value = "sessions"
                            }
                        )
                        "sessions" -> {
                            val projectId = selectedProjectId.value
                            if (projectId != null) {
                                SessionsListScreen(
                                    projectId = projectId,
                                    onNavigateBack = { currentScreen.value = "reports" },
                                    onDeleteSession = { sessionId ->
                                        lifecycleScope.launch {
                                            db.timeSessionDao().let { dao ->
                                                dao.getSessionsInRange(0, Long.MAX_VALUE)
                                                    .collect { sessions ->
                                                        sessions.find { it.id == sessionId }?.let {
                                                            dao.closeSession(it.id, it.endEpochMillis ?: System.currentTimeMillis())
                                                        }
                                                    }
                                            }
                                        }
                                    }
                                )
                            }
                        }
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
                                    lastEventMessage.value = "Tag assigned: ${project?.name}"
                                    Log.i(TAG, "Tag ${pendingTagUid.value} assigned to $projectId")
                                    pendingTagUid.value = null
                                    vibrateSuccess()
                                }
                            },
                            onCreateNewProject = { showNewProjectDialog.value = true }
                        )
                    }

                    if (showNewProjectDialog.value) {
                        AlertDialog(
                            containerColor = Color(0xFF111111),
                            titleContentColor = Color(0xFF00FF00),
                            textContentColor = Color(0xFF00FF00),
                            onDismissRequest = { showNewProjectDialog.value = false },
                            title = { Text("NEW PROJECT", fontFamily = retroFont, fontWeight = FontWeight.Bold) },
                            text = {
                                Column {
                                    OutlinedTextField(
                                        value = newProjectName.value,
                                        onValueChange = { newProjectName.value = it },
                                        label = { Text("NAME", fontFamily = retroFont, fontSize = 12.sp) },
                                        colors = OutlinedTextFieldDefaults.colors(
                                            focusedBorderColor = Color(0xFF00FF00),
                                            unfocusedBorderColor = Color(0xFF00FF00),
                                            focusedLabelColor = Color(0xFF00FF00),
                                            unfocusedLabelColor = Color(0xFF00AA00)
                                        ),
                                        textStyle = LocalTextStyle.current.copy(fontFamily = retroFont, color = Color(0xFF00FF00)),
                                        singleLine = true,
                                        modifier = Modifier.fillMaxWidth()
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
                                                lastEventMessage.value = "Created: ${newProject.name}"
                                                Log.i(TAG, "Created: ${newProject.name}")
                                                newProjectName.value = ""
                                                pendingTagUid.value = null
                                                showNewProjectDialog.value = false
                                                vibrateSuccess()
                                            }
                                        }
                                    },
                                    enabled = newProjectName.value.isNotBlank()
                                ) {
                                    Text("CREATE", fontFamily = retroFont, fontWeight = FontWeight.Bold)
                                }
                            },
                            dismissButton = {
                                TextButton(onClick = { showNewProjectDialog.value = false }) {
                                    Text("CANCEL", fontFamily = retroFont)
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
        val uid: String? = try {
            nfcHelper.extractUid(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Error extracting UID", e)
            lastEventMessage.value = "NFC error"
            return
        }

        if (uid == null) return

        lifecycleScope.launch {
            try {
                val mapping = db.tagMappingDao().findByUid(uid)

                if (mapping == null) {
                    pendingTagUid.value = uid
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
                        activeSessionStart.value = null
                        lastEventMessage.value = "STOPPED: ${project?.name}"
                        vibrateSuccess()
                    }
                    openSession != null -> {
                        db.timeSessionDao().closeSession(openSession.id, now)
                        db.timeSessionDao().insert(TimeSession(projectId = mapping.projectId, startEpochMillis = now))
                        activeProjectName.value = project?.name
                        activeSessionStart.value = now
                        lastEventMessage.value = "SWITCH: ${project?.name}"
                        vibrateSuccess()
                    }
                    else -> {
                        db.timeSessionDao().insert(TimeSession(projectId = mapping.projectId, startEpochMillis = now))
                        activeProjectName.value = project?.name
                        activeSessionStart.value = now
                        lastEventMessage.value = "STARTED: ${project?.name}"
                        vibrateSuccess()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error", e)
                lastEventMessage.value = "ERROR"
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
    activeSessionStart: Long?,
    statusMessage: String,
    onOpenProjects: () -> Unit,
    onViewReports: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp)
            .background(Color(0xFF0A0A0A)),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Column {
            Text(
                "CUBE TIME TRACKER",
                fontFamily = MainActivity.retroFont,
                fontWeight = FontWeight.Bold,
                fontSize = 20.sp,
                color = Color(0xFF00FF00)
            )
            Spacer(Modifier.height(20.dp))
            TimerDisplay(startTimeMillis = activeSessionStart)
            Spacer(Modifier.height(20.dp))
            Text(
                text = if (activeProjectName != null) "ACTIVE: $activeProjectName" else "NO TIMER",
                fontFamily = MainActivity.retroFont,
                fontSize = 14.sp,
                color = Color(0xFF00FF00)
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = statusMessage,
                fontFamily = MainActivity.retroFont,
                fontSize = 11.sp,
                color = Color(0xFF00AA00)
            )
        }

        if (!nfcAvailable || !nfcEnabled) {
            Text(
                text = if (!nfcAvailable) "NO NFC" else "NFC OFF",
                fontFamily = MainActivity.retroFont,
                fontSize = 12.sp,
                color = Color(0xFFFF0000)
            )
        }

        Column(
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            RetroButton(text = "PROJECTS", onClick = onOpenProjects)
            RetroButton(text = "REPORTS", onClick = onViewReports)
        }
    }
}

@Composable
fun RetroButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(48.dp)
            .border(1.dp, Color(0xFF00FF00))
            .background(Color.Transparent)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            fontFamily = MainActivity.retroFont,
            fontWeight = FontWeight.Bold,
            fontSize = 14.sp,
            color = Color(0xFF00FF00)
        )
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
        modifier = modifier
            .fillMaxWidth()
            .border(2.dp, Color(0xFF00FF00))
            .background(Color(0xFF111111))
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = if (isRunning) "RUNNING" else "STOPPED",
            fontFamily = MainActivity.retroFont,
            fontSize = 12.sp,
            color = if (isRunning) Color(0xFF00FF00) else Color(0xFF00AA00)
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = String.format("%02d:%02d:%02d", hours, minutes, seconds),
            fontFamily = MainActivity.retroFont,
            fontSize = 48.sp,
            fontWeight = FontWeight.Bold,
            color = if (isRunning) Color(0xFF00FF00) else Color(0xFF00AA00)
        )
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
        containerColor = Color(0xFF0A0A0A),
        topBar = {
            TopAppBar(
                title = { Text("PROJECTS", fontFamily = MainActivity.retroFont, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Color(0xFF00FF00))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF0A0A0A),
                    titleContentColor = Color(0xFF00FF00),
                    navigationIconContentColor = Color(0xFF00FF00)
                )
            )
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(projects, key = { it.id }) { project ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .border(1.dp, Color(0xFF00FF00))
                        .background(Color(0xFF111111))
                        .clickable { },
                    contentAlignment = Alignment.CenterStart
                ) {
                    Text(
                        text = project.name,
                        fontFamily = MainActivity.retroFont,
                        fontSize = 14.sp,
                        color = Color(0xFF00FF00),
                        modifier = Modifier.padding(start = 16.dp)
                    )
                }
            }

            item {
                RetroButton(text = "+ NEW PROJECT", onClick = { showCreateDialog = true })
            }
        }
    }

    if (showCreateDialog) {
        AlertDialog(
            containerColor = Color(0xFF111111),
            titleContentColor = Color(0xFF00FF00),
            textContentColor = Color(0xFF00FF00),
            onDismissRequest = { showCreateDialog = false },
            title = { Text("NEW PROJECT", fontFamily = MainActivity.retroFont, fontWeight = FontWeight.Bold) },
            text = {
                OutlinedTextField(
                    value = newProjectName,
                    onValueChange = { newProjectName = it },
                    label = { Text("NAME", fontFamily = MainActivity.retroFont, fontSize = 12.sp) },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color(0xFF00FF00),
                        unfocusedBorderColor = Color(0xFF00FF00),
                        focusedLabelColor = Color(0xFF00FF00),
                        unfocusedLabelColor = Color(0xFF00AA00)
                    ),
                    textStyle = LocalTextStyle.current.copy(fontFamily = MainActivity.retroFont, color = Color(0xFF00FF00)),
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
                    Text("CREATE", fontFamily = MainActivity.retroFont, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showCreateDialog = false }) {
                    Text("CANCEL", fontFamily = MainActivity.retroFont)
                }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReportsScreen(
    projects: List<Project>,
    onNavigateBack: () -> Unit,
    onProjectSelected: (Long) -> Unit
) {
    Scaffold(
        containerColor = Color(0xFF0A0A0A),
        topBar = {
            TopAppBar(
                title = { Text("REPORTS", fontFamily = MainActivity.retroFont, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Color(0xFF00FF00))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF0A0A0A),
                    titleContentColor = Color(0xFF00FF00),
                    navigationIconContentColor = Color(0xFF00FF00)
                )
            )
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(projects, key = { it.id }) { project ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .border(1.dp, Color(0xFF00FF00))
                        .background(Color(0xFF111111))
                        .clickable { onProjectSelected(project.id) },
                    contentAlignment = Alignment.CenterStart
                ) {
                    Text(
                        text = project.name,
                        fontFamily = MainActivity.retroFont,
                        fontSize = 14.sp,
                        color = Color(0xFF00FF00),
                        modifier = Modifier.padding(start = 16.dp)
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionsListScreen(
    projectId: Long,
    onNavigateBack: () -> Unit,
    onDeleteSession: (Long) -> Unit
) {
    val viewModel: SessionsViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
    val sessions by viewModel.getSessionsForProject(projectId).collectAsState(initial = emptyList())
    val project by viewModel.getProject(projectId).collectAsState(initial = null)
    val dateFormat = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US) }

    Scaffold(
        containerColor = Color(0xFF0A0A0A),
        topBar = {
            TopAppBar(
                title = { Text(project?.name ?: "SESSIONS", fontFamily = MainActivity.retroFont, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Color(0xFF00FF00))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF0A0A0A),
                    titleContentColor = Color(0xFF00FF00),
                    navigationIconContentColor = Color(0xFF00FF00)
                )
            )
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(sessions, key = { it.id }) { session ->
                val durationMin = session.endEpochMillis?.let { (it - session.startEpochMillis) / 60000 } ?: 0
                val startTime = dateFormat.format(Date(session.startEpochMillis))
                val endTime = session.endEpochMillis?.let { dateFormat.format(Date(it)) } ?: "ongoing"

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, Color(0xFF00FF00))
                        .background(Color(0xFF111111))
                        .padding(16.dp)
                ) {
                    Column {
                        Text(
                            text = "$startTime → $endTime",
                            fontFamily = MainActivity.retroFont,
                            fontSize = 11.sp,
                            color = Color(0xFF00AA00)
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "DURATION: ${durationMin} min",
                            fontFamily = MainActivity.retroFont,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF00FF00)
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(
                            horizontalArrangement = Arrangement.End,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            IconButton(
                                onClick = { onDeleteSession(session.id) },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(Icons.Default.Delete, contentDescription = "Delete", tint = Color(0xFFFF0000))
                            }
                        }
                    }
                }
            }
        }
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
        containerColor = Color(0xFF111111),
        titleContentColor = Color(0xFF00FF00),
        textContentColor = Color(0xFF00FF00),
        onDismissRequest = onDismiss,
        title = { Text("TAG DETECTED", fontFamily = MainActivity.retroFont, fontWeight = FontWeight.Bold) },
        text = {
            Column {
                Text("UID: $tagUid", fontFamily = MainActivity.retroFont, fontSize = 11.sp)
                Spacer(Modifier.height(16.dp))
                Text("ASSIGN TO:", fontFamily = MainActivity.retroFont, fontWeight = FontWeight.Bold, fontSize = 12.sp)
            }
        },
        confirmButton = {
            Column(
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                projects.forEach { project ->
                    TextButton(
                        onClick = { onProjectSelected(project.id) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(project.name, fontFamily = MainActivity.retroFont, fontSize = 12.sp)
                    }
                }
                OutlinedButton(
                    onClick = onCreateNewProject,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = Color(0xFF00FF00)
                    ),
                    border = ButtonDefaults.outlinedButtonBorder.copy(brush = androidx.compose.ui.graphics.SolidColor(Color(0xFF00FF00)))
                ) {
                    Text("+ NEW", fontFamily = MainActivity.retroFont, fontWeight = FontWeight.Bold)
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("SKIP", fontFamily = MainActivity.retroFont)
            }
        }
    )
}
