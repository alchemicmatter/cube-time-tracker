package com.cubetimetracker.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface TimeSessionDao {
    @Query("SELECT * FROM time_sessions WHERE endEpochMillis IS NULL LIMIT 1")
    suspend fun getOpenSession(): TimeSession?

    @Insert
    suspend fun insert(session: TimeSession): Long

    @Update
    suspend fun update(session: TimeSession)

    @Query("UPDATE time_sessions SET endEpochMillis = :endMillis WHERE id = :id")
    suspend fun closeSession(id: Long, endMillis: Long)

    @Query("DELETE FROM time_sessions WHERE id = :id")
    suspend fun deleteSession(id: Long)

    @Query("DELETE FROM time_sessions WHERE projectId = :projectId")
    suspend fun deleteForProject(projectId: Long)

    @Query("SELECT * FROM time_sessions ORDER BY startEpochMillis DESC")
    fun getAllSessions(): Flow<List<TimeSession>>

    @Query("SELECT * FROM time_sessions WHERE projectId = :projectId ORDER BY startEpochMillis DESC")
    fun getSessionsForProject(projectId: Long): Flow<List<TimeSession>>
}
