package org.thingai.app.aigateway.lm.tool

import com.google.ai.edge.litertlm.OpenApiTool

interface GatewayOpenApiToolSet {
    val name: String
    val tools: Collection<OpenApiTool>
}
