package com.noki.desktop

import androidx.compose.ui.graphics.toComposeImageBitmap

import com.noki.vpn.data.ServerSelectionMode

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.dp
import com.noki.vpn.ui.nokiGlassSurface
import com.noki.vpn.ui.nokiAvatarImage
import com.noki.vpn.ui.nokiBitmapResource
import com.noki.vpn.ui.clearNokiUiCaches
import com.noki.vpn.AppErrorMapper
import com.noki.vpn.data.AppLanguage
import com.noki.vpn.data.BackendException
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.noki.vpn.data.BackendSubscription
import com.noki.vpn.data.BackendUser
import com.noki.vpn.data.VpnServer
import com.noki.vpn.data.ServerLocation
import org.jetbrains.skia.EncodedImageFormat
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import javax.imageio.ImageIO

class DesktopUiTest {
    @OptIn(ExperimentalComposeUiApi::class)
    @Test fun startupFailureProvidesSafeDetailsAndRetry() {
        val secret = "private-value-must-not-be-copied"
        val details = desktopStartupFailureDetails(IllegalStateException(secret, com.sun.jna.platform.win32.Win32Exception(13)))
        assertTrue(details.contains("startup_failed"))
        assertTrue(details.contains("Windows error code: 13"))
        assertFalse(details.contains(secret))
        var copied: androidx.compose.ui.text.AnnotatedString? = null
        var clipboardBusy = true
        val clipboard = object : androidx.compose.ui.platform.ClipboardManager {
            override val nativeClipboard: Any get() = this
            override fun getText() = copied
            override fun setText(annotatedString: androidx.compose.ui.text.AnnotatedString) {
                check(!clipboardBusy) { "clipboard_busy" }; copied = annotatedString
            }
            override fun getClip(): androidx.compose.ui.platform.ClipEntry? = null
            override fun setClip(clipEntry: androidx.compose.ui.platform.ClipEntry?) = Unit
        }
        var failed by mutableStateOf(true)
        var retries = 0
        val scene = ImageComposeScene(1050, 674, density = Density(1f)) {
            CompositionLocalProvider(androidx.compose.ui.platform.LocalClipboardManager provides clipboard) {
                DesktopContent(DesktopState(), null, failure = if (failed) "Не удалось запустить Noki" else null,
                    failureDetails = if (failed) details else null, onRetry = { retries++; failed = false })
            }
        }
        fun descendants(node: SemanticsNode): Sequence<SemanticsNode> = sequence {
            yield(node); for (child in node.children) yieldAll(descendants(child))
        }
        fun label(text: String) = scene.semanticsOwners.asSequence().flatMap { descendants(it.rootSemanticsNode) }
            .firstOrNull { it.config.getOrNull(SemanticsProperties.Text)?.any { value -> value.text == text } == true }
        fun render() { repeat(4) { scene.render().close() } }
        fun click(text: String) {
            val point = checkNotNull(label(text)).boundsInRoot.center
            scene.sendPointerEvent(PointerEventType.Press, point, buttons = PointerButtons(isPrimaryPressed = true), button = PointerButton.Primary)
            scene.sendPointerEvent(PointerEventType.Release, point, buttons = PointerButtons(), button = PointerButton.Primary)
            render()
        }
        try {
            render()
            assertNotNull(label("Повторить"))
            assertNotNull(label("Копировать ошибку"))
            assertNull(copied)
            val output = java.nio.file.Path.of("build/screenshots/startup-failure.png")
            Files.createDirectories(output.parent)
            scene.render().use { it.encodeToData(EncodedImageFormat.PNG)!!.use { data -> Files.write(output, data.bytes) } }
            click("Копировать ошибку")
            assertNull(copied)
            assertNotNull(label("Копировать ошибку"))
            clipboardBusy = false
            click("Копировать ошибку")
            assertEquals(details, copied?.text)
            assertNotNull(label("Скопировано"))
            click("Повторить")
            assertEquals(1, retries)
            assertNull(label("Копировать ошибку"))
            assertNotNull(label("Открываем Noki Vpn…"))
        } finally { scene.close() }
    }


    @OptIn(ExperimentalComposeUiApi::class)
    @Test fun externalAccountLinksUseTrailingVectorIcons() {
        val vault = WindowsVault(Files.createTempDirectory("noki-external-links"), hardwareIdentity = { "fixture" })
        val app = DesktopController(vault, SingBoxRuntime(vault), com.noki.vpn.data.BackendApiClient(baseUrl = "http://127.0.0.1:9"))
        val base = DesktopState(user = BackendUser("fixture", "Fixture", "fixture@example.invalid", null, true, false))
        val state = base.copy(settings = base.settings.copy(personalizationSettings = base.settings.personalizationSettings.copy(language = AppLanguage.EN)))
        fun descendants(node: SemanticsNode): Sequence<SemanticsNode> = sequence {
            yield(node); for (child in node.children) yieldAll(descendants(child))
        }
        try {
            for (page in listOf(DesktopPage.SECURITY, DesktopPage.PROFILE)) {
                val scene = ImageComposeScene(1050, 674, density = Density(1f)) { DesktopContent(state, app, initialPage = page, earthVisible = false) }
                try {
                    repeat(4) { scene.render().close() }
                    val labels = scene.semanticsOwners.asSequence().flatMap { descendants(it.rootSemanticsNode) }
                        .flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty().asSequence() }.map { it.text }.toSet()
                    assertFalse(labels.any { "↗" in it })
                    if (page == DesktopPage.SECURITY) assertTrue(labels.containsAll(listOf("Change email", "Change password", "Telegram")))
                    val output = java.nio.file.Path.of("build/screenshots/linux-links"); Files.createDirectories(output)
                    scene.render().use { it.encodeToData(EncodedImageFormat.PNG)!!.use { data -> Files.write(output.resolve("${page.name.lowercase()}.png"), data.bytes) } }
                } finally { scene.close() }
            }
        } finally { runBlocking { app.closeAndJoin() }; app.close() }
    }



    @OptIn(ExperimentalComposeUiApi::class)
    @Test fun freePlanHidesAdvancedRoutingControls() {
        val keyField = if (DesktopBuild.IS_LINUX) LinuxPlatform::class.java.getDeclaredField("key").apply { isAccessible = true } else null
        val previousKey = keyField?.get(null)
        try {
            keyField?.set(null, ByteArray(32) { it.toByte() })
            val vault = WindowsVault(Files.createTempDirectory("noki-plan-controls-ui"), hardwareIdentity = { "fixture" })
            val app = DesktopController(vault, SingBoxRuntime(vault), com.noki.vpn.data.BackendApiClient(baseUrl = "http://127.0.0.1:9"))
            fun descendants(node: SemanticsNode): Sequence<SemanticsNode> = sequence {
                yield(node); for (child in node.children) yieldAll(descendants(child))
            }
            try {
                for ((code, tier, visible) in listOf(Triple("free", "free", false), Triple("free_plus", "free+", true), Triple("plus", "plus", true))) {
                    val state = DesktopState(user = BackendUser("fixture", "Fixture", "fixture@example.invalid", null, true, false),
                        subscription = BackendSubscription("active", code, null, 0.0, null, planTier = tier))
                    for (page in listOf(DesktopPage.ADVANCED, DesktopPage.SERVERS)) {
                        val scene = ImageComposeScene(1100, 1000, density = Density(1f)) {
                            DesktopContent(state, app, initialPage = page, earthVisible = false)
                        }
                        try {
                            repeat(4) { scene.render().close() }
                            val labels = scene.semanticsOwners.asSequence().flatMap { descendants(it.rootSemanticsNode) }
                                .flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty().asSequence() }.map { it.text }.toSet()
                            val controls = if (page == DesktopPage.ADVANCED) listOf("RU-реле", "Игровой режим", "YouTube без рекламы") else listOf("Мультихоп")
                            for (control in controls) assertEquals("$code: $control", visible, control in labels)
                        } finally { scene.close() }
                    }
                }
            } finally { runBlocking { app.closeAndJoin() }; app.close() }
        } finally { keyField?.set(null, previousKey) }
    }

    @OptIn(ExperimentalComposeUiApi::class)
    @Test fun `Auto country catalog does not expand the actual connected server`() {
        val locations = listOf(ServerLocation("de", "DE", "Германия", "", "", isOnline = true,
            servers = listOf(VpnServer("fixture-de", "DE-1", "DE", "de", "example.invalid", null, true))))
        for (mode in listOf(ServerSelectionMode.AUTO, ServerSelectionMode.COUNTRY, ServerSelectionMode.SERVER)) {
            val scene = ImageComposeScene(400, 500, density = Density(1f)) {
                DesktopTheme(null) {
                    CountryCatalog(DesktopState(), Modifier.fillMaxSize(), com.noki.vpn.data.HopSelection(mode, "DE", "fixture-de"),
                        locations = locations, onSelect = {})
                }
            }
            fun descendants(node: SemanticsNode): Sequence<SemanticsNode> = sequence {
                yield(node); for (child in node.children) yieldAll(descendants(child))
            }
            try {
                repeat(4) { scene.render().close() }
                val expanded = scene.semanticsOwners.asSequence().flatMap { descendants(it.rootSemanticsNode) }
                    .any { it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("Свернуть серверы") == true }
                assertEquals(mode == ServerSelectionMode.SERVER, expanded)
            } finally { scene.close() }
        }
    }

    @Test fun `failed watchdog evidence does not present a running core as disconnected`() {
        val active = DesktopState(running = true, verified = false, connectedAtMillis = 123L)
        assertTrue(active.hasEstablishedTunnel)
        assertFalse(active.copy(running = false).hasEstablishedTunnel)
        assertFalse(active.copy(connecting = true).hasEstablishedTunnel)
    }

    @get:org.junit.Rule val mainDispatcher: org.junit.rules.TestRule = DesktopMainDispatcherRule()

    @OptIn(ExperimentalComposeUiApi::class)
    @Test fun `focused composer submits native host Enter before text field consumes it`() {
        var draft by mutableStateOf("")
        val sent = mutableListOf<String>()
        val scene = ImageComposeScene(400, 160, density = Density(1f)) {
            DesktopTheme(null) {
                PcInput(draft, { draft = it }, "Message", Modifier.fillMaxWidth(), singleLine = false,
                    onSubmit = { sent += draft; draft = "" })
            }
        }
        // Use the same translation functions as TaoComposeSceneHostWindows.onKeyEvent.
        val nativeKeys = Class.forName("dev.nucleusframework.window.tao.event.TaoSyntheticKeyKt")
        val physical = nativeKeys.declaredMethods.single { it.name == "taoKeyEvent" }.apply { isAccessible = true }
        val typed = nativeKeys.declaredMethods.single { it.name == "taoTypedKeyEvent" }.apply { isAccessible = true }
        val boxKey = androidx.compose.ui.input.key.KeyEvent::class.java.declaredMethods.single { it.name == "box-impl" }
        fun keyEvent(event: Any) = boxKey.invoke(null, event) as androidx.compose.ui.input.key.KeyEvent
        fun render() { repeat(3) { scene.render().close() } }
        fun key(down: Boolean, shift: Boolean = false) {
            scene.sendKeyEvent(keyEvent(physical.invoke(null, down, java.awt.event.KeyEvent.VK_ENTER,
                java.awt.event.KeyEvent.KEY_LOCATION_STANDARD, shift, false, false, false, 0)))
            render()
        }
        try {
            render()
            scene.sendPointerEvent(PointerEventType.Press, Offset(80f, 25f), buttons = PointerButtons(isPrimaryPressed = true), button = PointerButton.Primary)
            scene.sendPointerEvent(PointerEventType.Release, Offset(80f, 25f), buttons = PointerButtons(), button = PointerButton.Primary)
            render()
            for (char in "help") {
                scene.sendKeyEvent(keyEvent(typed.invoke(null, char.code, java.awt.event.KeyEvent.KEY_LOCATION_STANDARD,
                    false, false, false, false)))
                render()
            }
            assertEquals("help", draft)
            key(true, shift = true); key(false, shift = true)
            assertEquals("help\n", draft)
            assertTrue(sent.isEmpty())
            key(true); key(false)
            assertEquals(listOf("help\n"), sent)
            assertEquals("", draft)
        } finally { scene.close() }
    }

    @OptIn(ExperimentalComposeUiApi::class)
    @Test fun `support chat composer and compact bubbles render`() = runBlocking<Unit> {
        val directory = Files.createTempDirectory("noki-support-ui")
        val vault = WindowsVault(directory)
        val app = DesktopController(vault, SingBoxRuntime(vault), com.noki.vpn.data.BackendApiClient(baseUrl = "http://127.0.0.1:9"))
        @Suppress("UNCHECKED_CAST")
        val chat = DesktopSupportChat::class.java.getDeclaredField("mutable").apply { isAccessible = true }
            .get(app.support) as kotlinx.coroutines.flow.MutableStateFlow<DesktopSupportChatState>
        chat.value = DesktopSupportChatState(isOpen = true, hasLoaded = true, draft = "Спасибо!", messages = listOf(
            com.noki.vpn.data.BackendSupportMessage(1, "Здравствуйте!", "outgoing", 1),
            com.noki.vpn.data.BackendSupportMessage(2, "какой ещё боб", "incoming", 2, status = "read")))
        val state = DesktopState(user = BackendUser("fixture", "Fixture", "fixture@example.invalid", null, true, false))
        val scene = ImageComposeScene(1100, 850, density = Density(1f)) {
            DesktopContent(state, app, initialPage = DesktopPage.SUPPORT, earthVisible = false)
        }
        fun descendants(node: SemanticsNode): Sequence<SemanticsNode> = sequence { yield(node); for (child in node.children) yieldAll(descendants(child)) }
        fun nodes() = scene.semanticsOwners.asSequence().flatMap { descendants(it.rootSemanticsNode) }
        fun label(text: String) = nodes().firstOrNull {
            it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(text) == true ||
                it.config.getOrNull(SemanticsProperties.Text)?.any { value -> value.text == text } == true
        }
        try {
            repeat(5) { scene.render().close() }
            val attach = checkNotNull(label("Прикрепить файл")).boundsInRoot
            val send = checkNotNull(label("Отправить")).boundsInRoot
            val field = checkNotNull(label("Сообщение...")).boundsInRoot
            assertEquals(56f, attach.width, 1f)
            assertEquals(54f, send.width, 1f)
            assertEquals(send.center.y, field.center.y, 1f)
            // Input semantics describe the content inside its 20 dp horizontal padding.
            assertEquals(25f, field.left - attach.right, 1f)
            assertEquals(25f, send.left - field.right, 1f)
            val body = checkNotNull(label("какой ещё боб")).boundsInRoot
            val ticks = checkNotNull(label("Прочитано")).boundsInRoot
            assertTrue("Time and ticks stay to the right of the short message", body.right < ticks.left)
            assertEquals(body.bottom, ticks.bottom, 2f)
            assertNull(nodes().firstOrNull { it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text == "Прочитано" || text.text == "Отправлено" } == true })
            val output = java.nio.file.Path.of("build/reports/support-chat.png")
            Files.createDirectories(output.parent)
            scene.render().use { image -> image.encodeToData(EncodedImageFormat.PNG)!!.use { Files.write(output, it.bytes) } }
        } finally { scene.close(); app.close(); directory.toFile().deleteRecursively() }
    }

    @Test fun `protocol label uses NokiNV and preserves ordinary preference during gaming`() {
        val auto = DesktopState(nokiNvCarrier = "quic")
        assertEquals("Auto", desktopProtocolLabel(auto))
        val settings = auto.settings.copy(profile = auto.settings.profile.copy(proxyType = "nokinv", transport = "nokinv"))
        val connecting = auto.copy(running = true, connectionSettings = settings)
        assertEquals("Auto", desktopProtocolLabel(connecting))
        val connected = connecting.copy(verified = true)
        assertEquals("NokiNV", desktopProtocolLabel(connected))
        assertEquals("NokiNV", desktopProtocolLabel(connected.copy(nokiNvCarrier = "tls")))
        assertEquals("NokiNV", desktopProtocolLabel(connected.copy(nokiNvCarrier = "mixed")))
        assertEquals("NokiNV", desktopProtocolLabel(connected.copy(nokiNvCarrier = "unknown")))
        assertEquals("NokiNV", desktopProtocolLabel(connected.copy(verified = false, networkRebinding = true, nokiNvCarrier = "")))
        assertEquals("—", desktopProtocolLabel(connected.copy(connectionSettings = null)))
        val manual = com.noki.vpn.data.EndpointGroupPolicy.protocolOptions().single { it.code == "hysteria2" }
        val ordinary = settings.copy(advancedSettings = settings.advancedSettings.copy(
            endpointSelectionMode = com.noki.vpn.data.EndpointSelectionMode.MANUAL,
            manualEndpointGroupKey = com.noki.vpn.data.EndpointGroupPolicy.groupKey(manual)))
        val gaming = ordinary.copy(advancedSettings = ordinary.advancedSettings.copy(gamingModeEnabled = true))
        assertEquals("NokiNV · игровой", desktopProtocolLabel(auto.copy(settings = gaming)))
        assertEquals("NokiNV · игровой", desktopProtocolLabel(connected.copy(connectionSettings = gaming)))
        assertEquals("Hysteria2", desktopProtocolLabel(auto.copy(settings = ordinary)))
        val english = gaming.copy(personalizationSettings = gaming.personalizationSettings.copy(language = AppLanguage.EN))
        assertEquals("NokiNV · gaming", desktopProtocolLabel(auto.copy(settings = english)))
    }

    @Test fun `protocol label shares picker names for actual carriers and Auto fallback across languages`() {
        for (language in AppLanguage.entries) for (manual in listOf(false, true)) {
            val base = DesktopState().settings
            val settings = base.copy(profile = base.profile.copy(endpointCode = "nv", proxyType = "nokinv", transport = "nokinv", transportMode = "quic"),
                personalizationSettings = base.personalizationSettings.copy(language = language),
                advancedSettings = base.advancedSettings.copy(endpointSelectionMode = if (manual) com.noki.vpn.data.EndpointSelectionMode.MANUAL else com.noki.vpn.data.EndpointSelectionMode.AUTO,
                    manualEndpointGroupKey = "tls|nokinv|quic"))
            val state = DesktopState(settings = settings, running = true, verified = true, connectionSettings = settings)
            for ((carrier, mode) in listOf("quic" to "quic")) {
                val expected = com.noki.vpn.data.EndpointGroupPolicy.protocolOptions().single { it.proxyType == "nokinv" && it.transportMode == mode }.label
                assertEquals(expected, desktopProtocolLabel(state.copy(nokiNvCarrier = carrier)))
                val gaming = settings.copy(advancedSettings = settings.advancedSettings.copy(gamingModeEnabled = true))
                assertEquals(expected + if (language == AppLanguage.RU) " · игровой" else " · gaming",
                    desktopProtocolLabel(state.copy(connectionSettings = gaming, nokiNvCarrier = carrier)))
            }
            assertEquals("NokiNV", desktopProtocolLabel(state.copy(nokiNvCarrier = "unknown")))
            val handover = state.copy(verified = false, networkRebinding = true, nokiNvCarrier = "")
            assertTrue(handover.hasEstablishedTunnel)
            assertEquals("NokiNV", desktopProtocolLabel(handover))
            val fallback = settings.copy(profile = settings.profile.copy(endpointCode = "hy", proxyType = "hysteria", transport = "hysteria", transportMode = ""),
                advancedSettings = settings.advancedSettings.copy(endpointSelectionMode = com.noki.vpn.data.EndpointSelectionMode.AUTO))
            assertEquals("Auto (Hysteria2 / UDP)", desktopProtocolLabel(state.copy(connectionSettings = fallback)))
        }
    }

    @OptIn(ExperimentalComposeUiApi::class)
    @Test fun `release protocol picker works without country and Auto waits for verified connection`() = runBlocking<Unit> {
        val directory = Files.createTempDirectory("noki-release-policy-ui")
        val vault = WindowsVault(directory)
        val app = DesktopController(vault, SingBoxRuntime(vault), com.noki.vpn.data.BackendApiClient(baseUrl = "http://127.0.0.1:9"))
        withTimeout(5_000) { app.state.first { !it.loading } }
        @Suppress("UNCHECKED_CAST")
        val mutable = DesktopController::class.java.getDeclaredField("mutable").apply { isAccessible = true }
            .get(app) as kotlinx.coroutines.flow.MutableStateFlow<DesktopState>
        val account = mutable.value.copy(user = BackendUser("fixture", "Fixture", "fixture@example.invalid", null, true, false),
            subscription = BackendSubscription("active", "plus", null, 0.0, null, planTier = "plus"),
            selectedServerId = "fixture-de", locations = listOf(ServerLocation("de", "DE", "Германия", "", "", isOnline = true,
                servers = listOf(VpnServer("fixture-de", "DE-1", "DE", "de", "example.invalid", null, true)))))
        mutable.value = account
        var page by mutableStateOf(DesktopPage.PROTOCOL)
        var renderedState by mutableStateOf(account)
        val scene = ImageComposeScene(1100, 850, density = Density(1f)) {
            key(page) { DesktopContent(renderedState, app, initialPage = page, earthVisible = false) }
        }
        fun descendants(node: SemanticsNode): Sequence<SemanticsNode> = sequence { yield(node); for (child in node.children) yieldAll(descendants(child)) }
        fun nodes() = scene.semanticsOwners.asSequence().flatMap { descendants(it.rootSemanticsNode) }
        fun label(text: String) = nodes().firstOrNull {
            it.config.getOrNull(SemanticsProperties.Text)?.any { value -> value.text == text } == true ||
                it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(text) == true
        }
        fun render() { repeat(5) { scene.render().close() } }
        try {
            render()
            for (protocol in listOf("Auto", "NokiNV", "Reality XHTTP stream-up", "Reality / TCP", "Hysteria2", "NaiveProxy")) {
                val node = checkNotNull(label(protocol))
                assertFalse("Protocol $protocol must be selectable without a country", node.config.contains(SemanticsProperties.Disabled))
            }
            assertNotNull(label("Reality / TCP"))
            assertNull(label("TLS / TCP"))
            assertNull(label("NokiNV / QUIC"))
            assertNull(label("NokiNV / TCP"))
            page = DesktopPage.HOME
            render()
            assertNotNull(label("Автовыбор сервера"))
            assertNull(label("Германия"))
            val connected = account.copy(running = true, connectionSettings = account.settings, verified = false)
            renderedState = connected
            render()
            assertNotNull(label("Автовыбор сервера"))
            assertNull(label("Германия"))
            renderedState = connected.copy(verified = true)
            render()
            assertNull(label("Автовыбор сервера"))
            assertNotNull(label("Германия"))
            renderedState = connected.copy(verified = false, networkRebinding = true,
                connectedAtMillis = System.currentTimeMillis() - 90_000)
            render()
            assertNull(label("Автовыбор сервера"))
            assertNotNull(label("Германия"))
            assertNotNull(label("Восстановление связи"))
            assertNull(label("Не удалось подключиться"))
            assertNull(label("00:00:00"))
            assertNotNull(label("Отключить VPN"))
            renderedState = renderedState.copy(networkRebinding = false)
            render()
            assertNotNull(label("Подключено"))
            assertNull(label("Проверка связи"))
            assertNull(label("Не подключено"))
            assertNull(label("Не удалось подключиться"))
            assertNull(label("00:00:00"))
            scene.render().use { frame -> frame.encodeToData(EncodedImageFormat.PNG)!!.use { data ->
                Files.createDirectories(java.nio.file.Path.of("build/screenshots"))
                Files.write(java.nio.file.Path.of("build/screenshots/watchdog-checking-1.6.8.png"), data.bytes)
            } }
            renderedState = account
            render()
            assertNotNull(label("Автовыбор сервера"))
            assertNotNull(label("Не подключено"))
            page = DesktopPage.SECURITY
            render()
            val version = checkNotNull(label(DesktopDiagnostics.appVersion))
            val support = checkNotNull(label("Поддержка"))
            assertTrue("Version stays above Support in the sidebar", version.boundsInRoot.bottom < support.boundsInRoot.top)
            assertEquals(support.boundsInRoot.center.x, version.boundsInRoot.center.x, 1f)
            assertNull(label("Версия " + DesktopDiagnostics.appVersion))
            val manual = com.noki.vpn.data.EndpointGroupPolicy.protocolOptions().last()
            val preference = com.noki.vpn.data.EndpointGroupPolicy.groupKey(manual)
            mutable.value = account.copy(settings = account.settings.copy(advancedSettings = account.settings.advancedSettings.copy(
                ruRelayEnabled = true, endpointSelectionMode = com.noki.vpn.data.EndpointSelectionMode.MANUAL,
                manualEndpointGroupKey = preference)))
            renderedState = mutable.value
            page = DesktopPage.ADVANCED
            render()
            fun click(text: String) {
                val point = checkNotNull(label(text)).boundsInRoot.center
                scene.sendPointerEvent(PointerEventType.Press, point, buttons = PointerButtons(isPrimaryPressed = true), button = PointerButton.Primary)
                scene.sendPointerEvent(PointerEventType.Release, point, buttons = PointerButtons(), button = PointerButton.Primary)
            }
            click("Игровой режим")
            withTimeout(5_000) { app.state.first { !it.loading && it.settings.advancedSettings.gamingModeEnabled } }
            assertFalse(app.state.value.settings.advancedSettings.ruRelayEnabled)
            assertTrue(WindowsVault(directory).loadSettings().advancedSettings.gamingModeEnabled)
            assertEquals(preference, app.state.value.settings.advancedSettings.manualEndpointGroupKey)
            renderedState = app.state.value
            render()
            click("RU-реле")
            withTimeout(5_000) { app.state.first { !it.loading && it.settings.advancedSettings.ruRelayEnabled } }
            assertFalse(app.state.value.settings.advancedSettings.gamingModeEnabled)
            assertFalse(WindowsVault(directory).loadSettings().advancedSettings.gamingModeEnabled)
            assertEquals(preference, app.state.value.settings.advancedSettings.manualEndpointGroupKey)
        } finally {
            scene.close(); app.closeAndJoin(); app.close()
            Files.list(directory).use { it.forEach(Files::delete) }; Files.delete(directory)
        }
    }

    @OptIn(ExperimentalComposeUiApi::class)
    @Test fun `advanced settings omit the global DNS protection toggle and keep routing accessible`() = runBlocking<Unit> {
        val directory = Files.createTempDirectory("noki-dns-protection-ui")
        val vault = WindowsVault(directory)
        val app = DesktopController(vault, SingBoxRuntime(vault), com.noki.vpn.data.BackendApiClient(baseUrl = "http://127.0.0.1:9"))
        withTimeout(5_000) { app.state.first { !it.loading } }
        @Suppress("UNCHECKED_CAST")
        val mutable = DesktopController::class.java.getDeclaredField("mutable").apply { isAccessible = true }
            .get(app) as kotlinx.coroutines.flow.MutableStateFlow<DesktopState>
        mutable.value = mutable.value.copy(user = BackendUser("fixture", "Fixture", "fixture@example.invalid", null, true, false))
        var scene = ImageComposeScene(1100, 850, density = Density(1f)) {
            val state by app.state.collectAsState()
            DesktopContent(state, app, initialPage = DesktopPage.ADVANCED, earthVisible = false)
        }
        fun descendants(node: SemanticsNode): Sequence<SemanticsNode> = sequence { yield(node); for (child in node.children) yieldAll(descendants(child)) }
        fun nodes() = scene.semanticsOwners.asSequence().flatMap { descendants(it.rootSemanticsNode) }
        fun label(text: String) = nodes().firstOrNull {
            it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(text) == true ||
                it.config.getOrNull(SemanticsProperties.Text)?.any { value -> value.text == text } == true
        }
        fun render() { repeat(4) { scene.render().close() } }
        fun click(text: String) {
            val point = checkNotNull(label(text)).boundsInRoot.center
            scene.sendPointerEvent(PointerEventType.Press, point, buttons = PointerButtons(isPrimaryPressed = true), button = PointerButton.Primary)
            scene.sendPointerEvent(PointerEventType.Release, point, buttons = PointerButtons(), button = PointerButton.Primary)
            render()
        }
        try {
            render()
            assertNull(label("Защита DNS"))
            assertNull(label("Защищённый DNS через VPN"))
            assertNull(label("DNS-фильтрация"))
            click("Маршрутизация")
            assertNull(label("Правила"))
            assertNull(label("Правила маршрутизации"))
            assertNull(label("Приложения"))
            fun button(text: String) = generateSequence(checkNotNull(label(text))) { it.parent }.first {
                it.config.contains(androidx.compose.ui.semantics.SemanticsActions.OnClick)
            }
            assertEquals(button("Российские ресурсы").boundsInRoot.left, button("Добавить").boundsInRoot.left, 1f)
            click("Добавить")
            assertNotNull(label("Название правила"))
            assertNotNull(label("Через VPN"))
            assertNotNull(label("Без VPN"))
            assertNotNull(label("Добавить .exe"))
            assertNotNull(label("Добавить папку"))
            val domains = nodes().filter { it.config.contains(SemanticsProperties.EditableText) }.elementAt(1).boundsInRoot
            val programs = checkNotNull(label("Выбрать установленные приложения")).boundsInRoot
            val screenshots = java.nio.file.Path.of("build", "screenshots")
            Files.createDirectories(screenshots)
            scene.render().use { image -> image.encodeToData(EncodedImageFormat.PNG)!!.use { data ->
                Files.write(screenshots.resolve("routing-rule-wide.png"), data.bytes)
            } }
            assertTrue("Applications follow domains in one column: domains=$domains, applications=$programs", programs.top > domains.bottom)
            assertNull(label("Добавить список"))
            assertNull(label("Списки geosite / geoip"))
            assertTrue("One column keeps the same left edge", kotlin.math.abs(domains.left - programs.left) <= 16f)
            fun setText(index: Int, text: String) {
                val input = nodes().filter { it.config.contains(SemanticsProperties.EditableText) }.elementAt(index)
                assertTrue(checkNotNull(input.config[androidx.compose.ui.semantics.SemanticsActions.SetText].action)
                    .invoke(androidx.compose.ui.text.AnnotatedString(text)))
                render()
            }
            setText(0, "Работа")
            setText(1, "example.com")
            click("Сохранить")
            withTimeout(5_000) { app.state.first { !it.loading && it.routingRules.any { row -> row.name == "Работа" } } }
            render()
            click("Работа")
            setText(0, "Рабочие сайты")
            click("Сохранить")
            withTimeout(5_000) { app.state.first { !it.loading && it.routingRules.any { row -> row.name == "Рабочие сайты" } } }
            assertEquals(listOf("domain:example.com"), vault.loadRoutingRules().single { it.name == "Рабочие сайты" }.domains)
            render()
            assertNotNull(label("Рабочие сайты"))
            click("Российские ресурсы")
            assertNull(label("Удалить правило"))
            click("Отключить")
            withTimeout(5_000) { app.state.first { !it.loading && it.routingRules.any { row -> row.id == DesktopRoutingRule.RUSSIAN_RESOURCES.id && !row.enabled } } }
            assertFalse(vault.loadRoutingRules().single { it.id == DesktopRoutingRule.RUSSIAN_RESOURCES.id }.enabled)
            render()
            click("Российские ресурсы")
            click("Включить")
            withTimeout(5_000) { app.state.first { !it.loading && it.routingRules.any { row -> row.id == DesktopRoutingRule.RUSSIAN_RESOURCES.id && row.enabled } } }
            scene.close()
            scene = ImageComposeScene(700, 850, density = Density(1f)) {
                val state by app.state.collectAsState()
                DesktopContent(state, app, initialPage = DesktopPage.ROUTING, earthVisible = false)
            }
            render()
            click("Добавить")
            assertTrue(checkNotNull(label("Выбрать установленные приложения")).boundsInRoot.top > nodes().filter { it.config.contains(SemanticsProperties.EditableText) }.last().boundsInRoot.top)
            scene.render().use { image -> image.encodeToData(EncodedImageFormat.PNG)!!.use { data ->
                Files.write(screenshots.resolve("routing-rule-narrow.png"), data.bytes)
            } }
        } finally {
            scene.close(); app.closeAndJoin(); app.close()
            Files.list(directory).use { it.forEach(Files::delete) }; Files.delete(directory)
        }
    }

    @OptIn(ExperimentalComposeUiApi::class)
    @Test fun `promo results appear only in a dismissible modal and preserve the applied promo`() = runBlocking {
        val directory = Files.createTempDirectory("noki-promo-dialog")
        val vault = WindowsVault(directory)
        val app = DesktopController(vault, SingBoxRuntime(vault), com.noki.vpn.data.BackendApiClient(baseUrl = "http://127.0.0.1:9"))
        withTimeout(5_000) { app.state.first { !it.loading } }
        @Suppress("UNCHECKED_CAST")
        val mutable = DesktopController::class.java.getDeclaredField("mutable").apply { isAccessible = true }
            .get(app) as kotlinx.coroutines.flow.MutableStateFlow<DesktopState>
        val account = mutable.value.copy(user = BackendUser("fixture", "Fixture", "fixture@example.invalid", null, true, false))
        mutable.value = account
        val scene = ImageComposeScene(1000, 720, density = Density(1f)) {
            val state by app.state.collectAsState()
            DesktopContent(state, app, initialPage = DesktopPage.PROFILE, earthVisible = false)
        }
        fun descendants(node: SemanticsNode): Sequence<SemanticsNode> = sequence {
            yield(node); for (child in node.children) yieldAll(descendants(child))
        }
        fun label(text: String) = scene.semanticsOwners.asSequence().flatMap { descendants(it.rootSemanticsNode) }.firstOrNull {
            it.config.getOrNull(SemanticsProperties.Text)?.any { label -> label.text == text } == true
        }
        fun render() { repeat(4) { scene.render().close() } }
        val days = com.noki.vpn.PaymentCheckoutState(
            promo = com.noki.vpn.data.BackendPromo("days", 7, "DAYS", null), promoMessage = "Добавлено дней: 7")
        val discount = com.noki.vpn.PaymentCheckoutState(
            promo = com.noki.vpn.data.BackendPromo("discount", 10, "DISCOUNT", null), promoPlanCode = "premium", promoMessage = "Скидка по промокоду 10%")
        try {
            for (checkout in listOf(days, discount, days, com.noki.vpn.PaymentCheckoutState(promoError = "Промокод недействителен"))) {
                mutable.value = account.copy(checkout = checkout)
                render()
                assertEquals(2, scene.semanticsOwners.size)
                assertNull(label("Сбросить"))
                val message = checkout.promoMessage ?: checkout.promoError
                assertNotNull(label(checkNotNull(message)))
                assertFalse(descendants(scene.semanticsOwners.first().rootSemanticsNode).any {
                    it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text == message } == true
                })
                val point = checkNotNull(label("Понятно")).boundsInRoot.center
                scene.sendPointerEvent(PointerEventType.Press, point, buttons = PointerButtons(isPrimaryPressed = true), button = PointerButton.Primary)
                scene.sendPointerEvent(PointerEventType.Release, point, buttons = PointerButtons(), button = PointerButton.Primary)
                render()
                assertEquals(1, scene.semanticsOwners.size)
                assertNull(label(message))
                assertNull(app.state.value.checkout.promoMessage)
                assertNull(app.state.value.checkout.promoError)
                assertEquals(checkout.promo, app.state.value.checkout.promo)
                assertEquals(checkout.promoPlanCode, app.state.value.checkout.promoPlanCode)
            }
        } finally {
            scene.close(); app.closeAndJoin(); app.close()
            Files.list(directory).use { it.forEach(Files::delete) }; Files.delete(directory)
        }
    }

    @OptIn(ExperimentalComposeUiApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun `connection clock ticks without recomposing its parent and stops with inactive UI`() {
        val scheduler = TestCoroutineScheduler()
        val dispatcher = StandardTestDispatcher(scheduler)
        var active by mutableStateOf(true)
        var nowMillis = 12_000L
        var clockReads = 0
        val clock = { clockReads++; nowMillis }
        var parentCompositions = 0
        val scene = ImageComposeScene(180, 50, density = Density(1f), coroutineContext = dispatcher) {
            SideEffect { parentCompositions++ }
            HomeConnectionDurationText(com.noki.vpn.data.VpnConnectionState.CONNECTED, 0L, active, clock)
        }
        fun text(): String {
            scheduler.runCurrent()
            scene.render().close()
            scheduler.runCurrent()
            scene.render().close()
            return scene.semanticsOwners.asSequence().flatMap { it.rootSemanticsNode.children.asSequence() }
                .flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty().asSequence() }.single().text
        }
        try {
            assertEquals("00:00:12", text())
            val initialCompositions = parentCompositions
            nowMillis += 1_000
            scheduler.advanceTimeBy(1_000)
            assertEquals("00:00:13", text())
            assertEquals(initialCompositions, parentCompositions)
            active = false
            assertEquals("--:--", text())
            val inactiveCompositions = parentCompositions
            val inactiveReads = clockReads
            nowMillis += 10_000
            scheduler.advanceTimeBy(10_000)
            assertEquals("--:--", text())
            assertEquals(inactiveCompositions, parentCompositions)
            assertEquals(inactiveReads, clockReads)
        } finally { scene.close(); scheduler.runCurrent() }
    }

    @OptIn(ExperimentalComposeUiApi::class)
    @Test fun `legacy window corners are transparent while caption and content stay opaque at display scales`() {
        for (density in listOf(1f, 1.25f, 1.5f, 2f)) {
            val width = (500 * density).toInt(); val height = (450 * density).toInt()
            val scene = ImageComposeScene(width, height, density = Density(density)) {
                Box(Modifier.fillMaxSize().clip(DesktopUiKit.WindowShape)) {
                    DesktopContent(DesktopState(), null)
                }
            }
            try {
                repeat(3) { scene.render().close() }
                scene.render().use { image ->
                    for ((x, y) in listOf(0 to 0, width - 1 to 0, 0 to height - 1, width - 1 to height - 1))
                        assertEquals("Only the corners must expose the desktop", 0, image.getRGB(x, y) ushr 24)
                    for ((x, y) in listOf(0 to height / 2, width - 1 to height / 2, width / 2 to 0, width / 2 to height - 1))
                        assertEquals("Window edges must remain opaque without a frame", 0xFF131313.toInt(), image.getRGB(x, y))
                }
            } finally { scene.close() }
        }
    }

    @OptIn(ExperimentalComposeUiApi::class)
    @Test fun `update button sits above avatar and starts download with progress and retry`() {
        val vault = WindowsVault(Files.createTempDirectory("noki-update-ui"))
        val app = DesktopController(vault, SingBoxRuntime(vault), com.noki.vpn.data.BackendApiClient(baseUrl = "http://127.0.0.1:9"))
        val state = DesktopState(user = BackendUser("fixture", "Fixture", "fixture@example.invalid", null, true, false))
        var update by mutableStateOf<DesktopUpdateUi?>(null)
        var downloads = 0
        val scene = ImageComposeScene(800, 600, density = Density(1f)) {
            CompositionLocalProvider(LocalDesktopUpdate provides update) {
                DesktopContent(state, app, initialPage = DesktopPage.PROFILE, earthVisible = false)
            }
        }
        fun descendants(node: SemanticsNode): Sequence<SemanticsNode> = sequence {
            yield(node); for (child in node.children) yieldAll(descendants(child))
        }
        fun nodes() = scene.semanticsOwners.asSequence().flatMap { descendants(it.rootSemanticsNode) }
        fun label(text: String) = nodes().firstOrNull {
            it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(text) == true ||
                it.config.getOrNull(SemanticsProperties.Text)?.any { label -> label.text == text } == true
        }
        fun render() { repeat(4) { scene.render().close() } }
        fun click(text: String) {
            val point = checkNotNull(label(text)).boundsInRoot.center
            scene.sendPointerEvent(PointerEventType.Press, point, buttons = PointerButtons(isPrimaryPressed = true), button = PointerButton.Primary)
            scene.sendPointerEvent(PointerEventType.Release, point, buttons = PointerButtons(), button = PointerButton.Primary)
            render()
        }
        fun status(stage: DesktopUpdateStage, percent: Int = 0) {
            update = DesktopUpdateUi(DesktopUpdateState("0.5.12", stage, percent)) { downloads++ }
            render()
        }
        try {
            render()
            assertNull(label("Установить обновление"))
            status(DesktopUpdateStage.AVAILABLE)
            val icon = checkNotNull(label("Установить обновление")).boundsInRoot
            val avatar = checkNotNull(label("F")).boundsInRoot
            assertTrue(icon.top > 0 && icon.bottom < avatar.top && avatar.bottom < 600)
            scene.render().use { image -> image.encodeToData(EncodedImageFormat.PNG)!!.use { data ->
                val painted = ImageIO.read(data.bytes.inputStream())
                val pixel = painted.getRGB(icon.center.x.toInt() + 15, icon.center.y.toInt())
                assertTrue((pixel shr 8 and 255) > (pixel shr 16 and 255) * 1.5)
            } }
            click("Установить обновление")
            assertEquals(1, downloads)
            assertNotNull(label("Обновление Noki Vpn"))
            status(DesktopUpdateStage.DOWNLOADING, 37)
            assertNotNull(label("Загрузка обновления · 37%"))
            status(DesktopUpdateStage.FAILED)
            click("Повторить")
            assertEquals(2, downloads)
        } finally { scene.close(); app.close() }
    }

    @OptIn(ExperimentalComposeUiApi::class)
    @Test fun `login matches the Figma split layout and opens code entry`() {
        val vault = WindowsVault(Files.createTempDirectory("noki-login-figma"))
        val app = DesktopController(vault, SingBoxRuntime(vault), com.noki.vpn.data.BackendApiClient(baseUrl = "http://127.0.0.1:9"))
        val scene = ImageComposeScene(1000, 650, density = Density(1f)) { DesktopContent(DesktopState(), app) }
        fun descendants(node: SemanticsNode): Sequence<SemanticsNode> = sequence {
            yield(node); for (child in node.children) yieldAll(descendants(child))
        }
        fun text(label: String) = scene.semanticsOwners.asSequence().flatMap { descendants(it.rootSemanticsNode) }
            .first { it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text == label } == true }
        try {
            repeat(4) { scene.render(it * 100_000_000L).close() }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            var sitePainted = false
            while (!sitePainted && System.nanoTime() < deadline) {
                scene.render().use { sitePainted = it.getRGB(820, 400) and 0xffffff != 0x131313 }
                if (!sitePainted) Thread.sleep(10)
            }
            assertTrue("The asynchronous site render must be painted", sitePainted)
            val logo = scene.semanticsOwners.asSequence().flatMap { descendants(it.rootSemanticsNode) }.first {
                it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("Noki") == true && it.boundsInRoot.width > 100
            }.boundsInRoot
            assertEquals(150f, logo.left, 1f); assertEquals(166f, logo.top, 1f)
            assertEquals(200f, logo.width, .1f); assertEquals(77f, logo.height, .1f)
            assertEquals(249.5f, text("Войти").boundsInRoot.center.x, 1f)
            assertEquals(328f, text("Войти").boundsInRoot.center.y, 2f)
            assertEquals(398f, text("Зарегистрироваться").boundsInRoot.center.y, 2f)
            fun layout(label: String): androidx.compose.ui.text.TextLayoutResult {
                val results = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
                assertTrue(text(label).config[androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult].action!!.invoke(results))
                return results.single()
            }
            assertEquals(15f, layout("Войти").layoutInput.style.fontSize.value, .01f)
            assertEquals(15f, layout("Зарегистрироваться").layoutInput.style.fontSize.value, .01f)
            scene.render().use { image -> image.encodeToData(EncodedImageFormat.PNG)!!.use { data ->
                Files.createDirectories(java.nio.file.Path.of("build/screenshots"))
                Files.write(java.nio.file.Path.of("build/screenshots/login-figma-1000x650.png"), data.bytes)
                val pixels = ImageIO.read(data.bytes.inputStream())
                assertEquals(0x131313, pixels.getRGB(480, 500) and 0xffffff)
                assertNotEquals(0x131313, pixels.getRGB(820, 400) and 0xffffff)
                val door = (500 until 1000).filter { x -> (80..630).count { y -> (pixels.getRGB(x, y) and 255) > 100 } > 2 }
                assertTrue("The illuminated door must be visible", door.isNotEmpty())
                assertEquals("The door must be centred in the right pane", 750f, (door.first() + door.last()) / 2f, 16f)
            } }
            fun checkPlainLink(label: String) {
                assertNotEquals(androidx.compose.ui.text.style.TextDecoration.Underline, layout(label).layoutInput.style.textDecoration)
                val bounds = text(label).boundsInRoot
                scene.sendPointerEvent(PointerEventType.Move, Offset.Zero)
                val before = scene.render().use { image -> image.encodeToData(EncodedImageFormat.PNG)!!.use { ImageIO.read(it.bytes.inputStream()) } }
                scene.sendPointerEvent(PointerEventType.Move, bounds.center)
                val after = scene.render().use { image -> image.encodeToData(EncodedImageFormat.PNG)!!.use { ImageIO.read(it.bytes.inputStream()) } }
                for (y in bounds.top.toInt() until bounds.bottom.toInt()) for (x in bounds.left.toInt() until bounds.right.toInt())
                    assertEquals("Login links must not paint a hover background", before.getRGB(x, y), after.getRGB(x, y))
            }
            checkPlainLink("Войти по коду")
            val point = text("Войти по коду").boundsInRoot.center
            scene.sendPointerEvent(PointerEventType.Press, point, buttons = PointerButtons(isPrimaryPressed = true), button = PointerButton.Primary)
            scene.sendPointerEvent(PointerEventType.Release, point, buttons = PointerButtons(), button = PointerButton.Primary)
            scene.render(600_000_000L).close()
            assertNotNull(text("Код приглашения"))
            assertNotNull(text("Назад"))
            checkPlainLink("Назад")
        } finally { scene.close(); app.close() }
    }

    @OptIn(ExperimentalComposeUiApi::class)
    @Test fun `invited devices never display the owners email on account screens`() {
        val vault = WindowsVault(Files.createTempDirectory("noki-invited-email"))
        val app = DesktopController(vault, SingBoxRuntime(vault), com.noki.vpn.data.BackendApiClient(baseUrl = "http://127.0.0.1:9"))
        val ownerEmail = "private-owner@example.invalid"
        fun texts(node: SemanticsNode): List<String> = node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } + node.children.flatMap(::texts)
        try {
            for (role in listOf("owner", "invited")) for (page in listOf(DesktopPage.SECURITY, DesktopPage.PROFILE, DesktopPage.PERSONALIZATION)) {
                val state = DesktopState(currentDeviceRole = role, user = BackendUser("fixture", "Fixture", ownerEmail, null, true, false))
                val scene = ImageComposeScene(1050, 674) { DesktopContent(state, app, initialPage = page, earthVisible = false) }
                try {
                    repeat(3) { scene.render().close() }
                    val labels = scene.semanticsOwners.flatMap { texts(it.rootSemanticsNode) }
                    if (role == "owner") assertTrue("Owner email must remain visible on $page", ownerEmail in labels)
                    else {
                        assertTrue("Invited role must be shown on $page", "Приглашённое устройство" in labels)
                        assertFalse("Owner identity must not leak on $page", labels.any { it.contains("private-owner") || it.contains("example.invalid") })
                    }
                } finally { scene.close() }
            }
        } finally { app.close() }
    }

    @OptIn(ExperimentalComposeUiApi::class)
    @Test fun `compact tray menu fits all three actions and dispatches them`() {
        var opened = 0
        var toggled = 0
        var exited = 0
        var state by mutableStateOf(DesktopState(running = true,
            user = BackendUser("fixture", "Fixture", "fixture@example.invalid", null, true, false)))
        val scene = ImageComposeScene(190, 120, density = Density(1f)) {
            DesktopTheme(null) { DesktopTrayMenu(state, { opened++ }, { toggled++ }, { exited++ }) }
        }
        try {
            repeat(3) { scene.render(it * 100_000_000L).close() }
            fun textNodes(node: SemanticsNode): List<SemanticsNode> =
                if (node.config.getOrNull(SemanticsProperties.Text) != null) listOf(node) else node.children.flatMap(::textNodes)
            fun labels() = scene.semanticsOwners.flatMap { textNodes(it.rootSemanticsNode) }
                .map { it.config[SemanticsProperties.Text].single().text }
            fun click(y: Float) {
                val point = Offset(95f, y)
                scene.sendPointerEvent(PointerEventType.Press, point, buttons = PointerButtons(isPrimaryPressed = true), button = PointerButton.Primary)
                scene.sendPointerEvent(PointerEventType.Release, point, buttons = PointerButtons(), button = PointerButton.Primary)
            }
            assertEquals(listOf("Открыть Noki Vpn", "Выключить VPN", "Выйти"), labels())
            click(25f); click(60f); click(95f)
            assertEquals(1, opened)
            assertEquals(1, toggled)
            assertEquals(1, exited)
            scene.sendPointerEvent(PointerEventType.Move, Offset(95f, 25f))
            scene.render(500_000_000L).use { image -> image.encodeToData(EncodedImageFormat.PNG)!!.use { data ->
                val painted = ImageIO.read(data.bytes.inputStream())
                assertEquals(0xFF272727.toInt(), painted.getRGB(5, 95))
                assertEquals(0xFF3C3C3C.toInt(), painted.getRGB(5, 25))
                assertEquals(0xFF272727.toInt(), painted.getRGB(5, 5))
                Files.createDirectories(java.nio.file.Path.of("build/screenshots"))
                Files.write(java.nio.file.Path.of("build/screenshots/tray-menu.png"), data.bytes)
            } }
            for (node in scene.semanticsOwners.flatMap { textNodes(it.rootSemanticsNode) }) {
                val results = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
                assertTrue(node.config[androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult].action!!.invoke(results))
                val layout = results.single()
                assertEquals(14f, layout.layoutInput.style.fontSize.value, .01f)
                assertFalse("Tray labels must fit without truncation", layout.isLineEllipsized(0))
                assertTrue("Tray text must stay within its bounds", layout.getLineRight(0) <= layout.size.width)
            }
            for (next in listOf(state.copy(running = false), DesktopState(), state.copy(connecting = true, running = false))) {
                state = next
                scene.render().close()
            assertEquals(listOf("Открыть Noki Vpn", if (next.connecting) "Выключить VPN" else "Включить VPN", "Выйти"), labels())
                if (next.user == null) { click(60f); assertEquals(1, toggled) }
                click(95f)
            }
            assertEquals(4, exited)
        } finally { scene.close() }
    }

    @Test fun `large avatar is rejected before conversion with a localized size error`() {
        val file = Files.createTempFile("noki-large-avatar", ".png")
        val source = java.awt.image.BufferedImage(1800, 1600, java.awt.image.BufferedImage.TYPE_INT_RGB)
        try {
            val random = java.util.Random(42)
            for (y in 0 until source.height) for (x in 0 until source.width) source.setRGB(x, y, random.nextInt())
            assertTrue(ImageIO.write(source, "png", file.toFile()))
            assertTrue(Files.size(file) > 5_000_000)
            val original = Files.readAllBytes(file)
            val error = runCatching { loadAvatarImage(file) }.exceptionOrNull()
            assertTrue(error is IllegalArgumentException)
            assertEquals("avatar_file_too_large", error?.message)
            assertEquals("Размер аватара не должен превышать 5 МБ", avatarFileError(AppLanguage.RU, checkNotNull(error)))
            assertEquals("Avatar must be 5 MB or smaller", avatarFileError(AppLanguage.EN, error))
            assertArrayEquals(original, Files.readAllBytes(file))
        } finally { source.flush(); Files.deleteIfExists(file) }
    }

    @OptIn(ExperimentalComposeUiApi::class)
    @Test fun `home country routing and power share a visible hover above their glass`() {
        val vault = WindowsVault(Files.createTempDirectory("noki-home-hover"))
        val app = DesktopController(vault, SingBoxRuntime(vault), com.noki.vpn.data.BackendApiClient(baseUrl = "http://127.0.0.1:9"))
        val state = DesktopState(user = BackendUser("preview", "Preview", "preview@example.invalid", null, true, false))
        val scene = ImageComposeScene(1050, 650, density = Density(1f)) {
            DesktopTheme(null) { DesktopWorkspace(state, app, DesktopPage.HOME, earthVisible = false) }
        }
        try {
            var time = 0L
            fun render() = scene.render(time.also { time += 100_000_000L }).use { image ->
                image.encodeToData(EncodedImageFormat.PNG)!!.use { ImageIO.read(it.bytes.inputStream()) }
            }
            repeat(3) { render() }
            for ((name, position) in listOf("country" to Offset(400f, 130f), "routing" to Offset(400f, 430f), "power" to Offset(145f, 545f))) {
                scene.sendPointerEvent(PointerEventType.Move, Offset(900f, 600f)); render()
                val before = render()
                scene.sendPointerEvent(PointerEventType.Move, position); render()
                val hovered = render()
                val x = position.x.toInt(); val y = position.y.toInt()
                val lift = (y - 4..y + 4).sumOf { row -> (x - 4..x + 4).sumOf { col ->
                    (hovered.getRGB(col, row) and 255) - (before.getRGB(col, row) and 255)
                } } / 81.0
                assertTrue("$name hover must be visible above the glass: $lift", lift > 3)
                Files.createDirectories(java.nio.file.Path.of("build/screenshots/home-hover"))
                ImageIO.write(hovered, "png", java.io.File("build/screenshots/home-hover/$name.png"))
            }
        } finally { scene.close(); app.close() }
    }

    @OptIn(ExperimentalComposeUiApi::class)
    @Test fun `near minimum size both background choices leave only the plain canvas`() {
        val vault = WindowsVault(Files.createTempDirectory("noki-compact-canvas"))
        val app = DesktopController(vault, SingBoxRuntime(vault), com.noki.vpn.data.BackendApiClient(baseUrl = "http://127.0.0.1:9"))
            var disabled by mutableStateOf(false)
            val scene = ImageComposeScene(600, 500, density = Density(1f)) {
                DesktopTheme(null) { DesktopWorkspace(DesktopState(earthDisabled = disabled,
                    user = BackendUser("preview", "Preview", "preview@example.invalid", null, true, false)), app, DesktopPage.HOME) }
            }
            try {
                for (choice in listOf(false, true)) {
                    disabled = choice
                    repeat(3) { scene.render().close() }
                    val image = scene.render().use { it.encodeToData(EncodedImageFormat.PNG)!!.use { data -> ImageIO.read(data.bytes.inputStream()) } }
                    assertEquals("Compact mode must contain no Earth or site render", 0x131313, image.getRGB(550, 250) and 0xffffff)
                    fun hasEarth(node: SemanticsNode): Boolean = node.config.getOrNull(SemanticsProperties.ContentDescription)
                        .orEmpty().any { "3D Earth" in it || "3D-Земля" in it } || node.children.any(::hasEarth)
                    assertFalse("Compact mode must not create an Earth viewport", scene.semanticsOwners.any { hasEarth(it.rootSemanticsNode) })
                }
            } finally { scene.close() }
        app.close()
    }

    @OptIn(ExperimentalComposeUiApi::class)
    @Test fun `country destination follows live multihop state and returns to countries after disable`() {
        val directory = Files.createTempDirectory("noki-route-navigation")
        val vault = WindowsVault(directory)
        val app = DesktopController(vault, SingBoxRuntime(vault), com.noki.vpn.data.BackendApiClient(baseUrl = "http://127.0.0.1:9"))
        val locations = listOf("LV" to "Латвия", "RO" to "Romania").map { (code, label) ->
            ServerLocation(code, code, label, "", "example.invalid", isOnline = true,
                servers = listOf(VpnServer(code, "$label 1", code, code, "example.invalid", null, true)))
        }
        val entry = com.noki.vpn.data.HopSelection(ServerSelectionMode.COUNTRY, "LV")
        val exit = com.noki.vpn.data.HopSelection(ServerSelectionMode.COUNTRY, "RO")
        var state by mutableStateOf(DesktopState(user = BackendUser("preview", "Preview", "preview@example.invalid", null, true, false),
            subscription = BackendSubscription("active", "plus", null, 0.0, null, planTier = "plus"), locations = locations))
        val scene = ImageComposeScene(1000, 650, density = Density(1f)) {
            DesktopTheme(null) { DesktopWorkspace(state, app, DesktopPage.SERVERS, earthVisible = false) }
        }
        try {
            fun nodes(node: SemanticsNode): Sequence<SemanticsNode> = sequence { yield(node); for (child in node.children) yieldAll(nodes(child)) }
            fun nodes() = scene.semanticsOwners.asSequence().flatMap { nodes(it.rootSemanticsNode) }
            fun texts(): List<String> {
                return nodes()
                    .flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty().asSequence().map { text -> text.text } }.toList()
            }
            repeat(3) { scene.render(it * 100_000_000L).close() }
            assertTrue(texts().contains("Локации"))
            val refresh = nodes().first { it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("Обновить список серверов") == true }.boundsInRoot
            val heading = nodes().first { it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text == "Локации" } == true }.boundsInRoot
            assertTrue("Refresh must fit to the right of the location heading", refresh.left >= heading.right && refresh.right <= 448f)
            assertEquals(heading.center.y, refresh.center.y, 1f)
            scene.render().use { image -> image.encodeToData(EncodedImageFormat.PNG)!!.use { data ->
                Files.createDirectories(java.nio.file.Path.of("build/screenshots"))
                Files.write(java.nio.file.Path.of("build/screenshots/servers-refresh.png"), data.bytes)
            } }
            state = state.copy(settings = state.settings.copy(advancedSettings = state.settings.advancedSettings.copy(
                multiHop = state.settings.advancedSettings.multiHop.copy(enabled = true, entry = entry, exit = exit))))
            repeat(3) { scene.render((it + 3) * 100_000_000L).close() }
            assertTrue("Delayed settings publication must switch the country destination to the enabled route", texts().contains("Входная локация") && texts().contains("Выбрать другой маршрут"))
            state = state.copy(settings = state.settings.copy(advancedSettings = state.settings.advancedSettings.copy(
                multiHop = state.settings.advancedSettings.multiHop.copy(enabled = false))))
            repeat(3) { scene.render((it + 6) * 100_000_000L).close() }
            assertTrue("Disabling MultiHop must restore the country catalog, not the entry picker", texts().contains("Локации"))
            assertFalse(texts().contains("Входная локация"))
        } finally { scene.close(); app.close() }
    }

    @OptIn(ExperimentalComposeUiApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun `server refresh shows pending and brief success but no false success after failure`() {
        val scheduler = TestCoroutineScheduler()
        var reply = kotlinx.coroutines.CompletableDeferred<Boolean>()
        var calls = 0
        val scene = ImageComposeScene(80, 80, density = Density(1f), coroutineContext = StandardTestDispatcher(scheduler)) {
            DesktopTheme(null) { ServerRefreshButton(DesktopState(), true) { calls++; reply.await() } }
        }
        fun nodes(node: SemanticsNode): Sequence<SemanticsNode> = sequence { yield(node); for (child in node.children) yieldAll(nodes(child)) }
        fun nodes() = scene.semanticsOwners.asSequence().flatMap { nodes(it.rootSemanticsNode) }
        fun button() = nodes().first { it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("Обновить список серверов") == true }
        fun render() { repeat(3) { scheduler.runCurrent(); scene.render(scheduler.currentTime * 1_000_000).close() }; scheduler.runCurrent() }
        fun click() {
            val point = button().boundsInRoot.center
            scene.sendPointerEvent(PointerEventType.Press, point, buttons = PointerButtons(isPrimaryPressed = true), button = PointerButton.Primary)
            scene.sendPointerEvent(PointerEventType.Release, point, buttons = PointerButtons(), button = PointerButton.Primary)
            render()
        }
        fun capture(name: String) {
            scene.render(scheduler.currentTime * 1_000_000).use { image -> image.encodeToData(EncodedImageFormat.PNG)!!.use { data ->
                Files.createDirectories(java.nio.file.Path.of("build/screenshots"))
                Files.write(java.nio.file.Path.of("build/screenshots/$name.png"), data.bytes)
            } }
        }
        try {
            render(); click()
            assertEquals("Обновляем список серверов", button().config[SemanticsProperties.StateDescription])
            assertTrue(nodes().any { it.config.getOrNull(SemanticsProperties.ProgressBarRangeInfo) != null })
            scheduler.advanceTimeBy(200); render(); capture("server-refresh-pending")
            click(); assertEquals(1, calls)
            reply.complete(true); render()
            assertEquals("Список серверов обновлён", button().config[SemanticsProperties.StateDescription])
            scheduler.advanceTimeBy(200); render(); capture("server-refresh-complete")
            scheduler.advanceTimeBy(1_200); render()
            assertEquals("Готово к обновлению", button().config[SemanticsProperties.StateDescription])
            reply = kotlinx.coroutines.CompletableDeferred()
            click(); reply.complete(false); render()
            assertEquals(2, calls)
            assertEquals("Готово к обновлению", button().config[SemanticsProperties.StateDescription])
        } finally { scene.close(); scheduler.runCurrent() }
    }

    @OptIn(ExperimentalComposeUiApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun `connection feedback waits for verification and stops when the UI is hidden`() {
        val vault = WindowsVault(Files.createTempDirectory("noki-connection-feedback"))
        val app = DesktopController(vault, SingBoxRuntime(vault), com.noki.vpn.data.BackendApiClient(baseUrl = "http://127.0.0.1:9"))
        val scheduler = TestCoroutineScheduler()
        var state by mutableStateOf(DesktopState(earthDisabled = true, user = BackendUser("preview", "Preview", "preview@example.invalid", null, true, false)))
        var active by mutableStateOf(true)
        var reducedMotion by mutableStateOf(false)
        val scene = ImageComposeScene(1000, 650, density = Density(1f), coroutineContext = StandardTestDispatcher(scheduler)) {
            DesktopTheme(null) { DesktopWorkspace(state, app, DesktopPage.HOME, earthVisible = active, reducedMotion = reducedMotion) }
        }
        fun nodes(node: SemanticsNode): Sequence<SemanticsNode> = sequence { yield(node); for (child in node.children) yieldAll(nodes(child)) }
        fun nodes() = scene.semanticsOwners.asSequence().flatMap { nodes(it.rootSemanticsNode) }
        fun button() = nodes().first { node -> node.config.getOrNull(SemanticsProperties.ContentDescription)?.any { it == "Подключить VPN" || it == "Отключить VPN" } == true }
        fun render() { repeat(3) { scheduler.runCurrent(); scene.render(scheduler.currentTime * 1_000_000).close() }; scheduler.runCurrent() }
        fun hasProgress() = nodes().any { it.config.getOrNull(SemanticsProperties.ProgressBarRangeInfo) != null }
        try {
            render()
            scene.render().use { frame -> frame.encodeToData(EncodedImageFormat.PNG)!!.use { data ->
                val image = ImageIO.read(data.bytes.inputStream())
                val bounds = button().boundsInRoot
                val glyph = (bounds.top.toInt() until bounds.bottom.toInt()).flatMap { y ->
                    (bounds.left.toInt() until bounds.right.toInt()).mapNotNull { x ->
                        val rgb = image.getRGB(x, y)
                        if ((rgb shr 16 and 255) > 235 && (rgb shr 8 and 255) > 235 && (rgb and 255) > 235) x to y else null
                    }
                }
                assertTrue(glyph.isNotEmpty())
                assertEquals(bounds.center.x.toDouble(), (glyph.minOf { it.first } + glyph.maxOf { it.first } + 1) / 2.0, 1.0)
                assertEquals(bounds.center.y.toDouble(), (glyph.minOf { it.second } + glyph.maxOf { it.second } + 1) / 2.0, 1.0)
                Files.createDirectories(java.nio.file.Path.of("build/screenshots"))
                Files.write(java.nio.file.Path.of("build/screenshots/power-centered.png"), data.bytes)
            } }
            state = state.copy(loading = true, connecting = true); render()
            assertEquals("Подключаемся", button().config[SemanticsProperties.StateDescription])
            assertNull("Connection cancellation must remain available", button().config.getOrNull(SemanticsProperties.Disabled))
            assertTrue(hasProgress())
            reducedMotion = true; render()
            assertEquals(.75f, nodes().first { it.config.getOrNull(SemanticsProperties.ProgressBarRangeInfo) != null }
                .config[SemanticsProperties.ProgressBarRangeInfo].current, .001f)
            active = false; render()
            assertFalse(hasProgress())
            active = true; state = state.copy(loading = false, connecting = false, running = true); render()
            assertEquals("Готово", button().config[SemanticsProperties.StateDescription])
            state = state.copy(verified = true); render()
            assertEquals("VPN подключён", button().config[SemanticsProperties.StateDescription])
            scheduler.advanceTimeBy(1_200); render()
            assertEquals("Готово", button().config[SemanticsProperties.StateDescription])
            assertFalse(hasProgress())
        } finally { scene.close(); scheduler.runCurrent(); app.close() }
    }

    @OptIn(ExperimentalComposeUiApi::class)
    @Test fun `caption close hover reaches the window edge and keeps a white glyph`() {
        var closed = false
        val scene = ImageComposeScene(360, 80, density = Density(1f)) {
            DesktopTheme(null) { PcTitlebar(onClose = { closed = true }) }
        }
        try {
            scene.render(0).close()
            scene.sendPointerEvent(PointerEventType.Move, Offset(338f, 20f))
            scene.render(100_000_000L).close()
            scene.render(200_000_000L).use { frame ->
                frame.encodeToData(EncodedImageFormat.PNG)!!.use { encoded ->
                    val image = ImageIO.read(encoded.bytes.inputStream())
                    Files.createDirectories(java.nio.file.Path.of("build/screenshots"))
                    Files.write(java.nio.file.Path.of("build/screenshots/caption-close-hover.png"), encoded.bytes)
                    assertEquals("Close hover must fill the right edge", 0xFFE5484D.toInt(), image.getRGB(359, 20))
                    assertEquals("Close hover must fill the top edge", 0xFFE5484D.toInt(), image.getRGB(340, 0))
                    assertTrue("White close glyph must remain above the red background", (334..343).any { x ->
                        (15..24).any { y -> val rgb = image.getRGB(x, y); ((rgb shr 8) and 255) > 160 && (rgb and 255) > 160 }
                    })
                }
            }
            scene.sendPointerEvent(PointerEventType.Press, Offset(338f, 20f), buttons = PointerButtons(isPrimaryPressed = true), button = PointerButton.Primary)
            scene.sendPointerEvent(PointerEventType.Release, Offset(338f, 20f), buttons = PointerButtons(), button = PointerButton.Primary)
            scene.render(300_000_000L).close()
            assertTrue(closed)
        } finally { scene.close() }
    }

    @OptIn(ExperimentalComposeUiApi::class)
    @Test fun `compact navigation scrolls with an ordinary wheel and retains clicks`() {
        var selected = -1
        var lastBounds = androidx.compose.ui.geometry.Rect.Zero
        val scene = ImageComposeScene(300, 100, density = Density(1f)) {
            DesktopTheme(null) {
                Row(Modifier.fillMaxWidth().pcHorizontalScroll(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    repeat(6) { index -> PcButton("Раздел $index", Modifier.width(130.dp)
                        .then(if (index == 5) Modifier.onGloballyPositioned {
                            val position = it.positionInRoot()
                            lastBounds = androidx.compose.ui.geometry.Rect(position.x, position.y, position.x + it.size.width, position.y + it.size.height)
                        } else Modifier),
                        compact = true, onClick = { selected = index }) }
                }
            }
        }
        try {
            scene.render(0).close()
            val before = lastBounds.left
            scene.sendPointerEvent(PointerEventType.Scroll, Offset(100f, 20f), scrollDelta = Offset(0f, 20f))
            repeat(3) { scene.render((it + 1) * 100_000_000L).close() }
            assertTrue("Vertical wheel must reveal the final horizontal tab: before=$before after=$lastBounds", lastBounds.left < before - 300f)
            assertTrue(lastBounds.right <= 300f)
            scene.sendPointerEvent(PointerEventType.Press, lastBounds.center, buttons = PointerButtons(isPrimaryPressed = true), button = PointerButton.Primary)
            scene.sendPointerEvent(PointerEventType.Release, lastBounds.center, buttons = PointerButtons(), button = PointerButton.Primary)
            scene.render(500_000_000L).close()
            assertEquals(5, selected)
        } finally { scene.close() }
    }

    @OptIn(ExperimentalComposeUiApi::class)
    @Test fun `avatar preview follows small mouse movement and agrees with saved crop at both densities`() {
        val sourcePixels = java.awt.image.BufferedImage(320, 240, java.awt.image.BufferedImage.TYPE_INT_RGB)
        for (y in 0 until sourcePixels.height) for (x in 0 until sourcePixels.width) sourcePixels.setRGB(x, y,
            (x * 255 / 319 shl 16) or (y * 255 / 239 shl 8) or 80)
        fun descendants(node: SemanticsNode): Sequence<SemanticsNode> = sequence {
            yield(node); for (child in node.children) yieldAll(descendants(child))
        }
        for (density in listOf(1f, 2f)) {
            val source = sourcePixels.toSkiaTestImage()
            var saved: com.noki.vpn.AvatarCropRequest? = null
            val scene = ImageComposeScene((600 * density).toInt(), (650 * density).toInt(), density = Density(density)) {
                DesktopTheme(null) { AvatarCropDialog(DesktopState(), java.nio.file.Path.of("avatar-preview.png"), source, {}, { saved = it }) }
            }
            try {
                repeat(3) { scene.render(it * 100_000_000L).close() }
                fun node(label: String) = scene.semanticsOwners.asSequence().flatMap { descendants(it.rootSemanticsNode) }.first {
                    it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(label) == true ||
                        it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text == label } == true
                }
                if (density == 2f) {
                    val rotate = node("Повернуть вправо").boundsInRoot.center
                    scene.sendPointerEvent(PointerEventType.Press, rotate, buttons = PointerButtons(isPrimaryPressed = true), button = PointerButton.Primary)
                    scene.sendPointerEvent(PointerEventType.Release, rotate, buttons = PointerButtons(), button = PointerButton.Primary)
                    scene.render(300_000_000L).close()
                }
                val cropBounds = node("Переместить аватар").boundsInRoot
                val start = cropBounds.center
                val delta = if (density == 2f) Offset(0f, 12 * density) else Offset(12 * density, 0f)
                scene.sendPointerEvent(PointerEventType.Press, start, buttons = PointerButtons(isPrimaryPressed = true), button = PointerButton.Primary)
                for (step in 1..4) scene.sendPointerEvent(PointerEventType.Move, start + delta * (step / 4f), buttons = PointerButtons(isPrimaryPressed = true))
                scene.render(400_000_000L).close()
                scene.sendPointerEvent(PointerEventType.Release, start + delta, buttons = PointerButtons(), button = PointerButton.Primary)
                scene.render(500_000_000L).close()
                val save = node("Сохранить").boundsInRoot.center
                scene.sendPointerEvent(PointerEventType.Press, save, buttons = PointerButtons(isPrimaryPressed = true), button = PointerButton.Primary)
                scene.sendPointerEvent(PointerEventType.Release, save, buttons = PointerButtons(), button = PointerButton.Primary)
                scene.render(600_000_000L).close()
                val request = requireNotNull(saved)
                assertEquals("Small mouse movement must respond without touch slop", delta.x / density, request.cropOffsetX, .1f)
                assertEquals(delta.y / density, request.cropOffsetY, .1f)
                assertEquals(if (density == 2f) 1 else 0, request.rotationQuarterTurns)
                val exported = croppedAvatar(source, request)
                scene.render(700_000_000L).use { preview ->
                    preview.encodeToData(EncodedImageFormat.PNG)!!.use { encoded ->
                        val painted = ImageIO.read(encoded.bytes.inputStream())
                        val actual = painted.getRGB((cropBounds.left + 80 * density).toInt(), (cropBounds.top + 90 * density).toInt())
                        val expected = exported.getRGB(80 * 512 / 200, 90 * 512 / 200)
                        Files.createDirectories(java.nio.file.Path.of("build/screenshots"))
                        Files.write(java.nio.file.Path.of("build/screenshots/avatar-crop-${density.toInt()}x.png"), encoded.bytes)
                        for (shift in listOf(0, 8, 16)) assertTrue("Preview and export share the Android image matrix: density=$density bounds=$cropBounds actual=${Integer.toHexString(actual)} expected=${Integer.toHexString(expected)}",
                            kotlin.math.abs(((expected shr shift) and 255) - ((actual shr shift) and 255)) <= 2)
                    }
                }
            } finally { scene.close() }
        }
    }

    @OptIn(ExperimentalComposeUiApi::class)
    @Test fun `explicit text line height does not crop wrapped titles or subtitles`() {
        val scene = ImageComposeScene(240, 160, density = Density(1f)) {
            DesktopTheme(null) { PcText("Первая строка\nВторая строка", Modifier.width(200.dp), lineHeight = 20) }
        }
        try {
            scene.render(0).close()
            fun textNode(node: SemanticsNode): SemanticsNode? =
                if (node.config.getOrNull(SemanticsProperties.Text)?.any { it.text.contains("Первая") } == true) node
                else node.children.firstNotNullOfOrNull(::textNode)
            val node = scene.semanticsOwners.firstNotNullOf { textNode(it.rootSemanticsNode) }
            assertTrue("Both lines need their own line box", node.boundsInRoot.height >= 40f)
        } finally { scene.close() }
    }

    @OptIn(ExperimentalComposeUiApi::class)
    @Test fun `extra devices reuse management modal and never offer creation`() {
        val vault = WindowsVault(Files.createTempDirectory("noki-device-pools-ui"))
        val app = DesktopController(vault, SingBoxRuntime(vault), com.noki.vpn.data.BackendApiClient(baseUrl = "http://127.0.0.1:9"))
        val state = DesktopState(earthDisabled = true,
            user = BackendUser("preview", "Preview", "preview@example.invalid", null, true, false), currentDeviceId = "current",
            subscription = BackendSubscription("active", "preview", null, 0.0, null, primaryDeviceLimit = 3,
                sharedTrafficUsedGb = 1.5, sharedTrafficLimitGb = 25.0),
            devices = listOf("current" to "shared", "Primary PC" to "primary", "Extra PC" to "shared").map { (id, pool) ->
                com.noki.vpn.data.BackendDevice(id, "fixture-key", id, platform = "windows", accessRole = "owner", isActive = true, lastSeenAt = null, trafficPool = pool)
            }, incyDevices = listOf(com.noki.vpn.data.BackendIncyDevice("primary-incy", "Primary INCY"),
                com.noki.vpn.data.BackendIncyDevice("extra-incy", "Extra INCY", trafficPool = "shared")))
        val scene = ImageComposeScene(1100, 1000, density = Density(1f)) {
            DesktopTheme(null) { DesktopWorkspace(state, app, DesktopPage.DEVICES, earthVisible = false) }
        }
        fun descendants(node: SemanticsNode): Sequence<SemanticsNode> = sequence { yield(node); for (child in node.children) yieldAll(descendants(child)) }
        fun nodes() = scene.semanticsOwners.asSequence().flatMap { descendants(it.rootSemanticsNode) }
        fun texts() = nodes().flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty().asSequence() }.map { it.text }.toList()
        fun render() { repeat(4) { scene.render(it * 100_000_000L).close() } }
        fun click(label: String) {
            val point = nodes().first { it.config.getOrNull(SemanticsProperties.Text)?.any { it.text == label } == true }.boundsInRoot.center
            scene.sendPointerEvent(PointerEventType.Press, point, buttons = PointerButtons(isPrimaryPressed = true), button = PointerButton.Primary)
            scene.sendPointerEvent(PointerEventType.Release, point, buttons = PointerButtons(), button = PointerButton.Primary)
            render()
        }
        try {
            render()
            assertTrue(texts().containsAll(listOf("current", "Primary PC", "Primary INCY", "Noki устройства", "Подключено 2 из 3 устройств")))
            assertFalse(texts().contains("Extra PC"))
            assertFalse(texts().contains("Extra INCY"))
            assertFalse(texts().contains("В основные устройства"))
            click("current")
            assertTrue(texts().contains("В основные устройства"))
            click("Закрыть")
            click("Доп. устройства")
            assertTrue(texts().containsAll(listOf("current", "Extra PC", "Extra INCY", "Общий трафик", "1,5 ГБ / 25 ГБ", "Подключено устройств: 3")))
            assertFalse(texts().contains("Primary PC"))
            assertFalse(texts().contains("Primary INCY"))
            assertFalse(texts().any { it == "Подключить устройство" || it == "Создать устройство incy" || it == "В основные устройства" })
            scene.render().use { it.encodeToData(EncodedImageFormat.PNG)!!.use { data ->
                Files.createDirectories(java.nio.file.Path.of("build/screenshots"))
                Files.write(java.nio.file.Path.of("build/screenshots/extra-devices.png"), data.bytes)
            } }
            click("Extra INCY")
            assertTrue(texts().contains("В основные устройства"))
        } finally { scene.close(); app.close() }
    }

    @OptIn(ExperimentalComposeUiApi::class)
    @Test fun `today chart exposes hourly samples and week exposes daily samples`() {
        val vault = WindowsVault(Files.createTempDirectory("noki-hourly-chart-ui"))
        val app = DesktopController(vault, SingBoxRuntime(vault), com.noki.vpn.data.BackendApiClient(baseUrl = "http://127.0.0.1:9"))
        val today = java.time.LocalDate.now()
        val state = DesktopState(earthDisabled = true, user = BackendUser("preview", "Preview", "preview@example.invalid", null, true, false),
            dailyStats = listOf(com.noki.vpn.data.DailyStats(today.toString(), rxBytes = 24_000_000, txBytes = 2_400_000,
                hourlyRxBytes = List(24) { (it + 1L) * 80_000 }, hourlyTxBytes = List(24) { (24L - it) * 8_000 }),
                com.noki.vpn.data.DailyStats(today.minusDays(1).toString(), rxBytes = 5_000_000, txBytes = 3_000_000)))
        val scene = ImageComposeScene(1100, 850, density = Density(1f)) {
            DesktopTheme(null) { DesktopWorkspace(state, app, DesktopPage.STATS, earthVisible = false) }
        }
        fun descendants(node: SemanticsNode): Sequence<SemanticsNode> = sequence { yield(node); for (child in node.children) yieldAll(descendants(child)) }
        fun nodes() = scene.semanticsOwners.asSequence().flatMap { descendants(it.rootSemanticsNode) }
        fun render() { repeat(4) { scene.render(it * 100_000_000L).close() } }
        fun click(node: SemanticsNode) {
            val point = node.boundsInRoot.center
            scene.sendPointerEvent(PointerEventType.Press, point, buttons = PointerButtons(isPrimaryPressed = true), button = PointerButton.Primary)
            scene.sendPointerEvent(PointerEventType.Release, point, buttons = PointerButtons(), button = PointerButton.Primary)
            render()
        }
        fun textNode(text: String) = nodes().first { it.config.getOrNull(SemanticsProperties.Text)?.any { value -> value.text == text } == true }
        fun assertTraffic(rx: Long, tx: Long) {
            textNode("↓ " + com.noki.vpn.data.TrafficFormat.bytes(rx, AppLanguage.RU).label)
            textNode("↑ " + com.noki.vpn.data.TrafficFormat.bytes(tx, AppLanguage.RU).label)
            textNode(com.noki.vpn.data.TrafficFormat.bytes(rx + tx, AppLanguage.RU).label)
        }
        try {
            render()
            assertEquals(7, nodes().count { it.config.getOrNull(SemanticsProperties.ContentDescription)?.any { value -> value.contains(": ") } == true })
            click(textNode("Месяц"))
            val day = today.format(java.time.format.DateTimeFormatter.ofPattern("d MMM", java.util.Locale.forLanguageTag("ru")))
            click(nodes().first { it.config.getOrNull(SemanticsProperties.ContentDescription)?.any { value -> value.startsWith("$day: ") } == true })
            assertTraffic(24_000_000, 2_400_000)
            click(textNode("Весь период"))
            val totals = com.noki.vpn.ui.aggregateDailyStats(state.dailyStats, com.noki.vpn.ui.StatsPeriod.MONTH, today)
            assertTraffic(totals.rxBytes, totals.txBytes)
            click(textNode("Сегодня"))
            val descriptions = nodes().flatMap { it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().asSequence() }.toList()
            assertEquals(24, descriptions.count { it.matches(Regex("\\d{2}:00: .*")) })
            assertTrue(descriptions.any { it.startsWith("00:00: ") })
            assertTrue(descriptions.any { it.startsWith("23:00: ") })
            click(nodes().first { it.config.getOrNull(SemanticsProperties.ContentDescription)?.any { value -> value.startsWith("13:00: ") } == true })
            assertTraffic(1_120_000, 88_000)
            click(textNode("Весь период"))
            assertTraffic(24_000_000, 2_400_000)
            scene.render().use { it.encodeToData(EncodedImageFormat.PNG)!!.use { data ->
                Files.createDirectories(java.nio.file.Path.of("build/screenshots"))
                Files.write(java.nio.file.Path.of("build/screenshots/statistics-hourly.png"), data.bytes)
            } }
        } finally { scene.close(); app.close() }
    }

    @Test fun `traffic chart buckets match each selected period and exclude future data`() {
        val today = java.time.LocalDate.of(2026, 10, 12)
        val stats = listOf(
            com.noki.vpn.data.DailyStats(date = "2026-09-30", rxBytes = 30, txBytes = 10),
            com.noki.vpn.data.DailyStats(date = "2026-10-11", rxBytes = 20, txBytes = 5),
            com.noki.vpn.data.DailyStats(date = "2026-10-12", rxBytes = 100, txBytes = 50,
                hourlyRxBytes = List(24) { if (it == 13) 100L else 0L }, hourlyTxBytes = List(24) { if (it == 13) 50L else 0L }),
            com.noki.vpn.data.DailyStats(date = "2026-10-13", rxBytes = 900, txBytes = 900),
            com.noki.vpn.data.DailyStats(date = "invalid", rxBytes = 500, txBytes = 500),
        )
        for (period in com.noki.vpn.ui.StatsPeriod.entries) {
            val series = desktopTrafficSeries(stats, period, today)
            assertEquals(com.noki.vpn.ui.aggregateDailyStats(stats, period, today).totalBytes, series.sumOf { it.second.totalBytes })
            assertTrue(series.filter { it.first.toLocalDate() > today }.all { it.second.totalBytes == 0L })
        }
        val hours = desktopTrafficSeries(stats, com.noki.vpn.ui.StatsPeriod.TODAY, today)
        assertEquals(24, hours.size)
        assertEquals(today.atTime(13, 0), hours[13].first)
        assertEquals(150L, hours[13].second.totalBytes)
        assertTrue("Legacy daily totals must not be fabricated into hourly history", desktopTrafficSeries(
            listOf(com.noki.vpn.data.DailyStats(today.toString(), rxBytes = 100)), com.noki.vpn.ui.StatsPeriod.TODAY, today).all { it.second.totalBytes == 0L })
        assertEquals(7, desktopTrafficSeries(stats, com.noki.vpn.ui.StatsPeriod.WEEK, today).size)
        assertEquals(29, desktopTrafficSeries(emptyList(), com.noki.vpn.ui.StatsPeriod.MONTH, java.time.LocalDate.of(2024, 2, 10)).size)
    }

    @OptIn(ExperimentalComposeUiApi::class)
    @Test fun `static bitmap cache can be cleared without invalidating existing painters`() {
        val path = "noki/android/drawable-nodpi/ic_launcher_bg.png"
        val first = nokiBitmapResource(path)
        assertSame(first, nokiBitmapResource(path))
        assertTrue(first.width > 0 && first.height > 0)
        val pixel = first.toPixelMap()[32, 32]
        clearNokiUiCaches()
        val refreshed = nokiBitmapResource(path)
        assertNotSame(first, refreshed)
        assertSame(refreshed, nokiBitmapResource(path))
        val scene = ImageComposeScene(64, 64, density = Density(1f)) {
            Canvas(Modifier.fillMaxSize()) { drawImage(first) }
        }
        try {
            scene.render().use { image -> assertEquals(pixel, image.toComposeImageBitmap().toPixelMap()[32, 32]) }
        } finally { scene.close() }
    }

    @Test fun `avatar image cache follows local file version and deletion`() {
        val file = Files.createTempFile("noki-avatar-image-test", ".png")
        try {
            fun write(color: Int) {
                val bitmap = java.awt.image.BufferedImage(2, 2, java.awt.image.BufferedImage.TYPE_INT_RGB)
                for (x in 0..1) for (y in 0..1) bitmap.setRGB(x, y, color)
                assertTrue(ImageIO.write(bitmap, "png", file.toFile()))
            }
            write(java.awt.Color.RED.rgb)
            val first = requireNotNull(nokiAvatarImage(file.toString()))
            assertSame(first, nokiAvatarImage(file.toString()))
            clearNokiUiCaches()
            val refreshed = requireNotNull(nokiAvatarImage(file.toString()))
            assertNotSame(first, refreshed)
            assertSame(refreshed, nokiAvatarImage(file.toString()))
            assertEquals(Color.Red, first.toPixelMap()[0, 0])
            assertEquals(Color.Red, refreshed.toPixelMap()[0, 0])
            val modified = Files.getLastModifiedTime(file)
            write(java.awt.Color.BLUE.rgb)
            Files.setLastModifiedTime(file, java.nio.file.attribute.FileTime.fromMillis(modified.toMillis() + 1_000))
            val replacement = requireNotNull(nokiAvatarImage(file.toString()))
            assertNotSame(refreshed, replacement)
            assertEquals(Color.Red, first.toPixelMap()[0, 0])
            assertEquals(Color.Blue, replacement.toPixelMap()[0, 0])
            Files.delete(file)
            assertNull(nokiAvatarImage(file.toString()))
        } finally { Files.deleteIfExists(file) }
    }

    @OptIn(ExperimentalComposeUiApi::class)
    @Test fun `glass menu corners preserve antialiasing at Windows display scales`() {
        fun render(density: Float, glass: Boolean): java.awt.image.BufferedImage {
            val scene = ImageComposeScene((230 * density).toInt(), (80 * density).toInt(), density = Density(density)) {
                val backdrop = rememberLayerBackdrop()
                DesktopTheme(backdrop) {
                    Box(Modifier.fillMaxSize()) {
                        Box(Modifier.fillMaxSize().layerBackdrop(backdrop))
                        Box(Modifier.offset(20.dp, 20.dp).size(190.dp, 40.dp).then(
                            if (glass) Modifier.pcGlass(Color.White, blurRadius = 0.dp, lightIntensity = 0f)
                            else Modifier.background(Color.White, DesktopUiKit.Shape)).pcClickable {})
                    }
                }
            }
            try {
                repeat(4) { scene.render(it * 100_000_000L).close() }
                return scene.render(500_000_000L).use { image -> image.encodeToData(EncodedImageFormat.PNG)!!.use { data ->
                    ImageIO.read(data.bytes.inputStream())
                } }
            } finally { scene.close() }
        }
        for (density in listOf(1f, 1.25f, 1.5f)) {
            val reference = render(density, false)
            val glass = render(density, true)
            val errors = (0 until glass.height).flatMap { y -> (0 until glass.width).map { x ->
                kotlin.math.abs((glass.getRGB(x, y) ushr 24) - (reference.getRGB(x, y) ushr 24))
            } }
            val partial = (0 until glass.height).sumOf { y -> (0 until glass.width).count { x -> (glass.getRGB(x, y) ushr 24) in 1..254 } }
            assertTrue("Rounded corners need partial pixel coverage at $density DPI", partial >= 12)
            assertTrue("Glass must retain the normal antialiased contour at $density DPI: ${errors.max()}", errors.max() <= 16)
        }
    }

    @OptIn(ExperimentalComposeUiApi::class)
    @Test fun `loading does not dim hovered controls or paint beyond rounded corners`() {
        val enabled = mutableStateOf(true)
        val scene = ImageComposeScene(320, 160, density = Density(1f)) {
            DesktopTheme(null) {
                Column(Modifier.fillMaxSize().background(DesktopUiKit.Canvas).padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    PcButton("Выбрать", Modifier.fillMaxWidth(), enabled = enabled.value, selected = true, onClick = {})
                    PcToggle("Настройка", true, enabled = enabled.value, onChange = {})
                }
            }
        }
        fun render(time: Long) = scene.render(time).use { image -> image.encodeToData(EncodedImageFormat.PNG)!!.use { data -> ImageIO.read(data.bytes.inputStream()) } }
        try {
            repeat(3) { render(it * 100_000_000L) }
            val baseline = render(300_000_000L)
            scene.sendPointerEvent(PointerEventType.Move, Offset(100f, 40f))
            repeat(4) { render(500_000_000L + it * 100_000_000L) }
            val before = render(1_000_000_000L)
            assertEquals("Hover must stay inside the rounded card", 0xFF131313.toInt(), before.getRGB(20, 20))
            enabled.value = false
            repeat(4) { render(1_200_000_000L + it * 100_000_000L) }
            val loading = render(2_000_000_000L)
            assertEquals("Loading must retain button brightness", baseline.getRGB(40, 40), loading.getRGB(40, 40))
            assertEquals("Loading must retain toggle brightness", baseline.getRGB(40, 100), loading.getRGB(40, 100))
        } finally { scene.close() }
    }

    @OptIn(ExperimentalComposeUiApi::class)
    @Test fun `desktop power surface blurs its captured background`() {
        val scene = ImageComposeScene(160, 120, density = Density(1f)) {
            val backdrop = rememberLayerBackdrop()
            DesktopTheme(backdrop) {
                Box(Modifier.fillMaxSize()) {
                    Canvas(Modifier.fillMaxSize().layerBackdrop(backdrop)) {
                        for (x in 0 until 160 step 8) drawRect(if (x % 16 == 0) Color.Red else Color.Blue,
                            topLeft = Offset(x.toFloat(), 0f), size = Size(8f, 120f))
                    }
                    PcSurface(Modifier.offset(20.dp, 20.dp).size(84.dp, 80.dp), DesktopUiKit.Error.copy(alpha = .11f)) {}
                }
            }
        }
        try {
            repeat(4) { scene.render(it * 100_000_000L).close() }
            scene.render(500_000_000L).use { image -> image.encodeToData(EncodedImageFormat.PNG)!!.use { data ->
                val decoded = ImageIO.read(data.bytes.inputStream())
                val pixel = decoded.getRGB(60, 60)
                assertTrue("Power glass must mix the red and blue stripes through blur", (pixel shr 16 and 255) > 40 && (pixel and 255) > 40)
                Files.createDirectories(java.nio.file.Path.of("build/screenshots"))
                Files.write(java.nio.file.Path.of("build/screenshots/power-glass.png"), data.bytes)
            } }
        } finally { scene.close() }
    }

    @OptIn(ExperimentalComposeUiApi::class)
    @Test fun `desktop glass keeps a visible rim and refracts beyond the highlight`() {
        fun render(glass: Boolean, highlight: Boolean, frost: Int): java.awt.image.BufferedImage {
            val scene = ImageComposeScene(300, 180, density = Density(1f)) {
                val backdrop = rememberLayerBackdrop()
                DesktopTheme(backdrop) {
                    Box(Modifier.fillMaxSize()) {
                        Canvas(Modifier.fillMaxSize().layerBackdrop(backdrop)) {
                            drawRect(Color(0xFF101010))
                            drawRect(Color(0xFF909090), topLeft = Offset(40f, 0f), size = Size(260f, 180f))
                        }
                        val surface = if (glass) Modifier.pcGlass(blurRadius = frost.dp, lightIntensity = if (highlight) .12f else 0f)
                        else Modifier.drawBackdrop(backdrop, shape = { DesktopUiKit.Shape },
                            effects = { blur(frost.dp.toPx()) }, highlight = null, shadow = null,
                            onDrawSurface = { drawRect(DesktopUiKit.Glass) })
                        Box(Modifier.offset(30.dp, 20.dp).size(240.dp, 140.dp).then(surface))
                    }
                }
            }
            try {
                repeat(4) { scene.render(it * 100_000_000L).close() }
                return scene.render(500_000_000L).use { image -> image.encodeToData(EncodedImageFormat.PNG)!!.use { data ->
                    ImageIO.read(data.bytes.inputStream())
                } }
            } finally { scene.close() }
        }
        val blurred = render(glass = false, highlight = false, frost = 4)
        val refracted = render(glass = true, highlight = false, frost = 4)
        val homeUnlit = render(glass = true, highlight = false, frost = 35)
        val highlighted = render(glass = true, highlight = true, frost = 35)
        Files.createDirectories(java.nio.file.Path.of("build/screenshots"))
        ImageIO.write(blurred, "png", java.io.File("build/screenshots/glass-blur-only.png"))
        ImageIO.write(refracted, "png", java.io.File("build/screenshots/glass-refraction.png"))
        ImageIO.write(highlighted, "png", java.io.File("build/screenshots/glass-rim-refraction.png"))
        fun light(image: java.awt.image.BufferedImage, x: Int, y: Int) = image.getRGB(x, y) and 255
        val rimLift = (30..32).maxOf { x -> light(highlighted, x, 90) - light(homeUnlit, x, 90) }
        assertTrue("The glass rim must stay subtle but visible on a dark backdrop: $rimLift", rimLift in 5..25)
        val edgeDisplacement = (35..45).maxOf { x -> kotlin.math.abs(light(refracted, x, 90) - light(blurred, x, 90)) }
        assertTrue("Refraction must displace the backdrop beyond the thin highlight: $edgeDisplacement", edgeDisplacement > 8)
        assertTrue("The lens must preserve the frosted center", kotlin.math.abs(light(refracted, 150, 90) - light(blurred, 150, 90)) <= 2)
        assertEquals("The glass must stay inside its rounded corners", blurred.getRGB(30, 20), highlighted.getRGB(30, 20))
    }

    @Test fun `modern file picker and installed application discovery use the Windows shell`() {
        javax.swing.SwingUtilities.invokeAndWait { WindowsShell.verifyDialogAvailable() }
        val applications = WindowsShell.installedApplications()
        assertTrue("This Windows machine has installed desktop apps", applications.isNotEmpty())
        assertEquals(applications.size, applications.map { it.id.lowercase() }.distinct().size)
        assertSame("Reopening the list must reuse the discovery snapshot", applications, WindowsShell.installedApplications())
        assertTrue(applications.all { app -> app.name.isNotBlank() && (app.folder != null || app.executables.isNotEmpty()) &&
            app.executables.all { PathValidation.isExecutablePath(it) } && app.selectionPaths.all { PathValidation.isExecutablePath(it) || PathValidation.isFolderPath(it) } })
    }

    @Test fun `invite countdown expires at backend deadline`() {
        val now = java.time.Instant.parse("2026-10-11T12:00:00Z").toEpochMilli()
        assertEquals(300L, inviteRemainingSeconds("2026-10-11T12:05:00Z", now))
        assertEquals(1L, inviteRemainingSeconds("2026-10-11T12:00:00.001", now))
        assertEquals(0L, inviteRemainingSeconds("2026-10-11T11:59:59Z", now))
        assertEquals(null, inviteRemainingSeconds("invalid", now))
    }

    @Test fun `device QR preserves Android payload and backend date formats`() {
        val payload = com.noki.vpn.ui.inviteQrPayload(" UYN8R-HN4JZ ")
        assertEquals("noki://invite/UYN8R-HN4JZ", payload)
        val bitmap = com.noki.vpn.ui.inviteCodeQrBitmap("UYN8R-HN4JZ", 512)
        val pixels = IntArray(512 * 512)
        bitmap.readPixels(pixels)
        val source = com.google.zxing.RGBLuminanceSource(512, 512, pixels)
        val result = com.google.zxing.MultiFormatReader().decode(com.google.zxing.BinaryBitmap(com.google.zxing.common.HybridBinarizer(source)))
        assertEquals(payload, result.text)
        assertEquals(formatDesktopDate("2026-10-06T22:30:00.975956Z"), formatDesktopDate("2026-10-06T22:30:00.975956"))
        assertFalse(formatDesktopDate("2026-10-06T22:30:00.975956").contains('T'))
        assertEquals("-", formatDesktopDate("invalid"))
        assertTrue(formatDesktopDate("2026-10-06T12:00:00Z", includeTime = false, language = AppLanguage.RU).contains("октября"))
        assertTrue(formatDesktopDate("2026-10-06T12:00:00Z", includeTime = false, language = AppLanguage.EN).contains("October"))
        assertFalse(formatDesktopDate("2026-10-06T12:00:00Z", includeTime = false).contains(':'))
    }

    @OptIn(ExperimentalComposeUiApi::class)
    @Test fun `Kyant Backdrop lens refracts the captured backdrop`() {
        fun render(refraction: Float): java.awt.image.BufferedImage {
            val scene = ImageComposeScene(192, 128, density = Density(1f)) {
                val backdrop = rememberLayerBackdrop()
                Box(Modifier.fillMaxSize()) {
                    Canvas(Modifier.fillMaxSize().layerBackdrop(backdrop)) {
                        for (x in 0 until 192 step 8) drawRect(if (x % 16 == 0) Color.Red else Color.Blue,
                            topLeft = Offset(x.toFloat(), 0f), size = Size(8f, 128f))
                    }
                    Box(Modifier.offset(24.dp, 24.dp).size(144.dp, 80.dp).nokiGlassSurface(
                        shape = RoundedCornerShape(20.dp), backdrop = backdrop, liveGlassEnabled = true,
                        shadowAlpha = 0f, highlightAlpha = 0f, saturation = 1f, contrast = 1f, brightness = 0f,
                        blurRadiusDp = 0f, lensRadiusDp = 24f, lensRefractionDp = refraction,
                        backdropShadowsEnabled = false, surfaceColor = Color.Transparent))
                }
            }
            try {
                repeat(3) { scene.render(it * 100_000_000L).close() }
                return scene.render(500_000_000L).use { image ->
                    image.encodeToData(EncodedImageFormat.PNG)!!.use { data ->
                        ImageIO.read(data.bytes.inputStream())
                    }
                }
            } finally { scene.close() }
        }
        val plain = render(0f)
        val glass = render(24f)
        val changed = (24 until 104).sumOf { y -> (24 until 168).count { x -> plain.getRGB(x, y) != glass.getRGB(x, y) } }
        assertTrue("The native lens must distort the original captured stripes", changed > 100)
    }

    @OptIn(ExperimentalComposeUiApi::class)
    @Test fun `render actual login and account content without an unlocked desktop`() = runBlocking(Dispatchers.Main) {
        check(java.awt.EventQueue.isDispatchThread())
        val directory = Files.createTempDirectory("noki-ui-test")
        val vault = WindowsVault(directory)
        val controller = DesktopController(vault, SingBoxRuntime(vault), com.noki.vpn.data.BackendApiClient(baseUrl = "http://127.0.0.1:9"))
        try {
            val login = DesktopState()
            val servers = listOf("11111111-1111-4111-8111-111111111111", "22222222-2222-4222-8222-222222222222").mapIndexed { index, id ->
                VpnServer(id, "DE-${index + 1}", "DE", "de", "example.invalid", probePort = null, isOnline = true,
                    latencyMs = 28 + index * 59, multiHopEntryAvailable = true, multiHopExitAvailable = true)
            }
            val account = DesktopState(
                user = BackendUser("preview", "Тестовый аккаунт", "preview@example.invalid", null, isActive = true, isAdmin = false),
                subscription = BackendSubscription("active", "preview", null, 11.7, null, planName = "Noki (пример)"),
                locations = listOf(ServerLocation("DE", "DE", "Германия", "", "example.invalid", isOnline = true, latencyMs = 28, servers = servers)),
                selectedServerId = "",
                devices = listOf(com.noki.vpn.data.BackendDevice("windows", "preview-key", "Windows PC", platform = "windows", accessRole = "owner", isActive = true, lastSeenAt = "2026-10-06T18:30:00Z"),
                    com.noki.vpn.data.BackendDevice("android", "preview-key-2", "samsung SM-G996B", platform = "android", accessRole = "guest", isActive = true, lastSeenAt = "2026-10-06T18:20:00Z")),
                currentDeviceId = "windows",
                dailyStats = (0L..30L).map { days -> com.noki.vpn.data.DailyStats(
                    date = java.time.LocalDate.now().minusDays(days).toString(),
                    rxBytes = (days % 5 + 1) * 800_000, txBytes = (days % 3 + 1) * 120_000) },
                incyDevices = listOf(com.noki.vpn.data.BackendIncyDevice("incy1", "Домашний ПК", status = "bound", deviceOs = "Windows 11"), com.noki.vpn.data.BackendIncyDevice("incy2", "Телевизор")),
                routingRules = listOf(DesktopRoutingRule.RUSSIAN_RESOURCES,
                    DesktopRoutingRule("work", "Рабочие сервисы", true, listOf("domain:example.com"), listOf("192.0.2.0/24"))),
                plans = listOf("plus", "pro", "premium").mapIndexed { index, code -> com.noki.vpn.data.BackendPlan(
                    code = code, name = code.replaceFirstChar { it.uppercase() }, tier = code, billingPeriodMonths = 1,
                    priceRub = listOf(250, 400, 1400)[index], monthlyEquivalentRub = listOf(250, 400, 1400)[index],
                    deviceLimit = listOf(3, 8, 12)[index], trafficLimitGb = if (index == 2) null else 800.0,
                    speedProfile = "default", features = listOf(if (index == 2) "Без лимита" else "800 ГБ", "100 Мбит/с"),
                    headline = null, badgeColor = null, isActive = true, sortOrder = index) },
                checkout = com.noki.vpn.PaymentCheckoutState(
                    config = com.noki.vpn.data.BackendPaymentConfig(true, true, listOf(
                        com.noki.vpn.data.BackendPaymentMethod(1, "sbp", "СБП", true),
                        com.noki.vpn.data.BackendPaymentMethod(2, "card", "Банковская карта", true))), methodCode = "sbp",
                    history = listOf(com.noki.vpn.data.BackendPayment("paid-example", "pro", "paid", 400, null, "2026-10-11T10:30:00Z", "Pro"))),
                notifications = listOf(
                    com.noki.vpn.data.BackendAppNotification("notice-security", "Проверьте настройки безопасности", "Откройте раздел безопасности, чтобы проверить способы входа в аккаунт.", "2026-10-11T10:30:00Z", "open_security_update"),
                    com.noki.vpn.data.BackendAppNotification("notice-plan", "Подписка продлена", "Оплата прошла успешно. Подписка активна.", "2026-10-11T10:30:00Z")),
            )
            val screenshots = java.nio.file.Path.of("build", "screenshots")
            Files.createDirectories(screenshots)
            val countryCatalog = account.copy(locations = account.locations + account.locations.single().copy(code = "fr", countryCode = "FR", country = "Франция"),
                settings = account.settings.copy(userProfile = account.settings.userProfile.copy(serverSelectionMode = ServerSelectionMode.COUNTRY, selectedCountryCode = "DE")))
            val avatar = directory.resolve("avatar.png")
            javax.imageio.ImageIO.write(java.awt.image.BufferedImage(70, 70, java.awt.image.BufferedImage.TYPE_INT_RGB).apply {
                val graphics = createGraphics(); graphics.color = java.awt.Color(66, 214, 164); graphics.fillRect(0, 0, width, height); graphics.dispose()
            }, "png", avatar.toFile())
            val protocol = com.noki.vpn.data.VpnEndpointOption(code = "reality", label = "Reality XHTTP stream-up", locationCode = "de", locationName = "Германия", host = "example.invalid", security = "reality", transport = "xhttp", transportMode = "stream-up")
            val figmaHome = account.copy(
                locations = listOf(account.locations.single().copy(code = "LV", countryCode = "LV", country = "Латвия",
                    servers = servers.map { it.copy(countryCode = "LV") })),
                running = true, verified = true, connectionSettings = account.settings,
                selectedServerId = servers.first().id,
                connectedAtMillis = System.currentTimeMillis() - 12_000,
                uploadBytesPerSecond = 75_000, downloadBytesPerSecond = 1_050_000, sessionBytes = 7_130_317)
            val figmaInvite = account.copy(incyDevices = emptyList(), subscription = account.subscription?.copy(planCode = "plus"),
                plans = account.plans.map { if (it.code == "plus") it.copy(deviceLimit = 5) else it },
                inviteOpen = true, invite = com.noki.vpn.data.BackendDeviceInvite("482916", java.time.Instant.now().plusSeconds(300).toString()))
            val enabledHops = com.noki.vpn.data.MultiHopSettings(true,
                com.noki.vpn.data.HopSelection(ServerSelectionMode.COUNTRY, "DE"),
                com.noki.vpn.data.HopSelection(ServerSelectionMode.SERVER, nodeId = servers.last().id))
            val enabledRoute = account.copy(settings = account.settings.copy(advancedSettings = account.settings.advancedSettings.copy(multiHop = enabledHops)))
            val examples = listOf("multihop-enabled-home" to enabledRoute,
                "ru-relay-home-example" to account.copy(settings = account.settings.copy(advancedSettings = account.settings.advancedSettings.copy(ruRelayEnabled = true))),
                "earth-overview" to account.copy(settings = account.settings.copy(userProfile = account.settings.userProfile.copy(serverSelectionMode = ServerSelectionMode.AUTO))),
                "earth-poland" to account.copy(locations = listOf(account.locations.single().copy(countryCode = "PL", country = "Польша")),
                    settings = account.settings.copy(userProfile = account.settings.userProfile.copy(serverSelectionMode = ServerSelectionMode.COUNTRY, selectedCountryCode = "PL"))),
                "earth-multihop" to account.copy(locations = listOf("HK", "US").map { code -> account.locations.single().copy(code = code, countryCode = code) },
                    settings = account.settings.copy(advancedSettings = account.settings.advancedSettings.copy(
                    multiHop = com.noki.vpn.data.MultiHopSettings(true, com.noki.vpn.data.HopSelection(ServerSelectionMode.COUNTRY, "HK"),
                        com.noki.vpn.data.HopSelection(ServerSelectionMode.COUNTRY, "US"))))),
                "catalog-country-example" to countryCatalog,
                "figma-home" to figmaHome, "figma-invite" to figmaInvite, "welcome" to login, "website-login" to login.copy(loading = true, websiteLoginPending = true),
                "avatar-layout-example" to account.copy(avatarPath = avatar.toString()),
                "maximized-example" to account,
                "invite-example" to account.copy(inviteOpen = true, invite = com.noki.vpn.data.BackendDeviceInvite("UYN8R-HN4JZ", java.time.Instant.now().plusSeconds(300).toString())),
                "invite-expired-example" to account.copy(inviteOpen = true, invite = com.noki.vpn.data.BackendDeviceInvite("UYN8R-HN4JZ", java.time.Instant.now().minusSeconds(30).toString())),
                "invite-loading-example" to account.copy(inviteOpen = true, inviteLoading = true),
                "invite-error-example" to account.copy(inviteOpen = true, inviteError = "Сервер временно недоступен"),
                "notice-example" to account.copy(message = "Скопировано в буфер обмена"),
                "username-dialog-example" to account.copy(accountSecurity = com.noki.vpn.AccountSecurityUiState(com.noki.vpn.AccountSecurityActionState.Username("Тестовый аккаунт"))),
                "incy-links-example" to account.copy(incyLinks = com.noki.vpn.data.IncyConnectionLinks(
                    com.noki.vpn.data.IncyImportLink.parse("incy://crypt1/" + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { it.toByte() })),
                    com.noki.vpn.data.V2raynSubscriptionUrl.parse("https://example.invalid/v2rayn/subscriptions/preview"))),
                "catalog-scroll-example" to account.copy(locations = (1..12).map { index -> account.locations.single().copy(code = "country-$index", country = "Страна $index") }),
                "email-error" to login.copy(message = AppErrorMapper.readableAuthError(AppLanguage.RU, BackendException("Invalid credentials", 401))), "home-example" to account,
                "manual-protocol-example" to account.copy(settings = account.settings.copy(endpointOptions = listOf(protocol),
                    userProfile = account.settings.userProfile.copy(serverSelectionMode = com.noki.vpn.data.ServerSelectionMode.COUNTRY, selectedCountryCode = "DE"),
                    advancedSettings = account.settings.advancedSettings.copy(endpointSelectionMode = com.noki.vpn.data.EndpointSelectionMode.MANUAL, manualEndpointGroupKey = com.noki.vpn.data.EndpointGroupPolicy.groupKey(protocol)))),
                "connected-example" to account.copy(running = true, verified = true, selectedServerId = servers.first().id, connectedAtMillis = System.currentTimeMillis(),
                    uploadBytesPerSecond = 1_200_000, downloadBytesPerSecond = 2_450_000, sessionBytes = 240_000_000))
            val pages = examples.map { Triple(it.first, it.second, when (it.first) {
                "catalog-scroll-example", "catalog-country-example" -> DesktopPage.SERVERS
                "figma-invite", "invite-example", "invite-expired-example", "invite-loading-example", "invite-error-example", "incy-links-example" -> DesktopPage.DEVICES
                "username-dialog-example" -> DesktopPage.PROFILE
                "avatar-layout-example" -> DesktopPage.PERSONALIZATION
                else -> DesktopPage.HOME
            }) } + listOf(Triple("multihop-overview", enabledRoute, DesktopPage.MULTIHOP)) + DesktopPage.entries.filter { it !in setOf(DesktopPage.HOME, DesktopPage.PLANS, DesktopPage.PAYMENTS) }.map {
                Triple(it.name.lowercase(), account, it) }
            val requestedPages = System.getenv("NOKI_UI_PAGE")?.split(',')?.map(String::trim)?.filter(String::isNotEmpty)?.toSet()
            val pagesToRender = if (requestedPages == null) pages else {
                require(requestedPages.isNotEmpty()) { "NOKI_UI_PAGE must name at least one smoke page" }
                val missing = requestedPages - pages.map { it.first }.toSet()
                require(missing.isEmpty()) { "Unknown NOKI_UI_PAGE values: ${missing.joinToString()}" }
                pages.filter { it.first in requestedPages }
            }
            for ((label, state, page) in pagesToRender) {
                val sizes = if (label.startsWith("earth-")) listOf(1050 to 674) else if (label.startsWith("figma-")) listOf(1050 to 674, 800 to 450, 560 to 800, 500 to 450)
                    else if (page == DesktopPage.HOME) listOf(1000 to 650, 960 to 540, 800 to 450, 1280 to 720, 560 to 800, 1920 to 1080)
                    else if (label in listOf("avatar-layout-example", "invite-example", "plans") || page == DesktopPage.PROFILE) listOf(1000 to 650, 800 to 450, 560 to 800, 500 to 450)
                    else if (page in listOf(DesktopPage.SETTINGS, DesktopPage.ADVANCED, DesktopPage.SECURITY, DesktopPage.PERSONALIZATION, DesktopPage.ROUTING, DesktopPage.STATS, DesktopPage.SUPPORT)) listOf(1000 to 650, 800 to 450, 560 to 800, 500 to 450)
                    else listOf(1000 to 650, 800 to 450, 560 to 800)
                for ((width, height) in sizes) {
                    val renderedState = mutableStateOf(state)
                    val scene = ImageComposeScene(width, height, density = Density(1f), coroutineContext = Dispatchers.Main) {
                        DesktopContent(renderedState.value, controller, initialPage = page)
                    }
                    var frameNanos = 0L
                    fun renderFrame(time: Long = 0L): org.jetbrains.skia.Image {
                        frameNanos = maxOf(time, frameNanos + 100_000_000L)
                        return scene.render(frameNanos)
                    }
                    try {
                        suspend fun awaitEarth(time: Long) {
                            fun loaded(node: SemanticsNode): Boolean = node.config.getOrNull(SemanticsProperties.ContentDescription)?.any { it.startsWith("3D") } == true || node.children.any(::loaded)
                            repeat(100) {
                                renderFrame(time).close()
                                if (scene.semanticsOwners.any { loaded(it.rootSemanticsNode) }) return
                                delay(10)
                            }
                            fail("Production 3D Earth must finish loading")
                        }
                        repeat(3) { renderFrame(it * 100_000_000L).close() }
                        if (state.user == null && width >= 760) {
                            fun hasRender(node: SemanticsNode): Boolean =
                                node.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("Рендер с сайта Noki") == true || node.children.any(::hasRender)
                            var loaded = false
                            for (frame in 0 until 100) {
                                renderFrame().close()
                                if (scene.semanticsOwners.any { hasRender(it.rootSemanticsNode) }) { loaded = true; break }
                                delay(10)
                            }
                            assertTrue("Login must display the site render", loaded)
                        }
                        if (state.user != null && DesktopUiKit.sceneFits(width.dp, height.dp) && page in setOf(DesktopPage.HOME, DesktopPage.SERVERS, DesktopPage.MULTIHOP, DesktopPage.PROTOCOL)) awaitEarth(400_000_000L)
                        if (label == "home-example" && width in listOf(1000, 1920)) {
                            val samples = (0 until 12).map { frame ->
                                val start = System.nanoTime()
                                renderFrame(500_000_000L + frame * 50_000_000L).close()
                                (System.nanoTime() - start) / 1_000_000.0
                            }.sorted()
                            Files.writeString(screenshots.resolve("home-offscreen-timing-${width}x$height.json"),
                                "{\"backend\":\"ImageComposeScene offscreen\",\"samples\":12,\"median_ms\":${samples[6]},\"p95_ms\":${samples[11]}}")
                        }
                        renderFrame(2_000_000_000L).use { image ->
                            if (page == DesktopPage.PROFILE) {
                                fun usageVisible(node: SemanticsNode): Boolean = node.config.getOrNull(SemanticsProperties.Text).orEmpty()
                                    .any { it.text == "11,7 ГБ из ∞ ГБ" } || node.children.any(::usageVisible)
                                assertTrue("Profile usage must include units for used and available traffic", scene.semanticsOwners.any { usageVisible(it.rootSemanticsNode) })
                            }
                            image.encodeToData(EncodedImageFormat.PNG)!!.use { data ->
                                val file = screenshots.resolve("$label-${width}x$height.png")
                                Files.write(file, data.bytes)
                                val decoded = ImageIO.read(file.toFile())
                                // Credential modals dim the caption; notifications and their shadows stay below it.
                                if (state.user != null && label != "username-dialog-example" &&
                                    page in setOf(DesktopPage.HOME, DesktopPage.SERVERS, DesktopPage.MULTIHOP, DesktopPage.PROTOCOL)) {
                                    for (y in 2 until 38 step 9) for (x in 150 until width - 132 step 23) {
                                        assertEquals("$label ${width}x$height: Content must stay below the header at $x,$y", 0xFF131313.toInt(), decoded.getRGB(x, y))
                                    }
                                }
                                if (label == "notice-example")
                                    assertEquals("Notification background must be opaque", 0xFF4A4A4A.toInt(), decoded.getRGB(width / 2, 56))
                                assertEquals(width, decoded.width)
                                assertEquals(height, decoded.height)
                                if (page == DesktopPage.STATS && width == 1000) {
                                    fun chartDay(node: SemanticsNode): SemanticsNode? =
                                        if (node.config.getOrNull(SemanticsProperties.ContentDescription)?.any { ": " in it } == true) node
                                        else node.children.firstNotNullOfOrNull(::chartDay)
                                    val day = scene.semanticsOwners.firstNotNullOf { chartDay(it.rootSemanticsNode) }.boundsInRoot
                                    scene.sendPointerEvent(PointerEventType.Move, day.center)
                                    renderFrame().close()
                                    renderFrame().use { hovered ->
                                        val painted = IntArray(width * height)
                                        hovered.toComposeImageBitmap().readPixels(painted)
                                        for (y in day.top.toInt() + 2 until day.bottom.toInt() - 2 step 4)
                                            for (x in day.left.toInt() + 2 until day.right.toInt() - 2 step 4)
                                                assertEquals("Chart dates must not light up as blocks on hover", decoded.getRGB(x, y), painted[y * width + x])
                                    }
                                }
                                if (label.startsWith("figma-")) {
                                    fun descendants(node: SemanticsNode): Sequence<SemanticsNode> = sequence {
                                        yield(node)
                                        for (child in node.children) yieldAll(descendants(child))
                                    }
                                    fun node(label: String) = scene.semanticsOwners.asSequence().flatMap { descendants(it.rootSemanticsNode) }.first {
                                        it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text == label } == true ||
                                            it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(label) == true
                                    }
                                    if (label == "figma-home") {
                                        val status = node("Подключено").boundsInRoot
                                        assertTrue("Connection status stays in the left pane", status.right <= 464f && status.left >= 200f)
                                        assertTrue("Connection status stays above the pane bottom", status.bottom < height - 32f)
                                        if (width == 1050) {
                                            assertTrue("Status occupies the compact bottom card", status.top >= 551f)
                                            assertTrue("Country selection is in the top card", node("Латвия").boundsInRoot.top < 160f)
                                        }
                                    } else {
                                        assertTrue("Pairing retains a visible return action", node("Назад").boundsInRoot.bottom <= height)
                                        if (width == 1050) {
                                            val qr = node("QR-код подключения").boundsInRoot
                                            assertEquals(160f, qr.width, .5f)
                                            assertEquals(160f, qr.height, .5f)
                                            assertEquals(460f, qr.left, .5f)
                                            assertEquals("QR retains the title and subtitle spacing below the workspace inset", 209f, qr.top, 1f)
                                            assertTrue("Copy code fits next to the code", node("Скопировать код").boundsInRoot.right <= 1006f)
                                        }
                                    }
                                }

                                if (state.user != null) {
                                    fun navigation(node: SemanticsNode): SemanticsNode? =
                                        if (node.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("Навигация") == true) node
                                        else node.children.firstNotNullOfOrNull(::navigation)
                                    val bounds = scene.semanticsOwners.firstNotNullOf { navigation(it.rootSemanticsNode) }.boundsInRoot
                                    assertEquals("Every page starts navigation 32dp below the 40dp header", 72f, bounds.top, .5f)
                                    assertEquals("Every page leaves the same 32dp bottom inset", height - 32f, bounds.bottom, .5f)
                                    if (label != "username-dialog-example" && page !in setOf(DesktopPage.HOME, DesktopPage.SERVERS, DesktopPage.MULTIHOP, DesktopPage.PROTOCOL))
                                        assertEquals("Navigation matches the other panels outside the Home section", 0xFF272727.toInt(), decoded.getRGB(25, height / 2))
                                }
                                if (label in listOf("catalog-country-example") && width == 1000) {
                                    fun named(node: SemanticsNode, text: String): SemanticsNode? =
                                        if (node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == text } == true) node
                                        else node.children.firstNotNullOfOrNull { named(it, text) }
                                    fun centerY(text: String) = scene.semanticsOwners.firstNotNullOf { named(it.rootSemanticsNode, text) }.boundsInRoot.center.y.toInt()
                                    val auto = decoded.getRGB(130, centerY("Автовыбор"))
                                    assertEquals("Inactive Automatic uses the same opaque surface as inactive countries", 0xFF272727.toInt(), auto)
                                    assertEquals(auto, decoded.getRGB(130, centerY("Франция")))
                                }
                                if (label == "settings" && width == 1000 && height == 650) {
                                    assertEquals("Top-level panels use 272727", 0xFF272727.toInt(), decoded.getRGB(25, 90))
                                    val nestedPixels = (140 until 300).sumOf { y -> (400 until 950).count { x -> decoded.getRGB(x, y) == 0xFF3C3C3C.toInt() } }
                                    assertTrue("Settings groups use the inner 3C3C3C surface", nestedPixels > 2000)
                                    assertEquals("Header matches the final canvas", 0xFF131313.toInt(), decoded.getRGB(500, 20))
                                }
                                if (label == "payments" && width == 1000 && height == 650) {
                                    scene.sendPointerEvent(PointerEventType.Press, Offset(665f, 154f), buttons = PointerButtons(isPrimaryPressed = true), button = PointerButton.Primary)
                                    scene.sendPointerEvent(PointerEventType.Release, Offset(665f, 154f), buttons = PointerButtons(), button = PointerButton.Primary)
                                    repeat(8) { frame -> renderFrame(3_000_000_000L + frame * 100_000_000L).close() }
                                    renderFrame(4_000_000_000L).use { image -> image.encodeToData(EncodedImageFormat.PNG)!!.use { data ->
                                        Files.write(screenshots.resolve("payment-details-1000x650.png"), data.bytes)
                                        val details = ImageIO.read(data.bytes.inputStream())
                                        assertEquals("Payment details open in the root dialog", 0xFF272727.toInt(), details.getRGB(250, 345))
                                        assertTrue("The details dialog has a dimmed backdrop", (details.getRGB(120, 70) and 255) < (decoded.getRGB(120, 70) and 255))
                                    } }
                                }
                                if (label == "routing" && width == 1000 && height == 650) {
                                    fun descendants(node: SemanticsNode): Sequence<SemanticsNode> = sequence {
                                        yield(node)
                                        for (child in node.children) yieldAll(descendants(child))
                                    }
                                    fun clickText(label: String) {
                                        fun target() = scene.semanticsOwners.asSequence().flatMap { descendants(it.rootSemanticsNode) }.first {
                                            it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text == label } == true ||
                                                it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(label) == true
                                        }
                                        if (label == "Удалить правило") {
                                            generateSequence(target().parent) { it.parent }.firstNotNullOfOrNull {
                                                it.config.getOrNull(androidx.compose.ui.semantics.SemanticsActions.ScrollBy)?.action
                                            }?.invoke(0f, 10_000f)
                                            repeat(6) { renderFrame().close() }
                                        }
                                        val point = target().boundsInRoot.center
                                        scene.sendPointerEvent(PointerEventType.Press, point, buttons = PointerButtons(isPrimaryPressed = true), button = PointerButton.Primary)
                                        scene.sendPointerEvent(PointerEventType.Release, point, buttons = PointerButtons(), button = PointerButton.Primary)
                                    }
                                    assertTrue(scene.semanticsOwners.asSequence().flatMap { descendants(it.rootSemanticsNode) }.any {
                                        it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text == "Российские ресурсы" } == true
                                    })
                                    clickText("Рабочие сервисы")
                                    repeat(4) { frame -> renderFrame(2_600_000_000L + frame * 100_000_000L).close() }
                                    assertTrue(scene.semanticsOwners.asSequence().flatMap { descendants(it.rootSemanticsNode) }.any {
                                        it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text == "Удалить правило" } == true
                                    })
                                    val rulesBefore = controller.state.value.routingRules
                                    clickText("Удалить правило")
                                    repeat(4) { renderFrame().close() }
                                    fun confirmVisible() = scene.semanticsOwners.asSequence().flatMap { descendants(it.rootSemanticsNode) }.any {
                                        it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text == "Точно удалить?" } == true
                                    }
                                    assertTrue("Deletion needs an explicit confirmation", confirmVisible())
                                    assertEquals(rulesBefore, controller.state.value.routingRules)
                                    renderFrame().use { modal -> modal.encodeToData(EncodedImageFormat.PNG)!!.use { data ->
                                        Files.write(screenshots.resolve("routing-delete-confirm-1000x650.png"), data.bytes)
                                    } }
                                    clickText("Нет")
                                    repeat(4) { renderFrame().close() }
                                    assertFalse(confirmVisible())
                                    assertEquals(rulesBefore, controller.state.value.routingRules)
                                    clickText("Удалить правило")
                                    repeat(4) { renderFrame().close() }
                                    clickText("Да")
                                    withTimeout(3_000) {
                                        controller.state.first { rows -> rows.routingRules.none { it.id == "work" } }
                                    }
                                    assertFalse(vault.loadRoutingRules().any { it.id == "work" })
                                    repeat(4) { renderFrame().close() }
                                    assertFalse(confirmVisible())
                                    scene.sendPointerEvent(PointerEventType.Press, Offset(10f, 100f), buttons = PointerButtons(isPrimaryPressed = true), button = PointerButton.Primary)
                                    scene.sendPointerEvent(PointerEventType.Release, Offset(10f, 100f), buttons = PointerButtons(), button = PointerButton.Primary)
                                    repeat(4) { frame -> renderFrame(3_000_000_000L + frame * 100_000_000L).close() }
                                    clickText("Добавить")
                                    repeat(8) { frame -> renderFrame(3_000_000_000L + frame * 100_000_000L).close() }
                                    renderFrame(4_000_000_000L).use { modal -> modal.encodeToData(EncodedImageFormat.PNG)!!.use { modalData ->
                                        Files.write(screenshots.resolve("$label-modal-1000x650.png"), modalData.bytes)
                                        val after = ImageIO.read(modalData.bytes.inputStream())
                                        assertEquals("Modal belongs to the workspace root and uses the final surface", 0xFF272727.toInt(), after.getRGB(500, 55))
                                    } }
                                    if (label == "routing") {
                                        fun click(point: Offset) {
                                            scene.sendPointerEvent(PointerEventType.Press, point, buttons = PointerButtons(isPrimaryPressed = true), button = PointerButton.Primary)
                                            scene.sendPointerEvent(PointerEventType.Release, point, buttons = PointerButtons(), button = PointerButton.Primary)
                                        }
                                        click(Offset(10f, 100f))
                                        repeat(4) { frame -> renderFrame(4_200_000_000L + frame * 100_000_000L).close() }
                                        clickText("Добавить")
                                        repeat(4) { frame -> renderFrame(4_600_000_000L + frame * 100_000_000L).close() }
                                        clickText("Выбрать установленные приложения")
                                        repeat(60) { frame ->
                                            renderFrame(5_000_000_000L + frame * 100_000_000L).close()
                                            delay(100)
                                        }
                                        renderFrame(12_000_000_000L).use { modal -> modal.encodeToData(EncodedImageFormat.PNG)!!.use { data ->
                                            Files.write(screenshots.resolve("applications-modal-1000x650.png"), data.bytes)
                                            val after = ImageIO.read(data.bytes.inputStream())
                                            assertEquals("Installed applications must use the entire workspace", 0xFF272727.toInt(), after.getRGB(970, 100))
                                        } }
                                        click(Offset(10f, 100f))
                                        repeat(4) { frame -> renderFrame(13_000_000_000L + frame * 100_000_000L).close() }
                                        click(Offset(10f, 100f))
                                        repeat(4) { renderFrame().close() }
                                        clickText("Назад")
                                        repeat(4) { frame -> renderFrame(14_000_000_000L + frame * 100_000_000L).close() }
                                        scene.sendPointerEvent(PointerEventType.Scroll, Offset(700f, 500f), scrollDelta = Offset(0f, 180f))
                                        repeat(4) { renderFrame().close() }
                                        clickText("Статистика использования")
                                        repeat(4) { frame -> renderFrame(15_000_000_000L + frame * 100_000_000L).close() }
                                        assertTrue("Routing must return to Advanced and open statistics", scene.semanticsOwners.asSequence().flatMap { descendants(it.rootSemanticsNode) }.any {
                                            it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text == "Статистика" } == true
                                        })
                                    }
                                }
                                 if (label == "multihop-enabled-home" && width == 1000 && height == 650) {
                                     fun descendants(node: SemanticsNode): Sequence<SemanticsNode> = sequence {
                                         yield(node); for (child in node.children) yieldAll(descendants(child))
                                     }
                                     fun allText() = scene.semanticsOwners.asSequence().flatMap { descendants(it.rootSemanticsNode) }
                                         .flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty().asSequence() }.map { it.text }.toList()
                                     fun clickText(text: String) {
                                         val target = scene.semanticsOwners.asSequence().flatMap { descendants(it.rootSemanticsNode) }.first {
                                             it.config.getOrNull(SemanticsProperties.Text)?.any { it.text == text } == true
                                         }.boundsInRoot.center
                                         scene.sendPointerEvent(PointerEventType.Press, target, buttons = PointerButtons(isPrimaryPressed = true), button = PointerButton.Primary)
                                         scene.sendPointerEvent(PointerEventType.Release, target, buttons = PointerButtons(), button = PointerButton.Primary)
                                         repeat(4) { frame -> renderFrame(4_000_000_000L + frame * 100_000_000L).close() }
                                     }
                                     assertTrue(allText().contains("Германия → DE-2"))
                                      clickText("Германия → DE-2")
                                     assertTrue(allText().containsAll(listOf("Входная локация", "Выходная локация", "Отключить мультихоп", "Выбрать другой маршрут")))
                                     assertFalse(allText().contains("Все страны"))
                                     renderFrame(4_800_000_000L).use { image -> image.encodeToData(EncodedImageFormat.PNG)!!.use { data ->
                                         Files.write(screenshots.resolve("multihop-overview-1000x650.png"), data.bytes)
                                     } }
                                     clickText("Выбрать другой маршрут")
                                     assertTrue(allText().contains("Продолжить"))
                                     assertFalse(allText().contains("Отключить мультихоп"))
                                 }
                                 if (label == "multihop" && width == 1000 && height == 650) {
                                    val beforeSettings = controller.state.value.settings.advancedSettings.multiHop
                                    fun click(point: Offset) {
                                        scene.sendPointerEvent(PointerEventType.Press, point, buttons = PointerButtons(isPrimaryPressed = true), button = PointerButton.Primary)
                                        scene.sendPointerEvent(PointerEventType.Release, point, buttons = PointerButtons(), button = PointerButton.Primary)
                                        repeat(8) { frame -> renderFrame(4_000_000_000L + frame * 100_000_000L).close() }
                                    }
                                    fun descendants(node: SemanticsNode): Sequence<SemanticsNode> = sequence {
                                        yield(node); for (child in node.children) yieldAll(descendants(child))
                                    }
                                    fun node(text: String) = scene.semanticsOwners.asSequence().flatMap { descendants(it.rootSemanticsNode) }.first {
                                        it.config.getOrNull(SemanticsProperties.Text)?.any { label -> label.text == text } == true ||
                                            it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(text) == true
                                    }
                                    click(node("Германия").boundsInRoot.center)
                                    click(node("Продолжить").boundsInRoot.center)
                                    assertTrue(node("Выходная локация").boundsInRoot.top < 120f)
                                    click(node("Германия").boundsInRoot.center)
                                    val confirm = node("Подтвердить маршрут")
                                    assertFalse("Countries with two eligible servers can form a route", confirm.config.contains(SemanticsProperties.Disabled))
                                    renderFrame(5_500_000_000L).use { image -> image.encodeToData(EncodedImageFormat.PNG)!!.use { data ->
                                        Files.write(screenshots.resolve("multihop-countries-1000x650.png"), data.bytes)
                                    } }
                                    click(node("Назад").boundsInRoot.center)
                                    assertTrue(node("Входная локация").boundsInRoot.top < 120f)
                                    assertEquals("Draft route changes must remain local until confirmation", beforeSettings, controller.state.value.settings.advancedSettings.multiHop)
                                }
                                if (label == "manual-protocol-example" && width == 1000 && height == 650) {
                                    fun descendants(node: SemanticsNode): Sequence<SemanticsNode> = sequence {
                                        yield(node); for (child in node.children) yieldAll(descendants(child))
                                    }
                                    fun labelled(text: String) = scene.semanticsOwners.asSequence().flatMap { descendants(it.rootSemanticsNode) }.first {
                                        it.config.getOrNull(SemanticsProperties.Text)?.any { label -> label.text == text } == true
                                    }
                                    val point = labelled("Протокол: Reality XHTTP stream-up").boundsInRoot.center
                                    scene.sendPointerEvent(PointerEventType.Press, point, buttons = PointerButtons(isPrimaryPressed = true), button = PointerButton.Primary)
                                    scene.sendPointerEvent(PointerEventType.Release, point, buttons = PointerButtons(), button = PointerButton.Primary)
                                    repeat(8) { frame -> renderFrame(3_000_000_000L + frame * 100_000_000L).close() }
                                    renderFrame(4_000_000_000L).use { modal -> modal.encodeToData(EncodedImageFormat.PNG)!!.use { modalData ->
                                        Files.write(screenshots.resolve("protocol-selected-1000x650.png"), modalData.bytes)
                                        val selected = labelled("Reality XHTTP stream-up").boundsInRoot
                                        val after = ImageIO.read(modalData.bytes.inputStream())
                                        val greenPixels = (selected.top.toInt() until selected.bottom.toInt()).sumOf { y ->
                                            (selected.left.toInt() until selected.right.toInt()).count { x ->
                                                val pixel = after.getRGB(x, y)
                                                (pixel shr 8 and 255) > 120 && (pixel shr 8 and 255) > (pixel shr 16 and 255) * 1.5
                                            }
                                        }
                                        assertTrue("Manually selected protocol must be green", greenPixels > selected.width * selected.height / 2)
                                    } }
                                }
                                if (label == "profile") {
                                    fun descendants(node: SemanticsNode): Sequence<SemanticsNode> = sequence {
                                        yield(node); for (child in node.children) yieldAll(descendants(child))
                                    }
                                    val text = scene.semanticsOwners.asSequence().flatMap { descendants(it.rootSemanticsNode) }
                                        .flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty().asSequence() }.map { it.text }.toList()
                                    assertFalse("Profile does not expose account deletion", "Удалить аккаунт" in text)
                                    assertFalse("Plan title replaces the generic active status", "Подписка активна" in text)
                                    if (width == 1000) {
                                        val actions = text.filter { it in listOf("Профиль", "Уведомления", "Тарифы", "История платежей") }.distinct().take(4)
                                        assertEquals(listOf("Профиль", "Уведомления", "Тарифы", "История платежей"), actions)
                                    }
                                }
                                if (label == "devices" && width == 1000 && height == 650) {
                                    scene.sendPointerEvent(PointerEventType.Scroll, Offset(400f, 400f), scrollDelta = Offset(0f, 100f))
                                    repeat(8) { frame -> renderFrame(3_000_000_000L + frame * 100_000_000L).close() }
                                    renderFrame(4_000_000_000L).use { bottom -> bottom.encodeToData(EncodedImageFormat.PNG)!!.use { data -> Files.write(screenshots.resolve("devices-bottom-1000x650.png"), data.bytes) } }
                                }
                                val colors = (0 until height step 4).flatMap { y ->
                                    (0 until width step 4).map { x -> decoded.getRGB(x, y) }
                                }.toSet()
                                assertTrue("Rendered controls and text must have visible detail", colors.size > 50)
                                if (label == "catalog-scroll-example" && width == 1000 && height == 650) {
                                    val messageRevisionBeforeDrag = controller.state.value.messageRevision
                                    scene.sendPointerEvent(PointerEventType.Press, Offset(400f, 400f), buttons = PointerButtons(isPrimaryPressed = true), button = PointerButton.Primary)
                                    for (y in listOf(345f, 320f, 285f, 250f, 210f, 170f)) {
                                        scene.sendPointerEvent(PointerEventType.Move, Offset(400f, y), buttons = PointerButtons(isPrimaryPressed = true))
                                        renderFrame(3_000_000_000L + (370 - y.toLong()) * 1_000_000L).close()
                                    }
                                    scene.sendPointerEvent(PointerEventType.Release, Offset(400f, 170f), buttons = PointerButtons(), button = PointerButton.Primary)
                                    repeat(8) { frame -> renderFrame(4_000_000_000L + frame * 100_000_000L).close() }
                                    renderFrame(5_000_000_000L).use { image -> image.encodeToData(EncodedImageFormat.PNG)!!.use { data ->
                                        Files.write(screenshots.resolve("catalog-drag-1000x650.png"), data.bytes)
                                        val after = ImageIO.read(data.bytes.inputStream())
                                        val changedList = (180 until 580).sumOf { y -> (130 until 480).count { x -> after.getRGB(x, y) != decoded.getRGB(x, y) } }
                                        assertTrue("Holding and dragging must scroll the list", changedList > 1000)
                                    } }
                                    assertFalse("Dragging must not trigger country selection", controller.state.value.settings.userProfile.serverSelectionMode == com.noki.vpn.data.ServerSelectionMode.COUNTRY)
                                    assertEquals("Dragging must not invoke a selection action against another catalog", messageRevisionBeforeDrag, controller.state.value.messageRevision)
                                }
                                if (label == "home-example" && width == 1000 && height == 650) {
                                    fun labelled(text: String): SemanticsNode {
                                        fun find(node: SemanticsNode): SemanticsNode? =
                                            if (node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == text } == true ||
                                                node.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(text) == true) node
                                            else node.children.firstNotNullOfOrNull(::find)
                                        return scene.semanticsOwners.firstNotNullOf { find(it.rootSemanticsNode) }
                                    }
                                    val position = labelled("Автовыбор").boundsInRoot.center
                                    scene.sendPointerEvent(PointerEventType.Press, position, buttons = PointerButtons(isPrimaryPressed = true), button = PointerButton.Primary)
                                    scene.sendPointerEvent(PointerEventType.Release, position, buttons = PointerButtons(), button = PointerButton.Primary)
                                    repeat(12) { frame -> renderFrame(3_000_000_000L + frame * 100_000_000L).close() }
                                    val arrow = labelled("Раскрыть серверы").boundsInRoot.center
                                    scene.sendPointerEvent(PointerEventType.Press, arrow, buttons = PointerButtons(isPrimaryPressed = true), button = PointerButton.Primary)
                                    scene.sendPointerEvent(PointerEventType.Release, arrow, buttons = PointerButtons(), button = PointerButton.Primary)
                                    repeat(20) { frame -> renderFrame(4_300_000_000L + frame * 100_000_000L).close() }
                                    renderFrame(5_000_000_000L).use { expanded ->
                                        expanded.encodeToData(EncodedImageFormat.PNG)!!.use { expandedData ->
                                            Files.write(screenshots.resolve("country-expanded-1000x650.png"), expandedData.bytes)
                                            val after = ImageIO.read(expandedData.bytes.inputStream())
                                            val server = labelled("DE-1").boundsInRoot
                                            assertEquals("Inline server row uses the catalog surface", 0xFF272727.toInt(), after.getRGB(server.center.x.toInt(), server.center.y.toInt()))
                                        }
                                    }
                                    fun click(x: Float, y: Float) {
                                        val point = Offset(x, y)
                                        scene.sendPointerEvent(PointerEventType.Press, point, buttons = PointerButtons(isPrimaryPressed = true), button = PointerButton.Primary)
                                        scene.sendPointerEvent(PointerEventType.Release, point, buttons = PointerButtons(), button = PointerButton.Primary)
                                    }
                                    for ((tab, y) in listOf("profile" to 155f, "settings" to 215f, "home" to 95f)) {
                                        click(55f, y)
                                        repeat(8) { frame -> renderFrame(6_000_000_000L + frame * 100_000_000L).close() }
                                        if (tab == "home") awaitEarth(6_900_000_000L)
                                        renderFrame(7_000_000_000L).use { image -> image.encodeToData(EncodedImageFormat.PNG)!!.use { data ->
                                            Files.write(screenshots.resolve("navigation-$tab-1000x650.png"), data.bytes)
                                            val after = ImageIO.read(data.bytes.inputStream())
                                            val changedMain = (60 until 630).sumOf { y -> (110 until 960).count { x -> after.getRGB(x, y) != decoded.getRGB(x, y) } }
                                            if (tab == "home") assertEquals("Home must restore the main controls", 0, changedMain)
                                            else assertTrue("Selected page must fill the main workspace", changedMain > 10000)
                                        } }
                                    }
                                    val protocolCenter = labelled("Протокол: Auto").boundsInRoot.center
                                    scene.sendPointerEvent(PointerEventType.Move, protocolCenter)
                                    repeat(8) { frame -> renderFrame(8_000_000_000L + frame * 100_000_000L).close() }
                                    renderFrame(9_000_000_000L).use { image -> image.encodeToData(EncodedImageFormat.PNG)!!.use { data ->
                                        val hovered = ImageIO.read(data.bytes.inputStream())
                                        val centerY = protocolCenter.y.toInt()
                                        val changed = (centerY - 13 until centerY + 13).sumOf { y -> (145 until 445).count { x -> hovered.getRGB(x, y) != decoded.getRGB(x, y) } }
                                        assertTrue("Hover must gently brighten the button", changed > 1000)
                                        val beforeChannel = decoded.getRGB(145, centerY) and 255
                                        val afterChannel = hovered.getRGB(145, centerY) and 255
                                        assertTrue("Hover brightness stays subtle", afterChannel - beforeChannel in 1..12)
                                    } }
                                    renderedState.value = state.copy(settings = state.settings.copy(userProfile = state.settings.userProfile.copy(
                                        serverSelectionMode = com.noki.vpn.data.ServerSelectionMode.COUNTRY, selectedCountryCode = "DE")))
                                    scene.sendPointerEvent(PointerEventType.Move, Offset(500f, 40f))
                                    repeat(20) { frame -> renderFrame(10_000_000_000L + frame * 100_000_000L).close() }
                                    fun currentImage(time: Long) = renderFrame(time).use { image -> image.encodeToData(EncodedImageFormat.PNG)!!.use { data ->
                                        ImageIO.read(data.bytes.inputStream())
                                    } }
                                    fun hasEarth(node: SemanticsNode): Boolean = node.config.getOrNull(SemanticsProperties.ContentDescription)?.any { it.startsWith("3D") } == true || node.children.any(::hasEarth)
                                    val selectedEarth = currentImage(12_000_000_000L)
                                    for ((index, tab) in listOf("profile" to 155f, "settings" to 215f).withIndex()) {
                                        val time = 13_000_000_000L + index * 2_000_000_000L
                                        click(55f, tab.second)
                                        repeat(3) { renderFrame(time + it * 100_000_000L).close() }
                                        assertFalse("Earth must stop drawing on ${tab.first}", scene.semanticsOwners.any { hasEarth(it.rootSemanticsNode) })
                                        click(55f, 95f)
                                        for (frame in 0..2) {
                                            val returned = currentImage(time + 300_000_000L + frame * 100_000_000L)
                                            assertTrue("The first Home frame retains the loaded renderer", scene.semanticsOwners.any { hasEarth(it.rootSemanticsNode) })
                                            val changed = (70 until 530).sumOf { y -> (520 until 970).count { x -> selectedEarth.getRGB(x, y) != returned.getRGB(x, y) } }
                                            assertEquals("Returning from ${tab.first} retains the selected camera on frame $frame", 0, changed)
                                        }
                                    }
                                }
                            }
                        }
                    } finally { scene.close() }
                }
            }
        } finally {
            var stopped = false
            withTimeout(5_000) { controller.exit { stopped = true }.join() }
            assertTrue(stopped)
            Files.list(directory).use { it.forEach(Files::delete) }
            Files.delete(directory)
        }
    }
}
