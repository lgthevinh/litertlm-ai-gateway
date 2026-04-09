# LiteRTLM AI Gateway — Technical Architecture (AI Should not read this)

> Version 0.3 | April 2026
> Single-instance, on-device AI gateway wrapping Google AI Edge LiteRTLM
> Storage: DaoSqlite (desktopplatform) | Logging: ILogImpl (desktopplatform) | Cache: LRUCache (applicationbase)

---

## 1. System Overview

```
                           +--------------------------+
                           |      Client / Admin      |
                           +-----------+--------------+
                                       |
                                  HTTP :8080
                                       |
+--------------------------------------v---------------------------------------+
|                           Ktor Application                                   |
|                                                                              |
|  +------------------+  +-------------------+  +---------------------------+  |
|  | Auth Middleware   |  | Rate Limit Middle |  | Content Negotiation       |  |
|  | (API Key / Admin)|  | (Token Bucket)    |  | (JSON via kotlinx.serial) |  |
|  +--------+---------+  +--------+----------+  +-------------+-------------+  |
|           |                      |                           |                |
|  +--------v----------------------v---------------------------v------------+  |
|  |                         Route Layer                                    |  |
|  |  /api/v1/conversations/*   /api/v1/models/*   /api/v1/tools/*         |  |
|  |  /admin/*                  /admin/api/*        / (SPA static)         |  |
|  +--------+-----------------------------------------------------------+  |  |
|           |                                                              |  |
|  +--------v----------------------------------------------------------+   |  |
|  |                      Service Layer                                |   |  |
|  |  ConversationService  ModelService  ToolService  PluginService    |   |  |
|  |  ApiKeyService        UsageService  AdminService                  |   |  |
|  +--------+----------------------------------------------------------+   |  |
|           |                                                              |  |
|  +--------v----------------------------------------------------------+   |  |
|  |                      Engine Layer                                 |   |  |
|  |  EngineManager (multi-model)                                      |   |  |
|  |    +-> EngineInstance[gemma4-e2b] -> ConversationHandler          |   |  |
|  |    +-> EngineInstance[gemma4-e4b] -> ConversationHandler          |   |  |
|  |  ToolRegistry -> ToolExecutor -> LiteRTLM ToolManager             |   |  |
|  |  PluginRegistry -> loaded plugins                                 |   |  |
|  +--------+----------------------------------------------------------+   |  |
|           |                                                              |  |
|  +--------v----------------------------------------------------------+   |  |
|  |                      Storage Layer (SQLite)                       |   |  |
|  |  api_keys | conversations | messages | usage_logs | models_config |   |  |
|  +-------------------------------------------------------------------+   |  |
+--------------------------------------------------------------------------+  |
+---------------------------------------------------------------------------+
```

---

## 2. Package Structure

```
org.thingai.app.aigateway/
|
+-- Main.kt                              # Ktor server bootstrap
|
+-- api/
|   +-- middleware/
|   |   +-- AuthMiddleware.kt             # API key validation plugin
|   |   +-- RateLimitMiddleware.kt        # Per-key rate limiting plugin
|   |   +-- AdminAuthMiddleware.kt        # Admin session/token auth
|   |
|   +-- route/
|   |   +-- Route.kt                      # Route registration (existing, extended)
|   |   +-- RouteConfig.kt               # GET / health, GET /config/* (existing)
|   |   +-- RouteConversation.kt          # /api/v1/conversations/* (existing, refactored)
|   |   +-- RouteModel.kt                # /api/v1/models/*
|   |   +-- RouteTool.kt                 # /api/v1/tools/*
|   |   +-- RouteAdmin.kt                # /admin/api/*
|   |   +-- RouteAdminDashboard.kt        # /admin/* (SPA static files)
|   |
|   +-- model/                            # Request/response DTOs
|   |   +-- ApiResponse.kt               # Unified envelope { ok, data, error }
|   |   +-- ConversationDto.kt           # Conversation request/response models
|   |   +-- ModelDto.kt                  # Model info DTOs
|   |   +-- ToolDto.kt                   # Tool registration DTOs
|   |   +-- UsageDto.kt                  # Usage statistics DTOs
|   |   +-- AdminDto.kt                  # Admin endpoint DTOs
|
+-- auth/
|   +-- ApiKey.kt                         # API key entity
|   +-- ApiKeyService.kt                  # Key generation, validation, revocation
|   +-- Permission.kt                     # Permission enum (CHAT, ADMIN, MODEL_SWITCH, TOOL_EXEC)
|
+-- conversation/
|   +-- ConversationEntity.kt             # Persistent conversation metadata
|   +-- MessageEntity.kt                  # Persistent message record
|   +-- ConversationService.kt            # Orchestrates engine + storage
|   +-- ConversationRepository.kt         # SQLite CRUD for conversations/messages
|
+-- engine/
|   +-- LMEngine.kt                       # Refactored -> EngineManager delegate
|   +-- EngineManager.kt                  # Multi-model engine pool
|   +-- EngineInstance.kt                 # Single engine + its conversations
|   +-- define/
|   |   +-- PreDefineConversationConfig.kt  # (existing)
|   +-- entity/
|   |   +-- LMConversation.kt            # (existing, will be used)
|   +-- handler/
|       +-- ConversationHandler.kt        # (existing, scoped per engine)
|       +-- ToolHandler.kt               # (existing stub -> implemented)
|
+-- tool/
|   +-- Tool.kt                           # Gateway tool interface
|   +-- ToolDescriptor.kt                # Name, description, parameter schema
|   +-- ToolContext.kt                    # Execution context (conversation, user, etc.)
|   +-- ToolResult.kt                    # Execution result wrapper
|   +-- ToolRegistry.kt                  # Register/unregister/lookup tools
|   +-- ToolExecutor.kt                  # Dispatch execution, bridge to LiteRTLM ToolManager
|   +-- builtin/                          # Built-in tools
|       +-- DateTimeTool.kt
|       +-- ConversationHistoryTool.kt
|
+-- plugin/
|   +-- Plugin.kt                         # Plugin lifecycle interface
|   +-- PluginContext.kt                  # What plugins can access
|   +-- PluginRegistry.kt                # Discovery, load, shutdown
|   +-- PluginConfig.kt                  # Per-plugin configuration
|   +-- connector/                        # Connector type interfaces
|       +-- VectorDatabaseConnector.kt
|       +-- KnowledgeBaseConnector.kt
|       +-- ServiceConnector.kt
|
+-- ratelimit/
|   +-- RateLimiter.kt                    # Token bucket implementation
|   +-- RateLimitConfig.kt               # Limits per tier
|
+-- usage/
|   +-- UsageRecord.kt                    # Single usage event entity
|   +-- UsageService.kt                  # Record and query usage
|   +-- UsageRepository.kt               # SQLite persistence
|
+-- storage/
|   +-- DatabaseManager.kt                # DaoSqlite lifecycle, schema init, migrations
|   +-- FileStore.kt                      # DaoFile wrapper for config/state JSON files
|   +-- entity/                           # @DaoTable / @DaoColumn annotated POJOs
|       +-- ApiKeyEntity.kt
|       +-- ConversationRecord.kt
|       +-- MessageRecord.kt
|       +-- UsageLogRecord.kt
|       +-- ModelRegistryRecord.kt
|
+-- log/
|   +-- AppLog.kt                         # ILogImpl singleton initializer + tag constants
|
+-- config/
|   +-- AppConfig.kt                      # Typed application config
|   +-- ModelConfig.kt                    # Per-model config entries
|
+-- callback/
|   +-- RequestCallback.kt               # (existing)
|
+-- admin/
    +-- AdminService.kt                   # System status, metrics aggregation
    +-- DashboardResources.kt             # SPA static file serving config
```

---

## 3. Core Module Specifications

### 3.1 Authentication & API Key Management

#### Entities

```kotlin
// auth/ApiKey.kt
data class ApiKey(
    val id: String,              // UUID
    val keyHash: String,         // SHA-256 hash of the raw key
    val keyPrefix: String,       // First 8 chars for identification (e.g. "lrtlm_ab")
    val name: String,            // Human-readable label
    val permissions: Set<Permission>,
    val rateLimitTier: String,   // "default", "premium", "unlimited"
    val active: Boolean,
    val createdAt: Long,         // epoch millis
    val lastUsedAt: Long?
)

// auth/Permission.kt
enum class Permission {
    CHAT,           // Send messages to conversations
    CONVERSATION,   // Create/delete conversations
    MODEL_SWITCH,   // Change active model for a conversation
    TOOL_EXEC,      // Execute tools
    ADMIN           // Access admin endpoints
}
```

#### Service Interface

```kotlin
// auth/ApiKeyService.kt
class ApiKeyService(private val dao: DaoSqlite) : org.thingai.base.Service() {

    init {
        name = "ApiKeyService"
        version = "1.0"
    }

    override fun onServiceInit() {
        ILog.i(name, "ApiKeyService ready")
    }

    // Returns the raw key (only time it's visible). Stores hashed.
    fun createKey(name: String, permissions: Set<Permission>,
                  rateLimitTier: String = "default"): Pair<ApiKey, String>

    fun validateKey(rawKey: String): ApiKey?   // null = invalid/inactive

    fun revokeKey(id: String): Boolean

    fun listKeys(): List<ApiKey>               // Never exposes hash

    fun updateLastUsed(id: String)
}
```

#### Key Format

Raw keys follow the pattern: `lrtlm_` + 48 random alphanumeric characters.
Example: `lrtlm_a3Bf9kLm2xPq7Rv1Wy4Zn8Cd6Eh0Gj5Il3Ko9Mt2Nu`

Only the SHA-256 hash is stored. The `keyPrefix` field stores the first 8 characters (`lrtlm_a3`) for display/identification in the admin dashboard without exposing the full key.

#### Auth Middleware

```kotlin
// api/middleware/AuthMiddleware.kt
// Installed as a Ktor plugin on /api/v1/* routes

val ApiKeyAuth = createRouteScopedPlugin("ApiKeyAuth") {
    on(AuthenticationChecked) {
        // Extract key from: Authorization: Bearer lrtlm_xxx
        // OR query param: ?api_key=lrtlm_xxx
        // Validate via ApiKeyService.validateKey()
        // Store validated ApiKey in call.attributes for downstream use
        // Reject with 401 if missing, 403 if insufficient permissions
    }
}
```

#### Admin Authentication

Admin endpoints use a separate mechanism — a master admin token defined in the application config (`admin.token`). This is intentionally simple: the gateway runs on-device, admin access is local.

```kotlin
// api/middleware/AdminAuthMiddleware.kt
// Checks: Authorization: Bearer <admin-token>
// Configured in application.conf: admin.token = "..."
// If not configured, admin endpoints are disabled (safe default)
```

---

### 3.2 Conversation Management (Enhanced)

#### Entities

```kotlin
// conversation/ConversationEntity.kt
data class ConversationEntity(
    val id: String,                  // UUID
    val name: String,                // User-provided name
    val modelId: String,             // Which model this conversation uses
    val systemInstruction: String,
    val samplerConfig: SamplerConfigData, // Stored config snapshot
    val apiKeyId: String,            // Owner key
    val createdAt: Long,
    val updatedAt: Long,
    val messageCount: Int,
    val active: Boolean              // false = soft-deleted / closed
)

// conversation/MessageEntity.kt
data class MessageEntity(
    val id: String,                  // UUID
    val conversationId: String,
    val role: String,                // "user", "model", "system", "tool"
    val content: String,             // Text content
    val toolCallId: String?,         // If this is a tool response
    val tokenCount: Int?,            // Estimated tokens (for usage tracking)
    val createdAt: Long
)

data class SamplerConfigData(
    val topK: Int = 10,
    val topP: Double = 0.95,
    val temperature: Double = 0.8
)
```

#### Reconciling In-Memory and Persistent State

LiteRTLM `Conversation` objects are in-memory and hold native JNI handles. They cannot be serialized. The architecture uses a **write-through cache** pattern:

```
Client sends message
    |
    v
ConversationService.sendMessage(conversationId, text)
    |
    +-- 1. Look up active LiteRTLM Conversation from EngineInstance
    |       (if not loaded, reconstruct from stored history — see below)
    |
    +-- 2. conversation.sendMessage(text)  [blocking, runs inference]
    |
    +-- 3. Persist user message + model response to SQLite (async)
    |
    +-- 4. Update conversation metadata (updatedAt, messageCount)
    |
    +-- Return response to client
```

**Cold-start reconstruction:** When a conversation exists in SQLite but has no live `Conversation` object (e.g. after server restart), the service:
1. Creates a new LiteRTLM `Conversation` with the stored `ConversationConfig`
2. Replays the stored message history as `initialMessages` in the config
3. Caches the live object for subsequent use

**Eviction:** When memory is constrained, idle conversations (no message for N minutes) can have their live `Conversation` object closed. The persistent state in SQLite allows reconstruction on next access.

#### History Compaction

For long conversations, storing every message is expensive for reconstruction. The compaction strategy:

- **Full history** is always stored in SQLite (never deleted)
- **Reconstruction window**: Only the last N messages (configurable, default 50) are replayed as `initialMessages` when cold-starting
- **Summary checkpoints**: Optionally, every M messages (e.g. 100), the model generates a summary of the conversation so far. This summary becomes the starting context for reconstruction, followed by the recent window.

#### Repository

```kotlin
// conversation/ConversationRepository.kt
class ConversationRepository(private val dao: DaoSqlite) {

    fun create(entity: ConversationEntity)
    fun findById(id: String): ConversationEntity?
    fun findByApiKey(apiKeyId: String): List<ConversationEntity>
    fun listActive(): List<ConversationEntity>
    fun updateMetadata(id: String, updatedAt: Long, messageCount: Int)
    fun softDelete(id: String)

    // Messages
    fun appendMessage(message: MessageEntity)
    fun getMessages(conversationId: String, limit: Int = 50,
                    offset: Int = 0): List<MessageEntity>
    fun getMessageCount(conversationId: String): Int
    fun getRecentMessages(conversationId: String, count: Int): List<MessageEntity>
}
```

#### Service

```kotlin
// conversation/ConversationService.kt
class ConversationService(
    private val engineManager: EngineManager,
    private val repository: ConversationRepository,
    private val usageService: UsageService,
    private val toolExecutor: ToolExecutor
) {
    // Creates persistent record + live LiteRTLM conversation
    fun createConversation(
        name: String, modelId: String, apiKeyId: String,
        systemInstruction: String, samplerConfig: SamplerConfigData,
        tools: List<String> = emptyList()  // Tool names to bind
    ): ConversationEntity

    // Sends message, persists both sides, tracks usage
    suspend fun sendMessage(conversationId: String, text: String): MessageEntity

    // Streaming variant — returns Flow of partial responses
    suspend fun sendMessageStream(conversationId: String, text: String):
        Flow<String>

    fun getConversation(id: String): ConversationEntity?
    fun listConversations(apiKeyId: String): List<ConversationEntity>
    fun getHistory(conversationId: String, limit: Int, offset: Int):
        List<MessageEntity>

    fun closeConversation(id: String)

    // Bind/unbind tools to a specific conversation
    fun bindTool(conversationId: String, toolName: String)
    fun unbindTool(conversationId: String, toolName: String)
}
```

---

### 3.3 Engine & Model Management

#### Multi-Model Strategy

Since LiteRTLM binds one model per `Engine`, supporting multiple models requires multiple `Engine` instances. The `EngineManager` maintains a pool:

```
EngineManager
  |
  +-- engines: Map<String, EngineInstance>
  |     "gemma4-e2b" -> EngineInstance(engine, handler, modelConfig)
  |     "gemma4-e4b" -> EngineInstance(engine, handler, modelConfig)
  |
  +-- modelRegistry: Map<String, ModelConfig>
        (all known models, loaded or not)
```

#### Entities

```kotlin
// config/ModelConfig.kt
data class ModelConfig(
    val id: String,              // "gemma4-e4b"
    val name: String,            // "Gemma 4 E4B Instruct"
    val path: String,            // Filesystem path to .litertlm file
    val backend: BackendType,    // CPU, GPU, GPU_ARTISAN, NPU
    val cpuThreads: Int?,        // For CPU backend
    val defaultSampler: SamplerConfigData,
    val maxConversations: Int,   // Max concurrent conversations on this engine
    val autoLoad: Boolean        // Load on startup?
)

enum class BackendType { CPU, GPU, GPU_ARTISAN, NPU }
```

#### EngineInstance

```kotlin
// engine/EngineInstance.kt
class EngineInstance(
    val modelConfig: ModelConfig,
    private val engine: Engine,
    val conversationHandler: ConversationHandler
) : AutoCloseable {

    val isReady: Boolean          // Engine initialized and healthy
    val activeConversations: Int  // Current count

    override fun close() {
        conversationHandler.closeAll()
        engine.close()
    }
}
```

#### EngineManager

```kotlin
// engine/EngineManager.kt
object EngineManager {

    private val engines = ConcurrentHashMap<String, EngineInstance>()
    private val modelRegistry = ConcurrentHashMap<String, ModelConfig>()

    // Register a model in the registry (does not load it)
    fun registerModel(config: ModelConfig)

    // Load a model — creates Engine, initializes, creates EngineInstance
    suspend fun loadModel(modelId: String): EngineInstance

    // Unload a model — closes all conversations, closes engine
    suspend fun unloadModel(modelId: String)

    // Get a loaded engine instance
    fun getEngine(modelId: String): EngineInstance?

    // Get the default engine (first loaded, or configured default)
    fun getDefaultEngine(): EngineInstance?

    // List all registered models with their load status
    fun listModels(): List<ModelStatus>

    // Reload a model (unload + load) — for config changes
    suspend fun reloadModel(modelId: String)
}

data class ModelStatus(
    val config: ModelConfig,
    val loaded: Boolean,
    val activeConversations: Int,
    val memoryEstimate: Long?   // bytes, if available
)
```

#### Dynamic Loading Flow

```
Admin calls POST /admin/api/models/gemma4-e2b/load
    |
    v
EngineManager.loadModel("gemma4-e2b")
    |
    +-- Look up ModelConfig from registry
    +-- Create EngineConfig(path, backend)
    +-- engine = Engine(engineConfig)
    +-- engine.initialize()          // Heavy — blocks on model load
    +-- handler = ConversationHandler(engine)
    +-- Store EngineInstance in engines map
    +-- Return success
```

**Conversation-to-model binding:** Each conversation is bound to a specific model at creation time (`ConversationEntity.modelId`). The conversation routes through the corresponding `EngineInstance`. Switching a conversation's model requires closing the live `Conversation` and reconstructing it on the new engine.

---

### 3.4 Tool Execution Framework

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
// tool/builtin/DateTimeTool.kt
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

### 3.5 Plugin Architecture (External Service Integration)

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

### 3.6 Rate Limiting & Usage Tracking

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

### 3.7 Admin Dashboard

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
| GET    | /admin/api/status             | System status (uptime, memory, engine states) |
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

### 3.8 Storage Layer

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
// engine/handler/ConversationHandler.kt  (updated)
class ConversationHandler(private val engine: Engine) {

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

### 3.9 Logging

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
  |  engine.initialize()       [heavy operation]
  |  Create ConversationHandler(engine)
  |  Store EngineInstance
  |
  v
Response: { "ok": true, "data": { "id": "gemma4-e2b", "loaded": true } }

---

Client: POST /api/v1/conversations
  { "name": "fast-chat", "model": "gemma4-e2b" }
  |
  v
ConversationService creates conversation bound to gemma4-e2b engine
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
