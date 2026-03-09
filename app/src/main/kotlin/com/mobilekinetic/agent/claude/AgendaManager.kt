package com.mobilekinetic.agent.claude

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Kotlin client for the Agenda MCP server (port 5567).
 * Provides access to persistent goals, intentions, and learned behaviors
 * that power mK:a's autonomous self-motivation.
 *
 * Called by:
 * - ContextSynthesizer: to inject agenda into CLAUDE.md
 * - HeartbeatScheduler: to check for due intentions
 * - AgendaScreen: UI for viewing/editing goals
 */
class AgendaManager(
    private val baseUrl: String = "http://localhost:5567"
) {
    companion object {
        private const val TAG = "AgendaManager"
        private const val TIMEOUT_MS = 10000
    }

    // --- Goal Operations ---

    data class Goal(
        val id: String,
        val description: String,
        val priority: Int,
        val status: String,
        val steps: List<GoalStep>,
        val createdAt: String,
        val deadline: String?,
        val lastTouched: String
    )

    data class GoalStep(
        val description: String,
        val status: String,
        val result: String?
    )

    suspend fun createGoal(
        description: String,
        priority: Int = 3,
        deadline: String? = null,
        steps: List<String> = emptyList()
    ): Goal? = withContext(Dispatchers.IO) {
        try {
            val body = JSONObject().apply {
                put("description", description)
                put("priority", priority)
                if (deadline != null) put("deadline", deadline)
                if (steps.isNotEmpty()) put("steps", JSONArray(steps))
            }
            val response = callTool("agenda_create_goal", body)
            response?.let { parseGoal(it) }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to create goal: ${e.message}")
            null
        }
    }

    suspend fun listGoals(
        status: String? = null,
        priorityMin: Int? = null,
        limit: Int = 20
    ): List<Goal> = withContext(Dispatchers.IO) {
        try {
            val body = JSONObject().apply {
                if (status != null) put("status", status)
                if (priorityMin != null) put("priority_min", priorityMin)
                put("limit", limit)
            }
            val response = callTool("agenda_list_goals", body)
            response?.let { parseGoalList(it) } ?: emptyList()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to list goals: ${e.message}")
            emptyList()
        }
    }

    suspend fun updateGoal(
        goalId: String,
        status: String? = null,
        stepIndex: Int? = null,
        stepStatus: String? = null,
        stepResult: String? = null,
        description: String? = null
    ): Goal? = withContext(Dispatchers.IO) {
        try {
            val body = JSONObject().apply {
                put("goal_id", goalId)
                if (status != null) put("status", status)
                if (stepIndex != null) put("step_index", stepIndex)
                if (stepStatus != null) put("step_status", stepStatus)
                if (stepResult != null) put("step_result", stepResult)
                if (description != null) put("description", description)
            }
            val response = callTool("agenda_update_goal", body)
            response?.let { parseGoal(it) }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to update goal: ${e.message}")
            null
        }
    }

    suspend fun deleteGoal(goalId: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val body = JSONObject().apply { put("goal_id", goalId) }
            callTool("agenda_delete_goal", body)
            true
        } catch (e: Exception) {
            Log.w(TAG, "Failed to delete goal: ${e.message}")
            false
        }
    }

    // --- Intention Operations ---

    data class Intention(
        val id: String,
        val description: String,
        val triggerType: String,
        val triggerValue: String,
        val status: String,
        val fireCount: Int,
        val maxFires: Int,
        val goalId: String?,
        val createdAt: String
    )

    suspend fun setIntention(
        description: String,
        triggerType: String,
        triggerValue: String,
        maxFires: Int = 1,
        goalId: String? = null
    ): Intention? = withContext(Dispatchers.IO) {
        try {
            val body = JSONObject().apply {
                put("description", description)
                put("trigger_type", triggerType)
                put("trigger_value", triggerValue)
                put("max_fires", maxFires)
                if (goalId != null) put("goal_id", goalId)
            }
            val response = callTool("agenda_set_intention", body)
            response?.let { parseIntention(it) }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to set intention: ${e.message}")
            null
        }
    }

    suspend fun getDueIntentions(): List<Intention> = withContext(Dispatchers.IO) {
        try {
            val response = callTool("agenda_get_due", JSONObject())
            response?.let { parseIntentionList(it) } ?: emptyList()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get due intentions: ${e.message}")
            emptyList()
        }
    }

    suspend fun cancelIntention(intentionId: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val body = JSONObject().apply { put("intention_id", intentionId) }
            callTool("agenda_cancel_intention", body)
            true
        } catch (e: Exception) {
            Log.w(TAG, "Failed to cancel intention: ${e.message}")
            false
        }
    }

    // --- Learned Behavior Operations ---

    data class LearnedBehavior(
        val id: String,
        val pattern: String,
        val confidence: Float,
        val sourceSessions: List<String>,
        val timesConfirmed: Int,
        val createdAt: String,
        val lastConfirmed: String
    )

    suspend fun learn(
        pattern: String,
        confidence: Float = 0.5f,
        sessionId: String? = null
    ): LearnedBehavior? = withContext(Dispatchers.IO) {
        try {
            val body = JSONObject().apply {
                put("pattern", pattern)
                put("confidence", confidence.toDouble())
                if (sessionId != null) put("session_id", sessionId)
            }
            val response = callTool("agenda_learn", body)
            response?.let { parseBehavior(it) }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to store learned behavior: ${e.message}")
            null
        }
    }

    suspend fun getBehaviors(
        minConfidence: Float = 0.3f,
        limit: Int = 20
    ): List<LearnedBehavior> = withContext(Dispatchers.IO) {
        try {
            val body = JSONObject().apply {
                put("min_confidence", minConfidence.toDouble())
                put("limit", limit)
            }
            val response = callTool("agenda_get_behaviors", body)
            response?.let { parseBehaviorList(it) } ?: emptyList()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get behaviors: ${e.message}")
            emptyList()
        }
    }

    // --- Context for Pre-Prompt ---

    /**
     * Get full agenda context summary formatted for CLAUDE.md injection.
     */
    suspend fun getContext(): String = withContext(Dispatchers.IO) {
        try {
            val response = callTool("agenda_get_context", JSONObject())
            response?.optString("context", "") ?: ""
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get agenda context: ${e.message}")
            ""
        }
    }

    /**
     * Quick check: are there any due intentions?
     */
    suspend fun hasDueIntentions(): Boolean {
        return getDueIntentions().isNotEmpty()
    }

    /**
     * Get an agenda snapshot for ContextSynthesizer.
     */
    suspend fun getSnapshot(): AgendaSnapshot = withContext(Dispatchers.IO) {
        val goals = listGoals(status = "active", limit = 10)
        val dueIntentions = getDueIntentions()
        val behaviors = getBehaviors(minConfidence = 0.5f, limit = 10)
        AgendaSnapshot(goals, dueIntentions, behaviors)
    }

    data class AgendaSnapshot(
        val activeGoals: List<Goal>,
        val dueIntentions: List<Intention>,
        val topBehaviors: List<LearnedBehavior>
    )

    // --- Private Helpers ---

    private fun callTool(toolName: String, arguments: JSONObject): JSONObject? {
        val url = URL("$baseUrl/tools/$toolName")
        val connection = url.openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.setRequestProperty("Content-Type", "application/json")
        connection.doOutput = true
        connection.connectTimeout = TIMEOUT_MS
        connection.readTimeout = TIMEOUT_MS

        try {
            connection.outputStream.bufferedWriter().use { it.write(arguments.toString()) }

            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                val responseText = connection.inputStream.bufferedReader().readText()
                return JSONObject(responseText)
            }
            Log.w(TAG, "Tool $toolName failed: HTTP ${connection.responseCode}")
            return null
        } catch (e: Exception) {
            Log.w(TAG, "Tool $toolName error: ${e.message}")
            return null
        } finally {
            connection.disconnect()
        }
    }

    private fun parseGoal(json: JSONObject): Goal {
        val stepsArray = json.optJSONArray("steps") ?: JSONArray()
        val steps = (0 until stepsArray.length()).map { i ->
            val step = stepsArray.getJSONObject(i)
            GoalStep(
                description = step.getString("description"),
                status = step.optString("status", "pending"),
                result = step.optString("result", null).takeIf { it != "null" }
            )
        }
        return Goal(
            id = json.getString("id"),
            description = json.getString("description"),
            priority = json.getInt("priority"),
            status = json.getString("status"),
            steps = steps,
            createdAt = json.getString("created_at"),
            deadline = json.optString("deadline", null).takeIf { it != "null" },
            lastTouched = json.getString("last_touched")
        )
    }

    private fun parseGoalList(json: JSONObject): List<Goal> {
        val arr = json.optJSONArray("goals") ?: return emptyList()
        return (0 until arr.length()).map { i -> parseGoal(arr.getJSONObject(i)) }
    }

    private fun parseIntention(json: JSONObject): Intention {
        return Intention(
            id = json.getString("id"),
            description = json.getString("description"),
            triggerType = json.getString("trigger_type"),
            triggerValue = json.getString("trigger_value"),
            status = json.getString("status"),
            fireCount = json.getInt("fire_count"),
            maxFires = json.getInt("max_fires"),
            goalId = json.optString("goal_id", null).takeIf { it != "null" },
            createdAt = json.getString("created_at")
        )
    }

    private fun parseIntentionList(json: JSONObject): List<Intention> {
        val arr = json.optJSONArray("intentions") ?: return emptyList()
        return (0 until arr.length()).map { i -> parseIntention(arr.getJSONObject(i)) }
    }

    private fun parseBehavior(json: JSONObject): LearnedBehavior {
        val sessionsArray = json.optJSONArray("source_sessions") ?: JSONArray()
        val sessions = (0 until sessionsArray.length()).map { sessionsArray.getString(it) }
        return LearnedBehavior(
            id = json.getString("id"),
            pattern = json.getString("pattern"),
            confidence = json.getDouble("confidence").toFloat(),
            sourceSessions = sessions,
            timesConfirmed = json.optInt("times_confirmed", 1),
            createdAt = json.getString("created_at"),
            lastConfirmed = json.optString("last_confirmed", json.getString("created_at"))
        )
    }

    private fun parseBehaviorList(json: JSONObject): List<LearnedBehavior> {
        val arr = json.optJSONArray("behaviors") ?: return emptyList()
        return (0 until arr.length()).map { i -> parseBehavior(arr.getJSONObject(i)) }
    }
}
