package org.thingai.app.aigateway.auth

data class LMApiKey(
    val id: String,
    val keyHash: String,
    val keyPrefix: String, // lrtlm_ for API key
    val name: String,
    val active: Boolean,
    val createdAt: Long,
    val lastUsedAt: Long?
)