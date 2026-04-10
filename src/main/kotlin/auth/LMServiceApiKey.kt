package org.thingai.app.aigateway.auth

import org.thingai.base.dao.exceptions.DaoException
import org.thingai.base.log.ILog
import org.thingai.platform.dao.DaoSqlite
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID

class LMServiceApiKey(private val dao: DaoSqlite) {

    companion object {
        private const val TAG = "LMApiKeyService"

        private const val KEY_PREFIX = "lrtlm_"
        private const val KEY_RANDOM_LENGTH = 48
        private const val KEY_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"

        private val secureRandom = SecureRandom()

        /** Generates a random raw key: lrtlm_<48 alphanumeric chars> */
        private fun buildRawKey(): String {
            val chars = CharArray(KEY_RANDOM_LENGTH) { KEY_ALPHABET[secureRandom.nextInt(KEY_ALPHABET.length)] }
            return KEY_PREFIX + String(chars)
        }

        /** SHA-256 hex digest of the raw key — only this is stored in the DB */
        private fun sha256(input: String): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
            return digest.joinToString("") { "%02x".format(it) }
        }
    }

    /**
     * Generates a new API key, stores a hashed copy, and returns the
     * [LMApiKeyResult] containing the [LMApiKey] record and the raw key.
     *
     * The raw key is returned **only once** — it cannot be recovered later.
     *
     * @param name  Human-readable label for the key (e.g. "mobile-client").
     * @return [LMApiKeyResult] with the persisted [LMApiKey] and the raw key string,
     *         or `null` if the insert failed.
     */
    fun generateApiKey(name: String = "default"): LMApiKeyResult? {
        val rawKey = buildRawKey()
        val keyHash = sha256(rawKey)
        val keyPrefix = rawKey.take(KEY_PREFIX.length + 2)   // "lrtlm_Xx"

        val record = LMApiKey(
            id          = UUID.randomUUID().toString(),
            keyHash     = keyHash,
            keyPrefix   = keyPrefix,
            name        = name,
            active      = true,
            createdAt   = System.currentTimeMillis(),
            lastUsedAt  = null
        )

        return try {
            dao.insert(record)
            ILog.i(TAG, "API key created: prefix=${record.keyPrefix}, name=$name")
            LMApiKeyResult(record, rawKey)
        } catch (e: DaoException) {
            ILog.e(TAG, "Failed to insert API key: ${e.message}")
            null
        }
    }

    /**
     * Validates a raw API key against the stored SHA-256 hash.
     *
     * Also updates the `lastUsedAt` timestamp on a successful match.
     *
     * @param apiKey  The raw key received from the client.
     * @return `true` if the key exists, is active, and the hash matches.
     */
    fun validateApiKey(apiKey: String): Boolean {
        if (!apiKey.startsWith(KEY_PREFIX)) {
            ILog.d(TAG, "validateApiKey: rejected — wrong prefix")
            return false
        }

        val keyHash = sha256(apiKey)
        return try {
            val matches = dao.query(LMApiKey::class.java, "keyHash", keyHash)
            val record = matches.firstOrNull()

            if (record == null) {
                ILog.d(TAG, "validateApiKey: no matching key found")
                return false
            }

            if (!record.active) {
                ILog.d(TAG, "validateApiKey: key is revoked (prefix=${record.keyPrefix})")
                return false
            }

            // Touch lastUsedAt without blocking the caller on failure
            try {
                dao.insertOrUpdate(record.copy(lastUsedAt = System.currentTimeMillis()))
            } catch (e: DaoException) {
                ILog.w(TAG, "Failed to update lastUsedAt for key ${record.keyPrefix}: ${e.message}")
            }

            ILog.d(TAG, "validateApiKey: valid (prefix=${record.keyPrefix})")
            true
        } catch (e: DaoException) {
            ILog.e(TAG, "validateApiKey query failed: ${e.message}")
            false
        }
    }

    /**
     * Soft-revokes an API key by setting `active = false`.
     *
     * The record is kept for audit purposes. The key will be rejected by
     * [validateApiKey] after revocation.
     *
     * @param apiKey  The raw key to revoke.
     * @return `true` if the key was found and deactivated; `false` otherwise.
     */
    fun revokeApiKey(apiKey: String): Boolean {
        if (!apiKey.startsWith(KEY_PREFIX)) {
            ILog.d(TAG, "revokeApiKey: rejected — wrong prefix")
            return false
        }

        val keyHash = sha256(apiKey)
        return try {
            val matches = dao.query(LMApiKey::class.java, "keyHash", keyHash)
            val record = matches.firstOrNull() ?: run {
                ILog.d(TAG, "revokeApiKey: key not found")
                return false
            }

            if (!record.active) {
                ILog.d(TAG, "revokeApiKey: key already revoked (prefix=${record.keyPrefix})")
                return false
            }

            dao.insertOrUpdate(record.copy(active = false))
            ILog.i(TAG, "API key revoked: prefix=${record.keyPrefix}")
            true
        } catch (e: DaoException) {
            ILog.e(TAG, "revokeApiKey failed: ${e.message}")
            false
        }
    }

    /**
     * Returns all stored API keys (active and revoked).
     * Key hashes are never exposed — only the prefix and metadata.
     */
    fun listApiKeys(): List<LMApiKey> {
        return try {
            dao.readAll(LMApiKey::class.java).toList()
        } catch (e: DaoException) {
            ILog.e(TAG, "listApiKeys failed: ${e.message}")
            emptyList()
        }
    }

    /**
     * Looks up the stored [LMApiKey] record for the given raw key.
     * Returns `null` if not found or on DB error.
     */
    fun getApiKeyInfo(apiKey: String): LMApiKey? {
        if (!apiKey.startsWith(KEY_PREFIX)) return null
        val keyHash = sha256(apiKey)
        return try {
            dao.query(LMApiKey::class.java, "keyHash", keyHash).firstOrNull()
        } catch (e: DaoException) {
            ILog.e(TAG, "getApiKeyInfo failed: ${e.message}")
            null
        }
    }
}
