package com.mobilekinetic.agent.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.mobilekinetic.agent.data.db.entity.VaultEntryEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface VaultDao {
    @Query("SELECT * FROM vault_entries ORDER BY updatedAt DESC")
    fun getAll(): Flow<List<VaultEntryEntity>>

    @Query("SELECT name FROM vault_entries")
    fun getAllNames(): Flow<List<String>>

    @Query("SELECT * FROM vault_entries WHERE name = :name LIMIT 1")
    suspend fun getByName(name: String): VaultEntryEntity?

    @Upsert
    suspend fun upsert(entry: VaultEntryEntity)

    @Query("DELETE FROM vault_entries WHERE name = :name")
    suspend fun deleteByName(name: String)

    @Query("SELECT * FROM vault_entries ORDER BY name ASC")
    suspend fun getAllSync(): List<VaultEntryEntity>

    @Query("SELECT COUNT(*) FROM vault_entries")
    suspend fun getCount(): Int
}
