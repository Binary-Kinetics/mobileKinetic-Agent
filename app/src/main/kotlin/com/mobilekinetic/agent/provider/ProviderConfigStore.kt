package com.mobilekinetic.agent.provider

import com.mobilekinetic.agent.data.db.dao.ProviderDao
import com.mobilekinetic.agent.data.db.entity.ProviderConfigEntity
import com.mobilekinetic.agent.data.db.entity.ProviderSettingsEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Centralized store for AI provider configuration.
 * Persists provider configs and active provider selection via Room.
 */
@Singleton
class ProviderConfigStore @Inject constructor(
    private val providerDao: ProviderDao
) {
    /**
     * Observe the active provider ID.
     */
    fun observeActiveProviderId(): Flow<String> =
        providerDao.observeSettings().map { it?.activeProviderId ?: "claude_cli" }

    /**
     * Get the active provider ID.
     */
    suspend fun getActiveProviderId(): String =
        providerDao.getSettings()?.activeProviderId ?: "claude_cli"

    /**
     * Set the active provider.
     */
    suspend fun setActiveProvider(providerId: String) {
        // Ensure settings row exists
        val existing = providerDao.getSettings()
        if (existing == null) {
            providerDao.upsertSettings(
                ProviderSettingsEntity(
                    activeProviderId = providerId,
                    updatedAt = System.currentTimeMillis()
                )
            )
        } else {
            providerDao.setActiveProvider(providerId)
        }
    }

    /**
     * Get all config values for a provider.
     */
    suspend fun getProviderConfig(providerId: String): Map<String, String> =
        providerDao.getConfigsForProvider(providerId)
            .associate { it.key to it.value }

    /**
     * Get a single config value.
     */
    suspend fun getConfigValue(providerId: String, key: String): String? =
        providerDao.getConfig(providerId, key)?.value

    /**
     * Set a single config value.
     */
    suspend fun setConfigValue(providerId: String, key: String, value: String) {
        providerDao.upsertConfig(
            ProviderConfigEntity(
                key = "${providerId}_$key",
                providerId = providerId,
                value = value,
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    /**
     * Set multiple config values for a provider.
     */
    suspend fun setProviderConfig(providerId: String, config: Map<String, String>) {
        val now = System.currentTimeMillis()
        val entities = config.map { (key, value) ->
            ProviderConfigEntity(
                key = "${providerId}_$key",
                providerId = providerId,
                value = value,
                updatedAt = now
            )
        }
        providerDao.upsertConfigs(entities)
    }

    /**
     * Clear all config for a provider.
     */
    suspend fun clearProviderConfig(providerId: String) {
        providerDao.deleteConfigsForProvider(providerId)
    }
}
