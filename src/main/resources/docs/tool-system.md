# Tool System

The gateway tool system lets the model call server-side functions during inference using LiteRTLM's native `automaticToolCalling` mechanism. The call/response loop happens entirely inside the SDK — the client sees only the final streamed answer.

---

## Core Interfaces & Classes

### `GatewayTool`

The interface every tool implements. Extends the SDK's `OpenApiTool` directly.

```kotlin
interface GatewayTool : OpenApiTool {
    val name: String                              // unique key in ToolRegistry
    fun getToolDescriptionJsonString(): String    // JSON schema — build via ToolDescriptor
    fun execute(paramsJsonString: String): String // called by SDK; never throw — return JSON error
}
```

**Rule:** Never throw from `execute()`. Return a JSON error string instead:
```json
{"error": "missing required parameter 'expression'"}
```

### `ToolDescriptor`

Builds the OpenAPI-compatible JSON schema the model reads to understand the tool.

```kotlin
data class ToolDescriptor(
    val name: String,
    val description: String,
    val parameters: List<ToolParam> = emptyList()
) {
    fun toJsonObject(): JsonObject   // produces JSON schema for the SDK
}
```

### `ToolParam` + `ToolParamType`

```kotlin
data class ToolParam(
    val name: String,
    val type: ToolParamType,    // STRING | INT | FLOAT | BOOLEAN | OBJECT
    val description: String,
    val required: Boolean = true,
    val default: Any? = null
)
```

| ToolParamType | JSON schema type |
|---|---|
| `STRING` | `"string"` |
| `INT` | `"integer"` |
| `FLOAT` | `"number"` |
| `BOOLEAN` | `"boolean"` |
| `OBJECT` | `"object"` |

### `ToolRegistry`

Singleton that owns all registered tools. Thread-safe (`ConcurrentHashMap`).

```kotlin
object ToolRegistry {
    fun register(tool: GatewayTool)                       // overwrites on name collision
    fun unregister(name: String): Boolean
    fun get(name: String): GatewayTool?
    fun getToolProviders(names: List<String>): List<ToolProvider>  // resolves + wraps for SDK
    fun listAll(): List<GatewayTool>                      // sorted by name
    fun clear()                                           // called on LMService.stop()
}
```

`getToolProviders()` silently skips unknown names with a warning log — the conversation continues with remaining tools.

---

## Built-in Tools

| Name | Class | Source | Description |
|------|-------|--------|-------------|
| `datetime` | `DateTimeTool` | bundled | Current date/time with optional format and timezone |
| `calculator` | `CalculatorTool` | bundled | Evaluate math expressions via JS engine or fallback parser |
| `litertlm-docs` | `LMServiceTool` | `resources/docs/` | List and read bundled project documentation |

### Planned / Stub Tools

These classes exist as stubs ready for implementation:

| Name | Class | Purpose |
|------|-------|---------|
| `github` | `GithubTool` | GitHub API integration |
| `trello` | `TrelloTool` | Trello board/card operations |
| `rogo` | `RogoTool` | Rogo service integration |

---

## Implementing a New Tool

### 1. Create the tool class

```kotlin
class MyTool : GatewayTool {

    private val descriptor = ToolDescriptor(
        name        = "my_tool",
        description = "Does something useful for the model.",
        parameters  = listOf(
            ToolParam("input",    ToolParamType.STRING,  "The input value"),
            ToolParam("optional", ToolParamType.BOOLEAN, "An optional flag", required = false)
        )
    )

    override val name = descriptor.name

    override fun getToolDescriptionJsonString(): String =
        descriptor.toJsonObject().toString()

    override fun execute(paramsJsonString: String): String {
        return try {
            val params   = JsonParser.parseString(paramsJsonString).asJsonObject
            val input    = params.get("input")?.asString
                ?: return """{"error": "missing required parameter 'input'"}"""
            val optional = params.get("optional")?.asBoolean ?: false

            // --- your logic here ---
            """{"result": "$input", "flag": $optional}"""
        } catch (e: Exception) {
            """{"error": "${e.message?.replace("\"", "'")}"}"""
        }
    }
}
```

### 2. Handle external credentials (API-backed tools)

Tools that call external APIs need credentials. Use `EnvConfig` — do not hardcode:

```kotlin
class GithubTool : GatewayTool {

    private val token: String? = EnvConfig["GITHUB_TOKEN"]

    override fun execute(paramsJsonString: String): String {
        if (token.isNullOrBlank())
            return """{"error": "GITHUB_TOKEN not configured"}"""
        // call GitHub API ...
    }
}
```

Add the env var to `.env.example` and document it.

### 3. Choose the resource strategy

| Tool reads from | How to access |
|-----------------|---------------|
| Bundled files (`resources/`) | `javaClass.classLoader.getResourceAsStream("path/file")` |
| On-disk files (runtime) | `File(EnvConfig["MY_DIR"] ?: "./data")` |
| External HTTP API | `java.net.http.HttpClient` or `OkHttp` |

### 4. Register in `LMService.start()`

```kotlin
private fun registerBuiltinTools() {
    ToolRegistry.register(DateTimeTool())
    ToolRegistry.register(CalculatorTool())
    ToolRegistry.register(LMServiceTool())
    ToolRegistry.register(GithubTool())    // ← add here
}
```

---

## Tool Binding Per Conversation

Tools are bound at conversation creation and stored as a comma-separated column in `lm_conversations`. At each `sendMessage`, `MessageHandler.buildConfig` resolves stored names via `ToolRegistry.getToolProviders()` and wires active tools into `ConversationConfig`.

```
POST /api/conversations
{ "name": "my-chat", "config": "coder", "tools": ["datetime", "github"] }
```

Stored in DB: `tools = "datetime,github"`

At inference: `ToolRegistry.getToolProviders(["datetime", "github"]) → List<ToolProvider> → ConversationConfig`

Tools can also be updated after creation via `PATCH /api/conversations/{name}` with a new `tools` array.

---

## Package Structure

```
lm/tool/
├── GatewayOpenApiTool.kt           interface: name, getToolDescriptionJsonString, execute
├── ToolDescriptor.kt        name, description, parameters → toJsonObject()
├── ToolParam.kt             name, type, description, required, default
├── ToolParamType.kt         enum: STRING | INT | FLOAT | BOOLEAN | OBJECT
├── ToolRegistry.kt          singleton: register, unregister, get, getToolProviders, listAll, clear
└── builtin/
    ├── DateTimeOpenApiTool.kt      "datetime"   — bundled, no credentials
    ├── CalculatorTool.kt    "calculator" — bundled, no credentials
    ├── LMServiceOpenApiTool.kt     "litertlm-docs" — reads resources/docs/*.md
    ├── GithubTool.kt        "github"     — stub, needs GITHUB_TOKEN
    ├── TrelloTool.kt        "trello"     — stub, needs TRELLO_API_KEY + TRELLO_TOKEN
    └── RogoTool.kt          "rogo"       — stub, needs ROGO_API_KEY
```

---

## `GET /api/tools` Endpoint

Lists all tools currently registered in `ToolRegistry`. Public — no auth required.

Response shape:
```json
{
  "ok": true,
  "tools": [
    {
      "name": "datetime",
      "description": "Get the current date and time.",
      "parameters": [
        { "name": "format",   "type": "STRING", "description": "Java date format pattern", "required": false },
        { "name": "timezone", "type": "STRING", "description": "IANA timezone ID",         "required": false }
      ]
    }
  ]
}
```
