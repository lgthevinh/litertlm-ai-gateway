package org.thingai.app.aigateway.api.route

import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receiveText
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import org.thingai.app.aigateway.LMApplication
import org.thingai.app.aigateway.api.plugin.AuthPlugin
import org.thingai.app.aigateway.api.plugin.callerIdentity
import org.thingai.app.aigateway.api.route.dto.ApiErrorResponse
import org.thingai.app.aigateway.api.route.dto.CreateUserRequest
import org.thingai.app.aigateway.api.route.dto.ListUsersResponse
import org.thingai.app.aigateway.api.route.dto.LoginRequest
import org.thingai.app.aigateway.api.route.dto.LoginResponse
import org.thingai.app.aigateway.api.route.dto.LogoutRequest
import org.thingai.app.aigateway.api.route.dto.OkResponse
import org.thingai.app.aigateway.api.route.dto.RefreshRequest
import org.thingai.app.aigateway.api.route.dto.RefreshResponse
import org.thingai.app.aigateway.api.route.dto.UserInfo
import org.thingai.app.aigateway.api.route.extension.respondJson
import org.thingai.app.aigateway.utils.JsonUtils

fun Route.auth() {
    route("/auth") {

        // ── Public endpoints ──────────────────────────────────────────────────

        // POST /api/auth/login
        // Body: { "username": "alice", "password": "s3cr3t" }
        //   Special: username "local" → issues token with no password (reserved dev account).
        // Response 200: { "ok": true, "accessToken": "...", "refreshToken": "..." }
        // Response 401: { "ok": false, "error": "Invalid credentials" }
        post("/login") {
            val req = call.receiveJson(LoginRequest::class.java) ?: run {
                call.respondJson(JsonUtils.toJson(ApiErrorResponse(false, "Invalid request body")), HttpStatusCode.BadRequest)
                return@post
            }

            val username = req.username?.trim()?.takeIf { it.isNotBlank() }
            if (username == null) {
                call.respondJson(JsonUtils.toJson(ApiErrorResponse(false, "Field required: username")), HttpStatusCode.BadRequest)
                return@post
            }

            // "local" is a reserved virtual user — no DB row, no password needed.
            val jwt = if (username == "local") {
                LMApplication.authService.issueLocalToken()
            } else {
                val password = req.password?.takeIf { it.isNotBlank() }
                if (password == null) {
                    call.respondJson(JsonUtils.toJson(ApiErrorResponse(false, "Field required: password")), HttpStatusCode.BadRequest)
                    return@post
                }
                LMApplication.authService.login(username, password)
            }

            if (jwt == null) {
                call.respondJson(JsonUtils.toJson(ApiErrorResponse(false, "Invalid credentials")), HttpStatusCode.Unauthorized)
                return@post
            }

            call.respondJson(JsonUtils.toJson(LoginResponse(ok = true, accessToken = jwt.accessToken, refreshToken = jwt.refreshToken)))
        }

        // POST /api/auth/refresh
        // Body: { "refreshToken": "..." }
        // Response 200: { "ok": true, "accessToken": "...", "refreshToken": "..." }
        // Response 401: invalid / expired refresh token
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

        // ── Protected endpoints (JWT required) ───────────────────────────────

        route("/user") {
            install(AuthPlugin)

            // POST /api/auth/user/create
            // Auth rules:
            //   - caller == "local"  → allowed without X-Api-Key (local machine shortcut)
            //   - any other caller   → requires X-Api-Key: <X_API_KEY>
            // Body: { "username": "alice", "password": "s3cr3t" }
            // Response 201: { "ok": true }
            // Response 409: { "ok": false, "error": "Username already taken" }
            post("/create") {
                val caller = call.callerIdentity

                if (caller != "local") {
                    val providedKey = call.request.headers["X-Api-Key"]?.trim()
                    if (providedKey.isNullOrBlank() || providedKey != LMApplication.xApiKey) {
                        call.respondJson(JsonUtils.toJson(ApiErrorResponse(false, "X-Api-Key required for remote user creation")), HttpStatusCode.Unauthorized)
                        return@post
                    }
                }

                val req = call.receiveJson(CreateUserRequest::class.java) ?: run {
                    call.respondJson(JsonUtils.toJson(ApiErrorResponse(false, "Invalid request body")), HttpStatusCode.BadRequest)
                    return@post
                }

                val username = req.username?.trim()?.takeIf { it.isNotBlank() }
                val password = req.password?.takeIf { it.isNotBlank() }

                if (username == null || password == null) {
                    call.respondJson(JsonUtils.toJson(ApiErrorResponse(false, "Fields required: username, password")), HttpStatusCode.BadRequest)
                    return@post
                }

                if (username == "local") {
                    call.respondJson(JsonUtils.toJson(ApiErrorResponse(false, "Username 'local' is reserved")), HttpStatusCode.Conflict)
                    return@post
                }

                val created = LMApplication.authService.register(username, password)
                if (!created) {
                    call.respondJson(JsonUtils.toJson(ApiErrorResponse(false, "Username already taken")), HttpStatusCode.Conflict)
                    return@post
                }

                call.respondJson(JsonUtils.toJson(OkResponse(ok = true)), HttpStatusCode.Created)
            }

            // DELETE /api/auth/user/{username}
            // Deletes the user and revokes all their refresh tokens.
            // A user cannot delete themselves.
            // Response 200: { "ok": true }
            // Response 404: { "ok": false, "error": "User not found" }
            delete("/{username}") {
                val caller   = call.callerIdentity
                val target   = call.parameters["username"]?.trim().orEmpty()

                if (target.isBlank()) {
                    call.respondJson(JsonUtils.toJson(ApiErrorResponse(false, "Missing username")), HttpStatusCode.BadRequest)
                    return@delete
                }
                if (target == "local") {
                    call.respondJson(JsonUtils.toJson(ApiErrorResponse(false, "Cannot delete the 'local' account")), HttpStatusCode.BadRequest)
                    return@delete
                }
                if (target == caller) {
                    call.respondJson(JsonUtils.toJson(ApiErrorResponse(false, "Cannot delete your own account")), HttpStatusCode.BadRequest)
                    return@delete
                }

                val deleted = LMApplication.authService.deleteUser(target)
                if (!deleted) {
                    call.respondJson(JsonUtils.toJson(ApiErrorResponse(false, "User not found")), HttpStatusCode.NotFound)
                    return@delete
                }

                call.respondJson(JsonUtils.toJson(OkResponse(ok = true)))
            }
        }

        // GET /api/auth/users
        // Returns list of all registered users (excludes the virtual "local" user).
        // Response 200: { "ok": true, "users": [{ "username": "...", "createdAt": 0 }] }
        route("/users") {
            install(AuthPlugin)

            get {
                val users = LMApplication.authService.listUsers().map {
                    UserInfo(username = it.username, createdAt = it.createdAt)
                }
                call.respondJson(JsonUtils.toJson(ListUsersResponse(ok = true, users = users)))
            }
        }
    }
}

// ── Helper ────────────────────────────────────────────────────────────────────

private suspend fun <T> io.ktor.server.application.ApplicationCall.receiveJson(clazz: Class<T>): T? =
    runCatching { JsonUtils.fromJson(receiveText().trim(), clazz) }.getOrNull()
