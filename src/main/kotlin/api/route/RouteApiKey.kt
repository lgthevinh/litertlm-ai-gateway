package org.thingai.app.aigateway.api.route

import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receiveText
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import org.thingai.app.aigateway.api.plugin.AuthPlugin
import org.thingai.app.aigateway.LMApplication
import org.thingai.app.aigateway.api.route.dto.ApiErrorResponse
import org.thingai.app.aigateway.api.route.dto.ApiKeyInfo
import org.thingai.app.aigateway.api.route.dto.GenerateKeyRequest
import org.thingai.app.aigateway.api.route.dto.GenerateKeyResponse
import org.thingai.app.aigateway.api.route.dto.KeyInfoResponse
import org.thingai.app.aigateway.api.route.dto.ListKeysResponse
import org.thingai.app.aigateway.api.route.dto.OkResponse
import org.thingai.app.aigateway.api.route.dto.RevokeKeyRequest
import org.thingai.app.aigateway.utils.JsonUtils

fun Route.apiKey() {
    route("/api-key") {
        install(AuthPlugin)

        // POST /api-key/generate
        // Body (optional JSON): { "name": "my-client" }
        // Response 201: { "ok": true, "key": "lrtlm_...", "id": "...", "prefix": "lrtlm_Xx", "name": "..." }
        post("/generate") {
            val body = call.receiveText().trim()
            val name = if (body.isNotEmpty()) {
                runCatching { JsonUtils.fromJson(body, GenerateKeyRequest::class.java)?.name }
                    .getOrNull()
                    ?.takeIf { it.isNotBlank() }
                    ?: "default"
            } else {
                "default"
            }

            val result = LMApplication.apiKeyService.generateApiKey(name)
            if (result == null) {
                call.respondJson(JsonUtils.toJson(ApiErrorResponse(false, "Failed to generate API key")), HttpStatusCode.InternalServerError)
                return@post
            }

            call.respondJson(
                JsonUtils.toJson(
                    GenerateKeyResponse(
                        ok     = true,
                        key    = result.rawKey,
                        id     = result.apiKey.id,
                        prefix = result.apiKey.keyPrefix,
                        name   = result.apiKey.name
                    )
                ),
                HttpStatusCode.Created
            )
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
            call.respondJson(JsonUtils.toJson(ListKeysResponse(ok = true, keys = keys)))
        }

        // DELETE /api-key/revoke
        // Body (required JSON): { "key": "lrtlm_..." }
        // Response 200: { "ok": true }
        // Response 404: { "ok": false, "error": "Key not found or already revoked" }
        delete("/revoke") {
            val body = call.receiveText().trim()
            if (body.isEmpty()) {
                call.respondJson(JsonUtils.toJson(ApiErrorResponse(false, "Request body is required")), HttpStatusCode.BadRequest)
                return@delete
            }

            val rawKey = runCatching { JsonUtils.fromJson(body, RevokeKeyRequest::class.java)?.key }
                .getOrNull()
                ?.takeIf { it.isNotBlank() }

            if (rawKey == null) {
                call.respondJson(JsonUtils.toJson(ApiErrorResponse(false, "Missing field: key")), HttpStatusCode.BadRequest)
                return@delete
            }

            val revoked = LMApplication.apiKeyService.revokeApiKey(rawKey)
            if (!revoked) {
                call.respondJson(JsonUtils.toJson(ApiErrorResponse(false, "Key not found or already revoked")), HttpStatusCode.NotFound)
                return@delete
            }

            call.respondJson(JsonUtils.toJson(OkResponse(ok = true)))
        }

        // GET /api-key/info?key=lrtlm_...
        // Response 200: { "ok": true, "key": { "id", "prefix", "name", "active", "createdAt", "lastUsedAt" } }
        // Response 404: { "ok": false, "error": "Key not found" }
        get("/info") {
            val rawKey = call.request.queryParameters["key"]?.takeIf { it.isNotBlank() }
            if (rawKey == null) {
                call.respondJson(JsonUtils.toJson(ApiErrorResponse(false, "Missing query param: key")), HttpStatusCode.BadRequest)
                return@get
            }

            val record = LMApplication.apiKeyService.getApiKeyInfo(rawKey)
            if (record == null) {
                call.respondJson(JsonUtils.toJson(ApiErrorResponse(false, "Key not found")), HttpStatusCode.NotFound)
                return@get
            }

            call.respondJson(
                JsonUtils.toJson(
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
            )
        }
    }
}
