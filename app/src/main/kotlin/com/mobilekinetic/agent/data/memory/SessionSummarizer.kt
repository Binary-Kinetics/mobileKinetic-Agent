package com.mobilekinetic.agent.data.memory

import android.util.Log
import com.mobilekinetic.agent.data.gemma.GemmaPrompts
import com.mobilekinetic.agent.data.gemma.GemmaTextGenerator
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SessionSummarizer @Inject constructor(
    private val textGenerator: GemmaTextGenerator
) {
    companion object {
        private const val TAG = "SessionSummarizer"
        private const val MAX_TRANSCRIPT_CHARS = 8000
        private const val MAX_TOKENS = 256
    }

    suspend fun summarize(transcript: String): String {
        val trimmed = if (transcript.length > MAX_TRANSCRIPT_CHARS) {
            transcript.takeLast(MAX_TRANSCRIPT_CHARS)
        } else transcript

        // Primary: Gemma on-device
        if (textGenerator.isReady) {
            return try {
                val prompt = GemmaPrompts.sessionSummary(trimmed)
                textGenerator.generate(prompt, maxTokens = MAX_TOKENS)
            } catch (e: Exception) {
                Log.w(TAG, "Gemma summarization failed, falling back to rule-based", e)
                ruleBased(trimmed)
            }
        }

        // Fallback: rule-based extraction
        return ruleBased(trimmed)
    }

    private fun ruleBased(transcript: String): String {
        val lines = transcript.lines()
        val keyLines = mutableListOf<String>()

        lines.forEach { line ->
            val lower = line.lowercase()
            when {
                lower.contains("error") || lower.contains("exception") -> keyLines.add("[ERROR] $line")
                lower.contains("completed") || lower.contains("success") -> keyLines.add("[DONE] $line")
                lower.contains("decided") || lower.contains("chose") -> keyLines.add("[DECISION] $line")
                lower.contains("learned") || lower.contains("preference") -> keyLines.add("[PREF] $line")
            }
        }

        return if (keyLines.isNotEmpty()) {
            keyLines.take(20).joinToString("\n")
        } else {
            "Session with ${lines.size} lines. No key events extracted."
        }
    }
}
