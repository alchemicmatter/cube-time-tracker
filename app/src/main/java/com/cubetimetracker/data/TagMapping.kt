package com.cubetimetracker.data

import androidx.room.Entity
import androidx.room.PrimaryKey

// Associa l'UID fisico di un tag NFC (qualsiasi tag, incluse bobine
// filamento riciclate) a un progetto. Non si scrive mai sul tag:
// si legge solo il suo identificativo di fabbrica.
@Entity(tableName = "tag_mappings")
data class TagMapping(
    @PrimaryKey val tagUid: String,
    val projectId: Long,
    val label: String = ""
)
