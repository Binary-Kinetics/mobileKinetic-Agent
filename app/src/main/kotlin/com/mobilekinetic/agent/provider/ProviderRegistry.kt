package com.mobilekinetic.agent.provider

import com.mobilekinetic.agent.provider.impl.ClaudeCliProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Central registry for all available AI providers.
 *
 * Manages provider registration, active provider selection, and switching.
 * The built-in [ClaudeCliProvider] is registered automatically at construction.
 * Additional providers (Gemini, OpenAI, Custom) are registered dynamically
 * via [registerProvider] once their configuration is loaded.
 *
 * Active provider selection is persisted through [ProviderConfigStore] so the
 * user's choice survives app restarts.
 */
@Singleton
class ProviderRegistry @Inject constructor(
    private val claudeCliProvider: ClaudeCliProvider,
    private val configStore: ProviderConfigStore
) {
    private val _providers = mutableMapOf<String, AiProvider>()

    private val _activeProvider = MutableStateFlow<AiProvider?>(null)
    val activeProvider: StateFlow<AiProvider?> = _activeProvider.asStateFlow()

    private val _activeProviderId = MutableStateFlow("claude_cli")
    val activeProviderId: StateFlow<String> = _activeProviderId.asStateFlow()

    init {
        // The CLI provider is always available — no configuration required.
        registerProvider(claudeCliProvider)
    }

    /**
     * Register an [AiProvider] so it becomes selectable.
     */
    fun registerProvider(provider: AiProvider) {
        _providers[provider.id] = provider
    }

    /**
     * Unregister a provider by id.
     * If it is the active provider, fall back to the default (claude_cli).
     */
    fun unregisterProvider(id: String) {
        _providers.remove(id)
        if (_activeProviderId.value == id) {
            _activeProvider.value = claudeCliProvider
            _activeProviderId.value = claudeCliProvider.id
        }
    }

    /**
     * Look up a provider by its id.
     */
    fun getProvider(id: String): AiProvider? = _providers[id]

    /**
     * Return all registered providers.
     */
    fun getAllProviders(): List<AiProvider> = _providers.values.toList()

    /**
     * Switch to a different provider.
     *
     * Disposes the currently active provider (if any), initializes the new one,
     * and persists the selection via [ProviderConfigStore].
     *
     * @return null on success, or an error message from [AiProvider.initialize].
     */
    suspend fun switchProvider(providerId: String): String? {
        val provider = _providers[providerId]
            ?: return "Provider '$providerId' is not registered"

        // Dispose the old provider if it is different from the new one.
        val current = _activeProvider.value
        if (current != null && current.id != providerId) {
            current.dispose()
        }

        // Initialize the new provider.
        val error = provider.initialize()
        if (error != null) {
            return error
        }

        _activeProvider.value = provider
        _activeProviderId.value = providerId
        configStore.setActiveProvider(providerId)
        return null
    }

    /**
     * Initialize the registry on app startup.
     *
     * Reads the persisted active-provider selection and initializes it.
     * Falls back to [claudeCliProvider] if the saved provider is not
     * registered or fails to initialize.
     */
    suspend fun initializeActive() {
        val savedId = configStore.getActiveProviderId()
        val provider = _providers[savedId] ?: claudeCliProvider

        val error = provider.initialize()
        if (error != null && provider !== claudeCliProvider) {
            // Fall back to the CLI provider if the saved one fails.
            claudeCliProvider.initialize()
            _activeProvider.value = claudeCliProvider
            _activeProviderId.value = claudeCliProvider.id
        } else {
            _activeProvider.value = provider
            _activeProviderId.value = provider.id
        }
    }

    /**
     * Return the currently active provider.
     * Falls back to [claudeCliProvider] if nothing has been activated yet.
     */
    fun getActiveProvider(): AiProvider = _activeProvider.value ?: claudeCliProvider
}
