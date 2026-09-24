package com.cubetimetracker.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface TagMappingDao {
    @Query("SELECT * FROM tag_mappings WHERE tagUid = :uid LIMIT 1")
    suspend fun findByUid(uid: String): TagMapping?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(mapping: TagMapping)

    @Query("SELECT * FROM tag_mappings")
    fun getAll(): Flow<List<TagMapping>>

    @Query("SELECT * FROM tag_mappings WHERE projectId = :projectId")
    fun getForProject(projectId: Long): Flow<List<TagMapping>>

    @Query("DELETE FROM tag_mappings WHERE tagUid = :uid")
    suspend fun delete(uid: String)

    @Query("DELETE FROM tag_mappings WHERE projectId = :projectId")
    suspend fun deleteForProject(projectId: Long)
}
