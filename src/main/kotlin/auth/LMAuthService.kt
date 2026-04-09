package org.thingai.app.aigateway.auth

import org.thingai.base.dao.exceptions.DaoException
import org.thingai.base.log.ILog
import org.thingai.platform.dao.DaoSqlite
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class LMAuthService(private val dao: DaoSqlite, private val jwtSecret: String) {

    companion object {
        private const val TAG = "LMAuthService"

        // Token lifetimes
        private const val ACCESS_TTL_MS  = 15 * 60 * 1000L          // 15 minutes
        private const val REFRESH_TTL_MS = 7 * 24 * 60 * 60 * 1000L // 7 days

        private const val JWT_ALGORITHM = "HmacSHA256"
        private const val JWT_TYPE      = "JWT"
        private const val JWT_ALG_CLAIM = "HS256"

        private val secureRandom = SecureRandom()

        // ── Crypto helpers ───────────────────────────────────────────────────

        /** Generate a random 16-byte hex salt. */
        fun generateSalt(): String {
            val bytes = ByteArray(16).also { secureRandom.nextBytes(it) }
            return bytes.joinToString("") { "%02x".format(it) }
        }

        /** PBKDF2-style: SHA-256(salt + password). Simple and dependency-free. */
        fun hashPassword(password: String, salt: String): String {
            val input = "$salt:$password"
            val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
            return digest.joinToString("") { "%02x".format(it) }
        }

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
         * Payload claims: sub (username), jti (token id), iat, exp, type ("access"|"refresh")
         */
        fun buildJwt(secret: String, username: String, jti: String, expiresAt: Long, type: String): String {
            val header  = """{"alg":"$JWT_ALG_CLAIM","typ":"$JWT_TYPE"}"""
            val issuedAt = System.currentTimeMillis() / 1000
            val expSecs  = expiresAt / 1000
            val payload = """{"sub":"$username","jti":"$jti","iat":$issuedAt,"exp":$expSecs,"type":"$type"}"""

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
                // Simple key:value extraction without a JSON parser dependency
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

    // ── Public API ───────────────────────────────────────────────────────────

    /**
     * Register a new user. Fails if [username] is already taken.
     * @return `true` on success, `false` if user already exists or DB error.
     */
    fun register(username: String, password: String): Boolean {
        val trimmed = username.trim()
        if (trimmed.isBlank() || password.isBlank()) {
            ILog.w(TAG, "register: blank username or password")
            return false
        }

        val existing = dao.query(LMAuthUser::class.java, "username", trimmed).firstOrNull()
        if (existing != null) {
            ILog.d(TAG, "register: username '$trimmed' already taken")
            return false
        }

        val salt         = generateSalt()
        val passwordHash = hashPassword(password, salt)
        val user = LMAuthUser(
            username     = trimmed,
            passwordHash = passwordHash,
            salt         = salt,
            createdAt    = System.currentTimeMillis()
        )

        return try {
            dao.insert(user)
            ILog.i(TAG, "register: user '$trimmed' created")
            true
        } catch (e: DaoException) {
            ILog.e(TAG, "register: DB insert failed: ${e.message}")
            false
        }
    }

    /**
     * Validates credentials and issues an [LMAuthJwt] pair.
     * @return JWT pair on success, `null` if credentials are wrong.
     */
    fun login(username: String, password: String): LMAuthJwt? {
        val user = dao.query(LMAuthUser::class.java, "username", username.trim()).firstOrNull()
        if (user == null) {
            ILog.d(TAG, "login: user '${username.trim()}' not found")
            return null
        }

        val hash = hashPassword(password, user.salt)
        if (hash != user.passwordHash) {
            ILog.d(TAG, "login: wrong password for '${user.username}'")
            return null
        }

        return issueTokenPair(user.username)
    }

    /**
     * Validates a refresh token and issues a new [LMAuthJwt] pair.
     * The old refresh token is revoked immediately (rotation).
     * @return new JWT pair, or `null` if the token is invalid/expired/revoked.
     */
    fun refresh(refreshToken: String): LMAuthJwt? {
        val claims = verifyJwt(jwtSecret, refreshToken)
        if (claims == null || claims["type"] != "refresh") {
            ILog.d(TAG, "refresh: invalid or expired refresh token")
            return null
        }

        val jti      = claims["jti"] ?: return null
        val username = claims["sub"] ?: return null

        val record = dao.query(LMAuthToken::class.java, "tokenId", jti).firstOrNull()
        if (record == null || record.revoked == 1) {
            ILog.d(TAG, "refresh: token $jti is revoked or not found")
            return null
        }

        // Revoke old refresh token (rotation — prevents reuse)
        revokeTokenRecord(jti)

        return issueTokenPair(username)
    }

    /**
     * Revokes the refresh token identified by its `jti` claim.
     * Access tokens are short-lived and not tracked — they expire naturally.
     * @return `true` if the token was found and revoked.
     */
    fun logout(refreshToken: String): Boolean {
        val claims = verifyJwt(jwtSecret, refreshToken) ?: run {
            // Token may be structurally valid but expired; try to parse jti anyway
            ILog.d(TAG, "logout: could not verify token, attempting blind revocation")
            return false
        }
        val jti = claims["jti"] ?: return false
        return revokeTokenRecord(jti)
    }

    /**
     * Validates an access token and returns the username, or `null` if invalid.
     * Used by middleware to authenticate protected routes.
     */
    fun validateAccessToken(accessToken: String): String? {
        val claims = verifyJwt(jwtSecret, accessToken) ?: return null
        if (claims["type"] != "access") return null
        return claims["sub"]
    }

    /**
     * Issues a JWT pair for the reserved "local" user without any credential check.
     * "local" is never stored in the DB, so this is safe by design —
     * it is a low-privilege dev/UI account, not an admin account.
     */
    fun issueLocalToken(): LMAuthJwt {
        ILog.i(TAG, "issueLocalToken: issuing token for local user")
        return issueTokenPair("local")
    }

    /**
     * Returns all registered users sorted by creation time (newest first).
     * The "local" virtual user is excluded since it has no DB row.
     */
    fun listUsers(): List<LMAuthUser> {
        return try {
            dao.readAll(LMAuthUser::class.java)
                .sortedByDescending { it.createdAt }
        } catch (e: DaoException) {
            ILog.e(TAG, "listUsers: DB error: ${e.message}")
            emptyList()
        }
    }

    /**
     * Deletes a user and revokes all their active refresh tokens.
     * @return `true` if the user existed and was deleted, `false` otherwise.
     */
    fun deleteUser(username: String): Boolean {
        val trimmed = username.trim()
        if (trimmed == "local") return false   // "local" is virtual, cannot be deleted

        val user = dao.query(LMAuthUser::class.java, "username", trimmed).firstOrNull()
            ?: return false

        return try {
            // Revoke all refresh tokens for this user
            val tokens = dao.query(LMAuthToken::class.java, "username", trimmed)
            tokens.forEach { dao.insertOrUpdate(it.copy(revoked = 1)) }

            dao.delete(user)
            ILog.i(TAG, "deleteUser: '$trimmed' deleted, ${tokens.size} token(s) revoked")
            true
        } catch (e: DaoException) {
            ILog.e(TAG, "deleteUser: DB error: ${e.message}")
            false
        }
    }

    // ── Private helpers ──────────────────────────────────────────────────────

    private fun issueTokenPair(username: String): LMAuthJwt {
        val accessJti  = UUID.randomUUID().toString()
        val refreshJti = UUID.randomUUID().toString()
        val now        = System.currentTimeMillis()

        val accessToken  = buildJwt(jwtSecret, username, accessJti,  now + ACCESS_TTL_MS,  "access")
        val refreshToken = buildJwt(jwtSecret, username, refreshJti, now + REFRESH_TTL_MS, "refresh")

        // Persist refresh token for revocation tracking
        try {
            dao.insert(
                LMAuthToken(
                    tokenId   = refreshJti,
                    username  = username,
                    expiresAt = now + REFRESH_TTL_MS,
                    revoked   = 0
                )
            )
        } catch (e: DaoException) {
            ILog.e(TAG, "issueTokenPair: failed to persist refresh token: ${e.message}")
        }

        ILog.i(TAG, "issueTokenPair: tokens issued for '$username'")
        return LMAuthJwt(accessToken = accessToken, refreshToken = refreshToken)
    }

    private fun revokeTokenRecord(jti: String): Boolean {
        val record = dao.query(LMAuthToken::class.java, "tokenId", jti).firstOrNull() ?: return false
        return try {
            dao.insertOrUpdate(record.copy(revoked = 1))
            ILog.i(TAG, "revokeTokenRecord: token $jti revoked")
            true
        } catch (e: DaoException) {
            ILog.e(TAG, "revokeTokenRecord: DB error: ${e.message}")
            false
        }
    }
}
