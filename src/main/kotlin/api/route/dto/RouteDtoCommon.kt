package org.thingai.app.aigateway.api.route.dto

data class OkResponse(val ok: Boolean)
data class ApiErrorResponse(val ok: Boolean, val error: String)