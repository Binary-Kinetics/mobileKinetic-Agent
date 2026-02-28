package com.mobilekinetic.agent.data.db.dao

import androidx.room.*
import com.mobilekinetic.agent.data.db.entity.ProviderConfigEntity
import com.mobilekinetic.agent.data.db.entity.ProviderSettingsEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ProviderDao {
    // Provider configs
    @Query("SELECT * FROM provider_configs WHERE providerId = :providerId")
    suspend fun getConfigsForProvider(providerId: String): List<ProviderConfigEntity>

    @Query("SELECT * FROM provider_configs WHERE providerId = :providerId AND `key` = :key")
    suspend fun getConfig(providerId: String, key: String): ProviderConfigEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertConfig(config: ProviderConfigEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertConfigs(configs: List<ProviderConfigEntity>)

    @Query("DELETE FROM provider_configs WHERE providerId = :providerId")
    suspend fun deleteConfigsForProvider(providerId: String)

    // Provider settings (singleton row)
    @Query("SELECT * FROM provider_settings WHERE id = 1")
    suspend fun getSettings(): ProviderSettingsEntity?

    @Query("SELECT * FROM provider_settings WHERE id = 1")
    fun observeSettings(): Flow<ProviderSettingsEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSettings(settings: ProviderSettingsEntity)

    @Query("UPDATE provider_settings SET activeProviderId = :providerId, updatedAt = :updatedAt WHERE id = 1")
    suspend fun setActiveProvider(providerId: String, updatedAt: Long = System.currentTimeMillis())
}
