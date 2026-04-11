# Conversation Architecture

This document covers how the gateway manages concurrent conversations, the engine pool design, conversation context and history, and the full inference flows for WebSocket and REST clients.

---

## Overview

The gateway supports multiple simultaneous conversations. Each conversation has its own config, message history, and inference state. Inference is **decoupled from the transport layer** — an inference job runs to completion regardless of whether the WebSocket client stays connected.

```
┌─────────────────────────────────────────────────────────────────────┐
│                         Client layer                                │
│      WebSocket (/ws/conversations/{name})                           │
│      REST POST (/api/conversations/{name}/messages)                 │
└───────────────────────────┬─────────────────────────────────────────┘
                            │
                            ▼
┌─────────────────────────────────────────────────────────────────────┐
│                     ConversationHandler                             │
│  - Guards (conversation exists, not BUSY)                           │
│  - Registers job in ConversationJobRegistry                         │
│  - Launches detached inference coroutine                            │
│  - Emits ConversationWsChunk.Busy to caller                         │
└──────────┬────────────────────────────┬────────────────────────────-┘
           │                            │
           ▼                            ▼
┌──────────────────────┐    ┌───────────────────────────┐
│   ConversationJob    │    │       EngineHandler        │
│   Registry           │    │                            │
│                      │    │  taskChannel (unbounded)   │
│  IDLE → BUSY → DONE  │    │  ┌──────────┐             │
│  watchdog timer      │    │  │ worker 0 │ → Engine 0  │
│  eviction TTL        │    │  │ worker 1 │ → Engine 1  │
│  CompletableDeferred │    │  └──────────┘             │
└──────────────────────┘    └───────────────────────────┘
```

---

## Engine Handler

### Design

`EngineHandler` manages a fixed pool of `Engine` instances and a shared task queue.

```
ENGINE_COUNT = 2

taskChannel (Channel.UNLIMITED)
    ├── worker #0 ──owns──► Engine #0
    └── worker #1 ──owns──► Engine #1
```

- Each worker **exclusively owns** one engine — no sharing, no locking
- The task channel is **unbounded** — submissions never block the caller; backpressure is natural (task waits in queue until a worker picks it up)
- Workers loop on `taskChannel` forever until it is closed (on `LMService.stop()`)

### ConversationTask

```kotlin
data class ConversationTask(
    val config: ConversationConfig,   // full config rebuilt from DB each turn
    val message: String,              // new user message
    val reply: Channel<ConversationWsChunk> = Channel(Channel.UNLIMITED)
)
```

The `reply` channel is the pipe between the engine worker and the `ConversationJobRegistry`. The worker sends `Token` frames as inference progresses, then a final `Done` or `Error`.

### Inference per task

```
worker picks up ConversationTask
    → engine.createConversation(config)    // native Conversation object, ephemeral
    → conversation.sendMessageAsync(msg)   // LiteRTLM native thread
        → Token frames → task.reply channel → ConversationJobRegistry.onToken()
    → ConversationWsChunk.Done sent
    → conversation.close()                 // engine resources released immediately
    → task.reply.close()
```

**Key point:** The native `Conversation` object is created fresh for every message turn, using the stored history as `initialMessages`. It is closed as soon as the turn completes. No engine state persists between turns — the engine is stateless from the gateway's perspective.

### Concurrency limits

With 2 engines, the gateway processes **2 inference tasks simultaneously**. Additional tasks queue in `taskChannel` and are picked up as engines free up. There is no per-conversation concurrency limit at the engine level — the per-conversation BUSY guard in `ConversationJobRegistry` enforces one active turn per conversation.

---

## Conversation Context

### How history is stored

Every completed turn persists two rows in `lm_messages`:

| Column | Value |
|--------|-------|
| `conversationName` | Foreign key to `lm_conversations.name` |
| `role` | `"user"` or `"model"` |
| `text` | Full message text |
| `seq` | Monotonically increasing within the conversation |
| `createdAt` | Unix epoch ms |

### How history is replayed

At the start of every turn, `MessageHandler.buildConfig()` reconstructs the full `ConversationConfig` from DB:

```
1. Load LMStoredConversation → system instruction, preset, sampler config, tool names
2. Load last HISTORY_LIMIT (40) messages → sorted by seq ascending
3. Map to SDK Message objects (user / model roles)
4. Build ConversationConfig(
       systemInstruction = ...,
       samplerConfig     = SamplerConfig(topK, topP, temperature),
       tools             = ToolRegistry.getToolProviders(toolNames),
       initialMessages   = Contents(messages)
   )
```

The engine re-opens a fresh `Conversation` with this config on every turn. This means:
- History is always DB-authoritative — no in-memory conversation object survives between turns
- History is capped at 40 messages to bound token consumption and inference latency
- The 40-message window slides — oldest messages drop off as new ones are added

---

## ConversationJobRegistry

The registry tracks one `ConversationJob` per conversation that has an active inference turn.

### ConversationState

```
IDLE  — not in registry (default)
BUSY  — inference running; reply accumulating in replyBuffer
```

There is no DONE state. The job is removed from the registry immediately inside `onDone()`
before the deferred is resolved. Any awaiting handler receives the reply directly from
`deferred.await()` — no separate consume step needed.

### ConversationJob fields

```kotlin
data class ConversationJob(
    val convName: String,
    val replyBuffer: StringBuilder,          // accumulates tokens during BUSY
    val completionDeferred: CompletableDeferred<String>,  // resolved with reply on completion
    var watchdogJob: Job?,                   // cancelled/reset on each token
    var state: ConversationState,
)
```

### State transitions

```
startJob(name)          IDLE → BUSY    creates job, starts 300s watchdog
onToken(name, token)    BUSY           appends to replyBuffer, resets watchdog
onDone(name)            BUSY → IDLE    removes job from registry, resolves deferred with reply
onError(name, reason)   BUSY → IDLE    removes job from registry, completes deferred exceptionally
```

### Timers

| Timer | Duration | Starts | Resets | Action on fire |
|-------|----------|--------|--------|----------------|
| Watchdog | 300 s | `startJob` | Every `onToken` | `onError` — kills hung inference |

A reconnecting client after inference has already completed finds no job in the registry
(IDLE) and loads history directly from DB via the messages endpoint.

---

## Inference Flows

### Flow 1: Client sends message, stays connected

```
Client ──WS message──► RouteWebSocket
                            │
                            ▼
                       ConversationHandler.sendMessage()
                            │ state = IDLE
                            ├─ startJob(name)        → BUSY
                            ├─ launch detached coroutine
                            │       └─ EngineHandler.submit(task).collect { ... }
                            │              onToken() → replyBuffer
                            │              onDone()  → persist DB → remove job → resolve deferred
                            └─ emit ConversationWsChunk.Busy
                            │
                            ▼
                       WS handler receives Busy
                            ├─ send { "type": "busy" } to client
                            └─ busyJob.completionDeferred.await()   ← suspends here
                                                │
                                    inference completes
                                                │
                                                ▼
                                       deferred resolved with reply text
                                                │
                                                ▼
                                       send { "type": "done", "reply": "..." }
```

### Flow 2: Client disconnects mid-inference, reconnects after

```
Client ──WS message──► inference starts → BUSY
Client disconnects     WS coroutine cancelled
                       detached inference coroutine keeps running  ← key
                            │
                            ▼
                       inference completes
                            ├─ persist to DB
                            └─ job removed from registry → IDLE
                               (deferred resolved, but no one is awaiting it)

Client reconnects ──► RouteWebSocket
                            │
                            ▼
                       ConversationJobRegistry.isBusy(name) = false  (job already gone)
                            │
                            ▼
                       Falls through to message loop
                       Client reads history via GET /api/conversations/{name}/messages
```

### Flow 3: Client reconnects while inference still running

```
Client reconnects ──► RouteWebSocket
                            │
                            ▼
                       ConversationJobRegistry.isBusy(name) = true
                            ├─ send { "type": "busy" } to client
                            └─ getBusyJob(name).completionDeferred.await()  ← suspends
                                                │
                                    inference completes
                                                │
                                                ▼
                                       deferred resolved with reply text
                                       send { "type": "done", "reply": "..." }
```

### Flow 4: Client sends message while BUSY

```
Client ──WS message──► ConversationHandler.sendMessage()
                            │ state = BUSY
                            └─ emit ConversationWsChunk.Error("conversation is busy")
                            │
                            ▼
                       WS handler sends { "type": "error", "error": "..." }
```

### Flow 5: REST POST /messages (blocking)

```
Client ──POST──► RouteConversation.post("/{name}/messages")
                            │
                            ▼
                       ConversationHandler.sendMessage()
                            ├─ startJob → BUSY
                            └─ emit ConversationWsChunk.Busy
                            │
                            ▼
                       REST handler receives Busy
                            └─ busyJob.completionDeferred.await()  ← HTTP request suspends
                                                │
                                    inference completes
                                                │
                                                ▼
                                       deferred resolved with reply text
                                       respond { "ok": true, "reply": "..." }
```

### Flow 6: Watchdog fires (hung inference)

```
BUSY job — 300 s with no token received
                            │
                            ▼
                       watchdog coroutine fires
                            └─ onError(name, "Inference timed out")
                                    ├─ job removed from registry → IDLE
                                    └─ completionDeferred.completeExceptionally(...)

WS/REST handler awaiting deferred
                            └─ runCatching { ... }.getOrNull() returns null
                            └─ sendError("Inference failed while waiting for result")
```

---

## Package Layout

```
lm/
├── LMService.kt                     # Lifecycle: start, stop, register tools
├── conversation/
│   ├── ConversationJob.kt           # Job data: state, replyBuffer, deferred, watchdog
│   ├── ConversationJobRegistry.kt   # State machine + watchdog timer (singleton)
│   ├── ConversationState.kt         # enum: IDLE, BUSY
│   └── ConversationWsChunk.kt       # sealed: Token, Done, Error, Busy
├── handler/
│   ├── ConversationHandler.kt       # Orchestration: create, update, delete, sendMessage
│   ├── EngineHandler.kt             # Engine pool + task queue + workers
│   └── MessageHandler.kt           # DB persistence: config, history, buildConfig()
├── entity/
│   ├── LMStoredConversation.kt      # DB row: config, sampler, tools
│   └── LMStoredMessage.kt          # DB row: role, text, seq, createdAt
├── builtin/
│   └── BuiltinConversationConfig.kt # Preset definitions: ASSISTANT, CODER, CONCISE, CREATIVE
└── tool/
    ├── GatewayTool.kt
    ├── ToolRegistry.kt
    └── builtin/                     # DateTimeTool, CalculatorTool
```
