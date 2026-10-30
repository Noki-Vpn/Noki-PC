package com.noki.vpn.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

enum class EndpointHealthEventType(val wireValue: String) {
    CONNECT_SUCCESS("connect_success"),
    CONNECT_FAIL("connect_fail"),
    ACTIVE_PROBE_SUCCESS("active_probe_success"),
    ACTIVE_PROBE_SLOW("active_probe_slow"),
    ACTIVE_PROBE_FAIL("active_probe_fail"),
    STARTUP_PRECHECK_FAIL("startup_precheck_fail"),
    CANDIDATE_PROBE("candidate_probe"),
    HEARTBEAT("heartbeat");

    companion object {
        fun fromWireValue(value: String?): EndpointHealthEventType? =
            entries.firstOrNull { it.wireValue == value }
    }
}

enum class EndpointScoreBucket(val wireValue: String) {
    GOOD("good"),
    OK("ok"),
    WEAK("weak"),
    BAD("bad");

    companion object {
        fun fromWireValue(value: String?): EndpointScoreBucket? =
            entries.firstOrNull { it.wireValue == value }
    }
}

data class EndpointHealthEvent(
    val endpointCode: String,
    val networkKind: EndpointRankingPolicy.NetworkKind,
    val eventType: EndpointHealthEventType,
    val success: Boolean,
    val slow: Boolean,
    val scoreBucket: EndpointScoreBucket,
)

interface EndpointHealthEventStore {
    fun loadEndpointHealthEventQueue(): List<EndpointHealthEvent>
    fun saveEndpointHealthEventQueue(events: List<EndpointHealthEvent>)
    fun updateEndpointHealthEventQueue(transform: (List<EndpointHealthEvent>) -> List<EndpointHealthEvent>)
    fun loadEndpointHealthLastHeartbeatAtMillis(): Long
    fun saveEndpointHealthLastHeartbeatAtMillis(value: Long)
}

object EndpointHealthEvents {
    const val MAX_BATCH_SIZE = 50
    const val MAX_QUEUE_SIZE = 500
    const val HEARTBEAT_INTERVAL_MS = 60L * 60L * 1_000L

    fun scoreBucket(health: EndpointHealth?): EndpointScoreBucket =
        scoreBucket(health?.score ?: 70)

    fun scoreBucket(score: Int): EndpointScoreBucket =
        when (score.coerceIn(0, 100)) {
            in 80..100 -> EndpointScoreBucket.GOOD
            in 60..79 -> EndpointScoreBucket.OK
            in 30..59 -> EndpointScoreBucket.WEAK
            else -> EndpointScoreBucket.BAD
        }

    fun networkKindValue(networkKind: EndpointRankingPolicy.NetworkKind): String =
        when (networkKind) {
            EndpointRankingPolicy.NetworkKind.WIFI -> "wifi"
            EndpointRankingPolicy.NetworkKind.CELLULAR -> "cellular"
            EndpointRankingPolicy.NetworkKind.OTHER -> "other"
        }

    fun toRequestJson(events: List<EndpointHealthEvent>): JSONObject =
        JSONObject()
            .put("platform", com.noki.desktop.DesktopBuild.PLATFORM)
            .put(
                "events",
                JSONArray().apply {
                    events.take(MAX_BATCH_SIZE).mapNotNull(::normalized).forEach { event ->
                        put(event.toEventJson())
                    }
                },
            )

    fun encodeQueue(events: List<EndpointHealthEvent>): String =
        JSONArray().apply {
            events.takeLast(MAX_QUEUE_SIZE).mapNotNull(::normalized).forEach { event ->
                put(event.toEventJson())
            }
        }.toString()

    fun decodeQueue(raw: String?): List<EndpointHealthEvent> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val event = item.toEndpointHealthEvent() ?: continue
                    add(event)
                }
            }.takeLast(MAX_QUEUE_SIZE)
        }.getOrDefault(emptyList())
    }

    fun normalized(event: EndpointHealthEvent): EndpointHealthEvent? {
        val code = event.endpointCode.trim().take(64)
        if (code.isBlank()) return null
        return event.copy(endpointCode = code)
    }

    private fun EndpointHealthEvent.toEventJson(): JSONObject =
        JSONObject()
            .put("endpoint_code", endpointCode)
            .put("network_kind", networkKindValue(networkKind))
            .put("event_type", eventType.wireValue)
            .put("success", success)
            .put("slow", slow)
            .put("score_bucket", scoreBucket.wireValue)

    private fun JSONObject.toEndpointHealthEvent(): EndpointHealthEvent? {
        val code = optString("endpoint_code").trim().take(64)
        if (code.isBlank()) return null
        val networkKind = when (optString("network_kind")) {
            "wifi" -> EndpointRankingPolicy.NetworkKind.WIFI
            "cellular" -> EndpointRankingPolicy.NetworkKind.CELLULAR
            else -> EndpointRankingPolicy.NetworkKind.OTHER
        }
        val eventType = EndpointHealthEventType.fromWireValue(optString("event_type")) ?: return null
        val bucket = EndpointScoreBucket.fromWireValue(optString("score_bucket")) ?: return null
        return EndpointHealthEvent(
            endpointCode = code,
            networkKind = networkKind,
            eventType = eventType,
            success = optBoolean("success", false),
            slow = optBoolean("slow", false),
            scoreBucket = bucket,
        )
    }
}

private val endpointHealthFlushMutex = Mutex()

class EndpointHealthEventReporter(
    private val store: EndpointHealthEventStore,
    private val upload: suspend (token: String, events: List<EndpointHealthEvent>) -> Unit,
    private val nowMillis: () -> Long = { System.currentTimeMillis() },
    private val loadSettings: (() -> StoredSettings)? = null,
) {
    suspend fun recordEvent(
        settings: StoredSettings,
        event: EndpointHealthEvent,
    ) {
        val current = currentSettings(settings) ?: return
        if (settings.advancedSettings.ruRelayEnabled || settings.advancedSettings.multiHop.enabled ||
            current.advancedSettings.ruRelayEnabled || current.advancedSettings.multiHop.enabled) {
            flush(settings)
            return
        }
        if (!AppDiagnosticLogPolicy.shouldUploadAutomatically(current.advancedSettings)) {
            flush(settings)
            return
        }
        val normalized = EndpointHealthEvents.normalized(event) ?: return
        store.updateEndpointHealthEventQueue { queue ->
            val latest = currentSettings(settings)
            if (latest != null && !latest.advancedSettings.ruRelayEnabled && !latest.advancedSettings.multiHop.enabled &&
                AppDiagnosticLogPolicy.shouldUploadAutomatically(latest.advancedSettings)) {
                (queue + normalized).takeLast(EndpointHealthEvents.MAX_QUEUE_SIZE)
            } else queue
        }
        flush(settings)
    }

    suspend fun flush(settings: StoredSettings) {
        endpointHealthFlushMutex.withLock {
            val current = currentSettings(settings) ?: return@withLock
            if (!AppDiagnosticLogPolicy.shouldUploadAutomatically(current.advancedSettings)) {
                store.updateEndpointHealthEventQueue { queue ->
                    val latest = currentSettings(settings)
                    if (latest != null && !AppDiagnosticLogPolicy.shouldUploadAutomatically(latest.advancedSettings)) {
                        emptyList()
                    } else queue
                }
                return@withLock
            }
            val token = current.backendAccessToken?.takeIf { it.isNotBlank() } ?: return@withLock
            val queue = store.loadEndpointHealthEventQueue()
            if (queue.isEmpty()) return@withLock
            val batch = queue.take(EndpointHealthEvents.MAX_BATCH_SIZE)
            val latest = currentSettings(settings) ?: return@withLock
            if (!AppDiagnosticLogPolicy.shouldUploadAutomatically(latest.advancedSettings)) return@withLock
            try {
                upload(token, batch)
                store.updateEndpointHealthEventQueue { current ->
                    if (currentSettings(settings) != null && current.take(batch.size) == batch) {
                        current.drop(batch.size)
                    } else current
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {}
        }
    }

    suspend fun recordHeartbeatIfDue(
        settings: StoredSettings,
        endpointCode: String,
        networkKind: EndpointRankingPolicy.NetworkKind,
        health: EndpointHealth?,
    ) {
        val current = currentSettings(settings) ?: return
        if (settings.advancedSettings.ruRelayEnabled || settings.advancedSettings.multiHop.enabled ||
            current.advancedSettings.ruRelayEnabled || current.advancedSettings.multiHop.enabled) return
        if (!AppDiagnosticLogPolicy.shouldUploadAutomatically(current.advancedSettings)) {
            flush(settings)
            return
        }
        val now = nowMillis()
        val last = store.loadEndpointHealthLastHeartbeatAtMillis()
        if (last > 0L && now - last < EndpointHealthEvents.HEARTBEAT_INTERVAL_MS) return
        store.saveEndpointHealthLastHeartbeatAtMillis(now)
        recordEvent(
            settings = settings,
            event = EndpointHealthEvent(
                endpointCode = endpointCode,
                networkKind = networkKind,
                eventType = EndpointHealthEventType.HEARTBEAT,
                success = true,
                slow = false,
                scoreBucket = EndpointHealthEvents.scoreBucket(health),
            ),
        )
    }

    private fun currentSettings(expected: StoredSettings): StoredSettings? =
        (loadSettings?.invoke() ?: expected).takeIf { it.hasSameAuthSessionAs(expected) }
}
