package org.thingai.app.aigateway.api.route

import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receiveText
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import org.thingai.app.aigateway.LMApplication
import org.thingai.app.aigateway.api.route.dto.ApiErrorResponse
import org.thingai.app.aigateway.api.route.dto.LoginRequest
import org.thingai.app.aigateway.api.route.dto.LoginResponse
import org.thingai.app.aigateway.api.route.dto.LogoutRequest
import org.thingai.app.aigateway.api.route.dto.OkResponse
import org.thingai.app.aigateway.api.route.dto.RefreshRequest
import org.thingai.app.aigateway.api.route.dto.RefreshResponse
import org.thingai.app.aigateway.api.route.extension.respondJson
import org.thingai.app.aigateway.utils.JsonUtils

fun Route.auth() {
    route("/auth") {

        // POST /api/auth/login
        // Body: { "username": "admin", "password": "..." }
        // Response 200: { "ok": true, "accessToken": "...", "refreshToken": "..." }
        // Response 401: { "ok": false, "error": "Invalid credentials" }
        post("/login") {
            val req = call.receiveJson(LoginRequest::class.java) ?: run {
                call.respondJson(JsonUtils.toJson(ApiErrorResponse(false, "Invalid request body")), HttpStatusCode.BadRequest)
                return@post
            }

            val username = req.username?.trim()?.takeIf { it.isNotBlank() }
            val password = req.password?.takeIf { it.isNotBlank() }

            if (username == null || password == null) {
                call.respondJson(JsonUtils.toJson(ApiErrorResponse(false, "Fields required: username, password")), HttpStatusCode.BadRequest)
                return@post
            }

            val jwt = LMApplication.authService.login(username, password)
            if (jwt == null) {
                call.respondJson(JsonUtils.toJson(ApiErrorResponse(false, "Invalid credentials")), HttpStatusCode.Unauthorized)
                return@post
            }

            call.respondJson(JsonUtils.toJson(LoginResponse(ok = true, accessToken = jwt.accessToken, refreshToken = jwt.refreshToken)))
        }

        // POST /api/auth/refresh
        // Body: { "refreshToken": "..." }
        // Response 200: { "ok": true, "accessToken": "...", "refreshToken": "..." }
        // Response 401: invalid / expired / already rotated
        post("/refresh") {
            val req = call.receiveJson(RefreshRequest::class.java) ?: run {
                call.respondJson(JsonUtils.toJson(ApiErrorResponse(false, "Invalid request body")), HttpStatusCode.BadRequest)
                return@post
            }

            val token = req.refreshToken?.takeIf { it.isNotBlank() }
            if (token == null) {
                call.respondJson(JsonUtils.toJson(ApiErrorResponse(false, "Field required: refreshToken")), HttpStatusCode.BadRequest)
                return@post
            }

            val jwt = LMApplication.authService.refresh(token)
            if (jwt == null) {
                call.respondJson(JsonUtils.toJson(ApiErrorResponse(false, "Invalid or expired refresh token")), HttpStatusCode.Unauthorized)
                return@post
            }

            call.respondJson(JsonUtils.toJson(RefreshResponse(ok = true, accessToken = jwt.accessToken, refreshToken = jwt.refreshToken)))
        }

        // POST /api/auth/logout
        // Body: { "refreshToken": "..." }
        // Response 200: { "ok": true }
        post("/logout") {
            val req = call.receiveJson(LogoutRequest::class.java) ?: run {
                call.respondJson(JsonUtils.toJson(ApiErrorResponse(false, "Invalid request body")), HttpStatusCode.BadRequest)
                return@post
            }

            val token = req.refreshToken?.takeIf { it.isNotBlank() }
            if (token == null) {
                call.respondJson(JsonUtils.toJson(ApiErrorResponse(false, "Field required: refreshToken")), HttpStatusCode.BadRequest)
                return@post
            }

            val revoked = LMApplication.authService.logout(token)
            if (!revoked) {
                call.respondJson(JsonUtils.toJson(ApiErrorResponse(false, "Invalid or already revoked token")), HttpStatusCode.BadRequest)
                return@post
            }

            call.respondJson(JsonUtils.toJson(OkResponse(ok = true)))
        }
    }
}

// ── Helper ────────────────────────────────────────────────────────────────────

private suspend fun <T> io.ktor.server.application.ApplicationCall.receiveJson(clazz: Class<T>): T? =
    runCatching { JsonUtils.fromJson(receiveText().trim(), clazz) }.getOrNull()
