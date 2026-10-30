package com.noki.vpn.data
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.*

class ClientLatencySampler(
    private val tcpConnect: (host: String, port: Int, timeoutMs: Int) -> Int? = DeviceLatency::measureTcpConnectMs,
    private val icmpPing: (host: String, count: Int, timeoutSeconds: Int) -> Int? = DeviceLatency::measureIcmpPingMs,
    private val locationTimeoutMillis: Long = 8_000L,
    private val tcpAttempts: Int = LATENCY_TCP_ATTEMPTS,
) {

    suspend fun measure(
        locations: List<ServerLocation>,
        relayTargets: Map<String, List<Pair<String, Int>>>? = null,
    ): Map<String, Int> = measureTargets(locations, relayTargets, startup = false)

    suspend fun measureForStartup(
        locations: List<ServerLocation>,
        relayTargets: Map<String, List<Pair<String, Int>>>? = null,
    ): Map<String, Int> = measureTargets(locations, relayTargets, startup = true)

    private suspend fun measureTargets(
        locations: List<ServerLocation>,
        relayTargets: Map<String, List<Pair<String, Int>>>?,
        startup: Boolean,
    ): Map<String, Int> = coroutineScope {
        val deadlineNanos = System.nanoTime() + 750_000_000L
        val probeSlots = Semaphore(DeviceLatency.MAX_CONCURRENT_DNS_LOOKUPS)
        locations
            .asSequence()
            .filter { location -> location.isOnline }
            .flatMap { location ->
                if (location.servers.isEmpty()) sequenceOf(Triple(ServerLocationMapper.latencyTargetKey(location), location.host, 443))
                else location.servers.asSequence().filter { it.isOnline }.map { Triple(ServerLocationMapper.latencyTargetKey(it), it.host, it.probePort) }
            }
            .distinctBy { it.first }
            .flatMap { target ->
                if (relayTargets == null) sequenceOf(target)
                else relayTargets[target.first].orEmpty().asSequence()
                    .map { (host, port) -> Triple(target.first, host, port) }
            }
            .distinct()
            .mapNotNull { (key, rawHost, port) ->
                val targetKey = key ?: return@mapNotNull null
                val host = rawHost.trim()
                async {
                    fun remainingMillis() = ((deadlineNanos - System.nanoTime()) / 1_000_000L).coerceAtLeast(0)
                    val budget = if (startup) remainingMillis() else locationTimeoutMillis.coerceAtLeast(1L)
                    val latency = withTimeoutOrNull(budget) {
                        probeSlots.withPermit {
                            runInterruptible(Dispatchers.IO) {
                                if (port == null) null else {
                                    val samples = List(if (startup) 1 else tcpAttempts.coerceIn(1, LATENCY_TCP_ATTEMPTS)) {
                                        val timeout = if (startup) minOf(LATENCY_TCP_TIMEOUT_MS.toLong(), remainingMillis()).toInt() else LATENCY_TCP_TIMEOUT_MS
                                        if (timeout > 0) tcpConnect(host, port, timeout) else null
                                    }.filterNotNull().sorted()
                                    samples.getOrNull(samples.size / 2)
                                        ?: if (!startup && relayTargets == null) {
                                            icmpPing(host, LATENCY_ICMP_COUNT, LATENCY_ICMP_TIMEOUT_SECONDS)
                                        } else null
                                }
                            }
                        }
                    }
                    latency?.let { targetKey to it }
                }
            }
            .toList()
            .awaitAll()
            .filterNotNull()
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, samples) -> samples.min() }
    }

    private companion object {
        const val LATENCY_TCP_ATTEMPTS = 3
        const val LATENCY_TCP_TIMEOUT_MS = 650
        const val LATENCY_ICMP_COUNT = 1
        const val LATENCY_ICMP_TIMEOUT_SECONDS = 1
    }
}
