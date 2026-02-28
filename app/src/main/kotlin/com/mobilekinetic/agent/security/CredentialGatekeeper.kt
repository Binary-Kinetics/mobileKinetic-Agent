package com.mobilekinetic.agent.security

import android.util.Log
import com.mobilekinetic.agent.data.gemma.GemmaPrompts
import com.mobilekinetic.agent.data.gemma.GemmaTextGenerator
import com.mobilekinetic.agent.data.vault.CatalogMeta
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CredentialGatekeeper @Inject constructor(
    private val textGenerator: GemmaTextGenerator
) {
    companion object {
        private const val TAG = "CredentialGatekeeper"
        private const val MAX_TOKENS = 32
        private const val TEMPERATURE = 0.0f
    }

    val isAvailable: Boolean get() = textGenerator.isReady

    suspend fun shouldAllow(context: String, credentialName: String, meta: CatalogMeta? = null): Boolean {
        if (!isAvailable) {
            Log.w(TAG, "Gemma not available — DENYING (fail-closed)")
            return false  // Fail-closed: deny when Gemma unavailable
        }

        return try {
            val prompt = GemmaPrompts.credentialGatekeeper(context, credentialName, meta)
            val result = textGenerator.generate(prompt, maxTokens = MAX_TOKENS, temperature = TEMPERATURE)
            val decision = result.trim().uppercase()
            val allowed = decision == "ALLOW"
            Log.i(TAG, "Gatekeeper decision for '$credentialName' (context='$context'): $decision")
            allowed
        } catch (e: Exception) {
            Log.w(TAG, "Gatekeeper failed — DENYING (fail-closed)", e)
            false  // Fail-closed: deny on error
        }
    }
}
