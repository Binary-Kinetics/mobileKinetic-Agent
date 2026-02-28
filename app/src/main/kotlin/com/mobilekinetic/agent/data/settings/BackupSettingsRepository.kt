package com.mobilekinetic.agent.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * DataStore-backed repository for backup configuration.
 *
 * Part 1 (local rolling) is handled by [HomeBackupWorker] with no user config needed.
 * Part 2 (remote nightly) is user-configurable: destination folder, schedule time.
 */
class BackupSettingsRepository(private val dataStore: DataStore<Preferences>) {

    // ── Preference Keys ────────────────────────────────────────────────────

    private object Keys {
        val REMOTE_BACKUP_ENABLED      = booleanPreferencesKey("remote_backup_enabled")
        val REMOTE_BACKUP_URI          = stringPreferencesKey("remote_backup_uri")
        val REMOTE_BACKUP_DISPLAY_NAME = stringPreferencesKey("remote_backup_display_name")
        val REMOTE_BACKUP_HOUR         = intPreferencesKey("remote_backup_hour")
        val REMOTE_BACKUP_MINUTE       = intPreferencesKey("remote_backup_minute")
        val LAST_REMOTE_BACKUP_TIME    = longPreferencesKey("last_remote_backup_time")
        val LAST_REMOTE_BACKUP_RESULT  = stringPreferencesKey("last_remote_backup_result")

        // SMB / NAS backup
        val SMB_BACKUP_ENABLED   = booleanPreferencesKey("smb_backup_enabled")
        val SMB_HOST             = stringPreferencesKey("smb_host")
        val SMB_SHARE            = stringPreferencesKey("smb_share")
        val SMB_PATH             = stringPreferencesKey("smb_path")
        val SMB_USERNAME         = stringPreferencesKey("smb_username")
        val SMB_PASSWORD         = stringPreferencesKey("smb_password")
        val LAST_SMB_BACKUP_TIME   = longPreferencesKey("last_smb_backup_time")
        val LAST_SMB_BACKUP_RESULT = stringPreferencesKey("last_smb_backup_result")
    }

    // ── Flow Properties (read) ─────────────────────────────────────────────

    val remoteBackupEnabled: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[Keys.REMOTE_BACKUP_ENABLED] ?: false
    }

    val remoteBackupUri: Flow<String> = dataStore.data.map { prefs ->
        prefs[Keys.REMOTE_BACKUP_URI] ?: ""
    }

    val remoteBackupDisplayName: Flow<String> = dataStore.data.map { prefs ->
        prefs[Keys.REMOTE_BACKUP_DISPLAY_NAME] ?: "Not set"
    }

    val remoteBackupHour: Flow<Int> = dataStore.data.map { prefs ->
        prefs[Keys.REMOTE_BACKUP_HOUR] ?: 2  // default 2 AM
    }

    val remoteBackupMinute: Flow<Int> = dataStore.data.map { prefs ->
        prefs[Keys.REMOTE_BACKUP_MINUTE] ?: 0
    }

    val lastRemoteBackupTime: Flow<Long> = dataStore.data.map { prefs ->
        prefs[Keys.LAST_REMOTE_BACKUP_TIME] ?: 0L
    }

    val lastRemoteBackupResult: Flow<String> = dataStore.data.map { prefs ->
        prefs[Keys.LAST_REMOTE_BACKUP_RESULT] ?: ""
    }

    // ── SMB Flow Properties ────────────────────────────────────────────────

    val smbBackupEnabled: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[Keys.SMB_BACKUP_ENABLED] ?: false
    }

    val smbHost: Flow<String> = dataStore.data.map { prefs ->
        prefs[Keys.SMB_HOST] ?: ""
    }

    val smbShare: Flow<String> = dataStore.data.map { prefs ->
        prefs[Keys.SMB_SHARE] ?: ""
    }

    val smbPath: Flow<String> = dataStore.data.map { prefs ->
        prefs[Keys.SMB_PATH] ?: "mK:a_backups"
    }

    val smbUsername: Flow<String> = dataStore.data.map { prefs ->
        prefs[Keys.SMB_USERNAME] ?: ""
    }

    val smbPassword: Flow<String> = dataStore.data.map { prefs ->
        prefs[Keys.SMB_PASSWORD] ?: ""
    }

    val lastSmbBackupTime: Flow<Long> = dataStore.data.map { prefs ->
        prefs[Keys.LAST_SMB_BACKUP_TIME] ?: 0L
    }

    val lastSmbBackupResult: Flow<String> = dataStore.data.map { prefs ->
        prefs[Keys.LAST_SMB_BACKUP_RESULT] ?: ""
    }

    // ── Suspend Setter Functions (write) ───────────────────────────────────

    suspend fun setRemoteBackupEnabled(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[Keys.REMOTE_BACKUP_ENABLED] = enabled }
    }

    suspend fun setRemoteBackupUri(uri: String) {
        dataStore.edit { prefs -> prefs[Keys.REMOTE_BACKUP_URI] = uri }
    }

    suspend fun setRemoteBackupDisplayName(name: String) {
        dataStore.edit { prefs -> prefs[Keys.REMOTE_BACKUP_DISPLAY_NAME] = name }
    }

    suspend fun setRemoteBackupHour(hour: Int) {
        dataStore.edit { prefs -> prefs[Keys.REMOTE_BACKUP_HOUR] = hour }
    }

    suspend fun setRemoteBackupMinute(minute: Int) {
        dataStore.edit { prefs -> prefs[Keys.REMOTE_BACKUP_MINUTE] = minute }
    }

    suspend fun setLastRemoteBackupTime(time: Long) {
        dataStore.edit { prefs -> prefs[Keys.LAST_REMOTE_BACKUP_TIME] = time }
    }

    suspend fun setLastRemoteBackupResult(result: String) {
        dataStore.edit { prefs -> prefs[Keys.LAST_REMOTE_BACKUP_RESULT] = result }
    }

    // ── SMB Setters ────────────────────────────────────────────────────────

    suspend fun setSmbBackupEnabled(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[Keys.SMB_BACKUP_ENABLED] = enabled }
    }

    suspend fun setSmbHost(host: String) {
        dataStore.edit { prefs -> prefs[Keys.SMB_HOST] = host }
    }

    suspend fun setSmbShare(share: String) {
        dataStore.edit { prefs -> prefs[Keys.SMB_SHARE] = share }
    }

    suspend fun setSmbPath(path: String) {
        dataStore.edit { prefs -> prefs[Keys.SMB_PATH] = path }
    }

    suspend fun setSmbUsername(username: String) {
        dataStore.edit { prefs -> prefs[Keys.SMB_USERNAME] = username }
    }

    suspend fun setSmbPassword(password: String) {
        dataStore.edit { prefs -> prefs[Keys.SMB_PASSWORD] = password }
    }

    suspend fun setLastSmbBackupTime(time: Long) {
        dataStore.edit { prefs -> prefs[Keys.LAST_SMB_BACKUP_TIME] = time }
    }

    suspend fun setLastSmbBackupResult(result: String) {
        dataStore.edit { prefs -> prefs[Keys.LAST_SMB_BACKUP_RESULT] = result }
    }
}
