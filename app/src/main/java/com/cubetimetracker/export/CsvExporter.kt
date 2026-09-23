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
 * Esporta lo storico sessioni in un CSV locale, condivisibile via
 * il normale Intent.ACTION_SEND di Android (email, Drive personale
 * dell'utente, ecc.) — sempre per scelta esplicita dell'utente,
 * mai in automatico e mai verso un server dell'app.
 */
object CsvExporter {

    fun export(context: Context, sessions: List<TimeSession>, projects: Map<Long, Project>): Uri? {
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ITALY)
        val file = File(context.cacheDir, "cube_time_export.csv")

        file.bufferedWriter().use { writer ->
            writer.write("progetto,inizio,fine,durata_minuti\n")
            sessions.forEach { s ->
                val projectName = projects[s.projectId]?.name ?: "Sconosciuto"
                val start = fmt.format(Date(s.startEpochMillis))
                val end = s.endEpochMillis?.let { fmt.format(Date(it)) } ?: "in corso"
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
