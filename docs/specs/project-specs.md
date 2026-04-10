## Project Overview

LiteRTLM AI Gateway is a Kotlin/JVM HTTP gateway that wraps Google AI Edge LiteRTLM for on-device LLM inference. It exposes a REST API for conversation management, authentication, and API key management, backed by SQLite persistence and a plugin-ready architecture.

---

## Tech Stack

| Layer | Technology |
|-------|-----------|
| Language | Kotlin 2.3.10, JDK 21 |
| Build | Gradle Kotlin DSL 9.0.0 |
| HTTP server | Ktor 3.4.2 (Netty lm) |
| LLM inference | Google AI Edge LiteRTLM JVM SDK 0.10.0 |
| Database | SQLite via `DaoSqlite` (desktopplatform.jar) |
| Logging | `ILog` / `ILogImpl` (applicationbase.jar) |
| JSON | Gson 2.13.2 |
| Connection pool | HikariCP 5.1.0 |

---

## Folder Layout

```
litertlm-ai-gateway/
│
├── .env                            # Runtime secrets (git-ignored)
├── .env.example                    # Template — commit this, not .env
├── build.gradle.kts                # Gradle build + dependencies
├── settings.gradle.kts
│
├── libs/                           # Local JARs (vendored)
│   ├── applicationbase.jar         # Dao interface, ILog, LRUCache, Service base
│   └── desktopplatform.jar         # DaoSqlite, DaoFile, ILogImpl
│
├── jni-libs/                       # Native shared libraries for LiteRTLM
│   └── libLiteRt_linux_x86_64.so
│
├── model/                          # LiteRTLM model files (git-ignored)
│   ├── gemma4-e2b/
│   └── gemma4-e4b/
│
├── docs/
│   └── specs/
│       ├── project-specs.md        # This file
│       ├── architecture.md         # Full system architecture
│       └── technical.md            # Technical design notes
│
└── src/main/kotlin/
    │
    ├── Main.kt                     # Entry point — starts Ktor on :8080
    ├── LMApplication.kt            # Service bootstrap: DB, auth, lm init
    │
    ├── api/
    │   ├── plugin/
    │   │   └── AuthPlugin.kt       # AuthPlugin (JWT), ApiKeyPlugin, DualAuthPlugin
    │   │
    │   └── route/
    │       ├── Route.kt            # Registers all routes on Application
    │       ├── RouteAuth.kt        # POST /auth/login|refresh|logout|user/create
    │       ├── RouteConversation.kt# GET|POST /conversations, POST|DELETE /{name}
    │       ├── RouteApiKey.kt      # POST /api-key/generate, GET /list|info, DELETE /revoke
    │       ├── RouteConfig.kt      # GET / (health), GET /config/models/current
    │       ├── RouteExtensions.kt  # respondJson() extension for ApplicationCall
    │       └── dto/
    │           ├── RouteDtoAuth.kt         # CreateUserRequest, LoginRequest/Response, ...
    │           ├── RouteDtoAppKey.kt       # GenerateKeyRequest/Response, ApiKeyInfo, ...
    │           ├── RouteDtoConversation.kt # CreateConversationRequest/Response, SendMessage...
    │           └── RouteDtoCommon.kt       # OkResponse, ApiErrorResponse
    │
    ├── auth/
    │   ├── LMApiKey.kt             # @DaoTable entity + LMApiKeyResult
    │   ├── LMApiKeyService.kt      # Generate, validate, revoke, list API keys
    │   ├── LMAuth.kt               # LMAuthUser, LMAuthToken @DaoTable entities, LMAuthJwt
    │   └── LMAuthService.kt        # Register, login, refresh, logout, validateAccessToken
    │
    ├── lm/
    │   ├── LMEngineManager.kt      # Singleton — holds Engine + ConversationHandler
    │   ├── builtin/
    │   │   └── BuiltinConversationConfig.kt  # ASSISTANT, CODER, CONCISE, CREATIVE presets
    │   ├── entity/
    │   │   ├── LMConversation.kt   # In-memory conversation wrapper
    │   │   └── LMMessage.kt        # Message entity stub
    │   └── handler/
    │       └── ConversationHandler.kt  # ConcurrentHashMap-backed conversation lifecycle
    │
    ├── callback/
    │   └── RequestCallback.kt      # Generic onSuccess/onError interface
    │
    └── utils/
        ├── EnvConfig.kt            # .env file parser, env var lookup
        └── JsonUtils.kt            # Gson wrapper (toJson, fromJson)
```

---

## API Routes

### Public (no auth)

| Method | Path | Description |
|--------|------|-------------|
| GET | `/` | Health check |
| GET | `/config/models/current` | Current model info |
| POST | `/auth/login` | Login → `{ accessToken, refreshToken }` |
| POST | `/auth/refresh` | Rotate tokens → new `{ accessToken, refreshToken }` |
| POST | `/auth/logout` | Revoke refresh token |
| POST | `/auth/user/create` | Create user — requires `X-Api-Key` header |

### Protected — JWT or API key (`DualAuthPlugin`)

| Method | Path | Description |
|--------|------|-------------|
| GET | `/conversations` | List active conversations |
| POST | `/conversations` | Create conversation (body: `name`, `config`, `systemInstruction`) |
| POST | `/conversations/{name}/messages` | Send message → `{ reply }` |
| DELETE | `/conversations/{name}` | Close conversation |

### Protected — JWT only (`AuthPlugin`)

| Method | Path | Description |
|--------|------|-------------|
| POST | `/api-key/generate` | Generate API key → one-time raw key |
| GET | `/api-key/list` | List all keys (hashes never exposed) |
| GET | `/api-key/info?key=lrtlm_...` | Get key metadata |
| DELETE | `/api-key/revoke` | Soft-revoke a key |

---

## Authentication

Two credential types, both passed as `Authorization: Bearer <token>`:

| Type | Prefix | Used for |
|------|--------|----------|
| JWT access token | `eyJ...` | Interactive users (login flow) |
| API key | `lrtlm_...` | Programmatic clients |

`/conversations` accepts either. `/api-key` accepts JWT only.

---

## Environment Variables (`.env`)

Copy `.env.example` to `.env` and fill in values before running.

| Variable | Description |
|----------|-------------|
| `X_API_KEY` | Master key required to create auth users via `POST /auth/user/create` |
| `JWT_SECRET` | HMAC-SHA256 signing secret for JWT tokens |

Real environment variables always take precedence over `.env` file values.

---

## Build & Run

### Prerequisites
- JDK 21+
- `.env` file configured (copy from `.env.example`)
- Model file(s) present under `model/`

### Build fat JAR

```bash
./gradlew buildFatJar
```

Output: `build/libs/aigateway-1.0-all.jar`

### Run fat JAR

```bash
./gradlew runFatJar
```

Or run the jar directly (useful for deployment):

```bash
java -jar build/libs/aigateway-1.0-all.jar
```

To override env vars at runtime without editing `.env`:

```bash
JWT_SECRET=mysecret X_API_KEY=mykey java -jar build/libs/aigateway-1.0-all.jar
```

The server starts on `http://0.0.0.0:8080`.

---

## Data Directory

At first run, `LMApplication` creates an app data directory via the `Service` base class (`appDirName = "lm_application"`). All persistent data lives there:

```
lm_application/
├── lm_application.db    # SQLite database (api keys, users, tokens)
└── (future: logs/, exports/)
```

---

## Implemented Features

- [x] User authentication (JWT — HS256, 15 min access / 7 day refresh, token rotation)
- [x] API key management (SHA-256 hashed, `lrtlm_` prefix, soft-revoke)
- [x] Conversation management (create, list, send message, delete)
- [x] Four builtin conversation presets (ASSISTANT, CODER, CONCISE, CREATIVE)
- [x] Dual-auth on conversation routes (JWT or API key)
- [x] `.env` file support with environment variable override
- [x] SQLite persistence via `DaoSqlite` (HikariCP pool)
- [x] Structured logging via `ILog` / `ILogImpl`

## Planned Features

- [ ] Conversation history persistence (SQLite messages table)
- [ ] Rate limiting and usage tracking per API key
- [ ] Multiple model support with dynamic loading/switching
- [ ] Tool execution framework with conversation binding
- [ ] Plugin architecture for external service integration (vector DBs, knowledge bases)
- [ ] Admin dashboard (SPA + SSE live metrics)
