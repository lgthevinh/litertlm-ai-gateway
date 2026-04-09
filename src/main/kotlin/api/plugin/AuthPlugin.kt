package org.thingai.app.aigateway.api.plugin

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.createRouteScopedPlugin
import io.ktor.server.response.respondText
import io.ktor.util.AttributeKey
import org.thingai.app.aigateway.LMApplication
import org.thingai.app.aigateway.utils.JsonUtils

// ── Shared identity key ───────────────────────────────────────────────────────

/**
 * Stores the caller identity on the call after successful authentication.
 *
 * - For JWT auth   → value is the username (e.g. "alice")
 * - For API key auth → value is the key prefix (e.g. "lrtlm_a3")
 *
 * Route handlers read this with [callerIdentity].
 */
val CallerIdentity = AttributeKey<String>("CallerIdentity")

val ApplicationCall.callerIdentity: String
    get() = attributes[CallerIdentity]

// ── AuthPlugin (JWT) ──────────────────────────────────────────────────────────

/**
 * Requires a valid JWT access token:
 *   `Authorization: Bearer <accessToken>`
 *
 * On success: stores the username in [CallerIdentity].
 * On failure: responds 401 and returns — pipeline does not reach the handler.
 */
val AuthPlugin = createRouteScopedPlugin("AuthPlugin") {
    onCall { call ->
        val token = call.extractBearerToken()

        if (token == null) {
            call.rejectWith(HttpStatusCode.Unauthorized, "Missing Authorization header. Expected: Bearer <accessToken>")
            return@onCall
        }

        val username = LMApplication.authService.validateAccessToken(token)
        if (username == null) {
            call.rejectWith(HttpStatusCode.Unauthorized, "Invalid or expired access token")
            return@onCall
        }

        call.attributes.put(CallerIdentity, username)
    }
}

// ── ApiKeyPlugin ──────────────────────────────────────────────────────────────

/**
 * Requires a valid API key:
 *   `Authorization: Bearer lrtlm_<key>`
 *
 * On success: stores the key prefix in [CallerIdentity].
 * On failure: responds 401 and returns — pipeline does not reach the handler.
 */
val ApiKeyPlugin = createRouteScopedPlugin("ApiKeyPlugin") {
    onCall { call ->
        val token = call.extractBearerToken()

        if (token == null) {
            call.rejectWith(HttpStatusCode.Unauthorized, "Missing Authorization header. Expected: Bearer lrtlm_<apiKey>")
            return@onCall
        }

        if (!token.startsWith("lrtlm_")) {
            call.rejectWith(HttpStatusCode.Unauthorized, "Invalid API key format. Key must start with 'lrtlm_'")
            return@onCall
        }

        val valid = LMApplication.apiKeyService.validateApiKey(token)
        if (!valid) {
            call.rejectWith(HttpStatusCode.Unauthorized, "Invalid or revoked API key")
            return@onCall
        }

        // Store the key prefix as the caller identity
        val prefix = token.take("lrtlm_".length + 2)   // "lrtlm_Xx"
        call.attributes.put(CallerIdentity, prefix)
    }
}

// ── DualAuthPlugin (JWT or API key) ──────────────────────────────────────────

/**
 * Accepts either a valid JWT access token **or** a valid API key.
 * The token type is detected automatically by the `lrtlm_` prefix.
 *
 * - `Authorization: Bearer eyJ...`     → JWT path  → stores username
 * - `Authorization: Bearer lrtlm_...`  → API key path → stores key prefix
 *
 * On success: stores the caller identity in [CallerIdentity].
 * On failure: responds 401 and returns — pipeline does not reach the handler.
 *
 * Use this on routes that should be accessible to both interactive users
 * (JWT) and programmatic clients (API keys), e.g. `/conversations`.
 */
val DualAuthPlugin = createRouteScopedPlugin("DualAuthPlugin") {
    onCall { call ->
        val token = call.extractBearerToken()

        if (token == null) {
            call.rejectWith(HttpStatusCode.Unauthorized, "Missing Authorization header. Expected: Bearer <accessToken|apiKey>")
            return@onCall
        }

        if (token.startsWith("lrtlm_")) {
            // ── API key path ──────────────────────────────────────────────
            val valid = LMApplication.apiKeyService.validateApiKey(token)
            if (!valid) {
                call.rejectWith(HttpStatusCode.Unauthorized, "Invalid or revoked API key")
                return@onCall
            }
            val prefix = token.take("lrtlm_".length + 2)
            call.attributes.put(CallerIdentity, prefix)
        } else {
            // ── JWT path ──────────────────────────────────────────────────
            val username = LMApplication.authService.validateAccessToken(token)
            if (username == null) {
                call.rejectWith(HttpStatusCode.Unauthorized, "Invalid or expired access token")
                return@onCall
            }
            call.attributes.put(CallerIdentity, username)
        }
    }
}

// ── Shared helpers ────────────────────────────────────────────────────────────

/**
 * Extracts the Bearer token value from the `Authorization` header.
 * Returns `null` if the header is absent or not in `Bearer <token>` format.
 */
fun ApplicationCall.extractBearerToken(): String? {
    val header = request.headers["Authorization"] ?: return null
    if (!header.startsWith("Bearer ", ignoreCase = true)) return null
    return header.substring(7).trim().takeIf { it.isNotBlank() }
}

/**
 * Responds with a JSON error and returns from `onCall` without calling `finish()`.
 * The handler will not run because the response is already committed.
 */
private suspend fun ApplicationCall.rejectWith(status: HttpStatusCode, message: String) {
    respondText(
        JsonUtils.toJson(mapOf("ok" to false, "error" to message)),
        ContentType.Application.Json,
        status
    )
}
