package com.mobilekinetic.agent.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * DataStore-backed repository for memory injection settings.
 *
 * Controls context injection pipeline behavior: which tiers are active,
 * maintenance (NapkinDab cleanup, backup), and classification (Haiku, auto-learn).
 */
class InjectionSettingsRepository(private val dataStore: DataStore<Preferences>) {

    // ── Preference Keys ────────────────────────────────────────────────────

    private object Keys {
        val CONTEXT_INJECTION_ENABLED = booleanPreferencesKey("context_injection_enabled")
        val TIER1_ENABLED             = booleanPreferencesKey("tier1_rag_enabled")
        val TIER2_ENABLED             = booleanPreferencesKey("tier2_semantic_enabled")
        val AUTO_LEARN_ENABLED        = booleanPreferencesKey("auto_learn_enabled")
        val NAPKIN_DAB_ENABLED        = booleanPreferencesKey("napkin_dab_enabled")
        val NAPKIN_DAB_THRESHOLD      = intPreferencesKey("napkin_dab_threshold")
        val BACKUP_ENABLED            = booleanPreferencesKey("backup_enabled")
        val HAIKU_CLASSIFICATION_ENABLED = booleanPreferencesKey("haiku_classification_enabled")
    }

    // ── Flow Properties (read) ─────────────────────────────────────────────

    val contextInjectionEnabled: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[Keys.CONTEXT_INJECTION_ENABLED] ?: true
    }

    val tier1Enabled: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[Keys.TIER1_ENABLED] ?: true
    }

    val tier2Enabled: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[Keys.TIER2_ENABLED] ?: true
    }

    val autoLearnEnabled: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[Keys.AUTO_LEARN_ENABLED] ?: true
    }

    val napkinDabEnabled: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[Keys.NAPKIN_DAB_ENABLED] ?: true
    }

    val napkinDabThreshold: Flow<Int> = dataStore.data.map { prefs ->
        prefs[Keys.NAPKIN_DAB_THRESHOLD] ?: 50
    }

    val backupEnabled: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[Keys.BACKUP_ENABLED] ?: true
    }

    val haikuClassificationEnabled: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[Keys.HAIKU_CLASSIFICATION_ENABLED] ?: true
    }

    // ── Suspend Setter Functions (write) ───────────────────────────────────

    suspend fun setContextInjectionEnabled(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[Keys.CONTEXT_INJECTION_ENABLED] = enabled }
    }

    suspend fun setTier1Enabled(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[Keys.TIER1_ENABLED] = enabled }
    }

    suspend fun setTier2Enabled(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[Keys.TIER2_ENABLED] = enabled }
    }

    suspend fun setAutoLearnEnabled(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[Keys.AUTO_LEARN_ENABLED] = enabled }
    }

    suspend fun setNapkinDabEnabled(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[Keys.NAPKIN_DAB_ENABLED] = enabled }
    }

    suspend fun setNapkinDabThreshold(threshold: Int) {
        dataStore.edit { prefs -> prefs[Keys.NAPKIN_DAB_THRESHOLD] = threshold }
    }

    suspend fun setBackupEnabled(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[Keys.BACKUP_ENABLED] = enabled }
    }

    suspend fun setHaikuClassificationEnabled(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[Keys.HAIKU_CLASSIFICATION_ENABLED] = enabled }
    }
}
