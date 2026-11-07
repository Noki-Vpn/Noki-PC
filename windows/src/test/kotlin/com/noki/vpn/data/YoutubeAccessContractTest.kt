package com.noki.vpn.data

import java.time.Instant
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class YoutubeAccessContractTest {
    @Test fun `fixed YouTube rejects other selected transports and IP overrides`() {
        for (mutate in listOf<(JSONObject) -> Unit>(
            { it.getJSONObject("endpoint").put("security", "tls") },
            { it.getJSONObject("endpoint").put("entry_port", 8443) },
            { it.getJSONObject("endpoint").put("connect_ip", "192.0.2.50") },
            { it.getJSONObject("endpoint").put("server_name", "other.example") },
            { it.getJSONObject("endpoint").put("flow", "") },
            { it.getJSONObject("endpoint").put("path", "/tunnel") },
            { it.getJSONObject("endpoint").put("request_host", "youtube.example") },
            { it.getJSONObject("endpoint").put("detour", "proxy") },
            { it.getJSONObject("endpoint").put("enable_mux", true) },
            { it.getJSONObject("endpoint").put("random_user_agent", true) },
            { it.getJSONObject("endpoint").put("transport_mode", "packet-up") },
            { it.getJSONObject("endpoint").put("entry_host", "192.0.2.50").put("server_name", "192.0.2.50") },
            { it.put("vpn_secret", "not-a-uuid") },
        )) assertThrows(Exception::class.java) { BackendYoutubeAccessJsonParser.parse(selected().also(mutate), now) }
    }

    private val now = Instant.parse("2030-01-01T00:00:00Z")
    private val deviceId = "22222222-2222-4222-8222-222222222222"
    private val keyId = "11111111-1111-4111-8111-111111111111"
    private val nonce = "nonce-1234567890123456"
    private val signature = "signature".repeat(8)

    @Test fun `admission uses exact body fresh proof headers and capability-only revoke`() = runBlocking {
        val requests = mutableListOf<Request>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            requests += chain.request()
            val body = if (chain.request().method == "DELETE") JSONObject() else selected()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(body.toString().toResponseBody("application/json".toMediaType())).build()
        }.build()
        val api = BackendApiClient(client, "https://api.example")
        val request = YoutubeAccessRequest(deviceId, "device-key", nonce, signature,
            "33333333-3333-4333-8333-333333333333")
        val access = api.createYoutubeAccess("access", request)
        api.pollYoutubeAccess("access", deviceId, "device-key", nonce, signature, access.grantId)
        api.renewYoutubeAccess("access", deviceId, "device-key", nonce, signature, access)
        api.revokeYoutubeAccess(access.grantId, access.revokeToken)

        assertEquals(listOf("POST", "GET", "POST", "DELETE"), requests.map { it.method })
        val create = JSONObject(Buffer().also { requests[0].body!!.writeTo(it) }.readUtf8())
        assertEquals(setOf("request_id", "device_id", "device_key", "device_nonce", "device_signature"), create.keys().asSequence().toSet())
        assertEquals(request.requestId, create.getString("request_id"))
        assertFalse(create.has("public_key_id"))
        assertFalse(create.has("primary_endpoint_code"))
        assertEquals("/v1/vpn/youtube-access", requests[0].url.encodedPath)
        for (sent in requests.take(3)) {
            assertEquals("fixed_reality_tcp_v1", sent.header("X-Youtube-Transport"))
            assertEquals("Bearer access", sent.header("Authorization"))
            assertEquals(deviceId, sent.header("X-Device-Id"))
        }
        for (sent in requests.subList(1, 3)) {
            assertEquals(deviceId, sent.url.queryParameter("device_id"))
            assertEquals("device-key", sent.header("X-Device-Key"))
            assertEquals(nonce, sent.header("X-Device-Nonce"))
            assertEquals(signature, sent.header("X-Device-Signature"))
        }
        assertEquals("/v1/vpn/youtube-access/${access.grantId}/renew", requests[2].url.encodedPath)
        assertEquals("{\"generation\":1}", Buffer().also { requests[2].body!!.writeTo(it) }.readUtf8())
        val revoke = requests.last()
        assertEquals(null, revoke.url.query)
        assertEquals(null, revoke.header("Authorization"))
        assertEquals(null, revoke.header("X-Device-Id"))
        assertEquals(null, revoke.header("X-Device-Nonce"))
        assertNull(revoke.header("X-Youtube-Transport"))
        assertEquals(access.revokeToken, revoke.header("X-Youtube-Revoke-Token"))
    }

    @Test fun `pending and blocked expose no connection material and blocked may be expired`() {
        val pending = base("pending")
        assertNull(BackendYoutubeAccessJsonParser.parse(pending, now).endpoint)
        val blocked = base("blocked").put("expires_at", now.minusSeconds(1).toString())
        assertEquals("blocked", BackendYoutubeAccessJsonParser.parse(blocked, now).status)
        for (field in listOf("wireguard", "public_key_id", "endpoint", "vpn_secret")) {
            assertThrows(IllegalArgumentException::class.java) {
                BackendYoutubeAccessJsonParser.parse(base("pending").put(field, "forbidden"), now)
            }
        }
    }

    @Test fun `ready validates version generation expiry and fixed payload shape`() {
        assertEquals("selected_transport", BackendYoutubeAccessJsonParser.parse(selected(), now).mode)
        for (mutate in listOf<(JSONObject) -> Unit>(
            { it.put("version", "1") }, { it.put("generation", "1") }, { it.put("generation", 0) },
            { it.put("mode", "wireguard") }, { it.put("unexpected", true) }, { it.put("grant_id", "invalid") },
            { it.put("expires_at", now.toString()) }, { it.put("expires_at", "2030-01-01T00:02:00") },
            { it.put("revoke_token", "bad") }, { it.put("public_key_id", deviceId) }, { it.put("nokinv_device_id", deviceId) },
            { it.put("vpn_username", "legacy-user") }, { it.remove("endpoint") },
        )) assertThrows(Exception::class.java) { BackendYoutubeAccessJsonParser.parse(selected().also(mutate), now) }
    }

    @Test fun `selected transport reuses endpoint policy and rejects insecure or unsupported material`() {
        val access = BackendYoutubeAccessJsonParser.parse(selected(), now)
        assertEquals("vless", access.endpoint!!.proxyType)
        assertEquals("11111111-1111-4111-8111-111111111111", access.vpnSecret)
        for (transport in listOf("raw", "tcp")) {
            val parsed = BackendYoutubeAccessJsonParser.parse(selected().apply {
                getJSONObject("endpoint").put("transport", transport)
            }, now)
            val connection = com.noki.vpn.vpn.YoutubeConnectionConfig.fromReady(parsed)
                as com.noki.vpn.vpn.YoutubeConnectionConfig.SelectedTransport
            assertEquals("tcp", connection.profile.transport)
            assertEquals("11111111-1111-4111-8111-111111111111", connection.profile.uuid)
        }
        for (mutate in listOf<(JSONObject) -> Unit>(
            { it.getJSONObject("endpoint").put("allow_insecure", true) },
            { it.getJSONObject("endpoint").put("allow_insecure", "false") },
            { it.getJSONObject("endpoint").put("canary_only", true) },
            { it.getJSONObject("endpoint").put("entry_port", 0) },
            { it.getJSONObject("endpoint").put("proxy_type", "nokinv") },
            { it.getJSONObject("endpoint").remove("security") },
            { it.put("vpn_secret", "") }, { it.put("public_key_id", keyId) },
        )) assertThrows(Exception::class.java) { BackendYoutubeAccessJsonParser.parse(selected().also(mutate), now) }
    }

    @Test fun `request validation forbids malformed identity or proof before network`() {
        val request = YoutubeAccessRequest(deviceId, null, nonce, signature,
            "33333333-3333-4333-8333-333333333333")
        assertFalse(request.toBackendJson().has("public_key_id"))
        for (invalid in listOf(request.copy(deviceId = "bad"), request.copy(deviceNonce = "short"),
            request.copy(deviceSignature = "short"))) {
            assertThrows(IllegalArgumentException::class.java) { invalid.toBackendJson() }
        }
    }

    private fun base(status: String) = JSONObject().put("version", 1).put("mode", "selected_transport")
        .put("status", status).put("grant_id", "44444444-4444-4444-8444-444444444444")
        .put("generation", 1).put("expires_at", "2099-01-01T00:02:00Z").put("revoke_token", "r".repeat(43))

    private fun selected() = base("ready").put("mode", "selected_transport").put("vpn_secret", "11111111-1111-4111-8111-111111111111")
        .put("endpoint", JSONObject().put("code", "youtube-tls").put("node_id", deviceId)
            .put("entry_host", "youtube.example").put("entry_port", 443).put("server_name", "youtube.example")
            .put("proxy_type", "vless").put("transport", "tcp").put("security", "reality").put("flow", "xtls-rprx-vision")
            .put("public_key", "p".repeat(43)).put("short_id", "0123456789abcdef"))
}
