package com.cubetimetracker

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

@Composable
fun TimerDisplay(
    startTimeMillis: Long?,
    modifier: Modifier = Modifier
) {
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
