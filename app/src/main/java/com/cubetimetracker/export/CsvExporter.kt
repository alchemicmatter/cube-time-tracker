package com.cubetimetracker.export

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.cubetimetracker.data.Project
import com.cubetimetracker.data.TimeSession
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * Exports the session history to a local CSV file, shareable via
 * Android's standard Intent.ACTION_SEND (email, the user's own
 * cloud drive, etc.) — always by explicit user choice, never
 * automatically and never to an app-owned server.
 */
object CsvExporter {

    fun export(context: Context, sessions: List<TimeSession>, projects: Map<Long, Project>): Uri? {
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        val file = File(context.cacheDir, "cube_time_export.csv")

        file.bufferedWriter().use { writer ->
            writer.write("project,start,end,duration_minutes\n")
            sessions.forEach { s ->
                val projectName = projects[s.projectId]?.name ?: "Unknown"
                val start = fmt.format(Date(s.startEpochMillis))
                val end = s.endEpochMillis?.let { fmt.format(Date(it)) } ?: "ongoing"
                val durationMin = s.endEpochMillis?.let { (it - s.startEpochMillis) / 60000 } ?: 0
                writer.write("\"$projectName\",$start,$end,$durationMin\n")
            }
        }

        return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }

    fun shareIntent(context: Context, uri: Uri): Intent =
        Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
}
