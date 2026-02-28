package com.mobilekinetic.agent.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File

/**
 * Creates the settings DataStore under ~/home/Memory/DataStore/ so it lives
 * inside the home directory tree and gets included in NAS backups automatically.
 * Migrates from the old default location (files/datastore/) on first use.
 */
fun createSettingsDataStore(context: Context): DataStore<Preferences> {
    val newDir = File(context.filesDir, "home/Memory/DataStore").apply { mkdirs() }
    val newFile = File(newDir, "settings.preferences_pb")
    // One-time migration from old default path
    if (!newFile.exists()) {
        val oldFile = File(context.filesDir, "datastore/settings.preferences_pb")
        if (oldFile.exists()) {
            oldFile.copyTo(newFile)
        }
    }
    return PreferenceDataStoreFactory.create(
        produceFile = { newFile }
    )
}

object SettingsKeys {
    val MODEL_SELECTION = stringPreferencesKey("model_selection")
    val API_KEY_HINT = stringPreferencesKey("api_key_hint")
    val HEARTBEAT_ENABLED = booleanPreferencesKey("heartbeat_enabled")
    val HEARTBEAT_PROMPT = stringPreferencesKey("heartbeat_prompt")
    val HEARTBEAT_INTERVAL_MINUTES = intPreferencesKey("heartbeat_interval_minutes")
    val AUTO_UPDATE_AFTER_DECAY = booleanPreferencesKey("auto_update_after_decay")

    // Phase 2A: User identity and service endpoint preferences
    val USER_NAME = stringPreferencesKey("user_name")
    val DEVICE_NAME = stringPreferencesKey("device_name")
    val TTS_SERVER_HOST = stringPreferencesKey("tts_server_host")
    val TTS_SERVER_PORT = intPreferencesKey("tts_server_port")
    val TTS_WSS_URL = stringPreferencesKey("tts_wss_url")
    val HA_SERVER_URL = stringPreferencesKey("ha_server_url")
    val NAS_IP = stringPreferencesKey("nas_ip")
    val SWITCHBOARD_URL = stringPreferencesKey("switchboard_url")
    val LOCAL_MODEL_URL = stringPreferencesKey("local_model_url")
    val PERSONAL_DOMAIN = stringPreferencesKey("personal_domain")

    // Phase 2B: First-run wizard completion flag
    val SETUP_COMPLETE = booleanPreferencesKey("setup_complete")

    // TTS Provider settings
    val TTS_PROVIDER_TYPE = stringPreferencesKey("tts_provider_type")
    val TTS_PROVIDER_URL = stringPreferencesKey("tts_provider_url")
    val TTS_API_KEY = stringPreferencesKey("tts_api_key")
    val TTS_VOICE_ID = stringPreferencesKey("tts_voice_id")
    val TTS_MODEL = stringPreferencesKey("tts_model")
    val TTS_RATE = floatPreferencesKey("tts_rate")
}

/**
 * Aggregate snapshot of all user preferences for bulk reads.
 */
data class UserPreferences(
    val modelSelection: String = "sonnet",
    val apiKeyHint: String = "",
    val heartbeatEnabled: Boolean = false,
    val heartbeatPrompt: String = "",
    val heartbeatIntervalMinutes: Int = 60,
    val autoUpdateAfterDecay: Boolean = false,
    // Phase 2A fields
    val userName: String = "",
    val deviceName: String = "",
    val ttsServerHost: String = "",
    val ttsServerPort: Int = 9199,
    val ttsWssUrl: String = "",
    val haServerUrl: String = "",
    val nasIp: String = "",
    val switchboardUrl: String = "",
    val localModelUrl: String = "",
    val personalDomain: String = "",
    // Phase 2B
    val setupComplete: Boolean = false,
    // TTS Provider settings
    val ttsProviderType: String = "kokoro",
    val ttsProviderUrl: String = "",
    val ttsApiKey: String = "",
    val ttsVoiceId: String = "",
    val ttsModel: String = "",
    val ttsRate: Float = 1.0f
)

class SettingsRepository(private val dataStore: DataStore<Preferences>) {

    // --- Composite flow ---

    val userPreferences: Flow<UserPreferences> = dataStore.data.map { prefs ->
        UserPreferences(
            modelSelection = prefs[SettingsKeys.MODEL_SELECTION] ?: "sonnet",
            apiKeyHint = prefs[SettingsKeys.API_KEY_HINT] ?: "",
            heartbeatEnabled = prefs[SettingsKeys.HEARTBEAT_ENABLED] ?: false,
            heartbeatPrompt = prefs[SettingsKeys.HEARTBEAT_PROMPT] ?: "",
            heartbeatIntervalMinutes = prefs[SettingsKeys.HEARTBEAT_INTERVAL_MINUTES] ?: 60,
            autoUpdateAfterDecay = prefs[SettingsKeys.AUTO_UPDATE_AFTER_DECAY] ?: false,
            userName = prefs[SettingsKeys.USER_NAME] ?: "",
            deviceName = prefs[SettingsKeys.DEVICE_NAME] ?: "",
            ttsServerHost = prefs[SettingsKeys.TTS_SERVER_HOST] ?: "",
            ttsServerPort = prefs[SettingsKeys.TTS_SERVER_PORT] ?: 9199,
            ttsWssUrl = prefs[SettingsKeys.TTS_WSS_URL] ?: "",
            haServerUrl = prefs[SettingsKeys.HA_SERVER_URL] ?: "",
            nasIp = prefs[SettingsKeys.NAS_IP] ?: "",
            switchboardUrl = prefs[SettingsKeys.SWITCHBOARD_URL] ?: "",
            localModelUrl = prefs[SettingsKeys.LOCAL_MODEL_URL] ?: "",
            personalDomain = prefs[SettingsKeys.PERSONAL_DOMAIN] ?: "",
            setupComplete = prefs[SettingsKeys.SETUP_COMPLETE] ?: false,
            ttsProviderType = prefs[SettingsKeys.TTS_PROVIDER_TYPE] ?: "kokoro",
            ttsProviderUrl = prefs[SettingsKeys.TTS_PROVIDER_URL] ?: "",
            ttsApiKey = prefs[SettingsKeys.TTS_API_KEY] ?: "",
            ttsVoiceId = prefs[SettingsKeys.TTS_VOICE_ID] ?: "",
            ttsModel = prefs[SettingsKeys.TTS_MODEL] ?: "",
            ttsRate = prefs[SettingsKeys.TTS_RATE] ?: 1.0f
        )
    }

    // --- Individual flows (existing) ---

    val modelSelection: Flow<String> = dataStore.data.map { prefs ->
        prefs[SettingsKeys.MODEL_SELECTION] ?: "sonnet"
    }

    val apiKeyHint: Flow<String> = dataStore.data.map { prefs ->
        prefs[SettingsKeys.API_KEY_HINT] ?: ""
    }

    val heartbeatEnabled: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[SettingsKeys.HEARTBEAT_ENABLED] ?: false
    }

    val heartbeatPrompt: Flow<String> = dataStore.data.map { prefs ->
        prefs[SettingsKeys.HEARTBEAT_PROMPT] ?: ""
    }

    val heartbeatIntervalMinutes: Flow<Int> = dataStore.data.map { prefs ->
        prefs[SettingsKeys.HEARTBEAT_INTERVAL_MINUTES] ?: 60
    }

    suspend fun setModelSelection(model: String) {
        dataStore.edit { prefs ->
            prefs[SettingsKeys.MODEL_SELECTION] = model
        }
    }

    suspend fun setApiKeyHint(hint: String) {
        dataStore.edit { prefs ->
            prefs[SettingsKeys.API_KEY_HINT] = hint
        }
    }

    suspend fun setHeartbeatEnabled(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[SettingsKeys.HEARTBEAT_ENABLED] = enabled
        }
    }

    suspend fun setHeartbeatPrompt(prompt: String) {
        dataStore.edit { prefs ->
            prefs[SettingsKeys.HEARTBEAT_PROMPT] = prompt
        }
    }

    suspend fun setHeartbeatIntervalMinutes(minutes: Int) {
        dataStore.edit { prefs ->
            prefs[SettingsKeys.HEARTBEAT_INTERVAL_MINUTES] = minutes
        }
    }

    val autoUpdateAfterDecay: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[SettingsKeys.AUTO_UPDATE_AFTER_DECAY] ?: false
    }

    suspend fun setAutoUpdateAfterDecay(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[SettingsKeys.AUTO_UPDATE_AFTER_DECAY] = enabled
        }
    }

    // --- Phase 2B: Setup completion ---

    val setupComplete: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[SettingsKeys.SETUP_COMPLETE] ?: false
    }

    suspend fun setSetupComplete(complete: Boolean) {
        dataStore.edit { prefs ->
            prefs[SettingsKeys.SETUP_COMPLETE] = complete
        }
    }

    // --- Phase 2A: Individual flows ---

    val userName: Flow<String> = dataStore.data.map { prefs ->
        prefs[SettingsKeys.USER_NAME] ?: ""
    }

    val deviceName: Flow<String> = dataStore.data.map { prefs ->
        prefs[SettingsKeys.DEVICE_NAME] ?: ""
    }

    val ttsServerHost: Flow<String> = dataStore.data.map { prefs ->
        prefs[SettingsKeys.TTS_SERVER_HOST] ?: ""
    }

    val ttsServerPort: Flow<Int> = dataStore.data.map { prefs ->
        prefs[SettingsKeys.TTS_SERVER_PORT] ?: 9199
    }

    val ttsWssUrl: Flow<String> = dataStore.data.map { prefs ->
        prefs[SettingsKeys.TTS_WSS_URL] ?: ""
    }

    val haServerUrl: Flow<String> = dataStore.data.map { prefs ->
        prefs[SettingsKeys.HA_SERVER_URL] ?: ""
    }

    val nasIp: Flow<String> = dataStore.data.map { prefs ->
        prefs[SettingsKeys.NAS_IP] ?: ""
    }

    val switchboardUrl: Flow<String> = dataStore.data.map { prefs ->
        prefs[SettingsKeys.SWITCHBOARD_URL] ?: ""
    }

    val localModelUrl: Flow<String> = dataStore.data.map { prefs ->
        prefs[SettingsKeys.LOCAL_MODEL_URL] ?: ""
    }

    val personalDomain: Flow<String> = dataStore.data.map { prefs ->
        prefs[SettingsKeys.PERSONAL_DOMAIN] ?: ""
    }

    // --- TTS Provider flows ---

    val ttsProviderType: Flow<String> = dataStore.data.map { prefs ->
        prefs[SettingsKeys.TTS_PROVIDER_TYPE] ?: "kokoro"
    }

    val ttsProviderUrl: Flow<String> = dataStore.data.map { prefs ->
        prefs[SettingsKeys.TTS_PROVIDER_URL] ?: ""
    }

    val ttsApiKey: Flow<String> = dataStore.data.map { prefs ->
        prefs[SettingsKeys.TTS_API_KEY] ?: ""
    }

    val ttsVoiceId: Flow<String> = dataStore.data.map { prefs ->
        prefs[SettingsKeys.TTS_VOICE_ID] ?: ""
    }

    val ttsModel: Flow<String> = dataStore.data.map { prefs ->
        prefs[SettingsKeys.TTS_MODEL] ?: ""
    }

    val ttsRate: Flow<Float> = dataStore.data.map { prefs ->
        prefs[SettingsKeys.TTS_RATE] ?: 1.0f
    }

    // --- Phase 2A: Setters ---

    suspend fun setUserName(name: String) {
        dataStore.edit { prefs ->
            prefs[SettingsKeys.USER_NAME] = name
        }
    }

    suspend fun setDeviceName(name: String) {
        dataStore.edit { prefs ->
            prefs[SettingsKeys.DEVICE_NAME] = name
        }
    }

    suspend fun setTtsServerHost(host: String) {
        dataStore.edit { prefs ->
            prefs[SettingsKeys.TTS_SERVER_HOST] = host
        }
    }

    suspend fun setTtsServerPort(port: Int) {
        dataStore.edit { prefs ->
            prefs[SettingsKeys.TTS_SERVER_PORT] = port
        }
    }

    suspend fun setTtsWssUrl(url: String) {
        dataStore.edit { prefs ->
            prefs[SettingsKeys.TTS_WSS_URL] = url
        }
    }

    suspend fun setHaServerUrl(url: String) {
        dataStore.edit { prefs ->
            prefs[SettingsKeys.HA_SERVER_URL] = url
        }
    }

    suspend fun setNasIp(ip: String) {
        dataStore.edit { prefs ->
            prefs[SettingsKeys.NAS_IP] = ip
        }
    }

    suspend fun setSwitchboardUrl(url: String) {
        dataStore.edit { prefs ->
            prefs[SettingsKeys.SWITCHBOARD_URL] = url
        }
    }

    suspend fun setLocalModelUrl(url: String) {
        dataStore.edit { prefs ->
            prefs[SettingsKeys.LOCAL_MODEL_URL] = url
        }
    }

    suspend fun setPersonalDomain(domain: String) {
        dataStore.edit { prefs ->
            prefs[SettingsKeys.PERSONAL_DOMAIN] = domain
        }
    }

    // --- TTS Provider setters ---

    suspend fun setTtsProviderType(type: String) {
        dataStore.edit { prefs ->
            prefs[SettingsKeys.TTS_PROVIDER_TYPE] = type
        }
    }

    suspend fun setTtsProviderUrl(url: String) {
        dataStore.edit { prefs ->
            prefs[SettingsKeys.TTS_PROVIDER_URL] = url
        }
    }

    suspend fun setTtsApiKey(key: String) {
        dataStore.edit { prefs ->
            prefs[SettingsKeys.TTS_API_KEY] = key
        }
    }

    suspend fun setTtsVoiceId(voiceId: String) {
        dataStore.edit { prefs ->
            prefs[SettingsKeys.TTS_VOICE_ID] = voiceId
        }
    }

    suspend fun setTtsModel(model: String) {
        dataStore.edit { prefs ->
            prefs[SettingsKeys.TTS_MODEL] = model
        }
    }

    suspend fun setTtsRate(rate: Float) {
        dataStore.edit { prefs ->
            prefs[SettingsKeys.TTS_RATE] = rate
        }
    }

    // --- Phase 2C: user_config.json writer ---

    /**
     * Writes the current user preferences to ~/user_config.json so Python
     * scripts can read config before Kotlin ProcessBuilder sets env vars.
     *
     * JSON keys match what mka_config.py expects on the Python side.
     */
    suspend fun writeUserConfigJson(homeDir: File) {
        val prefs = userPreferences.first()
        val json = JSONObject().apply {
            put("user_name", prefs.userName)
            put("device_name", prefs.deviceName)
            put("tts_host", prefs.ttsServerHost)
            put("tts_port", prefs.ttsServerPort)
            put("tts_wss_url", prefs.ttsWssUrl)
            put("ha_url", prefs.haServerUrl)
            put("nas_ip", prefs.nasIp)
            put("switchboard_url", prefs.switchboardUrl)
            put("local_model_url", prefs.localModelUrl)
            put("personal_domain", prefs.personalDomain)
            put("tts_provider_type", prefs.ttsProviderType)
            put("tts_provider_url", prefs.ttsProviderUrl)
            put("tts_api_key", prefs.ttsApiKey)
            put("tts_voice_id", prefs.ttsVoiceId)
            put("tts_model", prefs.ttsModel)
            put("tts_rate", prefs.ttsRate.toDouble())
        }
        val configFile = File(homeDir, "user_config.json")
        configFile.writeText(json.toString(2))
    }

    /**
     * Starts a coroutine that observes userPreferences and writes
     * user_config.json whenever any preference value changes.
     *
     * Call once during app initialization (e.g. from Application.onCreate
     * or a DI module). The coroutine lives as long as the provided scope.
     */
    fun startConfigSync(homeDir: File, scope: CoroutineScope) {
        scope.launch {
            userPreferences
                .distinctUntilChanged()
                .collect { prefs ->
                    val json = JSONObject().apply {
                        put("user_name", prefs.userName)
                        put("device_name", prefs.deviceName)
                        put("tts_host", prefs.ttsServerHost)
                        put("tts_port", prefs.ttsServerPort)
                        put("tts_wss_url", prefs.ttsWssUrl)
                        put("ha_url", prefs.haServerUrl)
                        put("nas_ip", prefs.nasIp)
                        put("switchboard_url", prefs.switchboardUrl)
                        put("local_model_url", prefs.localModelUrl)
                        put("personal_domain", prefs.personalDomain)
                        put("tts_provider_type", prefs.ttsProviderType)
                        put("tts_provider_url", prefs.ttsProviderUrl)
                        put("tts_api_key", prefs.ttsApiKey)
                        put("tts_voice_id", prefs.ttsVoiceId)
                        put("tts_model", prefs.ttsModel)
                        put("tts_rate", prefs.ttsRate.toDouble())
                    }
                    try {
                        val configFile = File(homeDir, "user_config.json")
                        configFile.writeText(json.toString(2))
                    } catch (e: Exception) {
                        // Log but don't crash — config sync is best-effort
                        android.util.Log.w(
                            "SettingsRepository",
                            "Failed to write user_config.json: ${e.message}"
                        )
                    }
                }
        }
    }
}
