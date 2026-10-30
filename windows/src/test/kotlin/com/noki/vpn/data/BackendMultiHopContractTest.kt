package com.noki.vpn.data

import com.noki.vpn.vpn.GeositeAssetTest
import com.noki.vpn.vpn.VpnConfigFactory
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BackendMultiHopContractTest {
    @Test
    fun `NV credentials remain separate through MH and gaming uses the ordinary release baseline`() {
        val session = BackendVpnSessionJsonParser.parse(fixture())
        val nv = EndpointSelector.profileFromCandidate(session, session.endpointCandidates.first())
        assertEquals("", nv.uuid)
        assertEquals(session.nokinvToken, nv.nokinvToken)
        val base = AdvancedSettings(protocol = VpnProtocol.REALITY, gamingModeEnabled = true,
            endpointSelectionMode = EndpointSelectionMode.MANUAL, manualEndpointGroupKey = "tls|hysteria|",
            multiHop = session.multiHop!!.selection)
        assertEquals(VpnProtocol.NOKINV, base.effectiveProtocol)
        assertEquals(VpnProtocol.REALITY, base.copy(gamingModeEnabled = false).effectiveProtocol)
        val geosite = GeositeAssetTest.asset("CATEGORY-RU" to listOf(GeositeAssetTest.domain(2, "ru.example")))
        for (gaming in listOf(false, true)) {
            val settings = base.copy(protocol = VpnProtocol.AUTO, gamingModeEnabled = gaming, endpointSelectionMode = EndpointSelectionMode.AUTO)
            val config = JSONObject(VpnConfigFactory.build(nv, settings, geosite))
            val outbound = config.getJSONArray("outbounds").getJSONObject(0)
            assertEquals("nokinv", outbound.getString("type"))
            assertEquals(if (gaming) "gaming" else "balanced", outbound.getString("mode"))
            assertEquals("quic", outbound.getString("transport"))
            assertFalse(outbound.has("tcp_exchange"))
            assertEquals("coherent", outbound.getString("web_profile"))
            assertEquals("off", outbound.getString("shaping"))
            assertEquals("/tunnel", outbound.getString("path"))
            assertEquals("1.3", outbound.getJSONObject("tls").getString("min_version"))
            assertEquals(nv.host, config.getJSONObject("dns").getJSONArray("servers").getJSONObject(0).getString("server"))
            assertEquals("proxy", config.getJSONObject("dns").getJSONArray("servers").getJSONObject(0).getString("detour"))
            assertFalse(outbound.has("uuid"))
        }
        assertFalse(VpnProfileValidator.isUsable(nv, base.copy(gamingModeEnabled = false, protocol = VpnProtocol.AUTO)))
        assertTrue(VpnProfileValidator.isUsable(nv.copy(nokinvMode = "gaming"), base))
        for (invalid in listOf(nv.copy(nokinvToken = ""), nv.copy(nokinvToken = "A".repeat(42) + "+"),
                nv.copy(nokinvCredentialGeneration = 0), nv.copy(nokinvMode = "experimental"))) {
            assertFalse(EndpointSecurityPolicy.isAllowedProfile(invalid))
            assertThrows(IllegalArgumentException::class.java) { VpnConfigFactory.buildProbe(invalid) }
        }
    }

    @Test
    fun `NV mapper rejects malformed partial and coerced credentials`() {
        val mutations: List<(JSONObject) -> Unit> = listOf(
            { it.remove("nokinv_token") }, { it.remove("nokinv_credential_generation") },
            { it.put("nokinv_token", " A".repeat(22)) }, { it.put("nokinv_token", 123) },
            { it.put("nokinv_token", "A".repeat(42) + "B") }, { it.put("nokinv_token", "A".repeat(43) + "=") },
            { it.put("nokinv_token", "A".repeat(42)) }, { it.put("nokinv_token", "A".repeat(44)) },
            { it.put("nokinv_token", "A".repeat(42) + "+") },
            { it.put("nokinv_credential_generation", "2") }, { it.put("nokinv_credential_generation", 0) },
            { it.put("nokinv_credential_generation", -1) }, { it.put("nokinv_credential_generation", 2.5) },
            { it.getJSONArray("endpoint_candidates").getJSONObject(0).put("nokinv_token", "A".repeat(42) + "B")
                .put("nokinv_credential_generation", 2) },
        )
        mutations.forEach { mutation ->
            val payload = fixture().also(mutation)
            assertThrows(IllegalArgumentException::class.java) { BackendVpnSessionJsonParser.parse(payload) }
        }
    }

    @Test
    fun `location API uses the current authenticated device without loading bootstrap`() = runBlocking {
        var requests = 0
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            requests++
            assertEquals("/v1/locations", chain.request().url.encodedPath)
            assertEquals("Bearer access", chain.request().header("Authorization"))
            assertEquals("device", chain.request().header("X-Device-Id"))
            assertEquals("proof", chain.request().header("X-Device-Key"))
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("[]".toResponseBody("application/json".toMediaType())).build()
        }.build()
        assertTrue(BackendApiClient(client, "https://api.example").serverLocations("access", "device", "proof").isEmpty())
        assertEquals(1, requests)
    }
    private fun fixture(): JSONObject {
        return checkNotNull(javaClass.getResourceAsStream("/contracts/multihop_session_v2.json"))
            .bufferedReader().use { JSONObject(it.readText()) }
    }

    @Test
    fun `v2 transports dial only entry while keeping exit authentication and Russian DIRECT`() {
        val payload = fixture()
        payload.getJSONArray("endpoint_candidates").getJSONObject(1).put("request_host", "exit-front.example")
        val session = BackendVpnSessionJsonParser.parse(payload)
        val route = requireNotNull(session.multiHop)
        val settings = AdvancedSettings(multiHop = route.selection,
            alwaysOnDomains = listOf("domain:russian.example"))
        val geosite = GeositeAssetTest.asset("CATEGORY-RU" to listOf(GeositeAssetTest.domain(2, "russian.example")))
        for (candidate in session.endpointCandidates) {
            val profile = EndpointSelector.profileFromCandidate(session, candidate)
            assertTrue(VpnProfileValidator.isUsable(profile, settings))
            val config = JSONObject(VpnConfigFactory.build(profile, settings, geosite))
            assertEquals(2, config.getJSONArray("outbounds").length())
            assertFalse(config.has("endpoints"))
            val outbound = config.getJSONArray("outbounds").getJSONObject(0)
            assertEquals(route.entryIp, outbound.getString("server"))
            assertEquals(candidate.entryPort, outbound.getInt("server_port"))
            assertFalse(outbound.has("detour"))
            val tls = outbound.getJSONObject("tls")
            assertEquals(candidate.serverName, tls.getString("server_name"))
            assertTrue(tls.getBoolean("enabled"))
            assertFalse(tls.optBoolean("insecure"))
            when (candidate.proxyType) {
                "hysteria" -> assertEquals(session.vpnSecret, outbound.getString("password"))
                "nokinv" -> {
                    assertEquals(session.nokinvToken, outbound.getString("token"))
                    assertEquals(2, outbound.getInt("credential_generation"))
                    assertEquals(route.entryIp, config.getJSONObject("dns").getJSONArray("servers").getJSONObject(0).getString("server"))
                }
                else -> assertEquals(session.vpnSecret, outbound.getString("uuid"))
            }
            if (candidate.security == "reality") {
                assertEquals(candidate.publicKey, tls.getJSONObject("reality").getString("public_key"))
                assertEquals(candidate.shortId, tls.getJSONObject("reality").getString("short_id"))
            }
            if (candidate.transport == "xhttp") {
                assertEquals(candidate.requestHost, outbound.getJSONObject("transport").getString("host"))
                assertEquals(candidate.path, outbound.getJSONObject("transport").getString("path"))
            }
            val rules = config.getJSONObject("route").getJSONArray("rules")
            assertEquals("direct", rules.getJSONObject(4).getString("outbound"))
            assertEquals("proxy", rules.getJSONObject(rules.length() - 1).getString("outbound"))
            assertEquals("proxy", config.getJSONObject("route").getString("final"))
            assertEquals("proxy", config.getJSONObject("dns").getJSONArray("servers").getJSONObject(0).getString("detour"))
            for (unsafe in listOf(profile.copy(host = "exit.example"), profile.copy(port = "443"),
                    profile.copy(multiHop = null), profile.copy(allowInsecure = true))) {
                assertFalse(VpnProfileValidator.isUsable(unsafe, settings))
                assertThrows(IllegalArgumentException::class.java) { VpnConfigFactory.build(unsafe, settings, geosite) }
            }
        }
    }

    @Test
    fun `malformed route cannot become a direct profile`() {
        val mutations: List<(JSONObject) -> Unit> = listOf(
            { it.getJSONObject("multihop").put("version", 1) },
            { it.put("route_mode", "direct") },
            { it.put("route_mode", "ru_wg_nebula") },
            { it.remove("multihop") },
            { it.getJSONObject("multihop").getJSONObject("selection").put("version", 1) },
            { it.getJSONObject("multihop").getJSONObject("selection").getJSONObject("entry").put("node_id", "00000000-0000-0000-0000-000000000003") },
            { it.getJSONObject("multihop").put("policy_hash", "invalid") },
            { it.getJSONArray("endpoint_candidates").getJSONObject(0).put("node_id", "wrong-exit") },
            { it.getJSONArray("endpoint_candidates").getJSONObject(0).put("connect_ip", "9.9.9.9") },
            { it.getJSONArray("endpoint_candidates").getJSONObject(0).put("entry_host", "exit.example") },
            { it.getJSONArray("endpoint_candidates").getJSONObject(0).put("entry_port", 443) },
            { it.getJSONArray("endpoint_candidates").getJSONObject(0).put("allow_insecure", true) },
            { it.put("connect_ip", "9.9.9.9") },
        )
        mutations.forEach { mutate ->
            val payload = fixture()
            mutate(payload)
            assertTrue(runCatching { BackendVpnSessionJsonParser.parse(payload) }.isFailure)
        }
    }

    @Test
    fun `session API sends an explicit pair without direct selectors`() = runBlocking {
        val payload = fixture()
        val selection = requireNotNull(BackendVpnSessionJsonParser.parse(payload).multiHop).selection
        var requests = 0
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            requests++
            val buffer = okio.Buffer()
            chain.request().body!!.writeTo(buffer)
            val request = JSONObject(buffer.readUtf8())
            assertFalse(request.has("route_mode"))
            assertEquals("auto", request.getString("profile_code"))
            assertEquals(listOf("nokinv_v1", "nokinv_release_v1"), request.getJSONArray("capabilities").toList())
            assertEquals(2, request.getJSONObject("multihop").getInt("version"))
            assertEquals(selection.entry!!.nodeId, request.getJSONObject("multihop").getJSONObject("entry").getString("node_id"))
            assertEquals(selection.exit!!.nodeId, request.getJSONObject("multihop").getJSONObject("exit").getString("node_id"))
            for (key in listOf("country_code", "location_code", "exclude_location_code", "node_id")) assertFalse(request.has(key))
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(payload.toString().toResponseBody("application/json".toMediaType())).build()
        }.build()
        val api = BackendApiClient(client, "https://api.example")
        for (protocol in listOf("reality", "tls")) assertEquals(selection,
            api.createVpnSession("token", "device", "proof", "nonce", "signature",
                null, null, null, protocol, null, selection).multiHop!!.selection)
        assertEquals(2, requests)
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { api.createVpnSession("token", "device", "proof", "nonce", "signature",
                "LV", null, null, "auto", null, selection) }
        }
        assertEquals(2, requests)
    }
}
