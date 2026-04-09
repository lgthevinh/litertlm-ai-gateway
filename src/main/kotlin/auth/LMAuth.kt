package org.thingai.app.aigateway.auth

import org.thingai.base.dao.annotations.DaoColumn
import org.thingai.base.dao.annotations.DaoTable

@DaoTable(name = "lm_auth_user")
data class LMAuthUser(
    @DaoColumn(primaryKey = true)
    val username: String,

    @DaoColumn
    val passwordHash: String,

    @DaoColumn
    val salt: String,

    @DaoColumn
    val createdAt: Long
)

/**
 * Persisted refresh token record.
 * One row per issued refresh token — allows per-device revocation.
 */
@DaoTable(name = "lm_auth_token")
data class LMAuthToken(
    @DaoColumn(primaryKey = true)
    val tokenId: String,        // UUID, stored in the JWT "jti" claim

    @DaoColumn
    val username: String,

    @DaoColumn
    val expiresAt: Long,        // epoch millis

    @DaoColumn
    val revoked: Int            // 0 = valid, 1 = revoked
)

/** Returned to the client after a successful login or refresh. */
data class LMAuthJwt(
    val accessToken: String,
    val refreshToken: String
)