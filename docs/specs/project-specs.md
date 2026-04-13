## Project Overview

LiteRTLM AI Gateway is a Kotlin/JVM HTTP gateway that wraps Google AI Edge LiteRTLM for on-device LLM inference. It exposes a REST API for conversation management, authentication, API key management, and tool execution, backed by SQLite persistence and a plugin-ready architecture.

---

## Tech Stack

| Layer | Technology |
|-------|-----------|
| Language | Kotlin 2.3.10, JDK 21 |
| Build | Gradle Kotlin DSL 9.0.0 |
| HTTP server | Ktor 3.4.2 (Netty engine) |
| LLM inference | Google AI Edge LiteRTLM JVM SDK 0.10.0 |
| Database | SQLite via `DaoSqlite` (desktopplatform.jar) |
| Logging | `ILog` / `ILogImpl` (applicationbase.jar) |
| JSON | Gson 2.13.2 |
| Connection pool | HikariCP 5.1.0 |

---

## Folder Layout

```
litertlm-ai-gateway/
│
├── .env                            # Runtime secrets (git-ignored)
├── .env.example                    # Template — commit this, not .env
├── build.gradle.kts                # Gradle build + dependencies
├── settings.gradle.kts
│
├── libs/                           # Local JARs (vendored)
│   ├── applicationbase.jar         # Dao interface, ILog, LRUCache, Service base
│   └── desktopplatform.jar         # DaoSqlite, DaoFile, ILogImpl
│
├── jni-libs/                       # Native shared libraries for LiteRTLM
│   └── libLiteRt_linux_x86_64.so
│
├── model/                          # LiteRTLM model files (git-ignored)
│   ├── gemma4-e2b/
│   └── gemma4-e4b/
│
├── docs/
│   ├── specs/
│   │   ├── project-specs.md        # This file
│   │   ├── litertlmlibs-specs.md   # LiteRTLM SDK research notes
│   │   └── ai-guidances.md         # AI agent behaviour guidelines
│   └── sketch/
│       ├── architecture.md         # Full system architecture
│       └── tool-architecture.md    # Tool system design
│
└── src/main/kotlin/
    │
    ├── Main.kt                     # Entry point — starts Ktor on :8080
    ├── LMApplication.kt            # Service bootstrap: DB, auth, engine init
    │
    ├── api/
    │   ├── plugin/
    │   │   └── AuthPlugin.kt       # AuthPlugin (JWT), ApiKeyPlugin, DualAuthPlugin
    │   │
    │   └── route/
    │       ├── Route.kt            # Registers all routes on Application
    │       ├── RouteAuth.kt        # POST /auth/login|refresh|logout
    │       ├── RouteConversation.kt# GET|POST /conversations, GET|POST|DELETE /{name}/messages
    │       ├── RouteWebSocket.kt   # WS /ws/conversations/{name}
    │       ├── RouteApiKey.kt      # POST /api-key/generate, GET /list|info, DELETE /revoke
    │       ├── RouteConfig.kt      # Static UI + GET /api/tools + GET /api/queue
    │       ├── RouteExtensions.kt  # respondJson() extension for ApplicationCall
    │       └── dto/
    │           ├── RouteDtoAuth.kt         # LoginRequest/Response, RefreshRequest, ...
    │           ├── RouteDtoAppKey.kt       # GenerateKeyRequest/Response, ApiKeyInfo, ...
    │           ├── RouteDtoConversation.kt # CreateConversationRequest/Response, SendMessage,
    │           │                           # GetMessages, StoredMessageDto, ToolDto, ...
    │           ├── RouteDtoWebSocket.kt    # WsIncomingMessage, WsQueuedFrame, WsBusyFrame,
    │           │                           # WsTokenFrame, WsDoneFrame, WsErrorFrame
    │           └── RouteDtoCommon.kt       # OkResponse, ApiErrorResponse
    │
    ├── auth/
    │   ├── LMApiKey.kt             # @DaoTable entity: id, keyHash, keyPrefix, name, active, timestamps
    │   ├── LMServiceApiKey.kt      # Generate, validate, revoke, list API keys
    │   ├── LMAuth.kt               # LMAuthJwt data class
    │   └── LMServiceAuth.kt        # Single-user JWT auth: login, refresh, logout, validate
    │
    ├── lm/
    │   ├── LMService.kt            # Singleton: setDao, setEngineConfig, start, stop
    │   │                           # Registers builtin tools on start
    │   ├── builtin/
    │   │   └── BuiltinConversationConfig.kt  # ASSISTANT, CODER, CONCISE, CREATIVE presets
    │   ├── entity/
    │   │   ├── LMStoredConversation.kt  # @DaoTable lm_conversations
    │   │   └── LMStoredMessage.kt       # @DaoTable lm_messages
    │   ├── handler/
    │   │   ├── WsChunk.kt               # sealed class: Token, Done, Error, Queued, Busy
    │   │   ├── ConversationHandler.kt   # Orchestrator: lifecycle + sendMessage flow
    │   │   ├── MessageHandler.kt        # DB persistence + buildConfig (with tool wiring)
    │   │   └── EngineHandler.kt         # Single engine, dedicated thread, task queue + queue visibility
    │   └── tool/
    │       ├── GatewayOpenApiTool.kt           # interface: descriptor + execute(JsonObject): Any?
    │       ├── GatewayToolProvider.kt   # extends ToolProvider — bridges to InternalJsonTool
    │       ├── ToolDescriptor.kt        # name, description, parameters + toJsonObject()
    │       ├── ToolParam.kt             # name, type, description, required, default
    │       ├── ToolParamType.kt         # enum: STRING, INT, FLOAT, BOOLEAN, OBJECT
    │       ├── ToolRegistry.kt          # singleton: register, get, getAll, list, clear
    │       └── builtin/
    │           ├── DateTimeOpenApiTool.kt      # "datetime" — current date/time
    │           └── CalculatorTool.kt    # "calculator" — math expression evaluator
    │
    ├── callback/
    │   └── RequestCallback.kt      # Generic onSuccess/onError interface
    │
    └── utils/
        ├── EnvConfig.kt            # .env file parser, env var lookup
        └── JsonUtils.kt            # Gson wrapper (toJson, fromJson)
```

---

## API Routes

### Public (no auth)

| Method | Path | Description |
|--------|------|-------------|
| GET | `/` | Serves the web UI (`index.html`) |
| GET | `/api/tools` | List all registered tools and their schemas |
| GET | `/api/queue` | Live inference queue (conversation names, positions, statuses) |
| POST | `/api/auth/login` | Login → `{ accessToken, refreshToken }` |
| POST | `/api/auth/refresh` | Rotate tokens → new `{ accessToken, refreshToken }` |
| POST | `/api/auth/logout` | Revoke refresh token |

### Protected — JWT or API key (`DualAuthPlugin`)

| Method | Path | Description |
|--------|------|-------------|
| GET | `/api/conversations` | List all conversations |
| POST | `/api/conversations` | Create conversation (`name`, `config`, `systemInstruction`, `tools`) |
| GET | `/api/conversations/{name}/messages` | Fetch full message history |
| POST | `/api/conversations/{name}/messages` | Send message → blocking `{ reply }` |
| DELETE | `/api/conversations/{name}` | Delete conversation and all history |
| WS | `/ws/conversations/{name}?token=...` | Streaming inference over WebSocket |

### Protected — JWT only (`AuthPlugin`)

| Method | Path | Description |
|--------|------|-------------|
| POST | `/api/api-key/generate` | Generate API key → one-time raw key |
| GET | `/api/api-key/list` | List all keys (hashes never exposed) |
| GET | `/api/api-key/info?key=lrtlm_...` | Get key metadata |
| DELETE | `/api/api-key/revoke` | Soft-revoke a key |

---

## Tool System

Tools allow the model to call server-side functions during inference using LiteRTLM's native `automaticToolCalling` mechanism. The entire tool call/response loop happens inside the SDK — from the client's perspective, the final reply streams out as normal tokens.

### How tools are wired

```
GatewayTool  →  GatewayToolProvider (extends ToolProvider)
                    ↓  passed as List<ToolProvider>
             ConversationConfig(tools=..., automaticToolCalling=true)
                    ↓
             SDK builds ToolManager internally
                    ↓
             During sendMessageAsync: model emits tool call
                    → SDK calls InternalJsonTool.execute(params)
                    → GatewayToolProvider dispatches to GatewayTool.execute()
                    → result returned to model as tool response
                    → model continues generating final answer
```

### Built-in tools

| Name | Description | Parameters |
|------|-------------|------------|
| `datetime` | Returns the current date/time | `format` (optional, Java pattern), `timezone` (optional, IANA ID) |
| `calculator` | Evaluates a math expression | `expression` (required, e.g. `Math.pow(2,10)`) |

### Tool binding

Tools are bound at conversation creation and stored as a comma-separated column in `lm_conversations`. At each `sendMessage`, `MessageHandler.buildConfig` resolves the stored names via `ToolRegistry` and passes the active tools into `ConversationConfig`.

Unknown tool names (e.g. a tool that was unregistered after conversation creation) are silently skipped with a warning log — the conversation continues with the remaining tools.

### Registering a custom tool

```kotlin
class MyTool : GatewayTool {
    override val descriptor = ToolDescriptor(
        name = "my_tool",
        description = "Does something useful",
        parameters = listOf(
            ToolParam("input", ToolParamType.STRING, "The input value")
        )
    )
    override fun execute(params: JsonObject): Any? {
        val input = params.get("input")?.asString
            ?: return "Error: missing 'input'"
        return "Result: $input"
    }
}

// Register before conversations start sending messages:
ToolRegistry.register(MyTool())
```

---

## Authentication

Two credential types, both passed as `Authorization: Bearer <token>`:

| Type | Prefix | Used for |
|------|--------|----------|
| JWT access token | `eyJ...` | Interactive users (login flow) |
| API key | `lrtlm_...` | Programmatic clients |

`/api/conversations` and `/ws/conversations` accept either. `/api/api-key` accepts JWT only.
Credentials are configured via environment variables — there is no user registration endpoint.

---

## Environment Variables (`.env`)

Copy `.env.example` to `.env` and fill in values before running.

| Variable | Description |
|----------|-------------|
| `AUTH_USERNAME` | Single admin username |
| `AUTH_PASSWORD` | Admin password |
| `JWT_SECRET` | HMAC-SHA256 signing secret for JWT tokens |

Real environment variables always take precedence over `.env` file values.

---

## Build & Run

### Prerequisites
- JDK 21+
- `.env` file configured (copy from `.env.example`)
- Model file(s) present under `model/`

### Build fat JAR

```bash
./gradlew buildFatJar
```

Output: `build/libs/aigateway-1.0-all.jar`

### Run

```bash
./gradlew runFatJar
```

Or directly:

```bash
java -jar build/libs/aigateway-1.0-all.jar
```

Override env vars at runtime:

```bash
AUTH_USERNAME=admin AUTH_PASSWORD=secret JWT_SECRET=mysecret \
  java -jar build/libs/aigateway-1.0-all.jar
```

The server starts on `http://0.0.0.0:8080`.

---

## Data Directory

At first run, `LMApplication` creates a data directory (`appDirName = "lm_application"`):

```
lm_application/
└── lm_application.db    # SQLite: api_keys, lm_conversations, lm_messages
```

---

## Implemented Features

- [x] User authentication (JWT — HS256, 15 min access / 7 day refresh, token rotation)
- [x] API key management (SHA-256 hashed, `lrtlm_` prefix, soft-revoke)
- [x] Conversation management (create, list, send message, delete)
- [x] Conversation history persistence (SQLite `lm_messages` table, `seq`-ordered)
- [x] Message history replay on every inference (`initialMessages` in `ConversationConfig`)
- [x] Four builtin conversation presets (ASSISTANT, CODER, CONCISE, CREATIVE)
- [x] Custom system instruction per conversation
- [x] Dual-auth on conversation routes (JWT or API key)
- [x] WebSocket streaming inference (`/ws/conversations/{name}`) with live token streaming
- [x] REST blocking inference (`POST /conversations/{name}/messages`)
- [x] Single-engine architecture with dedicated thread (native thread affinity)
- [x] Serialized task queue with live queue visibility (conversation names + positions)
- [x] Queue position notification to WS clients (queued frame before busy)
- [x] Detached inference — survives WS disconnect, client reconnects mid-stream or after
- [x] Token streaming via MutableSharedFlow (non-blocking, no thread boundary issues)
- [x] Tool execution framework — native `automaticToolCalling` via LiteRTLM SDK
- [x] Built-in tools: `datetime`, `calculator`
- [x] Tool binding per conversation (stored in DB, resolved at inference time)
- [x] `GET /api/tools` endpoint — list registered tools and schemas
- [x] `.env` file support with environment variable override
- [x] SQLite persistence via `DaoSqlite` (HikariCP pool)
- [x] Structured logging via `ILog` / `ILogImpl`
- [x] Web UI (SPA — conversations with live streaming, API keys, queue monitor, API docs with tool section)

## Planned Features

- [ ] Rate limiting and usage tracking per API key
- [ ] Multiple model support with dynamic loading/switching
- [ ] Plugin architecture for external service integration (vector DBs, knowledge bases)
- [ ] Admin dashboard (SPA + SSE live metrics)

