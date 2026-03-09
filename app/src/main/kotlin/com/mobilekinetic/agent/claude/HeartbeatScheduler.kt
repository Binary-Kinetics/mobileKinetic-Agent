package com.mobilekinetic.agent.claude

import android.content.Context
import android.util.Log
import androidx.work.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

/**
 * WorkManager-based self-prompting scheduler for mK:a autonomous operation.
 *
 * On each heartbeat tick:
 * 1. ContextSynthesizer builds fresh context snapshot
 * 2. AgendaManager checks for due intentions via agenda_get_due
 * 3. StateChangeDetector compares current vs last device state
 * 4. If due intentions exist OR significant state changes detected:
 *    - Sends a self-prompt to the active Claude Code session
 *    - Claude Code processes autonomously, SecurityGuardian gates all tool calls
 *
 * Uses Android WorkManager for reliable background execution that survives
 * doze mode, app restarts, and device reboots.
 */
class HeartbeatScheduler(
    private val context: Context
) {
    companion object {
        private const val TAG = "HeartbeatScheduler"
        private const val WORK_NAME = "mka_heartbeat"
        private const val KEY_INTERVAL_MINUTES = "interval_minutes"
        private const val KEY_AGENDA_URL = "agenda_url"
        private const val DEFAULT_INTERVAL_MINUTES = 15L
        private const val MIN_INTERVAL_MINUTES = 5L // below this isn't reliable with WorkManager
    }

    /**
     * Heartbeat configuration.
     */
    data class HeartbeatConfig(
        val intervalMinutes: Long = DEFAULT_INTERVAL_MINUTES,
        val enabled: Boolean = true,
        val checkAgenda: Boolean = true,
        val checkStateChanges: Boolean = true,
        val agendaUrl: String = "http://localhost:5567"
    )

    private var config = HeartbeatConfig()

    /**
     * Start the heartbeat with the given interval.
     * Enqueues periodic WorkManager work that survives app restarts.
     */
    fun start(config: HeartbeatConfig = HeartbeatConfig()) {
        this.config = config
        if (!config.enabled) {
            stop()
            return
        }

        val interval = config.intervalMinutes.coerceAtLeast(MIN_INTERVAL_MINUTES)

        val inputData = Data.Builder()
            .putLong(KEY_INTERVAL_MINUTES, interval)
            .putString(KEY_AGENDA_URL, config.agendaUrl)
            .build()

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.NOT_REQUIRED) // Works offline
            .build()

        val heartbeatWork = PeriodicWorkRequestBuilder<HeartbeatWorker>(
            interval, TimeUnit.MINUTES
        )
            .setConstraints(constraints)
            .setInputData(inputData)
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                PeriodicWorkRequest.MIN_BACKOFF_MILLIS,
                TimeUnit.MILLISECONDS
            )
            .addTag(WORK_NAME)
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            heartbeatWork
        )

        Log.i(TAG, "Heartbeat started: interval=${interval}m, agenda=${config.checkAgenda}, state=${config.checkStateChanges}")
    }

    /**
     * Stop the heartbeat.
     */
    fun stop() {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        Log.i(TAG, "Heartbeat stopped")
    }

    /**
     * Check if the heartbeat is currently scheduled.
     */
    fun isRunning(): Boolean {
        val workInfos = WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork(WORK_NAME)
            .get()
        return workInfos.any { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.RUNNING }
    }

    /**
     * Update heartbeat interval without stopping.
     */
    fun updateInterval(intervalMinutes: Long) {
        config = config.copy(intervalMinutes = intervalMinutes)
        start(config)
    }

    /**
     * Get current configuration.
     */
    fun getConfig(): HeartbeatConfig = config

    /**
     * The actual WorkManager worker that runs on each heartbeat tick.
     * This runs in a background process and must be self-contained.
     */
    class HeartbeatWorker(
        appContext: Context,
        params: WorkerParameters
    ) : CoroutineWorker(appContext, params) {

        companion object {
            private const val TAG = "HeartbeatWorker"
        }

        override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
            try {
                Log.i(TAG, "Heartbeat tick started")

                val agendaUrl = inputData.getString(KEY_AGENDA_URL) ?: "http://localhost:5567"
                val stateDetector = StateChangeDetector()

                // 1. Check for due intentions from agenda
                val dueIntentions = fetchDueIntentions(agendaUrl)

                // 2. Check for significant device state changes
                val stateChanges = stateDetector.detectChanges()

                // 3. Decide if we should self-prompt
                val shouldPrompt = dueIntentions.isNotEmpty() || stateChanges.isNotEmpty()

                if (!shouldPrompt) {
                    Log.d(TAG, "Heartbeat tick: no action needed")
                    return@withContext Result.success()
                }

                // 4. Build self-prompt context
                val prompt = buildSelfPrompt(dueIntentions, stateChanges)

                // 5. Send self-prompt to ClaudeCodeManager via local broadcast
                // The service picks this up and forwards to the active Claude session
                sendSelfPrompt(prompt)

                Log.i(TAG, "Heartbeat tick: self-prompt sent (${dueIntentions.size} intentions, ${stateChanges.size} changes)")
                Result.success()
            } catch (e: Exception) {
                Log.e(TAG, "Heartbeat tick failed: ${e.message}", e)
                Result.retry()
            }
        }

        private fun fetchDueIntentions(agendaUrl: String): List<DueIntention> {
            return try {
                val json = httpGet("$agendaUrl/tools/agenda_get_due")
                parseDueIntentions(json)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to fetch due intentions: ${e.message}")
                emptyList()
            }
        }

        private fun parseDueIntentions(json: String): List<DueIntention> {
            return try {
                val arr = JSONArray(json)
                (0 until arr.length()).map { i ->
                    val obj = arr.getJSONObject(i)
                    DueIntention(
                        id = obj.getString("id"),
                        description = obj.getString("description"),
                        goalId = obj.optString("goal_id", null)
                    )
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to parse due intentions: ${e.message}")
                emptyList()
            }
        }

        private fun buildSelfPrompt(
            dueIntentions: List<DueIntention>,
            stateChanges: List<StateChangeDetector.StateChange>
        ): String {
            return buildString {
                appendLine("# Heartbeat Self-Prompt")
                appendLine()
                appendLine("This is an autonomous heartbeat tick. Evaluate and act on your agenda.")
                appendLine()

                if (dueIntentions.isNotEmpty()) {
                    appendLine("## Due Intentions")
                    dueIntentions.forEach { intention ->
                        appendLine("- ${intention.description}")
                        if (intention.goalId != null) {
                            appendLine("  (linked to goal: ${intention.goalId})")
                        }
                    }
                    appendLine()
                }

                if (stateChanges.isNotEmpty()) {
                    appendLine("## Device State Changes")
                    stateChanges.forEach { change ->
                        val severity = when (change.severity) {
                            StateChangeDetector.Severity.URGENT -> "[URGENT] "
                            StateChangeDetector.Severity.HIGH -> "[HIGH] "
                            else -> ""
                        }
                        appendLine("- $severity${change.description}")
                    }
                    appendLine()
                }

                appendLine("Evaluate these changes against your active goals. Take appropriate action, respecting your guardrails.")
            }.trim()
        }

        private fun sendSelfPrompt(prompt: String) {
            // Send via local HTTP to the service's heartbeat endpoint
            // The MobileKineticService listens for this and forwards to ClaudeCodeManager
            try {
                val url = URL("http://localhost:5563/heartbeat/prompt")
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.setRequestProperty("Content-Type", "application/json")
                connection.doOutput = true
                connection.connectTimeout = 5000
                connection.readTimeout = 10000

                val body = JSONObject().apply {
                    put("prompt", prompt)
                    put("source", "heartbeat")
                    put("timestamp", System.currentTimeMillis())
                }.toString()

                connection.outputStream.bufferedWriter().use { it.write(body) }

                val responseCode = connection.responseCode
                if (responseCode != HttpURLConnection.HTTP_OK) {
                    Log.w(TAG, "Self-prompt delivery failed: HTTP $responseCode")
                }
                connection.disconnect()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send self-prompt: ${e.message}")
            }
        }

        private fun httpGet(url: String): String {
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            try {
                if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                    return connection.inputStream.bufferedReader().readText()
                }
                throw RuntimeException("HTTP ${connection.responseCode}")
            } finally {
                connection.disconnect()
            }
        }
    }

    data class DueIntention(
        val id: String,
        val description: String,
        val goalId: String? = null
    )
}
