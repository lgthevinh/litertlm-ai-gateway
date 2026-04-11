# API Manual

Complete reference for all HTTP and WebSocket endpoints exposed by the LiteRTLM AI Gateway. Base URL: `http://<host>:8080`

---

## Common Response Format

All REST responses are JSON. Successful responses include `"ok": true`. Errors include `"ok": false` and an `"error"` string.

```json
{ "ok": false, "error": "Conversation 'chat1' not found" }
```

---

## Authentication

Pass credentials in the `Authorization` header:

```
Authorization: Bearer <accessToken|apiKey>
```

| Credential | Format | Accepted on |
|------------|--------|-------------|
| JWT access token | `eyJ...` | All protected routes |
| API key | `lrtlm_...` | Conversation + WebSocket routes only |

WebSocket auth uses a query parameter instead of a header (see WebSocket section).

---

## Auth Routes

### `POST /api/auth/login`

Authenticate and receive tokens. No auth required.

**Request:** `{ "username": "admin", "password": "secret" }`
**Response 200:** `{ "ok": true, "accessToken": "eyJ...", "refreshToken": "eyJ..." }`
**Errors:** `400 "Fields required: username, password"` · `401 "Invalid credentials"`

---

### `POST /api/auth/refresh`

Rotate tokens. Old refresh token is invalidated immediately.

**Request:** `{ "refreshToken": "eyJ..." }`
**Response 200:** `{ "ok": true, "accessToken": "eyJ...", "refreshToken": "eyJ..." }`
**Errors:** `400 "Field required: refreshToken"` · `401 "Invalid or expired refresh token"`

---

### `POST /api/auth/logout`

Revoke the active refresh token. Access tokens expire naturally (15 min TTL).

**Request:** `{ "refreshToken": "eyJ..." }`
**Response 200:** `{ "ok": true }`
**Errors:** `400 "Invalid or already revoked token"`

---

## Conversation Routes

All routes require `Authorization: Bearer <accessToken|apiKey>`.

### `GET /api/conversations`

List all conversation names.

**Response 200:** `{ "ok": true, "conversations": ["chat1", "research"] }`

---

### `POST /api/conversations`

Create a new conversation.

**Request**
```json
{
  "name": "my-chat",
  "config": "assistant",
  "tools": ["datetime", "calculator"]
}
```

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `name` | string | ✅ | Unique conversation identifier |
| `config` | string | ❌ | Preset: `assistant` (default), `coder`, `concise`, `creative` |
| `systemInstruction` | string | ❌ | Custom system prompt. Overrides `config` when provided |
| `tools` | string[] | ❌ | Tool names to bind, e.g. `["datetime", "litertlm-docs"]` |

**Response 201**
```json
{ "ok": true, "name": "my-chat", "config": "assistant" }
```

**Errors**
| Status | Error |
|--------|-------|
| 400 | `"Missing field: name"` |
| 409 | `"Conversation 'my-chat' already exists"` |
| 503 | `"Engine not ready"` |

---

### `GET /api/conversations/{name}/messages`

Fetch full message history for a conversation, ordered oldest-first.

**Response 200**
```json
{
  "ok": true,
  "messages": [
    { "role": "user",  "text": "Hello",       "seq": 0, "createdAt": 1712800000000 },
    { "role": "model", "text": "Hi there!",   "seq": 1, "createdAt": 1712800001000 }
  ]
}
```

**Errors**
| Status | Error |
|--------|-------|
| 404 | `"Conversation 'name' not found"` |
| 503 | `"Engine not ready"` |

---

### `POST /api/conversations/{name}/messages`

Send a message and receive the full reply (blocking). Collects all streaming tokens internally.

**Request**
```json
{ "message": "What is the capital of France?" }
```

**Response 200**
```json
{ "ok": true, "reply": "The capital of France is Paris." }
```

**Errors**
| Status | Error |
|--------|-------|
| 400 | `"Missing field: message"` |
| 404 | `"Conversation 'name' not found"` |
| 503 | `"Engine not ready"` |

---

### `DELETE /api/conversations/{name}`

Delete a conversation and all its message history.

**Response 200:** `{ "ok": true }`
**Errors:** `404 "Conversation 'name' not found"` · `503 "Engine not ready"`

---

## WebSocket — Streaming Inference

### `WS /ws/conversations/{name}?token=<accessToken|apiKey>`

Persistent streaming connection for a conversation. Auth is passed as a query parameter since WebSocket handshakes cannot carry custom headers in most clients.

```
ws://localhost:8080/ws/conversations/my-chat?token=eyJ...
ws://localhost:8080/ws/conversations/my-chat?token=lrtlm_...
```

**Client → Server** (text frame, JSON):
```json
{ "message": "Tell me about Kotlin coroutines" }
```

**Server → Client** (text frames, JSON):
```json
{ "type": "token", "token": "Kotlin" }
{ "type": "token", "token": " coroutines" }
{ "type": "done" }
```

On error:
```json
{ "type": "error", "error": "Conversation 'my-chat' not found" }
```

**Protocol notes:**
- The connection stays open for the full conversation lifetime
- Send the next message after receiving `{ "type": "done" }`
- If both engine workers are busy, the message queues and waits — the connection does not close
- If the token is invalid the server closes with code `1008 (VIOLATED_POLICY)`

---

## API Key Routes

All routes require `Authorization: Bearer <accessToken>` (JWT only — API keys not accepted here).

### `POST /api/api-key/generate`

Generate a new API key. The raw key is returned **once only** — not stored in plaintext.

**Request** (optional): `{ "name": "my-client" }`
**Response 201:** `{ "ok": true, "key": "lrtlm_a3Bx...48chars", "id": "uuid", "prefix": "lrtlm_a3", "name": "my-client" }`

---

### `GET /api/api-key/list`

List all API keys. Raw keys are never exposed.

**Response 200** — `{ "ok": true, "keys": [ { "id", "prefix", "name", "active", "createdAt", "lastUsedAt" }, ... ] }`

---

### `GET /api/api-key/info?key=lrtlm_...`

Get metadata for a specific API key.

**Response 200** — `{ "ok": true, "key": { "id", "prefix", "name", "active", "createdAt", "lastUsedAt" } }`

**Errors:** `400 "Missing query param: key"` · `404 "Key not found"`

---

### `DELETE /api/api-key/revoke`

Soft-revoke an API key. The key record remains in the DB but becomes inactive.

**Request**
```json
{ "key": "lrtlm_a3Bx..." }
```

**Response 200**
```json
{ "ok": true }
```

**Errors**
| Status | Error |
|--------|-------|
| 400 | `"Missing field: key"` |
| 404 | `"Key not found or already revoked"` |

---

## Tools Route

### `GET /api/tools`

List all tools registered in `ToolRegistry`. Public — no auth required.

**Response 200**
```json
{
  "ok": true,
  "tools": [
    {
      "name": "datetime",
      "description": "Get the current date and time.",
      "parameters": [
        { "name": "format",   "type": "STRING", "required": false },
        { "name": "timezone", "type": "STRING", "required": false }
      ]
    },
    {
      "name": "calculator",
      "description": "Evaluate a mathematical expression.",
      "parameters": [
        { "name": "expression", "type": "STRING", "required": true }
      ]
    },
    {
      "name": "litertlm-docs",
      "description": "Access project technical documentation.",
      "parameters": [
        { "name": "action", "type": "STRING", "required": true  },
        { "name": "name",   "type": "STRING", "required": false }
      ]
    }
  ]
}
```
