package org.thingai.app.aigateway.api.route.extension

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respondText

suspend fun ApplicationCall.respondJson(
    json: String,
    status: HttpStatusCode = HttpStatusCode.OK
) = respondText(json, ContentType.Application.Json, status)
