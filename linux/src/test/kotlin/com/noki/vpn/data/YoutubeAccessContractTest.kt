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
    @Test fun `selected Naive sidecar carries separate username and password without changing primary credentials`() {
        fun payload() = selected().put("vpn_username", "youtube-user").apply {
            getJSONObject("endpoint").put("proxy_type", "naive").put("transport", "naive")
        }
        val access = BackendYoutubeAccessJsonParser.parse(payload(), now)
        val primary = VlessProfile(endpointCode = "ro-naive", proxyType = "naive", transport = "naive", security = "tls",
            host = "192.0.2.8", serverName = "main.example", vpnUsername = "main-user", uuid = "main-password")
        val connection = com.noki.vpn.vpn.YoutubeConnectionConfig.fromReady(access, primary, keyId, ByteArray(32))
            as com.noki.vpn.vpn.YoutubeConnectionConfig.SelectedTransport
        assertEquals("youtube-user", connection.profile.vpnUsername)
        assertEquals("youtube-secret", connection.profile.uuid)
        assertEquals("main-user", primary.vpnUsername)
        val outbound = JSONObject(com.noki.vpn.vpn.VpnConfigFactory.buildProbe(connection.profile))
            .getJSONArray("outbounds").getJSONObject(0)
        assertEquals("naive", outbound.getString("type"))
        assertEquals("youtube-user", outbound.getString("username"))
        assertEquals("youtube-secret", outbound.getString("password"))
        for (mutate in listOf<(JSONObject) -> Unit>({ it.remove("vpn_username") },
            { it.put("vpn_username", "") }, { it.put("vpn_username", "bad\nuser") })) {
            assertThrows(Exception::class.java) { BackendYoutubeAccessJsonParser.parse(payload().also(mutate), now) }
        }
        assertThrows(Exception::class.java) { BackendYoutubeAccessJsonParser.parse(selected().put("vpn_username", "unexpected"), now) }
    }

    @Test fun `selected NV sidecar validates device credentials and preserves the main NV outbound`() {
        fun payload(): JSONObject = selected().put("vpn_secret", "A".repeat(43))
            .put("nokinv_device_id", deviceId).put("nokinv_credential_generation", 3).apply {
                getJSONObject("endpoint").put("proxy_type", "nokinv").put("transport", "nokinv").put("path", "/tunnel")
            }
        val access = BackendYoutubeAccessJsonParser.parse(payload(), now)
        assertEquals(deviceId, access.nokinvDeviceId)
        assertEquals(3, access.nokinvCredentialGeneration)
        val primaryToken = "B".repeat(42) + "A"
        val primary = VlessProfile(endpointCode = "main-nv", proxyType = "nokinv", transport = "nokinv", security = "tls",
            host = "8.8.8.8", serverName = "main.example", path = "/tunnel", nokinvToken = primaryToken,
            nokinvCredentialGeneration = 7, nokinvMode = "gaming")
        val connection = com.noki.vpn.vpn.YoutubeConnectionConfig.fromReady(access, primary, keyId, ByteArray(32), deviceId)
            as com.noki.vpn.vpn.YoutubeConnectionConfig.SelectedTransport
        assertEquals("", connection.profile.uuid)
        assertEquals("A".repeat(43), connection.profile.nokinvToken)
        assertEquals("gaming", connection.profile.nokinvMode)
        val geosite = com.noki.vpn.vpn.GeositeAssetTest.asset(
            "CATEGORY-RU" to listOf(com.noki.vpn.vpn.GeositeAssetTest.domain(2, "ru.example")),
            "YOUTUBE" to listOf(com.noki.vpn.vpn.GeositeAssetTest.domain(2, "youtube.com")))
        val base = JSONObject(com.noki.vpn.vpn.VpnConfigFactory.build(primary, AdvancedSettings(gamingModeEnabled = true), geosite))
        val unchangedMain = base.toString()
        val config = com.noki.vpn.vpn.VpnConfigFactory.withYoutube(base, connection, geosite)
        val outbounds = config.getJSONArray("outbounds")
        val main = (0 until outbounds.length()).map(outbounds::getJSONObject).single { it.getString("tag") == "proxy" }
        val sidecar = (0 until outbounds.length()).map(outbounds::getJSONObject).single { it.getString("tag") == "youtube-ru" }
        assertEquals(primaryToken, main.getString("token"))
        assertEquals(7, main.getInt("credential_generation"))
        assertEquals("A".repeat(43), sidecar.getString("token"))
        assertEquals(3, sidecar.getInt("credential_generation"))
        assertEquals(unchangedMain, base.toString())
        assertEquals(primaryToken, primary.nokinvToken)
        for (mode in listOf("quic")) {
            val manual = primary.copy(transportMode = mode, nokinvMode = "balanced")
            val manualConnection = com.noki.vpn.vpn.YoutubeConnectionConfig.fromReady(access, manual, keyId, ByteArray(32), deviceId)
                as com.noki.vpn.vpn.YoutubeConnectionConfig.SelectedTransport
            assertEquals(mode, manualConnection.profile.transportMode)
            val manualBase = JSONObject(com.noki.vpn.vpn.VpnConfigFactory.build(manual, AdvancedSettings(), geosite))
            val manualConfig = com.noki.vpn.vpn.VpnConfigFactory.withYoutube(manualBase, manualConnection, geosite)
            val nativeOutbounds = manualConfig.getJSONArray("outbounds")
            for (tag in listOf("proxy", "youtube-ru")) {
                assertEquals(mode, (0 until nativeOutbounds.length()).map(nativeOutbounds::getJSONObject)
                    .single { it.getString("tag") == tag }.getString("transport"))
            }
            assertThrows(IllegalArgumentException::class.java) {
                val incompatible = BackendYoutubeAccessJsonParser.parse(payload().apply {
                    getJSONObject("endpoint").put("transport_mode", "tcp")
                }, now)
                com.noki.vpn.vpn.YoutubeConnectionConfig.fromReady(incompatible, manual, keyId, ByteArray(32), deviceId)
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            com.noki.vpn.vpn.YoutubeConnectionConfig.fromReady(access, primary, keyId, ByteArray(32), keyId)
        }
        for (mutate in listOf<(JSONObject) -> Unit>(
            { it.remove("nokinv_device_id") }, { it.put("nokinv_device_id", "bad") },
            { it.remove("nokinv_credential_generation") }, { it.put("nokinv_credential_generation", "3") },
            { it.put("nokinv_credential_generation", 0) }, { it.put("nokinv_credential_generation", -1) },
            { it.put("vpn_secret", "A".repeat(42) + "B") }, { it.put("vpn_secret", "A".repeat(43) + "=") },
            { it.put("vpn_secret", "A".repeat(42)) }, { it.put("vpn_secret", "A".repeat(44)) },
            { it.put("vpn_secret", "A".repeat(42) + "+") },
            { it.getJSONObject("endpoint").put("nokinv_token", "A".repeat(42) + "B")
                .put("nokinv_credential_generation", 3) },
        )) assertThrows(Exception::class.java) { BackendYoutubeAccessJsonParser.parse(payload().also(mutate), now) }
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
            val body = if (chain.request().method == "DELETE") JSONObject() else ready()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(body.toString().toResponseBody("application/json".toMediaType())).build()
        }.build()
        val api = BackendApiClient(client, "https://api.example")
        val request = YoutubeAccessRequest(deviceId, "device-key", nonce, signature,
            "33333333-3333-4333-8333-333333333333", keyId, "real-endpoint")
        val access = api.createYoutubeAccess("access", request)
        api.pollYoutubeAccess("access", deviceId, "device-key", nonce, signature, access.grantId)
        api.renewYoutubeAccess("access", deviceId, "device-key", nonce, signature, access)
        api.revokeYoutubeAccess(access.grantId, access.revokeToken)

        assertEquals(listOf("POST", "GET", "POST", "DELETE"), requests.map { it.method })
        val create = JSONObject(Buffer().also { requests[0].body!!.writeTo(it) }.readUtf8())
        assertEquals(setOf("request_id", "device_id", "device_key", "device_nonce", "device_signature",
            "public_key_id", "primary_endpoint_code"), create.keys().asSequence().toSet())
        assertEquals(request.requestId, create.getString("request_id"))
        assertEquals(keyId, create.getString("public_key_id"))
        assertEquals("real-endpoint", create.getString("primary_endpoint_code"))
        assertEquals("/v1/vpn/youtube-access", requests[0].url.encodedPath)
        for (sent in requests.take(3)) {
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
        assertEquals(access.revokeToken, revoke.header("X-Youtube-Revoke-Token"))
    }

    @Test fun `pending and blocked expose no connection material and blocked may be expired`() {
        val pending = base("pending")
        assertNull(BackendYoutubeAccessJsonParser.parse(pending, now).wireguard)
        val blocked = base("blocked").put("expires_at", now.minusSeconds(1).toString())
        assertEquals("blocked", BackendYoutubeAccessJsonParser.parse(blocked, now).status)
        for (field in listOf("wireguard", "public_key_id", "endpoint", "vpn_secret")) {
            assertThrows(IllegalArgumentException::class.java) {
                BackendYoutubeAccessJsonParser.parse(base("pending").put(field, "forbidden"), now)
            }
        }
    }

    @Test fun `ready wireguard validates strict state key network expiry and payload shape`() {
        val access = BackendYoutubeAccessJsonParser.parse(ready(), now)
        assertEquals(keyId, access.publicKeyId)
        assertEquals("10.89.0.2/32", access.wireguard!!.address)
        val invalid = listOf<(JSONObject) -> Unit>(
            { it.put("version", "1") }, { it.put("generation", "1") }, { it.put("generation", 0) },
            { it.put("mode", "automatic") }, { it.put("unexpected", true) },
            { it.put("grant_id", "invalid") }, { it.put("public_key_id", "invalid") },
            { it.put("expires_at", now.toString()) }, { it.put("expires_at", "2030-01-01T00:02:00") },
            { it.put("revoke_token", "bad") }, { it.put("vpn_secret", "forbidden") },
            { it.put("nokinv_device_id", deviceId) }, { it.remove("wireguard") },
            { it.getJSONObject("wireguard").put("endpoint", "[::1]:443") },
            { it.getJSONObject("wireguard").put("address", "10.89.0.2/24") },
            { it.getJSONObject("wireguard").put("mtu", "1280") },
            { it.getJSONObject("wireguard").put("allowed_ips", JSONArray().put("::/0")) },
            { it.getJSONObject("wireguard").put("server_public_key", java.util.Base64.getEncoder().encodeToString(ByteArray(32))) },
        )
        for (mutate in invalid) assertThrows(Exception::class.java) {
            BackendYoutubeAccessJsonParser.parse(ready().also(mutate), now)
        }
    }

    @Test fun `selected transport reuses endpoint policy and rejects insecure or unsupported material`() {
        val access = BackendYoutubeAccessJsonParser.parse(selected(), now)
        assertEquals("vless", access.endpoint!!.proxyType)
        assertEquals("youtube-secret", access.vpnSecret)
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
            request.copy(deviceSignature = "short"), request.copy(publicKeyId = "bad"),
            request.copy(primaryEndpointCode = ""))) {
            assertThrows(IllegalArgumentException::class.java) { invalid.toBackendJson() }
        }
    }

    private fun base(status: String) = JSONObject().put("version", 1).put("mode", "wireguard")
        .put("status", status).put("grant_id", "44444444-4444-4444-8444-444444444444")
        .put("generation", 1).put("expires_at", "2099-01-01T00:02:00Z").put("revoke_token", "r".repeat(43))

    private fun ready() = base("ready").put("public_key_id", keyId).put("wireguard", JSONObject()
        .put("endpoint", "192.0.2.50:51820").put("address", "10.89.0.2/32")
        .put("server_public_key", java.util.Base64.getEncoder().encodeToString(ByteArray(32) { 7 }))
        .put("mtu", 1280).put("dns", JSONArray().put("1.1.1.1")).put("allowed_ips", JSONArray().put("0.0.0.0/0")))

    private fun selected() = base("ready").put("mode", "selected_transport").put("vpn_secret", "youtube-secret")
        .put("endpoint", JSONObject().put("code", "youtube-tls").put("node_id", deviceId)
            .put("entry_host", "youtube.example").put("entry_port", 443).put("server_name", "youtube.example")
            .put("proxy_type", "vless").put("transport", "tcp").put("security", "tls"))
}
