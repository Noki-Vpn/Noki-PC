package com.noki.desktop

import com.noki.vpn.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import okhttp3.OkHttpClient
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONObject
import org.json.JSONArray
import okhttp3.Protocol
import java.util.concurrent.CopyOnWriteArrayList
import okio.Buffer

class DesktopConnectionTest {
    @get:org.junit.Rule val mainDispatcher: org.junit.rules.TestRule = DesktopMainDispatcherRule()

    @Test fun `native runtime logs survive removal of temporary config without synthetic UI diagnostics`() = runBlocking {
        Fixture().use { f ->
            f.dnsSnapshot = { WindowsDnsPolicy(emptyList(), emptyList()) }
            f.ready()
            f.app.connect()
            withTimeout(15_000) { f.app.state.first { !it.loading && it.verified } }
            assertFalse(Files.exists(f.vault.directory.resolve("vpn.json")))
            val dns = f.mainConfig().getJSONObject("dns")
            dns.getJSONArray("servers").put(JSONObject().put("tag", "system-dns-test").put("server", "192.0.2.53").put("bind_interface", "Test adapter"))
            dns.getJSONArray("rules").put(JSONObject().put("domain_suffix", "rdp.test").put("action", "evaluate").put("server", "system-dns-test"))
            val nativeMessage = "Native fixture: rdp.test via system-dns-test on Test adapter"
            f.runtime.logLines = listOf(SingBoxRuntime.CoreLogLine.decode(JSONObject().put("type", "info").put("payload", nativeMessage).toString()))
            val collecting = launch { f.app.collectCoreLogs() }
            try {
                val lines = withTimeout(5_000) { f.app.localCoreLogs.first { lines -> lines.any { "rdp.test" in it.message } } }
                assertEquals(listOf(nativeMessage), lines.map { it.message })
                assertTrue(lines.none { f.sessionSecret in it.message || "NoSuchFileException" in it.message })
            } finally { collecting.cancelAndJoin() }
        }
    }

    @Test fun `explicit node startup does not sample other locations or repeat bootstrap`() = runBlocking {
        Fixture(nodeCount = 3).use { f ->
            f.ready()
            f.app.selectServer(f.app.state.value.servers.last().id)
            withTimeout(5_000) { f.app.state.first { !it.loading && it.settings.userProfile.serverSelectionMode == ServerSelectionMode.SERVER } }
            val bootstraps = f.bootstrapCalls.get()
            f.tcpCalls.set(0)
            f.app.connect()
            withTimeout(15_000) { f.app.state.first { !it.loading && it.verified } }
            assertEquals(0, f.tcpCalls.get())
            assertEquals(bootstraps, f.bootstrapCalls.get())
            assertEquals(1, f.locationsCalls.get())
            assertEquals(f.app.state.value.settings.userProfile.selectedNodeId, f.sessionNodes.single())
        }
    }

    @Test fun `local log controls apply immediately without reconnect feedback or a new session`() = runBlocking {
        Fixture().use { f ->
            f.ready()
            f.app.connect()
            withTimeout(15_000) { f.app.state.first { !it.loading && it.verified } }
            val starts = f.runtime.starts.size
            val requests = f.sessionRequests.size
            for (visible in listOf(true, false)) {
                f.app.updateSettings { it.copy(advancedSettings = it.advancedSettings.copy(showLocalCoreLogs = visible)) }.join()
                assertNull(f.app.state.value.message)
                assertEquals(visible, f.app.state.value.settings.advancedSettings.showLocalCoreLogs)
            }
            f.app.setLoggingEnabled(false).join()
            assertNull(f.app.state.value.message)
            f.app.updateSettings { it.copy(advancedSettings = it.advancedSettings.copy(killSwitchEnabled = true)) }.join()
            assertTrue(f.app.state.value.message.orEmpty().contains("следующем подключении"))
            assertEquals(starts, f.runtime.starts.size)
            assertEquals(requests, f.sessionRequests.size)
        }
    }

    @Test fun `each visible disconnect replenishes single use preparation before the next connection`() = runBlocking {
        Fixture().use { f ->
            f.ready()
            repeat(3) { cycle ->
                f.prepareInAdvance()
                f.app.setWindowVisible(true)
                f.app.connect()
                withTimeout(15_000) { f.app.state.first { !it.loading && it.verified } }
                assertEquals(cycle + 1, f.sessionRequests.size)
                f.app.disconnect()
                withTimeout(5_000) { f.app.state.first { !it.loading && !it.running } }
                val field = DesktopController::class.java.getDeclaredField("preparationJob").apply { isAccessible = true }
                withTimeout(15_000) { while ((field.get(f.app) as? Deferred<*>)?.isCompleted != true) delay(10) }
                assertEquals(cycle + 2, f.sessionRequests.size)
            }
        }
    }

    @Test fun `prepared admission is single use and rejects expiry network and settings changes`() = runBlocking {
        for (change in listOf("none", "expired", "network", "settings", "auth")) Fixture().use { f ->
            f.ready()
            f.prepareInAdvance()
            val preparedRequests = f.sessionRequests.size
            assertEquals(1, preparedRequests)
            when (change) {
                "expired" -> f.clock.addAndGet(60_001)
                "network" -> f.networkSignature = "another-physical-network"
                "settings" -> f.app.selectEndpoint(EndpointGroupPolicy.protocolOptions().first { it.code == "reality-xhttp" }).join()
                "auth" -> (DesktopController::class.java.getDeclaredField("authEpoch").apply { isAccessible = true }
                    .get(f.app) as java.util.concurrent.atomic.AtomicLong).incrementAndGet()
            }
            f.app.connect()
            withTimeout(15_000) { f.app.state.first { !it.loading && it.verified } }
            assertEquals(if (change == "none") 1 else 2, f.sessionRequests.size)
            f.app.disconnect()
            withTimeout(5_000) { f.app.state.first { !it.loading && !it.running } }
            f.app.connect()
            withTimeout(15_000) { f.app.state.first { !it.loading && it.verified } }
            assertEquals(if (change == "none") 2 else 3, f.sessionRequests.size)
        }
    }

    @Test fun `prepared admission survives cosmetic and positive quota refresh and uses fresh presentation`() = runBlocking {
        Fixture().use { f ->
            f.ready()
            f.bootstrap.getJSONObject("subscription").put("traffic_limit_gb", 100).put("traffic_used_gb", 1)
                .put("shared_traffic_limit_gb", 10).put("shared_traffic_used_gb", 1).put("trial_traffic_remaining_gb", 5)
            f.app.refresh().await()
            f.prepareInAdvance()
            f.app.updateSettings { it.copy(
                userProfile = it.userProfile.copy(username = "New", email = "new@example.invalid", avatarUri = "new-avatar", trafficUsedGb = 3.0),
                personalizationSettings = it.personalizationSettings.copy(language = AppLanguage.EN),
            ) }.join()
            f.bootstrap.getJSONObject("user").put("username", "New account name").put("email", "new-account@example.invalid")
            f.bootstrap.getJSONObject("subscription").put("traffic_used_gb", 2).put("shared_traffic_used_gb", 2).put("trial_traffic_remaining_gb", 4)
            f.app.refresh().await()
            f.app.connect()
            withTimeout(10_000) { f.app.state.first { !it.loading && it.verified } }
            assertEquals(1, f.sessionRequests.size)
            assertEquals(AppLanguage.EN, f.app.state.value.connectionSettings?.personalizationSettings?.language)
            assertEquals("new-avatar", f.app.state.value.connectionSettings?.userProfile?.avatarUri)
            assertEquals("New account name", f.app.state.value.user?.username)
        }
    }

    @Test fun `prepared admission invalidates exhausted quota tariff and device credentials`() = runBlocking {
        for (change in listOf("trial", "primary", "shared", "plan", "device")) Fixture().use { f ->
            f.ready()
            val subscription = f.bootstrap.getJSONObject("subscription")
                .put("traffic_limit_gb", 10).put("traffic_used_gb", 1)
                .put("shared_traffic_limit_gb", 10).put("shared_traffic_used_gb", 1).put("trial_traffic_remaining_gb", 5)
            f.app.refresh().await()
            f.prepareInAdvance()
            when (change) {
                "trial" -> subscription.put("trial_traffic_remaining_gb", 0)
                "primary" -> subscription.put("traffic_used_gb", 10)
                "shared" -> subscription.put("shared_traffic_used_gb", 10)
                "plan" -> subscription.put("plan_code", "another-plan")
                "device" -> f.bootstrap.getJSONArray("devices").getJSONObject(0).put("device_key", "new-device-key")
            }
            f.app.refresh().await()
            f.app.connect()
            withTimeout(10_000) { f.app.state.first { !it.loading && it.verified } }
            assertEquals("Admission change: $change", 2, f.sessionRequests.size)
        }
    }

    @Test fun `connect joins in flight preparation without repeating admission`() = runBlocking {
        Fixture().use { f ->
            f.ready()
            val gate = java.util.concurrent.CountDownLatch(1)
            f.sessionGate = gate
            try {
                f.app.setWindowVisible(true)
                withTimeout(5_000) { while (f.sessionRequests.isEmpty()) delay(10) }
                f.app.connect()
                withTimeout(5_000) { f.app.state.first { it.connecting } }
                assertEquals(1, f.sessionRequests.size)
                gate.countDown()
                withTimeout(10_000) { f.app.state.first { !it.loading && it.verified } }
                assertEquals(1, f.sessionRequests.size)
            } finally { gate.countDown() }
        }
    }

    @Test fun `startup tries another transport family before another alias`() = runBlocking {
        Fixture(nvCandidates = true, hy2Candidates = true).use { f ->
            f.ready()
            f.runtime.failProbes.set(2)
            f.app.connect()
            withTimeout(10_000) { f.app.state.first { !it.loading && it.verified } }
            assertEquals(listOf("nokinv", "vless", "hysteria2"),
                f.runtime.starts.map { it.getJSONArray("outbounds").getJSONObject(0).getString("type") })
        }
    }

    @Test fun `underlay loss and rebind preserve runtime timer and admission with retry`() = runBlocking {
        Fixture().use { f ->
            f.ready()
            f.app.connect()
            withTimeout(10_000) { f.app.state.first { !it.loading && it.verified } }
            val active = f.runtime.active
            val requests = f.sessionRequests.size
            val locations = f.locationsCalls.get()
            val probes = f.runtime.probeCalls.get()
            f.networkAvailable = false
            f.networkSignature = "offline"
            withTimeout(5_000) { f.app.state.first { !it.verified } }
            assertTrue(f.app.state.value.networkRebinding)
            assertTrue(f.app.state.value.hasEstablishedTunnel)
            f.app.verifyConnection()
            delay(100)
            assertEquals(probes, f.runtime.probeCalls.get())
            assertEquals(active, f.runtime.active)
            assertTrue(f.runtime.stops.isEmpty())
            f.runtime.failRebinds.set(1)
            f.networkAvailable = true
            f.networkSignature = "new-physical-network"
            withTimeout(10_000) { f.app.state.first { it.verified } }
            assertEquals(2, f.runtime.rebindCalls.get())
            assertEquals(active, f.runtime.active)
            assertEquals(active?.startedAt, f.app.state.value.connectedAtMillis)
            assertFalse(f.app.state.value.networkRebinding)
            assertEquals(requests, f.sessionRequests.size)
            assertEquals(locations, f.locationsCalls.get())
            assertEquals(1, f.runtime.starts.size)
        }
    }

    @Test fun `repeated rebind failure returns control to recovery`() = runBlocking {
        Fixture().use { f ->
            f.ready(); f.app.connect()
            withTimeout(10_000) { f.app.state.first { !it.loading && it.verified } }
            val node = f.runtime.active?.serverId
            f.runtime.failRebinds.set(100)
            f.networkSignature = "replacement-network"
            withTimeout(15_000) { f.app.state.first { f.runtime.starts.size > 1 && !it.loading && it.verified } }
            assertEquals(3, f.runtime.rebindCalls.get())
            assertEquals(node, f.runtime.active?.serverId)
            assertFalse(f.app.state.value.networkRebinding)
        }
    }

    @Test fun `maintenance survives a transient runtime snapshot failure`() = runBlocking {
        Fixture().use { f ->
            f.ready(); f.app.connect()
            withTimeout(10_000) { f.app.state.first { !it.loading && it.verified } }
            val count = f.runtime.metricsCalls.get()
            f.runtime.failRestores.set(1)
            withTimeout(7_000) {
                while (f.runtime.failRestores.get() != 0 || f.runtime.metricsCalls.get() <= count) delay(20)
            }
            assertTrue(f.app.state.value.verified)
        }
    }

    @Test fun `old network watchdog failure cannot damage a rebound connection`() = runBlocking {
        Fixture().use { f ->
            f.ready()
            f.app.connect()
            withTimeout(10_000) { f.app.state.first { !it.loading && it.verified } }
            val active = f.runtime.active
            val oldGate = CompletableDeferred<Unit>()
            f.runtime.probeGate = oldGate
            f.runtime.probeIgnoresCancellation = true
            val count = f.runtime.probeCalls.get()
            f.app.verifyConnection()
            withTimeout(5_000) { while (f.runtime.probeCalls.get() == count) delay(10) }
            f.runtime.probeGate = null
            f.networkSignature = "new-network"
            withTimeout(8_000) { while (f.runtime.rebindCalls.get() == 0 || !f.app.state.value.verified) delay(10) }
            f.runtime.failProbes.set(1)
            oldGate.complete(Unit)
            delay(100)
            assertTrue(f.app.state.value.verified)
            assertEquals(active, f.runtime.active)
            assertEquals(1, f.runtime.starts.size)
            assertEquals(1, f.sessionRequests.size)
        }
    }

    @Test fun `network loss during watchdog probe or operation wait cannot penalize endpoint or restart session`() = runBlocking {
        for (waitForOperation in listOf(false, true)) Fixture().use { f ->
            f.ready()
            f.app.connect()
            withTimeout(10_000) { f.app.state.first { !it.loading && it.verified } }
            f.runtime.failProbes.set(1)
            f.app.verifyConnection()
            withTimeout(5_000) { f.app.state.first { !it.verified } }
            val health = f.vault.readEncrypted("endpoint-health.dpapi")
            val active = f.runtime.active
            val generation = DesktopController::class.java.getDeclaredField("connectionGeneration").apply { isAccessible = true }
            val before = generation.getLong(f.app)
            val operation = DesktopController::class.java.getDeclaredField("operation").apply { isAccessible = true }
                .get(f.app) as kotlinx.coroutines.sync.Mutex
            val gate = CompletableDeferred<Unit>()
            f.runtime.probeGate = gate
            f.runtime.failProbes.set(1)
            val probes = f.runtime.probeCalls.get()
            f.app.verifyConnection()
            withTimeout(5_000) { while (f.runtime.probeCalls.get() == probes) delay(10) }
            if (waitForOperation) operation.lock()
            try {
                if (waitForOperation) { gate.complete(Unit); delay(50) }
                f.networkAvailable = false
                f.networkSignature = "offline"
                if (!waitForOperation) gate.complete(Unit)
            } finally { if (waitForOperation) operation.unlock() }
            delay(150)
            assertEquals(before, generation.getLong(f.app))
            assertEquals(active, f.runtime.active)
            assertEquals(health, f.vault.readEncrypted("endpoint-health.dpapi"))
            assertEquals(1, f.runtime.starts.size)
            assertEquals(1, f.sessionRequests.size)
        }
    }

    @Test fun `one NV failure excludes aliases for this attempt and permits a new manual NV attempt`() = runBlocking {
        Fixture(nodeCount = 2, nvCandidates = true).use { f ->
            f.ready()
            f.runtime.failProbes.set(3)
            f.app.connect()
            withTimeout(15_000) { f.app.state.first { !it.loading && it.verified } }
            fun protocols() = f.runtime.starts.map { it.getJSONArray("outbounds").getJSONObject(0).getString("type") }
            assertEquals(listOf("nokinv", "vless", "vless", "vless"), protocols())
            assertEquals(2, f.sessionNodes.size)
            f.app.disconnect()
            withTimeout(5_000) { f.app.state.first { !it.loading && !it.running } }
            f.app.selectEndpoint(EndpointGroupPolicy.protocolOptions(f.app.state.value.endpointOptions).first { it.proxyType == "nokinv" }).join()
            assertEquals(EndpointSelectionMode.MANUAL, f.app.state.value.settings.advancedSettings.endpointSelectionMode)
            f.app.connect()
            withTimeout(15_000) { f.app.state.first { !it.loading && it.verified } }
            assertEquals("nokinv", protocols().last())
        }
    }

    @Test fun `manual NV carrier survives connecting another node and keeps actual runtime profile`() = runBlocking {
        for (mode in listOf("quic")) Fixture(nodeCount = 2, nvCandidates = true).use { f ->
            f.ready()
            val option = EndpointGroupPolicy.protocolOptions().single { it.proxyType == "nokinv" && it.transportMode == mode }
            f.app.selectEndpoint(option).join()
            repeat(2) { index ->
                if (index > 0) {
                    f.app.disconnect()
                    withTimeout(5_000) { f.app.state.first { !it.loading && !it.running } }
                    f.app.selectServer(f.app.state.value.servers.last().id)
                    withTimeout(5_000) { f.app.state.first { !it.loading && it.settings.userProfile.serverSelectionMode == ServerSelectionMode.SERVER } }
                }
                f.app.connect()
                val state = withTimeout(10_000) { f.app.state.first { !it.loading && it.verified } }
                assertEquals(mode, f.runtime.starts.last().getJSONArray("outbounds").getJSONObject(0).getString("transport"))
                assertEquals(mode, state.connectionSettings?.profile?.transportMode)
                assertEquals(EndpointGroupPolicy.groupKey(option), state.settings.advancedSettings.manualEndpointGroupKey)
                assertEquals(state.connectionSettings?.profile?.endpointCode, state.settings.advancedSettings.manualEndpointCode)
            }
        }
    }

    @Test fun `gaming keeps manual preference and cannot fall back to another protocol`() = runBlocking {
        Fixture(nvCandidates = true).use { f ->
            f.ready()
            f.app.selectEndpoint(EndpointGroupPolicy.protocolOptions().first { it.code == "reality-xhttp" }).join()
            f.app.setGamingMode(true).join()
            f.runtime.failProbes.set(2)
            f.app.connect()
            withTimeout(15_000) { f.app.state.first { !it.loading && it.message != null } }
            assertFalse(f.app.state.value.verified)
            assertEquals(2, f.runtime.starts.size)
            assertTrue(f.runtime.starts.all { config ->
                config.getJSONArray("outbounds").getJSONObject(0).let { it.getString("type") == "nokinv" && it.getString("mode") == "gaming" }
            })
            assertEquals("reality|xhttp|stream-up", f.app.state.value.settings.advancedSettings.manualEndpointGroupKey)
            f.app.setGamingMode(false).join()
            f.app.connect()
            withTimeout(15_000) { f.app.state.first { !it.loading && it.verified } }
            assertEquals("vless", f.runtime.starts.last().getJSONArray("outbounds").getJSONObject(0).getString("type"))
        }
    }

    @Test fun `visible account refresh uses five minutes and focus requires a minute of stale data`() = runBlocking {
        Fixture().use { f ->
            f.ready()
            val now = System.currentTimeMillis()
            f.app.setWindowVisible(true)
            assertNull(f.app.pollAccountData(now + 61_000))
            assertNull(f.app.pollAccountData(now + 4 * 60_000))
            assertNotNull(f.app.pollAccountData(now + 5 * 60_000 + 1000))
            withTimeout(5_000) { while (f.bootstrapCalls.get() < 2) delay(10) }
            f.app.setWindowVisible(false)
            f.app.refresh().await()
            assertNull(f.app.pollAccountData(System.currentTimeMillis(), onFocus = true))
            assertNotNull(f.app.pollAccountData(System.currentTimeMillis() + 61_000, onFocus = true))
        }
    }

    @Test fun `returning focus retries stale data immediately after a failed background request`() = runBlocking {
        Fixture(automaticLogs = false).use { f ->
            f.ready()
            val now = System.currentTimeMillis()
            f.bootstrapStatus.set(503)
            f.app.pollAccountData(now + 15 * 60_000 + 1000)?.join()
            assertNotNull(f.app.pollAccountData(now + 15 * 60_000 + 11_000, onFocus = true))
        }
    }

    @Test fun `guard status failure clears busy state and does not lock later actions`() = runBlocking {
        Fixture().use { f ->
            f.ready()
            f.runtime.failGuardRead = true
            withTimeout(5_000) { f.app.setEarthDisabled(true).join() }
            assertFalse(f.app.state.value.loading)
            assertTrue(f.app.state.value.networkBlocked)
            withTimeout(5_000) { f.app.setEarthDisabled(false).join() }
            assertFalse(f.app.state.value.earthDisabled)
            assertFalse(f.app.state.value.loading)
        }
    }

    @Test fun `device reads coalesce without blocking settings and mutations discard their stale replies`() = runBlocking {
        Fixture().use { f ->
            f.ready()
            val gate = java.util.concurrent.CountDownLatch(1)
            f.incyReadGate = gate
            val loading = checkNotNull(f.app.loadDevices())
            try {
                withTimeout(5_000) { f.incyReadStarted.await() }
                assertSame(loading, f.app.loadDevices())
                assertTrue(f.app.state.value.devicesLoading)
                assertFalse(f.app.state.value.loading)
                withTimeout(5_000) { f.app.setEarthDisabled(true).join() }
                assertTrue(f.app.state.value.earthDisabled)
                assertFalse(f.app.state.value.loading)
                f.app.createIncy("New device")
                withTimeout(5_000) { f.app.state.first { it.loading } }
                gate.countDown()
                withTimeout(5_000) { f.app.state.first { !it.loading && !it.devicesLoading } }
                assertTrue(loading.isCancelled)
                assertTrue(f.app.state.value.incyDevices.isEmpty())
            } finally { gate.countDown() }
        }
    }

    @Test fun `moving the connected device discards old credentials and verifies a fresh profile before releasing its temporary guard`() = runBlocking {
        Fixture().use { f ->
            f.ready(); f.app.connect()
            val previous = withTimeout(15_000) { f.app.state.first { !it.loading && it.verified } }
            val publicKey = f.vault.wireGuardKeyPair().second
            val starts = f.runtime.starts.size
            assertTrue(f.runtime.starts.last().toString().contains(f.sessionSecret))

            f.app.setDeviceTrafficPool(previous.currentDeviceId, "shared")
            val moved = withTimeout(15_000) { f.app.state.first { !it.loading && it.verified && it.devices.single().trafficPool == "shared" } }

            assertEquals(starts + 1, f.runtime.starts.size)
            assertEquals(2, f.sessionRequests.size)
            assertTrue(f.runtime.starts.last().toString().contains(f.sessionSecret))
            assertFalse(f.runtime.starts.last().toString().contains("00000000-0000-4000-8000-000000000099"))
            assertEquals(listOf("protect", "stop:false", "start:true", "release"), f.runtime.guardEvents.takeLast(4))
            assertFalse(f.runtime.armed)
            assertFalse(moved.settings.advancedSettings.killSwitchEnabled)
            assertEquals(publicKey, f.vault.wireGuardKeyPair().second)
            assertEquals(3.5, checkNotNull(moved.subscription?.sharedTrafficUsedGb), 0.0)
            assertEquals(40.0, checkNotNull(moved.subscription?.sharedTrafficLimitGb), 0.0)
            assertTrue(checkNotNull(f.vault.readEncrypted("connection.dpapi")).contains(f.sessionSecret))
            assertFalse(checkNotNull(f.vault.readEncrypted("connection.dpapi")).contains("00000000-0000-4000-8000-000000000099"))
            assertEquals(0, f.proofFailures.get())
        }
    }

    @Test fun `a failed profile refresh after moving the current device cannot restore revoked credentials or release the guard`() = runBlocking {
        Fixture().use { f ->
            f.ready(); f.app.connect()
            val connected = withTimeout(15_000) { f.app.state.first { !it.loading && it.verified } }
            f.sessionStatus.set(403)

            f.app.setDeviceTrafficPool(connected.currentDeviceId, "shared")
            val failed = withTimeout(15_000) { f.app.state.first { !it.loading && it.message != null && it.devices.single().trafficPool == "shared" } }

            assertFalse(failed.running)
            assertFalse(failed.verified)
            assertTrue(failed.networkBlocked)
            assertTrue(f.runtime.armed)
            assertEquals(listOf("protect", "stop:false"), f.runtime.guardEvents.takeLast(2))
            assertEquals(1, f.runtime.starts.size)
            assertNull(f.vault.readEncrypted("connection.dpapi"))
            assertNull(failed.connectionSettings)
            assertTrue(failed.settings.endpointOptions.isEmpty())
            assertEquals(VlessProfile(), f.vault.loadSettings().profile)
            assertFalse(f.vault.loadSettings().advancedSettings.killSwitchEnabled)
        }
    }

    @Test fun `a failed guard setup leaves the existing tunnel in place but invalidates its revoked recovery profile`() = runBlocking {
        Fixture().use { f ->
            f.ready(); f.app.connect()
            val connected = withTimeout(15_000) { f.app.state.first { !it.loading && it.verified } }
            val active = f.runtime.active
            f.runtime.failProtection = true

            f.app.setDeviceTrafficPool(connected.currentDeviceId, "shared")
            val failed = withTimeout(10_000) { f.app.state.first { !it.loading && it.message != null && it.devices.single().trafficPool == "shared" } }

            assertSame(active, f.runtime.active)
            assertTrue(failed.running)
            assertFalse(failed.verified)
            assertTrue(f.runtime.stops.isEmpty())
            assertEquals(1, f.runtime.starts.size)
            assertNull(f.vault.readEncrypted("connection.dpapi"))
            assertNull(failed.connectionSettings)
        }
    }

    @Test fun `a lost successful pool move reply invalidates the old profile without inventing a local pool result`() = runBlocking {
        Fixture().use { f ->
            f.ready(); f.app.connect()
            val connected = withTimeout(15_000) { f.app.state.first { !it.loading && it.verified } }
            f.poolReplyLost = true

            f.app.setDeviceTrafficPool(connected.currentDeviceId, "shared")
            val failed = withTimeout(10_000) { f.app.state.first { !it.loading && it.message != null } }

            assertEquals("shared", f.bootstrap.getJSONArray("devices").getJSONObject(0).getString("traffic_pool"))
            assertEquals(connected.devices, failed.devices)
            assertFalse(failed.running)
            assertTrue(failed.networkBlocked)
            assertTrue(f.runtime.armed)
            assertEquals(listOf("protect", "stop:false"), f.runtime.guardEvents.takeLast(2))
            assertNull(f.vault.readEncrypted("connection.dpapi"))
            assertEquals(1, f.runtime.starts.size)
        }
    }

    @Test fun `a full primary pool is rejected without changing devices or the active tunnel`() = runBlocking {
        Fixture(trafficPool = "shared").use { f ->
            f.ready(); f.app.connect()
            val connected = withTimeout(15_000) { f.app.state.first { !it.loading && it.verified } }
            val active = f.runtime.active
            val journal = f.vault.readEncrypted("connection.dpapi")
            f.poolStatus.set(409)

            f.app.setDeviceTrafficPool(connected.currentDeviceId, "primary")
            val refused = withTimeout(10_000) { f.app.state.first { !it.loading && it.message != null } }

            assertEquals(connected.devices, refused.devices)
            assertSame(active, f.runtime.active)
            assertEquals(journal, f.vault.readEncrypted("connection.dpapi"))
            assertTrue(refused.verified)
            assertTrue(f.runtime.stops.isEmpty())
            assertEquals(listOf("start:false"), f.runtime.guardEvents)
            assertTrue(checkNotNull(refused.message).let { it.contains("No primary device slots") || it.contains("нет свободного места") })
        }
    }

    @Test fun `cancelling a pool move after server commit cannot leave the revoked recovery profile`() = runBlocking {
        Fixture().use { f ->
            f.ready(); f.app.connect()
            val connected = withTimeout(15_000) { f.app.state.first { !it.loading && it.verified } }
            val scope = DesktopController::class.java.getDeclaredField("scope").apply { isAccessible = true }.get(f.app) as CoroutineScope
            val owner = checkNotNull(scope.coroutineContext[Job])
            val previousJobs = owner.children.toSet()
            val gate = java.util.concurrent.CountDownLatch(1)
            f.poolCommitGate = gate

            f.app.setDeviceTrafficPool(connected.currentDeviceId, "shared")
            withTimeout(5_000) { f.poolCommitted.await() }
            val operation = owner.children.single { it !in previousJobs }
            operation.cancel()
            gate.countDown()
            withTimeout(5_000) { operation.join() }

            assertEquals("shared", f.bootstrap.getJSONArray("devices").getJSONObject(0).getString("traffic_pool"))
            assertNull(f.vault.readEncrypted("connection.dpapi"))
            assertFalse(f.app.state.value.running)
            assertFalse(f.app.state.value.verified)
            assertTrue(f.app.state.value.networkBlocked)
            assertTrue(f.runtime.armed)
            assertEquals(listOf("protect", "stop:false"), f.runtime.guardEvents.takeLast(2))
            assertEquals(1, f.runtime.starts.size)
        }
    }

    @Test fun `a read only journal cannot prevent protection or revive old credentials after restart`() = runBlocking {
        org.junit.Assume.assumeTrue("Requires Windows DOS read-only file semantics", !DesktopBuild.IS_LINUX)
        for (killSwitch in listOf(false, true)) Fixture().use { f ->
            f.ready()
            f.app.updateSettings { it.copy(advancedSettings = it.advancedSettings.copy(killSwitchEnabled = killSwitch)) }.join()
            f.app.connect()
            val connected = withTimeout(15_000) { f.app.state.first { !it.loading && it.verified } }
            val journal = f.vault.directory.resolve("connection.dpapi")
            val old = f.vault.readEncrypted("connection.dpapi")
            Files.setAttribute(journal, "dos:readonly", true)
            try {
                f.app.setDeviceTrafficPool(connected.currentDeviceId, "shared")
                val failed = withTimeout(10_000) { f.app.state.first { !it.loading && it.message != null && it.devices.single().trafficPool == "shared" } }

                assertFalse(failed.running)
                assertTrue(failed.networkBlocked)
                assertTrue(f.runtime.armed)
                assertEquals(old, f.vault.readEncrypted("connection.dpapi"))
                assertEquals(listOf("protect", "stop:false"), f.runtime.guardEvents.takeLast(2))
                assertEquals(VlessProfile(), failed.settings.profile)
                assertNull(DesktopController::class.java.getDeclaredField("activeConnection").apply { isAccessible = true }.get(f.app))
                f.app.closeAndJoin()
            } finally { Files.setAttribute(journal, "dos:readonly", false) }

            f.app = DesktopController(f.vault, f.runtime, f.api, {}, f.network, dnsPolicySnapshot = { f.dnsSnapshot() })
            withTimeout(15_000) { f.app.state.first { !it.loading && it.verified && f.runtime.starts.size > 1 } }
            assertEquals("A protected restart must prepare credentials through the API", 2, f.sessionRequests.size)
            assertFalse(f.runtime.starts.last().toString().contains("00000000-0000-4000-8000-000000000099"))
            assertTrue(f.runtime.starts.last().toString().contains(f.sessionSecret))
            if (killSwitch) assertEquals("start:true", f.runtime.guardEvents.last())
            else assertEquals(listOf("start:true", "release"), f.runtime.guardEvents.takeLast(2))
            assertEquals(killSwitch, f.runtime.armed)
        }
    }

    @Test fun `a failed settings write still protects and stops the revoked tunnel`() = runBlocking {
        org.junit.Assume.assumeTrue("Requires Windows DOS read-only file semantics", !DesktopBuild.IS_LINUX)
        Fixture().use { f ->
            f.ready(); f.app.connect()
            val connected = withTimeout(15_000) { f.app.state.first { !it.loading && it.verified } }
            val settings = f.vault.directory.resolve("settings.dpapi")
            Files.setAttribute(settings, "dos:readonly", true)
            try {
                f.app.setDeviceTrafficPool(connected.currentDeviceId, "shared")
                val failed = withTimeout(10_000) { f.app.state.first { !it.loading && it.message != null && it.devices.single().trafficPool == "shared" } }

                assertFalse(failed.running)
                assertTrue(failed.networkBlocked)
                assertTrue(f.runtime.armed)
                assertNull(f.vault.readEncrypted("connection.dpapi"))
                assertEquals(listOf("protect", "stop:false"), f.runtime.guardEvents.takeLast(2))
                assertEquals(VlessProfile(), failed.settings.profile)
                assertEquals(1, f.runtime.starts.size)
            } finally { Files.setAttribute(settings, "dos:readonly", false) }
        }
    }

    @Test fun `a statistics checkpoint failure cannot prevent stopping a revoked tunnel`() = runBlocking {
        org.junit.Assume.assumeTrue("Requires Windows DOS read-only file semantics", !DesktopBuild.IS_LINUX)
        Fixture().use { f ->
            f.ready(); f.app.connect()
            val connected = withTimeout(15_000) { f.app.state.first { !it.loading && it.verified } }
            val statistics = f.vault.directory.resolve("stats.dpapi")
            if (!Files.exists(statistics)) Files.write(statistics, byteArrayOf())
            Files.setAttribute(statistics, "dos:readonly", true)
            try {
                f.app.setDeviceTrafficPool(connected.currentDeviceId, "shared")
                val failed = withTimeout(10_000) { f.app.state.first { !it.loading && it.message != null && it.devices.single().trafficPool == "shared" } }

                assertNull(f.runtime.active)
                assertFalse(failed.running)
                assertFalse(failed.verified)
                assertTrue(failed.networkBlocked)
                assertTrue(f.runtime.armed)
                assertTrue(f.runtime.stops.isNotEmpty() && f.runtime.stops.none { it })
                assertNull(f.vault.readEncrypted("connection.dpapi"))
            } finally { Files.setAttribute(statistics, "dos:readonly", false) }
        }
    }

    @Test fun `an INCY move keeps the subscription refresh reminder when account refresh fails and preserves auth refusal handling`() = runBlocking {
        for (status in listOf(503, 401)) Fixture().use { f ->
            f.ready()
            f.incyDevices.put(JSONObject().put("id", f.incyDeviceId).put("name", "Other client").put("traffic_pool", "primary"))
            f.app.loadDevices()
            withTimeout(5_000) { f.app.state.first { !it.loading && it.incyDevices.size == 1 } }
            f.bootstrapStatus.set(status)
            val moves = f.incyPoolCalls.get()

            f.app.setIncyTrafficPool(f.incyDeviceId, "shared")
            val moved = withTimeout(5_000) { f.app.state.first { !it.loading && it.message != null && f.incyPoolCalls.get() > moves } }

            assertTrue("HTTP $status: ${moved.message}", checkNotNull(moved.message).let { it.contains("Refresh this device's subscription") || it.contains("Обновите подписку этого устройства") })
            if (status == 401) assertNull(moved.user) else assertEquals("shared", moved.incyDevices.single().trafficPool)
            assertTrue(f.runtime.starts.isEmpty())
        }
    }

    @Test fun `YouTube opt out starts only the main core`() = runBlocking {
        Fixture().use { f ->
            f.ready(); f.app.connect()
            withTimeout(15_000) { f.app.state.first { it.running && !it.loading && it.verified } }
            assertEquals(0, f.runtime.youtubeStarts.get())
            assertTrue(f.youtubeRequests.isEmpty())
            assertFalse(f.runtime.starts.single().toString().contains("youtube-local"))
        }
    }

    @Test fun `local YouTube readiness preserves the main route and never requests server admission`() = runBlocking {
        Fixture().use { f ->
            f.ready(); f.enableYoutube(); f.app.connect(); f.awaitYoutubeReady()
            assertEquals("route", youtubeTcpAction(f.runtime.starts.first()))
            assertEquals(1, f.runtime.starts.size)
            val branch = checkNotNull(youtubeEndpoint(f.runtime.starts.last()))
            assertEquals("socks", branch.getString("type"))
            assertEquals("127.0.0.1", branch.getString("server"))
            assertEquals(1080, branch.getInt("server_port"))
            assertEquals(1, f.runtime.youtubeStarts.get())
            assertEquals(1, f.sessionNodes.size)
            assertTrue(f.youtubeRequests.isEmpty())
            assertFalse(f.mainConfig().toString().contains("youtube-local"))
            delay(2_300)
            assertEquals(1, f.runtime.starts.size)
            assertEquals(1, f.runtime.youtubeStarts.get())
        }
    }

    @Test fun `YouTube toggle stops the sidecar and preserves the active main route`() = runBlocking {
        Fixture().use { f ->
            f.ready(); f.app.connect()
            withTimeout(15_000) { f.app.state.first { it.running && !it.loading && it.verified } }
            val main = JSONObject(f.mainConfig().toString())
            val connectedAt = f.app.state.value.connectedAtMillis
            val label = f.app.state.value.connectionLabel
            f.enableYoutube(); f.awaitYoutubeReady()
            assertTrue(f.mainConfig().similar(main))
            assertEquals(connectedAt, f.app.state.value.connectedAtMillis)
            assertEquals(label, f.app.state.value.connectionLabel)
            f.app.updateSettings { it.copy(advancedSettings = it.advancedSettings.copy(youtubeAdBlockEnabled = false)) }.join()
            assertTrue(f.app.state.value.running)
            assertFalse(f.runtime.youtubeRunning)
            assertTrue(f.runtime.starts.last().similar(main))
            assertEquals(1, f.sessionNodes.size)
            assertTrue(f.youtubeRequests.isEmpty())
        }
    }

    @Test fun `failed local YouTube startup and cleanup keep the same main core and its closed SOCKS route`() = runBlocking {
        Fixture().use { f ->
            f.ready(); f.enableYoutube()
            f.runtime.failLocalYoutube = true
            f.runtime.failYoutubeStops = true
            f.app.connect()
            withTimeout(15_000) {
                while (f.app.state.value.loading || f.app.state.value.youtubeMessage == null) delay(10)
            }
            assertTrue(f.app.state.value.running)
            assertNotNull(youtubeEndpoint(f.runtime.starts.last()))
            assertEquals("route", youtubeTcpAction(f.runtime.starts.last()))
            assertEquals(1, f.runtime.starts.size)
            assertFalse(f.runtime.youtubeRunning)
            assertEquals(1, f.sessionNodes.size)
        }
    }

    @Test fun `YouTube endpoint allocation failure preserves the main core and never starts an unused child`() = runBlocking {
        Fixture().use { f ->
            f.ready(); f.enableYoutube()
            f.runtime.failYoutubePort = true
            f.app.connect()
            withTimeout(15_000) { f.app.state.first { it.running && it.verified && !it.loading && it.youtubeMessage != null } }
            val main = checkNotNull(f.runtime.active)
            val config = JSONObject(f.runtime.starts.single().toString())
            assertEquals("reject", youtubeTcpAction(config))
            assertNull(youtubeEndpoint(config))
            val primary = f.mainConfig().getJSONArray("outbounds").getJSONObject(0)
            assertTrue(primary.similar(config.getJSONArray("outbounds").getJSONObject(0)))
            assertEquals(0, f.runtime.youtubeStarts.get())
            f.runtime.failYoutubePort = false
            delay(2_300)
            assertEquals(main, f.runtime.active)
            assertEquals(1, f.runtime.starts.size)
            assertTrue(config.similar(f.runtime.starts.single()))
            assertEquals(0, f.runtime.youtubeStarts.get())
            assertFalse(f.runtime.youtubeRunning)
            assertNotNull(f.app.state.value.youtubeMessage)
            assertEquals(1, f.sessionNodes.size)
        }
    }

    @Test fun `local YouTube process failure blocks only YouTube and keeps the main VPN`() = runBlocking {
        Fixture().use { f ->
            f.ready(); f.enableYoutube(); f.app.connect(); f.awaitYoutubeReady()
            f.runtime.failLocalYoutube = true
            f.runtime.youtubeRunning = false
            withTimeout(5_000) { f.app.state.first { it.running && !it.loading && it.youtubeMessage != null } }
            assertNotNull(f.runtime.active)
            assertEquals("route", youtubeTcpAction(f.runtime.starts.last()))
            assertEquals(1, f.runtime.starts.size)
            assertEquals(1, f.sessionNodes.size)
            assertTrue(f.youtubeRequests.isEmpty())
        }
    }

    @Test fun `a physical network change replaces the local process without obtaining a new main session`() = runBlocking {
        Fixture().use { f ->
            f.ready(); f.enableYoutube(); f.app.connect(); f.awaitYoutubeReady()
            f.networkSignature = "another-physical-network"
            withTimeout(10_000) { while (f.runtime.youtubeStarts.get() < 2) delay(10) }
            f.awaitYoutubeReady()
            assertEquals(f.networkSignature, f.runtime.youtubeNetworks.last().signature)
            assertEquals(1, f.sessionNodes.size)
            assertTrue(f.youtubeRequests.isEmpty())
        }
    }

    @Test fun `Free cannot start a saved paid YouTube option`() = runBlocking {
        Fixture().use { f ->
            f.bootstrap.getJSONObject("subscription").put("plan_code", "free").put("plan_name", "Free")
            f.ready(); f.app.refresh().join()
            f.app.updateSettings { it.copy(advancedSettings = it.advancedSettings.copy(youtubeAdBlockEnabled = true)) }.join()
            f.app.connect()
            withTimeout(15_000) { f.app.state.first { it.running && !it.loading && it.youtubeMessage != null } }
            assertEquals(0, f.runtime.youtubeStarts.get())
            assertEquals("reject", youtubeTcpAction(f.runtime.starts.last()))
            assertNotNull(f.runtime.active)
        }
    }

    @Test fun `disconnect cancels delayed local YouTube startup without restoring the core`() = runBlocking {
        Fixture().use { f ->
            f.ready()
            val gate = CompletableDeferred<Unit>()
            f.runtime.youtubeGate = gate
            f.enableYoutube(); f.app.connect()
            withTimeout(15_000) { while (f.runtime.youtubeStarts.get() == 0) delay(10) }
            f.app.disconnect()
            withTimeout(1_500) { f.app.state.first { !it.running && !it.loading } }
            gate.complete(Unit)
            delay(50)
            assertNull(f.runtime.active)
            assertFalse(f.runtime.youtubeRunning)
            assertEquals(1, f.runtime.starts.size)
            assertNull(f.vault.readEncrypted("connection.dpapi"))
        }
    }

    private companion object {
        fun youtubeEndpoint(config: JSONObject): JSONObject? = config.optJSONArray("outbounds")?.let { endpoints ->
            (0 until endpoints.length()).map { endpoints.getJSONObject(it) }.firstOrNull { it.optString("tag") == "youtube-local" }
        }

        fun youtubeTcpAction(config: JSONObject): String? = config.getJSONObject("route").getJSONArray("rules").let { rules ->
            (0 until rules.length()).map { rules.getJSONObject(it) }.firstOrNull { rule ->
                rule.optString("type") == "logical" && rule.optString("action") in setOf("route", "reject") && rule.toString().contains("youtube") &&
                    rule.getJSONArray("rules").let { parts -> (0 until parts.length()).any {
                        parts.getJSONObject(it).optString("network") == "tcp"
                    } }
            }?.optString("action")
        }
    }

    @Test fun `quitting during a stalled connection stops the core before closing the app`() = runBlocking {
        Fixture().use { f ->
            f.ready()
            val gate = CompletableDeferred<Unit>()
            f.runtime.probeGate = gate
            try {
                f.app.connect()
                withTimeout(5_000) { while (f.runtime.probeCalls.get() == 0) delay(10) }
                assertNotNull(f.runtime.active)
                val exited = CompletableDeferred<Unit>()
                f.app.exit {
                    assertNull("Quit must release the VPN before closing", f.runtime.active)
                    exited.complete(Unit)
                }
                withTimeout(3_000) { exited.await() }
                assertNull(f.vault.readEncrypted("connection.dpapi"))
            } finally { gate.complete(Unit) }
        }
    }

    @Test fun `disconnect does not request final HTTP metrics from a stalled core`() = runBlocking {
        Fixture().use { f ->
            f.ready(); f.app.connect()
            withTimeout(15_000) { f.app.state.first { !it.loading && it.verified } }
            val gate = CompletableDeferred<Unit>()
            f.runtime.metricsGate = gate
            try {
                f.app.disconnect()
                withTimeout(1_500) { f.app.state.first { !it.loading && !it.running } }
                assertNull(f.runtime.active)
            } finally { gate.complete(Unit) }
        }
    }

    @Test fun `disconnect cancels startup bootstrap before taking the existing stop lock`() = runBlocking {
        Fixture().use { f ->
            f.dnsSnapshot = { WindowsDnsPolicy(emptyList(), emptyList()) }
            f.ready(); f.app.connect()
            withTimeout(15_000) { f.app.state.first { !it.loading && it.verified } }
            f.app.closeAndJoin()
            val gate = java.util.concurrent.CountDownLatch(1)
            f.bootstrapGate = gate
            val before = f.bootstrapCalls.get()
            try {
                f.app = DesktopController(f.vault, f.runtime, f.api, {}, f.network, dnsPolicySnapshot = { f.dnsSnapshot() })
                withTimeout(5_000) { while (f.bootstrapCalls.get() == before) delay(10) }
                f.app.disconnect()
                withTimeout(1_500) { f.app.state.first { !it.loading && !it.running } }
                assertNull(f.runtime.active)
                assertEquals(1L, gate.count)
                gate.countDown()
                delay(100)
                assertNull(f.runtime.active)
                assertEquals(1, f.runtime.starts.size)
            } finally { gate.countDown() }
        }
    }

    @Test fun `fresh DNS policy is read while admission is still in flight`() = runBlocking {
        Fixture().use { f ->
            f.ready()
            val gate = java.util.concurrent.CountDownLatch(1)
            val dnsStarted = CompletableDeferred<Unit>()
            f.sessionGate = gate
            f.dnsSnapshot = { dnsStarted.complete(Unit); WindowsDnsPolicy(emptyList(), emptyList()) }
            try {
                f.app.connect()
                withTimeout(5_000) { while (f.sessionRequests.isEmpty()) delay(10) }
                withTimeout(1_000) { dnsStarted.await() }
            } finally { gate.countDown() }
            withTimeout(15_000) { f.app.state.first { !it.loading && it.verified } }
            Unit
        }
    }

    @Test fun `disconnect cancels an explicit YouTube toggle replacing the core`() = runBlocking {
        Fixture().use { f ->
            f.ready(); f.app.connect()
            withTimeout(15_000) { f.app.state.first { !it.loading && it.verified } }
            f.runtime.startDelayMillis = 8_000
            val changing = launch { f.enableYoutube() }
            withTimeout(5_000) { while (f.runtime.starts.size < 2) delay(10) }
            f.app.disconnect()
            withTimeout(1_500) { f.app.state.first { !it.loading && !it.running } }
            changing.join()
            assertNull(f.runtime.active)
            assertFalse(f.runtime.youtubeRunning)
            assertEquals(2, f.runtime.starts.size)
        }
    }

    @Test fun `disconnect cancels a settings action replacing the core`() = runBlocking {
        Fixture().use { f ->
            f.ready(); f.app.connect()
            withTimeout(15_000) { f.app.state.first { !it.loading && it.verified } }
            f.runtime.startDelayMillis = 8_000
            val changing = launch { f.enableYoutube() }
            try {
                withTimeout(5_000) { while (f.runtime.starts.size < 2) delay(10) }
                f.app.disconnect()
                withTimeout(1_500) { f.app.state.first { !it.loading && !it.running } }
                assertNull(f.runtime.active)
            } finally { changing.cancelAndJoin() }
        }
    }

    @Test fun `disconnect publishes stopped state while RU revoke is still pending`() = runBlocking {
        Fixture().use { f ->
            f.ready(); f.app.connect()
            withTimeout(15_000) { f.app.state.first { !it.loading && it.verified } }
            val gate = java.util.concurrent.CountDownLatch(1)
            f.ruRevokeGate = gate
            f.vault.writeEncrypted("ru-revoke.dpapi", JSONObject().put("session_id", "fixture-ru-session").put("revoke_token", "fixture-token").toString())
            try {
                f.app.disconnect()
                withTimeout(1_500) { f.app.state.first { !it.loading && !it.running } }
                withTimeout(1_500) { while (f.ruRevokeCalls.get() == 0) delay(10) }
                assertNotNull(f.vault.readEncrypted("ru-revoke.dpapi"))
            } finally { gate.countDown() }
            withTimeout(5_000) { while (f.vault.readEncrypted("ru-revoke.dpapi") != null) delay(10) }
        }
    }

    @Test fun `opening servers refreshes device TCP medians during an active connection`() = runBlocking {
        val vault = WindowsVault(Files.createTempDirectory("noki-device-latencies"))
        val calls = AtomicInteger()
        val app = DesktopController(vault, SingBoxRuntime(vault), BackendApiClient(baseUrl = "http://127.0.0.1:9"),
            networkSnapshot = {
                assertFalse("Adapter discovery must not block the event-dispatch thread", javax.swing.SwingUtilities.isEventDispatchThread())
                WindowsNetworkSnapshot("wifi", EndpointRankingPolicy.NetworkKind.WIFI, true, 1)
            },
            latencySampler = { ClientLatencySampler(tcpConnect = { host, _, _ ->
                calls.incrementAndGet(); if (host == "fast.invalid") 20 else 90
            }, icmpPing = { _, _, _ -> error("No ICMP fallback on a bound device network") }) })
        try {
            withTimeout(5_000) { app.state.first { !it.loading } }
            DesktopController::class.java.getDeclaredField("tokens").apply { isAccessible = true }.set(app, BackendAuthTokens("access", null))
            @Suppress("UNCHECKED_CAST")
            val mutable = DesktopController::class.java.getDeclaredField("mutable").apply { isAccessible = true }.get(app) as MutableStateFlow<DesktopState>
            mutable.value = mutable.value.copy(user = BackendUser("preview", "Preview", "preview@example.invalid", null, true, false), running = true,
                locations = listOf(ServerLocation("DE", "DE", "Germany", "", "slow.invalid", isOnline = true,
                    servers = listOf("slow.invalid", "fast.invalid").map { VpnServer(it, it, "DE", "DE", it, 443, true) })))
            withContext(Dispatchers.Main) { app.refreshServerLatencies() }
            val measured = withTimeout(3_000) { app.state.first { it.locations.singleOrNull()?.latencyMs == 20 } }
            assertEquals(6, calls.get())
            assertEquals(listOf(90, 20), measured.servers.map { it.latencyMs })
            assertTrue(measured.running)
            withTimeout(3_000) {
                (DesktopController::class.java.getDeclaredField("latencyJob").apply { isAccessible = true }.get(app) as Job?)?.join()
            }
            app.setWindowVisible(false)
            assertEquals("Completed or hidden sampling must retain its results", listOf(90, 20), app.state.value.servers.map { it.latencyMs })
            app.refreshServerLatencies()
            withTimeout(3_000) { while (calls.get() < 12) delay(10) }
            assertEquals("Reopening must take fresh samples", 12, calls.get())
        } finally { app.closeAndJoin(); app.close() }
    }

    @Test fun `network changes discard a late catalog ping result`() = runBlocking {
        val vault = WindowsVault(Files.createTempDirectory("noki-device-latency-network"))
        var signature = "wifi"
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val app = DesktopController(vault, SingBoxRuntime(vault), BackendApiClient(baseUrl = "http://127.0.0.1:9"),
            networkSnapshot = { WindowsNetworkSnapshot(signature, EndpointRankingPolicy.NetworkKind.WIFI, true, 1) },
            latencySampler = { ClientLatencySampler(tcpConnect = { _, _, _ -> entered.countDown(); release.await(); 20 }, icmpPing = { _, _, _ -> null }) })
        try {
            withTimeout(5_000) { app.state.first { !it.loading } }
            DesktopController::class.java.getDeclaredField("tokens").apply { isAccessible = true }.set(app, BackendAuthTokens("access", null))
            @Suppress("UNCHECKED_CAST")
            val mutable = DesktopController::class.java.getDeclaredField("mutable").apply { isAccessible = true }.get(app) as MutableStateFlow<DesktopState>
            mutable.value = mutable.value.copy(locations = listOf(ServerLocation("DE", "DE", "Germany", "", "example.invalid", isOnline = true)))
            app.refreshServerLatencies()
            assertTrue(entered.await(3, java.util.concurrent.TimeUnit.SECONDS))
            signature = "ethernet"; release.countDown()
            delay(100)
            assertNull(app.state.value.locations.single().latencyMs)
        } finally { release.countDown(); app.closeAndJoin(); app.close() }
    }

    @Test fun `website login retries an offline saved session without discarding credentials`() = runBlocking {
        val vault = WindowsVault(Files.createTempDirectory("noki-offline-retry"))
        vault.saveTokens(BackendAuthTokens("saved-access", null))
        val calls = AtomicInteger()
        val browsers = AtomicInteger()
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            calls.incrementAndGet()
            okhttp3.Response.Builder().request(chain.request()).protocol(okhttp3.Protocol.HTTP_1_1).code(503)
                .message("offline").body("{\"detail\":\"offline\"}".toResponseBody("application/json".toMediaType())).build()
        }.build()
        val app = DesktopController(vault, SingBoxRuntime(vault), BackendApiClient(http, "https://example.invalid"), { browsers.incrementAndGet() })
        try {
            withTimeout(5_000) { app.state.first { !it.loading && it.message != null } }
            val before = calls.get()
            app.loginFromWebsite()
            withTimeout(3_000) { while (calls.get() == before) delay(10) }
            withTimeout(5_000) { app.state.first { !it.loading } }
            assertEquals("saved-access", vault.loadTokens()?.accessToken)
            assertEquals(0, browsers.get())
        } finally { app.closeAndJoin(); app.close() }
    }

    @Suppress("UNCHECKED_CAST")
    @Test fun `changing country preserves a global manual transport family`() = runBlocking {
        val vault = WindowsVault(Files.createTempDirectory("noki-manual-country"))
        val app = DesktopController(vault, SingBoxRuntime(vault), BackendApiClient(baseUrl = "http://127.0.0.1:9"))
        try {
            withTimeout(5_000) { app.state.first { !it.loading } }
            val mutable = DesktopController::class.java.getDeclaredField("mutable").also { it.isAccessible = true }.get(app) as MutableStateFlow<DesktopState>
            mutable.value = mutable.value.copy(locations = listOf("DE", "FR").map { code ->
                ServerLocation(code, code, code, "", "example.invalid", isOnline = true,
                    servers = listOf(VpnServer(code, code, code, code, "example.invalid", null, true)))
            }, settings = mutable.value.settings.copy(
                userProfile = UserProfile(serverSelectionMode = ServerSelectionMode.COUNTRY, selectedCountryCode = "DE"),
                advancedSettings = AdvancedSettings(endpointSelectionMode = EndpointSelectionMode.MANUAL,
                    manualEndpointCode = "de-xhttp", manualEndpointGroupKey = "reality|xhttp|stream-up")))
            app.selectLocation(ServerSelectionMode.COUNTRY, "FR")
            val changed = withTimeout(5_000) { app.state.first { !it.loading && it.settings.userProfile.selectedCountryCode == "FR" } }
            assertEquals(EndpointSelectionMode.MANUAL, changed.settings.advancedSettings.endpointSelectionMode)
            assertEquals("reality|xhttp|stream-up", changed.settings.advancedSettings.manualEndpointGroupKey)
            assertEquals("de-xhttp", changed.settings.advancedSettings.manualEndpointCode)
            assertEquals(EndpointSelectionMode.MANUAL, vault.loadSettings().advancedSettings.endpointSelectionMode)
        } finally { app.closeAndJoin(); app.close() }
    }

    @Test fun `RU automatic exit ranking considers measured latency instead of UUID order`() {
        val nodes = listOf(RuForeignNode("00000000-0000-4000-8000-000000000001", "slow", "FR", 400),
            RuForeignNode("00000000-0000-4000-8000-000000000002", "fast", "DE", 10))
        val settings = DesktopState().settings.copy(userProfile = UserProfile(serverSelectionMode = ServerSelectionMode.AUTO))
        assertEquals("fast", DesktopController.ruExits(settings, nodes).first().name)
    }

    @Test fun `RU country and automatic choices use fresh weighted random and exclude zero weight`() {
        val nodes = (1..3).map { RuForeignNode("00000000-0000-4000-8000-${it.toString().padStart(12, '0')}", "Node $it", "DE") }
        val physical = nodes.mapIndexed { i, n -> VpnServer(n.id, n.name, "DE", "de", "example.invalid", null, true,
            weight = listOf(1, 9, 0)[i], metricsAt = java.time.Instant.now().minusSeconds(1).toString()) }
        for (mode in listOf(ServerSelectionMode.AUTO, ServerSelectionMode.COUNTRY)) {
            val settings = DesktopState().settings.copy(userProfile = UserProfile(serverSelectionMode = mode, selectedCountryCode = "DE"))
            assertEquals(nodes[0], DesktopController.ruExits(settings, nodes, physical) { .01 }.first())
            assertEquals(nodes[1], DesktopController.ruExits(settings, nodes, physical) { .5 }.first())
            assertEquals(2, DesktopController.ruExits(settings, nodes, physical).size)
        }
    }

    @Test fun `failed tunnel profile falls back and records health without changing Auto`() = runBlocking {
        Fixture().use { f ->
            f.ready()
            f.runtime.failProbes.set(1)
            f.app.connect()
            val state = withTimeout(15_000) { f.app.state.first { !it.loading && it.verified } }
            assertEquals(2, f.runtime.starts.size)
            assertEquals(1, f.sessionNodes.size)
            assertEquals(EndpointSelectionMode.AUTO, state.settings.advancedSettings.endpointSelectionMode)
            assertEquals(ServerSelectionMode.AUTO, state.settings.userProfile.serverSelectionMode)
            val health = EndpointHealthStore.decode(f.vault.readEncrypted("endpoint-health.dpapi"))
            assertEquals(1, health["wifi|a-first"]?.failureCount)
            assertEquals(1, health["wifi|b-second"]?.successCount)
            assertNotNull(f.vault.readEncrypted("connection.dpapi"))
            assertNotNull(state.connectionSettings)
            f.app.updateSettings { it.copy(userProfile = it.userProfile.copy(
                serverSelectionMode = ServerSelectionMode.COUNTRY, selectedCountryCode = "DE")) }.join()
            val changed = f.app.state.value
            assertEquals(ServerSelectionMode.COUNTRY, changed.settings.userProfile.serverSelectionMode)
            assertEquals("The connected presentation retains the settings used by the core",
                ServerSelectionMode.AUTO, changed.displayedSettings.userProfile.serverSelectionMode)
            assertEquals(2, f.runtime.starts.size)
            f.app.disconnect()
            val disconnected = withTimeout(5_000) { f.app.state.first { !it.running && !it.loading } }
            assertNull(disconnected.connectionSettings)
            assertEquals(ServerSelectionMode.COUNTRY, disconnected.displayedSettings.userProfile.serverSelectionMode)
        }
    }

    @Test fun `missing selected country or node fails without enabling Auto`() = runBlocking {
        for (mode in listOf(ServerSelectionMode.COUNTRY, ServerSelectionMode.SERVER)) Fixture().use { f ->
            f.ready()
            f.app.updateSettings { it.copy(userProfile = it.userProfile.copy(serverSelectionMode = mode,
                selectedCountryCode = "FR", selectedNodeId = "00000000-0000-4000-8000-999999999999")) }.join()
            f.app.connect()
            val state = withTimeout(5_000) { f.app.state.first { !it.loading && it.message != null } }
            assertFalse(state.running)
            assertFalse(state.verified)
            assertNotNull(state.message)
            assertEquals(mode, state.settings.userProfile.serverSelectionMode)
            assertEquals("FR", state.settings.userProfile.selectedCountryCode)
            assertTrue(f.sessionRequests.isEmpty())
            assertTrue(f.runtime.starts.isEmpty())
            assertEquals(mode, f.vault.loadSettings().userProfile.serverSelectionMode)
        }
    }

    @Test fun `protocol choices are global and need no session or country`() = runBlocking {
        for (country in listOf("", "LV")) Fixture().use { f ->
            f.ready()
            f.app.updateSettings { it.copy(userProfile = it.userProfile.copy(selectedCountryCode = country)) }.join()
            val choices = EndpointGroupPolicy.protocolOptions(f.app.state.value.endpointOptions)
            assertEquals(listOf("nokinv-quic", "reality-xhttp", "reality-tcp", "hysteria2", "naive"), choices.map { it.code })
            assertNull(f.app.state.value.message)
            assertEquals(ServerSelectionMode.AUTO, f.app.state.value.settings.userProfile.serverSelectionMode)
            assertEquals(country, f.app.state.value.settings.userProfile.selectedCountryCode)
            assertEquals(choices, EndpointGroupPolicy.protocolOptions(f.app.state.value.endpointOptions))
            assertTrue(f.sessionRequests.isEmpty())
        }
    }

    @Test fun `connected protocol picker needs no new session or encrypted settings write`() = runBlocking {
        Fixture().use { f ->
            f.ready(); f.app.connect()
            withTimeout(15_000) { f.app.state.first { !it.loading && it.verified } }
            val before = Files.readAllBytes(f.vault.directory.resolve("settings.dpapi"))
            f.sessionStatus.set(503)
            repeat(3) { assertEquals(5, EndpointGroupPolicy.protocolOptions(f.app.state.value.endpointOptions).size) }
            assertEquals(1, f.sessionRequests.size)
            assertEquals(1, f.runtime.starts.size)
            assertNull(f.app.state.value.message)
            assertTrue(f.app.state.value.verified)
            assertArrayEquals(before, Files.readAllBytes(f.vault.directory.resolve("settings.dpapi")))
        }
    }

    @Test fun `changing a pending server keeps global protocol choices and the active route`() = runBlocking {
        Fixture(nodeCount = 2).use { f ->
            f.ready(); f.app.connect()
            val connected = withTimeout(15_000) { f.app.state.first { !it.loading && it.verified } }
            val next = connected.servers.first { it.id != connected.selectedServerId }
            f.app.updateSettings { it.copy(userProfile = it.userProfile.copy(
                serverSelectionMode = ServerSelectionMode.SERVER, selectedCountryCode = next.countryCode, selectedNodeId = next.id)) }.join()
            assertEquals(5, EndpointGroupPolicy.protocolOptions(f.app.state.value.endpointOptions).size)
            assertEquals(next.id, f.app.state.value.settings.userProfile.selectedNodeId)
            assertEquals(1, f.sessionRequests.size)
            assertEquals(connected.selectedServerId, f.app.state.value.selectedServerId)
            assertEquals(1, f.runtime.starts.size)
        }
    }

    @Test fun `failed connected samples retain a last successful ping until the network changes`() = runBlocking {
        Fixture().use { f ->
            f.ready(); f.measureLatencies(); f.app.connect()
            withTimeout(15_000) { f.app.state.first { !it.loading && it.verified } }
            suspend fun measure() {
                f.app.refreshServerLatencies()
                (DesktopController::class.java.getDeclaredField("latencyJob").apply { isAccessible = true }.get(f.app) as Job?)?.join()
            }
            f.tcpLatency.set(-1)
            measure()
            assertEquals(54L, f.app.state.value.latencyMs)
            assertEquals(54, f.app.state.value.locations.single().latencyMs)
            f.tcpLatency.set(79)
            measure()
            assertEquals(79L, f.app.state.value.latencyMs)
            assertEquals(79, f.app.state.value.servers.single().latencyMs)
            f.networkSignature = "new-network"
            f.tcpLatency.set(-1)
            measure()
            assertNull("An old network must not supply pings to the new one", f.app.state.value.latencyMs)
            assertNull(f.app.state.value.locations.single().latencyMs)
        }
    }

    @Test fun `account refresh keeps an active node and its ping during a transient offline heartbeat`() = runBlocking {
        Fixture().use { f ->
            f.ready(); f.measureLatencies(); f.app.connect()
            val connected = withTimeout(15_000) { f.app.state.first { !it.loading && it.verified } }
            f.bootstrap.getJSONArray("locations").getJSONObject(0).getJSONArray("servers").getJSONObject(0).put("is_online", false)
            assertTrue(f.app.refresh().await())
            assertEquals(connected.selectedServerId, f.app.state.value.selectedServerId)
            assertEquals(54L, f.app.state.value.latencyMs)
            assertTrue(f.app.state.value.verified)
        }
    }

    @Test fun `manual catalog refresh adds servers without restarting the active connection`() = runBlocking {
        Fixture().use { f ->
            f.ready(); f.app.connect()
            val connected = withTimeout(15_000) { f.app.state.first { !it.loading && it.verified } }
            val active = f.runtime.active
            val servers = f.bootstrap.getJSONArray("locations").getJSONObject(0).getJSONArray("servers")
            val addedId = "00000000-0000-4000-8000-000000000002"
            servers.put(JSONObject(servers.getJSONObject(0).toString()).put("id", addedId).put("name", "New node"))

            assertTrue(f.app.refresh().await())

            assertEquals(2, f.app.state.value.servers.size)
            assertTrue(f.app.state.value.servers.any { it.id == addedId })
            assertEquals(connected.selectedServerId, f.app.state.value.selectedServerId)
            assertEquals(connected.connectedAtMillis, f.app.state.value.connectedAtMillis)
            assertTrue(f.app.state.value.verified)
            assertSame(active, f.runtime.active)
            assertEquals(1, f.runtime.starts.size)
            assertTrue(f.runtime.stops.isEmpty())
        }
    }

    @Test fun `tray account poll is quiet every fifteen minutes and does not overlap requests`() = runBlocking {
        Fixture().use { f ->
            f.ready()
            val gate = java.util.concurrent.CountDownLatch(1)
            f.bootstrapGate = gate
            f.bootstrapStatus.set(503)
            val before = f.bootstrapCalls.get()
            val message = f.app.state.value.message
            assertNull(f.app.pollAccountData(System.currentTimeMillis() + 61_000))
            val now = System.currentTimeMillis() + 15 * 60_000 + 1000
            val request = f.app.pollAccountData(now)
            assertNotNull(request)
            assertNull(f.app.pollAccountData(now))
            withTimeout(5_000) { while (f.bootstrapCalls.get() == before) delay(10) }
            assertFalse(f.app.state.value.loading)
            assertNull(f.app.pollAccountData(now + 10_000))
            gate.countDown()
            withTimeout(5_000) { request?.join() }
            assertTrue(f.app.diagnostics.batch().any { it.message == "account_sync_failed" })
            assertEquals(message, f.app.state.value.message)
            assertEquals(before + 1, f.bootstrapCalls.get())
            assertTrue(f.runtime.starts.isEmpty())
        }
    }

    @Test fun `application log upload retries readable pending entries after an HTTP failure`() = runBlocking {
        Fixture(automaticLogs = false).use { f ->
            f.ready()
            f.app.setAutomaticLogSending(false).join()
            f.app.recordDiagnosticFailure("requested_operation_failed", java.io.IOException("Authorization: Bearer never-upload-this"))
            f.logStatus.set(503)
            f.app.uploadLogs().join()
            assertTrue(f.app.diagnostics.batch().any { it.message == "requested_operation_failed" })
            f.logStatus.set(200)
            f.app.uploadLogs().join()
            assertTrue(f.app.diagnostics.batch().isEmpty())
            val sent = f.uploadedLogs.last()
            assertTrue(sent.startsWith("Noki Vpn "))
            assertTrue(sent.contains("requested_operation_failed"))
            assertTrue(sent.contains("at com.noki.desktop.DesktopConnectionTest"))
            assertFalse(sent.contains("never-upload-this"))
        }
    }

    @Test fun `application log delivery respects server retry after without dropping pending errors`() = runBlocking {
        Fixture(automaticLogs = false).use { f ->
            f.ready()
            f.logStatus.set(429)
            f.logRetryAfter = "60"
            f.app.recordDiagnosticFailure("rate_limit_fixture", java.io.IOException("fixture"))
            f.app.uploadLogs().join()
            val requests = f.uploadedLogs.size
            assertEquals(1, requests)
            f.app.uploadLogs().join()
            assertEquals(requests, f.uploadedLogs.size)
            assertTrue(f.app.diagnostics.batch().any { it.message == "rate_limit_fixture" })
        }
    }

    @Test fun `catalog refresh shares its result and does not report success on an HTTP error`() = runBlocking {
        Fixture().use { f ->
            f.ready()
            val gate = java.util.concurrent.CountDownLatch(1)
            f.bootstrapGate = gate
            val before = f.bootstrapCalls.get()
            val first = f.app.refresh()
            withTimeout(5_000) { while (f.bootstrapCalls.get() == before) delay(10) }
            assertSame(first, f.app.refresh())
            gate.countDown()
            assertTrue(withTimeout(5_000) { first.await() })
            f.bootstrapGate = null
            f.bootstrapStatus.set(503)
            val servers = f.app.state.value.servers.map { it.id }

            assertFalse(withTimeout(5_000) { f.app.refresh().await() })
            assertEquals(servers, f.app.state.value.servers.map { it.id })
            assertNotNull(f.app.state.value.message)
            assertTrue(f.runtime.starts.isEmpty())
        }
    }

    @Test fun `an unavailable physical server is followed by another weighted candidate`() = runBlocking {
        Fixture(nodeCount = 2, failFirstSession = true).use { f ->
            f.ready(); f.app.connect()
            withTimeout(15_000) { f.app.state.first { !it.loading && it.verified } }
            assertEquals(2, f.sessionNodes.size)
            assertEquals(2, f.sessionNodes.distinct().size)
            assertEquals(1, f.runtime.starts.size)
        }
    }

    @Test fun `disabling automatic recovery preserves live tunnel and manual connection`() = runBlocking {
        Fixture().use { f ->
            f.ready()
            assertTrue(f.app.state.value.autoRecoveryEnabled)
            f.app.setAutoRecovery(false).join()
            f.app.connect()
            withTimeout(15_000) { f.app.state.first { !it.loading && it.verified } }
            val active = f.runtime.active
            val connectedAt = f.app.state.value.connectedAtMillis
            f.runtime.failProbes.set(100)
            repeat(3) { f.app.verifyConnection() }
            val queue = DesktopController::class.java.getDeclaredMethod("queueRecovery", Long::class.javaPrimitiveType).apply { isAccessible = true }
            queue.invoke(f.app, 0L)
            f.networkSignature = "replacement-with-recovery-disabled"
            val network = DesktopController::class.java.getDeclaredField("lastNetwork").apply { isAccessible = true }
            withTimeout(5_000) {
                while ((network.get(f.app) as? WindowsNetworkSnapshot)?.signature != f.networkSignature) delay(10)
            }
            assertEquals(0, f.runtime.watchdogProbes.get())
            assertEquals(0, f.runtime.rebindCalls.get())
            assertEquals(active, f.runtime.active)
            assertEquals(connectedAt, f.app.state.value.connectedAtMillis)
            assertEquals(1, f.runtime.starts.size)
            assertTrue(f.runtime.stops.isEmpty())
            assertFalse(f.vault.loadAutoRecovery())
            f.runtime.failProbes.set(0)
            f.app.setAutoRecovery(true).join()
            assertEquals(1, f.runtime.starts.size)
            f.app.verifyConnection()
            withTimeout(5_000) { while (f.runtime.watchdogProbes.get() == 0) delay(10) }
            assertEquals(active, f.runtime.active)
        }
    }

    @Test fun `watchdog requires two failed probes and tries the cached route before backend preparation`() = runBlocking {
        Fixture().use { f ->
            f.ready(); f.app.connect()
            withTimeout(15_000) { f.app.state.first { !it.loading && it.verified } }
            f.runtime.failProbes.set(2)
            f.app.verifyConnection()
            withTimeout(5_000) { f.app.state.first { !it.verified } }
            assertEquals(1, f.runtime.starts.size)
            f.app.verifyConnection()
            withTimeout(15_000) { f.app.state.first { !it.loading && it.verified && f.runtime.starts.size == 2 } }
            assertEquals("A valid cached route must not require another session request", 1, f.sessionNodes.size)
            assertEquals("Watchdog must use liveness checks instead of startup or latency measurements", 2, f.runtime.watchdogProbes.get())
            val journal = JSONObject(checkNotNull(f.vault.readEncrypted("connection.dpapi")))
            assertEquals(1, journal.getJSONArray("recoveries").length())
            f.app.closeAndJoin()
            f.app = DesktopController(f.vault, f.runtime, f.api, {}, f.network, dnsPolicySnapshot = { f.dnsSnapshot() })
            withTimeout(5_000) { f.app.state.first { !it.loading && it.verified } }
            f.app.closeAndJoin()
            assertEquals(1, JSONObject(checkNotNull(f.vault.readEncrypted("connection.dpapi"))).getJSONArray("recoveries").length())
        }
    }

    @Test fun `watchdog recovery keeps explicit node and country boundaries while Auto may change country`() = runBlocking {
        for ((mode, wholeCountryFailed) in listOf(ServerSelectionMode.SERVER to false, ServerSelectionMode.COUNTRY to false,
            ServerSelectionMode.COUNTRY to true, ServerSelectionMode.AUTO to true)) Fixture(nodeCount = 4, nodeCountries = listOf("DE", "DE", "FR", "FR")).use { f ->
            f.ready()
            val chosen = f.app.state.value.servers.first()
            f.app.updateSettings { it.copy(userProfile = it.userProfile.copy(serverSelectionMode = mode,
                selectedCountryCode = chosen.countryCode, selectedNodeId = chosen.id)) }.join()
            f.app.connect()
            withTimeout(10_000) { f.app.state.first { !it.loading && it.verified } }
            val first = checkNotNull(f.runtime.active?.serverId)
            val active = f.runtime.active
            val country = f.app.state.value.servers.single { it.id == first }.countryCode
            val countryNodes = f.app.state.value.servers.filter { it.countryCode == country }.map { it.id }.toSet()
            f.runtime.failedProbeNodes += if (wholeCountryFailed) countryNodes else setOf(first)
            f.app.verifyConnection()
            withTimeout(5_000) { f.app.state.first { !it.verified } }
            assertEquals(1, f.runtime.starts.size)
            assertTrue(f.app.state.value.hasEstablishedTunnel)
            f.app.verifyConnection()
            if (mode == ServerSelectionMode.SERVER) {
                val watchdog = DesktopController::class.java.getDeclaredField("watchdog").apply { isAccessible = true }.get(f.app)
                val probe = watchdog.javaClass.getDeclaredField("activeProbe").apply { isAccessible = true }
                withTimeout(5_000) { while (probe.get(watchdog) != null) delay(10) }
                assertEquals(active, f.runtime.active)
                assertEquals(1, f.runtime.starts.size)
                assertEquals(setOf(chosen.id), f.sessionNodes.toSet())
                assertTrue(f.app.state.value.hasEstablishedTunnel)
                return@use
            }
            withTimeout(15_000) { f.app.state.first { !it.loading &&
                if (mode == ServerSelectionMode.COUNTRY && wholeCountryFailed) !it.running
                else it.verified && it.selectedServerId != first } }
            assertTrue(f.runtime.starts.size > 1)
            assertEquals(mode, f.app.state.value.settings.userProfile.serverSelectionMode)
            when (mode) {
                ServerSelectionMode.SERVER -> error("Explicit server recovery is disabled")
                ServerSelectionMode.COUNTRY -> {
                    assertTrue(f.sessionNodes.all { it in countryNodes })
                    if (!wholeCountryFailed) assertTrue(f.app.state.value.selectedServerId in countryNodes)
                }
                ServerSelectionMode.AUTO -> assertFalse(f.app.state.value.selectedServerId in countryNodes)
            }
        }
    }

    @Test fun `watchdog recovery preserves manual protocol on another server`() = runBlocking {
        for (protocol in listOf("nokinv-quic", "reality-xhttp", "hysteria2")) Fixture(nodeCount = 2, nvCandidates = true, hy2Candidates = true).use { f ->
            f.ready()
            val option = EndpointGroupPolicy.protocolOptions().first { it.code == protocol }
            f.app.selectEndpoint(option).join()
            f.app.connect()
            withTimeout(10_000) { f.app.state.first { !it.loading && it.verified } }
            val first = checkNotNull(f.runtime.active?.serverId)
            val proxyType = f.app.state.value.connectionSettings?.profile?.proxyType
            f.runtime.failedProbeNodes += first
            f.app.verifyConnection()
            withTimeout(5_000) { f.app.state.first { !it.verified } }
            f.app.verifyConnection()
            withTimeout(15_000) { f.app.state.first { !it.loading && it.verified && it.selectedServerId != first } }
            assertEquals(proxyType, f.app.state.value.connectionSettings?.profile?.proxyType)
            assertEquals(EndpointSelectionMode.MANUAL, f.app.state.value.settings.advancedSettings.endpointSelectionMode)
            assertEquals(EndpointGroupPolicy.groupKey(option), f.app.state.value.settings.advancedSettings.manualEndpointGroupKey)
            for (config in f.runtime.starts) {
                val outbounds = config.getJSONArray("outbounds")
                val proxy = (0 until outbounds.length()).map(outbounds::getJSONObject).single { it.optString("tag") == "proxy" }
                assertEquals(if (proxyType == "hysteria") "hysteria2" else proxyType, proxy.getString("type"))
            }
        }
    }

    @Test fun `hiding and reopening the window keeps VPN metrics and watchdog alive`() = runBlocking {
        Fixture().use { f ->
            f.ready(); f.app.setWindowVisible(true); f.app.connect()
            withTimeout(15_000) { f.app.state.first { !it.loading && it.verified } }
            val active = checkNotNull(f.runtime.active)
            val metricsBefore = f.runtime.metricsCalls.get()
            f.app.setWindowVisible(false)
            withTimeout(6_000) { while (f.runtime.metricsCalls.get() == metricsBefore) delay(10) }
            f.runtime.failProbes.set(1)
            f.app.verifyConnection()
            withTimeout(5_000) { f.app.state.first { !it.verified } }
            assertTrue(f.app.state.value.running)
            assertEquals(active, f.runtime.active)
            f.app.verifyConnection()
            withTimeout(5_000) { f.app.state.first { it.verified } }
            f.app.setWindowVisible(true)
            assertEquals(active, f.runtime.active)
            assertEquals(active.pid, JSONObject(checkNotNull(f.vault.readEncrypted("connection.dpapi"))).getInt("pid"))
            assertEquals(1, f.runtime.starts.size)
            assertEquals(1, f.sessionNodes.size)
        }
    }

    @Test fun `unreadable RU journals do not stop monitoring or disconnect an ordinary VPN`() = runBlocking {
        for (failure in listOf("dpapi", "json", "io")) Fixture().use { f ->
            f.ready(); f.app.connect()
            withTimeout(15_000) { f.app.state.first { !it.loading && it.verified } }
            val active = checkNotNull(f.runtime.active)
            val path = f.vault.directory.resolve("ru-revoke.dpapi")
            when (failure) {
                "dpapi" -> Files.write(path, byteArrayOf(1, 2, 3))
                "json" -> f.vault.writeEncrypted("ru-revoke.dpapi", "{")
                "io" -> Files.createDirectory(path)
            }
            val before = if (failure == "io") null else Files.readAllBytes(path)
            val metricsBefore = f.runtime.metricsCalls.get()
            withTimeout(8_000) { while (f.runtime.metricsCalls.get() < metricsBefore + 3) delay(10) }
            assertEquals("A pending RU journal must not stop an ordinary VPN ($failure)", active, f.runtime.active)
            assertTrue(f.app.state.value.verified)
            assertEquals(1, f.runtime.starts.size)
            if (before == null) assertTrue(Files.isDirectory(path))
            else assertArrayEquals("The pending revoke must be preserved", before, Files.readAllBytes(path))
        }
    }

    @Test fun `an unreadable active RU journal stops only its core and preserves the guard and pending revoke`() = runBlocking {
        for (failure in listOf("dpapi", "json", "io")) Fixture().use { f ->
            f.ready(); f.app.connect()
            withTimeout(15_000) { f.app.state.first { !it.loading && it.verified } }
            f.markActiveRu()
            f.runtime.armed = true
            f.app.setLoggingEnabled(true).join()
            val path = f.vault.directory.resolve("ru-revoke.dpapi")
            when (failure) {
                "dpapi" -> Files.write(path, byteArrayOf(1, 2, 3))
                "json" -> f.vault.writeEncrypted("ru-revoke.dpapi", "{")
                "io" -> Files.createDirectory(path)
            }
            val before = if (failure == "io") null else Files.readAllBytes(path)
            val logs = f.vault.directory.resolve("logs.dpapi")
            Files.deleteIfExists(logs)
            Files.createDirectory(logs)
            withTimeout(8_000) { f.app.state.first { !it.running && !it.verified && it.message?.contains("RU-сессии") == true } }
            assertNull(f.runtime.active)
            assertTrue("Stopping an unverified RU session must retain kill switch", f.app.state.value.networkBlocked)
            assertEquals(listOf(false), f.runtime.stops)
            assertNull(f.vault.readEncrypted("connection.dpapi"))
            if (before == null) assertTrue(Files.isDirectory(path))
            else assertArrayEquals(before, Files.readAllBytes(path))

            Files.delete(path)
            Files.delete(logs)
            f.runtime.armed = false
            f.app.connect()
            withTimeout(15_000) { f.app.state.first { !it.loading && it.verified } }
            val metricsBefore = f.runtime.metricsCalls.get()
            withTimeout(8_000) { while (f.runtime.metricsCalls.get() < metricsBefore + 3) delay(10) }
        }
    }

    @Test fun `RU journal failures retry a failed core stop without repeating the same message`() = runBlocking {
        Fixture().use { f ->
            f.ready(); f.app.connect()
            withTimeout(15_000) { f.app.state.first { !it.loading && it.verified } }
            f.markActiveRu()
            f.runtime.failStops.set(3)
            f.vault.writeEncrypted("ru-revoke.dpapi", "{")
            val failed = withTimeout(5_000) { f.app.state.first { it.message == "Не удалось завершить проверку RU-сессии" } }
            withTimeout(6_000) { while (f.runtime.stops.size < 3) delay(10) }
            assertEquals("Retries for the same core must not repeat the error", failed.messageRevision, f.app.state.value.messageRevision)
            withTimeout(5_000) { f.app.state.first { !it.running && !it.loading } }
            assertNull(f.runtime.active)
            assertEquals(listOf(false, false, false, false), f.runtime.stops)
            assertEquals("{", f.vault.readEncrypted("ru-revoke.dpapi"))
        }
    }

    @Test fun `valid RU journals retain permanent and future leases and stop an expired lease`() = runBlocking {
        Fixture().use { f ->
            f.ready(); f.app.connect()
            withTimeout(15_000) { f.app.state.first { !it.loading && it.verified } }
            f.markActiveRu()
            val active = checkNotNull(f.runtime.active)
            val journal = JSONObject().put("session_id", "pending-session").put("revoke_token", "fixture-revoke")
            for (lease in listOf(JSONObject.NULL, System.currentTimeMillis() + 60_000)) {
                f.vault.writeEncrypted("ru-revoke.dpapi", journal.put("lease_expires_at", lease).toString())
                val metricsBefore = f.runtime.metricsCalls.get()
                withTimeout(6_000) { while (f.runtime.metricsCalls.get() < metricsBefore + 2) delay(10) }
                assertEquals(active, f.runtime.active)
                assertTrue(f.app.state.value.verified)
            }
            val expired = journal.put("lease_expires_at", System.currentTimeMillis() - 1).toString()
            f.vault.writeEncrypted("ru-revoke.dpapi", expired)
            withTimeout(6_000) { f.app.state.first { !it.running && !it.loading } }
            assertNull(f.runtime.active)
            assertEquals(listOf(false), f.runtime.stops)
            assertNull(f.vault.readEncrypted("connection.dpapi"))
            assertEquals("An offline revoke must not undo the local stop or discard the journal", expired, f.vault.readEncrypted("ru-revoke.dpapi"))
        }
    }

    @Test fun `permanent backend refusal removes automatic recovery intent`() = runBlocking {
        Fixture().use { f ->
            f.ready(); f.app.connect()
            withTimeout(15_000) { f.app.state.first { !it.loading && it.verified } }
            f.runtime.failProbes.set(3)
            f.sessionStatus.set(403)
            f.app.verifyConnection()
            withTimeout(5_000) { f.app.state.first { !it.verified } }
            f.app.verifyConnection()
            withTimeout(15_000) { f.app.state.first { !it.loading && !it.running && f.sessionNodes.size == 2 } }
            assertNull(f.vault.readEncrypted("connection.dpapi"))
            f.app.closeAndJoin()
            f.app = DesktopController(f.vault, f.runtime, f.api, {}, f.network, dnsPolicySnapshot = { f.dnsSnapshot() })
            f.ready(); delay(100)
            assertEquals(2, f.sessionNodes.size)
        }
    }

    @Test fun `handoff rejects a cached route belonging to another core identity`() = runBlocking {
        Fixture().use { f ->
            f.ready(); f.app.connect()
            withTimeout(15_000) { f.app.state.first { !it.loading && it.verified } }
            f.app.closeAndJoin()
            val journal = JSONObject(checkNotNull(f.vault.readEncrypted("connection.dpapi"))).put("pid", 1)
            f.vault.writeEncrypted("connection.dpapi", journal.toString())
            f.app = DesktopController(f.vault, f.runtime, f.api, {}, f.network, dnsPolicySnapshot = { f.dnsSnapshot() })
            withTimeout(5_000) { f.app.state.first { !it.loading && it.verified } }
            assertNull(f.vault.readEncrypted("connection.dpapi"))
            assertEquals(1, f.runtime.starts.size)
        }
    }

    @Test fun `manual latency refresh updates TCP without probing or restarting the connection`() = runBlocking {
        Fixture().use { f ->
            f.ready(); f.app.connect()
            withTimeout(15_000) { f.app.state.first { !it.loading && it.verified } }
            val active = checkNotNull(f.runtime.active)
            val probesBefore = f.runtime.probeCalls.get()
            val statsBefore = f.app.state.value.dailyStats
            f.tcpLatency.set(77)
            f.app.refreshServerLatencies()
            withTimeout(5_000) { f.app.state.first { it.latencyMs == 77L } }
            withTimeout(5_000) {
                (DesktopController::class.java.getDeclaredField("latencyJob").apply { isAccessible = true }.get(f.app) as Job?)?.join()
            }
            val tcpBefore = f.tcpCalls.get()
            f.tcpLatency.set(-1)
            f.app.refreshServerLatencies()
            withTimeout(5_000) { while (f.tcpCalls.get() == tcpBefore) delay(5) }
            withTimeout(5_000) {
                (DesktopController::class.java.getDeclaredField("latencyJob").apply { isAccessible = true }.get(f.app) as Job?)?.join()
            }
            assertEquals("An unavailable sample must retain the previous ping on the same active network", 77L, f.app.state.value.latencyMs)
            assertEquals(77, f.app.state.value.servers.single().latencyMs)
            assertEquals(probesBefore, f.runtime.probeCalls.get())
            assertEquals(statsBefore.sumOf { it.pingSamples }, f.app.state.value.dailyStats.sumOf { it.pingSamples })
            assertTrue(f.app.state.value.running && f.app.state.value.verified)
            assertEquals(active, f.runtime.active)
            assertEquals(1, f.runtime.starts.size)
            assertEquals(1, f.sessionNodes.size)
        }
    }

    @Test fun `watchdog latency updates statistics without adding a session or forcing a checkpoint`() = runBlocking {
        Fixture().use { f ->
            f.ready(); f.measureLatencies(); f.app.connect()
            withTimeout(15_000) { f.app.state.first { !it.loading && it.verified } }
            val before = f.app.state.value.dailyStats
            assertEquals(1, before.sumOf { it.sessions })
            assertEquals(1, before.sumOf { it.pingSamples })
            assertEquals(30L, before.sumOf { it.pingSumMs })
            val path = f.vault.directory.resolve("stats.dpapi")
            val checkpoint = Files.readAllBytes(path)
            f.app.verifyConnection()
            withTimeout(5_000) { f.app.state.first { it.dailyStats.sumOf { day -> day.pingSamples } == 2 } }
            assertEquals(1, f.app.state.value.dailyStats.sumOf { it.sessions })
            assertEquals(60L, f.app.state.value.dailyStats.sumOf { it.pingSumMs })
            assertEquals(54L, f.app.state.value.latencyMs)
            assertArrayEquals("A watchdog sample must use the normal checkpoint interval", checkpoint, Files.readAllBytes(path))
            f.runtime.failProbes.set(1)
            f.app.verifyConnection()
            withTimeout(5_000) { f.app.state.first { !it.verified } }
            assertEquals(2, f.app.state.value.dailyStats.sumOf { it.pingSamples })
            assertEquals(60L, f.app.state.value.dailyStats.sumOf { it.pingSumMs })
            assertEquals(1, f.app.state.value.dailyStats.sumOf { it.sessions })
            assertTrue(f.app.state.value.running)
            assertEquals(1, f.runtime.starts.size)
        }
    }

    @Test fun `home latency belongs to the active node rather than the fastest country member`() {
        val nodes = listOf(VpnServer("nl1", "NL1", "NL", "NL", "one.invalid", 443, true, latencyMs = 54),
            VpnServer("nl2", "NL2", "NL", "NL", "two.invalid", 443, true, latencyMs = 95))
        val state = DesktopState(running = true, verified = true, selectedServerId = "nl2",
            locations = listOf(ServerLocation("NL", "NL", "Netherlands", "", "one.invalid", isOnline = true,
                latencyMs = 54, servers = nodes)))
        assertEquals(95L, state.latencyMs)
        assertEquals(54L, state.copy(selectedServerId = "nl1").latencyMs)
        assertNull(state.copy(selectedServerId = "missing").latencyMs)
        assertNull(state.copy(running = false).latencyMs)
        assertNull(state.copy(verified = false).latencyMs)
    }

    @Test fun `a late probe cannot restore connected state after disconnect`() = runBlocking {
        Fixture().use { f ->
            f.ready(); f.app.connect()
            withTimeout(15_000) { f.app.state.first { !it.loading && it.verified } }
            val gate = CompletableDeferred<Unit>()
            f.runtime.probeGate = gate
            f.runtime.probeIgnoresCancellation = true
            val statsBefore = f.app.state.value.dailyStats
            val before = f.runtime.probeCalls.get()
            try {
                f.app.verifyConnection()
                withTimeout(5_000) { while (f.runtime.probeCalls.get() == before) delay(5) }
                f.app.disconnect()
                withTimeout(5_000) { f.app.state.first { !it.loading && !it.running } }
                val checkpoint = Files.readAllBytes(f.vault.directory.resolve("stats.dpapi"))
                gate.complete(Unit)
                withTimeout(5_000) { f.app.closeAndJoin() }
                assertEquals(statsBefore.sumOf { it.pingSamples }, f.app.state.value.dailyStats.sumOf { it.pingSamples })
                assertEquals(statsBefore.sumOf { it.pingSumMs }, f.app.state.value.dailyStats.sumOf { it.pingSumMs })
                assertEquals(statsBefore.sumOf { it.sessions }, f.app.state.value.dailyStats.sumOf { it.sessions })
                assertArrayEquals("A stale probe must not dirty the checkpoint", checkpoint, Files.readAllBytes(f.vault.directory.resolve("stats.dpapi")))
            } finally { gate.complete(Unit) }
            assertFalse(f.app.state.value.verified)
            assertNull(f.app.state.value.latencyMs)
            assertNull(f.vault.readEncrypted("connection.dpapi"))
            assertEquals(1, f.runtime.starts.size)
        }
    }

    @Test fun `disconnect cancels an in-flight initial connection`() = runBlocking {
        Fixture().use { f ->
            f.ready()
            f.runtime.probeGate = CompletableDeferred()
            f.app.connect()
            withTimeout(15_000) {
                f.app.state.first { it.connecting }
                while (f.runtime.probeCalls.get() == 0) delay(5)
            }
            f.app.disconnect()
            withTimeout(5_000) { f.app.state.first { !it.loading && !it.running } }
            assertNull(f.runtime.active)
            assertFalse(f.app.state.value.verified)
            assertNull(f.vault.readEncrypted("connection.dpapi"))
        }
    }

    @Test fun `disconnect cancels a connection started by changing location`() = runBlocking {
        Fixture().use { f ->
            f.ready(); f.app.connect()
            withTimeout(15_000) {
                f.app.state.first { !it.loading && it.verified }
                // The state is published before the initial connection job finishes.
                (DesktopController::class.java.getDeclaredField("connectionJob").apply { isAccessible = true }.get(f.app) as Job).join()
            }
            val before = f.runtime.probeCalls.get()
            f.runtime.probeGate = CompletableDeferred()
            f.app.selectLocation(ServerSelectionMode.AUTO)
            withTimeout(15_000) {
                f.app.state.first { it.connecting }
                while (f.runtime.probeCalls.get() <= before) delay(5)
            }
            f.app.disconnect()
            withTimeout(5_000) { f.app.state.first { !it.loading && !it.running } }
            assertNull(f.runtime.active)
            assertNull(f.vault.readEncrypted("connection.dpapi"))
        }
    }

    @Test fun `RU multihop resolves country choices with the same physical weights`() {
        val nodes = (1..4).map { i -> RuForeignNode("00000000-0000-4000-8000-${i.toString().padStart(12, '0')}", "Node $i", if (i < 3) "DE" else "FR") }
        val servers = nodes.mapIndexed { i, node -> VpnServer(node.id, node.name, node.countryCode, node.countryCode, "example.invalid", null, true,
            weight = listOf(0, 1, 1, 9)[i], metricsAt = java.time.Instant.now().toString()) }
        val method = DesktopController.Companion::class.java.getDeclaredMethod("ruServers", List::class.java, List::class.java).also { it.isAccessible = true }
        @Suppress("UNCHECKED_CAST") val ranked = method.invoke(DesktopController.Companion, nodes, servers) as List<VpnServer>
        val chosen = DesktopController.resolveMultiHop(MultiHopSettings(true, HopSelection(ServerSelectionMode.COUNTRY, "DE"),
            HopSelection(ServerSelectionMode.COUNTRY, "FR")), ranked, true) { .5 }
        assertEquals(nodes[1].id, chosen.entry?.nodeId)
        assertEquals(nodes[3].id, chosen.exit?.nodeId)
    }

    @Test fun `multihop health is isolated by network and both hops`() {
        val entry = "00000000-0000-4000-8000-000000000001"
        val exit = "00000000-0000-4000-8000-000000000002"
        val route = BackendMultiHopSession(MultiHopSettings(true, HopSelection(ServerSelectionMode.SERVER, nodeId = entry),
            HopSelection(ServerSelectionMode.SERVER, nodeId = exit)), entry, exit, "192.0.2.1", "a".repeat(64))
        val wifi = EndpointRankingPolicy.NetworkKind.WIFI
        val raw = mapOf(DesktopRouteHealthScope.routeKey(wifi, route, "endpoint") to EndpointHealth(score = 25),
            EndpointHealthScope.key(wifi, "endpoint") to EndpointHealth(score = 78))
        assertEquals(25, DesktopRouteHealthScope.forRoute(raw, wifi, route)["endpoint"]?.score)
        assertEquals(78, EndpointHealthScope.forNetwork(raw, wifi)["endpoint"]?.score)
        assertTrue(DesktopRouteHealthScope.forRoute(raw, EndpointRankingPolicy.NetworkKind.CELLULAR, route).isEmpty())
        assertTrue(DesktopRouteHealthScope.forRoute(raw, wifi, route.copy(exitNodeId = entry)).isEmpty())
    }

    @Test fun `statistics update in memory and flush once at handoff`() = runBlocking {
        Fixture().use { f ->
            f.ready(); f.app.connect()
            withTimeout(15_000) { f.app.state.first { !it.loading && it.verified } }
            val path = f.vault.directory.resolve("stats.dpapi")
            val before = Files.readAllBytes(path)
            val method = DesktopController::class.java.getDeclaredMethod("recordMetrics", SingBoxRuntime.Running::class.java, SingBoxRuntime.Metrics::class.java)
                .also { it.isAccessible = true }
            val active = checkNotNull(f.runtime.active)
            val lock = DesktopController::class.java.getDeclaredField("statsLock").also { it.isAccessible = true }.get(f.app)
            synchronized(lock) {
                DesktopController::class.java.getDeclaredField("hourlyStatsRuntime").also { it.isAccessible = true }.set(f.app, null)
                DesktopController::class.java.getDeclaredField("statsCursor").also { it.isAccessible = true }.set(f.app, null)
                method.invoke(f.app, active, SingBoxRuntime.Metrics(10, 20, 100, 200))
                method.invoke(f.app, active, SingBoxRuntime.Metrics(10, 20, 150, 350))
                method.invoke(f.app, active, SingBoxRuntime.Metrics(10, 20, 90, 150))
            }
            assertEquals(350L, f.app.state.value.dailyStats.sumOf { it.rxBytes })
            assertEquals(150L, f.app.state.value.dailyStats.sumOf { it.hourlyRxBytes.sum() })
            assertEquals(50L, f.app.state.value.dailyStats.sumOf { it.hourlyTxBytes.sum() })
            assertArrayEquals("A UI sample must not rewrite the encrypted checkpoint", before, Files.readAllBytes(path))
            f.app.closeAndJoin()
            assertEquals(350L, WindowsVault(f.vault.directory).loadStats().sumOf { it.rxBytes })
            assertEquals(150L, WindowsVault(f.vault.directory).loadStats().sumOf { it.hourlyRxBytes.sum() })
            assertEquals(50L, WindowsVault(f.vault.directory).loadStats().sumOf { it.hourlyTxBytes.sum() })
        }
    }

    @Test fun `automatic log interval stays in memory and limits each controller to fifteen minutes`() = runBlocking {
        Fixture(automaticLogs = false).use { f ->
            f.ready()
            val claim = DesktopController::class.java.getDeclaredMethod("claimAutomaticLogUpload", java.lang.Long.TYPE).also { it.isAccessible = true }
            val now = System.currentTimeMillis()
            assertEquals(true, claim.invoke(f.app, now))
            assertEquals(false, claim.invoke(f.app, now + 10_000))
            f.app.closeAndJoin()
            f.app = DesktopController(f.vault, f.runtime, f.api, {}, f.network, dnsPolicySnapshot = { f.dnsSnapshot() })
            f.ready()
            assertEquals(true, claim.invoke(f.app, now + 10_000))
            assertEquals(false, claim.invoke(f.app, now + 20_000))
            assertEquals(true, claim.invoke(f.app, now + 15 * 60_000 + 10_001))
        }
    }

    private class FakeRuntime(vault: WindowsVault) : SingBoxRuntime(vault) {
        val failRestores = AtomicInteger()
        @Volatile var active: Running? = null
        val starts = CopyOnWriteArrayList<JSONObject>()
        val startTimes = CopyOnWriteArrayList<Long>()
        val failProbes = AtomicInteger()
        val failedProbeNodes = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        val probeCalls = AtomicInteger()
        val watchdogProbes = AtomicInteger()
        val metricsCalls = AtomicInteger()
        @Volatile var metricsGate: CompletableDeferred<Unit>? = null
        val rebindCalls = AtomicInteger()
        val failRebinds = AtomicInteger()
        val stops = CopyOnWriteArrayList<Boolean>()
        val failStops = AtomicInteger()
        val youtubeStarts = AtomicInteger()
        val youtubeNetworks = CopyOnWriteArrayList<WindowsNetworkSnapshot>()
        @Volatile var youtubeRunning = false
        @Volatile var failLocalYoutube = false
        @Volatile var failYoutubePort = false
        @Volatile var failYoutubeStops = false
        @Volatile var youtubeGate: CompletableDeferred<Unit>? = null
        internal override fun youtubePort(): Int {
            if (failYoutubePort) throw java.io.IOException("Simulated local port allocation failure")
            return 1080
        }
        internal override suspend fun startYoutube(network: WindowsNetworkSnapshot): Int {
            youtubeStarts.incrementAndGet()
            youtubeNetworks += network
            youtubeGate?.await()
            check(!failLocalYoutube) { "Simulated local process failure" }
            youtubeRunning = true
            return 1080
        }
        internal override suspend fun stopYoutube() {
            check(!failYoutubeStops) { "Simulated child identity failure" }
            youtubeRunning = false
        }
        internal override suspend fun youtubeAlive(): Boolean = youtubeRunning
        @Volatile var armed = false
        @Volatile var failProtection = false
        @Volatile var failGuardRead = false
        val guardEvents = CopyOnWriteArrayList<String>()
        @Volatile var startDelayMillis = 0L
        @Volatile var probeGate: CompletableDeferred<Unit>? = null
        @Volatile var probeIgnoresCancellation = false
        @Volatile var logLines: List<CoreLogLine> = emptyList()
        internal override fun coreLogs(current: Running) = kotlinx.coroutines.flow.flowOf(logLines)
        override fun restored(): Running? {
            if (failRestores.getAndUpdate { (it - 1).coerceAtLeast(0) } > 0) error("Transient snapshot failure")
            return active
        }
        override fun guardArmed(): Boolean {
            check(!failGuardRead) { "Simulated guard status failure" }
            return armed
        }
        override suspend fun start(config: JSONObject, label: String, killSwitch: Boolean, serverId: String?): Running {
            check(active == null)
            guardEvents += "start:$killSwitch"
            if (killSwitch) armed = true
            starts += JSONObject(config.toString())
            startTimes += System.currentTimeMillis()
            delay(startDelayMillis)
            return Running(10_000 + starts.size, System.currentTimeMillis(), 10000, "fixture", 10001, label, serverId).also { active = it }
        }
        override suspend fun probe(current: Running): Long {
            probeCalls.incrementAndGet()
            probeGate?.let { gate ->
                if (probeIgnoresCancellation) withContext(NonCancellable) { gate.await() } else gate.await()
            }
            if (failProbes.getAndUpdate { (it - 1).coerceAtLeast(0) } > 0) throw java.io.IOException("Simulated tunnel failure")
            if (current.serverId?.let { it in failedProbeNodes } == true) throw java.io.IOException("Simulated failed node")
            return 30
        }
        override suspend fun probe(current: Running, fastStartup: Boolean): Long = probe(current)
        override suspend fun probeWatchdog(current: Running): Long {
            watchdogProbes.incrementAndGet()
            return probe(current)
        }
        override suspend fun rebindNetwork(current: Running) {
            check(active == current)
            rebindCalls.incrementAndGet()
            if (failRebinds.getAndUpdate { (it - 1).coerceAtLeast(0) } > 0) throw java.io.IOException("Simulated interface debounce")
        }
        override suspend fun metrics(current: Running): Metrics {
            metricsCalls.incrementAndGet()
            metricsGate?.await()
            return Metrics(0, 0, 0, 0)
        }
        override suspend fun stop(restoreInternet: Boolean) {
            stops += restoreInternet
            guardEvents += "stop:$restoreInternet"
            if (failStops.getAndUpdate { (it - 1).coerceAtLeast(0) } > 0) throw java.io.IOException("Simulated core stop failure")
            active = null
            if (restoreInternet) armed = false
        }
        override suspend fun protectConnection() {
            guardEvents += "protect"
            check(!failProtection) { "Simulated guard setup failure" }
            armed = true
        }
        override suspend fun releaseGuard(allowConnected: Boolean) {
            check(allowConnected || active == null)
            guardEvents += "release"
            armed = false
        }
    }

    private class Fixture(nodeCount: Int = 1, private val failFirstSession: Boolean = false, trafficPool: String = "primary", automaticLogs: Boolean = true,
        private val nvCandidates: Boolean = false, private val hy2Candidates: Boolean = false,
        private val nodeCountries: List<String> = List(nodeCount) { "DE" }) : AutoCloseable {
        val vault = WindowsVault(Files.createTempDirectory("noki-connection-flow"))
        val runtime = FakeRuntime(vault)
        val sessionNodes = CopyOnWriteArrayList<String>()
        val sessionRequests = CopyOnWriteArrayList<JSONObject>()
        val sessionStatus = AtomicInteger()
        val poolStatus = AtomicInteger(200)
        @Volatile var poolReplyLost = false
        @Volatile var poolCommitGate: java.util.concurrent.CountDownLatch? = null
        val poolCommitted = CompletableDeferred<Unit>()
        val incyDeviceId = "33333333-3333-4333-8333-333333333333"
        val incyDevices = JSONArray()
        @Volatile var incyReadGate: java.util.concurrent.CountDownLatch? = null
        val incyReadStarted = CompletableDeferred<Unit>()
        val incyPoolCalls = AtomicInteger()
        @Volatile var sessionSecret = "00000000-0000-4000-8000-000000000099"
        val tcpCalls = AtomicInteger()
        val tcpLatency = AtomicInteger(54)
        val bootstrapCalls = AtomicInteger()
        val locationsCalls = AtomicInteger()
        val clock = java.util.concurrent.atomic.AtomicLong(1000)
        val bootstrapStatus = AtomicInteger(200)
        val logStatus = AtomicInteger(200)
        @Volatile var logRetryAfter: String? = null
        val uploadedLogs = CopyOnWriteArrayList<String>()
        @Volatile var bootstrapGate: java.util.concurrent.CountDownLatch? = null
        @Volatile var sessionGate: java.util.concurrent.CountDownLatch? = null

        val wireGuardRegistrationCalls = AtomicInteger()



        val ruRevokeCalls = AtomicInteger()
        @Volatile var ruRevokeGate: java.util.concurrent.CountDownLatch? = null

        val youtubeRequests = CopyOnWriteArrayList<okhttp3.Request>()
        val proofFailures = AtomicInteger()
        private val challengeCounter = AtomicInteger()
        private val proofLock = Any()
        private var challengeNonce: String? = null
        private val deviceId = "22222222-2222-4222-8222-222222222222"
        private val wireGuardKeyId = "11111111-1111-4111-8111-111111111111"










        @Volatile var networkSignature = "fixture"
        @Volatile var dnsSnapshot: () -> WindowsDnsPolicy = { WindowsDnsPolicy(emptyList(), emptyList()) }
        @Volatile var networkAvailable = true
        val network: () -> WindowsNetworkSnapshot = { WindowsNetworkSnapshot(networkSignature, EndpointRankingPolicy.NetworkKind.WIFI, networkAvailable,
            java.net.NetworkInterface.getByInetAddress(java.net.InetAddress.getByName("127.0.0.1")).index) }
        private val listener = java.net.ServerSocket(0, 50, java.net.InetAddress.getByName("127.0.0.1"))
        private val acceptor = Thread {
            try { while (!listener.isClosed) listener.accept().close() } catch (_: java.io.IOException) { }
        }.apply { isDaemon = true; start() }
        private val nodes = (1..nodeCount).map { "00000000-0000-4000-8000-${it.toString().padStart(12, '0')}" }
        val bootstrap = JSONObject().put("user", JSONObject().put("id", "fixture-user").put("username", "Fixture").put("email", "fixture@example.invalid"))
            .put("devices", JSONArray().put(JSONObject().put("id", deviceId).put("device_key", vault.deviceKey).put("device_name", "Fixture")
                .put("platform", DesktopBuild.PLATFORM).put("is_active", true).put("traffic_pool", trafficPool)))
            .put("subscription", JSONObject().put("status", "active").put("plan_name", "Fixture"))
            .put("locations", JSONArray(nodes.zip(nodeCountries).groupBy { it.second }.map { (country, members) ->
                JSONObject().put("id", country.lowercase()).put("code", country.lowercase()).put("country_code", country).put("name", if (country == "DE") "Germany" else country)
                .put("entry_host", "127.0.0.1").put("is_online", true).put("servers", JSONArray(members.map { (id, _) ->
                    JSONObject().put("id", id).put("name", "Node").put("country_code", country).put("location_code", country.lowercase())
                        .put("host", "127.0.0.1").put("probe_port", listener.localPort).put("is_online", true).put("weight", 1).put("metrics_at", java.time.Instant.now().toString())
                })) }))
        private val http = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            if (request.url.encodedPath.contains("youtube-access")) youtubeRequests += request
            val payload = chain.request().body?.let { body -> Buffer().also(body::writeTo).readUtf8().takeIf { it.isNotBlank() }?.let(::JSONObject) } ?: JSONObject()
            var status = 200
            val body = when (chain.request().url.encodedPath) {
                "/v1/vpn/sessions/fixture-ru-session" -> {
                    ruRevokeCalls.incrementAndGet()
                    ruRevokeGate?.await(10, java.util.concurrent.TimeUnit.SECONDS)
                    JSONObject().put("ok", true)
                }
                "/v1/app/bootstrap" -> {
                    bootstrapCalls.incrementAndGet()
                    bootstrapGate?.await(10, java.util.concurrent.TimeUnit.SECONDS)
                    status = bootstrapStatus.get()
                    bootstrap
                }
                "/v1/locations" -> { locationsCalls.incrementAndGet(); bootstrap.getJSONArray("locations") }
                "/v1/vpn/access" -> JSONObject().put("can_connect", true)
                "/v1/app/logs" -> {
                    uploadedLogs += payload.getString("content")
                    status = logStatus.get()
                    JSONObject()
                }
                "/v1/devices/$deviceId/traffic-pool" -> {
                    if (request.method != "PATCH" || request.header("X-Device-Id") != deviceId || request.header("X-Device-Key") != vault.deviceKey)
                        proofFailures.incrementAndGet()
                    status = poolStatus.get()
                    if (status != 200) JSONObject().put("detail", "primary_device_limit_reached")
                    else {
                        bootstrap.getJSONArray("devices").getJSONObject(0).put("traffic_pool", payload.getString("traffic_pool"))
                        bootstrap.getJSONObject("subscription").put("shared_traffic_used_gb", 3.5).put("shared_traffic_limit_gb", 40)
                        sessionSecret = "00000000-0000-4000-8000-000000000098"
                        poolCommitted.complete(Unit)
                        poolCommitGate?.await(10, java.util.concurrent.TimeUnit.SECONDS)
                        if (poolReplyLost) throw java.io.IOException("Simulated lost pool move response")
                        bootstrap.getJSONArray("devices")
                    }
                }
                "/v1/incy/devices" -> if (request.method == "GET") {
                    incyReadGate?.let { gate ->
                        incyReadStarted.complete(Unit)
                        gate.await(10, java.util.concurrent.TimeUnit.SECONDS)
                        JSONArray().put(JSONObject().put("id", incyDeviceId).put("name", "Stale device"))
                    } ?: incyDevices
                } else { status = 503; JSONObject().put("detail", "fixture create unavailable") }
                "/v1/incy/devices/$incyDeviceId/traffic-pool" -> {
                    incyPoolCalls.incrementAndGet()
                    incyDevices.getJSONObject(0).put("traffic_pool", payload.getString("traffic_pool"))
                }
                "/v1/app/desktop-notifications", "/v1/app/notifications" -> JSONObject().put("notifications", JSONArray())
                "/v1/devices/challenge" -> synchronized(proofLock) {
                    challengeNonce = "fixture-device-nonce-${challengeCounter.incrementAndGet()}"
                    JSONObject().put("device_id", deviceId).put("nonce", challengeNonce)
                }
                "/v1/vpn/wireguard-keys" -> {
                    wireGuardRegistrationCalls.incrementAndGet()
                    if (!consumeProof(payload.getString("device_nonce"))) status = 403
                    JSONObject().put("id", wireGuardKeyId).put("public_key", payload.getString("public_key"))
                }

                "/v1/vpn/session" -> {
                    if (!consumeProof(payload.getString("device_nonce"))) status = 403
                    sessionRequests += JSONObject(payload.toString())
                    sessionGate?.await(10, java.util.concurrent.TimeUnit.SECONDS)
                    val id = payload.getString("node_id")
                    val country = payload.optString("country_code", "DE")
                    sessionNodes += id
                    if (sessionStatus.get() != 0) { status = sessionStatus.get(); JSONObject().put("detail", "subscription_required") }
                    else if (failFirstSession && sessionNodes.size == 1) { status = 503; JSONObject().put("detail", "node unavailable") }
                    else JSONObject().put("can_connect", true).put("location_code", country.lowercase()).put("location_name", if (country == "DE") "Germany" else country)
                        .put("entry_host", "127.0.0.1").put("entry_port", listener.localPort).put("server_name", "example.com")
                        .put("security", "reality").put("transport", "xhttp").put("transport_mode", "stream-up")
                        .put("public_key", "p".repeat(43)).put("short_id", "0123456789abcdef").put("path", "/tunnel")
                        .apply { if (nvCandidates) put("nokinv_token", "n".repeat(42) + "k").put("nokinv_credential_generation", 1) }
                        .put("vpn_username", "fixture").put("vpn_secret", sessionSecret)
                        .put("endpoint_candidates", JSONArray(((if (nvCandidates) listOf("nv-first", "nv-second", "a-first", "b-second") else listOf("a-first", "b-second")) +
                            if (hy2Candidates) listOf("hy2-last") else emptyList()).map { code -> JSONObject()
                            .put("code", if (nvCandidates && nodeCount > 1) "$code-${id.takeLast(1)}" else code).put("node_id", id).put("location_code", country.lowercase()).put("location_name", if (country == "DE") "Germany" else country)
                            .put("entry_host", "127.0.0.1").put("entry_port", listener.localPort).put("server_name", "example.com")
                            .put("proxy_type", when { code.startsWith("nv-") -> "nokinv"; code.startsWith("hy2-") -> "hysteria"; else -> "vless" })
                            .put("security", if (code.startsWith("nv-") || code.startsWith("hy2-")) "tls" else "reality")
                            .put("transport", when { code.startsWith("nv-") -> "nokinv"; code.startsWith("hy2-") -> "hysteria"; else -> "xhttp" })
                            .put("transport_mode", if (code.startsWith("nv-") || code.startsWith("hy2-")) "" else "stream-up")
                            .apply { if (!code.startsWith("nv-") && !code.startsWith("hy2-")) put("public_key", "p".repeat(43)).put("short_id", "0123456789abcdef") }
                            .put("path", if (code.startsWith("hy2-")) "/" else "/tunnel")
                            .put("priority", if (code.startsWith("hy2-")) 100 else 0).put("weight", 1) }))
                }
                else -> { status = 503; JSONObject().put("detail", "offline optional service") }
            }
            okhttp3.Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(status).message("fixture")
                .apply { if (request.url.encodedPath == "/v1/app/logs") logRetryAfter?.let { header("Retry-After", it) } }
                .body(body.toString().toResponseBody("application/json".toMediaType())).build()
        }.build()
        val api = BackendApiClient(http, "https://fixture.invalid")
        var app: DesktopController
        init {
            vault.deviceId = deviceId
            vault.saveTokens(BackendAuthTokens("fixture-access", null))
            if (!automaticLogs) vault.saveSettings(vault.loadSettings().let {
                it.copy(advancedSettings = it.advancedSettings.copy(anonymousLogsEnabled = false))
            })
            app = DesktopController(vault, runtime, api, {}, network,
                latencySampler = { ClientLatencySampler(tcpConnect = { _, _, _ -> tcpCalls.incrementAndGet(); tcpLatency.get().takeIf { it >= 0 } }, icmpPing = { _, _, _ -> null }) },
                monotonicMillis = clock::get, dnsPolicySnapshot = { dnsSnapshot() })
        }
        suspend fun ready() = withTimeout(5_000) {
            app.state.first { !it.loading && it.user != null }
            val operation = DesktopController::class.java.getDeclaredField("operation").apply { isAccessible = true }
                .get(app) as kotlinx.coroutines.sync.Mutex
            operation.withLock { }
        }
        suspend fun measureLatencies() {
            app.refreshServerLatencies()
            (DesktopController::class.java.getDeclaredField("latencyJob").also { it.isAccessible = true }.get(app) as Job?)?.join()
        }
        suspend fun prepareInAdvance() {
            app.setWindowVisible(true)
            val field = DesktopController::class.java.getDeclaredField("preparationJob").also { it.isAccessible = true }
            withTimeout(15_000) {
                while ((field.get(app) as? Deferred<*>)?.isCompleted != true) delay(10)
            }
            app.setWindowVisible(false)
        }
        suspend fun enableYoutube() {
            bootstrap.getJSONObject("subscription").put("plan_code", "pro").put("plan_name", "Pro")
            app.refresh().join()
            app.updateSettings { it.copy(advancedSettings = it.advancedSettings.copy(youtubeAdBlockEnabled = true)) }.join()
        }
        suspend fun awaitYoutubeReady() {
            withTimeout(12_000) {
                while (!app.state.value.running || app.state.value.loading || !runtime.youtubeRunning ||
                    runtime.starts.lastOrNull()?.let(::youtubeEndpoint) == null) delay(10)
            }
        }
        fun mainConfig(): JSONObject = (DesktopController::class.java.getDeclaredField("activeConnection")
            .apply { isAccessible = true }.get(app) as JSONObject).getJSONObject("config")
        private fun consumeProof(nonce: String?): Boolean = synchronized(proofLock) {
            if (nonce == null || nonce != challengeNonce) {
                proofFailures.incrementAndGet()
                false
            } else {
                challengeNonce = null
                true
            }
        }

        @Suppress("UNCHECKED_CAST")
        fun markActiveRu() {
            val mutable = DesktopController::class.java.getDeclaredField("mutable").apply { isAccessible = true }.get(app) as MutableStateFlow<DesktopState>
            val settings = mutable.value.displayedSettings.let { it.copy(advancedSettings = it.advancedSettings.copy(ruRelayEnabled = true)) }
            val connection = DesktopController::class.java.getDeclaredField("activeConnection").apply { isAccessible = true }.get(app) as JSONObject
            connection.put("settings", vault.encodeSettings(settings))
            mutable.value = mutable.value.copy(connectionSettings = settings)
        }
        override fun close() {
            poolCommitGate?.countDown()
            bootstrapGate?.countDown()

            runBlocking { withTimeout(5_000) { app.closeAndJoin() } }
            app.close(); listener.close(); acceptor.join(1_000)
            http.connectionPool.evictAll(); http.dispatcher.executorService.shutdownNow()
        }
    }
}
