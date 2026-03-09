package com.mobilekinetic.agent.claude

import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Builds the dynamic CLAUDE.md injected into Claude Code's working directory.
 * Replaces Python context_injector.py with a native Kotlin builder.
 *
 * Uses a tiered token-budget system (mirroring the Python original):
 *   Tier 0 (identity/guardrails): 800 tokens
 *   Tier 1 (device/MCP/agenda):   400 tokens each
 *   Tier 2 (RAG/calendar):        400 tokens each
 *   Tier 3 (learned behaviors):   400 tokens
 *   Total hard cap: ~4000 tokens (~16 000 chars)
 *
 * Lower priority number = higher importance. Sections are sorted by priority
 * before rendering and truncated from the bottom up when the budget is exceeded.
 */
class ClaudeMdBuilder {

    companion object {
        private const val TAG = "ClaudeMdBuilder"

        /** Approximate chars-per-token ratio (same as Python context_injector). */
        private const val CHARS_PER_TOKEN = 4

        /** Hard ceiling on total output in estimated tokens. */
        private const val TOTAL_TOKEN_BUDGET = 4000

        /** Maximum chars derived from the token budget. */
        private const val MAX_TOTAL_CHARS = TOTAL_TOKEN_BUDGET * CHARS_PER_TOKEN
    }

    // ----------------------------------------------------------------
    // Section container
    // ----------------------------------------------------------------

    /**
     * A single named section of the generated CLAUDE.md.
     * @param title  Markdown heading text (rendered as ## title).
     * @param content  Body text beneath the heading.
     * @param priority Lower number = rendered first and protected from truncation.
     */
    data class Section(
        val title: String,
        val content: String,
        val priority: Int = 5
    )

    private val sections = mutableListOf<Section>()

    // ----------------------------------------------------------------
    // Builder methods -- each returns `this` for chaining
    // ----------------------------------------------------------------

    /** Core identity -- who the agent is. Priority 0 (never truncated). */
    fun identity(
        name: String,
        deviceName: String,
        userName: String
    ): ClaudeMdBuilder {
        val body = buildString {
            appendLine("You are **$name**, a fully autonomous mobile agent running on **$deviceName**.")
            appendLine("Your operator is **$userName**.")
            appendLine()
            appendLine("You are not a plain text assistant. You have a terminal, HTTP APIs,")
            appendLine("a persistent RAG memory system, and Tasker automation. You can perceive")
            appendLine("the device and its surroundings, control its hardware, communicate through")
            appendLine("it, execute code, and automate tasks.")
        }
        sections.add(Section("Identity", body.trimEnd(), priority = 0))
        return this
    }

    /** Current device state snapshot. Priority 1. */
    fun deviceContext(
        batteryLevel: Int,
        isCharging: Boolean,
        wifiNetwork: String?,
        bluetoothDevices: List<String>,
        location: String?,
        currentTime: String
    ): ClaudeMdBuilder {
        val body = buildString {
            appendLine("- Battery: $batteryLevel%${if (isCharging) " (charging)" else ""}")
            if (wifiNetwork != null) appendLine("- WiFi: $wifiNetwork")
            if (bluetoothDevices.isNotEmpty()) {
                appendLine("- Bluetooth: ${bluetoothDevices.joinToString(", ")}")
            }
            if (location != null) appendLine("- Location: $location")
            appendLine("- Time: $currentTime")
        }
        sections.add(Section("Device State", body.trimEnd(), priority = 1))
        return this
    }

    /** Available MCP tool catalogue. Priority 2. */
    fun mcpToolCatalog(tools: List<McpToolInfo>): ClaudeMdBuilder {
        if (tools.isEmpty()) return this
        val body = buildString {
            // Group by server for readability
            val grouped = tools.groupBy { it.serverName }
            for ((server, serverTools) in grouped) {
                appendLine("### $server")
                for (tool in serverTools) {
                    appendLine("- `${tool.toolName}` -- ${tool.description}")
                }
                appendLine()
            }
        }
        sections.add(Section("Available MCP Tools", body.trimEnd(), priority = 2))
        return this
    }

    /** Active goals and due intentions from the agenda system. Priority 1. */
    fun activeAgenda(
        goals: List<AgendaGoal>,
        dueIntentions: List<AgendaIntention>
    ): ClaudeMdBuilder {
        if (goals.isEmpty() && dueIntentions.isEmpty()) return this
        val body = buildString {
            if (dueIntentions.isNotEmpty()) {
                appendLine("### Due Now")
                for (intention in dueIntentions) {
                    val recur = if (intention.recurring) " (recurring)" else ""
                    appendLine("- [${intention.id}] ${intention.description} -- trigger: ${intention.triggerTime}$recur")
                }
                appendLine()
            }
            if (goals.isNotEmpty()) {
                appendLine("### Goals")
                for (goal in goals.sortedBy { it.priority }) {
                    appendLine("- **[${goal.id}]** ${goal.description} (priority=${goal.priority}, ${goal.status})")
                    for (step in goal.steps) {
                        appendLine("  - $step")
                    }
                }
            }
        }
        sections.add(Section("Active Agenda", body.trimEnd(), priority = 1))
        return this
    }

    /** Guardrail rules governing autonomous behaviour. Priority 0. */
    fun guardrailRules(
        securityLevel: String,
        rules: List<String>
    ): ClaudeMdBuilder {
        val body = buildString {
            appendLine("Security level: **$securityLevel**")
            appendLine()
            for (rule in rules) {
                appendLine("- $rule")
            }
        }
        sections.add(Section("Guardrails", body.trimEnd(), priority = 0))
        return this
    }

    /** Patterns learned from past interactions. Priority 3. */
    fun learnedBehaviors(behaviors: List<LearnedBehavior>): ClaudeMdBuilder {
        if (behaviors.isEmpty()) return this
        val body = buildString {
            for (b in behaviors.sortedByDescending { it.confidence }) {
                val pct = (b.confidence * 100).toInt()
                appendLine("- [$pct%] ${b.pattern}")
            }
        }
        sections.add(Section("Learned Behaviors", body.trimEnd(), priority = 3))
        return this
    }

    /** Relevant RAG memories retrieved for the current conversation. Priority 2. */
    fun ragMemories(memories: List<RagMemory>): ClaudeMdBuilder {
        if (memories.isEmpty()) return this
        val body = buildString {
            for (mem in memories.sortedByDescending { it.relevance }) {
                val relPct = (mem.relevance * 100).toInt()
                appendLine("- [${mem.category}, $relPct%] ${mem.content}")
            }
        }
        sections.add(Section("Recalled Memories (RAG)", body.trimEnd(), priority = 2))
        return this
    }

    /** Upcoming calendar events. Priority 2. */
    fun calendarContext(events: List<CalendarEvent>): ClaudeMdBuilder {
        if (events.isEmpty()) return this
        val body = buildString {
            for (event in events) {
                val loc = if (event.location != null) " @ ${event.location}" else ""
                appendLine("- ${event.title}: ${event.startTime} - ${event.endTime}$loc")
            }
        }
        sections.add(Section("Upcoming Calendar", body.trimEnd(), priority = 2))
        return this
    }

    /** Arbitrary custom section. */
    fun section(title: String, content: String, priority: Int = 5): ClaudeMdBuilder {
        sections.add(Section(title, content, priority))
        return this
    }

    // ----------------------------------------------------------------
    // Build
    // ----------------------------------------------------------------

    /**
     * Renders all accumulated sections into a complete CLAUDE.md string.
     *
     * 1. Sorts sections by priority (ascending -- lower = more important).
     * 2. Renders each as a Markdown `## heading` block.
     * 3. Enforces the total token budget by truncating lower-priority sections
     *    at sentence boundaries when the budget would be exceeded.
     * 4. Appends a "Last updated" footer.
     */
    fun build(): String {
        val sorted = sections.sortedBy { it.priority }

        val rendered = mutableListOf<String>()
        var totalChars = 0

        for (section in sorted) {
            val block = buildString {
                appendLine("## ${section.title}")
                appendLine()
                appendLine(section.content)
            }.trimEnd()

            val blockLen = block.length
            val remaining = MAX_TOTAL_CHARS - totalChars

            if (remaining <= 0) {
                Log.d(TAG, "Budget exhausted -- skipping section '${section.title}'")
                break
            }

            if (blockLen <= remaining) {
                rendered.add(block)
                totalChars += blockLen
            } else {
                // Truncate at a sentence boundary inside the remaining budget
                val truncated = truncateAtSentence(block, remaining)
                rendered.add(truncated)
                totalChars += truncated.length
                Log.d(TAG, "Truncated section '${section.title}' from $blockLen to ${truncated.length} chars")
                break // no room for more sections
            }
        }

        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())

        return buildString {
            appendLine("---")
            appendLine()
            for ((i, block) in rendered.withIndex()) {
                appendLine(block)
                if (i < rendered.lastIndex) appendLine()
            }
            appendLine()
            appendLine("---")
            appendLine("*Last updated: $timestamp*")
        }.trimEnd() + "\n"
    }

    // ----------------------------------------------------------------
    // Helpers
    // ----------------------------------------------------------------

    /**
     * Truncates [text] to at most [maxChars] characters, snapping back to the
     * last sentence-ending period if one exists in the outer 30% of the slice.
     * Mirrors the Python TokenBudgetManager behaviour.
     */
    private fun truncateAtSentence(text: String, maxChars: Int): String {
        if (text.length <= maxChars) return text
        val slice = text.substring(0, maxChars)
        val boundary = (maxChars * 0.7).toInt()
        val lastDot = slice.lastIndexOf('.')
        val result = if (lastDot > boundary) {
            slice.substring(0, lastDot + 1)
        } else {
            slice
        }
        return result + "\n[...truncated to fit token budget]"
    }
}

// ----------------------------------------------------------------
// Data classes consumed by the builder
// ----------------------------------------------------------------

/** Describes a single MCP tool exposed by a named server. */
data class McpToolInfo(
    val serverName: String,
    val toolName: String,
    val description: String
)

/** A high-level goal tracked by the agenda system. */
data class AgendaGoal(
    val id: String,
    val description: String,
    val priority: Int,
    val status: String,
    val steps: List<String>
)

/** A time-triggered intention from the agenda system. */
data class AgendaIntention(
    val id: String,
    val description: String,
    val triggerTime: String,
    val recurring: Boolean
)

/** A behavioural pattern observed over past interactions. */
data class LearnedBehavior(
    val pattern: String,
    val confidence: Float
)

/** A RAG memory entry with relevance score. */
data class RagMemory(
    val content: String,
    val category: String,
    val relevance: Float
)

/** An upcoming calendar event. */
data class CalendarEvent(
    val title: String,
    val startTime: String,
    val endTime: String,
    val location: String?
)
