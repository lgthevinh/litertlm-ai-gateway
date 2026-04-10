package org.thingai.app.aigateway.auth

/** Returned to the client after a successful login or refresh. */
data class LMAuthJwt(
    val accessToken: String,
    val refreshToken: String
)
