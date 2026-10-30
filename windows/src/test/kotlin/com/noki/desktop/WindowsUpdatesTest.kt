package com.noki.desktop

import com.sun.jna.Memory
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class WindowsUpdatesTest {
    private fun native() = VelopackNative(Path.of("build/tools/velopack_libc_win_x64_msvc.dll"))

    @Test fun `uninstall skips the system idle process while enumerating Windows processes`() {
        assertFalse(stopWindowsUninstallProcess(0, emptySet()))
        ProcessHandle.allProcesses().use { processes ->
            processes.forEach { assertFalse(stopWindowsUninstallProcess(it.pid(), emptySet())) }
        }
    }

    @Test fun `uninstall stops only a matching process and tolerates an already stopped process`() {
        val executable = Path.of(System.getProperty("java.home"), "bin", "java.exe").toAbsolutePath().normalize()
        val kernel = com.sun.jna.platform.win32.Kernel32.INSTANCE
        val process = WindowsShell.launchProcess(executable, listOf("-cp",
            System.getProperty("noki.test.classpath", System.getProperty("java.class.path")),
            SingBoxRuntimeTest.GuardProcess::class.java.name, "child"))
        try {
            val pid = kernel.GetProcessId(process).toLong()
            assertFalse(stopWindowsUninstallProcess(pid, setOf(executable.resolveSibling("other.exe"))))
            assertEquals(258, kernel.WaitForSingleObject(process, 0))
            assertTrue(stopWindowsUninstallProcess(pid, setOf(executable)))
            assertEquals(0, kernel.WaitForSingleObject(process, 0))
            assertFalse(stopWindowsUninstallProcess(pid, setOf(executable)))
            assertFalse(stopWindowsUninstallProcess(ProcessHandle.current().pid(), setOf(executable)))
        } finally {
            if (kernel.WaitForSingleObject(process, 0) == 258) {
                kernel.TerminateProcess(process, 1); kernel.WaitForSingleObject(process, 5_000)
            }
            kernel.CloseHandle(process)
        }
    }

    @Test fun `installed uninstall entry uses Inno and rejects portable or unowned roots`() {
        val root = Files.createTempDirectory("noki-uninstall-entry")
        try {
            val current = Files.createDirectory(root.resolve("current"))
            Files.writeString(current.resolve("sq.version"), "<package><id>Noki.Desktop</id></package>")
            Files.write(root.resolve("Update.exe"), byteArrayOf(77, 90))
            assertNull(windowsUninstallCommand(current))
            Files.write(root.resolve("unins000.exe"), byteArrayOf(77, 90))
            assertEquals(WindowsShell.quoteArgument(root.resolve("unins000.exe").toString()), windowsUninstallCommand(current))
            Files.write(root.resolve(".portable"), byteArrayOf())
            assertNull(windowsUninstallCommand(current))
            Files.delete(root.resolve(".portable"))
            Files.writeString(current.resolve("sq.version"), "<package><id>Other.App</id></package>")
            assertNull(windowsUninstallCommand(current))
        } finally {
            Files.walk(root).use { it.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
        }
    }

    @Test fun `applied package cleanup preserves pending updates and respects the updater lock`() {
        val root = Files.createTempDirectory("noki-applied-packages")
        try {
            val current = Files.createDirectory(root.resolve("current"))
            val packages = Files.createDirectory(root.resolve("packages"))
            Files.writeString(current.resolve("sq.version"), "installed")
            Files.write(root.resolve("Update.exe"), byteArrayOf(77, 90))
            val names = listOf("Noki.Desktop-1.2.12-full.nupkg", "Noki.Desktop-1.3.1-full.nupkg",
                "Noki.Desktop-1.4.0-full.nupkg", "Other.App-1.0.0-full.nupkg", "Noki.Desktop-1.4.0-full.nupkg.partial", ".betaId")
            names.forEach { Files.writeString(packages.resolve(it), "fixture") }
            val handle = com.sun.jna.platform.win32.Kernel32.INSTANCE.CreateFile(packages.resolve(".velopack_lock").toString(),
                com.sun.jna.platform.win32.WinNT.GENERIC_READ or com.sun.jna.platform.win32.WinNT.GENERIC_WRITE,
                0, null, com.sun.jna.platform.win32.WinNT.OPEN_ALWAYS, 0, null)
            assertNotEquals(com.sun.jna.platform.win32.WinBase.INVALID_HANDLE_VALUE, handle)
            try {
                cleanupAppliedPackages(current, "1.3.1")
                names.forEach { assertTrue(Files.exists(packages.resolve(it))) }
            } finally { com.sun.jna.platform.win32.Kernel32.INSTANCE.CloseHandle(handle) }
            cleanupAppliedPackages(current, "1.3.1")
            names.take(2).forEach { assertFalse(Files.exists(packages.resolve(it))) }
            names.drop(2).forEach { assertTrue(Files.exists(packages.resolve(it))) }
            assertTrue(Files.exists(current.resolve("sq.version")))
        } finally { Files.walk(root).use { it.sorted(Comparator.reverseOrder()).forEach(Files::delete) } }
    }

    @Test fun `unpackaged test process has no Windows package identity`() {
        assertFalse(WindowsShell.isPackagedProcess())
    }

    @Test fun `packaged startup skips Velopack even in a native image`() {
        val property = "org.graalvm.nativeimage.imagecode"
        val previous = System.getProperty(property)
        try {
            System.setProperty(property, "runtime")
            VelopackNative.startup(packaged = true)
            assertNull(VelopackNative.current())
        } finally {
            if (previous == null) System.clearProperty(property) else System.setProperty(property, previous)
        }
    }

    @Test fun `native SDK uses the packaged bool size_t and callback ABI`() { verifyVelopackAbi(native()) }

    @Test fun `native SDK checks downloads and rejects a corrupted feed package`() = runBlocking<Unit> {
        val sdk = native()
        val root = Files.createTempDirectory("noki-update-feed")
        val current = Files.createDirectory(root.resolve("current"))
        val packages = Files.createDirectory(root.resolve("packages"))
        val updateExe = Files.write(root.resolve("Update.exe"), byteArrayOf(77, 90))
        val manifest = Files.writeString(current.resolve("sq.version"), """
            <package><metadata><id>Noki.Desktop</id><version>0.5.11</version><title>Noki</title><authors>Noki</authors>
            <mainExe>Noki.exe</mainExe><channel>win</channel><os>win</os><rid>win-x64</rid><machineArchitecture>x64</machineArchitecture></metadata></package>
        """.trimIndent())
        val data = java.io.ByteArrayOutputStream().also { output -> ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry("Noki.nuspec"))
            zip.write(Files.readString(manifest).replace("0.5.11", "0.5.12").toByteArray()); zip.closeEntry()
            zip.putNextEntry(ZipEntry("lib/app/Noki.exe"))
            zip.write(ByteArray(1024 * 1024).also { java.util.Random(42).nextBytes(it) }); zip.closeEntry()
        } }.toByteArray()
        fun hash(algorithm: String) = MessageDigest.getInstance(algorithm).digest(data).joinToString("") { "%02x".format(it) }
        val asset = JSONObject().put("PackageId", "Noki.Desktop").put("Version", "0.5.12").put("Type", "Full")
            .put("FileName", "Noki.Desktop-0.5.12-full.nupkg").put("Size", data.size).put("SHA256", hash("SHA-256")).put("SHA1", hash("SHA-1"))
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val corrupt = java.util.concurrent.atomic.AtomicBoolean(false)
        val published = java.util.concurrent.atomic.AtomicBoolean(true)
        val emptyChecks = java.util.concurrent.atomic.AtomicInteger()
        server.createContext("/") { exchange ->
            val bytes = if (exchange.requestURI.path.endsWith(".json")) {
                val assets = org.json.JSONArray()
                if (published.get()) assets.put(asset) else emptyChecks.incrementAndGet()
                JSONObject().put("Assets", assets).toString().toByteArray()
            }
                else if (corrupt.get()) ByteArray(data.size) else data
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { output ->
                if (exchange.requestURI.path.endsWith(".nupkg")) for (offset in bytes.indices step 32_768) {
                    output.write(bytes, offset, minOf(32_768, bytes.size - offset)); output.flush(); Thread.sleep(10)
                } else output.write(bytes)
            }; exchange.close()
        }
        val strings = listOf(root, updateExe, packages, manifest, current).map { path ->
            val bytes = path.toString().toByteArray()
            Memory(bytes.size + 1L).apply { clear(); write(0, bytes, 0, bytes.size) }
        }
        try {
            server.start()
            for (portable in 0..1) Memory(48).use { locator ->
                locator.clear(); strings.forEachIndexed { index, pointer -> locator.setPointer(index * 8L, pointer) }
                locator.setByte(40, portable.toByte())
                val manager = sdk.manager("http://127.0.0.1:${server.address.port}", locator)
                try {
                    assertEquals(portable, sdk.byte("is_portable", manager))
                    val info = checkNotNull(sdk.check(manager))
                    try {
                        assertEquals("0.5.12", info.getPointer(0).getPointer(8).getString(0))
                        val percent = java.util.concurrent.atomic.AtomicInteger(-1)
                        sdk.download(manager, info, VelopackProgress { percent.set(it) })
                        assertTrue("SDK must report download progress", percent.get() in 0..100)
                        val downloaded = packages.resolve("Noki.Desktop-0.5.12-full.nupkg")
                        assertArrayEquals(data, Files.readAllBytes(downloaded))
                        Files.delete(downloaded); corrupt.set(true)
                        assertNotNull(runCatching { sdk.download(manager, info, VelopackProgress {}) }.exceptionOrNull())
                        corrupt.set(false)
                        published.set(false)
                        emptyChecks.set(0)
                        val attempts = java.util.concurrent.atomic.AtomicInteger()
                        val refresher = WindowsUpdates(sdk, pollIntervalMillis = 50) {
                            check(attempts.incrementAndGet() > 1) { "Simulated initial manager failure" }
                            sdk.manager("http://127.0.0.1:${server.address.port}", locator)
                        }
                        try {
                            withTimeout(5_000) { while (emptyChecks.get() == 0) delay(10) }
                            assertNull(refresher.state.value.version)
                            published.set(true)
                            withTimeout(5_000) { refresher.state.first { it.version == "0.5.12" } }
                            assertTrue(attempts.get() >= 2)
                        } finally { refresher.close(); published.set(true) }
                        val updater = WindowsUpdates(sdk) { sdk.manager("http://127.0.0.1:${server.address.port}", locator) }
                        val stages = java.util.concurrent.CopyOnWriteArrayList<DesktopUpdateStage>()
                        val collected = launch(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
                            updater.state.map { it.stage }.distinctUntilChanged().collect { stages.add(it) }
                        }
                        try {
                            withTimeout(5_000) { updater.state.first { it.version == "0.5.12" } }
                            updater.download()
                            withTimeout(5_000) { updater.state.first { it.stage == DesktopUpdateStage.READY } }
                            // Repeated clicks must not start another stop/apply cycle after READY.
                            repeat(5) { updater.download(); delay(30) }
                            assertEquals(1, stages.count { it == DesktopUpdateStage.DOWNLOADING })
                            updater.stopFailed()
                            assertEquals("vpn_stop_failed", updater.state.value.error)
                            updater.download()
                            withTimeout(5_000) { updater.state.first { it.stage == DesktopUpdateStage.READY } }
                        } finally { updater.close(); collected.cancelAndJoin() }
                    } finally { sdk.void("free_update_info", info) }
                } finally {
                    sdk.void("free_update_manager", manager)
                    Files.deleteIfExists(packages.resolve("Noki.Desktop-0.5.12-full.nupkg"))
                }
            }
        } finally {
            server.stop(0); strings.forEach(Memory::close)
            Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
        }
    }
}
