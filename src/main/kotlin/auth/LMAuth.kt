package org.thingai.app.aigateway.auth

import org.thingai.base.dao.annotations.DaoColumn
import org.thingai.base.dao.annotations.DaoTable

@DaoTable(name = "lm_auth_user")
data class LMAuthUser(
    @DaoColumn(primaryKey = true)
    var username: String,

    @DaoColumn
    var passwordHash: String,

    @DaoColumn
    var salt: String,

    @DaoColumn
    var createdAt: Long
) {
    constructor() : this(
        username = "",
        passwordHash = "",
        salt = "",
        createdAt = 0L
    )
}

/**
 * Persisted refresh token record.
 * One row per issued refresh token — allows per-device revocation.
 */
@DaoTable(name = "lm_auth_token")
data class LMAuthToken(
    @DaoColumn(primaryKey = true)
    var tokenId: String,        // UUID, stored in the JWT "jti" claim

    @DaoColumn
    var username: String,

    @DaoColumn
    var expiresAt: Long,        // epoch millis

    @DaoColumn
    var revoked: Int            // 0 = valid, 1 = revoked
) {
    constructor() : this(
        tokenId = "",
        username = "",
        expiresAt = 0L,
        revoked = 0
    )
}

/** Returned to the client after a successful login or refresh. */
data class LMAuthJwt(
    val accessToken: String,
    val refreshToken: String
)