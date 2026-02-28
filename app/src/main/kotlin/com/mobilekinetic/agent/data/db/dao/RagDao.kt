package com.mobilekinetic.agent.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.mobilekinetic.agent.data.db.entity.RagDocumentEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface RagDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDocument(document: RagDocumentEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDocuments(documents: List<RagDocumentEntity>)

    @Update
    suspend fun updateDocument(document: RagDocumentEntity)

    @Query("SELECT * FROM rag_documents ORDER BY updatedAt DESC")
    fun getAllDocuments(): Flow<List<RagDocumentEntity>>

    @Query("SELECT * FROM rag_documents WHERE id = :id")
    suspend fun getDocument(id: String): RagDocumentEntity?

    @Query("SELECT * FROM rag_documents WHERE category = :category ORDER BY updatedAt DESC")
    fun getDocumentsByCategory(category: String): Flow<List<RagDocumentEntity>>

    @Query("SELECT * FROM rag_documents WHERE category = :category ORDER BY updatedAt DESC")
    suspend fun getDocumentsByCategoryOnce(category: String): List<RagDocumentEntity>

    @Query("SELECT * FROM rag_documents")
    suspend fun getAllDocumentsForSearch(): List<RagDocumentEntity>

    @Query("SELECT * FROM rag_documents WHERE category IN (:categories)")
    suspend fun getDocumentsForSearch(categories: List<String>): List<RagDocumentEntity>

    @Query("DELETE FROM rag_documents WHERE id = :id")
    suspend fun deleteDocument(id: String)

    @Query("DELETE FROM rag_documents WHERE category = :category")
    suspend fun deleteByCategory(category: String)

    @Query("SELECT COUNT(*) FROM rag_documents")
    suspend fun getDocumentCount(): Int

    @Query("SELECT COUNT(*) FROM rag_documents WHERE category = :category")
    suspend fun getDocumentCountByCategory(category: String): Int

    @Query("SELECT * FROM rag_documents WHERE embeddingVersion < :targetVersion")
    suspend fun getDocumentsNeedingMigration(targetVersion: Int): List<RagDocumentEntity>

    @Query("UPDATE rag_documents SET embedding = :embedding, embeddingVersion = :embeddingVersion, embeddingDim = :embeddingDim WHERE id = :id")
    suspend fun updateEmbedding(id: String, embedding: ByteArray, embeddingVersion: Int, embeddingDim: Int)

    @Query("SELECT * FROM rag_documents WHERE LENGTH(embedding) < 4 OR embeddingDim != :targetDim")
    suspend fun getDocumentsNeedingReindex(targetDim: Int): List<RagDocumentEntity>

    @Query("SELECT COUNT(*) FROM rag_documents WHERE LENGTH(embedding) < 4 OR embeddingDim != :targetDim")
    suspend fun getReindexCount(targetDim: Int): Int
}
