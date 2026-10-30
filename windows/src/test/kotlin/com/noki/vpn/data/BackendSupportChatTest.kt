package com.noki.vpn.data

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.ResponseBody
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.util.concurrent.TimeUnit

class BackendSupportChatTest {
    @get:Rule val temporary = TemporaryFolder()
    private val message = """{"id":9,"content":"Здравствуйте","message_type":"outgoing","created_at":1790000000}"""

    @Test fun `chat requests use only Noki bearer and message pagination`() = runBlocking {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            assertEquals("Bearer noki-access", request.header("Authorization"))
            assertNull(request.header("api_access_token"))
            assertEquals("/v1/app/support/chat", request.url.encodedPath)
            assertEquals("7", request.url.queryParameter("before"))
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("""{"conversation_id":1,"status":"open","messages":[$message],"next_before":7}""".toResponseBody()).build()
        }.build()
        val page = BackendApiClient(client, "https://noki.example").supportChat("noki-access", 7)
        assertTrue(page.messages.single().isFromSupport)
        assertEquals(7L, page.nextBefore)
    }

    @Test fun `send contains text and UUID without Chatwoot identifiers or credentials`() = runBlocking {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            assertEquals("Bearer noki-access", request.header("Authorization"))
            assertEquals("/v1/app/support/chat/messages", request.url.encodedPath)
            assertEquals("POST", request.method)
            val body = Buffer().also { request.body!!.writeTo(it) }.readUtf8()
            val json = JSONObject(body)
            assertEquals(setOf("content", "client_message_id"), json.keys().asSequence().toSet())
            assertEquals("Hello", json.getString("content"))
            assertEquals("00c0ea35-ff5a-491f-90a5-18f63a940c65", json.getString("client_message_id"))
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(message.replace("outgoing", "incoming").toResponseBody()).build()
        }.build()
        val sent = BackendApiClient(client, "https://noki.example").sendSupportMessage("noki-access", "Hello", "00c0ea35-ff5a-491f-90a5-18f63a940c65")
        assertFalse(sent.isFromSupport)
        assertEquals(9L, sent.id)
    }

    @Test fun `invalid message directions fail instead of impersonating support`() {
        assertThrows(IllegalArgumentException::class.java) {
            JSONObject(message.replace("outgoing", "activity")).toBackendSupportMessage()
        }
        val empty = JSONObject("""{"conversation_id":1,"status":"open","messages":[],"next_before":null}""").toBackendSupportChat()
        assertTrue(empty.messages.isEmpty())
        assertNull(empty.nextBefore)
    }

    @Test fun `attachment parser permits only the account scoped Noki path`() {
        val file = JSONObject().put("id", 8).put("type", "image").put("name", "photo.png")
            .put("size", 3).put("mime", "image/png").put("content_path", "/app/support/chat/messages/9/attachments/8")
        val response = JSONObject(message).put("content", "").put("attachments", org.json.JSONArray().put(file))
        val attachment = response.toBackendSupportMessage().attachments.single()
        assertTrue(attachment.hasImagePreview)
        assertEquals("/app/support/chat/messages/9/attachments/8", attachment.contentPath)
        for (path in listOf("https://chatwoot.example/private/blob", "/app/support/chat/messages/10/attachments/8", "//evil.example/file")) {
            file.put("content_path", path)
            assertThrows(IllegalArgumentException::class.java) { response.toBackendSupportMessage() }
        }
    }

    @Test fun `multipart upload uses streaming client preserves UUID and permits an empty caption`() = runBlocking {
        val file = temporary.newFile("photo.png").apply { writeText("abc") }
        val control = OkHttpClient.Builder().addInterceptor { error("media used control-plane client") }.build()
        val streaming = OkHttpClient.Builder().callTimeout(30, TimeUnit.MINUTES).addInterceptor { chain ->
            val request = chain.request()
            assertEquals("/v1/app/support/chat/attachments", request.url.encodedPath)
            assertEquals("Bearer noki-access", request.header("Authorization"))
            assertTrue(chain.call().timeout().timeoutNanos() > TimeUnit.SECONDS.toNanos(30))
            val body = Buffer().also { request.body!!.writeTo(it) }.readUtf8()
            assertTrue(body.contains("name=\"attachment\"; filename=\"photo.png\""))
            assertTrue(body.contains("image/png"))
            assertTrue(body.contains("request-id"))
            assertTrue(body.contains("abc"))
            assertFalse(body.contains("api_access_token"))
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK").body(message.toResponseBody()).build()
        }.build()
        val api = BackendApiClient(control, "https://noki.example", streamingClient = streaming)
        assertEquals(9L, api.sendSupportAttachments("noki-access", "", "request-id", listOf(SupportAttachmentDraft(file.path, "photo.png", "image/png", file.length()))).id)
    }

    @Test fun `attachment download keeps bearer on Noki origin and rejects oversize without replacing a file`() = runBlocking {
        val attachment = BackendSupportAttachment(8, 9, "file", "reply.txt", 0, "text/plain")
        val destination = temporary.newFile("reply.txt").apply { writeText("cached") }
        val control = OkHttpClient.Builder().addInterceptor { error("download used control-plane client") }.build()
        var oversize = true
        val streaming = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            assertEquals("noki.example", request.url.host)
            assertEquals("/v1/app/support/chat/messages/9/attachments/8", request.url.encodedPath)
            assertEquals("Bearer noki-access", request.header("Authorization"))
            assertNull(request.header("api_access_token"))
            val body = if (oversize) object : ResponseBody() {
                override fun contentType(): MediaType = "application/octet-stream".toMediaType()
                override fun contentLength(): Long = SUPPORT_ATTACHMENT_MAX_BYTES + 1
                override fun source() = Buffer().writeUtf8("ignored")
            } else "".toResponseBody()
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK").body(body).build()
        }.build()
        val api = BackendApiClient(control, "https://noki.example", streamingClient = streaming)
        val error = runCatching { api.downloadSupportAttachment("noki-access", attachment, destination) }.exceptionOrNull()
        assertEquals("support_attachment_too_large", error?.message)
        assertEquals("cached", destination.readText())
        assertFalse(java.io.File(destination.parentFile, "${destination.name}.part").exists())
        oversize = false
        api.downloadSupportAttachment("noki-access", attachment, destination)
        assertEquals(0L, destination.length())
    }
}
