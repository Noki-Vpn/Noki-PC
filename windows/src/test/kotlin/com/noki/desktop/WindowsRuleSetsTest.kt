package com.noki.desktop

import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class WindowsRuleSetsTest {
    @Test fun `named IP selectors reject paths and keep ordinary IP validation strict`() {
        assertEquals("geoip:my-list", WindowsRuleSets.normalizeGeoIp("GeoIP: My-List"))
        for (value in listOf("geoip:../ru", "geoip:C:/ru", "geoip:", "geoip:https://example.com"))
            assertTrue(runCatching { WindowsRuleSets.normalizeGeoIp(value) }.isFailure)
        assertTrue(runCatching { SingBoxConfig.normalizeIpRule("geoip:ru") }.isFailure)
        assertEquals(listOf("geoip:ru", "192.0.2.0/24"), DesktopRoutingRule("ip", "IP", true,
            ips = listOf("GEOIP:RU", "192.0.2.0/24")).normalized().ips)
        assertTrue(runCatching { DesktopRoutingRule("site", "Site", true, domains = listOf("geosite:../ru")).normalized() }.isFailure)
    }

    @Test fun `DNS projection rejects full configs and does not broaden inverted mixed lists`() {
        val text = WindowsRuleSets.textList("# comment\nexample.test\n192.0.2.0/24\n2001:db8::/32")
        val dns = WindowsRuleSets.checkedSource(text)
        assertEquals(1, dns.length())
        assertFalse(dns.toString().contains("ip_cidr"))
        assertTrue(runCatching { WindowsRuleSets.checkedSource(JSONObject("{\"version\":3,\"rules\":[],\"outbounds\":[]}")) }.isFailure)
        val logical = JSONObject().put("type", "logical").put("mode", "or").put("invert", true).put("rules", text.getJSONArray("rules"))
        assertEquals(0, WindowsRuleSets.checkedSource(JSONObject().put("version", 3).put("rules", JSONArray().put(logical))).length())
    }

    @Test fun `local named files resolve without URLs and persist checked snapshots`() = runBlocking {
        val directory = Files.createTempDirectory("noki-local-lists-test")
        try {
            val vault = WindowsVault(directory)
            val runtime = SingBoxRuntime(vault)
            val text = directory.resolve("geosite/my-list.txt")
            Files.writeString(text, "\nexample.test")
            val ip = directory.resolve("geoip/custom.json")
            Files.writeString(ip, "{\"version\":3,\"rules\":[{\"ip_cidr\":[\"192.0.2.0/24\"]}]}")
            val original = DesktopRoutingRule("list", "List", true,
                domains = listOf("geosite:my-list", "geosite:category-ru"), ips = listOf("geoip:custom"))
            val rule = WindowsRuleSets.resolve(vault, runtime, original)
            assertEquals(listOf("geosite:my-list", "geoip:custom"), rule.ruleSets.map { it.name })
            assertEquals(listOf("geosite:category-ru"), rule.serverDomains())
            assertTrue(Files.isRegularFile(Path.of(rule.ruleSets.first().path)))
            assertNotNull(rule.ruleSets.first().dnsPath)
            assertNull(rule.ruleSets.last().dnsPath)
            vault.saveRoutingRules(listOf(rule))
            vault.saveSettings(vault.loadSettings())
            assertEquals(listOf(rule), WindowsVault(directory).loadRoutingRules())

            val binary = directory.resolve("geosite/my-list.srs")
            runtime.ruleSetCommand("compile", Path.of(rule.ruleSets.first().path), binary)
            val restored = WindowsRuleSets.resolve(vault, runtime, rule)
            assertTrue(Files.readString(Path.of(restored.ruleSets.first().path)).contains("example.test"))
            Files.delete(binary)
            Files.writeString(text, "changed.test")
            val refreshed = WindowsRuleSets.resolve(vault, runtime, rule)
            assertNotEquals(rule.ruleSets.first().path, refreshed.ruleSets.first().path)
            assertTrue(Files.readString(Path.of(refreshed.ruleSets.first().path)).contains("changed.test"))

            assertTrue(runCatching { WindowsRuleSets.resolve(vault, runtime, original.copy(ips = listOf("geoip:missing"))) }.isFailure)
            Files.writeString(ip, "{\"version\":3,\"rules\":[{\"domain_suffix\":[\"example.test\"]}]}")
            assertTrue(runCatching { WindowsRuleSets.resolve(vault, runtime, original) }.isFailure)
            Files.delete(ip)
            assertEquals(rule.copy(enabled = false), WindowsRuleSets.resolve(vault, runtime, rule.copy(enabled = false)))
            assertTrue(WindowsRuleSets.resolve(vault, runtime, rule.copy(enabled = false, domains = listOf("example.com"), ips = emptyList())).ruleSets.isEmpty())
            val removed = WindowsRuleSets.resolve(vault, runtime, rule.copy(domains = listOf("example.com"), ips = emptyList()))
            assertTrue(removed.ruleSets.isEmpty())
            Files.delete(text)
            assertTrue(runCatching { WindowsRuleSets.resolve(vault, runtime, rule.copy(ips = emptyList())) }.isFailure)
            Files.write(directory.resolve("geosite/geosite.dat"), com.noki.vpn.vpn.GeositeAssetTest.asset(
                "CUSTOM" to listOf(com.noki.vpn.vpn.GeositeAssetTest.domain(2, "custom.test"))))
            val dat = WindowsRuleSets.resolve(vault, runtime, original.copy(domains = listOf("geosite:custom"), ips = emptyList()))
            assertEquals("geosite:custom", dat.ruleSets.single().name)
            assertTrue(Files.readString(Path.of(dat.ruleSets.single().path)).contains("custom.test"))
            assertEquals(listOf(rule), vault.loadRoutingRules())
        } finally {
            Files.walk(directory).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
        }
    }

    @Test fun `invalid regex and malformed UTF8 never replace saved rules`() = runBlocking {
        val directory = Files.createTempDirectory("noki-invalid-list-test")
        try {
            val vault = WindowsVault(directory)
            val runtime = SingBoxRuntime(vault)
            val file = directory.resolve("geosite/invalid.json")
            val original = DesktopRoutingRule("list", "List", true, domains = listOf("geosite:invalid"))
            val saved = vault.loadRoutingRules()
            Files.writeString(file, "{\"version\":3,\"rules\":[{\"domain_regex\":[\"(?<=foo)bar\"]}]}")
            assertTrue(runCatching { WindowsRuleSets.resolve(vault, runtime, original) }.isFailure)
            Files.write(file, byteArrayOf(0xC3.toByte(), 0x28))
            assertTrue(runCatching { WindowsRuleSets.resolve(vault, runtime, original) }.isFailure)
            assertEquals(saved, vault.loadRoutingRules())
        } finally {
            Files.walk(directory).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
        }
    }
}
