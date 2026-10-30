package com.noki.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.window.*
import com.noki.vpn.data.*
import com.noki.vpn.ui.*
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import dev.nucleusframework.application.DecoratedWindow
import dev.nucleusframework.application.NucleusBackend
import dev.nucleusframework.application.NucleusWindow
import dev.nucleusframework.window.WindowScaffold
import dev.nucleusframework.window.TitleBarPlacement
import dev.nucleusframework.window.WindowBackground
import dev.nucleusframework.window.WindowAppearance
import dev.nucleusframework.window.WindowAppearanceMode
import dev.nucleusframework.window.WindowControls
import dev.nucleusframework.window.WindowControlsRenderer
import dev.nucleusframework.window.WindowControlType
import dev.nucleusframework.window.windowDragArea
import dev.nucleusframework.application.nucleusApplication
import dev.nucleusframework.application.SingleInstanceRestoreEffect
import dev.nucleusframework.core.runtime.SingleInstanceManager
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.nio.file.Path
import kotlinx.coroutines.*

fun main(args: Array<String>) {
    if (DesktopBuild.IS_LINUX) {
        require(args.isEmpty() || args.contentEquals(arrayOf("--tray"))) { "Неизвестный параметр запуска Noki" }
        check(LinuxPlatform.uid != 0) { "Запускайте Noki от обычного пользователя" }
        runDesktop(initiallyVisible = "--tray" !in args)
        kotlin.system.exitProcess(0)
    }
    if (args.firstOrNull() == "--prepare-uninstall") {
        if (!WindowsShell.ensureAdministrator(args)) return
        try { prepareWindowsUninstall() }
        catch (error: Throwable) { error.printStackTrace(); kotlin.system.exitProcess(1) }
        return
    }
    if (args.firstOrNull() != "--network-guard") VelopackNative.startup()
    if (args.contentEquals(arrayOf("--platform-preflight"))) {
        try { runWindowsPlatformPreflight() }
        catch (error: Throwable) {
            error.printStackTrace()
            kotlin.system.exitProcess(1)
        }
        kotlin.system.exitProcess(0)
    }
    if (args.firstOrNull() == "--network-guard") {
        try { runNetworkGuard(args.drop(1).toTypedArray()) }
        catch (error: Throwable) {
            System.err.println(error.message ?: "Windows network guard failed")
            kotlin.system.exitProcess(1)
        }
        return
    }
    if (!WindowsShell.ensureAdministrator(args)) return
    SingleInstanceManager.configuration = SingleInstanceManager.Configuration(
        lockFilesDir = Files.createDirectories(WindowsVault.defaultDirectory()), lockIdentifier = "noki-instance")
    System.setProperty("skiko.gpu.resourceCacheLimit", "201326592")
    runDesktop(initiallyVisible = "--tray" !in args)
    kotlin.system.exitProcess(0)
}

internal fun acquireDesktopOwnership(directory: Path = WindowsVault.defaultDirectory()): FileChannel {
    Files.createDirectories(directory)
    val channel = FileChannel.open(directory.resolve("client.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE)
    try {
        check(channel.tryLock() != null) { "Noki Vpn уже запущен. Откройте существующее окно через значок в трее." }
        return channel
    } catch (error: Throwable) { channel.close(); throw error }
}

private fun runDesktop(initiallyVisible: Boolean) = nucleusApplication(backend = NucleusBackend.Tao, enableSingleInstance = !DesktopBuild.IS_LINUX) {
    val ownership = remember { runCatching { acquireDesktopOwnership() } }
    DisposableEffect(Unit) { onDispose { ownership.getOrNull()?.close() } }
    var controller by remember { mutableStateOf<Result<DesktopController>?>(null) }
    LaunchedEffect(ownership) {
        var created: DesktopController? = null
        try {
            controller = withContext(Dispatchers.IO) { ownership.mapCatching {
                if (DesktopBuild.IS_LINUX) LinuxPlatform.helper("refresh")
                val vault = WindowsVault()
                DesktopController(vault, SingBoxRuntime(vault)).also { created = it }
            } }
        } finally { if (controller == null) created?.close() }
    }
    var visible by remember { mutableStateOf(initiallyVisible || ownership.isFailure) }
    val app = controller?.getOrNull()
    val currentApp by rememberUpdatedState(app)
    val updater = remember { (if (DesktopBuild.IS_LINUX) null else VelopackNative.current())?.let { native -> WindowsUpdates(native,
        onFailure = { event, error -> currentApp?.recordDiagnosticFailure(event, error, "update") }) } }
    DisposableEffect(updater) { onDispose { updater?.close() } }
    val update = updater?.state?.collectAsState()?.value
    LaunchedEffect(update?.stage, app) {
        if (update?.stage == DesktopUpdateStage.READY && app != null) {
            var queued = false
            app.cancelWebsiteLogin()
            app.exit {
                updater.applyAndRestart()
                queued = true
                exitApplication()
            }.join()
            if (!queued) updater.stopFailed()
        }
    }
    DisposableEffect(app) {
        app?.diagnostics?.install()
        onDispose { app?.close() }
    }
    val state = app?.state?.collectAsState()?.value ?: DesktopState(loading = true)
    val windowState = rememberWindowState(width = 1050.dp, height = 674.dp)
    val legacyWindows = remember { !DesktopBuild.IS_LINUX && windowsBuildNumber() < 22000 }
    val scope = rememberCoroutineScope()
    var mainWindow by remember { mutableStateOf<NucleusWindow?>(null) }
    var menuPoint by remember { mutableStateOf<DesktopPoint?>(null) }
    LaunchedEffect(visible, menuPoint, mainWindow) {
        if (visible || menuPoint != null || mainWindow != null) return@LaunchedEffect
        delay(1000)
        clearNokiUiCaches()
        org.jetbrains.skia.Graphics.purgeAllCaches()
        // Native image cleaners need a collection after the disposed UI loses its last references.
        withContext(Dispatchers.Default) { System.gc() }
        delay(1000)
        org.jetbrains.skia.Graphics.purgeAllCaches()
        if (!DesktopBuild.IS_LINUX) runCatching { WindowsShell.optimizeProcessHeaps() }
    }
    fun restoreWindow() {
        menuPoint = null; windowState.isMinimized = false; visible = true
        mainWindow?.let { it.setMinimized(false); it.toFront(); it.requestFocus() }
    }
    if (!DesktopBuild.IS_LINUX) SingleInstanceRestoreEffect { restoreWindow() }
    fun quit() {
        app?.cancelWebsiteLogin()
        if (app == null) exitApplication() else app.exit { exitApplication() }
    }
    val open by rememberUpdatedState(::restoreWindow)
    val tray: DesktopTrayHandle? = if (ownership.isSuccess) remember {
        if (DesktopBuild.IS_LINUX) LinuxDesktopTray({ scope.launch { open() } }, { scope.launch { open() } })
        else DesktopTray({ scope.launch { open() } }, { point -> scope.launch { menuPoint = point } })
    } else null
    LaunchedEffect(tray) { if (DesktopBuild.IS_LINUX && tray is LinuxDesktopTray && !tray.available) visible = true }
    val lightTaskbar = tray?.lightTheme?.collectAsState()?.value ?: false
    if (tray != null) {
        DisposableEffect(tray) { onDispose { tray.close() } }
        SideEffect { tray.update(state.running && state.verified, state.language) }
    }
    LaunchedEffect(app) {
        app?.let { controller ->
            val saved = withContext(Dispatchers.IO) { runCatching { controller.loadWindowBounds() }.getOrNull() }
            if (saved != null) {
                val width = saved.optDouble("width"); val height = saved.optDouble("height")
                if (width in 500.0..8192.0 && height in 450.0..8192.0) windowState.size = DpSize(width.toFloat().dp, height.toFloat().dp)
                val x = saved.optDouble("x"); val y = saved.optDouble("y")
                if (x.isFinite() && y.isFinite()) {
                    if (DesktopBuild.IS_LINUX) windowState.position = WindowPosition.Absolute(x.toFloat().dp, y.toFloat().dp)
                    else {
                    val rect = com.sun.jna.platform.win32.WinDef.RECT().apply {
                        left = x.toInt(); top = y.toInt(); right = left + width.toInt(); bottom = top + height.toInt()
                    }
                    if (com.sun.jna.platform.win32.User32.INSTANCE.MonitorFromRect(rect, 0) != null)
                        windowState.position = WindowPosition.Absolute(x.toFloat().dp, y.toFloat().dp)
                    }
                }
                if (saved.optBoolean("maximized")) windowState.placement = WindowPlacement.Maximized
            }
        }
    }
    val windowUi = rememberSaveableStateHolder()
    var reducedMotion by remember { mutableStateOf(!windowsClientAnimations()) }
    val closeWindow: () -> Unit = {
        if (ownership.isSuccess) {
            val bounds = org.json.JSONObject().put("width", windowState.size.width.value).put("height", windowState.size.height.value)
                .put("maximized", windowState.placement == WindowPlacement.Maximized)
            (windowState.position as? WindowPosition.Absolute)?.let { bounds.put("x", it.x.value).put("y", it.y.value) }
            scope.launch(Dispatchers.IO) { runCatching { app?.saveWindowBounds(bounds) }.onFailure { app?.reportMessage("Не удалось сохранить положение окна") } }
            if (DesktopBuild.IS_LINUX && tray is LinuxDesktopTray && !tray.available) windowState.isMinimized = true else visible = false
        } else quit()
    }
    if (visible) windowUi.SaveableStateProvider("window") {
        DecoratedWindow(onCloseRequest = closeWindow, title = "Noki Vpn", state = windowState,
            undecorated = legacyWindows, transparent = legacyWindows,
            minimumSize = DpSize(500.dp, 450.dp),
            icon = nokiPainterResource(if (lightTaskbar) "noki/desktop/taskbar-light.png" else "noki/desktop/taskbar.png")) {
            val nativeWindow = checkNotNull(nucleusWindow.unsafe.taoWindow)
            WindowsTaskbarIcon(nativeWindow, lightTaskbar)
            LaunchedEffect(nucleusWindow) { nucleusWindow.toFront(); nucleusWindow.requestFocus() }
            DisposableEffect(nucleusWindow) {
                mainWindow = nucleusWindow
                onDispose { mainWindow = null }
            }
            DisposableEffect(nucleusWindow, app) {
                app?.attachWindow(nativeWindow.nativeHandle)
                onDispose { app?.attachWindow(null) }
            }
            val minimized by nucleusWindow.minimizedFlow.collectAsState()
            val focused by nucleusWindow.focusFlow.collectAsState()
            LaunchedEffect(focused, minimized) {
                if (focused && !minimized) {
                    reducedMotion = !windowsClientAnimations()
                    updater?.checkNow()
                }
            }
            LaunchedEffect(nucleusWindow, windowState.size, windowState.placement, focused) {
                if (!DesktopBuild.IS_LINUX && !legacyWindows) updateWindowsChrome(com.sun.jna.platform.win32.WinDef.HWND(com.sun.jna.Pointer(nativeWindow.nativeHandle)))
            }
            DisposableEffect(app, minimized) {
                app?.setWindowVisible(!minimized)
                onDispose { app?.setWindowVisible(false) }
            }
            DisposableEffect(app, focused, minimized) {
                app?.setWindowActive(focused && !minimized)
                onDispose { app?.setWindowActive(false) }
            }
            val failure = if (controller?.isFailure == true) {
                if (ownership.isFailure) ownership.exceptionOrNull()?.message ?: "Не удалось открыть папку приложения Windows"
                else if (DesktopBuild.IS_LINUX) "Не удалось открыть хранилище Noki. Разблокируйте системную связку ключей Secret Service."
                else "Не удалось открыть защищённое хранилище Windows. Данные не перезаписаны. Проверьте доступ к папке %LOCALAPPDATA%\\Noki\\Desktop."
            } else null
            val clipboard = remember(nativeWindow) { WindowsClipboard(com.sun.jna.Pointer(nativeWindow.nativeHandle)) }
            CompositionLocalProvider(androidx.compose.ui.platform.LocalClipboard provides clipboard,
                androidx.compose.ui.platform.LocalClipboardManager provides clipboard) {
            // Windows 10 has no DWM rounding; leave only the clipped corners transparent.
            WindowBackground(if (legacyWindows) Color.Transparent else DesktopUiKit.Canvas)
            WindowAppearance(WindowAppearanceMode.Dark)
            WindowScaffold(modifier = if (legacyWindows && windowState.placement == WindowPlacement.Floating)
                Modifier.clip(DesktopUiKit.WindowShape) else Modifier,
                titleBarPlacement = TitleBarPlacement.Overlay(), titleBar = {
                DesktopTheme(null, language = state.language) {
                    PcTitlebar(dragArea = Modifier.windowDragArea(window = nativeWindow), controls = {
                        WindowControls(renderer = WindowControlsRenderer { type, _, onClick ->
                            val icon = when (type) {
                                WindowControlType.Minimize -> "minimize"
                                WindowControlType.Close -> "close"
                                else -> "maximize"
                            }
                            val label = when (type) {
                                WindowControlType.Minimize -> tr(state, "Свернуть", "Minimize")
                                WindowControlType.Close -> tr(state, "Закрыть", "Close")
                                WindowControlType.Restore -> tr(state, "Восстановить", "Restore")
                                else -> tr(state, "Развернуть", "Maximize")
                            }
                            PcWindowControl(icon, label, onClick)
                        })
                    })
                }
            }) {
                CompositionLocalProvider(LocalDesktopUpdate provides update?.takeIf { it.version != null }?.let {
                    DesktopUpdateUi(it) { updater.download() }
                }) {
                    DesktopContent(state, app, failure, earthVisible = !minimized, reducedMotion = reducedMotion,
                        titlebar = { Spacer(Modifier.height(40.dp)) })
                }
            }
            }
        }
    }
    if (app != null && state.user != null && state.settings.advancedSettings.showLocalCoreLogs) {
        DesktopCoreLogsWindow(state, app, lightTaskbar)
    }
    menuPoint?.let { point ->
        var positioned by remember(point) { mutableStateOf(false) }
        DecoratedWindow(onCloseRequest = { menuPoint = null }, title = "Noki Vpn", undecorated = true,
            transparent = true, visible = positioned, resizable = false, alwaysOnTop = true, hiddenFromDock = true,
            state = rememberWindowState(width = 190.dp, height = 120.dp)) {
            val focused by nucleusWindow.focusFlow.collectAsState()
            var wasFocused by remember { mutableStateOf(false) }
            LaunchedEffect(nucleusWindow, point) {
                val native = checkNotNull(nucleusWindow.unsafe.taoWindow)
                val hwnd = com.sun.jna.platform.win32.WinDef.HWND(com.sun.jna.Pointer(native.nativeHandle))
                val user = com.sun.jna.platform.win32.User32.INSTANCE
                // Select the cursor's monitor first, then clamp the actual DPI-scaled native size.
                user.SetWindowPos(hwnd, null, point.x, point.y, 0, 0, 0x15)
                val bounds = com.sun.jna.platform.win32.WinDef.RECT()
                check(user.GetWindowRect(hwnd, bounds))
                val position = desktopTrayMenuPosition(point, bounds.right - bounds.left, bounds.bottom - bounds.top)
                user.SetWindowPos(hwnd, null, position.x, position.y, 0, 0, 0x15)
                positioned = true
            }
            LaunchedEffect(positioned) { if (positioned) { nucleusWindow.toFront(); nucleusWindow.requestFocus() } }
            LaunchedEffect(focused) { if (focused) wasFocused = true else if (wasFocused) menuPoint = null }
            fun action(block: () -> Unit) { menuPoint = null; block() }
            DesktopTheme(null, language = state.language) {
                DesktopTrayMenu(state, onOpen = { action(::restoreWindow) },
                    onToggle = { action { if (state.running || state.connecting) app?.disconnect() else app?.connect() } },
                    onExit = { action(::quit) })
            }
        }
    }
}

@Composable private fun DesktopCoreLogsWindow(state: DesktopState, app: DesktopController, lightTaskbar: Boolean) {
    val windowState = rememberWindowState(width = 1000.dp, height = 600.dp)
    DecoratedWindow(onCloseRequest = {
        app.updateSettings { it.copy(advancedSettings = it.advancedSettings.copy(showLocalCoreLogs = false)) }
    }, title = tr(state, "Логи ядра — Noki Vpn", "Core logs — Noki Vpn"), state = windowState,
        minimumSize = DpSize(640.dp, 360.dp),
        icon = nokiPainterResource(if (lightTaskbar) "noki/desktop/taskbar-light.png" else "noki/desktop/taskbar.png")) {
        val nativeWindow = checkNotNull(nucleusWindow.unsafe.taoWindow)
        WindowsTaskbarIcon(nativeWindow, lightTaskbar)
        val owner = remember(nativeWindow) { com.sun.jna.Pointer(nativeWindow.nativeHandle) }
        val clipboard = remember(owner) { WindowsClipboard(owner) }
        val minimized by nucleusWindow.minimizedFlow.collectAsState()
        LaunchedEffect(nucleusWindow) { nucleusWindow.toFront(); nucleusWindow.requestFocus() }
        CompositionLocalProvider(androidx.compose.ui.platform.LocalClipboard provides clipboard,
            androidx.compose.ui.platform.LocalClipboardManager provides clipboard) {
            WindowBackground(DesktopUiKit.Canvas)
            WindowAppearance(WindowAppearanceMode.Dark)
            DesktopTheme(null, language = state.language) {
                WindowScaffold(titleBarPlacement = TitleBarPlacement.Overlay(), titleBar = {
                    PcTitlebar(dragArea = Modifier.windowDragArea(window = nativeWindow), controls = { WindowControls() })
                }) {
                    Column(Modifier.fillMaxSize().background(DesktopUiKit.Canvas)) {
                        Spacer(Modifier.height(40.dp))
                        DesktopCoreLogPanel(state, app, !minimized) { WindowsShell.copyText(owner, it) }
                    }
                }
            }
        }
    }
}

@Composable private fun WindowsTaskbarIcon(window: dev.nucleusframework.window.tao.TaoWindow, light: Boolean) {
    if (DesktopBuild.IS_LINUX) return
    LaunchedEffect(window, light) {
        windowsTaskbarIcon({ window.nativeHandle }, light)
    }
}

@Composable internal fun DesktopContent(
    state: DesktopState,
    app: DesktopController?,
    failure: String? = null,
    initialPage: DesktopPage = DesktopPage.HOME,
    earthVisible: Boolean = true,
    reducedMotion: Boolean = false,
    titlebar: @Composable () -> Unit = { PcTitlebar() },
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
    val sceneVisible = earthVisible && DesktopUiKit.sceneFits(maxWidth, maxHeight)
    val earthNeeded = sceneVisible && !state.earthDisabled && failure == null && app != null && state.user != null
    val earth by rememberEarthRenderer(earthNeeded)
    var gpuCleanupPending by remember(earthNeeded) { mutableStateOf(true) }
    if (earthNeeded || gpuCleanupPending) {
    val gpu = dev.nucleusframework.window.tao.rememberTaoGpuRenderContext() as? dev.nucleusframework.window.tao.TaoOpenGlRenderContext
    LaunchedEffect(gpu, earthNeeded) {
        if (gpu == null) return@LaunchedEffect
        // Replay the replacement background before purging textures held by the previous display list.
        if (!earthNeeded) withFrameNanos { }
        withFrameNanos {
            gpu.withContextCurrent {
                if (!earthNeeded) {
                    gpu.skiaContext.flush().submit(true)
                    gpu.skiaContext.resourceCacheLimit = 0
                    org.jetbrains.skia.Graphics.purgeResourceCache()
                }
                gpu.skiaContext.resourceCacheLimit = if (earthNeeded) 192L * 1024 * 1024 else 32L * 1024 * 1024
            }
        }
        gpuCleanupPending = false
    }
    }
    val backdrop = rememberLayerBackdrop()
    DesktopTheme(backdrop, language = state.language) {
        Box(Modifier.fillMaxSize().background(DesktopUiKit.Canvas)) {
            if (failure == null && app != null && state.user != null) {
                DesktopWorkspace(state, app, initialPage, earthVisible, reducedMotion,
                    earth?.getOrNull(), earth?.isFailure == true, titlebar)
            } else {
                Box(Modifier.fillMaxSize().layerBackdrop(backdrop).background(DesktopUiKit.Canvas))
                Column(Modifier.fillMaxSize()) {
                    titlebar()
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        when {
                            failure != null -> PcText(failure, Modifier.align(Alignment.Center).padding(30.dp), color = DesktopUiKit.Error)
                            app == null -> PcText("Открываем Noki Vpn…", Modifier.align(Alignment.Center), color = DesktopUiKit.Muted)
                            state.user == null -> DesktopLoginScreen(state, app)
                        }
                    }
                }
            }
            if (state.networkBlocked && !state.running && app != null) Column(Modifier.align(Alignment.BottomCenter)
                .padding(15.dp).widthIn(max = 480.dp).pcCard().padding(15.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                PcText(tr(state, "Kill switch блокирует доступ в Интернет", "Kill switch is blocking Internet access"), size = 14)
                PcButton(tr(state, "Снять блокировку", "Restore Internet access"), Modifier.fillMaxWidth(), enabled = !state.loading, onClick = app::releaseNetworkGuard)
            }
        }
    }
    }
}

@Composable internal fun rememberEarthRenderer(needed: Boolean): State<Result<EarthRenderer>?> = key(needed) {
    produceState<Result<EarthRenderer>?>(null) {
        if (!needed) return@produceState
        var loaded: EarthRenderer? = null
        try {
            withContext(Dispatchers.Default) { loaded = EarthRenderer() }
            ensureActive()
            value = Result.success(checkNotNull(loaded))
            awaitCancellation()
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { ensureActive(); value = Result.failure(error) }
        finally { value = null; loaded?.close() }
    }
}

/** Nucleus owns the frame; Windows 11 supplies rounded corners without a coloured outline. */
private fun updateWindowsChrome(hwnd: com.sun.jna.platform.win32.WinDef.HWND) {
    val attribute = com.sun.jna.Function.getFunction("dwmapi", "DwmSetWindowAttribute", com.sun.jna.Function.ALT_CONVENTION)
    com.sun.jna.Memory(4).use { value ->
        value.setInt(0, 2)
        attribute.invokeInt(arrayOf(hwnd, 33, value, 4)) // DWMWA_WINDOW_CORNER_PREFERENCE, DWMWCP_ROUND.
        value.setInt(0, -2)
        attribute.invokeInt(arrayOf(hwnd, 34, value, 4)) // DWMWA_BORDER_COLOR, DWMWA_COLOR_NONE.
    }
}

private fun windowsClientAnimations(): Boolean = if (DesktopBuild.IS_LINUX) true else com.sun.jna.Memory(4).use { enabled ->
    val result = com.sun.jna.Function.getFunction("user32", "SystemParametersInfoW", com.sun.jna.Function.ALT_CONVENTION)
        .invokeInt(arrayOf(0x1042, 0, enabled, 0)) // SPI_GETCLIENTAREAANIMATION.
    result == 0 || enabled.getInt(0) != 0
}
