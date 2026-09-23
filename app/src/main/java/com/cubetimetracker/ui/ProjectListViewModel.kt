package com.cubetimetracker

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.cubetimetracker.data.AppDatabase
import com.cubetimetracker.data.Project
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ProjectListViewModel(application: Application) : AndroidViewModel(application) {

    private val db = AppDatabase.getInstance(application)

    val projects: StateFlow<List<Project>> = db.projectDao()
        .getActiveProjects()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun createProject(name: String) {
        viewModelScope.launch {
            db.projectDao().insert(Project(name = name))
        }
    }
}
