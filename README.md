# LiteRTLM AI Gateway

LiteRTLM AI Gateway is a Kotlin/JVM server that runs a LiteRTLM model locally and exposes it as HTTP and WebSocket APIs. It is designed for teams who want a simple, self-hosted chat gateway with authentication, API keys, and persistent conversation history.

The gateway wraps Google AI Edge LiteRTLM (on-device LLM inference) and provides a thin, production-style API that you can use from a web app, a script, or another service.

## What this project is for

- Run an on-device LiteRTLM model behind a clean HTTP API
- Build internal chat tools without depending on external LLM services
- Provide authenticated access for users and programmatic clients
- Store conversations and messages in SQLite for audit or replay

## Key features

- REST and WebSocket chat APIs
- JWT login + refresh tokens
- API key management for programmatic clients
- Conversation presets (assistant, coder, concise, creative)
- Tool calling (built-in datetime + calculator) with support for custom tool implementations
- Local inference: model runs on-device, keeping data private
- SQLite persistence with a local data directory

## Who this is for

- Developers who want an on-device LLM gateway with minimal setup
- Product teams building internal assistants
- Anyone experimenting with LiteRTLM and requiring a stable API layer

## Tech stack

- Kotlin 2.3.10, JDK 21
- Ktor 3.4.2 (Netty)
- Google AI Edge LiteRTLM JVM SDK 0.10.0
- SQLite (DaoSqlite)
- Gson, HikariCP

## Prerequisites

- JDK 21+
- A LiteRTLM model file under `model/`
- Currently supported platforms: Linux x86_64 and macOS ARM (Apple Silicon)

## Supported models (current)

- Model name (tested): gemma4-e4b-it.litertlm, gemma4-e2b-it.litertlm
- Download: [e4b](https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm), [e2b](https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm)

## Install and run

### 1) Configure environment variables

Copy the example file and update secrets:

```bash
copy .env.example .env
```

Set values in `.env` (these match `.env.example`):

```
JWT_SECRET=change-me-jwt-secret
AUTH_USERNAME=admin
AUTH_PASSWORD=change-me-password
```

### 2) Add a model

The default configuration in `src/main/kotlin/LMApplication.kt` expects:

```
model/gemma4-e4b/gemma-4-E4B-it.litertlm
```

Download the LiteRTLM model file for your target device, then place it at that path. To use a different model or folder, update the `EngineConfig` in `src/main/kotlin/LMApplication.kt`.

### 3) Build and run

Build a fat JAR:

```bash
./gradlew buildFatJar
```

Run it:

```bash
./gradlew runFatJar
```

Or run the JAR directly:

```bash
java -jar build/libs/aigateway-1.0-all.jar
```

The server starts on `http://0.0.0.0:8080`.

### 4) Run as a Linux service (systemd)

A service unit file is provided at `litertlm-gateway.service`. Before installing, open it and replace:

- `YOUR_USER` — the Linux user to run the process as
- `/path/to/litertlm-ai-gateway` — absolute path to the project root (appears 3 times)
- `aigateway-1.0-all.jar` — actual JAR filename if it differs

Then install and start the service:

```bash
sudo cp litertlm-gateway.service /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable litertlm-gateway
sudo systemctl start litertlm-gateway
```

Check status and follow logs:

```bash
sudo systemctl status litertlm-gateway
sudo journalctl -u litertlm-gateway -f
```

The service reads environment variables directly from your `.env` file and restarts automatically on failure.

## How to use

### 1) Log in and get a token

```http
POST /api/auth/login
{
  "username": "admin",
  "password": "change-me-password"
}
```

Response:

```json
{ "ok": true, "accessToken": "...", "refreshToken": "..." }
```

### 2) Create a conversation

```http
POST /api/conversations
Authorization: Bearer <accessToken|apiKey>

{ "name": "my-chat", "config": "assistant" }
```

### 3) Send a message (blocking)

```http
POST /api/conversations/my-chat/messages
Authorization: Bearer <accessToken|apiKey>

{ "message": "Hello" }
```

### 4) Stream responses (WebSocket)

```
ws://localhost:8080/ws/conversations/my-chat?token=<accessToken|apiKey>
```

Client -> server:

```
{ "message": "Hello" }
```

Server -> client:

```
{ "type": "token", "token": "Hel" }
{ "type": "token", "token": "lo" }
{ "type": "done" }
```

### 5) Generate API keys (JWT required)

```http
POST /api/api-key/generate
Authorization: Bearer <accessToken>

{ "name": "my-client" }
```

List keys:

```http
GET /api/api-key/list
Authorization: Bearer <accessToken>
```

Revoke a key:

```http
DELETE /api/api-key/revoke
Authorization: Bearer <accessToken>

{ "key": "lrtlm_..." }
```

## Application examples

- Internal customer support assistant running entirely on-device
- Local developer helper with custom tool integration
- Gateway for embedded or edge deployments without internet access
- Data crawling normalization with LLM-assisted cleanup
- Prototyping chat workflows with persistent history

## Data storage

On first run, the app creates a data directory named `lm_application` and stores:

```
lm_application/
└── lm_application.db
```

## Configuration notes

- Real environment variables override `.env` values.
- Default model path and settings are set in `src/main/kotlin/LMApplication.kt`.

## Troubleshooting

- `Engine not ready`: model file path is missing.
- `Invalid or expired access token`: re-login or refresh your token.
- `Unauthorized`: check that the `Authorization: Bearer ...` header is present and valid.
