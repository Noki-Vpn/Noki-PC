package com.noki.vpn.vpn

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VpnReadinessPolicyTest {
    @Test
    fun readinessRequiresPositiveEndToEndDelay() {
        assertFalse(VpnReadinessPolicy.accept(null))
        assertFalse(VpnReadinessPolicy.accept(0L))
        assertTrue(VpnReadinessPolicy.accept(120L))
    }

    @Test
    fun startupAndPostStartupFailuresRemainDistinct() {
        assertEquals(VpnReadinessPolicy.Failure.CoreStart, VpnReadinessPolicy.failureReason(started = false, delayMs = null))
        assertEquals(VpnReadinessPolicy.Failure.Probe, VpnReadinessPolicy.failureReason(started = true, delayMs = null))
        assertNull(VpnReadinessPolicy.failureReason(started = true, delayMs = 42L))
    }
}
