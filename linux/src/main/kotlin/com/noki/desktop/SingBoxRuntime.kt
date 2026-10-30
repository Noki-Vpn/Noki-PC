package com.noki.desktop

import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.WinBase
import com.sun.jna.platform.win32.WinNT
import com.sun.jna.ptr.IntByReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import com.noki.vpn.vpn.VpnProbePlanPolicy
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import org.json.JSONArray
import org.json.JSONTokener
import java.io.Reader
import java.io.IOException
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.resume
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

open class SingBoxRuntime(private val vault: WindowsVault,
    private val http: OkHttpClient = OkHttpClient.Builder().connectTimeout(2, TimeUnit.SECONDS).readTimeout(3, TimeUnit.SECONDS).callTimeout(4, TimeUnit.SECONDS).build()) {
    data class Running(val pid: Int, val startedAt: Long, val controlPort: Int, val controlSecret: String, val probePort: Int, val label: String, val serverId: String? = null)
    internal class StartupFailure(message: String, cause: Throwable) : IOException(message, cause)
    internal class ProbeFailure(message: String, cause: Throwable) : IOException(message, cause)
    data class Metrics(val uploadPerSecond: Long, val downloadPerSecond: Long, val uploadTotal: Long, val downloadTotal: Long,
        val nokiNvCarrier: String = "")
    internal data class CoreLogLine(val level: String, val message: String, val receivedAt: Long = System.currentTimeMillis()) {
        val isError: Boolean = when (level.trim().lowercase(java.util.Locale.ROOT)) {
            "error", "fatal", "panic" -> true
            else -> errorMarker.containsMatchIn(message)
        }
        val text: String = java.time.Instant.ofEpochMilli(receivedAt).atZone(java.time.ZoneId.systemDefault())
            .format(timeFormat) + " [${level.uppercase(java.util.Locale.ROOT)}] $message"

        companion object {
            private val timeFormat = java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss")
            private val errorMarker = Regex("(?i)(\\[(error|fatal|panic)]|(^|:\\s*)(error|failed)\\b|\\bfailed to\\b)")
            fun decode(value: String): CoreLogLine {
                val json = JSONObject(value)
                return CoreLogLine(json.getString("type").take(16), json.getString("payload").take(4096))
            }
        }
    }

    internal open fun coreLogs(current: Running): Flow<List<CoreLogLine>> = callbackFlow {
        val pending = ArrayDeque<CoreLogLine>()
        val access = Any()
        fun flush() {
            val lines = synchronized(access) { pending.toList().also { pending.clear() } }
            if (lines.isNotEmpty()) trySend(lines)
        }
        val updates = launch { while (isActive) { delay(100); flush() } }
        val socket = coreLogHttp.newWebSocket(Request.Builder().url("ws://127.0.0.1:${current.controlPort}/logs?level=info")
            .header("Authorization", "Bearer ${current.controlSecret}").build(), object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val line = CoreLogLine.decode(text)
                    synchronized(access) { if (pending.size == 500) pending.removeFirst(); pending.addLast(line) }
                }
                catch (error: Exception) { close(error) }
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { flush(); close(t) }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                flush(); webSocket.close(code, reason); close()
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { close() }
        })
        awaitClose { updates.cancel(); socket.cancel() }
    }.buffer(1, BufferOverflow.DROP_OLDEST)
    private var metricSample: Triple<Running, Metrics, Long>? = null
    private val coreLogHttp = http.newBuilder().proxy(Proxy.NO_PROXY).followRedirects(false).followSslRedirects(false)
        .retryOnConnectionFailure(false).build()
    private val binary: Path = findBinary()
    internal suspend fun ruleSetCommand(command: String, source: Path, output: Path) = withContext(Dispatchers.IO) {
        require(command in setOf("compile", "decompile"))
        verifyRuntimeBinary(binary)
        if (DesktopBuild.IS_LINUX) {
            val result = runInterruptible { LinuxPlatform.command(listOf(binary.toString(), "rule-set", command, source.toString(), "-o", output.toString())) }
            check(result.first == 0) { "Не удалось прочитать список sing-box. Проверьте его формат" }
            require(Files.isRegularFile(output) && Files.size(output) in 1..WindowsRuleSets.MAX_BYTES) { "Пустой или слишком большой список" }
            return@withContext
        }
        val process = WindowsShell.launchProcess(binary, listOf("rule-set", command, source.toString(), "-o", output.toString()))
        try {
            kotlinx.coroutines.withTimeout(30_000) {
                while (Kernel32.INSTANCE.WaitForSingleObject(process, 0) == 258) {
                    require(!Files.exists(output) || Files.size(output) <= WindowsRuleSets.MAX_BYTES) { "Распакованный список больше 32 МБ" }
                    delay(50)
                }
            }
            val code = IntByReference()
            check(Kernel32.INSTANCE.GetExitCodeProcess(process, code) && code.value == 0) { "Не удалось прочитать список sing-box. Проверьте его формат" }
            require(Files.isRegularFile(output) && Files.size(output) in 1..WindowsRuleSets.MAX_BYTES) { "Пустой или слишком большой список" }
        } finally {
            if (Kernel32.INSTANCE.WaitForSingleObject(process, 0) == 258) {
                Kernel32.INSTANCE.TerminateProcess(process, 1)
                Kernel32.INSTANCE.WaitForSingleObject(process, 2_000)
            }
            Kernel32.INSTANCE.CloseHandle(process)
        }
    }
    @Volatile private var running: Running? = loadRunning()
    private fun loadRunning(): Running? = vault.readEncrypted("runtime.dpapi")?.let {
        val json = JSONObject(it)
        Running(json.getInt("pid"), json.getLong("started_at"), json.getInt("control_port"),
            json.getString("control_secret"), json.getInt("probe_port"), json.getString("label"), json.optString("server_id").ifBlank { null })
    }

    open fun restored(): Running? = running?.takeIf(::matches)
        ?: loadRunning()?.takeIf(::matches)?.also { running = it }

    open fun guardArmed(): Boolean = runCatching { if (DesktopBuild.IS_LINUX) LinuxPlatform.helper("status").getBoolean("guard") else WindowsNetworkGuard().use { it.enabled() } }
        .getOrElse { if (DesktopBuild.IS_LINUX) true else runCatching { vault.readEncrypted("guard.dpapi") != null }.getOrDefault(true) }

    open suspend fun start(config: JSONObject, label: String, killSwitch: Boolean = false, serverId: String? = null): Running = withContext(Dispatchers.IO) {
        check(restored() == null) { "Сначала отключите текущее соединение" }
        val logPath = vault.directory.resolve("core.log").toAbsolutePath()
        if (!Files.exists(logPath)) vault.writePrivate("core.log", "")
        val runtimeConfig = withLocalCoreLog(config, logPath)
        val path = vault.writePrivate("vpn.json", runtimeConfig.toString())
        val report = vault.directory.resolve("core-startup.log")
        try {
            retryTunAdapterStartup(cleanup = {
                if (DesktopBuild.IS_LINUX || restored() != null) stop(restoreInternet = false) else clearRecord()
            }) {
                try {
                    vault.writePrivate(report.fileName.toString(), "launch-pending")
                    // Native run validates the same configuration and reports startup failures.
                    currentCoroutineContext().ensureActive()
                    val identity = if (DesktopBuild.IS_LINUX) {
                        runInterruptible { verifyRuntimeBinary(binary) }
                        if (killSwitch) protectConnection() else if (guardArmed()) releaseGuard()
                        val launchPath = LinuxPlatform.runtimeDirectory.resolve("vpn.json")
                        Files.copy(path, launchPath, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                        LinuxPlatform.privatePath(launchPath)
                        val reply = runInterruptible { LinuxPlatform.helper("start") }
                        val pid = reply.getInt("pid")
                        val startedAt = ProcessHandle.of(pid.toLong()).orElseThrow().info().startInstant().orElseThrow().toEpochMilli()
                        check(LinuxPlatform.coreMatches(pid, startedAt)) { "VPN-процесс завершился при запуске" }
                        pid to startedAt
                    } else if (killSwitch) {
                        val reply = guardHelper("launch", binary.toString(), path.fileName.toString(),
                            ProcessHandle.current().info().command().orElseThrow(), label)
                        reply.getInt("pid") to reply.getLong("started_at")
                    } else {
                        // The process that launches the core owns the single prelaunch integrity check.
                        runInterruptible { verifyRuntimeBinary(binary) }
                        currentCoroutineContext().ensureActive()
                        if (guardArmed()) releaseGuard()
                        currentCoroutineContext().ensureActive()
                        val handle = WindowsShell.launchProcess(binary, listOf("run", "-c", path.toString(), "--startup-report", report.toString()))
                        try { Kernel32.INSTANCE.GetProcessId(handle) to creationTime(handle) }
                        finally { Kernel32.INSTANCE.CloseHandle(handle) }
                    }
                    val controller = config.getJSONObject("experimental").getJSONObject("clash_api")
                    val controlPort = controller.getString("external_controller").substringAfterLast(':').toInt()
                    val mixed = config.getJSONArray("inbounds").let { array -> (0 until array.length()).map { array.getJSONObject(it) }.first { it.getString("type") == "mixed" } }
                    val current = Running(identity.first, identity.second, controlPort, controller.getString("secret"), mixed.getInt("listen_port"), label, serverId)
                    running = current
                    persistIdentity(vault, current)
                    currentCoroutineContext().ensureActive()
                    var ready = false
                    val startupSeconds = if (config.getJSONArray("inbounds").let { array ->
                        (0 until array.length()).any { array.getJSONObject(it).has("windows_dns") }
                    }) 30L else 10L
                    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(startupSeconds)
                    while (System.nanoTime() < deadline) {
                        if (!matches(current)) error("VPN-ядро завершилось при запуске")
                        val remaining = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()).coerceAtLeast(1)
                        if (withTimeoutOrNull(remaining) { apiReady(coreLogHttp, current) } == true) { ready = true; break }
                        delay(250)
                    }
                    check(ready) { "VPN-ядро не ответило за $startupSeconds секунд" }
                    if (DesktopBuild.IS_LINUX) runInterruptible { LinuxPlatform.helper("dns-on") }
                    current
                } catch (error: Exception) {
                    val detail = runCatching { startupDiagnostic(report, config) }.getOrDefault("")
                    val failure = if (error is kotlinx.coroutines.CancellationException || detail.isBlank()) error
                        else StartupFailure("${error.message.orEmpty()} [$detail]", error)
                    throw failure
                }
            }
        } finally {
            Files.deleteIfExists(path)
            if (DesktopBuild.IS_LINUX) Files.deleteIfExists(LinuxPlatform.runtimeDirectory.resolve("vpn.json"))
            Files.deleteIfExists(report)
        }
    }

    open suspend fun probe(current: Running): Long = withContext(Dispatchers.IO) {
        check(matches(current) && apiReady(coreLogHttp, current)) { "VPN-ядро не работает" }
        probeTargets(current.probePort, VpnProbePlanPolicy.connectedTargets(recovery = false))
    }

    open suspend fun probe(current: Running, fastStartup: Boolean): Long {
        if (!fastStartup) return probe(current)
        check(matches(current)) { "VPN-ядро не работает" }
        return probeTargets(current.probePort, VpnProbePlanPolicy.candidateTargets(), fastStartup = true)
    }

    open suspend fun probeWatchdog(current: Running): Long {
        check(matches(current)) { "VPN-ядро не работает" }
        return probeWatchdogTargets(current.probePort, VpnProbePlanPolicy.connectedTargets(recovery = false))
    }

    internal suspend fun probeWatchdogTargets(port: Int, targets: List<VpnProbePlanPolicy.ProbeTarget>): Long = coroutineScope {
        val connections = okhttp3.ConnectionPool()
        val timeout = VpnProbePlanPolicy.PER_TARGET_TIMEOUT_MILLIS
        val client = http.newBuilder().proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", port)))
            .followRedirects(false).followSslRedirects(false).connectionPool(connections).cache(null).retryOnConnectionFailure(false)
            .connectTimeout(timeout, TimeUnit.MILLISECONDS).readTimeout(timeout, TimeUnit.MILLISECONDS).callTimeout(timeout, TimeUnit.MILLISECONDS).build()
        val replies = Channel<Result<Long>>(targets.size)
        val pending = targets.map { target -> launch {
            val reply = try {
                val started = System.nanoTime()
                val call = client.newCall(Request.Builder().url(target.url).header("Accept-Encoding", "identity").get().build())
                Result.success(call.await {
                    // A verified HTTPS response proves the route is alive even when the website returns an error.
                    call.cancel()
                    ((System.nanoTime() - started) / 1_000_000).coerceAtLeast(1)
                })
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (error: IOException) { Result.failure(IOException("${target.key}: ${error.message}", error)) }
            replies.send(reply)
        } }
        try {
            val failure = IOException("Все проверки доступности через VPN завершились ошибкой")
            repeat(targets.size) {
                val reply = replies.receive()
                reply.getOrNull()?.let { return@coroutineScope it }
                failure.addSuppressed(checkNotNull(reply.exceptionOrNull()))
            }
            failure.suppressed.firstOrNull()?.let(failure::initCause)
            throw failure
        } finally {
            pending.forEach { it.cancel() }
            replies.cancel()
            connections.evictAll()
        }
    }

    /** Reset physical sockets in the same core; only a subsequent data-plane probe proves readiness. */
    open suspend fun rebindNetwork(current: Running): Unit = withContext(Dispatchers.IO) {
        check(restored() == current) { "VPN-ядро было заменено" }
        coreLogHttp.newCall(Request.Builder().url("http://127.0.0.1:${current.controlPort}/noki/network/rebind")
            .header("Authorization", "Bearer ${current.controlSecret}").post(ByteArray(0).toRequestBody()).build()).await { response ->
            check(response.code == 204) { "VPN-ядро не обновило физическую сеть (HTTP ${response.code})" }
        }
        currentCoroutineContext().ensureActive()
        check(restored() == current) { "VPN-ядро было заменено" }
    }

    internal suspend fun probeTargets(port: Int, targets: List<VpnProbePlanPolicy.ProbeTarget>, fastStartup: Boolean = false): Long {
        // Android takes the faster of two requests, reusing connections only within this measurement.
        val connections = okhttp3.ConnectionPool()
        try {
            val totalTimeout = if (fastStartup) VpnProbePlanPolicy.STARTUP_TOTAL_TIMEOUT_MILLIS else VpnProbePlanPolicy.TOTAL_TIMEOUT_MILLIS
            val perTargetTimeout = if (fastStartup) VpnProbePlanPolicy.STARTUP_PER_TARGET_TIMEOUT_MILLIS else VpnProbePlanPolicy.PER_TARGET_TIMEOUT_MILLIS
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(totalTimeout)
            val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", port))
            val failure = IOException("Туннель запущен, но проверка доступа в Интернет не прошла")
            for (target in targets) {
                currentCoroutineContext().ensureActive()
                val remaining = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())
                if (remaining <= 0) break
                val timeout = minOf(remaining, perTargetTimeout)
                val client = http.newBuilder().proxy(proxy).followRedirects(false).followSslRedirects(false)
                    .connectionPool(connections).cache(null).retryOnConnectionFailure(false)
                    .connectTimeout(timeout, TimeUnit.MILLISECONDS).readTimeout(timeout, TimeUnit.MILLISECONDS).callTimeout(timeout, TimeUnit.MILLISECONDS).build()
                var best: Long? = null
                var responseBudget = 256 * 1024
                var completed = false
                val latency = withTimeoutOrNull(timeout) {
                    for (attempt in 0 until (if (fastStartup) 1 else 2)) {
                        if (responseBudget <= 0) break
                        currentCoroutineContext().ensureActive()
                        val started = System.nanoTime()
                        try {
                            val call = client.newCall(Request.Builder().url(target.url).header("Accept-Encoding", "identity").get().build())
                            val sample = call.await { response ->
                                if (response.code != 200 && response.code != 204) {
                                    call.cancel()
                                    throw IOException("HTTP ${response.code}")
                                }
                                val body = checkNotNull(response.body).byteStream()
                                val buffer = ByteArray(8192)
                                while (true) {
                                    val count = body.read(buffer, 0, minOf(buffer.size, responseBudget + 1))
                                    if (count < 0) break
                                    responseBudget -= count
                                    if (responseBudget < 0) throw IOException("Probe response byte budget exceeded")
                                }
                                ((System.nanoTime() - started) / 1_000_000).coerceAtLeast(1)
                            }
                            best = minOf(best ?: sample, sample)
                        } catch (error: IOException) {
                            currentCoroutineContext().ensureActive()
                            failure.addSuppressed(ProbeFailure("${target.key}: ${error.javaClass.simpleName}", error))
                            if (responseBudget < 0) { completed = true; return@withTimeoutOrNull null }
                        }
                    }
                    completed = true
                    best
                }
                (latency ?: best.takeIf { responseBudget >= 0 })?.let { return it }
                if (!completed && best == null) failure.addSuppressed(ProbeFailure("${target.key}: deadline ${timeout} ms", java.net.SocketTimeoutException()))
            }
            failure.suppressed.firstOrNull()?.let(failure::initCause)
            throw failure
        } finally { connections.evictAll() }
    }

    open suspend fun metrics(current: Running): Metrics = withContext(Dispatchers.IO) {
        check(matches(current))
        val totals = coreLogHttp.newCall(Request.Builder().url("http://127.0.0.1:${current.controlPort}/connections")
            .header("Authorization", "Bearer ${current.controlSecret}").build()).await { response ->
            check(response.isSuccessful)
            readTotals(checkNotNull(response.body).charStream())
        }
        val carrier = coreLogHttp.newCall(Request.Builder().url("http://127.0.0.1:${current.controlPort}/noki/nokinv/carrier")
            .header("Authorization", "Bearer ${current.controlSecret}").build()).await { response ->
            check(response.isSuccessful)
            val bytes = checkNotNull(response.body).byteStream().readNBytes(1025)
            require(bytes.size <= 1024)
            JSONObject(String(bytes, Charsets.UTF_8)).getString("carrier").also { require(it in setOf("", "quic", "tls", "mixed")) }
        }
        synchronized(this@SingBoxRuntime) {
            val now = System.nanoTime()
            val previous = metricSample?.takeIf { it.first == current }
            if (previous != null && (totals.first < previous.second.uploadTotal || totals.second < previous.second.downloadTotal))
                return@synchronized previous.second.copy(nokiNvCarrier = carrier)
            val elapsed = previous?.let { (now - it.third) / 1_000_000_000.0 } ?: 0.0
            fun rate(total: Long, old: Long) = if (elapsed > 0) ((total - old).coerceAtLeast(0) / elapsed).toLong() else 0
            Metrics(rate(totals.first, previous?.second?.uploadTotal ?: 0), rate(totals.second, previous?.second?.downloadTotal ?: 0), totals.first, totals.second, carrier)
                .also { metricSample = Triple(current, it, now) }
        }
    }

    open suspend fun stop(restoreInternet: Boolean = true): Unit = withContext(Dispatchers.IO) {
        val current = running
        if (DesktopBuild.IS_LINUX) {
            // Stop the caller's fixed service even when startup failed before its identity was saved.
            LinuxPlatform.helper("stop")
            check(current == null || !matches(current)) { "VPN не остановлен. Повторите отключение" }
            clearRecord()
            if (restoreInternet && guardArmed()) releaseGuard()
            return@withContext
        }
        if (current == null || !matches(current)) { clearRecord(); if (restoreInternet && guardArmed()) releaseGuard(); return@withContext }
        stopProcess(current, binary, coreLogHttp)
        check(!matches(current)) { "VPN не остановлен. Повторите отключение" }
        clearRecord()
        if (restoreInternet && guardArmed()) releaseGuard()
    }

    open suspend fun protectConnection(): Unit = withContext(Dispatchers.IO) {
        if (DesktopBuild.IS_LINUX) LinuxPlatform.helper("on") else guardHelper("on", binary.toString(), ProcessHandle.current().info().command().orElseThrow())
        check(guardArmed()) { "Windows не включила сетевую блокировку" }
    }

    open suspend fun releaseGuard(allowConnected: Boolean = false): Unit = withContext(Dispatchers.IO) {
        check(allowConnected || restored() == null) { "Сначала отключите VPN" }
        if (DesktopBuild.IS_LINUX) LinuxPlatform.helper("off") else guardHelper("off")
        check(!guardArmed()) { "Windows не сняла сетевую блокировку" }
    }

    private suspend fun guardHelper(command: String, vararg arguments: String): JSONObject {
        val resultName = "guard-result-${java.util.UUID.randomUUID()}.json"
        val result = vault.directory.resolve(resultName)
        try {
            return withGuardHelperProcess(launch = { job ->
                WindowsShell.launchApplication(listOf("--network-guard", command,
                    vault.directory.toString(), resultName, WindowsNetworkGuard.currentUserSid()) + arguments, job = job)
            }) {
                check(Files.isRegularFile(result)) { "Noki не вернул результат управления VPN" }
                val reply = JSONObject(Files.readString(result))
                check(reply.optBoolean("ok")) { reply.optString("error", "Windows network guard failed") }
                if (command == "launch") {
                    // The helper persists identity before exit. Adopt that live process before releasing the job.
                    val current = checkNotNull(restored()) { "Noki не сохранил запущенный VPN-процесс" }
                    check(current.pid == reply.getInt("pid") && current.startedAt == reply.getLong("started_at")) {
                        "Noki вернул другой VPN-процесс"
                    }
                }
                reply
            }
        } finally { Files.deleteIfExists(result) }
    }

    private fun matches(current: Running): Boolean {
        if (DesktopBuild.IS_LINUX) return LinuxPlatform.coreMatches(current.pid, current.startedAt)
        val handle = Kernel32.INSTANCE.OpenProcess(0x1000 or 0x100000, false, current.pid) ?: return false
        return try {
            val path = CharArray(32768)
            val length = IntByReference(path.size)
            Kernel32.INSTANCE.WaitForSingleObject(handle, 0) == 258 &&
                Kernel32.INSTANCE.QueryFullProcessImageName(handle, 0, path, length) &&
                Path.of(String(path, 0, length.value)).toAbsolutePath().normalize() == binary.toAbsolutePath().normalize() &&
                creationTime(handle) == current.startedAt
        } finally { Kernel32.INSTANCE.CloseHandle(handle) }
    }

    private fun clearRecord() { running = null; Files.deleteIfExists(vault.directory.resolve("runtime.dpapi")) }

    companion object {
        internal suspend fun <T> withGuardHelperProcess(timeoutMillis: Long = 20_000,
            launch: (WinNT.HANDLE) -> WinNT.HANDLE, accept: () -> T): T {
            val job = WindowsShell.createStartupJob()
            var helper: WinNT.HANDLE? = null
            var handedOff = false
            var failure: Throwable? = null
            try {
                currentCoroutineContext().ensureActive()
                val process = launch(job).also { helper = it }
                val exited = withTimeoutOrNull(timeoutMillis) {
                    while (true) {
                        when (Kernel32.INSTANCE.WaitForSingleObject(process, 0)) {
                            0 -> return@withTimeoutOrNull true
                            258 -> delay(25)
                            else -> error("Windows не разрешила проверить helper")
                        }
                    }
                }
                check(exited == true) { "Windows не завершила настройку сетевой блокировки" }
                currentCoroutineContext().ensureActive()
                val result = accept()
                currentCoroutineContext().ensureActive()
                WindowsShell.setStartupJobKillOnClose(job, false)
                handedOff = true
                return result
            } catch (error: Throwable) { failure = error; throw error }
            finally {
                try {
                    if (!handedOff) withContext(NonCancellable) {
                        WindowsShell.terminateStartupJob(job)
                        check(withTimeoutOrNull(2_000) {
                            while (true) {
                                val status = helper?.let { Kernel32.INSTANCE.WaitForSingleObject(it, 0) } ?: 0
                                check(status == 0 || status == 258) { "Windows не разрешила проверить helper" }
                                if (status == 0 && WindowsShell.activeStartupProcesses(job) == 0) break
                                delay(25)
                            }
                            true
                        } == true) { "Windows не подтвердила завершение helper и VPN-процесса" }
                    }
                } catch (cleanup: Throwable) {
                    if (failure != null) failure.addSuppressed(cleanup) else throw cleanup
                } finally {
                    helper?.let { Kernel32.INSTANCE.CloseHandle(it) }
                    Kernel32.INSTANCE.CloseHandle(job)
                }
            }
        }

        internal suspend fun apiReady(http: OkHttpClient, current: Running): Boolean = try {
            http.newCall(Request.Builder().url("http://127.0.0.1:${current.controlPort}/version")
                .header("Authorization", "Bearer ${current.controlSecret}").get().build()).await { response ->
                if (!response.isSuccessful) return@await false
                val bytes = checkNotNull(response.body).byteStream().readNBytes(4097)
                check(bytes.size <= 4096) { "Некорректный ответ версии sing-box" }
                val version = try { JSONObject(bytes.toString(Charsets.UTF_8)).getString("version") }
                catch (error: org.json.JSONException) { throw IllegalStateException("Некорректный ответ версии sing-box", error) }
                check(version == "sing-box $BINARY_VERSION") { "Версия sing-box не совпадает" }
                true
            }
        } catch (_: IOException) { currentCoroutineContext().ensureActive(); false }

        internal suspend fun <T> retryTunAdapterStartup(cleanup: suspend () -> Unit, launch: suspend () -> T): T {
            var retries = 0
            while (true) {
                currentCoroutineContext().ensureActive()
                try { return launch() }
                catch (failure: Exception) {
                    withContext(NonCancellable) {
                        try { cleanup() }
                        catch (error: Exception) { failure.addSuppressed(error) }
                    }
                    val detail = failure.message.orEmpty()
                    val adapterRace = failure is StartupFailure &&
                        detail.contains("configure tun interface:") &&
                        detail.contains("create adapter: Cannot create a file when that file already exists.") &&
                        detail.contains("open existing adapter: Element not found.")
                    if (!adapterRace || failure.suppressed.isNotEmpty() || retries == 3) throw failure
                    // Windows may still be removing the old Wintun device after its process has exited.
                    delay(500L * ++retries)
                }
            }
        }

        internal fun withLocalCoreLog(config: JSONObject, path: Path): JSONObject = JSONObject(config.toString()).apply {
            val log = optJSONObject("log") ?: JSONObject().also { put("log", it) }
            log.put("disabled", false).put("level", "debug").put("timestamp", true).put("output", path.toString())
        }

        internal fun startupDiagnostic(path: Path, config: JSONObject): String {
            var message = Files.newInputStream(path).use { it.readNBytes(8192).toString(Charsets.UTF_8) }
            fun redact(value: Any?) {
                when (value) {
                    is JSONObject -> value.keys().forEach { key ->
                        val nested = value.opt(key)
                        if (key.lowercase(java.util.Locale.ROOT) in setOf("password", "private_key", "secret", "uuid", "token", "authorization", "short_id") &&
                            nested is String && nested.isNotEmpty()) message = message.replace(nested, "<redacted>")
                        else redact(nested)
                    }
                    is JSONArray -> for (index in 0 until value.length()) redact(value.opt(index))
                }
            }
            redact(config)
            return DesktopDiagnostics.safeMessage(message).trim()
        }

        internal fun creationTime(handle: WinNT.HANDLE): Long {
            val creation = WinBase.FILETIME()
            check(Kernel32.INSTANCE.GetProcessTimes(handle, creation, WinBase.FILETIME(), WinBase.FILETIME(), WinBase.FILETIME()))
            return creation.toDate().time
        }

        internal fun stopProcess(current: Running, executable: Path, http: OkHttpClient) {
            require(current.pid > 0 && current.startedAt > 0)
            val kernel = Kernel32.INSTANCE
            val process = kernel.OpenProcess(0x1000 or 0x100000 or 1, false, current.pid)
            if (process == null) {
                check(kernel.GetLastError() == 87) { "Windows не разрешила проверить VPN-процесс" }
                return
            }
            fun checkIdentity() {
                val path = CharArray(32768)
                val length = IntByReference(path.size)
                check(kernel.QueryFullProcessImageName(process, 0, path, length))
                check(Path.of(String(path, 0, length.value)).toAbsolutePath().normalize() == executable.toAbsolutePath().normalize()) { "Путь VPN-процесса изменился" }
                check(creationTime(process) == current.startedAt) { "VPN-процесс был заменён" }
            }
            try {
                checkIdentity()
                if (kernel.WaitForSingleObject(process, 0) == 0) return
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
                val acknowledged = try {
                    val call = http.newCall(Request.Builder().url("http://127.0.0.1:${current.controlPort}/noki/shutdown")
                        .header("Authorization", "Bearer ${current.controlSecret}").post(ByteArray(0).toRequestBody()).build())
                    call.timeout().timeout(750, TimeUnit.MILLISECONDS)
                    call.execute().use { it.code == 204 }
                } catch (_: Exception) { false }
                // The acknowledgement precedes Box.Close; only the process handle proves TUN cleanup has finished.
                val remaining = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()).coerceIn(0, 2_000).toInt()
                if (kernel.WaitForSingleObject(process, if (acknowledged) remaining else 0) == 0) return
                checkIdentity()
                check(kernel.TerminateProcess(process, 1) || kernel.WaitForSingleObject(process, 0) == 0) { "Windows не смогла завершить VPN-процесс" }
                check(kernel.WaitForSingleObject(process, 2_000) == 0) { "Windows не подтвердила завершение VPN-процесса" }
            } finally { kernel.CloseHandle(process) }
        }

        internal fun readTotals(reader: Reader): Pair<Long, Long> {
            val tokens = JSONTokener(reader)
            check(tokens.nextClean() == '{')
            var upload: Long? = null
            var download: Long? = null
            while (true) {
                val quote = tokens.nextClean()
                if (quote == '}') break
                check(quote == '"')
                val key = tokens.nextString(quote)
                check(tokens.nextClean() == ':')
                if (key == "uploadTotal" || key == "downloadTotal") {
                    val value = tokens.nextValue()
                    val counter = (value as? Number)?.toString()?.toLongOrNull()
                    require(counter != null && counter >= 0)
                    if (key == "uploadTotal") upload = counter else download = counter
                } else skipMetricValue(tokens)
                when (tokens.nextClean()) {
                    '}' -> break
                    ',' -> Unit
                    else -> error("Invalid VPN metrics")
                }
            }
            return checkNotNull(upload) to checkNotNull(download)
        }

        private fun skipMetricValue(tokens: JSONTokener) {
            var depth = 0
            var quoted = false
            var escaped = false
            var count = 0
            while (true) {
                val char = tokens.next()
                check(char != '\u0000' && ++count <= 32 * 1024 * 1024) { "Invalid VPN metrics" }
                if (quoted) {
                    if (escaped) escaped = false
                    else if (char == '\\') escaped = true
                    else if (char == '"') quoted = false
                } else when (char) {
                    '"' -> quoted = true
                    '{', '[' -> depth++
                    '}', ']' -> if (depth == 0) { tokens.back(); return } else depth--
                    ',' -> if (depth == 0) { tokens.back(); return }
                }
            }
        }

        private suspend fun <T> Call.await(read: (Response) -> T): T = suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { cancel() }
            enqueue(object : Callback {
                override fun onFailure(call: Call, error: IOException) { if (continuation.isActive) continuation.resumeWithException(error) }
                override fun onResponse(call: Call, response: Response) {
                    try {
                        val result = response.use {
                            try { read(it) } catch (error: Exception) { call.cancel(); throw error }
                        }
                        if (continuation.isActive) continuation.resume(result)
                    } catch (error: Exception) {
                        if (continuation.isActive) continuation.resumeWithException(error)
                    }
                }
            })
        }
        const val BINARY_HASH = "6058ec64bf5240f64dd2dca13f6aea60ee4f542ec3649c518a082824be9defff"
        const val BINARY_VERSION = "1.14.1-extended-2.7.2-nokinv-30-pc"
        const val CRONET_HASH = "3217c6260fbca5f16072e0b79735742f40109a63bb0ff88fd6b96dd6b54a2928"
        internal fun persistIdentity(vault: WindowsVault, current: Running) = vault.writeEncrypted("runtime.dpapi", JSONObject()
            .put("pid", current.pid).put("started_at", current.startedAt).put("control_port", current.controlPort)
            .put("control_secret", current.controlSecret).put("probe_port", current.probePort).put("label", current.label).put("server_id", current.serverId).toString())
        internal fun verifyRuntimeBinary(binary: Path) {
            check(BINARY_HASH != "PENDING_WINDOWS_BUILD") { "Windows VPN-ядро $BINARY_VERSION ещё не собрано" }
            check(Files.isRegularFile(binary)) { "sing-box не найден. Запустите подготовку VPN-ядра" }
            fun digest(path: Path) = Files.newInputStream(path).use { input ->
                val hash = MessageDigest.getInstance("SHA-256"); val bytes = ByteArray(65536)
                while (true) { val count = input.read(bytes); if (count < 0) break; hash.update(bytes, 0, count) }
                hash.digest().joinToString("") { "%02x".format(it) }
            }
            if (DesktopBuild.IS_LINUX) {
                val manifest = java.util.Properties().apply {
                    checkNotNull(SingBoxRuntime::class.java.getResourceAsStream("/noki/desktop/linux-core.properties")).use { load(it) }
                }
                check(manifest.getProperty("version") == BINARY_VERSION)
                check(digest(binary) == manifest.getProperty("sing-box"))
                check(digest(binary.resolveSibling("libcronet.so")) == manifest.getProperty("libcronet.so"))
                return
            }
            check(digest(binary) == BINARY_HASH) { "Контрольная сумма sing-box не совпадает" }
            val cronet = binary.resolveSibling("libcronet.dll")
            check(Files.isRegularFile(cronet) && digest(cronet) == CRONET_HASH) { "Контрольная сумма libcronet.dll не совпадает" }
        }
        fun freePort(): Int = ServerSocket(0, 0, java.net.InetAddress.getLoopbackAddress()).use { it.localPort }
        private fun findBinary(): Path {
            if (DesktopBuild.IS_LINUX) return Path.of("/usr/lib/noki/sing-box")
            val resources = System.getProperty("compose.application.resources.dir")
            val candidates = listOfNotNull(resources?.let { Path.of(it, "sing-box.exe") },
                resources?.let { Path.of(it, "windows-x64", "sing-box.exe") }, Path.of("build", "runtime", "windows-x64", "sing-box.exe"))
            return (candidates.firstOrNull { Files.isRegularFile(it) } ?: candidates.last()).toAbsolutePath().normalize()
        }
    }
}
