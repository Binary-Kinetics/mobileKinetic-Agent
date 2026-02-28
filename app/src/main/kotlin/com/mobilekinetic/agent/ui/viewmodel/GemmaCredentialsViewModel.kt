package com.mobilekinetic.agent.ui.viewmodel

import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mobilekinetic.agent.data.db.dao.VaultDao
import com.mobilekinetic.agent.data.db.entity.VaultEntryEntity
import com.mobilekinetic.agent.data.vault.CredentialVault
import com.mobilekinetic.agent.security.BiometricAuthManager
import com.mobilekinetic.agent.security.VaultSession
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class GemmaCredentialsViewModel @Inject constructor(
    private val vault: CredentialVault,
    private val vaultDao: VaultDao,
    private val session: VaultSession,
    private val biometricAuthManager: BiometricAuthManager
) : ViewModel() {

    data class UiState(
        val credentials: List<VaultEntryEntity> = emptyList(),
        val isLoading: Boolean = true,
        val isUnlocked: Boolean = false,
        val remainingSeconds: Int = 0,
        val error: String? = null,
        val showAddDialog: Boolean = false
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    init {
        loadCredentials()
        startSessionTimer()
    }

    private fun loadCredentials() {
        viewModelScope.launch {
            vaultDao.getAll().collect { entries ->
                _uiState.value = _uiState.value.copy(
                    credentials = entries,
                    isLoading = false
                )
            }
        }
    }

    private fun startSessionTimer() {
        viewModelScope.launch {
            while (true) {
                _uiState.value = _uiState.value.copy(
                    isUnlocked = session.isUnlocked,
                    remainingSeconds = (session.remainingMs / 1000).toInt()
                )
                delay(1000)
            }
        }
    }

    fun unlockVault(activity: FragmentActivity) {
        biometricAuthManager.authenticateForEncrypt(
            activity = activity,
            onSuccess = { _ ->
                session.unlock()
                _uiState.value = _uiState.value.copy(isUnlocked = true)
            },
            onError = { error ->
                _uiState.value = _uiState.value.copy(error = "Unlock failed: $error")
            }
        )
    }

    fun lockVault() {
        session.lock()
        _uiState.value = _uiState.value.copy(isUnlocked = false, remainingSeconds = 0)
    }

    fun showAddDialog() {
        _uiState.value = _uiState.value.copy(showAddDialog = true)
    }

    fun dismissDialog() {
        _uiState.value = _uiState.value.copy(showAddDialog = false)
    }

    fun addCredential(activity: FragmentActivity, name: String, value: String, description: String) {
        biometricAuthManager.authenticateForEncrypt(
            activity = activity,
            onSuccess = { _ ->
                viewModelScope.launch {
                    try {
                        vault.store(name, value, description)
                        _uiState.value = _uiState.value.copy(showAddDialog = false)
                    } catch (e: Exception) {
                        _uiState.value = _uiState.value.copy(error = "Save failed: ${e.message}")
                    }
                }
            },
            onError = { error ->
                _uiState.value = _uiState.value.copy(error = "Auth failed: $error")
            }
        )
    }

    fun deleteCredential(activity: FragmentActivity, name: String) {
        biometricAuthManager.authenticateForEncrypt(
            activity = activity,
            onSuccess = { _ ->
                viewModelScope.launch {
                    vault.delete(name)
                }
            },
            onError = { error ->
                _uiState.value = _uiState.value.copy(error = "Auth failed: $error")
            }
        )
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }
}
