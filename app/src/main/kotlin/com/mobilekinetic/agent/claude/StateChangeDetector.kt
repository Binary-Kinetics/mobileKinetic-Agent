package com.mobilekinetic.agent.claude

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Detects significant device state changes between heartbeat ticks.
 * Compares current device state snapshot to the previous one and reports
 * what changed — used by HeartbeatScheduler to decide whether a self-prompt
 * is warranted.
 *
 * Significant changes that warrant a self-prompt:
 * - New SMS/notification received
 * - Calendar event starting within 15 min
 * - Battery critical (<15%)
 * - WiFi network changed (home arrival/departure)
 * - Task deadline approaching
 * - User idle >1 hour (optional check-in)
 */
class StateChangeDetector(
    private val deviceApiUrl: String = "http://localhost:5563"
) {
    companion object {
        private const val TAG = "StateChangeDetector"
        private const val BATTERY_CRITICAL_THRESHOLD = 15
        private const val CALENDAR_SOON_MINUTES = 15
        private const val IDLE_THRESHOLD_MINUTES = 60
    }

    /**
     * Snapshot of device state at a point in time.
     */
    data class DeviceSnapshot(
        val batteryLevel: Int = -1,
        val isCharging: Boolean = false,
        val wifiNetwork: String? = null,
        val notificationCount: Int = 0,
        val unreadSmsCount: Int = 0,
        val nextCalendarEventMinutes: Int = -1,
        val screenOn: Boolean = false,
        val lastUserInteractionMinutesAgo: Int = 0,
        val timestamp: Long = System.currentTimeMillis()
    )

    /**
     * Describes a significant change detected between two snapshots.
     */
    data class StateChange(
        val type: ChangeType,
        val description: String,
        val severity: Severity = Severity.NORMAL
    )

    enum class ChangeType {
        BATTERY_CRITICAL,
        BATTERY_CHARGING_CHANGED,
        WIFI_CHANGED,
        NEW_NOTIFICATIONS,
        NEW_SMS,
        CALENDAR_EVENT_SOON,
        USER_IDLE,
        SCREEN_STATE_CHANGED
    }

    enum class Severity {
        LOW,
        NORMAL,
        HIGH,
        URGENT
    }

    private var lastSnapshot: DeviceSnapshot? = null

    /**
     * Take a new device state snapshot and compare with the previous one.
     * Returns a list of significant changes, empty if nothing noteworthy happened.
     */
    suspend fun detectChanges(): List<StateChange> {
        val current = takeSnapshot()
        val previous = lastSnapshot
        lastSnapshot = current

        if (previous == null) {
            // First snapshot — check for immediately actionable states
            return detectInitialState(current)
        }

        return compareSnapshots(previous, current)
    }

    /**
     * Check if the current state warrants a self-prompt (any significant changes).
     */
    suspend fun shouldSelfPrompt(): Boolean {
        return detectChanges().isNotEmpty()
    }

    /**
     * Get a formatted summary of detected changes for injection into a self-prompt.
     */
    suspend fun getChangeSummary(): String? {
        val changes = detectChanges()
        if (changes.isEmpty()) return null

        return buildString {
            appendLine("## Device State Changes Detected")
            changes.forEach { change ->
                val severityIcon = when (change.severity) {
                    Severity.URGENT -> "[URGENT]"
                    Severity.HIGH -> "[HIGH]"
                    Severity.NORMAL -> ""
                    Severity.LOW -> ""
                }
                appendLine("- $severityIcon ${change.description}")
            }
        }.trim()
    }

    /**
     * Force reset — clears the last snapshot so next detection treats everything as new.
     */
    fun reset() {
        lastSnapshot = null
    }

    /**
     * Get the last known snapshot without taking a new one.
     */
    fun getLastSnapshot(): DeviceSnapshot? = lastSnapshot

    // --- Private ---

    private suspend fun takeSnapshot(): DeviceSnapshot = withContext(Dispatchers.IO) {
        try {
            val json = httpGet("$deviceApiUrl/device/state")
            parseDeviceState(json)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to fetch device state: ${e.message}")
            DeviceSnapshot() // Return empty snapshot on failure
        }
    }

    private fun parseDeviceState(json: String): DeviceSnapshot {
        return try {
            val obj = JSONObject(json)
            DeviceSnapshot(
                batteryLevel = obj.optInt("battery_level", -1),
                isCharging = obj.optBoolean("is_charging", false),
                wifiNetwork = obj.optString("wifi_network", null)
                    .takeIf { !it.isNullOrBlank() && it != "null" },
                notificationCount = obj.optInt("notification_count", 0),
                unreadSmsCount = obj.optInt("unread_sms_count", 0),
                nextCalendarEventMinutes = obj.optInt("next_event_minutes", -1),
                screenOn = obj.optBoolean("screen_on", false),
                lastUserInteractionMinutesAgo = obj.optInt("idle_minutes", 0)
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse device state JSON: ${e.message}")
            DeviceSnapshot()
        }
    }

    private fun detectInitialState(snapshot: DeviceSnapshot): List<StateChange> {
        val changes = mutableListOf<StateChange>()

        if (snapshot.batteryLevel in 1 until BATTERY_CRITICAL_THRESHOLD && !snapshot.isCharging) {
            changes.add(StateChange(
                ChangeType.BATTERY_CRITICAL,
                "Battery critical at ${snapshot.batteryLevel}% and not charging",
                Severity.URGENT
            ))
        }

        if (snapshot.nextCalendarEventMinutes in 1..CALENDAR_SOON_MINUTES) {
            changes.add(StateChange(
                ChangeType.CALENDAR_EVENT_SOON,
                "Calendar event starting in ${snapshot.nextCalendarEventMinutes} minutes",
                Severity.HIGH
            ))
        }

        if (snapshot.unreadSmsCount > 0) {
            changes.add(StateChange(
                ChangeType.NEW_SMS,
                "${snapshot.unreadSmsCount} unread SMS messages",
                Severity.NORMAL
            ))
        }

        return changes
    }

    private fun compareSnapshots(prev: DeviceSnapshot, curr: DeviceSnapshot): List<StateChange> {
        val changes = mutableListOf<StateChange>()

        // Battery critical transition
        if (curr.batteryLevel in 1 until BATTERY_CRITICAL_THRESHOLD &&
            !curr.isCharging &&
            (prev.batteryLevel >= BATTERY_CRITICAL_THRESHOLD || prev.isCharging)) {
            changes.add(StateChange(
                ChangeType.BATTERY_CRITICAL,
                "Battery dropped to critical: ${curr.batteryLevel}%",
                Severity.URGENT
            ))
        }

        // Charging state changed
        if (curr.isCharging != prev.isCharging) {
            val desc = if (curr.isCharging) "Device started charging" else "Device unplugged"
            changes.add(StateChange(
                ChangeType.BATTERY_CHARGING_CHANGED,
                "$desc (battery: ${curr.batteryLevel}%)",
                Severity.LOW
            ))
        }

        // WiFi network changed (home arrival/departure detection)
        if (curr.wifiNetwork != prev.wifiNetwork) {
            val desc = when {
                prev.wifiNetwork == null && curr.wifiNetwork != null ->
                    "Connected to WiFi: ${curr.wifiNetwork}"
                prev.wifiNetwork != null && curr.wifiNetwork == null ->
                    "Disconnected from WiFi (was: ${prev.wifiNetwork})"
                else ->
                    "WiFi changed: ${prev.wifiNetwork} → ${curr.wifiNetwork}"
            }
            changes.add(StateChange(
                ChangeType.WIFI_CHANGED,
                desc,
                Severity.NORMAL
            ))
        }

        // New notifications
        if (curr.notificationCount > prev.notificationCount) {
            val newCount = curr.notificationCount - prev.notificationCount
            changes.add(StateChange(
                ChangeType.NEW_NOTIFICATIONS,
                "$newCount new notification(s) received",
                Severity.NORMAL
            ))
        }

        // New SMS
        if (curr.unreadSmsCount > prev.unreadSmsCount) {
            val newCount = curr.unreadSmsCount - prev.unreadSmsCount
            changes.add(StateChange(
                ChangeType.NEW_SMS,
                "$newCount new SMS message(s) received",
                Severity.HIGH
            ))
        }

        // Calendar event approaching
        if (curr.nextCalendarEventMinutes in 1..CALENDAR_SOON_MINUTES &&
            (prev.nextCalendarEventMinutes > CALENDAR_SOON_MINUTES || prev.nextCalendarEventMinutes == -1)) {
            changes.add(StateChange(
                ChangeType.CALENDAR_EVENT_SOON,
                "Calendar event starting in ${curr.nextCalendarEventMinutes} minutes",
                Severity.HIGH
            ))
        }

        // User idle detection
        if (curr.lastUserInteractionMinutesAgo >= IDLE_THRESHOLD_MINUTES &&
            prev.lastUserInteractionMinutesAgo < IDLE_THRESHOLD_MINUTES) {
            changes.add(StateChange(
                ChangeType.USER_IDLE,
                "User idle for ${curr.lastUserInteractionMinutesAgo} minutes",
                Severity.LOW
            ))
        }

        return changes
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
            throw RuntimeException("HTTP ${connection.responseCode}: ${connection.responseMessage}")
        } finally {
            connection.disconnect()
        }
    }
}
