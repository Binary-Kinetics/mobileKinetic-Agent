package com.mobilekinetic.agent.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.mobilekinetic.agent.data.memory.BackupWorker
import com.mobilekinetic.agent.data.memory.SmbBackupTransport
import com.mobilekinetic.agent.data.settings.BackupSettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class BackupSettingsViewModel @Inject constructor(
    private val app: Application,
    private val repository: BackupSettingsRepository
) : AndroidViewModel(app) {

    // ── NAS State ────────────────────────────────────────────────────────────
    //
    // Text fields use local MutableStateFlows for instant UI feedback.
    // Changes are debounced (500ms) before persisting to DataStore.

    val smbBackupEnabled: StateFlow<Boolean> = repository.smbBackupEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val smbHost = MutableStateFlow("")
    val smbShare = MutableStateFlow("Share")
    val smbPath = MutableStateFlow("mK:a")
    val smbUsername = MutableStateFlow("")
    val smbPassword = MutableStateFlow("")

    /** True after a successful test connection -- shows the folder field. */
    val nasConnected = MutableStateFlow(false)

    val lastSmbBackupTime: StateFlow<Long> = repository.lastSmbBackupTime
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0L)

    val lastSmbBackupResult: StateFlow<String> = repository.lastSmbBackupResult
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

    val smbTestResult: MutableStateFlow<String> = MutableStateFlow("")

    // ── Legacy remote backup state (kept for WorkManager compatibility) ─────

    val remoteBackupEnabled: StateFlow<Boolean> = repository.remoteBackupEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val remoteBackupUri: StateFlow<String> = repository.remoteBackupUri
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

    val remoteBackupDisplayName: StateFlow<String> = repository.remoteBackupDisplayName
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "Not set")

    val remoteBackupHour: StateFlow<Int> = repository.remoteBackupHour
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 2)

    val remoteBackupMinute: StateFlow<Int> = repository.remoteBackupMinute
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val lastRemoteBackupTime: StateFlow<Long> = repository.lastRemoteBackupTime
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0L)

    val lastRemoteBackupResult: StateFlow<String> = repository.lastRemoteBackupResult
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

    init {
        // Seed local buffers from DataStore (one-time read)
        viewModelScope.launch {
            smbHost.value = repository.smbHost.first()
            smbShare.value = repository.smbShare.first().ifBlank { "Share" }
            smbPath.value = repository.smbPath.first().ifBlank { "mK:a" }
            smbUsername.value = repository.smbUsername.first()
            smbPassword.value = repository.smbPassword.first()
        }

        // Debounced persistence -- drop(1) skips the initial seed value
        @OptIn(FlowPreview::class)
        fun <T> MutableStateFlow<T>.persistDebounced(writer: suspend (T) -> Unit) {
            this.drop(1).debounce(500).onEach { writer(it) }.launchIn(viewModelScope)
        }

        smbHost.persistDebounced { repository.setSmbHost(it) }
        smbShare.persistDebounced { repository.setSmbShare(it) }
        smbPath.persistDebounced { repository.setSmbPath(it) }
        smbUsername.persistDebounced { repository.setSmbUsername(it) }
        smbPassword.persistDebounced { repository.setSmbPassword(it) }
    }

    fun setSmbBackupEnabled(enabled: Boolean) {
        viewModelScope.launch { repository.setSmbBackupEnabled(enabled) }
    }

    /**
     * Auto-formats the NAS address: strips smb://, \\, trailing slashes.
     * Accepts any format the user types and normalizes to just the IP/hostname.
     */
    fun setSmbHost(raw: String) {
        val cleaned = raw
            .removePrefix("smb://")
            .removePrefix("\\\\")
            .removePrefix("//")
            .trimEnd('/', '\\')
        smbHost.value = cleaned
    }

    fun setSmbUsername(username: String) {
        smbUsername.value = username
    }

    fun setSmbPassword(password: String) {
        smbPassword.value = password
    }

    fun setSmbPath(path: String) {
        smbPath.value = path
    }

    fun testSmbConnection() {
        viewModelScope.launch(Dispatchers.IO) {
            smbTestResult.value = "Testing..."
            nasConnected.value = false
            val host = smbHost.value
            val share = smbShare.value
            val path = smbPath.value
            val user = smbUsername.value
            val pass = smbPassword.value
            if (host.isBlank()) {
                smbTestResult.value = "FAILED: NAS address is required"
                return@launch
            }
            // Flush pending debounced writes before testing
            repository.setSmbHost(host)
            repository.setSmbShare(share)
            repository.setSmbPath(path)
            repository.setSmbUsername(user)
            repository.setSmbPassword(pass)

            val transport = SmbBackupTransport(host, share, path, user, pass)
            val error = transport.testConnection()
            if (error == null) {
                smbTestResult.value = "SUCCESS: Connected to $host"
                nasConnected.value = true
            } else {
                smbTestResult.value = error
            }
        }
    }

    /**
     * Trigger a one-shot NAS backup via BackupWorker (local DB + SMB upload).
     */
    fun triggerNasBackupNow() {
        viewModelScope.launch {
            // Flush current values to DataStore so the worker picks them up
            repository.setSmbHost(smbHost.value)
            repository.setSmbShare(smbShare.value)
            repository.setSmbPath(smbPath.value)
            repository.setSmbUsername(smbUsername.value)
            repository.setSmbPassword(smbPassword.value)
            // Ensure SMB is enabled for the worker
            repository.setSmbBackupEnabled(true)

            // Enqueue AFTER flush completes to avoid race condition
            val request = OneTimeWorkRequestBuilder<BackupWorker>().build()
            WorkManager.getInstance(app).enqueue(request)
        }
    }
}
