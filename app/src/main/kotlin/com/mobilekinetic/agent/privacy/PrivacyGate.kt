package com.mobilekinetic.agent.privacy

import android.util.Log
import com.mobilekinetic.agent.privacy.GemmaPrivacyFilter
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.locks.ReentrantReadWriteLock
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.concurrent.read
import kotlin.concurrent.write

data class BlacklistRuleSnapshot(
    val id: String,
    val ruleType: String,
    val value: String,
    val action: String,
    val matchFields: String
)

data class BacklogItem(
    val source: String,       // "sms" or "notification"
    val item: JSONObject,     // original message/notification
    val timestamp: Long = System.currentTimeMillis()
)

@Singleton
class PrivacyGate @Inject constructor(
    private val gemmaFilter: GemmaPrivacyFilter
) {

    companion object {
        private const val TAG = "PrivacyGate"
        private const val GEMMA_TIMEOUT_MS = 3_000L
        private const val MAX_BACKLOG = 500
        private val EVAL_ORDER = listOf("APP_PACKAGE", "SENDER", "KEYWORD", "TOPIC", "PATTERN")
    }

    private val lock = ReentrantReadWriteLock()
    private var rules: List<BlacklistRuleSnapshot> = emptyList()
    private val backlog = ConcurrentLinkedQueue<BacklogItem>()

    fun updateRules(newRules: List<BlacklistRuleSnapshot>) {
        lock.write {
            rules = newRules.sortedBy { EVAL_ORDER.indexOf(it.ruleType).let { i -> if (i == -1) 99 else i } }
            Log.i(TAG, "Rules updated: ${rules.size} active rules")
        }
    }

    // --- Backlog access ---

    fun getBacklogSnapshot(): List<BacklogItem> = backlog.toList()

    fun getBacklogSize(): Int = backlog.size

    fun clearBacklog() {
        backlog.clear()
        Log.i(TAG, "Backlog cleared")
    }

    /** Process all backlog items through Gemma (no timeout). Returns classification results. */
    suspend fun processBacklog(): JSONArray {
        val results = JSONArray()
        val items = backlog.toList()
        backlog.clear()

        for (entry in items) {
            val text = getSearchableText(entry.item, "ALL")
            val classification = if (text.isNotBlank() && gemmaFilter.isAvailable) {
                try {
                    gemmaFilter.classify(text)
                } catch (e: Exception) {
                    Log.w(TAG, "Backlog Gemma classification failed", e)
                    "PASS"
                }
            } else {
                "PASS"
            }

            results.put(JSONObject().apply {
                put("source", entry.source)
                put("classification", classification)
                put("queued_at", entry.timestamp)
                put("item", entry.item)
            })
        }

        Log.i(TAG, "Processed ${items.size} backlog items")
        return results
    }

    // --- Filtering ---

    suspend fun filterNotifications(notifications: JSONArray): JSONArray {
        if (notifications.length() == 0) return notifications
        val result = JSONArray()
        for (i in 0 until notifications.length()) {
            val notif = notifications.getJSONObject(i)
            val verdict = evaluateItem(notif, "notification")
            when (verdict) {
                "ALLOW" -> result.put(notif)
                "BACKLOG" -> result.put(JSONObject(notif.toString()).apply {
                    put("_privacy", "backlog")
                })
                "REDACT" -> result.put(redactItem(notif))
                "BLOCK" -> Log.d(TAG, "Blocked notification from ${notif.optString("package", "unknown")}")
            }
        }
        return result
    }

    suspend fun filterSmsMessages(messages: JSONArray): JSONArray {
        if (messages.length() == 0) return messages
        val result = JSONArray()
        for (i in 0 until messages.length()) {
            val msg = messages.getJSONObject(i)
            when (evaluateItem(msg, "sms")) {
                "ALLOW" -> result.put(msg)
                "BACKLOG" -> result.put(JSONObject(msg.toString()).apply {
                    put("_privacy", "backlog")
                })
                "REDACT" -> result.put(redactItem(msg))
                "BLOCK" -> Log.d(TAG, "Blocked SMS from ${msg.optString("address", "unknown")}")
            }
        }
        return result
    }

    fun testRule(ruleType: String, value: String, action: String, testData: JSONObject): String {
        val snapshot = BlacklistRuleSnapshot("test", ruleType, value, action, "ALL")
        return evaluateSingleRule(snapshot, testData) ?: "ALLOW"
    }

    private suspend fun evaluateItem(item: JSONObject, source: String = "unknown"): String {
        // Rule-based first (fast)
        lock.read {
            for (rule in rules) {
                val result = evaluateSingleRule(rule, item)
                if (result != null) return result  // Rule matched -- use it
            }
        }

        // Gemma classification (slower, only if rules passed)
        // Timeout per-item: if slow, queue to backlog instead of hanging
        if (gemmaFilter.isAvailable) {
            val text = getSearchableText(item, "ALL")
            if (text.isNotBlank()) {
                val gemmaResult = withTimeoutOrNull(GEMMA_TIMEOUT_MS) {
                    gemmaFilter.classify(text)
                }
                if (gemmaResult != null && gemmaResult != "PASS") {
                    Log.d(TAG, "Gemma classified as $gemmaResult")
                    return gemmaResult
                }
                if (gemmaResult == null) {
                    // Gemma too slow -- queue for later classification
                    if (backlog.size < MAX_BACKLOG) {
                        backlog.add(BacklogItem(source = source, item = JSONObject(item.toString())))
                        Log.w(TAG, "Gemma timed out, queued to backlog (${backlog.size} pending)")
                    } else {
                        Log.w(TAG, "Gemma timed out, backlog full -- allowing item")
                    }
                    return "BACKLOG"
                }
            }
        }

        return "ALLOW"
    }

    private fun evaluateSingleRule(rule: BlacklistRuleSnapshot, item: JSONObject): String? {
        val matched = when (rule.ruleType) {
            "APP_PACKAGE" -> {
                val pkg = item.optString("package", "")
                pkg.equals(rule.value, ignoreCase = true)
            }
            "SENDER" -> {
                val sender = item.optString("address", item.optString("sender", ""))
                sender.contains(rule.value, ignoreCase = true)
            }
            "KEYWORD" -> {
                val text = getSearchableText(item, rule.matchFields)
                text.contains(rule.value, ignoreCase = true)
            }
            "TOPIC" -> {
                val text = getSearchableText(item, rule.matchFields)
                text.contains(rule.value, ignoreCase = true)
            }
            "PATTERN" -> {
                val text = getSearchableText(item, rule.matchFields)
                try {
                    Regex(rule.value, RegexOption.IGNORE_CASE).containsMatchIn(text)
                } catch (e: Exception) {
                    Log.w(TAG, "Invalid regex pattern: ${rule.value}", e)
                    false
                }
            }
            else -> false
        }
        return if (matched) rule.action else null
    }

    private fun getSearchableText(item: JSONObject, matchFields: String): String {
        return if (matchFields == "ALL") {
            buildString {
                item.optString("title", "").let { if (it.isNotEmpty()) append(it).append(" ") }
                item.optString("text", "").let { if (it.isNotEmpty()) append(it).append(" ") }
                item.optString("body", "").let { if (it.isNotEmpty()) append(it).append(" ") }
                item.optString("message", "").let { if (it.isNotEmpty()) append(it).append(" ") }
                item.optString("content", "").let { if (it.isNotEmpty()) append(it) }
            }
        } else {
            matchFields.split(",").joinToString(" ") { item.optString(it.trim(), "") }
        }
    }

    private fun redactItem(item: JSONObject): JSONObject {
        val redacted = JSONObject(item.toString())
        listOf("text", "body", "message", "content").forEach { key ->
            if (redacted.has(key)) redacted.put(key, "[REDACTED]")
        }
        return redacted
    }
}
