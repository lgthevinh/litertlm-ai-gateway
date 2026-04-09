package org.thingai.app.aigateway.auth

import org.thingai.base.dao.annotations.DaoColumn
import org.thingai.base.dao.annotations.DaoTable


@DaoTable(name = "lm_api_keys")
data class LMApiKey(
    @DaoColumn(primaryKey = true)
    var id: String,

    @DaoColumn(unique = true)
    var keyHash: String,

    @DaoColumn
    var keyPrefix: String, // lrtlm_ for API key

    @DaoColumn
    var name: String,

    @DaoColumn
    var active: Boolean,

    @DaoColumn
    var createdAt: Long,

    @DaoColumn
    var lastUsedAt: Long?
) {
    constructor() : this(
        id = "",
        keyHash = "",
        keyPrefix = "",
        name = "",
        active = false,
        createdAt = 0L,
        lastUsedAt = null
    )
}

/**
 * Returned by [LMApiKeyService.generateApiKey].
 * [rawKey] is the only time the unmasked key is accessible — store it immediately.
 */
data class LMApiKeyResult(
    val apiKey: LMApiKey,
    val rawKey: String
)