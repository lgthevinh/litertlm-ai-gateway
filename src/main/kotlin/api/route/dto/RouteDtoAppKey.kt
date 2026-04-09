package org.thingai.app.aigateway.api.route.dto

data class GenerateKeyRequest(val name: String?)
data class RevokeKeyRequest(val key: String?)

data class GenerateKeyResponse(
    val ok: Boolean,
    val key: String,
    val id: String,
    val prefix: String,
    val name: String
)


data class ApiKeyInfo(
    val id: String,
    val prefix: String,
    val name: String,
    val active: Boolean,
    val createdAt: Long,
    val lastUsedAt: Long?
)

data class ListKeysResponse(val ok: Boolean, val keys: List<ApiKeyInfo>)
data class KeyInfoResponse(val ok: Boolean, val key: ApiKeyInfo)
