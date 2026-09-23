package com.cubetimetracker.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface TimeSessionDao {
    @Query("SELECT * FROM time_sessions WHERE endEpochMillis IS NULL LIMIT 1")
    suspend fun getOpenSession(): TimeSession?

    @Insert
    suspend fun insert(session: TimeSession): Long

    @Query("UPDATE time_sessions SET endEpochMillis = :endMillis WHERE id = :id")
    suspend fun closeSession(id: Long, endMillis: Long)

    @Query("SELECT * FROM time_sessions ORDER BY startEpochMillis DESC")
    fun getAllSessions(): Flow<List<TimeSession>>

    @Query("""
        SELECT * FROM time_sessions
        WHERE startEpochMillis BETWEEN :fromMillis AND :toMillis
        ORDER BY startEpochMillis DESC
    """)
    suspend fun getSessionsInRange(fromMillis: Long, toMillis: Long): List<TimeSession>
}
