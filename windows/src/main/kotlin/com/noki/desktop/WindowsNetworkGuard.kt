package com.noki.desktop

import com.sun.jna.*
import com.sun.jna.platform.win32.Advapi32Util
import com.sun.jna.platform.win32.Advapi32
import com.sun.jna.platform.win32.WinNT
import com.sun.jna.platform.win32.WinError
import com.sun.jna.platform.win32.Guid.GUID
import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.LongByReference
import com.sun.jna.ptr.PointerByReference
import com.sun.jna.win32.StdCallLibrary
import org.json.JSONObject
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.lang.ref.Reference

/** User-scoped, persistent WFP filters. Only this SID's Noki filters are changed. */
internal class WindowsNetworkGuard : AutoCloseable {
    interface Wfp : StdCallLibrary {
        fun FwpmEngineOpen0(server: WString?, authn: Int, identity: Pointer?, session: Pointer?, handle: PointerByReference): Int
        fun FwpmEngineClose0(handle: Pointer): Int
        fun FwpmTransactionBegin0(handle: Pointer, flags: Int): Int
        fun FwpmTransactionCommit0(handle: Pointer): Int
        fun FwpmTransactionAbort0(handle: Pointer): Int
        fun FwpmSubLayerAdd0(handle: Pointer, layer: SubLayer, security: Pointer?): Int
        fun FwpmSubLayerDeleteByKey0(handle: Pointer, key: GUID): Int
        fun FwpmFilterAdd0(handle: Pointer, filter: Filter, security: Pointer?, id: LongByReference): Int
        fun FwpmFilterDeleteByKey0(handle: Pointer, key: GUID): Int
        fun FwpmFilterGetByKey0(handle: Pointer, key: GUID, result: PointerByReference): Int
        fun FwpmGetAppIdFromFileName0(file: WString, result: PointerByReference): Int
        fun FwpmFreeMemory0(memory: PointerByReference)
    }
    interface Security : StdCallLibrary {
        fun ConvertStringSecurityDescriptorToSecurityDescriptorW(value: WString, revision: Int, result: PointerByReference, size: IntByReference): Boolean
        fun SetFileSecurityW(path: WString, information: Int, descriptor: Pointer): Boolean
    }
    interface IpHelper : StdCallLibrary {
        fun ConvertInterfaceAliasToLuid(alias: WString, luid: LongByReference): Int
        fun ConvertInterfaceIndexToLuid(index: Int, luid: LongByReference): Int
    }

    @Structure.FieldOrder("name", "description")
    class Display : Structure() { @JvmField var name: WString? = null; @JvmField var description: WString? = null }
    @Structure.FieldOrder("size", "data")
    class Blob : Structure() { @JvmField var size = 0; @JvmField var data: Pointer? = null }
    class ValueUnion : Union() { @JvmField var number = 0L; @JvmField var pointer: Pointer? = null }
    @Structure.FieldOrder("type", "value")
    class Value : Structure() { @JvmField var type = 0; @JvmField var value = ValueUnion() }
    @Structure.FieldOrder("key", "display", "flags", "provider", "providerData", "weight")
    class SubLayer : Structure() {
        @JvmField var key = GUID(); @JvmField var display = Display(); @JvmField var flags = 1
        @JvmField var provider: Pointer? = null; @JvmField var providerData = Blob(); @JvmField var weight: Short = (-1).toShort()
    }
    @Structure.FieldOrder("key", "match", "value")
    class Condition : Structure() { @JvmField var key = GUID(); @JvmField var match = 0; @JvmField var value = Value() }
    @Structure.FieldOrder("type", "key")
    class Action : Structure() { @JvmField var type = 0; @JvmField var key = GUID() }
    class Context : Union() { @JvmField var number = 0L; @JvmField var key = GUID() }
    @Structure.FieldOrder("key", "display", "flags", "provider", "providerData", "layer", "subLayer", "weight", "conditionCount", "conditions", "action", "context", "reserved", "id", "effectiveWeight")
    class Filter : Structure() {
        @JvmField var key = GUID(); @JvmField var display = Display(); @JvmField var flags = 1
        @JvmField var provider: Pointer? = null; @JvmField var providerData = Blob(); @JvmField var layer = GUID(); @JvmField var subLayer = GUID()
        @JvmField var weight = Value(); @JvmField var conditionCount = 0; @JvmField var conditions: Pointer? = null
        @JvmField var action = Action(); @JvmField var context = Context(); @JvmField var reserved: Pointer? = null
        @JvmField var id = 0L; @JvmField var effectiveWeight = Value()
    }
    private val api = Native.load("fwpuclnt", Wfp::class.java)
    private val sid = currentUserSid()
    private val engine: Pointer
    private fun key(name: String) = GUID(UUID.nameUUIDFromBytes("Noki PC WFP:$sid:$name".toByteArray(Charsets.UTF_8)).toString())
    private val layers = listOf("c38d57d1-05a7-4c33-904f-7fbceee60e82", "4a72393b-319f-44bc-84c3-ba54dcb3b6b4")
    // Include obsolete DNS permits so upgrading also removes the former global bypass.
    private val ruleNames = listOf("block", "core", "client", "loopback", "tun", "dns-tcp", "dns-udp", "byedpi")
    init {
        val output = PointerByReference(); checked(api.FwpmEngineOpen0(null, 10, null, null, output)); engine = checkNotNull(output.value)
    }
    fun enabled(): Boolean {
        val output = PointerByReference()
        val status = api.FwpmFilterGetByKey0(engine, key("0:block"), output)
        if (status == 0) { api.FwpmFreeMemory0(output); return true }
        if (status == 0x80320003.toInt()) return false
        checked(status); return false
    }
    fun enable(core: Path, client: Path, tunLuid: Long?, youtube: Boolean = false) {
        val descriptors = mutableListOf<PointerByReference>()
        val security = Native.load("advapi32", Security::class.java)
        val user = PointerByReference()
        val descriptorSize = IntByReference()
        check(security.ConvertStringSecurityDescriptorToSecurityDescriptorW(WString("D:(A;;CC;;;$sid)"), 1, user, descriptorSize))
        fun pointerValue(type: Int, pointer: Pointer) = Value().apply { this.type = type; value.setType(Pointer::class.java); value.pointer = pointer }
        fun numericValue(type: Int, number: Long) = Value().apply { this.type = type; value.setType(java.lang.Long.TYPE); value.number = number }
        fun condition(guid: String, value: Value, match: Int = 0) = Condition().apply { key = GUID(guid); this.value = value; this.match = match }
        val userDescriptor = Blob().apply {
            data = checkNotNull(user.value); size = descriptorSize.value; write()
        }
        val userCondition = condition("af043a0a-b34d-4f86-979c-c90371af6e66", pointerValue(14, userDescriptor.pointer))
        fun appCondition(path: Path): Condition {
            val pointer = PointerByReference(); checked(api.FwpmGetAppIdFromFileName0(WString(path.toAbsolutePath().toString()), pointer))
            descriptors.add(pointer)
            return condition("d78e1e87-8644-4ea5-9437-d809ecefc971", pointerValue(12, checkNotNull(pointer.value)))
        }
        val luid = tunLuid?.let { Memory(8).apply { setLong(0, it) } }
        val physical = if (youtube) {
            val index = requireNotNull(windowsNetworkSnapshot().takeIf { it.available }?.interfaceIndex)
            require(youtubePhysicalInterface(index)) { "youtube_physical_network_unavailable" }
            val value = LongByReference()
            checked(Native.load("iphlpapi", IpHelper::class.java).ConvertInterfaceIndexToLuid(index, value))
            Memory(8).apply { setLong(0, value.value) }
        } else null
        try {
            val coreCondition = appCondition(core)
            val clientCondition = appCondition(client)
            val loopback = condition("632ce23b-5167-435c-86d7-e903684aa80c", numericValue(3, 1), 6)
            val tun = luid?.let { condition("4cd62a49-59c3-4969-b7f3-bda5d32890a4", pointerValue(4, it)) }
            val sidecar = physical?.let {
                val path = core.resolveSibling("ciadpi.exe")
                SingBoxRuntime.verifyYoutubeBinary(path)
                listOf(appCondition(path), condition("4cd62a49-59c3-4969-b7f3-bda5d32890a4", pointerValue(4, it)),
                    condition("3971ef2b-623e-4f9a-8cb1-6e79b806b9a7", numericValue(1, 6)))
            }
            transaction {
                removeFilters()
                val layer = SubLayer().apply { key = key("sublayer"); display.name = WString("Noki PC kill switch") }
                val added = api.FwpmSubLayerAdd0(engine, layer, null)
                if (added != 0x80320009.toInt()) checked(added)
                for ((index, guid) in layers.withIndex()) {
                    fun add(name: String, vararg extra: Condition) {
                        val conditions = listOf(userCondition) + extra
                        val native = Condition().toArray(conditions.size).map { it as Condition }
                        for ((position, source) in conditions.withIndex()) {
                            native[position].key = source.key; native[position].match = source.match; native[position].value = source.value; native[position].write()
                        }
                        val filter = Filter().apply {
                            key = key("$index:$name"); display.name = WString("Noki PC $name"); this.layer = GUID(guid); subLayer = key("sublayer")
                            weight = numericValue(1, if (name == "block") 0 else 15)
                            conditionCount = native.size; this.conditions = native.first().pointer
                            action.type = if (name == "block") 0x1001 else 0x1002
                        }
                        checked(api.FwpmFilterAdd0(engine, filter, null, LongByReference()))
                        Reference.reachabilityFence(native)
                    }
                    add("block"); add("core", coreCondition); add("client", clientCondition); add("loopback", loopback)
                    if (tun != null) add("tun", tun)
                    if (index == 0 && sidecar != null) add("byedpi", *sidecar.toTypedArray())
                }
            }
        } finally {
            descriptors.forEach(api::FwpmFreeMemory0)
            user.value?.let { Kernel32.INSTANCE.LocalFree(it) }
            Reference.reachabilityFence(userDescriptor)
            Reference.reachabilityFence(luid)
            Reference.reachabilityFence(physical)
        }
    }
    fun disable() = transaction {
        removeFilters()
        val status = api.FwpmSubLayerDeleteByKey0(engine, key("sublayer"))
        if (status != 0x80320007.toInt()) checked(status)
    }
    private fun removeFilters() {
        for (index in layers.indices) for (name in ruleNames) {
            val status = api.FwpmFilterDeleteByKey0(engine, key("$index:$name"))
            if (status != 0x80320003.toInt()) checked(status)
        }
    }
    private fun transaction(block: () -> Unit) {
        checked(api.FwpmTransactionBegin0(engine, 0))
        try { block(); checked(api.FwpmTransactionCommit0(engine)) } catch (error: Throwable) { api.FwpmTransactionAbort0(engine); throw error }
    }
    private fun checked(value: Int) { check(value == 0) { "WFP: 0x${Integer.toHexString(value)}" } }
    override fun close() { api.FwpmEngineClose0(engine) }
    companion object {
        fun currentUserSid(): String = WinNT.HANDLEByReference().let { token ->
            check(Advapi32.INSTANCE.OpenProcessToken(Kernel32.INSTANCE.GetCurrentProcess(), WinNT.TOKEN_QUERY, token))
            try {
                val length = IntByReference()
                check(!Advapi32.INSTANCE.GetTokenInformation(token.value, WinNT.TOKEN_INFORMATION_CLASS.TokenUser, null, 0, length)
                    && Native.getLastError() == WinError.ERROR_INSUFFICIENT_BUFFER && length.value > 0) { "GetTokenInformation: ${Native.getLastError()}" }
                val user = WinNT.TOKEN_USER(length.value)
                check(Advapi32.INSTANCE.GetTokenInformation(token.value, WinNT.TOKEN_INFORMATION_CLASS.TokenUser, user, length.value, length)) {
                    "GetTokenInformation: ${Native.getLastError()}"
                }
                Advapi32Util.convertSidToStringSid(user.User.Sid)
            } finally { Kernel32.INSTANCE.CloseHandle(token.value) }
        }
        fun requireCurrentUser(expected: String) {
            check(currentUserSid() == expected) { "UAC должен быть подтверждён под той же учётной записью Windows, что и Noki" }
        }
        fun tunLuid(): Long? {
            val output = LongByReference()
            val status = Native.load("iphlpapi", IpHelper::class.java).ConvertInterfaceAliasToLuid(WString("Noki"), output)
            return if (status == 0) output.value else null
        }
    }
}

/** Short-lived elevated launcher, packaged in the same application. Persistent WFP filters survive its exit. */
internal fun runNetworkGuard(args: Array<String>) {
    require(args.size == 4 || args.size == 6 || args.size == 8)
    WindowsNetworkGuard.requireCurrentUser(args[3]) // Before opening or changing another user's private vault.
    val command = args[0]
    val directory = Path.of(args[1]).toAbsolutePath().normalize()
    val result = directory.resolve(args[2]).normalize()
    require(result.parent == directory && result.fileName.toString().matches(Regex("guard-result-[a-z0-9-]+\\.json")))
    val vault = WindowsVault(directory)
    var core: WinNT.HANDLE? = null
    val reply = JSONObject()
    try {
        WindowsNetworkGuard().use { guard ->
            if (command == "off") {
                require(args.size == 4)
                guard.disable(); Files.deleteIfExists(directory.resolve("guard.dpapi"))
            } else if (command == "on" || command == "youtube-on") {
                require(args.size == 6)
                val binary = Path.of(args[4]).toAbsolutePath().normalize()
                SingBoxRuntime.verifyRuntimeBinary(binary)
                guard.enable(binary, Path.of(args[5]).toAbsolutePath().normalize(), WindowsNetworkGuard.tunLuid(), youtube = command == "youtube-on")
                vault.writeEncrypted("guard.dpapi", "enabled")
            } else {
                require(command == "launch" && args.size == 8)
                val binary = Path.of(args[4]).toAbsolutePath().normalize()
                val config = directory.resolve(args[5]).normalize()
                require(config.parent == directory && config.fileName.toString() == "vpn.json")
                val client = Path.of(args[6]).toAbsolutePath().normalize()
                // This launcher owns integrity; readiness checks the running core's version over its authenticated API.
                SingBoxRuntime.verifyRuntimeBinary(binary)
                val settings = JSONObject(Files.readString(config))
                val controller = settings.getJSONObject("experimental").getJSONObject("clash_api")
                val inbounds = settings.getJSONArray("inbounds").let { array -> (0 until array.length()).map { array.getJSONObject(it) } }
                val tun = inbounds.single { it.getString("type") == "tun" }
                check(tun.optString("dns_mode") == "hijack") { "Защита DNS обязательна" }
                val probe = inbounds.first { it.getString("type") == "mixed" }
                guard.enable(binary, client, null)
                vault.writeEncrypted("guard.dpapi", "enabled")
                // CreateProcess inherits the caller's startup job until the parent accepts the persisted identity.
                core = WindowsShell.launchProcess(binary, listOf("run", "-c", config.toString(),
                    "--startup-report", directory.resolve("core-startup.log").toString()))
                val started = checkNotNull(core)
                val pid = Kernel32.INSTANCE.GetProcessId(started)
                val startedAt = SingBoxRuntime.creationTime(started)
                val identity = SingBoxRuntime.Running(pid, startedAt,
                    controller.getString("external_controller").substringAfterLast(':').toInt(), controller.getString("secret"), probe.getInt("listen_port"), args[7])
                SingBoxRuntime.persistIdentity(vault, identity)
                var luid: Long? = null
                repeat(40) { if (luid == null) { check(Kernel32.INSTANCE.WaitForSingleObject(started, 0) == 258); luid = WindowsNetworkGuard.tunLuid(); if (luid == null) Thread.sleep(250) } }
                check(luid != null) { "Интерфейс Noki не создан" }
                guard.enable(binary, client, luid)
                reply.put("pid", pid).put("started_at", startedAt)
            }
        }
        reply.put("ok", true)
    } catch (error: Throwable) {
        core?.let {
            Kernel32.INSTANCE.TerminateProcess(it, 1)
            if (Kernel32.INSTANCE.WaitForSingleObject(it, 2_000) == 0)
                Files.deleteIfExists(directory.resolve("runtime.dpapi"))
        }
        reply.put("ok", false).put("error", error.message?.take(300) ?: "Windows network guard failed")
    } finally {
        core?.let { Kernel32.INSTANCE.CloseHandle(it) }
        vault.writePrivate(result.fileName.toString(), reply.toString())
    }
}
