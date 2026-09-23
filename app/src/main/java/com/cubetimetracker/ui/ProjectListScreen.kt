package com.cubetimetracker

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.cubetimetracker.data.Project

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectListScreen(
    onNavigateBack: () -> Unit,
    onProjectSelected: (Long) -> Unit,
    onCreateProject: (String) -> Unit
) {
    val viewModel: ProjectListViewModel = viewModel()
    val projects by viewModel.projects.collectAsState(initial = emptyList())
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
                },
                actions = {
                    IconButton(onClick = { showCreateDialog = true }) {
                        Icon(Icons.Default.Add, contentDescription = "New project")
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
                ProjectItem(
                    project = project,
                    onClick = { onProjectSelected(project.id) }
                )
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
fun ProjectItem(
    project: Project,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
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
            if (project.archived) {
                Text(
                    text = "(archived)",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                )
            }
        }
    }
}
