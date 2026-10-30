package com.noki.desktop

import com.noki.vpn.data.BackendEndpointCandidate
import com.noki.vpn.data.BackendVpnSession
import com.noki.vpn.data.AppFilterMode
import com.noki.vpn.data.*
import com.noki.vpn.vpn.GeositeAsset
import com.noki.vpn.vpn.VpnConfigFactory
import com.noki.vpn.vpn.YoutubeConnectionConfig
import org.json.JSONArray
import org.json.JSONObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

class SingBoxConfigTest {
    @Test fun `NaiveProxy uses session credentials in the native outbound and remains a separate manual family`() {
        val naive = hysteria().copy(code = "ro-naive", locationCode = "ro", locationName = "Romania",
            proxyType = "naive", transport = "naive", alpn = null, connectIp = "203.0.113.10")
        val session = session(naive).copy(vpnUsername = "naive-user", vpnSecret = "naive-password")
        val profile = EndpointSelector.profileFromCandidate(session, naive)
        val codec = StoredSettingsCodec { DesktopState().settings }
        assertEquals(profile, codec.decode(codec.encode(DesktopState().settings.copy(profile = profile))).profile)
        assertTrue(VpnProfileValidator.isUsable(profile, AdvancedSettings()))
        assertEquals("tls|naive|", EndpointGroupPolicy.groupKey(profile))
        assertEquals("NaiveProxy", EndpointGroupPolicy.displayLabelFor(EndpointSelector.optionsFromSession(session).single()))
        assertEquals(listOf("nokinv", "xhttp", "tcp", "hysteria", "naive"),
            EndpointGroupPolicy.protocolOptions().map { it.transport })
        val manual = AdvancedSettings(endpointSelectionMode = EndpointSelectionMode.MANUAL, manualEndpointGroupKey = "tls|naive|")
        assertEquals(listOf(naive), EndpointGroupPolicy.manualCandidates(listOf(naive, hysteria(), candidate()), manual))
        for (network in EndpointRankingPolicy.NetworkKind.entries) {
            assertEquals(naive, EndpointRankingPolicy.select(listOf(naive), emptyMap(), network, 1000L, { 0 })?.candidate)
        }
        val config = SingBoxConfig.generate(session, naive, 20909, SECRET, dnsPolicy = { unitDnsPolicy })
        val outbound = config.getJSONArray("outbounds").objects().single { it.optString("tag") == "proxy" }
        assertEquals("naive", outbound.getString("type"))
        assertEquals(naive.connectIp, outbound.getString("server"))
        assertEquals("naive-user", outbound.getString("username"))
        assertEquals("naive-password", outbound.getString("password"))
        assertEquals(naive.serverName, outbound.getJSONObject("tls").getString("server_name"))
        assertFalse(outbound.getJSONObject("tls").has("utls"))
        assertFalse(outbound.has("uuid"))
        assertFalse(outbound.getBoolean("quic"))
        assertTrue(outbound.getJSONObject("udp_over_tcp").getBoolean("enabled"))
        assertEquals(2, outbound.getJSONObject("udp_over_tcp").getInt("version"))
        checkWithCore(config)
    }

    @Test fun `NaiveProxy rejects missing credentials and incompatible transport settings`() {
        val naive = hysteria().copy(code = "ro-naive", locationCode = "ro", proxyType = "naive", transport = "naive")
        val valid = session(naive).copy(vpnUsername = "naive-user", vpnSecret = "naive-password")
        for (invalid in listOf(valid.copy(vpnUsername = ""), valid.copy(vpnSecret = ""),
            valid.copy(vpnUsername = "user\nname"), valid.copy(vpnSecret = "pass\u0000word"))) {
            assertTrue(SingBoxConfig.supportedCandidates(invalid).isEmpty())
        }
        for (invalid in listOf(naive.copy(allowInsecure = true), naive.copy(transportMode = "quic"),
            naive.copy(flow = "xtls-rprx-vision"), naive.copy(publicKey = KEY), naive.copy(path = "/noki"),
            naive.copy(alpn = "h3"), naive.copy(security = "reality"))) {
            assertTrue(SingBoxConfig.supportedCandidates(valid.copy(endpointCandidates = listOf(invalid))).isEmpty())
        }
    }

    @Test fun `Reality TCP is available to Auto and manual selection with Vision config`() {
        val tcp = candidate().copy(transport = "tcp", transportMode = null, path = null, flow = "xtls-rprx-vision")
        assertTrue(EndpointTransportPolicy.isDirectCandidate(tcp))
        val manual = AdvancedSettings(endpointSelectionMode = EndpointSelectionMode.MANUAL, manualEndpointGroupKey = "reality|tcp|")
        assertEquals(listOf(tcp), EndpointGroupPolicy.manualCandidates(listOf(tcp, candidate()), manual))
        assertEquals(tcp, EndpointRankingPolicy.select(listOf(tcp), emptyMap(), EndpointRankingPolicy.NetworkKind.WIFI, 1000L, { 0 })?.candidate)
        val config = SingBoxConfig.generate(session(tcp), tcp, 20909, SECRET, dnsPolicy = { unitDnsPolicy })
        val proxy = config.getJSONArray("outbounds").objects().single { it.optString("tag") == "proxy" }
        assertEquals("vless", proxy.getString("type"))
        assertEquals("xtls-rprx-vision", proxy.getString("flow"))
        assertFalse(proxy.has("transport"))
        assertTrue(proxy.getJSONObject("tls").getJSONObject("reality").getBoolean("enabled"))
        checkWithCore(config)
    }
    private val unitDnsPolicy = WindowsDnsPolicy(listOf(WindowsDnsServer("192.0.2.53", "Fixture adapter", 7, 0)), emptyList())
    @get:org.junit.Rule val mainDispatcher: org.junit.rules.TestRule = DesktopMainDispatcherRule()

    @Test fun `Windows exact app paths ignore case while IPv6 protected rules precede app bypass`() {
        val endpoint = candidate()
        val settings = DesktopState().settings.copy(filterMode = AppFilterMode.ALL_EXCEPT_SELECTED,
            selectedPackages = setOf("C:/Windows/System32/mstsc.exe"), advancedSettings = AdvancedSettings())
        val config = SingBoxConfig.generate(session(endpoint), endpoint, 20909, SECRET, settings = settings,
            routingRules = listOf(DesktopRoutingRule("protected", "Protected", false, listOf("corp.test"))),
            dnsPolicy = { WindowsDnsPolicy(emptyList(), emptyList(), listOf("10.0.0.0/9", "10.128.0.0/9")) })
        val rules = config.getJSONObject("route").getJSONArray("rules").objects()
        val apps = rules.first { it.toString().contains("process_path_regex") }
        val regex = apps.getJSONArray("rules").getJSONObject(1).getJSONArray("process_path_regex").getString(0).toRegex()
        assertTrue(regex.matches("c:\\WINDOWS\\system32\\MSTSC.EXE"))
        assertFalse(regex.matches("C:\\Windows\\System32\\mstsc.exe.bak"))
        assertEquals("reject", rules.last().getString("action"))
        assertEquals(6, rules.last().getInt("ip_version"))
        assertTrue(rules.indexOfFirst { it.optString("action") == "reject" && it.toString().contains("corp.test") } < rules.indexOf(apps))
        val tun = config.getJSONArray("inbounds").getJSONObject(0)
        assertTrue(tun.getJSONArray("route_address").toList().containsAll(listOf("10.0.0.0/9", "10.128.0.0/9")))
        assertFalse(tun.has("route_exclude_address"))
        assertTrue(config.getJSONObject("route").getBoolean("windows_ipv6_direct_only"))
        assertEquals("gvisor", tun.getString("stack"))
        assertFalse(rules.first { it.optString("action") == "sniff" }.optBoolean("override_destination"))
        checkWithCore(config)
    }

    @Test fun `DNS exceptions reuse domain selectors and preserve traffic rule priority without process attribution`() {
        val settings = DesktopState().settings.copy(filterMode = AppFilterMode.ALL_APPS,
            advancedSettings = AdvancedSettings(alwaysOnDomains = listOf("full:private.tnt-tv.ru")))
        val geosite = SingBoxConfig.bundledGeosite()
        val rules = listOf(DesktopRoutingRule("corp", "Corp", true, listOf("tnt-tv.ru", "regexp:^vpn[0-9]+\\.example$")),
            DesktopRoutingRule("ip", "IP", true, ips = listOf("10.0.0.0/8")), DesktopRoutingRule.RUSSIAN_RESOURCES)
        val match = SingBoxConfig.dnsBypassMatch(settings, rules, geosite)!!
        assertTrue(match.toString().contains("tnt-tv.ru"))
        assertTrue(match.toString().contains("private.tnt-tv.ru"))
        assertTrue(match.toString().contains("domain_regex"))
        assertTrue(match.toString().contains("domain_suffix"))
        assertTrue(match.toString().contains("invert"))
        assertFalse(match.toString().contains("ip_cidr"))
        assertFalse(match.toString().contains("process_path"))
        assertNull(SingBoxConfig.dnsBypassMatch(settings.copy(filterMode = AppFilterMode.ALL_APPS,
            advancedSettings = AdvancedSettings()), emptyList(), geosite))
    }

    @Test fun `application DNS bypass requires an identified executable and keeps shared Windows DNS protected`() {
        val path = "C:/Apps/Rdp.exe"
        val endpoint = candidate()
        val geosite = SingBoxConfig.bundledGeosite()
        for (mode in listOf(AppFilterMode.ALL_EXCEPT_SELECTED, AppFilterMode.ONLY_SELECTED)) {
            val settings = DesktopState().settings.copy(filterMode = mode, selectedPackages = setOf(path), advancedSettings = AdvancedSettings())
            val bypass = requireNotNull(SingBoxConfig.dnsBypassMatch(settings, emptyList(), geosite))
            val matches = bypass.getJSONArray("rules").objects()
            val knownPath = matches[1].getString("process_path_regex").toRegex()
            assertTrue(knownPath.matches("C:\\Apps\\Rdp.exe"))
            assertFalse(knownPath.matches(""))
            assertFalse(knownPath.matches("System"))
            val sharedService = matches[2].getString("process_path_regex").toRegex()
            assertTrue(matches[2].getBoolean("invert"))
            assertTrue(sharedService.containsMatchIn("C:\\WINDOWS\\System32\\SVCHOST.EXE"))
            assertEquals(mode == AppFilterMode.ONLY_SELECTED, matches[0].optBoolean("invert"))
            val config = SingBoxConfig.generate(session(endpoint), endpoint, 20909, SECRET, settings = settings,
                dnsPolicy = { WindowsDnsPolicy(listOf(WindowsDnsServer("192.0.2.53", "Ethernet", 5, 10)), emptyList()) })
            val dns = config.getJSONObject("dns")
            assertEquals("proxy", dns.getJSONArray("servers").objects().single { it.optString("tag") == "noki-node-dns" }.getString("detour"))
            checkWithCore(config)
        }
    }

    @Test fun `DNS bypass binds system and NRPT resolvers without weakening the protected final resolver`() {
        val endpoint = candidate()
        val config = generate(session(endpoint), endpoint)
        val bypass = JSONObject().put("domain_suffix", "tnt-tv.ru")
        val policy = WindowsDnsPolicy(listOf(WindowsDnsServer("192.0.2.53", "Ethernet", 5, 10),
            WindowsDnsServer("10.1.2.53", "Corp VPN", 7, 1, nrptOnly = true)), listOf(
            WindowsNrptRule(".tnt-tv.ru", listOf("10.1.2.53"), false),
            WindowsNrptRule("private.tnt-tv.ru", emptyList(), true)))
        SingBoxConfig.applyDnsPolicy(config, bypass, policy)
        val dns = config.getJSONObject("dns")
        assertEquals("noki-node-dns", dns.getString("final"))
        val servers = dns.getJSONArray("servers").objects().filter { it.optString("tag").startsWith("system-dns-") }
        assertEquals(listOf("Corp VPN", "Ethernet"), servers.map { it.getString("bind_interface") })
        assertFalse(servers.any { it.has("detour") })
        val rows = dns.getJSONArray("rules").objects()
        assertEquals("predefined", rows[1].getString("action"))
        assertEquals("SERVFAIL", rows[1].getString("rcode"))
        assertEquals("evaluate", rows[2].getString("action"))
        assertEquals("respond", rows[3].getString("action"))
        assertTrue(rows[3].getJSONArray("rules").getJSONObject(0).getBoolean("match_response"))
        assertEquals("NOERROR", rows[3].getJSONArray("rules").getJSONObject(0).getString("response_rcode"))
        assertTrue(rows.filter { it.optString("action") == "evaluate" }.all { it.getBoolean("disable_cache") })
        assertEquals("predefined", rows.last().getString("action"))
        assertFalse(config.getJSONArray("inbounds").getJSONObject(0).has("route_exclude_address"))
        val windows = config.getJSONArray("inbounds").getJSONObject(0).getJSONObject("windows_dns")
        assertEquals(setOf("servers"), windows.keySet())
        assertEquals(setOf(5, 7), windows.getJSONArray("servers").objects().map { it.getInt("interface_index") }.toSet())
        checkWithCore(config)
    }

    @Test fun `DNS protection stays enabled across every main path and YouTube branch`() {
        val youtube = YoutubeConnectionConfig.WireGuard(youtubeWireGuard(), ByteArray(32) { (it + 2).toByte() })
        val settings = DesktopState().settings.copy(advancedSettings = AdvancedSettings(youtubeAdBlockEnabled = true))
        for ((base, current) in fourMainPaths(settings)) {
            for (config in listOf(base, SingBoxConfig.withYoutube(base, current, null), SingBoxConfig.withYoutube(base, current, youtube))) {
                val tun = config.getJSONArray("inbounds").getJSONObject(0)
                assertTrue(tun.getBoolean("strict_route"))
                val dns = config.getJSONObject("dns")
                val servers = dns.getJSONArray("servers").objects()
                val route = config.getJSONObject("route")
                val rules = route.getJSONArray("rules").objects()
                assertTrue(route.getBoolean("windows_ipv6_direct_only"))
                assertEquals("prefer_ipv4", dns.getString("strategy"))
                assertEquals(listOf(dns.getString("final")), dns.getJSONArray("ipv4_only_servers").toList())
                assertFalse(dns.getJSONArray("ipv4_only_servers").toList().contains("bootstrap"))
                assertTrue(servers.filter { it.optString("tag").startsWith("system-dns-") }.none {
                    it.getString("tag") in dns.getJSONArray("ipv4_only_servers").toList()
                })
                assertEquals("hijack", tun.getString("dns_mode"))
                assertEquals("172.19.0.2", tun.getJSONArray("dns_address").getString(0))
                assertFalse(tun.has("route_exclude_address"))
                assertEquals("proxy", servers.single { it.optString("tag") == "noki-node-dns" }.getString("detour"))
                assertTrue(rules.any { it.optString("action") == "hijack-dns" })
                assertEquals("proxy", route.getString("final"))
                checkWithCore(config)
            }
        }
    }

    @Test fun `DNS provider owns both generation and cached policy comparison`() {
        val endpoint = candidate()
        val server = WindowsDnsServer("192.0.2.53", "Fixture adapter", 7, 0)
        val policy = WindowsDnsPolicy(listOf(server), emptyList())
        val config = SingBoxConfig.generate(session(endpoint), endpoint, 20909, SECRET,
            routingRules = listOf(DesktopRoutingRule("fixture", "Fixture", true, listOf("example.test"))), dnsPolicy = { policy })
        assertTrue(SingBoxConfig.dnsSettingsMatch(config) { policy })
        assertFalse(SingBoxConfig.dnsSettingsMatch(config) { policy.copy(servers = listOf(server.copy(address = "192.0.2.54"))) })
        val oldHandshakePolicy = JSONObject(config.toString())
        oldHandshakePolicy.getJSONObject("route").remove("windows_ipv6_direct_only")
        assertFalse(SingBoxConfig.dnsSettingsMatch(oldHandshakePolicy) { policy })
        val oldHintPolicy = JSONObject(config.toString())
        oldHintPolicy.getJSONObject("dns").remove("ipv4_only_servers")
        assertFalse(SingBoxConfig.dnsSettingsMatch(oldHintPolicy) { policy })
        val globalIpv6Block = JSONObject(config.toString())
        globalIpv6Block.getJSONObject("dns").getJSONArray("ipv4_only_servers").put("bootstrap")
        assertFalse(SingBoxConfig.dnsSettingsMatch(globalIpv6Block) { policy })
    }

    @Test fun `cached DNS policy rejects old global bypass and follows Windows resolver changes`() {
        val endpoint = candidate()
        val policy = WindowsDnsPolicy(listOf(WindowsDnsServer("192.0.2.53", "Fixture adapter", 7, 0)), emptyList())
        val settings = DesktopState().settings
        val protected = SingBoxConfig.generate(session(endpoint), endpoint, 20909, SECRET, settings = settings, dnsPolicy = { policy })
        assertTrue(SingBoxConfig.dnsSettingsMatch(protected) { policy })
        val system = JSONObject(protected.toString())
        system.getJSONArray("inbounds").getJSONObject(0).put("dns_mode", "disabled")
        assertFalse(SingBoxConfig.dnsSettingsMatch(system) { policy })
        val bypass = JSONObject().put("domain_suffix", "tnt-tv.ru")
        SingBoxConfig.applyDnsPolicy(protected, bypass, policy)
        assertTrue(SingBoxConfig.dnsSettingsMatch(protected) { policy })
        protected.getJSONObject("dns").getJSONArray("servers").objects()
            .first { it.optString("tag").startsWith("system-dns-") }.put("server", "203.0.113.254")
        assertFalse(SingBoxConfig.dnsSettingsMatch(protected) { policy })
    }

    @Test fun `NokiNV balanced and gaming keep credentials and Windows DNS policy on one TUN`() {
        val endpoint = nokiNv().copy(connectIp = "203.0.113.10")
        for (gaming in listOf(false, true)) {
            val settings = DesktopState().settings.copy(advancedSettings = AdvancedSettings(
                gamingModeEnabled = gaming))
            val config = SingBoxConfig.generate(session(endpoint), endpoint, 20909, SECRET, settings = settings, dnsPolicy = { unitDnsPolicy })
            val proxy = config.getJSONArray("outbounds").objects().single { it.optString("tag") == "proxy" }
            assertEquals("nokinv", proxy.getString("type"))
            assertEquals("203.0.113.10", proxy.getString("server"))
            assertEquals(NV_TOKEN, proxy.getString("token"))
            assertEquals(7, proxy.getInt("credential_generation"))
            assertEquals(if (gaming) "gaming" else "balanced", proxy.getString("mode"))
            assertEquals("quic", proxy.getString("transport"))
            assertEquals("/tunnel", proxy.getString("path"))
            assertFalse(proxy.has("tcp_exchange"))
            assertEquals("coherent", proxy.getString("web_profile"))
            assertEquals("off", proxy.getString("shaping"))
            assertFalse(proxy.has("uuid"))
            assertFalse(proxy.has("password"))
            val tls = proxy.getJSONObject("tls")
            assertTrue(tls.getBoolean("enabled"))
            assertEquals("1.3", tls.getString("min_version"))
            assertEquals("vpn.example.com", tls.getString("server_name"))
            assertFalse(tls.optBoolean("insecure"))
            val tun = config.getJSONArray("inbounds").objects().single { it.optString("type") == "tun" }
            assertEquals("hijack", tun.getString("dns_mode"))
            val servers = config.getJSONObject("dns").getJSONArray("servers").objects()
            assertEquals("172.19.0.2", tun.getJSONArray("dns_address").getString(0))
            val remote = servers.single { it.optString("tag") == "noki-node-dns" }
            assertEquals("tcp", remote.getString("type"))
            assertEquals("203.0.113.10", remote.getString("server"))
            assertEquals("proxy", remote.getString("detour"))
            assertFalse(remote.getString("server") == "172.19.0.2")
            assertFalse(remote.getString("server") == "198.18.0.1")
            assertTrue(SingBoxConfig.dnsSettingsMatch(config) { unitDnsPolicy })
            checkWithCore(config)
        }
    }

    @Test fun `manual NokiNV carrier reaches Windows outbound for catalog and legacy session candidates`() {
        for (legacy in listOf(false, true)) for (mode in listOf("quic", "tcp")) {
            val endpoint = nokiNv()
            val session = session(endpoint).let { if (legacy) it.copy(endpointCandidates = emptyList()) else it }
            val advanced = AdvancedSettings(endpointSelectionMode = EndpointSelectionMode.MANUAL,
                manualEndpointGroupKey = "tls|nokinv|$mode")
            val settings = DesktopState().settings.copy(advancedSettings = advanced)
            val raw = SingBoxConfig.supportedCandidates(session)
            assertTrue(raw.single().transportMode.isNullOrBlank())
            val selected = EndpointGroupPolicy.manualCandidates(raw, advanced).single()
            assertEquals("quic", selected.transportMode)
            val config = SingBoxConfig.generate(session, selected, 20909, SECRET, settings = settings, dnsPolicy = { unitDnsPolicy })
            val proxy = config.getJSONArray("outbounds").objects().single { it.optString("tag") == "proxy" }
            assertEquals("quic", proxy.getString("transport"))
            assertFalse(proxy.has("quic_version"))
            assertEquals("balanced", proxy.getString("mode"))
            assertEquals(NV_TOKEN, proxy.getString("token"))
            assertEquals(7, proxy.getInt("credential_generation"))
            rejected { SingBoxConfig.generate(session, selected.copy(entryPort = 9443), 20909, SECRET, settings = settings, dnsPolicy = { unitDnsPolicy }) }
            val gaming = settings.copy(advancedSettings = advanced.copy(gamingModeEnabled = true))
            val gamingProxy = SingBoxConfig.generate(session, raw.single(), 20909, SECRET, settings = gaming, dnsPolicy = { unitDnsPolicy })
                .getJSONArray("outbounds").objects().single { it.optString("tag") == "proxy" }
            assertEquals("quic", gamingProxy.getString("transport"))
            assertEquals("gaming", gamingProxy.getString("mode"))
            assertEquals(advanced.manualEndpointGroupKey, gaming.advancedSettings.manualEndpointGroupKey)
        }
        val quicOnly = nokiNv().copy(transportMode = "quic")
        val tcp = DesktopState().settings.copy(advancedSettings = AdvancedSettings(
            endpointSelectionMode = EndpointSelectionMode.MANUAL, manualEndpointGroupKey = "tls|nokinv|tcp"))
        rejected { SingBoxConfig.generate(session(quicOnly), quicOnly.copy(transportMode = "tcp"), 20909, SECRET, settings = tcp, dnsPolicy = { unitDnsPolicy }) }
    }

    @Test fun `NokiNV rejects missing malformed and partial release credentials`() {
        val endpoint = nokiNv()
        val valid = session(endpoint)
        assertEquals(listOf(endpoint), SingBoxConfig.supportedCandidates(valid))
        for (invalid in listOf(valid.copy(nokinvToken = ""), valid.copy(nokinvToken = "x".repeat(42)),
                valid.copy(nokinvToken = "x".repeat(44)), valid.copy(nokinvToken = "+".repeat(43)),
                valid.copy(nokinvCredentialGeneration = 0), valid.copy(nokinvCredentialGeneration = -1))) {
            assertTrue(SingBoxConfig.supportedCandidates(invalid).isEmpty())
            rejected { generate(invalid, endpoint) }
        }
        val legacy = valid.copy(endpointCandidates = emptyList())
        val fallback = SingBoxConfig.supportedCandidates(legacy).single()
        assertEquals(NV_TOKEN, generate(legacy, fallback).getJSONArray("outbounds").getJSONObject(0).getString("token"))
        assertTrue(SingBoxConfig.supportedCandidates(legacy.copy(nokinvCredentialGeneration = 0)).isEmpty())
    }

    @Test fun `NokiNV gaming multihop preserves credentials and route through settings round trip`() {
        val hops = MultiHopSettings(true, HopSelection(ServerSelectionMode.SERVER, nodeId = ENTRY), HopSelection(ServerSelectionMode.SERVER, nodeId = EXIT))
        val route = BackendMultiHopSession(hops, ENTRY, EXIT, "203.0.113.20", "a".repeat(64))
        val endpoint = nokiNv().copy(nodeId = EXIT, entryHost = route.entryIp, entryPort = 20443, connectIp = route.entryIp)
        val session = session(endpoint).copy(multiHop = route)
        val profile = EndpointSelector.profileFromCandidate(session, endpoint).copy(nokinvMode = "gaming")
        val settings = DesktopState().settings.copy(profile = profile, advancedSettings = AdvancedSettings(
            protocol = VpnProtocol.REALITY, gamingModeEnabled = true, multiHop = hops))
        val codec = StoredSettingsCodec { DesktopState().settings }
        val restored = codec.decode(codec.encode(settings))
        assertEquals(profile, restored.profile)
        assertEquals(hops, restored.advancedSettings.multiHop)
        assertEquals(VpnProtocol.REALITY, restored.advancedSettings.protocol)
        assertEquals(VpnProtocol.NOKINV, restored.advancedSettings.effectiveProtocol)
        assertTrue(VpnProfileValidator.isUsable(restored))
        val cached = JSONObject(VpnConfigFactory.buildProbe(restored.profile)).getJSONArray("outbounds").getJSONObject(0)
        assertEquals(NV_TOKEN, cached.getString("token"))
        assertEquals(7, cached.getInt("credential_generation"))
        assertEquals("gaming", cached.getString("mode"))
        val config = SingBoxConfig.generate(session, endpoint, 20909, SECRET, settings = restored, dnsPolicy = { unitDnsPolicy })
        val proxy = config.getJSONArray("outbounds").getJSONObject(0)
        assertEquals(route.entryIp, proxy.getString("server"))
        assertEquals(20443, proxy.getInt("server_port"))
        assertEquals(NV_TOKEN, proxy.getString("token"))
        assertEquals("gaming", proxy.getString("mode"))
        assertEquals(route.entryIp, config.getJSONObject("dns").getJSONArray("servers").getJSONObject(0).getString("server"))
        assertEquals(1, config.getJSONArray("inbounds").objects().count { it.optString("type") == "tun" })
        checkWithCore(config)
    }

    @Test fun `YouTube branches preserve all four main paths and precede user direct rules after app exclusions`() {
        val settings = DesktopState().settings.copy(filterMode = AppFilterMode.ALL_EXCEPT_SELECTED,
            selectedPackages = setOf("C:\\Apps\\Browser\\", "C:\\Apps\\Mail.exe"),
            advancedSettings = AdvancedSettings(youtubeAdBlockEnabled = true, bypassDomains = listOf("domain:youtube.com")))
        val geosite = SingBoxConfig.bundledGeosite()
        val domains = VpnConfigFactory.domainMatch(listOf("geosite:youtube"),
            GeositeAsset.resolveDomains(geosite, setOf("geosite:youtube")))!!
        val connections = listOf(YoutubeConnectionConfig.WireGuard(youtubeWireGuard(), ByteArray(32) { (it + 2).toByte() }),
            YoutubeConnectionConfig.SelectedTransport(EndpointSelector.profileFromCandidate(
                candidate().copy(code = "youtube", entryHost = "youtube.example.com"), UUID)))
        for ((base, current) in fourMainPaths(settings)) {
            val before = base.toString()
            assertFalse(before.contains("youtube-ru"))
            val apps = base.getJSONObject("route").getJSONArray("rules").objects().first { it.toString().contains("process_path_regex") }
            for (connection in connections) {
                val config = SingBoxConfig.withYoutube(base, current, connection, geosite)
                assertEquals(before, base.toString())
                assertMainPathUnchanged(base, config)
                val branch = when (connection) {
                    is YoutubeConnectionConfig.WireGuard -> {
                        val endpoint = config.getJSONArray("endpoints").objects().single { it.optString("tag") == "youtube-ru" }
                        assertEquals(0, endpoint.getJSONArray("peers").getJSONObject(0).getInt("persistent_keepalive_interval"))
                        assertEquals(connection.wireguard.address, endpoint.getJSONArray("address").getString(0))
                        assertEquals(connection.wireguard.mtu, endpoint.getInt("mtu"))
                        assertFalse(endpoint.getBoolean("system"))
                        endpoint
                    }
                    is YoutubeConnectionConfig.SelectedTransport -> config.getJSONArray("outbounds").objects()
                        .single { it.optString("tag") == "youtube-ru" }.also {
                            assertEquals("youtube.example.com", it.getString("server"))
                            assertEquals("vless", it.getString("type"))
                        }
                }
                assertFalse(branch.has("detour"))
                assertEquals("bootstrap", branch.getJSONObject("domain_resolver").getString("server"))
                val rows = config.getJSONObject("route").getJSONArray("rules").objects()
                val youtube = rows.filter { it.optJSONArray("rules")?.let { match ->
                    match.length() == 4 && match.getJSONObject(1).similar(domains)
                } == true }
                assertEquals(3, youtube.size)
                youtube.forEach { assertEquals("tun", it.getJSONArray("rules").getJSONObject(0).getString("inbound")) }
                assertEquals("reject", youtube[0].getString("action"))
                assertEquals(6, youtube[0].getJSONArray("rules").getJSONObject(2).getInt("ip_version"))
                assertEquals("reject", youtube[1].getString("action"))
                assertEquals("udp", youtube[1].getJSONArray("rules").getJSONObject(2).getString("network"))
                assertFalse(youtube[1].getJSONArray("rules").getJSONObject(2).has("port"))
                assertEquals("tcp", youtube[2].getJSONArray("rules").getJSONObject(2).getString("network"))
                assertEquals("youtube-ru", youtube[2].getString("outbound"))
                val appIndex = rows.indexOfFirst { it.similar(apps) }
                val youtubeIndex = rows.indexOf(youtube.first())
                val directIndex = rows.indexOfFirst { it.optString("outbound") == "direct" &&
                    it.optJSONArray("domain_suffix")?.toList()?.contains("youtube.com") == true }
                val localIndex = rows.indexOfFirst { it.has("ip_cidr") && it.optString("outbound") == "direct" }
                assertTrue(localIndex < youtubeIndex && youtubeIndex < directIndex && directIndex < appIndex)
                youtube.forEach { assertTrue(it.getJSONArray("rules").getJSONObject(3).getBoolean("invert")) }
                assertEquals(1, rows.count { it.similar(apps) })
                assertTrue(rows.first().getJSONArray("sniffer").toList().contains("quic"))
                assertEquals("AAAA", config.getJSONObject("dns").getJSONArray("rules").getJSONObject(0).getJSONArray("rules").getJSONObject(1).getString("query_type"))
                val firstDns = JSONObject(config.getJSONObject("dns").getJSONArray("rules").getJSONObject(1).toString())
                assertEquals("noki-node-dns", firstDns.getString("server"))
                firstDns.remove("server")
                assertTrue(firstDns.getJSONArray("rules").getJSONObject(0).similar(domains))
                assertTrue(firstDns.getJSONArray("rules").getJSONObject(1).getBoolean("invert"))
                assertTrue(firstDns.getJSONArray("rules").getJSONObject(1).toString().contains("process_path_regex"))
                checkWithCore(config)
            }
        }
    }

    @Test fun `YouTube disabled is unchanged and missing admission blocks only its eligible traffic`() {
        val enabled = DesktopState().settings.copy(filterMode = AppFilterMode.ONLY_SELECTED,
            selectedPackages = setOf("C:\\Apps\\Browser.exe"), advancedSettings = AdvancedSettings(youtubeAdBlockEnabled = true))
        val connection = YoutubeConnectionConfig.WireGuard(youtubeWireGuard(), ByteArray(32) { (it + 2).toByte() })
        for ((base, settings) in fourMainPaths(enabled)) {
            val disabled = settings.copy(advancedSettings = settings.advancedSettings.copy(youtubeAdBlockEnabled = false))
            val off = SingBoxConfig.withYoutube(base, disabled, connection, ByteArray(0))
            assertTrue(base.similar(off))
            assertNotSame(base, off)
            val blocked = SingBoxConfig.withYoutube(base, settings, null)
            assertMainPathUnchanged(base, blocked)
            assertFalse(blocked.toString().contains("youtube-ru"))
            val rows = blocked.getJSONObject("route").getJSONArray("rules").objects()
            val youtube = rows.filter { it.optJSONArray("rules")?.let { match ->
                match.length() == 4 && match.getJSONObject(0).optString("inbound") == "tun"
            } == true }
            assertEquals(3, youtube.size)
            assertTrue(youtube.all { it.optString("action") == "reject" && !it.has("outbound") })
            val process = rows.first { it.optString("outbound") == "direct" && it.toString().contains("process_path_regex") }
            assertTrue(process.getJSONArray("rules").getJSONObject(1).getBoolean("invert"))
            assertTrue(rows.indexOf(youtube.last()) < rows.indexOf(process))
            checkWithCore(blocked)
        }
    }

    @Test fun `YouTube ready material preserves actual transport and rejects incompatible or missing primaries`() {
        val key = ByteArray(32) { (it + 2).toByte() }
        val grant = BackendYoutubeAccess("wireguard", "ready", "grant", 1, Long.MAX_VALUE, "x".repeat(32),
            publicKeyId = "key", wireguard = youtubeWireGuard())
        assertTrue(YoutubeConnectionConfig.fromReady(grant, null, "key", key) is YoutubeConnectionConfig.WireGuard)
        rejected { YoutubeConnectionConfig.fromReady(grant, null, "another-key", key) }
        rejected { YoutubeConnectionConfig.fromReady(grant.copy(status = "pending"), null, "key", key) }
        val candidates = listOf(candidate(), nokiNv(), hysteria())
        val base = generate(session(candidate()), candidate())
        val settings = DesktopState().settings.copy(advancedSettings = AdvancedSettings(youtubeAdBlockEnabled = true))
        for (endpoint in candidates) {
            val nokiNv = EndpointTransportPolicy.isNokiNv(endpoint)
            val primary = EndpointSelector.profileFromCandidate(session(endpoint), endpoint).let {
                if (nokiNv) it.copy(nokinvMode = "gaming") else it
            }
            val selected = grant.copy(mode = "selected_transport", publicKeyId = null, wireguard = null,
                endpoint = endpoint.copy(code = "youtube", entryHost = "youtube.example.com"),
                vpnSecret = if (nokiNv) java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { 2 }) else UUID,
                nokinvDeviceId = if (nokiNv) DEVICE else null, nokinvCredentialGeneration = if (nokiNv) 9 else 0)
            val config = YoutubeConnectionConfig.fromReady(selected, primary, "key", key, expectedDeviceId = DEVICE) as YoutubeConnectionConfig.SelectedTransport
            assertEquals(primary.transport, config.profile.transport)
            assertNull(config.profile.multiHop)
            if (nokiNv) {
                assertEquals(selected.vpnSecret, config.profile.nokinvToken)
                assertEquals(9, config.profile.nokinvCredentialGeneration)
                assertEquals("gaming", config.profile.nokinvMode)
                rejected { YoutubeConnectionConfig.fromReady(selected.copy(nokinvDeviceId = null), primary, "key", key, DEVICE) }
                rejected { YoutubeConnectionConfig.fromReady(selected.copy(nokinvDeviceId = ENTRY), primary, "key", key, DEVICE) }
                rejected { YoutubeConnectionConfig.fromReady(selected.copy(nokinvCredentialGeneration = 0), primary, "key", key, DEVICE) }
                rejected { YoutubeConnectionConfig.fromReady(selected.copy(vpnSecret = UUID), primary, "key", key, DEVICE) }
            }
            val output = SingBoxConfig.withYoutube(base, settings, config)
            assertMainPathUnchanged(base, output)
            assertFalse(output.getJSONArray("outbounds").objects().single { it.optString("tag") == "youtube-ru" }.has("detour"))
            checkWithCore(output)
            rejected { YoutubeConnectionConfig.fromReady(selected, null, "key", key, DEVICE) }
            rejected { YoutubeConnectionConfig.fromReady(selected, primary.copy(endpointCode = ""), "key", key, DEVICE) }
            rejected { YoutubeConnectionConfig.fromReady(selected, primary.copy(security = "none"), "key", key, DEVICE) }
            rejected { YoutubeConnectionConfig.fromReady(selected.copy(endpoint = endpoint.copy(allowInsecure = true)), primary, "key", key, DEVICE) }
        }
        val primary = EndpointSelector.profileFromCandidate(session(candidate()), candidate())
        val mismatched = grant.copy(mode = "selected_transport", publicKeyId = null, wireguard = null, endpoint = hysteria(), vpnSecret = UUID)
        rejected { YoutubeConnectionConfig.fromReady(mismatched, primary, "key", key) }
        rejected { YoutubeConnectionConfig.fromReady(mismatched.copy(endpoint = candidate().copy(transportMode = "packet-up")), primary, "key", key) }
    }

    private fun fourMainPaths(settings: StoredSettings): List<Pair<JSONObject, StoredSettings>> {
        val rules = listOf(DesktopRoutingRule("youtube-direct", "YouTube", true, listOf("domain:youtube.com")))
        val endpoint = nokiNv()
        val direct = SingBoxConfig.generate(session(endpoint), endpoint, 20909, SECRET, settings = settings, routingRules = rules, dnsPolicy = { unitDnsPolicy })
        val hops = MultiHopSettings(true, HopSelection(ServerSelectionMode.SERVER, nodeId = ENTRY), HopSelection(ServerSelectionMode.SERVER, nodeId = EXIT))
        val hop = BackendMultiHopSession(hops, ENTRY, EXIT, "203.0.113.20", "a".repeat(64))
        val entry = endpoint.copy(nodeId = EXIT, entryHost = hop.entryIp, entryPort = 20443, connectIp = hop.entryIp)
        val multiSettings = settings.copy(advancedSettings = settings.advancedSettings.copy(multiHop = hops))
        val multi = SingBoxConfig.generate(session(entry).copy(multiHop = hop), entry, 20909, SECRET, settings = multiSettings, routingRules = rules, dnsPolicy = { unitDnsPolicy })
        val ruSettings = settings.copy(advancedSettings = settings.advancedSettings.copy(ruRelayEnabled = true))
        val ruMultiSettings = ruSettings.copy(advancedSettings = ruSettings.advancedSettings.copy(multiHop = hops))
        val identity = BackendRuWireGuardIdentity("request", "session", 1, "key", RuRelaySelection.Auto, "relay", EXIT,
            routeVersion = 1, routeId = "route", routeGeneration = 1, entryNodeId = null, leaseExpiresAtEpochMillis = null, revokeToken = "x".repeat(32))
        fun ru(current: StoredSettings, entryNodeId: String?) = SingBoxConfig.generateWireGuard(
            BackendRuWireGuardSession.Ready(identity.copy(entryNodeId = entryNodeId), youtubeWireGuard().copy(endpoint = "203.0.113.25:51820")),
            ByteArray(32) { (it + 2).toByte() }, 20909, SECRET, 20808, "https://api.noki.example", current, routingRules = rules, dnsPolicy = { unitDnsPolicy })
        return listOf(direct to settings, multi to multiSettings, ru(ruSettings, null) to ruSettings, ru(ruMultiSettings, ENTRY) to ruMultiSettings)
    }

    private fun youtubeWireGuard() = BackendRuWireGuardConfig("youtube.example.com:51820",
        java.util.Base64.getEncoder().encodeToString(ByteArray(32) { (it + 1).toByte() }), "10.201.0.2/32", listOf("10.201.0.1"), 1280, listOf("0.0.0.0/0"))

    private fun assertMainPathUnchanged(base: JSONObject, composed: JSONObject) {
        assertEquals("proxy", composed.getJSONObject("route").getString("final"))
        for (field in listOf("outbounds", "endpoints")) base.optJSONArray(field)?.objects()?.forEach { original ->
            assertTrue(composed.getJSONArray(field).objects().single { it.optString("tag") == original.optString("tag") }.similar(original))
        }
        assertTrue(base.getJSONObject("dns").getJSONArray("servers").similar(composed.getJSONObject("dns").getJSONArray("servers")))
        assertTrue(base.getJSONArray("inbounds").similar(composed.getJSONArray("inbounds")))
    }

    private fun JSONArray.objects(): List<JSONObject> = (0 until length()).map(::getJSONObject)

    @Test fun `routing regexp validation agrees with pinned Go core for supported and unsupported syntax`() {
        val endpoint = candidate()
        val patterns = listOf("(?i)^(?:www\\.)?example\\.com$", "(?P<host>foo|bar)\\.example\\.com", "[[:alpha:]]{1,20}\\.com", "\\p{Greek}+", "a{1000}")
        for (pattern in patterns) {
            val rule = DesktopRoutingRule("regexp", "Сервисы", false, listOf("regexp:$pattern")).normalized()
            val config = SingBoxConfig.generate(session(endpoint), endpoint, 20909, SECRET, routingRules = listOf(rule), dnsPolicy = { unitDnsPolicy })
            checkWithCore(config)
        }
        for (pattern in listOf("(?<=foo)bar", "(foo)\\1", "a++", "a{1001}")) {
            rejected { DesktopRoutingRule("regexp", "Сервисы", false, listOf("regexp:$pattern")).normalized() }
            val config = generate(session(endpoint), endpoint)
            config.getJSONObject("route").getJSONArray("rules").put(JSONObject()
                .put("domain_regex", org.json.JSONArray(listOf(pattern))).put("outbound", "direct"))
            checkWithCore(config, valid = false)
        }
    }

    @Test fun `application targets share rule action with domain and IP and keep DNS priority`() {
        val endpoint = candidate()
        val settings = DesktopState().settings.copy(advancedSettings = AdvancedSettings())
        val proxy = DesktopRoutingRule("browser", "Browser", false, applications = listOf("C:\\Apps\\Browser.exe"))
        val direct = DesktopRoutingRule("direct", "Direct", true, listOf("domain:example.test"), listOf("192.0.2.0/24"),
            listOf("C:\\Apps\\Games\\", "C:\\Apps\\Mail.exe"))
        val config = SingBoxConfig.generate(session(endpoint), endpoint, 20909, SECRET, settings = settings,
            routingRules = listOf(proxy, direct), dnsPolicy = { unitDnsPolicy })
        val rules = config.getJSONObject("route").getJSONArray("rules").objects().filter { it.optString("action") == "route" }
        val directRule = rules.single { it.optString("outbound") == "direct" && it.toString().contains("example.test") }
        val targets = directRule.getJSONArray("rules").getJSONObject(1).getJSONArray("rules")
        assertEquals(3, targets.length()) // Domain OR IP OR executable/folder.
        assertTrue(targets.getJSONObject(2).toString().contains("process_path_regex"))
        assertTrue(rules.any { it.optString("outbound") == "proxy" && it.toString().contains("Browser") })
        val bypass = requireNotNull(SingBoxConfig.dnsBypassMatch(settings, listOf(proxy, direct), SingBoxConfig.bundledGeosite()))
        assertTrue(bypass.toString().contains("svchost"))
        assertTrue(bypass.toString().contains("Browser"))
        assertTrue(bypass.toString().contains("invert"))
        assertFalse(bypass.toString().contains("ip_cidr"))
        checkWithCore(config)
        val migrated = SingBoxConfig.generate(session(endpoint), endpoint, 20909, SECRET, settings = settings,
            routingRules = listOf(proxy, DesktopRoutingRule("other", "Other", true, ips = listOf("0.0.0.0/0", "::/0"))), dnsPolicy = { unitDnsPolicy })
        assertEquals("direct", migrated.getJSONObject("route").getJSONArray("rules").objects().last { it.optString("action") == "route" }.getString("outbound"))
        checkWithCore(migrated)
        val disabled = SingBoxConfig.generate(session(endpoint), endpoint, 20909, SECRET, settings = settings,
            routingRules = listOf(DesktopRoutingRule.RUSSIAN_RESOURCES.copy(enabled = false)), dnsPolicy = { unitDnsPolicy })
        val empty = SingBoxConfig.generate(session(endpoint), endpoint, 20909, SECRET, settings = settings, dnsPolicy = { unitDnsPolicy })
        assertTrue(disabled.getJSONObject("route").similar(empty.getJSONObject("route")))
        assertTrue(disabled.getJSONObject("dns").similar(empty.getJSONObject("dns")))
    }

    @Test fun `imported geosite and geoip rule sets reuse the rule action and only domain lists bypass DNS`() {
        val directory = Files.createTempDirectory("noki-config-lists")
        try {
            val file = directory.resolve("ruleset-" + "a".repeat(64) + ".json")
            val dns = directory.resolve("ruleset-" + "a".repeat(64) + ".dns.json")
            val content = JSONObject().put("version", 3).put("rules", JSONArray().put(JSONObject().put("domain_suffix", JSONArray().put("example.test"))))
            Files.writeString(file, content.toString()); Files.writeString(dns, content.toString())
            val source = DesktopRuleSet(file.toString(), "geosite:custom", dns.toString())
            val ipFile = directory.resolve("ruleset-" + "b".repeat(64) + ".json")
            Files.writeString(ipFile, """{"version":3,"rules":[{"ip_cidr":["192.0.2.0/24"]}]}""")
            val ipSource = DesktopRuleSet(ipFile.toString(), "geoip:custom")
            val rule = DesktopRoutingRule("custom", "Custom", true, domains = listOf("geosite:custom"),
                ips = listOf("geoip:custom"), ruleSets = listOf(source, ipSource)).normalized()
            val endpoint = candidate()
            val settings = DesktopState().settings.copy(advancedSettings = AdvancedSettings())
            val config = SingBoxConfig.generate(session(endpoint), endpoint, 20909, SECRET, settings = settings, routingRules = listOf(rule), dnsPolicy = { unitDnsPolicy })
            assertEquals(3, config.getJSONObject("route").getJSONArray("rule_set").length())
            assertEquals(source.tag + "-dns", SingBoxConfig.dnsBypassMatch(settings, listOf(rule), SingBoxConfig.bundledGeosite())!!.getJSONArray("rule_set").getString(0))
            assertNull(SingBoxConfig.dnsBypassMatch(settings, listOf(rule.copy(ruleSets = listOf(source.copy(dnsPath = null)))), SingBoxConfig.bundledGeosite()))
            assertTrue(runCatching { SingBoxConfig.generate(session(endpoint), endpoint, 20909, SECRET,
                settings = settings, routingRules = listOf(rule.copy(ruleSets = listOf(source))), dnsPolicy = { unitDnsPolicy }) }.isFailure)
            checkWithCore(config)
        } finally { Files.list(directory).use { it.forEach(Files::delete) }; Files.delete(directory) }
    }

    @Test fun `named rules match domains or IPs and folder selection includes child processes`() {
        val rule = DesktopRoutingRule("rule", "Рабочие сервисы", true,
            listOf("domain:example.com"), listOf("192.0.2.0/24", "2001:db8::/32")).normalized()
        val endpoint = candidate()
        val settings = DesktopState().settings.copy(filterMode = AppFilterMode.ALL_EXCEPT_SELECTED, selectedPackages = setOf("C:\\Apps\\Browser\\", "C:\\Apps\\Mail.exe"))
        val config = SingBoxConfig.generate(session(endpoint), endpoint, 20909, SECRET, settings = settings, routingRules = listOf(rule), dnsPolicy = { unitDnsPolicy })
        val rules = config.getJSONObject("route").getJSONArray("rules")
        val scoped = (0 until rules.length()).map { rules.getJSONObject(it) }.first {
            it.optString("mode") == "and" && it.optJSONArray("rules")?.optJSONObject(1)?.optString("mode") == "or"
        }
        assertEquals("direct", scoped.getString("outbound"))
        assertEquals("tun", scoped.getJSONArray("rules").getJSONObject(0).getString("inbound"))
        val named = scoped.getJSONArray("rules").getJSONObject(1)
        assertEquals("example.com", named.getJSONArray("rules").getJSONObject(0).getJSONArray("domain_suffix").getString(0))
        assertEquals("2001:db8::/32", named.getJSONArray("rules").getJSONObject(1).getJSONArray("ip_cidr").getString(1))
        assertTrue(config.toString().contains("process_path_regex"))
        checkWithCore(config)
        rejected { rule.copy(ips = listOf("999.2.3.4/24")).normalized() }
        rejected { rule.copy(ips = listOf("example.com")).normalized() }
        rejected { rule.copy(ips = listOf("192.0.2.1/33")).normalized() }
    }

    @Test fun `multihop countries survive the session API round trip`() = kotlinx.coroutines.runBlocking {
        val saved = MultiHopSettings(true, HopSelection(ServerSelectionMode.COUNTRY, "PL"), HopSelection(ServerSelectionMode.COUNTRY, "RO"))
        val resolved = DesktopController.resolveMultiHop(saved, listOf(
            VpnServer(ENTRY, "Poland", "PL", "pl", "example.invalid", null, true, multiHopEntryAvailable = true),
            VpnServer(EXIT, "Romania", "RO", "ro", "example.invalid", null, true, multiHopExitAvailable = true)), false)
        val fixture = generateSequence(Path.of(System.getProperty("user.dir"))) { it.parent }
            .map { it.resolve("contracts/multihop_session_v2.json") }.first { Files.isRegularFile(it) }
        val payload = JSONObject(Files.readString(fixture))
        val route = payload.getJSONObject("multihop")
        route.put("entry_node_id", ENTRY).put("exit_node_id", EXIT)
        val selection = route.getJSONObject("selection")
        selection.getJSONObject("entry").put("node_id", ENTRY).put("country_code", JSONObject.NULL)
        selection.getJSONObject("exit").put("node_id", EXIT).put("country_code", JSONObject.NULL)
        val candidates = payload.getJSONArray("endpoint_candidates")
        for (i in 0 until candidates.length()) candidates.getJSONObject(i).put("node_id", EXIT)
        val http = okhttp3.OkHttpClient.Builder().addInterceptor { chain ->
            val body = okio.Buffer().also { chain.request().body!!.writeTo(it) }
            val request = JSONObject(body.readUtf8())
            assertFalse(request.has("country_code"))
            assertFalse(request.has("node_id"))
            val hops = request.getJSONObject("multihop")
            assertEquals(ENTRY, hops.getJSONObject("entry").getString("node_id"))
            assertEquals(EXIT, hops.getJSONObject("exit").getString("node_id"))
            okhttp3.Response.Builder().request(chain.request()).protocol(okhttp3.Protocol.HTTP_1_1).code(200).message("OK")
                .body(payload.toString().toResponseBody("application/json".toMediaType())).build()
        }.build()
        val api = BackendApiClient(http, "https://fixture.invalid")
        val session = api.createVpnSession("token", "device", "proof", "nonce", "signature",
            null, null, null, "auto", null, resolved)
        assertEquals(resolved, session.multiHop!!.selection)
        val endpoint = SingBoxConfig.supportedCandidates(session).first()
        val settings = DesktopState().settings.copy(advancedSettings = AdvancedSettings(multiHop = resolved))
        val config = SingBoxConfig.generate(session, endpoint, 20909, SECRET, settings = settings, dnsPolicy = { unitDnsPolicy })
        assertEquals(route.getString("entry_ip"), config.getJSONArray("outbounds").getJSONObject(0).getString("server"))
        assertEquals(ServerSelectionMode.COUNTRY, saved.entry!!.kind)
        assertEquals("PL", saved.entry!!.countryCode)
        assertEquals("RO", saved.exit!!.countryCode)
        for (invalid in listOf(JSONObject(selection.getJSONObject("entry").toString()).put("country_code", "PL"),
                JSONObject(selection.getJSONObject("entry").toString()).put("unexpected", JSONObject.NULL))) {
            selection.put("entry", invalid)
            assertTrue(runCatching { api.createVpnSession("token", "device", "proof", "nonce", "signature",
                null, null, null, "auto", null, resolved) }.isFailure)
        }
    }

    @Test fun `multihop countries connect to entry IP while preserving the original exit contract`() {
        val selection = DesktopController.resolveMultiHop(MultiHopSettings(true,
            HopSelection(ServerSelectionMode.COUNTRY, "NL"), HopSelection(ServerSelectionMode.COUNTRY, "PL")), listOf(
            VpnServer(ENTRY, "Entry", "NL", "nl", "example.invalid", null, true, multiHopEntryAvailable = true),
            VpnServer(EXIT, "Exit", "PL", "pl", "example.invalid", null, true, multiHopExitAvailable = true)), false)
        val hop = BackendMultiHopSession(selection, ENTRY, EXIT, "203.0.113.20", "a".repeat(64))
        val endpoint = candidate().copy(nodeId = EXIT, entryHost = hop.entryIp, entryPort = 20443, connectIp = hop.entryIp)
        val session = session(endpoint).copy(multiHop = hop)
        val settings = DesktopState().settings.copy(advancedSettings = AdvancedSettings(multiHop = selection))
        val config = SingBoxConfig.generate(session, endpoint, 20909, SECRET, settings = settings, dnsPolicy = { unitDnsPolicy })
        assertEquals(hop.entryIp, config.getJSONArray("outbounds").getJSONObject(0).getString("server"))
        assertEquals(20443, config.getJSONArray("outbounds").getJSONObject(0).getInt("server_port"))
        assertEquals("vpn.example.com", config.getJSONArray("outbounds").getJSONObject(0).getJSONObject("tls").getString("server_name"))
        checkWithCore(config)
        assertTrue(SingBoxConfig.supportedCandidates(session.copy(multiHop = hop.copy(version = 1))).isEmpty())
        assertTrue(SingBoxConfig.supportedCandidates(session.copy(endpointCandidates = listOf(endpoint.copy(connectIp = "203.0.113.30")))).isEmpty())
        rejected { SingBoxConfig.generate(session.copy(endpointCandidates = listOf(endpoint.copy(nodeId = ENTRY))), endpoint.copy(nodeId = ENTRY), 20909, SECRET, settings = settings, dnsPolicy = { unitDnsPolicy }) }
        assertTrue(SingBoxConfig.supportedCandidates(session.copy(endpointCandidates = emptyList())).isEmpty())
        assertTrue(SingBoxConfig.supportedCandidates(session.copy(endpointCandidates = listOf(endpoint.copy(entryPort = 443)))).isEmpty())
        rejected { SingBoxConfig.generate(session, endpoint, 20909, SECRET, settings = settings.copy(advancedSettings = AdvancedSettings()), dnsPolicy = { unitDnsPolicy }) }
    }

    @Test fun `default Russian traffic rule also excludes its domains from protected DNS`() {
        val endpoint = candidate()
        val settings = DesktopState().settings.copy(filterMode = AppFilterMode.ONLY_SELECTED, selectedPackages = setOf("C:\\Apps\\Browser.exe"))
        val config = SingBoxConfig.generate(session(endpoint), endpoint, 20909, SECRET, settings = settings,
            routingRules = listOf(DesktopRoutingRule.RUSSIAN_RESOURCES, DesktopRoutingRule("proxy", "VPN", false, listOf("domain:example.ru"))), dnsPolicy = { unitDnsPolicy })
        val rules = config.getJSONObject("route").getJSONArray("rules")
        val rows = (0 until rules.length()).map { rules.getJSONObject(it) }
        val russianDirect = rows.indexOfFirst { it.optString("outbound") == "direct" && it.toString().contains("koronapay.com") }
        assertTrue(russianDirect >= 0)
        val russianRule = rows[russianDirect].getJSONArray("rules").getJSONObject(1)
        val dns = config.getJSONObject("dns")
        assertEquals("noki-node-dns", dns.getString("final"))
        assertTrue(dns.getJSONArray("rules").objects().any { it.optString("action") == "evaluate" && it.toString().contains("koronapay.com") })
        assertTrue(config.getJSONArray("inbounds").getJSONObject(0).getJSONArray("route_address").toList().containsAll(listOf("0.0.0.0/1", "128.0.0.0/1")))
        val routeMatches = russianRule.getJSONArray("rules")
        for (field in listOf("domain_suffix", "domain")) {
            val match = (0 until routeMatches.length()).map { routeMatches.getJSONObject(it) }.single { it.has(field) }
            if (field == "domain_suffix") assertTrue(match.getJSONArray(field).toList().contains("ru"))
        }
        val russianProxy = rows.indexOfFirst { it.optString("outbound") == "proxy" }
        val processRule = rows.indexOfLast { it.optString("mode") == "and" }
        assertTrue(russianDirect >= 0 && russianDirect < russianProxy && russianProxy < processRule)
        checkWithCore(config)
    }

    @Test fun `editing or deleting Russian traffic rule removes its DNS exceptions`() {
        val endpoint = candidate()
        val throughVpn = SingBoxConfig.generate(session(endpoint), endpoint, 20909, SECRET,
            routingRules = listOf(DesktopRoutingRule.RUSSIAN_RESOURCES.copy(bypass = false)), dnsPolicy = { unitDnsPolicy })
        val rules = throughVpn.getJSONObject("route").getJSONArray("rules")
        val russian = (0 until rules.length()).map { rules.getJSONObject(it) }.single { it.optString("action") == "route" && it.toString().contains("koronapay.com") }
        assertEquals("proxy", russian.getString("outbound"))
        assertEquals(1, throughVpn.getJSONObject("dns").getJSONArray("rules").length())
        checkWithCore(throughVpn)
        val deleted = generate(session(endpoint), endpoint)
        assertFalse(deleted.toString().contains("koronapay.com"))
        assertEquals(2, deleted.getJSONObject("dns").getJSONArray("rules").length())
        checkWithCore(deleted)
    }

    @Test fun `RU WireGuard uses native endpoint and the supplied DNS through the tunnel`() {
        val identity = BackendRuWireGuardIdentity("request", "session", 1, "key", RuRelaySelection.Auto, "relay", EXIT,
            routeVersion = 1, routeId = "route", routeGeneration = 1, entryNodeId = null, leaseExpiresAtEpochMillis = null, revokeToken = "x".repeat(32))
        val wireguard = BackendRuWireGuardConfig("203.0.113.25:51820", java.util.Base64.getEncoder().encodeToString(ByteArray(32) { (it + 1).toByte() }),
            "10.200.0.2/32", listOf("10.200.0.1"), 1400, listOf("0.0.0.0/0"))
        val config = SingBoxConfig.generateWireGuard(BackendRuWireGuardSession.Ready(identity, wireguard), ByteArray(32) { (it + 2).toByte() },
            20909, SECRET, 20808, "https://api.noki.example", DesktopState().settings.copy(advancedSettings = AdvancedSettings(ruRelayEnabled = true)),
            routingRules = listOf(DesktopRoutingRule.RUSSIAN_RESOURCES), dnsPolicy = { unitDnsPolicy })
        val endpoint = config.getJSONArray("endpoints").getJSONObject(0)
        assertEquals("wireguard", endpoint.getString("type"))
        assertFalse(endpoint.getBoolean("system"))
        val servers = config.getJSONObject("dns").getJSONArray("servers")
        val remote = (0 until servers.length()).map { servers.getJSONObject(it) }.single { it.getString("tag") == "noki-node-dns" }
        assertEquals("10.200.0.1", remote.getString("server"))
        assertEquals("proxy", remote.getString("detour"))
        assertTrue(config.getJSONObject("dns").getJSONArray("rules").length() > 1)
        checkWithCore(config)
        val hops = MultiHopSettings(true, HopSelection(ServerSelectionMode.SERVER, nodeId = ENTRY), HopSelection(ServerSelectionMode.SERVER, nodeId = EXIT))
        val multi = SingBoxConfig.generateWireGuard(BackendRuWireGuardSession.Ready(identity.copy(entryNodeId = ENTRY), wireguard), ByteArray(32) { (it + 2).toByte() },
            20909, SECRET, 20808, "https://api.noki.example", DesktopState().settings.copy(advancedSettings = AdvancedSettings(ruRelayEnabled = true, multiHop = hops)),
            routingRules = listOf(DesktopRoutingRule.RUSSIAN_RESOURCES), dnsPolicy = { unitDnsPolicy })
        assertEquals(endpoint.toString(), multi.getJSONArray("endpoints").getJSONObject(0).toString())
        checkWithCore(multi)
    }
    @Test fun `only selected apps cannot redirect the protected probe inbound`() {
        val candidate = candidate()
        val settings = DesktopState().settings.copy(filterMode = AppFilterMode.ONLY_SELECTED,
            selectedPackages = setOf("C:\\Apps\\Browser.exe"))
        val config = SingBoxConfig.generate(session(candidate), candidate, 20909, SECRET, settings = settings, dnsPolicy = { unitDnsPolicy })
        val rules = config.getJSONObject("route").getJSONArray("rules")
        val direct = (0 until rules.length()).map { rules.getJSONObject(it) }.first { it.optString("mode") == "and" && it.optString("outbound") == "direct" }
        assertEquals("logical", direct.getString("type"))
        assertEquals("and", direct.getString("mode"))
        assertEquals("tun", direct.getJSONArray("rules").getJSONObject(0).getString("inbound"))
        assertTrue(direct.getJSONArray("rules").getJSONObject(1).getBoolean("invert"))
        checkWithCore(config)
    }

    @Test fun `Android domain priorities and log policy survive translation`() {
        val candidate = candidate()
        val settings = DesktopState().settings.copy(advancedSettings = DesktopState().settings.advancedSettings.copy(
            alwaysOnDomains = listOf("domain:example.com"), bypassDomains = listOf("domain:bypass.example.com"),
            connectionLogsEnabled = true, errorLogsEnabled = true))
        val config = SingBoxConfig.generate(session(candidate), candidate, 20909, SECRET, settings = settings, dnsPolicy = { unitDnsPolicy })
        assertEquals("error", config.getJSONObject("log").getString("level"))
        val rules = config.getJSONObject("route").getJSONArray("rules")
        val domainRules = (0 until rules.length()).map { rules.getJSONObject(it) }.filter { it.has("domain_suffix") }
        assertEquals("proxy", domainRules.first { it.getJSONArray("domain_suffix").toList().contains("example.com") }.getString("outbound"))
        assertEquals("direct", domainRules.first { it.getJSONArray("domain_suffix").toList().contains("bypass.example.com") }.getString("outbound"))
        checkWithCore(config)
    }

    private fun checkWithCore(config: JSONObject, valid: Boolean = true) {
        val binary = Path.of("build/runtime/windows-x64/sing-box.exe").toAbsolutePath()
        assertTrue("Pinned core must be prepared", Files.isRegularFile(binary))
        SingBoxRuntime.verifyRuntimeBinary(binary)
        val file = Files.createTempFile("noki-config-check-", ".json")
        try {
            Files.writeString(file, config.toString())
            val process = ProcessBuilder(binary.toString(), "check", "-c", file.toString()).redirectErrorStream(true).start()
            if (!process.waitFor(15, TimeUnit.SECONDS)) { process.destroyForcibly(); fail("sing-box check timed out") }
            val output = process.inputStream.bufferedReader().use { it.readText() }
            if (valid) assertEquals(output, 0, process.exitValue()) else assertNotEquals(output, 0, process.exitValue())
        } finally { Files.deleteIfExists(file) }
    }
    @Test fun `Reality XHTTP preserves credentials TLS verification and stream-up transport`() {
        val candidate = candidate().copy(requestHost = "cover.example.com")
        val config = generate(session(candidate), candidate)
        val proxy = config.getJSONArray("outbounds").getJSONObject(0)
        assertEquals("vless", proxy.getString("type"))
        assertEquals(UUID, proxy.getString("uuid"))
        assertFalse(proxy.has("flow"))
        val transport = proxy.getJSONObject("transport")
        assertEquals("xhttp", transport.getString("type"))
        assertEquals("stream-up", transport.getString("mode"))
        assertEquals("/noki", transport.getString("path"))
        assertEquals("cover.example.com", transport.getString("host"))
        assertFalse(proxy.has("network"))
        val tls = proxy.getJSONObject("tls")
        assertFalse(tls.optBoolean("insecure"))
        assertEquals("vpn.example.com", tls.getString("server_name"))
        assertEquals(KEY, tls.getJSONObject("reality").getString("public_key"))
        assertEquals("0123456789abcdef", tls.getJSONObject("reality").getString("short_id"))
        assertEquals("chrome", tls.getJSONObject("utls").getString("fingerprint"))
    }

    @Test fun `Android Hysteria maps to Hysteria2 password without UUID parsing`() {
        val candidate = hysteria().copy(transport = "hy2")
        val session = session(candidate).copy(vpnSecret = "test-password-without-UUID")
        val proxy = generate(session, candidate).getJSONArray("outbounds").getJSONObject(0)
        assertEquals("hysteria2", proxy.getString("type"))
        assertEquals(session.vpnSecret, proxy.getString("password"))
        assertFalse(proxy.has("uuid"))
        assertFalse(proxy.getJSONObject("tls").has("utls"))
        assertFalse(proxy.getJSONObject("tls").optBoolean("insecure"))
        assertEquals("h3", proxy.getJSONObject("tls").getJSONArray("alpn").getString(0))
    }

    @Test fun `unsupported or unsafe candidates are removed and never emitted`() {
        val base = candidate()
        val invalid = listOf(
            base.copy(canaryOnly = true), base.copy(allowInsecure = true),
            base.copy(transportMode = "packet-up"),
            base.copy(transport = "raw", transportMode = "stream-up", path = null, flow = "xtls-rprx-vision"),
            base.copy(transport = "tcp", transportMode = "packet-up", path = null),
            base.copy(security = "tls", publicKey = null, shortId = null, transport = "tcp", transportMode = null, path = null),
            base.copy(flow = "unrecognized"), hysteria().copy(flow = "xtls-rprx-vision"),
            base.copy(entryPort = 0), base.copy(entryPort = 65536), base.copy(serverName = "https://vpn.example.com"),
            base.copy(serverName = "127.0.0.1"), base.copy(connectIp = "not-an-ip"), base.copy(fingerprint = "unsupported"),
            base.copy(security = "reality", publicKey = "not-a-key"),
            base.copy(security = "reality", shortId = "f"), base.copy(security = "reality", shortId = "0123456789abcdef00"),
        )
        val session = session(base).copy(endpointCandidates = invalid + base)
        assertEquals(listOf(base), SingBoxConfig.supportedCandidates(session))
        invalid.forEach { rejected { generate(session, it) } }
        rejected { generate(session.copy(vpnSecret = "1-1-1-1-1"), base) }
        rejected { generate(session.copy(canConnect = false), base) }
    }

    @Test fun `old Xray mux is rejected by the native profile contract`() {
        val candidate = candidate().copy(enableMux = true)
        val session = session(candidate)
        assertTrue(SingBoxConfig.supportedCandidates(session).isEmpty())
        rejected { generate(session, candidate) }
    }

    @Test fun `pinned Windows core accepts all supported generated protocol configs`() {
        val binary = Path.of("build/runtime/windows-x64/sing-box.exe").toAbsolutePath()
        assumeTrue("Pinned Windows core is downloaded by the desktop packaging script", Files.isRegularFile(binary))
        val candidates = listOf(nokiNv(), candidate().copy(requestHost = "cover.example.com"), hysteria())
        candidates.forEach { candidate -> checkWithCore(generate(session(candidate), candidate)) }
    }

    @Test fun `empty candidate list uses validated legacy session values`() {
        val candidate = candidate().copy(connectIp = "203.0.113.10", alpn = "h2,http/1.1")
        val session = session(candidate).copy(endpointCandidates = emptyList())
        val fallback = SingBoxConfig.supportedCandidates(session).single()
        assertEquals(candidate.entryHost, fallback.entryHost)
        assertEquals(candidate.connectIp, fallback.connectIp)
        val proxy = generate(session, fallback).getJSONArray("outbounds").getJSONObject(0)
        assertEquals("203.0.113.10", proxy.getString("server"))
        assertEquals(listOf("h2", "http/1.1"), proxy.getJSONObject("tls").getJSONArray("alpn").toList())
        assertTrue(SingBoxConfig.supportedCandidates(session.copy(allowInsecure = true)).isEmpty())
        assertTrue(SingBoxConfig.supportedCandidates(session.copy(transportMode = "packet-up")).isEmpty())
    }

    @Test fun `regular DNS uses proxy and bootstrap resolves only endpoint and API`() {
        val candidate = candidate()
        val config = generate(session(candidate), candidate)
        val dns = config.getJSONObject("dns")
        assertEquals("noki-node-dns", dns.getString("final"))
        val remote = dns.getJSONArray("servers").getJSONObject(0)
        assertEquals("tcp", remote.getString("type"))
        assertEquals("proxy", remote.getString("detour"))
        assertEquals("198.18.0.1", remote.getString("server"))
        val bootstrap = dns.getJSONArray("servers").getJSONObject(1)
        assertEquals("local", bootstrap.getString("type"))
        assertEquals(2, dns.getJSONArray("rules").length())
        assertEquals("api.noki.example", dns.getJSONArray("rules").getJSONObject(0).getJSONArray("domain").getString(0))
        val protectedIpv6 = dns.getJSONArray("rules").getJSONObject(1)
        assertEquals("AAAA", protectedIpv6.getString("query_type"))
        assertEquals("predefined", protectedIpv6.getString("action"))
        assertEquals("NOERROR", protectedIpv6.getString("rcode"))
        val route = config.getJSONObject("route")
        assertEquals("sniff", route.getJSONArray("rules").getJSONObject(0).getString("action"))
        val apiRule = route.getJSONArray("rules").getJSONObject(1)
        assertEquals("api.noki.example", apiRule.getJSONArray("domain").getString(0))
        assertEquals("tun", apiRule.getString("inbound"))
        assertEquals("direct", apiRule.getString("outbound"))
        assertTrue(route.getBoolean("auto_detect_interface"))
        assertEquals("proxy", route.getString("final"))
        assertEquals("noki-node-dns", route.getJSONObject("default_domain_resolver").getString("server"))
        val tun = config.getJSONArray("inbounds").getJSONObject(0)
        assertTrue(tun.getBoolean("auto_route"))
        assertEquals("172.19.0.2", tun.getJSONArray("dns_address").getString(0))
        assertTrue(tun.getBoolean("strict_route"))
        assertEquals("hijack", tun.getString("dns_mode"))
        assertEquals("hijack-dns", route.getJSONArray("rules").getJSONObject(2).getString("action"))
    }

    @Test fun `control and probe stay on localhost with bounded distinct ports`() {
        val candidate = candidate()
        val session = session(candidate)
        val config = generate(session, candidate)
        val control = config.getJSONObject("experimental").getJSONObject("clash_api")
        assertEquals("127.0.0.1:20909", control.getString("external_controller"))
        assertEquals(SECRET, control.getString("secret"))
        val mixed = config.getJSONArray("inbounds").getJSONObject(1)
        assertEquals("127.0.0.1", mixed.getString("listen"))
        assertEquals(20808, mixed.getInt("listen_port"))
        rejected { SingBoxConfig.generate(session, candidate, 0, SECRET, dnsPolicy = { unitDnsPolicy }) }
        rejected { SingBoxConfig.generate(session, candidate, 20808, SECRET, dnsPolicy = { unitDnsPolicy }) }
        rejected { SingBoxConfig.generate(session, candidate, 20909, "", dnsPolicy = { unitDnsPolicy }) }
        rejected { SingBoxConfig.generate(session, candidate, 20909, SECRET, apiOrigin = "http://api.noki.example", dnsPolicy = { unitDnsPolicy }) }
        rejected { SingBoxConfig.generate(session, candidate.copy(entryHost = "other.example.com"), 20909, SECRET, dnsPolicy = { unitDnsPolicy }) }
    }

    private fun generate(session: BackendVpnSession, candidate: BackendEndpointCandidate): JSONObject =
        SingBoxConfig.generate(session, candidate, 20909, SECRET, apiOrigin = "https://api.noki.example", dnsPolicy = { unitDnsPolicy })

    private fun rejected(block: () -> Unit) {
        try { block(); fail("Unsupported configuration was accepted") } catch (_: IllegalArgumentException) { }
    }

    private fun candidate() = BackendEndpointCandidate(
        code = "test", nodeId = "test-node", label = "Test", locationCode = "test", locationName = "Test",
        entryHost = "vpn.example.com", entryPort = 443, serverName = "vpn.example.com", proxyType = "vless",
        transport = "xhttp", transportMode = "stream-up", security = "reality", fingerprint = "chrome", requestHost = null,
        path = "/noki", alpn = null, allowInsecure = false, enableMux = false, randomUserAgent = false,
        publicKey = KEY, shortId = "0123456789abcdef", flow = null, priority = 10, weight = 1,
        canaryOnly = false, tags = emptyList(),
    )

    private fun hysteria() = candidate().copy(proxyType = "hysteria", transport = "hysteria", transportMode = null,
        security = "tls", fingerprint = null, path = null, publicKey = null, shortId = null)

    private fun nokiNv() = hysteria().copy(proxyType = "nokinv", transport = "nokinv", path = "/tunnel",
        nokinvToken = NV_TOKEN, nokinvCredentialGeneration = 7)

    private fun session(candidate: BackendEndpointCandidate) = BackendVpnSession(
        canConnect = true, profileCode = "auto", locationCode = candidate.locationCode, locationName = candidate.locationName,
        endpointCode = candidate.code, entryHost = candidate.entryHost, entryPort = candidate.entryPort,
        serverName = candidate.serverName, proxyType = candidate.proxyType, transport = candidate.transport,
        transportMode = candidate.transportMode, security = candidate.security, fingerprint = candidate.fingerprint,
        requestHost = candidate.requestHost, path = candidate.path, alpn = candidate.alpn,
        allowInsecure = candidate.allowInsecure, enableMux = candidate.enableMux, randomUserAgent = candidate.randomUserAgent,
        publicKey = candidate.publicKey, shortId = candidate.shortId, vpnUsername = "fixture", vpnSecret = UUID,
        flow = candidate.flow, planCode = "fixture", endpointCandidates = listOf(candidate), connectIp = candidate.connectIp,
        nokinvToken = candidate.nokinvToken, nokinvCredentialGeneration = candidate.nokinvCredentialGeneration,
    )

    companion object {
        private const val UUID = "bf000d23-0752-40b4-affe-68f7707a9661"
        private const val KEY = "jNXHt1yRo0vDuchQlIP6Z0ZvjT3KtzVI-T4E7RoLJS0"
        private const val SECRET = "0123456789abcdef0123456789abcdef"
        private const val DEVICE = "10000000-0000-4000-8000-000000000003"
        private const val NV_TOKEN = "AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE"
        private const val ENTRY = "10000000-0000-4000-8000-000000000001"
        private const val EXIT = "10000000-0000-4000-8000-000000000002"
    }
}
