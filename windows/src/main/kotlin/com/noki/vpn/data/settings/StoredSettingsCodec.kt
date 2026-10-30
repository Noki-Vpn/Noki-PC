package com.noki.vpn.data

import org.json.JSONArray
import org.json.JSONObject

internal class StoredSettingsCodec(
    private val defaults: () -> StoredSettings,
) {
    fun decode(raw: String?): StoredSettings {
        if (raw.isNullOrBlank()) return defaults()
        return runCatching {
            val json = JSONObject(raw)
            val stored = decode(json).let { value ->
                if (value.profile.proxyType.equals("nokinv", true) && value.profile.transportMode.trim().lowercase() == "tcp")
                    value.copy(profile = value.profile.copy(transportMode = "quic")) else value
            }
            val migrated = stored.copy(advancedSettings = stored.advancedSettings.copy(
                gamingModeEnabled = stored.advancedSettings.gamingModeEnabled && !stored.advancedSettings.ruRelayEnabled,
                manualEndpointGroupKey = EndpointGroupPolicy.normalizedGroupKey(stored.advancedSettings.manualEndpointGroupKey),
            ))
            val validated = if (migrated.profile.proxyType.equals("nokinv", true) && !EndpointSecurityPolicy.isAllowedProfile(migrated.profile))
                migrated.copy(profile = VlessProfile()) else migrated
            if (json.optInt("russianDirectDefaultsVersion") >= 1) validated else validated.copy(selectedPackages = DefaultStoredSettingsFactory.updatedDefaultPackages(
                stored.selectedPackages, stored.filterMode,
            ))
        }.getOrElse { defaults() }
    }

    fun encode(settings: StoredSettings): String = JSONObject()
        .put("russianDirectDefaultsVersion", 1)
        .put("desktop_ru_relay_reset_version", 1)
        .put("remark", settings.profile.remark)
        .put("endpointCode", settings.profile.endpointCode)
        .put("proxyType", settings.profile.proxyType)
        .put("transport", settings.profile.transport)
        .put("transportMode", settings.profile.transportMode)
        .put("host", settings.profile.host)
        .put("port", settings.profile.port)
        .put("uuid", settings.profile.uuid)
        .put("vpnUsername", settings.profile.vpnUsername)
        .put("nokinvToken", settings.profile.nokinvToken)
        .put("nokinvCredentialGeneration", settings.profile.nokinvCredentialGeneration)
        .put("nokinvMode", settings.profile.nokinvMode)
        .put("flow", settings.profile.flow)
        .put("security", settings.profile.security)
        .put("fingerprint", settings.profile.fingerprint)
        .put("serverName", settings.profile.serverName)
        .put("requestHost", settings.profile.requestHost)
        .put("path", settings.profile.path)
        .put("alpn", settings.profile.alpn)
        .put("allowInsecure", settings.profile.allowInsecure)
        .put("enableMux", settings.profile.enableMux)
        .put("randomUserAgent", settings.profile.randomUserAgent)
        .put("publicKey", settings.profile.publicKey)
        .put("shortId", settings.profile.shortId)
        .put("spiderX", settings.profile.spiderX)
        .put("multiHopRuntime", settings.profile.multiHop?.let { route -> JSONObject()
            .put("version", route.version).put("selection", encodeMultiHopSettings(route.selection))
            .put("entryNodeId", route.entryNodeId).put("exitNodeId", route.exitNodeId)
            .put("entryIp", route.entryIp).put("policyHash", route.policyHash) })
        .put("filterMode", settings.filterMode.name)
        .put("selectedPackages", JSONArray(settings.selectedPackages.sorted()))
        .put("backendUserId", settings.userProfile.backendUserId)
        .put("username", settings.userProfile.username)
        .put("email", settings.userProfile.email)
        .put("avatarUri", settings.userProfile.avatarUri ?: "")
        .put("hasRealEmail", settings.userProfile.hasRealEmail)
        .put("hasPassword", settings.userProfile.hasPassword)
        .put("telegramLinked", settings.userProfile.telegramLinked)
        .put("selectedPlanCode", settings.userProfile.selectedPlanCode.code)
        .put("selectedPlanCodeRaw", settings.userProfile.selectedPlanCodeRaw)
        .put("selectedPlanName", settings.userProfile.selectedPlanName ?: "")
        .put("selectedPlanTier", settings.userProfile.selectedPlanTier ?: "")
        .put("selectedPlanBadgeColor", settings.userProfile.selectedPlanBadgeColor ?: "")
        .put("selectedCountryCode", settings.userProfile.selectedCountryCode)
        .put("serverSelectionMode", settings.userProfile.serverSelectionMode.name)
        .put("selectedNodeId", settings.userProfile.selectedNodeId)
        .put("selectedServerCode", settings.userProfile.selectedServerCode)
        .put("actualCountryCode", settings.userProfile.actualCountryCode)
        .put("trafficUsedGb", settings.userProfile.trafficUsedGb)
        .put("trafficLimitGb", settings.userProfile.trafficLimitGb)
        .put("subscriptionExpiresAt", settings.userProfile.subscriptionExpiresAt ?: "")
        .put("subscriptionStatus", settings.userProfile.subscriptionStatus)
        .put("language", settings.personalizationSettings.language.tag)
        .put("accentPalette", settings.personalizationSettings.accentPalette.key)
        .put("homeLayoutVariant", settings.personalizationSettings.homeLayoutVariant.name)
        .put("glassMode", settings.personalizationSettings.glassMode.name)
        .put("biometricEnabled", settings.securitySettings.biometricEnabled)
        .put("loginAlertsEnabled", settings.securitySettings.loginAlertsEnabled)
        .put("protectNewDevices", settings.securitySettings.protectNewDevices)
        .put("protocol", settings.advancedSettings.protocol.name)
        .put("ruRelayEnabled", settings.advancedSettings.ruRelayEnabled)
        .put("gamingModeEnabled", settings.advancedSettings.gamingModeEnabled)
        .put("youtubeAdBlockEnabled", settings.advancedSettings.youtubeAdBlockEnabled)
        .put("ruRelaySelectionRevision", settings.ruRelaySelectionRevision)
        .put("endpointSelectionMode", settings.advancedSettings.endpointSelectionMode.name)
        .put("manualEndpointCode", settings.advancedSettings.manualEndpointCode)
        .put("manualEndpointGroupKey", settings.advancedSettings.manualEndpointGroupKey)
        .put("connectionLogsEnabled", settings.advancedSettings.connectionLogsEnabled)
        .put("errorLogsEnabled", settings.advancedSettings.errorLogsEnabled)
        .put("anonymousLogsEnabled", settings.advancedSettings.anonymousLogsEnabled)
        .put("multiHopSettings", encodeMultiHopSettings(settings.advancedSettings.multiHop))
        .put("killSwitchEnabled", settings.advancedSettings.killSwitchEnabled)
        .put("launchAtStartup", settings.advancedSettings.launchAtStartup)
        .put("autoConnectOnLaunch", settings.advancedSettings.autoConnectOnLaunch)
        .put("showLocalCoreLogs", settings.advancedSettings.showLocalCoreLogs)
        .put("alwaysOnDomains", JSONArray(settings.advancedSettings.alwaysOnDomains))
        .put("bypassDomains", JSONArray(settings.advancedSettings.bypassDomains))
        .put("endpointOptions", encodeEndpointOptions(settings.endpointOptions))
        .put("isAuthenticated", settings.isAuthenticated)
        .put("backendAccessToken", settings.backendAccessToken ?: "")
        .put("backendRefreshToken", settings.backendRefreshToken ?: "")
        .put("backendRefreshRequestId", settings.backendRefreshRequestId ?: "")
        .put("backendAccessTokenExpiresInSeconds", settings.backendAccessTokenExpiresInSeconds ?: JSONObject.NULL)
        .put("backendRefreshExpiresAt", settings.backendRefreshExpiresAt ?: "")
        .put("backendDeviceKey", settings.backendDeviceKey)
        .put("backendDeviceId", settings.backendDeviceId)
        .put("backendDeviceAccessRole", settings.backendDeviceAccessRole)
        .toString()

    private fun decode(json: JSONObject) = StoredSettings(
        profile = VlessProfile(
            remark = json.optString("remark", "Noki VPN"),
            endpointCode = json.optString("endpointCode"),
            proxyType = json.optString("proxyType", "vless"),
            transport = json.optString("transport", "tcp"),
            transportMode = json.optString("transportMode"),
            host = json.optString("host"),
            port = json.optString("port", "443"),
            uuid = json.optString("uuid"),
            vpnUsername = json.optString("vpnUsername"),
            nokinvToken = json.opt("nokinvToken") as? String ?: "",
            nokinvCredentialGeneration = json.opt("nokinvCredentialGeneration") as? Int ?: 0,
            nokinvMode = json.optString("nokinvMode", "balanced"),
            flow = json.optString("flow"),
            security = json.optString("security", "reality"),
            fingerprint = json.optString("fingerprint", "chrome"),
            serverName = json.optString("serverName"),
            requestHost = json.optString("requestHost"),
            path = json.optString("path"),
            alpn = json.optString("alpn"),
            allowInsecure = json.optBoolean("allowInsecure", false),
            enableMux = json.optBoolean("enableMux", false),
            randomUserAgent = json.optBoolean("randomUserAgent", false),
            publicKey = json.optString("publicKey"),
            shortId = json.optString("shortId"),
            spiderX = json.optString("spiderX", "/"),
            multiHop = json.optJSONObject("multiHopRuntime")?.let { route -> BackendMultiHopSession(
                selection = decodeMultiHopSettings(route.getJSONObject("selection")),
                entryNodeId = route.getString("entryNodeId"), exitNodeId = route.getString("exitNodeId"),
                entryIp = route.optString("entryIp"), policyHash = route.getString("policyHash"),
                version = route.getInt("version"),
            ) },
        ),
        filterMode = runCatching {
            AppFilterMode.valueOf(json.optString("filterMode", AppFilterMode.ALL_APPS.name))
        }.getOrElse { AppFilterMode.ALL_APPS },
        selectedPackages = json.optJSONArray("selectedPackages").toStringSet(),
        userProfile = UserProfile(
            backendUserId = json.optString("backendUserId"),
            username = json.optString("username"),
            email = json.optString("email"),
            avatarUri = json.optString("avatarUri").takeIf { it.isNotBlank() },
            hasRealEmail = json.optBoolean("hasRealEmail", true),
            hasPassword = json.optBoolean("hasPassword", true),
            telegramLinked = json.optBoolean("telegramLinked", false),
            selectedPlanCode = PlanCode.fromCode(json.optString("selectedPlanCode", PlanCode.FREE.code)),
            selectedPlanCodeRaw = json.optString(
                "selectedPlanCodeRaw",
                json.optString("selectedPlanCode", PlanCode.FREE.code),
            ).ifBlank { PlanCode.FREE.code },
            selectedPlanName = json.optString("selectedPlanName").takeIf { it.isNotBlank() },
            selectedPlanTier = json.optString("selectedPlanTier").takeIf { it.isNotBlank() },
            selectedPlanBadgeColor = json.optString("selectedPlanBadgeColor").takeIf { it.isNotBlank() },
            selectedCountryCode = json.optString("selectedCountryCode"),
            serverSelectionMode = runCatching {
                ServerSelectionMode.valueOf(json.optString("serverSelectionMode", ServerSelectionMode.COUNTRY.name))
            }.getOrDefault(ServerSelectionMode.COUNTRY),
            selectedNodeId = json.optString("selectedNodeId"),
            actualCountryCode = json.optString("actualCountryCode"),
            selectedServerCode = json.optString("selectedServerCode", "lv"),
            trafficUsedGb = json.optNullableDouble("trafficUsedGb"),
            trafficLimitGb = json.optNullableDouble("trafficLimitGb"),
            subscriptionExpiresAt = json.optString("subscriptionExpiresAt").takeIf { it.isNotBlank() },
            subscriptionStatus = json.optString("subscriptionStatus", "active"),
        ),
        personalizationSettings = PersonalizationSettings(
            language = AppLanguage.fromTag(json.optString("language")),
            accentPalette = AccentPalette.fromKey(json.optString("accentPalette")),
            homeLayoutVariant = runCatching {
                HomeLayoutVariant.valueOf(json.optString("homeLayoutVariant", HomeLayoutVariant.MAIN.name))
            }.getOrElse { HomeLayoutVariant.MAIN },
            glassMode = if (json.has("glassMode")) {
                when (json.optString("glassMode")) {
                    GlassMode.SIMPLE.name -> GlassMode.SIMPLE
                    GlassMode.FULL.name, "SIMPLE_ANIMATION" -> GlassMode.FULL
                    else -> GlassMode.FULL
                }
            } else if (json.optBoolean("simpleModeEnabled", false)) {
                GlassMode.FULL
            } else {
                GlassMode.FULL
            },
        ),
        securitySettings = SecuritySettings(
            biometricEnabled = json.optBoolean("biometricEnabled", true),
            loginAlertsEnabled = json.optBoolean("loginAlertsEnabled", true),
            protectNewDevices = json.optBoolean("protectNewDevices", true),
        ),
        advancedSettings = AdvancedSettings(
            protocol = runCatching {
                VpnProtocol.valueOf(json.optString("protocol", VpnProtocol.AUTO.name))
            }.getOrElse { VpnProtocol.AUTO }.takeUnless { it == VpnProtocol.WIREGUARD } ?: VpnProtocol.AUTO,
            ruRelayEnabled = json.optBoolean("ruRelayEnabled", defaults().advancedSettings.ruRelayEnabled),
            gamingModeEnabled = json.optBoolean("gamingModeEnabled", false),
            youtubeAdBlockEnabled = json.optBoolean("youtubeAdBlockEnabled", false),
            endpointSelectionMode = runCatching {
                EndpointSelectionMode.valueOf(
                    json.optString("endpointSelectionMode", EndpointSelectionMode.AUTO.name),
                )
            }.getOrElse { EndpointSelectionMode.AUTO },
            manualEndpointCode = json.optString("manualEndpointCode"),
            manualEndpointGroupKey = json.optString("manualEndpointGroupKey"),
            connectionLogsEnabled = json.optBoolean("connectionLogsEnabled", true),
            errorLogsEnabled = json.optBoolean("errorLogsEnabled", true),
            anonymousLogsEnabled = json.optBoolean("anonymousLogsEnabled", true),
            multiHop = decodeMultiHopSettings(json.optJSONObject("multiHopSettings")),
            killSwitchEnabled = json.optBoolean("killSwitchEnabled", false),
            launchAtStartup = json.optBoolean("launchAtStartup", false),
            autoConnectOnLaunch = json.optBoolean("autoConnectOnLaunch", false),
            showLocalCoreLogs = json.optBoolean("showLocalCoreLogs", false),
            alwaysOnDomains = DefaultStoredSettingsFactory.normalizeAlwaysOnDomains(
                json.optJSONArray("alwaysOnDomains").toStringList(),
            ),
            bypassDomains = DefaultStoredSettingsFactory.normalizeBypassDomains(
                json.optJSONArray("bypassDomains").toStringList(),
            ),
        ),
        endpointOptions = json.optJSONArray("endpointOptions").toEndpointOptions(),
        isAuthenticated = json.optBoolean("isAuthenticated", false),
        backendAccessToken = json.optString("backendAccessToken").takeIf { it.isNotBlank() },
        backendRefreshToken = json.optString("backendRefreshToken").takeIf { it.isNotBlank() },
        backendRefreshRequestId = json.optString("backendRefreshRequestId").takeIf { it.isNotBlank() },
        backendAccessTokenExpiresInSeconds = json.optLongOrNull("backendAccessTokenExpiresInSeconds"),
        backendRefreshExpiresAt = json.optString("backendRefreshExpiresAt").takeIf { it.isNotBlank() },
        backendDeviceKey = json.optString("backendDeviceKey"),
        backendDeviceId = json.optString("backendDeviceId"),
        backendDeviceAccessRole = json.optString("backendDeviceAccessRole", "owner").ifBlank { "owner" },
        ruRelaySelectionRevision = json.optLong("ruRelaySelectionRevision", 0L).coerceAtLeast(0L),
    )

    private fun encodeMultiHopSettings(settings: MultiHopSettings): JSONObject = JSONObject()
        .put("enabled", settings.enabled)
        .put("entry", settings.entry?.let(::encodeHop) ?: JSONObject.NULL)
        .put("exit", settings.exit?.let(::encodeHop) ?: JSONObject.NULL)

    private fun encodeHop(hop: HopSelection): JSONObject = JSONObject()
        .put("kind", hop.kind.name)
        .put("countryCode", hop.countryCode)
        .put("nodeId", hop.nodeId)

    private fun decodeMultiHopSettings(json: JSONObject?): MultiHopSettings {
        if (json == null) return MultiHopSettings()
        fun hop(value: JSONObject?): HopSelection? {
            if (value == null) return null
            val kind = runCatching { ServerSelectionMode.valueOf(value.getString("kind")) }.getOrNull()
                ?: return null
            if (kind != ServerSelectionMode.SERVER) return null
            return HopSelection(kind, value.optString("countryCode").takeIf(String::isNotBlank),
                value.optString("nodeId").takeIf(String::isNotBlank))
        }
        return MultiHopSettings(json.optBoolean("enabled", false),
            hop(json.optJSONObject("entry")), hop(json.optJSONObject("exit")))
    }

    private fun encodeEndpointOptions(options: List<VpnEndpointOption>) = JSONArray().apply {
        options.forEach { option ->
            put(JSONObject()
                .put("code", option.code)
                .put("nodeId", option.nodeId ?: "")
                .put("label", option.label)
                .put("locationCode", option.locationCode)
                .put("locationName", option.locationName)
                .put("host", option.host)
                .put("port", option.port)
                .put("proxyType", option.proxyType)
                .put("transport", option.transport)
                .put("transportMode", option.transportMode)
                .put("security", option.security)
                .put("canaryOnly", option.canaryOnly)
                .put("priority", option.priority)
                .put("endpointGroupKey", option.endpointGroupKey)
                .put("groupSize", option.groupSize)
                .put("groupPorts", JSONArray(option.groupPorts)))
        }
    }
}

private fun JSONArray?.toStringSet(): Set<String> {
    if (this == null) return emptySet()
    return buildSet {
        for (index in 0 until length()) {
            optString(index).takeIf { it.isNotBlank() }?.let(::add)
        }
    }
}

private fun JSONArray?.toStringList(): List<String> {
    if (this == null) return emptyList()
    return buildList {
        for (index in 0 until length()) {
            optString(index).takeIf { it.isNotBlank() }?.let(::add)
        }
    }
}

private fun JSONArray?.toEndpointOptions(): List<VpnEndpointOption> {
    if (this == null) return emptyList()
    return buildList {
        for (index in 0 until length()) {
            val json = optJSONObject(index) ?: continue
            val code = json.optString("code").takeIf { it.isNotBlank() } ?: continue
            add(VpnEndpointOption(
                code = code,
                nodeId = json.optString("nodeId").takeIf { it.isNotBlank() },
                label = json.optString("label"),
                locationCode = json.optString("locationCode"),
                locationName = json.optString("locationName"),
                host = json.optString("host"),
                port = json.optInt("port", 443),
                proxyType = json.optString("proxyType", "vless"),
                transport = json.optString("transport", "tcp"),
                transportMode = json.optString("transportMode"),
                security = json.optString("security", "tls"),
                canaryOnly = json.optBoolean("canaryOnly", false),
                priority = json.optInt("priority", 100),
                endpointGroupKey = json.optString("endpointGroupKey"),
                groupSize = json.optInt("groupSize", 1).coerceAtLeast(1),
                groupPorts = json.optJSONArray("groupPorts").toIntList(),
            ))
        }
    }
}

private fun JSONArray?.toIntList(): List<Int> {
    if (this == null) return emptyList()
    return buildList {
        for (index in 0 until length()) {
            val value = optInt(index, -1)
            if (value > 0) add(value)
        }
    }
}

private fun JSONObject.optNullableDouble(name: String): Double? =
    if (!has(name) || isNull(name)) null else optDouble(name).takeIf { it.isFinite() }

private fun JSONObject.optLongOrNull(name: String): Long? =
    if (!has(name) || isNull(name)) null else runCatching { getLong(name) }.getOrNull()
