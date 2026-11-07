package com.noki.desktop

import com.noki.vpn.data.EndpointRankingPolicy
import com.sun.jna.Function
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.WString
import com.sun.jna.platform.win32.Advapi32Util
import com.sun.jna.platform.win32.Guid.GUID
import com.sun.jna.platform.win32.IPHlpAPI
import com.sun.jna.platform.win32.WinReg
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.PointerByReference
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketAddress
import java.net.SocketException
import java.net.SocketTimeoutException
import java.io.InterruptedIOException
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONArray
import org.json.JSONObject
import com.noki.vpn.data.DeviceLatency
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive

internal data class WindowsNetworkSnapshot(val signature: String, val kind: EndpointRankingPolicy.NetworkKind, val available: Boolean,
    val interfaceIndex: Int? = null)

/** A foreign VPN's default route must never become ByeDPI's underlay. */
internal fun youtubePhysicalInterface(index: Int): Boolean {
    val adapter = NetworkInterface.getByIndex(index) ?: return false
    if (!adapter.isUp || adapter.isLoopback || adapter.isVirtual || adapter.name.equals("Noki", true)) return false
    return if (DesktopBuild.IS_LINUX) java.nio.file.Files.isDirectory(java.nio.file.Path.of("/sys/class/net", adapter.name, "device"))
    else WindowsDnsWmi().use { it.physicalInterface(index) }
}

/** Keep topology sampling independent of account and core-control request latency. */
internal fun windowsNetworkSnapshots(snapshot: () -> WindowsNetworkSnapshot = ::windowsNetworkSnapshot): Flow<WindowsNetworkSnapshot> = flow {
    while (currentCoroutineContext().isActive) {
        val network = try { snapshot() }
        catch (_: Exception) { currentCoroutineContext().ensureActive(); null }
        if (network != null) emit(network)
        delay(2_000)
    }
}

/** Underlying IPv4 topology; the Noki TUN must not invalidate its own samples. */
internal fun windowsNetworkSnapshot(): WindowsNetworkSnapshot {
        if (DesktopBuild.IS_LINUX) return LinuxPlatform.snapshot()
    val adapters = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty().mapNotNull { adapter ->
        if (!adapter.isUp || adapter.isLoopback) return@mapNotNull null
        val row = IPHlpAPI.MIB_IFROW().apply { dwIndex = adapter.index }
        if (IPHlpAPI.INSTANCE.GetIfEntry(row) != 0) return@mapNotNull null
        if (row.dwType in setOf(53, 131) || adapter.name.equals("Noki", true) || adapter.displayName.equals("Noki", true)) return@mapNotNull null
        val addresses = adapter.inetAddresses.toList().filterIsInstance<Inet4Address>()
            .filterNot { it.isLoopbackAddress || it.isLinkLocalAddress || it.hostAddress == "172.19.0.1" }
            .map { it.hostAddress }.sorted()
        if (addresses.isEmpty()) return@mapNotNull null
        Triple(adapter.index, row.dwType, addresses)
    }.sortedBy { it.first }
    // The TUN installs more-specific routes. Choose the physical default route, not GetBestInterface through the VPN.
    val defaultRoute = run {
        val size = IntByReference()
        val table = Function.getFunction("iphlpapi", "GetIpForwardTable", Function.ALT_CONVENTION)
        repeat(3) {
            check(table.invokeInt(arrayOf(null, size, false)) == 122)
            Memory(size.value.toLong()).use { rows ->
                val result = table.invokeInt(arrayOf(rows, size, false))
                if (result == 122) return@repeat // Routes can grow between the size query and the read.
                check(result == 0) { "Не удалось прочитать маршруты Windows (код $result)" }
                return@run (0 until rows.getInt(0)).map { rows.share(4L + it * 56L) }
                    .filter { it.getInt(0) == 0 && it.getInt(4) == 0 && adapters.any { adapter -> adapter.first == it.getInt(16) } }
                    .minWithOrNull(compareBy<Pointer> { Integer.toUnsignedLong(it.getInt(36)) }.thenBy { it.getInt(16) })
                    ?.let { it.getInt(16) to Integer.toUnsignedLong(it.getInt(12)) }
            }
        }
        error("Таблица маршрутов Windows изменяется. Повторите подключение")
    }
    val selected = adapters.firstOrNull { it.first == defaultRoute?.first }
    val kind = when (selected?.second) {
        71 -> EndpointRankingPolicy.NetworkKind.WIFI
        243, 244 -> EndpointRankingPolicy.NetworkKind.CELLULAR
        else -> EndpointRankingPolicy.NetworkKind.OTHER
    }
    return WindowsNetworkSnapshot(selected?.let { "${it.first}:${it.second}:${it.third.joinToString(",")}|${defaultRoute?.second}" }
        ?: "unavailable", kind, selected != null, selected?.first)
}

internal data class WindowsDnsServer(val address: String, val interfaceName: String, val interfaceIndex: Int, val metric: Int,
    val nrptOnly: Boolean = false)

internal data class WindowsNrptRule(val namespace: String, val servers: List<String>, val unsupportedSecurity: Boolean)
internal data class WindowsDnsPolicy(val servers: List<WindowsDnsServer>, val nrpt: List<WindowsNrptRule>,
    val captureRoutes: List<String> = emptyList(), val diagnostics: List<String> = emptyList())

/** Ask the Windows provider for effective NRPT, including Group Policy precedence, without a shell. */
internal fun windowsDnsPolicy(): WindowsDnsPolicy {
        if (DesktopBuild.IS_LINUX) return LinuxPlatform.dnsPolicy()
    val adapters = windowsSystemDnsServers()
    return WindowsDnsWmi().use { wmi ->
        windowsDnsPolicy(adapters, { windowsNrptPolicyRows(wmi.effectiveNrpt(), ownedWindowsNrptRules()) }, { wmi.routes() })
    }
}

internal fun windowsDnsPolicy(adapters: List<WindowsDnsServer>, nrpt: () -> JSONArray, routes: () -> JSONArray): WindowsDnsPolicy {
    val effective = nrpt() // A missing/failed effective provider must never turn into an empty NRPT policy.
    var routeFailure: String? = null
    val routeRows = try { routes() } catch (failure: Exception) {
        if (failure is InterruptedException || failure is InterruptedIOException || Thread.currentThread().isInterrupted) throw failure
        routeFailure = failure.message ?: failure.javaClass.simpleName
        null // Keep the existing adapter fallback; NRPT without a route still fails closed in the parser.
    }
    val policy = parseWindowsDnsPolicy(JSONObject().put("nrpt", effective).put("routes", routeRows ?: JSONObject.NULL).toString(), adapters)
    return routeFailure?.let { policy.copy(diagnostics = policy.diagnostics + "Windows route lookup: $it") } ?: policy
}

private val windowsNrptProperties = listOf("Namespace", "NameServers", "DirectAccessEnabled", "DirectAccessDnsServers",
    "DirectAccessIPsecCARestriction", "DirectAccessProxyName", "DirectAccessProxyType", "DirectAccessQueryIPsecEncryption",
    "DirectAccessQueryIPsecRequired", "DnsSecIPsecCARestriction", "DnsSecQueryIPsecEncryption", "DnsSecQueryIPsecRequired",
    "DnsSecValidationRequired", "NameEncoding", "QueryPolicy", "SecureNameQueryFallback")

private fun windowsPolicyStrings(value: Any?): List<String> {
    val values = when (value) {
        null -> emptyList()
        is String -> listOf(value)
        is List<*> -> value
        is Array<*> -> value.toList()
        else -> throw IllegalArgumentException("Windows вернула некорректное строковое поле DNS-политики")
    }
    return values.filterNotNull().map {
        require(it is String) { "Windows вернула некорректное строковое поле DNS-политики" }; it
    }
}

/** Registry is used only to recognize our obsolete rules, never to compute effective GPO policy. */
private fun ownedWindowsNrptRules(): Map<String, Map<String, Any?>> = buildMap {
    for (root in listOf("SYSTEM\\CurrentControlSet\\Services\\Dnscache\\Parameters\\DnsPolicyConfig",
        "SOFTWARE\\Policies\\Microsoft\\Windows NT\\DNSClient\\DnsPolicyConfig")) {
        if (!Advapi32Util.registryKeyExists(WinReg.HKEY_LOCAL_MACHINE, root)) continue
        for (child in Advapi32Util.registryGetKeys(WinReg.HKEY_LOCAL_MACHINE, root).filter { it.startsWith("Noki-PC-DNS-", true) })
            put("$root\\$child", Advapi32Util.registryGetValues(WinReg.HKEY_LOCAL_MACHINE, "$root\\$child"))
    }
}

internal fun windowsNrptPolicyRows(effective: List<Map<String, Any?>>, owned: Map<String, Map<String, Any?>>): JSONArray {
    require(effective.size <= 4096) { "Windows вернула слишком много правил NRPT" }
    val legacy = owned.filterKeys { it.substringAfterLast('\\').startsWith("Noki-PC-DNS-", true) }.values
    val result = JSONArray()
    for (policy in effective) {
        val namespaces = windowsPolicyStrings(policy["Namespace"])
        require(namespaces.isNotEmpty()) { "Windows не вернула пространство имён эффективной NRPT-политики" }
        val servers = windowsPolicyStrings(policy["NameServers"])
        for (namespace in namespaces) {
            val row = JSONObject(policy).put("Namespace", namespace).put("NameServers", JSONArray(servers))
                .put("DirectAccessDnsServers", JSONArray(windowsPolicyStrings(policy["DirectAccessDnsServers"])))
            val obsolete = !unsupportedWindowsNrptSecurity(row) && legacy.any { old ->
                windowsPolicyStrings(old["Name"]).any { it.equals(namespace, true) } &&
                    (old["GenericDNSServers"] as? String)?.takeIf { it.isNotBlank() }?.split(';')
                        ?.map { dnsAddress(it, allowNoki = true) }?.sorted() == servers.map { dnsAddress(it, allowNoki = true) }.sorted()
            }
            if (!obsolete) result.put(row)
        }
    }
    return result
}

internal fun windowsRouteRows(interfaces: List<Map<String, Any?>>, routes: List<Map<String, Any?>>, aliveState: Long): JSONArray {
    fun Map<String, Any?>.number(name: String): Long {
        val value = get(name)
        require(value is Byte || value is Short || value is Int || value is Long) { "Windows не вернула параметр маршрута $name" }
        return (value as Number).toLong()
    }
    val connected = interfaces.filter { it.number("Store") == 1L && it.number("ConnectionState") == 1L && it.number("CompartmentId") == 1L }
        .associateBy { it.number("InterfaceIndex") to it.number("AddressFamily") }
    return JSONArray(routes.mapNotNull { route ->
        if (route.number("Store") != 1L || route.number("State") != aliveState || route.number("CompartmentId") != 1L) return@mapNotNull null
        val name = route["InterfaceAlias"] as? String ?: error("Windows не вернула имя интерфейса маршрута")
        if (name.equals("Noki", true)) return@mapNotNull null
        val index = route.number("InterfaceIndex")
        val adapter = connected[index to route.number("AddressFamily")] ?: return@mapNotNull null
        val prefix = route["DestinationPrefix"] as? String ?: error("Windows не вернула префикс маршрута")
        // Match the existing Windows cmdlet snapshot's [long] conversion for an unset interface metric.
        val interfaceMetric = adapter["InterfaceMetric"]?.let { adapter.number("InterfaceMetric") } ?: 0L
        JSONObject().put("prefix", prefix).put("name", name).put("index", index)
            .put("metric", route.number("RouteMetric") + interfaceMetric)
    })
}

/** One COM apartment and deadline per snapshot. All pointers, BSTRs and VARIANTs stay on their owner thread. */
private class WindowsDnsWmi : AutoCloseable {
    private val references = ArrayDeque<Pointer>()
    private val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
    private val finished = CountDownLatch(1)
    private var initialized = false
    private var cancellable = false
    private var watchdog: Thread? = null
    private val watchdogFailure = AtomicReference<Throwable?>()
    private var stringBytes = 0L
    private fun function(library: String, name: String) = Function.getFunction(library, name, Function.ALT_CONVENTION)
    private fun checkHr(value: Int, operation: String = "WMI") {
        check(value >= 0) { "Не удалось прочитать DNS-политику Windows ($operation, 0x${Integer.toHexString(value)})" }
    }
    private fun active() {
        if (Thread.currentThread().isInterrupted) throw InterruptedException("Чтение DNS-политики Windows отменено")
        watchdogFailure.get()?.let { throw it }
        check(System.nanoTime() < deadline) { "Истекло время чтения DNS-политики Windows" }
    }
    private fun own(value: Pointer?): Pointer = checkNotNull(value) { "Windows WMI не вернула объект DNS-политики" }.also { references.addFirst(it) }
    private fun create(clsid: String, iid: String): Pointer {
        active()
        val output = PointerByReference()
        checkHr(function("ole32", "CoCreateInstance").invokeInt(arrayOf(GUID(clsid), null, 1, GUID(iid), output)))
        return own(output.value)
    }
    init {
        check(Native.POINTER_SIZE == 8) { "Чтение DNS-политики требует Windows x64" }
        try {
            active()
            val apartment = function("ole32", "CoInitializeEx").invokeInt(arrayOf(null, 0))
            if (apartment != 0x80010106.toInt()) checkHr(apartment) // RPC_E_CHANGED_MODE: reuse an existing apartment.
            initialized = apartment >= 0
            val security = function("ole32", "CoInitializeSecurity").invokeInt(arrayOf(null, -1, null, null, 0, 3, null, 0, null))
            if (security != 0x80010119.toInt()) checkHr(security) // RPC_E_TOO_LATE: another COM owner already initialized security.
            checkHr(function("ole32", "CoEnableCallCancellation").invokeInt(arrayOf(null)))
            cancellable = true
            val owner = Thread.currentThread()
            val threadId = function("kernel32", "GetCurrentThreadId").invokeInt(emptyArray())
            val ready = CountDownLatch(1)
            watchdog = kotlin.concurrent.thread(name = "noki-dns-wmi-cancel", isDaemon = true) {
                var apartmentReady = false
                try {
                    checkHr(function("ole32", "CoInitializeEx").invokeInt(arrayOf(null, 0)))
                    apartmentReady = true
                    ready.countDown()
                    while (!finished.await(50, TimeUnit.MILLISECONDS)) {
                        if (owner.isInterrupted || System.nanoTime() >= deadline)
                            function("ole32", "CoCancelCall").invokeInt(arrayOf(threadId, 0))
                    }
                } catch (failure: Throwable) { watchdogFailure.set(failure) }
                finally {
                    ready.countDown()
                    if (apartmentReady) function("ole32", "CoUninitialize").invokeVoid(emptyArray())
                }
            }
            check(ready.await(10, TimeUnit.SECONDS)) { "Не удалось подготовить отмену запроса DNS-политики Windows" }
            active()
        } catch (failure: Throwable) { close(); throw failure }
    }
    override fun close() {
        var interrupted = Thread.interrupted()
        try {
            finished.countDown()
            // runInterruptible can interrupt cleanup too; never leave a watcher targeting a reused pool thread.
            while (watchdog?.isAlive == true) {
                try { watchdog?.join() } catch (_: InterruptedException) { interrupted = true }
            }
            for (reference in references) callWindowsCom(reference, 2)
            references.clear()
        } finally {
            try { if (cancellable) function("ole32", "CoDisableCallCancellation").invokeInt(arrayOf(null)) }
            finally {
                if (initialized) function("ole32", "CoUninitialize").invokeVoid(emptyArray())
                if (interrupted) Thread.currentThread().interrupt()
            }
        }
    }
    private fun <T> bstr(text: String, block: (Pointer) -> T): T {
        val value = checkNotNull(function("oleaut32", "SysAllocString").invokePointer(arrayOf(WString(text))))
        try { return block(value) } finally { function("oleaut32", "SysFreeString").invokeVoid(arrayOf(value)) }
    }
    private fun text(value: Pointer?): String? {
        if (value == null) return null
        val size = function("oleaut32", "SysStringLen").invokeInt(arrayOf(value))
        stringBytes += size.toLong() * 2
        check(size >= 0 && stringBytes <= 512 * 1024) { "Windows вернула слишком большую DNS-политику" }
        return String(value.getByteArray(0, size * 2), Charsets.UTF_16LE)
    }
    private fun connect(namespace: String): Pointer {
        val locator = create("4590f811-1d3a-11d0-891f-00aa004b2e24", "dc12a687-737f-11cf-884d-00aa004b2e24")
        val output = PointerByReference()
        bstr(namespace) { checkHr(callWindowsCom(locator, 3, it, null, null, null, 0x80, null, null, output), "ConnectServer $namespace") }
        val service = own(output.value)
        active()
        // Keep the caller's identity and use packet privacy; no stored credentials or policy impersonation.
        checkHr(function("ole32", "CoSetProxyBlanket").invokeInt(arrayOf(service, 10, 0, null, 6, 3, null, 0)))
        return service
    }
    private fun result(start: (PointerByReference) -> Int): Pointer? {
        active()
        val pending = PointerByReference()
        checkHr(start(pending))
        val call = own(pending.value)
        while (true) {
            active()
            val output = PointerByReference()
            val status = callWindowsCom(call, 3, 100, output) // IWbemCallResult::GetResultObject.
            if (status == 0x40004) continue // WBEM_S_TIMEDOUT.
            checkHr(status)
            val completed = IntByReference()
            val completionStatus = callWindowsCom(call, 6, 100, completed)
            if (completionStatus == 0x40004) {
                output.value?.let { callWindowsCom(it, 2) }
                continue
            }
            checkHr(completionStatus)
            checkHr(completed.value, "WMI operation completion")
            return output.value?.let(::own)
        }
    }
    private fun objectAt(service: Pointer, path: String): Pointer = bstr(path) { pathValue ->
        // State.Values is an amended qualifier and is absent from the base WMI class.
        checkNotNull(result { pending -> callWindowsCom(service, 6, pathValue, 0x10 or 0x20000, null, null, pending) }) {
            "Windows не вернула описание класса DNS-политики"
        }
    }
    private fun value(owner: Pointer, name: String, qualifier: Boolean = false, objectFields: List<String>? = null): Any? = Memory(24).use { variant ->
        active()
        variant.clear()
        val type = IntByReference()
        try {
            checkHr(if (qualifier) callWindowsCom(owner, 3, WString(name), 0, variant, null)
                else callWindowsCom(owner, 4, WString(name), 0, variant, type, null), "WMI Get $name")
            val kind = variant.getShort(0).toInt().and(0xffff)
            when (kind) {
                0, 1 -> null // VT_EMPTY / VT_NULL.
                2 -> variant.getShort(8).toInt()
                3 -> if (type.value == 19) Integer.toUnsignedLong(variant.getInt(8)) else variant.getInt(8)
                8 -> text(variant.getPointer(8))
                11 -> variant.getShort(8).let { check(it == 0.toShort() || it == (-1).toShort()); it != 0.toShort() }
                17 -> variant.getByte(8).toInt().and(255)
                18 -> variant.getShort(8).toInt().and(65535)
                19 -> Integer.toUnsignedLong(variant.getInt(8))
                0x2008, 0x200d -> {
                    val array = variant.getPointer(8) ?: return@use emptyList<Any?>()
                    check(function("oleaut32", "SafeArrayGetDim").invokeInt(arrayOf(array)) == 1)
                    check(function("oleaut32", "SafeArrayGetElemsize").invokeInt(arrayOf(array)) == Native.POINTER_SIZE)
                    val lower = IntByReference(); val upper = IntByReference()
                    checkHr(function("oleaut32", "SafeArrayGetLBound").invokeInt(arrayOf(array, 1, lower)))
                    checkHr(function("oleaut32", "SafeArrayGetUBound").invokeInt(arrayOf(array, 1, upper)))
                    val count = upper.value.toLong() - lower.value + 1
                    check(count in 0..4096) { "Windows вернула слишком большой массив DNS-политики" }
                    val data = PointerByReference()
                    checkHr(function("oleaut32", "SafeArrayAccessData").invokeInt(arrayOf(array, data)))
                    try { (0 until count.toInt()).map { index ->
                        val item = data.value.getPointer(index.toLong() * Native.POINTER_SIZE)
                        if (kind == 0x2008) text(item)
                        else {
                            checkNotNull(item); checkNotNull(objectFields)
                            val objectRef = PointerByReference()
                            checkHr(callWindowsCom(item, 0, GUID("dc12a681-737f-11cf-884d-00aa004b2e24"), objectRef))
                            val instance = checkNotNull(objectRef.value)
                            try { objectFields.associateWith { value(instance, it) } }
                            finally { callWindowsCom(instance, 2) }
                        }
                    } } finally { checkHr(function("oleaut32", "SafeArrayUnaccessData").invokeInt(arrayOf(array))) }
                }
                else -> error("Windows вернула неподдерживаемый тип WMI $kind для $name")
            }
        } finally { checkHr(function("oleaut32", "VariantClear").invokeInt(arrayOf(variant))) }
    }
    fun effectiveNrpt(): List<Map<String, Any?>> {
        val service = connect("ROOT\\Microsoft\\Windows\\DNS")
        val definition = objectAt(service, "PS_DnsClientNrptPolicy")
        val signatureRef = PointerByReference()
        checkHr(callWindowsCom(definition, 19, WString("Get"), 0, signatureRef, null))
        val signature = own(signatureRef.value)
        val inputRef = PointerByReference()
        checkHr(callWindowsCom(signature, 15, 0, inputRef))
        val input = own(inputRef.value)
        Memory(24).use { argument ->
            argument.clear(); argument.setShort(0, 11); argument.setShort(8, -1)
            checkHr(callWindowsCom(input, 5, WString("Effective"), 0, argument, 0))
            argument.clear(); argument.setShort(0, 1)
            checkHr(callWindowsCom(input, 5, WString("Namespace"), 0, argument, 0))
        }
        val output = bstr("PS_DnsClientNrptPolicy") { path -> bstr("Get") { method ->
            result { pending ->
                // A null ppOutParams suppresses the method output, including GetResultObject's result.
                val immediate = PointerByReference()
                val status = callWindowsCom(service, 24, path, method, 0x10, null, input, immediate, pending)
                immediate.value?.let { own(it) }
                status
            }
        } }
        // This provider returns no output for an empty policy; result() still verifies operation success.
        if (output == null) return emptyList()
        val status = value(output, "ReturnValue")
        check(status is Number && status.toLong() == 0L) { "Провайдер эффективной DNS-политики Windows вернул ошибку $status" }
        val policies = value(output, "cmdletOutput", objectFields = windowsNrptProperties) ?: return emptyList()
        check(policies is List<*>) { "Windows не вернула массив эффективной NRPT-политики" }
        @Suppress("UNCHECKED_CAST")
        return policies as List<Map<String, Any?>>
    }
    private fun rows(service: Pointer, className: String, properties: List<String>,
                     predicate: String = "Store = 1 AND CompartmentId = 1"): List<Map<String, Any?>> {
        val output = PointerByReference()
        // Match Get-NetRoute/Get-NetIPInterface without IncludeAllCompartments: the primary compartment is 1.
        bstr("WQL") { language -> bstr("SELECT ${properties.joinToString(",")} FROM $className WHERE $predicate") { query ->
            checkHr(callWindowsCom(service, 20, language, query, 0x30, null, output), "WMI query $className")
        } }
        val enumeration = own(output.value)
        val rows = mutableListOf<Map<String, Any?>>()
        while (true) {
            active()
            val item = PointerByReference(); val returned = IntByReference()
            val status = callWindowsCom(enumeration, 4, 100, 1, item, returned)
            checkHr(status)
            if (returned.value == 1) {
                val instance = checkNotNull(item.value)
                try {
                    check(rows.size < 4096) { "Windows вернула слишком много сетевых маршрутов" }
                    rows += properties.associateWith { value(instance, it) }
                } finally { callWindowsCom(instance, 2) }
            }
            if (status == 1) return rows // WBEM_S_FALSE is end of enumeration, including an empty table.
        }
    }
    fun physicalInterface(index: Int): Boolean {
        require(index > 0)
        val rows = rows(connect("ROOT\\StandardCimv2"), "MSFT_NetAdapter", listOf("InterfaceIndex", "HardwareInterface", "Virtual"),
            "InterfaceIndex = $index")
        return rows.singleOrNull()?.let { it["HardwareInterface"] == true && it["Virtual"] == false } == true
    }

    fun routes(): JSONArray {
        val service = connect("ROOT\\StandardCimv2")
        val definition = objectAt(service, "MSFT_NetRoute")
        val qualifiersRef = PointerByReference()
        checkHr(callWindowsCom(definition, 11, WString("State"), qualifiersRef))
        val qualifiers = own(qualifiersRef.value)
        val names = windowsPolicyStrings(value(qualifiers, "Values", qualifier = true))
        val values = windowsPolicyStrings(value(qualifiers, "ValueMap", qualifier = true))
        val position = names.indexOfFirst { it.equals("Alive", true) }
        check(position >= 0 && values.size == names.size) { "Windows не вернула описание состояния активных маршрутов" }
        val alive = checkNotNull(values[position].toLongOrNull()) { "Windows вернула некорректное состояние активного маршрута" }
        return windowsRouteRows(rows(service, "MSFT_NetIPInterface", listOf("InterfaceIndex", "AddressFamily", "InterfaceMetric", "ConnectionState", "Store", "CompartmentId")),
            rows(service, "MSFT_NetRoute", listOf("DestinationPrefix", "InterfaceAlias", "InterfaceIndex", "AddressFamily", "RouteMetric", "State", "Store", "CompartmentId")), alive)
    }
}

private fun unsupportedWindowsNrptSecurity(rule: JSONObject): Boolean {
    fun flag(name: String): Boolean {
        if (rule.isNull(name)) return false
        val value = rule.get(name)
        require(value is Boolean) { "Windows вернула некорректный параметр безопасности NRPT" }
        return value
    }
    fun nonDefault(name: String, vararg defaults: String): Boolean {
        val value = if (rule.isNull(name)) "" else rule.getString(name)
        return value.isNotBlank() && defaults.none { value.equals(it, true) }
    }
    return listOf("DirectAccessEnabled", "DirectAccessQueryIPsecRequired", "DnsSecQueryIPsecRequired", "DnsSecValidationRequired").any(::flag) ||
        listOf("DirectAccessIPsecCARestriction", "DirectAccessProxyName", "DnsSecIPsecCARestriction").any { nonDefault(it) } ||
        listOf("DirectAccessQueryIPsecEncryption", "DnsSecQueryIPsecEncryption").any { nonDefault(it, "None") } ||
        nonDefault("DirectAccessProxyType", "Direct", "UseDefault", "NoProxy") ||
        nonDefault("QueryPolicy", "QueryBoth") || nonDefault("SecureNameQueryFallback", "Disable") ||
        nonDefault("NameEncoding", "Utf8WithoutMapping", "Punycode")
}

internal fun parseWindowsNrptPolicy(json: String): List<WindowsNrptRule> {
    val rules = JSONArray(json)
    return (0 until rules.length()).map { index ->
        val rule = rules.getJSONObject(index)
        val raw = rule.getString("Namespace").trim().lowercase(Locale.ROOT)
        val namespace = if (raw == ".") raw else raw.removeSuffix(".")
        val host = namespace.removePrefix(".")
        require(namespace == "." || host.length in 1..253 && host.split('.').all {
            it.matches(Regex("[a-z0-9_](?:[a-z0-9_-]{0,61}[a-z0-9_])?"))
        }) { "Неподдерживаемое пространство имён Windows NRPT: $raw" }
        val unsupported = unsupportedWindowsNrptSecurity(rule)
        val servers = if (rule.isNull("NameServers")) emptyList() else rule.getJSONArray("NameServers").let { values ->
            (0 until values.length()).map { dnsAddress(values.getString(it)) }.distinct()
        }
        WindowsNrptRule(namespace, servers, unsupported)
    }.distinct().also { values ->
        require(values.distinctBy { it.namespace }.size == values.size) { "Windows вернула противоречивые правила NRPT" }
    }.sortedWith(compareByDescending<WindowsNrptRule> { it.namespace.trim('.').length }.thenBy { it.namespace.startsWith('.') })
}

internal fun parseWindowsDnsPolicy(json: String, adapters: List<WindowsDnsServer>): WindowsDnsPolicy {
    val snapshot = JSONObject(json)
    val nrpt = parseWindowsNrptPolicy(snapshot.getJSONArray("nrpt").toString())
    require(snapshot.has("routes")) { "Windows не вернула состояние маршрутов DNS" }
    val rawRoutes = if (snapshot.isNull("routes")) JSONArray() else snapshot.getJSONArray("routes")
    require(rawRoutes.length() <= 4096) { "Windows вернула слишком много маршрутов" }
    val routes = (0 until rawRoutes.length()).map { index ->
        val row = rawRoutes.getJSONObject(index)
        val prefix = SingBoxConfig.normalizeIpRule(row.getString("prefix"))
        val metric = row.getLong("metric")
        require(row.getString("name").isNotBlank() && row.getInt("index") > 0 && metric in 0..Int.MAX_VALUE.toLong()) {
            "Windows вернула некорректный маршрут DNS"
        }
        prefix to WindowsDnsServer("", row.getString("name"), row.getInt("index"), metric.toInt())
    }.filterNot { it.second.interfaceName.equals("Noki", true) }
    val diagnostics = mutableListOf<String>()
    fun select(address: String, fallback: WindowsDnsServer?): WindowsDnsServer {
        val host = InetAddress.getByName(address.substringBefore('%'))
        val bytes = host.address
        val scope = if ('%' in address) address.substringAfter('%').toIntOrNull().also {
            require(it != null && it > 0) { "Windows вернула некорректный scope DNS" }
        } else fallback?.interfaceIndex?.takeIf { host.isLinkLocalAddress }
        check(!host.isLinkLocalAddress || scope != null) { "Для link-local DNS Windows NRPT требуется scope интерфейса: $address" }
        val scopedAddress = if (host.isLinkLocalAddress && '%' !in address) "$address%$scope" else address
        val selected = routes.filter { (prefix, server) ->
            val subnet = InetAddress.getByName(prefix.substringBefore('/')).address
            subnet.size == bytes.size && (scope == null || scope == server.interfaceIndex) &&
                (0 until prefix.substringAfter('/').toInt()).all { bit ->
                    (subnet[bit / 8].toInt() xor bytes[bit / 8].toInt()) and (1 shl (7 - bit % 8)) == 0
                }
        }.minWithOrNull(compareByDescending<Pair<String, WindowsDnsServer>> { it.first.substringAfter('/').toInt() }
            .thenBy { it.second.metric }.thenBy { it.second.interfaceIndex })
        if (selected != null) {
            // Another VPN's default route must not take ownership of an adapter's configured DNS.
            if (fallback != null && selected.first.substringAfter('/').toInt() == 0)
                return fallback.copy(address = scopedAddress)
            return selected.second.copy(address = scopedAddress, nrptOnly = fallback == null)
        }
        check(fallback != null && !fallback.interfaceName.equals("Noki", true)) { "Не найден исходный интерфейс для DNS-сервера Windows NRPT $address" }
        diagnostics += "DNS $address: adapter fallback ${fallback.interfaceName}, reason=" +
            if (snapshot.isNull("routes")) "route-lookup-unavailable" else "no-eligible-route"
        return fallback.copy(address = scopedAddress)
    }
    val servers = adapters.map { select(it.address, it) }.toMutableList()
    for (address in nrpt.filterNot { it.unsupportedSecurity }.flatMap { it.servers }.distinct()) servers += select(address, null)
    // More-specific physical routes must enter TUN so one routing policy also applies with Kill switch.
    val capture = routes.flatMap { (prefix, _) ->
        val address = InetAddress.getByName(prefix.substringBefore('/'))
        val bits = prefix.substringAfter('/').toInt()
        if (bits == 0 || address.isLoopbackAddress || address.isMulticastAddress || address.isAnyLocalAddress && bits == address.address.size * 8) emptyList()
        else if (bits == address.address.size * 8) listOf(prefix)
        else {
            val first = address.address.copyOf()
            for (bit in bits until first.size * 8) first[bit / 8] = (first[bit / 8].toInt() and (1 shl (7 - bit % 8)).inv()).toByte()
            val second = first.copyOf().apply { this[bits / 8] = (this[bits / 8].toInt() or (1 shl (7 - bits % 8))).toByte() }
            listOf(first, second).map { "${InetAddress.getByAddress(it).hostAddress}/${bits + 1}" }
        }
    }.distinct().sorted()
    if (snapshot.isNull("routes")) diagnostics += "Route capture unavailable; physical bypass remains blocked by Kill switch"
    return WindowsDnsPolicy(servers.distinctBy { Triple(it.address, it.interfaceIndex, it.nrptOnly) }, nrpt, capture, diagnostics)
}

private fun dnsAddress(raw: String, allowNoki: Boolean = false): String {
    val value = raw.trim()
    val host = value.substringBefore('%')
    require('/' !in value && ('%' !in value || ':' in host && value.count { it == '%' } == 1 && value.substringAfter('%').toLongOrNull()?.let { it > 0 } == true)) {
        "Для DNS-сервера Windows NRPT требуется IP-адрес"
    }
    SingBoxConfig.normalizeIpRule(host)
    require(allowNoki || host != "172.19.0.2") { "Windows NRPT не должна указывать на DNS Noki" }
    return InetAddress.getByName(host).hostAddress + if ('%' in value) "%${value.substringAfter('%')}" else ""
}

/** Read all active adapters: a resolver can belong to an interface without a default gateway. */
internal fun windowsSystemDnsServers(): List<WindowsDnsServer> {
    check(Native.POINTER_SIZE == 8) { "Чтение системного DNS требует Windows x64" }
    val read = Function.getFunction("iphlpapi", "GetAdaptersAddresses", Function.ALT_CONVENTION)
    var capacity = 15_000
    repeat(3) {
        check(capacity in 1..1_048_576) { "Windows вернула слишком большой список сетевых адаптеров" }
        val size = IntByReference(capacity)
        Memory(capacity.toLong()).use { buffer ->
            when (val status = read.invokeInt(arrayOf(0, 7, null, buffer, size))) { // AF_UNSPEC, skip unicast/anycast/multicast.
                111 -> capacity = size.value // ERROR_BUFFER_OVERFLOW: the adapter list can change during the read.
                232 -> return emptyList() // ERROR_NO_DATA.
                0 -> {
                    fun offset(pointer: Pointer?, bytes: Long): Long {
                        val position = Pointer.nativeValue(pointer) - Pointer.nativeValue(buffer)
                        check(pointer != null && position >= 0 && bytes >= 0 && position <= buffer.size() - bytes) {
                            "Windows вернула повреждённые данные системного DNS"
                        }
                        return position
                    }
                    fun name(pointer: Pointer?): String {
                        if (pointer == null) return ""
                        val start = offset(pointer, 2)
                        val text = StringBuilder()
                        for (index in 0 until minOf(4096, ((buffer.size() - start) / 2).toInt())) {
                            val char = buffer.getShort(start + index * 2L).toInt().and(0xffff).toChar()
                            if (char == '\u0000') return text.toString()
                            text.append(char)
                        }
                        error("Windows вернула незавершённое имя сетевого адаптера")
                    }
                    val servers = mutableListOf<WindowsDnsServer>()
                    val adapters = mutableSetOf<Long>()
                    var adapter: Pointer? = buffer
                    while (adapter != null) {
                        // IP_ADAPTER_ADDRESSES through OperStatus, fixed by the Windows x64 ABI.
                        val position = offset(adapter, 112)
                        val structureLength = buffer.getInt(position)
                        check(structureLength >= 112 && adapters.add(position)) { "Windows вернула повреждённый список сетевых адаптеров" }
                        val interfaceName = name(buffer.getPointer(position + 72))
                        if (buffer.getInt(position + 104) == 1 &&
                            !interfaceName.equals("Noki", true) &&
                            !name(buffer.getPointer(position + 64)).equals("Noki", true)) {
                            var dns = buffer.getPointer(position + 48)
                            val visited = mutableSetOf<Long>()
                            while (dns != null) {
                                val node = offset(dns, 32) // IP_ADAPTER_DNS_SERVER_ADDRESS + SOCKET_ADDRESS.
                                check(buffer.getInt(node) >= 32 && visited.add(node)) { "Windows вернула повреждённый список DNS-серверов" }
                                val address = buffer.getPointer(node + 16)
                                val socket = offset(address, 2)
                                val length = buffer.getInt(node + 24)
                                val family = buffer.getShort(socket).toInt().and(0xffff)
                                val bytes = when (family) {
                                    2 -> { check(length >= 16); offset(address, length.toLong()); buffer.getByteArray(socket + 4, 4) }
                                    23 -> { check(length >= 28); offset(address, length.toLong()); buffer.getByteArray(socket + 8, 16) }
                                    else -> null
                                }
                                if (bytes != null && !(family == 23 && bytes[0].toInt().and(255) == 0xfe && bytes[1].toInt().and(0xc0) == 0xc0)) {
                                    var host = InetAddress.getByAddress(bytes).hostAddress
                                    if (family == 23) {
                                        val scope = Integer.toUnsignedLong(buffer.getInt(socket + 24))
                                        if (scope != 0L) host += "%$scope"
                                    }
                                    if (host != "172.19.0.2") {
                                        val index = buffer.getInt(position + if (family == 23) 108 else 4)
                                        check(index > 0 && interfaceName.isNotBlank()) { "Windows не вернула интерфейс системного DNS" }
                                        val metric = if (structureLength >= 224) {
                                            offset(adapter, 224)
                                            buffer.getInt(position + if (family == 23) 220 else 216).takeIf { it >= 0 } ?: Int.MAX_VALUE
                                        } else Int.MAX_VALUE
                                        servers.add(WindowsDnsServer(host, interfaceName, index, metric))
                                    }
                                }
                                dns = buffer.getPointer(node + 8)
                            }
                        }
                        adapter = buffer.getPointer(position + 8)
                    }
                    return servers.sortedBy { it.metric }.distinctBy { it.address to it.interfaceIndex }
                }
                else -> error("Не удалось прочитать системный DNS Windows (код $status)")
            }
        }
    }
    error("Не удалось прочитать системный DNS: список сетевых адаптеров постоянно меняется")
}

/** Keep DNS on the OS route: strict_route blocks DNS on the physical interface. Only TCP bypasses the TUN. */
internal fun windowsTcpLatency(host: String, port: Int, timeoutMs: Int, interfaceIndex: Int): Int? =
    if (DesktopBuild.IS_LINUX) LinuxPlatform.tcpLatency(host, port, timeoutMs, interfaceIndex)
    else DeviceLatency.measureTcpConnectMs(host, port, timeoutMs, onDiagnostic = {},
        socketFactory = { WindowsProbeSocket(interfaceIndex) })

/** Connect-only socket: no payload leaves the device; cancellation is checked at most every 25 ms. */
private class WindowsProbeSocket(private val interfaceIndex: Int) : Socket() {
    private val functions = listOf("WSAStartup", "WSACleanup", "socket", "setsockopt", "ioctlsocket",
        "connect", "WSAPoll", "getsockopt", "closesocket").associateWith { Function.getFunction("ws2_32", it, Function.ALT_CONVENTION) }
    private var handle = -1L
    private var initialized = false
    private fun winsock(name: String, vararg args: Any?) =
        functions.getValue(name).invokeInt(args)
    private fun checkSocket(result: Int) { if (result != 0) throw SocketException("Winsock ${Native.getLastError()}") }
    override fun connect(endpoint: SocketAddress, timeout: Int) {
        val address = endpoint as? InetSocketAddress ?: throw SocketException("Invalid probe endpoint")
        val bytes = address.address?.address?.takeIf { it.size == 4 } ?: throw SocketException("No physical IPv4 address")
        Memory(512).use { data -> checkSocket(winsock("WSAStartup", 0x202, data)) }; initialized = true
        handle = functions.getValue("socket").invokeLong(arrayOf(2, 1, 6))
        if (handle == -1L) throw SocketException("Could not create probe socket")
        Memory(4).use { option ->
            option.setInt(0, Integer.reverseBytes(interfaceIndex))
            checkSocket(winsock("setsockopt", handle, 0, 31, option, 4)) // IP_UNICAST_IF, network byte order
            option.setInt(0, 1)
            checkSocket(winsock("ioctlsocket", handle, 0x8004667e.toInt(), option))
        }
        Memory(16).use { target ->
            target.clear(); target.setShort(0, 2)
            target.setByte(2, (address.port ushr 8).toByte()); target.setByte(3, address.port.toByte()); target.write(4, bytes, 0, 4)
            if (winsock("connect", handle, target, 16) == 0) return
            // JNA preserves the native error; another foreign call can overwrite the OS slot.
            val error = Native.getLastError()
            if (error !in setOf(10035, 10036)) throw SocketException("Probe connection failed: $error")
        }
        val deadline = System.nanoTime() + timeout.coerceAtLeast(1) * 1_000_000L
        Memory(16).use { poll -> Memory(4).use { error -> Memory(4).use { length ->
            poll.clear(); poll.setLong(0, handle); poll.setShort(8, 0x10)
            while (true) {
                if (Thread.currentThread().isInterrupted) throw InterruptedIOException("Probe cancelled")
                val remaining = ((deadline - System.nanoTime()) / 1_000_000L).toInt()
                if (remaining <= 0) throw SocketTimeoutException("Probe timed out")
                val ready = winsock("WSAPoll", poll, 1, minOf(remaining, 25))
                if (ready < 0) throw SocketException("Probe poll failed")
                if (ready == 0) continue
                length.setInt(0, 4)
                checkSocket(winsock("getsockopt", handle, 0xffff, 0x1007, error, length))
                if (error.getInt(0) != 0 || poll.getShort(10).toInt() and 0x10 == 0) throw SocketException("Probe connection failed")
                return
            }
        } } }
    }
    override fun close() {
        if (handle != -1L) { winsock("closesocket", handle); handle = -1L }
        if (initialized) { winsock("WSACleanup"); initialized = false }
        super.close()
    }
}
