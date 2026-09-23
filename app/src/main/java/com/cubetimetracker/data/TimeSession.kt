package com.cubetimetracker.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "time_sessions")
data class TimeSession(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val projectId: Long,
    val startEpochMillis: Long,
    val endEpochMillis: Long? = null // null = sessione ancora in corso
)
