package com.mobilekinetic.agent.claude

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Orchestrates context gathering from device APIs, RAG, and agenda system,
 * then produces a complete CLAUDE.md string via [ClaudeMdBuilder].
 *
 * Replaces the Python context_injector.py with native Kotlin + coroutines.
 *
 * Data sources (all on-device, fetched in parallel):
 *   - Device API  (port 5563) -- battery, wifi, bluetooth, location
 *   - RAG         (port 5562) -- semantic memory search
 *   - Agenda      (port 5567) -- goals, due intentions
 *   - Calendar    (port 5563) -- upcoming events from the Kotlin device API
 *
 * Every source is optional. If a fetch fails the section is simply omitted
 * and a warning is logged. The synthesizer never throws.
 */
class ContextSynthesizer(
    private val deviceApiUrl: String = "http://localhost:5563",
    private val ragUrl: String = "http://localhost:5562",
    private val agendaUrl: String = "http://localhost:5567",
    private val config: ContextConfig = ContextConfig()
) {

    companion object {
        private const val TAG = "ContextSynthesizer"

        /** HTTP connect/read timeout for all fetches. */
        private const val HTTP_TIMEOUT_MS = 5000
    }

    // ----------------------------------------------------------------
    // Configuration
    // ----------------------------------------------------------------

    data class ContextConfig(
        val userName: String = "Michael",
        val deviceName: String = "mK:a",
        val agentName: String = "mK:a",
        val securityLevel: String = "default",
        val maxRagMemories: Int = 10,
        val maxCalendarEvents: Int = 5,
        val maxGoals: Int = 10,
        val enableCalendar: Boolean = true,
        val enableRag: Boolean = true,
        val enableAgenda: Boolean = true
    )

    // ----------------------------------------------------------------
    // Internal snapshot containers
    // ----------------------------------------------------------------

    private data class DeviceState(
        val batteryLevel: Int,
        val isCharging: Boolean,
        val wifiNetwork: String?,
        val bluetoothDevices: List<String>,
        val location: String?,
        val currentTime: String
    )

    private data class AgendaSnapshot(
        val goals: List<AgendaGoal>,
        val dueIntentions: List<AgendaIntention>
    )

    // ----------------------------------------------------------------
    // Public API
    // ----------------------------------------------------------------

    /**
     * Build the complete CLAUDE.md content by gathering all context sources
     * in parallel. Called before every Claude Code session start AND on
     * heartbeat ticks when a full refresh is warranted.
     *
     * @param conversationHint  Optional search hint for RAG (e.g. latest
     *   user message) to retrieve the most relevant memories.
     * @return  Fully rendered CLAUDE.md string ready to write to disk.
     */
    suspend fun synthesize(conversationHint: String? = null): String =
        withContext(Dispatchers.IO) {
            coroutineScope {
                // Fire all fetches concurrently
                val deviceDeferred = async { fetchDeviceState() }
                val ragDeferred = if (config.enableRag) {
                    async { fetchRagMemories(conversationHint) }
                } else null
                val agendaDeferred = if (config.enableAgenda) {
                    async { fetchAgenda() }
                } else null
                val calendarDeferred = if (config.enableCalendar) {
                    async { fetchCalendar() }
                } else null

                // Await all results (each handles its own errors)
                val device = deviceDeferred.await()
                val memories = ragDeferred?.await() ?: emptyList()
                val agenda = agendaDeferred?.await() ?: AgendaSnapshot(emptyList(), emptyList())
                val calendar = calendarDeferred?.await() ?: emptyList()

                // Assemble via builder
                buildClaudeMd(device, memories, agenda, calendar)
            }
        }

    /**
     * Quick refresh -- only update device state and check for due agenda items.
     * Used for heartbeat ticks where full synthesis is too expensive.
     *
     * @return  Lightweight CLAUDE.md string with device state + due intentions.
     */
    suspend fun quickRefresh(): String = withContext(Dispatchers.IO) {
        coroutineScope {
            val deviceDeferred = async { fetchDeviceState() }
            val agendaDeferred = if (config.enableAgenda) {
                async { fetchDueOnly() }
            } else null

            val device = deviceDeferred.await()
            val dueIntentions = agendaDeferred?.await() ?: emptyList()
            val agenda = AgendaSnapshot(goals = emptyList(), dueIntentions = dueIntentions)

            buildClaudeMd(device, emptyList(), agenda, emptyList())
        }
    }

    // ----------------------------------------------------------------
    // Builder assembly
    // ----------------------------------------------------------------

    private fun buildClaudeMd(
        device: DeviceState,
        memories: List<RagMemory>,
        agenda: AgendaSnapshot,
        calendar: List<CalendarEvent>
    ): String {
        val builder = ClaudeMdBuilder()
            .identity(
                name = config.agentName,
                deviceName = config.deviceName,
                userName = config.userName
            )
            .guardrailRules(
                securityLevel = config.securityLevel,
                rules = defaultGuardrails()
            )
            .deviceContext(
                batteryLevel = device.batteryLevel,
                isCharging = device.isCharging,
                wifiNetwork = device.wifiNetwork,
                bluetoothDevices = device.bluetoothDevices,
                location = device.location,
                currentTime = device.currentTime
            )
            .activeAgenda(
                goals = agenda.goals,
                dueIntentions = agenda.dueIntentions
            )
            .ragMemories(memories)
            .calendarContext(calendar)

        return builder.build()
    }

    private fun defaultGuardrails(): List<String> = listOf(
        "NEVER access /sdcard or /storage/emulated -- hangs permanently",
        "NEVER run termux-* commands -- no IPC bridge, hangs forever",
        "NEVER install latest claude-code -- pin v2.0.37 (arm64 compat)",
        "NEVER use pkg/apt -- broken paths, use pip or manual install",
        "NEVER use the Grep tool -- ripgrep not installed, will hang; use bash grep",
        "Always query RAG before acting on new tasks",
        "Build tools in ~/tools/, document them in RAG"
    )

    // ----------------------------------------------------------------
    // Data fetchers -- each catches its own errors
    // ----------------------------------------------------------------

    private fun fetchDeviceState(): DeviceState {
        val now = SimpleDateFormat("yyyy-MM-dd HH:mm:ss z", Locale.US).format(Date())

        // Battery
        val battery = try {
            val json = JSONObject(httpGet("$deviceApiUrl/battery"))
            Pair(json.optInt("level", -1), json.optBoolean("charging", false))
        } catch (e: Exception) {
            Log.w(TAG, "Failed to fetch battery: ${e.message}")
            Pair(-1, false)
        }

        // WiFi
        val wifi = try {
            val json = JSONObject(httpGet("$deviceApiUrl/wifi"))
            json.optString("ssid", null)?.takeIf { it.isNotBlank() && it != "<unknown ssid>" }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to fetch wifi: ${e.message}")
            null
        }

        // Bluetooth
        val bluetooth = try {
            val json = JSONObject(httpGet("$deviceApiUrl/bluetooth"))
            val arr = json.optJSONArray("devices") ?: JSONArray()
            (0 until arr.length()).mapNotNull { i ->
                arr.optJSONObject(i)?.optString("name")?.takeIf { it.isNotBlank() }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to fetch bluetooth: ${e.message}")
            emptyList()
        }

        // Location (may not be available)
        val location = try {
            val json = JSONObject(httpGet("$deviceApiUrl/location"))
            val lat = json.optDouble("latitude", Double.NaN)
            val lon = json.optDouble("longitude", Double.NaN)
            if (!lat.isNaN() && !lon.isNaN()) "%.4f, %.4f".format(lat, lon) else null
        } catch (e: Exception) {
            Log.w(TAG, "Failed to fetch location: ${e.message}")
            null
        }

        return DeviceState(
            batteryLevel = battery.first,
            isCharging = battery.second,
            wifiNetwork = wifi,
            bluetoothDevices = bluetooth,
            location = location,
            currentTime = now
        )
    }

    private fun fetchRagMemories(hint: String?): List<RagMemory> {
        return try {
            val query = hint ?: "recent activity current context"
            val payload = JSONObject().apply {
                put("query", query)
                put("top_k", config.maxRagMemories)
            }
            val responseText = httpPost("$ragUrl/search", payload.toString())
            val json = JSONObject(responseText)

            // The RAG server returns { "results": [ { "text": "...", "category": "...", "score": 0.85 }, ... ] }
            val results = json.optJSONArray("results") ?: return emptyList()
            (0 until results.length()).map { i ->
                val obj = results.getJSONObject(i)
                RagMemory(
                    content = obj.optString("text", obj.optString("content", "")),
                    category = obj.optString("category", "general"),
                    relevance = obj.optDouble("score", obj.optDouble("relevance", 0.5)).toFloat()
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to fetch RAG memories: ${e.message}")
            emptyList()
        }
    }

    private fun fetchAgenda(): AgendaSnapshot {
        val goals = try {
            val responseText = httpGet("$agendaUrl/tools/agenda_list_goals")
            val arr = JSONArray(responseText)
            (0 until minOf(arr.length(), config.maxGoals)).map { i ->
                val obj = arr.getJSONObject(i)
                AgendaGoal(
                    id = obj.optString("id", "goal_$i"),
                    description = obj.optString("description", ""),
                    priority = obj.optInt("priority", 5),
                    status = obj.optString("status", "active"),
                    steps = parseStringArray(obj.optJSONArray("steps"))
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to fetch agenda goals: ${e.message}")
            emptyList()
        }

        val dueIntentions = fetchDueOnly()

        return AgendaSnapshot(goals = goals, dueIntentions = dueIntentions)
    }

    /** Fetch only due intentions -- used by both full and quick refresh. */
    private fun fetchDueOnly(): List<AgendaIntention> {
        return try {
            val responseText = httpGet("$agendaUrl/tools/agenda_get_due")
            val arr = JSONArray(responseText)
            (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                AgendaIntention(
                    id = obj.optString("id", "int_$i"),
                    description = obj.optString("description", ""),
                    triggerTime = obj.optString("trigger_time", obj.optString("due_at", "")),
                    recurring = obj.optBoolean("recurring", false)
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to fetch due intentions: ${e.message}")
            emptyList()
        }
    }

    private fun fetchCalendar(): List<CalendarEvent> {
        return try {
            val responseText = httpGet("$deviceApiUrl/calendar/events")
            val arr = JSONArray(responseText)
            val limit = minOf(arr.length(), config.maxCalendarEvents)
            (0 until limit).map { i ->
                val obj = arr.getJSONObject(i)
                CalendarEvent(
                    title = obj.optString("title", "Untitled"),
                    startTime = obj.optString("start", obj.optString("dtstart", "")),
                    endTime = obj.optString("end", obj.optString("dtend", "")),
                    location = obj.optString("location", null)?.takeIf { it.isNotBlank() }
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to fetch calendar events: ${e.message}")
            emptyList()
        }
    }

    // ----------------------------------------------------------------
    // HTTP helpers
    // ----------------------------------------------------------------

    private fun httpGet(url: String): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = HTTP_TIMEOUT_MS
        connection.readTimeout = HTTP_TIMEOUT_MS
        try {
            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                return connection.inputStream.bufferedReader().readText()
            }
            throw RuntimeException("HTTP ${connection.responseCode}: ${connection.responseMessage}")
        } finally {
            connection.disconnect()
        }
    }

    private fun httpPost(url: String, jsonBody: String): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.connectTimeout = HTTP_TIMEOUT_MS
        connection.readTimeout = HTTP_TIMEOUT_MS
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/json")
        try {
            connection.outputStream.bufferedWriter().use { it.write(jsonBody) }
            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                return connection.inputStream.bufferedReader().readText()
            }
            throw RuntimeException("HTTP ${connection.responseCode}: ${connection.responseMessage}")
        } finally {
            connection.disconnect()
        }
    }

    // ----------------------------------------------------------------
    // JSON helpers
    // ----------------------------------------------------------------

    private fun parseStringArray(arr: JSONArray?): List<String> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            arr.optString(i)?.takeIf { it.isNotBlank() }
        }
    }
}
