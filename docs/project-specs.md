## Project Overview
LiteRTLM AI Gateway is a Kotlin/JVM service that exposes a lightweight HTTP entrypoint and initializes a local LiteRTLM engine for on-device inference. The codebase is intentionally minimal and is structured to grow into a gateway with session and conversation management.

### Folder Layout
- `src/main/kotlin/Main.kt` - Ktor server entrypoint, wires routing and engine configuration.
- `src/main/kotlin/api/route/Route.kt` - HTTP route definitions.
- `src/main/kotlin/engine/LiteRTLMEngine.kt` - LiteRTLM engine wrapper and initialization.
- `src/main/kotlin/engine/handler/` - session/conversation handling scaffolding.
- `build.gradle.kts` - Gradle Kotlin DSL build with dependencies.

### Tech Stack
- Kotlin JVM (2.3.10), Gradle Kotlin DSL, JDK 21 toolchain.
- Ktor server (core, Netty) with SLF4J Simple logging.
- Google AI Edge LiteRTLM JVM SDK.

### Current Features
- Embedded Ktor server on port 8080 with a basic `/` route.
- LiteRTLM engine configuration (model path, CPU backend) and async initialization.
- Initial scaffolding for session and conversation handlers.
