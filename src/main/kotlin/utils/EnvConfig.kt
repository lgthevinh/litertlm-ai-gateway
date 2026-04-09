package org.thingai.app.aigateway.utils

import org.thingai.base.log.ILog
import java.io.File

/**
 * Loads key=value pairs from a `.env` file in the working directory.
 *
 * Rules:
 * - Lines starting with `#` are comments and are ignored.
 * - Blank lines are ignored.
 * - Values may optionally be quoted with `"` — quotes are stripped.
 * - If a key is already set as a real environment variable, the env var wins.
 *
 * Usage:
 * ```kotlin
 * EnvConfig.load()
 * val secret = EnvConfig["JWT_SECRET"] ?: error("JWT_SECRET not set")
 * ```
 */
object EnvConfig {

    private const val TAG = "EnvConfig"
    private const val ENV_FILE = ".env"

    private val values = mutableMapOf<String, String>()

    /**
     * Reads the `.env` file from [path] (defaults to project working directory).
     * Safe to call multiple times — reloads on each call.
     */
    fun load(path: String = ENV_FILE) {
        values.clear()

        val file = File(path)
        if (!file.exists()) {
            ILog.w(TAG, ".env file not found at '${file.absolutePath}' — using environment variables only")
            return
        }

        var loaded = 0
        file.forEachLine { raw ->
            val line = raw.trim()
            if (line.isBlank() || line.startsWith("#")) return@forEachLine

            val eqIdx = line.indexOf('=')
            if (eqIdx < 1) return@forEachLine

            val key   = line.substring(0, eqIdx).trim()
            val value = line.substring(eqIdx + 1).trim().removeSurrounding("\"")

            if (key.isNotBlank()) {
                values[key] = value
                loaded++
            }
        }

        ILog.i(TAG, "Loaded $loaded entries from $ENV_FILE")
    }

    /**
     * Returns the value for [key], preferring actual environment variables over `.env` file values.
     * Returns `null` if not found in either source.
     */
    operator fun get(key: String): String? =
        System.getenv(key)?.takeIf { it.isNotBlank() } ?: values[key]?.takeIf { it.isNotBlank() }

    /**
     * Returns the value for [key] or throws [IllegalStateException] if not set.
     */
    fun require(key: String): String =
        get(key) ?: error("Required env variable '$key' is not set. Add it to .env or set it as an environment variable.")
}
