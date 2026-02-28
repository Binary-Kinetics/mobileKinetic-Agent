package com.mobilekinetic.agent.privacy

import android.util.Log
import com.mobilekinetic.agent.data.gemma.GemmaPrompts
import com.mobilekinetic.agent.data.gemma.GemmaTextGenerator
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GemmaPrivacyFilter @Inject constructor(
    private val textGenerator: GemmaTextGenerator
) {
    companion object {
        private const val TAG = "GemmaPrivacyFilter"
        private const val MAX_TOKENS = 32
        private const val TEMPERATURE = 0.0f
    }

    val isAvailable: Boolean get() = textGenerator.isReady

    suspend fun classify(text: String): String {
        if (!isAvailable) return "PASS"  // Fail-open

        return try {
            val prompt = GemmaPrompts.privacyClassification(text)
            val result = textGenerator.generate(prompt, maxTokens = MAX_TOKENS, temperature = TEMPERATURE)
            val classification = result.trim().uppercase()
            when (classification) {
                "PASS", "REDACT", "BLOCK" -> classification
                else -> {
                    Log.w(TAG, "Unexpected classification: $classification, defaulting to PASS")
                    "PASS"
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Gemma classification failed, fail-open to PASS", e)
            "PASS"  // Fail-open: if Gemma unavailable, rule-based result stands
        }
    }
}
