package com.noki.desktop

import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import java.util.UUID
import java.util.concurrent.TimeUnit
import com.noki.vpn.data.EndpointRankingPolicy
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.junit.Assume.assumeTrue

class WindowsNetworkTest {
    @Test fun `native effective policy keeps scalar namespaces empty exclusions and every security field`() {
        val rows = windowsNrptPolicyRows(listOf(
            mapOf("Namespace" to ".corp.test", "NameServers" to listOf("10.0.0.53"), "DnsSecValidationRequired" to false),
            mapOf("Namespace" to "public.corp.test", "NameServers" to null),
            mapOf("Namespace" to listOf("secure.test", ".secure.test"), "NameServers" to "192.0.2.53", "DnsSecValidationRequired" to true)
        ), emptyMap())
        val rules = parseWindowsNrptPolicy(rows.toString())
        assertEquals(4, rules.size)
        assertTrue(rules.single { it.namespace == "public.corp.test" }.servers.isEmpty())
        assertFalse(rules.single { it.namespace == ".corp.test" }.unsupportedSecurity)
        assertTrue(rules.filter { it.namespace.contains("secure") }.all { it.unsupportedSecurity })
        assertThrows(IllegalArgumentException::class.java) {
            windowsNrptPolicyRows(listOf(mapOf("Namespace" to null)), emptyMap())
        }
        assertThrows(IllegalArgumentException::class.java) {
            windowsNrptPolicyRows(listOf(mapOf("Namespace" to "corp.test", "NameServers" to listOf(123))), emptyMap())
        }
    }

    @Test fun `legacy cleanup excludes only matching owned NRPT rules and never hides security restrictions`() {
        val owned = mapOf(
            "Noki-PC-DNS-old" to mapOf("Name" to arrayOf(".old.test"), "GenericDNSServers" to "172.19.0.2"),
            "Corporate-policy" to mapOf("Name" to arrayOf(".corp.test"), "GenericDNSServers" to "192.0.2.53")
        )
        val rows = windowsNrptPolicyRows(listOf(
            mapOf("Namespace" to ".old.test", "NameServers" to listOf("172.19.0.2")),
            mapOf("Namespace" to ".corp.test", "NameServers" to listOf("192.0.2.53")),
            mapOf("Namespace" to ".old.test", "NameServers" to listOf("192.0.2.54")),
            mapOf("Namespace" to ".old.test", "NameServers" to listOf("172.19.0.2"), "DnsSecValidationRequired" to true)
        ), owned)
        assertEquals(3, rows.length())
        assertEquals(".corp.test", rows.getJSONObject(0).getString("Namespace"))
        assertEquals("192.0.2.54", rows.getJSONObject(1).getJSONArray("NameServers").getString(0))
        assertTrue(rows.getJSONObject(2).getBoolean("DnsSecValidationRequired"))
        assertThrows(IllegalArgumentException::class.java) { parseWindowsNrptPolicy(rows.toString()) }
    }

    @Test fun `native routes preserve active store live state family metrics and interface ownership`() {
        fun adapter(index: Int, family: Int, metric: Long?, connected: Int = 1, store: Int = 1) = mapOf(
            "InterfaceIndex" to index, "AddressFamily" to family, "InterfaceMetric" to metric,
            "ConnectionState" to connected, "Store" to store, "CompartmentId" to 1)
        fun route(index: Int, family: Int, name: String, prefix: String, state: Int = 7, store: Int = 1) = mapOf(
            "InterfaceIndex" to index, "AddressFamily" to family, "InterfaceAlias" to name,
            "DestinationPrefix" to prefix, "RouteMetric" to 5, "State" to state, "Store" to store, "CompartmentId" to 1)
        val rows = windowsRouteRows(listOf(adapter(4, 2, 20), adapter(8, 2, 100), adapter(8, 23, 200),
            adapter(9, 2, 0, connected = 0), adapter(10, 2, 0, store = 0), adapter(11, 2, null)), listOf(
            route(4, 2, "Ethernet", "0.0.0.0/0"),
            route(8, 2, "Corporate VPN", "10.2.0.0/24"),
            route(8, 23, "Corporate VPN", "2001:db8::/64"),
            route(11, 2, "Automatic metric", "192.0.2.0/24"),
            route(4, 2, "Ethernet", "10.2.0.53/32", state = 8),
            route(4, 2, "Ethernet", "10.2.0.53/32", store = 0),
            route(9, 2, "Disconnected", "10.2.0.53/32"),
            route(10, 2, "Persistent", "10.2.0.53/32"),
            route(4, 2, "Noki", "10.2.0.53/32"),
            route(4, 2, "Isolated", "10.2.0.53/32") + ("CompartmentId" to 2)
        ), aliveState = 7)
        assertEquals(4, rows.length())
        assertEquals(listOf(25L, 105L, 205L, 5L), (0 until rows.length()).map { rows.getJSONObject(it).getLong("metric") })
        val policy = windowsDnsPolicy(emptyList(), { JSONArray("""[{"Namespace":".corp.test","NameServers":["10.2.0.53"]}]""") }, { rows })
        assertEquals(WindowsDnsServer("10.2.0.53", "Corporate VPN", 8, 105, nrptOnly = true), policy.servers.single())
    }

    @Test fun `effective provider failure and cancellation cannot become an empty NRPT snapshot`() {
        val ethernet = WindowsDnsServer("192.0.2.53", "Ethernet", 4, 25)
        assertThrows(IllegalStateException::class.java) {
            windowsDnsPolicy(listOf(ethernet), { error("effective provider unavailable") }, { JSONArray() })
        }
        val fallback = windowsDnsPolicy(listOf(ethernet), { JSONArray() }, { error("routes unavailable") })
        assertEquals(listOf(ethernet), fallback.servers)
        assertTrue(fallback.diagnostics.any { it.contains("route-lookup-unavailable") })
        assertThrows(IllegalStateException::class.java) {
            windowsDnsPolicy(emptyList(), { JSONArray("""[{"Namespace":".corp.test","NameServers":["10.2.0.53"]}]""") }, { error("routes unavailable") })
        }
        assertThrows(InterruptedException::class.java) {
            windowsDnsPolicy(listOf(ethernet), { JSONArray() }, { throw InterruptedException("cancelled") })
        }
    }

    @Test fun `native WMI snapshot matches effective DNS and active routes from Windows cmdlets`() {
        assumeTrue(System.getProperty("os.name").startsWith("Windows"))
        assertNativePolicyMatchesWindows()
        assertFalse("The COM cancellation watcher must be joined before returning the snapshot",
            Thread.getAllStackTraces().keys.any { it.isAlive && it.name == "noki-dns-wmi-cancel" })
    }

    @Test fun `native WMI decodes effective embedded policy arrays DNSSEC and empty exclusions`() {
        assumeTrue(System.getProperty("os.name").startsWith("Windows") && System.getenv("NOKI_NATIVE_WMI_POLICY_TESTS") == "1")
        val suffix = "noki-wmi-${UUID.randomUUID()}.invalid"
        val owned = mutableListOf<String>()
        fun add(namespace: String, options: String) {
            val identifier = powershellReference("""
                ${'$'}created = Add-DnsClientNrptRule -Namespace '$namespace' -Comment '$suffix' -NameEncoding Punycode -PassThru $options
                try { ConvertTo-Json -InputObject @(${'$'}created.Name) -Compress }
                catch { Remove-DnsClientNrptRule -Name ${'$'}created.Name -Force; throw }
            """.trimIndent()).let { JSONArray(it).getString(0) }
            check(identifier.matches(Regex("\\{[0-9a-fA-F-]{36}\\}")))
            owned += identifier
        }
        try {
            add(".plain.$suffix", "-NameServers @('192.0.2.53','192.0.2.54')")
            add("public.plain.$suffix", "")
            add(".secure.$suffix", "-NameServers '2001:db8::53' -DnsSecEnable -DnsSecValidationRequired")
            val policy = assertNativePolicyMatchesWindows()
            val plain = policy.nrpt.single { it.namespace == ".plain.$suffix" }
            assertEquals(listOf("192.0.2.53", "192.0.2.54"), plain.servers)
            if (plain.unsupportedSecurity) {
                fail("Unexpected security options for the plain test rule: " + powershellReference(
                    "Get-DnsClientNrptPolicy -Effective -Namespace '.plain.$suffix' | Format-List * | Out-String"))
            }
            assertTrue(policy.nrpt.single { it.namespace == "public.plain.$suffix" }.servers.isEmpty())
            assertTrue(policy.nrpt.single { it.namespace == ".secure.$suffix" }.unsupportedSecurity)
        } finally {
            // Remove only the exact names returned by this test's Add calls; never clear other NRPT entries.
            var failure: Throwable? = null
            for (identifier in owned.asReversed()) {
                try { powershellReference("Remove-DnsClientNrptRule -Name '$identifier' -Force") }
                catch (error: Throwable) { if (failure == null) failure = error else failure.addSuppressed(error) }
            }
            failure?.let { throw it }
        }
    }

    private fun assertNativePolicyMatchesWindows(): WindowsDnsPolicy {
        // The shell is an independent test oracle only. Production uses direct COM and creates no shell child.
        val reference = powershellReference("""
            ${'$'}owned = @('HKLM:\SYSTEM\CurrentControlSet\Services\Dnscache\Parameters\DnsPolicyConfig', 'HKLM:\SOFTWARE\Policies\Microsoft\Windows NT\DNSClient\DnsPolicyConfig' | ForEach-Object {
                Get-ChildItem -LiteralPath ${'$'}_ -ErrorAction SilentlyContinue | Where-Object { ${'$'}_.PSChildName -like 'Noki-PC-DNS-*' } | ForEach-Object { Get-ItemProperty -LiteralPath ${'$'}_.PSPath }
            })
            ${'$'}nrpt = @(Get-DnsClientNrptPolicy -Effective | ForEach-Object {
                ${'$'}policy = ${'$'}_
                foreach (${'$'}namespace in @(${'$'}policy.Namespace)) {
                    ${'$'}old = @(${'$'}owned | Where-Object { @(${'$'}_.Name) -contains ${'$'}namespace -and
                        ((@(${'$'}_.GenericDNSServers -split ';' | ForEach-Object { [Net.IPAddress]::Parse(${'$'}_).ToString() } | Sort-Object) -join ';') -eq
                         (@(${'$'}policy.NameServers | ForEach-Object { [Net.IPAddress]::Parse(${'$'}_).ToString() } | Sort-Object) -join ';')) })
                    if (${'$'}old.Count -eq 0) {
                        ${'$'}row = ${'$'}policy | Select-Object Namespace,NameServers,DirectAccessEnabled,DirectAccessDnsServers,DirectAccessIPsecCARestriction,DirectAccessProxyName,DirectAccessProxyType,DirectAccessQueryIPsecEncryption,DirectAccessQueryIPsecRequired,DnsSecIPsecCARestriction,DnsSecQueryIPsecEncryption,DnsSecQueryIPsecRequired,DnsSecValidationRequired,NameEncoding,QueryPolicy,SecureNameQueryFallback
                        ${'$'}row.Namespace = ${'$'}namespace.ToString()
                        ${'$'}row.NameServers = @(${'$'}policy.NameServers | ForEach-Object { if (${'$'}null -ne ${'$'}_) { ${'$'}_.ToString() } })
                        ${'$'}row.DirectAccessDnsServers = @(${'$'}policy.DirectAccessDnsServers | ForEach-Object { if (${'$'}null -ne ${'$'}_) { ${'$'}_.ToString() } })
                        ${'$'}row
                    }
                }
            })
            ${'$'}interfaces = @(Get-NetIPInterface -PolicyStore ActiveStore | Where-Object { ${'$'}_.ConnectionState -eq 'Connected' })
            ${'$'}routes = @(Get-NetRoute -PolicyStore ActiveStore | Where-Object { ${'$'}_.InterfaceAlias -ne 'Noki' -and ${'$'}_.State -eq 'Alive' } | ForEach-Object {
                ${'$'}route = ${'$'}_
                ${'$'}interface = ${'$'}interfaces | Where-Object { ${'$'}_.InterfaceIndex -eq ${'$'}route.InterfaceIndex -and ${'$'}_.AddressFamily -eq ${'$'}route.AddressFamily } | Select-Object -First 1
                if (${'$'}null -ne ${'$'}interface) { [ordered]@{ prefix = ${'$'}route.DestinationPrefix; name = ${'$'}route.InterfaceAlias; index = ${'$'}route.InterfaceIndex; metric = [long]${'$'}route.RouteMetric + [long]${'$'}interface.InterfaceMetric } }
            })
            ConvertTo-Json -InputObject ([ordered]@{ nrpt = ${'$'}nrpt; routes = ${'$'}routes }) -Depth 5 -Compress
        """.trimIndent())
        val expected = parseWindowsDnsPolicy(reference, windowsSystemDnsServers())
        val actual = windowsDnsPolicy()
        assertFalse("A failed WMI route provider must be visible to the platform test: ${actual.diagnostics}",
            actual.diagnostics.any { it.contains("route-lookup-unavailable") })
        assertEquals(expected.nrpt, actual.nrpt)
        assertEquals(expected.servers.toSet(), actual.servers.toSet())
        assertEquals(expected.captureRoutes, actual.captureRoutes)
        return actual
    }

    private fun powershellReference(query: String): String {
        val output = Files.createTempFile("noki-wmi-reference-", ".json")
        val errors = Files.createTempFile("noki-wmi-reference-", ".err")
        try {
            val script = """
                ${'$'}ErrorActionPreference = 'Stop'
                ${'$'}ProgressPreference = 'SilentlyContinue'
                [Console]::OutputEncoding = [Text.UTF8Encoding]::new(${'$'}false)
                $query
            """.trimIndent()
            val executable = Path.of(checkNotNull(System.getenv("SystemRoot")), "System32", "WindowsPowerShell", "v1.0", "powershell.exe")
            val process = ProcessBuilder(executable.toString(), "-NoProfile", "-NonInteractive", "-EncodedCommand",
                Base64.getEncoder().encodeToString(script.toByteArray(Charsets.UTF_16LE)))
                .redirectOutput(output.toFile()).redirectError(errors.toFile()).start()
            try {
                check(process.waitFor(30, TimeUnit.SECONDS)) { "The Windows reference cmdlet timed out" }
                check(Files.size(output) <= 512 * 1024 && Files.size(errors) <= 512 * 1024)
                check(process.exitValue() == 0) { Files.readString(errors) }
                return Files.readString(output).removePrefix("\uFEFF").trim()
            } finally { if (process.isAlive) process.destroyForcibly().waitFor(2, TimeUnit.SECONDS) }
        } finally { Files.deleteIfExists(output); Files.deleteIfExists(errors) }
    }

    @Test fun `another VPN default route cannot take ownership of Ethernet DNS`() {
        val ethernet = WindowsDnsServer("8.8.8.8", "Ethernet", 5, 35)
        val snapshot = """{"nrpt":[],"routes":[
            {"prefix":"0.0.0.0/0","name":"singbox_tun","index":12,"metric":0},
            {"prefix":"0.0.0.0/0","name":"Ethernet","index":5,"metric":35},
            {"prefix":"8.8.8.8/32","name":"Noki","index":20,"metric":0}
        ]}"""
        assertEquals(listOf(ethernet), parseWindowsDnsPolicy(snapshot, listOf(ethernet)).servers)
        val nrpt = snapshot.replace("\"nrpt\":[]", "\"nrpt\":[{\"Namespace\":\".corp.test\",\"NameServers\":[\"8.8.8.8\"]}]")
        assertEquals(12, parseWindowsDnsPolicy(nrpt, emptyList()).servers.single().interfaceIndex)
    }

    @Test fun `ordinary DNS follows destination routes and preserves scoped adapter identity`() {
        val ethernet = WindowsDnsServer("10.2.0.53", "Ethernet", 4, 25)
        val corporate = WindowsDnsServer("10.2.0.53", "Corporate VPN", 8, 100)
        val scoped = WindowsDnsServer("fe80:0:0:0:0:0:0:53%8", "Corporate VPN", 8, 100)
        val policy = parseWindowsDnsPolicy("""{"nrpt":[],"routes":[
            {"prefix":"0.0.0.0/0","name":"Ethernet","index":4,"metric":25},
            {"prefix":"10.2.0.0/24","name":"Corporate VPN","index":8,"metric":100},
            {"prefix":"10.2.0.0/24","name":"Other VPN","index":9,"metric":200},
            {"prefix":"10.2.0.53/32","name":"Noki","index":20,"metric":0},
            {"prefix":"fe80::/64","name":"Ethernet","index":4,"metric":1},
            {"prefix":"fe80::/64","name":"Corporate VPN","index":8,"metric":100}
        ]}""", listOf(ethernet, corporate, scoped))
        assertEquals(listOf(corporate, scoped), policy.servers)
        assertTrue(policy.captureRoutes.containsAll(listOf("10.2.0.0/25", "10.2.0.128/25")))
        assertFalse(policy.captureRoutes.contains("10.2.0.53/32"))
        assertEquals(ethernet, parseWindowsDnsPolicy("""{"nrpt":[],"routes":[]}""", listOf(ethernet)).servers.single())
        assertTrue(parseWindowsDnsPolicy("""{"nrpt":[],"routes":null}""", listOf(ethernet)).diagnostics.any { it.contains("route-lookup-unavailable") })
        val unscoped = parseWindowsDnsPolicy("""{"nrpt":[],"routes":[]}""", listOf(scoped.copy(address = "fe80:0:0:0:0:0:0:53")))
        assertEquals(scoped, unscoped.servers.single())
        assertTrue(runCatching { parseWindowsDnsPolicy("""{"nrpt":[{"Namespace":".corp.test","NameServers":["fe80::53"]}],"routes":[]}""", emptyList()) }.isFailure)
        for (invalid in listOf("", "{}", "{\"nrpt\":[],\"routes\":[{}]}"))
            assertTrue(runCatching { parseWindowsDnsPolicy(invalid, listOf(ethernet)) }.isFailure)
    }

    @Test fun `network monitor keeps snapshots retryable by their owner and continues after a failed native sample`() = runTest {
        val first = WindowsNetworkSnapshot("wifi", EndpointRankingPolicy.NetworkKind.WIFI, true, 10)
        val absent = WindowsNetworkSnapshot("unavailable", EndpointRankingPolicy.NetworkKind.OTHER, false)
        val next = WindowsNetworkSnapshot("ethernet", EndpointRankingPolicy.NetworkKind.OTHER, true, 20)
        var samples = 0
        val changes = windowsNetworkSnapshots {
            when (++samples) {
                1, 3 -> first
                2 -> throw java.net.SocketException("adapter changed during enumeration")
                4 -> absent
                else -> next
            }
        }.take(4).toList()
        assertEquals(listOf(first, first, absent, next), changes)
        assertEquals(5, samples)
    }

    @Test fun `effective NRPT preserves exact exclusions before suffix and root policies`() {
        val rules = parseWindowsNrptPolicy("""[
            {"Namespace":".","NameServers":["192.0.2.53"]},
            {"Namespace":".CORP.TEST","NameServers":["10.0.0.53","10.0.0.53"]},
            {"Namespace":"public.corp.test.","NameServers":[]},
            {"Namespace":"secure.test","NameServers":["2001:db8::53"],"DnsSecValidationRequired":true}
        ]""")
        assertEquals(listOf("public.corp.test", "secure.test", ".corp.test", "."), rules.map { it.namespace })
        assertEquals(emptyList<String>(), rules.first().servers)
        assertEquals(listOf("10.0.0.53"), rules.single { it.namespace == ".corp.test" }.servers)
        assertTrue(rules.single { it.namespace == "secure.test" }.unsupportedSecurity)
        assertFalse(rules.last().unsupportedSecurity)
        assertTrue(parseWindowsNrptPolicy("[]").isEmpty())
    }

    @Test fun `NRPT security restrictions cannot silently become ordinary UDP queries`() {
        for (property in listOf("DirectAccessEnabled", "DirectAccessQueryIPsecRequired", "DnsSecQueryIPsecRequired", "DnsSecValidationRequired")) {
            assertTrue(parseWindowsNrptPolicy("""[{"Namespace":"corp.test","$property":true}]""").single().unsupportedSecurity)
            assertFalse(parseWindowsNrptPolicy("""[{"Namespace":"corp.test","$property":false}]""").single().unsupportedSecurity)
        }
        for ((property, value) in listOf("DnsSecQueryIPsecEncryption" to "High", "QueryPolicy" to "QueryIPv6Only",
            "SecureNameQueryFallback" to "FallbackSecure", "DirectAccessProxyType" to "UseProxyName", "NameEncoding" to "Disable")) {
            assertTrue(parseWindowsNrptPolicy("""[{"Namespace":"corp.test","$property":"$value"}]""").single().unsupportedSecurity)
        }
    }

    @Test fun `NRPT rejects ambiguous namespaces and nonliteral resolver addresses`() {
        for (namespace in listOf("", "..corp.test", "*.corp.test", "corp/test", "https://corp.test")) {
            assertThrows(IllegalArgumentException::class.java) { parseWindowsNrptPolicy("""[{"Namespace":"$namespace"}]""") }
        }
        for (invalid in listOf("dns.example.test", "999.0.0.1", "192.0.2.0/24", "fe80::53%", "192.0.2.53%1", "172.19.0.2")) {
            assertThrows(IllegalArgumentException::class.java) { parseWindowsNrptPolicy("""[{"Namespace":"corp.test","NameServers":["$invalid"]}]""") }
        }
        assertThrows(IllegalArgumentException::class.java) {
            parseWindowsNrptPolicy("""[{"Namespace":"corp.test","NameServers":[]},{"Namespace":"corp.test","NameServers":["10.0.0.53"]}]""")
        }
        assertThrows(IllegalArgumentException::class.java) {
            parseWindowsNrptPolicy("""[{"Namespace":"corp.test","DnsSecValidationRequired":"false"}]""")
        }
    }

    @Test fun `corporate NRPT resolver uses the specific adapter route without a default gateway`() {
        val physical = WindowsDnsServer("192.0.2.53", "Ethernet", 4, 25)
        val snapshot = """{"nrpt":[{"Namespace":".corp.test","NameServers":["10.2.0.53"]}],"routes":[
            {"prefix":"0.0.0.0/0","name":"Ethernet","index":4,"metric":25},
            {"prefix":"10.2.0.0/24","name":"Corporate VPN","index":8,"metric":100},
            {"prefix":"10.2.0.53/32","name":"Noki","index":20,"metric":1}
        ]}"""
        val policy = parseWindowsDnsPolicy(snapshot, listOf(physical))
        assertEquals(listOf(physical, WindowsDnsServer("10.2.0.53", "Corporate VPN", 8, 100, nrptOnly = true)), policy.servers)
        assertEquals(".corp.test", policy.nrpt.single().namespace)
        assertThrows(IllegalStateException::class.java) {
            parseWindowsDnsPolicy("""{"nrpt":[{"Namespace":"corp.test","NameServers":["10.0.0.53"]}],"routes":[]}""", emptyList())
        }
        assertTrue(parseWindowsDnsPolicy("""{"nrpt":[{"Namespace":"corp.test","NameServers":["10.0.0.53"],"DnsSecValidationRequired":true}],"routes":[]}""", emptyList()).servers.isEmpty())
        val scoped = parseWindowsDnsPolicy("""{"nrpt":[{"Namespace":"corp.test","NameServers":["fe80::53%8"]}],"routes":[
            {"prefix":"fe80::/64","name":"Ethernet","index":4,"metric":1},
            {"prefix":"fe80::/64","name":"Corporate VPN","index":8,"metric":100}
        ]}""", emptyList())
        assertEquals(8, scoped.servers.single().interfaceIndex)
    }

    @Test fun `NRPT follows the corporate route even when the same resolver is configured on other adapters`() {
        val ethernet = WindowsDnsServer("10.2.0.53", "Ethernet", 4, 25)
        val corporate = WindowsDnsServer("10.2.0.53", "Corporate VPN", 8, 100)
        val policy = parseWindowsDnsPolicy("""{"nrpt":[{"Namespace":".corp.test","NameServers":["10.2.0.53"]}],"routes":[
            {"prefix":"0.0.0.0/0","name":"Ethernet","index":4,"metric":25},
            {"prefix":"10.2.0.0/24","name":"Corporate VPN","index":8,"metric":100},
            {"prefix":"10.2.0.53/32","name":"Noki","index":20,"metric":1}
        ]}""", listOf(ethernet, corporate))
        assertEquals(listOf(corporate, corporate.copy(nrptOnly = true)), policy.servers)
    }

    @Test fun `DNS policy snapshot reads effective Windows settings without issuing DNS queries`() {
        val policy = windowsDnsPolicy()
        assertEquals(policy.servers.distinctBy { Triple(it.address, it.interfaceIndex, it.nrptOnly) }, policy.servers)
        assertFalse(policy.servers.any { it.interfaceName.equals("Noki", true) })
        assertTrue(policy.nrpt.filterNot { it.unsupportedSecurity }.flatMap { it.servers }.all { address -> policy.servers.any { it.address == address && it.nrptOnly } })
    }

    @Test fun `system DNS discovery reads native IPv4 and IPv6 literals without the Noki resolver`() {
        assertEquals("GetAdaptersAddresses offsets require the packaged Windows x64 ABI", 8, com.sun.jna.Native.POINTER_SIZE)
        val servers = windowsSystemDnsServers()
        assertEquals(servers.distinctBy { it.address to it.interfaceIndex }, servers)
        assertEquals(servers.sortedBy { it.metric }, servers)
        assertFalse(servers.any { it.address == "172.19.0.2" })
        for (server in servers) {
            val host = server.address.substringBefore('%')
            assertTrue("A resolver must be an IP literal: $server", host.matches(Regex("[0-9a-fA-F:.]+")))
            val bytes = InetAddress.getByName(host).address
            assertEquals(host, InetAddress.getByAddress(bytes).hostAddress)
            assertFalse(bytes.size == 16 && bytes[0].toInt().and(255) == 0xfe && bytes[1].toInt().and(0xc0) == 0xc0)
            assertTrue(server.interfaceName.isNotBlank())
            assertNotEquals("Noki", server.interfaceName)
            assertTrue(server.interfaceIndex > 0)
            assertTrue(server.metric >= 0)
            if ('%' in server.address) assertTrue(server.address.substringAfter('%').toLong() > 0)
        }
    }

    @Test fun `bound native TCP connects through the selected interface and rejects a closed port`() {
        val address = InetAddress.getByName("127.0.0.1")
        val index = NetworkInterface.getByInetAddress(address).index
        val port = ServerSocket(0, 10, address).use { server ->
            val measured = windowsTcpLatency("127.0.0.1", server.localPort, 650, index)
            assertNotNull("A physical-interface probe must finish the TCP handshake", measured)
            assertTrue(measured!! in 1..650)
            server.accept().use { accepted ->
                accepted.soTimeout = 1_000
                assertEquals("The bound physical-interface sampler must send no application payload", -1, accepted.getInputStream().read())
            }
            assertNotNull("System DNS may resolve a hostname before the bound TCP handshake", windowsTcpLatency("localhost", server.localPort, 650, index))
            server.localPort
        }
        assertNull("A refused connection must not become a successful zero ping", windowsTcpLatency("127.0.0.1", port, 650, index))
    }

    @Test fun `network snapshot selects a live underlying adapter and is stable without topology changes`() {
        val first = windowsNetworkSnapshot()
        assertTrue(first.available)
        assertNotNull(first.interfaceIndex)
        assertNotEquals("Noki", NetworkInterface.getByIndex(first.interfaceIndex!!).displayName)
        val row = com.sun.jna.platform.win32.IPHlpAPI.MIB_IFROW().apply { dwIndex = first.interfaceIndex }
        assertEquals(0, com.sun.jna.platform.win32.IPHlpAPI.INSTANCE.GetIfEntry(row))
        assertFalse("Probes must not bind to a software virtual or tunnel interface", row.dwType in setOf(53, 131))
        assertEquals(first.signature, windowsNetworkSnapshot().signature)
    }
}
