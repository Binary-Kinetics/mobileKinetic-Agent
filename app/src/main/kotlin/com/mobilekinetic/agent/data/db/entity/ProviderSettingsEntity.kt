package com.mobilekinetic.agent.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "provider_settings")
data class ProviderSettingsEntity(
    @PrimaryKey
    val id: Int = 1,
    val activeProviderId: String = "claude_cli",
    val updatedAt: Long
)
