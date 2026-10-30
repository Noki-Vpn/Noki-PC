package com.noki.vpn.data

import com.noki.vpn.vpn.GeositeAssetTest
import com.noki.vpn.vpn.VpnConfigFactory
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NokiNVReleaseTest {
    private val nv = tcpCandidate("nv").copy(proxyType = "nokinv", transport = "nokinv", transportMode = null,
        security = "tls", publicKey = null, shortId = null, flow = null, path = "/tunnel",
        nokinvToken = "A".repeat(43), nokinvCredentialGeneration = 1)
    private val option = VpnEndpointOption(code = nv.code, nodeId = nv.nodeId, host = nv.entryHost, port = nv.entryPort,
        locationCode = nv.locationCode, proxyType = "nokinv", transport = "nokinv", security = "tls")

    @Test
    fun nokiNvCredentialsRequireCanonicalUnpaddedBase64UrlAndPositiveGeneration() {
        val settings = AdvancedSettings(protocol = VpnProtocol.NOKINV)
        val profile = EndpointSelector.profileFromCandidate(nv, "")
        for (token in listOf("A".repeat(43), "B".repeat(42) + "A", "_".repeat(42) + "8")) {
            assertTrue(EndpointSecurityPolicy.isValidNokiNvCredential(token, 1))
            assertTrue(EndpointSecurityPolicy.isAllowedProfile(profile.copy(nokinvToken = token)))
            assertTrue(VpnProfileValidator.isUsable(profile.copy(nokinvToken = token), settings))
        }
        val invalid = listOf("", "A".repeat(42) + "B", "A".repeat(43) + "=", "A".repeat(42),
            "A".repeat(44), "A".repeat(42) + "+", "A".repeat(42) + "/", "A".repeat(42) + " ")
            .map { profile.copy(nokinvToken = it) } +
            listOf(0, -1, Int.MIN_VALUE).map { profile.copy(nokinvCredentialGeneration = it) }
        for (rejected in invalid) {
            assertFalse(EndpointSecurityPolicy.isValidNokiNvCredential(rejected.nokinvToken, rejected.nokinvCredentialGeneration))
            assertFalse(EndpointSecurityPolicy.isAllowedProfile(rejected))
            assertFalse(VpnProfileValidator.isUsable(rejected, settings))
            assertThrows(IllegalArgumentException::class.java) { VpnConfigFactory.buildProbe(rejected) }
        }
    }

    @Test
    fun manualNokiNvUsesOnlyQuicAndMigratesOldGroupIdentity() {
        val choices = EndpointGroupPolicy.manualOptions(listOf(option))
        assertEquals(listOf("NokiNV"), choices.map { it.label })
        for (legacy in listOf("tls|nokinv|", "tls|nokinv|tcp", "lv|tls|nokinv|tcp|100")) {
            val settings = AdvancedSettings(endpointSelectionMode = EndpointSelectionMode.MANUAL, manualEndpointGroupKey = legacy)
            assertEquals("tls|nokinv|quic", EndpointGroupPolicy.normalizedGroupKey(legacy))
            assertEquals("quic", EndpointGroupPolicy.manualCandidates(listOf(nv), settings).single().transportMode)
        }
        val profile = EndpointSelector.profileFromCandidate(nv, "")
        for (gaming in listOf(false, true)) {
            val geosite = GeositeAssetTest.asset("CATEGORY-RU" to listOf(GeositeAssetTest.domain(2, "ru.example")))
            val outbound = JSONObject(VpnConfigFactory.build(profile, AdvancedSettings(gamingModeEnabled = gaming), geosite))
                .getJSONArray("outbounds").getJSONObject(0)
            assertEquals("quic", outbound.getString("transport"))
            assertEquals(if (gaming) "gaming" else "balanced", outbound.getString("mode"))
            assertFalse(outbound.has("tcp_exchange"))
            assertFalse(outbound.has("ip_tunnel"))
        }
        assertFalse(EndpointSecurityPolicy.isAllowedCandidate(nv.copy(transportMode = "tcp")))
        assertThrows(IllegalArgumentException::class.java) { VpnConfigFactory.buildProbe(profile.copy(transportMode = "tcp")) }
    }
}
