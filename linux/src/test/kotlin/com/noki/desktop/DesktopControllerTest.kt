package com.noki.desktop

import androidx.compose.ui.semantics.getOrNull

import androidx.compose.runtime.collectAsState
import com.noki.vpn.AuthSessionAttempt
import com.noki.vpn.data.*
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class DesktopControllerTest {
    private val notificationsPath = if (DesktopBuild.IS_LINUX) "/v1/app/notifications" else "/v1/app/desktop-notifications"
    @Test fun `corrupt local caches do not block startup or change the selected route`() = runBlocking {
        org.junit.Assume.assumeTrue(System.getProperty("os.name").startsWith("Windows"))
        val sandbox = Files.createTempDirectory("noki-startup-caches").toAbsolutePath().normalize()
        var app: DesktopController? = null
        try {
            val vault = WindowsVault(sandbox) { "smbios:startup-caches-fixture" }
            val settings = vault.loadSettings().let { it.copy(advancedSettings = it.advancedSettings.copy(
                protocol = VpnProtocol.REALITY, killSwitchEnabled = true, autoConnectOnLaunch = false)) }
            vault.saveSettings(settings)
            val rules = listOf(DesktopRoutingRule("fixture", "Work", false, domains = listOf("example.org")).normalized())
            vault.saveRoutingRules(rules)
            val originalSettings = Files.readAllBytes(sandbox.resolve("settings.dpapi"))
            val originalSession = Files.readAllBytes(sandbox.resolve("session.dpapi"))
            for (name in listOf("stats.dpapi", "account-local.dpapi", "endpoint-health.dpapi", "window.dpapi")) {
                vault.writeEncrypted(name, "{")
            }
            Files.write(sandbox.resolve("runtime.dpapi"), ByteArray(64) { 42 })
            app = DesktopController(vault, SingBoxRuntime(vault, canRecoverRuntime = { true }), networkSnapshot = {
                WindowsNetworkSnapshot("fixture-offline", EndpointRankingPolicy.NetworkKind.OTHER, false, null)
            })
            val state = withTimeout(5_000) { app.state.first { !it.loading } }
            assertEquals(settings, state.settings)
            assertEquals(VpnProtocol.REALITY, state.settings.advancedSettings.protocol)
            assertEquals(rules, state.routingRules.filter { it.id == "fixture" })
            assertTrue(state.dailyStats.isEmpty())
            assertFalse(state.running)
            assertNull(app.loadWindowBounds())
            app.saveWindowBounds(JSONObject().put("width", 960))
            assertEquals(960, app.loadWindowBounds()!!.getInt("width"))
            val backups = Files.newDirectoryStream(sandbox, "*.unreadable-*.bak").use { it.toList() }
            assertEquals(5, backups.size)
            assertArrayEquals(originalSession, Files.readAllBytes(sandbox.resolve("session.dpapi")))
            assertArrayEquals(originalSettings, Files.readAllBytes(sandbox.resolve("settings.dpapi")))
        } finally {
            app?.closeAndJoin()
            Files.walk(sandbox).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach {
                check(it.toAbsolutePath().normalize().startsWith(sandbox)); Files.delete(it)
            } }
        }
    }

    @get:org.junit.Rule val mainDispatcher: org.junit.rules.TestRule = DesktopMainDispatcherRule()

    @Test fun `parallel authorized calls run together and share one refresh after simultaneous 401 responses`() = runBlocking {
        val directory = Files.createTempDirectory("noki-parallel-authorization")
        val vault = WindowsVault(directory)
        val refreshes = java.util.concurrent.atomic.AtomicInteger()
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            assertEquals("/v1/auth/refresh", chain.request().url.encodedPath)
            refreshes.incrementAndGet()
            val sent = JSONObject(okio.Buffer().also { chain.request().body!!.writeTo(it) }.readUtf8())
            assertEquals("old-refresh", sent.getString("refresh_token"))
            okhttp3.Response.Builder().request(chain.request()).protocol(okhttp3.Protocol.HTTP_1_1).code(200).message("OK")
                .body("{\"access_token\":\"fresh-access\",\"refresh_token\":\"fresh-refresh\"}".toResponseBody("application/json".toMediaType())).build()
        }.build()
        val app = DesktopController(vault, stoppedTestRuntime(vault), BackendApiClient(http, "https://example.invalid"))
        val bothStarted = kotlinx.coroutines.CompletableDeferred<Unit>()
        val responses = kotlinx.coroutines.CompletableDeferred<Unit>()
        val started = java.util.concurrent.atomic.AtomicInteger()
        val usedTokens = java.util.concurrent.CopyOnWriteArrayList<String>()
        try {
            withTimeout(5_000) { app.state.first { !it.loading } }
            DesktopController::class.java.getDeclaredField("tokens").apply { isAccessible = true }
                .set(app, BackendAuthTokens("old-access", "old-refresh"))
            val method = DesktopController::class.java.declaredMethods.single { it.name == "authorized" }.apply { isAccessible = true }
            suspend fun request(): String {
                val block: suspend (String) -> String = { token ->
                    usedTokens += token
                    if (token == "old-access") {
                        if (started.incrementAndGet() == 2) bothStarted.complete(Unit)
                        responses.await()
                        throw BackendException("expired", 401)
                    }
                    token
                }
                return kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn { method.invoke(app, block, it) }
            }
            val first = async { request() }
            val second = async { request() }
            withTimeout(2_000) { bothStarted.await() }
            responses.complete(Unit)
            assertEquals("fresh-access", first.await())
            assertEquals("fresh-access", second.await())
            assertEquals(1, refreshes.get())
            assertEquals(2, usedTokens.count { it == "old-access" })
            assertEquals(2, usedTokens.count { it == "fresh-access" })
            assertEquals(BackendAuthTokens("fresh-access", "fresh-refresh"), vault.loadTokens())
        } finally {
            responses.complete(Unit)
            app.closeAndJoin(); app.close()
            http.connectionPool.evictAll(); http.dispatcher.executorService.shutdownNow()
            Files.list(directory).use { it.forEach(Files::delete) }; Files.delete(directory)
        }
    }

    @Test fun `bootstrap starts unread notifications without waiting for their network response`() = runBlocking {
        val directory = Files.createTempDirectory("noki-bootstrap-notifications")
        val vault = WindowsVault(directory)
        vault.saveTokens(BackendAuthTokens("saved-access", null)); vault.deviceId = "pc-device"
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val acknowledgements = java.util.concurrent.atomic.AtomicInteger()
        val readStatus = java.util.concurrent.atomic.AtomicInteger(200)
        val reads = java.util.concurrent.atomic.AtomicInteger()
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            var status = 200
            val body = when (chain.request().url.encodedPath) {
                "/v1/app/bootstrap" -> JSONObject().put("user", JSONObject().put("id", "user").put("username", "User").put("email", "user@example.invalid"))
                    .put("devices", JSONArray().put(JSONObject().put("id", "pc-device").put("device_key", vault.deviceKey)
                        .put("device_name", "PC").put("platform", DesktopBuild.PLATFORM).put("is_active", true)))
                notificationsPath -> {
                    assertEquals(vault.deviceId, chain.request().header("X-Device-Id"))
                    assertEquals(vault.deviceKey, chain.request().header("X-Device-Key"))
                    entered.countDown(); assertTrue(release.await(5, TimeUnit.SECONDS))
                    reads.incrementAndGet()
                    status = readStatus.get()
                    JSONObject().put("notifications", JSONArray().put(JSONObject().put("id", "unread")
                        .put("title", "Notice").put("message", "New message").put("created_at", "2026-10-15T08:00:00Z")))
                }
                "$notificationsPath/ack" -> {
                    assertEquals(vault.deviceId, chain.request().header("X-Device-Id"))
                    assertEquals(vault.deviceKey, chain.request().header("X-Device-Key"))
                    val saved = AppNotificationHistoryStore.decodeState(WindowsVault(directory).loadAccountLocal().getString("notifications_user"))
                    assertEquals(listOf("unread"), saved.notifications.map { it.id })
                    val sent = JSONObject(okio.Buffer().also { chain.request().body!!.writeTo(it) }.readUtf8())
                    assertEquals("unread", sent.getJSONArray("notification_ids").getString(0))
                    if (acknowledgements.incrementAndGet() == 1) status = 503
                    JSONObject()
                }
                else -> throw AssertionError("Unexpected path: ${chain.request().url.encodedPath}")
            }
            okhttp3.Response.Builder().request(chain.request()).protocol(okhttp3.Protocol.HTTP_1_1).code(status).message("OK")
                .body(body.toString().toResponseBody("application/json".toMediaType())).build()
        }.build()
        val app = DesktopController(vault, stoppedTestRuntime(vault), BackendApiClient(http, "https://example.invalid"))
        try {
            withTimeout(3_000) { app.state.first { !it.loading && it.user != null } }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            assertFalse(app.state.value.hasUnreadNotifications)
            release.countDown()
            val state = withTimeout(3_000) { app.state.first { it.hasUnreadNotifications } }
            assertEquals(listOf("unread"), state.notifications.map { it.id })
            assertFalse(state.loading)
            app.loadNotifications(markRead = true).join()
            assertEquals(if (DesktopBuild.IS_LINUX) 0 else 2, acknowledgements.get())
            assertEquals(1, app.state.value.notifications.size)
            assertFalse(app.state.value.hasUnreadNotifications)
            readStatus.set(503)
            app.loadNotifications(silent = true).join()
            assertEquals(if (DesktopBuild.IS_LINUX) 0 else 2, acknowledgements.get())
            assertEquals(listOf("unread"), app.state.value.notifications.map { it.id })
            app.clearMessage()
            readStatus.set(404)
            app.loadNotifications(markRead = true).join()
            assertNull(app.state.value.message)
            val before = reads.get()
            app.loadNotifications(silent = true).join()
            app.loadNotifications().join()
            assertEquals("An unavailable endpoint must stop automatic polling", before, reads.get())
            assertEquals(listOf("unread"), app.state.value.notifications.map { it.id })
            readStatus.set(200)
            app.loadNotifications(markRead = true).join()
            assertEquals("Opening notifications retries after backend deployment", before + 1, reads.get())
            assertNull(app.state.value.message)
        } finally {
            release.countDown(); app.closeAndJoin(); app.close()
            http.connectionPool.evictAll(); http.dispatcher.executorService.shutdownNow()
            Files.list(directory).use { it.forEach(Files::delete) }; Files.delete(directory)
        }
    }

    @Test fun `restored owner migrates hardware key on the same device before account sync`() = runBlocking<Unit> {
        val directory = Files.createTempDirectory("noki-hardware-owner")
        val legacy = WindowsVault(directory) { null }
        legacy.deviceId = "existing-pc"
        legacy.saveTokens(BackendAuthTokens("access", null))
        val vault = WindowsVault(directory) { "smbios:fixture" }
        var registered = false
        val calls = java.util.concurrent.CopyOnWriteArrayList<String>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request(); calls.add(request.url.encodedPath)
            fun device(key: String) = JSONObject().put("id", "existing-pc").put("device_key", key)
                .put("access_role", "owner").put("platform", DesktopBuild.PLATFORM).put("device_name", "PC")
            val body = when (request.url.encodedPath) {
                "/v1/app/bootstrap" -> {
                    assertEquals(if (registered) vault.registrationDeviceKey else legacy.deviceKey, request.header("X-Device-Key"))
                    JSONObject().put("user", JSONObject().put("id", "fixture").put("username", "Fixture").put("email", "fixture@example.invalid"))
                        .put("devices", JSONArray().put(device(if (registered) vault.registrationDeviceKey else legacy.deviceKey)))
                }
                "/v1/devices/register" -> {
                    val sent = JSONObject(okio.Buffer().also { request.body!!.writeTo(it) }.readUtf8())
                    assertEquals("existing-pc", sent.getString("device_id"))
                    assertEquals(vault.registrationDeviceKey, sent.getString("device_key"))
                    assertEquals(legacy.publicKey, sent.getString("public_key"))
                    registered = true
                    device(vault.registrationDeviceKey)
                }
                notificationsPath -> JSONObject().put("notifications", JSONArray())
                else -> throw AssertionError("Unexpected request: ${request.url.encodedPath}")
            }
            okhttp3.Response.Builder().request(request).protocol(okhttp3.Protocol.HTTP_1_1).code(200).message("OK")
                .body(body.toString().toResponseBody("application/json".toMediaType())).build()
        }.build()
        val app = DesktopController(vault, stoppedTestRuntime(vault), BackendApiClient(client, "https://example.invalid"), {})
        try {
            withTimeout(5_000) { app.state.first { !it.loading && it.user != null } }
            assertTrue(registered)
            assertFalse(vault.needsHardwareRegistration)
            assertEquals("existing-pc", vault.deviceId)
            assertEquals(1, calls.count { it == "/v1/devices/register" })
        } finally {
            app.closeAndJoin(); app.close()
            Files.list(directory).use { it.forEach(Files::delete) }; Files.delete(directory)
        }
    }

    @Test fun `code login preserves the invited device identity and does not register an owner device`() = runBlocking<Unit> {
        val directory = Files.createTempDirectory("noki-code-login")
        val vault = WindowsVault(directory)
        val calls = java.util.concurrent.CopyOnWriteArrayList<String>()
        val device = JSONObject().put("id", "pc-invited").put("device_key", vault.deviceKey)
            .put("access_role", "invited").put("platform", DesktopBuild.PLATFORM).put("device_name", "Invited PC")
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request(); calls.add(request.url.encodedPath)
            val body = when (request.url.encodedPath) {
                "/v1/devices/invites/accept" -> {
                    val sent = JSONObject(okio.Buffer().also { request.body!!.writeTo(it) }.readUtf8())
                    assertEquals("ABCDE-12345", sent.getString("invite_code"))
                    assertEquals(DesktopBuild.PLATFORM, sent.getString("platform"))
                    assertEquals(vault.deviceKey, sent.getString("device_key"))
                    assertEquals(vault.publicKey, sent.getString("public_key"))
                    JSONObject().put("access_token", "invited-access").put("refresh_token", "invite-refresh").put("device", device)
                }
                "/v1/auth/refresh" -> {
                    val sent = JSONObject(okio.Buffer().also { request.body!!.writeTo(it) }.readUtf8())
                    assertEquals("pc-invited", sent.getString("device_id"))
                    assertEquals("invite-refresh", sent.getString("refresh_token"))
                    JSONObject().put("access_token", "invited-access").put("refresh_token", "invite-bound-refresh")
                }
                "/v1/app/bootstrap" -> JSONObject().put("user", JSONObject().put("id", "fixture")
                    .put("username", "Fixture").put("email", "fixture@example.invalid"))
                    .put("devices", JSONArray().put(device))
                notificationsPath -> JSONObject().put("notifications", JSONArray())
                else -> throw AssertionError("Unexpected request: ${request.url.encodedPath}")
            }
            okhttp3.Response.Builder().request(request).protocol(okhttp3.Protocol.HTTP_1_1).code(200).message("OK")
                .body(body.toString().toResponseBody("application/json".toMediaType())).build()
        }.build()
        val app = DesktopController(vault, stoppedTestRuntime(vault), BackendApiClient(client, "https://example.invalid"), {})
        try {
            withTimeout(5_000) { app.state.first { !it.loading } }
            app.loginFromCode("noki://invite/abcde-12345")
            try { withTimeout(5_000) { app.state.first { !it.loading && it.user != null } } }
            catch (error: Exception) { throw AssertionError("Code login requests=$calls, state=${app.state.value}", error) }
            assertEquals(listOf("/v1/devices/invites/accept", "/v1/auth/refresh", "/v1/app/bootstrap"), calls.filterNot { it == notificationsPath })
            assertTrue(app.state.value.invited)
            assertEquals("pc-invited", vault.deviceId)
            assertEquals("invited-access", vault.loadTokens()?.accessToken)
            assertEquals("invite-bound-refresh", vault.loadTokens()?.refreshToken)
        } finally {
            app.closeAndJoin(); app.close()
            Files.list(directory).use { it.forEach(Files::delete) }; Files.delete(directory)
        }
    }

    @Test fun `top messages expire after two seconds and a repeated message gets its own lifetime`() = runBlocking<Unit> {
        val vault = WindowsVault(Files.createTempDirectory("noki-message-lifetime"))
        val app = DesktopController(vault, stoppedTestRuntime(vault), BackendApiClient(baseUrl = "http://127.0.0.1:9"))
        try {
            withTimeout(5_000) { app.state.first { !it.loading } }
            val started = System.nanoTime()
            app.reportMessage("First message")
            withTimeout(2_800) { app.state.first { it.message == null } }
            assertTrue("Messages must not disappear immediately", System.nanoTime() - started >= 1_800_000_000L)
            app.reportMessage("Repeated message")
            kotlinx.coroutines.delay(1_000)
            app.reportMessage("Repeated message")
            kotlinx.coroutines.delay(1_100)
            assertEquals("An old timer must not dismiss the repeated message", "Repeated message", app.state.value.message)
            withTimeout(1_700) { app.state.first { it.message == null } }
        } finally { app.closeAndJoin(); app.close() }
    }

    @Test fun `tray handoff cancels controller jobs without removing the core journal`() = runBlocking {
        val directory = Files.createTempDirectory("noki-tray-handoff")
        val vault = WindowsVault(directory)
        val app = DesktopController(vault, stoppedTestRuntime(vault), BackendApiClient(baseUrl = "http://127.0.0.1:9"))
        try {
            withTimeout(5_000) { app.state.first { !it.loading } }
            app.setEarthDisabled(true)
            withTimeout(5_000) { app.state.first { it.earthDisabled && !it.loading } }
            vault.writeEncrypted("runtime.dpapi", "{\"handoff_fixture\":true}")
            val before = Files.readAllBytes(directory.resolve("runtime.dpapi"))
            withTimeout(5_000) { app.closeAndJoin() }
            assertArrayEquals(before, Files.readAllBytes(directory.resolve("runtime.dpapi")))
            assertTrue(WindowsVault(directory).loadEarthDisabled())
        } finally { app.close() }
    }

    @Test fun `language changes keep navigation available without a bootstrap request`() = runBlocking {
        val directory = Files.createTempDirectory("noki-language-local")
        val vault = WindowsVault(directory)
        val calls = java.util.concurrent.atomic.AtomicInteger()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            calls.incrementAndGet()
            okhttp3.Response.Builder().request(chain.request()).protocol(okhttp3.Protocol.HTTP_1_1)
                .code(503).message("offline").body("{\"detail\":\"offline\"}".toResponseBody("application/json".toMediaType())).build()
        }.build()
        val app = DesktopController(vault, stoppedTestRuntime(vault), BackendApiClient(client, "https://example.invalid"))
        try {
            withTimeout(5_000) { app.state.first { !it.loading } }
            DesktopController::class.java.getDeclaredField("tokens").also { it.isAccessible = true }
                .set(app, BackendAuthTokens("test-access", null))
            @Suppress("UNCHECKED_CAST")
            val mutable = DesktopController::class.java.getDeclaredField("mutable").also { it.isAccessible = true }
                .get(app) as kotlinx.coroutines.flow.MutableStateFlow<DesktopState>
            mutable.value = mutable.value.copy(running = true)
            app.updateSettings { it.copy(personalizationSettings = it.personalizationSettings.copy(language = AppLanguage.EN)) }
            withTimeout(5_000) { app.state.first { it.language == AppLanguage.EN && !it.loading } }
            assertEquals("A local language change must not wait for a network bootstrap", 0, calls.get())
            assertNull("A live language change must not announce a reconnect", app.state.value.message)
            assertEquals(AppLanguage.EN, vault.loadSettings().personalizationSettings.language)
        } finally {
            app.close(); Files.list(directory).use { it.forEach(Files::delete) }; Files.delete(directory)
        }
    }

    @Test fun `queued account operation cannot use a replacement session`() = runBlocking {
        val directory = Files.createTempDirectory("noki-account-race")
        val vault = WindowsVault(directory)
        val app = DesktopController(vault, stoppedTestRuntime(vault), BackendApiClient(OkHttpClient(), "https://example.invalid"))
        val mutex = DesktopController::class.java.getDeclaredField("authOperation").also { it.isAccessible = true }
            .get(app) as kotlinx.coroutines.sync.Mutex
        val tokenField = DesktopController::class.java.getDeclaredField("tokens").also { it.isAccessible = true }
        val epoch = DesktopController::class.java.getDeclaredField("authEpoch").also { it.isAccessible = true }
            .get(app) as java.util.concurrent.atomic.AtomicLong
        val method = DesktopController::class.java.declaredMethods.single { it.name == "forAttempt" }.also { it.isAccessible = true }
        var usedToken: String? = null
        try {
            withTimeout(5_000) { app.state.first { !it.loading } }
            tokenField.set(app, BackendAuthTokens("first-access", "first-refresh"))
            val owner = AuthSessionAttempt("first-access", epoch.get())
            mutex.lock()
            val replacement = async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                mutex.lock()
                try { epoch.incrementAndGet(); tokenField.set(app, BackendAuthTokens("second-access", "second-refresh")) }
                finally { mutex.unlock() }
            }
            val pending = async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                val request: suspend (String) -> Unit = { usedToken = it }
                kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn<Unit> { continuation ->
                    method.invoke(app, owner, request, continuation)
                }
            }
            mutex.unlock()
            replacement.await()
            assertTrue(runCatching { pending.await() }.exceptionOrNull() is kotlinx.coroutines.CancellationException)
            assertNull("An outdated action must be rejected before calling the API with the new token", usedToken)
        } finally {
            if (mutex.isLocked) mutex.unlock()
            app.close(); Files.list(directory).use { it.forEach(Files::delete) }; Files.delete(directory)
        }
    }

    @Test fun `multihop countries use weighted ranking without replacing saved choices`() {
        fun node(number: Int, weight: Int = 1) = VpnServer("00000000-0000-4000-8000-${number.toString().padStart(12, '0')}",
            "Node $number", "PL", "pl", "example.invalid", null, true, weight = weight,
            metricsAt = java.time.Instant.now().toString(), multiHopEntryAvailable = true, multiHopExitAvailable = true)
        val light = node(1); val heavy = node(2, 9)
        val country = HopSelection(ServerSelectionMode.COUNTRY, "PL")
        val saved = MultiHopSettings(true, country, country)
        val catalog = listOf(light, heavy, node(3, 0), node(4).copy(isOnline = false),
            node(5).copy(multiHopEntryAvailable = false, multiHopExitAvailable = false))
        val resolved = DesktopController.resolveMultiHop(saved, catalog, false) { .5 }
        assertEquals(heavy.id to light.id, resolved.explicitNodeIds())
        assertEquals(country, saved.entry); assertEquals(country, saved.exit)
        val explicitExit = saved.copy(exit = HopSelection(ServerSelectionMode.SERVER, nodeId = heavy.id))
        assertEquals(light.id to heavy.id, DesktopController.resolveMultiHop(explicitExit, catalog, false) { .5 }.explicitNodeIds())
        assertTrue(runCatching { DesktopController.resolveMultiHop(saved, listOf(light), false) }.isFailure)
        assertEquals(saved.copy(enabled = false), DesktopController.resolveMultiHop(saved.copy(enabled = false), emptyList(), false))
        val directory = Files.createTempDirectory("noki-country-hops")
        try {
            val vault = WindowsVault(directory)
            val settings = vault.loadSettings()
            vault.saveSettings(settings.copy(advancedSettings = settings.advancedSettings.copy(multiHop = saved)))
            vault.saveReducedMotion(true)
            vault.saveRoutingRules(emptyList())
            assertEquals(saved, vault.loadSettings().advancedSettings.multiHop)
        } finally { Files.list(directory).use { it.forEach(Files::delete) }; Files.delete(directory) }
    }

    @Test fun `account links open the requested website section without an API call`() = runBlocking {
        val directory = Files.createTempDirectory("noki-account-links-test")
        val vault = WindowsVault(directory)
        val opened = java.util.concurrent.CopyOnWriteArrayList<String>()
        val http = OkHttpClient.Builder().addInterceptor { throw AssertionError("Website links must not call the API") }.build()
        val app = DesktopController(vault, stoppedTestRuntime(vault), BackendApiClient(http, "https://example.invalid"), { opened.add(it) })
        try {
            withTimeout(5_000) { app.state.first { !it.loading } }
            for (section in listOf("plans", "payments", "username", "password", "email")) {
                app.openAccountWebsite(section)
                val expected = if (section == "plans") "https://noki.ykino.tech/plans" else "https://noki.ykino.tech/account#$section"
                withTimeout(5_000) { while (expected !in opened) kotlinx.coroutines.delay(10) }
            }
            app.openAccountWebsite("https://example.invalid")
            assertEquals(5, opened.size)
        } finally {
            app.close(); Files.list(directory).use { it.forEach(Files::delete) }; Files.delete(directory)
        }
    }

    @Test fun `website login proof stays in client and cancellation allows another attempt`() = runBlocking {
        val directory = Files.createTempDirectory("noki-site-login-test")
        val vault = WindowsVault(directory)
        val started = java.util.concurrent.atomic.AtomicInteger()
        val polled = java.util.concurrent.atomic.AtomicInteger()
        val challenge = AtomicReference<String>()
        val browser = AtomicReference<String>()
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val body = okio.Buffer().also { request.body!!.writeTo(it) }.readUtf8()
            val response = when (request.url.encodedPath) {
                "/v1/web/auth/desktop/start" -> {
                    started.incrementAndGet(); challenge.set(JSONObject(body).getString("code_challenge"))
                    JSONObject().put("request_id", "q".repeat(43))
                }
                "/v1/web/auth/desktop/token" -> {
                    val verifier = JSONObject(body).getString("code_verifier")
                    val hash = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
                        java.security.MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))
                    assertEquals(challenge.get(), hash)
                    assertFalse(browser.get().contains(verifier))
                    polled.incrementAndGet(); JSONObject().put("status", "pending")
                }
                else -> throw AssertionError("Unexpected path: ${request.url.encodedPath}")
            }
            okhttp3.Response.Builder().request(request).protocol(okhttp3.Protocol.HTTP_1_1).code(200).message("OK")
                .body(response.toString().toResponseBody("application/json".toMediaType())).build()
        }.build()
        val app = DesktopController(vault, stoppedTestRuntime(vault), BackendApiClient(http, "https://example.invalid"), browser::set)
        try {
            withTimeout(5_000) { app.state.first { !it.loading } }
            app.loginFromWebsite()
            withTimeout(5_000) { while (polled.get() == 0) kotlinx.coroutines.delay(10) }
            assertTrue(app.state.value.websiteLoginPending)
            assertEquals("https://noki.ykino.tech/account?desktop=1#" + "q".repeat(43), browser.get())
            app.loginFromWebsite()
            assertEquals(1, started.get())
            app.cancelWebsiteLogin()
            withTimeout(5_000) { app.state.first { !it.loading && !it.websiteLoginPending } }
            assertNull(vault.loadTokens())
            app.loginFromWebsite()
            withTimeout(5_000) { while (started.get() < 2) kotlinx.coroutines.delay(10) }
            app.cancelWebsiteLogin()
            withTimeout(5_000) { app.state.first { !it.loading && !it.websiteLoginPending } }
            assertNull(vault.loadTokens())
        } finally {
            app.close(); Files.list(directory).use { it.forEach(Files::delete) }; Files.delete(directory)
        }
    }

    @Test fun `restored expired token refreshes before bootstrap without a failed request`() = runBlocking {
        val directory = Files.createTempDirectory("noki-session-startup-test")
        val vault = WindowsVault(directory)
        vault.saveSettings(vault.loadSettings().let { it.copy(securitySettings = it.securitySettings.copy(biometricEnabled = false)) })
        fun jwt(expiry: Long) = "header." + java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString(JSONObject().put("exp", expiry).toString().toByteArray()) + ".signature"
        val expired = jwt(1)
        assertTrue(DesktopController.accessExpired(expired))
        assertFalse(DesktopController.accessExpired("invalid"))
        assertFalse(DesktopController.accessExpired(jwt(100), nowMillis = 1_000))
        vault.saveTokens(BackendAuthTokens(expired, "saved-refresh"))
        vault.deviceId = "pc-device"
        val calls = java.util.concurrent.CopyOnWriteArrayList<String>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/") { exchange ->
            val body = exchange.requestBody.use { it.readAllBytes().toString(Charsets.UTF_8) }
            val path = exchange.requestURI.path
            calls.add(path)
            val response = when (path) {
                "/v1/auth/refresh" -> {
                    assertEquals("saved-refresh", JSONObject(body).getString("refresh_token"))
                    JSONObject().put("access_token", "fresh").put("refresh_token", "new-refresh")
                }
                "/v1/app/bootstrap" -> {
                    assertEquals("Bearer fresh", exchange.requestHeaders.getFirst("Authorization"))
                    JSONObject().put("user", JSONObject().put("id", "user").put("username", "Test").put("email", "test@example.invalid"))
                        .put("devices", JSONArray())
                }
                notificationsPath -> JSONObject().put("notifications", JSONArray())
                else -> throw AssertionError("Unexpected path: $path")
            }.toString().toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, response.size.toLong())
            exchange.responseBody.use { it.write(response) }
        }
        val http = OkHttpClient.Builder().addInterceptor { chain -> chain.proceed(chain.request().newBuilder()
            .url(chain.request().url.newBuilder().scheme("http").build()).build()) }.build()
        var app: DesktopController? = null
        try {
            server.start()
            val restored = DesktopController(vault, stoppedTestRuntime(vault), BackendApiClient(http, "https://127.0.0.1:${server.address.port}"), {})
            app = restored
            try { withTimeout(5_000) { restored.state.first { !it.loading && it.user != null } } }
            catch (error: Exception) { throw AssertionError("Requests=$calls, message=${restored.state.value.message}", error) }
            assertEquals(listOf("/v1/auth/refresh", "/v1/app/bootstrap"), calls.filterNot { it == notificationsPath })
            assertEquals("new-refresh", vault.loadTokens()!!.refreshToken)
        } finally {
            app?.close(); server.stop(0)
            Files.list(directory).use { it.forEach(Files::delete) }; Files.delete(directory)
        }
    }

    @Test fun `RU selection keeps explicit route nodes and never substitutes a missing exit`() {
        val entry = "10000000-0000-4000-8000-000000000001"
        val exit = "10000000-0000-4000-8000-000000000002"
        val other = "10000000-0000-4000-8000-000000000003"
        val nodes = listOf(RuForeignNode(other, "Other", "US"), RuForeignNode(exit, "Exit", "DE"), RuForeignNode(entry, "Entry", "FI"))
        val settings = DesktopState().settings.copy(advancedSettings = AdvancedSettings(ruRelayEnabled = true),
            userProfile = UserProfile(serverSelectionMode = ServerSelectionMode.SERVER, selectedNodeId = other))
        assertEquals(listOf(other), DesktopController.ruExits(settings, nodes).map { it.id })
        assertTrue(DesktopController.ruExits(settings.copy(userProfile = settings.userProfile.copy(selectedNodeId = "missing")), nodes).isEmpty())
        val hops = MultiHopSettings(true, HopSelection(ServerSelectionMode.SERVER, nodeId = entry), HopSelection(ServerSelectionMode.SERVER, nodeId = exit))
        val multi = settings.copy(advancedSettings = settings.advancedSettings.copy(multiHop = hops))
        assertEquals(listOf(exit), DesktopController.ruExits(multi, nodes).map { it.id })
        assertTrue(DesktopController.ruExits(multi, nodes.filterNot { it.id == exit }).isEmpty())
        assertTrue(runCatching { DesktopController.ruExits(multi.copy(advancedSettings = multi.advancedSettings.copy(
            multiHop = hops.copy(entry = HopSelection(ServerSelectionMode.COUNTRY, countryCode = "FI")))), nodes) }.isFailure)
        assertEquals(nodes.map { it.id }.toSet(), DesktopState(settings = multi, ruForeignNodes = nodes).connectionLocations.flatMap { it.servers }.map { it.id }.toSet())
    }

    @Test fun `RU journal restores the complete persistent authorization and rejects legacy identity`() {
        val identity = BackendRuWireGuardIdentity(
            requestId = "10000000-0000-4000-8000-000000000001", sessionId = "10000000-0000-4000-8000-000000000002",
            generation = 3, publicKeyId = "10000000-0000-4000-8000-000000000003", relaySelection = RuRelaySelection.Auto,
            relayNodeId = "10000000-0000-4000-8000-000000000004", exitNodeId = "10000000-0000-4000-8000-000000000005",
            routeVersion = 1, routeId = "10000000-0000-4000-8000-000000000006", routeGeneration = 7,
            entryNodeId = "10000000-0000-4000-8000-000000000007", leaseExpiresAtEpochMillis = null, revokeToken = "r".repeat(32),
        )
        val journal = JSONObject(DesktopController.ruJournal(identity).toString())
        assertTrue(journal.has("lease_expires_at") && journal.isNull("lease_expires_at"))
        val restored = DesktopController.ruIdentity(journal)
        assertEquals(identity, restored)
        assertFalse(restored.hasSameAuthorization(identity.copy(routeGeneration = 8)))
        val manual = identity.copy(relaySelection = RuRelaySelection.Node(identity.relayNodeId), entryNodeId = null)
        assertEquals(manual, DesktopController.ruIdentity(DesktopController.ruJournal(manual)))
        journal.remove("route_id")
        assertTrue(runCatching { DesktopController.ruIdentity(journal) }.isFailure)
        assertEquals(identity.sessionId, journal.getString("session_id"))
        assertEquals(identity.revokeToken, journal.getString("revoke_token"))
    }

    @Test fun `avatar download does not delay login restore refresh or logout`() = runBlocking {
        val directory = Files.createTempDirectory("noki-avatar-controller-test")
        val vault = WindowsVault(directory)
        val stalePart = directory.resolve("avatar-${java.util.UUID.randomUUID()}.cache.part")
        val staleCache = directory.resolve("avatar-${java.util.UUID.randomUUID()}.cache")
        val unrelated = directory.resolve("avatar-user-note.cache.part")
        val cached = directory.resolve("avatar.cache")
        for (path in listOf(stalePart, staleCache, unrelated, cached)) Files.writeString(path, "previous")
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val executor = java.util.concurrent.Executors.newCachedThreadPool()
        server.executor = executor
        val avatarGate = AtomicReference(CountDownLatch(1))
        val avatarRequested = java.util.concurrent.LinkedBlockingQueue<Int>()
        val avatarRequests = java.util.concurrent.atomic.AtomicInteger()
        val avatarResponses = java.util.concurrent.atomic.AtomicInteger()
        val bootstrapRequests = java.util.concurrent.atomic.AtomicInteger()
        val requestPaths = java.util.concurrent.ConcurrentLinkedQueue<String>()
        val avatarUrl = AtomicReference("/v1/app/profile/avatar/photo-1.jpg")
        val userId = AtomicReference("user")
        val device = JSONObject().put("id", "pc-device").put("device_key", vault.deviceKey)
            .put("device_name", "PC").put("platform", DesktopBuild.PLATFORM).put("is_active", true).put("access_role", "owner")
        val catalog = JSONObject().put("user", JSONObject().put("id", "user").put("username", "Test")
            .put("email", "test@example.invalid"))
            .put("devices", JSONArray().put(device))
        server.createContext("/v1/") { exchange ->
            requestPaths.add(exchange.requestURI.path)
            exchange.requestBody.use { it.readAllBytes() }
            if (exchange.requestURI.path.startsWith("/v1/app/profile/avatar/")) {
                val number = avatarRequests.incrementAndGet()
                val gate = avatarGate.get()
                try {
                    assertEquals("Bearer bound", exchange.requestHeaders.getFirst("Authorization"))
                    val response = "avatar-$number".toByteArray()
                    exchange.sendResponseHeaders(200, response.size.toLong())
                    exchange.responseBody.use {
                        it.write(response, 0, 4); it.flush()
                        avatarRequested.add(number)
                        assertTrue(gate.await(20, TimeUnit.SECONDS))
                        it.write(response, 4, response.size - 4)
                    }
                } finally { avatarResponses.incrementAndGet(); exchange.close() }
            } else {
                val response = when (exchange.requestURI.path) {
                    "/v1/web/auth/desktop/start" -> JSONObject().put("request_id", "q".repeat(43))
                    "/v1/web/auth/desktop/token" -> JSONObject().put("status", "approved").put("access_token", "provisional").put("refresh_token", "initial-refresh")
                    "/v1/devices/register" -> device
                    "/v1/auth/refresh" -> JSONObject().put("access_token", "bound").put("refresh_token", "bound-refresh")
                    "/v1/app/bootstrap" -> catalog.also {
                        it.getJSONObject("user").put("id", userId.get()).put("avatar_url", avatarUrl.get())
                        bootstrapRequests.incrementAndGet()
                    }
                    "/v1/app/profile/avatar" -> JSONObject().put("avatar_url", JSONObject.NULL)
                    notificationsPath -> JSONObject().put("notifications", JSONArray())
                    "/v1/auth/logout" -> JSONObject()
                    else -> throw AssertionError("Unexpected path: ${exchange.requestURI.path}")
                }.toString().toByteArray()
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(200, response.size.toLong())
                exchange.responseBody.use { it.write(response) }
            }
        }
        // Keep production HTTPS URL validation while routing this fixture to its local HTTP server.
        val http = OkHttpClient.Builder().callTimeout(15, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).addInterceptor { chain ->
            chain.proceed(chain.request().newBuilder().url(chain.request().url.newBuilder().scheme("http").build()).build())
        }.build()
        val api = BackendApiClient(http, "https://127.0.0.1:${server.address.port}")
        var controller: DesktopController? = null
        suspend fun ready(app: DesktopController) = withTimeout(5_000) { app.state.first { !it.loading && it.user != null } }
        suspend fun avatarRequest(): Int {
            val number = checkNotNull(avatarRequested.poll(5, TimeUnit.SECONDS)) {
                "Avatar request did not start: requests=$requestPaths, loading=${controller?.state?.value?.loading}, user=${controller?.state?.value?.user?.id}, pending=${controller?.state?.value?.websiteLoginPending}, message=${controller?.state?.value?.message}"
            }
            withTimeout(5_000) {
                while (Files.list(directory).use { paths -> paths.filter {
                    it.fileName.toString().endsWith(".cache.part") && runCatching { Files.size(it) }.getOrDefault(0) == 4L
                }.count() } < 1) kotlinx.coroutines.delay(10)
            }
            return number
        }
        fun stop(app: DesktopController) {
            val stopped = CountDownLatch(1)
            app.exit { stopped.countDown() }
            assertTrue("Exit must not wait for optional content", stopped.await(5, TimeUnit.SECONDS))
        }
        suspend fun transfersFinished(count: Int) = withTimeout(5_000) {
            while (avatarResponses.get() < count || http.dispatcher.runningCallsCount() > 0 ||
                Files.list(directory).use { paths -> paths.anyMatch {
                    it != unrelated && it.fileName.toString().startsWith("avatar-")
                } }) kotlinx.coroutines.delay(10)
        }
        try {
            server.start()
            var app = DesktopController(vault, stoppedTestRuntime(vault), api, {})
            controller = app
            withTimeout(5_000) { app.state.first { !it.loading } }
            assertFalse(Files.exists(stalePart))
            assertFalse(Files.exists(staleCache))
            assertEquals("Startup must retain the committed avatar", "previous", Files.readString(cached))
            assertEquals("Startup must retain unrelated files", "previous", Files.readString(unrelated))
            app.loginFromWebsite()
            assertEquals(1, avatarRequest())
            ready(app)
            assertEquals(1L, avatarGate.get().count)
            assertEquals(0, avatarResponses.get())
            assertNull("Account must become usable while the avatar response is blocked", app.state.value.avatarPath)
            assertEquals("bound", vault.loadTokens()!!.accessToken)
            avatarGate.get().countDown()
            withTimeout(5_000) { app.state.first { it.avatarPath != null } }
            assertEquals("avatar-1", Files.readString(cached))
            assertEquals("user", vault.loadAccountLocal().getJSONObject("avatar_cache").getString("user_id"))
            val previousBootstrap = bootstrapRequests.get()
            withTimeout(5_000) { app.refresh().join() }
            assertTrue(bootstrapRequests.get() > previousBootstrap)
            ready(app)
            assertEquals("Refresh must retain an unchanged cached avatar", cached.toString(), app.state.value.avatarPath)
            assertEquals(1, avatarRequests.get())
            stop(app)
            controller = null
            app = DesktopController(vault, stoppedTestRuntime(vault), api)
            controller = app
            ready(app)
            assertEquals("Restore must reuse the persisted avatar", cached.toString(), app.state.value.avatarPath)
            assertEquals(1, avatarRequests.get())

            avatarGate.set(CountDownLatch(1))
            avatarUrl.set("/v1/app/profile/avatar/photo-2.jpg")
            withTimeout(5_000) { app.refresh().join() }
            assertEquals(2, avatarRequest())
            ready(app)
            assertEquals(1L, avatarGate.get().count)
            assertEquals(1, avatarResponses.get())
            assertNull(app.state.value.avatarPath)
            repeat(6) {
                val previous = bootstrapRequests.get()
                withTimeout(5_000) { app.refresh().join() }
                assertTrue(bootstrapRequests.get() > previous)
                ready(app)
                assertEquals("Refresh must share a single physical avatar transfer", 2, avatarRequests.get())
                assertEquals(1L, avatarGate.get().count)
                assertEquals(1, avatarResponses.get())
            }
            avatarGate.get().countDown()
            withTimeout(5_000) { app.state.first { it.avatarPath != null } }
            transfersFinished(2)
            assertEquals("Repeated refresh must publish the original transfer without downloading twice", 2, avatarRequests.get())
            assertEquals("avatar-2", Files.readString(cached))
            assertEquals(avatarUrl.get(), vault.loadAccountLocal().getJSONObject("avatar_cache").getString("url"))
            avatarGate.set(CountDownLatch(1))
            avatarUrl.set("/v1/app/profile/avatar/photo-3.jpg")
            withTimeout(5_000) { app.refresh().join() }
            assertEquals(3, avatarRequest())
            ready(app)
            stop(app)
            controller = null
            avatarGate.get().countDown()
            transfersFinished(3)
            assertEquals("avatar-2", Files.readString(cached))
            avatarGate.set(CountDownLatch(1))

            val restored = DesktopController(vault, stoppedTestRuntime(vault), api, {})
            controller = restored
            assertEquals(4, avatarRequest())
            ready(restored)
            assertEquals(1L, avatarGate.get().count)
            assertEquals(3, avatarResponses.get())
            restored.logout()
            withTimeout(5_000) { restored.state.first { !it.loading && it.user == null } }
            assertNull(vault.loadTokens())
            assertNull(restored.state.value.avatarPath)
            avatarGate.get().countDown()
            transfersFinished(4)
            assertNull("Cancelled content must not republish after logout", restored.state.value.avatarPath)
            assertEquals("Cancelled content must not replace the committed cache", "avatar-2", Files.readString(cached))

            avatarGate.set(CountDownLatch(1))
            avatarUrl.set("/v1/app/profile/avatar/photo-2.jpg")
            userId.set("other-user")
            restored.loginFromWebsite()
            assertEquals(5, avatarRequest())
            ready(restored)
            assertNull("A different account must not reuse another owner's avatar", restored.state.value.avatarPath)
            avatarGate.get().countDown()
            withTimeout(5_000) { restored.state.first { it.avatarPath != null } }
            assertEquals("avatar-5", Files.readString(cached))
            assertEquals("other-user", vault.loadAccountLocal().getJSONObject("avatar_cache").getString("user_id"))
            restored.deleteAvatar()
            withTimeout(5_000) { restored.state.first { !it.loading && it.user?.avatarUrl == null } }
            assertNull(restored.state.value.avatarPath)
            assertNull(vault.loadAccountLocal().optJSONObject("avatar_cache"))
            assertFalse(Files.exists(cached))
        } finally {
            avatarGate.get().countDown()
            controller?.let(::stop)
            server.stop(0)
            executor.shutdownNow()
            http.connectionPool.evictAll()
            http.dispatcher.executorService.shutdownNow()
            Files.list(directory).use { it.forEach(Files::delete) }
            Files.delete(directory)
        }
    }

    @OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
    @Test fun `desktop API binds the current platform device and retries refresh with persisted request id`() = runBlocking {
        val directory = Files.createTempDirectory("noki-controller-test")
        val vault = WindowsVault(directory)
        val calls = java.util.concurrent.CopyOnWriteArrayList<Pair<String, JSONObject>>()
        val expire = AtomicBoolean(false)
        val failRefresh = AtomicBoolean(false)
        val failLogout = AtomicBoolean(false)
        val failRuRevoke = AtomicBoolean(true)
        val invalidGeosite = AtomicBoolean(false)
        val failInvite = AtomicBoolean(true)
        val inviteRequested = CountDownLatch(1)
        val inviteResponse = CountDownLatch(1)
        val inviteExpiry = AtomicReference<String?>(null)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val device = JSONObject().put("id", "pc-device").put("device_key", vault.deviceKey)
            .put("device_name", "PC").put("platform", DesktopBuild.PLATFORM).put("is_active", true)
        val catalog = JSONObject().put("user", JSONObject().put("id", "user").put("username", "Test")
            .put("email", "test@example.invalid")).put("devices", JSONArray().put(device))
            .put("subscription", JSONObject().put("status", "active").put("plan_name", "Test plan"))
            .put("locations", JSONArray().put(JSONObject().put("id", "loc").put("code", "de")
                .put("name", "Germany").put("entry_host", "example.invalid").put("country_code", "DE")
                .put("servers", JSONArray().put(JSONObject().put("id", "node").put("name", "Test server")
                    .put("country_code", "DE").put("location_code", "de").put("host", "example.invalid").put("is_online", true)))))
        server.createContext("/v1/") { exchange ->
            val path = exchange.requestURI.path
            val body = exchange.requestBody.use { it.readAllBytes().toString(Charsets.UTF_8) }
            val payload = if (body.isBlank()) JSONObject() else JSONObject(body)
            calls.add(path to payload)
            var status = 200
            val response = when (path) {
                "/v1/web/auth/desktop/start" -> JSONObject().put("request_id", "q".repeat(43))
                "/v1/web/auth/desktop/token" -> JSONObject().put("status", "approved").put("access_token", "provisional").put("refresh_token", "initial-refresh")
                "/v1/devices/register" -> {
                    assertEquals("Bearer provisional", exchange.requestHeaders.getFirst("Authorization"))
                    device
                }
                "/v1/auth/refresh" -> if (failRefresh.get()) {
                    status = 503; JSONObject().put("detail", "simulated response lost after rotation")
                } else {
                    expire.set(false)
                    JSONObject().put("access_token", "bound").put("refresh_token", "bound-refresh")
                }
                "/v1/app/bootstrap" -> {
                    assertEquals("Bearer bound", exchange.requestHeaders.getFirst("Authorization"))
                    assertEquals("pc-device", exchange.requestHeaders.getFirst("X-Device-Id"))
                    assertEquals(vault.deviceKey, exchange.requestHeaders.getFirst("X-Device-Key"))
                    if (expire.get()) { status = 401; JSONObject().put("detail", "expired") } else catalog
                }
                "/v1/locations" -> catalog.getJSONArray("locations")
                "/v1/auth/logout" -> { if (failLogout.get()) status = 503; JSONObject() }
                "/v1/app/geosite" -> if (invalidGeosite.get()) JSONObject().put("sha256", "invalid").put("size_bytes", 1024)
                    else { status = 503; JSONObject().put("detail", "offline geosite service") }
                "/v1/vpn/access" -> JSONObject().put("can_connect", true)
                "/v1/devices/challenge" -> JSONObject().put("device_id", "pc-device").put("nonce", "test-vpn-challenge")
                "/v1/vpn/session" -> { status = 503; JSONObject().put("detail", "route unavailable") }
                "/v1/vpn/sessions/pending-session" -> {
                    assertEquals("DELETE", exchange.requestMethod)
                    assertEquals("r".repeat(32), exchange.requestHeaders.getFirst("X-Ru-Revoke-Token"))
                    if (failRuRevoke.get()) status = 503
                    JSONObject()
                }
                "/v1/app/logs" -> JSONObject()
                "/v1/devices/invites" -> {
                    inviteRequested.countDown()
                    assertTrue(inviteResponse.await(5, TimeUnit.SECONDS))
                    if (failInvite.get()) {
                        status = 503; JSONObject().put("detail", "temporarily_unavailable")
                    } else JSONObject().put("invite_code", "ABCDE-12345").put("expires_at", inviteExpiry.get() ?: JSONObject.NULL)
                }
                "/v1/app/security/username" -> catalog.getJSONObject("user").put("username", payload.getString("username"))
                "$notificationsPath/ack" -> JSONObject()
                notificationsPath -> JSONObject().put("notifications", JSONArray()
                    .put(JSONObject().put("id", "n1").put("title", "First").put("message", "One").put("created_at", "2026-10-06T08:00:00Z"))
                    .put(JSONObject().put("id", "n2").put("title", "Second").put("message", "Two").put("created_at", "2026-10-05T08:00:00Z")))
                else -> { status = 404; JSONObject() }
            }.toString().toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(status, response.size.toLong())
            exchange.responseBody.use { it.write(response) }
        }
        val http = OkHttpClient.Builder().callTimeout(3, TimeUnit.SECONDS).build()
        var controller: DesktopController? = null
        try {
            server.start()
            val app = DesktopController(vault, stoppedTestRuntime(vault), BackendApiClient(http, "http://127.0.0.1:${server.address.port}"), {},
                dnsPolicySnapshot = { WindowsDnsPolicy(emptyList(), emptyList()) })
            controller = app
            withTimeout(5_000) { app.state.first { !it.loading } }
            app.loginFromWebsite()
            withTimeout(10_000) { app.state.first { !it.loading && (it.user != null || it.message != null) } }
            assertNull(app.state.value.message)
            assertEquals("", app.state.value.selectedServerId)
            assertEquals("DE", app.state.value.locations.single().countryCode)
            assertEquals("node", app.state.value.locations.single().servers.single().id)
            assertNull(app.state.value.locations.single().latencyMs)
            assertEquals("Test plan", app.state.value.subscription!!.planName)
            val register = calls.single { it.first.endsWith("/devices/register") }.second
            assertEquals(DesktopBuild.PLATFORM, register.getString("platform"))
            assertEquals(vault.publicKey, register.getString("public_key"))
            assertEquals("rsa-sha256", register.getString("key_algorithm"))
            assertEquals(43, calls.first().second.getString("code_challenge").length)
            assertFalse("The PC must never collect a password", calls.any { it.second.has("password") })
            assertEquals("bound", WindowsVault(directory).loadTokens()!!.accessToken)
            assertEquals("pc-device", WindowsVault(directory).deviceId)

            app.createInvite()
            assertTrue("Invite modal must open before the network response", app.state.value.inviteOpen)
            assertTrue(app.state.value.inviteLoading)
            assertTrue(inviteRequested.await(5, TimeUnit.SECONDS))
            app.createInvite()
            inviteResponse.countDown()
            withTimeout(5_000) { app.state.first { !it.loading && !it.inviteLoading && it.inviteError != null } }
            assertTrue("Failure must stay visible inside the invite modal", app.state.value.inviteOpen)
            assertEquals("Repeated click must not create a second pending request", 1, calls.count { it.first == "/v1/devices/invites" })
            app.clearInvite()
            assertNull(app.state.value.inviteError)

            failInvite.set(false)
            inviteExpiry.set(java.time.LocalDateTime.ofInstant(java.time.Instant.now().plusSeconds(5), java.time.ZoneOffset.UTC).toString())
            app.createInvite()
            app.clearInvite()
            withTimeout(5_000) { app.state.first { !it.loading && !it.inviteLoading && it.invite != null } }
            assertFalse("A late response must not reopen the dismissed modal", app.state.value.inviteOpen)
            assertNull(app.state.value.inviteError)
            val inviteCalls = calls.count { it.first == "/v1/devices/invites" }
            app.createInvite()
            assertTrue(app.state.value.inviteOpen)
            assertFalse("A valid invite must reopen without waiting for network", app.state.value.inviteLoading)
            assertEquals(inviteCalls, calls.count { it.first == "/v1/devices/invites" })
            app.clearInvite()

            val cachedInviteExpiry = java.time.LocalDateTime.parse(checkNotNull(app.state.value.invite?.expiresAt))
                .toInstant(java.time.ZoneOffset.UTC)
            kotlinx.coroutines.delay((cachedInviteExpiry.toEpochMilli() - System.currentTimeMillis()).coerceAtLeast(0) + 50)
            for (expiry in listOf(java.time.Instant.now().minusSeconds(1).toString(), null, "invalid", java.time.Instant.now().plusSeconds(3600).toString())) {
                inviteExpiry.set(expiry)
                val beforeInvite = calls.count { it.first == "/v1/devices/invites" }
                app.createInvite()
                withTimeout(5_000) { app.state.first { !it.loading && !it.inviteLoading && it.invite != null } }
                assertEquals(beforeInvite + 1, calls.count { it.first == "/v1/devices/invites" })
                app.clearInvite()
            }
            app.clearMessage()

            val scene = androidx.compose.ui.ImageComposeScene(1000, 650, density = androidx.compose.ui.unit.Density(1f)) {
                val state = app.state.collectAsState().value
                DesktopContent(state, app, initialPage = DesktopPage.SERVERS)
            }
            fun click(x: Float, y: Float) {
                val point = androidx.compose.ui.geometry.Offset(x, y)
                scene.sendPointerEvent(androidx.compose.ui.input.pointer.PointerEventType.Press, point,
                    buttons = androidx.compose.ui.input.pointer.PointerButtons(isPrimaryPressed = true), button = androidx.compose.ui.input.pointer.PointerButton.Primary)
                scene.sendPointerEvent(androidx.compose.ui.input.pointer.PointerEventType.Release, point,
                    buttons = androidx.compose.ui.input.pointer.PointerButtons(), button = androidx.compose.ui.input.pointer.PointerButton.Primary)
            }
            fun clickLabel(label: String) {
                fun target(node: androidx.compose.ui.semantics.SemanticsNode): androidx.compose.ui.semantics.SemanticsNode? =
                    if (node.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.Text)?.any { it.text == label } == true ||
                        node.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.ContentDescription)?.contains(label) == true) node
                    else node.children.firstNotNullOfOrNull(::target)
                val center = scene.semanticsOwners.firstNotNullOf { target(it.rootSemanticsNode) }.boundsInRoot.center
                click(center.x, center.y)
            }
            try {
                repeat(4) { scene.render(it * 100_000_000L).close() }
                clickLabel(app.state.value.locations.single().country)
                withTimeout(5_000) { app.state.first { !it.loading && it.settings.userProfile.serverSelectionMode == com.noki.vpn.data.ServerSelectionMode.COUNTRY } }
                assertEquals("DE", app.state.value.settings.userProfile.selectedCountryCode)
                repeat(8) { scene.render(1_000_000_000L + it * 100_000_000L).close() }
                clickLabel("Раскрыть серверы")
                repeat(10) { scene.render(2_000_000_000L + it * 100_000_000L).close() }
                assertEquals("Arrow must keep country selection", com.noki.vpn.data.ServerSelectionMode.COUNTRY, app.state.value.settings.userProfile.serverSelectionMode)
                clickLabel("Test server")
                withTimeout(5_000) { app.state.first { !it.loading && it.settings.userProfile.serverSelectionMode == com.noki.vpn.data.ServerSelectionMode.SERVER } }
                assertEquals("node", app.state.value.selectedServerId)
            } finally { scene.close() }

            app.saveRoutingRule(DesktopRoutingRule("work", "Работа", false, listOf("example.com"), listOf("192.0.2.0/24")))
            withTimeout(5_000) { app.state.first { !it.loading && it.routingRules.any { rule -> rule.id == "work" } } }
            assertEquals(DesktopRoutingRule.RUSSIAN_RESOURCES, app.state.value.routingRules.first())
            assertEquals(app.state.value.routingRules, WindowsVault(directory).loadRoutingRules())

            app.setReducedMotion(true)
            withTimeout(5_000) { app.state.first { !it.loading && it.reducedMotion } }

            app.setLoggingEnabled(false)
            withTimeout(5_000) { app.state.first { !it.loading && !it.settings.advancedSettings.connectionLogsEnabled && !it.settings.advancedSettings.errorLogsEnabled } }
            app.setAutomaticLogSending(true)
            withTimeout(5_000) { app.state.first { !it.loading } }
            assertFalse(app.state.value.settings.advancedSettings.anonymousLogsEnabled)
            assertFalse(com.noki.vpn.data.AppDiagnosticLogPolicy.shouldUploadAutomatically(app.state.value.settings.advancedSettings))
            app.setLoggingEnabled(true)
            withTimeout(5_000) { app.state.first { !it.loading && it.settings.advancedSettings.connectionLogsEnabled && it.settings.advancedSettings.errorLogsEnabled } }
            app.setAutomaticLogSending(true)
            withTimeout(5_000) { app.state.first { !it.loading && it.settings.advancedSettings.anonymousLogsEnabled } }
            assertTrue(com.noki.vpn.data.AppDiagnosticLogPolicy.shouldUploadAutomatically(app.state.value.settings.advancedSettings))
            app.setLoggingEnabled(false)
            withTimeout(5_000) { app.state.first { !it.loading && !it.settings.advancedSettings.connectionLogsEnabled } }
            assertFalse(app.state.value.settings.advancedSettings.anonymousLogsEnabled)
            assertFalse(WindowsVault(directory).loadSettings().advancedSettings.anonymousLogsEnabled)

            val browserRule = DesktopRoutingRule("browser", "Browser", false, applications = listOf(if (DesktopBuild.IS_LINUX) "/opt/apps/browser" else "C:\\Apps\\Browser.exe"))
            app.saveRoutingRule(browserRule)
            withTimeout(5_000) { app.state.first { !it.loading && it.routingRules.contains(browserRule) } }
            assertEquals(app.state.value.routingRules, WindowsVault(directory).loadRoutingRules())
            assertEquals(app.state.value.settings, WindowsVault(directory).loadSettings())
            assertEquals("Appearance updates must preserve Android settings", app.state.value.settings, WindowsVault(directory).loadSettings())
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                app.editCredential("username"); app.credentials.updateUsername("Updated_name"); app.credentials.submitUsername()
            }
            withTimeout(5_000) { app.state.first { it.user?.username == "Updated_name" } }
            assertNull(app.state.value.accountSecurity.action)
            app.loadNotifications()
            withTimeout(5_000) { app.state.first { !it.loading && it.notifications.size == 2 } }
            assertTrue(app.state.value.hasUnreadNotifications)
            app.loadNotifications(markRead = true)
            withTimeout(5_000) { app.state.first { !it.loading && !it.hasUnreadNotifications } }
            app.hideNotification("n1")
            withTimeout(5_000) { app.state.first { !it.loading && it.notifications.size == 1 } }
            val before = calls.count { it.first == notificationsPath }
            app.loadNotifications()
            withTimeout(5_000) { while (calls.count { it.first == notificationsPath } <= before || app.state.value.loading) kotlinx.coroutines.delay(10) }
            assertEquals(listOf("n2"), app.state.value.notifications.map { it.id })
            assertFalse(app.state.value.hasUnreadNotifications)
            assertEquals(DesktopBuild.PLATFORM, calls.last { it.first == "/v1/app/logs" }.second.getString("platform"))
            BackendApiClient(http, "http://127.0.0.1:${server.address.port}").uploadAppLogs("bound", null, null, "Android", "{}")
            assertEquals("android", calls.last { it.first == "/v1/app/logs" }.second.getString("platform"))

            val entryId = "10000000-0000-4000-8000-000000000001"
            val exitId = "10000000-0000-4000-8000-000000000002"
            val nodes = catalog.getJSONArray("locations").getJSONObject(0).getJSONArray("servers")
            for ((id, entry) in listOf(entryId to true, exitId to false)) nodes.put(JSONObject()
                .put("id", id).put("name", id).put("country_code", "DE").put("location_code", "de")
                .put("host", "example.invalid").put("is_online", true)
                .put("multihop_entry_available", entry).put("multihop_exit_available", !entry))
            app.refresh()
            withTimeout(5_000) { app.state.first { !it.loading && it.servers.size == 3 } }
            val hops = MultiHopSettings(true, HopSelection(ServerSelectionMode.COUNTRY, "DE"), HopSelection(ServerSelectionMode.COUNTRY, "DE"))
            app.updateSettings { it.copy(advancedSettings = it.advancedSettings.copy(multiHop = hops)) }
            withTimeout(5_000) { app.state.first { !it.loading && it.settings.advancedSettings.multiHop.enabled } }
            app.connect()
            withTimeout(15_000) { while (app.state.value.loading || calls.none { it.first == "/v1/vpn/session" }) kotlinx.coroutines.delay(10) }
            val route = calls.last { it.first == "/v1/vpn/session" }.second
            assertEquals("auto", route.getString("profile_code"))
            for (key in listOf("country_code", "location_code", "node_id", "exclude_location_code")) assertTrue(route.isNull(key))
            assertEquals(entryId, route.getJSONObject("multihop").getJSONObject("entry").getString("node_id"))
            assertEquals(exitId, route.getJSONObject("multihop").getJSONObject("exit").getString("node_id"))
            assertEquals(hops, app.state.value.settings.advancedSettings.multiHop)
            assertFalse(app.state.value.running)
            assertFalse(Files.exists(directory.resolve("runtime.dpapi")))
            invalidGeosite.set(true)
            val admitted = calls.count { it.first == "/v1/vpn/session" }
            val geositeRequests = calls.count { it.first == "/v1/app/geosite" }
            app.connect()
            withTimeout(10_000) { while (app.state.value.loading || calls.count { it.first == "/v1/app/geosite" } <= geositeRequests) kotlinx.coroutines.delay(10) }
            assertEquals("Invalid geosite metadata must stop admission instead of using a fallback", admitted, calls.count { it.first == "/v1/vpn/session" })
            app.updateSettings { it.copy(advancedSettings = it.advancedSettings.copy(multiHop = MultiHopSettings())) }
            withTimeout(5_000) { app.state.first { !it.loading && !it.settings.advancedSettings.multiHop.enabled } }

            app.clearMessage()
            expire.set(true); failRefresh.set(true)
            app.refresh()
            withTimeout(10_000) { app.state.first { !it.loading && it.message != null } }
            val failedId = calls.last { it.first.endsWith("/auth/refresh") }.second.getString("request_id")
            assertEquals(failedId, WindowsVault(directory).refreshRequestId("bound-refresh"))
            failRefresh.set(false)
            app.refresh()
            withTimeout(10_000) { app.state.first { !it.loading && it.message == null } }
            val retried = calls.last { it.first.endsWith("/auth/refresh") }.second
            assertEquals(failedId, retried.getString("request_id"))
            assertEquals("pc-device", retried.getString("device_id"))
            assertEquals("bound-refresh", vault.loadTokens()!!.refreshToken)
            failLogout.set(true)
            vault.writeEncrypted("ru-revoke.dpapi", JSONObject().put("session_id", "pending-session").put("revoke_token", "r".repeat(32)).toString())
            app.logout()
            withTimeout(10_000) { app.state.first { !it.loading && it.user == null } }
            assertNull(WindowsVault(directory).loadTokens())
            assertTrue("Logout must preserve reduced motion", app.state.value.reducedMotion)
            assertEquals("Logout must retain default and custom routing", listOf("russian-resources", "work", "browser"), app.state.value.routingRules.map { it.id })
            assertTrue(app.state.value.message!!.contains("Серверный отзыв"))
            assertEquals("bound-refresh", calls.last { it.first.endsWith("/auth/logout") }.second.getString("refresh_token"))
            assertNotNull("Offline logout must retain the durable RU revoke", vault.readEncrypted("ru-revoke.dpapi"))
            failRuRevoke.set(false)
            app.disconnect()
            withTimeout(5_000) { while (app.state.value.loading || vault.readEncrypted("ru-revoke.dpapi") != null) kotlinx.coroutines.delay(10) }
        } finally {
            inviteResponse.countDown()
            controller?.let { app ->
                val stopped = CountDownLatch(1)
                app.exit { stopped.countDown() }
                assertTrue("Exit callback must run before controller cancellation", stopped.await(5, TimeUnit.SECONDS))
            }
            server.stop(0)
            http.connectionPool.evictAll()
            http.dispatcher.executorService.shutdownNow()
            Files.list(directory).use { it.forEach(Files::delete) }
            Files.delete(directory)
        }
    }
}

// These controller/scene fixtures have no active VPN; OS shutdown has its own runtime tests.
internal fun stoppedTestRuntime(vault: WindowsVault): SingBoxRuntime = object : SingBoxRuntime(vault) {
    override fun restored(): Running? = null
    override suspend fun stop(restoreInternet: Boolean) = Unit
}
