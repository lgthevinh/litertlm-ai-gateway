# LiteRTLM AI Gateway — Tool Architecture

> Version 0.1 | April 2026
> Covers the design and wiring of the gateway tool system using LiteRTLM's native
> `ToolProvider` / `InternalJsonTool` / `automaticToolCalling` SDK surface.

---

## 1. SDK Tool Contract (What LiteRTLM Gives Us)

From decompiled stubs:

```
InternalJsonTool  (interface)
  ├── getToolDescription(): JsonObject   ← model reads this to know the tool exists
  └── execute(params: JsonObject): Any?  ← SDK calls this when model emits a tool call

ToolProvider  (abstract class)
  └── provideTools(): Map<String, InternalJsonTool>   ← groups tools by name

ToolManager(tools: List<ToolProvider>)
  ├── execute(functionName, params): JsonElement      ← internal dispatch
  └── getToolsDescription(): JsonArray                ← aggregated tool schemas

ConversationConfig(
  tools:               List<ToolProvider>,   ← registered at conversation creation
  automaticToolCalling: Boolean              ← true = SDK handles call/response loop
)

Conversation(handle, toolManager, automaticToolCalling)
  └── sendMessageAsync(...)
        ← if automaticToolCalling=true, SDK intercepts tool calls transparently
        ← model output tokens flow out normally after tool loop completes
```

**Critical property:** when `automaticToolCalling = true`, the entire tool call/response
loop happens **inside the SDK** before any token reaches our `collect {}` block.
`EngineHandler.processTask` does not change at all — tools are completely transparent
to the streaming layer.

---

## 2. Gateway Tool Layer (What We Build)

We never implement `InternalJsonTool` or extend `ToolProvider` directly in application
code. Instead we introduce our own interface `GatewayTool` and a bridge class
`GatewayToolProvider` that adapts our tools to what the SDK expects.

```
         ┌─────────────────────────────────────────────────────┐
         │                   Our Code                           │
         │                                                       │
         │   GatewayTool (interface)                            │
         │     descriptor: ToolDescriptor                       │
         │     execute(params: JsonObject): Any?                │
         │              │                                        │
         │   ┌──────────▼──────────────────────────┐           │
         │   │       GatewayToolProvider            │           │
         │   │  extends ToolProvider                │           │
         │   │                                      │           │
         │   │  provideTools():                     │           │
         │   │    tools.associate { tool ->         │           │
         │   │      name to InternalJsonTool {      │           │
         │   │        getToolDescription()          │           │
         │   │          = tool.descriptor           │           │
         │   │            .toJsonObject()           │           │
         │   │        execute(params)               │           │
         │   │          = tool.execute(params)      │           │
         │   │      }                               │           │
         │   │    }                                 │           │
         │   └──────────────────────────────────────┘           │
         │              │                                        │
         └──────────────┼────────────────────────────────────── ┘
                        │  passed as List<ToolProvider>
                        ▼
         ┌──────────────────────────────┐
         │   LiteRTLM SDK               │
         │   ToolManager                │
         │   ConversationConfig         │
         │   Conversation               │
         └──────────────────────────────┘
```

---

## 3. Full Component Diagram

```
┌─────────────────────────────────────────────────────────────────────────────┐
│  lm/tool/                                                                    │
│                                                                              │
│  ┌─────────────────┐   ┌───────────────────────────────────────────────┐   │
│  │  ToolRegistry   │   │  GatewayTool (interface)                       │   │
│  │  (object)       │   │  ─────────────────────────────────────────     │   │
│  │                 │   │  descriptor: ToolDescriptor                    │   │
│  │  register(tool) │   │    name: String                                │   │
│  │  unregister(n)  │   │    description: String                         │   │
│  │  get(name)      │   │    parameters: List<ToolParam>                 │   │
│  │  getAll(names)  │   │                                                │   │
│  │  list()         │   │  execute(params: JsonObject): Any?             │   │
│  │                 │   │    ↑ synchronous — called on SDK thread        │   │
│  │  tools:         │   │    ↑ return String for simple results          │   │
│  │  Map<String,    │   │    ↑ return Map<String,Any> for structured     │   │
│  │  GatewayTool>   │   │    ↑ return error String on failure (no throw) │   │
│  └────────┬────────┘   └───────────────────────────┬───────────────────┘   │
│           │                                         │                        │
│           │  getAll(names)                          │ implemented by         │
│           ▼                                         ▼                        │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │  builtin/                                                            │   │
│  │   DateTimeTool    — name: "datetime"                                 │   │
│  │   CalculatorTool  — name: "calculator"                               │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                                                                              │
│  ┌───────────────────────────────────────────────────────────────────────┐  │
│  │  GatewayToolProvider  (extends LiteRTLM ToolProvider)                 │  │
│  │  ─────────────────────────────────────────────────────────────────    │  │
│  │  constructor(tools: List<GatewayTool>)                                │  │
│  │  provideTools(): Map<String, InternalJsonTool>                        │  │
│  │    wraps each GatewayTool as an anonymous InternalJsonTool:           │  │
│  │      getToolDescription() = descriptor.toJsonObject()                 │  │
│  │      execute(params)      = gatewayTool.execute(params)               │  │
│  └───────────────────────────────────────────────────────────────────────┘  │
│                                                                              │
│  ┌───────────────────────────────────────────────────────────────────────┐  │
│  │  ToolDescriptor                    ToolParam                           │  │
│  │  ─────────────                    ──────────                           │  │
│  │  name: String                     name: String                         │  │
│  │  description: String              type: ToolParamType                  │  │
│  │  parameters: List<ToolParam>        (STRING|INT|FLOAT|BOOLEAN|OBJECT)  │  │
│  │  toJsonObject(): JsonObject       description: String                  │  │
│  │                                   required: Boolean                    │  │
│  │                                   default: Any?                        │  │
│  └───────────────────────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## 4. Integration with Existing Handler Stack

```
POST /api/conversations  { "name": "my-chat", "config": "coder", "tools": ["datetime"] }
        │
        ▼
ConversationHandler.createConversation(name, ..., tools = ["datetime"])
        │
        ▼
MessageHandler.saveConversation(LMStoredConversation(
    name        = "my-chat",
    configLabel = "coder",
    tools       = "datetime"          ← comma-separated, nullable column
))
        │
        ─────────────────────────────────────────────────────────────────────
        later: client sends a message
        ─────────────────────────────────────────────────────────────────────
        │
        ▼
ConversationHandler.sendMessage("my-chat", "What time is it?")
        │
        ▼
MessageHandler.buildConfig("my-chat")
        │
        ├─ getConversation("my-chat")     → LMStoredConversation
        ├─ loadHistory("my-chat")         → List<Message>
        ├─ parseTools("datetime")         → ToolRegistry.getAll(["datetime"])
        │                                    → List<GatewayTool>
        │
        └─ ConversationConfig(
               systemInstruction   = ...,
               initialMessages     = history,
               samplerConfig       = SamplerConfig(topK, topP, temperature),
               tools               = listOf(GatewayToolProvider(gatewayTools)),
               automaticToolCalling = tools.isNotEmpty()
           )
        │
        ▼
ConversationTask(config, message)  →  EngineHandler.taskChannel
        │                              (no change to EngineHandler)
        ▼
Worker picks task:
  engine.createConversation(config)
        │
        │  ConversationConfig.tools → SDK builds ToolManager internally
        │
        ▼
  conversation.sendMessageAsync("What time is it?")
        │
        │   ┌─────────────────────────────────────────────────────────┐
        │   │  Inside LiteRTLM SDK (automaticToolCalling = true)       │
        │   │                                                           │
        │   │  model emits: <tool_call>datetime({})</tool_call>        │
        │   │       │                                                   │
        │   │       ▼                                                   │
        │   │  ToolManager.execute("datetime", {})                     │
        │   │       │                                                   │
        │   │       ▼                                                   │
        │   │  GatewayToolProvider → DateTimeTool.execute({})          │
        │   │       │                                                   │
        │   │       ▼                                                   │
        │   │  returns "2026-04-11 10:30:00"                           │
        │   │       │                                                   │
        │   │       ▼                                                   │
        │   │  SDK feeds result back to model as tool response         │
        │   │  model continues generating final answer                 │
        │   └─────────────────────────────────────────────────────────┘
        │
        ▼
  Flow<Message> — only final answer tokens reach our collect {}
        │
        ▼
  WsChunk.Token → WsChunk.Token → ... → WsChunk.Done
        │
        ▼
ConversationHandler persists user + model messages (unchanged)
```

---

## 5. Package Structure

```
lm/
└── tool/
    ├── GatewayTool.kt            interface: descriptor, execute(JsonObject): Any?
    ├── GatewayToolProvider.kt    extends ToolProvider — bridges List<GatewayTool>
    │                             to Map<String, InternalJsonTool>
    ├── ToolDescriptor.kt         name, description, parameters: List<ToolParam>
    │                             + toJsonObject(): JsonObject  (schema for model)
    ├── ToolParam.kt              name, type: ToolParamType, description,
    │                             required: Boolean, default: Any?
    ├── ToolParamType.kt          enum: STRING, INT, FLOAT, BOOLEAN, OBJECT
    └── builtin/
        ├── DateTimeTool.kt       "datetime" — current date/time, optional format param
        └── CalculatorTool.kt     "calculator" — evaluate a math expression
```

### Files changed in existing packages

| File | Change |
|---|---|
| `lm/entity/LMStoredConversation.kt` | Add `tools: String?` column — comma-separated tool names, null = no tools |
| `lm/handler/MessageHandler.kt` | `buildConfig`: parse `tools` column → `ToolRegistry.getAll()` → `GatewayToolProvider` → `ConversationConfig.tools` |
| `lm/handler/ConversationHandler.kt` | `createConversation`: add `tools: List<String>` param |
| `lm/LMService.kt` | `start()`: register builtin tools in `ToolRegistry` after engine ready |
| `api/route/RouteConversation.kt` | `POST /conversations`: read optional `"tools"` array from body |
| `api/route/dto/RouteDtoConversation.kt` | `CreateConversationRequest`: add `tools: List<String>?` |
| `api/route/RouteConfig.kt` | Add `GET /api/tools` — list registered tools + descriptors |

---

## 6. ToolDescriptor JSON Schema

`ToolDescriptor.toJsonObject()` must produce the schema the model reads to understand
what the tool does and what parameters to pass:

```json
{
  "name": "datetime",
  "description": "Get the current date and time in a specified format.",
  "parameters": {
    "type": "object",
    "properties": {
      "format": {
        "type": "string",
        "description": "Java date format pattern e.g. yyyy-MM-dd HH:mm:ss"
      }
    },
    "required": []
  }
}
```

```json
{
  "name": "calculator",
  "description": "Evaluate a mathematical expression and return the numeric result.",
  "parameters": {
    "type": "object",
    "properties": {
      "expression": {
        "type": "string",
        "description": "A mathematical expression e.g. (3 + 5) * 2"
      }
    },
    "required": ["expression"]
  }
}
```

`ToolParamType` maps to JSON schema `type` strings:

| ToolParamType | JSON schema type |
|---|---|
| `STRING` | `"string"` |
| `INT` | `"integer"` |
| `FLOAT` | `"number"` |
| `BOOLEAN` | `"boolean"` |
| `OBJECT` | `"object"` |

---

## 7. Error Handling

`InternalJsonTool.execute()` returns `Any?`. The SDK serializes this and feeds it to
the model. We never throw from `execute()` — throwing propagates into the SDK's
`ToolManager.execute()` and may corrupt the inference state.

Instead, all tools return a descriptive error string:

```kotlin
override fun execute(params: JsonObject): Any? {
    val expr = params.get("expression")?.asString
        ?: return "Error: missing required parameter 'expression'"
    return try {
        evaluate(expr).toString()
    } catch (e: Exception) {
        "Error: could not evaluate expression '$expr': ${e.message}"
    }
}
```

The model receives the error string as the tool result and can respond accordingly
(e.g. "I couldn't evaluate that expression, please check the syntax.").

---

## 8. ToolRegistry

`ToolRegistry` is a singleton that owns all registered tools. Builtins are registered
automatically in `LMService.start()`. Additional tools can be registered at any time
before a conversation sends a message (registration is checked at `buildConfig` time,
not at conversation creation time).

```
object ToolRegistry {
    private val tools: ConcurrentHashMap<String, GatewayTool>

    fun register(tool: GatewayTool)           // idempotent — overwrites on name collision
    fun unregister(name: String): Boolean
    fun get(name: String): GatewayTool?
    fun getAll(names: List<String>): List<GatewayTool>
                                              // silently skips unknown names + logs warning
    fun list(): List<ToolDescriptor>          // all registered tools, for GET /api/tools
}
```

**Registration in `LMService.start()`:**
```kotlin
ToolRegistry.register(DateTimeTool())
ToolRegistry.register(CalculatorTool())
// future: ToolRegistry.register(WebSearchTool(apiKey))
```

**Unknown tool names at `buildConfig` time:** if a stored conversation references
`"web_search"` but that tool is not registered, `getAll()` skips it and logs a warning.
The conversation still works — it just has fewer tools available than when it was created.

---

## 9. DB Change — `LMStoredConversation.tools`

```kotlin
@DaoTable(name = "lm_conversations")
data class LMStoredConversation(
    ...
    @DaoColumn var tools: String?,   // null = no tools  |  "datetime,calculator" = two tools
    ...
)
```

Comma-separated string — no join table needed. Tool names are short identifiers with
no commas. `buildConfig` splits on `","` and trims each entry.

**Why not a join table?** Tool bindings are read-only after conversation creation and
the set is small (typically 0–3 tools). A join table adds two queries and a DAO class
for no practical benefit at this scale.

---

## 10. API Changes

### `POST /api/conversations` — updated request body

```json
{
  "name":   "research-chat",
  "config": "assistant",
  "tools":  ["datetime", "calculator"]
}
```

`tools` is optional. Omitting it or passing `[]` creates a conversation with no tools
and `automaticToolCalling = false`.

### `GET /api/tools` — new endpoint (no auth required, informational)

Lists all tools currently registered in `ToolRegistry`:

```json
{
  "ok": true,
  "tools": [
    {
      "name": "datetime",
      "description": "Get the current date and time in a specified format.",
      "parameters": [
        { "name": "format", "type": "STRING", "description": "...", "required": false }
      ]
    },
    {
      "name": "calculator",
      "description": "Evaluate a mathematical expression and return the numeric result.",
      "parameters": [
        { "name": "expression", "type": "STRING", "description": "...", "required": true }
      ]
    }
  ]
}
```

---

## 11. Open Questions

| # | Question | Decision needed |
|---|---|---|
| 1 | `execute()` return type | Return `String` for simplicity, or `Map<String,Any>` for structured data? Recommend **String** — the model reads it as text, no serialization ambiguity |
| 2 | Tool registration timing | Auto-register builtins in `LMService.start()` (recommended) or explicit caller registration? |
| 3 | Unknown tool at message time | Skip silently + warn (recommended) or error the whole `sendMessage`? |
| 4 | `RECURRING_TOOL_CALL_LIMIT` | SDK has a hard limit on recursive tool calls. Value unknown (compiled). Need to test to discover the limit and document it. |
| 5 | Tool call serialization | Does `execute()` returning a plain `String` get correctly serialized by the SDK back to the model, or does it need to be a `JsonPrimitive`? Needs a quick test. |
