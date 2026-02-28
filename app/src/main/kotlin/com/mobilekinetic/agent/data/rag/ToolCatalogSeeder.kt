package com.mobilekinetic.agent.data.rag

import android.content.Context
import android.util.Log
import com.mobilekinetic.agent.data.db.dao.ToolDao
import com.mobilekinetic.agent.data.db.entity.ToolEntity
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

class ToolCatalogSeeder(
    private val context: Context,
    private val toolDao: ToolDao
) {
    companion object {
        private const val TAG = "ToolCatalogSeeder"
        private const val PREFS_NAME = "tool_catalog_seeder"
        private const val KEY_SEED_VERSION = "tool_catalog_seed_version"
        private const val CURRENT_SEED_VERSION = 2

        private val PREFIX_MAP = listOf(
            "sms_" to "COMMUNICATION", "call_" to "COMMUNICATION", "contacts_" to "COMMUNICATION",
            "camera_" to "MEDIA", "media_" to "MEDIA", "audio_" to "MEDIA", "tts_" to "MEDIA",
            "volume_" to "DEVICE_CONTROL", "display_" to "DEVICE_CONTROL", "brightness_" to "DEVICE_CONTROL",
            "battery_" to "SYSTEM", "app_" to "SYSTEM", "clipboard_" to "SYSTEM", "toast_" to "SYSTEM",
            "wifi_" to "NETWORK", "bluetooth_" to "NETWORK", "network_" to "NETWORK",
            "calendar_" to "CALENDAR",
            "task_" to "TASKS", "alarm_" to "TASKS", "timer_" to "TASKS",
            "file_" to "FILES", "storage_" to "FILES",
            "location_" to "LOCATION", "gps_" to "LOCATION",
            "lamp_" to "HOME_AUTOMATION", "light_" to "HOME_AUTOMATION", "home_" to "HOME_AUTOMATION",
            "rag_" to "AI", "embedding_" to "AI", "gemma_" to "AI",
            "vault_" to "SECURITY", "biometric_" to "SECURITY",
            "shell_" to "SHELL", "exec_" to "SHELL",
            "tasker_" to "TASKER",
            "notification_" to "NOTIFICATION", "notify_" to "NOTIFICATION"
        ).sortedByDescending { it.first.length }
    }

    suspend fun seedIfNeeded() {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val lastVersion = prefs.getInt(KEY_SEED_VERSION, 0)
        val toolCount = toolDao.getToolCount()

        if (lastVersion >= CURRENT_SEED_VERSION && toolCount > 0) {
            Log.i(TAG, "Seed not needed (version=$lastVersion, tools=$toolCount)")
            return
        }

        Log.i(TAG, "Starting tool catalog seed (lastVersion=$lastVersion, tools=$toolCount)")

        val entities = LinkedHashMap<String, ToolEntity>()

        // Parse rag_seed.json first (lower priority — Tasker overwrites dupes)
        parseRagSeed(entities)

        // Parse toolDescriptions.json second (higher priority, richer schemas)
        parseTaskerTools(entities)

        val toolList = entities.values.toList()
        if (toolList.isNotEmpty()) {
            try {
                toolDao.insertToolsIfAbsent(toolList)
                Log.i(TAG, "Inserted ${toolList.size} tools into catalog")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to insert ${toolList.size} tools into DB", e)
                return  // Don't mark as seeded if insert failed
            }
        } else {
            Log.w(TAG, "No tools parsed from seed files -- nothing to insert")
        }

        // Verify the insert actually worked
        val finalCount = toolDao.getToolCount()
        if (finalCount > 0) {
            prefs.edit().putInt(KEY_SEED_VERSION, CURRENT_SEED_VERSION).apply()
            Log.i(TAG, "Seed complete: $finalCount tools in database")
        } else {
            Log.e(TAG, "Seed appeared to succeed but toolCount is still 0 -- not marking as seeded")
        }
    }

    private fun parseRagSeed(entities: LinkedHashMap<String, ToolEntity>) {
        try {
            val json = context.assets.open("rag_seed.json").bufferedReader().use { it.readText() }
            val root = JSONObject(json)
            val entries = root.getJSONArray("entries")

            var count = 0
            for (i in 0 until entries.length()) {
                val entry = entries.getJSONObject(i)
                if (entry.getString("category") != "tool") continue

                val text = entry.getString("text")
                val parts = text.split("|").map { it.trim() }
                if (parts.size < 4) continue

                val toolName = parts[1]
                val description = parts[2]
                val paramsRaw = parts[3]
                val schemaJson = buildSchemaFromParams(paramsRaw)
                val category = inferCategory(toolName)

                entities[toolName] = ToolEntity(
                    id = UUID.randomUUID().toString(),
                    name = toolName.replace("_", " ").replaceFirstChar { it.uppercase() },
                    description = description,
                    executionType = "mcp_tool",
                    schemaJson = schemaJson,
                    category = category,
                    source = "RAG_SEED",
                    technicalName = toolName
                )
                count++
            }
            Log.i(TAG, "Parsed $count tools from rag_seed.json")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse rag_seed.json", e)
        }
    }

    private fun parseTaskerTools(entities: LinkedHashMap<String, ToolEntity>) {
        try {
            val json = context.assets.open("scripts/config/toolDescriptions.json")
                .bufferedReader().use { it.readText() }
            val arr = JSONArray(json)

            var count = 0
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val taskerName = obj.getString("tasker_name")
                val name = obj.getString("name")
                val description = obj.getString("description")
                val inputSchema = obj.getJSONObject("inputSchema")
                val category = inferCategory(name)

                entities[name] = ToolEntity(
                    id = UUID.randomUUID().toString(),
                    name = name.replace("_", " ").replaceFirstChar { it.uppercase() },
                    description = description,
                    executionType = "tasker",
                    schemaJson = inputSchema.toString(),
                    category = category,
                    source = "TASKER",
                    technicalName = name
                )
                count++
            }
            Log.i(TAG, "Parsed $count tools from toolDescriptions.json")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse toolDescriptions.json", e)
        }
    }

    private fun buildSchemaFromParams(paramsRaw: String): String {
        if (paramsRaw == "(none)" || paramsRaw.isBlank()) {
            return """{"type":"object","properties":{}}"""
        }

        val props = JSONObject()
        val required = mutableListOf<String>()

        for (param in paramsRaw.split(" ")) {
            val trimmed = param.trim()
            if (trimmed.isEmpty()) continue

            val isRequired = trimmed.endsWith("!")
            val isOptional = trimmed.endsWith("?")
            val cleaned = trimmed.removeSuffix("!").removeSuffix("?")
            val colonIdx = cleaned.indexOf(':')

            val paramName: String
            val paramType: String
            if (colonIdx > 0) {
                paramName = cleaned.substring(0, colonIdx)
                paramType = when (cleaned.substring(colonIdx + 1)) {
                    "s" -> "string"
                    "i" -> "integer"
                    "f" -> "number"
                    "b" -> "boolean"
                    else -> "string"
                }
            } else {
                paramName = cleaned
                paramType = "string"
            }

            props.put(paramName, JSONObject().apply { put("type", paramType) })
            if (isRequired) required.add(paramName)
        }

        val schema = JSONObject().apply {
            put("type", "object")
            put("properties", props)
            if (required.isNotEmpty()) put("required", JSONArray(required))
        }
        return schema.toString()
    }

    private fun inferCategory(technicalName: String): String {
        for ((prefix, category) in PREFIX_MAP) {
            if (technicalName.startsWith(prefix)) return category
        }
        return "OTHER"
    }
}
