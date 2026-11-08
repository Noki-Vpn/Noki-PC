package com.noki.desktop

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.LocalScrollbarStyle
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.material3.Slider
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.automirrored.filled.RotateLeft
import androidx.compose.material.icons.automirrored.filled.RotateRight
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AttachFile
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.DoneAll
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.ui.input.key.*
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.noki.vpn.data.*
import com.noki.vpn.*
import com.noki.vpn.R
import com.noki.vpn.ui.nokiPainterResource
import com.noki.vpn.ui.nokiAvatarImage
import com.noki.vpn.ui.AccountPresentationPolicy
import com.noki.vpn.ui.AccountPlanTitle
import com.noki.vpn.ui.CountryFlagMarker
import com.noki.vpn.ui.rememberConnectionTimeLabel
import com.noki.vpn.ui.formatMetricValue
import com.noki.vpn.ui.StatsPeriod
import com.noki.vpn.ui.aggregateDailyStats
import com.noki.vpn.ui.statsPeriodLabel
import com.noki.vpn.ui.formatStatsDuration
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import java.net.URI
import java.nio.file.Path
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible

internal enum class DesktopPage { HOME, PROFILE, SETTINGS, SERVERS, DEVICES, EXTRA_DEVICES, PLANS, STATS, SECURITY, PERSONALIZATION, ADVANCED, ROUTING, MULTIHOP, PROTOCOL, SUPPORT, NOTIFICATIONS, PAYMENTS }

@Composable internal fun DesktopLoginScreen(state: DesktopState, app: DesktopController) {
    var codeLogin by rememberSaveable { mutableStateOf(false) }
    var code by rememberSaveable { mutableStateOf("") }
    BoxWithConstraints(Modifier.fillMaxSize().background(DesktopUiKit.Canvas)) {
        val wide = maxWidth >= 760.dp
        // The door's centre is at 71.6% of the original site render.
        val paneWidth = maxWidth.value / 2
        val imageWidth = maxOf(paneWidth, maxHeight.value * 16 / 9)
        val overflow = imageWidth - paneWidth
        val doorBias = if (overflow > 0) ((imageWidth * .716f - paneWidth / 2) / overflow * 2 - 1).coerceIn(-1f, 1f) else 0f
        Row(Modifier.fillMaxSize()) {
        BoxWithConstraints(Modifier.weight(1f).fillMaxHeight()) {
        Column(Modifier.align(Alignment.Center).offset(x = (-1).dp, y = (-19).dp).widthIn(max = 329.dp)
            .width((maxWidth - 64.dp).coerceAtMost(329.dp)), horizontalAlignment = Alignment.CenterHorizontally) {
            Image(nokiPainterResource("noki/desktop/login-logo.svg"), "Noki", Modifier.size(200.dp, 77.dp))
            Spacer(Modifier.height(60.dp))
            if (state.websiteLoginPending) {
                PcText(tr(state, "Завершите вход или регистрацию на сайте", "Finish signing in or registering on the website"), size = 14, textAlign = TextAlign.Center)
                Spacer(Modifier.height(20.dp))
                LoginButton(tr(state, "Отмена", "Cancel"), true, app::cancelWebsiteLogin)
            } else if (codeLogin) {
                PcInput(code, { code = it.take(32) }, tr(state, "Код приглашения", "Invite code"), Modifier.fillMaxWidth(), enabled = !state.loading)
                Spacer(Modifier.height(20.dp))
                LoginButton(tr(state, "Войти", "Sign in"), !state.loading && code.isNotBlank()) { app.loginFromCode(code) }
                Spacer(Modifier.height(36.dp))
                LoginLink(tr(state, "Назад", "Back"), !state.loading) { codeLogin = false }
            } else {
                LoginButton(tr(state, "Войти", "Sign in"), !state.loading) { app.loginFromWebsite() }
                Spacer(Modifier.height(20.dp))
                LoginButton(tr(state, "Зарегистрироваться", "Register"), !state.loading) { app.loginFromWebsite() }
                Spacer(Modifier.height(36.dp))
                LoginLink(tr(state, "Войти по коду", "Sign in with a code"), !state.loading) { codeLogin = true }
            }
            if (state.loading && !state.websiteLoginPending) {
                Spacer(Modifier.height(16.dp))
                CircularProgressIndicator(Modifier.size(20.dp), color = DesktopUiKit.Accent, strokeWidth = 2.dp)
            }
        }
        }
        if (wide) {
            SiteRenderViewport(Modifier.weight(1f).fillMaxHeight(), visible = true,
                description = tr(state, "Рендер с сайта Noki", "Noki website render"), alignment = androidx.compose.ui.BiasAlignment(doorBias, 0f))
        }
        }
        state.message?.let { PcText(it, Modifier.align(Alignment.BottomCenter).padding(20.dp), size = 12, color = DesktopUiKit.Error, textAlign = TextAlign.Center) }
    }
}

@Composable private fun LoginButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(20.dp)
    Box(Modifier.fillMaxWidth().height(50.dp).background(DesktopUiKit.Glass, shape)
        .border(.5.dp, Color.White.copy(alpha = .08f), shape).pcClickable(enabled = enabled, shape = shape, onClick = onClick),
        contentAlignment = Alignment.Center) { PcText(label, size = 15, lineHeight = 22) }
}

@Composable private fun LoginLink(label: String, enabled: Boolean, onClick: () -> Unit) {
    androidx.compose.material3.Text(label, Modifier.pcClickable(enabled = enabled, hoverColor = Color.Transparent, onClick = onClick),
        color = DesktopUiKit.Text, fontFamily = com.noki.vpn.ui.ManropeFontFamily, fontSize = 16.sp, lineHeight = 22.sp,
        fontWeight = FontWeight.Normal, letterSpacing = 0.sp)
}

@Composable internal fun DesktopWorkspace(state: DesktopState, app: DesktopController, initialPage: DesktopPage, earthVisible: Boolean = true,
    reducedMotion: Boolean = false,
    titlebar: @Composable () -> Unit = { PcTitlebar() }) {
    var requestedPage by rememberSaveable { mutableStateOf(if (initialPage == DesktopPage.SETTINGS) DesktopPage.DEVICES else initialPage) }
    val isFreePlan = com.noki.vpn.SettingsPreparedStatePolicy.isFreePlan(state.androidState())
    val page = if (isFreePlan && requestedPage == DesktopPage.MULTIHOP) DesktopPage.SERVERS
        else if (!isFreePlan && requestedPage == DesktopPage.SERVERS && state.settings.advancedSettings.multiHop.enabled) DesktopPage.MULTIHOP else requestedPage
    var dialog by remember { mutableStateOf<(@Composable () -> Unit)?>(null) }
    var updateDialog by rememberSaveable { mutableStateOf(false) }
    val update = LocalDesktopUpdate.current
    var history by rememberSaveable { mutableStateOf(listOf<DesktopPage>()) }
    fun navigate(destination: DesktopPage) {
        if (destination == DesktopPage.PLANS || destination == DesktopPage.PAYMENTS) {
            app.openAccountWebsite(if (destination == DesktopPage.PLANS) "plans" else "payments")
            return
        }
        val target = when {
            destination == DesktopPage.SETTINGS -> DesktopPage.DEVICES
            else -> destination
        }
        dialog = null
        if (state.inviteOpen) app.clearInvite()
        if (state.incyLinks != null) app.clearIncyLinks()
        if (target == requestedPage) return
        history = if (target in setOf(DesktopPage.HOME, DesktopPage.PROFILE, DesktopPage.DEVICES)) emptyList() else history + requestedPage
        requestedPage = target
    }
    val onPage: (DesktopPage) -> Unit = ::navigate
    fun selectLocation(selection: HopSelection) {
        val id = if (selection.kind == ServerSelectionMode.SERVER) selection.nodeId.orEmpty() else state.connectionLocations.firstOrNull { it.countryCode == selection.countryCode }?.code.orEmpty()
        if (state.loading || selection.kind != ServerSelectionMode.AUTO && id.isBlank()) return
        if (!state.running) app.selectLocation(selection.kind, id)
        else dialog = { PcDialog(tr(state, "Сменить сервер?", "Change server?"), { dialog = null }, tr(state, "Подключиться", "Connect"),
            onConfirm = { app.selectLocation(selection.kind, id); dialog = null }) {
            PcText(tr(state, "Текущее соединение будет перезапущено", "The current connection will restart"), size = 14)
        } }
    }
    val displayed = state.displayedSettings
    val displayedLocations = state.locationsFor(displayed.advancedSettings.ruRelayEnabled)
    val selection = displayed.userProfile
    val selectedCountry = if (selection.serverSelectionMode == ServerSelectionMode.AUTO && !state.hasEstablishedTunnel) null
        else if (state.running || selection.serverSelectionMode == ServerSelectionMode.SERVER)
        displayedLocations.firstOrNull { country -> country.servers.any { it.id == state.selectedServerId } }?.countryCode
        else if (selection.serverSelectionMode == ServerSelectionMode.COUNTRY) selection.selectedCountryCode else null
    val multiHop = state.settings.advancedSettings.multiHop
    val displayedMultiHop = displayed.advancedSettings.multiHop
    val routeKnown = !state.running || state.connectionSettings != null
    fun hopCountry(hop: HopSelection?): String? = hopLocation(state, hop, displayedLocations)?.countryCode
    val backdrop = key(state.earthDisabled) { rememberLayerBackdrop() }
    val style = LocalDesktopStyle.current
    val homePages = setOf(DesktopPage.HOME, DesktopPage.SERVERS, DesktopPage.MULTIHOP, DesktopPage.PROTOCOL)
    val profilePages = listOf(DesktopPage.PROFILE, DesktopPage.NOTIFICATIONS, DesktopPage.PLANS, DesktopPage.PAYMENTS)
    val profileSection = page in profilePages || (page == DesktopPage.SUPPORT && DesktopPage.PROFILE in history)
    val selectedTab = when {
        page in homePages -> DesktopPage.HOME
        profileSection -> DesktopPage.PROFILE
        else -> DesktopPage.SETTINGS
    }
    val sectionPages = if (profileSection) profilePages else listOf(DesktopPage.DEVICES, DesktopPage.ADVANCED, DesktopPage.SECURITY, DesktopPage.PERSONALIZATION)
    val selectedSection = when (page) {
        DesktopPage.ROUTING, DesktopPage.STATS -> DesktopPage.ADVANCED
        DesktopPage.EXTRA_DEVICES -> DesktopPage.DEVICES
        else -> page
    }
    fun menuIcon(target: DesktopPage): Int? = when (target) {
        DesktopPage.PROFILE -> R.raw.account_nav_profile
        DesktopPage.PLANS -> R.raw.account_plans
        DesktopPage.PAYMENTS -> R.raw.account_history
        DesktopPage.DEVICES -> R.drawable.settings_devices_icon
        DesktopPage.ADVANCED -> R.drawable.settings_advanced_icon
        DesktopPage.SECURITY -> R.drawable.settings_security_icon
        DesktopPage.PERSONALIZATION -> R.drawable.settings_personalization_icon
        DesktopPage.SUPPORT -> R.drawable.settings_support_icon
        else -> null
    }
    LaunchedEffect(page) {
        when (page) {
            DesktopPage.SERVERS, DesktopPage.MULTIHOP -> app.refreshServerLatencies()
            DesktopPage.DEVICES, DesktopPage.EXTRA_DEVICES -> app.loadDevices()
            DesktopPage.NOTIFICATIONS -> app.loadNotifications(markRead = true)
            else -> Unit
        }
    }
    LaunchedEffect(state.checkout.launchUrl) {
        state.checkout.launchUrl?.let { url ->
            runCatching { openDesktopUrl(url) }.onFailure { app.payments.browserFailed() }
            app.payments.consumeLaunchUrl()
        }
    }
    CompositionLocalProvider(LocalDesktopStyle provides style.copy(backdrop = backdrop)) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val sceneVisible = earthVisible && DesktopUiKit.sceneFits(maxWidth, maxHeight)
            val earthNeeded = sceneVisible && !state.earthDisabled && state.user != null && page in homePages
            val earth by rememberEarthRenderer(earthNeeded)
            val earthRenderer = earth?.getOrNull()
            DesktopGraphicsCache(earthNeeded)
            Box(Modifier.fillMaxSize().layerBackdrop(backdrop)) {
                Box(Modifier.fillMaxSize().background(DesktopUiKit.Canvas))
                if (state.earthDisabled) {
                    if (sceneVisible && page in homePages) CountryMapViewport(Modifier.fillMaxSize().drawWithContent {
                        clipRect(top = 40.dp.toPx()) { this@drawWithContent.drawContent() }
                    }, selectedCountryCode = if (routeKnown && displayedMultiHop.enabled) hopCountry(displayedMultiHop.exit) else selectedCountry,
                        entryCountryCode = if (routeKnown && displayedMultiHop.enabled) hopCountry(displayedMultiHop.entry) else null,
                        description = tr(state, "2D-карта: выбранная страна и маршрут", "2D map: selected country and route"))
                }
                else EarthViewport(Modifier.fillMaxSize().drawWithContent { clipRect(top = 40.dp.toPx()) { this@drawWithContent.drawContent() } },
                    tr(state, "3D-Земля: выбранная страна и маршрут мультихопа", "3D Earth: selected country and MultiHop route"),
                    tr(state, "Не удалось отобразить 3D-Землю", "Could not render 3D Earth"),
                    renderer = earthRenderer, failedToLoad = earth?.isFailure == true,
                    selectedCountryCode = if (routeKnown && displayedMultiHop.enabled) hopCountry(displayedMultiHop.exit) else selectedCountry,
                    entryCountryCode = if (routeKnown && displayedMultiHop.enabled) hopCountry(displayedMultiHop.entry) else null,
                    visible = sceneVisible && page in homePages, reducedMotion = reducedMotion || state.reducedMotion)
            }
            Column(Modifier.fillMaxSize()) {
                titlebar()
                BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                    val wide = maxWidth >= 800.dp
                    if (page in homePages) {
                        if (page != DesktopPage.SERVERS && page != DesktopPage.MULTIHOP)
                            HomeConnectionPane(state, app, Modifier.fillMaxSize(), protocolOpen = page == DesktopPage.PROTOCOL,
                                uiActive = earthVisible, reducedMotion = reducedMotion || state.reducedMotion, onPage = onPage,
                                onConnectionSettings = { dialog = { ConnectionSettingsDialog(app) { dialog = null } } })
                        if (page == DesktopPage.SERVERS || page == DesktopPage.MULTIHOP) {
                            Column(Modifier.padding(start = 110.dp, top = 32.dp, bottom = 32.dp, end = 20.dp)
                                .widthIn(max = DesktopUiKit.HomePanelWidth).fillMaxWidth().fillMaxHeight()
                                .pcGlass(DesktopUiKit.Glass, DesktopUiKit.PanelShape, blurRadius = 63.dp, lightIntensity = .18f, rimWidth = .5.dp, depth = 14.dp, refraction = .73f).padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(15.dp)) {
                                if (page == DesktopPage.SERVERS) {
                                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                        Box(Modifier.weight(1f)) {
                                            PcPageHeading(tr(state, "Локации", "Locations")) { navigate(DesktopPage.HOME) }
                                        }
                                        ServerRefreshButton(state, earthVisible, reducedMotion || state.reducedMotion) { app.refresh().await() }
                                    }
                                    CountryCatalog(state, Modifier.weight(1f).fillMaxWidth(),
                                        HopSelection(state.settings.userProfile.serverSelectionMode, state.settings.userProfile.selectedCountryCode, state.selectedServerId), onSelect = ::selectLocation)
                                    if (!isFreePlan) Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).pcCard(DesktopUiKit.Surface, DesktopUiKit.MenuShape).border(.5.dp, Color.White.copy(alpha = .08f), DesktopUiKit.MenuShape).pcClickable(shape = DesktopUiKit.MenuShape) { navigate(DesktopPage.MULTIHOP) }.padding(horizontal = 16.dp, vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                            PcText(tr(state, "Мультихоп", "MultiHop"), size = 14, lineHeight = 20)
                                            PcText(tr(state, "Выключен", "Disabled"), size = 11, lineHeight = 14, color = DesktopUiKit.Muted)
                                        }
                                        PcIcon("chevronRight", null, Modifier.size(8.dp, 11.dp))
                                    }
                                } else MultiHopPage(state, app, onBack = { navigate(if (multiHop.enabled) DesktopPage.HOME else DesktopPage.SERVERS) }, onDone = ::navigate)
                            }
                        }
                    } else {
                        Row(Modifier.fillMaxSize().padding(start = 110.dp, end = 20.dp, top = 32.dp, bottom = 32.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                            if (wide) Column(Modifier.width(280.dp).fillMaxHeight().pcCard(DesktopUiKit.Surface, DesktopUiKit.PanelShape).padding(horizontal = 16.dp, vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(15.dp)) {
                                Box(Modifier.fillMaxWidth().height(32.dp), contentAlignment = Alignment.CenterStart) {
                                    PcText(pageTitle(if (profileSection) DesktopPage.PROFILE else DesktopPage.SETTINGS, state.language), size = DesktopUiKit.SectionTitleSize, color = Color.White)
                                }
                                for (target in sectionPages) Row(Modifier.fillMaxWidth().height(44.dp)
                                    .pcGlass(if (selectedSection == target) style.selected else DesktopUiKit.NestedSurface,
                                        DesktopUiKit.MenuShape, blurRadius = 4.dp, lightIntensity = DesktopUiKit.ControlHighlight)
                                    .pcClickable(shape = DesktopUiKit.MenuShape) { navigate(target) }.padding(horizontal = 15.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    if (target == DesktopPage.NOTIFICATIONS) Icon(Icons.Outlined.Notifications, null, Modifier.size(16.dp), tint = DesktopUiKit.Text)
                                    else menuIcon(target)?.let { Image(nokiPainterResource(it), null, Modifier.size(16.dp)) }
                                    PcText(pageTitle(target, state.language), Modifier.weight(1f), size = 13, maxLines = 1, color = Color.White)
                                    if (target == DesktopPage.PLANS || target == DesktopPage.PAYMENTS)
                                        Icon(Icons.AutoMirrored.Rounded.OpenInNew, null, Modifier.size(16.dp), tint = DesktopUiKit.Muted)
                                }
                                Spacer(Modifier.weight(1f))
                                PcText(DesktopDiagnostics.appVersion, Modifier.fillMaxWidth(), size = 11,
                                    color = DesktopUiKit.Muted, textAlign = TextAlign.Center)
                                CompositionLocalProvider(LocalPcCardColor provides DesktopUiKit.NestedSurface, LocalDesktopStyle provides LocalDesktopStyle.current.copy(selected = style.selected)) {
                                    PcButton(tr(state, "Поддержка", "Support"), Modifier.fillMaxWidth().height(50.dp), selected = page == DesktopPage.SUPPORT,
                                        glass = true, glassBlurRadius = 4.dp, glassLightIntensity = DesktopUiKit.ControlHighlight, shape = DesktopUiKit.MenuShape, glassColor = DesktopUiKit.NestedSurface,
                                        iconResource = R.drawable.settings_support_icon, iconSize = 20.dp, textColor = Color.White, onClick = { navigate(DesktopPage.SUPPORT) })
                                }
                            }
                            Column(Modifier.weight(1f).fillMaxHeight().pcCard(DesktopUiKit.Surface, DesktopUiKit.PanelShape).padding(horizontal = 24.dp, vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                CompositionLocalProvider(LocalPcCardColor provides DesktopUiKit.NestedSurface) {
                                    if (!wide) CompositionLocalProvider(LocalDesktopStyle provides LocalDesktopStyle.current.copy(selected = style.selected)) {
                                    Row(Modifier.fillMaxWidth().pcHorizontalScroll(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        for (target in sectionPages + DesktopPage.SUPPORT) PcButton(pageTitle(target, state.language), Modifier.height(40.dp), compact = true,
                                            glass = true, glassBlurRadius = 4.dp, glassLightIntensity = DesktopUiKit.ControlHighlight,
                                            selected = selectedSection == target, iconResource = menuIcon(target), iconVector = if (target == DesktopPage.NOTIFICATIONS) Icons.Outlined.Notifications else null, iconSize = 16.dp, onClick = { navigate(target) })
                                    }
                                    }
                                    key(page, state.inviteOpen, state.incyLinks) {
                                        val scroll = rememberScrollState()
                                        Box(Modifier.weight(1f).fillMaxWidth()) {
                                            Column(Modifier.fillMaxSize().then(if (page == DesktopPage.SUPPORT && !state.inviteOpen && state.incyLinks == null) Modifier else Modifier.verticalScroll(scroll)), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                                when {
                                                    state.inviteOpen -> ConnectionInvitePage(state, app)
                                                    state.incyLinks != null -> IncyConnectionPage(state, app)
                                                    else -> when (page) {
                                                        DesktopPage.PROFILE -> ProfilePage(state, app, onPage) { dialog = it }
                                                        DesktopPage.SETTINGS, DesktopPage.DEVICES, DesktopPage.EXTRA_DEVICES -> DevicesPage(state, app, page == DesktopPage.EXTRA_DEVICES, onPage) { dialog = it }
                                                        DesktopPage.NOTIFICATIONS -> NotificationsPage(state, app, onPage)
                                                        DesktopPage.SECURITY -> SecurityPage(state, app) { dialog = it }
                                                        DesktopPage.PERSONALIZATION -> PersonalizationPage(state, app) { dialog = it }
                                                        DesktopPage.ADVANCED -> AdvancedPage(state, app, onPage)
                                                        DesktopPage.ROUTING -> RoutingPage(state, app, onPage) { dialog = it }
                                                        DesktopPage.STATS -> StatsPage(state, onPage)
                                                        DesktopPage.SUPPORT -> SupportPage(state, app, earthVisible)
                                                        else -> Unit
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    PcNavigation(selectedTab, Modifier.align(Alignment.TopStart).padding(start = 20.dp,
                        top = 32.dp, bottom = 32.dp).fillMaxHeight(), state,
                        onUpdate = { updateDialog = true; update?.install?.invoke() }, onPage = onPage)
                }
            }
            state.message?.let { message ->
                Box(Modifier.fillMaxSize().padding(top = 40.dp).clipToBounds().zIndex(1f)) {
                    Row(Modifier.align(Alignment.TopCenter).padding(12.dp).widthIn(max = 580.dp)
                        .shadow(12.dp, DesktopUiKit.MenuShape).pcCard(Color(0xFF4A4A4A), DesktopUiKit.MenuShape).border(1.dp, Color.White.copy(alpha = .16f), DesktopUiKit.MenuShape).padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(15.dp)) {
                        PcText(message, Modifier.weight(1f), size = 14)
                        Box(Modifier.size(28.dp).pcClickable(onClick = app::clearMessage), contentAlignment = Alignment.Center) { PcIcon("close", tr(state, "Закрыть", "Close"), Modifier.size(10.dp)) }
                    }
                }
            }
            dialog?.invoke()
            if (updateDialog && update != null) DesktopUpdateDialog(state, update) { updateDialog = false }
            CredentialDialog(state, app)
        }
    }
}

private fun hopLocation(state: DesktopState, hop: HopSelection?, locations: List<ServerLocation> = state.connectionLocations): ServerLocation? = when (hop?.kind) {
    ServerSelectionMode.COUNTRY -> locations.firstOrNull { it.countryCode.equals(hop.countryCode, true) }
    ServerSelectionMode.SERVER -> locations.firstOrNull { country -> country.servers.any { it.id == hop.nodeId } }
    else -> null
}
private fun hopLabel(state: DesktopState, hop: HopSelection?, locations: List<ServerLocation> = state.connectionLocations): String = when (hop?.kind) {
    ServerSelectionMode.COUNTRY -> hopLocation(state, hop, locations)?.country ?: hop.countryCode.orEmpty()
    ServerSelectionMode.SERVER -> hopLocation(state, hop, locations)?.servers?.firstOrNull { it.id == hop.nodeId }?.name ?: tr(state, "Сервер недоступен", "Server unavailable")
    else -> tr(state, "Не выбран", "Not selected")
}

internal fun tr(state: DesktopState, russian: String, english: String) = if (state.language == AppLanguage.RU) russian else english
private fun pageTitle(page: DesktopPage, language: AppLanguage): String {
    val labels = when (page) {
        DesktopPage.HOME -> "Главная" to "Home"; DesktopPage.PROFILE -> "Профиль" to "Profile"; DesktopPage.SETTINGS -> "Настройки" to "Settings"
        DesktopPage.SERVERS -> "Серверы" to "Servers"
        DesktopPage.PROTOCOL -> "Протокол" to "Protocol"
        DesktopPage.DEVICES -> "Устройства" to "Devices"; DesktopPage.EXTRA_DEVICES -> "Доп. устройства" to "Extra devices"
        DesktopPage.PLANS -> "Тарифы" to "Plans"; DesktopPage.STATS -> "Статистика" to "Statistics"
        DesktopPage.SECURITY -> "Безопасность" to "Security"; DesktopPage.PERSONALIZATION -> "Персонализация" to "Personalization"
        DesktopPage.ADVANCED -> "Расширенные настройки" to "Advanced settings"; DesktopPage.ROUTING -> "Маршрутизация" to "Routing"
        DesktopPage.MULTIHOP -> "Мультихоп" to "MultiHop"; DesktopPage.SUPPORT -> "Поддержка" to "Support"
        DesktopPage.NOTIFICATIONS -> "Уведомления" to "Notifications"; DesktopPage.PAYMENTS -> "История платежей" to "Payment history"
    }
    return if (language == AppLanguage.RU) labels.first else labels.second
}

@Composable private fun PcPageHeading(title: String, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.size(32.dp).pcClickable(onClick = onBack), contentAlignment = Alignment.Center) {
            PcIcon("chevronRight", if (LocalDesktopLanguage.current == AppLanguage.RU) "Назад" else "Back",
                Modifier.size(10.dp, 14.dp).graphicsLayer { rotationZ = 180f })
        }
        PcText(title, Modifier.weight(1f), size = DesktopUiKit.SectionTitleSize)
    }
}

@Composable private fun PcMenuRow(label: String, detail: String? = null, enabled: Boolean = true,
    iconResource: Int? = null, leadingIcon: (@Composable () -> Unit)? = null, externalLink: Boolean = false, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 76.dp).pcCard()
        .pcClickable(enabled = enabled, onClick = onClick).padding(horizontal = 20.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        if (leadingIcon != null) { leadingIcon(); Spacer(Modifier.width(14.dp)) }
        else iconResource?.let { Image(nokiPainterResource(it), null, Modifier.size(24.dp)); Spacer(Modifier.width(14.dp)) }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            PcText(label, size = 14, lineHeight = 20)
            detail?.let { PcText(it, size = 12, lineHeight = 16, color = Color(0xFFB9B9B9)) }
        }
        Spacer(Modifier.width(14.dp))
        if (externalLink) Icon(Icons.AutoMirrored.Rounded.OpenInNew, null, Modifier.size(16.dp), tint = DesktopUiKit.Muted)
        else PcIcon("chevronRight", null, Modifier.size(14.dp))
    }
}

private fun copyText(value: String, app: DesktopController) {
    runCatching { app.copyText(value) }
        .onSuccess { app.reportMessage(tr(app.state.value, "Скопировано в буфер обмена", "Copied to clipboard")) }
        .onFailure { app.reportMessage(tr(app.state.value, "Не удалось скопировать в буфер обмена", "Could not copy to clipboard")) }
}

@Composable private fun ProfilePage(state: DesktopState, app: DesktopController, onPage: (DesktopPage) -> Unit, show: ((@Composable () -> Unit)?) -> Unit) {
    val profile = AccountPresentationPolicy.prepare(state.androidState())
    PcText(tr(state, "Профиль", "Profile"), size = DesktopUiKit.SectionTitleSize)
    CompositionLocalProvider(LocalPcCardColor provides DesktopUiKit.NestedSurface) {
        Row(Modifier.fillMaxWidth().pcCard().padding(20.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            val avatar = nokiAvatarImage(state.avatarPath)
            Box(Modifier.size(72.dp).clip(RoundedCornerShape(16.dp)).pcClickable(shape = RoundedCornerShape(16.dp)) { onPage(DesktopPage.PERSONALIZATION) }, contentAlignment = Alignment.Center) {
                if (avatar == null) Image(nokiPainterResource(R.raw.account_nav_profile), tr(state, "Изменить аватар", "Change avatar"), Modifier.size(40.dp))
                else Image(BitmapPainter(avatar), tr(state, "Изменить аватар", "Change avatar"), Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                PcText(profile.username, size = 18, maxLines = 1); PcText(profile.email, size = 12, color = Color(0xFFB9B9B9), maxLines = 1)
            }
        }
        Column(Modifier.fillMaxWidth().pcCard().padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                AccountPlanTitle(profile, scale = 18f / 27f)
            }
            PcText(profile.usageLabel.replace(" / ", tr(state, " из ", " of ")), size = 24)
            Box(Modifier.fillMaxWidth().height(5.dp).background(DesktopUiKit.Surface, DesktopUiKit.Shape)) {
                Box(Modifier.fillMaxWidth(profile.remainingFraction).fillMaxHeight().background(LocalDesktopStyle.current.accent, DesktopUiKit.Shape))
            }
            PcText(profile.remainingExplanation + " · " + tr(state, "действует до ", "valid until ") + profile.expirationDateLabel, size = 11, color = Color(0xFFB9B9B9))
        }
        PromoField(state, app, null)
        PcButton(tr(state, "Выйти из аккаунта", "Log out"), Modifier.fillMaxWidth().height(48.dp), enabled = !state.loading,
            iconName = "logout", iconSize = 18.dp, onClick = {
                show { PcDialog(tr(state, "Выйти из аккаунта?", "Log out?"), { show(null) }, tr(state, "Выйти", "Logout"), danger = true,
                    onConfirm = { app.logout(); show(null) }) { } }
            })
    }
}

@Composable private fun PromoField(state: DesktopState, app: DesktopController, planCode: String?) {
    var code by remember(planCode) { mutableStateOf("") }
    CompositionLocalProvider(LocalPcCardColor provides DesktopUiKit.NestedSurface) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PcInput(code, { code = it.take(80) }, tr(state, "Промокод", "Promo code"), Modifier.weight(1f).height(48.dp), enabled = !state.checkout.promoBusy && !state.checkout.isSubmitting, shape = DesktopUiKit.LeadingControlShape, textAlign = TextAlign.Center)
            PcButton(if (state.checkout.promoBusy) tr(state, "Проверяем…", "Checking…") else tr(state, "Применить", "Apply"), Modifier.widthIn(min = 112.dp, max = 150.dp).height(48.dp), selected = true, shape = DesktopUiKit.TrailingControlShape,
                enabled = code.isNotBlank() && !state.checkout.promoBusy && !state.checkout.isSubmitting && !state.invited,
                onClick = { app.payments.submitPromo(code, planCode) })
        }
    }
    (state.checkout.promoMessage ?: state.checkout.promoError)?.let { message ->
        PcDialog(tr(state, "Промокод", "Promo code"), app.payments::dismissPromoResult,
            dismissLabel = tr(state, "Понятно", "OK")) {
            PcText(message, size = 14)
        }
    }
}

@Composable private fun NotificationsPage(state: DesktopState, app: DesktopController, onPage: (DesktopPage) -> Unit) {
    PcText(tr(state, "Уведомления", "Notifications"), size = DesktopUiKit.SectionTitleSize)
    CompositionLocalProvider(LocalPcCardColor provides DesktopUiKit.NestedSurface) {
        for (notification in state.notifications) Column(Modifier.fillMaxWidth().pcCard().padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            PcText(notification.title, size = 16)
            PcText(notification.message, size = 13, color = Color(0xFFB9B9B9))
            PcText(formatDesktopDate(notification.createdAt, includeTime = false, language = state.language), size = 11, color = DesktopUiKit.Muted)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (AppNotificationAction.parse(notification.action) == AppNotificationAction.OpenSecurityUpdate)
                    PcButton(tr(state, "Безопасность", "Security"), Modifier.weight(1f).height(40.dp), compact = true, selected = true, onClick = { onPage(DesktopPage.SECURITY) })
                PcButton(tr(state, "Удалить", "Delete"), Modifier.weight(1f).height(40.dp), compact = true, onClick = { app.hideNotification(notification.id) })
            }
        }
    }
    if (state.notifications.isEmpty()) PcText(tr(state, "Нет уведомлений", "No notifications"), color = DesktopUiKit.Muted)
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable internal fun AvatarCropDialog(state: DesktopState, path: Path, source: org.jetbrains.skia.Image, dismiss: () -> Unit, confirm: (AvatarCropRequest) -> Unit) {
    var crop by remember(path) { mutableStateOf(AvatarCropRequest(path.toString(), 200f, 200f, 200f, 1f, 0f, 0f)) }
    val density = LocalDensity.current.density
    val bitmap = remember(source) { source.toComposeImageBitmap() }
    DisposableEffect(source) { onDispose { source.close() } }
    PcDialog("", dismiss, tr(state, "Сохранить", "Save"), onConfirm = { confirm(crop) }) {
        Canvas(Modifier.align(Alignment.CenterHorizontally).size(200.dp).clip(CircleShape)
            .semantics(mergeDescendants = true) { contentDescription = tr(state, "Переместить аватар", "Move avatar") }
            .pointerInput(path, density) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    drag(down.id) { change ->
                        val delta = change.positionChange() / density
                        crop = crop.copy(cropOffsetX = crop.cropOffsetX + delta.x, cropOffsetY = crop.cropOffsetY + delta.y).clampOffsets(source.width, source.height)
                        change.consume()
                    }
                }
            }) {
            val values = crop.imageMatrix(source.width, source.height).values
            withTransform({ transform(Matrix(floatArrayOf(
                values[0] * density, values[3] * density, 0f, 0f,
                values[1] * density, values[4] * density, 0f, 0f,
                0f, 0f, 1f, 0f, values[2] * density, values[5] * density, 0f, 1f,
            ))) }) { drawImage(bitmap) }
        }
        val accent = LocalDesktopStyle.current.accent
        Slider(crop.cropScale, { crop = crop.copy(cropScale = it).clampOffsets(source.width, source.height) }, valueRange = 1f..5f,
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = tr(state, "Масштаб аватара", "Avatar zoom") },
            thumb = { Box(Modifier.size(20.dp).background(accent, CircleShape).border(3.dp, DesktopUiKit.Text, CircleShape)) },
            track = { slider -> Canvas(Modifier.fillMaxWidth().height(6.dp)) {
                val radius = androidx.compose.ui.geometry.CornerRadius(size.height / 2f)
                drawRoundRect(Color.White.copy(alpha = .14f), cornerRadius = radius)
                drawRoundRect(accent, size = androidx.compose.ui.geometry.Size(size.width * ((slider.value - 1f) / 4f).coerceIn(0f, 1f), size.height), cornerRadius = radius)
            } })
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            for (turn in listOf(-1, 1)) PcButton("", Modifier.weight(1f).height(44.dp)
                .semantics { contentDescription = tr(state, if (turn < 0) "Повернуть влево" else "Повернуть вправо", if (turn < 0) "Rotate left" else "Rotate right") },
                iconVector = if (turn < 0) Icons.AutoMirrored.Filled.RotateLeft else Icons.AutoMirrored.Filled.RotateRight, onClick = {
                    crop = crop.copy(rotationQuarterTurns = crop.rotationQuarterTurns + turn).clampOffsets(source.width, source.height)
                })
        }
    }
}

@Composable private fun SecurityPage(state: DesktopState, app: DesktopController, show: ((@Composable () -> Unit)?) -> Unit) {
    val logging = state.settings.advancedSettings.connectionLogsEnabled || state.settings.advancedSettings.errorLogsEnabled
    val profile = AccountPresentationPolicy.prepare(state.androidState())
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        PcText(tr(state, "Безопасность", "Security"), size = DesktopUiKit.SectionTitleSize)
        SettingsSection(tr(state, "Вход в аккаунт", "Account sign-in")) {
            PcMenuRow(tr(state, "Имя пользователя", "Username"), state.user?.username, enabled = !state.loading && !state.invited,
                iconResource = R.raw.account_nav_profile) { app.editCredential("username") }
            PcMenuRow(tr(state, "Изменить почту", "Change email"), profile.email, enabled = !state.loading && !state.invited, externalLink = true,
                iconResource = R.drawable.security_email_icon) { app.openAccountWebsite("email") }
            PcMenuRow(tr(state, "Изменить пароль", "Change password"), enabled = !state.loading && !state.invited, externalLink = true,
                leadingIcon = { com.noki.vpn.ui.SecurityPasswordIcon(Modifier.size(24.dp)) }) { app.openAccountWebsite("password") }
            PcMenuRow("Telegram", if (state.user?.telegramLinked == true) tr(state, "Привязан", "Linked") else tr(state, "Привязка на сайте Noki", "Link on the Noki website"), externalLink = true,
                enabled = !state.loading && !state.invited,
                leadingIcon = { Icon(nokiPainterResource(R.drawable.login_telegram_icon), null, Modifier.size(24.dp), tint = Color.White) }) {
                app.openAccountWebsite("security")
            }
        }
        SettingsSection(tr(state, "Диагностика", "Diagnostics")) {
            PcToggle(tr(state, "Показать локальные логи", "Show local logs"), state.settings.advancedSettings.showLocalCoreLogs, !state.loading) { value ->
                app.updateSettings { it.copy(advancedSettings = it.advancedSettings.copy(showLocalCoreLogs = value)) }
            }
            PcToggle(tr(state, "Логирование", "Logging"), logging, !state.loading, onChange = app::setLoggingEnabled)
            if (logging) {
                HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = Color.White.copy(alpha = .08f))
                PcToggle(tr(state, "Автоматическая отправка логов", "Automatic log sending"), state.settings.advancedSettings.anonymousLogsEnabled, !state.loading,
                    onChange = app::setAutomaticLogSending)
                PcButton(tr(state, "Отправить логи", "Send logs"), Modifier.padding(12.dp).fillMaxWidth().height(48.dp), enabled = !state.loading,
                    selected = true, onClick = app::uploadLogs)
            }
        }
    }
}

@Composable internal fun DesktopCoreLogPanel(state: DesktopState, app: DesktopController, visible: Boolean, onCopy: (String) -> Unit) {
    val lines by app.localCoreLogs.collectAsState()
    var autoRefresh by rememberSaveable { mutableStateOf(true) }
    var autoScroll by rememberSaveable { mutableStateOf(true) }
    var streamError by remember { mutableStateOf<String?>(null) }
    val active = visible && autoRefresh && (state.running || state.connecting)
    LaunchedEffect(app, active) {
        streamError = null
        if (!active) return@LaunchedEffect
        while (kotlinx.coroutines.currentCoroutineContext().isActive) {
            try { app.collectCoreLogs(); streamError = null }
            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { streamError = tr(state, "Поток ядра недоступен. Повторяем подключение…", "Core stream unavailable. Reconnecting…") }
            delay(1000)
        }
    }
    val scroll = rememberLazyListState()
    LaunchedEffect(lines, autoScroll) { if (autoScroll && lines.isNotEmpty()) scroll.scrollToItem(lines.lastIndex) }
    Column(Modifier.fillMaxSize().padding(12.dp)
        .pcCard().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PcText(tr(state, "Логи ядра", "Core logs"), Modifier.weight(1f), size = 14)
            PcButton(tr(state, "Обновление", "Live"), compact = true, selected = autoRefresh, onClick = { autoRefresh = !autoRefresh })
            PcButton(tr(state, "Прокрутка", "Scroll"), compact = true, selected = autoScroll, onClick = { autoScroll = !autoScroll })
            PcButton(tr(state, "Очистить", "Clear"), compact = true, enabled = lines.isNotEmpty(), onClick = app::clearCoreLogs)
            PcButton(tr(state, "Копировать", "Copy"), compact = true, enabled = lines.isNotEmpty(),
                onClick = { onCopy(lines.joinToString("\n") { it.text }) })
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (lines.isEmpty()) PcText(streamError ?: tr(state, "Логов ядра пока нет", "No core logs yet"), size = 12, color = DesktopUiKit.Muted)
            androidx.compose.foundation.text.selection.SelectionContainer {
                LazyColumn(state = scroll, modifier = Modifier.fillMaxSize().padding(end = 10.dp)) {
                    items(lines) { line ->
                        androidx.compose.material3.Text(line.text, modifier = Modifier.fillMaxWidth()
                            .background(if (line.isError) Color(0xFFFF3333).copy(alpha = .16f) else Color.Transparent)
                            .padding(horizontal = 6.dp, vertical = 2.dp), fontSize = 12.sp, lineHeight = 17.sp,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                            fontWeight = if (line.isError) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (line.isError) Color(0xFFFF5353) else when (line.level.lowercase(java.util.Locale.ROOT)) {
                                "warn", "warning" -> Color(0xFFFFCF70)
                                "debug", "trace" -> DesktopUiKit.Muted
                                else -> DesktopUiKit.Text
                            })
                    }
                }
            }
            VerticalScrollbar(rememberScrollbarAdapter(scroll), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
        }
    }
}

@Composable private fun SettingsSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        PcText(title, Modifier.padding(start = 4.dp), size = 14, color = Color(0xFFB9B9B9))
        Column(Modifier.fillMaxWidth().pcCard(DesktopUiKit.NestedSurface),
            verticalArrangement = Arrangement.spacedBy(2.dp), content = content)
    }
}

@Composable private fun CredentialDialog(state: DesktopState, app: DesktopController) {
    val action = state.accountSecurity.action as? AccountSecurityActionState.Username ?: return
    PcDialog(tr(state, "Изменить имя", "Change username"), app::dismissCredential,
        confirmLabel = tr(state, "Сохранить", "Save"), enabled = !action.isLoading, onConfirm = app.credentials::submitUsername) {
        PcInput(action.username, app.credentials::updateUsername, tr(state, "Имя", "Username"), Modifier.fillMaxWidth(), enabled = !action.isLoading)
        action.error?.let { PcText(it, color = DesktopUiKit.Error, size = 12) }
    }
}

@Composable private fun PersonalizationPage(state: DesktopState, app: DesktopController, show: ((@Composable () -> Unit)?) -> Unit) {
    val scope = rememberCoroutineScope()
    var avatarOpening by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        PcText(tr(state, "Персонализация", "Personalization"), size = DesktopUiKit.SectionTitleSize)
        SettingsSection(tr(state, "Аватар", "Avatar")) {
            val profile = AccountPresentationPolicy.prepare(state.androidState())
            val avatar = nokiAvatarImage(state.avatarPath)
            Row(Modifier.fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Box(Modifier.size(72.dp).clip(RoundedCornerShape(16.dp)), contentAlignment = Alignment.Center) {
                    if (avatar != null) Image(BitmapPainter(avatar), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    else Image(nokiPainterResource(R.raw.account_nav_profile), null, Modifier.size(40.dp))
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    PcText(profile.username, size = 18, maxLines = 1)
                    PcText(profile.email, size = 12, color = Color(0xFFB9B9B9), maxLines = 1)
                }
                if (avatarOpening) CircularProgressIndicator(Modifier.size(24.dp), color = LocalDesktopStyle.current.accent, strokeWidth = 2.dp)
                else PcIconButton(R.drawable.personalization_avatar_edit_icon, tr(state, "Изменить аватар", "Change avatar"), enabled = !state.invited && !state.loading) {
                    show { PcDialog(tr(state, "Аватар", "Avatar"), { show(null) }) {
                        PcButton(tr(state, "Выбрать фото", "Choose photo"), Modifier.fillMaxWidth(), iconName = "profile", iconSize = 18.dp, onClick = {
                            runCatching { app.pickPath(tr(state, "Выбрать изображение", "Select image"), "*.png;*.jpg;*.jpeg") }.onSuccess { path ->
                                if (path != null) {
                                    show(null)
                                    avatarOpening = true
                                    scope.launch {
                                        try {
                                            val image = runInterruptible(Dispatchers.IO) { loadAvatarImage(path) }
                                            show { AvatarCropDialog(state, path, image, { show(null) }) { app.uploadAvatar(it); show(null) } }
                                        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                                        catch (error: Exception) { app.reportMessage(avatarFileError(state.language, error)) }
                                        finally { avatarOpening = false }
                                    }
                                }
                            }.onFailure { app.reportMessage(it.message ?: tr(state, "Не удалось открыть выбор файла", "Could not open file picker")) }
                        })
                        PcButton(tr(state, "Удалить", "Remove"), Modifier.fillMaxWidth(), enabled = avatar != null, onClick = { app.deleteAvatar(); show(null) })
                    } }
                }
            }
        }
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            PcText(tr(state, "Язык", "Language"), Modifier.padding(start = 4.dp), size = 14, color = Color(0xFFB9B9B9))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                for (language in AppLanguage.entries) PcButton(if (language == AppLanguage.RU) "Русский" else "English", Modifier.weight(1f).height(45.dp), selected = state.language == language, enabled = !state.loading,
                    glassColor = DesktopUiKit.NestedSurface, shape = DesktopUiKit.MenuShape,
                    onClick = { app.updateSettings { it.copy(personalizationSettings = it.personalizationSettings.copy(language = language)) } })
            }
        }
        SettingsSection(tr(state, "Анимация", "Animation")) {
            PcToggle(tr(state, "Отключить Землю", "Disable Earth"), state.earthDisabled, !state.loading, onChange = app::setEarthDisabled)
            PcToggle(tr(state, "Отключить анимацию Земли", "Disable Earth animation"), state.reducedMotion, !state.loading && !state.earthDisabled, onChange = app::setReducedMotion)
        }
    }
}

@Composable private fun AdvancedPage(state: DesktopState, app: DesktopController, onPage: (DesktopPage) -> Unit) {
    val settings = state.settings.advancedSettings
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        PcText(tr(state, "Расширенные настройки", "Advanced settings"), size = DesktopUiKit.SectionTitleSize)
        SettingsSection(tr(state, "Соединение", "Connection")) {
            ConnectionSettingsControls(state, app)
        }
        SettingsSection(tr(state, "Управление трафиком", "Traffic control")) {
            PcMenuRow(tr(state, "Маршрутизация", "Routing"), tr(state, "Сайты, IP-адреса и приложения", "Websites, IP addresses and applications")) { onPage(DesktopPage.ROUTING) }
        }
        SettingsSection(tr(state, "Запуск приложения", "Application startup")) {
            PcToggle(tr(state, "Запускать с системой", "Launch at startup"), settings.launchAtStartup, !state.loading) { value ->
                app.updateSettings { it.copy(advancedSettings = it.advancedSettings.copy(launchAtStartup = value)) }
            }
            PcToggle(tr(state, "Автоматически подключаться при запуске", "Automatically connect on launch"),
                settings.autoConnectOnLaunch, !state.loading && settings.launchAtStartup) { value ->
                app.updateSettings { it.copy(advancedSettings = it.advancedSettings.copy(autoConnectOnLaunch = value)) }
            }
        }
        PcMenuRow(tr(state, "Статистика использования", "Usage statistics"), tr(state, "Трафик, время подключения и задержка", "Traffic, connection time and latency")) { onPage(DesktopPage.STATS) }
        if (state.running) PcText(tr(state, "Изменения соединения применятся при следующем подключении", "Connection changes apply on your next connection"), size = 11, color = Color(0xFFB9B9B9))
    }
}

@Composable private fun ConnectionSettingsDialog(app: DesktopController, onDismiss: () -> Unit) {
    val state by app.state.collectAsState()
    PcDialog(tr(state, "Настройки соединения", "Connection settings"), onDismiss,
        dismissLabel = tr(state, "Готово", "Done")) {
        ConnectionSettingsControls(state, app)
        if (state.running) PcText(tr(state, "Изменения соединения применятся при следующем подключении", "Connection changes apply on your next connection"),
            Modifier.padding(12.dp), size = 11, color = DesktopUiKit.Muted)
    }
}

@Composable private fun ConnectionSettingsControls(state: DesktopState, app: DesktopController) {
    val settings = state.settings.advancedSettings
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
    PcToggle(tr(state, "Автовосстановление соединения", "Automatic connection recovery"), state.autoRecoveryEnabled, !state.loading,
        help = tr(state, "Если связь пропадёт, Noki попробует подключиться снова.", "If the connection drops, Noki will try to reconnect."), onChange = app::setAutoRecovery)
    PcToggle("Kill switch", settings.killSwitchEnabled, !state.loading,
        help = tr(state, "При обрыве VPN останавливает трафик, который должен идти через него.", "If the VPN disconnects, blocks traffic that should pass through it.")) { value ->
        app.updateSettings { it.copy(advancedSettings = it.advancedSettings.copy(killSwitchEnabled = value)) }
    }
    if (!com.noki.vpn.SettingsPreparedStatePolicy.isFreePlan(state.androidState())) {
        PcToggle(tr(state, "RU-реле", "RU relay"), settings.ruRelayEnabled, !state.loading,
            help = tr(state, "Подключает к выбранному зарубежному серверу через сервер в России.", "Connects to the selected foreign server through a server in Russia.")) { value ->
            app.updateSettings { it.copy(advancedSettings = it.advancedSettings.copy(ruRelayEnabled = value)) }
        }
        PcToggle(tr(state, "Игровой режим", "Gaming mode"), settings.gamingModeEnabled, !state.loading,
            help = tr(state, "Использует игровой профиль NokiNV. При включении отключает RU-реле.", "Uses the NokiNV gaming profile and turns off RU relay."), onChange = app::setGamingMode)
        PcToggle(tr(state, "YouTube без рекламы", "YouTube without ads"), settings.youtubeAdBlockEnabled, !state.loading,
            help = tr(state, "Направляет YouTube через отдельный российский сервер. Остальной трафик сохраняет обычный маршрут.", "Routes YouTube through a separate Russian server. Other traffic keeps its usual route.")) { value ->
            app.updateSettings { it.copy(advancedSettings = it.advancedSettings.copy(youtubeAdBlockEnabled = value)) }
        }
    }
    }
}

@Composable private fun RoutingPage(state: DesktopState, app: DesktopController, onPage: (DesktopPage) -> Unit, show: ((@Composable () -> Unit)?) -> Unit) {
    PcPageHeading(tr(state, "Маршрутизация", "Routing")) { onPage(DesktopPage.ADVANCED) }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
        PcButton(tr(state, "Добавить", "Add"), Modifier.height(44.dp).widthIn(min = 132.dp), iconVector = Icons.Rounded.Add, iconSize = 18.dp,
            selected = true, enabled = !state.loading, onClick = { show { RoutingRuleDialog(state, app, null) { show(null) } } })
    }
    if (state.routingRules.isEmpty()) PcText(tr(state, "Добавьте домены, IP-адреса или приложения.", "Add domains, IP addresses or applications."), size = 13, color = DesktopUiKit.Muted)
    for (rule in state.routingRules) {
        val russianResources = rule.domains == DesktopRoutingRule.RUSSIAN_RESOURCES.domains && rule.ips.isEmpty() && rule.applications.isEmpty()
        val mode = tr(state, if (rule.bypass) "Без VPN" else "Через VPN", if (rule.bypass) "Bypass VPN" else "Through VPN")
        val resources = listOfNotNull(
            rule.domains.takeIf { it.isNotEmpty() }?.let { "${it.size} " + tr(state, "доменов", "domains") },
            rule.ips.takeIf { it.isNotEmpty() }?.let { "${it.size} IP" },
            rule.applications.takeIf { it.isNotEmpty() }?.let { "${it.size} " + tr(state, "приложений / папок", "applications / folders") },
            rule.ruleSets.takeIf { it.isNotEmpty() }?.let { "${it.size} " + tr(state, "списков", "lists") }).joinToString(" · ")
        val target = if (russianResources) tr(state, "Российские ресурсы", "Russian resources") else rule.name
        PcMenuRow(target, if (!rule.enabled) tr(state, "Отключено", "Disabled") else if (russianResources) mode else "$mode · $resources", enabled = !state.loading) {
            show { RoutingRuleDialog(state, app, rule) { show(null) } }
        }
    }
}

@Composable private fun RoutingRuleDialog(state: DesktopState, app: DesktopController, original: DesktopRoutingRule?, dismiss: () -> Unit) {
    var name by remember { mutableStateOf(original?.name.orEmpty()) }
    var domains by remember { mutableStateOf(original?.domains?.joinToString("\n").orEmpty()) }
    var ips by remember { mutableStateOf(original?.ips?.joinToString("\n").orEmpty()) }
    var applications by remember { mutableStateOf(original?.applications.orEmpty().toSet()) }
    var bypass by remember { mutableStateOf(original?.bypass ?: false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var choosingApplications by remember { mutableStateOf(false) }
    val id = remember { original?.id ?: UUID.randomUUID().toString() }
    val rule = runCatching { DesktopRoutingRule(id, name, bypass, domains.lines().filter { it.isNotBlank() },
        ips.split('\n', ',').filter { it.isNotBlank() }, applications.toList(), original?.enabled ?: true).normalized() }
    if (choosingApplications) {
        InstalledApplications(app, applications, { applications = it; choosingApplications = false }) { choosingApplications = false }
        return
    }
    if (confirmDelete && original != null) PcDialog(tr(state, "Точно удалить?", "Delete this rule?"), { confirmDelete = false },
        tr(state, "Да", "Yes"), enabled = !state.loading, danger = true, dismissLabel = tr(state, "Нет", "No"),
        onConfirm = { app.deleteRoutingRule(original.id); dismiss() }) {
        PcText(tr(state, "Домены, IP и приложения этого правила будут удалены.", "This rule's domains, IPs and applications will be removed."), size = 14)
    } else PcDialog(tr(state, if (original == null) "Добавить правило" else "Изменить правило", if (original == null) "Add rule" else "Edit rule"), dismiss,
        tr(state, "Сохранить", "Save"), enabled = rule.isSuccess && !state.loading, spacing = 16.dp, width = 660.dp,
        onConfirm = { app.saveRoutingRule(rule.getOrThrow()); dismiss() }) {
        PcInput(name, { name = it.take(120) }, tr(state, "Название правила", "Rule name"), Modifier.fillMaxWidth(), enabled = !state.loading)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PcButton(tr(state, "Через VPN", "Through VPN"), Modifier.weight(1f).height(44.dp), selected = !bypass, onClick = { bypass = false })
            PcButton(tr(state, "Без VPN", "Bypass VPN"), Modifier.weight(1f).height(44.dp), selected = bypass, onClick = { bypass = true })
        }
        PcText(tr(state, "Домены · по одному в строке", "Domains · one per line"), size = 13, color = DesktopUiKit.Muted)
        PcInput(domains, { domains = it.take(100_000) }, "example.com\ngeosite:category-ru", Modifier.fillMaxWidth().heightIn(min = 80.dp, max = 144.dp), singleLine = false)
        PcText(tr(state, "IP / CIDR · по одному в строке", "IP / CIDR · one per line"), size = 13, color = DesktopUiKit.Muted)
        PcInput(ips, { ips = it.take(100_000) }, "192.0.2.0/24\ngeoip:ru", Modifier.fillMaxWidth().heightIn(min = 80.dp, max = 144.dp), singleLine = false)
        PcText(tr(state, "Приложения", "Applications"), size = 13, color = DesktopUiKit.Muted)
        PcMenuRow(tr(state, "Выбрать установленные приложения", "Choose installed applications"),
            tr(state, "Найти приложения на этом ПК", "Find applications on this PC"), enabled = !state.loading) { choosingApplications = true }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            for (folder in listOf(false, true)) PcButton(tr(state, if (folder) "Добавить папку" else if (DesktopBuild.IS_LINUX) "Добавить приложение" else "Добавить .exe", if (folder) "Add folder" else if (DesktopBuild.IS_LINUX) "Add application" else "Add .exe"),
                Modifier.weight(1f), enabled = !state.loading, onClick = {
                    runCatching { app.pickPath(tr(state, if (folder) "Выбрать папку приложения" else "Выбрать приложение", if (folder) "Select app folder" else "Select application"), if (folder || DesktopBuild.IS_LINUX) null else "*.exe", folder) }
                        .onSuccess { path -> if (path != null) applications = applications + (path.toString().trimEnd('\\', '/') + if (folder) (if (DesktopBuild.IS_LINUX) "/" else "\\") else "") }
                        .onFailure { app.reportMessage(it.message ?: tr(state, "Не удалось открыть выбор файла", "Could not open file picker")) }
                })
        }
        if (applications.isEmpty()) PcText(tr(state, "Приложения не выбраны", "No applications selected"), size = 12, color = DesktopUiKit.Muted)
        else {
            PcText(tr(state, "Выбрано: ${applications.size}", "Selected: ${applications.size}"), size = 12, color = DesktopUiKit.Muted)
            Column(Modifier.fillMaxWidth().heightIn(max = 220.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                for (path in applications.sorted()) ApplicationCheckRow(Path.of(path).fileName?.toString() ?: path, path, true, !state.loading) {
                    applications = applications - path
                }
            }
        }
        if ((domains.isNotBlank() || ips.isNotBlank() || applications.isNotEmpty()) && rule.isFailure)
            PcText(rule.exceptionOrNull()?.message.orEmpty(), size = 12, color = DesktopUiKit.Error)
        if (original?.id == DesktopRoutingRule.RUSSIAN_RESOURCES.id) PcButton(
            tr(state, if (original.enabled) "Отключить" else "Включить", if (original.enabled) "Disable" else "Enable"),
            Modifier.fillMaxWidth(), enabled = !state.loading, onClick = { app.saveRoutingRule(original.copy(enabled = !original.enabled)); dismiss() })
        else if (original != null) PcButton(tr(state, "Удалить правило", "Delete rule"), Modifier.fillMaxWidth(), danger = true, enabled = !state.loading, onClick = { confirmDelete = true })
    }
}

@Composable private fun InstalledApplications(app: DesktopController, initialSelection: Set<String>, onSelect: (Set<String>) -> Unit, dismiss: () -> Unit) {
    val state by app.state.collectAsState()
    var applications by remember { mutableStateOf<List<WindowsApplication>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var expanded by rememberSaveable { mutableStateOf<String?>(null) }
    var search by rememberSaveable { mutableStateOf("") }
    var selected by remember { mutableStateOf(initialSelection) }
    var loadingExecutables by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { runCatching { WindowsShell.installedApplications() } }
            .onSuccess { applications = it }.onFailure { error = it.message ?: tr(state, "Не удалось получить приложения", "Could not list applications") }
    }
    LaunchedEffect(expanded) {
        val application = applications?.firstOrNull { it.id == expanded } ?: return@LaunchedEffect
        loadingExecutables = application.id
        try {
            val executables = kotlinx.coroutines.runInterruptible(Dispatchers.IO) { WindowsShell.applicationExecutables(application) }
            applications = applications?.map { if (it.id == application.id) it.copy(executables = executables) else it }
        } finally { if (loadingExecutables == application.id) loadingExecutables = null }
    }
    PcDialog(tr(state, "Выбрать из установленных приложений", "Choose from installed applications"), dismiss,
        tr(state, "Сохранить", "Save"), enabled = !state.loading, fullScreen = true, scrollContent = false,
        onConfirm = { onSelect(selected) }) {
        PcInput(search, { search = it }, tr(state, "Поиск приложений", "Search applications"), Modifier.fillMaxWidth())
        error?.let { PcText(it, color = DesktopUiKit.Error) }
        if (applications == null && error == null) PcText(tr(state, "Получаем приложения…", "Loading applications…"), color = DesktopUiKit.Muted)
        val listState = rememberLazyListState()
        Box(Modifier.weight(1f).fillMaxWidth()) {
        LazyColumn(Modifier.fillMaxSize().padding(end = 12.dp), state = listState, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            for (application in applications.orEmpty().filter { it.name.contains(search, true) }) {
                val folder = application.folder?.trimEnd('\\', '/')?.plus(if (DesktopBuild.IS_LINUX) "/" else "\\")
                val checked = application.selectionPaths.all { it in selected } ||
                    (application.executables.isNotEmpty() && application.executables.all { it in selected })
                item(key = application.id) {
                    Row(Modifier.fillMaxWidth().pcCard().pcClickable(role = Role.Checkbox) {
                        selected = (selected - application.selectionPaths - application.executables.toSet()) + if (!checked) application.selectionPaths else emptySet()
                    }.semantics { toggleableState = if (checked) ToggleableState.On else ToggleableState.Off }, verticalAlignment = Alignment.CenterVertically) {
                        ApplicationCheckRow(application.name, application.folder, checked, true, Modifier.weight(1f), clickable = false) {}
                        Box(Modifier.size(48.dp).clickable(interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null,
                            role = Role.Button, onClick = { expanded = if (expanded == application.id) null else application.id }), contentAlignment = Alignment.Center) {
                            PcIcon("chevron", tr(state, "Исполняемые файлы", "Executables"), Modifier.size(11.dp, 8.dp).graphicsLayer { rotationZ = if (expanded == application.id) 180f else 0f })
                        }
                    }
                }
                if (expanded == application.id && loadingExecutables == application.id) item(key = application.id + ":loading") {
                    PcText(tr(state, "Получаем исполняемые файлы…", "Loading executables…"), Modifier.padding(16.dp), size = 12, color = DesktopUiKit.Muted)
                }
                if (expanded == application.id) items(application.executables, key = { application.id + ":" + it }) { executable ->
                    ApplicationCheckRow(Path.of(executable).fileName.toString(), executable,
                        executable in selected || (folder in selected && Path.of(executable).startsWith(Path.of(checkNotNull(folder)))), true, Modifier.padding(start = 24.dp).pcCard()) { value ->
                        val paths = if (folder != null && folder in selected) selected - folder + application.executables else selected
                        selected = if (value) paths + executable else paths - executable
                    }
                }
            }
        }
        VerticalScrollbar(rememberScrollbarAdapter(listState), Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
            style = LocalScrollbarStyle.current.copy(unhoverColor = Color.White.copy(alpha = .2f), hoverColor = Color.White.copy(alpha = .4f)))
        }
    }
}

@Composable private fun ApplicationCheckRow(label: String, detail: String?, checked: Boolean, enabled: Boolean, modifier: Modifier = Modifier, clickable: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(modifier.fillMaxWidth().heightIn(min = 60.dp).then(if (clickable) Modifier.pcClickable(enabled = enabled, role = Role.Checkbox) { onChange(!checked) } else Modifier)
        .semantics { toggleableState = if (checked) ToggleableState.On else ToggleableState.Off }.padding(end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
            Checkbox(checked, null, colors = CheckboxDefaults.colors(checkedColor = LocalDesktopStyle.current.accent))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            PcText(label, size = 14, maxLines = 1)
            detail?.let { PcText(it, size = 12, color = DesktopUiKit.Muted, maxLines = 2) }
        }
    }
}

@Composable private fun ColumnScope.MultiHopPage(state: DesktopState, app: DesktopController, onBack: () -> Unit, onDone: (DesktopPage) -> Unit) {
    val current = state.settings.advancedSettings.multiHop
    var choosingRoute by rememberSaveable(current.enabled) { mutableStateOf(!current.enabled) }
    if (current.enabled && !choosingRoute) {
        PcPageHeading(tr(state, "Мультихоп", "MultiHop"), onBack)
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            for ((label, hop) in listOf(tr(state, "Входная локация", "Entry location") to current.entry,
                tr(state, "Выходная локация", "Exit location") to current.exit)) {
                PcText(label, Modifier.padding(horizontal = 12.dp), size = 12, lineHeight = 16, color = DesktopUiKit.Muted)
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).pcCard(DesktopUiKit.Surface, DesktopUiKit.MenuShape)
                    .border(.5.dp, Color.White.copy(alpha = .08f), DesktopUiKit.MenuShape).padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                        hopLocation(state, hop)?.let { CountryFlagMarker(it, 1f, 28f) }
                        PcText(hopLabel(state, hop), size = 14, lineHeight = 20, maxLines = 2)
                }
            }
            PcButton(tr(state, "Выбрать другой маршрут", "Choose another route"), Modifier.fillMaxWidth().height(48.dp), selected = true, enabled = !state.loading,
                onClick = { choosingRoute = true })
            PcButton(tr(state, "Отключить мультихоп", "Disable MultiHop"), Modifier.fillMaxWidth().height(48.dp), enabled = !state.loading, onClick = {
                app.updateSettings { it.copy(advancedSettings = it.advancedSettings.copy(multiHop = current.copy(enabled = false))) }
                onDone(DesktopPage.SERVERS)
            })
        }
        return
    }
    var entryStep by remember { mutableStateOf(true) }
    var entry by remember { mutableStateOf(current.entry) }
    var exit by remember { mutableStateOf(current.exit) }
    val ruRelay = state.settings.advancedSettings.ruRelayEnabled
    val eligible = state.connectionLocations.map { country -> country.copy(servers =
        DesktopController.multiHopServers(null, country.servers, entryStep, ruRelay)
            .filter { it.id != (if (entryStep) exit else entry)?.takeIf { it.kind == ServerSelectionMode.SERVER }?.nodeId })
    }.filter { it.servers.isNotEmpty() }
    val selected = if (entryStep) entry else exit
    val candidates = eligible.flatMap { it.servers }
    val canContinue = selected != null && selected.kind != ServerSelectionMode.AUTO &&
        DesktopController.multiHopServers(selected, candidates, entryStep, ruRelay).isNotEmpty() &&
        (entryStep || DesktopController.multiHopServers(entry, state.connectionLocations.flatMap { it.servers }, true, ruRelay)
            .any { input -> DesktopController.multiHopServers(exit, candidates, false, ruRelay).any { it.id != input.id } })
    PcPageHeading(tr(state, if (entryStep) "Входная локация" else "Выходная локация", if (entryStep) "Entry location" else "Exit location")) {
        if (!entryStep) entryStep = true else if (current.enabled) choosingRoute = false else onBack()
    }
    key(entryStep) {
        CountryCatalog(state, Modifier.weight(1f).fillMaxWidth(), selected ?: HopSelection(ServerSelectionMode.AUTO), allowAuto = false, locations = eligible) {
            if (entryStep) entry = it else exit = it
        }
    }
    PcButton(tr(state, if (entryStep) "Продолжить" else "Подтвердить маршрут", if (entryStep) "Continue" else "Confirm route"),
        Modifier.fillMaxWidth().height(48.dp), enabled = !state.loading && canContinue,
        onClick = {
            if (entryStep) entryStep = false
            else {
                app.updateSettings { it.copy(advancedSettings = it.advancedSettings.copy(
                    multiHop = it.advancedSettings.multiHop.copy(enabled = true, entry = entry, exit = exit))) }
                onDone(DesktopPage.HOME)
            }
        })
    if (current.enabled) PcButton(tr(state, "Выключить мультихоп", "Disable MultiHop"), Modifier.fillMaxWidth(), compact = true, enabled = !state.loading,
        onClick = {
            app.updateSettings { it.copy(advancedSettings = it.advancedSettings.copy(multiHop = it.advancedSettings.multiHop.copy(enabled = false))) }
            onDone(DesktopPage.SERVERS)
        })
}

internal fun desktopTrafficSeries(stats: List<DailyStats>, period: StatsPeriod, today: java.time.LocalDate): List<Pair<java.time.LocalDateTime, com.noki.vpn.ui.StatsAggregate>> {
    if (period == StatsPeriod.TODAY) {
        val day = stats.firstOrNull { it.date == today.toString() }
        return (0..23).map { hour -> today.atTime(hour, 0) to com.noki.vpn.ui.StatsAggregate(
            rxBytes = day?.hourlyRxBytes?.getOrNull(hour) ?: 0L,
            txBytes = day?.hourlyTxBytes?.getOrNull(hour) ?: 0L) }
    }
    val dates = when (period) {
        StatsPeriod.TODAY -> listOf(today)
        StatsPeriod.WEEK -> (0L..6L).map { today.minusDays((today.dayOfWeek.value - 1).toLong()).plusDays(it) }
        StatsPeriod.MONTH -> (1..today.lengthOfMonth()).map { today.withDayOfMonth(it) }
        StatsPeriod.YEAR -> (1..12).map { today.withMonth(it).withDayOfMonth(1) }
    }
    return dates.map { date ->
        val end = if (period == StatsPeriod.YEAR) minOf(date.withDayOfMonth(date.lengthOfMonth()), today) else date
        date.atStartOfDay() to if (date > today) com.noki.vpn.ui.StatsAggregate() else
            aggregateDailyStats(stats, if (period == StatsPeriod.YEAR) StatsPeriod.MONTH else StatsPeriod.TODAY, end)
    }
}

@Composable private fun StatsPage(state: DesktopState, onPage: (DesktopPage) -> Unit) {
    var period by rememberSaveable { mutableStateOf(StatsPeriod.WEEK) }
    PcPageHeading(tr(state, "Статистика", "Statistics")) { onPage(DesktopPage.ADVANCED) }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val scrollPeriods = maxWidth < 380.dp
        Row(Modifier.fillMaxWidth().then(if (scrollPeriods) Modifier.pcHorizontalScroll() else Modifier), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (option in StatsPeriod.entries) PcButton(when (option) {
                StatsPeriod.TODAY -> tr(state, "Сегодня", "Today")
                StatsPeriod.WEEK -> tr(state, "Неделя", "Week")
                StatsPeriod.MONTH -> tr(state, "Месяц", "Month")
                StatsPeriod.YEAR -> tr(state, "Год", "Year")
            }, (if (scrollPeriods) Modifier.width(92.dp) else Modifier.weight(1f)).height(45.dp), compact = true,
                glassColor = DesktopUiKit.NestedSurface, shape = DesktopUiKit.MenuShape,
                selected = option == period, onClick = { period = option })
        }
    }
    val today = java.time.LocalDate.now()
    val aggregate = remember(state.dailyStats, period, today) { aggregateDailyStats(state.dailyStats, period, today) }
    val series = remember(state.dailyStats, period, today) { desktopTrafficSeries(state.dailyStats, period, today) }
    var selectedDate by remember(period, today) { mutableStateOf<java.time.LocalDateTime?>(null) }
    val selectedDay = series.firstOrNull { it.first == selectedDate }
    val displayedStats = selectedDay?.second ?: aggregate
    val accent = LocalDesktopStyle.current.accent
    val uploadColor = Color(0xFF90C8FF)
    val locale = if (state.language == AppLanguage.RU) java.util.Locale.forLanguageTag("ru") else java.util.Locale.ENGLISH
    val formatter = java.time.format.DateTimeFormatter.ofPattern(when (period) {
        StatsPeriod.TODAY -> "HH:mm"
        StatsPeriod.WEEK -> "EEE, d MMM"
        StatsPeriod.YEAR -> "LLLL"
        else -> "d MMM"
    }, locale)
    Column(Modifier.fillMaxWidth().pcCard().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                PcText(selectedDay?.first?.format(formatter) ?: statsPeriodLabel(period, state.language), size = 12, lineHeight = 16, color = Color(0xFFB9B9B9))
                PcText(TrafficFormat.bytes(displayedStats.totalBytes, state.language).label, size = 28, lineHeight = 36)
            }
            if (selectedDay != null) PcButton(tr(state, "Весь период", "Full period"), compact = true, onClick = { selectedDate = null })
        }
        val plotted = if (period == StatsPeriod.TODAY || period == StatsPeriod.WEEK) series else series.takeWhile { it.first.toLocalDate() <= today }
        val maximum = plotted.maxOf { maxOf(it.second.rxBytes, it.second.txBytes) }.coerceAtLeast(4)
        Row(Modifier.fillMaxWidth().height(184.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(Modifier.width(56.dp).fillMaxHeight().padding(vertical = 2.dp), verticalArrangement = Arrangement.SpaceBetween) {
                for (part in 4 downTo 0) PcText(TrafficFormat.bytes((maximum / 4.0 * part).toLong(), state.language).label,
                    size = 10, lineHeight = 12, color = DesktopUiKit.Muted)
            }
            Box(Modifier.weight(1f).fillMaxHeight()) {
                Canvas(Modifier.fillMaxSize()) {
                    val top = 8.dp.toPx()
                    val bottom = size.height - top
                    val height = bottom - top
                    val inset = 3.dp.toPx()
                    fun x(index: Int) = if (plotted.size == 1) size.width / 2 else inset + (size.width - inset * 2) * index / (plotted.size - 1)
                    for (part in 0..4) drawLine(Color.White.copy(alpha = .07f), Offset(0f, bottom - height * part / 4), Offset(size.width, bottom - height * part / 4), 1.dp.toPx())
                    for ((upload, color) in listOf(false to accent, true to uploadColor)) {
                        val points = plotted.mapIndexed { index, (_, day) -> Offset(x(index),
                            bottom - height * (if (upload) day.txBytes else day.rxBytes).toDouble().div(maximum).toFloat()) }
                        val line = androidx.compose.ui.graphics.Path().apply {
                            moveTo(points.first().x, points.first().y)
                            points.drop(1).forEach { lineTo(it.x, it.y) }
                        }
                        if (!upload && points.size > 1) {
                            val area = androidx.compose.ui.graphics.Path().apply {
                                addPath(line); lineTo(points.last().x, bottom); lineTo(points.first().x, bottom); close()
                            }
                            drawPath(area, Brush.verticalGradient(listOf(color.copy(alpha = .24f), color.copy(alpha = .03f), Color.Transparent), top, bottom))
                        }
                        drawPath(line, color, style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
                        points.forEachIndexed { index, point -> if (points.size == 1 || plotted[index].first == selectedDate) drawCircle(color, 4.dp.toPx(), point) }
                    }
                    plotted.indexOfFirst { it.first == selectedDate }.takeIf { it >= 0 }?.let { index ->
                        drawLine(Color.White.copy(alpha = .2f), Offset(x(index), top), Offset(x(index), bottom), 1.dp.toPx())
                    }
                }
                Row(Modifier.fillMaxSize()) {
                    for ((index, value) in plotted.withIndex()) {
                        val (date, day) = value
                        Box(Modifier.weight(if (plotted.size > 1 && (index == 0 || index == plotted.lastIndex)) .5f else 1f).fillMaxHeight()
                            .pcClickable(hoverColor = Color.Transparent) { selectedDate = date }
                            .semantics { contentDescription = "${date.format(formatter)}: ${TrafficFormat.bytes(day.totalBytes, state.language).label}" })
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(start = 66.dp), horizontalArrangement = if (plotted.size == 1) Arrangement.Center else Arrangement.SpaceBetween) {
            val ticks = when (period) {
                StatsPeriod.TODAY -> listOf(0, 6, 12, 18, 23)
                StatsPeriod.WEEK -> plotted.indices.toList()
                else -> listOf(0, plotted.size / 2, plotted.lastIndex).distinct()
            }
            for (index in ticks)
                PcText(plotted[index].first.format(if (period == StatsPeriod.WEEK) java.time.format.DateTimeFormatter.ofPattern("EEE", locale) else formatter), size = 10, lineHeight = 14, color = DesktopUiKit.Muted)
        }
        if (aggregate.totalBytes == 0L) PcText(tr(state, "За этот период трафика пока нет", "No traffic in this period yet"), size = 12, color = DesktopUiKit.Muted)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            PcText("↓ " + TrafficFormat.bytes(displayedStats.rxBytes, state.language).label, size = 12, color = accent)
            PcText("↑ " + TrafficFormat.bytes(displayedStats.txBytes, state.language).label, size = 12, color = uploadColor)
        }
    }
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        for ((label, value) in listOf(tr(state, "Время онлайн", "Time online") to formatStatsDuration(aggregate.onlineSeconds, state.language),
            tr(state, "Задержка", "Latency") to aggregate.averagePingLabel(), tr(state, "Подключений", "Sessions") to aggregate.sessions.toString())) {
            Column(Modifier.weight(1f).fillMaxHeight().pcCard().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                PcText(label, size = 11, lineHeight = 16, color = Color(0xFFB9B9B9))
                PcText(value, size = 18, lineHeight = 24)
            }
        }
    }
}

@Composable private fun ColumnScope.SupportPage(state: DesktopState, app: DesktopController, active: Boolean) {
    val chat by app.support.state.collectAsState()
    DisposableEffect(app.support) { onDispose { app.support.close() } }
    LaunchedEffect(chat.isOpen, active) {
        if (chat.isOpen && active) while (isActive) {
            app.support.refresh()
            delay(10_000)
        }
    }
    if (chat.isOpen) {
        PcPageHeading(tr(state, "Поддержка Noki", "Noki support"), app.support::close)
        val list = rememberLazyListState()
        val scope = rememberCoroutineScope()
        var previousLatest by remember { mutableStateOf<Long?>(null) }
        var unread by remember { mutableStateOf(false) }
        val atBottom by remember { derivedStateOf { list.firstVisibleItemIndex == 0 && list.firstVisibleItemScrollOffset == 0 } }
        LaunchedEffect(chat.messages.lastOrNull()?.id) {
            val latest = chat.messages.lastOrNull() ?: return@LaunchedEffect
            if (previousLatest == null || atBottom || !latest.isFromSupport) list.scrollToItem(0) else unread = true
            previousLatest = latest.id
        }
        LaunchedEffect(atBottom) { if (atBottom) unread = false }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (chat.messages.isEmpty()) Column(Modifier.align(Alignment.Center).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (chat.isLoading) CircularProgressIndicator(Modifier.size(24.dp), color = LocalDesktopStyle.current.accent, strokeWidth = 2.dp)
                else if (chat.error == null) {
                    PcText(tr(state, "С чего начнём?", "How can we help?"), size = 24)
                    PcText(tr(state, "Расскажите, что случилось. Ответ появится в этом чате.", "Tell us what happened. The reply will appear here."),
                        size = 14, color = DesktopUiKit.Muted, textAlign = TextAlign.Center)
                }
            }
            LazyColumn(Modifier.fillMaxSize().padding(end = 12.dp), state = list, reverseLayout = true,
                verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(vertical = 8.dp)) {
                items(chat.messages.asReversed(), key = { it.id }) { message ->
                    Column(Modifier.fillMaxWidth(), horizontalAlignment = if (message.isFromSupport) Alignment.Start else Alignment.End) {
                        BoxWithConstraints(Modifier.fillMaxWidth(.82f), contentAlignment = if (message.isFromSupport) Alignment.TopStart else Alignment.TopEnd) {
                        Column(Modifier.widthIn(max = maxWidth).pcCard(if (message.isFromSupport) DesktopUiKit.NestedSurface else Color(0xFF19433F),
                            RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp, bottomStart = if (message.isFromSupport) 4.dp else 14.dp, bottomEnd = if (message.isFromSupport) 14.dp else 4.dp))
                            .padding(horizontal = 10.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            if (message.isFromSupport) PcText("Noki", size = 12, color = LocalDesktopStyle.current.accent)
                            for (attachment in message.attachments) {
                                val path = chat.attachmentFiles[attachment.id]
                                val loading = attachment.id in chat.attachmentLoading
                                val fileError = chat.attachmentErrors[attachment.id]
                                LaunchedEffect(attachment.id) {
                                    if (attachment.hasImagePreview && !loading && fileError == null) app.support.previewAttachment(attachment)
                                }
                                if (attachment.hasImagePreview && path != null) SupportAttachmentPreview(path, attachment.name) { app.support.openAttachment(attachment) }
                                PcText(attachment.name, size = 13)
                                if (fileError != null) PcText(fileError, size = 12, color = DesktopUiKit.Error)
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    PcText(tr(state, if (loading) "Загружаем…" else "Открыть", if (loading) "Loading…" else "Open"),
                                        Modifier.pcCard(Color.White.copy(alpha = .08f), RoundedCornerShape(8.dp))
                                            .pcClickable(enabled = canOpenSupportAttachment(attachment.mime) && !loading && !chat.isSavingAttachment,
                                                onClick = { app.support.openAttachment(attachment) }).padding(horizontal = 10.dp, vertical = 6.dp),
                                        size = 12, color = Color.White)
                                    PcText(tr(state, "Сохранить", "Save"),
                                        Modifier.pcCard(Color.White.copy(alpha = .08f), RoundedCornerShape(8.dp))
                                            .pcClickable(enabled = !loading && !chat.isSavingAttachment,
                                                onClick = { app.support.saveAttachment(attachment) }).padding(horizontal = 10.dp, vertical = 6.dp),
                                        size = 12, color = Color.White)
                                }
                            }
                            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                if (message.content.isNotBlank()) androidx.compose.foundation.text.selection.SelectionContainer(Modifier.weight(1f, fill = false)) {
                                    PcText(message.content, size = 14)
                                }
                                val time = remember(message.createdAt) {
                                    java.time.Instant.ofEpochSecond(message.createdAt).atZone(java.time.ZoneId.systemDefault())
                                        .format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))
                                }
                                PcText(time, Modifier.padding(bottom = 1.dp), size = 10, color = Color.White)
                                if (!message.isFromSupport) Icon(if (message.status == "read") Icons.Rounded.DoneAll else Icons.Rounded.Check,
                                    tr(state, if (message.status == "read") "Прочитано" else "Отправлено", if (message.status == "read") "Read" else "Sent"),
                                    Modifier.size(15.dp), tint = Color.White)
                            }
                        }
                        }
                    }
                }
                if (chat.nextBefore != null) item(key = "older") {
                    PcButton(tr(state, "Предыдущие сообщения", "Earlier messages"), Modifier.fillMaxWidth(), compact = true,
                        enabled = !chat.isLoadingOlder, onClick = { app.support.refresh(true) })
                }
            }
            if (unread && !atBottom) PcButton(tr(state, "Новые сообщения ↓", "New messages ↓"), Modifier.align(Alignment.BottomCenter), compact = true,
                onClick = { scope.launch { list.animateScrollToItem(0) } })
        }
        for ((error, retry) in listOf(chat.error to { app.support.refresh() }, chat.sendError to { app.support.send() })) {
            if (error != null) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PcText(error, Modifier.weight(1f), size = 12, color = DesktopUiKit.Error)
                PcButton(tr(state, "Повторить", "Retry"), compact = true, onClick = retry)
            }
        }
        chat.fileError?.let { PcText(it, size = 12, color = DesktopUiKit.Error) }
        if (chat.isPreparingAttachment || chat.isSavingAttachment) PcText(tr(state, "Обрабатываем файл…", "Processing file…"), size = 12, color = DesktopUiKit.Muted)
        if (chat.pendingAttachments.isNotEmpty()) Row(Modifier.fillMaxWidth().pcHorizontalScroll(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (attachment in chat.pendingAttachments) PcButton(attachment.name + " ×", compact = true, enabled = !chat.isSending,
                onClick = { app.support.removeAttachment(attachment.path) })
        }
        val canSend = !chat.isSending && !chat.isPreparingAttachment && (chat.draft.isNotBlank() || chat.pendingAttachments.isNotEmpty())
        CompositionLocalProvider(LocalPcCardColor provides Color(0xFF3C3C3C)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.Bottom) {
            Box(Modifier.size(56.dp, 54.dp).pcCard(shape = RoundedCornerShape(15.dp))
                .pcClickable(enabled = !chat.isSending && !chat.isPreparingAttachment && chat.pendingAttachments.size < SUPPORT_ATTACHMENT_MAX_COUNT,
                    shape = RoundedCornerShape(15.dp), onClick = app.support::pickAttachment)
                .semantics { contentDescription = tr(state, "Прикрепить файл", "Attach file") }, contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.AttachFile, null, Modifier.size(23.dp), tint = DesktopUiKit.Muted)
            }
            PcInput(chat.draft, app.support::updateDraft, tr(state, "Сообщение...", "Message..."),
                Modifier.weight(1f).heightIn(min = 54.dp, max = 136.dp),
                singleLine = false, enabled = !chat.isSending, shape = RoundedCornerShape(15.dp), fontSize = 16,
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 13.dp),
                onSubmit = { if (canSend) app.support.send() })
            PcButton("", Modifier.size(54.dp).semantics { contentDescription = tr(state, "Отправить", "Send") },
                glass = false, shape = RoundedCornerShape(15.dp), iconName = if (canSend) "chat-send-active" else "chat-send", iconSize = 20.dp,
                enabled = canSend, onClick = app.support::send)
        }
        }
        return
    }
    PcText(tr(state, "Поддержка", "Support"), size = DesktopUiKit.SectionTitleSize)
    SettingsSection(tr(state, "Контакты", "Contact us")) {
        PcMenuRow(tr(state, "Онлайн-чат", "Online chat"), leadingIcon = {
            Icon(Icons.Rounded.Forum, null, Modifier.size(26.dp), tint = Color.White)
        }) { app.support.open() }
        PcMenuRow(tr(state, "Написать в Telegram", "Message us on Telegram"), "@NokiSupport", leadingIcon = {
            Image(nokiPainterResource(R.drawable.login_telegram_icon), null, Modifier.size(24.dp), colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(Color.White))
        }) { openDesktopUrl("https://t.me/NokiSupport") }
        PcMenuRow(tr(state, "Почта", "Email"), "noki_support@ykino.tech", iconResource = R.drawable.security_email_icon) {
            openDesktopUrl("mailto:noki_support@ykino.tech")
        }
    }
}

@Composable private fun SupportAttachmentPreview(path: String, name: String, onOpen: () -> Unit) {
    val bitmap by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, path) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                javax.imageio.ImageIO.createImageInputStream(Path.of(path).toFile()).use { input ->
                    val reader = javax.imageio.ImageIO.getImageReaders(input).asSequence().firstOrNull() ?: return@use null
                    try {
                        reader.input = input
                        val width = reader.getWidth(0); val height = reader.getHeight(0)
                        require(width > 0 && height > 0 && width.toLong() * height <= 100_000_000)
                        val sample = maxOf(1, maxOf(width, height) / 512)
                        val parameters = reader.defaultReadParam.apply { setSourceSubsampling(sample, sample, 0, 0) }
                        val image = reader.read(0, parameters)
                        try { image.toComposeImageBitmap() } finally { image.flush() }
                    } finally { reader.dispose() }
                }
            }.getOrNull()
        }
    }
    bitmap?.let { Image(it, name, Modifier.widthIn(max = 320.dp).fillMaxWidth().height(160.dp)
        .clip(DesktopUiKit.MenuShape).pcClickable(onClick = onOpen), contentScale = ContentScale.Fit) }
}

@Composable internal fun DesktopTrayMenu(state: DesktopState, onOpen: () -> Unit, onToggle: () -> Unit, onExit: () -> Unit) {
    Column(Modifier.fillMaxSize().pcCard(DesktopUiKit.Surface, RoundedCornerShape(8.dp)).padding(vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp)) {
        TrayMenuItem(tr(state, "Открыть Noki Vpn", "Open Noki Vpn"), onClick = onOpen)
        TrayMenuItem(if (state.running || state.connecting) tr(state, "Выключить VPN", "Turn off VPN") else tr(state, "Включить VPN", "Turn on VPN"),
            enabled = state.user != null && (state.running || state.connecting || !state.loading), onClick = onToggle)
        TrayMenuItem(tr(state, "Выйти", "Quit"), onClick = onExit)
    }
}

@Composable private fun TrayMenuItem(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    Box(Modifier.fillMaxWidth().height(30.dp)
        .pcClickable(enabled = enabled, shape = RectangleShape, hoverColor = Color(0xFF3C3C3C), onClick = onClick)
        .padding(horizontal = 16.dp), contentAlignment = Alignment.CenterStart) {
        PcText(label, size = 14, maxLines = 1, color = DesktopUiKit.Text.copy(alpha = if (enabled) 1f else .45f))
    }
}

@Composable private fun DevicesPage(state: DesktopState, app: DesktopController, shared: Boolean,
    onPage: (DesktopPage) -> Unit, show: ((@Composable () -> Unit)?) -> Unit) {
    if (state.invited) { PcText(tr(state, "Это действие доступно только владельцу аккаунта", "This action is available to the account owner only")); return }
    val busy = state.loading || state.devicesLoading
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedIncy by rememberSaveable { mutableStateOf(false) }
    val selectedDevice = state.devices.firstOrNull { it.id == selectedId && !selectedIncy }
    val selectedExternal = state.incyDevices.firstOrNull { it.id == selectedId && selectedIncy }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (shared) Box(Modifier.size(28.dp).pcClickable(role = Role.Button) { onPage(DesktopPage.DEVICES) }, contentAlignment = Alignment.Center) {
                PcIcon("chevronRight", tr(state, "Назад", "Back"), Modifier.size(10.dp, 14.dp).graphicsLayer { rotationZ = 180f })
            }
            PcText(tr(state, if (shared) "Доп. устройства" else "Устройства", if (shared) "Extra devices" else "Devices"),
                Modifier.weight(1f), size = DesktopUiKit.SectionTitleSize)
            val count = if (shared) state.devices.count { it.isActive && it.trafficPool == "shared" } +
                state.incyDevices.count { it.isSlotActive && it.trafficPool == "shared" } else state.primaryDeviceCount
            val limit = if (shared) null else state.primaryDeviceLimit
            PcText(if (limit != null) tr(state, "Подключено $count из $limit устройств", "$count of $limit devices connected")
                else tr(state, "Подключено устройств: $count", "Connected devices: $count"), size = 11, color = LocalDesktopStyle.current.accent)
        }
        if (state.devicesLoading) PcText(tr(state, "Обновляем устройства…", "Updating devices…"), size = 12, color = DesktopUiKit.Muted)
        if (shared) Column(Modifier.fillMaxWidth().pcCard().padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            PcText(tr(state, "Общий трафик", "Shared traffic"), size = 14)
            val used = state.subscription?.sharedTrafficUsedGb?.let { TrafficFormat.gigabytes(it, state.language).label } ?: "—"
            val limit = state.subscription?.sharedTrafficLimitGb?.let { TrafficFormat.gigabytes(it, state.language).label } ?: "—"
            PcText("$used / $limit", size = 24)
        }
        if (!shared) state.devices.firstOrNull { it.id == state.currentDeviceId }?.let { device ->
            DesktopDeviceRow(device.customName ?: device.deviceName, device.platform, device.platform, status = tr(state, "Текущее устройство", "Current device")) {
                selectedIncy = false; selectedId = device.id
            }
        }
        if (!shared) BoxWithConstraints(Modifier.fillMaxWidth()) {
            val logoutOthers: @Composable (Modifier) -> Unit = { modifier ->
            PcButton(tr(state, "Удалить все устройства кроме текущего", "Remove all other devices"), modifier.height(45.dp), compact = true,
                shape = DesktopUiKit.MenuShape,
                iconName = "logout", iconSize = 17.dp, enabled = !busy, onClick = {
                    show { PcDialog(tr(state, "Выйти на других устройствах?", "Log out other devices?"), { show(null) }, tr(state, "Выйти", "Logout"), danger = true,
                        onConfirm = { app.logoutOthers(); show(null) }) { } }
                })
            }
            val connectDevice: @Composable (Modifier) -> Unit = { modifier ->
                PcButton(tr(state, "Подключить устройство", "Connect device"), modifier.height(45.dp), compact = true,
                    shape = DesktopUiKit.MenuShape,
                    iconName = "qr", iconSize = 17.dp, enabled = !busy, onClick = app::createInvite)
            }
            if (maxWidth < 450.dp) Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                logoutOthers(Modifier.fillMaxWidth())
                connectDevice(Modifier.fillMaxWidth())
            } else Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                logoutOthers(Modifier.weight(1.84f))
                connectDevice(Modifier.weight(1f))
            }
        }
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            PcText(tr(state, "Noki устройства", "Noki devices"), Modifier.padding(start = 4.dp), size = 14, color = DesktopUiKit.Muted)
            for (device in state.devices.filter { (it.trafficPool == "shared") == shared && (shared || it.id != state.currentDeviceId) }) {
                DesktopDeviceRow(device.customName ?: device.deviceName, device.platform, device.platform) { selectedIncy = false; selectedId = device.id }
            }
        }
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            PcText("INCY", Modifier.padding(start = 4.dp), size = 14, color = DesktopUiKit.Muted)
            if (!shared) DesktopDeviceRow(tr(state, "Создать устройство incy", "Create incy device"), incy = true, enabled = !busy) {
                show { NameDialog("incy / v2ray", "", { show(null) }) { app.createIncy(it); show(null) } }
            }
            for (device in state.incyDevices.filter { (it.trafficPool == "shared") == shared }) DesktopDeviceRow(device.name, device.deviceModel ?: device.deviceOs ?:
                tr(state, if (device.status.equals("waiting", true)) "Ожидает подключения" else "incy / v2ray", if (device.status.equals("waiting", true)) "Waiting for connection" else "incy / v2ray"), incy = true) {
                selectedIncy = true; selectedId = device.id
            }
        }

        if (!shared) PcButton(tr(state, "Доп. устройства", "Extra devices"), Modifier.fillMaxWidth(), onClick = { onPage(DesktopPage.EXTRA_DEVICES) })
    }
    if (selectedDevice != null || selectedExternal != null) PcDialog(
        tr(state, if (selectedIncy) "Устройство INCY" else "Устройство", if (selectedIncy) "INCY device" else "Device"),
        { selectedId = null }, dismissLabel = tr(state, "Закрыть", "Close")) {
        if (selectedDevice != null) {
            Column(Modifier.fillMaxWidth().pcCard().padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                DesktopDeviceRow(selectedDevice.customName ?: selectedDevice.deviceName, selectedDevice.platform, selectedDevice.platform, identity = true)
                selectedDevice.lastSeenAt?.let { PcText(tr(state, "Последняя активность: ", "Last active: ") + formatDesktopDate(it, language = state.language), size = 12, lineHeight = 16, color = Color(0xFFB9B9B9)) }
            }
            if (selectedDevice.id != state.currentDeviceId) PcToggle(tr(state, "Полный доступ", "Full access"), selectedDevice.accessRole == "owner", !busy) { app.setDeviceAccess(selectedDevice.id, it) }
            PcButton(tr(state, "Переименовать", "Rename"), Modifier.fillMaxWidth(), enabled = !busy, onClick = {
                selectedId = null
                show { NameDialog(tr(state, "Переименовать", "Rename"), selectedDevice.customName ?: selectedDevice.deviceName, { show(null) }) { app.renameDevice(selectedDevice.id, it); show(null) } }
            })
            PcButton(tr(state, "Удалить устройство", "Remove device"), Modifier.fillMaxWidth(), enabled = !busy, danger = true, onClick = {
                selectedId = null
                show { PcDialog(tr(state, "Удалить устройство?", "Remove device?"), { show(null) }, tr(state, "Удалить", "Remove"), danger = true,
                    onConfirm = { app.deleteDevice(selectedDevice.id); show(null) }) { PcText(selectedDevice.customName ?: selectedDevice.deviceName) } }
            })

            PcButton(tr(state, if (selectedDevice.trafficPool == "shared") "В основные устройства" else "В доп. устройства",
                if (selectedDevice.trafficPool == "shared") "Move to primary devices" else "Move to extra devices"), Modifier.fillMaxWidth(),
                enabled = !busy, onClick = {
                    app.setDeviceTrafficPool(selectedDevice.id, if (selectedDevice.trafficPool == "shared") "primary" else "shared")
                    selectedId = null
                })
        } else if (selectedExternal != null) {
            Column(Modifier.fillMaxWidth().pcCard().padding(20.dp)) {
                DesktopDeviceRow(selectedExternal.name, listOfNotNull(selectedExternal.deviceOs, "incy / v2ray").joinToString(" · "), incy = true, identity = true)
            }
            PcButton(tr(state, "Ссылка подключения", "Connection link"), Modifier.fillMaxWidth(), selected = true, enabled = !busy, onClick = { selectedId = null; app.incyLinks(selectedExternal.id) })
            PcButton(tr(state, "Переименовать", "Rename"), Modifier.fillMaxWidth(), enabled = !busy, onClick = {
                selectedId = null
                show { NameDialog(tr(state, "Переименовать", "Rename"), selectedExternal.name, { show(null) }) { app.renameIncy(selectedExternal.id, it); show(null) } }
            })
            PcButton(tr(state, "Перевыпустить ссылку", "Reissue link"), Modifier.fillMaxWidth(), enabled = !busy, onClick = {
                selectedId = null
                show { PcDialog(tr(state, "Перевыпустить ссылку?", "Reissue link?"), { show(null) }, tr(state, "Перевыпустить", "Reissue"),
                    onConfirm = { app.incyLinks(selectedExternal.id, true); show(null) }) { } }
            })
            PcButton(tr(state, "Удалить устройство", "Remove device"), Modifier.fillMaxWidth(), danger = true, enabled = !busy, onClick = {
                selectedId = null
                show { PcDialog(tr(state, "Удалить устройство?", "Remove device?"), { show(null) }, tr(state, "Удалить", "Remove"), danger = true,
                    onConfirm = { app.deleteIncy(selectedExternal.id); show(null) }) { PcText(selectedExternal.name) } }
            })

            PcButton(tr(state, if (selectedExternal.trafficPool == "shared") "В основные устройства" else "В доп. устройства",
                if (selectedExternal.trafficPool == "shared") "Move to primary devices" else "Move to extra devices"), Modifier.fillMaxWidth(),
                enabled = !busy, onClick = {
                    app.setIncyTrafficPool(selectedExternal.id, if (selectedExternal.trafficPool == "shared") "primary" else "shared")
                    selectedId = null
                })
        }
    }
}

@Composable private fun DesktopDeviceRow(name: String, detail: String? = null, platform: String = "", incy: Boolean = false,
    status: String? = null, identity: Boolean = false, enabled: Boolean = true, onClick: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().then(if (identity) Modifier else Modifier.heightIn(min = 70.dp).pcCard())
        .then(if (onClick != null) Modifier.pcClickable(enabled = enabled, role = Role.Button, onClick = onClick) else Modifier)
        .then(if (identity) Modifier else Modifier.padding(horizontal = 20.dp, vertical = 12.dp)),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(15.dp)) {
        Box(Modifier.size(if (identity) 28.dp else 24.5.dp), contentAlignment = Alignment.Center) {
            if (incy) Image(nokiPainterResource(R.drawable.settings_devices_icon), null, Modifier.fillMaxSize())
            else if (platform.contains("windows", true) || platform.contains("mac", true) || platform.contains("linux", true)) PcIcon("monitor", null, Modifier.size(24.5.dp, 22.5.dp))
            else com.noki.vpn.ui.PhoneGlyph(.8f)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            PcText(name, size = if (identity) 18 else 14, lineHeight = if (identity) 24 else 20, maxLines = 1)
            detail?.let { PcText(it, size = 12, lineHeight = 16, color = Color(0xFFB9B9B9), maxLines = 1) }
        }
        if (status != null) PcText(status, size = 11, color = LocalDesktopStyle.current.accent)
        else if (onClick != null) PcIcon("chevronRight", null, Modifier.size(9.dp, 12.dp))
    }
}

@Composable private fun NameDialog(title: String, initial: String, dismiss: () -> Unit, submit: (String) -> Unit) {
    var value by remember { mutableStateOf(initial) }
    val russian = LocalDesktopLanguage.current == AppLanguage.RU
    PcDialog(title, dismiss, if (russian) "Сохранить" else "Save", enabled = value.trim().isNotBlank(), onConfirm = { submit(value) }) {
        PcInput(value, { value = it.take(120) }, if (russian) "Имя" else "Name", Modifier.fillMaxWidth())
    }
}

internal fun openDesktopUrl(value: String) {
    val uri = URI(value)
    require(uri.scheme == "https" && uri.userInfo == null)
    WindowsShell.openUrl(uri.toASCIIString())
}

@Composable internal fun PcTitlebar(
    onMinimize: () -> Unit = {},
    onMaximize: () -> Unit = {},
    onClose: () -> Unit = {},
    dragArea: Modifier = Modifier,
    controls: (@Composable () -> Unit)? = null,
) {
    val russian = LocalDesktopLanguage.current == AppLanguage.RU
    Row(Modifier.fillMaxWidth().height(40.dp).drawBehind {
            drawLine(Color(0xFF2C2C2C), Offset(0f, size.height), Offset(size.width, size.height), .5.dp.toPx())
        }, verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f).fillMaxHeight().then(dragArea)) {
            Box(Modifier.fillMaxSize().padding(start = 20.dp), contentAlignment = Alignment.CenterStart) {
                PcIcon("logo", "Noki", Modifier.size(45.37.dp, 17.5.dp))
            }
        }
        if (controls != null) controls() else for ((icon, label, action) in listOf(Triple("minimize", if (russian) "Свернуть" else "Minimize", onMinimize),
            Triple("maximize", if (russian) "Развернуть" else "Maximize", onMaximize), Triple("close", if (russian) "Закрыть" else "Close", onClose))) {
            PcWindowControl(icon, label, action)
        }
    }
}

@Composable internal fun PcWindowControl(icon: String, label: String, onClick: () -> Unit) {
    Box(Modifier.width(42.dp).height(40.dp).pcClickable(role = Role.Button, shape = RectangleShape,
        hoverColor = if (icon == "close") Color(0xFFE5484D) else Color.White.copy(alpha = .08f), onClick = onClick), contentAlignment = Alignment.Center) {
        Image(pcPainter(icon), label, Modifier.size(8.dp, if (icon == "minimize") 1.dp else 8.dp),
            colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(Color.White))
    }
}

@Composable internal fun PcNavigation(page: DesktopPage, modifier: Modifier = Modifier, state: DesktopState? = null,
    onUpdate: () -> Unit = {}, onPage: (DesktopPage) -> Unit) {
    val style = LocalDesktopStyle.current
    val russian = LocalDesktopLanguage.current == AppLanguage.RU
    Column(modifier.width(70.dp).semantics { contentDescription = if (russian) "Навигация" else "Navigation" }
        .then(if (page == DesktopPage.HOME)
            Modifier.pcGlass(DesktopUiKit.Glass, DesktopUiKit.PanelShape, blurRadius = 63.dp, lightIntensity = 0f, depth = 14.dp, refraction = .73f)
            else Modifier.pcCard(DesktopUiKit.Surface, DesktopUiKit.PanelShape))
        .padding(start = 10.dp, end = 10.dp, top = 15.dp, bottom = 10.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(20.dp)) {
        for ((target, icon, label) in listOf(Triple(DesktopPage.HOME, "home", if (russian) "Главная" else "Home"),
            Triple(DesktopPage.PROFILE, "profile", if (russian) "Профиль" else "Profile"), Triple(DesktopPage.SETTINGS, "settings", if (russian) "Настройки" else "Settings"))) {
            val selected = page == target
            Box(Modifier.size(40.dp).then(if (selected) Modifier.pcGlass(style.selected, DesktopUiKit.Shape, blurRadius = 0.dp, lightIntensity = DesktopUiKit.ControlHighlight, depth = 18.dp) else Modifier)
                .pcClickable(role = Role.Tab, shape = DesktopUiKit.Shape, onClick = { onPage(target) }), contentAlignment = Alignment.Center) {
                PcIcon(icon, label, Modifier.size(if (icon == "home") 11.dp else if (icon == "profile") 14.dp else 15.dp,
                    if (icon == "home") 18.dp else 16.dp))
                if (target == DesktopPage.PROFILE && state?.hasUnreadNotifications == true)
                    Box(Modifier.align(Alignment.TopEnd).padding(top = 6.dp, end = 6.dp).size(6.dp).background(DesktopUiKit.Error, CircleShape))
            }
        }
        Spacer(Modifier.weight(1f))
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (LocalDesktopUpdate.current != null) Box(Modifier.size(40.dp)
                .pcCard(style.selected, DesktopUiKit.Shape).pcClickable(role = Role.Button, shape = DesktopUiKit.Shape, onClick = onUpdate), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.ArrowDownward, if (russian) "Установить обновление" else "Install update", Modifier.size(22.dp), tint = DesktopUiKit.Text)
            }
            val avatar = nokiAvatarImage(state?.avatarPath)
            Box(Modifier.size(50.dp).pcCard(DesktopUiKit.NestedSurface, DesktopUiKit.MenuShape).pcClickable(shape = DesktopUiKit.MenuShape) { onPage(DesktopPage.PERSONALIZATION) }, contentAlignment = Alignment.Center) {
                if (avatar != null) Image(BitmapPainter(avatar), if (russian) "Персонализация" else "Personalization", Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                else PcText(state?.user?.username?.firstOrNull()?.uppercase() ?: "N", size = 20)
            }
        }
    }
}

@Composable private fun DesktopUpdateDialog(state: DesktopState, update: DesktopUpdateUi, onDismiss: () -> Unit) {
    val status = update.state
    PcDialog(tr(state, "Обновление Noki Vpn", "Noki Vpn update"), onDismiss,
        confirmLabel = if (status.stage == DesktopUpdateStage.FAILED) tr(state, "Повторить", "Retry") else null,
        onConfirm = update.install, dismissLabel = tr(state, "Закрыть", "Close"), scrollContent = false) {
        PcText(tr(state, "Версия ", "Version ") + status.version.orEmpty(), size = 16)
        PcText(when (status.stage) {
            DesktopUpdateStage.AVAILABLE -> tr(state, "Подготовка загрузки…", "Preparing download…")
            DesktopUpdateStage.DOWNLOADING -> tr(state, "Загрузка обновления", "Downloading update") + " · ${status.percent}%"
            DesktopUpdateStage.READY -> tr(state, "Завершаем VPN перед установкой…", "Stopping the VPN before installation…")
            DesktopUpdateStage.APPLYING -> tr(state, "Установка и перезапуск…", "Installing and restarting…")
            DesktopUpdateStage.FAILED -> when (status.error) {
                "vpn_stop_failed" -> tr(state, "Не удалось завершить VPN. Отключите его и повторите обновление.", "Could not stop the VPN. Disconnect it and retry the update.")
                "apply_failed" -> tr(state, "Не удалось запустить установку. Повторите попытку.", "Could not start installation. Please retry.")
                else -> tr(state, "Не удалось загрузить обновление. Проверьте подключение и повторите.", "Could not download the update. Check your connection and retry.")
            }
        }, size = 14)
        androidx.compose.material3.LinearProgressIndicator(progress = { status.percent / 100f }, Modifier.fillMaxWidth(),
            color = LocalDesktopStyle.current.accent, trackColor = Color.White.copy(alpha = .12f), drawStopIndicator = {})
        PcText(tr(state, "После установки Noki Vpn откроется снова.", "Noki Vpn will reopen after installation."), size = 12, color = DesktopUiKit.Muted)
    }
}

@Composable internal fun ServerRefreshButton(state: DesktopState, uiActive: Boolean, reducedMotion: Boolean = state.reducedMotion,
    onRefresh: suspend () -> Boolean) {
    val scope = rememberCoroutineScope()
    var refreshing by remember { mutableStateOf(false) }
    var completed by remember { mutableStateOf(false) }
    val accent = LocalDesktopStyle.current.accent
    Box(Modifier.size(32.dp).semantics {
        contentDescription = tr(state, "Обновить список серверов", "Refresh server list")
        stateDescription = when {
            refreshing -> tr(state, "Обновляем список серверов", "Refreshing server list")
            completed -> tr(state, "Список серверов обновлён", "Server list updated")
            else -> tr(state, "Готово к обновлению", "Ready to refresh")
        }
    }.pcClickable(enabled = !state.loading && !refreshing && !completed) {
        if (refreshing || completed) return@pcClickable
        refreshing = true
        scope.launch {
            try {
                val success = onRefresh()
                refreshing = false
                completed = success
                if (success) delay(1_000)
            } finally { refreshing = false; completed = false }
        }
    }, contentAlignment = Alignment.Center) {
        OperationIcon(refreshing && uiActive, completed && uiActive, reducedMotion || !uiActive,
            accent, Modifier.size(20.dp)) {
            Icon(Icons.Rounded.Refresh, null, Modifier.fillMaxSize(), tint = DesktopUiKit.Muted)
        }
    }
}

@Composable private fun OperationIcon(busy: Boolean, completed: Boolean, reducedMotion: Boolean,
    color: Color, modifier: Modifier, idle: @Composable () -> Unit) {
    Crossfade(when { busy -> 1; completed -> 2; else -> 0 }, modifier,
        animationSpec = tween(if (reducedMotion) 0 else 150), label = "operation-icon") { phase ->
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            when (phase) {
                1 -> if (reducedMotion) CircularProgressIndicator(progress = { .75f }, Modifier.fillMaxSize(), color, strokeWidth = 2.dp)
                    else CircularProgressIndicator(Modifier.fillMaxSize(), color, strokeWidth = 2.dp)
                2 -> Icon(Icons.Rounded.Check, null, Modifier.fillMaxSize(), tint = color)
                else -> idle()
            }
        }
    }
}

@Composable private fun HomeConnectionPane(
    state: DesktopState,
    app: DesktopController,
    modifier: Modifier,
    protocolOpen: Boolean = false,
    uiActive: Boolean = true,
    reducedMotion: Boolean = state.reducedMotion,
    onPage: (DesktopPage) -> Unit,
    onConnectionSettings: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val displayed = state.displayedSettings
    val locations = state.locationsFor(displayed.advancedSettings.ruRelayEnabled)
    val selection = displayed.userProfile
    val manualGroup = EndpointGroupPolicy.resolveManualGroupKey(state.settings.advancedSettings, state.endpointOptions, state.settings.profile)
    val location = if (selection.serverSelectionMode == ServerSelectionMode.AUTO && !state.hasEstablishedTunnel) null
        else if (state.running || selection.serverSelectionMode == ServerSelectionMode.SERVER)
        locations.firstOrNull { country -> country.servers.any { it.id == state.selectedServerId } }
        else if (selection.serverSelectionMode == ServerSelectionMode.COUNTRY) locations.firstOrNull { it.countryCode == selection.selectedCountryCode } else null
    val connection = when {
        state.hasEstablishedTunnel -> VpnConnectionState.CONNECTED
        state.connecting -> VpnConnectionState.CONNECTING
        state.running -> VpnConnectionState.FAILED
        else -> VpnConnectionState.DISCONNECTED
    }
    var wasConnected by remember { mutableStateOf(connection == VpnConnectionState.CONNECTED) }
    var connectionCompleted by remember { mutableStateOf(false) }
    LaunchedEffect(connection, uiActive) {
        connectionCompleted = uiActive && connection == VpnConnectionState.CONNECTED && !wasConnected
        wasConnected = connection == VpnConnectionState.CONNECTED
        if (connectionCompleted) { delay(1_000); connectionCompleted = false }
    }
    val style = LocalDesktopStyle.current
    val multiHop = displayed.advancedSettings.multiHop
    val displayLocation = if (multiHop.enabled && (!state.running || state.connectionSettings != null)) hopLocation(state, multiHop.exit, locations) else location
    val title = when {
        state.running && state.connectionSettings == null -> state.connectionLabel.ifBlank { tr(state, "Активное соединение", "Active connection") }
        multiHop.enabled -> "${hopLabel(state, multiHop.entry, locations)} → ${hopLabel(state, multiHop.exit, locations)}"
        displayed.advancedSettings.ruRelayEnabled -> "${tr(state, "RU-реле", "RU relay")} → ${location?.servers?.firstOrNull { it.id == state.selectedServerId }?.name ?: location?.country ?: tr(state, "Автовыбор", "Automatic") }"
        selection.serverSelectionMode == ServerSelectionMode.AUTO -> tr(state, "Автовыбор", "Automatic")
        else -> location?.country ?: tr(state, "Нет сервера", "No server")
    }
    val subtitle = when {
        state.running && state.connectionSettings == null -> null
        multiHop.enabled || displayed.advancedSettings.ruRelayEnabled -> null
        selection.serverSelectionMode == ServerSelectionMode.SERVER -> location?.servers?.firstOrNull { it.id == state.selectedServerId }?.name
        selection.serverSelectionMode == ServerSelectionMode.AUTO -> location?.country
        else -> null
    }
    val traffic = TrafficFormat.bytes(state.sessionBytes ?: 0, state.language)
    BoxWithConstraints(modifier) {
        val panelWidth = minOf(DesktopUiKit.HomePanelWidth, maxWidth - 130.dp)
        val panelBackdrop = rememberLayerBackdrop()
        Box(Modifier.padding(start = 110.dp, top = 32.dp, bottom = 32.dp).width(panelWidth).fillMaxHeight()) {
            Box(Modifier.matchParentSize().pcConnectionShadow().layerBackdrop(panelBackdrop)
                .pcGlass(DesktopUiKit.Glass, DesktopUiKit.PanelShape, blurRadius = 63.dp, lightIntensity = .18f, rimWidth = .5.dp, depth = 14.dp, refraction = .73f))
            CompositionLocalProvider(LocalDesktopStyle provides style.copy(backdrop = panelBackdrop)) {
                Column(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 15.dp)) {
                    Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
                        PcSurface(Modifier.fillMaxWidth().height(90.dp).pcConnectionShadow(), enabled = !state.loading, onClick = { onPage(DesktopPage.SERVERS) }) {
                            Row(Modifier.fillMaxSize().padding(horizontal = 15.dp), verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                if (displayLocation != null) CountryFlagMarker(displayLocation, 1f, 55f)
                                else Box(Modifier.size(55.dp), contentAlignment = Alignment.Center) {
                                    Icon(Icons.Rounded.AutoAwesome, tr(state, "Автовыбор сервера", "Automatic server selection"), Modifier.size(36.dp), tint = style.accent)
                                }
                                Column(Modifier.weight(1f)) {
                                    PcText(title, size = 24, lineHeight = 28, maxLines = if (multiHop.enabled || displayed.advancedSettings.ruRelayEnabled) 2 else 1)
                                    subtitle?.takeIf { it.isNotBlank() }?.let { PcText(it, size = 12, lineHeight = 16, color = Color(0xFFB9B9B9), maxLines = 2) }
                                }
                            }
                        }
                        Spacer(Modifier.height(20.dp))
                        val metrics = listOf(
                            Triple("upload", state.uploadBytesPerSecond?.let { (it * 8.0 / 1_000_000).formatMetricValue() } ?: "--", tr(state, "Мбит/с", "Mb/s")),
                            Triple("latency", state.latencyMs?.toString() ?: "--", tr(state, "мс", "ms")),
                            Triple("download", state.downloadBytesPerSecond?.let { (it * 8.0 / 1_000_000).formatMetricValue() } ?: "--", tr(state, "Мбит/с", "Mb/s")),
                            Triple("traffic", traffic.value.takeIf { state.sessionBytes != null } ?: "--", traffic.unit),
                        )
                        Column(Modifier.fillMaxWidth().height(120.dp), verticalArrangement = Arrangement.spacedBy(15.dp)) {
                            for (metricRow in metrics.chunked(2)) Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                for ((icon, value, unit) in metricRow) PcSurface(Modifier.weight(1f).fillMaxHeight().pcConnectionShadow()
                                    .then(if (icon == "latency" && state.running) Modifier.semantics { contentDescription = tr(state, "Обновить пинг", "Refresh latency") } else Modifier),
                                    enabled = !state.loading, onClick = if (icon == "latency" && state.running) ({ scope.launch { app.refreshServerLatencies() } }) else null) {
                                    Row(Modifier.align(Alignment.Center), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Box(Modifier.size(14.dp), contentAlignment = Alignment.Center) {
                                            PcIcon(icon, null, Modifier.size(
                                                (when (icon) { "latency" -> 14f; "traffic" -> 10.4601f; else -> 8.78003f }).dp,
                                                (when (icon) { "latency" -> 14f; "traffic" -> 11.5103f; else -> 10.6925f }).dp))
                                        }
                                        PcText(value, size = 15, metric = true, maxLines = 1)
                                        PcText(unit, size = 12, metric = true, color = DesktopUiKit.Muted, maxLines = 1)
                                    }
                                }
                            }
                        }
                        Spacer(Modifier.height(20.dp))
                        val protocolLabel = desktopProtocolLabel(state)
                        PcButton(tr(state, "Протокол: $protocolLabel", "Protocol: $protocolLabel"), Modifier.padding(start = 1.dp).fillMaxWidth().height(50.dp).pcConnectionShadow(),
                            compact = true, glass = true, glassColor = DesktopUiKit.Glass, shape = DesktopUiKit.PanelShape,
                            enabled = !state.loading && !state.settings.advancedSettings.ruRelayEnabled && !state.settings.advancedSettings.gamingModeEnabled,
                            onClick = { onPage(DesktopPage.PROTOCOL) })
                        Spacer(Modifier.height(12.dp))
                        PcSurface(Modifier.padding(start = 1.dp).fillMaxWidth().height(50.dp).pcConnectionShadow(), onClick = { onPage(DesktopPage.ROUTING) }) {
                            Row(Modifier.align(Alignment.Center), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                PcIcon("routing", null, Modifier.size(23.dp, 18.dp))
                                PcText(tr(state, "Маршрутизация", "Routing"), size = 12)
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        PcButton(tr(state, "Настройки соединения", "Connection settings"), Modifier.padding(start = 1.dp).fillMaxWidth().height(50.dp).pcConnectionShadow(),
                            compact = true, glass = true, glassColor = DesktopUiKit.Glass, shape = DesktopUiKit.PanelShape,
                            enabled = !state.loading, onClick = onConnectionSettings)
                    }
                    Spacer(Modifier.height(20.dp))
                    Row(Modifier.padding(start = 1.dp).fillMaxWidth().height(81.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        PcSurface(Modifier.width(84.dp).height(80.dp).pcConnectionShadow().semantics {
                            contentDescription = if (state.running || state.connecting) tr(state, "Отключить VPN", "Disconnect VPN") else tr(state, "Подключить VPN", "Connect VPN")
                            stateDescription = when {
                                connectionCompleted -> tr(state, "VPN подключён", "VPN connected")
                                state.connecting -> tr(state, "Подключаемся", "Connecting")
                                else -> tr(state, "Готово", "Ready")
                            }
                        },
                            if (connection == VpnConnectionState.CONNECTED || connection == VpnConnectionState.CONNECTING) style.selected else DesktopUiKit.Error.copy(alpha = .2f),
                            enabled = state.running || state.connecting || !state.loading,
                            onClick = { if (state.running || state.connecting) app.disconnect() else app.connect() }) {
                            Box(Modifier.align(Alignment.Center).size(28.dp), contentAlignment = Alignment.Center) {
                                OperationIcon(state.connecting && uiActive, connectionCompleted, reducedMotion || !uiActive,
                                    Color.White, Modifier.fillMaxSize()) {
                                    PcIcon("power", null, Modifier.size(25.dp, 27.dp))
                                }
                            }
                        }
                        PcSurface(Modifier.weight(1f).fillMaxHeight().pcConnectionShadow()) {
                            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                                HomeConnectionDurationText(connection, state.connectedAtMillis, uiActive)
                                PcText(when (connection) {
                                    VpnConnectionState.CONNECTED -> if (state.networkRebinding) tr(state, "Восстановление связи", "Restoring connection")
                                        else tr(state, "Подключено", "Connected")
                                    VpnConnectionState.CONNECTING -> tr(state, "Подключаемся", "Connecting")
                                    VpnConnectionState.FAILED -> tr(state, "Не удалось подключиться", "Connection failed")
                                    else -> tr(state, "Не подключено", "Not connected")
                                }, size = 12, color = if (connection in setOf(VpnConnectionState.FAILED, VpnConnectionState.DISCONNECTED)) DesktopUiKit.Error else style.accent, maxLines = 1)
                            }
                        }
                    }
                }
            }
        }
        if (protocolOpen) {
            Box(Modifier.fillMaxSize().clickable(
                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                indication = null, onClick = { onPage(DesktopPage.HOME) }))
            Column(Modifier.offset(x = 110.dp, y = 20.dp).width(minOf(390.dp, maxWidth - 130.dp))
                .heightIn(max = maxHeight - 40.dp).pcGlass(Color(0xFF1A1A1A).copy(alpha = .85f), blurRadius = 4.dp)
                .padding(20.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                PcText(tr(state, "Протокол", "Protocol"), size = 24)
                CompositionLocalProvider(LocalPcCardColor provides DesktopUiKit.NestedSurface) {
                    PcButton("Auto", Modifier.fillMaxWidth().height(44.dp),
                        selected = state.settings.advancedSettings.endpointSelectionMode == EndpointSelectionMode.AUTO,
                        enabled = !state.loading && !state.settings.advancedSettings.gamingModeEnabled,
                        onClick = { app.selectEndpoint(null); onPage(DesktopPage.HOME) })
                    for (option in EndpointGroupPolicy.protocolOptions(state.endpointOptions)) PcButton(option.label, Modifier.fillMaxWidth().height(44.dp),
                        selected = state.settings.advancedSettings.endpointSelectionMode == EndpointSelectionMode.MANUAL && manualGroup == EndpointGroupPolicy.groupKey(option),
                        enabled = !state.loading && !state.settings.advancedSettings.gamingModeEnabled,
                        onClick = { app.selectEndpoint(option); onPage(DesktopPage.HOME) })
                    if (state.loading) CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally).size(20.dp), color = style.accent, strokeWidth = 2.dp)
                }
            }
        }
    }
}

internal fun desktopProtocolLabel(state: DesktopState): String {
    if (state.running && state.connectionSettings == null) return "—"
    val displayed = state.displayedSettings
    val settings = displayed.advancedSettings
    if (settings.ruRelayEnabled) return "WireGuard"
    val gaming = settings.gamingModeEnabled
    val profile = displayed.profile
    val currentOption = VpnEndpointOption(code = profile.endpointCode, proxyType = profile.proxyType,
        transport = profile.transport, transportMode = profile.transportMode, security = profile.security)
    val activeNv = state.hasEstablishedTunnel && profile.proxyType.equals("nokinv", true)
    val label = if (activeNv || gaming) "NokiNV" else if (settings.endpointSelectionMode == EndpointSelectionMode.MANUAL) {
        val group = EndpointGroupPolicy.resolveManualGroupKey(settings, state.endpointOptions, displayed.profile)
        EndpointGroupPolicy.protocolOptions(state.endpointOptions).firstOrNull { EndpointGroupPolicy.groupKey(it) == group }?.label
            ?: tr(state, "Ручной", "Manual")
    } else if (state.hasEstablishedTunnel && profile.endpointCode.isNotBlank())
        "Auto (${EndpointGroupPolicy.displayLabelFor(currentOption)})" else "Auto"
    return label + if (gaming) tr(state, " · игровой", " · gaming") else ""
}

@Composable internal fun HomeConnectionDurationText(connection: VpnConnectionState, connectedAtMillis: Long?, uiActive: Boolean,
    nowMillis: () -> Long = System::currentTimeMillis) {
    val duration = rememberConnectionTimeLabel(connection, connectedAtMillis, uiActive, nowMillis)
    PcText(if (connection == VpnConnectionState.CONNECTED) {
        if (duration.length == 5 && duration.first().isDigit()) "00:$duration" else duration
    } else "00:00:00", size = 18, maxLines = 1)
}

@Composable internal fun CountryCatalog(state: DesktopState, modifier: Modifier, selection: HopSelection, allowAuto: Boolean = true,
    locations: List<ServerLocation> = state.connectionLocations, onSelect: (HopSelection) -> Unit) {
    var expandedCountry by rememberSaveable { mutableStateOf(if (selection.kind == ServerSelectionMode.SERVER)
        locations.firstOrNull { country -> country.servers.any { it.id == selection.nodeId } }?.code else null) }
    val listState = rememberLazyListState()
    val accent = LocalDesktopStyle.current.accent
    LazyColumn(modifier.pointerInput(listState) {
        detectVerticalDragGestures { change, delta -> listState.dispatchRawDelta(-delta); change.consume() }
    }, state = listState, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (allowAuto) {
            item {
                CompositionLocalProvider(LocalPcCardColor provides DesktopUiKit.Surface) {
                    PcButton(tr(state, "Автовыбор", "Automatic"), Modifier.fillMaxWidth().height(48.dp)
                        .border(.5.dp, if (selection.kind == ServerSelectionMode.AUTO) accent.copy(alpha = .3f) else Color.White.copy(alpha = .08f), DesktopUiKit.MenuShape),
                        selected = selection.kind == ServerSelectionMode.AUTO,
                        enabled = !state.loading, onClick = { onSelect(HopSelection(ServerSelectionMode.AUTO)) })
                }
            }
            item { PcText(tr(state, "Все страны", "All countries"), Modifier.padding(top = 8.dp, bottom = 3.dp), size = 12, color = Color(0xFFB9B9B9)) }
        }
        if (locations.isEmpty()) item { PcText(if (state.loading) tr(state, "Загрузка", "Loading") else tr(state, "Нет серверов", "No servers"), Modifier.padding(20.dp), color = DesktopUiKit.Muted) }
        items(locations, key = { it.code }) { country ->
            val expanded = expandedCountry == country.code
            val selectedCountry = selection.kind == ServerSelectionMode.COUNTRY && selection.countryCode == country.countryCode ||
                selection.kind == ServerSelectionMode.SERVER && country.servers.any { it.id == selection.nodeId }
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(Modifier.fillMaxWidth().height(48.dp)
                    .pcCard(if (selectedCountry) accent.copy(alpha = .1f) else DesktopUiKit.Surface, DesktopUiKit.MenuShape)
                    .border(.5.dp, if (selectedCountry) accent.copy(alpha = .3f) else Color.White.copy(alpha = .08f), DesktopUiKit.MenuShape)
                    .pcClickable(enabled = country.isOnline && !state.loading, shape = DesktopUiKit.MenuShape) {
                        onSelect(HopSelection(ServerSelectionMode.COUNTRY, countryCode = country.countryCode))
                    },
                    verticalAlignment = Alignment.CenterVertically) {
                    Spacer(Modifier.width(12.dp))
                    CountryFlagMarker(country, 1f, 28f)
                    Spacer(Modifier.width(20.dp))
                    PcText(country.country, Modifier.weight(1f), size = 14, color = if (country.isOnline) DesktopUiKit.Text else DesktopUiKit.Muted, maxLines = 1)
                    PcText("${country.latencyMs ?: "--"} " + tr(state, "мс", "ms"), size = 12, color = DesktopUiKit.Muted, maxLines = 1)
                    Box(Modifier.width(44.dp).fillMaxHeight().clickable(interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null,
                        role = Role.Button, onClick = { expandedCountry = if (expanded) null else country.code })
                        .semantics { stateDescription = tr(state, if (expanded) "Развернуто" else "Свернуто", if (expanded) "Expanded" else "Collapsed") }, contentAlignment = Alignment.Center) {
                        PcIcon("chevron", tr(state, if (expanded) "Свернуть серверы" else "Раскрыть серверы", if (expanded) "Collapse servers" else "Expand servers"),
                            Modifier.size(11.dp, 8.dp).graphicsLayer { rotationZ = if (expanded) 180f else 0f })
                    }
                }
                AnimatedVisibility(expanded) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        for (server in country.servers) {
                            val selected = selection.kind == ServerSelectionMode.SERVER && server.id == selection.nodeId
                            Row(Modifier.fillMaxWidth().heightIn(min = 40.dp).pcCard(if (selected) accent.copy(alpha = .2f) else DesktopUiKit.Surface, DesktopUiKit.MenuShape)
                                .then(if (selected) Modifier.border(.5.dp, accent.copy(alpha = .3f), DesktopUiKit.MenuShape) else Modifier)
                                .pcClickable(enabled = server.isOnline && !state.loading, shape = DesktopUiKit.MenuShape, onClick = { onSelect(HopSelection(ServerSelectionMode.SERVER, nodeId = server.id)) })
                                .padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                PcText(server.name, Modifier.weight(1f), size = 12, color = if (server.isOnline) DesktopUiKit.Text else DesktopUiKit.Muted, maxLines = 1)
                                PcText(if (server.isOnline) "${server.latencyMs ?: "--"} " + tr(state, "мс", "ms") else tr(state, "Не доступен", "Offline"),
                                    size = 12, color = if (selected) accent else DesktopUiKit.Muted)
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun desktopTimestamp(value: String): java.time.Instant = runCatching {
    java.time.OffsetDateTime.parse(value).toInstant()
}.getOrElse {
    java.time.LocalDateTime.parse(value).toInstant(java.time.ZoneOffset.UTC)
}

internal fun formatDesktopDate(value: String, includeTime: Boolean = true, language: AppLanguage = AppLanguage.RU): String = runCatching {
    desktopTimestamp(value).atZone(java.time.ZoneId.systemDefault()).format(java.time.format.DateTimeFormatter.ofPattern(
        if (includeTime) "d MMMM yyyy, HH:mm" else "d MMMM yyyy", if (language == AppLanguage.RU) java.util.Locale.forLanguageTag("ru") else java.util.Locale.ENGLISH))
}.getOrDefault("-")

internal fun inviteRemainingSeconds(expiresAt: String, nowMillis: Long): Long? = runCatching {
    ((desktopTimestamp(expiresAt).toEpochMilli() - nowMillis + 999) / 1000).coerceAtLeast(0)
}.getOrNull()

@Composable private fun ConnectionInvitePage(state: DesktopState, app: DesktopController) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
    val compact = maxWidth < 450.dp
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            val heading: @Composable (Modifier) -> Unit = { modifier ->
                Box(modifier) { PcText(tr(state, "Подключить устройство", "Connect device"), size = 20, color = Color.White, lineHeight = if (compact) null else 28) }
            }
            val deviceLimit: @Composable () -> Unit = {
                val count = state.primaryDeviceCount
                val limit = state.primaryDeviceLimit
                PcText(if (limit != null) tr(state, "Подключено $count из $limit устройств", "$count of $limit devices connected")
                    else tr(state, "Подключено устройств: $count", "Connected devices: $count"), size = 11, color = LocalDesktopStyle.current.accent, lineHeight = if (compact) null else 15)
            }
            if (compact) Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                heading(Modifier.fillMaxWidth())
                deviceLimit()
            } else Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                heading(Modifier.weight(1f))
                deviceLimit()
            }
            PcText(tr(state, "Отсканируйте QR-код в Noki на новом устройстве.", "Scan the QR code in Noki on the new device."), size = 12, color = Color(0xFFB9B9B9), lineHeight = if (compact) null else 17)
        }
        if (state.inviteLoading) Row(Modifier.fillMaxWidth().pcCard().padding(20.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            CircularProgressIndicator(Modifier.size(24.dp), color = LocalDesktopStyle.current.accent, strokeWidth = 2.dp)
            PcText(tr(state, "Создаём приглашение...", "Creating invite..."), size = 14)
        }
        state.inviteError?.let {
            PcText(it, size = 14, color = DesktopUiKit.Error)
            PcButton(tr(state, "Повторить", "Retry"), Modifier.fillMaxWidth(), enabled = !state.inviteLoading, onClick = app::createInvite)
        }
        state.invite?.let { invite ->
            val remaining by produceState(invite.expiresAt?.let { inviteRemainingSeconds(it, System.currentTimeMillis()) }, invite.expiresAt) {
                while (true) {
                    value = invite.expiresAt?.let { inviteRemainingSeconds(it, System.currentTimeMillis()) }
                    if (value == null || value == 0L) break
                    kotlinx.coroutines.delay(1000)
                }
            }
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                val qr: @Composable () -> Unit = {
                    Box(Modifier.width(180.dp), contentAlignment = Alignment.Center) {
                        ConnectionQr(com.noki.vpn.ui.inviteQrPayload(invite.inviteCode), state, Modifier.size(160.dp))
                    }
                }
                val steps: @Composable (Modifier) -> Unit = { modifier ->
                    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        val instructions = listOf(
                            tr(state, "Откройте Noki", "Open Noki") to tr(state, "На устройстве, которое хотите подключить.", "On the device you want to connect."),
                            tr(state, "Выберите «Подключить устройство»", "Choose “Connect device”") to tr(state, "В разделе «Настройки → Устройства».", "In Settings → Devices."),
                            tr(state, "Отсканируйте QR-код", "Scan the QR code") to tr(state, "Подтвердите подключение на новом устройстве.", "Confirm the connection on the new device."),
                        )
                        instructions.forEachIndexed { index, (title, detail) ->
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Box(Modifier.size(22.dp).background(LocalDesktopStyle.current.accent.copy(alpha = .1f), RoundedCornerShape(6.dp)), contentAlignment = Alignment.Center) {
                                    PcText("${index + 1}", size = 11, color = LocalDesktopStyle.current.accent, lineHeight = if (compact) null else 15)
                                }
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    PcText(title, size = 12, color = Color.White, weight = FontWeight.Medium, lineHeight = if (compact) null else 17)
                                    PcText(detail, size = 11, color = Color(0xFFB9B9B9), lineHeight = if (compact) null else 15)
                                }
                            }
                        }
                    }
                }
                val card = Modifier.fillMaxWidth().heightIn(min = 226.dp).pcCard(shape = DesktopUiKit.PanelShape).padding(16.dp)
                if (compact) Column(card, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(20.dp)) {
                    qr()
                    steps(Modifier.fillMaxWidth())
                } else Row(card, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    qr()
                    steps(Modifier.weight(1f))
                }
                remaining?.let { seconds ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        PcIcon("clock", null, Modifier.size(12.dp))
                        PcText(if (seconds == 0L) tr(state, "Срок действия кода истёк", "The code has expired")
                            else tr(state, "Код действителен ", "Code valid for ") + "%02d:%02d".format(seconds / 60, seconds % 60), size = 11, color = Color(0xFFB9B9B9), lineHeight = if (compact) null else 15)
                    }
                }
            }
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(15.dp)) {
                PcText(tr(state, "Нет камеры? Введите этот код в Noki", "No camera? Enter this code in Noki"), size = 12, color = Color.White, lineHeight = if (compact) null else 16)
                val code: @Composable (Modifier) -> Unit = { modifier ->
                    Box(modifier.height(48.dp).pcCard(shape = if (compact) DesktopUiKit.PanelShape else DesktopUiKit.LeadingControlShape), contentAlignment = Alignment.Center) { PcText(invite.inviteCode, size = 22, maxLines = 1, color = Color.White, weight = FontWeight.SemiBold) }
                }
                val copy: @Composable (Modifier) -> Unit = { modifier ->
                    PcButton(tr(state, "Скопировать код", "Copy code"), modifier.height(48.dp), selected = true, glass = true, glassBlurRadius = 4.dp, glassLightIntensity = DesktopUiKit.ControlHighlight, shape = if (compact) DesktopUiKit.PanelShape else DesktopUiKit.TrailingControlShape, enabled = remaining != 0L, textColor = Color.White,
                        onClick = { copyText(invite.inviteCode, app) })
                }
                if (compact) Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    code(Modifier.fillMaxWidth())
                    copy(Modifier.fillMaxWidth())
                } else Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    code(Modifier.weight(1f))
                    copy(Modifier.width(180.dp))
                }
            }
            if (remaining == 0L) PcButton(tr(state, "Получить новый код", "Get a new code"), Modifier.fillMaxWidth(), selected = true, enabled = !state.inviteLoading, onClick = app::createInvite)
        }
        PcButton(tr(state, "Назад", "Back"), Modifier.fillMaxWidth().height(50.dp), shape = DesktopUiKit.PanelShape, textColor = Color.White, onClick = app::clearInvite)
    }
    }
}

@Composable private fun IncyConnectionPage(state: DesktopState, app: DesktopController) {
    val links = state.incyLinks ?: return
    var v2rayn by remember(links) { mutableStateOf(false) }
    val link = if (v2rayn) links.v2raynSubscriptionUrl?.value ?: links.importLink.value else links.importLink.value
    PcPageHeading("incy / v2ray", app::clearIncyLinks)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        PcButton("Incy", Modifier.weight(1f).height(40.dp), selected = !v2rayn, onClick = { v2rayn = false })
        PcButton("v2rayN", Modifier.weight(1f).height(40.dp), selected = v2rayn, enabled = links.v2raynSubscriptionUrl != null, onClick = { v2rayn = true })
    }
    Column(Modifier.fillMaxWidth().pcCard().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        ConnectionQr(link, state, Modifier.size(160.dp))
        PcText(tr(state, "Отсканируйте QR-код в ", "Scan the QR code in ") + if (v2rayn) "v2rayN" else "Incy", size = 13, color = Color(0xFFB9B9B9))
    }
    PcInput(link, {}, tr(state, "Ссылка подключения", "Connection link"), Modifier.fillMaxWidth(), enabled = false)
    PcButton(tr(state, "Копировать ссылку", "Copy link"), Modifier.fillMaxWidth(), selected = true, onClick = { copyText(link, app) })
}

@Composable private fun ConnectionQr(payload: String, state: DesktopState, modifier: Modifier = Modifier.fillMaxWidth().height(210.dp)) {
    val bitmap = remember(payload) { runCatching { com.noki.vpn.ui.qrPayloadBitmap(payload, 512) } }
    bitmap.onSuccess { Image(BitmapPainter(it), tr(state, "QR-код подключения", "Connection QR code"),
        modifier.clip(RoundedCornerShape(14.dp)), contentScale = ContentScale.Fit) }
        .onFailure { PcText(tr(state, "Ссылка слишком длинная для QR-кода. Скопируйте её ниже.", "This link is too long for a QR code. Copy it below."), size = 14, color = DesktopUiKit.Muted) }
}
