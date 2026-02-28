package com.mobilekinetic.agent.data.chat

/**
 * Response segment types for Claude output
 *
 * - VOICE: Conversational content meant to be spoken aloud via TTS
 * - DISPLAY: Tool output, code, diffs, technical content (shown but not vocalized)
 *
 * Claude Code explicitly tags output with <voice> and <display> tags.
 * This ensures clean separation: conversation is spoken, raw output is shown.
 */
enum class SegmentType {
    VOICE,
    DISPLAY
}

/**
 * A parsed segment of Claude's response
 */
data class ResponseSegment(
    val type: SegmentType,
    val content: String
)

/**
 * Complete parsed response with separated voice and display content
 */
data class ParsedResponse(
    val segments: List<ResponseSegment>
) {
    /**
     * All content meant to be spoken via TTS
     */
    val voiceContent: String
        get() = segments
            .filter { it.type == SegmentType.VOICE }
            .joinToString(" ") { it.content.trim() }

    /**
     * All content meant for display only (code, tool output, etc.)
     */
    val displayContent: String
        get() = segments
            .filter { it.type == SegmentType.DISPLAY }
            .joinToString("\n\n") { it.content.trim() }

    /**
     * Whether this response has any voice content
     */
    val hasVoice: Boolean
        get() = segments.any { it.type == SegmentType.VOICE }

    /**
     * Whether this response has any display-only content
     */
    val hasDisplay: Boolean
        get() = segments.any { it.type == SegmentType.DISPLAY }
}

/**
 * Parses Claude Code's response into voice and display segments.
 *
 * Claude Code explicitly tags its output:
 * - <voice>Conversational explanation here</voice>
 * - <display>Code, diffs, tool output here</display>
 *
 * This is cleaner than heuristic-based detection because:
 * - Claude controls what gets spoken vs shown
 * - Tool outputs are automatically display-only
 * - No guessing or misclassification
 *
 * Fallback behavior for untagged content:
 * - If no tags present, entire response is treated as VOICE (legacy mode)
 * - Code blocks (```) within voice tags are stripped for TTS
 */
object ResponseParser {

    // Explicit tag patterns - Claude Code marks voice vs display content
    private val voiceTagPattern = Regex("<voice>(.*?)</voice>", RegexOption.DOT_MATCHES_ALL)
    private val displayTagPattern = Regex("<display>(.*?)</display>", RegexOption.DOT_MATCHES_ALL)

    // For cleaning voice content
    private val codeBlockPattern = Regex("```[\\s\\S]*?```")
    private val inlineCodePattern = Regex("`[^`]+`")
    private val memoryTagPattern = Regex("""\[MEMORY_TAG:[^\]]*\]""")

    /**
     * Parse a raw response string into voice and display segments.
     *
     * Expects Claude Code to use explicit <voice> and <display> tags.
     * Segments are returned in the order they appear in the response.
     */
    fun parse(rawResponse: String): ParsedResponse {
        if (rawResponse.isBlank()) {
            return ParsedResponse(emptyList())
        }

        val segments = mutableListOf<ResponseSegment>()

        // Check if response uses explicit tags
        val hasVoiceTags = voiceTagPattern.containsMatchIn(rawResponse)
        val hasDisplayTags = displayTagPattern.containsMatchIn(rawResponse)

        if (!hasVoiceTags && !hasDisplayTags) {
            // Legacy mode: no tags, treat entire response as voice
            // This handles responses from non-tagged Claude instances
            segments.add(ResponseSegment(SegmentType.VOICE, rawResponse))
            return ParsedResponse(segments)
        }

        // Parse tagged content in order of appearance
        var currentIndex = 0
        val tagPattern = Regex("<(voice|display)>(.*?)</\\1>", RegexOption.DOT_MATCHES_ALL)

        for (match in tagPattern.findAll(rawResponse)) {
            // Any untagged content between tags is ignored (or could be treated as display)
            val tagType = match.groupValues[1]
            val content = match.groupValues[2].trim()

            if (content.isNotBlank()) {
                val segmentType = when (tagType) {
                    "voice" -> SegmentType.VOICE
                    "display" -> SegmentType.DISPLAY
                    else -> SegmentType.DISPLAY
                }
                segments.add(ResponseSegment(segmentType, content))
            }

            currentIndex = match.range.last + 1
        }

        return ParsedResponse(segments)
    }

    /**
     * Clean voice content for TTS - removes code blocks and normalizes whitespace.
     *
     * Small inline code references (like `variableName`) are kept but backticks removed,
     * so Claude can naturally reference code in conversation.
     */
    fun cleanForVoice(text: String): String {
        return text
            .replace(memoryTagPattern, "")  // Strip memory tags (not for speech)
            .replace(codeBlockPattern, "")  // Remove code blocks entirely
            .replace(inlineCodePattern) { match ->
                // Keep the content, just remove backticks
                match.value.trim('`')
            }
            .replace('!', '.')              // Tame exclamations for TTS (display keeps them)
            .replace(Regex("\\s+"), " ")    // Normalize whitespace
            .trim()
    }

    /**
     * Check if a response uses explicit tagging.
     * Useful for determining if Claude Code is properly configured.
     */
    fun usesExplicitTags(rawResponse: String): Boolean {
        return voiceTagPattern.containsMatchIn(rawResponse) ||
               displayTagPattern.containsMatchIn(rawResponse)
    }
}
