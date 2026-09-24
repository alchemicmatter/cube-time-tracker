package com.cubetimetracker.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface ProjectDao {
    @Query("SELECT * FROM projects WHERE archived = 0 ORDER BY name")
    fun getActiveProjects(): Flow<List<Project>>

    @Query("SELECT * FROM projects WHERE archived = 1 ORDER BY name")
    fun getArchivedProjects(): Flow<List<Project>>

    @Insert
    suspend fun insert(project: Project): Long

    @Update
    suspend fun update(project: Project)

    @Query("SELECT * FROM projects WHERE id = :id")
    suspend fun getById(id: Long): Project?

    @Query("SELECT * FROM projects WHERE id = :id")
    fun observeById(id: Long): Flow<Project?>

    @Query("DELETE FROM projects WHERE id = :id")
    suspend fun deleteById(id: Long)
}
