package com.mobilekinetic.agent.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mobilekinetic.agent.data.preferences.SettingsRepository
import com.mobilekinetic.agent.data.tts.TtsManager
import com.mobilekinetic.agent.data.tts.TtsProviderConfig
import com.mobilekinetic.agent.data.tts.TtsProviderType
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class TtsSettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository
) : ViewModel() {

    val ttsProviderType: StateFlow<String> = settingsRepository.ttsProviderType
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "kokoro")

    val ttsProviderUrl: StateFlow<String> = settingsRepository.ttsProviderUrl
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

    val ttsApiKey: StateFlow<String> = settingsRepository.ttsApiKey
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

    val ttsVoiceId: StateFlow<String> = settingsRepository.ttsVoiceId
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

    val ttsModel: StateFlow<String> = settingsRepository.ttsModel
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

    val ttsRate: StateFlow<Float> = settingsRepository.ttsRate
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 1.0f)

    private val _statusMessage = MutableStateFlow<String?>(null)
    val statusMessage: StateFlow<String?> = _statusMessage.asStateFlow()

    private val _isTesting = MutableStateFlow(false)
    val isTesting: StateFlow<Boolean> = _isTesting.asStateFlow()

    fun setProviderType(type: String) {
        viewModelScope.launch { settingsRepository.setTtsProviderType(type) }
    }

    fun setProviderUrl(url: String) {
        viewModelScope.launch { settingsRepository.setTtsProviderUrl(url) }
    }

    fun setApiKey(key: String) {
        viewModelScope.launch { settingsRepository.setTtsApiKey(key) }
    }

    fun setVoiceId(voiceId: String) {
        viewModelScope.launch { settingsRepository.setTtsVoiceId(voiceId) }
    }

    fun setModel(model: String) {
        viewModelScope.launch { settingsRepository.setTtsModel(model) }
    }

    fun setRate(rate: Float) {
        viewModelScope.launch { settingsRepository.setTtsRate(rate) }
    }

    fun applySettings() {
        viewModelScope.launch {
            val type = TtsProviderType.fromId(ttsProviderType.value)
            val config = TtsProviderConfig(
                url = ttsProviderUrl.value,
                apiKey = ttsApiKey.value,
                voiceId = ttsVoiceId.value,
                model = ttsModel.value,
                rate = ttsRate.value
            )
            TtsManager.switchProvider(type, config)
            _statusMessage.value = "TTS switched to ${type.displayName}"
        }
    }

    fun testVoice() {
        viewModelScope.launch {
            _isTesting.value = true
            val type = TtsProviderType.fromId(ttsProviderType.value)
            val config = TtsProviderConfig(
                url = ttsProviderUrl.value,
                apiKey = ttsApiKey.value,
                voiceId = ttsVoiceId.value,
                model = ttsModel.value,
                rate = ttsRate.value
            )
            // Ensure provider is configured before testing
            TtsManager.switchProvider(type, config)

            TtsManager.speak(
                text = "Hello! This is a test of the ${type.displayName} voice provider. " +
                    "Binary Agent text-to-speech is working correctly.",
                onComplete = {
                    _isTesting.value = false
                    _statusMessage.value = "Test complete"
                },
                onError = { e ->
                    _isTesting.value = false
                    _statusMessage.value = "Test failed: ${e.message}"
                }
            )
        }
    }

    fun stopTest() {
        TtsManager.stop()
        _isTesting.value = false
    }

    fun clearStatus() {
        _statusMessage.value = null
    }
}
