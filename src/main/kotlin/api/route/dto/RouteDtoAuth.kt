package org.thingai.app.aigateway.api.route.dto

// ── POST /auth/login ─────────────────────────────────────────────────────────

data class LoginRequest(
    val username: String?,
    val password: String?
)

data class LoginResponse(
    val ok: Boolean,
    val accessToken: String,
    val refreshToken: String
)

// ── POST /auth/refresh ───────────────────────────────────────────────────────

data class RefreshRequest(
    val refreshToken: String?
)

data class RefreshResponse(
    val ok: Boolean,
    val accessToken: String,
    val refreshToken: String
)

// ── POST /auth/logout ────────────────────────────────────────────────────────

data class LogoutRequest(
    val refreshToken: String?
)
