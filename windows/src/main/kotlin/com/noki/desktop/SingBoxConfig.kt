package com.noki.desktop

import com.noki.vpn.data.*
import com.noki.vpn.vpn.GeositeAsset
import com.noki.vpn.vpn.VpnConfigFactory
import com.noki.vpn.vpn.YoutubeConnectionConfig
import org.json.JSONArray
import org.json.JSONObject
import java.net.InetAddress
import java.net.URI
import java.util.Base64
import java.util.Locale
import java.util.UUID

/** Shared native VPN policy with Windows TUN, local control and process routing. */
object SingBoxConfig {
    const val DEFAULT_PROBE_PORT = 20808
    private val fingerprints = setOf("chrome", "firefox", "edge", "safari", "360", "qq", "ios", "android", "random", "randomized")
    private val hostnameLabel = Regex("[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?")

    fun bundledGeosite(): ByteArray = checkNotNull(SingBoxConfig::class.java.getResourceAsStream("/noki/vpn/geosite.dat")) {
        "Bundled geosite is missing"
    }.use { it.readBytes() }

    fun supportedCandidates(session: BackendVpnSession): List<BackendEndpointCandidate> {
        if (!session.canConnect) return emptyList()
        return session.endpointCandidates.ifEmpty {
            if (session.multiHop == null) listOf(candidateFromSession(session)) else emptyList()
        }.filter { candidate -> runCatching {
            validate(session, candidate)
            val profile = EndpointSelector.profileFromCandidate(session, candidate)
            session.multiHop?.let { require(VpnProfileValidator.isUsable(profile, AdvancedSettings(multiHop = it.selection))) }
            VpnConfigFactory.buildProbe(profile)
        }.isSuccess }
    }

    internal fun generate(session: BackendVpnSession, candidate: BackendEndpointCandidate, controlPort: Int, controlSecret: String,
                  probePort: Int = DEFAULT_PROBE_PORT, apiOrigin: String? = null, settings: StoredSettings? = null,
                  geositeBytes: ByteArray = bundledGeosite(), routingRules: List<DesktopRoutingRule> = emptyList(),
                  dnsPolicy: () -> WindowsDnsPolicy = ::windowsDnsPolicy): JSONObject {
        val current = settings ?: DesktopState().settings
        val supported = supportedCandidates(session)
        val allowed = if (!current.advancedSettings.gamingModeEnabled && current.advancedSettings.endpointSelectionMode == EndpointSelectionMode.MANUAL)
            EndpointGroupPolicy.manualCandidates(supported, current.advancedSettings) else supported
        require(candidate in allowed) { "sing_box_endpoint_unsupported" }
        val profile = EndpointSelector.profileFromCandidate(session, candidate).copy(
            nokinvMode = if (current.advancedSettings.gamingModeEnabled) "gaming" else "balanced")
        val config = JSONObject(VpnConfigFactory.build(profile, current.advancedSettings, geositeBytes))
        return windowsConfig(config, controlPort, controlSecret, probePort, apiOrigin, current, geositeBytes, routingRules, dnsPolicy)
    }

    internal fun generateWireGuard(ready: BackendRuWireGuardSession.Ready, privateKey: ByteArray, controlPort: Int, secret: String,
                          probePort: Int, apiOrigin: String, settings: StoredSettings, geositeBytes: ByteArray = bundledGeosite(),
                          routingRules: List<DesktopRoutingRule> = emptyList(), dnsPolicy: () -> WindowsDnsPolicy = ::windowsDnsPolicy): JSONObject {
        val expectedHops = settings.advancedSettings.multiHop.explicitNodeIds()
        require(ready.identity.routeVersion == 1 && ready.identity.leaseExpiresAtEpochMillis == null &&
            ready.identity.exitNodeId == (expectedHops?.second ?: ready.identity.exitNodeId) &&
            ready.identity.entryNodeId == expectedHops?.first) { "ru_route_mismatch" }
        val config = JSONObject(VpnConfigFactory.buildWireGuard(ready.wireguard, privateKey, settings.advancedSettings, geositeBytes))
        return windowsConfig(config, controlPort, secret, probePort, apiOrigin, settings, geositeBytes, routingRules, dnsPolicy)
    }

    fun withYoutube(baseConfig: JSONObject, settings: StoredSettings, connection: YoutubeConnectionConfig?,
                    geositeBytes: ByteArray? = null): JSONObject {
        if (!settings.advancedSettings.youtubeAdBlockEnabled) return JSONObject(baseConfig.toString())
        val config = VpnConfigFactory.withYoutube(baseConfig, connection, geositeBytes ?: bundledGeosite())
        val youtubeMatch = JSONObject(config.getJSONObject("dns").getJSONArray("rules").getJSONObject(0).toString()).apply { remove("server") }
        // YouTube inserts a protected resolver before the bypass rules.
        config.getJSONObject("dns").getJSONArray("rules").let { original ->
            config.getJSONObject("dns").put("rules", JSONArray().put(JSONObject().put("type", "logical").put("mode", "and")
                .put("rules", JSONArray().put(youtubeMatch)
                    .put(JSONObject().put("query_type", "AAAA"))).put("action", "predefined").put("rcode", "NOERROR"))
                .apply { for (index in 0 until original.length()) put(original.getJSONObject(index)) })
        }
        applicationBypassRule(settings)?.let { applications ->
            val rules = config.getJSONObject("route").getJSONArray("rules")
            require((0 until rules.length()).count { rules.getJSONObject(it).similar(applications) } == 1) { "youtube_app_config_mismatch" }
            val eligible = JSONObject(applications.toString()).apply { remove("action"); remove("outbound"); put("invert", true) }
            // Skip YouTube for bypassed apps without moving them ahead of explicit protected rules.
            for (index in 0 until rules.length()) {
                val match = rules.getJSONObject(index).optJSONArray("rules") ?: continue
                if (match.length() == 3 && match.getJSONObject(1).similar(youtubeMatch)) match.put(eligible)
            }
            val dnsEligible = JSONObject().put("type", "logical").put("mode", "and")
                .put("rules", JSONArray().put(applicationBypassMatch(settings))).put("invert", true)
            val dnsMatch = JSONObject().put("type", "logical").put("mode", "and")
                .put("rules", JSONArray().put(youtubeMatch).put(dnsEligible))
            val dnsRules = config.getJSONObject("dns").getJSONArray("rules")
            dnsRules.getJSONObject(0).getJSONArray("rules").put(0, dnsMatch)
            dnsRules.put(1, JSONObject(dnsMatch.toString()).put("server", dnsRules.getJSONObject(1).getString("server")))
        }
        return config
    }

    private fun windowsConfig(config: JSONObject, controlPort: Int, secret: String, probePort: Int, apiOrigin: String?,
                              settings: StoredSettings, geositeBytes: ByteArray, routingRules: List<DesktopRoutingRule>,
                              dnsPolicy: () -> WindowsDnsPolicy): JSONObject {
        require(controlPort in 1..65535 && probePort in 1..65535 && controlPort != probePort) { "invalid_local_ports" }
        require(secret.length in 32..256 && secret.all { it.code in 33..126 }) { "invalid_control_secret" }
        val api = apiOrigin?.let {
            URI(it).also { uri -> require(uri.scheme == "https" && uri.userInfo == null && uri.fragment == null &&
                uri.host != null && validHost(uri.host) && (uri.port == -1 || uri.port in 1..65535)) { "invalid_api_origin" } }
        }
        config.getJSONArray("inbounds").getJSONObject(0).apply {
            put("interface_name", "Noki").put("address", JSONArray().put("172.19.0.1/30"))
            put("auto_route", true).put("strict_route", true)
            put("dns_mode", "hijack").put("dns_address", JSONArray().put("172.19.0.2"))
        }
        config.getJSONArray("inbounds").put(JSONObject().put("type", "mixed").put("tag", "probe")
            .put("listen", "127.0.0.1").put("listen_port", probePort))
        config.put("experimental", JSONObject().put("clash_api", JSONObject()
            .put("external_controller", "127.0.0.1:$controlPort").put("secret", secret)
            .put("access_control_allow_origin", JSONArray().put("http://127.0.0.1"))))
        val route = config.getJSONObject("route")
        val rules = JSONArray()
        config.getJSONObject("dns").put("rules", JSONArray())
        api?.let {
            val dnsRules = config.getJSONObject("dns").getJSONArray("rules")
            config.getJSONObject("dns").put("rules", JSONArray().put(JSONObject().put("domain", JSONArray().put(it.host))
                .put("server", "bootstrap")).apply { for (index in 0 until dnsRules.length()) put(dnsRules.getJSONObject(index)) })
        }
        val nativeRules = route.getJSONArray("rules")
        require(nativeRules.getJSONObject(0).getString("action") == "sniff")
        require(nativeRules.getJSONObject(1).getString("action") == "hijack-dns" &&
            nativeRules.getJSONObject(2).getString("action") == "reject" && nativeRules.getJSONObject(3).getString("action") == "reject")
        for (index in 0 until nativeRules.length()) {
            val rule = nativeRules.getJSONObject(index)
            rules.put(rule)
            // Cached OS DNS needs SNI sniffing before the API domain can match.
            if (index == 0 && api != null) rules.put(JSONObject().put("inbound", "tun").put("domain", JSONArray().put(api.host))
                .put("port", if (api.port == -1) 443 else api.port).put("action", "route").put("outbound", "direct"))
        }
        val activeRules = routingRules.filter { it.enabled }.map(DesktopRoutingRule::normalized)
        for (row in activeRules) require(row.ips.filter { it.startsWith("geoip:") }.all { selector -> row.ruleSets.any { it.name == selector } }) { "Не найден локальный список geoip. Сохраните правило заново." }
        val sources = JSONArray()
        for (source in activeRules.flatMap { it.ruleSets }.distinctBy { it.path }) {
            source.normalized()
            sources.put(JSONObject().put("type", "local").put("tag", source.tag).put("format", "source").put("path", source.path))
            source.dnsPath?.let { sources.put(JSONObject().put("type", "local").put("tag", source.tag + "-dns").put("format", "source").put("path", it)) }
        }
        if (sources.length() > 0) route.put("rule_set", sources)
        val selectors = activeRules.flatMap { it.serverDomains() }.filter { it.startsWith("geosite:") }.toSet()
        val geosites = GeositeAsset.resolveDomains(geositeBytes, selectors)
        for (raw in activeRules) {
            val item = raw.normalized()
            val matches = mutableListOf<JSONObject>()
            VpnConfigFactory.domainMatch(item.serverDomains(), geosites)?.let { domains ->
                matches += domains
            }
            val ips = item.ips.filterNot { it.startsWith("geoip:") }
            if (ips.isNotEmpty()) matches += JSONObject().put("ip_cidr", JSONArray(ips))
            applicationMatch(item.applications)?.let(matches::add)
            if (item.ruleSets.isNotEmpty()) matches += JSONObject().put("rule_set", JSONArray(item.ruleSets.map { it.tag }))
            val match = if (matches.size == 1) matches.single() else JSONObject().put("type", "logical").put("mode", "or").put("rules", JSONArray(matches))
            rules.put(tunOnly(match).put("action", "route").put("outbound", if (item.bypass) "direct" else "proxy"))
        }
        applicationBypassRule(settings)?.let(rules::put)
        route.put("rules", rules)
        applyDnsPolicy(config, dnsBypassMatch(settings, routingRules, geositeBytes), dnsPolicy())
        return config
    }

    internal fun dnsBypassMatch(settings: StoredSettings, routingRules: List<DesktopRoutingRule>, geositeBytes: ByteArray): JSONObject? {
        val advanced = DomainRulePolicy.normalizeSettings(settings.advancedSettings)
        val rows = routingRules.filter { it.enabled }.map(DesktopRoutingRule::normalized)
        val selectors = advanced.bypassDomains + advanced.alwaysOnDomains + rows.flatMap { it.serverDomains() }
        val geosites = GeositeAsset.resolveDomains(geositeBytes, selectors.filter { it.startsWith("geosite:") }.toSet())
        val direct = mutableListOf<JSONObject>()
        VpnConfigFactory.domainMatch(advanced.bypassDomains, geosites)?.let(direct::add)
        val protected = mutableListOf<JSONObject>()
        VpnConfigFactory.domainMatch(advanced.alwaysOnDomains, geosites)?.let(protected::add)
        for (row in rows) {
            val matches = mutableListOf<JSONObject>()
            VpnConfigFactory.domainMatch(row.serverDomains(), geosites)?.let(matches::add)
            val sourceTags = row.ruleSets.filter { it.dnsPath != null }.map { it.tag + "-dns" }
            if (sourceTags.isNotEmpty()) matches += JSONObject().put("rule_set", JSONArray(sourceTags))
            applicationMatch(row.applications)?.let { matches += if (row.bypass) applicationDnsMatch(it) else it }
            if (row.ips.containsAll(listOf("0.0.0.0/0", "::/0"))) matches += applicationDnsMatch()
            if (matches.isEmpty()) continue
            val match = if (matches.size == 1) matches.single() else JSONObject().put("type", "logical").put("mode", "or").put("rules", JSONArray(matches))
            if (!row.bypass) protected += match
            else direct += if (protected.isEmpty()) match else JSONObject().put("type", "logical").put("mode", "and")
                .put("rules", JSONArray().put(match).put(JSONObject().put("type", "logical").put("mode", "or")
                    .put("rules", JSONArray(protected)).put("invert", true)))
        }
        applicationBypassMatch(settings)?.let { applications ->
            val matches = applicationDnsMatch(applications).getJSONArray("rules")
            if (protected.isNotEmpty()) matches.put(JSONObject().put("type", "logical").put("mode", "or")
                .put("rules", JSONArray(protected)).put("invert", true))
            direct += JSONObject().put("type", "logical").put("mode", "and").put("rules", matches)
        }
        return when (direct.size) {
            0 -> null
            1 -> direct.single()
            else -> JSONObject().put("type", "logical").put("mode", "or").put("rules", JSONArray(direct))
        }
    }

    internal fun applyDnsPolicy(config: JSONObject, bypass: JSONObject?, policy: WindowsDnsPolicy) {
        config.optJSONArray("inbounds")?.let { inbounds ->
            val tun = (0 until inbounds.length()).map(inbounds::getJSONObject).firstOrNull { it.optString("type") == "tun" }
            tun?.apply {
                remove("windows_dns")
                if (!DesktopBuild.IS_LINUX && bypass != null && policy.servers.isNotEmpty()) {
                    put("windows_dns", JSONObject()
                        .put("servers", JSONArray(policy.servers.distinctBy { it.address to it.interfaceIndex }.map {
                            JSONObject().put("address", it.address).put("interface_index", it.interfaceIndex)
                        })))
                }
                put("address", JSONArray().put("172.19.0.1/30").put("fdfe:dcba:9876::1/126"))
                put("dns_address", JSONArray().put("172.19.0.2").put("fdfe:dcba:9876::2"))
                // NRPT can target a more-specific physical route; capture its DNS before strict-route WFP blocks it.
                put("route_address", JSONArray((listOf("0.0.0.0/1", "128.0.0.0/1", "::/1", "8000::/1") + policy.captureRoutes + policy.servers.mapNotNull { server ->
                    val host = server.address.substringBefore('%')
                    if (InetAddress.getByName(host).isLoopbackAddress) null else normalizeIpRule(host)
                }).distinct().sorted()))
                config.optJSONObject("route")?.let { route ->
                    if (DesktopBuild.IS_LINUX) {
                        route.remove("windows_ipv6_direct_only")
                        route.put("default_mark", 20047)
                        route.remove("default_interface")
                        route.put("auto_detect_interface", true)
                    } else route.put("windows_ipv6_direct_only", true)
                    val old = route.getJSONArray("rules")
                    val rules = JSONArray()
                    for (index in 0 until old.length()) {
                        val rule = old.getJSONObject(index)
                        if (rule.optInt("ip_version") == 6 && rule.optString("action") == "reject") continue
                        if (rule.optString("action") == "route" && rule.optString("outbound") != "direct") {
                            val match = JSONObject(rule.toString()).apply { remove("action"); remove("outbound") }
                            rules.put(JSONObject().put("type", "logical").put("mode", "and")
                                .put("rules", JSONArray().put(match).put(JSONObject().put("ip_version", 6))).put("action", "reject"))
                        }
                        rules.put(rule)
                    }
                    rules.put(JSONObject().put("ip_version", 6).put("action", "reject"))
                    route.put("rules", rules)
                }
            }
        }
        val dns = config.getJSONObject("dns")
        dns.put("strategy", "prefer_ipv4")
        // Protected traffic has no IPv6 outbound; keep HTTPS/SVCB hints aligned with AAAA.
        // System/NRPT and bootstrap responses retain working direct IPv6 destinations.
        dns.put("ipv4_only_servers", JSONArray().put(dns.getString("final")))
        val protectedAAAA = JSONObject().put("query_type", "AAAA").put("action", "predefined").put("rcode", "NOERROR")
        val originalDnsRules = dns.getJSONArray("rules")
        val rules = JSONArray((0 until originalDnsRules.length()).map(originalDnsRules::getJSONObject).filterNot { it.similar(protectedAAAA) })
        dns.put("rules", rules)
        val servers = dns.getJSONArray("servers")
        val tags = linkedMapOf<Pair<String, String>, String>()
        fun query(match: JSONObject, resolvers: List<WindowsDnsServer>, blocked: Boolean = false) {
            if (!blocked) for (resolver in resolvers) {
                val tag = tags.getOrPut(resolver.address to resolver.interfaceName) {
                    "system-dns-${tags.size}".also { tag -> servers.put(JSONObject().put("type", "udp").put("tag", tag)
                        .put("server", resolver.address).put("server_port", 53).put("bind_interface", resolver.interfaceName)) }
                }
                rules.put(JSONObject(match.toString()).put("action", "evaluate").put("server", tag)
                    .put("timeout", "2s").put("disable_cache", true))
                rules.put(JSONObject("""{
                    "type":"logical","mode":"and","rules":[
                        {"match_response":true,"response_rcode":"NOERROR"},
                        {"type":"logical","mode":"or","rules":[
                            {"query_type":["A","AAAA"],"invert":true},
                            {"query_type":"A","match_response":true,"ip_cidr":"0.0.0.0/0"},
                            {"query_type":"AAAA","match_response":true,"ip_cidr":"::/0"}
                        ]}
                    ],"action":"respond"
                }"""))
            }
            if (!blocked && resolvers.isNotEmpty())
                rules.put(JSONObject().put("match_response", true).put("action", "respond"))
            // Never disclose an excluded name to the protected resolver after a local failure.
            rules.put(JSONObject(match.toString()).put("action", "predefined").put("rcode", "SERVFAIL"))
        }
        val defaultServers = policy.servers.filterNot { it.nrptOnly }
        if (bypass == null) {
            rules.put(protectedAAAA)
            return
        }
        for (rule in policy.nrpt.sortedWith(compareByDescending<WindowsNrptRule> { it.namespace.trimStart('.').length }
            .thenBy { it.namespace.startsWith('.') })) {
            val namespace = when {
                rule.namespace == "." -> null
                rule.namespace.startsWith('.') -> JSONObject().put("domain_suffix", rule.namespace.substring(1))
                else -> JSONObject().put("domain", rule.namespace)
            }
            val match = namespace?.let { JSONObject().put("type", "logical").put("mode", "and")
                .put("rules", JSONArray().put(bypass).put(it)) } ?: bypass
            val resolvers = if (rule.servers.isEmpty()) defaultServers else rule.servers.flatMap { address ->
                policy.servers.filter { it.address == address && it.nrptOnly }
            }
            query(match, resolvers, rule.unsupportedSecurity)
        }
        query(bypass, defaultServers)
        rules.put(protectedAAAA)
    }

    internal fun dnsSettingsMatch(config: JSONObject, dnsPolicy: () -> WindowsDnsPolicy = ::windowsDnsPolicy): Boolean {
        val tun = config.getJSONArray("inbounds").getJSONObject(0)
        if (tun.optString("dns_mode") != "hijack" || tun.has("route_exclude_address")) return false
        if (!DesktopBuild.IS_LINUX && !config.getJSONObject("route").optBoolean("windows_ipv6_direct_only")) return false
        val dns = config.getJSONObject("dns")
        val rules = dns.getJSONArray("rules")
        val last = (rules.length() - 1 downTo 0).map(rules::getJSONObject)
            .firstOrNull { it.optString("action") == "predefined" && it.optString("rcode") == "SERVFAIL" }
        val bypass = last?.let { JSONObject(it.toString()).apply { remove("action"); remove("rcode") } }
        val rebuilt = JSONObject().put("dns", JSONObject().put("rules", JSONArray()).put("servers", JSONArray()).put("final", dns.getString("final")))
            .put("inbounds", JSONArray().put(JSONObject(tun.toString())))
        applyDnsPolicy(rebuilt, bypass, dnsPolicy())
        val expected = rebuilt.getJSONObject("dns")
        val current = JSONArray((0 until dns.getJSONArray("servers").length()).map { dns.getJSONArray("servers").getJSONObject(it) }
            .filter { it.optString("tag").startsWith("system-dns-") })
        val expectedRules = expected.getJSONArray("rules")
        if (rules.length() < expectedRules.length()) return false
        return dns.optJSONArray("ipv4_only_servers")?.similar(expected.getJSONArray("ipv4_only_servers")) == true &&
            tun.optJSONArray("route_address")?.similar(rebuilt.getJSONArray("inbounds").getJSONObject(0).getJSONArray("route_address")) == true &&
            (tun.optJSONObject("windows_dns")?.similar(rebuilt.getJSONArray("inbounds").getJSONObject(0).optJSONObject("windows_dns"))
                ?: !rebuilt.getJSONArray("inbounds").getJSONObject(0).has("windows_dns")) &&
            current.similar(expected.getJSONArray("servers")) &&
            JSONArray((rules.length() - expectedRules.length() until rules.length()).map(rules::getJSONObject)).similar(expectedRules)
    }

    private fun tunOnly(match: JSONObject) = JSONObject().put("type", "logical").put("mode", "and")
        .put("rules", JSONArray().put(JSONObject().put("inbound", "tun")).put(match))

    private fun applicationBypassRule(settings: StoredSettings): JSONObject? = applicationBypassMatch(settings)?.let {
        tunOnly(it).put("action", "route").put("outbound", "direct")
    }

    private fun applicationBypassMatch(settings: StoredSettings): JSONObject? {
        if (settings.filterMode == AppFilterMode.ALL_APPS) return null
        require(settings.selectedPackages.isNotEmpty()) { "Выберите приложения для маршрутизации" }
        val processes = checkNotNull(applicationMatch(settings.selectedPackages.toList()))
        return if (settings.filterMode == AppFilterMode.ONLY_SELECTED) processes.put("invert", true) else processes
    }

    private fun applicationDnsMatch(processes: JSONObject? = null): JSONObject {
        val matches = JSONArray()
        processes?.let(matches::put)
        if (DesktopBuild.IS_LINUX) {
            matches.put(JSONObject().put("process_path_regex", "^/.+"))
                .put(JSONObject().put("process_name", JSONArray().put("systemd-resolved").put("dnsmasq").put("unbound")).put("invert", true))
            return JSONObject().put("type", "logical").put("mode", "and").put("rules", matches)
        }
        matches.put(JSONObject().put("process_path_regex", "(?i)^[a-z]:[\\\\/].+\\.exe$"))
            // Shared Windows DNS has no reliable originating app; keep it protected.
            .put(JSONObject().put("process_path_regex", "(?i)[\\\\/]svchost\\.exe$").put("invert", true))
        return JSONObject().put("type", "logical").put("mode", "and").put("rules", matches)
    }

    private fun applicationMatch(applications: List<String>): JSONObject? {
        if (applications.isEmpty()) return null
        require(applications.all { PathValidation.isExecutablePath(it) || PathValidation.isFolderPath(it) }) { "Выберите полный путь к .exe или папке" }
        if (DesktopBuild.IS_LINUX) {
            return JSONObject().put("process_path_regex", JSONArray(applications.sorted().map {
                "^" + Regex.escape(it.trimEnd('/')) + if (PathValidation.isFolderPath(it)) "/.*" else "$"
            }))
        }
        val paths = applications.filter(PathValidation::isExecutablePath).sorted()
        val folders = applications.filter(PathValidation::isFolderPath).sorted()
        val processMatches = mutableListOf<JSONObject>()
        if (paths.isNotEmpty()) processMatches += JSONObject().put("process_path_regex", JSONArray(paths.map {
            "(?i)^" + Regex.escape(it.replace('/', '\\')) + "$"
        }))
        if (folders.isNotEmpty()) processMatches += JSONObject().put("process_path_regex", JSONArray(folders.map {
            "(?i)^" + Regex.escape(it.trimEnd('\\', '/').replace('/', '\\')) + "[\\\\/].*"
        }))
        return if (processMatches.size == 1) processMatches.single() else JSONObject().put("type", "logical").put("mode", "or").put("rules", JSONArray(processMatches))
    }

    private fun validate(session: BackendVpnSession, candidate: BackendEndpointCandidate) {
        require(!candidate.canaryOnly && EndpointTransportPolicy.isDirectCandidate(candidate) && EndpointSecurityPolicy.isAllowedCandidate(candidate)) {
            "endpoint_security_rejected"
        }
        require(candidate.entryPort in 1..65535 && validHost(candidate.entryHost) && validHostname(candidate.serverName)) { "invalid_endpoint_address" }
        val connectIp = candidate.connectIp
        require(connectIp.isNullOrBlank() || validIp(connectIp)) { "invalid_connect_ip" }
        val alpn = candidate.alpn.orEmpty().split(',').map(String::trim).filter(String::isNotEmpty)
        require(candidate.alpn.orEmpty().length <= 2048 && alpn.size <= 16 &&
            alpn.all { token -> token.length in 1..255 && token.all { it.code in 33..126 } }) { "invalid_alpn" }
        if (EndpointTransportPolicy.isNokiNv(candidate)) {
            val profile = EndpointSelector.profileFromCandidate(session, candidate)
            require(VpnProfileValidator.isUsable(profile, AdvancedSettings(multiHop = profile.multiHop?.selection ?: MultiHopSettings()))) {
                "invalid_nokinv_profile"
            }
        } else if (EndpointTransportPolicy.isNaive(candidate)) {
            require(EndpointSecurityPolicy.isValidNaiveCredential(session.vpnUsername) &&
                EndpointSecurityPolicy.isValidNaiveCredential(session.vpnSecret)) { "invalid_naive_credentials" }
        } else if (EndpointTransportPolicy.isHysteria(candidate)) {
            require(session.vpnSecret.toByteArray(Charsets.UTF_8).size in 1..4096 && '\u0000' !in session.vpnSecret) { "invalid_hysteria_password" }
        } else {
            require(session.vpnSecret.length == 36 && runCatching { UUID.fromString(session.vpnSecret).toString().equals(session.vpnSecret, true) }.getOrDefault(false)) { "invalid_vless_uuid" }
            require(candidate.fingerprint.orEmpty().ifBlank { "chrome" } in fingerprints) { "unsupported_tls_fingerprint" }
            if (candidate.security.equals("reality", true)) {
                require(candidate.publicKey.orEmpty().matches(Regex("[A-Za-z0-9_-]{43}")) && Base64.getUrlDecoder().decode(candidate.publicKey).size == 32) { "invalid_reality_public_key" }
                require(candidate.shortId.orEmpty().matches(Regex("(?:[A-Fa-f0-9]{2}){1,8}"))) { "invalid_reality_short_id" }
            }
        }
    }

    private fun validHostname(value: String): Boolean = value.length in 1..253 && value.any { it !in "0123456789." } && !validIp(value) &&
        value.split('.').all { hostnameLabel.matches(it) }
    private fun validHost(value: String): Boolean = validIp(value) || (value.any { it !in "0123456789." } && validHostname(value))
    private fun validIp(value: String): Boolean {
        if (':' in value) return value.length <= 45 && value.all { it in "0123456789abcdefABCDEF:" } && runCatching { InetAddress.getByName(value) }.isSuccess
        val parts = value.split('.')
        return parts.size == 4 && parts.all { part -> part.toIntOrNull()?.let { it in 0..255 && it.toString() == part } == true }
    }
    internal fun normalizeIpRule(raw: String): String {
        val value = raw.trim().lowercase(Locale.ROOT)
        val host = value.substringBefore('/')
        require(validIp(host)) { "Некорректный IP-адрес: $raw" }
        val bits = if (':' in host) 128 else 32
        val prefix = if ('/' in value) value.substringAfter('/').toIntOrNull() else bits
        require(prefix != null && prefix in 0..bits) { "Некорректная маска IP: $raw" }
        return "$host/$prefix"
    }
    private fun candidateFromSession(session: BackendVpnSession) = BackendEndpointCandidate(
        code = session.endpointCode ?: "session", nodeId = null, label = session.locationName,
        locationCode = session.locationCode, locationName = session.locationName,
        entryHost = session.entryHost, entryPort = session.entryPort, serverName = session.serverName,
        proxyType = session.proxyType, transport = session.transport, transportMode = session.transportMode,
        security = session.security, fingerprint = session.fingerprint, requestHost = session.requestHost,
        path = session.path, alpn = session.alpn, allowInsecure = session.allowInsecure, enableMux = session.enableMux,
        randomUserAgent = session.randomUserAgent, publicKey = session.publicKey, shortId = session.shortId,
        flow = session.flow, priority = 0, weight = 1, canaryOnly = false, tags = emptyList(), connectIp = session.connectIp,
        nokinvToken = session.nokinvToken, nokinvCredentialGeneration = session.nokinvCredentialGeneration,
    )
}
