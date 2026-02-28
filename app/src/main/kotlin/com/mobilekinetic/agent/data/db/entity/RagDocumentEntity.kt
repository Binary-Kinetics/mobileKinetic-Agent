package com.mobilekinetic.agent.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "rag_documents")
data class RagDocumentEntity(
    @PrimaryKey val id: String,
    val text: String,
    val category: String,
    val embedding: ByteArray,
    val metadata: String = "{}",
    val embeddingVersion: Int = 1,
    val embeddingDim: Int = 384,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is RagDocumentEntity) return false
        return id == other.id
    }

    override fun hashCode(): Int = id.hashCode()
}
