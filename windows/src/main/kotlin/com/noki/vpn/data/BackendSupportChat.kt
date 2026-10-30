package com.noki.vpn.data

import org.json.JSONObject

const val SUPPORT_ATTACHMENT_MAX_BYTES = 40L * 1024 * 1024
const val SUPPORT_ATTACHMENT_MAX_COUNT = 10

data class SupportAttachmentDraft(val path: String, val name: String, val mime: String, val size: Long)

fun canOpenSupportAttachment(mime: String): Boolean = mime.startsWith("video/") || mime.startsWith("audio/") ||
    mime in setOf("image/jpeg", "image/png", "image/gif", "image/webp", "image/bmp", "image/heic", "image/heif", "image/avif", "application/pdf", "text/plain")

data class BackendSupportAttachment(
    val id: Long,
    val messageId: Long,
    val type: String,
    val name: String,
    val size: Long,
    val mime: String,
) {
    val contentPath: String get() = "/app/support/chat/messages/$messageId/attachments/$id"
    val hasImagePreview: Boolean get() = type == "image" && mime in setOf("image/jpeg", "image/png", "image/gif", "image/webp")
}

data class BackendSupportMessage(
    val id: Long,
    val content: String,
    val messageType: String,
    val createdAt: Long,
    val attachments: List<BackendSupportAttachment> = emptyList(),
    val status: String = "sent",
) {
    val isFromSupport: Boolean get() = messageType == "outgoing"
}

data class BackendSupportChat(
    val conversationId: Long,
    val status: String,
    val messages: List<BackendSupportMessage>,
    val nextBefore: Long?,
)

internal fun JSONObject.toBackendSupportMessage(): BackendSupportMessage {
    val type = getString("message_type")
    require(type == "incoming" || type == "outgoing") { "invalid_support_message_type" }
    val messageId = getLong("id").also { require(it > 0) }
    val files = optJSONArray("attachments")
    return BackendSupportMessage(
        id = messageId,
        content = getString("content"),
        messageType = type,
        createdAt = getLong("created_at").also { require(it >= 0) },
        status = optString("status", "sent").also { require(it in setOf("sent", "read")) },
        attachments = (0 until (files?.length() ?: 0)).map { index ->
            val file = files!!.getJSONObject(index)
            BackendSupportAttachment(
                id = file.getLong("id").also { require(it > 0) },
                messageId = messageId,
                type = file.getString("type").also { require(it in setOf("image", "video", "file")) },
                name = file.getString("name").take(180),
                size = file.getLong("size").also { require(it in 0..SUPPORT_ATTACHMENT_MAX_BYTES) },
                mime = file.getString("mime").lowercase().also { require('\r' !in it && '\n' !in it) },
            ).also { require(file.getString("content_path") == it.contentPath) { "support_attachment_path_invalid" } }
        },
    )
}

internal fun JSONObject.toBackendSupportChat(): BackendSupportChat {
    val rows = getJSONArray("messages")
    return BackendSupportChat(
        conversationId = getLong("conversation_id"),
        status = getString("status"),
        messages = (0 until rows.length()).map { rows.getJSONObject(it).toBackendSupportMessage() },
        nextBefore = if (isNull("next_before")) null else getLong("next_before").also { require(it > 0) },
    )
}
