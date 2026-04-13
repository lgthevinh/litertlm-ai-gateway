package org.thingai.app.aigateway.lm.attachment

/**
 * Type of a multimodal attachment on a conversation message.
 */
enum class AttachmentType {
    IMAGE, AUDIO
}

/**
 * A single attachment reference stored alongside a message.
 *
 * Serialised to/from the [org.thingai.app.aigateway.lm.entity.LMStoredMessage.attachments] column as `"type:filename"` entries.
 * Example column value: `"image:0_0.jpg,audio:0_1.wav"`.
 *
 * @param type     Whether this is an image or audio file.
 * @param filename The filename on disk (relative to the conversation's attachments dir).
 */
data class AttachmentRef(
    val type: AttachmentType,
    val filename: String
)

// ── Serialisation helpers ────────────────────────────────────────────────────

/**
 * Converts a list of [AttachmentRef] to the comma-separated DB column value.
 * Returns `null` for an empty list (text-only message).
 */
fun List<AttachmentRef>.toColumnValue(): String? =
    ifEmpty { return null }
        .joinToString(",") { "${it.type.name.lowercase()}:${it.filename}" }

/**
 * Parses the [org.thingai.app.aigateway.lm.entity.LMStoredMessage.attachments] column value back into a list of [AttachmentRef].
 * Returns an empty list for `null` or blank strings.
 */
fun String?.toAttachmentRefs(): List<AttachmentRef> {
    if (isNullOrBlank()) return emptyList()
    return split(",").mapNotNull { token ->
        val parts = token.split(":", limit = 2)
        if (parts.size != 2) return@mapNotNull null
        val type = runCatching { AttachmentType.valueOf(parts[0].trim().uppercase()) }.getOrNull()
            ?: return@mapNotNull null
        AttachmentRef(type = type, filename = parts[1].trim())
    }
}
