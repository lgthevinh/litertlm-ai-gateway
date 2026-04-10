package org.thingai.app.aigateway.auth

import org.thingai.base.log.ILog
import java.util.Base64
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class LMServiceAuth(
    private val jwtSecret: String,
    private val username: String,
    private val password: String,
) {

    companion object {
        private const val TAG = "LMServiceAuth"

        private const val ACCESS_TTL_MS  = 15 * 60 * 1000L           // 15 minutes
        private const val REFRESH_TTL_MS = 7 * 24 * 60 * 60 * 1000L  // 7 days

        private const val JWT_ALGORITHM = "HmacSHA256"
        private const val JWT_TYPE      = "JWT"
        private const val JWT_ALG_CLAIM = "HS256"

        // ── JWT helpers ──────────────────────────────────────────────────────

        private fun base64url(data: ByteArray): String =
            Base64.getUrlEncoder().withoutPadding().encodeToString(data)

        private fun base64url(s: String): String = base64url(s.toByteArray(Charsets.UTF_8))

        private fun hmac256(secret: String, data: String): ByteArray {
            val mac = Mac.getInstance(JWT_ALGORITHM)
            mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), JWT_ALGORITHM))
            return mac.doFinal(data.toByteArray(Charsets.UTF_8))
        }

        /**
         * Builds a minimal HS256 JWT.
         * Payload claims: sub, jti, iat, exp, type ("access"|"refresh")
         */
        fun buildJwt(secret: String, username: String, jti: String, expiresAt: Long, type: String): String {
            val header   = """{"alg":"$JWT_ALG_CLAIM","typ":"$JWT_TYPE"}"""
            val issuedAt = System.currentTimeMillis() / 1000
            val expSecs  = expiresAt / 1000
            val payload  = """{"sub":"$username","jti":"$jti","iat":$issuedAt,"exp":$expSecs,"type":"$type"}"""

            val signingInput = "${base64url(header)}.${base64url(payload)}"
            val signature    = base64url(hmac256(secret, signingInput))
            return "$signingInput.$signature"
        }

        /**
         * Verifies signature and expiry. Returns the parsed claims map or null on failure.
         * Claims map contains: sub, jti, exp, type — all as strings.
         */
        fun verifyJwt(secret: String, token: String): Map<String, String>? {
            val parts = token.split(".")
            if (parts.size != 3) return null

            val signingInput = "${parts[0]}.${parts[1]}"
            val expected     = base64url(hmac256(secret, signingInput))
            if (expected != parts[2]) return null

            return try {
                val payloadJson = String(Base64.getUrlDecoder().decode(parts[1]), Charsets.UTF_8)
                val claims = mutableMapOf<String, String>()
                payloadJson.removeSurrounding("{", "}")
                    .split(",")
                    .forEach { entry ->
                        val (k, v) = entry.split(":").map { it.trim().removeSurrounding("\"") }
                        claims[k] = v
                    }
                val exp = claims["exp"]?.toLongOrNull() ?: return null
                if (System.currentTimeMillis() / 1000 > exp) null   // expired
                else claims
            } catch (_: Exception) {
                null
            }
        }
    }

    /**
     * In-memory JTI of the currently valid refresh token.
     * Null = no active session. Replaced on every login/refresh, cleared on logout.
     * Resets to null on server restart — user must log in again.
     */
    @Volatile
    private var currentRefreshJti: String? = null

    // ── Public API ───────────────────────────────────────────────────────────

    /**
     * Validates credentials against the env-configured username/password.
     * Issues a new JWT pair and invalidates any previous session.
     *
     * @return [LMAuthJwt] on success, null if credentials are wrong.
     */
    fun login(inputUsername: String, inputPassword: String): LMAuthJwt? {
        if (inputUsername.trim() != username || inputPassword != password) {
            ILog.d(TAG, "login: invalid credentials")
            return null
        }
        return issueTokenPair()
    }

    /**
     * Validates a refresh token: checks signature, expiry, and JTI against the
     * in-memory [currentRefreshJti]. Rotates to a new pair on success.
     *
     * @return new [LMAuthJwt], or null if invalid/expired/already rotated.
     */
    fun refresh(refreshToken: String): LMAuthJwt? {
        val claims = verifyJwt(jwtSecret, refreshToken)
        if (claims == null || claims["type"] != "refresh") {
            ILog.d(TAG, "refresh: invalid or expired refresh token")
            return null
        }

        val jti = claims["jti"] ?: return null
        if (jti != currentRefreshJti) {
            ILog.d(TAG, "refresh: JTI mismatch — token already rotated or session cleared")
            return null
        }

        return issueTokenPair()
    }

    /**
     * Clears [currentRefreshJti], ending the current session.
     * Access tokens expire naturally after 15 minutes.
     *
     * @return true if a session was active, false if already logged out.
     */
    fun logout(refreshToken: String): Boolean {
        val claims = verifyJwt(jwtSecret, refreshToken)
        val jti    = claims?.get("jti")

        if (jti == null || jti != currentRefreshJti) {
            ILog.d(TAG, "logout: token does not match current session")
            return false
        }

        currentRefreshJti = null
        ILog.i(TAG, "logout: session cleared")
        return true
    }

    /**
     * Validates an access token and returns the username, or null if invalid/expired.
     * Used by auth middleware to protect routes.
     */
    fun validateAccessToken(accessToken: String): String? {
        val claims = verifyJwt(jwtSecret, accessToken) ?: return null
        if (claims["type"] != "access") return null
        return claims["sub"]
    }

    // ── Private helpers ──────────────────────────────────────────────────────

    private fun issueTokenPair(): LMAuthJwt {
        val accessJti  = UUID.randomUUID().toString()
        val refreshJti = UUID.randomUUID().toString()
        val now        = System.currentTimeMillis()

        val accessToken  = buildJwt(jwtSecret, username, accessJti,  now + ACCESS_TTL_MS,  "access")
        val refreshToken = buildJwt(jwtSecret, username, refreshJti, now + REFRESH_TTL_MS, "refresh")

        currentRefreshJti = refreshJti
        ILog.i(TAG, "issueTokenPair: new session issued for '$username'")
        return LMAuthJwt(accessToken = accessToken, refreshToken = refreshToken)
    }
}
