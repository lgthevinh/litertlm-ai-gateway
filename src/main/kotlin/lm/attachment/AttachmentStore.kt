package org.thingai.app.aigateway.lm.attachment

import org.thingai.base.log.ILog
import java.io.File

/**
 * Manages multimodal attachment files on disk.
 *
 * Attachments are stored under `{appDir}/attachments/{conversationName}/`
 * with deterministic filenames: `{seq}_{index}.{ext}` (e.g. `3_0.jpg`, `3_1.wav`).
 *
 * On history replay, absolute paths are resolved so the engine can load files
 * via [com.google.ai.edge.litertlm.Content.ImageFile] / [Content.AudioFile].
 *
 * @param appDir The application data directory (e.g. `lm_application/`).
 */
class AttachmentStore(private val appDir: File) {

    companion object {
        private const val TAG = "AttachmentStore"
    }

    /**
     * Returns the directory for a conversation's attachments, creating it if needed.
     */
    private fun convDir(convName: String): File =
        File(appDir, "attachments/$convName").also { if (!it.exists()) it.mkdirs() }

    /**
     * Saves raw bytes to disk as an attachment file.
     *
     * @param convName Conversation name.
     * @param seq      Message sequence number (used in the filename).
     * @param index    Index within this message's attachments (0, 1, 2...).
     * @param ext      File extension without dot (e.g. "jpg", "wav").
     * @param bytes    Raw file content.
     * @return The filename written (e.g. "3_0.jpg").
     */
    fun save(convName: String, seq: Int, index: Int, ext: String, bytes: ByteArray): String {
        val sanitizedExt = ext.lowercase().filter { it.isLetterOrDigit() }.take(8).ifEmpty { "bin" }
        val filename = "${seq}_${index}.$sanitizedExt"
        val file = File(convDir(convName), filename)
        file.writeBytes(bytes)
        ILog.d(TAG, "save: '$convName/$filename' (${bytes.size} bytes)")
        return filename
    }

    /**
     * Resolves the absolute path for an attachment file.
     * Used during history replay to build [Content.ImageFile] / [Content.AudioFile].
     */
    fun absolutePath(convName: String, filename: String): String =
        File(convDir(convName), filename).absolutePath

    /**
     * Deletes all attachment files for a conversation.
     * Called when a conversation is deleted.
     */
    fun deleteConversation(convName: String) {
        val dir = File(appDir, "attachments/$convName")
        if (dir.exists()) {
            val deleted = dir.deleteRecursively()
            ILog.i(TAG, "deleteConversation: '$convName' attachments ${if (deleted) "deleted" else "failed to delete"}")
        }
    }
}
