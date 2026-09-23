package com.cubetimetracker.data

import androidx.room.Entity
import androidx.room.PrimaryKey

// Maps the physical UID of an NFC tag (any tag, including recycled
// filament spool chips) to a project. The tag is never written to:
// only its factory-set identifier is read.
@Entity(tableName = "tag_mappings")
data class TagMapping(
    @PrimaryKey val tagUid: String,
    val projectId: Long,
    val label: String = ""
)
