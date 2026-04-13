# LiteRTLM AI Gateway — API Endpoint Reference

> Base URL: `http://<host>:8080`
> All request/response bodies are `application/json` unless noted.
> All protected endpoints require `Authorization: Bearer <token>` (JWT or API key).
> WebSocket auth uses `?token=<token>` query parameter.

---

## Table of Contents

1. [Authentication](#authentication)
2. [Conversations](#conversations)
3. [Messages](#messages)
4. [WebSocket Streaming](#websocket-streaming)
5. [Tools](#tools)
6. [Queue](#queue)
7. [Attachments](#attachments)
8. [API Keys](#api-keys)
9. [Error Responses](#error-responses)
10. [Quick Start Examples](#quick-start-examples)

---

## Authentication

### POST `/api/auth/login`

Obtain access and refresh tokens.

**Auth:** None

**Request**
```json
{
  "username": "admin",
  "password": "your-password"
}
```

**Response 200**
```json
{
  "ok": true,
  "accessToken": "eyJhbGciOiJIUzI1NiJ9...",
  "refreshToken": "eyJhbGciOiJIUzI1NiJ9...",
  "username": "admin"
}
```

**Response 401** — wrong credentials
```json
{ "ok": false, "error": "Invalid credentials" }
```

---

### POST `/api/auth/refresh`

Rotate tokens. The old refresh token is invalidated.

**Auth:** None

**Request**
```json
{
  "refreshToken": "eyJhbGciOiJIUzI1NiJ9..."
}
```

**Response 200**
```json
{
  "ok": true,
  "accessToken": "eyJhbGciOiJIUzI1NiJ9...",
  "refreshToken": "eyJhbGciOiJIUzI1NiJ9..."
}
```

**Response 401** — expired or invalid token

---

### POST `/api/auth/logout`

Revoke the refresh token.

**Auth:** None

**Request**
```json
{
  "refreshToken": "eyJhbGciOiJIUzI1NiJ9..."
}
```

**Response 200**
```json
{ "ok": true }
```

---

## Conversations

### GET `/api/conversations`

List all conversations, newest-first.

**Auth:** JWT or API key

**Response 200**
```json
{
  "ok": true,
  "conversations": [
    { "name": "my-chat",    "stateless": false },
    { "name": "quick-chat", "stateless": true  }
  ]
}
```

---

### POST `/api/conversations`

Create a new conversation.

**Auth:** JWT or API key

**Request**
```json
{
  "name":              "my-chat",
  "config":            "assistant",
  "systemInstruction": "You are a helpful assistant.",
  "tools":             ["datetime", "calculator"],
  "stateless":         false
}
```

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `name` | string | **yes** | Unique conversation identifier. Letters, digits, hyphens. |
| `config` | string | no | Preset: `assistant` (default), `coder`, `concise`, `creative` |
| `systemInstruction` | string | no | Custom system prompt. Overrides `config` preset if provided. |
| `tools` | string[] | no | Tool names to enable (e.g. `["datetime"]`). Default: none. |
| `stateless` | boolean | no | If `true`, no history is loaded or persisted. Default: `false`. |

**Response 201**
```json
{
  "ok":       true,
  "name":     "my-chat",
  "config":   "assistant",
  "stateless": false
}
```

**Response 409** — name already exists
```json
{ "ok": false, "error": "Conversation 'my-chat' already exists" }
```

---

### PATCH `/api/conversations/{name}`

Update conversation configuration. All fields are optional — only provided fields are updated.

**Auth:** JWT or API key

**Request**
```json
{
  "config":                "coder",
  "systemInstruction":     "You are an expert Go developer.",
  "clearSystemInstruction": false,
  "topK":                  40,
  "topP":                  0.95,
  "temperature":           0.8,
  "tools":                 ["datetime"]
}
```

| Field | Type | Description |
|-------|------|-------------|
| `config` | string | Change preset label |
| `systemInstruction` | string | Set a new custom system instruction |
| `clearSystemInstruction` | boolean | If `true`, remove system instruction and revert to preset |
| `topK` | int | Sampler top-K |
| `topP` | float | Sampler top-P (nucleus sampling) |
| `temperature` | float | Sampler temperature |
| `tools` | string[] | Replace full tool list. Pass `[]` to disable all tools. |

**Response 200**
```json
{
  "ok":     true,
  "name":   "my-chat",
  "config": "coder"
}
```

**Response 404** — conversation not found

---

### DELETE `/api/conversations/{name}`

Delete a conversation, all message history, and all attachment files on disk.

**Auth:** JWT or API key

**Response 200**
```json
{ "ok": true }
```

**Response 404** — conversation not found

---

### GET `/api/conversations/{name}/state`

Check whether a conversation is currently running inference.

**Auth:** JWT or API key

**Response 200**
```json
{
  "ok":    true,
  "name":  "my-chat",
  "state": "IDLE"
}
```

`state` is one of:
| Value | Meaning |
|-------|---------|
| `IDLE` | No inference in progress. Ready to receive messages. |
| `BUSY` | Inference is running. New message requests will be rejected with an error. |

---

## Messages

### GET `/api/conversations/{name}/messages`

Fetch the full message history for a conversation, oldest-first.

**Auth:** JWT or API key

**Response 200**
```json
{
  "ok": true,
  "messages": [
    {
      "role":      "user",
      "text":      "What's in this image?",
      "seq":       0,
      "createdAt": 1712345678000,
      "attachments": [
        { "type": "image", "filename": "0_0.jpg" }
      ]
    },
    {
      "role":      "model",
      "text":      "The image shows a tabby cat sitting on a windowsill.",
      "seq":       1,
      "createdAt": 1712345680000,
      "attachments": null
    }
  ]
}
```

| Field | Type | Description |
|-------|------|-------------|
| `role` | string | `"user"` or `"model"` |
| `text` | string | Message text content |
| `seq` | int | Monotonically increasing sequence number within the conversation |
| `createdAt` | long | Unix epoch milliseconds |
| `attachments` | array\|null | List of attachment references, or `null` for text-only messages |
| `attachments[].type` | string | `"image"` or `"audio"` |
| `attachments[].filename` | string | Filename — use with `GET /api/attachments/{name}/{filename}` to fetch the file |

**Response 404** — conversation not found

---

### POST `/api/conversations/{name}/messages`

Send a message and receive the full reply. This is a **blocking** endpoint — the HTTP connection stays open until inference completes. For streaming, use the [WebSocket endpoint](#websocket-streaming) instead.

**Auth:** JWT or API key

#### Text-only (application/json)

**Request**
```json
{
  "message": "Explain the concept of recursion."
}
```

#### With attachments (multipart/form-data)

```
POST /api/conversations/my-chat/messages
Content-Type: multipart/form-data; boundary=----FormBoundary

------FormBoundary
Content-Disposition: form-data; name="message"

What is in this image?
------FormBoundary
Content-Disposition: form-data; name="images"; filename="photo.jpg"
Content-Type: image/jpeg

<binary JPEG data>
------FormBoundary--
```

| Part name | Type | Description |
|-----------|------|-------------|
| `message` | text | The user message text (required) |
| `images` | file | Image file attachment (repeat for multiple images) |
| `audio` | file | Audio file attachment (repeat for multiple audio files) |

Supported image formats: `jpg`, `jpeg`, `png`, `webp`, `gif`
Supported audio formats: `wav`, `mp3`, `ogg`, `m4a`

**Response 200**
```json
{
  "ok":    true,
  "reply": "The image shows a tabby cat sitting on a windowsill..."
}
```

**Response 400** — missing message field
**Response 404** — conversation not found
**Response 500** — inference error
**Response 503** — engine not ready

---

## WebSocket Streaming

### WS `/ws/conversations/{name}?token=<token>`

Real-time streaming inference over WebSocket. Supports multimodal messages. The connection stays open for the full conversation lifetime — send multiple messages in sequence.

**Auth:** `?token=<accessToken|apiKey>` query parameter

**Connection**
```
ws://host:8080/ws/conversations/my-chat?token=eyJhbGciOiJIUzI1NiJ9...
```
or with an API key:
```
ws://host:8080/ws/conversations/my-chat?token=lrtlm_abc123...
```

---

#### Client → Server: Text message

```json
{ "message": "Hello, how are you?" }
```

#### Client → Server: Multimodal message

```json
{
  "message": "What is in this image?",
  "images":  ["data:image/png;base64,iVBORw0KGgo..."],
  "audio":   ["data:audio/wav;base64,UklGRnoGAAA..."]
}
```

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `message` | string | **yes** | The user message text |
| `images` | string[] | no | Base64-encoded image files. Data-URI prefix (`data:image/png;base64,`) is supported. |
| `audio` | string[] | no | Base64-encoded audio files. Data-URI prefix (`data:audio/wav;base64,`) is supported. |

---

#### Server → Client frame types

##### `queued` — task is waiting in queue

Sent immediately when the engine is busy with another conversation.

```json
{ "type": "queued", "position": 2 }
```

`position` is 1-based. Position 1 = currently processing.

---

##### `busy` — inference is running

Sent when inference begins (either immediately, or after leaving the queue).

```json
{ "type": "busy" }
```

After receiving `busy`, wait for `token` frames and then a `done` frame.

---

##### `token` — partial response token

Sent for each token as the model generates them.

```json
{ "type": "token", "token": "Hello" }
```

Tokens arrive in order and can be concatenated to build the partial response.

---

##### `done` — turn complete

Sent when inference finishes. Contains the **authoritative full reply** — use this instead of the concatenated tokens (they may have been missed on reconnect).

```json
{ "type": "done", "reply": "Hello! I'm doing well, thank you for asking." }
```

After receiving `done`, the conversation is ready for the next message.

---

##### `error` — recoverable error

```json
{ "type": "error", "error": "Conversation 'my-chat' is busy — please wait for the current reply to complete" }
```

The connection stays open after an error. Send another message to try again.

---

#### Connection lifecycle

```
Client connects
    |-- Engine not ready → error frame + close
    |-- Not authenticated → error frame + close
    |-- Conversation is BUSY (reconnect mid-inference) →
    |       send busy frame
    |       stream remaining tokens
    |       send done frame
    |       fall through to message loop
    +-- IDLE → message loop

Message loop:
    Client sends { "message": "..." }
        → queued frame (if engine busy with another conversation)
        → busy frame (when inference starts)
        → token frames (streamed)
        → done frame (full reply)
    Client sends next message...
```

---

#### Reconnect behaviour

If the client disconnects during inference, the inference continues in a detached coroutine on the server. On reconnect:

- If inference is **still running**: client receives `busy` → tokens from the current position → `done`
- If inference **already completed**: client receives no frames; use `GET /messages` to fetch history

---

#### WebSocket example (JavaScript)

```javascript
const ws = new WebSocket('ws://localhost:8080/ws/conversations/my-chat?token=' + accessToken);
let fullReply = '';

ws.onmessage = (evt) => {
  const frame = JSON.parse(evt.data);
  switch (frame.type) {
    case 'queued': console.log('Queued at position', frame.position); break;
    case 'busy':   console.log('Inference running...'); break;
    case 'token':  fullReply += frame.token; process.stdout.write(frame.token); break;
    case 'done':   console.log('\nFull reply:', frame.reply); fullReply = ''; break;
    case 'error':  console.error('Error:', frame.error); break;
  }
};

ws.onopen = () => {
  ws.send(JSON.stringify({ message: 'Tell me a joke.' }));
};
```

---

#### Multimodal WebSocket example (JavaScript)

```javascript
async function sendImage(ws, message, imageFile) {
  const reader = new FileReader();
  reader.onload = (e) => {
    ws.send(JSON.stringify({
      message: message,
      images: [e.target.result]  // data-URI string, e.g. "data:image/jpeg;base64,..."
    }));
  };
  reader.readAsDataURL(imageFile);
}
```

---

## Tools

### GET `/api/tools`

List all tools currently registered in the gateway.

**Auth:** None (public endpoint)

**Response 200**
```json
{
  "ok": true,
  "tools": [
    {
      "name": "datetime",
      "description": "Get the current date and time in a specified format.",
      "parameters": {
        "type": "object",
        "properties": {
          "format":   { "type": "string", "description": "Java date format pattern e.g. yyyy-MM-dd HH:mm:ss" },
          "timezone": { "type": "string", "description": "IANA timezone ID e.g. America/New_York" }
        },
        "required": []
      }
    },
    {
      "name": "calculator",
      "description": "Evaluate a mathematical expression and return the numeric result.",
      "parameters": {
        "type": "object",
        "properties": {
          "expression": { "type": "string", "description": "A mathematical expression e.g. (3 + 5) * 2" }
        },
        "required": ["expression"]
      }
    }
  ]
}
```

Tools are assigned per conversation at creation time via the `tools` field on `POST /conversations`.
At inference time, the model may call tools automatically — tool calls and responses are handled entirely inside the SDK and are transparent to the client.

---

## Queue

### GET `/api/queue`

Inspect the current inference queue. Useful for monitoring load and debugging.

**Auth:** None (public endpoint)

**Response 200**
```json
{
  "ok":   true,
  "size": 2,
  "queue": [
    { "name": "my-chat",    "position": 1, "status": "processing" },
    { "name": "other-chat", "position": 2, "status": "waiting"    }
  ]
}
```

| Field | Description |
|-------|-------------|
| `size` | Total number of conversations currently queued or processing |
| `queue[].name` | Conversation name |
| `queue[].position` | 1-based position (1 = currently running) |
| `queue[].status` | `"processing"` or `"waiting"` |

Only one conversation is processed at a time (single engine). All others wait in `waiting` state.

---

## Attachments

### GET `/api/attachments/{convName}/{filename}`

Fetch a stored attachment file (image or audio) from conversation history.

**Auth:** None (public endpoint)

**Path parameters**

| Parameter | Description |
|-----------|-------------|
| `convName` | Conversation name (must match a valid conversation) |
| `filename` | Filename as returned in `GET /messages` → `attachments[].filename` |

**Response 200** — raw file bytes with appropriate `Content-Type`

| Extension | Content-Type |
|-----------|-------------|
| `jpg`, `jpeg` | `image/jpeg` |
| `png` | `image/png` |
| `gif` | `image/gif` |
| `webp` | `image/webp` |
| `wav` | `audio/wav` |
| `mp3` | `audio/mpeg` |
| `ogg` | `audio/ogg` |
| `m4a` | `audio/mp4` |

**Response 404** — file not found
**Response 400** — invalid path (path traversal attempt rejected)

**Example**
```
GET /api/attachments/my-chat/0_0.jpg
→ 200 image/jpeg <binary JPEG>
```

Filenames follow the pattern `{seq}_{index}.{ext}`:
- `seq` is the sequence number of the user message in the conversation
- `index` is the 0-based position among attachments in that message

---

## API Keys

All API key endpoints require a valid **JWT** token (not an API key).

---

### POST `/api/api-key/generate`

Generate a new API key. The raw key is returned **once** — it cannot be retrieved again.

**Auth:** JWT only

**Request**
```json
{
  "name": "my-service-key"
}
```

**Response 200**
```json
{
  "ok":     true,
  "key":    "lrtlm_abc123xyz...",
  "prefix": "lrtlm_abc1",
  "name":   "my-service-key"
}
```

Store `key` securely. Use it as a Bearer token: `Authorization: Bearer lrtlm_abc123xyz...`

---

### GET `/api/api-key/list`

List all API keys. Key hashes are never exposed.

**Auth:** JWT only

**Response 200**
```json
{
  "ok": true,
  "keys": [
    {
      "id":        "uuid-here",
      "prefix":    "lrtlm_abc1",
      "name":      "my-service-key",
      "active":    true,
      "createdAt": 1712345678000,
      "lastUsedAt": 1712345680000
    }
  ]
}
```

---

### GET `/api/api-key/info?key=lrtlm_...`

Get metadata for a specific API key.

**Auth:** JWT only

**Query parameter:** `key=lrtlm_abc123xyz...` (the full raw key)

**Response 200**
```json
{
  "ok":     true,
  "prefix": "lrtlm_abc1",
  "name":   "my-service-key",
  "active": true
}
```

---

### DELETE `/api/api-key/revoke`

Soft-revoke an API key. The key remains in the DB but will be rejected on use.

**Auth:** JWT only

**Request**
```json
{
  "key": "lrtlm_abc123xyz..."
}
```

**Response 200**
```json
{ "ok": true }
```

---

## Error Responses

All errors return a JSON body with `ok: false`:

```json
{ "ok": false, "error": "Human-readable error message" }
```

| HTTP Status | Meaning |
|-------------|---------|
| `400 Bad Request` | Missing or invalid request fields |
| `401 Unauthorized` | Missing, expired, or invalid token |
| `404 Not Found` | Conversation, message, or file not found |
| `409 Conflict` | Conversation name already exists |
| `500 Internal Server Error` | Inference failure or unexpected server error |
| `503 Service Unavailable` | Engine not ready (still initialising) |

---

## Quick Start Examples

### 1. Login and send a message

```bash
# 1. Login
TOKEN=$(curl -s -X POST http://localhost:8080/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"changeme"}' \
  | jq -r '.accessToken')

# 2. Create a conversation
curl -s -X POST http://localhost:8080/api/conversations \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"name":"test-chat","config":"assistant"}'

# 3. Send a message
curl -s -X POST http://localhost:8080/api/conversations/test-chat/messages \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"message":"What is 2 + 2?"}' \
  | jq '.reply'
```

### 2. Send a message with an image

```bash
curl -s -X POST http://localhost:8080/api/conversations/test-chat/messages \
  -H "Authorization: Bearer $TOKEN" \
  -F 'message=What is in this image?' \
  -F 'images=@/path/to/photo.jpg' \
  | jq '.reply'
```

### 3. Stream with WebSocket (Python)

```python
import asyncio, websockets, json

async def chat():
    uri = "ws://localhost:8080/ws/conversations/test-chat?token=YOUR_TOKEN"
    async with websockets.connect(uri) as ws:
        await ws.send(json.dumps({"message": "Tell me a haiku about autumn."}))
        full_reply = ""
        async for msg in ws:
            frame = json.loads(msg)
            if frame["type"] == "token":
                print(frame["token"], end="", flush=True)
                full_reply += frame["token"]
            elif frame["type"] == "done":
                print()  # newline
                break

asyncio.run(chat())
```

### 4. Generate and use an API key

```bash
# Generate
KEY=$(curl -s -X POST http://localhost:8080/api/api-key/generate \
  -H "Authorization: Bearer $JWT_TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"name":"ci-pipeline"}' \
  | jq -r '.key')

# Use the API key directly (no login needed)
curl -s http://localhost:8080/api/conversations \
  -H "Authorization: Bearer $KEY" \
  | jq '.conversations'
```

### 5. Create a conversation with tools

```bash
curl -s -X POST http://localhost:8080/api/conversations \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{
    "name":   "tools-chat",
    "config": "assistant",
    "tools":  ["datetime", "calculator"]
  }'

# The model can now call datetime and calculator automatically
curl -s -X POST http://localhost:8080/api/conversations/tools-chat/messages \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"message":"What day is it, and what is 17 * 23?"}' \
  | jq '.reply'
```

### 6. Fetch history and load an image attachment

```bash
# Get message history
MESSAGES=$(curl -s http://localhost:8080/api/conversations/test-chat/messages \
  -H "Authorization: Bearer $TOKEN")

# Extract the filename of the first attachment on the first user message
FILENAME=$(echo $MESSAGES | jq -r '.messages[] | select(.role=="user") | .attachments[0].filename // empty' | head -1)

# Fetch the image
curl -s "http://localhost:8080/api/attachments/test-chat/$FILENAME" -o downloaded.jpg
```
