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
    ) = DesktopSupportChat(scope, { AppLanguage.RU }, { AuthSessionAttempt("token", 1) }, current,
        load, send, { error("no attachment expected") }, { _, _ -> error("no download expected") },
        { _, _ -> }, { _, _ -> }, {}, { true })

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

    companion object {
        private fun message(id: Long, text: String) = BackendSupportMessage(id, text, "incoming", id)
    }
}
