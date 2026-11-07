package com.noki.vpn.vpn

import com.noki.vpn.data.AdvancedSettings
import com.noki.vpn.data.BackendRuWireGuardConfig
import com.noki.vpn.data.DomainRulePolicy
import com.noki.vpn.data.EndpointSelector
import com.noki.vpn.data.EndpointSecurityPolicy
import com.noki.vpn.data.VlessProfile
import com.noki.vpn.data.VpnProfileValidator
import java.util.Base64
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

data class YoutubeConnectionConfig(val port: Int) {
    init { require(port in 1..65535) { "youtube_local_port_invalid" } }
}

object VpnConfigFactory {
    private const val DNS_TAG = "noki-node-dns"
    private const val BOOTSTRAP_TAG = "bootstrap"
    private const val YOUTUBE_TAG = "youtube-local"

    // The same V2Fly private set used by the server compiler, including CGNAT/reserved ranges.
    private val privateCidrs = listOf(
        "0.0.0.0/8", "10.0.0.0/8", "100.64.0.0/10", "127.0.0.0/8", "169.254.0.0/16",
        "172.16.0.0/12", "192.0.0.0/24", "192.0.2.0/24", "192.88.99.0/24", "192.168.0.0/16",
        "198.18.0.0/15", "198.51.100.0/24", "203.0.113.0/24", "224.0.0.0/4", "240.0.0.0/4",
        "255.255.255.255/32", "::/128", "::1/128", "fc00::/7", "fe80::/10", "ff00::/8",
    )

    fun build(profile: VlessProfile, advancedSettings: AdvancedSettings, geositeBytes: ByteArray): String {
        require(!advancedSettings.ruRelayEnabled) { "ru_wireguard_session_required" }
        val runtimeProfile = profile.copy(nokinvMode = if (advancedSettings.gamingModeEnabled) "gaming" else "balanced")
        require(VpnProfileValidator.isUsable(runtimeProfile, advancedSettings)) { "vpn_profile_invalid" }
        return routedConfig(advancedSettings, geositeBytes, JSONArray().put(proxyOutbound(runtimeProfile)),
            mtu = VpnTunnelPolicy.MTU, address = "10.10.0.2/32", dnsServers = listOf(VpnTunnelPolicy.DNS_SERVER),
            remoteDnsServer = if (profile.proxyType.equals("nokinv", true)) profile.host else VpnTunnelPolicy.DNS_SERVER).toString(2)
    }

    fun buildWireGuard(config: BackendRuWireGuardConfig, privateKey: ByteArray,
                       advancedSettings: AdvancedSettings, geositeBytes: ByteArray): String {
        require(advancedSettings.ruRelayEnabled) { "wireguard_requires_ru_relay" }
        val endpoint = wireGuardEndpoint(config, privateKey, "proxy", keepalive = 5)
        return routedConfig(advancedSettings, geositeBytes, JSONArray(), config.mtu, config.address, config.dns)
            .put("endpoints", JSONArray().put(endpoint)).toString(2)
    }

    private fun wireGuardEndpoint(config: BackendRuWireGuardConfig, privateKey: ByteArray, tag: String,
                                  keepalive: Int): JSONObject {
        require(privateKey.size == 32 && privateKey.any { it != 0.toByte() } && config.dns.isNotEmpty()) { "wireguard_config_invalid" }
        val peerHost = config.endpoint.substringBeforeLast(':')
        val peerPort = config.endpoint.substringAfterLast(':').toIntOrNull()
        require(peerHost.isNotBlank() && peerPort != null && peerPort in 1..65535) { "wireguard_config_invalid" }
        return JSONObject().put("type", "wireguard").put("tag", tag).put("system", false)
            .put("address", JSONArray().put(config.address)).put("private_key", Base64.getEncoder().encodeToString(privateKey))
            .put("mtu", config.mtu).put("peers", JSONArray().put(JSONObject()
                .put("address", peerHost).put("port", peerPort).put("public_key", config.serverPublicKey)
                .put("allowed_ips", JSONArray(config.allowedIps)).put("persistent_keepalive_interval", keepalive)))
    }

    /** Compose from the main config; a missing local process blocks only the YouTube branch. */
    fun withYoutube(baseConfig: JSONObject, connection: YoutubeConnectionConfig?, geositeBytes: ByteArray): JSONObject {
        val config = JSONObject(baseConfig.toString())
        val youtube = domainMatch(listOf("geosite:youtube"), GeositeAsset.resolveDomains(geositeBytes, setOf("geosite:youtube")))
            ?: throw IllegalArgumentException("geosite_youtube_empty")
        val route = config.getJSONObject("route")
        val original = route.getJSONArray("rules")
        val localRules = JSONArray(privateCidrs).toString()
        val localIndex = (0 until original.length()).firstOrNull {
            original.getJSONObject(it).optJSONArray("ip_cidr")?.toString() == localRules &&
                original.getJSONObject(it).optString("outbound") == "direct"
        } ?: throw IllegalArgumentException("youtube_main_config_invalid")
        val rules = JSONArray()
        for (index in 0 until original.length()) {
            val rule = original.getJSONObject(index)
            rules.put(rule)
            if (index == localIndex) {
                fun match(extra: JSONObject) = JSONObject().put("type", "logical").put("mode", "and")
                    .put("rules", JSONArray().put(JSONObject().put("inbound", "tun"))
                        .put(JSONObject(youtube.toString())).put(extra))
                rules.put(match(JSONObject().put("ip_version", 6)).put("action", "reject"))
                rules.put(match(JSONObject().put("network", "udp")).put("action", "reject"))
                if (connection != null) rules.put(match(JSONObject().put("network", "tcp"))
                    .put("action", "resolve").put("server", DNS_TAG).put("strategy", "ipv4_only"))
                rules.put(match(JSONObject().put("network", "tcp")).put("action", if (connection == null) "reject" else "route")
                    .apply { if (connection != null) put("outbound", YOUTUBE_TAG) })
            }
        }
        route.put("rules", rules)
        val dns = config.getJSONObject("dns")
        val dnsRules = dns.getJSONArray("rules")
        dns.put("rules", JSONArray().put(JSONObject(youtube.toString()).put("server", DNS_TAG))
            .apply { for (index in 0 until dnsRules.length()) put(dnsRules.getJSONObject(index)) })
        if (connection != null) config.getJSONArray("outbounds").put(JSONObject()
            .put("type", "socks").put("tag", YOUTUBE_TAG).put("server", "127.0.0.1")
            .put("server_port", connection.port).put("version", "5").put("network", "tcp"))
        return config
    }

    fun buildProbe(profile: VlessProfile): String = JSONObject()
        .put("log", JSONObject().put("level", "warn"))
        .put("dns", JSONObject().put("servers", JSONArray().put(bootstrapServer())).put("final", BOOTSTRAP_TAG))
        .put("outbounds", JSONArray().put(proxyOutbound(profile)))
        .put("route", JSONObject().put("final", "proxy").put("auto_detect_interface", true)
            .put("default_domain_resolver", resolver(BOOTSTRAP_TAG)))
        .toString(2)

    private fun routedConfig(settings: AdvancedSettings, geositeBytes: ByteArray, outbounds: JSONArray,
                              mtu: Int, address: String, dnsServers: List<String>, remoteDnsServer: String = dnsServers.first()): JSONObject {
        val normalized = DomainRulePolicy.normalizeSettings(settings)
        val selectors = (normalized.alwaysOnDomains + normalized.bypassDomains)
            .filter { it.startsWith("geosite:") }.toSet()
        val resolved = GeositeAsset.resolveDomains(geositeBytes, selectors)
        val directDomains = domainMatch(normalized.bypassDomains, resolved)
        val rules = JSONArray()
            .put(JSONObject().put("inbound", JSONArray().put("tun")).put("action", "sniff")
                .put("sniffer", JSONArray().put("http").put("tls").put("quic")))
            .put(JSONObject().put("port", JSONArray().put(53)).put("action", "hijack-dns"))
            .put(JSONObject().put("port", JSONArray().put(853)).put("network", JSONArray().put("tcp").put("udp"))
                .put("action", "reject"))
            .put(JSONObject().put("type", "logical").put("mode", "and").put("rules", JSONArray()
                .put(JSONObject().put("domain_suffix", JSONArray().put("cloudflare-dns.com").put("one.one.one.one")))
                .put(JSONObject().put("port", JSONArray().put(443)))
                .put(JSONObject().put("network", JSONArray().put("tcp")))).put("action", "reject"))
            .put(route(JSONObject().put("domain_suffix", JSONArray().put("googleapis.cn")), "direct"))
            .put(route(JSONObject().put("ip_cidr", JSONArray(privateCidrs)), "direct"))
        directDomains?.let { rules.put(route(JSONObject(it.toString()), "direct")) }
        rules.put(JSONObject().put("ip_version", 6).put("action", "reject"))
        domainMatch(normalized.alwaysOnDomains, resolved)?.let { rules.put(route(it, "proxy")) }
        // The wire list supplies an explicit primary. Native DNS has no synthesized failover.
        val dns = JSONArray().put(JSONObject().put("type", "tcp").put("tag", DNS_TAG)
            .put("server", remoteDnsServer).put("server_port", 53).put("detour", "proxy"))
        dns.put(bootstrapServer())
        return JSONObject()
            .put("log", JSONObject().put("level", when {
                normalized.errorLogsEnabled -> "error"
                normalized.connectionLogsEnabled -> "info"
                else -> "warn"
            }))
            .put("inbounds", JSONArray().put(JSONObject().put("type", "tun").put("tag", "tun")
                .put("interface_name", "noki0").put("mtu", mtu).put("address", JSONArray().put(address))
                .put("dns_mode", "hijack").put("dns_address", JSONArray().put(dnsServers.first()))
                // The VPN app is excluded from TUN; mixed's kernel TCP replies would follow its underlay route.
                .put("auto_route", false).put("stack", "gvisor")))
            .put("outbounds", outbounds.put(JSONObject().put("type", "direct").put("tag", "direct")
                .put("domain_resolver", resolver(BOOTSTRAP_TAG))))
            .put("dns", JSONObject().put("servers", dns).put("final", DNS_TAG).put("strategy", "ipv4_only")
                .put("reverse_mapping", true).put("rules", JSONArray().apply {
                    directDomains?.let { put(JSONObject(it.toString()).put("server", BOOTSTRAP_TAG)) }
                }))
            .put("route", JSONObject().put("rules", rules).put("final", "proxy").put("auto_detect_interface", true)
                .put("default_domain_resolver", resolver(DNS_TAG)))
    }

    private fun route(match: JSONObject, outbound: String): JSONObject = match.put("action", "route").put("outbound", outbound)
    private fun bootstrapServer(): JSONObject = JSONObject().put("type", "local").put("tag", BOOTSTRAP_TAG)
    private fun resolver(server: String): JSONObject = JSONObject().put("server", server).put("strategy", "ipv4_only")

    fun domainMatch(selectors: List<String>, geosite: Map<String, JSONObject>): JSONObject? {
        val fields = linkedMapOf<String, LinkedHashSet<String>>()
        for (selector in selectors) {
            val values = if (selector.startsWith("geosite:")) geosite.getValue(selector) else {
                val field = when (selector.substringBefore(':')) {
                    "domain" -> "domain_suffix"
                    "full" -> "domain"
                    "regexp" -> "domain_regex"
                    else -> throw IllegalArgumentException("domain_selector_invalid")
                }
                JSONObject().put(field, JSONArray().put(selector.substringAfter(':')))
            }
            for (field in values.keys()) {
                val array = values.getJSONArray(field)
                val target = fields.getOrPut(field) { linkedSetOf() }
                for (index in 0 until array.length()) target += array.getString(index)
            }
        }
        val branches = fields.filterValues { it.isNotEmpty() }.map { (field, values) -> JSONObject().put(field, JSONArray(values)) }
        return when (branches.size) {
            0 -> null
            1 -> branches.single()
            else -> JSONObject().put("type", "logical").put("mode", "or").put("rules", JSONArray(branches))
        }
    }

    private fun proxyOutbound(profile: VlessProfile): JSONObject {
        require(EndpointSecurityPolicy.isAllowedProfile(profile)) { "vpn_profile_invalid" }
        val port = profile.port.toIntOrNull()
        require(port != null && port in 1..65535) { "vpn_profile_invalid" }
        require(!profile.enableMux && !profile.randomUserAgent && !profile.allowInsecure &&
            profile.spiderX in listOf("", "/")) { "vpn_profile_option_unsupported" }
        val transport = profile.transport.lowercase(Locale.ROOT)
        val kind = profile.proxyType.lowercase(Locale.ROOT)
        val security = profile.security.lowercase(Locale.ROOT)
        require(security in setOf("tls", "reality") && profile.serverName.isNotBlank()) { "vpn_profile_tls_required" }
        if (kind == "nokinv") {
            require(profile.requestHost.isBlank() && profile.path in listOf("", "/tunnel")) { "vpn_profile_nokinv_invalid" }
            return JSONObject().put("tag", "proxy").put("type", "nokinv")
                .put("server", profile.host).put("server_port", port)
                .put("token", profile.nokinvToken).put("credential_generation", profile.nokinvCredentialGeneration)
                .put("transport", "quic")
                .put("mode", profile.nokinvMode).put("path", "/tunnel")
                .put("web_profile", "coherent").put("shaping", "off")
                .put("tls", JSONObject().put("enabled", true).put("server_name", profile.serverName).put("min_version", "1.3"))
                .put("domain_resolver", resolver(BOOTSTRAP_TAG))
        }
        require(profile.uuid.isNotBlank()) { "vpn_profile_invalid" }
        if (kind == "naive") {
            return JSONObject().put("tag", "proxy").put("type", "naive")
                .put("server", profile.host).put("server_port", port)
                .put("username", profile.vpnUsername).put("password", profile.uuid)
                .put("quic", false).put("udp_over_tcp", JSONObject().put("enabled", true).put("version", 2))
                .put("tls", JSONObject().put("enabled", true).put("server_name", profile.serverName))
                .put("domain_resolver", resolver(BOOTSTRAP_TAG))
        }
        val hy2 = kind in setOf("hysteria", "hysteria2", "hy2")
        if (hy2) {
            require(transport in setOf("hysteria", "hysteria2", "hy2") && security == "tls" &&
                profile.flow.isBlank() && profile.transportMode.isBlank() && profile.requestHost.isBlank() &&
                profile.path in listOf("", "/") && profile.publicKey.isBlank() && profile.shortId.isBlank()) { "vpn_profile_transport_unsupported" }
        } else {
            require(kind == "vless" && transport in setOf("tcp", "raw", "xhttp") &&
                profile.flow in listOf("", "xtls-rprx-vision")) { "vpn_profile_transport_unsupported" }
            if (transport == "xhttp") require(profile.transportMode in listOf("", "stream-up") &&
                security == "reality" && profile.flow.isBlank()) { "vpn_profile_transport_unsupported" }
            else require(profile.transportMode.isBlank() && profile.requestHost.isBlank() && profile.path in listOf("", "/")) {
                "vpn_profile_option_unsupported"
            }
        }
        val tls = JSONObject().put("enabled", true).put("server_name", profile.serverName)
        val alpn = profile.alpn.split(',').map(String::trim).filter(String::isNotEmpty)
        if (alpn.isNotEmpty()) tls.put("alpn", JSONArray(alpn)) else if (hy2) tls.put("alpn", JSONArray().put("h3"))
        if (!hy2) tls.put("utls", JSONObject().put("enabled", true).put("fingerprint", profile.fingerprint.ifBlank { "chrome" }))
        if (security == "reality") {
            require(profile.publicKey.isNotBlank()) { "vpn_profile_reality_invalid" }
            tls.put("reality", JSONObject().put("enabled", true).put("public_key", profile.publicKey).put("short_id", profile.shortId))
        } else require(profile.publicKey.isBlank() && profile.shortId.isBlank()) { "vpn_profile_option_unsupported" }
        return JSONObject().put("tag", "proxy").put("type", if (hy2) "hysteria2" else "vless")
            .put("server", profile.host).put("server_port", port).put("tls", tls)
            .put("domain_resolver", resolver(BOOTSTRAP_TAG)).apply {
                if (hy2) put("password", profile.uuid) else {
                    put("uuid", profile.uuid)
                    if (profile.flow.isNotBlank()) put("flow", profile.flow)
                    if (transport == "xhttp") put("transport", JSONObject().put("type", "xhttp").put("mode", "stream-up")
                        .put("path", profile.path.ifBlank { "/" }).put("host", profile.requestHost.ifBlank { profile.serverName })
                        .put("x_padding_bytes", "100-1000"))
                }
            }
    }
}
