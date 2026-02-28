package com.mobilekinetic.agent.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(tableName = "blacklist_rules")
data class BlacklistRuleEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val ruleType: String,  // KEYWORD, TOPIC, SENDER, APP_PACKAGE, PATTERN
    val value: String,
    val action: String = "REDACT",  // REDACT or BLOCK
    val isEnabled: Boolean = true,
    val description: String? = null,
    val matchFields: String = "ALL",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)
