package com.noki.vpn.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectedWatchdogPolicyTest {
    @Test
    fun explicitServerDisablesAutomaticRecovery() {
        for (mode in com.noki.vpn.data.ServerSelectionMode.entries) {
            assertEquals(mode != com.noki.vpn.data.ServerSelectionMode.SERVER,
                ConnectedWatchdogPolicy.automaticRecoveryAllowed(mode))
        }
    }

    @Test
    fun unexpectedTransitionExceptionDuringLockdownRecoveryKeepsTruthfulFailedTunnel() {
        assertEquals(
            ConnectedWatchdogPolicy.TransitionExceptionDecision.KeepTruthfulLockdownFailure,
            ConnectedWatchdogPolicy.transitionExceptionDecision(activeLockdownRecovery = true),
        )
    }

    @Test
    fun lockdownRetryFailureStaysFailedAndReschedules() {
        assertEquals(
            ConnectedWatchdogPolicy.RetryFailureDecision.KeepTruthfulFailedAndReschedule,
            ConnectedWatchdogPolicy.retryFailureDecision(
                isLockdown = true,
            ),
        )
    }

    @Test
    fun retryFailureOutsideLockdownUsesGeneralFailure() {
        assertEquals(
            ConnectedWatchdogPolicy.RetryFailureDecision.UseGeneralFailure,
            ConnectedWatchdogPolicy.retryFailureDecision(isLockdown = false),
        )
    }

    @Test
    fun staleProbeCannotAffectReplacementGenerationCoreOrTunnel() {
        val staleOwner = ConnectedWatchdogPolicy.Owner(
            generationId = 7L,
            tunnelIdentity = 11,
            coreIdentity = 13L,
        )
        val replacementOwner = ConnectedWatchdogPolicy.Owner(
            generationId = 8L,
            tunnelIdentity = 17,
            coreIdentity = 19L,
        )

        assertFalse(ConnectedWatchdogPolicy.canApplyResult(staleOwner, replacementOwner))
        assertTrue(ConnectedWatchdogPolicy.canApplyResult(replacementOwner, replacementOwner))
    }

    @Test
    fun staleNetworkRevisionCannotAffectTheSameRuntime() {
        val owner = ConnectedWatchdogPolicy.Owner(7L, 11, 13L)
        val reboundOwner = owner.copy(networkRevision = 1L)

        assertFalse(ConnectedWatchdogPolicy.canApplyResult(owner, reboundOwner))
        assertTrue(ConnectedWatchdogPolicy.canApplyResult(reboundOwner, reboundOwner))
    }

    @Test
    fun twoIdleMinutesRequireProbe() {
        assertEquals(
            ConnectedWatchdogPolicy.TrafficDecision.Probe,
            ConnectedWatchdogPolicy.probeScheduleDecision(
                millisSinceHealthy = ConnectedWatchdogPolicy.PROBE_AFTER_IDLE_MS,
            ),
        )
    }

    @Test
    fun secondProbeFailureRequestsRecovery() {
        assertEquals(
            ConnectedWatchdogPolicy.ProbeDecision.Recover,
            ConnectedWatchdogPolicy.probeDecision(success = false, previousFailures = 1),
        )
    }

    @Test
    fun fourthRecoveryInsideTenMinutesFailsClosed() {
        val now = 1_000_000L
        assertEquals(
            ConnectedWatchdogPolicy.RecoveryDecision.FailClosed,
            ConnectedWatchdogPolicy.recoveryDecision(
                recoveryTimes = listOf(now - 1L, now - 2L, now - 3L),
                nowMillis = now,
            ),
        )
    }

    @Test
    fun exhaustedRecoveryReleasesTunnelOutsideLockdown() {
        assertEquals(
            ConnectedWatchdogPolicy.ExhaustedRecoveryDecision.ReleaseTunnel,
            ConnectedWatchdogPolicy.exhaustedRecoveryDecision(isLockdown = false),
        )
        assertEquals(
            "watchdog_recovery_exhausted",
            ConnectedWatchdogPolicy.failureReason(isLockdown = false),
        )
    }

    @Test
    fun exhaustedRecoveryReportsLockdownAndWaitsForRecoveryWindow() {
        val now = 1_000_000L
        assertEquals(
            ConnectedWatchdogPolicy.ExhaustedRecoveryDecision.ReportLockdownBlockedAndRetry,
            ConnectedWatchdogPolicy.exhaustedRecoveryDecision(isLockdown = true),
        )
        assertEquals(
            "watchdog_recovery_exhausted_lockdown",
            ConnectedWatchdogPolicy.failureReason(isLockdown = true),
        )
        assertEquals(
            ConnectedWatchdogPolicy.RECOVERY_WINDOW_MS - 1_000L,
            ConnectedWatchdogPolicy.lockdownRetryDelayMillis(
                recoveryTimes = listOf(now - 1_000L, now - 500L, now - 100L),
                nowMillis = now,
            ),
        )
    }

    @Test
    fun unexpectedDropRetriesCurrentServerOnceBeforeAnotherPermittedServer() {
        assertEquals(
            listOf(
                ConnectedWatchdogPolicy.RecoveryTarget(locationCode = "lv-2"),
                ConnectedWatchdogPolicy.RecoveryTarget(excludeLocationCode = "lv-2"),
            ),
            ConnectedWatchdogPolicy.recoveryTargets("lv-2"),
        )
    }

    @Test
    fun transientControlPlaneFailureDoesNotConsumeEndpointAttempt() {
        assertEquals(
            ConnectedWatchdogPolicy.PreparationFailureDecision.RetryWithoutConsumingEndpointAttempt,
            ConnectedWatchdogPolicy.preparationFailureDecision(
                isTransient = true,
                preparedSessionAvailable = false,
            ),
        )
    }

    @Test
    fun preparedSessionAllowsRealEndpointAttempt() {
        assertEquals(
            ConnectedWatchdogPolicy.PreparationFailureDecision.CountEndpointAttempt,
            ConnectedWatchdogPolicy.preparationFailureDecision(
                isTransient = true,
                preparedSessionAvailable = true,
            ),
        )
    }

    @Test
    fun transientControlPlaneBackoffCapsAtFiveMinutes() {
        val ceilings = listOf(15_000L, 30_000L, 60_000L, 120_000L, 240_000L, 300_000L)
        for ((attempt, ceiling) in ceilings.withIndex()) {
            assertEquals(ceiling / 2, ConnectedWatchdogPolicy.transientRetryDelayMillis(attempt, 0.0))
            assertEquals(ceiling * 3 / 4, ConnectedWatchdogPolicy.transientRetryDelayMillis(attempt, 0.5))
            assertEquals(ceiling, ConnectedWatchdogPolicy.transientRetryDelayMillis(attempt, 1.0))
            assertTrue(ConnectedWatchdogPolicy.transientRetryDelayMillis(attempt) in ceiling / 2..ceiling)
        }
        assertEquals(300_000L, ConnectedWatchdogPolicy.transientRetryDelayMillis(Int.MAX_VALUE, 1.0))
        assertEquals(7_500L, ConnectedWatchdogPolicy.transientRetryDelayMillis(Int.MIN_VALUE, 0.0))
    }

    @Test
    fun firstTransientRestartUsesCacheThenRequiresFreshSelection() {
        assertEquals(
            VpnPreparationStrategy.CachedFirst,
            ConnectedWatchdogPolicy.transientStartDecision(attempt = 0),
        )
        assertEquals(
            VpnPreparationStrategy.FreshOnly,
            ConnectedWatchdogPolicy.transientStartDecision(attempt = 1),
        )
    }
}
