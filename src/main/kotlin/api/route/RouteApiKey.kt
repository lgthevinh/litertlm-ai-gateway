package org.thingai.app.aigateway.api.route

import com.google.gson.Gson
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import org.thingai.app.aigateway.LMApplication
import org.thingai.app.aigateway.api.route.dto.*

private val gson = Gson()

fun Route.apiKey() {
    route("/api-key") {

        // POST /api-key/generate
        // Body (optional JSON): { "name": "my-client" }
        // Response 201: { "ok": true, "key": "lrtlm_...", "id": "...", "prefix": "lrtlm_Xx", "name": "..." }
        post("/generate") {
            val body = call.receiveText().trim()
            val name = if (body.isNotEmpty()) {
                runCatching { gson.fromJson(body, GenerateKeyRequest::class.java)?.name }
                    .getOrNull()
                    ?.takeIf { it.isNotBlank() }
                    ?: "default"
            } else {
                "default"
            }

            val result = LMApplication.apiKeyService.generateApiKey(name)
            if (result == null) {
                val error = gson.toJson(ApiErrorResponse(ok = false, error = "Failed to generate API key"))
                call.respondText(error, ContentType.Application.Json, HttpStatusCode.InternalServerError)
                return@post
            }

            val response = gson.toJson(
                GenerateKeyResponse(
                    ok     = true,
                    key    = result.rawKey,
                    id     = result.apiKey.id,
                    prefix = result.apiKey.keyPrefix,
                    name   = result.apiKey.name
                )
            )
            call.respondText(response, ContentType.Application.Json, HttpStatusCode.Created)
        }

        // GET /api-key/list
        // Response 200: { "ok": true, "keys": [ { "id", "prefix", "name", "active", "createdAt", "lastUsedAt" }, ... ] }
        get("/list") {
            val keys = LMApplication.apiKeyService.listApiKeys().map { k ->
                ApiKeyInfo(
                    id         = k.id,
                    prefix     = k.keyPrefix,
                    name       = k.name,
                    active     = k.active,
                    createdAt  = k.createdAt,
                    lastUsedAt = k.lastUsedAt
                )
            }
            val response = gson.toJson(ListKeysResponse(ok = true, keys = keys))
            call.respondText(response, ContentType.Application.Json, HttpStatusCode.OK)
        }

        // DELETE /api-key/revoke
        // Body (required JSON): { "key": "lrtlm_..." }
        // Response 200: { "ok": true }
        // Response 404: { "ok": false, "error": "Key not found or already revoked" }
        delete("/revoke") {
            val body = call.receiveText().trim()
            if (body.isEmpty()) {
                val error = gson.toJson(ApiErrorResponse(ok = false, error = "Request body is required"))
                call.respondText(error, ContentType.Application.Json, HttpStatusCode.BadRequest)
                return@delete
            }

            val rawKey = runCatching { gson.fromJson(body, RevokeKeyRequest::class.java)?.key }
                .getOrNull()
                ?.takeIf { it.isNotBlank() }

            if (rawKey == null) {
                val error = gson.toJson(ApiErrorResponse(ok = false, error = "Missing field: key"))
                call.respondText(error, ContentType.Application.Json, HttpStatusCode.BadRequest)
                return@delete
            }

            val revoked = LMApplication.apiKeyService.revokeApiKey(rawKey)
            if (!revoked) {
                val error = gson.toJson(ApiErrorResponse(ok = false, error = "Key not found or already revoked"))
                call.respondText(error, ContentType.Application.Json, HttpStatusCode.NotFound)
                return@delete
            }

            call.respondText(gson.toJson(OkResponse(ok = true)), ContentType.Application.Json, HttpStatusCode.OK)
        }

        // GET /api-key/info?key=lrtlm_...
        // Response 200: { "ok": true, "key": { "id", "prefix", "name", "active", "createdAt", "lastUsedAt" } }
        // Response 404: { "ok": false, "error": "Key not found" }
        get("/info") {
            val rawKey = call.request.queryParameters["key"]?.takeIf { it.isNotBlank() }
            if (rawKey == null) {
                val error = gson.toJson(ApiErrorResponse(ok = false, error = "Missing query param: key"))
                call.respondText(error, ContentType.Application.Json, HttpStatusCode.BadRequest)
                return@get
            }

            val record = LMApplication.apiKeyService.getApiKeyInfo(rawKey)
            if (record == null) {
                val error = gson.toJson(ApiErrorResponse(ok = false, error = "Key not found"))
                call.respondText(error, ContentType.Application.Json, HttpStatusCode.NotFound)
                return@get
            }

            val response = gson.toJson(
                KeyInfoResponse(
                    ok  = true,
                    key = ApiKeyInfo(
                        id         = record.id,
                        prefix     = record.keyPrefix,
                        name       = record.name,
                        active     = record.active,
                        createdAt  = record.createdAt,
                        lastUsedAt = record.lastUsedAt
                    )
                )
            )
            call.respondText(response, ContentType.Application.Json, HttpStatusCode.OK)
        }
    }
}