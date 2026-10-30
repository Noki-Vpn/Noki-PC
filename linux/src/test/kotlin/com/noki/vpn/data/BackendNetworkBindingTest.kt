package com.noki.vpn.data

import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.SocketFactory
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONObject

class BackendNetworkBindingTest {
    @Test
    fun desktopLogsKeepTheirWindowsPlatform() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        var payload: JSONObject? = null
        server.createContext("/") { exchange ->
            payload = JSONObject(exchange.requestBody.bufferedReader().use { it.readText() })
            val response = "{}".toByteArray()
            exchange.sendResponseHeaders(200, response.size.toLong())
            exchange.responseBody.use { it.write(response) }
        }
        server.start()
        try {
            BackendApiClient(baseUrl = "http://127.0.0.1:${server.address.port}")
                .uploadAppLogs("token", "device", "key", "PC", "sample", platform = "windows")
            assertEquals("windows", payload?.getString("platform"))
            assertEquals("sample", payload?.getString("content"))
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun boundClientUsesProvidedSocketFactoryAndDns() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/payments/config") { exchange ->
            val body = """{"configured":true,"checkout_enabled":true,"methods":[]}""".toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        val sockets = RecordingSocketFactory()
        val resolved = mutableListOf<String>()
        try {
            val api = BackendApiClient(baseUrl = "http://bound.invalid:${server.address.port}")
                .onNetwork(sockets) { host ->
                    resolved += host
                    listOf(InetAddress.getByName("127.0.0.1"))
                }
            assertTrue(api.paymentConfig().configured)
            assertEquals(listOf("bound.invalid"), resolved)
            assertTrue(sockets.created > 0)
        } finally {
            server.stop(0)
        }
    }

    private class RecordingSocketFactory : SocketFactory() {
        var created = 0
        override fun createSocket(): Socket = Socket().also { created++ }
        override fun createSocket(host: String, port: Int): Socket = Socket(host, port)
        override fun createSocket(host: String, port: Int, local: InetAddress, localPort: Int): Socket =
            Socket(host, port, local, localPort)
        override fun createSocket(host: InetAddress, port: Int): Socket = Socket(host, port)
        override fun createSocket(host: InetAddress, port: Int, local: InetAddress, localPort: Int): Socket =
            Socket(host, port, local, localPort)
    }
}
