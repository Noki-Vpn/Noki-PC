package com.noki.vpn.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EndpointStartupPreflightPolicyTest {
    @Test
    fun nativeSecurityPolicyRejectsPacketUpgradeAndUnspecifiedXhttpMode() {
        val stream = tcpCandidate("stream").copy(transport = "xhttp", transportMode = "stream-up", flow = null)
        assertTrue(EndpointSecurityPolicy.isAllowedCandidate(stream))
        for (candidate in listOf(stream.copy(transportMode = "packet-up"),
            stream.copy(transportMode = null), stream.copy(transport = "httpupgrade"),
            stream.copy(transport = "unknown"))) {
            assertFalse(candidate.code, EndpointSecurityPolicy.isAllowedCandidate(candidate))
        }
        val reality = tcpCandidate("vision").copy(transport = "tcp", transportMode = null)
        val tls = tcpCandidate("tls").copy(security = "tls", transport = "tcp", transportMode = null)
        for (candidate in listOf(reality, tls)) {
            assertTrue(EndpointSecurityPolicy.isAllowedCandidate(candidate))
        }
        assertTrue(EndpointTransportPolicy.isDirectCandidate(reality))
        assertFalse(EndpointTransportPolicy.isDirectCandidate(tls))
        assertTrue(EndpointSecurityPolicy.isAllowedCandidate(hysteriaCandidate("hy2")))
    }

    @Test
    fun autoReachesHysteriaAfterAllTcpFailuresInOneSelectionOnEveryNetwork() {
        for (network in EndpointRankingPolicy.NetworkKind.entries) {
            val checked = mutableListOf<String>()
            val result = EndpointStartupPreflightPolicy.selectWithTcpPrecheck(
                candidates = (1..4).map { tcpCandidate("tcp$it") } + hysteriaCandidate("hy2"),
                health = emptyMap(),
                networkKind = network,
                nowMillis = 0L,
                rotationIndex = { 0 },
                canReach = { checked += it.code; false },
            )

            assertEquals(network.name, "hy2", result.selected?.code)
            assertEquals(setOf("tcp1", "tcp2", "tcp3", "tcp4"), checked.toSet())
            assertEquals(4, checked.size)
            assertFalse(result.shouldPenalizeFailedTcp)
        }
    }

    @Test
    fun unprovenHysteriaDoesNotValidateEarlierTcpFailures() {
        val result = EndpointStartupPreflightPolicy.selectWithTcpPrecheck(
            candidates = listOf(tcpCandidate("tcp"), hysteriaCandidate("hy2")),
            health = emptyMap(),
            networkKind = EndpointRankingPolicy.NetworkKind.CELLULAR,
            nowMillis = 0L,
            rotationIndex = { 0 },
            canReach = { false },
        )

        assertEquals("hy2", result.selected?.code)
        assertFalse(result.shouldPenalizeFailedTcp)
    }
}
