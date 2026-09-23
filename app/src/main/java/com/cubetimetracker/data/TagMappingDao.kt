package com.cubetimetracker.data

import androidx.room.*

@Dao
interface TagMappingDao {
    @Query("SELECT * FROM tag_mappings WHERE tagUid = :uid LIMIT 1")
    suspend fun findByUid(uid: String): TagMapping?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(mapping: TagMapping)

    @Query("SELECT * FROM tag_mappings")
    suspend fun getAll(): List<TagMapping>

    @Query("DELETE FROM tag_mappings WHERE tagUid = :uid")
    suspend fun delete(uid: String)
}
