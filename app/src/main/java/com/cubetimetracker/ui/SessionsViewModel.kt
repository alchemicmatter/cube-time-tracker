package com.cubetimetracker

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.cubetimetracker.data.AppDatabase
import com.cubetimetracker.data.Project
import com.cubetimetracker.data.TimeSession
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

class SessionsViewModel(application: Application) : AndroidViewModel(application) {

    private val db = AppDatabase.getInstance(application)

    fun getSessionsForProject(projectId: Long): StateFlow<List<TimeSession>> {
        return db.timeSessionDao()
            .getAllSessions()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    }

    fun getProject(projectId: Long): StateFlow<Project?> {
        return db.projectDao()
            .getActiveProjects()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
            .let { flow ->
                flow.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
                    .let { stateFlow ->
                        object : StateFlow<Project?> {
                            override val replayCache: List<Project?> get() = listOf(stateFlow.value.find { it.id == projectId })
                            override suspend fun collect(collector: kotlinx.coroutines.flow.FlowCollector<in Project?>) {
                                stateFlow.collect { list -> collector.emit(list.find { it.id == projectId }) }
                            }
                            override val value: Project? get() = stateFlow.value.find { it.id == projectId }
                        }
                    }
            }
    }
}
