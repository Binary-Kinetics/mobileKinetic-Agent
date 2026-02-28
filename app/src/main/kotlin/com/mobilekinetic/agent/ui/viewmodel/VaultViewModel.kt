package com.mobilekinetic.agent.ui.viewmodel

import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mobilekinetic.agent.data.db.entity.CredentialEntity
import com.mobilekinetic.agent.data.vault.VaultRepository
import com.mobilekinetic.agent.security.BiometricAuthManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class VaultUiState(
    val credentials: List<CredentialEntity> = emptyList(),
    val isLoading: Boolean = true,
    val error: String? = null,
    val selectedCredential: CredentialEntity? = null,
    val decryptedPassword: String? = null,
    val showAddDialog: Boolean = false,
    val showEditDialog: Boolean = false
)

@HiltViewModel
class VaultViewModel @Inject constructor(
    private val vaultRepository: VaultRepository,
    private val biometricAuthManager: BiometricAuthManager
) : ViewModel() {
    private val _uiState = MutableStateFlow(VaultUiState())
    val uiState: StateFlow<VaultUiState> = _uiState.asStateFlow()
    init { loadCredentials() }

    fun loadCredentials() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            vaultRepository.getAllCredentials()
                .catch { e -> _uiState.update { it.copy(isLoading = false, error = e.message ?: "Failed") } }
                .collect { creds -> _uiState.update { it.copy(credentials = creds, isLoading = false) } }
        }
    }
    fun onAddCredential() { _uiState.update { it.copy(showAddDialog = true, selectedCredential = null) } }
    fun onEditCredential(entity: CredentialEntity) { _uiState.update { it.copy(selectedCredential = entity, showEditDialog = true) } }
    fun onDismissDialog() { _uiState.update { it.copy(showAddDialog = false, showEditDialog = false, selectedCredential = null, decryptedPassword = null) } }
    fun saveCredential(activity: FragmentActivity, serviceName: String, username: String, password: String) {
        biometricAuthManager.authenticateForEncrypt(activity = activity,
            onSuccess = { cipher ->
                viewModelScope.launch {
                    try {
                        vaultRepository.saveCredential(cipher = cipher, serviceName = serviceName, username = username, password = password)
                        _uiState.update { it.copy(showAddDialog = false, error = null) }
                    } catch (e: Exception) {
                        _uiState.update { it.copy(error = e.message ?: "Save failed") }
                    }
                }
            },
            onError = { errorMsg -> _uiState.update { it.copy(error = errorMsg.toString()) } }
        )
    }
    fun updateCredential(activity: FragmentActivity, id: String, serviceName: String, username: String, password: String) {
        biometricAuthManager.authenticateForEncrypt(activity = activity,
            onSuccess = { cipher ->
                viewModelScope.launch {
                    try {
                        vaultRepository.updateCredential(cipher = cipher, id = id, serviceName = serviceName, username = username, password = password)
                        _uiState.update { it.copy(showEditDialog = false, selectedCredential = null, error = null) }
                    } catch (e: Exception) {
                        _uiState.update { it.copy(error = e.message ?: "Update failed") }
                    }
                }
            },
            onError = { errorMsg -> _uiState.update { it.copy(error = errorMsg.toString()) } })
    }
    fun revealPassword(activity: FragmentActivity, entity: CredentialEntity) {
        val iv = vaultRepository.getIvFromCredential(entity)
        biometricAuthManager.authenticateForDecrypt(activity = activity, iv = iv,
            onSuccess = { cipher ->
                try {
                    val decrypted = vaultRepository.decryptPassword(cipher, entity)
                    _uiState.update { it.copy(selectedCredential = entity, decryptedPassword = decrypted, error = null) }
                } catch (e: Exception) {
                    _uiState.update { it.copy(error = e.message ?: "Decrypt failed") }
                }
            },
            onError = { errorMsg -> _uiState.update { it.copy(error = errorMsg.toString()) } })
    }
    fun hidePassword() { _uiState.update { it.copy(decryptedPassword = null, selectedCredential = null) } }
    fun deleteCredential(id: String) {
        viewModelScope.launch { vaultRepository.deleteCredential(id) }
    }
    fun clearError() { _uiState.update { it.copy(error = null) } }
}
