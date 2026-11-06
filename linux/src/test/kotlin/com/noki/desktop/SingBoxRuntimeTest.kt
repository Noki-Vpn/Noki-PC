package com.noki.desktop

import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.Shell32
import com.sun.jna.platform.win32.Shell32Util
import com.sun.jna.platform.win32.ShellAPI
import com.sun.jna.platform.win32.WinBase
import com.sun.jna.platform.win32.WinNT
import com.sun.jna.ptr.IntByReference
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.io.IOException

class SingBoxRuntimeTest {
    @Test fun `unreadable runtime record recovers only after confirmed core absence`() {
        assumeWindows()
        val sandbox = Files.createTempDirectory("noki-runtime-record").toAbsolutePath().normalize()
        try {
            val vault = WindowsVault(sandbox)
            vault.saveTokens(com.noki.vpn.data.BackendAuthTokens("fixture-access", "fixture-refresh"))
            vault.saveSettings(vault.loadSettings())
            vault.writeEncrypted("guard.dpapi", "enabled")
            vault.writeEncrypted("connection.dpapi", "{\"version\":3,\"user_id\":\"fixture\"}")
            val untouched = listOf("session.dpapi", "settings.dpapi", "guard.dpapi", "connection.dpapi")
                .associateWith { Files.readAllBytes(sandbox.resolve(it)) }
            for ((index, failure) in listOf("ciphertext", "json", "empty").withIndex()) {
                val path = sandbox.resolve("runtime.dpapi")
                when (failure) {
                    "ciphertext" -> Files.write(path, ByteArray(64) { 42 })
                    "empty" -> Files.write(path, byteArrayOf())
                    else -> vault.writeEncrypted("runtime.dpapi", "{")
                }
                val original = Files.readAllBytes(path)
                var checks = 0
                assertTrue(runCatching { SingBoxRuntime(vault, canRecoverRuntime = { checks++; false }) }.isFailure)
                assertEquals(1, checks)
                assertArrayEquals(original, Files.readAllBytes(path))
                val denied = com.sun.jna.platform.win32.Win32Exception(5)
                assertSame(denied, runCatching { SingBoxRuntime(vault, canRecoverRuntime = { throw denied }) }.exceptionOrNull())
                assertArrayEquals(original, Files.readAllBytes(path))
                assertTrue(vault.takeRecoveredFiles().isEmpty())
                val runtime = SingBoxRuntime(vault, canRecoverRuntime = { checks++; true })
                assertNull(runtime.restored())
                assertEquals(2, checks)
                assertFalse(Files.exists(path))
                val backups = Files.newDirectoryStream(sandbox, "runtime.dpapi.unreadable-*.bak").use { it.toList() }
                assertEquals(index + 1, backups.size)
                assertTrue(backups.any { Files.readAllBytes(it).contentEquals(original) })
                assertEquals(setOf("runtime.dpapi"), vault.takeRecoveredFiles().keys)
                val current = SingBoxRuntime.Running(12, 34, 12345, "fixture-secret", 12346, "fixture")
                SingBoxRuntime.persistIdentity(vault, current)
                SingBoxRuntime(vault, canRecoverRuntime = { error("Valid state must not trigger recovery") })
                assertEquals(12, JSONObject(vault.readEncrypted("runtime.dpapi")!!).getInt("pid"))
            }
            for ((name, bytes) in untouched) assertArrayEquals(name, bytes, Files.readAllBytes(sandbox.resolve(name)))
        } finally {
            Files.walk(sandbox).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach {
                check(it.toAbsolutePath().normalize().startsWith(sandbox)); Files.delete(it)
            } }
        }
    }

    @Test fun `runtime record access errors and failed preservation cannot clear identity`() {
        assumeWindows()
        val sandbox = Files.createTempDirectory("noki-runtime-io").toAbsolutePath().normalize()
        try {
            val vault = WindowsVault(sandbox)
            vault.writeEncrypted("runtime.dpapi", "{")
            val path = sandbox.resolve("runtime.dpapi")
            val original = Files.readAllBytes(path)
            for (share in listOf(0, WinNT.FILE_SHARE_READ or WinNT.FILE_SHARE_WRITE)) {
                val handle = Kernel32.INSTANCE.CreateFile(path.toString(), WinNT.GENERIC_READ, share, null, WinNT.OPEN_EXISTING, 0, null)
                assertNotEquals(WinBase.INVALID_HANDLE_VALUE, handle)
                var checks = 0
                try {
                    assertThrows(IOException::class.java) { SingBoxRuntime(vault, canRecoverRuntime = { checks++; true }) }
                    assertEquals(if (share == 0) 0 else 1, checks)
                } finally { Kernel32.INSTANCE.CloseHandle(handle) }
                assertArrayEquals(original, Files.readAllBytes(path))
                assertTrue(vault.takeRecoveredFiles().isEmpty())
            }
        } finally {
            Files.walk(sandbox).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach {
                check(it.toAbsolutePath().normalize().startsWith(sandbox)); Files.delete(it)
            } }
        }
    }

    @Test fun `Windows core snapshot never treats partial enumeration as core absence`() {
        assumeWindows()
        val handle = WinNT.HANDLE(com.sun.jna.Pointer(1234))
        for ((names, failure) in listOf(listOf("java.exe", "explorer.exe") to 18,
            listOf("java.exe", "SiNg-BoX.ExE") to 18, listOf("java.exe") to 5, emptyList<String>() to 5)) {
            var index = 0
            var closed = false
            val kernel = java.lang.reflect.Proxy.newProxyInstance(Kernel32::class.java.classLoader, arrayOf(Kernel32::class.java)) { _, method, args ->
                when (method.name) {
                    "CreateToolhelp32Snapshot" -> handle
                    "Process32First", "Process32Next" -> if (index < names.size) {
                        (args!![1] as com.sun.jna.platform.win32.Tlhelp32.PROCESSENTRY32).szExeFile.apply {
                            fill('\u0000'); names[index++].toCharArray().copyInto(this)
                        }; true
                    } else false
                    "GetLastError" -> failure
                    "CloseHandle" -> { assertEquals(handle, args!![0]); closed = true; true }
                    else -> error(method.name)
                }
            } as Kernel32
            if (failure == 5) assertThrows(com.sun.jna.platform.win32.Win32Exception::class.java) { SingBoxRuntime.windowsCoreProcessesStopped(kernel) }
            else assertEquals(names.none { it.equals("sing-box.exe", ignoreCase = true) }, SingBoxRuntime.windowsCoreProcessesStopped(kernel))
            assertTrue(closed)
        }
        // Exercise real structure marshalling without touching any process or network protection.
        SingBoxRuntime.windowsCoreProcessesStopped()
    }

    @Test fun `uninstall helper preserves its command through elevation and rejects mixed modes`() {
        assertTrue(WindowsShell.ensureAdministrator(arrayOf("--prepare-uninstall"), elevated = true) { error("Unexpected relaunch") })
        var forwarded = emptyList<String>()
        assertFalse(WindowsShell.ensureAdministrator(arrayOf("--prepare-uninstall"), elevated = false) { forwarded = it })
        assertEquals("--prepare-uninstall", forwarded.first())
        assertTrue(WindowsShell.ensureAdministrator(forwarded.toTypedArray(), elevated = true) { error("Unexpected relaunch") })
        assertTrue(runCatching { WindowsShell.ensureAdministrator(arrayOf("--tray", "--prepare-uninstall"), elevated = true) {} }.isFailure)
    }

    @Test fun `authenticated readiness accepts only the pinned core version`() = kotlinx.coroutines.runBlocking {
        val current = SingBoxRuntime.Running(1, 1, 12451, "readiness-secret", 0, "fixture")
        var status = 200
        var response = JSONObject().put("version", "sing-box ${SingBoxRuntime.BINARY_VERSION}").toString()
        val requests = java.util.concurrent.CopyOnWriteArrayList<Request>()
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            requests += chain.request()
            okhttp3.Response.Builder().request(chain.request()).protocol(okhttp3.Protocol.HTTP_1_1)
                .code(status).message("fixture").body(response.toResponseBody()).build()
        }.build()
        try {
            assertTrue(SingBoxRuntime.apiReady(http, current))
            assertEquals("http://127.0.0.1:12451/version", requests.single().url.toString())
            assertEquals("Bearer readiness-secret", requests.single().header("Authorization"))
            for (invalid in listOf("sing-box previous-version", SingBoxRuntime.BINARY_VERSION, "")) {
                response = JSONObject().put("version", invalid).toString()
                val failure = runCatching { SingBoxRuntime.apiReady(http, current) }.exceptionOrNull()
                assertTrue("A successful HTTP status must not accept version '$invalid'", failure is IllegalStateException)
                assertEquals("Версия sing-box не совпадает", failure?.message)
            }
            for (invalid in listOf("{}", "not JSON", "x".repeat(4097))) {
                response = invalid
                assertTrue("Readiness must reject an invalid version body", runCatching { SingBoxRuntime.apiReady(http, current) }.isFailure)
            }
            status = 401
            response = "unauthorized"
            assertFalse(SingBoxRuntime.apiReady(http, current))
        } finally {
            http.connectionPool.evictAll(); http.dispatcher.executorService.shutdownNow()
        }
    }

    @Test fun `guard helper cancellation and timeout reap its inherited core before returning`() = kotlinx.coroutines.runBlocking {
        assumeWindows()
        val kernel = Kernel32.INSTANCE
        for (cancel in listOf(true, false)) {
            val directory = Files.createTempDirectory("noki-guard-lifetime")
            val marker = directory.resolve("child.pid")
            var helper: WinNT.HANDLE? = null
            var child: WinNT.HANDLE? = null
            val failure = java.util.concurrent.atomic.AtomicReference<Throwable?>()
            val accepted = java.util.concurrent.atomic.AtomicBoolean()
            val pending = launch(kotlinx.coroutines.Dispatchers.IO) {
                try {
                    SingBoxRuntime.withGuardHelperProcess(timeoutMillis = 10_000, launch = { job ->
                        launchGuardFixture("wait", marker, job).also { handle ->
                            helper = kernel.OpenProcess(0x1000 or 0x100000 or 1, false, kernel.GetProcessId(handle))
                        }
                    }) { accepted.set(true) }
                } catch (error: Throwable) { failure.set(error) }
            }
            try {
                kotlinx.coroutines.withTimeout(8_000) { while (!Files.isRegularFile(marker)) kotlinx.coroutines.delay(10) }
                child = checkNotNull(kernel.OpenProcess(0x1000 or 0x100000 or 1, false, Files.readString(marker).trim().toInt()))
                assertEquals(258, kernel.WaitForSingleObject(child, 0))
                val stoppedAt = System.nanoTime()
                if (cancel) pending.cancel()
                kotlinx.coroutines.withTimeout(if (cancel) 3_000 else 12_000) { pending.join() }
                assertFalse(accepted.get())
                assertTrue(if (cancel) failure.get() is kotlinx.coroutines.CancellationException else failure.get() is IllegalStateException)
                assertEquals("The helper must be reaped when its wait ends", 0, kernel.WaitForSingleObject(checkNotNull(helper), 0))
                assertEquals("A core launched before identity handoff must not survive", 0, kernel.WaitForSingleObject(child, 0))
                if (cancel) assertTrue("Cancellation must not wait for the 20-second helper deadline",
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - stoppedAt) < 3_000)
            } finally {
                pending.cancel(); pending.join()
                for (process in listOfNotNull(helper, child)) {
                    if (kernel.WaitForSingleObject(process, 0) == 258) { kernel.TerminateProcess(process, 1); kernel.WaitForSingleObject(process, 2_000) }
                    kernel.CloseHandle(process)
                }
                directory.toFile().deleteRecursively()
            }
        }
    }

    @Test fun `guard job releases its core only after accepted ownership`() = kotlinx.coroutines.runBlocking {
        assumeWindows()
        val kernel = Kernel32.INSTANCE
        for (accept in listOf(false, true)) {
            val directory = Files.createTempDirectory("noki-guard-handoff")
            val marker = directory.resolve("child.pid")
            var child: WinNT.HANDLE? = null
            val rejected = IOException("identity was not persisted")
            try {
                val outcome = runCatching {
                    SingBoxRuntime.withGuardHelperProcess(launch = { job -> launchGuardFixture("exit", marker, job) }) {
                        child = checkNotNull(kernel.OpenProcess(0x1000 or 0x100000 or 1, false, Files.readString(marker).trim().toInt()))
                        assertEquals(258, kernel.WaitForSingleObject(child, 0))
                        if (!accept) throw rejected
                        "accepted"
                    }
                }
                if (accept) {
                    assertEquals("accepted", outcome.getOrThrow())
                    assertEquals("Closing the startup job after handoff must preserve the core", 258,
                        kernel.WaitForSingleObject(checkNotNull(child), 0))
                } else {
                    assertSame(rejected, outcome.exceptionOrNull())
                    assertEquals("A rejected handoff must reap the already-running core", 0,
                        kernel.WaitForSingleObject(checkNotNull(child), 0))
                }
            } finally {
                child?.let {
                    if (kernel.WaitForSingleObject(it, 0) == 258) { kernel.TerminateProcess(it, 1); kernel.WaitForSingleObject(it, 2_000) }
                    kernel.CloseHandle(it)
                }
                directory.toFile().deleteRecursively()
            }
        }
    }

    private fun launchGuardFixture(mode: String, marker: Path, job: WinNT.HANDLE): WinNT.HANDLE =
        WindowsShell.launchProcess(Path.of(System.getProperty("java.home"), "bin", "java.exe"),
            listOf("-cp", System.getProperty("noki.test.classpath", System.getProperty("java.class.path")),
                GuardProcess::class.java.name, mode, marker.toString()), job = job)

    object GuardProcess {
        @JvmStatic fun main(args: Array<String>) {
            if (args[0] == "child") {
                if (args.size > 2) while (!Files.exists(Path.of(args[2]))) Thread.sleep(10)
                else Thread.sleep(60_000)
                return
            }
            val child = WindowsShell.launchProcess(Path.of(System.getProperty("java.home"), "bin", "java.exe"),
                listOf("-cp", System.getProperty("java.class.path"), GuardProcess::class.java.name, "child", args[1]))
            try {
                val marker = Path.of(args[1])
                val temporary = marker.resolveSibling("child.tmp")
                Files.writeString(temporary, Kernel32.INSTANCE.GetProcessId(child).toString())
                Files.move(temporary, marker, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
            }
            finally { Kernel32.INSTANCE.CloseHandle(child) }
            if (args[0] == "wait") Thread.sleep(60_000)
        }
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun `adapter removal race reaps each failed launch before retrying the same session`() = kotlinx.coroutines.test.runTest {
        val race = SingBoxRuntime.StartupFailure("startup-failed: start service: start inbound/tun[tun]: configure tun interface: " +
            "(create adapter: Cannot create a file when that file already exists. | open existing adapter: Element not found.)", IOException())
        val events = mutableListOf<String>()
        val session = Any()
        var attempts = 0
        val result = SingBoxRuntime.retryTunAdapterStartup(cleanup = { events += "reaped" }) {
            events += "launch"
            if (++attempts < 3) throw race
            session
        }
        assertSame(session, result)
        assertEquals(listOf("launch", "reaped", "launch", "reaped", "launch"), events)
        assertEquals(1_500L, testScheduler.currentTime)
        attempts = 0
        val failure = runCatching {
            SingBoxRuntime.retryTunAdapterStartup(cleanup = { events += "reaped" }) { attempts++; throw race }
        }.exceptionOrNull()
        assertSame(race, failure)
        assertEquals(4, attempts)
        assertEquals(4_500L, testScheduler.currentTime)
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun `startup retry excludes other failures cleanup failures and cancellation`() = kotlinx.coroutines.test.runTest {
        for (reason in listOf("configure tun interface: Access is denied.",
            "configure tun interface: create adapter: Cannot create a file when that file already exists.",
            "open existing adapter: Element not found.")) {
            var attempts = 0
            var cleaned = 0
            val failure = SingBoxRuntime.StartupFailure(reason, IOException())
            assertSame(failure, runCatching {
                SingBoxRuntime.retryTunAdapterStartup(cleanup = { cleaned++ }) { attempts++; throw failure }
            }.exceptionOrNull())
            assertEquals(1, attempts)
            assertEquals(1, cleaned)
        }
        val race = SingBoxRuntime.StartupFailure("configure tun interface: create adapter: Cannot create a file when that file already exists. " +
            "open existing adapter: Element not found.", IOException())
        var attempts = 0
        val cleanupFailure = IOException("cannot reap child")
        assertSame(race, runCatching {
            SingBoxRuntime.retryTunAdapterStartup(cleanup = { throw cleanupFailure }) { attempts++; throw race }
        }.exceptionOrNull())
        assertEquals(1, attempts)
        assertSame(cleanupFailure, race.suppressed.single())
        var cleaned = false
        val pending = launch {
            SingBoxRuntime.retryTunAdapterStartup(cleanup = { cleaned = true }) {
                throw SingBoxRuntime.StartupFailure("configure tun interface: create adapter: Cannot create a file when that file already exists. " +
                    "open existing adapter: Element not found.", IOException())
            }
        }
        testScheduler.runCurrent()
        assertTrue(cleaned)
        pending.cancel()
        pending.join()
        assertEquals(0L, testScheduler.currentTime)
    }

    @Test fun `local core log configuration leaves caller policy untouched`() {
        val config = JSONObject().put("log", JSONObject().put("level", "warn"))
            .put("route", JSONObject().put("final", "proxy"))
        val before = config.toString()
        val path = Path.of("core.log").toAbsolutePath()
        val local = SingBoxRuntime.withLocalCoreLog(config, path)
        assertEquals(before, config.toString())
        assertEquals(path.toString(), local.getJSONObject("log").getString("output"))
        assertEquals("debug", local.getJSONObject("log").getString("level"))
        assertEquals("proxy", local.getJSONObject("route").getString("final"))
    }

    @Test fun `core log flood is bounded and delivered in batches with errors retained`() {
        assumeWindows()
        val directory = Files.createTempDirectory("noki-core-log-stream")
        val server = java.net.ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())
        val finish = java.util.concurrent.CountDownLatch(1)
        val failure = java.util.concurrent.atomic.AtomicReference<Throwable>()
        val worker = Thread {
            try { server.accept().use { socket ->
                val reader = socket.getInputStream().bufferedReader()
                assertTrue(reader.readLine().startsWith("GET /logs?level=info "))
                var key = ""
                while (true) {
                    val line = reader.readLine() ?: error("missing WebSocket headers")
                    if (line.isEmpty()) break
                    if (line.startsWith("Sec-WebSocket-Key:", true)) key = line.substringAfter(':').trim()
                }
                val accept = java.util.Base64.getEncoder().encodeToString(java.security.MessageDigest.getInstance("SHA-1")
                    .digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray(Charsets.US_ASCII)))
                val output = socket.getOutputStream().buffered()
                output.write("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: $accept\r\n\r\n".toByteArray(Charsets.US_ASCII))
                for (index in 0 until 1000) {
                    val level = if (index == 999) "error" else "info"
                    val body = "{\"type\":\"$level\",\"payload\":\"line-$index\"}".toByteArray(Charsets.UTF_8)
                    output.write(0x81); output.write(body.size); output.write(body)
                }
                output.flush()
                finish.await(5, TimeUnit.SECONDS)
            } } catch (error: Throwable) { failure.set(error) }
        }.apply { isDaemon = true; start() }
        try {
            val runtime = SingBoxRuntime(WindowsVault(directory))
            val current = SingBoxRuntime.Running(1, 1, server.localPort, "fixture", 0, "fixture")
            val batch = kotlinx.coroutines.runBlocking {
                kotlinx.coroutines.withTimeout(5000) { runtime.coreLogs(current).first { rows -> rows.any { it.message == "line-999" } } }
            }
            assertTrue(batch.size in 1..500)
            assertTrue(batch.last().isError)
            assertEquals("line-999", batch.last().message)
        } finally {
            finish.countDown(); server.close(); worker.join(1000)
            directory.toFile().deleteRecursively()
        }
        failure.get()?.let { throw AssertionError("WebSocket fixture failed", it) }
    }

    @Test fun `startup diagnostics preserve reason but remove configuration secrets`() {
        val file = Files.createTempFile("noki-startup-detail", ".log")
        try {
            val config = JSONObject().put("outbounds", JSONArray().put(JSONObject().put("password", "raw-private-value")))
                .put("experimental", JSONObject().put("secret", "control-key"))
            Files.writeString(file, "start-service: invalid password raw-private-value; secret control-key; user@example.invalid")
            val detail = SingBoxRuntime.startupDiagnostic(file, config)
            assertTrue(detail.contains("start-service"))
            for (secret in listOf("raw-private-value", "control-key", "user@example.invalid")) assertFalse(detail.contains(secret))
            val failure = SingBoxRuntime.StartupFailure(detail, java.io.IOException("connection timed out"))
            val details = DesktopDiagnostics.errorDetails(failure)
            assertTrue(details.contains("start-service"))
            assertTrue(details.contains("java.io.IOException"))
            assertFalse(details.contains("connection timed out"))
            Files.writeString(file, "x".repeat(20_000))
            assertTrue(SingBoxRuntime.startupDiagnostic(file, config).length <= 8192)
        } finally { Files.deleteIfExists(file) }
    }

    @Test fun `native startup failure writes bounded reason before control API exists`() {
        assumeWindows(); assumeRuntimeBuilt()
        val directory = Files.createTempDirectory("noki-startup-failure")
        try {
            val config = directory.resolve("invalid.json")
            val report = directory.resolve("startup.log")
            val binary = Path.of("build/runtime/windows-x64/sing-box.exe").toAbsolutePath()
            for ((invalid, reason) in listOf(
                "{\"unknown_config_field\":true}" to "unknown_config_field",
                "{\"route\":{\"final\":\"missing-outbound\"}}" to "missing-outbound",
            )) {
                Files.writeString(config, invalid)
                val process = ProcessBuilder(binary.toString(), "run", "-c", config.toString(), "--startup-report", report.toString())
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start()
                try {
                    assertTrue(process.waitFor(10, TimeUnit.SECONDS))
                    assertNotEquals(0, process.exitValue())
                    val message = Files.readString(report)
                    assertTrue(message.startsWith("startup-failed:"))
                    assertTrue(message.contains(reason))
                    assertTrue(Files.size(report) <= 8192)
                } finally { if (process.isAlive) process.destroyForcibly().waitFor() }
            }
        } finally { Files.walk(directory).use { it.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) } }
    }

    @Test fun `rebind uses authenticated empty POST and accepts only a completed reset for the current core`() = kotlinx.coroutines.runBlocking {
        assumeWindows()
        val requests = java.util.concurrent.CopyOnWriteArrayList<Request>()
        var status = 204
        var afterRequest: () -> Unit = {}
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            requests += chain.request()
            afterRequest()
            okhttp3.Response.Builder().request(chain.request()).protocol(okhttp3.Protocol.HTTP_1_1)
                .code(status).message("fixture").body("".toResponseBody()).build()
        }.build()
        val directory = Files.createTempDirectory("noki-network-rebind")
        try {
            val current = SingBoxRuntime.Running(100, 1_000, 12345, "test-secret", 23456, "fixture")
            var active: SingBoxRuntime.Running? = current
            val runtime = object : SingBoxRuntime(WindowsVault(directory), http) {
                override fun restored() = active
                override suspend fun stop(restoreInternet: Boolean) { error("Rebind must preserve the core") }
                override suspend fun probe(current: Running): Long = error("Readiness has a separate owner")
                override suspend fun releaseGuard(allowConnected: Boolean) { error("Rebind must preserve the guard") }
            }
            runtime.rebindNetwork(current)
            assertEquals(current, runtime.restored())
            val request = requests.single()
            assertEquals("POST", request.method)
            assertEquals("http://127.0.0.1:12345/noki/network/rebind", request.url.toString())
            assertEquals("Bearer test-secret", request.header("Authorization"))
            assertEquals(0L, request.body!!.contentLength())
            for (response in listOf(200, 302, 401, 503)) {
                status = response
                assertTrue("HTTP $response must not prove a completed reset", runCatching { runtime.rebindNetwork(current) }.isFailure)
            }
            val beforeStaleRequest = requests.size
            active = current.copy(startedAt = 2_000)
            assertTrue(runCatching { runtime.rebindNetwork(current) }.isFailure)
            assertEquals("A stale owner must not reset another core", beforeStaleRequest, requests.size)
            active = current
            status = 204
            afterRequest = { active = current.copy(startedAt = 3_000) }
            assertTrue("A replaced core must not accept the old reply", runCatching { runtime.rebindNetwork(current) }.isFailure)
        } finally {
            http.connectionPool.evictAll(); http.dispatcher.executorService.shutdownNow()
            Files.walk(directory).use { it.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        }
    }

    @Test fun `cancelled rebind closes its pending loopback control request`() = kotlinx.coroutines.runBlocking {
        assumeWindows()
        val accepted = java.util.concurrent.CountDownLatch(1)
        val closed = java.util.concurrent.CountDownLatch(1)
        val listener = java.net.ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))
        val worker = Thread {
            try {
                listener.accept().use { socket ->
                    socket.soTimeout = 5_000
                    val input = socket.getInputStream().bufferedReader()
                    while (!input.readLine().isNullOrEmpty()) { }
                    accepted.countDown()
                    if (input.read() == -1) closed.countDown()
                }
            } catch (_: IOException) { }
        }.apply { isDaemon = true; start() }
        val http = OkHttpClient()
        val directory = Files.createTempDirectory("noki-network-rebind-cancel")
        try {
            val current = SingBoxRuntime.Running(100, 1_000, listener.localPort, "test-secret", 23456, "fixture")
            val runtime = object : SingBoxRuntime(WindowsVault(directory), http) { override fun restored() = current }
            val rebind = launch(kotlinx.coroutines.Dispatchers.IO) { runtime.rebindNetwork(current) }
            assertTrue(accepted.await(3, TimeUnit.SECONDS))
            rebind.cancel()
            kotlinx.coroutines.withTimeout(1_000) { rebind.join() }
            assertTrue("Cancelling rebind must close the live control call", closed.await(1, TimeUnit.SECONDS))
            assertEquals(current, runtime.restored())
        } finally {
            listener.close(); worker.join(6_000)
            http.connectionPool.evictAll(); http.dispatcher.executorService.shutdownNow()
            Files.walk(directory).use { it.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        }
    }

    @Test fun `core log error highlighting respects levels and embedded error markers`() {
        for (level in listOf("error", "ERROR", "fatal", "panic"))
            assertTrue(SingBoxRuntime.CoreLogLine(level, "transport closed").isError)
        for (message in listOf("[ERROR] dial failed", "transport: error reading response", "failed to establish connection"))
            assertTrue(SingBoxRuntime.CoreLogLine("info", message).isError)
        assertFalse(SingBoxRuntime.CoreLogLine("info", "outbound connection to error.example:443").isError)
        assertFalse(SingBoxRuntime.CoreLogLine("warn", "connection is slow").isError)
    }
    @Test fun `local core logs read the authenticated native stream without application diagnostics`() = kotlinx.coroutines.runBlocking {
        assumeWindows()
        assumeRuntimeBuilt()
        val codec = com.noki.vpn.data.StoredSettingsCodec { DesktopState().settings }
        assertFalse(codec.decode(null).advancedSettings.showLocalCoreLogs)
        val saved = DesktopState().settings.let { it.copy(advancedSettings = it.advancedSettings.copy(showLocalCoreLogs = true)) }
        assertTrue(codec.decode(codec.encode(saved)).advancedSettings.showLocalCoreLogs)
        assertEquals(4096, SingBoxRuntime.CoreLogLine.decode(JSONObject().put("type", "error").put("payload", "x".repeat(8192)).toString()).message.length)
        val binary = Path.of("build/runtime/windows-x64/sing-box.exe").toAbsolutePath()
        val directory = Files.createTempDirectory("noki-core-stream-")
        val http = OkHttpClient()
        var core: WinNT.HANDLE? = null
        try {
            val apiPort = SingBoxRuntime.freePort()
            var mixedPort = SingBoxRuntime.freePort()
            while (mixedPort == apiPort) mixedPort = SingBoxRuntime.freePort()
            val secret = UUID.randomUUID().toString()
            val config = JSONObject().put("log", JSONObject().put("level", "error"))
                .put("inbounds", JSONArray().put(JSONObject().put("type", "mixed").put("tag", "core-log-fixture")
                    .put("listen", "127.0.0.1").put("listen_port", mixedPort)))
                .put("outbounds", JSONArray().put(JSONObject().put("type", "direct").put("tag", "direct")))
                .put("route", JSONObject().put("final", "direct"))
                .put("experimental", JSONObject().put("clash_api", JSONObject()
                    .put("external_controller", "127.0.0.1:$apiPort").put("secret", secret)))
            val file = directory.resolve("core.json")
            val logFile = directory.resolve("core.log")
            Files.writeString(file, SingBoxRuntime.withLocalCoreLog(config, logFile).toString())
            core = launchHidden(binary, listOf("run", "-c", file.toString()))
            awaitApi(core, apiPort, secret, logFile)
            val runtime = SingBoxRuntime(WindowsVault(directory.resolve("vault")), http)
            kotlinx.coroutines.withTimeout(5000) {
                val received = async { runtime.coreLogs(SingBoxRuntime.Running(0, 0, apiPort, secret, mixedPort, "fixture"))
                    .first { lines -> lines.any { it.level == "info" && it.message.contains("core-log-fixture") } } }
                while (!received.isCompleted) {
                    java.net.Socket("127.0.0.1", mixedPort).use { socket ->
                        socket.getOutputStream().write("GET http://127.0.0.1:9/ HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n".toByteArray())
                    }
                    kotlinx.coroutines.delay(100)
                }
                assertTrue(received.await().any { it.text.contains("[INFO]") })
            }
            assertFalse(Files.exists(directory.resolve("vault/logs.dpapi")))
        } finally {
            core?.let { Kernel32.INSTANCE.TerminateProcess(it, 0); Kernel32.INSTANCE.WaitForSingleObject(it, 5000); Kernel32.INSTANCE.CloseHandle(it) }
            http.connectionPool.evictAll(); http.dispatcher.executorService.shutdownNow()
            Files.walk(directory).use { it.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        }
    }
    @Test fun `watchdog accepts HTTP errors through SOCKS without waiting for a stalled body`() = kotlinx.coroutines.runBlocking {
        val closed = java.util.concurrent.CountDownLatch(1)
        val server = ProbeServer { _, socket ->
            socket.getOutputStream().apply {
                write("HTTP/1.1 503 Service Unavailable\r\nContent-Length: 1000000\r\n\r\n".toByteArray()); flush()
            }
            if (socket.getInputStream().read() == -1) closed.countDown()
        }
        val http = OkHttpClient()
        try {
            val runtime = SingBoxRuntime(WindowsVault(Files.createTempDirectory("noki-watchdog-status")), http)
            val targets = listOf(com.noki.vpn.vpn.VpnProbePlanPolicy.ProbeTarget("fixture", "http://example.invalid/health"))
            val started = System.nanoTime()
            assertTrue(runtime.probeWatchdogTargets(server.port, targets) > 0)
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 1_000)
            assertEquals(1, server.requests.get())
            assertTrue("A liveness response must close its pending body", closed.await(1, TimeUnit.SECONDS))
        } finally {
            http.connectionPool.evictAll(); http.dispatcher.executorService.shutdownNow(); server.close()
        }
    }

    @Test fun `watchdog independent targets do not wait for a blocked first target`() = kotlinx.coroutines.runBlocking {
        val blockedStarted = java.util.concurrent.CountDownLatch(1)
        val goodStarted = java.util.concurrent.CountDownLatch(1)
        val requested = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val host = chain.request().url.host
            requested += host
            if (host == "blocked.invalid") {
                blockedStarted.countDown()
                goodStarted.await(1_500, TimeUnit.MILLISECONDS)
                throw IOException("Blocked target")
            }
            check(blockedStarted.await(1, TimeUnit.SECONDS)) { "The blocked target must start independently" }
            goodStarted.countDown()
            okhttp3.Response.Builder().request(chain.request()).protocol(okhttp3.Protocol.HTTP_1_1)
                .code(403).message("fixture").body("".toResponseBody()).build()
        }.build()
        try {
            val runtime = SingBoxRuntime(WindowsVault(Files.createTempDirectory("noki-watchdog-parallel")), http)
            val targets = listOf("blocked.invalid", "good.invalid").map {
                com.noki.vpn.vpn.VpnProbePlanPolicy.ProbeTarget(it, "https://$it/health")
            }
            val started = System.nanoTime()
            assertTrue(runtime.probeWatchdogTargets(9, targets) > 0)
            assertTrue("A blocked target must not delay another target", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 1_000)
            assertEquals(setOf("blocked.invalid", "good.invalid"), requested)
        } finally {
            http.connectionPool.evictAll(); http.dispatcher.executorService.shutdownNow()
        }
    }

    @Test fun `watchdog rejects a cycle when all independent targets fail and preserves causes`() = kotlinx.coroutines.runBlocking {
        val requested = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            requested += chain.request().url.host
            throw java.net.SocketTimeoutException("Simulated transport failure")
        }.build()
        try {
            val runtime = SingBoxRuntime(WindowsVault(Files.createTempDirectory("noki-watchdog-failure")), http)
            val hosts = setOf("first.invalid", "second.invalid", "third.invalid")
            val targets = hosts.map { com.noki.vpn.vpn.VpnProbePlanPolicy.ProbeTarget(it, "https://$it/health") }
            val failure = runCatching { runtime.probeWatchdogTargets(9, targets) }.exceptionOrNull()
            assertTrue(failure is IOException)
            assertEquals(hosts, requested)
            val detail = failure!!.stackTraceToString()
            hosts.forEach { assertTrue("Diagnostic must retain the failure for $it", detail.contains(it)) }
            assertTrue("Redacted diagnostic must retain the transport error type",
                DesktopDiagnostics.errorDetails(failure).contains("java.net.SocketTimeoutException"))
        } finally {
            http.connectionPool.evictAll(); http.dispatcher.executorService.shutdownNow()
        }
    }

    @Test fun `cancelling watchdog cancels all pending SOCKS handshakes`() = kotlinx.coroutines.runBlocking {
        val server = ProbeServer(handshakeDelayMillis = 5_000) { _, _ -> }
        val http = OkHttpClient()
        try {
            val runtime = SingBoxRuntime(WindowsVault(Files.createTempDirectory("noki-watchdog-cancel")), http)
            val targets = List(3) { com.noki.vpn.vpn.VpnProbePlanPolicy.ProbeTarget("fixture$it", "http://example$it.invalid/health") }
            val pending = launch { runtime.probeWatchdogTargets(server.port, targets) }
            kotlinx.coroutines.withTimeout(2_000) { while (server.accepted.get() < 3) kotlinx.coroutines.delay(10) }
            pending.cancel()
            kotlinx.coroutines.withTimeout(1_000) { pending.join() }
            assertEquals(0, server.requests.get())
        } finally {
            http.connectionPool.evictAll(); http.dispatcher.executorService.shutdownNow(); server.close()
        }
    }

    @Test fun `connected latency takes the faster of two requests on one fresh tunnel connection`() = kotlinx.coroutines.runBlocking {
        val server = ProbeServer(handshakeDelayMillis = 250) { request, socket ->
            Thread.sleep(if (request == 1) 750 else 25)
            socket.getOutputStream().apply { write("HTTP/1.1 204 No Content\r\nContent-Length: 0\r\n\r\n".toByteArray()); flush() }
        }
        val http = OkHttpClient()
        try {
            val runtime = SingBoxRuntime(WindowsVault(Files.createTempDirectory("noki-fresh-latency")), http)
            val targets = listOf(com.noki.vpn.vpn.VpnProbePlanPolicy.ProbeTarget("fixture", "http://example.invalid/generate_204"))
            val samples = List(2) { runtime.probeTargets(server.port, targets) }
            println("Loopback latency: $samples ms; SOCKS connections: ${server.accepted.get()}; HTTP requests: ${server.requests.get()}")
            assertEquals("Each measurement needs two HTTP samples; observed latency: $samples", 4, server.requests.get())
            assertEquals("A new measurement must not retain the previous measurement's connection", 2, server.accepted.get())
            assertTrue("Android returns the repeat request RTT instead of SOCKS setup plus the first request: $samples", samples.all { it in 1..750 })
        } finally {
            http.connectionPool.evictAll(); http.dispatcher.executorService.shutdownNow()
            server.close()
        }
    }

    @Test fun `connected latency waits for the complete response body`() = kotlinx.coroutines.runBlocking {
        val server = ProbeServer { _, socket ->
            socket.getOutputStream().apply {
                write("HTTP/1.1 200 OK\r\nContent-Length: 1\r\n\r\n".toByteArray()); flush()
                Thread.sleep(250)
                write('x'.code); flush()
            }
        }
        val http = OkHttpClient()
        try {
            val runtime = SingBoxRuntime(WindowsVault(Files.createTempDirectory("noki-body-latency")), http)
            val targets = listOf(com.noki.vpn.vpn.VpnProbePlanPolicy.ProbeTarget("fixture", "http://example.invalid/health"))
            runtime.probeTargets(server.port, targets)
            val latency = runtime.probeTargets(server.port, targets)
            assertTrue("Latency must include the delayed response body: $latency", latency >= 180)
        } finally {
            http.connectionPool.evictAll(); http.dispatcher.executorService.shutdownNow(); server.close()
        }
    }

    @Test fun `second sample deadline keeps the first successful response without marking a healthy tunnel dead`() = kotlinx.coroutines.runBlocking {
        val server = ProbeServer { _, socket ->
            Thread.sleep(2_500)
            socket.getOutputStream().apply { write("HTTP/1.1 204 No Content\r\nContent-Length: 0\r\n\r\n".toByteArray()); flush() }
        }
        val http = OkHttpClient()
        try {
            val runtime = SingBoxRuntime(WindowsVault(Files.createTempDirectory("noki-latency-deadline")), http)
            val targets = listOf(com.noki.vpn.vpn.VpnProbePlanPolicy.ProbeTarget("fixture", "http://example.invalid/health"))
            val started = System.nanoTime()
            val result = runCatching { runtime.probeTargets(server.port, targets) }
            val elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
            assertTrue("A completed response proves readiness even when the repeat sample times out", result.isSuccess)
            assertTrue(result.getOrThrow() in 2_000..3_500)
            assertEquals(2, server.requests.get())
            assertTrue("The target deadline was not enforced: $elapsed ms", elapsed < 6_000)
        } finally {
            http.connectionPool.evictAll(); http.dispatcher.executorService.shutdownNow(); server.close()
        }
    }

    @Test fun `fast startup uses one real SOCKS request and leaves connected measurements unchanged`() = kotlinx.coroutines.runBlocking {
        val server = ProbeServer { _, socket ->
            socket.getOutputStream().apply { write("HTTP/1.1 204 No Content\r\nContent-Length: 0\r\n\r\n".toByteArray()); flush() }
        }
        val http = OkHttpClient()
        try {
            val runtime = SingBoxRuntime(WindowsVault(Files.createTempDirectory("noki-startup-probe")), http)
            val targets = listOf(com.noki.vpn.vpn.VpnProbePlanPolicy.ProbeTarget("fixture", "http://example.invalid/v1/vpn/connectivity"))
            assertTrue(runtime.probeTargets(server.port, targets, fastStartup = true) > 0)
            assertEquals(1, server.requests.get())
            assertTrue(runtime.probeTargets(server.port, targets) > 0)
            assertEquals(3, server.requests.get())
        } finally {
            http.connectionPool.evictAll(); http.dispatcher.executorService.shutdownNow(); server.close()
        }
    }

    @Test fun `startup failure retains each target transport error in the diagnostic`() = kotlinx.coroutines.runBlocking {
        val http = OkHttpClient.Builder().addInterceptor {
            throw java.net.SocketTimeoutException("fixture transport timeout")
        }.build()
        try {
            val runtime = SingBoxRuntime(WindowsVault(Files.createTempDirectory("noki-startup-cause")), http)
            val targets = listOf("api", "cloudflare", "gstatic").map {
                com.noki.vpn.vpn.VpnProbePlanPolicy.ProbeTarget(it, "https://$it.invalid/health")
            }
            val failure = runCatching { runtime.probeTargets(9, targets, fastStartup = true) }.exceptionOrNull()
            assertTrue(failure is IOException)
            assertTrue(failure!!.cause!!.cause is java.net.SocketTimeoutException)
            val detail = DesktopDiagnostics.errorDetails(failure)
            targets.forEach { assertTrue(detail.contains(it.key)) }
            assertTrue(detail.contains("SocketTimeoutException"))
            assertFalse("Transport exception messages stay private", detail.contains("fixture transport timeout"))
        } finally { http.connectionPool.evictAll(); http.dispatcher.executorService.shutdownNow() }
    }

    @Test fun `fast startup bounds all targets including a blocked SOCKS handshake`() = kotlinx.coroutines.runBlocking {
        val server = ProbeServer(handshakeDelayMillis = 5_000) { _, _ -> }
        val http = OkHttpClient()
        try {
            val runtime = SingBoxRuntime(WindowsVault(Files.createTempDirectory("noki-startup-deadline")), http)
            val targets = List(3) { com.noki.vpn.vpn.VpnProbePlanPolicy.ProbeTarget("fixture$it", "http://example.invalid/v1/vpn/connectivity") }
            val started = System.nanoTime()
            assertTrue(runCatching { runtime.probeTargets(server.port, targets, fastStartup = true) }.isFailure)
            val elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
            assertTrue("All targets must share the 3000 ms startup budget: $elapsed ms", elapsed in 2_000..4_500)
            assertEquals(0, server.requests.get())
        } finally {
            http.connectionPool.evictAll(); http.dispatcher.executorService.shutdownNow(); server.close()
        }
    }

    @Test fun `cancelling a latency measurement closes a pending response body`() = kotlinx.coroutines.runBlocking {
        val headers = java.util.concurrent.CountDownLatch(1)
        val closed = java.util.concurrent.CountDownLatch(1)
        val server = ProbeServer { _, socket ->
            socket.getOutputStream().apply { write("HTTP/1.1 200 OK\r\nContent-Length: 1\r\n\r\n".toByteArray()); flush() }
            headers.countDown()
            if (socket.getInputStream().read() == -1) closed.countDown()
        }
        val http = OkHttpClient()
        try {
            val runtime = SingBoxRuntime(WindowsVault(Files.createTempDirectory("noki-latency-cancel")), http)
            val targets = listOf(com.noki.vpn.vpn.VpnProbePlanPolicy.ProbeTarget("fixture", "http://example.invalid/health"))
            val probe = launch(kotlinx.coroutines.Dispatchers.IO) { runtime.probeTargets(server.port, targets) }
            assertTrue(headers.await(5, TimeUnit.SECONDS))
            assertNull("Headers alone must not complete the probe", kotlinx.coroutines.withTimeoutOrNull(200) { probe.join(); true })
            probe.cancel()
            kotlinx.coroutines.withTimeout(2_000) { probe.join() }
            assertTrue("Cancellation must close the live call while its body is pending", closed.await(1, TimeUnit.SECONDS))
        } finally {
            http.connectionPool.evictAll(); http.dispatcher.executorService.shutdownNow(); server.close()
        }
    }

    @org.junit.Test fun `metrics parser ignores nested connection counters without building their objects`() {
        val raw = """{"connections":[{"uploadTotal":999999,"metadata":{"escaped":"a\\\"b,}[]"}}],"uploadTotal":123,"downloadTotal":456,"memory":10}"""
        org.junit.Assert.assertEquals(123L to 456L, SingBoxRuntime.readTotals(raw.reader()))
        org.junit.Assert.assertTrue(runCatching { SingBoxRuntime.readTotals("{\"uploadTotal\":-1,\"downloadTotal\":0}".reader()) }.isFailure)
        org.junit.Assert.assertTrue(runCatching { SingBoxRuntime.readTotals("{\"connections\":[{".reader()) }.isFailure)
    }

    @org.junit.Test fun `probe uses the next independent target when the first fails`() = kotlinx.coroutines.runBlocking {
        val urls = java.util.concurrent.CopyOnWriteArrayList<String>()
        val http = okhttp3.OkHttpClient.Builder().addInterceptor { chain ->
            urls += chain.request().url.host
            okhttp3.Response.Builder().request(chain.request()).protocol(okhttp3.Protocol.HTTP_1_1)
                .code(if (chain.request().url.host == "first.invalid") 503 else 204).message("fixture")
                .body("".toResponseBody()).build()
        }.build()
        val runtime = SingBoxRuntime(WindowsVault(java.nio.file.Files.createTempDirectory("noki-probe-targets")), http)
        val targets = listOf("first.invalid", "second.invalid").map { com.noki.vpn.vpn.VpnProbePlanPolicy.ProbeTarget(it, "https://$it/generate_204") }
        org.junit.Assert.assertTrue(runtime.probeTargets(9, targets) > 0)
        org.junit.Assert.assertEquals(listOf("first.invalid", "first.invalid", "second.invalid", "second.invalid"), urls)
        http.connectionPool.evictAll(); http.dispatcher.executorService.shutdownNow()
        Unit
    }

    @Test fun `probe rejects unexpected status and shares the response budget across samples`() = kotlinx.coroutines.runBlocking {
        val urls = java.util.concurrent.CopyOnWriteArrayList<String>()
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val host = chain.request().url.host.also { urls += it }
            okhttp3.Response.Builder().request(chain.request()).protocol(okhttp3.Protocol.HTTP_1_1)
                .code(if (host == "partial.invalid") 206 else if (host == "budget.invalid") 200 else 204).message("fixture")
                .body((if (host == "budget.invalid") "x".repeat(160 * 1024) else "").toResponseBody()).build()
        }.build()
        try {
            val runtime = SingBoxRuntime(WindowsVault(Files.createTempDirectory("noki-probe-response")), http)
            val targets = listOf("partial.invalid", "budget.invalid", "good.invalid").map { com.noki.vpn.vpn.VpnProbePlanPolicy.ProbeTarget(it, "https://$it/health") }
            assertTrue(runtime.probeTargets(9, targets) > 0)
            assertEquals(listOf("partial.invalid", "partial.invalid", "budget.invalid", "budget.invalid", "good.invalid", "good.invalid"), urls)
        } finally {
            http.connectionPool.evictAll(); http.dispatcher.executorService.shutdownNow()
        }
    }
    @Test
    fun refusesUnpinnedRuntimeBeforeExecution() {
        val binary = Files.createTempFile("noki-untrusted-core", ".exe")
        try {
            Files.write(binary, byteArrayOf(1, 2, 3))
            val failure = assertThrows(IllegalStateException::class.java) { SingBoxRuntime.verifyRuntimeBinary(binary) }
            assertEquals(if (SingBoxRuntime.BINARY_HASH == "PENDING_WINDOWS_BUILD")
                "Windows VPN-ядро ${SingBoxRuntime.BINARY_VERSION} ещё не собрано" else "Контрольная сумма sing-box не совпадает", failure.message)
        } finally { Files.deleteIfExists(binary) }
    }

    @Test fun `pinned runtime rejects missing and corrupt Cronet before execution`() {
        val source = Path.of("build/runtime/windows-x64/sing-box.exe").toAbsolutePath()
        SingBoxRuntime.verifyRuntimeBinary(source)
        val directory = Files.createTempDirectory("noki-cronet-integrity")
        val binary = directory.resolve("sing-box.exe")
        val cronet = directory.resolve("libcronet.dll")
        try {
            Files.copy(source, binary)
            for (corrupt in listOf(false, true)) {
                if (corrupt) Files.write(cronet, byteArrayOf(1, 2, 3))
                val failure = assertThrows(IllegalStateException::class.java) { SingBoxRuntime.verifyRuntimeBinary(binary) }
                assertEquals("Контрольная сумма libcronet.dll не совпадает", failure.message)
            }
        } finally {
            Files.deleteIfExists(cronet)
            Files.deleteIfExists(binary)
            Files.deleteIfExists(directory)
        }
    }

    @Test fun `native source delivery notices and hash agree with the single build recipe`() {
        val manifest = JSONObject(Files.readString(Path.of("SOURCE-ORIGINS.json"))).getJSONObject("native_core")
        val archive = Path.of(manifest.getString("archive"))
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(archive))
            .joinToString("") { "%02x".format(it) }
        assertEquals(manifest.getString("sha256"), digest)
        val recipe = Files.readString(Path.of("scripts/prepare-runtime-linux.py"))
        assertTrue(recipe.contains(digest))
        assertTrue(recipe.contains(SingBoxRuntime.BINARY_VERSION))
        assertEquals(SingBoxRuntime.BINARY_VERSION, manifest.getString("version"))
        java.util.zip.ZipFile(archive.toFile()).use { zip ->
            val tree = java.security.MessageDigest.getInstance("SHA-256")
            zip.entries().asSequence().sortedBy { it.name }.forEach { entry ->
                tree.update(entry.name.toByteArray(Charsets.UTF_8)); tree.update(0.toByte())
                tree.update(java.security.MessageDigest.getInstance("SHA-256").digest(zip.getInputStream(entry).use { it.readAllBytes() }))
            }
            assertEquals(manifest.getString("frozen_source_sha256"), tree.digest().joinToString("") { "%02x".format(it) })
            for (notice in listOf("LICENSE", "COPYING", "NOTICE")) {
                assertArrayEquals(Files.readAllBytes(Path.of("vendor/sing-box/$notice")), zip.getInputStream(zip.getEntry(notice)).use { it.readAllBytes() })
            }
            assertNotNull(zip.getEntry("third_party/utls/LICENSE"))
            assertNotNull(zip.getEntry("third_party/quic-go/LICENSE"))
            assertNull(zip.getEntry("src/protocol/nokinv/carrier_tls.go"))
            assertNull(zip.getEntry("src/protocol/nokinv/auto.go"))
            val control = zip.getInputStream(zip.getEntry("src/experimental/clashapi/server.go")).bufferedReader().use { it.readText() }
            assertTrue(control.contains("/noki/nokinv/carrier"))
            assertTrue(control.contains("telemetry.GetNokiNVCarrier()"))
            assertTrue(control.contains("/noki/network/rebind"))
            val windowsTun = zip.getInputStream(zip.getEntry("src/pc-patches/tun_windows.go.patch")).bufferedReader().use { it.readText() }
            assertTrue(windowsTun.contains("NewWithSystemDNS"))
            assertEquals(2, windowsTun.lineSequence().count { it.contains("DNSModeDisabled && !t.preserveSystemDNS") })
            assertTrue(windowsTun.contains("FWPM_CONDITION_IP_REMOTE_PORT, winsys.FWP_UINT16, 53"))
            val supervisor = zip.getInputStream(zip.getEntry("src/protocol/tun/dns_policy_windows.go")).bufferedReader().use { it.readText() }
            assertFalse(supervisor.contains("registry.CreateKey"))
            assertFalse(supervisor.contains("RegCreateKey"))
            assertFalse(supervisor.contains("Get-DnsClientNrptPolicy"))
        }
    }

    @Test
    fun nativeDnsPolicyRoutesEachQueryOnSharedUdpAndTcpConnections() {
        assumeWindows()
        assumeRuntimeBuilt()
        val binary = Path.of("build/runtime/windows-x64/sing-box.exe").toAbsolutePath().normalize()
        SingBoxRuntime.verifyRuntimeBinary(binary)
        val loopback = java.net.InetAddress.getByName("127.0.0.1")
        val adapter = java.net.NetworkInterface.getByInetAddress(loopback)
        val interfaceName = com.sun.jna.Memory(8).use { luid ->
            assertEquals(0, com.sun.jna.Function.getFunction("iphlpapi", "ConvertInterfaceIndexToLuid", com.sun.jna.Function.ALT_CONVENTION)
                .invokeInt(arrayOf(adapter.index, luid)))
            com.sun.jna.Memory(514).use { alias ->
                assertEquals(0, com.sun.jna.Function.getFunction("iphlpapi", "ConvertInterfaceLuidToAlias", com.sun.jna.Function.ALT_CONVENTION)
                    .invokeInt(arrayOf(luid, alias, 257L)))
                alias.getWideString(0)
            }
        }
        val directNames = java.util.concurrent.CopyOnWriteArrayList<String>()
        val rejectedNames = java.util.concurrent.CopyOnWriteArrayList<String>()
        val protectedNames = java.util.concurrent.CopyOnWriteArrayList<String>()
        val fixtureErrors = java.util.concurrent.CopyOnWriteArrayList<Throwable>()
        fun fixture(ip: ByteArray, names: MutableList<String>): Pair<java.net.DatagramSocket, Thread> {
            val socket = java.net.DatagramSocket(java.net.InetSocketAddress(loopback, 0))
            val worker = Thread {
                try { while (!socket.isClosed) {
                    val packet = java.net.DatagramPacket(ByteArray(4096), 4096)
                    socket.receive(packet)
                    val query = packet.data.copyOf(packet.length)
                    var offset = 12
                    val labels = mutableListOf<String>()
                    while (query[offset].toInt() != 0) {
                        val size = query[offset++].toInt() and 255
                        labels += String(query, offset, size, Charsets.US_ASCII)
                        offset += size
                    }
                    val name = labels.joinToString(".")
                    val type = ((query[offset + 1].toInt() and 255) shl 8) or (query[offset + 2].toInt() and 255)
                    names += name
                    val rcode = if (names === rejectedNames) when (name) {
                        "term.tnt-tv.ru" -> 3
                        "tnt-tv.ru" -> 2
                        "nodata.tnt-tv.ru", "empty.tnt-tv.ru", "v6.tnt-tv.ru", "txt.tnt-tv.ru", "srv.tnt-tv.ru", "only.scoped.tnt-tv.ru" -> 0
                        else -> 5
                    } else if (name == "missing.tnt-tv.ru" && names === directNames) 3 else 0
                    val missing = rcode != 0 || name == "empty.tnt-tv.ru" || names === rejectedNames && name in listOf("nodata.tnt-tv.ru", "v6.tnt-tv.ru", "only.scoped.tnt-tv.ru")
                    val payload = when (type) {
                        28 -> java.net.InetAddress.getByName("2001:db8::72").address
                        16 -> byteArrayOf(3, 'v'.code.toByte(), 'p'.code.toByte(), 'n'.code.toByte())
                        33 -> byteArrayOf(0, 0, 0, 1, 13, 61, 0)
                        else -> ip
                    }
                    val response = query.copyOf(offset + 5).apply {
                        this[2] = 0x81.toByte(); this[3] = (0x80 or rcode).toByte()
                        this[6] = 0; this[7] = if (missing) 0 else 1
                        this[8] = 0; this[9] = 0; this[10] = 0; this[11] = 0
                    } + if (missing) ByteArray(0) else byteArrayOf(0xc0.toByte(), 12, 0, type.toByte(), 0, 1, 0, 0, 0, 60, 0, payload.size.toByte()) + payload
                    socket.send(java.net.DatagramPacket(response, response.size, packet.socketAddress))
                } } catch (error: Exception) { if (!socket.isClosed) fixtureErrors += error }
            }.apply { isDaemon = true; start() }
            return socket to worker
        }
        val directIp = byteArrayOf(10, 131.toByte(), 52, 202.toByte())
        val protectedIp = byteArrayOf(203.toByte(), 0, 113, 222.toByte())
        val direct = fixture(directIp, directNames)
        val rejected = fixture(directIp, rejectedNames)
        val protected = fixture(protectedIp, protectedNames)
        val directory = Files.createTempDirectory("noki-dns-query-policy")
        var core: WinNT.HANDLE? = null
        var fixtureProcess: Process? = null
        try {
            val apiPort = SingBoxRuntime.freePort()
            var dnsPort = SingBoxRuntime.freePort()
            while (dnsPort == apiPort || dnsPort == direct.first.localPort || dnsPort == protected.first.localPort)
                dnsPort = SingBoxRuntime.freePort()
            val secret = UUID.randomUUID().toString()
            val config = JSONObject()
                .put("log", JSONObject().put("level", "debug").put("output", directory.resolve("core.log").toString()))
                .put("inbounds", JSONArray().put(JSONObject().put("type", "direct").put("tag", "dns-fixture")
                    .put("listen", "127.0.0.1").put("listen_port", dnsPort).put("override_address", "127.0.0.1").put("override_port", 53)))
                .put("outbounds", JSONArray().put(JSONObject().put("type", "direct").put("tag", "proxy")))
                .put("route", JSONObject().put("final", "proxy").put("auto_detect_interface", true).put("default_domain_resolver", "protected").put("rules", JSONArray().put(JSONObject()
                    .put("inbound", "dns-fixture").put("action", "hijack-dns"))))
                .put("dns", JSONObject().put("disable_cache", true).put("final", "protected")
                    .put("rules", JSONArray()).put("servers", JSONArray().put(JSONObject().put("type", "udp")
                        .put("tag", "protected").put("server", "127.0.0.1").put("server_port", protected.first.localPort).put("detour", "proxy"))))
                .put("experimental", JSONObject().put("clash_api", JSONObject()
                    .put("external_controller", "127.0.0.1:$apiPort").put("secret", secret)))
            val settings = DesktopState().settings.let { it.copy(advancedSettings = it.advancedSettings.copy(
                alwaysOnDomains = listOf("full:private.tnt-tv.ru"))) }
            val bypass = checkNotNull(SingBoxConfig.dnsBypassMatch(settings,
                listOf(DesktopRoutingRule("corp", "Corp", true, listOf("tnt-tv.ru"))), SingBoxConfig.bundledGeosite()))
            SingBoxConfig.applyDnsPolicy(config, bypass,
                WindowsDnsPolicy(listOf(WindowsDnsServer("127.0.0.2", interfaceName, adapter.index, 0),
                    WindowsDnsServer("127.0.0.1", interfaceName, adapter.index, 1),
                    WindowsDnsServer("127.0.0.2", interfaceName, adapter.index, 0, nrptOnly = true)), listOf(
                        WindowsNrptRule(".scoped.tnt-tv.ru", listOf("127.0.0.2"), false),
                        WindowsNrptRule("blocked.tnt-tv.ru", emptyList(), true))))
            val servers = config.getJSONObject("dns").getJSONArray("servers")
            for (i in 0 until servers.length()) servers.getJSONObject(i).takeIf { it.getString("tag").startsWith("system-dns-") }
                ?.apply {
                    put("server_port", if (getString("tag") == "system-dns-0") rejected.first.localPort else direct.first.localPort)
                    put("server", "127.0.0.1")
                }
            val file = directory.resolve("core.json")
            Files.writeString(file, config.toString())
            val checkOutput = directory.resolve("check.log")
            val check = ProcessBuilder(binary.toString(), "check", "-c", file.toString())
                .redirectErrorStream(true).redirectOutput(checkOutput.toFile()).start()
            try {
                assertTrue("Loopback DNS configuration check timed out", check.waitFor(15, TimeUnit.SECONDS))
                assertEquals(Files.readString(checkOutput), 0, check.exitValue())
            } finally { if (check.isAlive) check.destroyForcibly() }
            fixtureProcess = ProcessBuilder(binary.toString(), "run", "-c", file.toString())
                .redirectErrorStream(true).redirectOutput(directory.resolve("console.log").toFile()).start()
            core = Kernel32.INSTANCE.OpenProcess(0x1000 or 0x100000 or 1, false, fixtureProcess.pid().toInt())
            awaitApi(core, apiPort, secret, directory.resolve("core.log"))
            var id = 0
            fun query(name: String, type: Int): ByteArray = java.io.ByteArrayOutputStream().also { bytes ->
                java.io.DataOutputStream(bytes).apply {
                    writeShort(++id); writeShort(0x0100); writeShort(1); writeShort(0); writeShort(0); writeShort(0)
                    for (label in name.split('.')) { writeByte(label.length); write(label.toByteArray(Charsets.US_ASCII)) }
                    writeByte(0); writeShort(type); writeShort(1)
                }
            }.toByteArray()
            fun verify(name: String, type: Int, request: ByteArray, response: ByteArray) {
                assertTrue("DNS response for $name is incomplete", response.size >= 12)
                assertEquals(request[0], response[0]); assertEquals(request[1], response[1])
                assertEquals("Unexpected DNS status for $name", when (name) { "missing.tnt-tv.ru" -> 3; "blocked.tnt-tv.ru" -> 2; else -> 0 }, response[3].toInt() and 15)
                if (name in listOf("empty.tnt-tv.ru", "only.scoped.tnt-tv.ru", "blocked.tnt-tv.ru") || type == 28 && name in listOf("public.example", "private.tnt-tv.ru")) {
                    assertEquals("NODATA expected for $name", 0, response[7].toInt())
                } else if (name != "missing.tnt-tv.ru") {
                    val payload = when (type) {
                        28 -> java.net.InetAddress.getByName("2001:db8::72").address
                        16 -> byteArrayOf(3, 'v'.code.toByte(), 'p'.code.toByte(), 'n'.code.toByte())
                        33 -> byteArrayOf(0, 0, 0, 1, 13, 61, 0)
                        else -> if (name == "public.example" || name == "private.tnt-tv.ru") protectedIp else directIp
                    }
                    assertArrayEquals("Wrong answer for $name type=$type", payload, response.takeLast(payload.size).toByteArray())
                }
            }
            val names = listOf("term.tnt-tv.ru", "public.example", "tnt-tv.ru", "private.tnt-tv.ru", "missing.tnt-tv.ru", "public.example").map { it to 1 } +
                listOf("nodata.tnt-tv.ru" to 1, "empty.tnt-tv.ru" to 1, "v6.tnt-tv.ru" to 28, "public.example" to 28,
                    "private.tnt-tv.ru" to 28, "txt.tnt-tv.ru" to 16, "srv.tnt-tv.ru" to 33, "only.scoped.tnt-tv.ru" to 1, "blocked.tnt-tv.ru" to 1)
            java.net.DatagramSocket(java.net.InetSocketAddress(loopback, 0)).use { udp ->
                udp.soTimeout = 5_000
                udp.connect(loopback, dnsPort)
                for ((name, type) in names) {
                    val request = query(name, type)
                    udp.send(java.net.DatagramPacket(request, request.size))
                    val response = java.net.DatagramPacket(ByteArray(4096), 4096)
                    udp.receive(response)
                    verify(name, type, request, response.data.copyOf(response.length))
                }
            }
            java.net.Socket(loopback, dnsPort).use { tcp ->
                tcp.soTimeout = 5_000
                val input = java.io.DataInputStream(tcp.getInputStream())
                val output = java.io.DataOutputStream(tcp.getOutputStream())
                for ((name, type) in names) {
                    val request = query(name, type)
                    output.writeShort(request.size); output.write(request); output.flush()
                    verify(name, type, request, input.readNBytes(input.readUnsignedShort()))
                }
            }
            val expectedDirect = listOf("term.tnt-tv.ru", "tnt-tv.ru", "missing.tnt-tv.ru", "nodata.tnt-tv.ru", "empty.tnt-tv.ru", "v6.tnt-tv.ru")
            assertEquals(expectedDirect.let { it + it }, directNames)
            assertEquals((expectedDirect + listOf("txt.tnt-tv.ru", "srv.tnt-tv.ru", "only.scoped.tnt-tv.ru")).let { it + it }, rejectedNames)
            assertEquals(listOf("public.example", "private.tnt-tv.ru", "public.example").let { it + it }, protectedNames)
            assertTrue("DNS fixture failed: $fixtureErrors", fixtureErrors.isEmpty())
            // Native file logging flushes every second; DNS queries can finish before the first flush.
            kotlinx.coroutines.runBlocking {
                kotlinx.coroutines.withTimeout(3_000) {
                    while (!Files.readString(directory.resolve("core.log")).contains("destination route 127.0.0.1 via"))
                        kotlinx.coroutines.delay(20)
                }
            }
        } catch (failure: Throwable) {
            val log = runCatching { Files.readString(directory.resolve("core.log")).takeLast(16_384) }.getOrDefault("No core log")
            val console = runCatching { Files.readString(directory.resolve("console.log")).takeLast(8192) }.getOrDefault("")
            throw AssertionError("${failure.message}\nLoopback DNS core log:\n$log\nConsole:\n$console\nFixture errors: $fixtureErrors", failure)
        } finally {
            core?.let { Kernel32.INSTANCE.TerminateProcess(it, 0); Kernel32.INSTANCE.WaitForSingleObject(it, 5000); Kernel32.INSTANCE.CloseHandle(it) }
            fixtureProcess?.let { if (it.isAlive) it.destroyForcibly().waitFor() }
            for ((socket, worker) in listOf(direct, rejected, protected)) { socket.close(); worker.join(1_000) }
            Files.walk(directory).use { it.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        }
    }

    @Test
    fun pinnedNativeCoreChecksXhttpStreamUpAndWireGuardWithGvisor() {
        assumeWindows()
        assumeRuntimeBuilt()
        val binary = Path.of("build/runtime/windows-x64/sing-box.exe").toAbsolutePath().normalize()
        SingBoxRuntime.verifyRuntimeBinary(binary)
        val configurations = mapOf(
            "nokinv" to """{
                "outbounds":[{"type":"nokinv","tag":"proxy","server":"203.0.113.1","server_port":10444,
                    "token":"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA","credential_generation":1,
                    "transport":"quic","mode":"gaming","web_profile":"coherent","shaping":"off","path":"/tunnel",
                    "tls":{"enabled":true,"server_name":"example.com","min_version":"1.3"}}],
                "route":{"final":"proxy"}
            }""",
            "xhttp" to """{
                "outbounds":[{"type":"vless","tag":"proxy","server":"203.0.113.1","server_port":443,
                    "uuid":"75e9f9e8-a9f8-4d42-9de1-f91f1a210991",
                    "tls":{"enabled":true,"server_name":"example.com","utls":{"enabled":true,"fingerprint":"chrome"},
                        "reality":{"enabled":true,"public_key":"pOCSkrZRwni5dyxWn1-puxPZBrRqtoyd-dwrRAn4ogk","short_id":"abcd"}},
                    "transport":{"type":"xhttp","mode":"stream-up","path":"/noki","host":"example.com","x_padding_bytes":"100-1000"}}],
                "route":{"final":"proxy"}
            }""",
            "wireguard" to """{
                "inbounds":[{"type":"tun","tag":"tun-in","address":["10.10.0.2/32"],"stack":"gvisor","auto_route":false,"mtu":1280}],
                "endpoints":[{"type":"wireguard","tag":"proxy","system":false,"address":["10.240.1.2/32"],
                    "private_key":"AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE=",
                    "peers":[{"address":"203.0.113.10","port":51820,"public_key":"AgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgI=",
                        "allowed_ips":["0.0.0.0/0"],"persistent_keepalive_interval":5}]}],
                "outbounds":[{"type":"direct","tag":"direct"}],"route":{"final":"proxy"}
            }"""
        )
        val directory = Files.createTempDirectory("noki-native-check")
        try {
            configurations.forEach { (name, config) ->
                val path = directory.resolve("$name.json")
                val output = directory.resolve("$name.log")
                Files.writeString(path, config)
                // check constructs native transports but does not start the TUN or alter routes.
                val process = ProcessBuilder(binary.toString(), "check", "-c", path.toString())
                    .redirectErrorStream(true).redirectOutput(output.toFile()).start()
                try {
                    assertTrue("$name check timed out", process.waitFor(10, TimeUnit.SECONDS))
                    assertEquals("$name: " + Files.readString(output), 0, process.exitValue())
                } finally { if (process.isAlive) process.destroyForcibly() }
            }
        } finally {
            Files.list(directory).use { files -> files.forEach(Files::deleteIfExists) }
            Files.deleteIfExists(directory)
        }
    }

    @Test
    fun windowsArgumentsRoundTripThroughNativeParser() {
        assumeWindows()
        val arguments = listOf("", "plain", "two words", "C:\\folder with spaces\\", "\\", "\\\\",
            "embedded\"quote", "slash\\\"quote", "slashes\\\\\"quote", "\t tabs \n newline", "Клиент 日本語 🙂")
        val command = "test.exe " + arguments.joinToString(" ", transform = WindowsShell::quoteArgument)
        assertEquals(arguments, Shell32Util.CommandLineToArgv(command).drop(1))
    }

    @Test
    fun shutdownWaitsForGracefulExitAndRejectsForeignIdentityBeforeRequest() = shutdownFixture("graceful")

    @Test
    fun shutdownFallsBackWithinBudgetWhenAcknowledgedButHungUnavailableOrRejected() {
        for (mode in listOf("acknowledged", "hung", "absent", "error", "redirect")) shutdownFixture(mode)
    }

    private fun shutdownFixture(mode: String) {
        assumeWindows()
        val kernel = Kernel32.INSTANCE
        val directory = Files.createTempDirectory("noki-shutdown-")
        val exitMarker = directory.resolve("exit")
        val binary = Path.of(System.getProperty("java.home"), "bin", "java.exe")
        val requests = java.util.concurrent.CopyOnWriteArrayList<Pair<String, String?>>()
        val release = java.util.concurrent.CountDownLatch(1)
        val workers = java.util.concurrent.Executors.newCachedThreadPool()
        val server = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0)
        val port = server.address.port
        server.executor = workers
        server.createContext("/") { exchange ->
            requests += (exchange.requestMethod + " " + exchange.requestURI.path) to exchange.requestHeaders.getFirst("Authorization")
            try {
                when (mode) {
                    "hung" -> release.await(10, TimeUnit.SECONDS)
                    "error" -> exchange.sendResponseHeaders(503, -1)
                    "redirect" -> {
                        exchange.responseHeaders.set("Location", "http://127.0.0.1:$port/redirected")
                        exchange.sendResponseHeaders(302, -1)
                    }
                    else -> {
                        exchange.sendResponseHeaders(204, -1)
                        exchange.close()
                        if (mode == "graceful") Files.writeString(exitMarker, "exit")
                    }
                }
            } finally { exchange.close() }
        }
        server.start()
        if (mode == "absent") server.stop(0)
        val http = OkHttpClient.Builder().proxy(java.net.Proxy.NO_PROXY).followRedirects(false).followSslRedirects(false)
            .retryOnConnectionFailure(false).build()
        var process: WinNT.HANDLE? = null
        try {
            process = WindowsShell.launchProcess(binary, listOf("-cp", System.getProperty("noki.test.classpath", System.getProperty("java.class.path")),
                GuardProcess::class.java.name, "child", "unused", exitMarker.toString()))
            val current = SingBoxRuntime.Running(kernel.GetProcessId(process), SingBoxRuntime.creationTime(process), port, "shutdown-secret", 0, "fixture")
            if (mode == "graceful") {
                assertThrows(IllegalStateException::class.java) {
                    SingBoxRuntime.stopProcess(current, binary.resolveSibling("foreign.exe"), http)
                }
                assertThrows(IllegalStateException::class.java) {
                    SingBoxRuntime.stopProcess(current.copy(startedAt = current.startedAt + 10_000), binary, http)
                }
                assertTrue("Foreign identity must not receive any shutdown request", requests.isEmpty())
                assertEquals("Foreign identity must not be killed", 258, kernel.WaitForSingleObject(process, 0))
            }
            val started = System.nanoTime()
            SingBoxRuntime.stopProcess(current, binary, http)
            val elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
            assertEquals("Stop must confirm actual process exit: $mode", 0, kernel.WaitForSingleObject(process, 0))
            val exit = IntByReference()
            assertTrue(kernel.GetExitCodeProcess(process, exit))
            assertEquals("Only graceful shutdown should retain the child's exit code: $mode", if (mode == "graceful") 0 else 1, exit.value)
            assertTrue("Shutdown exceeded its short budget: $mode $elapsed ms", elapsed < 3_000)
            if (mode == "acknowledged") assertTrue("204 must not be mistaken for process exit", elapsed >= 1_500)
            if (mode != "absent") assertEquals(listOf("POST /noki/shutdown" to "Bearer shutdown-secret"), requests.toList())
            println("shutdown fixture $mode: $elapsed ms, exit=${exit.value}")
        } finally {
            release.countDown()
            server.stop(0); workers.shutdownNow()
            process?.let {
                if (kernel.WaitForSingleObject(it, 0) == 258) { kernel.TerminateProcess(it, 1); kernel.WaitForSingleObject(it, 2_000) }
                kernel.CloseHandle(it)
            }
            http.connectionPool.evictAll(); http.dispatcher.executorService.shutdownNow()
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun nativeStopChecksProcessIdentityAndClosesTheCoreGracefully() {
        assumeWindows()
        assumeRuntimeBuilt()
        val binary = Path.of("build/runtime/windows-x64/sing-box.exe").toAbsolutePath().normalize()
        assertTrue("Prepare the fixed Windows runtime before this test", Files.isRegularFile(binary))
        SingBoxRuntime.verifyRuntimeBinary(binary)
        val directory = Files.createTempDirectory("noki runtime тест ")
        var core: WinNT.HANDLE? = null
        try {
            val controlPort = SingBoxRuntime.freePort()
            var mixedPort = SingBoxRuntime.freePort()
            while (mixedPort == controlPort) mixedPort = SingBoxRuntime.freePort()
            val secret = UUID.randomUUID().toString()
            val config = JSONObject()
                .put("log", JSONObject().put("level", "info").put("output", directory.resolve("core.log").toString()))
                .put("inbounds", JSONArray().put(JSONObject().put("type", "mixed").put("tag", "local-test")
                    .put("listen", "127.0.0.1").put("listen_port", mixedPort)))
                .put("outbounds", JSONArray().put(JSONObject().put("type", "direct").put("tag", "direct")))
                .put("route", JSONObject().put("final", "direct"))
                .put("experimental", JSONObject().put("clash_api", JSONObject()
                    .put("external_controller", "127.0.0.1:$controlPort").put("secret", secret)))
            val configPath = directory.resolve("test config.json")
            Files.writeString(configPath, config.toString())
            core = launchHidden(binary, listOf("run", "-c", configPath.toString()))
            val pid = Kernel32.INSTANCE.GetProcessId(core)
            val creation = WinBase.FILETIME()
            assertTrue(Kernel32.INSTANCE.GetProcessTimes(core, creation, WinBase.FILETIME(), WinBase.FILETIME(), WinBase.FILETIME()))
            val startedAt = creation.toDate().time
            val actualPath = CharArray(32768)
            val pathLength = IntByReference(actualPath.size)
            assertTrue(Kernel32.INSTANCE.QueryFullProcessImageName(core, 0, actualPath, pathLength))
            assertEquals(binary, Path.of(String(actualPath, 0, pathLength.value)).toAbsolutePath().normalize())
            awaitApi(core, controlPort, secret, directory.resolve("core.log"))
            val control = OkHttpClient.Builder().proxy(java.net.Proxy.NO_PROXY).build()
            try {
                val carrierUrl = "http://127.0.0.1:$controlPort/noki/nokinv/carrier"
                control.newCall(Request.Builder().url(carrierUrl).build()).execute().use { assertEquals(401, it.code) }
                control.newCall(Request.Builder().url(carrierUrl).header("Authorization", "Bearer $secret").build()).execute().use {
                    assertTrue(it.isSuccessful)
                    assertEquals("", JSONObject(checkNotNull(it.body).string()).getString("carrier"))
                }
                val rebindUrl = "http://127.0.0.1:$controlPort/noki/network/rebind"
                control.newCall(Request.Builder().url(rebindUrl).post(ByteArray(0).toRequestBody()).build()).execute().use {
                    assertEquals("Network reset must require the same bearer secret", 401, it.code)
                }
                control.newCall(Request.Builder().url(rebindUrl).header("Authorization", "Bearer $secret").build()).execute().use {
                    assertEquals("A read request must not reset native sockets", 405, it.code)
                }
            } finally { control.connectionPool.evictAll(); control.dispatcher.executorService.shutdownNow() }

            val vault = WindowsVault(directory)
            val current = SingBoxRuntime.Running(pid, startedAt, controlPort, secret, mixedPort, "Test", serverId = "test-exit")
            SingBoxRuntime.persistIdentity(vault, current.copy(startedAt = startedAt + 10_000))
            val runtime = SingBoxRuntime(vault)
            assertNull(runtime.restored())
            // A new launcher record must replace the cached stale identity, including the actual server.
            SingBoxRuntime.persistIdentity(vault, current)
            assertEquals(current, runtime.restored())
            kotlinx.coroutines.runBlocking { runtime.rebindNetwork(current) }
            assertEquals("Rebind must preserve native process identity and its original timer", current, runtime.restored())
            assertEquals(258, Kernel32.INSTANCE.WaitForSingleObject(core, 0))

            assertThrows(IllegalStateException::class.java) { SingBoxRuntime.stopProcess(current, binary.resolveSibling("wrong-core.exe"), control) }
            assertEquals(258, Kernel32.INSTANCE.WaitForSingleObject(core, 0))
            assertThrows(IllegalStateException::class.java) { SingBoxRuntime.stopProcess(current.copy(startedAt = startedAt + 10_000), binary, control) }
            assertEquals(258, Kernel32.INSTANCE.WaitForSingleObject(core, 0))
            val stopStarted = System.nanoTime()
            kotlinx.coroutines.runBlocking { runtime.stop(restoreInternet = false) }
            val stoppedInMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - stopStarted)
            println("Identity-safe graceful native stop: $stoppedInMillis ms")
            assertTrue("Graceful stop must have a short bounded budget: $stoppedInMillis ms", stoppedInMillis < 3_000)
            assertEquals(0, Kernel32.INSTANCE.WaitForSingleObject(core, 5_000))
            val exit = IntByReference()
            assertTrue(Kernel32.INSTANCE.GetExitCodeProcess(core, exit))
            assertEquals("Acknowledged native shutdown must close the core without TerminateProcess", 0, exit.value)
            kotlinx.coroutines.runBlocking { runtime.stop(restoreInternet = false) }
            assertNull(runtime.restored())
        } finally {
            core?.let {
                // This handle identifies only the process created by this test, even after PID reuse.
                if (Kernel32.INSTANCE.WaitForSingleObject(it, 0) == 258) {
                    Kernel32.INSTANCE.TerminateProcess(it, 1)
                    Kernel32.INSTANCE.WaitForSingleObject(it, 5_000)
                }
                Kernel32.INSTANCE.CloseHandle(it)
            }
            Files.list(directory).use { files -> files.forEach(Files::deleteIfExists) }
            Files.deleteIfExists(directory)
        }
    }

    @Test fun `desktop startup elevates once and preserves tray launch`() {
        assumeWindows()
        var requests = 0
        var forwarded = emptyList<String>()
        val relaunch: (List<String>) -> Unit = { requests++; forwarded = it }
        assertFalse(WindowsShell.ensureAdministrator(arrayOf("--tray"), elevated = false, relaunch))
        assertEquals(listOf("--tray", "--elevated-user=${WindowsNetworkGuard.currentUserSid()}"), forwarded)
        assertEquals(1, requests)
        assertTrue(WindowsShell.ensureAdministrator(forwarded.toTypedArray(), elevated = true, relaunch))
        assertThrows(IllegalStateException::class.java) {
            WindowsShell.ensureAdministrator(forwarded.toTypedArray(), elevated = false, relaunch)
        }
        assertEquals("A failed elevation must not create a prompt loop", 1, requests)
        assertThrows(IllegalStateException::class.java) {
            WindowsShell.ensureAdministrator(arrayOf("--elevated-user=S-1-0-0"), elevated = true, relaunch)
        }
        assertThrows(IllegalArgumentException::class.java) {
            WindowsShell.ensureAdministrator(arrayOf("--unknown"), elevated = false, relaunch)
        }
        assertEquals(1, requests)
    }

    @Test fun `cancelled startup elevation exits before opening a desktop`() {
        assumeWindows()
        assertFalse(WindowsShell.ensureAdministrator(emptyArray(), elevated = false) {
            throw com.sun.jna.platform.win32.Win32Exception(1223)
        })
        assertThrows(com.sun.jna.platform.win32.Win32Exception::class.java) {
            WindowsShell.ensureAdministrator(emptyArray(), elevated = false) {
                throw com.sun.jna.platform.win32.Win32Exception(5)
            }
        }
    }

    private fun launchHidden(executable: Path, arguments: List<String>): WinNT.HANDLE {
        val info = ShellAPI.SHELLEXECUTEINFO().apply {
            cbSize = size(); fMask = 0x00000040; lpVerb = "open"; lpFile = executable.toString()
            lpParameters = arguments.joinToString(" ", transform = WindowsShell::quoteArgument)
            lpDirectory = executable.parent.toString(); nShow = 0
        }
        assertTrue("Windows could not start the isolated test process", Shell32.INSTANCE.ShellExecuteEx(info))
        return checkNotNull(info.hProcess)
    }

    private fun awaitApi(core: WinNT.HANDLE, port: Int, secret: String, coreLog: Path) {
        val http = OkHttpClient.Builder().callTimeout(1, TimeUnit.SECONDS).build()
        var lastResult = "No HTTP attempt completed"
        try {
            repeat(40) {
                assertEquals("Test core exited during startup", 258, Kernel32.INSTANCE.WaitForSingleObject(core, 0))
                val ready = runCatching {
                    http.newCall(Request.Builder().url("http://127.0.0.1:$port/version")
                        .header("Authorization", "Bearer $secret").build()).execute().use {
                            lastResult = "HTTP ${it.code}"
                            it.isSuccessful
                        }
                }.getOrElse { lastResult = "${it.javaClass.simpleName}: ${it.message}"; false }
                if (ready) return
                Thread.sleep(100)
            }
            val log = runCatching { Files.readString(coreLog).takeLast(8192) }.getOrElse { "Unavailable: ${it.message}" }
            fail("Loopback control API did not start on port $port: $lastResult\nCore log:\n$log")
        } finally {
            http.connectionPool.evictAll()
            http.dispatcher.executorService.shutdownNow()
        }
    }

    private class ProbeServer(private val handshakeDelayMillis: Long = 0, private val respond: (Int, java.net.Socket) -> Unit) : AutoCloseable {
        val accepted = java.util.concurrent.atomic.AtomicInteger()
        val requests = java.util.concurrent.atomic.AtomicInteger()
        private val sockets = java.util.concurrent.CopyOnWriteArrayList<java.net.Socket>()
        private val workers = java.util.concurrent.CopyOnWriteArrayList<Thread>()
        private val listener = java.net.ServerSocket(0, 10, java.net.InetAddress.getByName("127.0.0.1"))
        val port get() = listener.localPort
        private val acceptor = Thread {
            try { while (!listener.isClosed) {
                val socket = listener.accept().also { sockets += it; accepted.incrementAndGet() }
                workers += Thread {
                    try { socket.use {
                        socket.soTimeout = 8_000
                        val input = socket.getInputStream(); val output = socket.getOutputStream()
                        check(input.read() == 5)
                        input.readNBytes(input.read()); output.write(byteArrayOf(5, 0)); output.flush()
                        val command = input.readNBytes(4)
                        val addressBytes = when (command[3].toInt()) { 1 -> 4; 3 -> input.read(); 4 -> 16; else -> error("SOCKS address") }
                        input.readNBytes(addressBytes + 2)
                        Thread.sleep(handshakeDelayMillis)
                        output.write(byteArrayOf(5, 0, 0, 1, 127, 0, 0, 1, 0, 80)); output.flush()
                        val reader = input.bufferedReader()
                        var request = 0
                        while (reader.readLine() != null) {
                            while (!reader.readLine().isNullOrEmpty()) { }
                            requests.incrementAndGet()
                            respond(++request, socket)
                        }
                    } } catch (_: Exception) { }
                }.apply { isDaemon = true; start() }
            } } catch (_: java.io.IOException) { }
        }.apply { isDaemon = true; start() }

        override fun close() {
            listener.close(); sockets.forEach { it.close() }; workers.forEach { it.interrupt() }
            acceptor.join(1_000); workers.forEach { it.join(1_000) }
        }
    }

    private fun assumeWindows() = assumeTrue(System.getProperty("os.name").startsWith("Windows"))
    private fun assumeRuntimeBuilt() = assumeTrue("Windows EXE build deferred by the user; the previous core must not be used",
        SingBoxRuntime.BINARY_HASH != "PENDING_WINDOWS_BUILD")
}
