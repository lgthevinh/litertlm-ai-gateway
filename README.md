# LiteRTLM AI Gateway

LiteRTLM AI Gateway is a Kotlin/JVM HTTP gateway that wraps Google AI Edge LiteRTLM for on-device LLM inference. It exposes REST and WebSocket APIs for conversation management, user authentication, and API key management, backed by SQLite persistence and a plugin-ready architecture.

## Key Features

- REST and WebSocket endpoints for chat conversations
- JWT-based user authentication with refresh tokens
- API key generation and validation for programmatic clients
- SQLite persistence for users, tokens, and API keys
- Built-in conversation presets: assistant, coder, concise, creative
- Configurable via `.env` with environment variable overrides

## Tech Stack

- Kotlin 2.3.10, JDK 21
- Ktor 3.4.2 (Netty)
- Google AI Edge LiteRTLM JVM SDK 0.10.0
- SQLite (via DaoSqlite)
- Gson, HikariCP

## Prerequisites

- JDK 21+
- LiteRTLM model files downloaded to `model/`
- Native LiteRTLM library for your platform

## Setup

1. Create environment file.

```bash
copy .env.example .env
```

2. Edit `.env` and set secure values:

```
X_API_KEY=change-me-x-api-key
JWT_SECRET=change-me-jwt-secret
```

3. Download the LiteRTLM model and place it under `model/`.

The default engine config in `src/main/kotlin/LMApplication.kt` expects:

```
model/gemma4-e4b/gemma-4-E4B-it.litertlm
```

Model download steps:

1) Obtain the LiteRTLM model file for Gemma 4 E4B Instruct from your distribution source (Google AI Edge LiteRTLM model package).
2) Create the directory `model/gemma4-e4b/`.
3) Place the `.litertlm` file at `model/gemma4-e4b/gemma-4-E4B-it.litertlm`.

If you want to use a different model file or path, update the `EngineConfig` in `src/main/kotlin/LMApplication.kt`.

4. Ensure the native LiteRTLM library is available.

The repository includes `jni-libs/libLiteRt_linux_x86_64.so` for Linux. For other platforms, place the correct native library where the LiteRTLM JVM SDK can load it (per your LiteRTLM installation instructions).

## API Manual

### 1) Health Check

```
GET /
```

### 2) Login and Token Flow

Login:

```
POST /api/auth/login
{
  "username": "alice",
  "password": "s3cr3t"
}
```

Response:

```
{ "ok": true, "accessToken": "...", "refreshToken": "..." }
```

Refresh:

```
POST /api/auth/refresh
{ "refreshToken": "..." }
```

Logout:

```
POST /api/auth/logout
{ "refreshToken": "..." }
```

Special local account:

- Use `username: "local"` with no password for local development (reserved virtual user).

### 3) Create Users (JWT + X-Api-Key)

To create a new user remotely, you must provide `X-Api-Key` that matches `X_API_KEY` in `.env`.

```
POST /api/auth/user/create
Authorization: Bearer <accessToken>
X-Api-Key: <X_API_KEY>

{ "username": "alice", "password": "s3cr3t" }
```

### 4) API Key Management (JWT required)

Generate a key:

```
POST /api/api-key/generate
Authorization: Bearer <accessToken>

{ "name": "my-client" }
```

List keys:

```
GET /api/api-key/list
Authorization: Bearer <accessToken>
```

Revoke key:

```
DELETE /api/api-key/revoke
Authorization: Bearer <accessToken>

{ "key": "lrtlm_..." }
```

### 5) Conversations (JWT or API Key)

Create conversation:

```
POST /api/conversations
Authorization: Bearer <accessToken|apiKey>

{ "name": "my-chat", "config": "assistant" }
```

Send message:

```
POST /api/conversations/my-chat/messages
Authorization: Bearer <accessToken|apiKey>

{ "message": "Hello" }
```

Close conversation:

```
DELETE /api/conversations/my-chat
Authorization: Bearer <accessToken|apiKey>
```

### 6) WebSocket Streaming

Connect:

```
ws://localhost:8080/ws/conversations/my-chat?token=<accessToken|apiKey>
```

Client → Server:

```
{ "message": "Hello" }
```

Server → Client:

```
{ "type": "token", "token": "Hel" }
{ "type": "token", "token": "lo" }
{ "type": "done" }
```

### 7) Built-in Conversation Presets

Use one of:

- `assistant`
- `coder`
- `concise`
- `creative`

Or provide a custom system instruction:

```
{ "name": "my-chat", "systemInstruction": "You are a pirate." }
```

## Data Directory

On first run, the app creates a data directory named `lm_application` and stores:

```
lm_application/
└── lm_application.db
```

## Configuration Notes

- `.env` values can be overridden by real environment variables at runtime.
- The default model path and backend are set in `src/main/kotlin/LMApplication.kt`.

## Troubleshooting

- `Engine not ready`: check that the model file exists at the configured path and the native LiteRTLM library is available for your platform.
- `Invalid or expired access token`: re-login or refresh the token.
- `X-Api-Key required for remote user creation`: ensure `X_API_KEY` is set and passed in the header.
