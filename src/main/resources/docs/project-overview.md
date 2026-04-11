# Project Overview

LiteRTLM AI Gateway is a Kotlin/JVM HTTP gateway that wraps Google AI Edge LiteRTLM for on-device LLM inference. It exposes a REST API for conversation management, authentication, API key management, and tool execution, backed by SQLite persistence.

---

## Tech Stack

| Layer | Technology |
|-------|-----------|
| Language | Kotlin 2.3.10, JDK 21 |
| Build | Gradle Kotlin DSL 9.0.0 |
| HTTP server | Ktor 3.4.2 (Netty engine) |
| LLM inference | Google AI Edge LiteRTLM JVM SDK 0.10.0 |
| Database | SQLite via `DaoSqlite` (desktopplatform.jar) |
| Logging | `ILog` / `ILogImpl` (applicationbase.jar) |
| JSON | Gson 2.13.2 |
| Connection pool | HikariCP 5.1.0 |

---

## Folder Layout

```
litertlm-ai-gateway/
├── build.gradle.kts
├── libs/
│   ├── applicationbase.jar       # ILog, LRUCache, Service base
│   └── desktopplatform.jar       # DaoSqlite, ILogImpl
├── jni-libs/
│   └── libLiteRt_linux_x86_64.so
├── model/
│   ├── gemma4-e2b/
│   └── gemma4-e4b/
└── src/main/kotlin/
    ├── Main.kt                   # Entry point — starts Ktor on :8080
    ├── LMApplication.kt          # Service bootstrap
    ├── api/plugin/               # AuthPlugin, ApiKeyPlugin, DualAuthPlugin
    ├── api/route/                # All route handlers + DTOs
    ├── auth/                     # JWT auth + API key management
    ├── lm/                       # LMService, handlers, tools, entities
    ├── callback/                 # RequestCallback interface
    └── utils/                    # EnvConfig, JsonUtils
```

---

## API Routes

### Public (no auth)

| Method | Path | Description |
|--------|------|-------------|
| GET | `/` | Serves the web UI |
| GET | `/api/tools` | List all registered tools and schemas |
| POST | `/api/auth/login` | Login → `{ accessToken, refreshToken }` |
| POST | `/api/auth/refresh` | Rotate tokens |
| POST | `/api/auth/logout` | Revoke refresh token |

### Protected — JWT or API key (`DualAuthPlugin`)

| Method | Path | Description |
|--------|------|-------------|
| GET | `/api/conversations` | List all conversations |
| POST | `/api/conversations` | Create conversation |
| GET | `/api/conversations/{name}/messages` | Fetch message history |
| POST | `/api/conversations/{name}/messages` | Send message → blocking `{ reply }` |
| DELETE | `/api/conversations/{name}` | Delete conversation |
| WS | `/ws/conversations/{name}?token=...` | Streaming inference |

### Protected — JWT only (`AuthPlugin`)

| Method | Path | Description |
|--------|------|-------------|
| POST | `/api/api-key/generate` | Generate API key |
| GET | `/api/api-key/list` | List all keys |
| GET | `/api/api-key/info?key=lrtlm_...` | Get key metadata |
| DELETE | `/api/api-key/revoke` | Soft-revoke a key |

---

## Authentication

Two credential types, both via `Authorization: Bearer <token>`:

| Type | Prefix | Used for |
|------|--------|----------|
| JWT access token | `eyJ...` | Interactive users |
| API key | `lrtlm_...` | Programmatic clients |

Credentials are configured via environment variables — no user registration endpoint.

---

## Environment Variables (`.env`)

| Variable | Description |
|----------|-------------|
| `AUTH_USERNAME` | Single admin username |
| `AUTH_PASSWORD` | Admin password |
| `JWT_SECRET` | HMAC-SHA256 signing secret |

Real environment variables always take precedence over `.env` file values.

---

## Data Directory

At first run, `LMApplication` creates `lm_application/` next to the JAR:

```
lm_application/
└── lm_application.db    # SQLite: api_keys, lm_conversations, lm_messages
```

---

## Implemented Features

- JWT auth (HS256, 15 min access / 7 day refresh, token rotation)
- API key management (SHA-256 hashed, `lrtlm_` prefix, soft-revoke)
- Conversation management (create, list, send, delete)
- Message history persistence and replay (last 40 messages)
- Four builtin conversation presets: ASSISTANT, CODER, CONCISE, CREATIVE
- Custom system instruction per conversation
- WebSocket streaming inference + REST blocking inference
- Fixed 2-engine pool with shared task queue
- Tool execution framework (`automaticToolCalling` via LiteRTLM SDK)
- Built-in tools: `datetime`, `calculator`, `docs`
- SQLite persistence via `DaoSqlite` (HikariCP pool)
- Web UI (SPA — conversations, API keys, API docs)
