package com.mobilekinetic.agent.data.rag

import android.util.Log
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import com.mobilekinetic.agent.data.memory.SessionMemoryRepository
import org.json.JSONArray
import org.json.JSONObject

class RagHttpServer(
    private val ragRepository: RagRepository,
    private val sessionMemoryRepository: SessionMemoryRepository,
    port: Int = DEFAULT_PORT
) : NanoHTTPD(port) {

    companion object {
        private const val TAG = "RagHttpServer"
        const val DEFAULT_PORT = 5562
        const val MIME_JSON = "application/json"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val resolverStopWords = setOf(
        "params", "parameter", "parameters", "string", "integer", "boolean",
        "number", "optional", "required", "default", "returns", "return",
        "mcp", "tool", "device", "endpoint", "json", "type", "value",
        "specified", "response", "request", "body", "query", "header",
        "status", "success", "error", "true", "false", "null", "none",
        "get", "post", "put", "delete", "curl", "http", "https",
        "localhost", "127.0.0.1", "port", "5563", "5564", "5562",
        "the", "a", "an", "is", "are", "was", "were", "it", "its",
        "this", "that", "these", "those", "of", "in", "on", "at",
        "to", "for", "with", "and", "or", "but", "not", "be", "been",
        "being", "have", "has", "had", "do", "does", "did",
        "will", "would", "could", "should", "can", "may", "might"
    )

    private fun stripStopWords(text: String): String {
        return text.split(" ")
            .filter { it.lowercase() !in resolverStopWords }
            .joinToString(" ")
    }

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        val method = session.method

        return try {
            when {
                uri == "/health" && method == Method.GET -> handleHealth()
                uri == "/search" && method == Method.POST -> handleSearch(session)
                uri == "/context" && method == Method.POST -> handleContext(session)
                uri == "/memory" && method == Method.POST -> handleAddMemory(session)
                uri == "/memory" && method == Method.GET -> handleListMemories(session)
                uri.startsWith("/memory/") && method == Method.DELETE -> handleDeleteMemory(uri)
                uri == "/categories" && method == Method.GET -> handleCategories()
                uri == "/session_context" && method == Method.POST -> handleSessionContext()
                uri == "/capture_fact" && method == Method.POST -> handleCaptureFact(session)
                uri == "/archive_session" && method == Method.POST -> handleArchiveSession(session)
                uri == "/reindex" && method == Method.POST -> handleReindex()
                uri == "/reindex" && method == Method.GET -> handleReindexStatus()
                uri == "/facts" && method == Method.GET -> handleDumpFacts()
                uri == "/summaries" && method == Method.GET -> handleDumpSummaries()
                uri == "/rag/dump" && method == Method.GET -> handleDumpRag()
                else -> newFixedLengthResponse(
                    Response.Status.NOT_FOUND,
                    MIME_JSON,
                    """{"error": "Not found: $method $uri"}"""
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling $method $uri", e)
            newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR,
                MIME_JSON,
                """{"error": "${e.message?.replace("\"", "'")}"}"""
            )
        }
    }

    private fun handleHealth(): Response {
        val count = runBlocking { ragRepository.getDocumentCount() }
        val json = JSONObject().apply {
            put("status", "healthy")
            put("memories", count)
            put("port", DEFAULT_PORT)
            put("engine", "on-device")
            put("model", "gemma-embedding-300m")
            put("dimensions", ragRepository.embeddingDim)
            put("embedding_provider", "gemma")
            put("embedding_dim", ragRepository.embeddingDim)
            put("embedding_version", ragRepository.embeddingVersion)
        }
        return newFixedLengthResponse(Response.Status.OK, MIME_JSON, json.toString())
    }

    private fun handleSearch(session: IHTTPSession): Response {
        val body = parseBody(session)
        val json = JSONObject(body)
        val query = json.getString("query")
        val topK = json.optInt("top_k", 5)
        val minScore = json.optDouble("min_score", 0.3).toFloat()

        val category = if (json.has("category")) json.optString("category", null) else null
        val filteredQuery = stripStopWords(query)
        val results = runBlocking {
            if (category != null) {
                ragRepository.searchInCategories(query, listOf(category), topK, minScore)
            } else {
                ragRepository.search(query, topK, minScore, embeddingText = filteredQuery)
            }
        }

        val resultsArray = JSONArray()
        results.forEach { result ->
            resultsArray.put(JSONObject().apply {
                put("id", result.id)
                put("text", result.text)
                put("category", result.category)
                put("metadata", result.metadata)
                put("score", result.score.toDouble())
            })
        }

        val responseJson = JSONObject().apply {
            put("query", query)
            put("results", resultsArray)
            put("count", results.size)
        }

        return newFixedLengthResponse(Response.Status.OK, MIME_JSON, responseJson.toString())
    }

    private fun handleContext(session: IHTTPSession): Response {
        val body = parseBody(session)
        val json = JSONObject(body)
        val query = json.getString("query")
        val topK = json.optInt("top_k", 10)
        val category = if (json.has("category")) json.optString("category", null) else null

        val context = runBlocking {
            if (category != null) {
                val results = ragRepository.searchInCategories(query, listOf(category), topK)
                if (results.isEmpty()) ""
                else buildString {
                    appendLine("## RELEVANT CONTEXT FROM ON-DEVICE RAG")
                    appendLine()
                    results.forEach { result ->
                        appendLine("  [${result.category.uppercase()} - ${(result.score * 100).toInt()}% match] ${result.text}")
                        appendLine()
                    }
                }
            } else {
                ragRepository.getContext(query, topK)
            }
        }

        val responseJson = JSONObject().apply {
            put("query", query)
            put("context", context)
        }

        return newFixedLengthResponse(Response.Status.OK, MIME_JSON, responseJson.toString())
    }

    private fun handleAddMemory(session: IHTTPSession): Response {
        val body = parseBody(session)
        val json = JSONObject(body)
        val text = json.getString("text")
        val category = json.optString("category", "general")
        val metadata = json.optString("metadata", "{}")

        val filteredText = stripStopWords(text)
        val id = runBlocking {
            ragRepository.addDocument(text, category, metadata, embeddingText = filteredText)
        }

        return if (id != null) {
            val responseJson = JSONObject().apply {
                put("id", id)
                put("status", "stored")
            }
            newFixedLengthResponse(Response.Status.OK, MIME_JSON, responseJson.toString())
        } else {
            newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR,
                MIME_JSON,
                """{"error": "Failed to generate embedding"}"""
            )
        }
    }

    private fun handleListMemories(session: IHTTPSession): Response {
        val category = session.parms["category"]
        val count = runBlocking {
            if (category != null) {
                ragRepository.searchInCategories("", listOf(category), topK = 1000, minScore = 0f).size
            } else {
                ragRepository.getDocumentCount()
            }
        }
        val responseJson = JSONObject().apply {
            put("count", count)
            if (category != null) put("category", category)
        }
        return newFixedLengthResponse(Response.Status.OK, MIME_JSON, responseJson.toString())
    }

    private fun handleDeleteMemory(uri: String): Response {
        val id = uri.removePrefix("/memory/")
        if (id.isBlank()) {
            return newFixedLengthResponse(
                Response.Status.BAD_REQUEST,
                MIME_JSON,
                """{"error": "Missing memory ID"}"""
            )
        }
        runBlocking { ragRepository.deleteDocument(id) }
        val responseJson = JSONObject().apply {
            put("id", id)
            put("status", "deleted")
        }
        return newFixedLengthResponse(Response.Status.OK, MIME_JSON, responseJson.toString())
    }

    private fun handleCategories(): Response {
        val count = runBlocking { ragRepository.getDocumentCount() }
        val responseJson = JSONObject().apply {
            put("total", count)
        }
        return newFixedLengthResponse(Response.Status.OK, MIME_JSON, responseJson.toString())
    }

    private fun handleSessionContext(): Response {
        val context = runBlocking { sessionMemoryRepository.buildSessionContext() }
        val responseJson = JSONObject().apply {
            put("context", context)
        }
        return newFixedLengthResponse(Response.Status.OK, MIME_JSON, responseJson.toString())
    }

    private fun handleCaptureFact(session: IHTTPSession): Response {
        val body = parseBody(session)
        val json = JSONObject(body)
        val category = json.getString("category")
        val key = json.getString("key")
        val value = json.getString("value")
        val source: String? = if (json.has("source")) json.getString("source") else null

        runBlocking {
            sessionMemoryRepository.captureFact(
                category = category,
                key = key,
                value = value,
                source = source
            )
        }

        val responseJson = JSONObject().apply {
            put("status", "captured")
            put("key", key)
        }
        return newFixedLengthResponse(Response.Status.OK, MIME_JSON, responseJson.toString())
    }

    private fun handleArchiveSession(session: IHTTPSession): Response {
        val body = parseBody(session)
        val json = JSONObject(body)
        val sessionId = json.getString("session_id")
        val startTime = json.getLong("start_time")
        val endTime = json.getLong("end_time")
        val messageCount = json.getInt("message_count")
        val transcript = json.optString("transcript", "")

        runBlocking {
            sessionMemoryRepository.archiveSession(
                sessionId = sessionId,
                startTime = startTime,
                endTime = endTime,
                messageCount = messageCount,
                toolsUsed = emptyList(),
                errorsEncountered = 0,
                transcript = transcript
            )
        }

        val responseJson = JSONObject().apply {
            put("status", "archived")
            put("session_id", sessionId)
        }
        return newFixedLengthResponse(Response.Status.OK, MIME_JSON, responseJson.toString())
    }

    private fun handleReindex(): Response {
        val progress = ragRepository.reindexProgress
        if (progress.inProgress) {
            val json = JSONObject().apply {
                put("status", "already_running")
                put("total", progress.total)
                put("completed", progress.completed)
                put("failed", progress.failed)
            }
            return newFixedLengthResponse(Response.Status.OK, MIME_JSON, json.toString())
        }

        val missing = runBlocking { ragRepository.getReindexCount() }
        if (missing == 0) {
            val json = JSONObject().apply {
                put("status", "not_needed")
                put("message", "All documents already have embeddings")
            }
            return newFixedLengthResponse(Response.Status.OK, MIME_JSON, json.toString())
        }

        scope.launch { ragRepository.reindexMissingEmbeddings() }

        val json = JSONObject().apply {
            put("status", "started")
            put("total", missing)
        }
        return newFixedLengthResponse(Response.Status.OK, MIME_JSON, json.toString())
    }

    private fun handleReindexStatus(): Response {
        val progress = ragRepository.reindexProgress
        val json = JSONObject().apply {
            put("status", if (progress.inProgress) "running" else "idle")
            put("total", progress.total)
            put("completed", progress.completed)
            put("failed", progress.failed)
        }
        return newFixedLengthResponse(Response.Status.OK, MIME_JSON, json.toString())
    }

    // === SecondBrain dump endpoints ===

    private fun handleDumpFacts(): Response {
        val facts = runBlocking { sessionMemoryRepository.getAllFacts() }
        val arr = JSONArray()
        facts.forEach { fact ->
            arr.put(JSONObject().apply {
                put("id", fact.id)
                put("category", fact.category)
                put("key", fact.key)
                put("value", fact.value)
                put("source", fact.source ?: "")
                put("confidence", fact.confidence.toDouble())
                put("stabilityScore", fact.stabilityScore.toDouble())
                put("decayRate", fact.decayRate.toDouble())
                put("accessCount", fact.accessCount)
                put("isPinned", fact.isPinned)
                put("createdAt", fact.createdAt)
                put("updatedAt", fact.updatedAt)
            })
        }
        val json = JSONObject().apply {
            put("count", facts.size)
            put("facts", arr)
        }
        return newFixedLengthResponse(Response.Status.OK, MIME_JSON, json.toString())
    }

    private fun handleDumpSummaries(): Response {
        val summaries = runBlocking { sessionMemoryRepository.getAllSummaries() }
        val arr = JSONArray()
        summaries.forEach { s ->
            arr.put(JSONObject().apply {
                put("id", s.id)
                put("sessionId", s.sessionId)
                put("startTime", s.startTime)
                put("endTime", s.endTime)
                put("messageCount", s.messageCount)
                put("toolsUsed", s.toolsUsed)
                put("errorsEncountered", s.errorsEncountered)
                put("summaryFull", s.summaryFull)
                put("summaryKeypoints", s.summaryKeypoints ?: "")
                put("summaryTopics", s.summaryTopics ?: "")
                put("summaryFacts", s.summaryFacts ?: "")
                put("currentTier", s.currentTier)
                put("stabilityScore", s.stabilityScore.toDouble())
                put("accessCount", s.accessCount)
                put("isPinned", s.isPinned)
                put("isLandmark", s.isLandmark)
                put("createdAt", s.createdAt)
                put("updatedAt", s.updatedAt)
            })
        }
        val json = JSONObject().apply {
            put("count", summaries.size)
            put("summaries", arr)
        }
        return newFixedLengthResponse(Response.Status.OK, MIME_JSON, json.toString())
    }

    private fun handleDumpRag(): Response {
        val docs = runBlocking { ragRepository.getAllDocumentsForDump() }
        val arr = JSONArray()
        docs.forEach { doc ->
            arr.put(JSONObject().apply {
                put("id", doc.id)
                put("text", doc.text)
                put("category", doc.category)
                put("metadata", doc.metadata)
                put("createdAt", doc.createdAt)
                put("updatedAt", doc.updatedAt)
            })
        }
        val json = JSONObject().apply {
            put("count", docs.size)
            put("documents", arr)
        }
        return newFixedLengthResponse(Response.Status.OK, MIME_JSON, json.toString())
    }

    private fun parseBody(session: IHTTPSession): String {
        val files = HashMap<String, String>()
        session.parseBody(files)
        return files["postData"] ?: ""
    }

    fun startServer() {
        try {
            start(SOCKET_READ_TIMEOUT, false)
            Log.i(TAG, "RAG HTTP server started on port $DEFAULT_PORT")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start RAG HTTP server", e)
        }
    }

    fun stopServer() {
        stop()
        Log.i(TAG, "RAG HTTP server stopped")
    }
}
