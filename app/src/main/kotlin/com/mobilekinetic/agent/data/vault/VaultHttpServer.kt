package com.mobilekinetic.agent.data.vault

import android.util.Base64
import android.util.Log
import com.mobilekinetic.agent.security.CredentialGatekeeper
import com.mobilekinetic.agent.security.VaultSession
import io.ktor.client.*
import io.ktor.client.engine.cio.CIO as ClientCIO
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.gson.*
import io.ktor.server.application.*
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

class VaultHttpServer(
    private val vault: CredentialVault,
    private val gatekeeper: CredentialGatekeeper,
    private val session: VaultSession
) {

    companion object {
        private const val TAG = "VaultHttpServer"
        private const val PORT = 5565

        /** Headers that must never be returned to the caller. */
        private val STRIPPED_RESPONSE_HEADERS = setOf(
            "authorization", "set-cookie", "cookie"
        )
    }

    private var server: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null

    private val httpClient = HttpClient(ClientCIO) {
        engine {
            requestTimeout = 30_000
        }
        // Do not throw on non-2xx responses — we forward the status as-is
        expectSuccess = false
    }

    fun start() {
        server = embeddedServer(CIO, port = PORT, host = "127.0.0.1") {
            install(ContentNegotiation) {
                gson()
            }
            routing {

                // ── Health ────────────────────────────────────────────
                get("/vault/health") {
                    val count = vault.count()
                    call.respond(mapOf(
                        "status" to "ok",
                        "count" to count,
                        "locked" to !session.isUnlocked
                    ))
                }

                // ── List credential names ─────────────────────────────
                get("/vault/list") {
                    if (!requireSession()) return@get
                    val names = vault.list()
                    call.respond(mapOf("names" to names))
                }

                // ── Credential catalog (metadata, never values) ─────
                get("/vault/catalog") {
                    if (!requireSession()) return@get
                    val entries = vault.listWithMeta()
                    val catalog = entries.map { (name, desc) ->
                        val meta = CatalogMeta.parse(desc)
                        mapOf(
                            "name" to name,
                            "description" to meta.desc,
                            "injectAs" to meta.inject,
                            "service" to meta.service,
                            "contexts" to meta.contexts,
                            "hint" to meta.hint
                        )
                    }
                    call.respond(mapOf("credentials" to catalog))
                }

                // ── Store a credential ────────────────────────────────
                post("/vault/store") {
                    if (!requireSession()) return@post
                    try {
                        val body = call.receive<Map<String, String>>()
                        val name = body["name"]
                        val value = body["value"]
                        if (name.isNullOrBlank() || value.isNullOrBlank()) {
                            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "missing_name_or_value"))
                            return@post
                        }
                        vault.store(name, value, body["description"])
                        Log.i(TAG, "vault/store name=$name")
                        call.respond(mapOf("status" to "stored"))
                    } catch (e: Exception) {
                        Log.e(TAG, "vault/store failed", e)
                        call.respond(HttpStatusCode.InternalServerError, mapOf("error" to e.message))
                    }
                }

                // ── Delete a credential ───────────────────────────────
                delete("/vault/delete") {
                    if (!requireSession()) return@delete
                    val name = call.parameters["name"]
                    if (name.isNullOrBlank()) {
                        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "missing_name"))
                        return@delete
                    }
                    val deleted = vault.delete(name)
                    if (deleted) {
                        Log.i(TAG, "vault/delete name=$name")
                        call.respond(mapOf("status" to "deleted"))
                    } else {
                        call.respond(HttpStatusCode.NotFound, mapOf("error" to "not_found"))
                    }
                }

                // ── Credential-injecting proxy ────────────────────────
                post("/vault/proxy-http") {
                    if (!requireSession()) return@post

                    val body: Map<String, Any?>
                    try {
                        body = call.receive()
                    } catch (e: Exception) {
                        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid_json"))
                        return@post
                    }

                    val credentialName = body["credentialName"] as? String
                    val url = body["url"] as? String
                    val method = (body["method"] as? String)?.uppercase() ?: "GET"
                    @Suppress("UNCHECKED_CAST")
                    val extraHeaders = body["headers"] as? Map<String, String> ?: emptyMap()
                    val requestBody = body["body"] as? String
                    val injectAs = body["injectAs"] as? String ?: "bearer_header"
                    val context = body["context"] as? String ?: "proxy-http"

                    if (credentialName.isNullOrBlank() || url.isNullOrBlank()) {
                        call.respond(HttpStatusCode.BadRequest, mapOf(
                            "error" to "missing_required_fields",
                            "message" to "credentialName and url are required"
                        ))
                        return@post
                    }

                    // Parse catalog metadata for gatekeeper context
                    val entry = vault.getEntry(credentialName)
                    val meta = entry?.let { CatalogMeta.parse(it.description) }

                    // Gatekeeper check
                    if (gatekeeper.isAvailable) {
                        val allowed = gatekeeper.shouldAllow(context, credentialName, meta)
                        if (!allowed) {
                            Log.w(TAG, "Gatekeeper denied proxy access to $credentialName (context=$context)")
                            call.respond(HttpStatusCode.Forbidden, mapOf(
                                "error" to "access_denied",
                                "message" to "Credential gatekeeper denied access"
                            ))
                            return@post
                        }
                    }

                    // Fetch credential
                    val credentialValue: String
                    try {
                        val value = vault.get(credentialName)
                        if (value == null) {
                            call.respond(HttpStatusCode.NotFound, mapOf(
                                "error" to "credential_not_found",
                                "message" to "No credential named '$credentialName'"
                            ))
                            return@post
                        }
                        credentialValue = value
                    } catch (e: android.security.keystore.UserNotAuthenticatedException) {
                        call.respond(HttpStatusCode.ServiceUnavailable, mapOf(
                            "error" to "vault_locked",
                            "message" to "Keystore requires re-authentication"
                        ))
                        return@post
                    }

                    // Build the injected header
                    val injectedHeaderName: String
                    val injectedHeaderValue: String
                    when {
                        injectAs == "bearer_header" -> {
                            injectedHeaderName = "Authorization"
                            injectedHeaderValue = "Bearer $credentialValue"
                        }
                        injectAs == "basic_auth" -> {
                            injectedHeaderName = "Authorization"
                            val encoded = Base64.encodeToString(
                                credentialValue.toByteArray(Charsets.UTF_8),
                                Base64.NO_WRAP
                            )
                            injectedHeaderValue = "Basic $encoded"
                        }
                        injectAs.startsWith("header:") -> {
                            injectedHeaderName = injectAs.removePrefix("header:")
                            injectedHeaderValue = credentialValue
                        }
                        else -> {
                            call.respond(HttpStatusCode.BadRequest, mapOf(
                                "error" to "invalid_inject_as",
                                "message" to "injectAs must be 'bearer_header', 'basic_auth', or 'header:<name>'"
                            ))
                            return@post
                        }
                    }

                    // Execute outbound request
                    try {
                        val response = httpClient.request(url) {
                            this.method = when (method) {
                                "GET" -> HttpMethod.Get
                                "POST" -> HttpMethod.Post
                                "PUT" -> HttpMethod.Put
                                "DELETE" -> HttpMethod.Delete
                                "PATCH" -> HttpMethod.Patch
                                "HEAD" -> HttpMethod.Head
                                "OPTIONS" -> HttpMethod.Options
                                else -> HttpMethod.Get
                            }
                            // Add user-supplied headers
                            for ((k, v) in extraHeaders) {
                                header(k, v)
                            }
                            // Inject the credential header
                            header(injectedHeaderName, injectedHeaderValue)
                            // Set body if present
                            if (requestBody != null) {
                                contentType(ContentType.Application.Json)
                                setBody(requestBody)
                            }
                        }

                        val responseBody = response.bodyAsText()

                        // Build filtered response headers
                        val filteredHeaders = mutableMapOf<String, String>()
                        response.headers.forEach { name, values ->
                            if (name.lowercase() !in STRIPPED_RESPONSE_HEADERS) {
                                filteredHeaders[name] = values.joinToString(", ")
                            }
                        }

                        Log.i(TAG, "proxy-http $method $url -> ${response.status.value}")
                        call.respond(mapOf(
                            "status" to response.status.value,
                            "body" to responseBody,
                            "headers" to filteredHeaders
                        ))
                    } catch (e: Exception) {
                        Log.e(TAG, "proxy-http failed: ${e.message}", e)
                        call.respond(HttpStatusCode.BadGateway, mapOf(
                            "error" to "proxy_request_failed",
                            "message" to (e.message ?: "Unknown error")
                        ))
                    }
                }
            }
        }.start(wait = false)
        Log.i(TAG, "Vault HTTP server started on 127.0.0.1:$PORT")
    }

    fun stop() {
        httpClient.close()
        server?.stop(1000, 2000)
        server = null
        Log.i(TAG, "Vault HTTP server stopped")
    }

    // ── Helpers ───────────────────────────────────────────────────────

    /**
     * Returns true if the session is unlocked and the route should proceed.
     * Returns false (and sends a 423 response) if the vault is locked.
     */
    private suspend fun RoutingContext.requireSession(): Boolean {
        if (!session.isUnlocked) {
            call.respond(HttpStatusCode.fromValue(423), mapOf(
                "error" to "vault_locked",
                "message" to "Vault is locked. Unlock via biometric in Settings."
            ))
            return false
        }
        return true
    }
}
