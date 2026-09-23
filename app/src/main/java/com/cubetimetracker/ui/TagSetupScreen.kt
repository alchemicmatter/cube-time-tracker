package com.cubetimetracker

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.cubetimetracker.data.Project

@Composable
fun TagSetupScreen(
    tagUid: String,
    projects: List<Project>,
    onProjectSelected: (Long) -> Unit,
    onCreateProjectAndSelect: (String) -> Unit,
    onSkip: () -> Unit
) {
    var showCreateDialog by remember { mutableStateOf(false) }
    var newProjectName by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "Unknown tag detected",
            style = MaterialTheme.typography.headlineSmall
        )
        Text(
            text = "UID: $tagUid",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = "Assign this tag to a project:",
            style = MaterialTheme.typography.bodyLarge
        )

        projects.forEach { project ->
            Button(
                onClick = { onProjectSelected(project.id) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(project.name)
            }
        }

        OutlinedButton(
            onClick = { showCreateDialog = true },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Create new project and assign")
        }

        Spacer(Modifier.height(16.dp))

        TextButton(onClick = onSkip) {
            Text("Skip (tag will remain unassigned)")
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
                            onCreateProjectAndSelect(newProjectName)
                            showCreateDialog = false
                        }
                    },
                    enabled = newProjectName.isNotBlank()
                ) {
                    Text("Create & assign")
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
