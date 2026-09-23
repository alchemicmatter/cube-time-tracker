package com.cubetimetracker

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.cubetimetracker.data.AppDatabase
import com.cubetimetracker.data.Project
import com.cubetimetracker.data.TagMapping
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class TagSetupViewModel(application: Application) : AndroidViewModel(application) {

    private val db = AppDatabase.getInstance(application)

    val projects: StateFlow<List<Project>> = db.projectDao()
        .getActiveProjects()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun assignTag(uid: String, projectId: Long) {
        viewModelScope.launch {
            db.tagMappingDao().upsert(TagMapping(tagUid = uid, projectId = projectId))
        }
    }

    fun createProjectAndAssign(uid: String, name: String) {
        viewModelScope.launch {
            val newProject = Project(name = name)
            val newId = db.projectDao().insert(newProject)
            db.tagMappingDao().upsert(TagMapping(tagUid = uid, projectId = newId))
        }
    }
}
