package dev.nucleusframework.window.tao.scene

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.coroutines.EmptyCoroutineContext

class SceneMainDispatcherTest {
    @Test
    fun `scene and accessibility work complete without driving a frame`() {
        val dispatcher = SceneMainDispatcher()
        val completed = CountDownLatch(2)
        val events = CopyOnWriteArrayList<String>()
        // The real Tao dispatcher's pre-loop executor exercises off-frame delivery;
        // this test never creates a window, starts a native loop or calls a render drain.
        dispatcher.dispatch(EmptyCoroutineContext, Runnable {
            events += "coroutine"
            completed.countDown()
        })
        dispatcher.enqueue(Runnable {
            events += "accessibility"
            completed.countDown()
        })
        assertTrue("Scene work waited for a frame", completed.await(5, TimeUnit.SECONDS))
        assertEquals(listOf("coroutine", "accessibility"), events)
    }
}
