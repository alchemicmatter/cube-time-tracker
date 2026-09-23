package com.cubetimetracker.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.cubetimetracker.data.AppDatabase
import com.cubetimetracker.data.Project
import com.cubetimetracker.data.TimeSession
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted

class SessionsViewModel(application: Application) : AndroidViewModel(application) {

    private val db = AppDatabase.getInstance(application)

    fun sessionsForProject(projectId: Long): Flow<List<TimeSession>> =
        db.timeSessionDao().getSessionsForProject(projectId)

    fun project(projectId: Long): Flow<Project?> =
        db.projectDao().observeById(projectId)

    suspend fun deleteSession(sessionId: Long) {
        db.timeSessionDao().deleteSession(sessionId)
    }

    suspend fun updateSession(session: TimeSession) {
        db.timeSessionDao().update(session)
    }
}
