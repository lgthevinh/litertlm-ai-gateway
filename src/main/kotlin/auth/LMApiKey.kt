package org.thingai.app.aigateway.auth

import org.thingai.base.dao.annotations.DaoColumn
import org.thingai.base.dao.annotations.DaoTable


@DaoTable(name = "lm_api_keys")
data class LMApiKey(
    @DaoColumn(primaryKey = true)
    val id: String,

    @DaoColumn(unique = true)
    val keyHash: String,

    @DaoColumn
    val keyPrefix: String, // lrtlm_ for API key

    @DaoColumn
    val name: String,

    @DaoColumn
    val active: Boolean,

    @DaoColumn
    val createdAt: Long,

    @DaoColumn
    val lastUsedAt: Long?
)