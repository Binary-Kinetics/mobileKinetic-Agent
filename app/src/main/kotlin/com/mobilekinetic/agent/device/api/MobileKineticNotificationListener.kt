package com.mobilekinetic.agent.device.api

import android.app.Notification
import android.app.RemoteInput
import android.content.ComponentName
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * mK:a Notification Listener - provides read access to all device notifications
 * and the ability to dismiss or reply to them.
 *
 * Capabilities:
 * - List all active (posted) notifications with metadata
 * - Dismiss individual notifications by key
 * - Dismiss all notifications
 * - Reply to messaging-style notifications via RemoteInput
 *
 * The service stores itself as a companion object singleton so DeviceApiServer
 * can access it directly without binding.
 *
 * Requires the user to grant Notification Access in:
 *   Settings > Apps & notifications > Special app access > Notification access
 */
class MobileKineticNotificationListener : NotificationListenerService() {

    companion object {
        private const val TAG = "mK:aNotifLstnr"

        @Volatile
        var instance: MobileKineticNotificationListener? = null
            private set

        fun isRunning(): Boolean = instance != null

        /**
         * Check whether the app has notification listener permission enabled
         * in system settings. This does NOT require a running instance.
         */
        fun isEnabled(context: android.content.Context): Boolean {
            val cn = ComponentName(context, MobileKineticNotificationListener::class.java)
            val flat = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
            return flat != null && flat.contains(cn.flattenToString())
        }
    }

    // ==================== LIFECYCLE ====================

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
        Log.i(TAG, "Notification listener connected")
    }

    override fun onListenerDisconnected() {
        instance = null
        Log.i(TAG, "Notification listener disconnected")
        super.onListenerDisconnected()
    }

    override fun onDestroy() {
        instance = null
        Log.i(TAG, "Notification listener destroyed")
        super.onDestroy()
    }

    // ==================== NOTIFICATION QUERIES ====================

    /**
     * Get all currently active (posted) notifications as a JSONArray.
     * Each notification includes: key, package, title, text, subText, time,
     * bigText, category, group, isOngoing, isClearable, and available actions.
     */
    fun getActiveNotificationsJson(): JSONArray {
        val result = JSONArray()
        try {
            val sbns = activeNotifications ?: return result
            for (sbn in sbns) {
                result.put(sbnToJson(sbn))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting active notifications", e)
        }
        return result
    }

    /**
     * Convert a single StatusBarNotification to a JSONObject with all useful fields.
     * Includes StatusBarNotification metadata, Notification extras, and action details.
     */
    private fun sbnToJson(sbn: StatusBarNotification): JSONObject {
        val extras = sbn.notification.extras
        val json = JSONObject().apply {
            // === StatusBarNotification identity fields ===
            put("key", sbn.key)
            put("id", sbn.id)
            put("package", sbn.packageName)
            put("op_pkg", sbn.opPkg ?: sbn.packageName)
            put("tag", sbn.tag ?: JSONObject.NULL)
            put("uid", sbn.uid)
            put("user_id", sbn.user?.hashCode() ?: 0)
            put("post_time", sbn.postTime)

            // === StatusBarNotification group fields ===
            put("group_key", sbn.groupKey ?: "")
            put("override_group_key", sbn.overrideGroupKey ?: JSONObject.NULL)
            put("is_group", sbn.isGroup)
            put("is_app_group", sbn.isAppGroup)

            // === StatusBarNotification state flags ===
            put("is_ongoing", sbn.isOngoing)
            put("is_clearable", sbn.isClearable)

            // === Text fields from notification extras ===
            put("title", extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: "")
            put("text", extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: "")
            put("sub_text", extras?.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString() ?: "")
            put("big_text", extras?.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString() ?: "")
            put("info_text", extras?.getCharSequence(Notification.EXTRA_INFO_TEXT)?.toString() ?: "")
            put("summary_text", extras?.getCharSequence(Notification.EXTRA_SUMMARY_TEXT)?.toString() ?: "")
            put("ticker_text", sbn.notification.tickerText?.toString() ?: "")

            // === Notification metadata ===
            put("category", sbn.notification.category ?: "")
            put("group", sbn.notification.group ?: "")
            put("channel_id", sbn.notification.channelId ?: "")
            put("when", sbn.notification.`when`)
            put("color", sbn.notification.color)
            put("visibility", sbn.notification.visibility)
            put("number", sbn.notification.number)
            put("flags", sbn.notification.flags)

            // === Intent presence indicators ===
            put("has_content_intent", sbn.notification.contentIntent != null)
            put("has_delete_intent", sbn.notification.deleteIntent != null)
            put("has_full_screen_intent", sbn.notification.fullScreenIntent != null)

            // === Actions ===
            val actionCount = sbn.notification.actions?.size ?: 0
            put("action_count", actionCount)

            val actionsArray = JSONArray()
            sbn.notification.actions?.forEachIndexed { index, action ->
                val actionJson = JSONObject().apply {
                    put("index", index)
                    put("title", action.title?.toString() ?: "")
                    // Check if this action has RemoteInput (i.e., supports reply)
                    val remoteInputs = action.remoteInputs
                    put("has_remote_input", remoteInputs != null && remoteInputs.isNotEmpty())
                    if (remoteInputs != null && remoteInputs.isNotEmpty()) {
                        val inputsArray = JSONArray()
                        for (ri in remoteInputs) {
                            inputsArray.put(JSONObject().apply {
                                put("result_key", ri.resultKey)
                                put("label", ri.label?.toString() ?: "")
                            })
                        }
                        put("remote_inputs", inputsArray)
                    }
                    // Semantic action (API 28+)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        put("semantic_action", action.semanticAction)
                        put("is_contextual", action.isContextual)
                    }
                }
                actionsArray.put(actionJson)
            }
            put("actions", actionsArray)
        }
        return json
    }

    // ==================== NOTIFICATION ACTIONS ====================

    /**
     * Dismiss a single notification by its key.
     * @return true if the notification was found and dismissed, false otherwise.
     */
    fun dismissNotification(key: String): Boolean {
        return try {
            // Verify the notification exists
            val exists = activeNotifications?.any { it.key == key } == true
            if (!exists) {
                Log.w(TAG, "Notification with key '$key' not found")
                return false
            }
            cancelNotification(key)
            Log.i(TAG, "Dismissed notification: $key")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error dismissing notification: $key", e)
            false
        }
    }

    /**
     * Dismiss all clearable notifications.
     * @return the count of notifications that were active before dismissal.
     */
    fun dismissAllNotifications(): Int {
        return try {
            val count = activeNotifications?.size ?: 0
            cancelAllNotifications()
            Log.i(TAG, "Dismissed all notifications (had $count active)")
            count
        } catch (e: Exception) {
            Log.e(TAG, "Error dismissing all notifications", e)
            0
        }
    }

    /**
     * Reply to a messaging notification using RemoteInput.
     *
     * @param key The notification key
     * @param replyText The text to send as a reply
     * @param actionIndex The index of the action containing the RemoteInput (default: auto-detect first reply action)
     * @return JSONObject with success/error info
     */
    fun replyToNotification(key: String, replyText: String, actionIndex: Int? = null): JSONObject {
        val result = JSONObject()
        try {
            val sbn = activeNotifications?.find { it.key == key }
            if (sbn == null) {
                result.put("success", false)
                result.put("error", "Notification with key '$key' not found")
                return result
            }

            val actions = sbn.notification.actions
            if (actions == null || actions.isEmpty()) {
                result.put("success", false)
                result.put("error", "Notification has no actions")
                return result
            }

            // Find the target action - either by explicit index or auto-detect first with RemoteInput
            val targetAction = if (actionIndex != null) {
                if (actionIndex < 0 || actionIndex >= actions.size) {
                    result.put("success", false)
                    result.put("error", "Action index $actionIndex out of range (0..${actions.size - 1})")
                    return result
                }
                actions[actionIndex]
            } else {
                // Auto-detect: find first action with RemoteInput
                actions.firstOrNull { action ->
                    action.remoteInputs != null && action.remoteInputs.isNotEmpty()
                }
            }

            if (targetAction == null) {
                result.put("success", false)
                result.put("error", "No action with RemoteInput found on this notification")
                return result
            }

            val remoteInputs = targetAction.remoteInputs
            if (remoteInputs == null || remoteInputs.isEmpty()) {
                result.put("success", false)
                result.put("error", "Selected action has no RemoteInput")
                return result
            }

            // Build the reply intent with all RemoteInput result keys filled
            val intent = Intent()
            val bundle = Bundle()
            for (ri in remoteInputs) {
                bundle.putCharSequence(ri.resultKey, replyText)
            }
            RemoteInput.addResultsToIntent(remoteInputs, intent, bundle)

            // Fire the PendingIntent with the filled RemoteInput
            targetAction.actionIntent.send(applicationContext, 0, intent)

            result.put("success", true)
            result.put("message", "Reply sent to notification")
            result.put("notification_key", key)
            result.put("reply_text", replyText)
            Log.i(TAG, "Replied to notification $key with text: $replyText")

        } catch (e: Exception) {
            Log.e(TAG, "Error replying to notification: $key", e)
            result.put("success", false)
            result.put("error", "Failed to reply: ${e.message}")
        }
        return result
    }
}
