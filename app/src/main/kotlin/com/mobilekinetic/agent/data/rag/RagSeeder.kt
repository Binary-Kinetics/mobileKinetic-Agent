package com.mobilekinetic.agent.data.rag

import android.content.Context
import android.util.Log
import kotlinx.coroutines.delay
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

class RagSeeder(private val context: Context) {
    companion object {
        private const val TAG = "RagSeeder"
        private const val RAG_URL = "http://127.0.0.1:5562"
        private const val PREFS_NAME = "rag_seeder"
        private const val KEY_LAST_VERSION = "last_seeded_version"
    }

    suspend fun checkAndSeed() {
        try {
            // 1. Wait for RAG server to be ready (up to 30 seconds)
            var healthJson: String? = null
            for (attempt in 1..15) {
                healthJson = httpGet("$RAG_URL/health")
                if (healthJson != null) break
                Log.d(TAG, "RAG server not ready, retry $attempt/15...")
                delay(2000)
            }
            if (healthJson == null) {
                Log.w(TAG, "RAG server not reachable after 30s, skipping seed")
                return
            }
            val health = JSONObject(healthJson)
            val memoryCount = health.optInt("memories", health.optInt("total_memories", 0))

            // 2. Read seed file
            val seedJson = context.assets.open("rag_seed.json").bufferedReader().use { it.readText() }
            val seed = JSONObject(seedJson)
            val seedVersion = seed.getInt("version")
            val entries = seed.getJSONArray("entries")

            // 3. Check if seeding needed
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val lastVersion = prefs.getInt(KEY_LAST_VERSION, 0)

            if (memoryCount >= 10 && seedVersion <= lastVersion) {
                Log.i(TAG, "Seed not needed (memories=$memoryCount, seedVersion=$seedVersion, lastVersion=$lastVersion)")
                return
            }

            Log.i(TAG, "Starting seed: ${entries.length()} entries (memories=$memoryCount, seedVersion=$seedVersion, lastVersion=$lastVersion)")

            // 4. Seed entries
            var successCount = 0
            for (i in 0 until entries.length()) {
                val entry = entries.getJSONObject(i)
                val body = JSONObject().apply {
                    put("text", entry.getString("text"))
                    put("category", entry.getString("category"))
                    put("metadata", entry.getJSONObject("metadata"))
                }
                val result = httpPost("$RAG_URL/memory", body.toString())
                if (result != null) successCount++
                if (i % 10 == 9) Log.i(TAG, "Seeded ${i + 1}/${entries.length()} entries")
                delay(100)
            }

            // 5. Store version
            prefs.edit().putInt(KEY_LAST_VERSION, seedVersion).apply()
            Log.i(TAG, "Seed complete: $successCount/${entries.length()} entries stored, version=$seedVersion")

        } catch (e: Exception) {
            Log.e(TAG, "Seed failed (non-fatal)", e)
        }
    }

    private fun httpGet(url: String): String? {
        return try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 3000
            conn.readTimeout = 5000
            conn.requestMethod = "GET"
            if (conn.responseCode == 200) {
                conn.inputStream.bufferedReader().use { it.readText() }
            } else null
        } catch (e: Exception) {
            null
        }
    }

    private fun httpPost(url: String, body: String): String? {
        return try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 3000
            conn.readTimeout = 5000
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true
            OutputStreamWriter(conn.outputStream).use { it.write(body) }
            if (conn.responseCode in 200..299) {
                conn.inputStream.bufferedReader().use { it.readText() }
            } else null
        } catch (e: Exception) {
            null
        }
    }
}
