package com.noki.desktop

import com.noki.vpn.data.*
import java.lang.ref.WeakReference
import java.nio.file.Files
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference
import java.util.logging.Handler
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger

/** One bounded, encrypted outbox for application errors; core traffic logs stay in their own viewer. */
internal class DesktopDiagnostics(private val vault: WindowsVault, private val settings: () -> AdvancedSettings) : AutoCloseable {
    private var pending = runCatching { AppLogStore.decode(readLog("logs.dpapi")) }.getOrElse {
        listOf(entry("diagnostic_storage_read_failed", true, it, null, "diagnostics"))
    }.map(::sanitize)
    private var crash = runCatching { AppLogStore.decode(readLog("crash.dpapi")).lastOrNull() }.getOrNull()?.let(::sanitize)
    private var registration: AutoCloseable? = null
    private var lastFailure = WeakReference<Throwable>(null)
    private val emergencyReserve = AtomicReference<ByteArray?>(null)

    init {
        pending = AppLogStore.prune((pending + listOfNotNull(crash)).distinct())
        applySettings(settings())
    }

    @Synchronized fun record(event: String, error: Boolean = false, cause: Throwable? = null,
        latency: Long? = null, category: String = "app") {
        if (!enabled(error)) return
        val entry = entry(event, error, cause, latency, category)
        val previous = pending.lastOrNull()
        if (previous?.copy(timestamp = entry.timestamp) == entry &&
            Instant.now().epochSecond - Instant.parse(previous.timestamp).epochSecond in 0..59) return
        pending = AppLogStore.prune(pending + entry)
        persist()
    }

    @Synchronized internal fun captureFailure(cause: Throwable) {
        emergencyReserve.set(null)
        if (!enabled(error = true) || lastFailure.get() === cause) return
        lastFailure = WeakReference(cause)
        val entry = entry("unhandled_exception", true, cause, null, "crash")
        // Keep the fatal write small: serializing the full outbox may fail again during an OOM.
        crash = entry
        runCatching { vault.writeEncrypted("crash.dpapi", AppLogStore.encode(listOf(entry))) }
        if (cause !is VirtualMachineError) {
            pending = AppLogStore.prune(pending + entry)
            persist()
        }
    }

    @Synchronized fun batch(): List<AppLogEntry> {
        val selected = ArrayList<AppLogEntry>()
        var size = 0
        for (entry in pending.sortedWith(compareBy<AppLogEntry> { it.category != "crash" }
            .thenBy { it.level != "error" }.thenByDescending { it.timestamp })) {
            val length = render(entry).length
            if (size + length > 160_000) break
            selected.add(entry)
            size += length
        }
        return selected.sortedBy { it.timestamp }
    }

    @Synchronized fun hasPending(): Boolean = pending.isNotEmpty()

    @Synchronized fun acknowledge(sent: List<AppLogEntry>) {
        val remaining = pending - sent.toSet()
        vault.writeEncrypted("logs.dpapi", AppLogStore.encode(remaining))
        pending = remaining
        if (crash != null && crash in sent) {
            Files.deleteIfExists(vault.directory.resolve("crash.dpapi"))
            crash = null
        }
    }

    @Synchronized fun applySettings(value: AdvancedSettings) {
        pending = pending.filter { if (it.level == "error") value.errorLogsEnabled else value.connectionLogsEnabled }
        if (!value.errorLogsEnabled) {
            crash = null
            runCatching { Files.deleteIfExists(vault.directory.resolve("crash.dpapi")) }
        }
        if (!AppDiagnosticLogPolicy.shouldStoreAppLog(value)) {
            pending = emptyList()
            runCatching { Files.deleteIfExists(vault.directory.resolve("logs.dpapi")) }
        } else persist()
    }

    @Synchronized fun install() {
        if (registration != null) return
        emergencyReserve.set(ByteArray(1024 * 1024))
        fun capture(error: Throwable) {
            runCatching { captureFailure(error) }
        }
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        val uncaught = Thread.UncaughtExceptionHandler { thread, error ->
            capture(error)
            if (previous != null) previous.uncaughtException(thread, error) else error.printStackTrace()
        }
        val loggers = listOf("dev.nucleusframework", "org.jetbrains.skiko", "androidx.compose").map(Logger::getLogger)
        val handler = object : Handler() {
            override fun publish(record: LogRecord) {
                val error = record.thrown ?: return
                if (record.level.intValue() >= Level.WARNING.intValue() &&
                    listOf("dev.nucleusframework.", "org.jetbrains.skiko.", "androidx.compose.").any { record.loggerName.orEmpty().startsWith(it) }) capture(error)
            }
            override fun flush() = Unit
            override fun close() = Unit
        }
        // Capture before parent console handlers try to format the same failure during an OOM.
        loggers.forEach { it.addHandler(handler) }
        Thread.setDefaultUncaughtExceptionHandler(uncaught)
        registration = AutoCloseable {
            loggers.forEach { it.removeHandler(handler) }
            if (Thread.getDefaultUncaughtExceptionHandler() === uncaught) Thread.setDefaultUncaughtExceptionHandler(previous)
            emergencyReserve.set(null)
        }
    }

    @Synchronized override fun close() { registration?.close(); registration = null }

    private fun enabled(error: Boolean): Boolean = settings().let {
        AppDiagnosticLogPolicy.shouldStoreAppLog(it) && if (error) it.errorLogsEnabled else it.connectionLogsEnabled
    }

    private fun persist() { runCatching { vault.writeEncrypted("logs.dpapi", AppLogStore.encode(pending)) } }

    private fun readLog(name: String): String? {
        val file = vault.directory.resolve(name)
        if (Files.exists(file)) check(Files.size(file) <= 16L * 1024 * 1024) { "diagnostic_file_too_large" }
        return vault.readEncrypted(name)
    }

    companion object {
        val appVersion: String by lazy {
            DesktopDiagnostics::class.java.getResourceAsStream("/noki/desktop/version.txt")?.use {
                it.readNBytes(64).toString(Charsets.UTF_8).trim().takeIf { value -> value.matches(Regex("[0-9]+\\.[0-9]+\\.[0-9]+")) }
            } ?: "unknown"
        }

        internal fun entry(event: String, error: Boolean, cause: Throwable?, latency: Long?, category: String): AppLogEntry = AppLogEntry(
            timestamp = Instant.now().toString(), level = if (error) "error" else "info", category = category,
            message = AppDiagnosticRedactor.redact(event).take(256), details = cause?.let(::errorDetails),
            appVersion = appVersion, errorType = cause?.javaClass?.name, apiResponseTimeMs = latency,
            connectionSuccess = if (latency != null) true else null,
        ).let(::sanitize)

        internal fun errorDetails(error: Throwable): String = buildString {
            var cause: Throwable? = error
            repeat(8) { depth ->
                val current = cause ?: return@buildString
                if (depth > 0) append("Caused by: ")
                append(current.javaClass.name)
                if (current is BackendException) append(" (HTTP ").append(current.statusCode).append(')')
                if (current is SingBoxRuntime.StartupFailure || current is SingBoxRuntime.ProbeFailure)
                    current.message?.takeIf { it.isNotBlank() }?.let { append(": ").append(safeMessage(it)) }
                append('\n')
                current.stackTrace.take(32).forEach { append("    at ").append(it).append('\n') }
                current.suppressed.take(4).forEach { suppressed ->
                    append("Suppressed: ").append(suppressed.javaClass.name)
                    if (suppressed is SingBoxRuntime.ProbeFailure) append(": ").append(safeMessage(suppressed.message.orEmpty()))
                    append('\n')
                    suppressed.stackTrace.take(8).forEach { append("    at ").append(it).append('\n') }
                }
                cause = current.cause
            }
        }.take(16_384) + if (error is OutOfMemoryError) {
            val memory = Runtime.getRuntime()
            "\nheap_used_bytes=${memory.totalMemory() - memory.freeMemory()} heap_max_bytes=${memory.maxMemory()}\n"
        } else ""

        internal fun safeMessage(value: String): String = safeText(value).take(8192)

        internal fun render(entry: AppLogEntry): String = buildString {
            append(entry.timestamp).append(" [").append(entry.level.uppercase(java.util.Locale.ROOT)).append("] [").append(entry.category)
                .append("] [").append(entry.appVersion ?: "unknown").append("] ")
                .append(entry.message).append('\n')
            entry.apiResponseTimeMs?.let { append("    latency_ms=").append(it).append('\n') }
            entry.details?.let { append(it).append('\n') }
        }

        private fun sanitize(entry: AppLogEntry): AppLogEntry = entry.copy(
            message = safeText(entry.message.take(256)), details = entry.details?.take(16_384)?.let(::safeText),
            category = entry.category.take(64), appVersion = entry.appVersion?.take(64), errorType = entry.errorType?.take(256),
        )

        private fun safeText(value: String): String = AppDiagnosticRedactor.redact(value)
            .replace(Regex("(?i)https?://[^\\s]+"), "<url>")
            .replace(Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}"), "<email>")
            .replace(Regex("(?i)[A-Z]:[\\\\/][^\\r\\n]+"), "<path>")
    }
}
