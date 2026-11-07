package com.noki.desktop

import com.noki.vpn.data.*
import com.noki.vpn.AppErrorMapper
import com.noki.vpn.*
import com.noki.vpn.vpn.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import org.json.JSONObject
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicLong
import java.security.SecureRandom
import java.util.Base64

internal class WindowsAvatarMatrix {
    val values = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
    fun postTranslate(x: Float, y: Float) { values[2] += x; values[5] += y }
    fun postScale(x: Float, y: Float) { for (i in 0..2) values[i] *= x; for (i in 3..5) values[i] *= y }
    fun postRotate(degrees: Float) {
        val angle = Math.toRadians(degrees.toDouble())
        val c = kotlin.math.cos(angle).toFloat(); val s = kotlin.math.sin(angle).toFloat()
        for (i in 0..2) { val x = values[i]; val y = values[i + 3]; values[i] = c * x - s * y; values[i + 3] = s * x + c * y }
    }
}

internal fun loadAvatarImage(file: Path): org.jetbrains.skia.Image {
    require(Files.isRegularFile(file) && Files.size(file) > 0) { "avatar_open_failed" }
    require(Files.size(file) <= 5_000_000) { "avatar_file_too_large" }
    val bytes = Files.newInputStream(file).use { it.readNBytes(5_000_001) }
    require(bytes.size <= 5_000_000) { "avatar_file_too_large" }
    org.jetbrains.skia.Data.makeFromBytes(bytes).use { data ->
        org.jetbrains.skia.Codec.makeFromData(data).use { codec ->
            require(codec.encodedImageFormat == org.jetbrains.skia.EncodedImageFormat.PNG ||
                codec.encodedImageFormat == org.jetbrains.skia.EncodedImageFormat.JPEG) { "avatar_format_unsupported" }
            require(codec.width > 0 && codec.height > 0 && codec.width.toLong() * codec.height <= 16_777_216) {
                "avatar_dimensions_too_large"
            }
            val sample = AvatarBitmapDecoder.inSampleSize(codec.width, codec.height)
            val width = (codec.width + sample - 1) / sample; val height = (codec.height + sample - 1) / sample
            val decodeSample = if (codec.encodedImageFormat == org.jetbrains.skia.EncodedImageFormat.JPEG) sample.coerceAtMost(8) else 1
            org.jetbrains.skia.Bitmap().use { bitmap ->
                check(bitmap.allocN32Pixels((codec.width + decodeSample - 1) / decodeSample,
                    (codec.height + decodeSample - 1) / decodeSample)) { "avatar_open_failed" }
                codec.readPixels(bitmap)
                bitmap.setImmutable()
                org.jetbrains.skia.Image.makeFromBitmap(bitmap).use { source ->
                    return org.jetbrains.skia.Surface.makeRasterN32Premul(width, height).use { surface ->
                        surface.canvas.drawImageRect(source, org.jetbrains.skia.Rect.makeWH(width.toFloat(), height.toFloat()))
                        surface.makeImageSnapshot()
                    }
                }
            }
        }
    }
}

internal fun avatarFileError(language: AppLanguage, error: Throwable): String = when (error.message) {
    "avatar_file_too_large" -> if (language == AppLanguage.RU) "Размер аватара не должен превышать 5 МБ" else "Avatar must be 5 MB or smaller"
    "avatar_dimensions_too_large" -> if (language == AppLanguage.RU) "Слишком большое разрешение аватара (максимум 16 777 216 пикселей)" else "Avatar resolution exceeds 16,777,216 pixels"
    "avatar_format_unsupported" -> if (language == AppLanguage.RU) "Выберите изображение PNG или JPEG" else "Choose a PNG or JPEG image"
    else -> if (language == AppLanguage.RU) "Не удалось открыть изображение" else "Could not open image"
}

internal fun croppedAvatar(source: org.jetbrains.skia.Image, request: AvatarCropRequest): org.jetbrains.skia.Image =
    org.jetbrains.skia.Surface.makeRasterN32Premul(512, 512).use { surface ->
        val canvas = surface.canvas
        canvas.clear(0xff000000.toInt())
        val scale = 512f / request.cropCircleSizePx
        canvas.scale(scale, scale)
        canvas.translate(-(request.previewWidthPx - request.cropCircleSizePx) / 2f, -(request.previewHeightPx - request.cropCircleSizePx) / 2f)
        val m = request.imageMatrix(source.width, source.height).values
        canvas.concat(org.jetbrains.skia.Matrix33(m[0], m[1], m[2], m[3], m[4], m[5], m[6], m[7], m[8]))
        canvas.drawImage(source, 0f, 0f)
        surface.makeImageSnapshot()
    }

data class DesktopState(
    val reducedMotion: Boolean = false,
    val earthDisabled: Boolean = false,
    val autoRecoveryEnabled: Boolean = true,
    val loading: Boolean = false,
    val websiteLoginPending: Boolean = false,
    val user: BackendUser? = null,
    val subscription: BackendSubscription? = null,
    val locations: List<ServerLocation> = emptyList(),
    val ruForeignNodes: List<RuForeignNode> = emptyList(),
    val selectedServerId: String = "",
    val running: Boolean = false,
    val connecting: Boolean = false,
    val connectionSettings: StoredSettings? = null,
    val verified: Boolean = false,
    val networkRebinding: Boolean = false,
    val nokiNvCarrier: String = "",
    val connectionLabel: String = "",
    val connectedAtMillis: Long? = null,
    val message: String? = null,
    val messageRevision: Long = 0,
    val settings: StoredSettings = StoredSettings(profile = VlessProfile(), filterMode = AppFilterMode.ALL_APPS,
        selectedPackages = emptySet(), userProfile = UserProfile(serverSelectionMode = ServerSelectionMode.AUTO),
        personalizationSettings = PersonalizationSettings(language = AppLanguage.RU), securitySettings = SecuritySettings(biometricEnabled = false),
        advancedSettings = AdvancedSettings(), isAuthenticated = false),
    val plans: List<BackendPlan> = emptyList(),
    val devices: List<BackendDevice> = emptyList(),
    val devicesLoading: Boolean = false,
    val currentDeviceId: String = "",
    val currentDeviceRole: String = "owner",
    val incyDevices: List<BackendIncyDevice> = emptyList(),
    val incyLinks: IncyConnectionLinks? = null,
    val invite: BackendDeviceInvite? = null,
    val inviteOpen: Boolean = false,
    val inviteLoading: Boolean = false,
    val inviteError: String? = null,
    val notifications: List<BackendAppNotification> = emptyList(),
    val hasUnreadNotifications: Boolean = false,
    val checkout: PaymentCheckoutState = PaymentCheckoutState(),
    val accountSecurity: AccountSecurityUiState = AccountSecurityUiState(),
    val dailyStats: List<DailyStats> = emptyList(),
    val avatarPath: String? = null,
    val uploadBytesPerSecond: Long? = null,
    val downloadBytesPerSecond: Long? = null,
    val sessionBytes: Long? = null,
    val networkBlocked: Boolean = false,
    val youtubeMessage: String? = null,
    val routingRules: List<DesktopRoutingRule> = emptyList(),
) {
    val servers: List<VpnServer> get() = locations.flatMap { it.servers }.distinctBy { it.id }
    val displayedSettings: StoredSettings get() = if (running) connectionSettings ?: settings else settings
    val hasEstablishedTunnel: Boolean get() = running && (verified || (networkRebinding || connectedAtMillis != null) && !connecting)
    val latencyMs: Long? get() = if (!running || !verified) null else
        locationsFor(displayedSettings.advancedSettings.ruRelayEnabled).asSequence().flatMap { it.servers.asSequence() }
            .firstOrNull { it.id == selectedServerId }?.latencyMs?.toLong()
    val connectionLocations: List<ServerLocation> get() = locationsFor(settings.advancedSettings.ruRelayEnabled)
    internal fun locationsFor(ruRelay: Boolean): List<ServerLocation> = if (!ruRelay) locations else
        ruForeignNodes.groupBy { it.countryCode }.toSortedMap().map { (code, nodes) ->
            ServerLocation(code, code, java.util.Locale("", code).getDisplayCountry(java.util.Locale(language.tag)), "", "", isOnline = true,
                latencyMs = nodes.mapNotNull { it.latencyMs }.minOrNull(),
                servers = nodes.sortedBy { it.id }.map { VpnServer(it.id, it.name, code, code, "", null, true, latencyMs = it.latencyMs) })
        }
    val language: AppLanguage get() = settings.personalizationSettings.language
    val profile: String get() = settings.advancedSettings.effectiveProtocol.name.lowercase()
    val endpointOptions: List<VpnEndpointOption> get() = settings.endpointOptions
    val invited: Boolean get() = currentDeviceRole.equals("invited", true)
    val primaryDeviceCount: Int get() = devices.count { it.isActive && it.trafficPool == "primary" } +
        incyDevices.count { it.isSlotActive && it.trafficPool == "primary" }
    val primaryDeviceLimit: Int? get() = subscription?.primaryDeviceLimit ?: plans.firstOrNull { it.code == subscription?.planCode }?.deviceLimit
    // Derived input to Android's presentation and workflows; DesktopState is the only mutable owner.
    internal fun androidState(): AppUiState = AppUiState(
        userProfile = settings.userProfile.copy(backendUserId = user?.id.orEmpty(), username = user?.username.orEmpty(),
            email = user?.email.orEmpty(), avatarUri = avatarPath, hasRealEmail = user?.hasRealEmail ?: true,
            hasPassword = user?.hasPassword ?: true, telegramLinked = user?.telegramLinked ?: false,
            selectedPlanCode = PlanCode.fromCode(subscription?.planCode.orEmpty()), selectedPlanCodeRaw = subscription?.planCode ?: "free",
            selectedPlanName = subscription?.planName, selectedPlanTier = subscription?.planTier,
            selectedPlanBadgeColor = subscription?.planBadgeColor, trafficUsedGb = subscription?.trafficUsedGb,
            trafficLimitGb = subscription?.trafficLimitGb, subscriptionExpiresAt = subscription?.expiresAt,
            subscriptionStatus = subscription?.status.orEmpty()),
        isAuthenticated = user != null, personalizationSettings = settings.personalizationSettings, securitySettings = settings.securitySettings,
        advancedSettings = settings.advancedSettings, currentDeviceAccessRole = currentDeviceRole, dailyStats = dailyStats,
        accountSecurityState = accountSecurity, paymentCheckout = checkout,
        plans = plans.map { PlanSummary(it.code, it.tier, it.name, it.deviceLimit, it.trafficLimitGb, "",
            it.priceRub, it.monthlyEquivalentRub, it.badgeColor, it.headline, it.features) },
    )
}

class DesktopController internal constructor(
    private val vault: WindowsVault,
    private val runtime: SingBoxRuntime,
    private val api: BackendApiClient = BackendApiClient(),
    private val openBrowser: (String) -> Unit = ::openDesktopUrl,
    private val networkSnapshot: () -> WindowsNetworkSnapshot = ::windowsNetworkSnapshot,
    private val latencySampler: (WindowsNetworkSnapshot) -> ClientLatencySampler = { network ->
        ClientLatencySampler(tcpConnect = { host, port, timeout ->
            network.interfaceIndex?.let { windowsTcpLatency(host, port, timeout, it) }
        }, icmpPing = { _, _, _ -> null })
    },
    private val monotonicMillis: () -> Long = { System.nanoTime() / 1_000_000L },
    private val dnsPolicySnapshot: () -> WindowsDnsPolicy = ::windowsDnsPolicy,
) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val operation = Mutex()
    @Volatile private var activeAction: Job? = null
    private val authOperation = Mutex()
    private val avatarDownload = Mutex()
    private val notificationOperation = Mutex()
    private val geositeOperation = Mutex()
    @Volatile private var lastNotificationPoll = 0L
    @Volatile private var notificationEndpointAvailable = true
    private val authEpoch = AtomicLong()
    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val mutable = MutableStateFlow(DesktopState(loading = true, settings = vault.loadSettings(), dailyStats = vault.loadStats(),
        reducedMotion = vault.loadReducedMotion(), earthDisabled = vault.loadEarthDisabled(),
        autoRecoveryEnabled = vault.loadAutoRecovery(), routingRules = vault.loadRoutingRules()))
    val state: StateFlow<DesktopState> = mutable
    internal val support = DesktopSupportChat(uiScope, { state.value.language }, ::attempt, ::current,
        load = { owner, before -> forAttempt(owner) { api.supportChat(it, before) } },
        send = { owner, content, id, files -> forAttempt(owner) {
            if (files.isEmpty()) api.sendSupportMessage(it, content, id) else api.sendSupportAttachments(it, content, id, files)
        } },
        stageAttachment = { source -> withContext(Dispatchers.IO) {
            val file = Path.of(source)
            require(Files.isRegularFile(file) && Files.size(file) <= SUPPORT_ATTACHMENT_MAX_BYTES) { "support_attachment_too_large" }
            val target = Files.createTempFile(vault.directory, "support-", ".upload")
            try {
                Files.newInputStream(file).use { input -> Files.newOutputStream(target).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var total = 0L
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        require(total <= SUPPORT_ATTACHMENT_MAX_BYTES) { "support_attachment_too_large" }
                        output.write(buffer, 0, read)
                    }
                } }
                SupportAttachmentDraft(target.toString(), file.fileName.toString().replace(Regex("[\\r\\n\\\"]"), "_").take(180),
                    Files.probeContentType(file) ?: "application/octet-stream", Files.size(target))
            } catch (error: Throwable) { Files.deleteIfExists(target); throw error }
        } },
        downloadAttachment = { owner, attachment ->
            withContext(Dispatchers.IO) {
                val extension = when (attachment.mime) {
                    "image/jpeg" -> ".jpg"; "image/png" -> ".png"; "image/gif" -> ".gif"; "image/webp" -> ".webp"
                    "application/pdf" -> ".pdf"; "text/plain" -> ".txt"; "video/mp4" -> ".mp4"; "audio/mpeg" -> ".mp3"
                    else -> ".download"
                }
                val target = Files.createTempFile(vault.directory, "support-", extension)
                try { forAttempt(owner) { api.downloadSupportAttachment(it, attachment, target.toFile()) }.path }
                catch (error: Throwable) { Files.deleteIfExists(target); throw error }
            }
        },
        openAttachment = { path, mime ->
            require(canOpenSupportAttachment(mime) && !path.endsWith(".download")) { "support_attachment_save_only" }
            WindowsShell.openUrl(Path.of(path).toAbsolutePath().toString())
        },
        exportAttachment = { source, destination -> withContext(Dispatchers.IO) {
            val target = Path.of(destination).toAbsolutePath()
            val temporary = Files.createTempFile(target.parent, ".noki-support-", ".part")
            try {
                Files.copy(Path.of(source), temporary, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                currentCoroutineContext().ensureActive()
                Files.move(temporary, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
            } finally { Files.deleteIfExists(temporary) }
            Unit
        } },
        deleteFile = { path -> runCatching { Files.deleteIfExists(Path.of(path)) }; Unit },
        isFileAvailable = { path -> Files.isRegularFile(Path.of(path)) },
        pickFiles = { withContext(Dispatchers.IO) { listOfNotNull(pickPath(if (state.value.language == AppLanguage.RU) "Прикрепить файл" else "Attach file")?.toString()) } },
        saveFile = { name -> withContext(Dispatchers.IO) {
            WindowsShell.pickPath(windowHandle, if (state.value.language == AppLanguage.RU) "Сохранить вложение" else "Save attachment", null, false, name)?.toString()
        } },
    )
    internal val diagnostics = DesktopDiagnostics(vault) { state.value.settings.advancedSettings }
    private val mutableCoreLogs = MutableStateFlow<List<SingBoxRuntime.CoreLogLine>>(emptyList())
    internal val localCoreLogs: StateFlow<List<SingBoxRuntime.CoreLogLine>> = mutableCoreLogs
    internal suspend fun collectCoreLogs() {
        val active = runtime.restored() ?: return
        runtime.coreLogs(active).collect { lines -> mutableCoreLogs.update { (it + lines).takeLast(500) } }
    }

    internal fun clearCoreLogs() { mutableCoreLogs.value = emptyList() }
    @Volatile private var tokens: BackendAuthTokens? = vault.loadTokens()
    private var registered: BackendDevice? = null
    private var catalogLocations: List<BackendLocation> = emptyList()
    @Volatile private var windowHandle: com.sun.jna.Pointer? = null
    private val accountLocal = vault.loadAccountLocal()
    private var statsCursor = vault.loadStatsCursor()
    private var hourlyStatsRuntime: SingBoxRuntime.Running? = null
    private val statsLock = Any()
    private val accountLocalLock = Any()
    @Volatile private var logUploadJob: Job? = null
    @Volatile private var manualLogUpload = false
    @Volatile private var logUploadNotBefore = 0L
    private var lastLogUploadAttempt = 0L
    @Volatile private var lastAccountPoll = System.currentTimeMillis()
    @Volatile private var lastAccountSuccess = lastAccountPoll
    private var statsDirty = false
    private var lastStatsCheckpoint = System.currentTimeMillis()
    private var lastRuStatusCheck = 0L
    @Volatile private var latencyJob: Job? = null
    @Volatile private var windowVisible = false
    @Volatile private var windowActive = false
    @Volatile private var accountFocusPending = false
    private data class PreparationKey(val settings: StoredSettings, val rules: List<DesktopRoutingRule>,
        val network: String, val token: String, val epoch: Long, val userId: String?,
        val subscription: BackendSubscription?, val device: BackendDevice?)
    private data class DirectPreparation(val settings: StoredSettings, val catalog: List<BackendLocation>,
        val locations: List<ServerLocation>, val multiHop: MultiHopSettings, val nodes: List<VpnServer?>,
        val session: BackendVpnSession, val geosites: ByteArray, val createdAt: Long)
    private val preparationLock = Any()
    private var preparationKey: PreparationKey? = null
    private var preparationJob: Deferred<DirectPreparation>? = null
    private var preparationCompletedAt: Long? = null
    private var accountRefreshJob: Deferred<Boolean>? = null
    @Volatile private var accountRefreshSilent = true
    @Volatile private var devicesLoadJob: Job? = null
    private var avatarJob: Job? = null
    private var websiteLoginJob: Job? = null
    private var avatarIdentity: Triple<Long, String, String>? = null
    private var latencyMeasuredAt = 0L
    private var activeConnection = vault.readEncrypted("connection.dpapi")?.let(::JSONObject)
    private val endpointHealthLock = Any()
    private var endpointHealth = vault.loadCache("endpoint-health.dpapi") { raw ->
        raw?.let { org.json.JSONArray(it) }
        EndpointHealthStore.decode(raw)
    }
    private val watchdogJobs = mutableMapOf<Any, Job>()
    @Volatile private var connectionGeneration = 0L
    private var recoveryJob: Job? = null
    private var connectionJob: Job? = null
    private var youtubeJob: Job? = null
    private val youtubeEpoch = AtomicLong()
    private val youtubeRevokes = Mutex()
    private val ruRevokes = Mutex()
    @Volatile private var youtubeGrantId: String? = null
    @Volatile private var youtubeConfirmed: BackendYoutubeAccess? = null
    private var appliedYoutubeConfig: String? = null
    private var transientRecoveryAttempt = 0
    @Volatile private var lastNetwork = runCatching { networkSnapshot() }.getOrNull()
    private val networkChangeLock = Any()
    @Volatile private var networkRebindJob: Job? = null
    private val recoveryTimes = ArrayDeque<Long>().apply {
        activeConnection?.optJSONArray("recoveries")?.let { times ->
            for (i in 0 until times.length()) times.optLong(i).takeIf { System.currentTimeMillis() - it in 0..ConnectedWatchdogPolicy.RECOVERY_WINDOW_MS }?.let(::addLast)
        }
    }
    private val watchdog = VpnWatchdogController(
        scheduler = object : DelayedTaskScheduler {
            override fun schedule(owner: Any, delayMillis: Long, task: () -> Unit) {
                synchronized(watchdogJobs) {
                    watchdogJobs.remove(owner)?.cancel()
                    watchdogJobs[owner] = scope.launch { delay(delayMillis); task() }
                }
            }
            override fun cancel(owner: Any) { synchronized(watchdogJobs) { watchdogJobs.remove(owner)?.cancel() } }
        },
        nowMillis = System::currentTimeMillis,
        launchProbe = ::launchWatchdogProbe,
        isLockdown = { activeSettings().advancedSettings.killSwitchEnabled },
        recoveryTimes = recoveryTimes,
    )
    private fun attempt(): AuthSessionAttempt? = tokens?.let { AuthSessionAttempt(it.accessToken, authEpoch.get()) }
    private fun current(attempt: AuthSessionAttempt) = attempt.epoch == authEpoch.get() && tokens != null
    private fun publishAndroid(value: AppUiState) { mutable.update { it.copy(checkout = value.paymentCheckout, accountSecurity = value.accountSecurityState) } }
    internal val payments = PaymentCheckoutWorkflow(uiScope, { state.value.androidState() }, ::publishAndroid, ::attempt, ::current,
        api::paymentConfig,
        { owner, plan, method, promo -> forAttempt(owner) { api.createPayment(it, plan, method, vault.deviceId, vault.deviceKey, promo) } },
        { owner -> forAttempt(owner) { api.payments(it, vault.deviceId, vault.deviceKey) } },
        { synchronized(accountLocalLock) { accountLocal.optString("payment_id").ifBlank { null } } },
        { id -> synchronized(accountLocalLock) { accountLocal.put("payment_id", id); vault.saveAccountLocal(accountLocal) } },
        { refresh() },
        { owner, code, plan, redeem -> forAttempt(owner) { api.promo(it, code, plan, redeem, vault.deviceId, vault.deviceKey) } },
    )
    internal val credentials = AccountSecurityUiWorkflow(uiScope, { state.value.androidState() }, ::publishAndroid,
        { state.value.invited }, ::attempt,
        { owner, email -> forAttempt(owner) { api.sendAccountEmailCode(it, email, vault.deviceId, vault.deviceKey, null, null) } },
        { owner, email, code -> forAttempt(owner) { api.changeAccountEmail(it, email, code, vault.deviceId, vault.deviceKey, null, null) }; Unit },
        { owner, email, currentEmail, code -> forAttempt(owner) { api.sendAccountEmailCode(it, email, vault.deviceId, vault.deviceKey, currentEmail, code) } },
        { owner, email, code, currentEmail, currentCode -> forAttempt(owner) { api.changeAccountEmail(it, email, code, vault.deviceId, vault.deviceKey, currentEmail, currentCode) } },
        { owner, password -> forAttempt(owner) { token ->
            api.changeAccountPassword(token, null, password, vault.deviceId, vault.deviceKey).also { result ->
                if (current(owner)) result.tokens?.let { tokens = it; vault.saveTokens(it) }
            }.user
        } },
        { owner, name -> forAttempt(owner) { api.changeAccountUsername(it, name, vault.deviceId, vault.deviceKey) } },
        { user, _ -> mutable.update { it.copy(user = user, accountSecurity = AccountSecurityUiState()) } },
    )

    init {
        recordLog("app_started", category = "app")
        recordStorageRecovery()
        runCatching {
            Files.newDirectoryStream(vault.directory, "avatar-*.cache*").use { paths ->
                for (path in paths) {
                    val id = path.fileName.toString().removePrefix("avatar-").removeSuffix(".part").removeSuffix(".cache")
                    if (Files.isRegularFile(path, java.nio.file.LinkOption.NOFOLLOW_LINKS) &&
                        runCatching { java.util.UUID.fromString(id).toString() == id }.getOrDefault(false)) runCatching { Files.deleteIfExists(path) }
                }
            }
        }
        scope.launch {
            state.map { it.message to it.messageRevision }.distinctUntilChanged().collectLatest { (message, revision) ->
                if (message != null) {
                    delay(2_000)
                    mutable.update { if (it.messageRevision == revision && it.message == message) it.copy(message = null) else it }
                }
            }
        }
        mutable.update { it.copy(networkBlocked = runtime.guardArmed(), selectedServerId = it.settings.userProfile.selectedNodeId) }
        val restoredAtStartup = runtime.restored()
        restoredAtStartup?.let { active ->
            if (activeConnection?.let { it.optInt("pid") != active.pid || it.optLong("started_at") != active.startedAt } == true) {
                activeConnection = null
                Files.deleteIfExists(vault.directory.resolve("connection.dpapi"))
            }
            mutable.update { old -> old.copy(running = true, connectionLabel = active.label, connectedAtMillis = active.startedAt,
                selectedServerId = active.serverId ?: old.selectedServerId,
                connectionSettings = activeConnection?.optString("settings")?.takeIf(String::isNotBlank)?.let(vault::decodeSettings)) }
            startWatchdog(active, initialEvidence = false)
        }
        action {
            if (state.value.settings.advancedSettings.launchAtStartup) runCatching { WindowsShell.setLaunchAtStartup(true) }
                .onFailure { recordLog("startup_registration_failed", error = true, cause = it, category = "app") }
            if (tokens != null) syncAccount()
            if (runtime.restored() != null && tokens != null) checkRuAuthorization()
            if (runtime.restored() != null && activeSettings().advancedSettings.youtubeAdBlockEnabled) {
                replaceYoutubeConnection(null)
                startYoutubeAccess()
            }
            val settings = state.value.settings
            if (settings.advancedSettings.launchAtStartup && settings.advancedSettings.autoConnectOnLaunch &&
                tokens != null && runtime.restored() == null && activeConnection == null) {
                connectNow(automaticLaunchSettings(settings))
            }
        }
        scope.launch {
            state.first { !it.loading }
            operation.withLock {
                if (restoredAtStartup != null && runtime.restored() == restoredAtStartup) {
                    if (activeConnection?.optInt("version")?.let { it != 3 } == true) queueRecovery(0)
                    else watchdog.forceProbe()
                }
                else if (runtime.restored() == null && tokens != null && activeConnection != null) {
                    transientRecoveryAttempt = activeConnection?.optInt("transient_attempt") ?: 0
                    queueRecovery(((activeConnection?.optLong("retry_at") ?: 0) - System.currentTimeMillis()).coerceAtLeast(0))
                }
            }
        }
        scope.launch {
            windowsNetworkSnapshots(networkSnapshot).collect { network ->
                try { physicalNetworkChanged(network) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) { recordDiagnosticFailure("network_observation_failed", error, "vpn") }
            }
        }
        scope.launch {
            var dnsCheckedAt = 0L
            var lastRuLeaseFailure: SingBoxRuntime.Running? = null
            while (isActive) {
                delay(2000)
                try {
                    pollAccountData()
                    if (!state.value.loading) {
                        val active = runtime.restored()
                        if (mutable.value.running && active == null) {
                            clearConnectionState()
                            reportMessage("VPN-ядро завершилось. Проверяем и восстанавливаем соединение")
                            watchdog.forceProbe()
                        }
                        if (!state.value.loading) active?.let { runCatching { recordMetrics(it, runtime.metrics(it)) } }
                    }
                    if (System.currentTimeMillis() - dnsCheckedAt >= 30_000) {
                        dnsCheckedAt = System.currentTimeMillis()
                        if (!state.value.loading && !state.value.networkRebinding && lastNetwork?.available == true) {
                            val connection = activeConnection
                            val dnsChanged = connection != null && runCatching {
                                !SingBoxConfig.dnsSettingsMatch(connection.getJSONObject("config"), dnsPolicySnapshot)
                            }.onFailure { recordLog("system_dns_read_failed", error = true, cause = it) }.getOrDefault(false)
                            if (dnsChanged && operation.tryLock()) try {
                                if (activeConnection === connection && !state.value.loading && !state.value.networkRebinding) {
                                    watchdog.currentOwner()?.let(watchdog::discardProbe)
                                    recoveryJob?.cancel(); queueRecovery(0)
                                }
                            } finally { operation.unlock() }
                        }
                    }
                    if (windowVisible && !state.value.loading && latencyJob?.isActive != true &&
                        System.currentTimeMillis() - latencyMeasuredAt >= 60_000) refreshClientLatencies()
                    if (!state.value.loading && state.value.user != null && notificationEndpointAvailable && !notificationOperation.isLocked &&
                        System.currentTimeMillis() - lastNotificationPoll >= 60_000) loadNotifications(silent = true)
                    if (!state.value.loading && state.value.user != null &&
                        AppDiagnosticLogPolicy.shouldUploadAutomatically(state.value.settings.advancedSettings) &&
                        diagnostics.hasPending() && claimAutomaticLogUpload(System.currentTimeMillis())) {
                        uploadLogs(manual = false)
                    }
                    if (!state.value.loading &&
                        System.currentTimeMillis() - lastRuStatusCheck >= 5 * 60_000L && operation.tryLock()) {
                        try {
                            lastRuStatusCheck = System.currentTimeMillis()
                            if (runtime.restored() == null) revokeRu()
                            else if (tokens != null) checkRuAuthorization()
                            revokeYoutubePending()
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (error: Exception) {
                            reportMessage("Не удалось проверить или отозвать RU-сессию")
                            recordDiagnosticFailure("ru_status_check_failed", error, "vpn")
                        } finally { operation.unlock() }
                    }
                    if (!state.value.loading && operation.tryLock()) {
                        var active: SingBoxRuntime.Running? = null
                        try {
                            active = if (state.value.running && state.value.displayedSettings.advancedSettings.ruRelayEnabled)
                                runtime.restored() else null
                            if (active == null) {
                                lastRuLeaseFailure = null
                                continue
                            }
                            val journal: Result<JSONObject?> = try {
                                Result.success(vault.readEncrypted("ru-revoke.dpapi")?.let(::JSONObject))
                            } catch (cancelled: CancellationException) { throw cancelled }
                            catch (error: Exception) { Result.failure(error) }
                            val lease = journal.getOrNull()?.optLong("lease_expires_at", 0) ?: 0
                            if (journal.isFailure || lease > 0 && lease <= System.currentTimeMillis()) {
                                stopRuntime(restoreInternet = false)
                                clearConnectionState()
                                if (journal.isSuccess) revokeRu()
                                reportMessage(if (journal.isFailure) "Не удалось прочитать данные RU-сессии. Подключитесь снова"
                                    else "Срок RU-сессии истёк. Подключитесь снова")
                                journal.exceptionOrNull()?.let { recordDiagnosticFailure("ru_journal_read_failed", it, "vpn") }
                            } else lastRuLeaseFailure = null
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (error: Exception) {
                            if (lastRuLeaseFailure != active) {
                                lastRuLeaseFailure = active
                                reportMessage("Не удалось завершить проверку RU-сессии")
                                recordDiagnosticFailure("ru_lease_check_failed", error, "vpn")
                            }
                        } finally { operation.unlock() }
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) { recordDiagnosticFailure("maintenance_failed", error, "app") }
            }
        }
    }

    fun selectServer(id: String) = selectLocation(ServerSelectionMode.SERVER, id)
    fun selectLocation(mode: ServerSelectionMode, id: String = "") {
      if (state.value.loading || connectionJob?.isActive == true) return
      connectionJob = action {
        val server = state.value.connectionLocations.flatMap { it.servers }.firstOrNull { it.id == id }
        val country = if (mode == ServerSelectionMode.COUNTRY) state.value.connectionLocations.first { it.code == id }.countryCode else server?.countryCode.orEmpty()
        if (mode == ServerSelectionMode.SERVER) require(server?.isOnline == true) { "Сервер недоступен" }
        val reconnect = state.value.running
        if (reconnect) stopRuntime(restoreInternet = false)
        val previous = state.value.settings
        invalidatePreparation()
        val changed = previous.copy(endpointOptions = emptyList(), userProfile = previous.userProfile.copy(
            serverSelectionMode = mode, selectedCountryCode = country, selectedNodeId = if (mode == ServerSelectionMode.SERVER) id else "",
            selectedServerCode = if (mode == ServerSelectionMode.COUNTRY) id else server?.locationCode.orEmpty()))
        vault.saveSettings(changed)
        mutable.update { it.copy(settings = changed,
            selectedServerId = if (mode == ServerSelectionMode.SERVER) id else "", running = false, verified = false, connectionSettings = null) }
        if (reconnect) connectNow()
        else prepareConnectionInAdvance()
      }
    }
    fun refresh(): Deferred<Boolean> = refresh(silent = false)

    internal fun pollAccountData(now: Long = System.currentTimeMillis(), onFocus: Boolean = false): Job? {
        if (onFocus) accountFocusPending = true
        if (accountFocusPending && now - lastAccountSuccess in 0 until 60_000L) accountFocusPending = false
        val interval = if (accountFocusPending) 60_000L else if (windowVisible) 5 * 60_000L else 15 * 60_000L
        if (state.value.user == null || state.value.loading || state.value.connecting || operation.isLocked ||
            !accountFocusPending && now - lastAccountPoll in 0 until interval) return null
        accountFocusPending = false
        lastAccountPoll = now
        return refresh(silent = true)
    }

    @Synchronized private fun refresh(silent: Boolean): Deferred<Boolean> {
        accountRefreshJob?.takeIf { it.isActive }?.let {
            if (!silent) accountRefreshSilent = false
            return it
        }
        accountRefreshSilent = silent
        return scope.async {
            val owner = attempt() ?: return@async false
            try {
                val bootstrap = forAttempt(owner) { api.bootstrap(it, vault.deviceId.ifBlank { null }, vault.deviceKey) }
                val foreign = if (state.value.settings.advancedSettings.ruRelayEnabled || state.value.connectionSettings?.advancedSettings?.ruRelayEnabled == true)
                    forAttempt(owner) { api.ruRelayCatalog(it, vault.deviceId, vault.deviceKey) }.foreignNodes.map { it.copy(latencyMs = null) } else emptyList()
                operation.withLock {
                    if (!current(owner)) return@withLock false
                    applyAccount(bootstrap, foreign, refreshRelated = !accountRefreshSilent)
                    true
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                if (!accountRefreshSilent || error is BackendException && error.statusCode == 401) accountReadFailed(owner, error)
                else if (current(owner)) recordDiagnosticFailure("account_sync_failed", error, "account")
                false
            }
        }.also { accountRefreshJob = it }
    }

    fun selectEndpoint(option: VpnEndpointOption?) = updateSettings { settings ->
        require(option == null || option in EndpointGroupPolicy.protocolOptions(state.value.endpointOptions)) { "Выберите доступный протокол" }
        settings.copy(advancedSettings = settings.advancedSettings.copy(protocol = VpnProtocol.AUTO,
            endpointSelectionMode = if (option == null) EndpointSelectionMode.AUTO else EndpointSelectionMode.MANUAL,
            manualEndpointCode = option?.code.orEmpty(), manualEndpointGroupKey = option?.let(EndpointGroupPolicy::groupKey).orEmpty()))
    }

    fun setGamingMode(enabled: Boolean) = updateSettings { settings ->
        settings.copy(advancedSettings = settings.advancedSettings.copy(gamingModeEnabled = enabled,
            ruRelayEnabled = if (enabled) false else settings.advancedSettings.ruRelayEnabled))
    }

    fun setReducedMotion(enabled: Boolean) = action {
        vault.saveReducedMotion(enabled)
        mutable.update { it.copy(reducedMotion = enabled) }
    }

    fun setEarthDisabled(enabled: Boolean) = action {
        vault.saveEarthDisabled(enabled)
        mutable.update { it.copy(earthDisabled = enabled) }
    }

    fun setAutoRecovery(enabled: Boolean) = action {
        vault.saveAutoRecovery(enabled)
        mutable.update { it.copy(autoRecoveryEnabled = enabled) }
        stopWatchdog()
        if (enabled) runtime.restored()?.let { startWatchdog(it, state.value.verified) }
    }

    fun setLoggingEnabled(enabled: Boolean) = updateSettings {
        val changed = AdvancedSettingsStateReducer.setAllLogging(state.value.androidState(), enabled).advancedSettings
        it.copy(advancedSettings = if (enabled) changed else changed.copy(anonymousLogsEnabled = false))
    }

    fun setAutomaticLogSending(enabled: Boolean) = updateSettings {
        val logging = it.advancedSettings.connectionLogsEnabled || it.advancedSettings.errorLogsEnabled
        it.copy(advancedSettings = AdvancedSettingsStateReducer.setAnonymousLogs(state.value.androidState(), enabled && logging).advancedSettings)
    }

    fun updateSettings(transform: (StoredSettings) -> StoredSettings) = action {
        val previous = state.value.settings
        val previousLanguage = state.value.language
        val previousRuRelay = state.value.settings.advancedSettings.ruRelayEnabled
        val previousYoutube = previous.advancedSettings.youtubeAdBlockEnabled
        val changed = transform(state.value.settings)
        val advanced = changed.advancedSettings.let {
            if (it.ruRelayEnabled && !previousRuRelay) it.copy(gamingModeEnabled = false) else it
        }
        val normalized = changed.copy(advancedSettings = DomainRulePolicy.normalizeSettings(advanced))
        if (preparationSettings(normalized) != preparationSettings(previous)) invalidatePreparation()
        validateMultiHop(normalized.advancedSettings.multiHop)
        if (normalized.advancedSettings.launchAtStartup != previous.advancedSettings.launchAtStartup)
            WindowsShell.setLaunchAtStartup(normalized.advancedSettings.launchAtStartup)
        vault.saveSettings(normalized)
        if (normalized.advancedSettings.connectionLogsEnabled != previous.advancedSettings.connectionLogsEnabled ||
            normalized.advancedSettings.errorLogsEnabled != previous.advancedSettings.errorLogsEnabled)
            diagnostics.applySettings(normalized.advancedSettings)
        val locations = if (normalized.personalizationSettings.language != previousLanguage && catalogLocations.isNotEmpty()) {
            val latencies = state.value.servers.mapNotNull { server ->
                server.latencyMs?.let { latency -> ServerLocationMapper.latencyTargetKey(server)?.let { it to latency } }
            }.toMap()
            ServerLocationMapper.mapLocations(catalogLocations, normalized.personalizationSettings.language, latencies)
        } else state.value.locations
        mutable.update { it.copy(settings = normalized, locations = locations,
            message = if (it.running && preparationSettings(normalized.copy(advancedSettings =
                    normalized.advancedSettings.copy(youtubeAdBlockEnabled = previousYoutube))) != preparationSettings(previous))
                tr(it, "Изменения применятся при следующем подключении", "Changes will apply on the next connection") else null,
            messageRevision = it.messageRevision + 1) }
        if (normalized.advancedSettings.youtubeAdBlockEnabled != previousYoutube && runtime.restored() != null) {
            stopYoutubeAccess(waitForCleanup = false)
            val active = activeSettings().let { it.copy(advancedSettings = it.advancedSettings.copy(
                youtubeAdBlockEnabled = normalized.advancedSettings.youtubeAdBlockEnabled)) }
            activeConnection?.put("settings", vault.encodeSettings(active))
            mutable.update { it.copy(connectionSettings = active) }
            persistConnection()
            replaceYoutubeConnection(null)
            if (active.advancedSettings.youtubeAdBlockEnabled) startYoutubeAccess()
        }
        if (normalized.advancedSettings.ruRelayEnabled != previousRuRelay) refresh()
        prepareConnectionInAdvance()
    }

    fun openAccountWebsite(section: String) {
        val url = when (section) {
            "plans" -> "https://noki.ykino.tech/plans"
            "payments", "username", "password", "email", "security" -> "https://noki.ykino.tech/account#$section"
            else -> return
        }
        scope.launch {
            runCatching { openBrowser(url) }.onFailure {
                reportMessage(tr(state.value, "Не удалось открыть браузер", "Could not open browser"))
            }
        }
    }

    fun editCredential(kind: String) {
        if (state.value.invited) { denyOwner(); return }
        credentials.invalidate()
        if (kind != "username") { openAccountWebsite(kind); return }
        mutable.update { it.copy(accountSecurity = AccountSecurityStateReducer.username(it.accountSecurity, it.user?.username.orEmpty())) }
    }

    fun dismissCredential() { credentials.invalidate(); mutable.update { it.copy(accountSecurity = AccountSecurityUiState()) } }
    fun reportMessage(message: String) { mutable.update { it.copy(message = message, messageRevision = it.messageRevision + 1) } }
    fun clearMessage() { mutable.update { it.copy(message = null, messageRevision = it.messageRevision + 1) } }
    fun saveRoutingRule(rule: DesktopRoutingRule) = action {
        invalidatePreparation()
        val previous = state.value.routingRules.firstOrNull { it.id == rule.id }
        val normalized = WindowsRuleSets.resolve(vault, runtime, rule.copy(ruleSets = previous?.ruleSets.orEmpty()))
        val rows = state.value.routingRules.let { old ->
            if (old.any { it.id == rule.id }) old.map { if (it.id == rule.id) normalized else it } else old + normalized }
        vault.saveRoutingRules(rows)
        mutable.update { it.copy(routingRules = rows,
            message = if (it.running) "Изменения применятся при следующем подключении" else null, messageRevision = it.messageRevision + 1) }
    }
    fun deleteRoutingRule(id: String) = action {
        require(id != DesktopRoutingRule.RUSSIAN_RESOURCES.id) { "Стандартное правило можно отключить" }
        invalidatePreparation()
        val rows = state.value.routingRules.filterNot { it.id == id }
        vault.saveRoutingRules(rows)
        mutable.update { it.copy(routingRules = rows,
            message = if (it.running) "Изменения применятся при следующем подключении" else null, messageRevision = it.messageRevision + 1) }
    }
    fun pickPath(title: String, extensions: String? = null, folder: Boolean = false): Path? = WindowsShell.pickPath(windowHandle, title, extensions, folder)
    fun copyText(value: String) = WindowsShell.copyText(windowHandle, value)
    @Synchronized fun setWindowVisible(visible: Boolean) {
        windowVisible = visible
        if (visible) scope.launch { refreshClientLatencies(); prepareConnectionInAdvance() }
        else latencyJob?.cancel()
    }
    @Synchronized fun setWindowActive(active: Boolean) {
        val returned = active && !windowActive
        windowActive = active
        if (returned) scope.launch { pollAccountData(onFocus = true) }
    }
    fun attachWindow(handle: Long?) {
        windowHandle = handle?.takeIf { it != 0L }?.let { com.sun.jna.Pointer(it) }
    }
    internal fun loadWindowBounds(): JSONObject? = vault.loadCache("window.dpapi") { it?.let(::JSONObject) }.also { recordStorageRecovery() }
    internal fun saveWindowBounds(value: JSONObject) = vault.writeEncrypted("window.dpapi", value.toString())
    private fun recordStorageRecovery() {
        vault.takeRecoveredFiles().forEach { (name, error) ->
            recordLog("local_storage_recovered:$name", error = true, cause = error, category = "app")
        }
    }
    private fun denyOwner() = reportMessage("Это действие доступно только владельцу аккаунта")
    private fun ownerAction(block: suspend () -> Unit) { if (state.value.invited) denyOwner() else action {
        check(!state.value.invited) { "owner_device_required" }
        devicesLoadJob?.cancelAndJoin()
        accountRefreshJob?.cancelAndJoin()
        invalidatePreparation()
        block()
    } }
    @Synchronized fun loadDevices(): Job? {
        if (state.value.invited) { denyOwner(); return null }
        devicesLoadJob?.takeIf { it.isActive }?.let { return it }
        val owner = attempt() ?: return null
        val job = scope.launch(start = CoroutineStart.LAZY) {
            val ownJob = currentCoroutineContext()[Job]
            try {
                operation.withLock {
                    if (!current(owner) || state.value.invited) return@launch
                    mutable.update { it.copy(devicesLoading = true) }
                }
                if (!refresh().await() || !current(owner) || state.value.invited) return@launch
                val devices = forAttempt(owner) { api.listIncyDevices(it) }
                operation.withLock {
                    if (current(owner) && !state.value.invited) mutable.update { it.copy(incyDevices = devices) }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { accountReadFailed(owner, error) }
            finally {
                synchronized(this@DesktopController) {
                    if (devicesLoadJob === ownJob) {
                        devicesLoadJob = null
                        if (current(owner)) mutable.update { it.copy(devicesLoading = false) }
                    }
                }
            }
        }
        devicesLoadJob = job
        job.start()
        return job
    }
    fun renameDevice(id: String, name: String) = ownerAction {
        require(name.trim().length in 1..120)
        val rows = authorized { api.renameDevice(it, id, name.trim(), vault.deviceId, vault.deviceKey) }
        mutable.update { it.copy(devices = rows) }
    }
    fun deleteDevice(id: String) = ownerAction {
        if (id == vault.deviceId) {
            stopRuntime(); authorized { api.deleteCurrentDevice(it, vault.deviceId, vault.deviceKey) }; clearAccount()
        } else {
            val rows = authorized { api.deleteDevice(it, id, vault.deviceId, vault.deviceKey) }
            mutable.update { it.copy(devices = rows) }
        }
    }
    fun setDeviceAccess(id: String, full: Boolean) = ownerAction {
        val rows = authorized { api.setDeviceFullAccess(it, id, full, vault.deviceId, vault.deviceKey) }
        mutable.update { it.copy(devices = rows) }
    }
    fun setDeviceTrafficPool(id: String, trafficPool: String) = ownerAction {
        require(trafficPool in setOf("primary", "shared"))
        val rows = try { authorized { api.setDeviceTrafficPool(it, id, trafficPool, vault.deviceId, vault.deviceKey) } }
        catch (error: Exception) {
            if (id == vault.deviceId && (error !is BackendException || error.statusCode >= 500 || error.statusCode == 408)) {
                // A lost response does not prove that the server kept the old account alive.
                withContext(NonCancellable) { runCatching { invalidateDeviceConnection() }.onFailure(error::addSuppressed) }
            }
            if (error is BackendException && error.statusCode == 409)
                throw IllegalStateException(tr(state.value, "В основных устройствах нет свободного места", "No primary device slots are available"))
            throw error
        }
        mutable.update { it.copy(devices = rows) }
        if (id == vault.deviceId) {
            val reconnect = invalidateDeviceConnection()
            syncAccount()
            if (reconnect) {
                connectNow(protectUntilVerified = true)
                if (!state.value.settings.advancedSettings.killSwitchEnabled) runtime.releaseGuard(allowConnected = true)
            }
        } else syncAccount()
    }
    private suspend fun invalidateDeviceConnection(): Boolean = withContext(NonCancellable) {
        val reconnect = runtime.restored() != null
        stopWatchdog()
        activeConnection = null
        val settings = state.value.settings.copy(profile = VlessProfile(), endpointOptions = emptyList())
        mutable.update { it.copy(settings = settings, connectionSettings = null, verified = false) }
        var failure: Throwable? = null
        fun failed(error: Throwable) { if (failure == null) failure = error else checkNotNull(failure).addSuppressed(error) }
        val protected = reconnect && runCatching { runtime.protectConnection() }.onFailure(::failed).isSuccess
        runCatching { stopYoutubeAccess() }.onFailure(::failed)
        if (protected) {
            runCatching { stopRuntime(restoreInternet = false, retainConnection = true) }.onFailure(::failed)
            if (runtime.restored() == null) clearConnectionState()
        }
        // Storage failure must not skip network protection or restore the in-memory recovery intent.
        runCatching { Files.deleteIfExists(vault.directory.resolve("connection.dpapi")) }.onFailure(::failed)
        runCatching { vault.saveSettings(settings) }.onFailure(::failed)
        failure?.let { throw it }
        reconnect
    }
    fun logoutOthers() = ownerAction {
        val rows = authorized { api.clearOtherDevices(it, vault.deviceId, vault.deviceKey) }
        mutable.update { it.copy(devices = rows) }
    }
    fun createInvite() {
        if (state.value.invited) { denyOwner(); return }
        mutable.update { it.copy(inviteOpen = true, inviteError = null) }
        if (state.value.inviteLoading) return
        val expiresAt = state.value.invite?.expiresAt?.let { value -> runCatching {
            runCatching { java.time.OffsetDateTime.parse(value).toInstant() }
                .getOrElse { java.time.LocalDateTime.parse(value).toInstant(java.time.ZoneOffset.UTC) }
        }.getOrNull() }
        if (expiresAt?.isAfter(java.time.Instant.now()) == true) return
        mutable.update { it.copy(invite = null, inviteLoading = true) }
        action {
            try {
                check(!state.value.invited) { "owner_device_required" }
                val invite = authorized { api.createDeviceInvite(it, vault.deviceId, vault.deviceKey) }
                mutable.update { it.copy(invite = invite) }
                syncAccount()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                mutable.update { it.copy(inviteError = if (it.inviteOpen) AppErrorMapper.readableNetworkError(it.language, error) else null) }
                throw error
            } finally { mutable.update { it.copy(inviteLoading = false) } }
        }
    }
    fun clearInvite() { mutable.update { it.copy(inviteOpen = false, inviteError = null) } }
    fun createIncy(name: String) = ownerAction {
        require(name.trim().length in 1..120)
        val created = authorized { api.createIncyDevice(it, name.trim()) }
        mutable.update { it.copy(incyDevices = it.incyDevices + created.device,
            incyLinks = IncyConnectionLinks(IncyImportLink.parse(created.importLink), created.v2raynSubscriptionUrl?.let(V2raynSubscriptionUrl::parse))) }
        syncAccount()
    }
    fun incyLinks(id: String, reissue: Boolean = false) = ownerAction {
        val links = authorized { if (reissue) api.reissueIncyDevice(it, id) else api.getIncyImportLink(it, id) }
        mutable.update { it.copy(incyLinks = links) }
    }
    fun clearIncyLinks() { mutable.update { it.copy(incyLinks = null) } }
    fun renameIncy(id: String, name: String) = ownerAction {
        require(name.trim().length in 1..120)
        val row = authorized { api.renameIncyDevice(it, id, name.trim()) }
        mutable.update { it.copy(incyDevices = it.incyDevices.map { old -> if (old.id == id) row else old }) }
    }
    fun setIncyTrafficPool(id: String, trafficPool: String) = ownerAction {
        require(trafficPool in setOf("primary", "shared"))
        val row = try { authorized { api.setIncyDeviceTrafficPool(it, id, trafficPool) } }
        catch (error: BackendException) {
            if (error.statusCode == 409) throw IllegalStateException(tr(state.value, "В основных устройствах нет свободного места", "No primary device slots are available"))
            throw error
        }
        mutable.update { it.copy(incyDevices = it.incyDevices.map { old -> if (old.id == id) row else old }, incyLinks = null) }
        val reminder = tr(state.value, "Обновите подписку этого устройства в стороннем клиенте", "Refresh this device's subscription in its VPN client")
        try { syncAccount() }
        catch (cancelled: CancellationException) { reportMessage(reminder); throw cancelled }
        catch (error: Exception) {
            throw IllegalStateException("$reminder. ${AppErrorMapper.readableNetworkError(state.value.language, error)}", error)
        }
        reportMessage(reminder)
    }
    fun deleteIncy(id: String) = ownerAction {
        authorized { api.deleteIncyDevice(it, id) }; mutable.update { it.copy(incyDevices = it.incyDevices.filterNot { row -> row.id == id }, incyLinks = null) }
    }
    fun loadNotifications(markRead: Boolean = false, silent: Boolean = false) = scope.launch {
        val owner = attempt() ?: return@launch
        val userId = state.value.user?.id ?: return@launch
        notificationOperation.withLock {
            if (!current(owner)) return@withLock
            lastNotificationPoll = System.currentTimeMillis()
            fun saveHistory(rows: List<BackendAppNotification>) = synchronized(accountLocalLock) {
                if (!current(owner) || state.value.user?.id != userId) return@synchronized false
                val key = "notifications_$userId"
                val hidden = accountLocal.optJSONArray("hidden_$userId")?.let { array -> (0 until array.length()).map { array.getString(it) }.toSet() }.orEmpty()
                val cached = AppNotificationHistoryStore.decodeState(accountLocal.optString(key))
                var history = cached
                for (row in rows.filterNot { it.id in hidden }) history = AppNotificationHistoryStore.mergeState(history, row)
                if (markRead) history = AppNotificationHistoryStore.markRead(history)
                if (history != cached) {
                    val encoded = AppNotificationHistoryStore.encodeState(history)
                    vault.saveAccountLocal(JSONObject(accountLocal.toString()).put(key, encoded))
                    accountLocal.put(key, encoded)
                }
                mutable.update { it.copy(notifications = history.notifications, hasUnreadNotifications = history.hasUnread) }
                true
            }
            try {
                if (!saveHistory(emptyList())) return@withLock
                if (!notificationEndpointAvailable && !markRead) return@withLock
                val rows = forAttempt(owner) { api.appNotifications(it, vault.deviceId, vault.deviceKey) }
                notificationEndpointAvailable = true
                if (saveHistory(rows) && rows.isNotEmpty()) {
                    forAttempt(owner) { api.acknowledgeAppNotifications(it, vault.deviceId, vault.deviceKey, rows.map { row -> row.id }) }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                if (current(owner) && error is BackendException && error.statusCode == 404) {
                    notificationEndpointAvailable = false
                    return@withLock
                }
                if (!silent || error is BackendException && error.statusCode == 401) accountReadFailed(owner, error)
                else if (current(owner)) recordDiagnosticFailure("notifications_sync_failed", error, "account")
            }
        }
    }
    fun hideNotification(id: String) = action {
        val userId = checkNotNull(state.value.user).id
        synchronized(accountLocalLock) {
            val key = "notifications_$userId"
            val history = AppNotificationHistoryStore.remove(StoredAppNotificationHistory(state.value.notifications, state.value.hasUnreadNotifications), id)
            val hiddenKey = "hidden_$userId"
            val hidden = accountLocal.optJSONArray(hiddenKey) ?: org.json.JSONArray()
            hidden.put(id); accountLocal.put(hiddenKey, hidden).put(key, AppNotificationHistoryStore.encodeState(history)); vault.saveAccountLocal(accountLocal)
            mutable.update { it.copy(notifications = history.notifications, hasUnreadNotifications = history.hasUnread) }
        }
    }
    internal fun uploadAvatar(request: AvatarCropRequest) = ownerAction {
        avatarJob?.cancel()
        val bytes = loadAvatarImage(Path.of(request.sourceUri)).use { source ->
            croppedAvatar(source, request).use { output ->
                checkNotNull(output.encodeToData(org.jetbrains.skia.EncodedImageFormat.JPEG, 90)).use { it.bytes }
            }
        }
        val url = authorized { api.uploadAvatar(it, "avatar.jpg", "image/jpeg", bytes) }
        invalidateAvatarCache()
        mutable.update { it.copy(user = it.user?.copy(avatarUrl = url)) }; cacheAvatar(url)
    }
    fun deleteAvatar() = ownerAction {
        avatarJob?.cancel()
        val url = authorized(api::deleteAvatar)
        invalidateAvatarCache()
        mutable.update { it.copy(user = it.user?.copy(avatarUrl = url), avatarPath = null) }
        Files.deleteIfExists(vault.directory.resolve("avatar.cache"))
    }
    fun uploadLogs(): Job = uploadLogs(manual = true)

    @Synchronized private fun uploadLogs(manual: Boolean): Job {
        logUploadJob?.takeIf { it.isActive }?.let {
            if (manual) manualLogUpload = true
            return it
        }
        manualLogUpload = manual
        return scope.launch {
            try {
                if (System.currentTimeMillis() < logUploadNotBefore) {
                    if (manualLogUpload) reportMessage(tr(state.value, "Отправка временно ограничена. Повторите позже", "Uploads are temporarily limited. Try again later"))
                    return@launch
                }
                val sent = sendLogs()
                if (manualLogUpload) reportMessage(if (sent && diagnostics.hasPending()) tr(state.value, "Логи отправлены частично. Остаток сохранён", "Some logs were sent. Remaining entries are saved")
                    else if (sent) tr(state.value, "Логи отправлены", "Logs sent")
                    else tr(state.value, "Новых записей нет", "No new log entries"))
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                (error as? BackendException)?.retryAfterMillis?.takeIf { it > 0 }?.let {
                    logUploadNotBefore = System.currentTimeMillis() + it.coerceAtMost(24 * 60 * 60_000L)
                }
                recordDiagnosticFailure("log_upload_failed", error, "diagnostics")
                if (manualLogUpload) reportMessage(tr(state.value, "Не удалось отправить логи. Повторите позже", "Could not send logs. Try again later"))
            }
        }.also { logUploadJob = it }
    }

    private suspend fun sendLogs(): Boolean {
        val owner = checkNotNull(attempt()) { "Войдите в аккаунт Noki" }
        if (!current(owner) || !manualLogUpload && !AppDiagnosticLogPolicy.shouldUploadAutomatically(state.value.settings.advancedSettings)) return false
        val batch = diagnostics.batch()
        if (batch.isEmpty()) return false
        val snapshot = state.value
        val text = buildString {
            append("Noki Vpn ").append(DesktopDiagnostics.appVersion).append(if (DesktopBuild.IS_LINUX) " | Linux " else " | Windows ").append(System.getProperty("os.version"))
                .append(' ').append(System.getProperty("os.arch")).append('\n')
            append("Core: ").append(SingBoxRuntime.BINARY_VERSION).append('\n')
            append("State at upload: running=").append(snapshot.running).append(" verified=").append(snapshot.verified)
                .append(" profile=").append(snapshot.profile).append(" kill_switch=").append(snapshot.settings.advancedSettings.killSwitchEnabled)
                .append(" ru_relay=").append(snapshot.settings.advancedSettings.ruRelayEnabled).append('\n').append('\n')
            batch.forEach { append(DesktopDiagnostics.render(it)) }
        }
        check(text.length < 200_000)
        forAttempt(owner) { api.uploadAppLogs(it, vault.deviceId, vault.deviceKey, if (DesktopBuild.IS_LINUX) "Linux PC" else "Windows PC", text, platform = com.noki.desktop.DesktopBuild.PLATFORM) }
        logUploadNotBefore = System.currentTimeMillis() + 60_000L
        diagnostics.acknowledge(batch)
        return true
    }

    private fun recordLog(message: String, error: Boolean = false, latency: Long? = null, cause: Throwable? = null, category: String = "vpn") {
        runCatching { diagnostics.record(message, error, cause, latency, category) }
    }

    internal fun recordDiagnosticFailure(event: String, error: Throwable, category: String = "app") {
        runCatching {
            if (error is SingBoxRuntime.StartupFailure) {
                val line = SingBoxRuntime.CoreLogLine("error", DesktopDiagnostics.safeMessage(error.message.orEmpty()))
                mutableCoreLogs.update { if (it.lastOrNull()?.message == line.message) it else (it + line).takeLast(500) }
            }
            if (error is VirtualMachineError) diagnostics.captureFailure(error)
            else diagnostics.record(event, error = true, cause = error, category = category)
        }
    }
    private fun invalidateAvatarCache() = synchronized(accountLocalLock) {
        accountLocal.remove("avatar_cache")
        vault.saveAccountLocal(accountLocal)
    }

    private fun cacheAvatar(url: String?) {
        val userId = state.value.user?.id
        val owner = attempt()
        if (!url.isNullOrBlank() && userId != null && owner != null && !state.value.invited &&
            avatarJob?.isActive == true && avatarIdentity == Triple(owner.epoch, userId, url)) return
        avatarJob?.cancel()
        val path = vault.directory.resolve("avatar.cache")
        val reusable = synchronized(accountLocalLock) {
            val saved = accountLocal.optJSONObject("avatar_cache")
            saved != null && saved.optString("user_id") == userId && saved.optString("url") == url
        }
        if (!url.isNullOrBlank() && !state.value.invited && reusable &&
            runCatching { Files.isRegularFile(path) && Files.size(path) in 1..5_000_000 }.getOrDefault(false)) {
            mutable.update { it.copy(avatarPath = path.toString()) }
            return
        }
        mutable.update { it.copy(avatarPath = null) }
        if (url.isNullOrBlank() || userId == null || owner == null || state.value.invited) return
        avatarIdentity = Triple(owner.epoch, userId, url)
        avatarJob = scope.launch {
            val downloaded = vault.directory.resolve("avatar-${java.util.UUID.randomUUID()}.cache")
            try {
                // shortcut: cancelled transfers finish under the API timeout; cancel immediately when transport owns file cleanup.
                avatarDownload.withLock {
                    ensureActive()
                    withContext(NonCancellable) { api.downloadProfileAvatar(owner.accessToken, url, downloaded.toFile()) }
                }
                operation.withLock {
                    ensureActive()
                    if (current(owner) && state.value.user?.id == userId && state.value.user?.avatarUrl == url && !state.value.invited) {
                        synchronized(accountLocalLock) {
                            invalidateAvatarCache()
                            Files.move(downloaded, path, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
                            accountLocal.put("avatar_cache", JSONObject().put("user_id", userId).put("url", url))
                            vault.saveAccountLocal(accountLocal)
                        }
                        mutable.update { it.copy(avatarPath = path.toString()) }
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { }
            finally { runCatching { Files.deleteIfExists(downloaded) } }
        }
    }

    private suspend fun <T> forAttempt(owner: AuthSessionAttempt, block: suspend (String) -> T): T {
        check(current(owner)) { "auth_required" }
        return authorized { token ->
            if (!current(owner)) throw CancellationException("Account changed")
            block(token)
        }
    }

    private suspend fun <T> withDeviceProof(deviceId: String, block: suspend (String, String, String) -> T): T = authorized { token ->
        vault.withChallengeTransaction {
            val challenge = api.createDeviceChallenge(token, deviceId)
            block(token, challenge.nonce, vault.signChallenge(challenge.nonce))
        }
    }

    private suspend fun accountReadFailed(owner: AuthSessionAttempt, error: Exception) {
        if (!current(owner)) return
        recordDiagnosticFailure("account_sync_failed", error, "account")
        val message = AppErrorMapper.readableNetworkError(state.value.language, error)
        if (error is BackendException && error.statusCode == 401) operation.withLock {
            if (!current(owner)) return@withLock
            runCatching { stopRuntime(restoreInternet = false); revokeRu() }
            clearAccount()
            mutable.update { it.copy(loading = false, running = runtime.restored() != null,
                message = message, messageRevision = it.messageRevision + 1) }
        } else reportMessage(message)
    }

    fun loginFromWebsite() {
        if (state.value.loading || websiteLoginJob?.isActive == true) return
        if (tokens != null) { refresh(); return }
        action(auth = true) {
            mutable.update { it.copy(websiteLoginPending = true) }
            val verifier = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also { SecureRandom().nextBytes(it) })
            val challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(java.security.MessageDigest.getInstance("SHA-256")
                .digest(verifier.toByteArray(Charsets.US_ASCII)))
            val start = api.startDesktopAuth(challenge, System.getenv(if (DesktopBuild.IS_LINUX) "HOSTNAME" else "COMPUTERNAME").orEmpty().ifBlank { if (DesktopBuild.IS_LINUX) "Linux PC" else "Windows PC" }.take(120))
            val requestId = start.getString("request_id")
            require(requestId.matches(Regex("[A-Za-z0-9_-]{43}"))) { "Некорректный ответ сервера" }
            val url = "https://noki.ykino.tech/account?desktop=1#$requestId"
            openBrowser(url)
            val provisional = withTimeout(300_000) {
                var response: JSONObject
                do {
                    delay(1_000)
                    response = api.pollDesktopAuth(requestId, verifier)
                } while (response.optString("status") == "pending")
                check(response.optString("status") == "approved") { "Не удалось подтвердить вход на сайте" }
                BackendAuthTokens(response.getString("access_token"), response.getString("refresh_token"))
            }
            mutable.update { it.copy(websiteLoginPending = false) }
            finishLogin(provisional)
        }
    }

    fun cancelWebsiteLogin() { websiteLoginJob?.cancel() }

    fun loginFromCode(rawCode: String) {
        if (state.value.loading || websiteLoginJob?.isActive == true || tokens != null) return
        val code = InviteCodeFormatter.extract(rawCode)
        if (code.length < 6) {
            reportMessage(if (state.value.language == AppLanguage.RU) "Введите код приглашения" else "Enter invite code")
            return
        }
        action(auth = true) {
            val accepted = api.acceptDeviceInvite(code, vault.registrationDeviceKey, vault.deviceId.ifBlank { null },
                System.getenv(if (DesktopBuild.IS_LINUX) "HOSTNAME" else "COMPUTERNAME").orEmpty().ifBlank { if (DesktopBuild.IS_LINUX) "Linux PC" else "Windows PC" }.take(120), vault.publicKey, vault.deviceClaims, com.noki.desktop.DesktopBuild.PLATFORM)
            finishLogin(accepted.tokens, accepted.device)
        }
    }

    private suspend fun finishLogin(provisional: BackendAuthTokens, acceptedDevice: BackendDevice? = null) {
        var committed = false
        try {
            avatarJob?.cancel()
            authEpoch.incrementAndGet()
            withContext(Dispatchers.Main) { payments.invalidate(); credentials.invalidate() }
            val device = acceptedDevice ?: api.registerDevice(provisional.accessToken, vault.registrationDeviceKey, vault.deviceId.ifBlank { null },
                System.getenv(if (DesktopBuild.IS_LINUX) "HOSTNAME" else "COMPUTERNAME").orEmpty().ifBlank { if (DesktopBuild.IS_LINUX) "Linux PC" else "Windows PC" }.take(120),
                vault.publicKey, vault.deviceClaims, com.noki.desktop.DesktopBuild.PLATFORM)
            registered = device
            vault.acceptRegisteredDevice(device)
            val bound = provisional.refreshToken?.let { renewToken(it, device.id) } ?: provisional
            tokens = bound
            vault.saveTokens(bound)
            committed = true
            syncAccount()
        } catch (error: Exception) {
            if (!committed) withContext(NonCancellable) { provisional.refreshToken?.let { runCatching { api.revokeRefreshToken(it) } } }
            throw error
        }
    }

    fun connect() {
        if (state.value.loading || state.value.running || connectionJob?.isActive == true) return
        stopWatchdog()
        connectionJob = action {
            activeConnection = null
            Files.deleteIfExists(vault.directory.resolve("connection.dpapi"))
            connectNow()
        }
    }
    fun releaseNetworkGuard() = action { runtime.releaseGuard() }
    internal fun automaticLaunchSettings(settings: StoredSettings): StoredSettings = settings.copy(
        endpointOptions = emptyList(),
        userProfile = settings.userProfile.copy(serverSelectionMode = ServerSelectionMode.AUTO,
            selectedCountryCode = "", selectedNodeId = "", selectedServerCode = ""),
        advancedSettings = settings.advancedSettings.copy(protocol = VpnProtocol.AUTO, ruRelayEnabled = false, gamingModeEnabled = false,
            multiHop = settings.advancedSettings.multiHop.copy(enabled = false), endpointSelectionMode = EndpointSelectionMode.AUTO,
            manualEndpointCode = "", manualEndpointGroupKey = ""),
    )
    private fun preparationSettings(settings: StoredSettings): StoredSettings = settings.copy(
        profile = VlessProfile(), personalizationSettings = PersonalizationSettings(),
        userProfile = settings.userProfile.copy(username = "", email = "", avatarUri = null, trafficUsedGb = null),
        advancedSettings = settings.advancedSettings.copy(connectionLogsEnabled = true, errorLogsEnabled = true,
            anonymousLogsEnabled = true, showLocalCoreLogs = false, launchAtStartup = false, autoConnectOnLaunch = false),
    )

    private fun preparationKeyFor(settings: StoredSettings, network: WindowsNetworkSnapshot): PreparationKey? {
        if (settings.advancedSettings.ruRelayEnabled || settings.advancedSettings.multiHop.enabled || !network.available) return null
        val owner = attempt() ?: return null
        // Android's key normalization; PC keeps account admission outside StoredSettings.
        val subscription = state.value.subscription?.let { value -> value.copy(
            trafficUsedGb = value.trafficLimitGb?.takeIf { it > 0 && (value.trafficUsedGb ?: 0.0) >= it },
            sharedTrafficUsedGb = value.sharedTrafficLimitGb?.takeIf { it > 0 && (value.sharedTrafficUsedGb ?: 0.0) >= it },
            trialTrafficRemainingGb = value.trialTrafficRemainingGb?.takeIf { it <= 0.0 },
            planName = null, planBadgeColor = null,
        ) }
        return PreparationKey(preparationSettings(settings), state.value.routingRules.toList(),
            network.signature, owner.accessToken, owner.epoch, state.value.user?.id, subscription,
            registered?.copy(deviceName = "", customName = null, lastSeenAt = null))
    }

    private fun invalidatePreparation() = synchronized(preparationLock) {
        preparationJob?.cancel()
        preparationJob = null
        preparationKey = null
        preparationCompletedAt = null
    }

    private fun prepareConnectionInAdvance() {
        if (!windowVisible || state.value.user == null || state.value.running || state.value.connecting) return
        val device = registered ?: return
        val settings = state.value.settings
        val network = runCatching { networkSnapshot() }.getOrNull() ?: return
        val key = preparationKeyFor(settings, network) ?: return
        synchronized(preparationLock) {
            if (preparationKey == key && (preparationJob?.isActive == true || preparationCompletedAt?.let {
                monotonicMillis() - it in 0 until 60_000L
            } == true)) return
            preparationJob?.cancel()
            preparationKey = key
            preparationCompletedAt = null
            val job = scope.async(start = CoroutineStart.LAZY) {
                val ownJob = currentCoroutineContext()[Job]
                withTimeout(20_000) { prepareDirect(settings, device, network) }.also { result ->
                    synchronized(preparationLock) {
                        if (preparationJob === ownJob) preparationCompletedAt = result.createdAt
                    }
                }
            }
            preparationJob = job
            job.start()
        }
    }

    private suspend fun consumePreparation(settings: StoredSettings, network: WindowsNetworkSnapshot): DirectPreparation? {
        val key = preparationKeyFor(settings, network)
        val pending = synchronized(preparationLock) {
            val result = preparationJob.takeIf { key != null && preparationKey == key }
            if (result == null) preparationJob?.cancel()
            preparationJob = null
            preparationKey = null
            preparationCompletedAt = null
            result
        } ?: return null
        val prepared = try { pending.await() }
        catch (cancelled: CancellationException) { currentCoroutineContext().ensureActive(); return null }
        catch (error: Exception) { recordDiagnosticFailure("prepared_connection_failed", error, "vpn"); return null }
        finally { pending.cancel() }
        if (monotonicMillis() - prepared.createdAt !in 0 until 60_000L ||
            preparationKeyFor(settings, networkSnapshot()) != key) return null
        recordLog("prepared_connection_consumed")
        val selected = prepared.settings.userProfile
        val fresh = settings.copy(userProfile = settings.userProfile.copy(
            serverSelectionMode = selected.serverSelectionMode, selectedCountryCode = selected.selectedCountryCode,
            selectedNodeId = selected.selectedNodeId, selectedServerCode = selected.selectedServerCode,
            actualCountryCode = selected.actualCountryCode,
        ))
        val samples = prepared.locations.flatMap { it.servers }.mapNotNull { server ->
            server.latencyMs?.let { latency -> ServerLocationMapper.latencyTargetKey(server)?.let { it to latency } }
        }.toMap()
        return prepared.copy(settings = fresh,
            locations = ServerLocationMapper.mapLocations(prepared.catalog, fresh.personalizationSettings.language, samples))
    }

    private suspend fun createDirectSession(device: BackendDevice, settings: StoredSettings,
        server: VpnServer?, multiHop: MultiHopSettings): BackendVpnSession = withDeviceProof(device.id) { token, nonce, signature ->
        api.createVpnSession(token, device.id, device.deviceKey, nonce, signature,
            server?.countryCode, server?.locationCode, null, settings.advancedSettings.effectiveProtocol.name.lowercase(),
            server?.id, multiHop.takeIf { it.enabled })
    }

    private fun eligibleCandidates(session: BackendVpnSession, settings: StoredSettings): List<BackendEndpointCandidate> {
        val advanced = settings.advancedSettings
        val candidates = SingBoxConfig.supportedCandidates(session).filter {
            EndpointTransportPolicy.matchesProtocol(it, advanced.effectiveProtocol)
        }
        return if (!advanced.gamingModeEnabled && advanced.endpointSelectionMode == EndpointSelectionMode.MANUAL)
            EndpointGroupPolicy.manualCandidates(candidates, advanced) else candidates
    }

    private suspend fun prepareDirect(requested: StoredSettings, device: BackendDevice, network: WindowsNetworkSnapshot,
        onlyNodeId: String? = null, excludedNodeIds: Set<String> = emptySet()): DirectPreparation = coroutineScope {
        val selectors = (requested.advancedSettings.alwaysOnDomains + requested.advancedSettings.bypassDomains +
            state.value.routingRules.filter { it.enabled }.flatMap { it.serverDomains() }).filter { it.startsWith("geosite:") }.toSet()
        val geosites = async { loadGeosites(selectors) }
        val catalog = authorized { api.serverLocations(it, device.id, device.deviceKey) }
        val settings = requested
        val samplesFromUi = if (lastNetwork?.signature == network.signature) state.value.servers.mapNotNull { server ->
            server.latencyMs?.let { latency -> ServerLocationMapper.latencyTargetKey(server)?.let { it to latency } }
        }.toMap() else emptyMap()
        var locations = ServerLocationMapper.mapLocations(catalog, state.value.language, samplesFromUi)
        var visible = locations.flatMap { it.servers }.filter { it.id !in excludedNodeIds && (onlyNodeId == null || it.id == onlyNodeId) }
        var servers = EndpointRankingPolicy.rankServers(visible, settings.userProfile)
        if (!settings.advancedSettings.multiHop.enabled && servers.size > 1) {
            val ids = servers.mapTo(mutableSetOf()) { it.id }
            val targets = locations.map { it.copy(servers = it.servers.filter { node -> node.id in ids }) }.filter { it.servers.isNotEmpty() }
            val samples = latencySampler(network).measureForStartup(targets)
            locations = locations.map { ServerLocationMapper.withLatencies(it, samples) }
            visible = locations.flatMap { it.servers }.filter { it.id in ids }
            servers = EndpointRankingPolicy.rankServers(visible, settings.userProfile)
        }
        currentCoroutineContext().ensureActive()
        check(networkSnapshot().signature == network.signature) { "Сеть изменилась. Повторите подключение" }
        val multiHop = resolveMultiHop(settings.advancedSettings.multiHop, locations.flatMap { it.servers }, false)
        val nodes: List<VpnServer?> = if (multiHop.enabled) listOf(null) else servers
        check(nodes.isNotEmpty()) { "Нет доступных серверов для выбранного маршрута" }
        val geositeBytes = geosites.await()
        var lastFailure: Exception = IllegalStateException("Выбранный протокол недоступен. Выберите другой протокол или Auto")
        for ((index, server) in nodes.withIndex()) {
            val session = try { createDirectSession(device, settings, server, multiHop) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: BackendException) {
                if (error.statusCode !in setOf(404, 503)) throw error
                lastFailure = error
                continue
            }
            check(session.canConnect) { "VPN недоступен для текущей подписки или устройства" }
            if (eligibleCandidates(session, settings).isEmpty()) continue
            return@coroutineScope DirectPreparation(settings, catalog, locations, multiHop, nodes.drop(index), session, geositeBytes, monotonicMillis())
        }
        throw lastFailure
    }

    private suspend fun connectNow(requestedSettings: StoredSettings = state.value.settings, onlyNodeId: String? = null,
        excludedNodeIds: Set<String> = emptySet(), protectUntilVerified: Boolean = false) {
        // One monotonic coroutine budget includes admission, core startup and every fallback.
        val completed = withTimeoutOrNull(45_000L) {
            connectWithinDeadline(requestedSettings, onlyNodeId, excludedNodeIds, protectUntilVerified)
            true
        }
        if (completed != true) throw java.net.SocketTimeoutException("Время ожидания подключения истекло")
    }

    private suspend fun connectWithinDeadline(requestedSettings: StoredSettings, onlyNodeId: String?, excludedNodeIds: Set<String>,
        protectUntilVerified: Boolean): Unit = coroutineScope {
        val requestedAt = monotonicMillis()
        check(!state.value.running) { "VPN уже запущен" }
        val publishSelection = requestedSettings == state.value.settings
        mutable.update { it.copy(connecting = true, nokiNvCarrier = "") }
        latencyJob?.cancelAndJoin()
        latencyJob = null
        recordLog("connect_requested")
        val dnsPolicy = async { runInterruptible(Dispatchers.IO) { dnsPolicySnapshot() } }
        if (runtime.restored() == null) revokeRu()
        val device = registered ?: authorized { token -> api.registerDevice(token, vault.registrationDeviceKey,
            vault.deviceId.ifBlank { null }, System.getenv(if (DesktopBuild.IS_LINUX) "HOSTNAME" else "COMPUTERNAME").orEmpty().ifBlank { if (DesktopBuild.IS_LINUX) "Linux PC" else "Windows PC" }.take(120),
            vault.publicKey, vault.deviceClaims, com.noki.desktop.DesktopBuild.PLATFORM) }.also { vault.acceptRegisteredDevice(it); registered = it }
        val network = networkSnapshot()
        if (requestedSettings.advancedSettings.ruRelayEnabled) {
            invalidatePreparation()
            val selectors = (requestedSettings.advancedSettings.alwaysOnDomains + requestedSettings.advancedSettings.bypassDomains +
                state.value.routingRules.filter { it.enabled }.flatMap { it.serverDomains() }).filter { it.startsWith("geosite:") }.toSet()
            coroutineScope {
                val geosites = async { loadGeosites(selectors) }
                connectWireGuard(device, requestedSettings, geosites, onlyNodeId, excludedNodeIds, protectUntilVerified, dnsPolicy)
            }
            return@coroutineScope
        }
        val prepared = if (onlyNodeId == null && excludedNodeIds.isEmpty()) consumePreparation(requestedSettings, network) else null
        val plan = prepared ?: prepareDirect(requestedSettings, device, network, onlyNodeId, excludedNodeIds)
        val preparedAt = monotonicMillis()
        val settings = plan.settings
        catalogLocations = plan.catalog
        mutable.update { it.copy(locations = plan.locations) }
        val hops = plan.multiHop.explicitNodeIds()
        val auto = settings.advancedSettings.effectiveProtocol == VpnProtocol.AUTO &&
            settings.advancedSettings.endpointSelectionMode != EndpointSelectionMode.MANUAL
        var excludeNokiNv = false
        var lastFailure: Exception = java.io.IOException("Нет доступного профиля подключения")
        for ((nodeIndex, server) in plan.nodes.withIndex()) {
            currentCoroutineContext().ensureActive()
            val session = if (nodeIndex == 0) plan.session else try { createDirectSession(device, settings, server, plan.multiHop) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (error: BackendException) {
                    if (error.statusCode !in setOf(404, 503)) throw error
                    lastFailure = error
                    continue
                }
            check(session.canConnect) { "VPN недоступен для текущей подписки или устройства" }
            val eligible = eligibleCandidates(session, settings)
            val excludedCodes = mutableSetOf<String>()
            val attemptedTransportGroups = mutableSetOf<String>()
            while (true) {
                currentCoroutineContext().ensureActive()
                val remaining = eligible.filter { it.code !in excludedCodes && (!excludeNokiNv || !EndpointTransportPolicy.isNokiNv(it)) }
                if (remaining.isEmpty()) break
                val untriedTransports = remaining.filter { EndpointGroupPolicy.groupKey(it) !in attemptedTransportGroups }
                val candidates = untriedTransports.ifEmpty { remaining }
                val route = EndpointSelector.profileFromCandidate(session, remaining.first()).multiHop
                val health = healthFor(network.kind, route)
                val preflight = if (hops == null) runInterruptible(Dispatchers.IO) {
                    EndpointStartupPreflightPolicy.selectWithTcpPrecheck(candidates, health, network.kind, System.currentTimeMillis(), ::nextEndpointRotation,
                        { candidate -> network.interfaceIndex?.let { index ->
                            windowsTcpLatency(EndpointSelector.profileFromCandidate(session, candidate).host, candidate.entryPort, 800, index)
                        } != null }, excludeNokiNv = excludeNokiNv)
                } else null
                if (preflight?.shouldPenalizeFailedTcp == true) preflight.failedTcpCodes.forEach { recordEndpointResult(it, false, network.kind, route) }
                excludedCodes += preflight?.failedTcpCodes.orEmpty()
                val candidate = if (hops == null) preflight?.selected else EndpointRankingPolicy.select(candidates, health, network.kind,
                    System.currentTimeMillis(), ::nextEndpointRotation, excludeNokiNv = excludeNokiNv)?.candidate
                if (candidate == null) break
                attemptedTransportGroups += EndpointGroupPolicy.groupKey(candidate)
                val controlPort = SingBoxRuntime.freePort()
                var probePort = SingBoxRuntime.freePort()
                while (probePort == controlPort) probePort = SingBoxRuntime.freePort()
                val secret = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also { SecureRandom().nextBytes(it) })
                val resolvedSettings = settings.copy(advancedSettings = settings.advancedSettings.copy(multiHop = plan.multiHop))
                val profile = EndpointSelector.profileFromCandidate(session, candidate).copy(
                    nokinvMode = if (settings.advancedSettings.gamingModeEnabled) "gaming" else "balanced")
                val policy = dnsPolicy.await()
                val config = runInterruptible(Dispatchers.IO) {
                    SingBoxConfig.generate(session, candidate, controlPort, secret, probePort, NokiBackendConfig.apiBaseUrl,
                        resolvedSettings, plan.geosites, state.value.routingRules, dnsPolicy = { policy })
                }
                try {
                    check(networkSnapshot().signature == network.signature) { "Сеть изменилась. Повторите подключение" }
                    val launchAt = monotonicMillis()
                    val active = startRuntime(config, resolvedSettings, "${candidate.locationName.ifBlank { session.locationName }} · ${candidate.label.ifBlank { candidate.code }}",
                        candidate.nodeId ?: server?.id, protectUntilVerified)
                    val readyAt = monotonicMillis()
                    mutable.update { it.copy(selectedServerId = candidate.nodeId ?: server?.id.orEmpty(), running = true, verified = false,
                        connectionLabel = active.label, connectedAtMillis = active.startedAt, connectionSettings = resolvedSettings.copy(profile = profile)) }
                    // Naive's outer HTTP/2 handshake shares startup with the first DNS streams.
                    val latency = runtime.probe(active, fastStartup = hops == null && !EndpointTransportPolicy.isNaive(candidate))
                    val verifiedAt = monotonicMillis()
                    mutable.update { it.copy(verified = true) }
                    recordLatencySample(latency, sessionStarted = true)
                    recordEndpointResult(candidate.code, true, network.kind, route, latency)
                    val options = EndpointSelector.optionsFromSession(session.copy(endpointCandidates = SingBoxConfig.supportedCandidates(session)))
                    val selectedSettings = settings.copy(endpointOptions = options,
                        advancedSettings = EndpointGroupPolicy.settingsAfterSelection(settings.advancedSettings, candidate.code, options))
                    if (publishSelection) {
                        vault.saveSettings(selectedSettings)
                        mutable.update { it.copy(settings = selectedSettings) }
                    }
                    rememberConnection(active, config, resolvedSettings.copy(profile = profile), network.kind)
                    startWatchdog(active, initialEvidence = true)
                    transientRecoveryAttempt = 0
                    val finishedAt = monotonicMillis()
                    recordLog("connect_timing prepare_ms=${preparedAt - requestedAt} setup_ms=${launchAt - preparedAt} " +
                        "core_ms=${readyAt - launchAt} probe_ms=${verifiedAt - readyAt} finish_ms=${finishedAt - verifiedAt} " +
                        "total_ms=${finishedAt - requestedAt}", category = "vpn")
                    return@coroutineScope
                } catch (error: Exception) {
                    withContext(NonCancellable) {
                        runCatching { stopRuntime(restoreInternet = false, retainConnection = true) }.onFailure { error.addSuppressed(it) }
                        val active = runtime.restored()
                        mutable.update { it.copy(running = active != null, verified = false, nokiNvCarrier = "",
                            connectedAtMillis = active?.startedAt, connectionLabel = active?.label.orEmpty()) }
                    }
                    if (error is CancellationException || runtime.restored() != null) throw error
                    recordDiagnosticFailure("connection_attempt_failed", error, "vpn")
                    recordEndpointResult(candidate.code, false, network.kind, route)
                    excludedCodes += candidate.code
                    if (auto && EndpointTransportPolicy.isNokiNv(candidate)) excludeNokiNv = true
                    lastFailure = error
                }
            }
        }
        if (lastFailure is BackendException || lastFailure !is java.io.IOException) throw lastFailure
        throw EndpointAttemptFailure(lastFailure)
    }

    private fun healthFor(network: EndpointRankingPolicy.NetworkKind, route: BackendMultiHopSession?): Map<String, EndpointHealth> = synchronized(endpointHealthLock) {
        if (route == null) EndpointHealthScope.forNetwork(endpointHealth, network) else DesktopRouteHealthScope.forRoute(endpointHealth, network, route)
    }

    private fun recordEndpointResult(code: String, success: Boolean, network: EndpointRankingPolicy.NetworkKind, route: BackendMultiHopSession?, latency: Long? = null) {
        if (code.isBlank()) return
        synchronized(endpointHealthLock) {
            val key = route?.let { DesktopRouteHealthScope.routeKey(network, it, code) } ?: EndpointHealthScope.key(network, code)
            val previous = healthFor(network, route)[code] ?: EndpointHealth()
            endpointHealth = endpointHealth + (key to EndpointRankingPolicy.updateAfterResult(previous, success, System.currentTimeMillis(), latencyMs = latency))
            vault.writeEncrypted("endpoint-health.dpapi", EndpointHealthStore.encode(endpointHealth))
        }
    }

    private fun nextEndpointRotation(key: String): Int = synchronized(accountLocalLock) {
        val rotations = accountLocal.optJSONObject("endpoint_rotations") ?: JSONObject()
        val current = rotations.optInt(key).coerceAtLeast(0)
        rotations.put(key, if (current == Int.MAX_VALUE) 0 else current + 1)
        accountLocal.put("endpoint_rotations", rotations)
        vault.saveAccountLocal(accountLocal)
        current
    }

    private fun activeSettings(): StoredSettings = activeConnection?.optString("settings")?.takeIf { it.isNotBlank() }
        ?.let(vault::decodeSettings) ?: state.value.settings

    private fun activeNetwork(): EndpointRankingPolicy.NetworkKind = activeConnection?.optString("network")
        ?.let { name -> EndpointRankingPolicy.NetworkKind.entries.firstOrNull { it.name == name } } ?: EndpointRankingPolicy.NetworkKind.OTHER

    private fun persistConnection() {
        activeConnection?.let {
            it.put("recoveries", org.json.JSONArray(synchronized(watchdog) { recoveryTimes.toList() }))
            vault.writeEncrypted("connection.dpapi", it.toString())
        }
    }

    private fun rememberConnection(active: SingBoxRuntime.Running, config: JSONObject, settings: StoredSettings, network: EndpointRankingPolicy.NetworkKind) {
        activeConnection = JSONObject().put("version", 3).put("pid", active.pid).put("started_at", active.startedAt)
            .put("label", active.label).put("server_id", active.serverId).put("config", config)
            .put("settings", vault.encodeSettings(settings)).put("network", network.name).put("user_id", state.value.user?.id)
        mutable.update { it.copy(connectionSettings = settings) }
        persistConnection()
        startYoutubeAccess()
    }

    private fun configHash(config: JSONObject): String = Base64.getEncoder().encodeToString(
        java.security.MessageDigest.getInstance("SHA-256").digest(config.toString().toByteArray(Charsets.UTF_8)))

    private suspend fun startRuntime(base: JSONObject, settings: StoredSettings, label: String, serverId: String?, protectUntilVerified: Boolean = false): SingBoxRuntime.Running {
        // Keep the YouTube classifier identical across initial start, lease renewal and blocking.
        val config = SingBoxConfig.withYoutube(base, settings, null)
        return runtime.start(config, label, settings.advancedSettings.killSwitchEnabled || protectUntilVerified, serverId).also {
            appliedYoutubeConfig = configHash(config)
        }
    }

    // The connection journal owns the main route; supplementary access never replaces its credentials.
    private suspend fun replaceYoutubeConnection(connection: YoutubeConnectionConfig?) {
        val journal = checkNotNull(activeConnection) { "youtube_main_connection_missing" }
        val old = runtime.restored() ?: return
        val settings = activeSettings()
        val base = journal.getJSONObject("config")
        val config = SingBoxConfig.withYoutube(base, settings, connection)
        if (configHash(config) == appliedYoutubeConfig) return
        stopWatchdog()
        val verified = state.value.verified
        val wasLoading = state.value.loading
        val replacement = currentCoroutineContext()[Job]
        val previousConnection = connectionJob
        connectionJob = replacement
        mutable.update { it.copy(loading = true) }
        try {
            withContext(NonCancellable) { stopRuntime(restoreInternet = false, retainConnection = true, preserveYoutube = true) }
            var supplementaryFailure: Exception? = null
            val active = try {
                runtime.start(config, old.label, settings.advancedSettings.killSwitchEnabled, old.serverId).also {
                    appliedYoutubeConfig = configHash(config)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                supplementaryFailure = error
                // A bad supplementary profile must not take the main VPN down or fall back to DIRECT.
                runCatching { runtime.stop(restoreInternet = false) }
                val blocked = SingBoxConfig.withYoutube(base, settings, null)
                runtime.start(blocked, old.label, settings.advancedSettings.killSwitchEnabled, old.serverId).also {
                    appliedYoutubeConfig = configHash(blocked)
                    mutable.update { state -> state.copy(youtubeMessage = tr(state,
                        "Канал YouTube недоступен. YouTube заблокирован", "YouTube channel unavailable. YouTube is blocked")) }
                }
            }
            journal.put("pid", active.pid).put("started_at", active.startedAt).put("label", active.label).put("server_id", active.serverId)
            persistConnection()
            mutable.update { it.copy(running = true, verified = verified, connectionSettings = settings,
                uploadBytesPerSecond = null, downloadBytesPerSecond = null) }
            startWatchdog(active, initialEvidence = verified)
            supplementaryFailure?.let { throw it }
        } finally {
            if (connectionJob === replacement) connectionJob = previousConnection
            mutable.update { it.copy(loading = wasLoading) }
        }
    }

    private fun youtubeCurrent(epoch: Long, owner: AuthSessionAttempt) = youtubeEpoch.get() == epoch && current(owner) &&
        state.value.running && activeSettings().advancedSettings.youtubeAdBlockEnabled

    private suspend fun stopYoutubeAccess(waitForCleanup: Boolean = true) {
        youtubeEpoch.incrementAndGet()
        val job = youtubeJob
        job?.cancel()
        if (waitForCleanup) job?.join()
        // Keep a retiring worker as the predecessor of the next admission.
        if (youtubeJob === job && (waitForCleanup || job?.isCompleted != false)) youtubeJob = null
        youtubeConfirmed = null
        mutable.update { it.copy(youtubeMessage = null) }
    }

    private fun startYoutubeAccess() {
        val previous = youtubeJob
        previous?.cancel()
        val epoch = youtubeEpoch.incrementAndGet()
        youtubeConfirmed = null
        if (!activeSettings().advancedSettings.youtubeAdBlockEnabled || !state.value.running) { youtubeJob = null; return }
        val owner = attempt() ?: return
        val device = registered ?: return
        youtubeJob = scope.launch {
            previous?.join()
            if (!youtubeCurrent(epoch, owner)) return@launch
            runYoutubeAccess(epoch, owner, device)
        }
    }

    private suspend fun runYoutubeAccess(epoch: Long, owner: AuthSessionAttempt, device: BackendDevice) = coroutineScope {
        var grant: BackendYoutubeAccess? = null
        var leaseTimer: Job? = null
        var createAttempted = false
        val requestId = java.util.UUID.randomUUID().toString()
        var retryMillis = 2_000L
        suspend fun receive(previous: BackendYoutubeAccess?, renewal: Boolean = false,
                            call: suspend () -> BackendYoutubeAccess): BackendYoutubeAccess = withContext(NonCancellable) {
            val response = call()
            if (previous != null) {
                require(response.grantId == previous.grantId && response.mode == previous.mode && response.revokeToken == previous.revokeToken) {
                    "youtube_access_identity_mismatch"
                }
                require(if (renewal) response.generation == previous.generation + 1 else
                    response.generation == previous.generation || response.status == "blocked" && response.generation > previous.generation) {
                    "youtube_access_generation_mismatch"
                }
            }
            try { stageYoutubeRevoke(response) } catch (error: Exception) {
                runCatching { api.revokeYoutubeAccess(response.grantId, response.revokeToken) }
                throw error
            }
            grant = response
            response
        }
        suspend fun create(): BackendYoutubeAccess = receive(null) {
            withDeviceProof(device.id) { token, nonce, signature ->
                createAttempted = true
                api.createYoutubeAccess(token, YoutubeAccessRequest(device.id, device.deviceKey, nonce, signature,
                    requestId))
            }
        }
        suspend fun apply(connection: YoutubeConnectionConfig?, message: String?, confirmed: BackendYoutubeAccess? = null) = operation.withLock {
            if (!youtubeCurrent(epoch, owner)) return@withLock
            val valid = confirmed == null || confirmed.expiresAtEpochMillis > System.currentTimeMillis()
            replaceYoutubeConnection(connection.takeIf { valid })
            val expired = confirmed != null && confirmed.expiresAtEpochMillis <= System.currentTimeMillis()
            if (expired) replaceYoutubeConnection(null)
            youtubeConfirmed = confirmed.takeUnless { expired }
            mutable.update { it.copy(youtubeMessage = if (expired)
                tr(it, "Доступ к YouTube истёк. YouTube заблокирован", "YouTube access expired. YouTube is blocked") else message) }
        }
        try {
            revokeYoutubePending()
            while (youtubeCurrent(epoch, owner)) {
                try {
                    val currentGrant = grant ?: create()
                    currentCoroutineContext().ensureActive()
                    if (!youtubeCurrent(epoch, owner)) break
                    when (currentGrant.status) {
                        "ready" -> {
                            check(currentGrant.expiresAtEpochMillis > System.currentTimeMillis()) { "youtube_access_expired" }
                            val connection = YoutubeConnectionConfig.fromReady(currentGrant)
                            apply(connection, null, currentGrant)
                            leaseTimer?.cancel()
                            leaseTimer = launch {
                                delay((currentGrant.expiresAtEpochMillis - System.currentTimeMillis()).coerceAtLeast(0))
                                operation.withLock {
                                    if (youtubeCurrent(epoch, owner) && youtubeConfirmed === currentGrant) {
                                        replaceYoutubeConnection(null)
                                        youtubeConfirmed = null
                                        mutable.update { it.copy(youtubeMessage = tr(it,
                                            "Доступ к YouTube истёк. YouTube заблокирован", "YouTube access expired. YouTube is blocked")) }
                                    }
                                }
                            }
                            delay(((currentGrant.expiresAtEpochMillis - System.currentTimeMillis()) * 2 / 3).coerceAtLeast(1))
                            // Retries use the last confirmed generation; the backend renew is idempotent.
                            while (youtubeCurrent(epoch, owner)) {
                                try {
                                    receive(currentGrant, renewal = true) {
                                        withDeviceProof(device.id) { token, nonce, signature ->
                                            api.renewYoutubeAccess(token, device.id, device.deviceKey, nonce, signature, currentGrant)
                                        }
                                    }
                                    break
                                } catch (cancelled: CancellationException) { throw cancelled }
                                catch (error: Exception) {
                                    if (!BackendRetryPolicy.isTransient(error)) throw error
                                    delay(retryMillis); retryMillis = (retryMillis * 2).coerceAtMost(30_000)
                                }
                            }
                        }
                        "pending" -> {
                            if (youtubeConfirmed == null) apply(null, tr(state.value,
                                "Подготавливаем канал YouTube. YouTube пока заблокирован", "Preparing the YouTube channel. YouTube is blocked for now"))
                            check(currentGrant.expiresAtEpochMillis > System.currentTimeMillis()) { "youtube_access_expired" }
                            delay(2_000)
                            receive(currentGrant) { withDeviceProof(device.id) { token, nonce, signature ->
                                api.pollYoutubeAccess(token, device.id, device.deviceKey, nonce, signature, currentGrant.grantId)
                            } }
                        }
                        else -> { apply(null, tr(state.value,
                            "Доступ к YouTube отозван. YouTube заблокирован", "YouTube access revoked. YouTube is blocked")); break }
                    }
                    retryMillis = 2_000
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) {
                    if (!BackendRetryPolicy.isTransient(error)) throw error
                    if (youtubeConfirmed == null) apply(null, tr(state.value,
                        "Канал YouTube недоступен. Повторим запрос", "YouTube channel unavailable. Retrying"))
                    delay(retryMillis); retryMillis = (retryMillis * 2).coerceAtMost(30_000)
                }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { apply(null, tr(state.value,
            "Канал YouTube недоступен. YouTube заблокирован", "YouTube channel unavailable. YouTube is blocked")) }
        finally {
            leaseTimer?.cancel()
            withContext(NonCancellable) {
                // Recover a lost create reply by its stable request ID before capability-only cleanup.
                if (createAttempted && grant == null && current(owner)) runCatching { create() }
                if (youtubeEpoch.get() == epoch || youtubeGrantId == grant?.grantId) youtubeGrantId = null
                runCatching { revokeYoutubePending() }
            }
        }
    }

    private suspend fun stageYoutubeRevoke(grant: BackendYoutubeAccess) = youtubeRevokes.withLock {
        val pending = vault.readEncrypted("youtube-revoke.dpapi")?.let(::JSONObject) ?: JSONObject()
        pending.put(grant.grantId, grant.revokeToken)
        vault.writeEncrypted("youtube-revoke.dpapi", pending.toString())
        youtubeGrantId = grant.grantId
    }

    private suspend fun revokeYoutubePending() = youtubeRevokes.withLock {
        val pending = vault.readEncrypted("youtube-revoke.dpapi")?.let(::JSONObject) ?: return@withLock
        for (id in pending.keySet().toList()) {
            if (id == youtubeGrantId) continue
            try {
                api.revokeYoutubeAccess(id, pending.getString(id))
                pending.remove(id)
                if (pending.isEmpty) Files.deleteIfExists(vault.directory.resolve("youtube-revoke.dpapi"))
                else vault.writeEncrypted("youtube-revoke.dpapi", pending.toString())
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* Keep the capability encrypted for the next cleanup attempt. */ }
        }
    }

    private fun startWatchdog(active: SingBoxRuntime.Running, initialEvidence: Boolean) {
        if (!state.value.autoRecoveryEnabled) return
        mutable.update { it.copy(networkRebinding = false, nokiNvCarrier = "") }
        val owner = ConnectedWatchdogPolicy.Owner(++connectionGeneration, active.pid, active.startedAt)
        watchdog.start(owner, activeSettings(), initialEvidence)
    }

    internal fun verifyConnection() = watchdog.forceProbe()

    private fun stopWatchdog() {
        networkRebindJob?.cancel()
        networkRebindJob = null
        mutable.update { it.copy(networkRebinding = false) }
        connectionGeneration++
        watchdog.stop(null)
        recoveryJob?.cancel()
        recoveryJob = null
    }

    private fun launchWatchdogProbe(owner: ConnectedWatchdogPolicy.Owner, settings: StoredSettings): CancelableTask? {
        if (state.value.loading || state.value.connecting || networkRebindJob?.isActive == true) return null
        val network = runCatching { networkSnapshot() }.getOrNull()?.takeIf {
            it.available && it.signature == lastNetwork?.signature
        } ?: return null
        val job = scope.launch {
            val active = try { runtime.restored()?.takeIf { it.pid == owner.tunnelIdentity && it.startedAt == owner.coreIdentity } }
                catch (error: Exception) {
                    watchdog.discardProbe(owner)
                    recordDiagnosticFailure("watchdog_runtime_read_failed", error, "vpn")
                    return@launch
                }
            val latency = try { active?.let { runtime.probeWatchdog(it) } }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) {
                    recordDiagnosticFailure("watchdog_probe_failed", error, "vpn")
                    null
                }
            operation.withLock {
                currentCoroutineContext().ensureActive()
                val currentNetwork = runCatching { networkSnapshot() }.getOrNull()
                val outcome = synchronized(networkChangeLock) {
                    if (!watchdog.accepts(owner) || owner.generationId != connectionGeneration) return@withLock
                    if (currentNetwork?.available != true || currentNetwork.signature != network.signature ||
                        lastNetwork?.signature != network.signature) {
                        watchdog.discardProbe(owner)
                        return@withLock
                    }
                    val outcome = watchdog.completeProbe(owner, latency)
                    if (outcome == WatchdogProbeOutcome.Ignored) return@withLock
                    val live = runtime.restored()
                    val completingRebind = state.value.networkRebinding
                    mutable.update { it.copy(verified = outcome == WatchdogProbeOutcome.Healthy && live == active && live != null,
                        running = live != null, networkRebinding = completingRebind && live != null && outcome != WatchdogProbeOutcome.Healthy) }
                    if (outcome == WatchdogProbeOutcome.Healthy) {
                        activeConnection?.put("network", network.kind.name)
                        if (live == active && live != null && latency != null) recordLatencySample(latency)
                        recordEndpointResult(settings.profile.endpointCode, true, network.kind, settings.profile.multiHop, latency)
                    }
                    if (outcome is WatchdogProbeOutcome.Unhealthy && outcome.action == WatchdogAction.Refresh)
                        recordEndpointResult(settings.profile.endpointCode, false, network.kind, settings.profile.multiHop)
                    if (outcome is WatchdogProbeOutcome.Unhealthy || completingRebind) persistConnection()
                    outcome
                }
                if (outcome is WatchdogProbeOutcome.Unhealthy && watchdog.accepts(owner)) {
                    val live = runtime.restored()
                    when (val action = outcome.action) {
                        WatchdogAction.None -> if (state.value.networkRebinding && live == active && active != null)
                            queueNetworkRebind(active, owner, network.signature, delayMillis = 2_000)
                        WatchdogAction.Refresh -> {
                            recordLog("watchdog_recovery_requested")
                            recoverConnectedVpn(settings, expectedOwner = owner)
                        }
                        WatchdogAction.ReleaseOutsideLockdown -> {
                            if (!claimWatchdogAction(owner)) return@withLock
                            stopRuntime(restoreInternet = false)
                            clearConnectionState()
                            reportMessage("Не удалось восстановить VPN. Подключитесь снова")
                        }
                        is WatchdogAction.EnterLockdownFailure -> {
                            if (!claimWatchdogAction(owner)) return@withLock
                            stopRuntime(restoreInternet = false, retainConnection = true)
                            clearConnectionState()
                            reportMessage("Не удалось восстановить VPN. Kill switch блокирует Интернет; повторим подключение позже")
                            queueRecovery(action.retryDelayMillis)
                        }
                    }
                }
            }
        }
        return CancelableTask(job::cancel)
    }

    private fun claimWatchdogAction(owner: ConnectedWatchdogPolicy.Owner): Boolean = synchronized(networkChangeLock) {
        if (!watchdog.accepts(owner)) false else { watchdog.stop(null); true }
    }

    private fun physicalNetworkChanged(network: WindowsNetworkSnapshot): Unit = synchronized(networkChangeLock) {
        if (network.signature == lastNetwork?.signature && network.available == lastNetwork?.available) return@synchronized
        val active = runtime.restored()
        lastNetwork = network
        invalidatePreparation()
        latencyMeasuredAt = 0
        mutable.update { it.copy(locations = it.locations.map { location -> ServerLocationMapper.withLatencies(location, emptyMap()) },
            ruForeignNodes = it.ruForeignNodes.map { node -> node.copy(latencyMs = null) }) }
        watchdog.networkChanged()
        networkRebindJob?.cancel()
        networkRebindJob = null
        if (!state.value.autoRecoveryEnabled) return@synchronized
        mutable.update { it.copy(networkRebinding = active != null,
            verified = if (active != null) false else it.verified, nokiNvCarrier = "") }
        if (!network.available) return@synchronized
        val owner = watchdog.currentOwner()
        if (active != null && owner != null) queueNetworkRebind(active, owner, network.signature)
        else if (active == null && activeConnection != null) {
            val generation = connectionGeneration
            scope.launch {
                operation.withLock {
                    if (generation == connectionGeneration && network.signature == lastNetwork?.signature &&
                        runtime.restored() == null && activeConnection != null && !state.value.loading) {
                        recoveryJob?.cancel(); queueRecovery(0)
                    }
                }
            }
        }
    }

    private fun queueNetworkRebind(active: SingBoxRuntime.Running, owner: ConnectedWatchdogPolicy.Owner,
        signature: String, delayMillis: Long = 0): Unit = synchronized(networkChangeLock) {
        if (!watchdog.accepts(owner) || runtime.restored() != active || lastNetwork?.signature != signature ||
            lastNetwork?.available != true) return@synchronized
        networkRebindJob?.cancel()
        val job = scope.launch(start = CoroutineStart.LAZY) {
            val ownJob = currentCoroutineContext()[Job]
            var rebound = false
            var attempts = 0
            try {
                delay(delayMillis)
                while (isActive && watchdog.accepts(owner) && runtime.restored() == active && lastNetwork?.signature == signature) {
                    if (++attempts > 3) break
                    val network = runCatching { networkSnapshot() }.getOrNull()
                    currentCoroutineContext().ensureActive()
                    if (!watchdog.accepts(owner) || lastNetwork?.signature != signature) return@launch
                    if (network == null) { delay(2_000); continue }
                    if (!network.available || network.signature != signature) { physicalNetworkChanged(network); return@launch }
                    try {
                        val connection = activeConnection
                        val dnsMatches = connection?.let { runInterruptible(Dispatchers.IO) {
                            SingBoxConfig.dnsSettingsMatch(it.getJSONObject("config"), dnsPolicySnapshot)
                        } } ?: true
                        if (!watchdog.accepts(owner) || runtime.restored() != active || activeConnection !== connection) return@launch
                        if (!dnsMatches) {
                            operation.withLock {
                                if (watchdog.accepts(owner) && runtime.restored() == active) {
                                    mutable.update { it.copy(networkRebinding = false) }
                                    recoveryJob?.cancel(); queueRecovery(0)
                                }
                            }
                            return@launch
                        }
                        runtime.rebindNetwork(active)
                        rebound = true
                        break
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (error: Exception) { recordDiagnosticFailure("network_rebind_failed", error, "vpn") }
                    delay(2_000)
                }
            } finally {
                synchronized(networkChangeLock) { if (networkRebindJob === ownJob) networkRebindJob = null }
            }
            if (rebound && watchdog.accepts(owner) && runtime.restored() == active && lastNetwork?.signature == signature)
                watchdog.forceProbe()
            else if (!rebound) operation.withLock {
                if (watchdog.accepts(owner) && runtime.restored() == active && lastNetwork?.signature == signature &&
                    lastNetwork?.available == true) {
                    mutable.update { it.copy(networkRebinding = false) }
                    recoveryJob?.cancel(); queueRecovery(0)
                }
            }
        }
        networkRebindJob = job
        job.start()
    }

    private fun clearConnectionState() {
        mutable.update { it.copy(running = false, verified = false, networkRebinding = false, nokiNvCarrier = "", connectedAtMillis = null, connectionLabel = "",
            uploadBytesPerSecond = null, downloadBytesPerSecond = null, sessionBytes = null, connectionSettings = null, networkBlocked = runtime.guardArmed()) }
    }

    private suspend fun startCachedConnection(cached: JSONObject): Boolean {
        if (cached.optInt("version") != 3) return false
        val user = state.value.user?.id
        check(user == null || cached.optString("user_id").ifBlank { user } == user) { "Аккаунт соединения изменился" }
        val settings = vault.decodeSettings(cached.getString("settings"))
        if (!VpnProfileValidator.isUsable(settings.profile, settings.advancedSettings)) return false
        // RU authorization must be reissued by its control plane; never reuse a revoked lease.
        if (settings.advancedSettings.ruRelayEnabled) return false
        // An armed guard may outlive a failed write that left revoked credentials on disk.
        if (runtime.guardArmed() && runtime.restored() == null) return false
        val config = cached.getJSONObject("config")
        if (!SingBoxConfig.dnsSettingsMatch(config, dnsPolicySnapshot)) return false
        val active = startRuntime(config, settings, cached.getString("label"),
            cached.optString("server_id").ifBlank { null })
        mutable.update { it.copy(running = true, verified = false, connectionLabel = active.label, connectedAtMillis = active.startedAt,
            selectedServerId = active.serverId.orEmpty(), connectionSettings = settings) }
        val latency = runtime.probe(active)
        mutable.update { it.copy(verified = true) }
        recordLatencySample(latency, sessionStarted = true)
        recordEndpointResult(settings.profile.endpointCode, true, activeNetwork(), settings.profile.multiHop, latency)
        rememberConnection(active, config, settings, activeNetwork())
        startWatchdog(active, initialEvidence = true)
        transientRecoveryAttempt = 0
        return true
    }

    private suspend fun recoverConnectedVpn(settings: StoredSettings, expectedOwner: ConnectedWatchdogPolicy.Owner? = null) {
        if (!state.value.autoRecoveryEnabled) return
        if (!ConnectedWatchdogPolicy.automaticRecoveryAllowed(settings.userProfile.serverSelectionMode)) return
        if (runCatching { networkSnapshot().available }.getOrDefault(false).not()) {
            if (runtime.restored() == null)
                queueRecovery(ConnectedWatchdogPolicy.transientRetryDelayMillis(transientRecoveryAttempt++))
            return
        }
        synchronized(networkChangeLock) {
            if (expectedOwner != null && !claimWatchdogAction(expectedOwner)) return
            networkRebindJob?.cancel()
            networkRebindJob = null
            mutable.update { it.copy(networkRebinding = false) }
            watchdog.stop(null)
        }
        recoveryJob = currentCoroutineContext()[Job]
        mutable.update { it.copy(loading = true, connecting = true, verified = false) }
        val cached = activeConnection
        val protectUntilVerified = runtime.guardArmed() && !settings.advancedSettings.killSwitchEnabled
        val failedNode = cached?.optString("server_id")?.ifBlank { null }
        try {
            stopRuntime(restoreInternet = false, retainConnection = true)
            clearConnectionState()
            if (cached != null) {
                try { if (startCachedConnection(cached)) { recordLog("watchdog_cached_success"); return } }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) {
                    recordDiagnosticFailure("watchdog_cached_failed", error, "vpn")
                    stopRuntime(restoreInternet = false, retainConnection = true)
                    clearConnectionState()
                }
            }
            val targets = ConnectedWatchdogPolicy.recoveryTargets(failedNode.orEmpty()).flatMap { target -> List(target.attempts) { target } }
            for (target in targets) {
                try {
                    connectNow(settings, onlyNodeId = target.locationCode?.ifBlank { null },
                        excludedNodeIds = setOfNotNull(target.excludeLocationCode?.ifBlank { null }),
                        protectUntilVerified = protectUntilVerified)
                    if (protectUntilVerified) runtime.releaseGuard(allowConnected = true)
                    recordLog("watchdog_recovery_success")
                    return
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) {
                    recordDiagnosticFailure("watchdog_recovery_failed", error, "vpn")
                    if (error is BackendException && error.statusCode in setOf(401, 403)) throw error
                    if (error !is EndpointAttemptFailure && BackendRetryPolicy.isTransient(error)) {
                        queueRecovery(ConnectedWatchdogPolicy.transientRetryDelayMillis(transientRecoveryAttempt++))
                        return
                    }
                }
            }
            if (settings.advancedSettings.killSwitchEnabled) {
                queueRecovery(watchdog.lockdownRetryDelayMillis())
                reportMessage("VPN недоступен. Kill switch сохраняет блокировку; повторим подключение позже")
            } else {
                activeConnection = null
                Files.deleteIfExists(vault.directory.resolve("connection.dpapi"))
                reportMessage("Не удалось восстановить VPN. Подключитесь снова")
            }
            recordLog("watchdog_recovery_exhausted", error = true)
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) { stopRuntime(restoreInternet = false, retainConnection = true); clearConnectionState() }
            throw cancelled
        } catch (error: Exception) {
            stopRuntime(restoreInternet = false, retainConnection = true)
            clearConnectionState()
            if (error is BackendException && error.statusCode == 401) clearAccount()
            else if (error is BackendException && error.statusCode == 403) {
                activeConnection = null
                Files.deleteIfExists(vault.directory.resolve("connection.dpapi"))
            }
            reportMessage(AppErrorMapper.readableNetworkError(state.value.language, error))
        } finally {
            mutable.update { it.copy(loading = false, connecting = false, networkBlocked = runtime.guardArmed()) }
        }
    }

    private fun queueRecovery(delayMillis: Long) {
        if (!state.value.autoRecoveryEnabled) return
        val owner = connectionGeneration
        activeConnection?.put("retry_at", System.currentTimeMillis() + delayMillis)?.put("transient_attempt", transientRecoveryAttempt)
        persistConnection()
        recoveryJob = scope.launch {
            delay(delayMillis.coerceAtLeast(0))
            operation.withLock {
                if (owner == connectionGeneration && tokens != null && activeConnection != null) recoverConnectedVpn(activeSettings())
            }
        }
    }

    private class EndpointAttemptFailure(cause: Exception) : java.io.IOException("Не удалось подключиться через доступные профили", cause)

    private suspend fun loadGeosites(selectors: Set<String>): ByteArray = geositeOperation.withLock {
        val path = vault.directory.resolve("geosite.dat")
        val bytes = try {
            val (hash, size) = api.geositeMetadata()
            val cached = if (Files.isRegularFile(path) && Files.size(path) == size.toLong())
                Files.readAllBytes(path).takeIf { GeositeAsset.sha256(it) == hash } else null
            cached ?: api.downloadGeosite(size).also {
                require(GeositeAsset.sha256(it) == hash) { "Контрольная сумма geosite не совпадает" }
                GeositeAsset.resolveDomains(it, selectors)
                currentCoroutineContext().ensureActive()
                val temporary = vault.directory.resolve("geosite.dat.tmp")
                Files.write(temporary, it); Files.move(temporary, path, java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) {
            if (!BackendRetryPolicy.isTransient(error)) throw error
            SingBoxConfig.bundledGeosite()
        }
        GeositeAsset.resolveDomains(bytes, selectors)
        bytes
    }

    private suspend fun connectWireGuard(device: BackendDevice, requestedSettings: StoredSettings, geosites: Deferred<ByteArray>,
        onlyNodeId: String?, excludedNodeIds: Set<String>, protectUntilVerified: Boolean = false, dnsPolicy: Deferred<WindowsDnsPolicy>) {
        var privateKey: ByteArray? = null
        var reserved: BackendRuWireGuardIdentity? = null
        try {
            val geositeBytes = geosites.await()
            val (ready, exit, settings) = withTimeoutOrNull(30_000L) {
                val requestedMultiHop = requestedSettings.advancedSettings.multiHop
                val requestedHops = if (requestedMultiHop.entry?.kind == ServerSelectionMode.SERVER &&
                    requestedMultiHop.exit?.kind == ServerSelectionMode.SERVER) requestedMultiHop.explicitNodeIds() else null
                val requestedExit = requestedHops?.second ?: if (!requestedMultiHop.enabled)
                    onlyNodeId ?: requestedSettings.userProfile.selectedNodeId.takeIf {
                        requestedSettings.userProfile.serverSelectionMode == ServerSelectionMode.SERVER && it.isNotBlank()
                    } else null
                val firstCatalog = authorized { api.ruRelayCatalog(it, device.id, vault.deviceKey, requestedExit, requestedHops?.first) }
                val foreign = firstCatalog.foreignNodes
                mutable.update { it.copy(ruForeignNodes = foreign.map { node -> node.copy(latencyMs = null) }) }
                val multiHop = resolveMultiHop(requestedSettings.advancedSettings.multiHop,
                    ruServers(foreign, state.value.servers), true)
                val settings = requestedSettings.copy(advancedSettings = requestedSettings.advancedSettings.copy(multiHop = multiHop))
                val hops = multiHop.explicitNodeIds()
                if (hops != null) check(foreign.any { it.id == hops.first }) { "Входная нода недоступна" }
                var selectedExit: RuForeignNode? = null
                var selectedCatalog: RuRelayCatalog? = null
                var catalogFailure: Exception? = null
                for (candidate in ruExits(settings, foreign, state.value.servers).filter { it.id !in excludedNodeIds && (onlyNodeId == null || it.id == onlyNodeId) }) {
                    val catalog = try {
                        if (candidate.id == requestedExit && hops?.first == requestedHops?.first) firstCatalog
                        else authorized { api.ruRelayCatalog(it, device.id, vault.deviceKey, candidate.id, hops?.first) }
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (error: Exception) {
                        if (error is BackendException && error.statusCode !in setOf(404, 503)) throw error
                        if (error !is BackendException && error !is java.io.IOException) throw error
                        catalogFailure = error
                        continue
                    }
                    val exit = catalog.foreignNodes.firstOrNull { it.id == candidate.id } ?: continue
                    if (hops != null && catalog.foreignNodes.none { it.id == hops.first }) continue
                    if (catalog.relays.any { it.available }) { selectedExit = exit; selectedCatalog = catalog; break }
                }
                val exit = selectedExit ?: throw (catalogFailure ?: IllegalStateException("Нет доступного RU-маршрута для выбранного выхода"))
                val catalog = checkNotNull(selectedCatalog)
                val network = networkSnapshot()
                val keys = vault.wireGuardKeyPair().also { privateKey = it.first }
                val (samples, key) = coroutineScope {
                    val samples = async { measureRuRelayRtt(catalog) { relay -> runInterruptible(Dispatchers.IO) {
                        network.interfaceIndex?.let { index -> windowsTcpLatency(checkNotNull(relay.probeHost), checkNotNull(relay.probePort), 2_000, index) }
                    } } }
                    authorized { token ->
                        val access = api.vpnAccess(token, device.id, vault.deviceKey)
                        check(access.canConnect) { "VPN недоступен для текущей подписки или устройства" }
                    }
                    val key = withDeviceProof(device.id) { token, nonce, signature ->
                        api.registerRuWireGuardKey(token, device.id, vault.deviceKey, nonce, signature, keys.second)
                    }
                    samples.await() to key
                }
                val requestId = java.util.UUID.randomUUID().toString()
                var session = withDeviceProof(device.id) { token, nonce, signature ->
                    api.createRuWireGuardSession(token, RuWireGuardSessionRequest(device.id, vault.deviceKey, nonce, signature,
                        requestId, key.id, exit.id, RuRelaySelection.Auto, samples,
                        entryNodeId = hops?.first, routeVersion = catalog.routeVersion))
                }
                reserved = session.identity
                stageRuRevoke(session.identity)
                val initial = session.identity
                session = authorized { api.activateRuWireGuardSession(it, device.id, vault.deviceKey, initial) }
                stageRuRevoke(session.identity)
                val ready = awaitRuWireGuardReady(session, poll = {
                    authorized { api.pollRuWireGuardSession(it, device.id, vault.deviceKey, initial) }.also { stageRuRevoke(it.identity) }
                })
                check(ready.identity.leaseExpiresAtEpochMillis == null) { "RU-сессия должна быть постоянной" }
                stageRuRevoke(ready.identity, ready.wireguard)
                Triple(ready, exit, settings)
            } ?: throw java.net.SocketTimeoutException("Время ожидания RU-подключения истекло")
            val controlPort = SingBoxRuntime.freePort(); var probePort = SingBoxRuntime.freePort()
            while (controlPort == probePort) probePort = SingBoxRuntime.freePort()
            val secret = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also { SecureRandom().nextBytes(it) })
            val policy = dnsPolicy.await()
            val config = runInterruptible(Dispatchers.IO) {
                SingBoxConfig.generateWireGuard(ready, checkNotNull(privateKey), controlPort, secret, probePort,
                    NokiBackendConfig.apiBaseUrl, settings, geositeBytes, state.value.routingRules, dnsPolicy = { policy })
            }
            val active = startRuntime(config, settings, "${exit.name} · RU WireGuard", exit.id, protectUntilVerified)
            lastRuStatusCheck = 0L
            mutable.update { it.copy(running = true, selectedServerId = exit.id, connectionLabel = active.label, connectedAtMillis = active.startedAt, connectionSettings = settings) }
            val latency = runtime.probe(active)
            mutable.update { it.copy(verified = true) }
            recordLatencySample(latency, sessionStarted = true)
            rememberConnection(active, config, settings, networkSnapshot().kind)
            startWatchdog(active, initialEvidence = true)
            transientRecoveryAttempt = 0
        } catch (error: Exception) {
            withContext(NonCancellable) {
                runCatching { stopRuntime(restoreInternet = false, retainConnection = true) }.onFailure { error.addSuppressed(it) }
                runCatching {
                    if (vault.readEncrypted("ru-revoke.dpapi") == null) reserved?.let { stageRuRevoke(it) }
                    revokeRu()
                }.onFailure { error.addSuppressed(it) }
                val active = runtime.restored()
                mutable.update { it.copy(running = active != null, verified = false,
                    connectedAtMillis = active?.startedAt, connectionLabel = active?.label.orEmpty()) }
            }
            throw error
        } finally { privateKey?.fill(0) }
    }

    private suspend fun stageRuRevoke(identity: BackendRuWireGuardIdentity, wireguard: BackendRuWireGuardConfig? = null) = ruRevokes.withLock {
        vault.writeEncrypted("ru-revoke.dpapi", ruJournal(identity).put("wireguard_hash", wireguard?.let(::wireguardHash)).toString())
    }
    private fun wireguardHash(config: BackendRuWireGuardConfig) = java.security.MessageDigest.getInstance("SHA-256")
        .digest(config.toString().toByteArray(Charsets.UTF_8)).let { Base64.getEncoder().encodeToString(it) }

    private suspend fun checkRuAuthorization() {
        if (!state.value.running || !state.value.displayedSettings.advancedSettings.ruRelayEnabled) return
        val journal = vault.readEncrypted("ru-revoke.dpapi")?.let(::JSONObject) ?: return
        if (!journal.has("wireguard_hash")) return
        val valid = try {
            val identity = ruIdentity(journal)
            val response = withTimeoutOrNull(15_000) { authorized { api.pollRuWireGuardSession(it, vault.deviceId, vault.deviceKey, identity) } } ?: return
            response is BackendRuWireGuardSession.Ready && response.identity.hasSameAuthorization(identity) &&
                response.identity.leaseExpiresAtEpochMillis == null &&
                wireguardHash(response.wireguard) == journal.getString("wireguard_hash")
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { if (BackendRetryPolicy.isTransient(error)) return else false }
        if (!valid) {
            stopRuntime(restoreInternet = false); runCatching { revokeRu() }
            mutable.update { it.copy(running = false, verified = false, networkBlocked = runtime.guardArmed(),
                message = "RU-сессия отозвана или изменена. Подключитесь снова", messageRevision = it.messageRevision + 1) }
            recordLog("ru_session_invalid", error = true)
        }
    }
    private suspend fun revokeRu(expectedSessionId: String? = null) = ruRevokes.withLock {
        val pending = vault.readEncrypted("ru-revoke.dpapi")?.let(::JSONObject) ?: return@withLock
        if (expectedSessionId != null && pending.getString("session_id") != expectedSessionId) return@withLock
        try { api.revokeRuWireGuardSession(pending.getString("session_id"), pending.getString("revoke_token")) }
        catch (error: BackendException) { if (error.statusCode != 404) throw error }
        Files.deleteIfExists(vault.directory.resolve("ru-revoke.dpapi"))
    }

    private fun recordLatencySample(latency: Long, sessionStarted: Boolean = false) = synchronized(statsLock) {
        if (sessionStarted) recordLog("connection_verified", latency = latency)
        val date = java.time.LocalDate.now().toString()
        mutable.update { old ->
            val day = old.dailyStats.firstOrNull { it.date == date } ?: DailyStats(date)
            old.copy(dailyStats = old.dailyStats.filterNot { it.date == date } + day.copy(sessions = day.sessions + if (sessionStarted) 1 else 0,
                pingSumMs = day.pingSumMs + latency, pingSamples = day.pingSamples + 1))
        }
        statsDirty = true
        flushStats(force = sessionStarted)
    }
    private fun recordMetrics(active: SingBoxRuntime.Running, metrics: SingBoxRuntime.Metrics) = synchronized(statsLock) {
        if (runtime.restored() != active || !state.value.running) return@synchronized
        val previous = statsCursor?.takeIf { it.optInt("pid") == active.pid && it.optLong("started_at") == active.startedAt }
        if (previous != null && (metrics.downloadTotal < previous.optLong("download") || metrics.uploadTotal < previous.optLong("upload")))
            return@synchronized // A concurrent, older HTTP response cannot move the durable cursor backwards.
        val rx = (metrics.downloadTotal - (previous?.optLong("download") ?: 0)).coerceAtLeast(0)
        val tx = (metrics.uploadTotal - (previous?.optLong("upload") ?: 0)).coerceAtLeast(0)
        val now = System.currentTimeMillis()
        val online = ((now - (previous?.optLong("sampled_at") ?: active.startedAt)) / 1000).coerceAtLeast(0)
        val sampledAt = java.time.Instant.ofEpochMilli(now).atZone(java.time.ZoneId.systemDefault()).toLocalDateTime()
        // The first sample is a baseline: restored cumulative totals have no known hourly distribution.
        val includeHourly = hourlyStatsRuntime == active && previous != null
        mutable.update { old -> old.copy(uploadBytesPerSecond = metrics.uploadPerSecond, downloadBytesPerSecond = metrics.downloadPerSecond,
            nokiNvCarrier = if (old.verified && old.displayedSettings.profile.proxyType == "nokinv") metrics.nokiNvCarrier else "",
            sessionBytes = metrics.uploadTotal + metrics.downloadTotal,
            dailyStats = DailyStatsStore.recordTraffic(old.dailyStats, rx, tx, online, sampledAt, includeHourly)) }
        hourlyStatsRuntime = active
        val sampleTime = (previous?.optLong("sampled_at") ?: active.startedAt) + online * 1000
        statsCursor = JSONObject().put("pid", active.pid).put("started_at", active.startedAt).put("download", metrics.downloadTotal)
            .put("upload", metrics.uploadTotal).put("sampled_at", sampleTime)
        statsDirty = true
        flushStats()
    }

    private fun flushStats(force: Boolean = false) = synchronized(statsLock) {
        val now = System.currentTimeMillis()
        if (statsDirty && (force || now - lastStatsCheckpoint >= 60_000L)) {
            vault.saveStats(state.value.dailyStats, statsCursor)
            statsDirty = false
            lastStatsCheckpoint = now
        }
    }

    @Synchronized private fun claimAutomaticLogUpload(now: Long): Boolean {
        if (now < logUploadNotBefore || now - lastLogUploadAttempt in 0 until 15 * 60_000L) return false
        lastLogUploadAttempt = now
        return true
    }

    private suspend fun stopRuntime(restoreInternet: Boolean = true, retainConnection: Boolean = false, preserveYoutube: Boolean = false) {
        if (!retainConnection) stopWatchdog()
        if (!preserveYoutube) stopYoutubeAccess(waitForCleanup = false)
        try { runtime.stop(restoreInternet) }
        catch (error: Exception) {
            if (!retainConnection) runtime.restored()?.let { startWatchdog(it, state.value.verified) }
            throw error
        }
        if (!retainConnection) {
            activeConnection = null
            Files.deleteIfExists(vault.directory.resolve("connection.dpapi"))
        }
        flushStats(force = true)
    }

    fun disconnect() {
      invalidatePreparation()
      activeAction?.cancel()
      connectionJob?.cancel()
      recoveryJob?.cancel()
      stopWatchdog()
      youtubeJob?.cancel()
      action(interruptible = false) {
        stopRuntime()
        val pendingRuId = vault.readEncrypted("ru-revoke.dpapi")?.let(::JSONObject)?.getString("session_id")
        recordLog("disconnected")
            mutable.update { it.copy(running = false, verified = false, nokiNvCarrier = "", connectedAtMillis = null, connectionLabel = "",
            uploadBytesPerSecond = null, downloadBytesPerSecond = null, sessionBytes = null, connectionSettings = null,
            message = null, messageRevision = it.messageRevision + 1) }
        if (pendingRuId != null) {
            val owner = attempt()
            scope.launch {
                try { revokeRu(pendingRuId) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) {
                    recordLog("ru_revoke_pending", error = true, cause = error)
                    if (owner != null && current(owner)) mutable.update { if (it.running || it.connecting) it else it.copy(
                        message = "VPN остановлен. Отзыв RU-сессии будет повторён после восстановления связи", messageRevision = it.messageRevision + 1) }
                }
            }
        }
        refreshClientLatencies()
        prepareConnectionInAdvance()
      }
    }

    fun logout() = action {
        avatarJob?.cancel()
        stopRuntime()
        val ruRevoked = runCatching { revokeRu() }.isSuccess
        val revoked = try {
            tokens?.refreshToken?.let { refresh -> api.revokeRefreshToken(refresh) }
            true
        } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { false }
        clearAccount()
        mutable.update { it.copy(loading = true, message = if (revoked && ruRevoked) null
            else "Вы вышли из аккаунта на этом ПК. Серверный отзыв сессии не подтверждён из-за ошибки связи", messageRevision = it.messageRevision + 1)
        }
    }

    fun exit(onStopped: suspend () -> Unit): Job {
        connectionJob?.cancel()
        recoveryJob?.cancel()
        return action {
            avatarJob?.cancel()
            stopRuntime()
            runCatching { revokeRu() }.onFailure { recordLog("ru_revoke_pending", error = true, cause = it) }
            withContext(Dispatchers.Main) { onStopped() }
            close()
        }
    }

    override fun close() {
        try { support.invalidate(); stopWatchdog(); scope.cancel(); uiScope.cancel(); flushStats(force = true) }
        finally { diagnostics.close() }
    }

    internal suspend fun closeAndJoin() {
        withContext(Dispatchers.Main) { support.invalidate() }
        scope.cancel()
        uiScope.cancel()
        // A cancelled connection may still need the operation lock for its final cleanup.
        // Wait for its jobs before taking that lock for the final checkpoint.
        scope.coroutineContext[kotlinx.coroutines.Job]?.join()
        uiScope.coroutineContext[kotlinx.coroutines.Job]?.join()
        operation.withLock {
            flushStats(force = true)
            persistConnection()
        }
        diagnostics.close()
    }

    private suspend fun syncAccount() {
        if (vault.needsHardwareRegistration && runtime.restored() == null) {
            val existing = authorized { api.bootstrap(it, vault.deviceId.ifBlank { null }, vault.deviceKey) }
                .devices.firstOrNull { it.id == vault.deviceId && it.isActive }
            if (existing?.accessRole == "owner") {
                val device = authorized { api.registerDevice(it, vault.registrationDeviceKey, existing.id,
                    existing.deviceName, vault.publicKey, vault.deviceClaims, com.noki.desktop.DesktopBuild.PLATFORM) }
                vault.acceptRegisteredDevice(device)
            }
        }
        val bootstrap = authorized { api.bootstrap(it, vault.deviceId.ifBlank { null }, vault.deviceKey) }
        val foreign = if ((state.value.settings.advancedSettings.ruRelayEnabled || state.value.connectionSettings?.advancedSettings?.ruRelayEnabled == true) &&
            bootstrap.devices.any { it.id == vault.deviceId && it.isActive })
            authorized { api.ruRelayCatalog(it, vault.deviceId, vault.deviceKey) }.foreignNodes.map { it.copy(latencyMs = null) } else emptyList()
        applyAccount(bootstrap, foreign)
    }

    private fun applyAccount(bootstrap: BootstrapPayload, foreign: List<RuForeignNode>, refreshRelated: Boolean = true) {
        val oldKey = lastNetwork?.let { preparationKeyFor(state.value.settings, it) }
        lastAccountPoll = System.currentTimeMillis()
        lastAccountSuccess = lastAccountPoll
        catalogLocations = bootstrap.locations
        val device = bootstrap.devices.firstOrNull { it.id == vault.deviceId && it.isActive }
        registered = device
        val previousLatencies = state.value.servers.mapNotNull { server ->
            server.latencyMs?.let { latency -> ServerLocationMapper.latencyTargetKey(server)?.let { it to latency } }
        }.toMap()
        val locations = ServerLocationMapper.mapLocations(bootstrap.locations, state.value.language, previousLatencies)
        val servers = locations.flatMap { it.servers }.distinctBy { it.id }
        mutable.update { old -> old.copy(user = bootstrap.user, subscription = bootstrap.subscription,
            locations = locations, ruForeignNodes = foreign.map { node -> node.copy(latencyMs = old.ruForeignNodes.firstOrNull { it.id == node.id }?.latencyMs) },
            plans = bootstrap.plans, devices = bootstrap.devices, currentDeviceId = vault.deviceId,
            currentDeviceRole = registered?.accessRole ?: "invited", selectedServerId = old.selectedServerId.ifBlank { old.settings.userProfile.selectedNodeId }
                .takeIf { id -> old.running || servers.any { it.id == id && it.isOnline } || foreign.any { it.id == id } }.orEmpty()) }
        if (oldKey != lastNetwork?.let { preparationKeyFor(state.value.settings, it) }) invalidatePreparation()
        cacheAvatar(bootstrap.user.avatarUrl)
        if (refreshRelated) {
            loadNotifications()
            refreshClientLatencies()
            prepareConnectionInAdvance()
        }
    }

    suspend fun refreshServerLatencies() = withContext(Dispatchers.IO) { refreshClientLatencies(force = true) }

    @Synchronized private fun refreshClientLatencies(force: Boolean = false) {
        if (latencyJob?.isActive == true) return
        if (!windowVisible && !force) return
        if (state.value.connecting) return
        val owner = attempt() ?: return
        val network = try { networkSnapshot() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { recordDiagnosticFailure("latency_network_read_failed", error, "vpn"); return }
        val locations = state.value.connectionLocations
        val ruRelay = state.value.settings.advancedSettings.ruRelayEnabled
        fun target(state: DesktopState) = state.connectionLocations.filter { it.isOnline }.flatMap { location ->
            if (location.servers.isEmpty()) listOf(ServerLocationMapper.latencyTargetKey(location))
            else location.servers.filter { it.isOnline }.map { ServerLocationMapper.latencyTargetKey(it) }
        }.toSet()
        val requestedTarget = target(state.value)
        if (!network.available || locations.isEmpty()) return
        val job = scope.launch(start = CoroutineStart.LAZY) {
            val ownJob = currentCoroutineContext()[Job]
            try {
                val relayTargets = if (!ruRelay) null else coroutineScope {
                    val slots = kotlinx.coroutines.sync.Semaphore(4)
                    locations.flatMap { it.servers }.filter { it.isOnline }.distinctBy { it.id }.map { server -> async {
                        val key = ServerLocationMapper.latencyTargetKey(server) ?: return@async null
                        val relays = withTimeoutOrNull(8_000) {
                            slots.withPermit {
                                try { authorized { api.ruRelayCatalog(it, vault.deviceId, vault.deviceKey, server.id) }.relays }
                                catch (cancelled: CancellationException) { throw cancelled }
                                catch (_: Exception) { emptyList() }
                            }
                        }.orEmpty()
                        key to relays.filter { it.available }.mapNotNull { relay ->
                            val host = relay.probeHost ?: return@mapNotNull null
                            val port = relay.probePort ?: return@mapNotNull null
                            host to port
                        }
                    } }.awaitAll().filterNotNull().toMap()
                }
                val samples = latencySampler(network).measure(locations, relayTargets)
                currentCoroutineContext().ensureActive()
                synchronized(this@DesktopController) {
                    if (latencyJob !== ownJob || !current(owner) || state.value.connecting ||
                        state.value.settings.advancedSettings.ruRelayEnabled != ruRelay ||
                        networkSnapshot().signature != network.signature || target(state.value) != requestedTarget) return@launch
                    val serverKeys = locations.flatMap { it.servers }.associate { it.id to ServerLocationMapper.latencyTargetKey(it) }
                    mutable.update { old ->
                        if (!current(owner) || old.connecting || old.settings.advancedSettings.ruRelayEnabled != ruRelay ||
                            target(old) != requestedTarget) old else {
                            val keepPrevious = old.running && network.signature == lastNetwork?.signature
                            val measured = if (!keepPrevious) samples else old.servers.mapNotNull { server ->
                                server.latencyMs?.let { latency -> ServerLocationMapper.latencyTargetKey(server)?.let { it to latency } }
                            }.toMap() + samples
                            old.copy(
                                locations = if (ruRelay) old.locations else old.locations.map { ServerLocationMapper.withLatencies(it, measured) },
                                ruForeignNodes = if (!ruRelay) old.ruForeignNodes else old.ruForeignNodes.map { node ->
                                    node.copy(latencyMs = serverKeys[node.id]?.let(samples::get) ?: node.latencyMs.takeIf { keepPrevious })
                                })
                        }
                    }
                    latencyMeasuredAt = System.currentTimeMillis()
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { recordDiagnosticFailure("client_latency_refresh_failed", error, "network") }
            finally {
                synchronized(this@DesktopController) { if (latencyJob === ownJob) {
                    latencyJob = null
                } }
            }
        }
        latencyJob = job
        job.start()
    }

    private suspend fun <T> authorized(block: suspend (String) -> T): T {
        val epoch = authEpoch.get()
        fun ensureAccount() { if (epoch != authEpoch.get() || tokens == null) throw CancellationException("Account changed") }
        val current = authOperation.withLock {
            ensureAccount()
            val saved = checkNotNull(tokens)
            if (saved.refreshToken != null && accessExpired(saved.accessToken)) {
                renewToken(checkNotNull(saved.refreshToken), vault.deviceId.ifBlank { null }).also {
                    ensureAccount(); tokens = it; vault.saveTokens(it)
                }
            } else saved
        }
        try { return block(current.accessToken).also { ensureAccount() } }
        catch (error: BackendException) {
            if (error.statusCode != 401) throw error
            val renewed = authOperation.withLock {
                ensureAccount()
                val latest = checkNotNull(tokens)
                if (latest.accessToken != current.accessToken) latest else {
                    val refresh = current.refreshToken ?: throw error
                    renewToken(refresh, vault.deviceId.ifBlank { null }).also {
                        ensureAccount(); tokens = it; vault.saveTokens(it)
                    }
                }
            }
            return block(renewed.accessToken).also { ensureAccount() }
        }
    }

    private suspend fun clearAccount() {
        withContext(Dispatchers.Main) { support.invalidate() }
        invalidatePreparation()
        clearCoreLogs()
      stopYoutubeAccess()
      authOperation.withLock {
        stopWatchdog()
        activeConnection = null
        Files.deleteIfExists(vault.directory.resolve("connection.dpapi"))
        avatarJob?.cancel()
        authEpoch.incrementAndGet()
        vault.saveTokens(null); tokens = null; registered = null; catalogLocations = emptyList()
        mutable.value = DesktopState(loading = true, settings = state.value.settings, dailyStats = state.value.dailyStats,
            reducedMotion = state.value.reducedMotion, earthDisabled = state.value.earthDisabled,
            autoRecoveryEnabled = state.value.autoRecoveryEnabled,
            routingRules = state.value.routingRules, networkBlocked = runtime.guardArmed())
        uiScope.launch { payments.invalidate(); credentials.invalidate() }
      }
    }

    private suspend fun renewToken(refresh: String, deviceId: String?): BackendAuthTokens =
        api.refreshAuthToken(refresh, deviceId, vault.refreshRequestId(refresh)).let {
            it.copy(refreshToken = it.refreshToken ?: refresh)
        }

    companion object {
        internal fun accessExpired(token: String, nowMillis: Long = System.currentTimeMillis()): Boolean = runCatching {
            val payload = JSONObject(Base64.getUrlDecoder().decode(token.split('.')[1]).toString(Charsets.UTF_8))
            // Expiry is a refresh hint only; the server still authenticates every request.
            payload.getLong("exp") <= nowMillis / 1000 + 30
        }.getOrDefault(false)
        internal fun validateMultiHop(hops: MultiHopSettings) {
            if (!hops.enabled) return
            for (selection in listOf(hops.entry, hops.exit)) {
                requireNotNull(selection) { "Выберите входную и выходную локации" }
                when (selection.kind) {
                    ServerSelectionMode.COUNTRY -> require(selection.countryCode?.matches(Regex("[A-Za-z]{2}")) == true) { "Выберите страну" }
                    ServerSelectionMode.SERVER -> require(java.util.UUID.fromString(selection.nodeId).toString().equals(selection.nodeId, true)) { "Некорректный сервер" }
                    ServerSelectionMode.AUTO -> error("Выберите страну или сервер для каждого шага")
                }
            }
            if (hops.entry?.kind == ServerSelectionMode.SERVER && hops.exit?.kind == ServerSelectionMode.SERVER) hops.explicitNodeIds()
        }

        internal fun multiHopServers(selection: HopSelection?, servers: List<VpnServer>, entry: Boolean, ruRelay: Boolean): List<VpnServer> =
            servers.filter { server ->
                server.isOnline && server.weight > 0 && (ruRelay || if (entry) server.multiHopEntryAvailable else server.multiHopExitAvailable) &&
                    when (selection?.kind) {
                        ServerSelectionMode.COUNTRY -> server.countryCode.equals(selection.countryCode, true)
                        ServerSelectionMode.SERVER -> server.id == selection.nodeId
                        else -> true
                    }
            }

        internal fun resolveMultiHop(hops: MultiHopSettings, servers: List<VpnServer>, ruRelay: Boolean,
            random: () -> Double = { kotlin.random.Random.nextDouble() }): MultiHopSettings {
            validateMultiHop(hops)
            if (!hops.enabled) return hops
            val exits = multiHopServers(hops.exit, servers, false, ruRelay)
            val entries = multiHopServers(hops.entry, servers, true, ruRelay).filter { entry -> exits.any { it.id != entry.id } }
            fun choose(candidates: List<VpnServer>) = EndpointRankingPolicy.rankServers(candidates, UserProfile(serverSelectionMode = ServerSelectionMode.AUTO), random = random).firstOrNull()
                ?: error("Нет доступной пары серверов для выбранных локаций")
            val entry = choose(entries)
            val exit = choose(exits.filter { it.id != entry.id })
            return hops.copy(entry = HopSelection(ServerSelectionMode.SERVER, nodeId = entry.id),
                exit = HopSelection(ServerSelectionMode.SERVER, nodeId = exit.id)).also { it.explicitNodeIds() }
        }

        internal fun ruExits(settings: StoredSettings, nodes: List<RuForeignNode>, servers: List<VpnServer> = emptyList(),
            random: () -> Double = { kotlin.random.Random.nextDouble() }): List<RuForeignNode> {
            val hops = settings.advancedSettings.multiHop.explicitNodeIds()
            val selected = settings.userProfile
            val eligible = nodes.filter { node ->
                if (hops != null) node.id == hops.second else when (selected.serverSelectionMode) {
                    ServerSelectionMode.AUTO -> true
                    ServerSelectionMode.COUNTRY -> node.countryCode.equals(selected.selectedCountryCode, true)
                    ServerSelectionMode.SERVER -> node.id == selected.selectedNodeId
                }
            }
            val ranked = ruServers(eligible, servers)
            val scope = if (hops == null) selected else selected.copy(serverSelectionMode = ServerSelectionMode.SERVER, selectedNodeId = hops.second)
            val byId = eligible.associateBy { it.id }
            return EndpointRankingPolicy.rankServers(ranked, scope, random = random).mapNotNull { byId[it.id] }
        }

        private fun ruServers(nodes: List<RuForeignNode>, servers: List<VpnServer>): List<VpnServer> {
            val physical = servers.associateBy { it.id }
            return nodes.map { node ->
                physical[node.id]?.copy(latencyMs = node.latencyMs ?: physical[node.id]?.latencyMs)
                    ?: VpnServer(node.id, node.name, node.countryCode, node.countryCode, "", null, true, latencyMs = node.latencyMs)
            }
        }

        internal fun ruJournal(identity: BackendRuWireGuardIdentity): JSONObject = JSONObject()
            .put("session_id", identity.sessionId).put("revoke_token", requireNotNull(identity.revokeToken))
            .put("lease_expires_at", identity.leaseExpiresAtEpochMillis ?: JSONObject.NULL).put("request_id", identity.requestId)
            .put("generation", identity.generation).put("public_key_id", identity.publicKeyId).put("relay_node_id", identity.relayNodeId)
            .put("exit_node_id", identity.exitNodeId).put("route_version", identity.routeVersion).put("route_id", identity.routeId)
            .put("route_generation", identity.routeGeneration).put("entry_node_id", identity.entryNodeId ?: JSONObject.NULL)
            .put("relay_selection", when (val selection = identity.relaySelection) {
                RuRelaySelection.Auto -> "auto"
                is RuRelaySelection.Node -> selection.nodeId
                RuRelaySelection.Off -> error("ru_relay_disabled")
            })

        internal fun ruIdentity(journal: JSONObject) = BackendRuWireGuardIdentity(
            requestId = journal.getString("request_id"), sessionId = journal.getString("session_id"), generation = journal.getInt("generation"),
            publicKeyId = journal.getString("public_key_id"), relaySelection = journal.getString("relay_selection").let {
                if (it == "auto") RuRelaySelection.Auto else RuRelaySelection.Node(it)
            }, relayNodeId = journal.getString("relay_node_id"), exitNodeId = journal.getString("exit_node_id"),
            routeVersion = journal.getInt("route_version"), routeId = journal.getString("route_id"), routeGeneration = journal.getInt("route_generation"),
            entryNodeId = if (journal.isNull("entry_node_id")) null else journal.getString("entry_node_id"),
            leaseExpiresAtEpochMillis = if (journal.isNull("lease_expires_at")) null else journal.getLong("lease_expires_at"),
            revokeToken = journal.getString("revoke_token"),
        )
    }

    private fun action(auth: Boolean = false, interruptible: Boolean = true, block: suspend () -> Unit): Job {
        val job = scope.launch {
          operation.withLock {
            val owner = currentCoroutineContext().job
            if (interruptible) activeAction = owner
            mutable.update { it.copy(loading = true, message = null, messageRevision = it.messageRevision + 1) }
            try { block() } catch (timeout: TimeoutCancellationException) {
                if (!auth) throw timeout
                mutable.update { it.copy(message = "Время ожидания входа истекло. Начните вход через сайт заново.", messageRevision = it.messageRevision + 1) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                val running = runtime.restored()
                val backendError = error as? BackendException ?: error.cause as? BackendException
                val message = when (error) {
                    is BackendException -> if (auth) AppErrorMapper.readableAuthError(state.value.language, error)
                        else AppErrorMapper.readableNetworkError(state.value.language, error)
                    is java.io.IOException -> AppErrorMapper.readableNetworkError(state.value.language, error)
                    else -> if (error.message in listOf("avatar_file_too_large", "avatar_open_failed", "avatar_dimensions_too_large", "avatar_format_unsupported")) avatarFileError(state.value.language, error)
                    else if (error.message in listOf("multihop_selector_missing", "multihop_selector_invalid", "ru_route_identity_invalid", "ru_wireguard_uuid_invalid"))
                        "Для мультихопа выберите две разные конкретные ноды"
                    else if (backendError != null && error.cause === backendError) error.message
                        ?: AppErrorMapper.readableNetworkError(state.value.language, backendError)
                    else error.message?.takeIf { !it.contains("http", true) }
                        ?: AppErrorMapper.readableNetworkError(state.value.language, error)
                }
                recordLog("action_failed", error = true, cause = error, category = if (auth) "auth" else "app")
                mutable.update { it.copy(message = message, messageRevision = it.messageRevision + 1, running = running != null,
                    verified = it.verified && running != null,
                    connectedAtMillis = running?.startedAt, connectionLabel = running?.label.orEmpty()) }
                if (backendError?.statusCode == 401) {
                    runCatching { stopRuntime(restoreInternet = false); revokeRu() }
                    clearAccount()
                    mutable.update { it.copy(message = message, messageRevision = it.messageRevision + 1, running = runtime.restored() != null) }
                }
            } finally {
              try {
                val blocked = runCatching { runtime.guardArmed() }.getOrDefault(true)
                mutable.update { it.copy(loading = false, websiteLoginPending = false, connecting = false, networkBlocked = blocked) }
              } finally {
                if (activeAction === owner) activeAction = null
              }
            }
          }
        }
        if (auth) websiteLoginJob = job
        return job
    }
}
