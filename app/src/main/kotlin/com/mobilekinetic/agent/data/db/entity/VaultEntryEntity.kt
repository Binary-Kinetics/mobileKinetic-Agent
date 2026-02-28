package com.mobilekinetic.agent.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "vault_entries", indices = [Index(value = ["name"], unique = true)])
data class VaultEntryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val encryptedValue: ByteArray,
    val iv: ByteArray,
    val description: String? = null,
    val createdAt: Long,
    val updatedAt: Long
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is VaultEntryEntity) return false
        return id == other.id &&
                name == other.name &&
                encryptedValue.contentEquals(other.encryptedValue) &&
                iv.contentEquals(other.iv) &&
                description == other.description &&
                createdAt == other.createdAt &&
                updatedAt == other.updatedAt
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + name.hashCode()
        result = 31 * result + encryptedValue.contentHashCode()
        result = 31 * result + iv.contentHashCode()
        result = 31 * result + (description?.hashCode() ?: 0)
        result = 31 * result + createdAt.hashCode()
        result = 31 * result + updatedAt.hashCode()
        return result
    }
}
