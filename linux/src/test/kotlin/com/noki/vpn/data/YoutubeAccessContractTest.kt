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
    private val now = Instant.parse("2030-01-01T00:00:00Z")
    private val deviceId = "22222222-2222-4222-8222-222222222222"
    private val keyId = "11111111-1111-4111-8111-111111111111"
    private val nonce = "nonce-1234567890123456"
    private val signature = "signature".repeat(8)

    @Test fun `direct WireGuard admission is accepted without selected transport material`() {
        val access = BackendYoutubeAccessJsonParser.parse(ready(), now)
        assertEquals("wireguard", access.mode)
        assertEquals(keyId, access.publicKeyId)
        assertEquals("10.89.0.2/32", access.wireguard!!.address)
        val privateKey = ByteArray(32) { 3 }
        val connection = com.noki.vpn.vpn.YoutubeConnectionConfig.fromReady(access, keyId, privateKey)
        assertEquals("192.0.2.50:51820", connection.wireguard.endpoint)
        assertThrows(IllegalArgumentException::class.java) {
            com.noki.vpn.vpn.YoutubeConnectionConfig.fromReady(access, deviceId, privateKey)
        }
    }

    @Test fun `admission sends the registered key fresh proof and capability-only revoke`() = runBlocking {
        val requests = mutableListOf<Request>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            requests += chain.request()
            val body = if (chain.request().method == "DELETE") JSONObject() else ready()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(body.toString().toResponseBody("application/json".toMediaType())).build()
        }.build()
        val api = BackendApiClient(client, "https://api.example")
        val request = YoutubeAccessRequest(deviceId, "device-key", nonce, signature,
            "33333333-3333-4333-8333-333333333333", keyId)
        val access = api.createYoutubeAccess("access", request)
        api.pollYoutubeAccess("access", deviceId, "device-key", nonce, signature, access.grantId)
        api.renewYoutubeAccess("access", deviceId, "device-key", nonce, signature, access)
        api.revokeYoutubeAccess(access.grantId, access.revokeToken)

        assertEquals(listOf("POST", "GET", "POST", "DELETE"), requests.map { it.method })
        val create = JSONObject(Buffer().also { requests[0].body!!.writeTo(it) }.readUtf8())
        assertEquals(setOf("request_id", "device_id", "device_key", "device_nonce", "device_signature", "public_key_id"),
            create.keys().asSequence().toSet())
        assertEquals(request.requestId, create.getString("request_id"))
        assertEquals(keyId, create.getString("public_key_id"))
        assertEquals(nonce, create.getString("device_nonce"))
        assertEquals(signature, create.getString("device_signature"))
        assertEquals("/v1/vpn/youtube-access", requests[0].url.encodedPath)
        for (sent in requests.take(3)) {
            assertNull(sent.header("X-Youtube-Transport"))
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
        assertNull(revoke.url.query)
        assertNull(revoke.header("Authorization"))
        assertNull(revoke.header("X-Device-Id"))
        assertNull(revoke.header("X-Device-Nonce"))
        assertNull(revoke.header("X-Youtube-Transport"))
        assertEquals(access.revokeToken, revoke.header("X-Youtube-Revoke-Token"))
    }

    @Test fun `pending and blocked expose no connection material and blocked may be expired`() {
        assertNull(BackendYoutubeAccessJsonParser.parse(base("pending"), now).wireguard)
        val blocked = base("blocked").put("expires_at", now.minusSeconds(1).toString())
        assertEquals("blocked", BackendYoutubeAccessJsonParser.parse(blocked, now).status)
        for (field in listOf("wireguard", "public_key_id", "endpoint", "vpn_secret", "vpn_username",
            "nokinv_device_id", "nokinv_credential_generation")) {
            assertThrows(IllegalArgumentException::class.java) {
                BackendYoutubeAccessJsonParser.parse(base("pending").put(field, "forbidden"), now)
            }
        }
    }

    @Test fun `ready WireGuard rejects invalid identity network expiry and selected transport material`() {
        for (mutate in listOf<(JSONObject) -> Unit>(
            { it.put("version", "1") }, { it.put("generation", "1") }, { it.put("generation", 0) },
            { it.put("mode", "selected_transport") }, { it.put("unexpected", true) },
            { it.put("grant_id", "invalid") }, { it.put("public_key_id", "invalid") },
            { it.put("expires_at", now.toString()) }, { it.put("expires_at", "2030-01-01T00:02:00") },
            { it.put("revoke_token", "bad") }, { it.put("vpn_secret", "forbidden") },
            { it.put("endpoint", JSONObject()) }, { it.put("nokinv_device_id", deviceId) },
            { it.put("vpn_username", "legacy-user") }, { it.remove("wireguard") }, { it.remove("public_key_id") },
            { it.getJSONObject("wireguard").put("endpoint", "[::1]:443") },
            { it.getJSONObject("wireguard").put("endpoint", "192.0.2.50:0") },
            { it.getJSONObject("wireguard").put("address", "10.89.0.2/24") },
            { it.getJSONObject("wireguard").put("mtu", "1280") },
            { it.getJSONObject("wireguard").put("dns", JSONArray()) },
            { it.getJSONObject("wireguard").put("allowed_ips", JSONArray().put("::/0")) },
            { it.getJSONObject("wireguard").put("detour", "proxy") },
            { it.getJSONObject("wireguard").put("server_public_key", java.util.Base64.getEncoder().encodeToString(ByteArray(32))) },
        )) assertThrows(Exception::class.java) { BackendYoutubeAccessJsonParser.parse(ready().also(mutate), now) }
    }

    @Test fun `request validation forbids malformed identity key or proof before network`() {
        val request = YoutubeAccessRequest(deviceId, null, nonce, signature,
            "33333333-3333-4333-8333-333333333333", keyId)
        assertEquals(keyId, request.toBackendJson().getString("public_key_id"))
        for (invalid in listOf(request.copy(deviceId = "bad"), request.copy(deviceNonce = "short"),
            request.copy(deviceSignature = "short"), request.copy(publicKeyId = "bad"))) {
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
}
