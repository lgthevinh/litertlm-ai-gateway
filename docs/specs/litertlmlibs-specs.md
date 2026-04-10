# LiteRTLM Library — Research Notes

> **Status:** Early research / investigation phase.
> All notes below reflect current understanding from decompiled stubs and limited testing.
> Nothing here is confirmed production behaviour.

---

## Engine

The `Engine` class is the top-level entry point for the LiteRTLM runtime.
It must be initialised before any `Conversation` or `Session` can be created.

```kotlin
val lm = Engine(engineConfig)
lm.initialize()
```

- Initialisation is blocking and CPU-heavy — run on `Dispatchers.Default`.
- Only **one `Engine` instance** should be alive at a time; call `lm.close()` before re-initialising.
- The lm holds the loaded model weights in memory.

**Current limitation:** It is unclear whether the lm supports true concurrent inference.
All calls currently assume single-threaded model access.

---

## Conversation

`Conversation` is the **stateful** mode — the lm maintains a full turn history across
multiple `sendMessage` / `sendMessageAsync` calls, behaving like a chat session.

```kotlin
val conv = lm.createConversation(conversationConfig)
conv.sendMessage("Hello")                          // blocking
conv.sendMessageAsync("Hello").collect { ... }     // streaming Flow<Message>
conv.close()
```

- Each `Conversation` object holds its own context window (turn history).
- **Known limitation:** Only **one conversation per lm** appears to be supported at a time.
  Creating multiple conversations backed by the same lm leads to undefined/shared state.
  This is an active research area — multi-session support may depend on lm-level isolation.
- `ConversationConfig` accepts a `systemInstruction` (`Contents`) and a `SamplerConfig`
  (topK, topP, temperature).
- `sendMessageAsync` returns a `Flow<Message>` — each emission is a partial token chunk.
  The flow completes naturally when the model finishes generating.
- Must call `close()` when done to free native resources.

**Usage in this gateway:** `ConversationHandler` wraps lm conversations, keyed by name.
The REST and WebSocket routes in `RouteConversation.kt` / `RouteWebSocket.kt` expose them.

---

## Session

`Session` is the **stateless** mode — each `generateContent` / `generateContentStream`
call is an independent one-shot inference with no shared history.

```kotlin
val session = lm.createSession(sessionConfig)
session.generateContent(inputs)                                 // blocking
session.generateContentStream(inputs, responseCallback)         // streaming via callback
session.close()
```

- `SessionConfig` only holds a `SamplerConfig` — no system instruction field was found in the stub.
- `InputData` is a sealed class with three variants: `Text(text)`, `Image(bytes)`, `Audio(bytes)`.
- `ResponseCallback` interface: `onNext(String)`, `onError(Throwable)`, `onDone()`.

### ⚠ Currently Unusable

**The `Session` API is not functional in the current build.**
Testing shows that `generateContent` and `generateContentStream` do not produce output,
hang indefinitely, or throw native exceptions.

Possible reasons (unconfirmed):
- Session mode may require a different model variant or a model file compiled with session support enabled.
- The `SessionConfig` may require additional fields not exposed in the public stub.
- Session and Conversation modes may share the same underlying context and cannot be used interchangeably without lm re-initialisation.

**Action:** Session-related routes and UI have been removed until the underlying behaviour
is understood. All inference currently goes through `Conversation`.

---

## Open Questions

| # | Question | Status |
|---|----------|--------|
| 1 | Can multiple `Conversation` objects be active on one `Engine`? | ❓ Unconfirmed — appears to be **no** |
| 2 | What does `Session` actually do differently from `Conversation`? | ❓ Unknown |
| 3 | Does `Session` require a special model file or config flag? | ❓ Unknown |
| 4 | Is concurrent inference (two messages in parallel) safe? | ❓ Unknown — assumed unsafe |
| 5 | What is the `InputData.Audio` / `InputData.Image` pipeline for sessions? | ❓ Unknown |
