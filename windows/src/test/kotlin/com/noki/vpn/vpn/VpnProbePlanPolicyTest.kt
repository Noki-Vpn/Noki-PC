package com.noki.vpn.vpn

import org.junit.Assert.assertEquals
import org.junit.Test

class VpnProbePlanPolicyTest {
    @Test
    fun routineProbeUsesIndependentTargetsInStableOrder() {
        assertEquals(
            listOf("api", "cloudflare", "gstatic"),
            VpnProbePlanPolicy.connectedTargets(recovery = false).map { it.key },
        )
        assertEquals("${com.noki.vpn.data.NokiBackendConfig.apiBaseUrl}/v1/vpn/connectivity",
            VpnProbePlanPolicy.candidateTargets().first().url)
    }

    @Test
    fun recoveryUsesTheSameBoundedTargetSet() {
        assertEquals(
            VpnProbePlanPolicy.connectedTargets(recovery = false),
            VpnProbePlanPolicy.connectedTargets(recovery = true),
        )
        assertEquals(4_000L, VpnProbePlanPolicy.PER_TARGET_TIMEOUT_MILLIS)
        assertEquals(10_000L, VpnProbePlanPolicy.TOTAL_TIMEOUT_MILLIS)
        assertEquals(1_500L, VpnProbePlanPolicy.STARTUP_PER_TARGET_TIMEOUT_MILLIS)
        assertEquals(3_000L, VpnProbePlanPolicy.STARTUP_TOTAL_TIMEOUT_MILLIS)
    }
}
