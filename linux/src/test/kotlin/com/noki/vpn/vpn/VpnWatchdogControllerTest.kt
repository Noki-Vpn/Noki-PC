package com.noki.vpn.vpn

import com.noki.vpn.data.DefaultStoredSettingsFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VpnWatchdogControllerTest {
    @Test
    fun `received primary traffic keeps a working tunnel healthy when all probe targets fail`() {
        var now = 1_000L
        val controller = VpnWatchdogController(WatchdogScheduler(), { now },
            { _, _ -> CancelableTask {} }, { false })
        val owner = watchdogOwner(40L)
        controller.start(owner, DefaultStoredSettingsFactory.create(), initialXrayEvidence = true)
        repeat(3) {
            now += 60_000L
            controller.forceProbe()
            controller.recordEvidence(WatchdogEvidence.UID_TRAFFIC)
            assertEquals(WatchdogProbeOutcome.Healthy, controller.completeProbe(owner, null))
        }
    }

    @Test
    fun `primary evidence expires and a dead core or stale network cannot use it`() {
        var now = 1_000L
        val controller = VpnWatchdogController(WatchdogScheduler(), { now },
            { _, _ -> CancelableTask {} }, { false })
        val owner = watchdogOwner(41L)
        controller.start(owner, DefaultStoredSettingsFactory.create(), initialXrayEvidence = true)
        controller.recordEvidence(WatchdogEvidence.UID_TRAFFIC, owner)
        controller.forceProbe()
        assertEquals(WatchdogProbeOutcome.Unhealthy(WatchdogAction.None), controller.completeProbe(owner, null, runtimeAlive = false))
        now += ConnectedWatchdogPolicy.PROBE_AFTER_IDLE_MS
        assertEquals(WatchdogAction.Refresh, failedProbe(controller, owner))
        controller.networkChanged()
        val current = checkNotNull(controller.currentOwner())
        controller.recordEvidence(WatchdogEvidence.UID_TRAFFIC, owner)
        assertEquals(WatchdogAction.None, failedProbe(controller, current))
        assertEquals(WatchdogAction.Refresh, failedProbe(controller, current))
    }

    @Test
    fun `primary receive progress postpones scheduled probes until the tunnel is idle`() {
        var now = 1_000L
        val scheduler = WatchdogScheduler()
        var probes = 0
        val controller = VpnWatchdogController(scheduler, { now },
            { _, _ -> probes++; CancelableTask {} }, { false })
        val owner = watchdogOwner(42L)
        controller.start(owner, DefaultStoredSettingsFactory.create(), initialXrayEvidence = true)
        repeat(5) {
            now += 60_000L
            controller.recordEvidence(WatchdogEvidence.UID_TRAFFIC, owner)
            scheduler.runPending()
        }
        assertEquals(0, probes)
        now += ConnectedWatchdogPolicy.PROBE_AFTER_IDLE_MS
        scheduler.runPending()
        assertEquals(1, probes)
    }

    @Test
    fun `explicit server remains diagnostic even after repeated failures in and outside lockdown`() {
        for (lockdown in listOf(false, true)) {
            val recoveries = ArrayDeque<Long>()
            val controller = VpnWatchdogController(WatchdogScheduler(), { 1_000L },
                { _, _ -> CancelableTask {} }, { lockdown }, recoveryTimes = recoveries)
            val owner = watchdogOwner(7L)
            val settings = DefaultStoredSettingsFactory.create().let {
                it.copy(userProfile = it.userProfile.copy(serverSelectionMode = com.noki.vpn.data.ServerSelectionMode.SERVER))
            }
            controller.start(owner, settings, initialXrayEvidence = true)
            repeat(10) { assertEquals(WatchdogAction.None, failedProbe(controller, owner)) }
            assertTrue(recoveries.isEmpty())
            controller.forceProbe()
            assertEquals(WatchdogProbeOutcome.Healthy, controller.completeProbe(owner, 25L))
        }
    }

    @Test
    fun `discarded current probe permits retry without resetting the failure budget`() {
        var launched = 0
        var cancelled = 0
        val cancellations = mutableListOf<WatchdogProbeOutcome>()
        lateinit var controller: VpnWatchdogController
        controller = VpnWatchdogController(
            scheduler = WatchdogScheduler(),
            nowMillis = { 1_000L },
            launchProbe = { owner, _ ->
                launched++
                CancelableTask {
                    cancelled++
                    cancellations += controller.completeProbe(owner, latencyMs = null)
                }
            },
            isLockdown = { false },
        )
        val owner = watchdogOwner(10L)
        controller.start(owner, DefaultStoredSettingsFactory.create(), initialXrayEvidence = true)
        assertEquals(WatchdogAction.None, failedProbe(controller, owner))
        controller.forceProbe()

        controller.discardProbe(owner)

        assertEquals(1, cancelled)
        assertEquals(listOf(WatchdogProbeOutcome.Ignored), cancellations)
        assertEquals(owner, controller.currentOwner())
        assertTrue(controller.hasFreshXrayEvidence())
        assertEquals(WatchdogAction.Refresh, failedProbe(controller, owner))
        assertEquals(3, launched)
    }

    @Test
    fun `stale discard preserves the current network probe`() {
        var launched = 0
        var cancelled = 0
        val controller = VpnWatchdogController(
            scheduler = WatchdogScheduler(),
            nowMillis = { 1_000L },
            launchProbe = { _, _ ->
                launched++
                CancelableTask { cancelled++ }
            },
            isLockdown = { false },
        )
        val owner = watchdogOwner(11L)
        controller.start(owner, DefaultStoredSettingsFactory.create(), initialXrayEvidence = true)
        controller.networkChanged()
        val current = checkNotNull(controller.currentOwner())
        controller.forceProbe()

        controller.discardProbe(owner)
        controller.forceProbe()

        assertEquals(0, cancelled)
        assertEquals(1, launched)
        assertEquals(WatchdogProbeOutcome.Healthy, controller.completeProbe(current, latencyMs = 25L))
    }

    @Test
    fun `network change cancels stale probe and evidence while preserving the runtime owner`() {
        var cancelled = 0
        val launched = mutableListOf<ConnectedWatchdogPolicy.Owner>()
        val controller = VpnWatchdogController(
            scheduler = WatchdogScheduler(),
            nowMillis = { 1_000L },
            launchProbe = { owner, _ ->
                launched += owner
                CancelableTask { cancelled++ }
            },
            isLockdown = { false },
        )
        val owner = watchdogOwner(7L)
        val settings = DefaultStoredSettingsFactory.create()
        controller.start(owner, settings, initialXrayEvidence = true)
        controller.forceProbe()

        controller.networkChanged()

        val updated = owner.copy(networkRevision = 1L)
        assertEquals(updated, controller.currentOwner())
        assertEquals(settings, controller.currentSettings())
        assertEquals(1, cancelled)
        assertFalse(controller.accepts(owner))
        assertFalse(controller.hasFreshXrayEvidence())
        controller.stop(owner)
        assertEquals(updated, controller.currentOwner())

        controller.forceProbe()
        assertEquals(listOf(owner, updated), launched)
        assertEquals(WatchdogProbeOutcome.Ignored, controller.completeProbe(owner, latencyMs = 25L))
        assertFalse(controller.hasFreshXrayEvidence())
        assertEquals(
            WatchdogProbeOutcome.Unhealthy(WatchdogAction.None),
            controller.completeProbe(updated, latencyMs = null),
        )
        assertEquals(WatchdogAction.Refresh, failedProbe(controller, updated))
    }

    @Test
    fun `network change replaces the scheduled probe with the current network owner`() {
        var now = 1_000L
        val scheduler = WatchdogScheduler()
        val launched = mutableListOf<ConnectedWatchdogPolicy.Owner>()
        val controller = VpnWatchdogController(
            scheduler = scheduler,
            nowMillis = { now },
            launchProbe = { owner, _ ->
                launched += owner
                CancelableTask {}
            },
            isLockdown = { false },
        )
        val owner = watchdogOwner(8L)
        controller.start(owner, DefaultStoredSettingsFactory.create(), initialXrayEvidence = true)

        controller.networkChanged()
        now += ConnectedWatchdogPolicy.CHECK_INTERVAL_MS
        scheduler.runPending()

        assertEquals(listOf(owner.copy(networkRevision = 1L)), launched)
    }

    @Test
    fun `network retries consume failures and preserve the recovery budget across handovers`() {
        val controller = VpnWatchdogController(
            scheduler = WatchdogScheduler(),
            nowMillis = { 1_000L },
            launchProbe = { _, _ -> CancelableTask {} },
            isLockdown = { false },
        )
        controller.start(watchdogOwner(9L), DefaultStoredSettingsFactory.create(), initialXrayEvidence = true)

        repeat(ConnectedWatchdogPolicy.MAX_RECOVERIES_IN_WINDOW) {
            controller.networkChanged()
            val current = checkNotNull(controller.currentOwner())
            assertEquals(WatchdogAction.None, failedProbe(controller, current))
            assertEquals(WatchdogAction.Refresh, failedProbe(controller, current))
        }

        controller.networkChanged()
        val current = checkNotNull(controller.currentOwner())
        assertEquals(WatchdogAction.None, failedProbe(controller, current))
        assertEquals(WatchdogAction.ReleaseOutsideLockdown, failedProbe(controller, current))
    }

    @Test
    fun `forced and scheduled requests coalesce to one in-flight Xray probe`() {
        var now = 0L
        val scheduler = WatchdogScheduler()
        val launched = mutableListOf<ConnectedWatchdogPolicy.Owner>()
        val controller = VpnWatchdogController(
            scheduler = scheduler,
            nowMillis = { now },
            launchProbe = { owner, _ ->
                launched += owner
                CancelableTask {}
            },
            isLockdown = { false },
        )
        val owner = watchdogOwner(core = 1L)
        controller.start(owner, DefaultStoredSettingsFactory.create(), initialXrayEvidence = true)

        now = ConnectedWatchdogPolicy.PROBE_AFTER_IDLE_MS
        scheduler.runPending()
        controller.forceProbe()

        assertEquals(listOf(owner), launched)

        controller.completeProbe(owner, latencyMs = 25L)
        controller.forceProbe()

        assertEquals(listOf(owner, owner), launched)
    }

    @Test
    fun `replacement rejects stale probe completion`() {
        val controller = VpnWatchdogController(
            scheduler = WatchdogScheduler(),
            nowMillis = { 1_000L },
            launchProbe = { _, _ -> CancelableTask {} },
            isLockdown = { false },
        )
        val first = watchdogOwner(core = 1L)
        val second = watchdogOwner(core = 2L)
        controller.start(first, DefaultStoredSettingsFactory.create(), initialXrayEvidence = true)
        controller.forceProbe()
        controller.start(second, DefaultStoredSettingsFactory.create(), initialXrayEvidence = true)

        val stale = controller.completeProbe(first, latencyMs = null)

        assertEquals(WatchdogProbeOutcome.Ignored, stale)
    }

    @Test
    fun `UID traffic cannot replace Xray probe evidence`() {
        var now = 1_000L
        val controller = VpnWatchdogController(
            scheduler = WatchdogScheduler(),
            nowMillis = { now },
            launchProbe = { _, _ -> CancelableTask {} },
            isLockdown = { false },
            evidenceFreshMillis = 5_000L,
        )
        val owner = watchdogOwner(core = 3L)
        controller.start(owner, DefaultStoredSettingsFactory.create(), initialXrayEvidence = false)

        controller.recordEvidence(WatchdogEvidence.UID_TRAFFIC)
        assertFalse(controller.hasFreshXrayEvidence())

        controller.recordEvidence(WatchdogEvidence.XRAY_PROBE)
        assertTrue(controller.hasFreshXrayEvidence())

        now += 5_001L
        assertFalse(controller.hasFreshXrayEvidence())
    }

    @Test
    fun `recovery budget produces typed lifecycle actions`() {
        var now = 1_000L
        val controller = VpnWatchdogController(
            scheduler = WatchdogScheduler(),
            nowMillis = { now },
            launchProbe = { _, _ -> CancelableTask {} },
            isLockdown = { false },
        )
        val owner = watchdogOwner(core = 4L)
        controller.start(owner, DefaultStoredSettingsFactory.create(), initialXrayEvidence = true)

        repeat(ConnectedWatchdogPolicy.MAX_RECOVERIES_IN_WINDOW) {
            assertEquals(WatchdogAction.None, failedProbe(controller, owner))
            assertEquals(WatchdogAction.Refresh, failedProbe(controller, owner))
            now += 1L
            controller.start(owner, DefaultStoredSettingsFactory.create(), initialXrayEvidence = true)
        }

        assertEquals(WatchdogAction.None, failedProbe(controller, owner))
        assertEquals(WatchdogAction.ReleaseOutsideLockdown, failedProbe(controller, owner))
    }

    @Test
    fun `completion without an in-flight probe is rejected`() {
        val controller = VpnWatchdogController(
            scheduler = WatchdogScheduler(),
            nowMillis = { 1_000L },
            launchProbe = { _, _ -> CancelableTask {} },
            isLockdown = { false },
        )
        val owner = watchdogOwner(core = 5L)
        controller.start(owner, DefaultStoredSettingsFactory.create(), initialXrayEvidence = true)

        assertEquals(WatchdogProbeOutcome.Ignored, controller.completeProbe(owner, latencyMs = 25L))
    }

    @Test
    fun `probe may complete before launcher returns its cancellation handle`() {
        lateinit var controller: VpnWatchdogController
        lateinit var completion: WatchdogProbeOutcome
        controller = VpnWatchdogController(
            scheduler = WatchdogScheduler(),
            nowMillis = { 1_000L },
            launchProbe = { owner, _ ->
                completion = controller.completeProbe(owner, latencyMs = 25L)
                CancelableTask {}
            },
            isLockdown = { false },
        )
        val owner = watchdogOwner(core = 6L)
        controller.start(owner, DefaultStoredSettingsFactory.create(), initialXrayEvidence = true)

        controller.forceProbe()

        assertEquals(WatchdogProbeOutcome.Healthy, completion)
    }

    private fun failedProbe(
        controller: VpnWatchdogController,
        owner: ConnectedWatchdogPolicy.Owner,
    ): WatchdogAction {
        controller.forceProbe()
        return (controller.completeProbe(owner, latencyMs = null) as WatchdogProbeOutcome.Unhealthy).action
    }

    private fun watchdogOwner(core: Long) = ConnectedWatchdogPolicy.Owner(
        generationId = core,
        tunnelIdentity = core.toInt(),
        coreIdentity = core,
    )
}

private class WatchdogScheduler : DelayedTaskScheduler {
    private val pending = mutableMapOf<Any, () -> Unit>()

    override fun schedule(owner: Any, delayMillis: Long, task: () -> Unit) {
        pending[owner] = task
    }

    override fun cancel(owner: Any) {
        pending.remove(owner)
    }

    fun runPending() {
        val tasks = pending.values.toList()
        pending.clear()
        tasks.forEach { it() }
    }
}
