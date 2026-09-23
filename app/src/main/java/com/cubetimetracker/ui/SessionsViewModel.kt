package com.cubetimetracker.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.cubetimetracker.ProjectSummary
import com.cubetimetracker.data.AppDatabase
import com.cubetimetracker.data.Project
import com.cubetimetracker.data.TimeSession
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

class SessionsViewModel(application: Application) : AndroidViewModel(application) {
    private val db = AppDatabase.getInstance(application)

    fun activeProjects(): Flow<List<Project>> = db.projectDao().getActiveProjects()

    fun sessionsForProject(projectId: Long): Flow<List<TimeSession>> =
        db.timeSessionDao().getSessionsForProject(projectId)

    fun project(projectId: Long): Flow<Project?> = db.projectDao().observeById(projectId)

    fun projectsWithTotals(): Flow<List<ProjectSummary>> = combine(
        db.projectDao().getActiveProjects(),
        db.timeSessionDao().getAllSessions()
    ) { projects, sessions ->
        val now = System.currentTimeMillis()
        projects.map { project ->
            val projectSessions = sessions.filter { it.projectId == project.id }
            ProjectSummary(
                project = project,
                totalMillis = projectSessions.sumOf { session ->
                    (session.endEpochMillis ?: now) - session.startEpochMillis
                },
                sessionCount = projectSessions.size
            )
        }
    }

    suspend fun deleteSession(sessionId: Long) {
        db.timeSessionDao().deleteSession(sessionId)
    }

    suspend fun updateSession(session: TimeSession) {
        db.timeSessionDao().update(session)
    }
}
