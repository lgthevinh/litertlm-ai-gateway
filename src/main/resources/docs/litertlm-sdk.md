# LiteRTLM SDK

Research notes on the Google AI Edge LiteRTLM JVM SDK (v0.10.0). All notes reflect current understanding from decompiled stubs and limited testing — not confirmed production behaviour.

---

## Engine

`Engine` is the top-level entry point for the LiteRTLM runtime. Must be initialised before any `Conversation` can be created.

```kotlin
val engine = Engine(engineConfig)
engine.initialize()
```

- Initialisation is blocking and CPU-heavy — run on `Dispatchers.Default`
- Only **one `Engine` instance** should be alive at a time; call `engine.close()` before re-initialising
- The engine holds the loaded model weights in memory

---

## Conversation

`Conversation` is the **stateful** mode — the engine maintains a full turn history across multiple calls, behaving like a chat session.

```kotlin
val conv = engine.createConversation(conversationConfig)
conv.sendMessage("Hello")                        // blocking
conv.sendMessageAsync("Hello").collect { ... }   // streaming Flow<Message>
conv.close()
```

- Each `Conversation` holds its own context window (turn history)
- **Known limitation:** Only **one conversation per engine** appears safe. Creating multiple conversations on the same engine leads to undefined/shared state
- `ConversationConfig` accepts `systemInstruction`, `initialMessages` (history replay), `SamplerConfig` (topK, topP, temperature), and `tools`
- `sendMessageAsync` returns a `Flow<Message>` — each emission is a partial token chunk
- Must call `close()` when done to free native resources

---

## Session (Unusable)

`Session` is the stateless one-shot mode. **Currently non-functional** — `generateContent` and `generateContentStream` do not produce output, hang indefinitely, or throw native exceptions.

All inference goes through `Conversation`. Session-related routes have been removed.

---

## ConversationConfig

```kotlin
ConversationConfig(
    systemInstruction    = Contents(...),          // optional system prompt
    initialMessages      = listOf(...),            // history replay
    samplerConfig        = SamplerConfig(
        topK             = 40,
        topP             = 0.9,
        temperature      = 1.0
    ),
    tools                = listOf(toolProvider),   // optional tool providers
    automaticToolCalling = true                    // SDK handles tool call/response loop
)
```

---

## Tool Integration

When `automaticToolCalling = true`, the entire tool call/response loop happens **inside the SDK** before any token reaches the streaming layer. From the gateway's perspective, inference streams out normally — tools are completely transparent to `EngineHandler`.

```
model emits tool call
    → SDK calls ToolManager.execute(name, params)
    → GatewayToolProvider dispatches to GatewayTool.execute()
    → result fed back to model
    → model continues generating final answer
    → tokens stream out normally
```

---

## Open Questions

| # | Question | Status |
|---|----------|--------|
| 1 | Can multiple `Conversation` objects be active on one `Engine`? | ❓ Appears **no** |
| 2 | Is concurrent inference (two messages in parallel) safe? | ❓ Assumed unsafe |
| 3 | What does `Session` require to work? | ❓ Unknown |
| 4 | `RECURRING_TOOL_CALL_LIMIT` — SDK hard limit on recursive tool calls | ❓ Value unknown |
| 5 | Does `execute()` returning plain `String` serialize correctly for the model? | ❓ Needs test |
