# LiteRTLM AI Gateway — Technical Architecture (AI Should not read this)

> Version 0.4 | April 2026
> Single-instance, on-device AI gateway wrapping Google AI Edge LiteRTLM
> Storage: DaoSqlite (desktopplatform) | Logging: ILogImpl (desktopplatform) | Cache: LRUCache (applicationbase)
>
> **Legend:** Sections marked `[IMPLEMENTED]` reflect the current working codebase.
> Sections marked `[PLANNED]` are design targets not yet built.

---

## 1. System Overview `[IMPLEMENTED]`

```
                        +--------------------------+
                        |   Client / Browser UI    |
                        +-----------+--------------+
                                    |
                               HTTP/WS :8080
                                    |
+-----------------------------------v--------------------------------------+
|                          Ktor Application                                |
|                                                                          |
|  +---------------------+  +--------------------------------------------+|
|  | AuthPlugin (JWT)    |  | DualAuthPlugin (JWT or API Key)             ||
|  | ApiKeyPlugin        |  | — installed per route group, not globally   ||
|  +----------+----------+  +-------------------+------------------------+||
|             |                                  |                         |
|  +----------v----------------------------------v---------------------+  |
|  |                        Route Layer                                |  |
|  |  /api/auth/*           /api/conversations/*   /api/api-key/*     |  |
|  |  /ws/conversations/*   /api/queue             / (static UI)      |  |
|  +----------+----------------------------------------------------+--+  |
|             |                                                     |     |
|  +----------v--------------------------------------------------+  |     |
|  |                     LMService (singleton)                   |  |     |
|  |  setDao() / setEngineConfig() / start() / stop()           |  |     |
|  |                                                             |  |     |
|  |  +------------------+   +------------------+               |  |     |
|  |  | ConversationHandler|  | MessageHandler   |               |  |     |
|  |  | (orchestrator)   |   | (DB persistence) |               |  |     |
|  |  +--------+---------+   +--------+---------+               |  |     |
|  |           |                      |                          |  |     |
|  |  +--------v----------------------v-+                        |  |     |
|  |  |        EngineHandler            |                        |  |     |
|  |  |  taskChannel: Channel<Task>     |                        |  |     |
|  |  |  queueNames: ConcurrentDeque    |                        |  |     |
|  |  |  worker ── Engine (1 thread)    |                        |  |     |
|  |  +---------------------------------+                        |  |     |
|  +-------------------------------------------------------------+  |     |
|                                                                    |     |
|  +-----------------------------------+                             |     |
|  |      Storage Layer (SQLite)       |<----------------------------+     |
|  |  lm_conversations | lm_messages   |                                   |
|  |  api_keys                         |                                   |
|  +-----------------------------------+                                   |
+--------------------------------------------------------------------------+
```

### Request flow — WebSocket streaming

```
Client connects: WS /ws/conversations/{name}?token=...
    |
    |-- DualAuthPlugin validates token (JWT or API key)
    |-- Engine ready check (503 if LMService.conversationHandler == null)
    |
    |  [message loop — connection stays open for full conversation lifetime]
    |
    Client sends: { "message": "Hello" }
    |
    v
ConversationHandler.sendMessage(name, message): Flow<WsChunk>
    |
    |-- MessageHandler.buildConfig(name)
    |    |-- getConversation(name)   -> LMStoredConversation  (config + sampler)
    |    +-- loadHistory(name)       -> List<Message>  (last 10, ordered by seq)
    |         +-- ConversationConfig(systemInstruction, initialMessages, samplerConfig)
    |
    |-- ConversationJobRegistry.startJob(name)  -> BUSY
    |
    |-- launch detached coroutine:
    |    ConversationTask(convName, config, message) enqueued to EngineHandler.taskChannel
    |        +-- queueNames.addLast(convName)
    |        +-- suspends until worker picks it up (queues if busy)
    |
    |-- if queueSize > 0: emit Queued(position)
    +-- emit Busy
    |
    Worker picks up task (on dedicated engine-thread):
    |-- engine.createConversation(config)   -- transient native object
    |-- conversation.sendMessageAsync(message)
    |    |-- Token -> task.reply channel -> onToken() -> replyBuffer + tokenFlow.tryEmit()
    |    +-- (inference complete)
    |-- WsChunk.Done emitted
    |    +-- ConversationHandler persists user + model messages via MessageHandler
    |-- conversation.close()     -- frees engine resources
    |-- queueNames.pollFirst()   -- remove from visible queue
    |
    v
    WS handler:
    |-- { "type": "queued", "position": N }  (if task was queued)
    |-- { "type": "busy" }
    |-- launch { tokenFlow.collect -> { "type": "token", "token": "..." } }
    |-- deferred.await()
    |-- tokenJob.cancelAndJoin()
    +-- { "type": "done", "reply": "..." }
    |
    [client sends next message — same WS connection, new inference cycle]
```

### Request flow — REST (blocking)

```
POST /api/conversations/{name}/messages  { "message": "..." }
    │
    ▼
Same ConversationHandler.sendMessage() flow as above
    │
    collect all WsChunk.Token → join into full reply string
    │
    ▼
200 { "ok": true, "reply": "..." }
```

---

## 2. Package Structure `[IMPLEMENTED]`

```
org.thingai.app.aigateway/
│
├── Main.kt                              # Ktor server bootstrap
├── LMApplication.kt                     # Service bootstrap: DB init, auth, engine start
│
├── api/
│   ├── plugin/
│   │   └── AuthPlugin.kt               # AuthPlugin, ApiKeyPlugin, DualAuthPlugin
│   │
│   └── route/
│       ├── Route.kt                     # Route registration
│       ├── RouteConfig.kt              # Static UI serving + GET /api/tools + GET /api/queue
│       ├── RouteAuth.kt                # POST /auth/login|refresh|logout
│       ├── RouteConversation.kt        # GET|POST /conversations, GET|POST|DELETE /{name}/messages
│       ├── RouteApiKey.kt              # POST /api-key/generate, GET /list|info, DELETE /revoke
│       ├── RouteWebSocket.kt           # WS /ws/conversations/{name}
│       └── dto/
│           ├── RouteDtoCommon.kt       # OkResponse, ApiErrorResponse
│           ├── RouteDtoAuth.kt         # LoginRequest/Response, RefreshRequest/Response, LogoutRequest
│           ├── RouteDtoConversation.kt # CreateConversation*, ListConversations*, GetMessages*, SendMessage*
│           ├── RouteDtoAppKey.kt       # GenerateKey*, ListKeys*, RevokeKey*, KeyInfo*
│           └── RouteDtoWebSocket.kt    # WsIncomingMessage, WsQueuedFrame, WsBusyFrame,
│                                      # WsTokenFrame, WsDoneFrame, WsErrorFrame
│
├── auth/
│   ├── LMApiKey.kt                     # @DaoTable entity: id, keyHash, keyPrefix, name, active, timestamps
│   ├── LMServiceApiKey.kt              # generateApiKey, validateApiKey, revokeApiKey, listApiKeys
│   ├── LMServiceAuth.kt               # Single-user JWT auth: login, refresh, logout, validateAccessToken
│   └── LMAuth.kt                       # LMAuthJwt data class
│
├── lm/
│   ├── LMService.kt                    # Singleton: setDao, setEngineConfig, start, stop
│   │                                   # Constructs and owns: EngineHandler, MessageHandler, ConversationHandler
│   │
│   ├── handler/
│   │   ├── WsChunk.kt                  # sealed class: Token(text), Done, Error(message), Queued(position), Busy
│   │   ├── ConversationHandler.kt      # createConversation, deleteConversation, listConversations,
│   │   │                               # hasConversation, getHistory, sendMessage → Flow<WsChunk>
│   │   ├── MessageHandler.kt           # saveConversation, getConversation, listConversations,
│   │   │                               # deleteConversation, appendMessages, nextSeq,
│   │   │                               # getHistory, buildConfig
│   │   └── EngineHandler.kt            # Single engine, dedicated thread, task queue, QueueEntry
│   │
│   ├── entity/
│   │   ├── LMStoredConversation.kt     # @DaoTable lm_conversations: name(PK), systemInstruction,
│   │   │                               # configLabel, topK, topP(Double), temperature(Double), createdAt
│   │   └── LMStoredMessage.kt          # @DaoTable lm_messages: id(PK), conversationName, role,
│   │                                   # text, seq, createdAt
│   │
│   └── builtin/
│       └── BuiltinConversationConfig.kt # ASSISTANT, CODER, CONCISE, CREATIVE presets
│
├── utils/
│   ├── EnvConfig.kt                    # .env file loader
│   └── JsonUtils.kt                    # Gson-based JSON helpers
│
└── callback/
    └── RequestCallback.kt              # onSuccess / onError interface
```

---

## 3. Core Module Specifications

> Sections 3.1–3.3 reflect the current implementation.
> Sections 3.4–3.9 are planned features not yet built.

### 3.1 Authentication & API Key Management `[IMPLEMENTED]`

#### Single-user JWT Auth (`LMServiceAuth`)

Credentials are configured via environment variables — no DB table for users.

```
ENV vars:
  AUTH_USERNAME=admin         # single admin username
  AUTH_PASSWORD=...           # admin password
  JWT_SECRET=...              # HS256 signing secret
```

```kotlin
class LMServiceAuth(jwtSecret: String, username: String, password: String) {
    // In-memory: only currentRefreshJti tracked (@Volatile)
    // Access token TTL:  15 minutes
    // Refresh token TTL: 7 days (rotates on every refresh)

    fun login(username, password): LMAuthJwt?     // returns { accessToken, refreshToken }
    fun refresh(refreshToken): LMAuthJwt?          // rotates JTI, returns new pair
    fun logout(refreshToken): Boolean              // clears currentRefreshJti
    fun validateAccessToken(token): String?        // returns username or null
}
```

JWT payload: `{ sub, jti, iat, exp, type: "access"|"refresh" }`
Algorithm: HS256 (HMAC-SHA256), built without external JWT library.

#### API Key Management (`LMServiceApiKey`)

```kotlin
// LMApiKey @DaoTable: lm_api_keys (stored in lm_application.db)
data class LMApiKey(
    var id: String,          // UUID
    var keyHash: String,     // SHA-256 of raw key — never stored in plaintext
    var keyPrefix: String,   // first 8 chars e.g. "lrtlm_a3" — for display only
    var name: String,        // human label
    var active: Boolean,
    var createdAt: Long,
    var lastUsedAt: Long?
)

class LMServiceApiKey(dao: DaoSqlite) {
    fun generateApiKey(name): GeneratedKey?        // returns { rawKey, apiKey record }
    fun validateApiKey(rawKey): Boolean            // hashes + checks active
    fun revokeApiKey(rawKey): Boolean
    fun listApiKeys(): List<LMApiKey>
    fun getApiKeyInfo(rawKey): LMApiKey?
}
```

Raw key format: `lrtlm_` + 48 random alphanumeric chars.

#### Auth Plugins (Ktor route-scoped)

| Plugin | Header | Used on |
|---|---|---|
| `AuthPlugin` | `Authorization: Bearer <accessToken>` | `/api/api-key/*` |
| `DualAuthPlugin` | `Authorization: Bearer <accessToken\|apiKey>` | `/api/conversations/*`, WS |

---

### 3.2 Conversation & Message Architecture `[IMPLEMENTED]`

#### Entities

```kotlin
// lm/entity/LMStoredConversation.kt  →  @DaoTable("lm_conversations")
data class LMStoredConversation(
    var name: String,               // PK — user-provided unique name
    var systemInstruction: String?, // null → use builtin preset
    var configLabel: String,        // "assistant"|"coder"|"concise"|"creative"|"custom"
    var topK: Int,
    var topP: Double,               // Double — SQLite stores REAL as Double
    var temperature: Double,
    var createdAt: Long
)

// lm/entity/LMStoredMessage.kt  →  @DaoTable("lm_messages")
data class LMStoredMessage(
    var id: String,               // PK — UUID
    var conversationName: String, // FK → LMStoredConversation.name
    var role: String,             // "user" | "model"
    var text: String,
    var seq: Int,                 // monotonically increasing within conversation
                                  // user msg = seq N, model reply = seq N+1
    var createdAt: Long
)
```

#### MessageHandler — DB layer

```
MessageHandler(dao: DaoSqlite)

saveConversation(record)           → insert LMStoredConversation; false if name exists
getConversation(name)              → query by PK
listConversations()                → readAll, sort by createdAt desc, return names
deleteConversation(name)           → delete all lm_messages + the lm_conversations row
appendMessages(name, user, model, seq) → insert 2 rows (seq=N user, seq=N+1 model)
nextSeq(name)                      → max(seq) + 1, or 0 if no messages
getHistory(name)                   → all rows for conversation, sorted by seq asc
buildConfig(name)                  → getConversation + loadHistory → ConversationConfig
```

`buildConfig` constructs a `ConversationConfig` ready for engine re-open:
- `systemInstruction` → `Contents.of(text)` (custom) or from `BuiltinConversationConfig` preset
- `initialMessages` → last 10 `LMStoredMessage` rows mapped to `Message.user()` / `Message.model()`
- `samplerConfig` → `SamplerConfig(topK, topP, temperature)` from stored values

#### BuiltinConversationConfig presets

| Label | topK | topP | temperature | Purpose |
|---|---|---|---|---|
| `assistant` | 40 | 0.95 | 0.8 | General Q&A |
| `coder` | 10 | 0.9 | 0.3 | Deterministic code output |
| `concise` | 10 | 0.85 | 0.2 | Brief factual answers |
| `creative` | 80 | 0.98 | 1.2 | Storytelling / brainstorming |

#### ConversationHandler — orchestrator

```
ConversationHandler(messageHandler, engineHandler)

createConversation(name, systemInstruction?, configLabel, topK, topP, temperature)
    → builds LMStoredConversation record
    → delegates to messageHandler.saveConversation()

deleteConversation(name)   → messageHandler.deleteConversation()
hasConversation(name)      → messageHandler.getConversation(name) != null
listConversations()        → messageHandler.listConversations()
getHistory(name)           → messageHandler.getHistory(name)  [for GET /messages API]

sendMessage(name, message): Flow<WsChunk>
    1. messageHandler.buildConfig(name)           -> null -> emit Error, return
    2. Guard: isBusy(name)                        -> true -> emit Error("busy"), return
    3. ConversationJobRegistry.startJob(name)     -> BUSY
    4. launch detached coroutine:
         ConversationTask(convName, config, message) -> engineHandler.submit()
         collect:
           Token  -> onToken(name, text): replyBuffer + tokenFlow.tryEmit
           Done   -> appendMessages(name, message, replyBuffer, nextSeq) + onDone()
           Error  -> onError(name, reason)
    5. if queueSize > 0: emit Queued(position)
    6. emit Busy
```

#### Key design decisions

- **No native `Conversation` object is ever stored** — all LiteRTLM state is transient, created and closed inside a single `processTask` call
- **History replay on every send** — `initialMessages` in `ConversationConfig` carries the last 10 messages, so the model sees context without an open native session
- **Persistence only on success** — if inference errors, neither the user message nor the partial model reply is persisted, keeping history clean
- **`topP`/`temperature` stored as `Double`** — SQLite REAL maps to JVM `Double`; `Float` fields fail reflection-based DAO deserialization

---

### 3.3 Engine Architecture `[IMPLEMENTED]`

#### EngineHandler — single engine with dedicated thread and task queue

```kotlin
class EngineHandler(engineConfig: EngineConfig) {

    private val taskChannel = Channel<ConversationTask>(Channel.UNLIMITED)
    private val queueNames  = ConcurrentLinkedDeque<String>()
    private val engineThread = Executors.newSingleThreadExecutor { r ->
        Thread(r, "engine-thread").also { it.isDaemon = true }
    }.asCoroutineDispatcher()
    private val scope  = CoroutineScope(engineThread)
    private var engine: Engine? = null
}

data class ConversationTask(
    val convName: String,
    val config: ConversationConfig,
    val message: String,
    val reply: Channel<WsChunk> = Channel(Channel.UNLIMITED)
)

data class QueueEntry(
    val name: String,       // conversation name
    val position: Int,      // 1-based (1 = processing)
    val status: String      // "processing" | "waiting"
)
```

**Startup (`start`):**
```
scope.launch {  // runs on engine-thread
    engine = Engine(engineConfig).also { it.initialize() }
    launchWorker()
    onReady(true)
}
```

**Worker loop (runs on engine-thread):**
```kotlin
for (task in taskChannel) {
    processTask(engine, task)
    queueNames.pollFirst()   // remove from visible queue
}
```

**processTask (runs on engine-thread — same thread as init):**
```
conversation = engine.createConversation(task.config)
    +-- includes initialMessages (history replay)

conversation.sendMessageAsync(task.message)
    .catch { e ->
        task.reply.send(WsChunk.Error(...))
        inferenceError = true
    }
    .collect { token -> task.reply.send(WsChunk.Token(token.toString())) }

if (!inferenceError) task.reply.send(WsChunk.Done)

finally:
    conversation.close()   // release engine resources
    task.reply.close()     // signals Flow completion to caller
```

**submit(task): Flow<WsChunk>**
```
queueNames.addLast(task.convName)    // track in visible queue
taskChannel.send(task)               // enqueue — never blocks (UNLIMITED channel)
for (chunk in task.reply):           // suspends here until worker closes reply channel
    emit(chunk)
    if chunk is Done or Error -> break
```

**Queue visibility:**
```
getQueueSize(): Int = queueNames.size
getQueueEntries(): List<QueueEntry>  // snapshot with position + status
```

**Thread affinity model:**
- LiteRTLM native engine requires all operations on the same thread it was initialized on
- `engine-thread` is a single-thread executor — engine init, createConversation, sendMessageAsync, and close all run here
- The `scope = CoroutineScope(engineThread)` ensures the worker coroutine is always pinned to this thread
- No cross-thread engine access — eliminates native `abort()` / core dump risk

**Concurrency model:**
- Tasks are serialized — one at a time — via the channel
- Queue is unbounded — no request is ever rejected; worst-case latency grows with queue depth
- Queue position is visible via `GET /api/queue` (polled by the UI)

#### LMService — wiring singleton

```kotlin
object LMService {
    var conversationHandler: ConversationHandler?   // null until start() succeeds

    fun setDao(dao: DaoSqlite)
    fun setEngineConfig(config: EngineConfig)

    fun start(onReady: (Boolean) -> Unit):
        // stops previous EngineHandler if any
        // creates: EngineHandler + MessageHandler + ConversationHandler
        // calls engineHandler.start { success →
        //     if success: assigns conversationHandler
        // }

    fun stop():
        // engineHandler.stop() → closes taskChannel + all Engine objects
        // conversationHandler = null
}
```

Routes check `LMService.conversationHandler == null` and return `503 Service Unavailable` during model load.

---

### 3.4 Tool Execution Framework `[PLANNED]`

#### Design

The tool framework bridges two worlds:
1. **Gateway-level tools** — defined via the `Tool` interface, registered in `ToolRegistry`
2. **LiteRTLM-level tools** — the SDK's `ToolManager`, `ToolSet`, `automaticToolCalling`

Gateway tools are wrapped into LiteRTLM-compatible tools so the model can invoke them natively via its function-calling capability.

#### Interfaces

```kotlin
// tool/Tool.kt
interface Tool {
    val descriptor: ToolDescriptor
    suspend fun execute(context: ToolContext, params: Map<String, Any?>): ToolResult
}

// tool/ToolDescriptor.kt
data class ToolDescriptor(
    val name: String,               // Unique identifier, e.g. "vector_search"
    val description: String,        // For the model's function-calling prompt
    val parameters: List<ToolParam> // Parameter schema
)

data class ToolParam(
    val name: String,
    val type: ParamType,            // STRING, INT, FLOAT, BOOLEAN, JSON
    val description: String,
    val required: Boolean = true,
    val default: Any? = null
)

enum class ParamType { STRING, INT, FLOAT, BOOLEAN, JSON }

// tool/ToolContext.kt
data class ToolContext(
    val conversationId: String,
    val apiKeyId: String,
    val modelId: String,
    val messageHistory: List<MessageEntity>  // Recent context
)

// tool/ToolResult.kt
sealed class ToolResult {
    data class Success(val data: Any, val metadata: Map<String, Any?> = emptyMap()) : ToolResult()
    data class Error(val message: String, val code: String? = null) : ToolResult()
}
```

#### Registry and Executor

```kotlin
// tool/ToolRegistry.kt
object ToolRegistry {

    private val tools = ConcurrentHashMap<String, Tool>()

    fun register(tool: Tool)
    fun unregister(name: String)
    fun get(name: String): Tool?
    fun list(): List<ToolDescriptor>

    // Convert a gateway Tool to a LiteRTLM-compatible tool for a conversation
    fun toLiteRTLMToolSet(toolNames: List<String>): ToolSet
}

// tool/ToolExecutor.kt
class ToolExecutor(
    private val registry: ToolRegistry,
    private val usageService: UsageService
) {
    // Called when LiteRTLM model emits a ToolCall
    suspend fun execute(toolCall: ToolCall, context: ToolContext): ToolResult {
        val tool = registry.get(toolCall.name)
            ?: return ToolResult.Error("Unknown tool: ${toolCall.name}")

        return try {
            val result = tool.execute(context, toolCall.parameters)
            usageService.recordToolExecution(context, toolCall.name, success = true)
            result
        } catch (e: Exception) {
            usageService.recordToolExecution(context, toolCall.name, success = false)
            ToolResult.Error(e.message ?: "Tool execution failed")
        }
    }
}
```

#### Conversation-Bound Tools

When creating a conversation, you specify which tools it can access:

```
POST /api/v1/conversations
{
    "name": "research-chat",
    "model": "gemma4-e4b",
    "tools": ["vector_search", "datetime"]
}
```

The `ConversationService` passes the requested tools to `ToolRegistry.toLiteRTLMToolSet()`, which creates a `ToolSet` that the LiteRTLM `Conversation` uses for automatic tool calling. During inference, when the model emits a tool call:

```
Model generates: <tool_call>vector_search(query="...")</tool_call>
    |
    v
LiteRTLM ToolManager intercepts (automaticToolCalling = true)
    |
    v
ToolExecutor.execute(toolCall, context)
    |
    v
VectorSearchTool.execute(context, params)
    |  (plugin-provided tool, queries external vector DB)
    v
ToolResult.Success(data = [relevant documents])
    |
    v
Result fed back to model as Content.ToolResponse
    |
    v
Model generates final answer incorporating search results
```

#### Built-in Tools

```kotlin
// tool/builtin/DateTimeOpenApiTool.kt
class DateTimeTool : Tool {
    override val descriptor = ToolDescriptor(
        name = "datetime",
        description = "Get current date, time, or timezone information",
        parameters = listOf(
            ToolParam("format", ParamType.STRING, "Date format pattern", required = false,
                      default = "yyyy-MM-dd HH:mm:ss")
        )
    )
    override suspend fun execute(context: ToolContext, params: Map<String, Any?>): ToolResult {
        val format = params["format"] as? String ?: "yyyy-MM-dd HH:mm:ss"
        return ToolResult.Success(LocalDateTime.now().format(DateTimeFormatter.ofPattern(format)))
    }
}

// tool/builtin/ConversationHistoryTool.kt
class ConversationHistoryTool(private val repository: ConversationRepository) : Tool {
    override val descriptor = ToolDescriptor(
        name = "conversation_history",
        description = "Retrieve past messages from the current conversation",
        parameters = listOf(
            ToolParam("count", ParamType.INT, "Number of recent messages", required = false, default = 10)
        )
    )
    // ...
}
```

---

### 3.5 Plugin Architecture (External Service Integration) `[PLANNED]`

#### Plugin Lifecycle

```
Discovery (ServiceLoader + config) -> instantiate -> init(context) -> active -> shutdown()
```

#### Interfaces

```kotlin
// plugin/Plugin.kt
interface Plugin : AutoCloseable {
    val id: String                  // Unique plugin identifier
    val name: String                // Display name
    val version: String

    // Called once after instantiation. Plugin should validate config and
    // register its tools, connectors, etc.
    fun init(context: PluginContext)

    // Health check — called periodically by admin dashboard
    fun healthCheck(): PluginHealth

    // Called on shutdown. Clean up connections, flush buffers, etc.
    override fun close()
}

data class PluginHealth(
    val healthy: Boolean,
    val message: String = "",
    val details: Map<String, Any?> = emptyMap()
)

// plugin/PluginContext.kt
class PluginContext(
    val pluginId: String,
    val config: Map<String, Any?>,   // Plugin-specific config from application config
    val toolRegistry: ToolRegistry,  // Register tools
    val logger: Logger               // Scoped logger
) {
    // Convenience: register a tool scoped to this plugin
    fun registerTool(tool: Tool) {
        toolRegistry.register(tool)
    }
}
```

#### Connector Interfaces

Connectors are specialized plugin types. A plugin may implement one or more:

```kotlin
// plugin/connector/VectorDatabaseConnector.kt
interface VectorDatabaseConnector {
    suspend fun search(query: String, collection: String,
                       topK: Int = 5): List<VectorSearchResult>
    suspend fun upsert(id: String, vector: FloatArray, metadata: Map<String, Any?>,
                       collection: String)
    fun listCollections(): List<String>
}

data class VectorSearchResult(
    val id: String,
    val score: Float,
    val content: String,
    val metadata: Map<String, Any?>
)

// plugin/connector/KnowledgeBaseConnector.kt
interface KnowledgeBaseConnector {
    suspend fun query(question: String, context: String? = null): List<KnowledgeEntry>
    suspend fun ingest(content: String, metadata: Map<String, Any?>): String // returns entry ID
}

data class KnowledgeEntry(
    val id: String,
    val content: String,
    val source: String?,
    val relevanceScore: Float
)

// plugin/connector/ServiceConnector.kt
// Generic escape hatch for any external service
interface ServiceConnector {
    val serviceType: String  // e.g. "email", "calendar", "webhook"
    suspend fun invoke(action: String, params: Map<String, Any?>): Map<String, Any?>
}
```

#### Plugin Registry

```kotlin
// plugin/PluginRegistry.kt
object PluginRegistry {

    private val plugins = ConcurrentHashMap<String, Plugin>()

    // Discover and load plugins. Called at startup.
    fun loadPlugins(configs: List<PluginConfig>) {
        // 1. Use ServiceLoader<Plugin> to discover plugins on classpath
        // 2. Match discovered plugins against configs (by plugin ID)
        // 3. For each matched config: instantiate, call init(context), store
        // 4. Log warnings for configured but missing plugins
    }

    fun getPlugin(id: String): Plugin?
    fun listPlugins(): List<PluginInfo>

    // Typed connector access
    inline fun <reified T> getConnector(pluginId: String): T?

    fun shutdownAll() {
        plugins.values.forEach { it.close() }
        plugins.clear()
    }
}

// plugin/PluginConfig.kt
data class PluginConfig(
    val id: String,
    val enabled: Boolean = true,
    val config: Map<String, Any?> = emptyMap()
)
```

#### Example: Vector Database Plugin

A plugin that wraps a vector database and exposes a search tool:

```kotlin
// (separate module or JAR, implements Plugin interface)
class ChromaDBPlugin : Plugin, VectorDatabaseConnector {
    override val id = "chromadb"
    override val name = "ChromaDB Vector Store"
    override val version = "1.0.0"

    private lateinit var client: ChromaClient

    override fun init(context: PluginContext) {
        val host = context.config["host"] as? String ?: "localhost"
        val port = context.config["port"] as? Int ?: 8000
        client = ChromaClient(host, port)

        // Register a tool that conversations can use
        context.registerTool(object : Tool {
            override val descriptor = ToolDescriptor(
                name = "vector_search",
                description = "Search the vector database for relevant documents",
                parameters = listOf(
                    ToolParam("query", ParamType.STRING, "Search query"),
                    ToolParam("collection", ParamType.STRING, "Collection name",
                              required = false, default = "default"),
                    ToolParam("top_k", ParamType.INT, "Number of results",
                              required = false, default = 5)
                )
            )
            override suspend fun execute(context: ToolContext,
                                         params: Map<String, Any?>): ToolResult {
                val results = search(
                    query = params["query"] as String,
                    collection = params["collection"] as? String ?: "default",
                    topK = params["top_k"] as? Int ?: 5
                )
                return ToolResult.Success(results)
            }
        })
    }

    override suspend fun search(query: String, collection: String,
                                topK: Int): List<VectorSearchResult> {
        return client.query(collection, query, topK).map { /* map to VectorSearchResult */ }
    }

    // ... other VectorDatabaseConnector methods ...

    override fun healthCheck() = PluginHealth(client.isConnected(), "ChromaDB connection")
    override fun close() { client.close() }
}
```

---

### 3.6 Rate Limiting & Usage Tracking `[PLANNED]`

#### Rate Limiter

In-memory token-bucket algorithm, keyed by API key ID:

```kotlin
// ratelimit/RateLimiter.kt
class RateLimiter {

    private val buckets = ConcurrentHashMap<String, TokenBucket>()

    // Check and consume. Returns true if allowed, false if rate-limited.
    fun tryAcquire(keyId: String, tier: String): Boolean

    // Get remaining quota info for headers
    fun getQuota(keyId: String, tier: String): QuotaInfo

    fun resetBucket(keyId: String)
}

data class QuotaInfo(
    val limit: Int,
    val remaining: Int,
    val resetsAt: Long          // epoch millis
)

// ratelimit/RateLimitConfig.kt
data class RateLimitConfig(
    val tiers: Map<String, TierConfig>
)

data class TierConfig(
    val requestsPerMinute: Int,
    val requestsPerHour: Int,
    val concurrentConversations: Int
)
```

Default tiers:

| Tier       | Requests/min | Requests/hour | Max Conversations |
|------------|-------------|---------------|-------------------|
| default    | 20          | 500           | 5                 |
| premium    | 60          | 2000          | 20                |
| unlimited  | -1          | -1            | -1                |

Rate limit info is returned in response headers:

```
X-RateLimit-Limit: 20
X-RateLimit-Remaining: 17
X-RateLimit-Reset: 1712678400
```

#### Usage Tracking

```kotlin
// usage/UsageRecord.kt
data class UsageRecord(
    val id: String,
    val apiKeyId: String,
    val conversationId: String?,
    val action: String,            // "message", "create_conversation", "tool_exec", "model_switch"
    val modelId: String?,
    val inputTokens: Int?,
    val outputTokens: Int?,
    val latencyMs: Long,
    val toolName: String?,         // If action = "tool_exec"
    val success: Boolean,
    val createdAt: Long
)

// usage/UsageService.kt
class UsageService(private val dao: DaoSqlite) {

    // Fire-and-forget recording (non-blocking)
    fun record(record: UsageRecord)

    // Query aggregations for dashboard
    fun getUsageByKey(apiKeyId: String, fromTime: Long, toTime: Long): UsageSummary
    fun getUsageByModel(modelId: String, fromTime: Long, toTime: Long): UsageSummary
    fun getGlobalUsage(fromTime: Long, toTime: Long): UsageSummary
    fun getRecentActivity(limit: Int = 100): List<UsageRecord>
}

data class UsageSummary(
    val totalRequests: Int,
    val totalInputTokens: Long,
    val totalOutputTokens: Long,
    val averageLatencyMs: Long,
    val errorCount: Int,
    val topModels: Map<String, Int>,
    val topTools: Map<String, Int>
)
```

Usage is recorded asynchronously — the `UsageService.record()` method dispatches to a background coroutine with a channel buffer to avoid blocking request handling.

---

### 3.7 Admin Dashboard `[PLANNED]`

#### SPA Architecture

The admin dashboard is a lightweight static SPA (HTML + vanilla JS or Alpine.js) served directly from Ktor's static resources:

```
src/main/resources/admin/
    index.html          # Dashboard shell
    app.js              # Dashboard logic
    style.css           # Styling
```

```kotlin
// api/route/RouteAdminDashboard.kt
fun Route.adminDashboard() {
    staticResources("/admin", "admin") {
        default("index.html")   // SPA fallback
    }
}
```

#### Admin API Endpoints

All under `/admin/api/*`, protected by `AdminAuthMiddleware`:

| Method | Path | Description |
|--------|------|-------------|
| GET    | /admin/api/status             | System status (uptime, memory, lm states) |
| GET    | /admin/api/models             | List all models with load status |
| POST   | /admin/api/models/{id}/load   | Load a model |
| POST   | /admin/api/models/{id}/unload | Unload a model |
| GET    | /admin/api/keys               | List API keys (masked) |
| POST   | /admin/api/keys               | Create new API key |
| DELETE | /admin/api/keys/{id}          | Revoke API key |
| GET    | /admin/api/usage              | Usage statistics (with time range query params) |
| GET    | /admin/api/usage/live         | SSE stream of real-time usage events |
| GET    | /admin/api/conversations      | List all active conversations across all keys |
| GET    | /admin/api/plugins            | List plugins with health status |
| GET    | /admin/api/tools              | List all registered tools |
| POST   | /admin/api/config             | Update runtime configuration |

---

### 3.8 Storage Layer `[PLANNED]`

#### Technology

The storage layer is built entirely on the two custom libraries already in `libs/`:

| Library | JAR | Role |
|---------|-----|------|
| `org.thingai.base.dao.Dao` | `applicationbase.jar` | Abstract DAO interface (CRUD, transactions, queries) |
| `org.thingai.platform.dao.DaoSqlite` | `desktopplatform.jar` | SQLite implementation backed by **HikariCP** connection pool |
| `org.thingai.platform.dao.DaoFile` | `desktopplatform.jar` | JSON file read/write for config snapshots and export |
| `org.thingai.base.dao.annotations.*` | `applicationbase.jar` | `@DaoTable` / `@DaoColumn` for reflective schema mapping |

Both JARs are already declared in `build.gradle.kts`:
```kotlin
implementation(files("libs/applicationbase.jar"))
implementation(files("libs/desktopplatform.jar"))
```

`DaoSqlite` requires HikariCP and the SQLite JDBC driver (both already present in `build.gradle.kts`):
```kotlin
implementation("org.xerial:sqlite-jdbc:3.43.2.0")
implementation("com.zaxxer:HikariCP:5.1.0")
```

No Exposed ORM dependency is needed.

#### Entity Annotations

All database entities are plain Java-compatible data classes annotated with `@DaoTable` and `@DaoColumn`. The `DaoSqlite` implementation uses reflection to derive table/column names and build DDL automatically via `initDao(Class[])`.

```kotlin
// storage/entity/ApiKeyRecord.kt
@DaoTable(name = "api_keys")
data class ApiKeyRecord(
    @DaoColumn(name = "id", primaryKey = true, nullable = false)
    val id: String = "",

    @DaoColumn(name = "key_hash", nullable = false, unique = true)
    val keyHash: String = "",

    @DaoColumn(name = "key_prefix", nullable = false)
    val keyPrefix: String = "",

    @DaoColumn(name = "name", nullable = false)
    val name: String = "",

    @DaoColumn(name = "permissions", nullable = false)
    val permissions: String = "",        // JSON: ["CHAT","CONVERSATION"]

    @DaoColumn(name = "rate_limit_tier", nullable = false, defaultValue = "default")
    val rateLimitTier: String = "default",

    @DaoColumn(name = "active", nullable = false, defaultValue = "1")
    val active: Int = 1,                 // 1 = active, 0 = revoked

    @DaoColumn(name = "created_at", nullable = false)
    val createdAt: Long = 0L,

    @DaoColumn(name = "last_used_at", nullable = true)
    val lastUsedAt: Long? = null
)

// storage/entity/ConversationRecord.kt
@DaoTable(name = "conversations")
data class ConversationRecord(
    @DaoColumn(name = "id", primaryKey = true, nullable = false)
    val id: String = "",

    @DaoColumn(name = "name", nullable = false)
    val name: String = "",

    @DaoColumn(name = "model_id", nullable = false)
    val modelId: String = "",

    @DaoColumn(name = "system_instruction", nullable = false)
    val systemInstruction: String = "",

    @DaoColumn(name = "sampler_config", nullable = false)
    val samplerConfig: String = "",       // JSON snapshot

    @DaoColumn(name = "api_key_id", nullable = false)
    val apiKeyId: String = "",

    @DaoColumn(name = "created_at", nullable = false)
    val createdAt: Long = 0L,

    @DaoColumn(name = "updated_at", nullable = false)
    val updatedAt: Long = 0L,

    @DaoColumn(name = "message_count", nullable = false, defaultValue = "0")
    val messageCount: Int = 0,

    @DaoColumn(name = "active", nullable = false, defaultValue = "1")
    val active: Int = 1
)

// storage/entity/MessageRecord.kt
@DaoTable(name = "messages")
data class MessageRecord(
    @DaoColumn(name = "id", primaryKey = true, nullable = false)
    val id: String = "",

    @DaoColumn(name = "conversation_id", nullable = false)
    val conversationId: String = "",

    @DaoColumn(name = "role", nullable = false)           // user | model | system | tool
    val role: String = "",

    @DaoColumn(name = "content", nullable = false)
    val content: String = "",

    @DaoColumn(name = "tool_call_id", nullable = true)
    val toolCallId: String? = null,

    @DaoColumn(name = "token_count", nullable = true)
    val tokenCount: Int? = null,

    @DaoColumn(name = "created_at", nullable = false)
    val createdAt: Long = 0L
)

// storage/entity/UsageLogRecord.kt
@DaoTable(name = "usage_logs")
data class UsageLogRecord(
    @DaoColumn(name = "id", primaryKey = true, nullable = false)
    val id: String = "",

    @DaoColumn(name = "api_key_id", nullable = false)
    val apiKeyId: String = "",

    @DaoColumn(name = "conversation_id", nullable = true)
    val conversationId: String? = null,

    @DaoColumn(name = "action", nullable = false)
    val action: String = "",              // message | create_conversation | tool_exec | model_switch

    @DaoColumn(name = "model_id", nullable = true)
    val modelId: String? = null,

    @DaoColumn(name = "input_tokens", nullable = true)
    val inputTokens: Int? = null,

    @DaoColumn(name = "output_tokens", nullable = true)
    val outputTokens: Int? = null,

    @DaoColumn(name = "latency_ms", nullable = false)
    val latencyMs: Long = 0L,

    @DaoColumn(name = "tool_name", nullable = true)
    val toolName: String? = null,

    @DaoColumn(name = "success", nullable = false, defaultValue = "1")
    val success: Int = 1,

    @DaoColumn(name = "created_at", nullable = false)
    val createdAt: Long = 0L
)

// storage/entity/ModelRegistryRecord.kt
@DaoTable(name = "model_registry")
data class ModelRegistryRecord(
    @DaoColumn(name = "id", primaryKey = true, nullable = false)
    val id: String = "",

    @DaoColumn(name = "name", nullable = false)
    val name: String = "",

    @DaoColumn(name = "path", nullable = false)
    val path: String = "",

    @DaoColumn(name = "backend", nullable = false, defaultValue = "CPU")
    val backend: String = "CPU",

    @DaoColumn(name = "cpu_threads", nullable = true)
    val cpuThreads: Int? = null,

    @DaoColumn(name = "default_sampler", nullable = false)
    val defaultSampler: String = "",      // JSON snapshot

    @DaoColumn(name = "max_conversations", nullable = false, defaultValue = "10")
    val maxConversations: Int = 10,

    @DaoColumn(name = "auto_load", nullable = false, defaultValue = "0")
    val autoLoad: Int = 0
)
```

#### Database Manager

```kotlin
// storage/DatabaseManager.kt
object DatabaseManager {

    private const val TAG = "DatabaseManager"
    private lateinit var dao: DaoSqlite

    /**
     * Opens (or creates) the SQLite database at [dbPath] and ensures all
     * tables exist. DaoSqlite uses HikariCP internally — no extra pool config needed.
     */
    fun init(dbPath: String = "data/gateway.db") {
        ILog.i(TAG, "Initializing database at $dbPath")
        dao = DaoSqlite(dbPath)
        dao.initDao(
            arrayOf(
                ApiKeyRecord::class.java,
                ConversationRecord::class.java,
                MessageRecord::class.java,
                UsageLogRecord::class.java,
                ModelRegistryRecord::class.java
            )
        )
        ILog.i(TAG, "Database ready")
    }

    fun close() = dao.close()

    /** Exposes the underlying Dao for repositories. */
    fun dao(): DaoSqlite = dao

    /**
     * Executes [block] in a single SQLite transaction.
     * Wraps Dao.executeTransaction to give callers a Kotlin-friendly API.
     */
    fun transaction(block: (Dao) -> Unit) {
        dao.executeTransaction { it.block(it) }
    }
}
```

#### Repository Pattern

Each domain module owns a repository that holds a reference to `DatabaseManager.dao()` and calls the `Dao` interface methods directly. There is no ORM query DSL — column/value filtering uses `Dao.query(Class, column, value)` or `Dao.query(Class, columns[], values[])`:

```kotlin
// conversation/ConversationRepository.kt
class ConversationRepository(private val dao: DaoSqlite) {

    fun create(record: ConversationRecord) =
        dao.insert(record)

    fun findById(id: String): ConversationRecord? =
        dao.query(ConversationRecord::class.java, "id", id).firstOrNull()

    fun findByApiKey(apiKeyId: String): List<ConversationRecord> =
        dao.query(ConversationRecord::class.java, "api_key_id", apiKeyId).toList()

    fun listActive(): List<ConversationRecord> =
        dao.query(ConversationRecord::class.java, "active", "1").toList()

    /** Update mutable metadata fields using insertOrUpdate (upsert on primary key). */
    fun updateMetadata(record: ConversationRecord) =
        dao.insertOrUpdate(record)

    fun softDelete(id: String) {
        val existing = findById(id) ?: return
        dao.insertOrUpdate(existing.copy(active = 0))
    }

    // ── Messages ────────────────────────────────────────────────────────────

    fun appendMessage(record: MessageRecord) =
        dao.insert(record)

    /** Returns the [limit] most-recent messages for a conversation. */
    fun getRecentMessages(conversationId: String, limit: Int = 50): List<MessageRecord> {
        // Raw SQL via queryRaw for ORDER BY + LIMIT support
        val sql = """
            SELECT * FROM messages
            WHERE conversation_id = '$conversationId'
            ORDER BY created_at DESC
            LIMIT $limit
        """.trimIndent()
        return dao.queryRaw(sql).map { row -> MessageRecord(
            id              = row["id"] as String,
            conversationId  = row["conversation_id"] as String,
            role            = row["role"] as String,
            content         = row["content"] as String,
            toolCallId      = row["tool_call_id"] as? String,
            tokenCount      = (row["token_count"] as? Number)?.toInt(),
            createdAt       = (row["created_at"] as Number).toLong()
        )}.reversed()   // return chronological order
    }

    fun getMessageCount(conversationId: String): Int {
        val sql = "SELECT COUNT(*) as cnt FROM messages WHERE conversation_id = '$conversationId'"
        return (dao.queryRaw(sql).firstOrNull()?.get("cnt") as? Number)?.toInt() ?: 0
    }
}
```

#### File Store

`DaoFile` handles JSON files on disk. It is used for:

- **Plugin config snapshots** — a plugin can persist its runtime state as a JSON file
- **Conversation export** — export a full conversation thread as a portable JSON file
- **Model registry backup** — write a snapshot of the model registry to a human-readable JSON file for manual editing

```kotlin
// storage/FileStore.kt
class FileStore(rootPath: String) {

    private const val TAG = "FileStore"
    private val file = DaoFile(rootPath)

    /** Read a JSON file relative to rootPath. Returns null if not found. */
    fun readJson(relativePath: String): String? = try {
        file.readJsonFile(relativePath)
    } catch (e: IOException) {
        ILog.w(TAG, "Could not read $relativePath: ${e.message}")
        null
    }

    /** Write a JSON string to a file relative to rootPath. */
    fun writeJson(relativePath: String, json: String) = try {
        file.writeJsonFile(relativePath, json)
    } catch (e: IOException) {
        ILog.e(TAG, "Could not write $relativePath: ${e.message}")
    }

    /** Export a conversation to data/exports/<id>.json */
    fun exportConversation(conversationId: String, json: String) =
        writeJson("exports/$conversationId.json", json)

    /** Persist a plugin's state snapshot */
    fun savePluginState(pluginId: String, json: String) =
        writeJson("plugins/$pluginId.state.json", json)

    fun loadPluginState(pluginId: String): String? =
        readJson("plugins/$pluginId.state.json")
}
```

`FileStore` is constructed once at startup and injected where needed (e.g. `PluginContext`, `AdminService`).

#### In-Memory Conversation Cache

Live `Conversation` objects (LiteRTLM JNI handles) are cached using `LRUCache<String, Conversation>` from `applicationbase.jar`. When the cache is full the least-recently-used conversation is evicted; its state remains in SQLite and will be cold-reconstructed on next access.

```kotlin
// lm/handler/ConversationHandler.kt  (updated)
class ConversationHandler(private val lm: Engine) {

    // LRUCache(maxSize, backingMap) — use ConcurrentHashMap as the backing store
    private val cache: LRUCache<String, Conversation> =
        LRUCache(MAX_LIVE_CONVERSATIONS, ConcurrentHashMap())

    companion object {
        const val MAX_LIVE_CONVERSATIONS = 20
    }

    fun get(id: String): Conversation? = cache.get(id)
    fun put(id: String, conv: Conversation) = cache.put(id, conv)
    fun evict(id: String): Conversation? = cache.remove(id)
    fun contains(id: String): Boolean = cache.containsKey(id)
    fun closeAll() {
        // LRUCache has no iterator, so evict tracked keys from a parallel set
        // (tracked by ConversationService)
    }
}
```

---

### 3.9 Logging `[PLANNED]`

#### Library

All logging uses `org.thingai.base.log.ILog` (abstract, from `applicationbase.jar`) with the concrete `org.thingai.platform.log.ILogImpl` (from `desktopplatform.jar`).

`ILog` exposes four **static** methods callable from anywhere without an instance reference:

```java
// org.thingai.base.log.ILog  (Java source)
ILog.d(tag, ...messages)   // DEBUG
ILog.i(tag, ...messages)   // INFO
ILog.w(tag, ...messages)   // WARN
ILog.e(tag, ...messages)   // ERROR
```

`ILogImpl` is the singleton implementation. It writes log entries asynchronously via a `BlockingQueue<String>` + a dedicated writer thread, with optional file output:

```kotlin
// Constructor: ILogImpl(logDirectory: String, enableFileLogging: Boolean)
// Static factories:
ILogImpl.initialize()                           // defaults: no file logging
ILogImpl.initialize(logDirectory, enableFileLogging)
```

#### Initialization (`log/AppLog.kt`)

```kotlin
// log/AppLog.kt
object AppLog {

    // Tag constants — avoids stringly-typed tag strings throughout the codebase
    const val TAG_MAIN          = "Main"
    const val TAG_ENGINE        = "EngineManager"
    const val TAG_CONVERSATION  = "ConversationService"
    const val TAG_AUTH          = "ApiKeyService"
    const val TAG_RATELIMIT     = "RateLimiter"
    const val TAG_USAGE         = "UsageService"
    const val TAG_TOOL          = "ToolExecutor"
    const val TAG_PLUGIN        = "PluginRegistry"
    const val TAG_STORAGE       = "DatabaseManager"
    const val TAG_ADMIN         = "AdminService"

    /**
     * Call once at server startup, before any other module initializes.
     * [logDir] is the directory for rotating log files (e.g. "data/logs").
     * [fileLogging] enables log-file output in addition to console.
     */
    fun init(logDir: String = "data/logs", fileLogging: Boolean = true) {
        ILogImpl.initialize(logDir, fileLogging)
        ILog.logLevel = ILog.INFO     // DEBUG | INFO | WARN | ERROR
        ILog.ENABLE_LOGGING = true
        ILog.i(TAG_MAIN, "Logging initialized. File logging=$fileLogging, dir=$logDir")
    }
}
```

#### Usage Pattern

Every class uses its own tag constant and the static `ILog.*` methods. No logger field injection required:

```kotlin
// Example in ConversationService
import org.thingai.app.aigateway.log.AppLog.TAG_CONVERSATION
import org.thingai.base.log.ILog

class ConversationService(...) {

    fun createConversation(name: String, modelId: String, ...): ConversationEntity {
        ILog.i(TAG_CONVERSATION, "Creating conversation: name=$name, model=$modelId")
        // ...
    }

    suspend fun sendMessage(conversationId: String, text: String): MessageEntity {
        ILog.d(TAG_CONVERSATION, "sendMessage: conv=$conversationId, len=${text.length}")
        try {
            // ...
        } catch (e: Exception) {
            ILog.e(TAG_CONVERSATION, "sendMessage failed: ${e.message}")
            throw e
        }
    }
}
```

#### Log File Behavior

`ILogImpl` auto-generates rotating log file names using `LOG_FILE_PREFIX` + date (`yyyy-MM-dd`) + `.log` extension. Files are written in the configured `logDirectory`. The internal queue prevents log calls from blocking inference threads.

| Config | Default | Description |
|--------|---------|-------------|
| `ILog.logLevel` | `INFO` | Minimum level written to output |
| `ILog.ENABLE_LOGGING` | `true` | Master on/off switch |
| `enableFileLogging` | `true` | Write to rotating file in `data/logs/` |
| `ILogImpl.setEnableFileLogging(bool)` | — | Toggle file logging at runtime (admin API) |

> **SLF4J note:** The existing `org.slf4j:slf4j-simple` dependency in `build.gradle.kts` can be removed once `ILogImpl` is wired up. Ktor's own internal logs (which use SLF4J) can be bridged with `slf4j-nop` or a minimal adapter if console noise is unwanted.

---

## 4. Request Flow Diagrams

### 4.1 Authenticated Message Send

```
Client
  |  POST /api/v1/conversations/abc123/messages
  |  Authorization: Bearer lrtlm_a3Bf9k...
  |  Content-Type: application/json
  |  { "text": "Explain quantum computing" }
  |
  v
AuthMiddleware
  |  Extract Bearer token
  |  ApiKeyService.validateKey("lrtlm_a3Bf9k...")
  |  -> ApiKey { permissions: [CHAT], tier: "default" }
  |  Check: CHAT in permissions? YES
  |  Store ApiKey in call.attributes
  |
  v
RateLimitMiddleware
  |  RateLimiter.tryAcquire(keyId, "default")
  |  -> true (17/20 remaining)
  |  Set X-RateLimit-* headers
  |
  v
RouteConversation (POST /{id}/messages)
  |  Parse JSON body
  |  Validate conversation belongs to this API key
  |
  v
ConversationService.sendMessage("abc123", "Explain quantum computing")
  |
  +-- Get EngineInstance for conversation's modelId
  |   EngineManager.getEngine("gemma4-e4b")
  |
  +-- Get or reconstruct live Conversation object
  |
  +-- Persist user message to SQLite
  |   MessageEntity(role="user", content="Explain quantum computing")
  |
  +-- conversation.sendMessage("Explain quantum computing")
  |   [LiteRTLM inference — blocking]
  |
  +-- Persist model response to SQLite
  |   MessageEntity(role="model", content="Quantum computing is...")
  |
  +-- UsageService.record(inputTokens, outputTokens, latency)
  |
  v
Response
  { "ok": true, "data": { "id": "msg_xyz", "role": "model",
    "content": "Quantum computing is...", "tokenCount": 142 } }
```

### 4.2 Tool-Calling Conversation

```
Client sends: "What documents do we have about neural networks?"
  |
  v
ConversationService.sendMessage(...)
  |
  v
conversation.sendMessage(text)
  [automaticToolCalling = true, tools = ["vector_search"]]
  |
  v
Model output: <tool_call>vector_search(query="neural networks")</tool_call>
  |
  v
LiteRTLM ToolManager intercepts
  |
  v
Gateway ToolExecutor.execute(toolCall, context)
  |
  v
ChromaDBPlugin's vector_search tool
  |  -> queries ChromaDB: collection="default", query="neural networks", topK=5
  |  -> returns: [{id:"doc1", score:0.92, content:"Neural networks are..."}]
  |
  v
Content.ToolResponse fed back to model
  |
  v
Model generates final response incorporating search results:
  "Based on your knowledge base, we have 5 documents about neural networks.
   The most relevant one covers..."
  |
  v
Both tool call and final response persisted to messages table
Response returned to client
```

### 4.3 Model Switch

```
Admin: POST /admin/api/models/gemma4-e2b/load
  |
  v
EngineManager.loadModel("gemma4-e2b")
  |  Look up ModelConfig from registry
  |  Create Engine(EngineConfig(path, Backend.CPU()))
  |  lm.initialize()       [heavy operation]
  |  Create ConversationHandler(lm)
  |  Store EngineInstance
  |
  v
Response: { "ok": true, "data": { "id": "gemma4-e2b", "loaded": true } }

---

Client: POST /api/v1/conversations
  { "name": "fast-chat", "model": "gemma4-e2b" }
  |
  v
ConversationService creates conversation bound to gemma4-e2b lm
```

---

## 5. Configuration

Application configuration uses Ktor's HOCON format (`application.conf`):

```hocon
# src/main/resources/application.conf

server {
    port = 8080
    host = "0.0.0.0"
}

admin {
    # Master admin token. If empty, admin endpoints are disabled.
    token = ${?ADMIN_TOKEN}
    token = "change-me-in-production"
}

storage {
    path = "data/gateway.db"
}

models {
    default = "gemma4-e4b"

    registry = [
        {
            id = "gemma4-e4b"
            name = "Gemma 4 E4B Instruct"
            path = "/data/model/gemma4-e4b/gemma-4-E4B-it.litertlm"
            backend = "CPU"
            cpuThreads = 4
            maxConversations = 10
            autoLoad = true
            defaultSampler {
                topK = 10
                topP = 0.95
                temperature = 0.8
            }
        },
        {
            id = "gemma4-e2b"
            name = "Gemma 4 E2B Instruct"
            path = "/data/model/gemma4-e2b/gemma-4-E2B-it.litertlm"
            backend = "CPU"
            cpuThreads = 2
            maxConversations = 20
            autoLoad = false
            defaultSampler {
                topK = 5
                topP = 0.9
                temperature = 0.7
            }
        }
    ]
}

rateLimits {
    tiers {
        default {
            requestsPerMinute = 20
            requestsPerHour = 500
            concurrentConversations = 5
        }
        premium {
            requestsPerMinute = 60
            requestsPerHour = 2000
            concurrentConversations = 20
        }
        unlimited {
            requestsPerMinute = -1
            requestsPerHour = -1
            concurrentConversations = -1
        }
    }
}

plugins = [
    {
        id = "chromadb"
        enabled = true
        config {
            host = "localhost"
            port = 8000
        }
    }
]

conversation {
    # Max messages replayed for cold-start reconstruction
    reconstructionWindow = 50
    # Idle timeout before evicting live conversation (minutes)
    idleEvictionMinutes = 30
}
```

---

## 6. API Response Envelope

All API responses use a unified JSON envelope:

```kotlin
// api/model/ApiResponse.kt
@Serializable
sealed class ApiResponse<T> {
    @Serializable
    data class Success<T>(
        val ok: Boolean = true,
        val data: T
    ) : ApiResponse<T>()

    @Serializable
    data class Error<T>(
        val ok: Boolean = false,
        val error: ErrorDetail
    ) : ApiResponse<T>()
}

@Serializable
data class ErrorDetail(
    val code: String,       // "NOT_FOUND", "RATE_LIMITED", "UNAUTHORIZED", etc.
    val message: String
)
```

Success example:
```json
{ "ok": true, "data": { "id": "conv_abc", "name": "my-chat", "model": "gemma4-e4b" } }
```

Error example:
```json
{ "ok": false, "error": { "code": "RATE_LIMITED", "message": "Rate limit exceeded. Retry after 42 seconds." } }
```

---

## 7. Security Considerations

| Concern | Mitigation |
|---------|------------|
| API key storage | SHA-256 hashed. Raw key shown once at creation. |
| Admin access | Separate token, configurable via env var (`ADMIN_TOKEN`). Disabled if unset. |
| Input validation | All route handlers validate parameters before processing. Max message length enforced. |
| Resource exhaustion | Rate limiting per key. Max conversations per key. Idle conversation eviction. |
| SQLite injection | `DaoSqlite` uses parameterized queries exclusively (via JDBC `PreparedStatement`). |
| Model path traversal | Model paths validated against allowed directory at registration time. |
| Plugin isolation | Plugins run in-process but receive a scoped `PluginContext` with controlled access. |

---

## 8. Dependencies

### Already in `build.gradle.kts` (no changes needed)

```kotlin
// Google AI Edge LiteRTLM
implementation("com.google.ai.edge.litertlm:litertlm-jvm:0.10.0")

// Ktor Server
implementation("io.ktor:ktor-server-core-jvm:3.4.2")
implementation("io.ktor:ktor-server-netty-jvm:3.4.2")

// Custom platform libraries (storage + logging)
implementation(files("libs/applicationbase.jar"))   // Dao, ILog, LRUCache, Service
implementation(files("libs/desktopplatform.jar"))   // DaoSqlite, DaoFile, ILogImpl

// SQLite JDBC + HikariCP (required by DaoSqlite)
implementation("org.xerial:sqlite-jdbc:3.43.2.0")
implementation("com.zaxxer:HikariCP:5.1.0")

// JSON serialization (already using Gson; switch to kotlinx if preferred)
implementation("com.google.code.gson:gson:2.13.2")
```

### New additions required

```kotlin
// build.gradle.kts — additions

plugins {
    kotlin("jvm") version "2.3.10"
    kotlin("plugin.serialization") version "2.3.10"   // ADD: for @Serializable DTOs
    id("io.ktor.plugin") version "3.4.2"
}

dependencies {
    // JSON serialization for API DTOs and config snapshots
    implementation("io.ktor:ktor-server-content-negotiation-jvm:3.4.2")
    implementation("io.ktor:ktor-serialization-kotlinx-json-jvm:3.4.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")

    // SSE for live dashboard events
    implementation("io.ktor:ktor-server-sse-jvm:3.4.2")

    // Static file serving for admin SPA
    implementation("io.ktor:ktor-server-static-resources-jvm:3.4.2")

    // Coroutines (transitive via Ktor, explicit for clarity)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")

    // Testing
    testImplementation("io.ktor:ktor-server-test-host-jvm:3.4.2")
    testImplementation("io.ktor:ktor-client-content-negotiation-jvm:3.4.2")
}
```

> **Note:** `org.slf4j:slf4j-simple` (currently in `build.gradle.kts`) can be replaced with `slf4j-nop` once `ILogImpl` is the primary logger, since Ktor's SLF4J log calls will be silenced in favour of `ILog`.

### Dependencies NOT needed (compared to a greenfield Kotlin project)

| Dependency | Reason not needed |
|------------|-------------------|
| `org.jetbrains.exposed:exposed-core` | `DaoSqlite` replaces Exposed ORM |
| `org.jetbrains.exposed:exposed-jdbc` | Same |
| `org.jetbrains.exposed:exposed-coroutines` | Same |
| Any structured-logging framework | `ILogImpl` handles logging |

---

## 9. Implementation Priority

| Phase | Scope | Rationale |
|-------|-------|-----------|
| **1** | Storage layer + DB schema + migrations | Foundation for everything else |
| **2** | API key management + auth middleware | Security gate before adding features |
| **3** | Conversation module refactor (persistence, JSON API) | Core functionality upgrade |
| **4** | Engine manager (multi-model) | Enables model flexibility |
| **5** | Tool framework + built-in tools | Extensibility foundation |
| **6** | Plugin architecture + connector interfaces | External integrations |
| **7** | Rate limiting + usage tracking | Production hardening |
| **8** | Admin dashboard (API + SPA) | Observability and management |

Each phase builds on the previous. Phases 1-3 deliver a production-usable gateway. Phases 4-6 deliver the extensibility platform. Phases 7-8 deliver operational maturity.
