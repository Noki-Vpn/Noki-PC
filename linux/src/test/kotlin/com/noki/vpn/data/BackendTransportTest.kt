package com.noki.vpn.data

import com.noki.vpn.BuildConfig
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.BufferedSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BackendTransportTest {
    @Test fun desktopNotificationsUseTheHostPlatformEndpoint() = runBlocking {
        val requests = mutableListOf<Request>()
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            requests += chain.request()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("""{"notifications":[{"id":"notice","title":"Notice","message":"Message","created_at":"2026-11-01T00:00:00Z"}]}"""
                    .toResponseBody("application/json".toMediaType())).build()
        }.build()
        try {
            val api = BackendApiClient(http, "https://example.invalid")
            assertEquals(listOf("notice"), api.appNotifications("access", "device", "key").map { it.id })
            api.acknowledgeAppNotifications("access", "device", "key", listOf("notice"))
            val expected = if (com.noki.desktop.DesktopBuild.IS_LINUX) listOf("/v1/app/notifications")
                else listOf("/v1/app/desktop-notifications", "/v1/app/desktop-notifications/ack")
            assertEquals(expected, requests.map { it.url.encodedPath })
            assertTrue(requests.all { it.header("X-Device-Id") == "device" && it.header("X-Device-Key") == "key" })
        } finally { http.connectionPool.evictAll(); http.dispatcher.executorService.shutdownNow() }
    }


    @Test
    fun `VPN access sends the release version with authenticated device proof`() = runBlocking {
        var request: Request? = null
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            request = chain.request()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("{\"can_connect\":true}".toResponseBody("application/json".toMediaType())).build()
        }.build()

        val access = BackendApiClient(client, "https://api.example").vpnAccess("access", "device", "proof")
        val sent = checkNotNull(request)

        assertTrue(access.canConnect)
        assertEquals("/v1/vpn/access", sent.url.encodedPath)
        assertEquals("Bearer access", sent.header("Authorization"))
        assertEquals("device", sent.header("X-Device-Id"))
        assertEquals("proof", sent.header("X-Device-Key"))
        assertEquals(BuildConfig.VERSION_CODE.toString(), sent.header("X-Noki-Version-Code"))
    }

    @Test
    fun `backend control plane and streaming retain default protocols`() {
        val controlPlane = defaultBackendControlPlaneClient()
        val streaming = backendStreamingClient(controlPlane)

        assertEquals(OkHttpClient().protocols, controlPlane.protocols)
        assertEquals(controlPlane.protocols, streaming.protocols)
    }

    @Test
    fun `control plane and streaming both have finite total deadlines`() {
        val controlPlane = defaultBackendControlPlaneClient()
        val streaming = backendStreamingClient(controlPlane)

        assertEquals(30_000, controlPlane.callTimeoutMillis)
        assertEquals(30 * 60 * 1_000, streaming.callTimeoutMillis)
        assertEquals(controlPlane.connectTimeoutMillis, streaming.connectTimeoutMillis)
        assertEquals(controlPlane.readTimeoutMillis, streaming.readTimeoutMillis)
        assertEquals(controlPlane.writeTimeoutMillis, streaming.writeTimeoutMillis)
    }

    @Test
    fun `object array and stream expose identical backend error`() {
        val transport = BackendTransport(errorClient(), "https://api.example.com/v1")
        val request = Request.Builder().url("https://api.example.com/v1/test").build()

        val errors = listOf(
            assertBackendError { runBlocking { transport.jsonObject(request) } },
            assertBackendError { runBlocking { transport.jsonArray(request) } },
            assertBackendError { runBlocking { transport.stream(request) {} } },
        )

        errors.forEach {
            assertEquals(422, it.statusCode)
            assertEquals(3_000L, it.retryAfterMillis)
            assertEquals("email: invalid", it.message)
        }
    }

    @Test
    fun `request outside configured origin or path is rejected before network`() {
        val transport = BackendTransport(OkHttpClient(), "https://api.example.com/v1")

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { transport.jsonObject(Request.Builder().url("https://evil.example/v1/test").build()) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { transport.jsonObject(Request.Builder().url("https://api.example.com/other").build()) }
        }
    }

    @Test
    fun `cancelling coroutine cancels active okhttp call`() = runBlocking {
        val requestStarted = CountDownLatch(1)
        val callCancelled = CountDownLatch(1)
        val releaseInterceptor = AtomicBoolean(false)
        val client = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                requestStarted.countDown()
                while (!releaseInterceptor.get()) {
                    if (chain.call().isCanceled()) {
                        callCancelled.countDown()
                        throw IOException("cancelled")
                    }
                    Thread.sleep(5L)
                }
                throw IOException("test interceptor released")
            })
            .build()
        val transport = BackendTransport(client, "https://api.example.com/v1")
        val request = Request.Builder().url("https://api.example.com/v1/slow").build()
        val requestJob = launch(Dispatchers.IO) {
            runCatching { transport.jsonObject(request) }
        }

        assertTrue("The real OkHttp call did not start", requestStarted.await(1L, TimeUnit.SECONDS))
        requestJob.cancel()
        val cancellationReachedCall = try {
            callCancelled.await(1L, TimeUnit.SECONDS)
        } finally {
            releaseInterceptor.set(true)
            requestJob.cancelAndJoin()
        }

        assertTrue("Coroutine cancellation must cancel the active OkHttp call", cancellationReachedCall)
    }

    @Test
    fun `declared oversized json response is rejected`() {
        val body = ByteArray(MAX_JSON_RESPONSE_BYTES + 1) { 'x'.code.toByte() }
            .toResponseBody("application/json".toMediaType())
        val transport = BackendTransport(responseClient(body), "https://api.example.com/v1")
        val request = Request.Builder().url("https://api.example.com/v1/oversized").build()

        val failure = assertThrows(BackendException::class.java) {
            runBlocking { transport.jsonObject(request) }
        }

        assertEquals("Backend response is too large", failure.message)
    }

    @Test
    fun `streamed oversized json response is rejected when length is unknown`() {
        val bytes = ByteArray(MAX_JSON_RESPONSE_BYTES + 1) { 'x'.code.toByte() }
        val body = object : ResponseBody() {
            private val content = Buffer().write(bytes)

            override fun contentType() = "application/json".toMediaType()

            override fun contentLength(): Long = -1L

            override fun source(): BufferedSource = content
        }
        val transport = BackendTransport(responseClient(body), "https://api.example.com/v1")
        val request = Request.Builder().url("https://api.example.com/v1/oversized").build()

        val failure = assertThrows(BackendException::class.java) {
            runBlocking { transport.jsonObject(request) }
        }

        assertEquals("Backend response is too large", failure.message)
    }

    private fun assertBackendError(block: () -> Unit): BackendException =
        assertThrows(BackendException::class.java, block)

    private fun errorClient(): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(Interceptor { chain ->
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(422)
                .message("validation")
                .header("Retry-After", "3")
                .body(
                    """{"detail":[{"loc":["body","email"],"msg":"invalid"}]}"""
                        .toResponseBody("application/json".toMediaType()),
                )
                .build()
        })
        .build()

    private fun responseClient(body: ResponseBody): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor { chain ->
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(body)
                .build()
        }
        .build()

    private companion object {
        const val MAX_JSON_RESPONSE_BYTES = 2 * 1024 * 1024
    }
}
