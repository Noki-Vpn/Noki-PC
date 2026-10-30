package com.noki.vpn.data

import java.time.Instant
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import com.noki.vpn.vpn.awaitRuWireGuardReady

class RuWireGuardContractTest {
    @Test
    fun readyPollingDeadlineIncludesHttpAndCancelsPendingWork() = runBlocking {
        val root = fixture()
        val initial = RuWireGuardSessionJsonParser.parse(root.getJSONObject("pending"), expectation(root),
            Instant.parse(root.getString("frozen_now")))
        var cancelled = false
        assertThrows(kotlinx.coroutines.TimeoutCancellationException::class.java) { runBlocking {
            awaitRuWireGuardReady(initial, poll = {
                try { kotlinx.coroutines.awaitCancellation() } finally { cancelled = true }
            }, pause = {}, timeoutMillis = 40)
        } }
        assertTrue(cancelled)
    }
    @Test
    fun persistentStatusAndExplicitRevokeTargetTheSameSession() = runBlocking {
        val root = fixture()
        val payload = root.getJSONObject("ready")
        val current = RuWireGuardSessionJsonParser.parse(payload, expectation(root),
            Instant.parse(root.getString("frozen_now"))) as BackendRuWireGuardSession.Ready
        val requests = mutableListOf<okhttp3.Request>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            requests += chain.request()
            val body = if (chain.request().method == "DELETE") "{}" else payload.toString()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }.build()
        val deviceId = root.getJSONObject("request").getString("device_id")
        val api = BackendApiClient(client, "https://api.example")
        assertEquals(current, api.pollRuWireGuardSession("access", deviceId, "proof", current.identity))
        assertEquals(current, api.activateRuWireGuardSession("access", deviceId, "proof", current.identity))
        api.revokeRuWireGuardSession(current.identity.sessionId, checkNotNull(current.identity.revokeToken))
        assertEquals(listOf("GET", "POST", "DELETE"), requests.map { it.method })
        requests.forEach {
            assertTrue(it.url.encodedPath.startsWith("/v1/vpn/sessions/${current.identity.sessionId}"))
        }
        val get = requests[0]
        assertEquals(deviceId, get.url.queryParameter("device_id"))
        assertEquals("Bearer access", get.header("Authorization"))
        assertEquals(deviceId, get.header("X-Device-Id"))
        assertEquals("proof", get.header("X-Device-Key"))
        val activate = requests[1]
        assertEquals("/v1/vpn/sessions/${current.identity.sessionId}/activate", activate.url.encodedPath)
        assertEquals(deviceId, activate.url.queryParameter("device_id"))
        assertEquals("Bearer access", activate.header("Authorization"))
        assertEquals(deviceId, activate.header("X-Device-Id"))
        assertEquals("proof", activate.header("X-Device-Key"))
        val delete = requests[2]
        assertEquals("/v1/vpn/sessions/${current.identity.sessionId}", delete.url.encodedPath)
        assertEquals(null, delete.url.query)
        assertEquals(null, delete.header("Authorization"))
        assertEquals(null, delete.header("X-Device-Id"))
        assertEquals(null, delete.header("X-Device-Key"))
        assertEquals(current.identity.revokeToken, delete.header("X-Ru-Revoke-Token"))
    }

    @Test
    fun keyEnrollmentSendsOnlyPublicKeyAndDeviceProof() = runBlocking {
        val keyId = "11111111-1111-4111-8111-111111111111"
        val deviceId = "22222222-2222-4222-8222-222222222222"
        val publicKey = java.util.Base64.getEncoder().encodeToString(ByteArray(32) { 7 })
        var observedRequest: okhttp3.Request? = null
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            observedRequest = chain.request()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK")
                .body("""{"id":"$keyId","public_key":"$publicKey"}"""
                    .toResponseBody("application/json".toMediaType())).build()
        }.build()
        val result = BackendApiClient(client, "https://api.example").registerRuWireGuardKey(
            token = "access", deviceId = deviceId, deviceKey = "proof",
            deviceNonce = "nonce-1234567890123456", deviceSignature = "signature".repeat(8),
            publicKey = publicKey,
        )
        assertEquals(keyId, result.id)
        assertEquals(publicKey, result.publicKey)
        val request = observedRequest!!
        assertEquals("/v1/vpn/wireguard-keys", request.url.encodedPath)
        assertEquals("Bearer access", request.header("Authorization"))
        assertEquals(deviceId, request.header("X-Device-Id"))
        val payload = JSONObject(Buffer().also { request.body!!.writeTo(it) }.readUtf8())
        assertEquals(publicKey, payload.getString("public_key"))
        assertFalse(payload.has("private_key"))
        assertEquals(setOf("device_id", "device_key", "device_nonce", "device_signature", "public_key"),
            payload.keys().asSequence().toSet())
    }
    private fun fixture(): JSONObject {
        return checkNotNull(javaClass.getResourceAsStream("/contracts/ru_wireguard_session_v2.json"))
            .bufferedReader().use { JSONObject(it.readText()) }
    }

    private fun expectation(root: JSONObject): RuWireGuardSessionExpectation {
        val request = root.getJSONObject("request")
        return RuWireGuardSessionExpectation(
            requestId = request.getString("request_id"),
            publicKeyId = request.getString("public_key_id"),
            exitNodeId = request.getString("exit_node_id"),
            relaySelection = RuRelaySelection.Node(request.getJSONObject("relay_selection").getString("node_id")),
        )
    }

    @Test
    fun sharedReadyAndPendingResponsesKeepManualRoute() {
        val root = fixture()
        val now = Instant.parse(root.getString("frozen_now"))
        val expected = expectation(root)
        val ready = RuWireGuardSessionJsonParser.parse(root.getJSONObject("ready"), expected, now)
        val pending = RuWireGuardSessionJsonParser.parse(root.getJSONObject("pending"), expected, now)
        assertTrue(ready is BackendRuWireGuardSession.Ready)
        assertTrue(pending is BackendRuWireGuardSession.Pending)
        assertEquals(null, ready.identity.leaseExpiresAtEpochMillis)
        assertTrue(requireNotNull(pending.identity.leaseExpiresAtEpochMillis) > now.toEpochMilli())
        assertEquals("10.87.0.2/32", (ready as BackendRuWireGuardSession.Ready).wireguard.address)
        assertEquals(expected.exitNodeId, ready.identity.exitNodeId)
        assertEquals(1, ready.identity.routeVersion)
        assertEquals(4, ready.identity.routeGeneration)
        assertEquals(1, ready.identity.generation)
    }

    @Test
    fun explicitForeignEntryIsBoundToTheRequestAndNeverAcceptedAsAShorterOrOverlappingRoute() {
        val root = fixture()
        val entryId = "88888888-8888-4888-8888-888888888888"
        val expected = expectation(root).copy(entryNodeId = entryId)
        val payload = root.getJSONObject("ready").put("entry_node_id", entryId)
        val now = Instant.parse(root.getString("frozen_now"))
        assertEquals(entryId, RuWireGuardSessionJsonParser.parse(payload, expected, now).identity.entryNodeId)
        val request = RuWireGuardSessionRequest(
            deviceId = root.getJSONObject("request").getString("device_id"),
            deviceKey = "proof", deviceNonce = "nonce", deviceSignature = "signature",
            requestId = expected.requestId, publicKeyId = expected.publicKeyId,
            exitNodeId = expected.exitNodeId, entryNodeId = entryId, relaySelection = expected.relaySelection,
        )
        assertEquals(entryId, request.toBackendJson().getString("entry_node_id"))
        assertThrows(IllegalArgumentException::class.java) {
            RuWireGuardSessionJsonParser.parse(JSONObject(payload.toString()).put("entry_node_id", JSONObject.NULL), expected, now)
        }
        for (overlap in listOf(expected.exitNodeId, payload.getString("relay_node_id"))) {
            assertThrows(IllegalArgumentException::class.java) {
                RuWireGuardSessionJsonParser.parse(JSONObject(payload.toString()).put("entry_node_id", overlap),
                    expected.copy(entryNodeId = overlap), now)
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            request.copy(entryNodeId = expected.exitNodeId).toBackendJson()
        }
    }

    @Test
    fun autoRequestCarriesNoManualNodeAndResponseKeepsChosenRelay() {
        val root = fixture()
        val now = Instant.parse(root.getString("frozen_now"))
        val expected = expectation(root).copy(relaySelection = RuRelaySelection.Auto)
        val payload = JSONObject(root.getJSONObject("ready").toString())
        payload.put("relay_selection", RuRelaySelection.Auto.toBackendJson())
        val result = RuWireGuardSessionJsonParser.parse(payload, expected, now)
        assertEquals(root.getJSONObject("ready").getString("relay_node_id"), result.identity.relayNodeId)
        assertFalse(RuRelaySelection.Auto.toBackendJson().has("node_id"))
        assertEquals(result.identity.relayNodeId,
            RuRelaySelection.Node(result.identity.relayNodeId).toBackendJson().getString("node_id"))
    }

    @Test
    fun requestMappingSendsOnlyTheRuRouteAndSelectedExit() {
        val root = fixture()
        val expected = expectation(root)
        val auto = RuWireGuardSessionRequest(
            deviceId = root.getJSONObject("request").getString("device_id"),
            deviceKey = "device-key", deviceNonce = "nonce-1234567890123456", deviceSignature = "signature",
            requestId = expected.requestId, publicKeyId = expected.publicKeyId,
            exitNodeId = expected.exitNodeId, relaySelection = RuRelaySelection.Auto,
            relayRttSamples = listOf(RuRelayRttSample(
                root.getJSONObject("ready").getString("relay_node_id"), 24)),
        ).toBackendJson()
        assertEquals("ru_wg_nebula", auto.getString("route_mode"))
        assertEquals(listOf("wireguard_native_v1", "wireguard_persistent_v2", "wireguard_routes_v1"),
            (0 until auto.getJSONArray("capabilities").length()).map { auto.getJSONArray("capabilities").getString(it) })
        assertEquals(1, auto.getInt("route_version"))
        assertTrue(auto.has("entry_node_id") && auto.isNull("entry_node_id"))
        assertEquals("auto", auto.getString("profile_code"))
        assertEquals(expected.exitNodeId, auto.getString("exit_node_id"))
        assertEquals("auto", auto.getJSONObject("relay_selection").getString("kind"))
        assertFalse(auto.getJSONObject("relay_selection").has("node_id"))
        assertEquals(24, auto.getJSONArray("relay_rtt_samples").getJSONObject(0).getInt("rtt_ms"))
        assertFalse(auto.has("node_id"))
        assertFalse(auto.has("multihop"))
        val manual = RuWireGuardSessionRequest(
            deviceId = root.getJSONObject("request").getString("device_id"),
            deviceKey = null, deviceNonce = "nonce-1234567890123456", deviceSignature = "signature",
            requestId = expected.requestId, publicKeyId = expected.publicKeyId,
            exitNodeId = expected.exitNodeId, relaySelection = expected.relaySelection,
        ).toBackendJson()
        assertEquals((expected.relaySelection as RuRelaySelection.Node).nodeId,
            manual.getJSONObject("relay_selection").getString("node_id"))
    }

    @Test
    fun expiredUnknownEngineWrongKeyAndWrongRelayFailClosed() {
        val root = fixture()
        val now = Instant.parse(root.getString("frozen_now"))
        val expected = expectation(root)
        val mutations = listOf<Pair<String, String>>(
            "lease_expires_at" to "2026-09-25T11:59:59Z",
            "engine" to "xray",
            "public_key_id" to "77777777-7777-4777-8777-777777777777",
            "relay_node_id" to "77777777-7777-4777-8777-777777777777",
            "exit_node_id" to "77777777-7777-4777-8777-777777777777",
        )
        for ((key, value) in mutations) {
            val payload = JSONObject(root.getJSONObject("ready").toString()).put(key, value)
            assertThrows(key, IllegalArgumentException::class.java) {
                RuWireGuardSessionJsonParser.parse(payload, expected, now)
            }
        }
        val missingConfig = JSONObject(root.getJSONObject("ready").toString())
        missingConfig.remove("wireguard")
        assertThrows(IllegalArgumentException::class.java) {
            RuWireGuardSessionJsonParser.parse(missingConfig, expected, now)
        }
        val badKey = JSONObject(root.getJSONObject("ready").toString())
        badKey.getJSONObject("wireguard").put("server_public_key", "bad")
        assertThrows(IllegalArgumentException::class.java) {
            RuWireGuardSessionJsonParser.parse(badKey, expected, now)
        }
        val expiredReservation = JSONObject(root.getJSONObject("pending").toString())
            .put("lease_expires_at", now.minusSeconds(1).toString())
        assertThrows(IllegalArgumentException::class.java) {
            RuWireGuardSessionJsonParser.parse(expiredReservation, expected, now)
        }
        val legacyReady = JSONObject(root.getJSONObject("ready").toString()).put("contract_version", 1)
        assertThrows(IllegalArgumentException::class.java) {
            RuWireGuardSessionJsonParser.parse(legacyReady, expected, now)
        }
        val missingRevokeToken = JSONObject(root.getJSONObject("ready").toString()).apply { remove("revoke_token") }
        assertThrows(IllegalArgumentException::class.java) {
            RuWireGuardSessionJsonParser.parse(missingRevokeToken, expected, now)
        }
        val missingPendingToken = JSONObject(root.getJSONObject("pending").toString()).apply { remove("revoke_token") }
        assertThrows(IllegalArgumentException::class.java) {
            RuWireGuardSessionJsonParser.parse(missingPendingToken, expected, now)
        }
    }

    @Test
    fun missingOrUnsupportedRouteIdentityFailsClosed() {
        val root = fixture()
        val now = Instant.parse(root.getString("frozen_now"))
        for (field in listOf("route_version", "route_id", "route_generation", "entry_node_id")) {
            val payload = JSONObject(root.getJSONObject("ready").toString()).apply { remove(field) }
            assertThrows(field, IllegalArgumentException::class.java) {
                RuWireGuardSessionJsonParser.parse(payload, expectation(root), now)
            }
        }
        for ((field, value) in listOf("route_version" to 2, "route_version" to "1",
                "route_generation" to 0, "route_generation" to "4", "generation" to true,
                "route_id" to "7-7-7-7-7")) {
            val payload = JSONObject(root.getJSONObject("ready").toString()).put(field, value)
            assertThrows(field, IllegalArgumentException::class.java) {
                RuWireGuardSessionJsonParser.parse(payload, expectation(root), now)
            }
        }
    }

    @Test
    fun pollAndActivateRejectChangedRouteWhileSessionGenerationIsUnchanged() = runBlocking {
        val root = fixture()
        val payload = root.getJSONObject("ready")
        val current = RuWireGuardSessionJsonParser.parse(payload, expectation(root),
            Instant.parse(root.getString("frozen_now")))
        for ((field, value) in listOf("route_id" to "88888888-8888-4888-8888-888888888888",
                "route_generation" to 5, "entry_node_id" to "99999999-9999-4999-8999-999999999999")) {
            val changed = JSONObject(payload.toString()).put(field, value)
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                    .body(changed.toString().toResponseBody("application/json".toMediaType())).build()
            }.build()
            val api = BackendApiClient(client, "https://api.example")
            val device = root.getJSONObject("request").getString("device_id")
            assertThrows(field, IllegalArgumentException::class.java) { runBlocking {
                api.pollRuWireGuardSession("access", device, "proof", current.identity)
            } }
            assertThrows(field, IllegalArgumentException::class.java) { runBlocking {
                api.activateRuWireGuardSession("access", device, "proof", current.identity)
            } }
        }
    }

    @Test
    fun readyWaitRejectsChangedSessionEvenWhenGenerationMatches() = runBlocking {
        val root = fixture()
        val now = Instant.parse(root.getString("frozen_now"))
        val initial = RuWireGuardSessionJsonParser.parse(root.getJSONObject("pending"), expectation(root), now)
        val changed = RuWireGuardSessionJsonParser.parse(JSONObject(root.getJSONObject("ready").toString())
            .put("session_id", "88888888-8888-4888-8888-888888888888"), expectation(root), now)
        assertThrows(IllegalStateException::class.java) { runBlocking {
            awaitRuWireGuardReady(initial, poll = { changed }, pause = {})
        } }
        Unit
    }

    @Test
    fun persistentRecoveryPendingCanHaveNoDeadlineAndNeverProvidesConfig() {
        val root = fixture()
        val payload = JSONObject(root.getJSONObject("pending").toString()).put("lease_expires_at", JSONObject.NULL)
        val result = RuWireGuardSessionJsonParser.parse(payload, expectation(root),
            Instant.parse(root.getString("frozen_now")))
        assertTrue(result is BackendRuWireGuardSession.Pending)
        assertEquals(null, result.identity.leaseExpiresAtEpochMillis)
        payload.put("wireguard", root.getJSONObject("ready").getJSONObject("wireguard"))
        assertThrows(IllegalArgumentException::class.java) {
            RuWireGuardSessionJsonParser.parse(payload, expectation(root), Instant.parse(root.getString("frozen_now")))
        }
    }
}
