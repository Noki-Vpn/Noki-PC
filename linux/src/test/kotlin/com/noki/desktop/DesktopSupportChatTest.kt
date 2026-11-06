package com.noki.desktop

import com.noki.vpn.AuthSessionAttempt
import com.noki.vpn.data.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class DesktopSupportChatTest {
    private fun workflow(scope: CoroutineScope,
        load: suspend (AuthSessionAttempt, Long?) -> BackendSupportChat = { _, _ -> BackendSupportChat(1, "open", emptyList(), null) },
        send: suspend (AuthSessionAttempt, String, String, List<SupportAttachmentDraft>) -> BackendSupportMessage = { _, text, _, _ -> message(1, text) },
        current: (AuthSessionAttempt) -> Boolean = { true },
        stage: suspend (String) -> SupportAttachmentDraft = { error("no attachment expected") },
        download: suspend (AuthSessionAttempt, BackendSupportAttachment) -> String = { _, _ -> error("no download expected") },
        delete: (String) -> Unit = {},
        open: (String, String) -> Unit = { _, _ -> },
    ) = DesktopSupportChat(scope, { AppLanguage.RU }, { AuthSessionAttempt("token", 1) }, current,
        load, send, stage, download, open, { _, _ -> }, delete, { true })

    @Test fun `uncertain send retries with same identity and preserves draft`() = runBlocking {
        val ids = mutableListOf<String>()
        val chat = workflow(this, send = { _, text, id, _ ->
            ids += id
            if (ids.size == 1) throw java.io.IOException("network")
            message(3, text)
        })
        chat.open(); chat.updateDraft("help"); chat.send(); yield()
        assertEquals("help", chat.state.value.draft)
        assertNotNull(chat.state.value.sendError)
        chat.send(); yield()
        assertEquals(listOf(ids.first(), ids.first()), ids)
        assertEquals("", chat.state.value.draft)
        assertEquals(3L, chat.state.value.messages.single().id)
    }

    @Test fun `older pages merge by id without losing read status`() = runBlocking {
        val chat = workflow(this, load = { _, before ->
            if (before == null) BackendSupportChat(1, "open", listOf(message(3, "new")), 3)
            else BackendSupportChat(1, "open", listOf(message(1, "old"), message(3, "new").copy(status = "read")), null)
        })
        chat.open(); chat.refresh(); yield(); chat.refresh(older = true); yield()
        assertEquals(listOf(1L, 3L), chat.state.value.messages.map { it.id })
        assertEquals("read", chat.state.value.messages.last().status)
        assertNull(chat.state.value.nextBefore)
    }

    @Test fun `account invalidation discards late responses and draft`() = runBlocking {
        val pending = CompletableDeferred<BackendSupportChat>()
        val chat = workflow(this, load = { _, _ -> withContext(NonCancellable) { pending.await() } })
        chat.open(); chat.updateDraft("private"); chat.refresh(); yield()
        chat.invalidate()
        pending.complete(BackendSupportChat(1, "open", listOf(message(3, "private")), null)); yield()
        assertEquals(DesktopSupportChatState(), chat.state.value)
    }

    @Test fun `replacement conversation clears empty history and cached files but preserves the draft`() = runBlocking {
        for (older in listOf(false, true)) {
            val attachment = BackendSupportAttachment(8, 4, "file", "reply.txt", 3, "text/plain")
            val pending = SupportAttachmentDraft("private/draft.txt", "draft.txt", "text/plain", 3)
            var page = BackendSupportChat(1, "open", listOf(message(4, "old").copy(attachments = listOf(attachment))), 4)
            val deleted = mutableListOf<String>()
            val chat = workflow(this, load = { _, _ -> page }, stage = { pending },
                download = { _, _ -> "private/reply.txt" }, delete = { deleted += it })
            chat.open(); chat.refresh(); yield()
            chat.previewAttachment(attachment); yield()
            chat.updateDraft("unfinished")
            assertTrue(chat.beginAttachmentPick())
            chat.selectAttachments(listOf("picked")); yield()
            page = BackendSupportChat(2, "open", emptyList(), null)
            chat.refresh(older); yield()
            assertTrue(chat.state.value.messages.isEmpty())
            assertNull(chat.state.value.nextBefore)
            assertTrue(chat.state.value.attachmentFiles.isEmpty())
            assertEquals(listOf("private/reply.txt"), deleted)
            assertEquals("unfinished", chat.state.value.draft)
            assertEquals(listOf(pending), chat.state.value.pendingAttachments)
            page = BackendSupportChat(2, "open", listOf(message(2, "new conversation")), null)
            chat.refresh(); yield()
            assertEquals(listOf(2L), chat.state.value.messages.map { it.id })
        }
    }

    @Test fun `late delivery and download cannot restore a deleted conversation`() = runBlocking {
        val attachment = BackendSupportAttachment(8, 4, "file", "reply.txt", 3, "text/plain")
        var page = BackendSupportChat(1, "open", listOf(message(4, "old").copy(attachments = listOf(attachment))), null)
        val delivery = CompletableDeferred<BackendSupportMessage>()
        val download = CompletableDeferred<String>()
        val deleted = mutableListOf<String>()
        val opened = mutableListOf<String>()
        val keys = mutableListOf<String>()
        val chat = workflow(this, load = { _, _ -> page },
            send = { _, text, id, _ ->
                keys += id
                if (keys.size == 1) withContext(NonCancellable) { delivery.await() } else message(6, text)
            }, download = { _, _ -> withContext(NonCancellable) { download.await() } },
            delete = { deleted += it }, open = { path, _ -> opened += path })
        chat.open(); chat.refresh(); yield()
        chat.openAttachment(attachment); chat.updateDraft("unfinished"); chat.send(); yield()
        page = BackendSupportChat(2, "open", emptyList(), null)
        chat.refresh(); yield()
        delivery.complete(message(5, "unfinished")); download.complete("private/late.txt"); yield(); yield()
        assertTrue(chat.state.value.messages.isEmpty())
        assertTrue(chat.state.value.attachmentFiles.isEmpty())
        assertFalse(chat.state.value.isSending)
        assertTrue(chat.state.value.attachmentLoading.isEmpty())
        assertTrue(opened.isEmpty())
        assertEquals(listOf("private/late.txt"), deleted)
        assertEquals("unfinished", chat.state.value.draft)
        chat.send(); yield()
        assertEquals(listOf(6L), chat.state.value.messages.map { it.id })
        assertNotEquals(keys[0], keys[1])
    }

    companion object {
        private fun message(id: Long, text: String) = BackendSupportMessage(id, text, "incoming", id)
    }
}
