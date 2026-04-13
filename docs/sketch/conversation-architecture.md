# Conversation Architecture

This document covers how the gateway manages concurrent conversations, the engine design, conversation context and history, multimodal attachment handling, and the full inference flows for WebSocket and REST clients.

---

## Overview

The gateway supports multiple simultaneous conversations. Each conversation has its own config, message history, and inference state. Inference is **decoupled from the transport layer** — an inference job runs to completion regardless of whether the WebSocket client stays connected.

```
+---------------------------------------------------------------------+
|                         Client layer                                 |
|      WebSocket (/ws/conversations/{name})                            |
|      REST POST (/api/conversations/{name}/messages)                  |
+----------------------------+----------------------------------------+
                             |
                             v
+---------------------------------------------------------------------+
|                     ConversationHandler                              |
|  - Guards (conversation exists, not BUSY)                            |
|  - Builds Contents (text + optional ImageBytes/AudioBytes)           |
|  - Saves attachment bytes to disk (AttachmentStore)                  |
|  - Registers job in ConversationJobRegistry                          |
|  - Launches detached inference coroutine                             |
|  - Emits Queued (if engine busy) then Busy to caller                 |
+-----------+-------------------------+-------------------------------+
            |                         |
            v                         v
+----------------------+    +-----------------------------------+
|   ConversationJob    |    |         EngineHandler              |
|   Registry           |    |                                    |
|                      |    |  taskChannel (unbounded)           |
|  IDLE -> BUSY        |    |  queueNames (ConcurrentLinkedDeque)|
|  watchdog timer      |    |  single worker -> Engine           |
|  CompletableDeferred |    |  (dedicated thread)                |
|  tokenFlow (shared)  |    |                                    |
+----------------------+    +-----------------------------------+
```

---

## Engine Handler

### Design

`EngineHandler` owns a single `Engine` instance running on a dedicated thread. All engine operations (initialize, createConversation, sendMessageAsync, close) run on the same thread to satisfy LiteRTLM native thread-affinity requirements.

```
engine-thread  (single dedicated thread, daemon)
    +-- Engine init
    +-- worker loop: for (task in taskChannel) { processTask(engine, task) }
```

- Tasks are serialized — one at a time — via the unbounded `taskChannel`
- A `ConcurrentLinkedDeque<String>` tracks conversation names in submission order for queue visibility
- If inference is running, new tasks queue and wait their turn

### ConversationTask

```kotlin
data class ConversationTask(
    val convName: String,                           // conversation name (for queue visibility)
    val config: ConversationConfig,                 // full config rebuilt from DB each turn
    val contents: Contents,                         // multimodal user message (text + optional media)
    val userText: String,                           // plain text portion (for DB persistence)
    val attachmentRefs: List<AttachmentRef>,        // saved attachment filenames (for DB persistence)
    val reply: Channel<ConversationWsChunk> = Channel(Channel.UNLIMITED)
)
```

`contents` is built by `ConversationHandler` from `Contents.of(Content.Text(message), Content.ImageBytes(...), ...)`.
For text-only messages, `contents = Contents.of(message)` and `attachmentRefs` is empty.

The `reply` channel is the pipe between the engine worker and the `ConversationJobRegistry`. The worker sends `Token` frames as inference progresses, then a final `Done` or `Error`.

### Inference per task

```
worker picks up ConversationTask
    -> engine.createConversation(config)    // native Conversation object, ephemeral
    -> conversation.sendMessageAsync(task.contents)  // multimodal Contents
        -> Token frames -> task.reply channel -> ConversationJobRegistry.onToken()
    -> ConversationWsChunk.Done sent
    -> conversation.close()                 // engine resources released immediately
    -> task.reply.close()
    -> queueNames.pollFirst()               // remove from visible queue
```

**Key point:** The native `Conversation` object is created fresh for every message turn, using the stored history as `initialMessages`. It is closed as soon as the turn completes. No engine state persists between turns — the engine is stateless from the gateway's perspective.

### Queue visibility

```kotlin
class EngineHandler {
    private val queueNames = ConcurrentLinkedDeque<String>()

    fun getQueueSize(): Int = queueNames.size

    fun getQueueEntries(): List<QueueEntry>
    // Returns: [{ name, position (1-based), status ("processing"|"waiting") }]
}
```

Exposed via `GET /api/queue` — the UI Queue page polls this every 2 seconds.

### Thread safety

- `Engine` is only ever accessed from `engine-thread` — no cross-thread calls
- `taskChannel` is a coroutine `Channel` — thread-safe producer/consumer
- `queueNames` is `ConcurrentLinkedDeque` — thread-safe append/remove

---

## Multimodal Attachment Handling

### IncomingAttachment

Raw bytes arrive from the transport layer and are passed to `ConversationHandler`:

```kotlin
data class IncomingAttachment(
    val type: AttachmentType,   // IMAGE or AUDIO
    val bytes: ByteArray,
    val ext: String             // file extension without dot: "jpg", "png", "wav", ...
)
```

### AttachmentStore

`AttachmentStore` manages attachment files on disk:

```
{appDir}/attachments/{conversationName}/{seq}_{index}.{ext}

e.g.  lm_application/attachments/my-chat/4_0.jpg
      lm_application/attachments/my-chat/4_1.wav
```

```kotlin
class AttachmentStore(appDir: File) {
    fun save(convName, seq, index, ext, bytes): String    // returns filename
    fun absolutePath(convName, filename): String          // for Content.ImageFile / AudioFile
    fun deleteConversation(convName)                      // called on conversation delete
}
```

### How ConversationHandler builds Contents

```kotlin
// On sendMessage() with attachments:
val contentParts = mutableListOf<Content>(Content.Text(message))
val attachmentRefs = mutableListOf<AttachmentRef>()

val seq = messageHandler.nextSeq(name)
attachments.forEachIndexed { index, att ->
    // For current inference turn: use in-memory bytes
    when (att.type) {
        IMAGE -> contentParts += Content.ImageBytes(att.bytes)
        AUDIO -> contentParts += Content.AudioBytes(att.bytes)
    }
    // Save to disk for API serving; NOT for history replay
    val filename = attachmentStore.save(name, seq, index, att.ext, att.bytes)
    attachmentRefs += AttachmentRef(att.type, filename)
}

val contents = Contents.of(contentParts)
```

### DB persistence

After inference completes (Done), `MessageHandler.appendMessages` persists:

```
lm_messages row (role=user):
    text        = "What's in this image?"
    attachments = "image:4_0.jpg,audio:4_1.wav"   ← nullable column

lm_messages row (role=model):
    text        = "The image shows a cat..."
    attachments = null                              ← model replies are text-only
```

### History replay — text-only by design

Attachments are **intentionally not** re-injected when rebuilding history for subsequent turns. `MessageHandler.toMessage()` always returns a text-only `Message` regardless of the stored `attachments` column:

```kotlin
// Always text-only — no Content.ImageFile / AudioFile in history
private fun LMStoredMessage.toMessage(...): Message = when (role) {
    "model" -> Message.model(text)
    else    -> Message.user(text)
}
```

**Rationale:** Loading large binary files on every conversation re-open is expensive and the model has already processed the image in the turn it was sent. Only the text context is needed for continuity.

---

## Conversation Context

### How history is stored

Every completed turn persists two rows in `lm_messages`:

| Column | Value |
|--------|-------|
| `conversationName` | Foreign key to `lm_conversations.name` |
| `role` | `"user"` or `"model"` |
| `text` | Full message text |
| `attachments` | Comma-separated `type:filename` refs, or `null` |
| `seq` | Monotonically increasing within the conversation |
| `createdAt` | Unix epoch ms |

### How history is replayed

At the start of every turn, `MessageHandler.buildConfig()` reconstructs the full `ConversationConfig` from DB:

```
1. Load LMStoredConversation -> system instruction, preset, sampler config, tool names
2. Load last HISTORY_LIMIT (20) messages -> sorted by seq ascending
3. Map to SDK Message objects (text-only — attachments not replayed)
4. Build ConversationConfig(
       systemInstruction = ...,
       samplerConfig     = SamplerConfig(topK, topP, temperature),
       tools             = ToolRegistry.getToolProviders(toolNames),
       initialMessages   = history
   )
```

The engine re-opens a fresh `Conversation` with this config on every turn. This means:
- History is always DB-authoritative — no in-memory conversation object survives between turns
- History is capped at 20 messages to bound token consumption and inference latency
- The 20-message window slides — oldest messages drop off as new ones are added
- Stateless conversations skip history load and persistence entirely

---

## ConversationJobRegistry

The registry tracks one `ConversationJob` per conversation that has an active inference turn.

### ConversationState

```
IDLE  -- not in registry (default)
BUSY  -- inference running; reply accumulating in replyBuffer
```

There is no DONE state. The job is removed from the registry immediately inside `onDone()`
before the deferred is resolved. Any awaiting handler receives the reply directly from
`deferred.await()` — no separate consume step needed.

### ConversationJob fields

```kotlin
data class ConversationJob(
    val convName: String,
    val replyBuffer: StringBuilder,                // accumulates tokens during BUSY
    val completionDeferred: CompletableDeferred<String>,  // resolved with reply on completion
    val tokenFlow: MutableSharedFlow<String>,       // hot flow for live token streaming to WS
    var watchdogJob: Job?,                          // cancelled/reset on each token
    var state: ConversationState,
)
```

### tokenFlow

`MutableSharedFlow<String>` with `extraBufferCapacity = 256` and `BufferOverflow.DROP_OLDEST`.

- `onToken()` calls `tokenFlow.tryEmit(token)` — non-blocking, thread-safe
- WS handler collects in a child `launch` coroutine — does not block the incoming frame loop
- No close semantics needed — collection is terminated by `cancelAndJoin()` when the deferred resolves
- If no WS client is connected, emissions are dropped silently (no subscribers)

### State transitions

```
startJob(name)          IDLE -> BUSY    creates job, starts 300s watchdog
onToken(name, token)    BUSY            appends to replyBuffer, emits to tokenFlow, resets watchdog
onDone(name)            BUSY -> IDLE    removes job from registry, resolves deferred with reply
onError(name, reason)   BUSY -> IDLE    removes job from registry, completes deferred exceptionally
```

### Timers

| Timer | Duration | Starts | Resets | Action on fire |
|-------|----------|--------|--------|----------------|
| Watchdog | 300 s | `startJob` | Every `onToken` | `onError` -- kills hung inference |

A reconnecting client after inference has already completed finds no job in the registry
(IDLE) and loads history directly from DB via the messages endpoint.

---

## WebSocket Protocol

### Client → Server frame

```json
{ "message": "Hello" }
```

With multimodal attachments:
```json
{
  "message": "What is in this image?",
  "images": ["data:image/png;base64,iVBOR...", "data:image/jpeg;base64,..."],
  "audio":  ["data:audio/wav;base64,UklGR..."]
}
```

- `images` and `audio` are lists of base64-encoded strings
- Data-URI prefix (`data:image/png;base64,`) is supported and stripped automatically
- Plain base64 without a data-URI prefix defaults to extension `bin`

### Server → Client frames

```
{ "type": "queued",  "position": 2 }             -- task is queued (engine busy)
{ "type": "busy" }                                -- inference is running
{ "type": "token",  "token": "partial text" }     -- streamed token (live)
{ "type": "done",   "reply": "full reply text" }  -- turn complete (authoritative)
{ "type": "error",  "error": "message" }          -- recoverable error
```

### Token streaming architecture

```
engine-thread
    processTask() -> task.reply.send(Token)
                          |
                          v
inferenceScope (Dispatchers.Default, detached)
    engineHandler.submit(task).collect {
        Token -> onToken() -> replyBuffer.append + tokenFlow.tryEmit
        Done  -> persist to DB (userText + attachmentRefs) -> completionDeferred.complete(reply)
    }
              |                              |
              | completionDeferred           | tokenFlow
              v                              v
WS coroutine (Ktor IO)
    launch {                        <-- child coroutine, non-blocking
      tokenFlow
        .takeWhile { !deferred.isCompleted }
        .collect { token -> send WsTokenFrame }
    } <- tokenJob

    val reply = deferred.await()    <-- suspends, incoming loop stays free
    tokenJob.cancelAndJoin()        <-- stop collection
    send WsDoneFrame(reply)         <-- authoritative full reply
```

---

## Inference Flows

### Flow 1: Client sends message, engine is free, stays connected

```
Client --WS message--> RouteWebSocket
                            |
                            v
                       ConversationHandler.sendMessage()
                            | state = IDLE
                            |-- build Contents (text [+ image/audio bytes])
                            |-- save attachments to disk (AttachmentStore)
                            |-- startJob(name)        -> BUSY
                            |-- launch detached coroutine
                            |       +-- EngineHandler.submit(task).collect { ... }
                            |              onToken() -> replyBuffer + tokenFlow
                            |              onDone()  -> persist DB -> remove job -> resolve deferred
                            |-- emit ConversationWsChunk.Busy
                            |
                            v
                       WS handler receives Busy
                            |-- send { "type": "busy" } to client
                            |-- launch { tokenFlow.collect -> send token frames }
                            +-- deferred.await()   <-- suspends here
                                                |
                                    inference completes
                                                |
                                                v
                                       tokenJob.cancelAndJoin()
                                       send { "type": "done", "reply": "..." }
```

### Flow 2: Client sends message, engine is busy (queued)

```
Client --WS message--> ConversationHandler.sendMessage()
                            |
                            |-- build Contents + save attachments
                            |-- startJob(name) -> BUSY
                            |-- launch detached coroutine (task queued in taskChannel)
                            |-- engineHandler.getQueueSize() > 0
                            |-- emit ConversationWsChunk.Queued(position)
                            +-- emit ConversationWsChunk.Busy
                            |
                            v
                       WS handler receives Queued then Busy
                            |-- send { "type": "queued", "position": 2 }
                            |-- send { "type": "busy" }
                            |-- launch { tokenFlow.collect }
                            +-- deferred.await()
                                    |
                                    ... task waits in queue ...
                                    ... engine finishes previous task, picks up this one ...
                                    ... inference runs, tokens emitted ...
                                    |
                                    v
                               tokenJob.cancelAndJoin()
                               send { "type": "done", "reply": "..." }
```

### Flow 3: Client disconnects mid-inference, reconnects after

```
Client --WS message--> inference starts -> BUSY
Client disconnects     WS coroutine cancelled
                       detached inference coroutine keeps running  <-- key
                            |
                            v
                       inference completes
                            |-- persist to DB (text + attachmentRefs)
                            +-- job removed from registry -> IDLE
                               (deferred resolved, but no one is awaiting it)

Client reconnects --> RouteWebSocket
                            |
                            v
                       ConversationJobRegistry.isBusy(name) = false  (job already gone)
                            |
                            v
                       Falls through to message loop
                       Client reads history via GET /api/conversations/{name}/messages
```

### Flow 4: Client reconnects while inference still running

```
Client reconnects --> RouteWebSocket
                            |
                            v
                       ConversationJobRegistry.isBusy(name) = true
                            |-- send { "type": "busy" } to client
                            |-- launch { tokenFlow.collect }  <-- partial tokens only
                            +-- deferred.await()              <-- suspends
                                                |
                                    inference completes
                                                |
                                                v
                                       tokenJob.cancelAndJoin()
                                       send { "type": "done", "reply": "..." }

Note: tokens emitted before reconnection are missed (SharedFlow replay=0).
The done frame always carries the complete reply.
```

### Flow 5: Client sends message while BUSY

```
Client --WS message--> ConversationHandler.sendMessage()
                            | state = BUSY
                            +-- emit ConversationWsChunk.Error("conversation is busy")
                            |
                            v
                       WS handler sends { "type": "error", "error": "..." }
```

### Flow 6: REST POST /messages (blocking)

```
Client --POST multipart--> RouteConversation.post("/{name}/messages")
                            |
                            v
                       ConversationHandler.sendMessage(message, attachments)
                            |-- build Contents + save attachments
                            |-- startJob -> BUSY
                            +-- emit ConversationWsChunk.Busy
                            |
                            v
                       REST handler receives Busy
                            +-- busyJob.completionDeferred.await()  <-- HTTP request suspends
                                                |
                                    inference completes
                                                |
                                                v
                                       deferred resolved with reply text
                                       respond { "ok": true, "reply": "..." }
```

### Flow 7: Watchdog fires (hung inference)

```
BUSY job -- 300 s with no token received
                            |
                            v
                       watchdog coroutine fires
                            +-- onError(name, "Inference timed out")
                                    |-- job removed from registry -> IDLE
                                    +-- completionDeferred.completeExceptionally(...)

WS/REST handler awaiting deferred
                            +-- runCatching { ... }.getOrNull() returns null
                            +-- sendError("Inference failed while waiting for result")
```

---

## Package Layout

```
lm/
|-- LMService.kt                     # Lifecycle: setDao, setAppDir, start, stop, register tools
|-- conversation/
|   |-- ConversationJob.kt           # Job data: state, replyBuffer, deferred, tokenFlow, watchdog
|   |-- ConversationJobRegistry.kt   # State machine + watchdog timer (singleton)
|   +-- ConversationWsChunk.kt       # sealed: Token, Done, Error, Queued, Busy
|-- attachment/
|   |-- AttachmentRef.kt             # AttachmentType enum, AttachmentRef data class
|   |                                # toColumnValue() / toAttachmentRefs() helpers
|   +-- AttachmentStore.kt           # File I/O: save, absolutePath, deleteConversation
|-- handler/
|   |-- ConversationHandler.kt       # Orchestration: create, update, delete, sendMessage
|   |                                # IncomingAttachment data class lives here
|   |-- EngineHandler.kt             # Single engine, dedicated thread, task queue, QueueEntry
|   |                                # ConversationTask: contents: Contents (not String)
|   +-- MessageHandler.kt            # DB persistence: config, history, buildConfig()
|                                    # Injected with AttachmentStore
|-- entity/
|   |-- LMStoredConversation.kt      # DB row: config, sampler, tools, stateless
|   +-- LMStoredMessage.kt           # DB row: role, text, attachments (nullable), seq, createdAt
|-- builtin/
|   +-- BuiltinConversationConfig.kt # Preset definitions: ASSISTANT, CODER, CONCISE, CREATIVE
+-- tool/
    |-- GatewayOpenApiTool.kt
    |-- ToolRegistry.kt
    +-- builtin/                     # DateTimeTool, CalculatorTool
```
