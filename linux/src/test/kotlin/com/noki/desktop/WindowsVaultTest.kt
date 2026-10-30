package com.noki.desktop

import com.noki.vpn.data.BackendAuthTokens
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.nio.file.Files
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

class WindowsVaultTest {
    @Test fun `automatic recovery defaults on and persists across unrelated preference saves`() {
        val directory = Files.createTempDirectory("noki-recovery-preference")
        try {
            val vault = WindowsVault(directory)
            assertTrue(vault.loadAutoRecovery())
            vault.saveAutoRecovery(false)
            vault.saveSettings(vault.loadSettings())
            vault.saveRoutingRules(vault.loadRoutingRules())
            vault.saveReducedMotion(true)
            vault.saveEarthDisabled(true)
            assertFalse(WindowsVault(directory).loadAutoRecovery())
            vault.saveAutoRecovery(true)
            assertTrue(WindowsVault(directory).loadAutoRecovery())
        } finally {
            Files.list(directory).use { it.forEach(Files::delete) }; Files.delete(directory)
        }
    }

    @Test fun `hardware identity survives vault loss while RSA proof rotates`() {
        val directory = Files.createTempDirectory("noki-hardware")
        try {
            val first = WindowsVault(directory) { "smbios:0123456789abcdef0123456789abcdef" }
            val key = first.deviceKey
            val publicKey = first.publicKey
            Files.delete(directory.resolve("session.dpapi"))
            val second = WindowsVault(directory) { "smbios:0123456789abcdef0123456789abcdef" }
            assertEquals(key, second.deviceKey)
            assertNotEquals(publicKey, second.publicKey)
            assertFalse(second.needsHardwareRegistration)
            assertEquals(key, WindowsVault(directory) { null }.deviceKey)
            assertNotEquals(key, WindowsVault(directory) { "smbios:fedcba9876543210fedcba9876543210" }.registrationDeviceKey)
        } finally {
            Files.list(directory).use { it.forEach(Files::delete) }; Files.delete(directory)
        }
    }

    @Test fun `legacy identity changes only after hardware registration succeeds`() {
        val directory = Files.createTempDirectory("noki-hardware-migration")
        try {
            val legacy = WindowsVault(directory) { null }
            legacy.deviceId = "existing-device"
            legacy.saveTokens(BackendAuthTokens("access", "refresh"))
            val migrated = WindowsVault(directory) { "smbios:fixture" }
            assertEquals(legacy.deviceKey, migrated.deviceKey)
            assertTrue(migrated.needsHardwareRegistration)
            val device = com.noki.vpn.data.BackendDevice("existing-device", migrated.registrationDeviceKey,
                "PC", platform = "windows", accessRole = "owner", isActive = true, lastSeenAt = null)
            migrated.acceptRegisteredDevice(device)
            val restored = WindowsVault(directory) { "smbios:fixture" }
            assertEquals(device.deviceKey, restored.deviceKey)
            assertEquals("existing-device", restored.deviceId)
            assertEquals(legacy.publicKey, restored.publicKey)
            assertEquals("access", restored.loadTokens()?.accessToken)
            assertFalse(restored.needsHardwareRegistration)
        } finally {
            Files.list(directory).use { it.forEach(Files::delete) }; Files.delete(directory)
        }
    }

    @Test fun `SMBIOS identity parser rejects missing placeholder and truncated UUIDs`() {
        val raw = ByteArray(8 + 24 + 2)
        raw[1] = 3; raw[2] = 2; raw[4] = 26; raw[8] = 1; raw[9] = 24
        for (i in 0..15) raw[16 + i] = (i + 1).toByte()
        assertEquals("smbios:0102030405060708090a0b0c0d0e0f10", WindowsVault.hardwareIdentityFromSmbios(raw))
        for (end in 0 until raw.size) assertNull(WindowsVault.hardwareIdentityFromSmbios(raw.copyOf(end)))
        for (placeholder in listOf(0.toByte(), 255.toByte())) {
            raw.fill(placeholder, 16, 32)
            assertNull(WindowsVault.hardwareIdentityFromSmbios(raw))
        }
        raw[9] = 3
        assertNull(WindowsVault.hardwareIdentityFromSmbios(raw))
    }

    private fun dacl(path: java.nio.file.Path): String {
        val api = com.sun.jna.platform.win32.Advapi32.INSTANCE
        val length = com.sun.jna.ptr.IntByReference()
        api.GetFileSecurity(path.toString(), 4, null, 0, length)
        com.sun.jna.Memory(length.value.toLong()).use { descriptor ->
            assertTrue(api.GetFileSecurity(path.toString(), 4, descriptor, length.value, length))
            val text = com.sun.jna.ptr.PointerByReference()
            val convert = com.sun.jna.Function.getFunction("advapi32", "ConvertSecurityDescriptorToStringSecurityDescriptorW", com.sun.jna.Function.ALT_CONVENTION)
            assertNotEquals(0, convert.invokeInt(arrayOf(descriptor, 1, 4, text, null)))
            try { return text.value.getWideString(0) }
            finally { com.sun.jna.platform.win32.Kernel32.INSTANCE.LocalFree(text.value) }
        }
    }

    @Test fun `private vault ACL uses only token SID and new children inherit full access`() {
        assumeTrue(System.getProperty("os.name").startsWith("Windows"))
        val directory = Files.createTempDirectory("noki-vault-acl")
        try {
            val vault = WindowsVault(directory)
            val sid = WindowsNetworkGuard.currentUserSid()
            assertEquals("D:P(A;OICI;FA;;;$sid)", dacl(directory))
            val child = Files.writeString(directory.resolve("inherited.txt"), "inherit")
            assertEquals("D:(A;;FA;;;$sid)", dacl(child))
            val childDirectory = Files.createDirectory(directory.resolve("child"))
            assertEquals("D:(A;OICI;FA;;;$sid)", dacl(childDirectory))
            val privateFile = vault.writePrivate("vpn.json", "private")
            assertEquals("D:P(A;;FA;;;$sid)", dacl(privateFile))
            vault.writeEncrypted("roundtrip.dpapi", "encrypted")
            assertEquals("encrypted", WindowsVault(directory).readEncrypted("roundtrip.dpapi"))
            assertEquals("D:P(A;;FA;;;$sid)", dacl(directory.resolve("roundtrip.dpapi")))
        } finally { Files.list(directory).use { it.forEach(Files::delete) }; Files.delete(directory) }
    }

    @Test fun `NV settings round trip preserves credentials manual family gaming and explicit RU choice`() {
        val codec = com.noki.vpn.data.StoredSettingsCodec { DesktopState().settings }
        val initial = codec.decode(null)
        assertFalse(initial.advancedSettings.ruRelayEnabled)
        assertTrue(codec.decode("{\"ruRelayEnabled\":true}").advancedSettings.ruRelayEnabled)
        val profile = com.noki.vpn.data.VlessProfile(proxyType = "nokinv", transport = "nokinv", security = "tls",
            host = "8.8.8.8", serverName = "exit.example", path = "/tunnel", nokinvToken = "A".repeat(43),
            nokinvCredentialGeneration = 7, nokinvMode = "gaming")
        val settings = initial.copy(profile = profile, isAuthenticated = true, backendAccessToken = "access",
            advancedSettings = initial.advancedSettings.copy(
            protocol = com.noki.vpn.data.VpnProtocol.REALITY, gamingModeEnabled = true,
            endpointSelectionMode = com.noki.vpn.data.EndpointSelectionMode.MANUAL,
            manualEndpointGroupKey = "lv|reality|xhttp|stream-up|100"))
        val restored = codec.decode(codec.encode(settings))
        assertEquals(profile, restored.profile)
        assertTrue(restored.advancedSettings.gamingModeEnabled)
        assertEquals(com.noki.vpn.data.VpnProtocol.NOKINV, restored.advancedSettings.effectiveProtocol)
        assertEquals(com.noki.vpn.data.VpnProtocol.REALITY, restored.advancedSettings.copy(gamingModeEnabled = false).effectiveProtocol)
        assertEquals("reality|xhttp|stream-up", restored.advancedSettings.manualEndpointGroupKey)
        val enabledRu = restored.copy(advancedSettings = restored.advancedSettings.copy(ruRelayEnabled = true, gamingModeEnabled = false))
        assertTrue(codec.decode(codec.encode(enabledRu)).advancedSettings.ruRelayEnabled)
        val malformed = org.json.JSONObject(codec.encode(settings)).put("nokinvCredentialGeneration", "7")
        assertEquals("", codec.decode(malformed.toString()).profile.nokinvToken)
        assertEquals(restored.backendAccessToken, codec.decode(malformed.toString()).backendAccessToken)
    }

    @Test fun `upgrade disables old RU relay once and preserves later user choice`() {
        val directory = Files.createTempDirectory("noki-relay-upgrade")
        try {
            val vault = WindowsVault(directory)
            vault.writeEncrypted("settings.dpapi", """{"ruRelayEnabled":true,"anonymousLogsEnabled":false}""")
            val migrated = vault.loadSettings()
            assertFalse(migrated.advancedSettings.ruRelayEnabled)
            assertFalse(migrated.advancedSettings.anonymousLogsEnabled)
            vault.saveSettings(migrated.copy(advancedSettings = migrated.advancedSettings.copy(ruRelayEnabled = true)))
            assertTrue(WindowsVault(directory).loadSettings().advancedSettings.ruRelayEnabled)
        } finally { Files.list(directory).use { it.forEach(Files::delete) }; Files.delete(directory) }
    }

    @Test fun `startup sampler takes one sample while UI keeps median three and numeric IPv4 skips DNS`() = runBlocking {
        var calls = 0
        val samples = listOf(30, 10, 20)
        val sampler = com.noki.vpn.data.ClientLatencySampler(tcpConnect = { _, _, _ -> samples[(calls++) % 3] },
            icmpPing = { _, _, _ -> error("unexpected ICMP") })
        val location = com.noki.vpn.data.ServerLocation("lv", country = "Latvia", city = "", host = "8.8.8.8", isOnline = true)
        assertEquals(listOf(30), sampler.measureForStartup(listOf(location)).values.toList())
        assertEquals(1, calls)
        calls = 0
        assertEquals(listOf(20), sampler.measure(listOf(location)).values.toList())
        assertEquals(3, calls)
        assertEquals("8.8.8.8", com.noki.vpn.data.DeviceLatency.resolveAddress("8.8.8.8", 10,
            lookup = { error("numeric IPv4 must not enter DNS executor") })!!.hostAddress)
    }

    @Test fun `startup sampler includes queued targets in one deadline and cancels blocking samples`() = runBlocking {
        val active = java.util.concurrent.atomic.AtomicInteger()
        val sampler = com.noki.vpn.data.ClientLatencySampler(tcpConnect = { _, _, _ ->
            active.incrementAndGet()
            try { Thread.sleep(5_000); null } finally { active.decrementAndGet() }
        })
        val locations = (1..8).map { com.noki.vpn.data.ServerLocation("node-$it", country = "LV", city = "", host = "host-$it", isOnline = true) }
        val started = System.nanoTime()
        assertTrue(sampler.measureForStartup(locations).isEmpty())
        assertEquals(0, active.get())
        assertTrue((System.nanoTime() - started) / 1_000_000 < 1_500)
    }
    @Test fun `diagnostic outbox retains failed batches and preserves newer entries after acknowledgement`() {
        val directory = Files.createTempDirectory("noki-diagnostic-outbox")
        try {
            val vault = WindowsVault(directory)
            val settings = com.noki.vpn.data.AdvancedSettings()
            DesktopDiagnostics(vault) { settings }.use { logs ->
                val cause = java.io.IOException("Authorization: Bearer test-secret; user@example.invalid")
                cause.stackTrace = Array(32) { StackTraceElement("com.noki.Source", "operation", "LongSource" + "x".repeat(480) + ".kt", it + 1) }
                repeat(36) { logs.record("network_failed_$it", error = true, cause = cause) }
                val first = logs.batch()
                assertTrue(first.size in 1..35)
                val text = first.joinToString("") { DesktopDiagnostics.render(it) }
                assertTrue(text.length <= 160_000)
                assertTrue(text.contains("at com.noki.Source.operation"))
                assertFalse(text.contains("test-secret"))
                assertFalse(text.contains("user@example.invalid"))
                DesktopDiagnostics(vault) { settings }.use { reopened -> assertEquals(first, reopened.batch()) }
                logs.record("newer_event")
                logs.acknowledge(first)
                DesktopDiagnostics(vault) { settings }.use { reopened ->
                    val remaining = reopened.batch()
                    assertTrue(remaining.none { it in first })
                    assertTrue(com.noki.vpn.data.AppLogStore.decode(vault.readEncrypted("logs.dpapi")).any { it.message == "newer_event" })
                }
            }
        } finally { Files.walk(directory).use { it.sorted(Comparator.reverseOrder()).forEach(Files::delete) } }
    }

    @Test fun `fatal diagnostic survives restart and obeys logging opt out`() {
        val directory = Files.createTempDirectory("noki-fatal-diagnostic")
        try {
            val vault = WindowsVault(directory)
            var settings = com.noki.vpn.data.AdvancedSettings()
            val previous = Thread.getDefaultUncaughtExceptionHandler()
            DesktopDiagnostics(vault) { settings }.use { logs ->
                logs.install()
                logs.captureFailure(OutOfMemoryError("simulated"))
                assertTrue(Files.exists(directory.resolve("crash.dpapi")))
            }
            assertSame(previous, Thread.getDefaultUncaughtExceptionHandler())
            DesktopDiagnostics(vault) { settings }.use { logs ->
                val batch = logs.batch()
                val crash = batch.single { it.category == "crash" }
                assertTrue(crash.details.orEmpty().contains("at com.noki.desktop.WindowsVaultTest"))
                assertTrue(crash.details.orEmpty().contains("heap_max_bytes="))
                logs.acknowledge(batch)
                assertFalse(Files.exists(directory.resolve("crash.dpapi")))
                settings = settings.copy(connectionLogsEnabled = false, errorLogsEnabled = false, anonymousLogsEnabled = false)
                logs.applySettings(settings)
                logs.captureFailure(IllegalStateException("disabled"))
                assertTrue(logs.batch().isEmpty())
                assertFalse(Files.exists(directory.resolve("crash.dpapi")))
            }
        } finally { Files.walk(directory).use { it.sorted(Comparator.reverseOrder()).forEach(Files::delete) } }
    }

    @Test fun `settings ignore the obsolete global DNS toggle and DNS rule lists`() {
        val codec = com.noki.vpn.data.StoredSettingsCodec { DesktopState().settings }
        assertFalse(org.json.JSONObject(codec.encode(codec.decode(null))).has("dnsProtectionEnabled"))
        val expected = codec.decode("{\"anonymousLogsEnabled\":false}")
        for (enabled in listOf(false, true)) {
            val settings = codec.decode(org.json.JSONObject().put("dnsProtectionEnabled", enabled)
                .put("anonymousLogsEnabled", false).toString())
            assertEquals(expected, settings)
            val saved = codec.encode(settings)
            assertFalse(org.json.JSONObject(saved).has("dnsProtectionEnabled"))
            assertEquals(settings, codec.decode(saved))
        }
        val directory = Files.createTempDirectory("noki-dns-protection")
        try {
            val vault = WindowsVault(directory)
            vault.saveSettings(vault.loadSettings().copy(advancedSettings = vault.loadSettings().advancedSettings.copy(
                anonymousLogsEnabled = false)))
            val legacy = org.json.JSONObject(checkNotNull(vault.readEncrypted("settings.dpapi")))
                .put("dnsProtectionEnabled", false)
                .put("desktop_dns_rules", org.json.JSONArray().put("invalid-old-rule"))
            vault.writeEncrypted("settings.dpapi", legacy.toString())
            assertFalse(vault.loadSettings().advancedSettings.anonymousLogsEnabled)
            assertEquals(listOf(DesktopRoutingRule.RUSSIAN_RESOURCES), vault.loadRoutingRules())
            val routing = listOf(DesktopRoutingRule("traffic", "Traffic", true, listOf("example.test")).normalized())
            vault.saveRoutingRules(routing)
            assertFalse(org.json.JSONObject(checkNotNull(vault.readEncrypted("settings.dpapi"))).has("dnsProtectionEnabled"))
            assertFalse(org.json.JSONObject(checkNotNull(vault.readEncrypted("settings.dpapi"))).has("desktop_dns_rules"))
            vault.writeEncrypted("settings.dpapi", org.json.JSONObject(checkNotNull(vault.readEncrypted("settings.dpapi")))
                .put("desktop_dns_rules", org.json.JSONArray().put("invalid-old-rule")).toString())
            vault.saveSettings(vault.loadSettings())
            assertFalse(org.json.JSONObject(checkNotNull(vault.readEncrypted("settings.dpapi"))).has("desktop_dns_rules"))
            vault.saveReducedMotion(true)
            vault.saveEarthDisabled(true)
            val restored = WindowsVault(directory)
            assertEquals(routing, restored.loadRoutingRules())
            assertTrue(restored.loadReducedMotion())
            assertTrue(restored.loadEarthDisabled())
            assertFalse(restored.loadSettings().advancedSettings.anonymousLogsEnabled)
            val saved = Files.readAllBytes(directory.resolve("settings.dpapi"))
            assertFalse(saved.toString(Charsets.ISO_8859_1).contains("example.test"))
            assertFalse(org.json.JSONObject(checkNotNull(restored.readEncrypted("settings.dpapi"))).has("dnsProtectionEnabled"))
        } finally { Files.list(directory).use { it.forEach(Files::delete) }; Files.delete(directory) }
    }

    @Test fun `automatic logs default on and saved choices survive without the old consent marker`() {
        val codec = com.noki.vpn.data.StoredSettingsCodec { DesktopState().settings }
        assertTrue(codec.decode(null).advancedSettings.anonymousLogsEnabled)
        assertTrue(codec.decode("{}").advancedSettings.anonymousLogsEnabled)
        assertTrue(com.noki.vpn.data.DefaultStoredSettingsFactory.create(isFreshInstall = false).advancedSettings.anonymousLogsEnabled)
        for (enabled in listOf(false, true)) {
            val settings = codec.decode(org.json.JSONObject().put("anonymousLogsEnabled", enabled).toString())
            assertEquals(enabled, settings.advancedSettings.anonymousLogsEnabled)
            val saved = org.json.JSONObject(codec.encode(settings))
            assertEquals(enabled, codec.decode(saved.toString()).advancedSettings.anonymousLogsEnabled)
        }
    }

    @Test fun `hourly traffic keeps real upload and download deltas across hours and encrypted restart`() {
        val directory = Files.createTempDirectory("noki-hourly-stats")
        try {
            val today = java.time.LocalDate.now()
            val legacy = com.noki.vpn.data.DailyStats(today.toString(), rxBytes = 900, txBytes = 500)
            val vault = WindowsVault(directory)
            vault.saveStats(listOf(legacy))
            val baseline = com.noki.vpn.data.DailyStatsStore.recordTraffic(vault.loadStats(), 100, 50, 1,
                today.atTime(9, 59, 57), includeHourly = false)
            assertTrue(baseline.single().hourlyRxBytes.isEmpty())
            assertTrue(baseline.single().hourlyTxBytes.isEmpty())
            val first = com.noki.vpn.data.DailyStatsStore.recordTraffic(baseline, 20, 5, 2,
                today.atTime(9, 59, 59), includeHourly = true)
            val second = com.noki.vpn.data.DailyStatsStore.recordTraffic(first, 30, 6, 2,
                today.atTime(10, 0, 1), includeHourly = true)
            val day = second.single()
            assertEquals(1050L, day.rxBytes)
            assertEquals(561L, day.txBytes)
            assertEquals(24, day.hourlyRxBytes.size)
            assertEquals(24, day.hourlyTxBytes.size)
            assertEquals(20L, day.hourlyRxBytes[9])
            assertEquals(30L, day.hourlyRxBytes[10])
            assertEquals(5L, day.hourlyTxBytes[9])
            assertEquals(6L, day.hourlyTxBytes[10])
            assertEquals(50L, day.hourlyRxBytes.sum())
            assertEquals(11L, day.hourlyTxBytes.sum())
            val cursor = org.json.JSONObject().put("download", 1050).put("upload", 561)
            vault.saveStats(second, cursor)
            val restored = WindowsVault(directory)
            assertEquals(second, restored.loadStats())
            assertEquals(1050L, restored.loadStatsCursor()?.getLong("download"))
        } finally { Files.list(directory).use { it.forEach(Files::delete) }; Files.delete(directory) }
    }

    @Test fun `midnight discards old hourly buckets without changing daily history`() {
        val today = java.time.LocalDate.now()
        val yesterday = today.minusDays(1)
        val before = com.noki.vpn.data.DailyStatsStore.recordTraffic(emptyList(), 70, 40, 2,
            yesterday.atTime(23, 59, 59), includeHourly = true)
        val after = com.noki.vpn.data.DailyStatsStore.recordTraffic(before, 10, 8, 2,
            today.atTime(0, 0, 1), includeHourly = true)
        val previous = after.first { it.date == yesterday.toString() }
        assertEquals(70L, previous.rxBytes)
        assertEquals(40L, previous.txBytes)
        assertTrue(previous.hourlyRxBytes.isEmpty())
        assertTrue(previous.hourlyTxBytes.isEmpty())
        val current = after.first { it.date == today.toString() }
        assertEquals(10L, current.hourlyRxBytes[0])
        assertEquals(8L, current.hourlyTxBytes[0])
        assertEquals(10L, current.hourlyRxBytes.sum())
        assertEquals(8L, current.hourlyTxBytes.sum())
        assertEquals(after, com.noki.vpn.data.DailyStatsStore.decode(com.noki.vpn.data.DailyStatsStore.encode(after), today))
        val nextDay = com.noki.vpn.data.DailyStatsStore.decode(com.noki.vpn.data.DailyStatsStore.encode(after), today.plusDays(1))
        assertTrue(nextDay.all { it.hourlyRxBytes.isEmpty() && it.hourlyTxBytes.isEmpty() })
        assertEquals(after.sumOf { it.rxBytes }, nextDay.sumOf { it.rxBytes })
    }

    @Test fun `device challenge transaction holds its lock through consume and releases it on cancellation`() = runBlocking {
        assumeTrue(System.getProperty("os.name").startsWith("Windows"))
        val directory = Files.createTempDirectory("noki-device-challenge")
        try {
            val vault = WindowsVault(directory)
            val finishConsume = CompletableDeferred<Unit>()
            val order = mutableListOf<String>()
            val main = launch(start = CoroutineStart.UNDISPATCHED) {
                vault.withChallengeTransaction {
                    order += "main challenge"
                    finishConsume.await()
                    order += "main consume"
                }
            }
            val youtube = async(start = CoroutineStart.UNDISPATCHED) {
                vault.withChallengeTransaction {
                    order += "youtube challenge"
                    order += "youtube consume"
                    "ready"
                }
            }
            assertEquals(listOf("main challenge"), order)
            assertFalse(youtube.isCompleted)
            finishConsume.complete(Unit)
            main.join()
            assertEquals("ready", withTimeout(5_000) { youtube.await() })
            assertEquals(listOf("main challenge", "main consume", "youtube challenge", "youtube consume"), order)

            val cancelled = launch(start = CoroutineStart.UNDISPATCHED) {
                vault.withChallengeTransaction { awaitCancellation() }
            }
            val waiting = async(start = CoroutineStart.UNDISPATCHED) {
                vault.withChallengeTransaction { "lock released" }
            }
            assertFalse(waiting.isCompleted)
            cancelled.cancelAndJoin()
            assertEquals("lock released", withTimeout(5_000) { waiting.await() })
        } finally { Files.list(directory).use { it.forEach(Files::delete) }; Files.delete(directory) }
    }

    @Test fun `YouTube opt in survives settings roundtrip and old settings keep it off`() {
        val defaults = com.noki.vpn.data.DefaultStoredSettingsFactory.create()
        val codec = com.noki.vpn.data.StoredSettingsCodec { defaults }
        assertFalse(codec.decode(null).advancedSettings.youtubeAdBlockEnabled)
        val enabled = defaults.copy(advancedSettings = defaults.advancedSettings.copy(youtubeAdBlockEnabled = true))
        val encoded = codec.encode(enabled)
        assertTrue(codec.decode(encoded).advancedSettings.youtubeAdBlockEnabled)
        val legacy = org.json.JSONObject(encoded).also { it.remove("youtubeAdBlockEnabled") }.toString()
        assertFalse(codec.decode(legacy).advancedSettings.youtubeAdBlockEnabled)
        assertEquals(enabled.profile, codec.decode(legacy).profile)
        assertEquals(enabled.selectedPackages, codec.decode(legacy).selectedPackages)
    }

    @Test fun `Russian resources default is saved once and user removal or edits survive other settings writes`() {
        val directory = Files.createTempDirectory("noki-russian-default")
        try {
            val vault = WindowsVault(directory)
            assertEquals(listOf(DesktopRoutingRule.RUSSIAN_RESOURCES), vault.loadRoutingRules())
            vault.saveRoutingRules(listOf(DesktopRoutingRule.RUSSIAN_RESOURCES.copy(bypass = false)))
            vault.saveSettings(vault.loadSettings())
            assertFalse(WindowsVault(directory).loadRoutingRules().single().bypass)
            vault.saveRoutingRules(emptyList())
            vault.saveEarthDisabled(true)
            vault.saveSettings(vault.loadSettings())
            assertTrue(WindowsVault(directory).loadRoutingRules().isEmpty())
        } finally { Files.list(directory).use { it.forEach(Files::delete) }; Files.delete(directory) }
    }

    @Test fun `existing PC rules get a Russian default without losing a full old rule list`() {
        val directory = Files.createTempDirectory("noki-russian-migration")
        try {
            val vault = WindowsVault(directory)
            val custom = List(200) { DesktopRoutingRule("custom-$it", "Сеть $it", false, ips = listOf("192.0.2.1/32")) }
            vault.saveRoutingRules(custom)
            val old = org.json.JSONObject(requireNotNull(vault.readEncrypted("settings.dpapi")))
            old.remove("desktop_routing_rules_version")
            vault.writeEncrypted("settings.dpapi", old.toString())
            assertEquals(listOf(DesktopRoutingRule.RUSSIAN_RESOURCES) + custom, vault.loadRoutingRules())
            assertEquals(201, WindowsVault(directory).loadRoutingRules().size)
            vault.saveSettings(vault.loadSettings())
            assertEquals(201, vault.loadRoutingRules().size)
        } finally { Files.list(directory).use { it.forEach(Files::delete) }; Files.delete(directory) }
    }

    @Test fun `routing rules reject unsupported RE2 expressions without replacing saved rules`() {
        val directory = Files.createTempDirectory("noki-regexp-test")
        try {
            val vault = WindowsVault(directory)
            val rule = DesktopRoutingRule("regexp", "Сервисы", false, listOf("regexp:(?i)^(?:www\\.)?example\\.com$"))
            vault.saveRoutingRules(listOf(rule))
            val saved = Files.readAllBytes(directory.resolve("settings.dpapi"))
            for (pattern in listOf("(?<=foo)bar", "(foo)\\1", "a++", "a{1001}")) {
                val error = runCatching { vault.saveRoutingRules(listOf(rule.copy(domains = listOf("regexp:$pattern")))) }.exceptionOrNull()
                assertTrue(error is IllegalArgumentException)
                assertTrue(error?.message.orEmpty().contains("RE2"))
                assertArrayEquals(saved, Files.readAllBytes(directory.resolve("settings.dpapi")))
                assertEquals(listOf(rule), vault.loadRoutingRules())
            }
        } finally { Files.list(directory).use { it.forEach(Files::delete) }; Files.delete(directory) }
    }

    @Test fun `application rules persist and legacy app selection migrates once without losing preferences`() {
        for (mode in listOf(com.noki.vpn.data.AppFilterMode.ALL_EXCEPT_SELECTED, com.noki.vpn.data.AppFilterMode.ONLY_SELECTED)) {
            val directory = Files.createTempDirectory("noki-app-rule-migration")
            try {
                val vault = WindowsVault(directory)
                val legacy = vault.loadSettings().copy(filterMode = mode, selectedPackages = setOf("C:\\Apps\\Browser.exe", "C:\\Apps\\Games\\"))
                vault.writeEncrypted("settings.dpapi", org.json.JSONObject(vault.encodeSettings(legacy))
                    .put("desktop_reduced_motion", true).put("desktop_auto_recovery", false).toString())
                assertEquals(com.noki.vpn.data.AppFilterMode.ALL_APPS, vault.loadSettings().filterMode)
                assertTrue(vault.loadSettings().selectedPackages.isEmpty())
                val rules = vault.loadRoutingRules()
                val applications = rules.single { it.applications.isNotEmpty() }
                assertEquals(legacy.selectedPackages, applications.applications.toSet())
                assertEquals(mode == com.noki.vpn.data.AppFilterMode.ALL_EXCEPT_SELECTED, applications.bypass)
                if (mode == com.noki.vpn.data.AppFilterMode.ONLY_SELECTED) assertEquals(listOf("0.0.0.0/0", "::/0"), rules.last().ips)
                assertEquals(rules, WindowsVault(directory).loadRoutingRules())
                vault.saveSettings(vault.loadSettings())
                assertEquals(rules, WindowsVault(directory).loadRoutingRules())
                assertTrue(vault.loadReducedMotion())
                assertFalse(vault.loadAutoRecovery())
                val before = vault.readEncrypted("settings.dpapi")
                assertTrue(runCatching { vault.saveRoutingRules(listOf(applications.copy(applications = listOf("relative.exe")))) }.isFailure)
                assertEquals(before, vault.readEncrypted("settings.dpapi"))
            } finally { Files.list(directory).use { it.forEach(Files::delete) }; Files.delete(directory) }
        }
    }

    @Test fun `named routing migration preserves encrypted rules across settings changes`() {
        val directory = Files.createTempDirectory("noki-rules-test")
        try {
            val vault = WindowsVault(directory)
            val settings = vault.loadSettings().copy(advancedSettings = com.noki.vpn.data.AdvancedSettings(
                alwaysOnDomains = listOf("example.com"), bypassDomains = listOf("full:local.example")))
            vault.writeEncrypted("settings.dpapi", org.json.JSONObject(com.noki.vpn.data.StoredSettingsCodec { settings }.encode(settings))
                .put("desktop_transparent_window", true).put("desktop_reduced_motion", true).toString())
            assertTrue(vault.loadSettings().advancedSettings.alwaysOnDomains.isEmpty())
            val migrated = vault.loadRoutingRules()
            assertEquals(3, migrated.size)
            assertEquals(DesktopRoutingRule.RUSSIAN_RESOURCES, migrated.first())
            assertEquals(listOf("domain:example.com"), migrated[1].domains)
            assertTrue(migrated.last().bypass)
            vault.saveRoutingRules(migrated + DesktopRoutingRule("ip-rule", "Сеть", false, ips = listOf("192.0.2.1")))
            vault.saveSettings(vault.loadSettings().copy(filterMode = com.noki.vpn.data.AppFilterMode.ONLY_SELECTED))
            val restored = WindowsVault(directory)
            assertEquals(4, restored.loadRoutingRules().size)
            assertEquals(listOf("192.0.2.1/32"), restored.loadRoutingRules().last().ips)
            assertFalse("Abandoned window transparency must not survive settings writes",
                org.json.JSONObject(requireNotNull(restored.readEncrypted("settings.dpapi"))).has("desktop_transparent_window"))
            assertTrue(restored.loadReducedMotion())
            restored.saveEarthDisabled(true)
            restored.saveReducedMotion(false)
            restored.saveSettings(restored.loadSettings())
            assertTrue(WindowsVault(directory).loadEarthDisabled())
            assertFalse(WindowsVault(directory).loadReducedMotion())
            assertEquals(4, WindowsVault(directory).loadRoutingRules().size)
            assertFalse(Files.readString(directory.resolve("settings.dpapi"), Charsets.ISO_8859_1).contains("example.com"))
        } finally {
            Files.list(directory).use { it.forEach(Files::delete) }
            Files.delete(directory)
        }
    }

    @Test fun `identity persists encrypted and signs backend nonce`() {
        assumeTrue(System.getProperty("os.name").startsWith("Windows"))
        val directory = Files.createTempDirectory("noki-vault-test")
        try {
            val first = WindowsVault(directory)
            first.saveTokens(BackendAuthTokens("private-access", "private-refresh"))
            first.deviceId = "device-id"
            val privateFile = first.writePrivate("vpn.json", "private-vpn-secret")
            assertEquals("D:P(A;;FA;;;${WindowsNetworkGuard.currentUserSid()})", dacl(privateFile))
            val pending = first.refreshRequestId("private-refresh")
            val restored = WindowsVault(directory)
            assertEquals(pending, restored.refreshRequestId("private-refresh"))
            assertEquals(first.deviceKey, restored.deviceKey)
            assertEquals("device-id", restored.deviceId)
            assertEquals("private-access", restored.loadTokens()!!.accessToken)
            assertFalse(Files.readString(directory.resolve("session.dpapi"), Charsets.ISO_8859_1).contains("private-access"))
            val publicKey = KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(restored.publicKey)))
            val verifier = Signature.getInstance("SHA256withRSA").apply { initVerify(publicKey); update("nonce".toByteArray()) }
            assertTrue(verifier.verify(Base64.getDecoder().decode(restored.signChallenge("nonce"))))
            restored.saveTokens(null)
            assertNotEquals(pending, WindowsVault(directory).refreshRequestId("private-refresh"))
            assertNull(WindowsVault(directory).loadTokens())
            assertEquals(first.deviceKey, WindowsVault(directory).deviceKey)
        } finally {
            Files.list(directory).use { it.forEach(Files::delete) }
            Files.delete(directory)
        }
    }
}
