package com.noki.vpn.data
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.*

class ClientLatencySampler(
    private val tcpConnect: (host: String, port: Int, timeoutMs: Int) -> Int? = DeviceLatency::measureTcpConnectMs,
    private val icmpPing: (host: String, count: Int, timeoutSeconds: Int) -> Int? = DeviceLatency::measureIcmpPingMs,
    private val locationTimeoutMillis: Long = 8_000L,
    private val tcpAttempts: Int = LATENCY_TCP_ATTEMPTS,
    private val batchProbe: ((List<ProbeTarget>, Boolean) -> Map<String, Int>)? = null,
) {
    data class ProbeTarget(val key: String, val host: String, val port: Int?, val probeIp: String? = null)

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
        val targets = locations
            .asSequence()
            .filter { location -> location.isOnline }
            .flatMap { location ->
                if (location.servers.isEmpty()) sequenceOf(ServerLocationMapper.latencyTargetKey(location)?.let { ProbeTarget(it, location.host, 443) })
                else location.servers.asSequence().filter { it.isOnline }.map { node -> ServerLocationMapper.latencyTargetKey(node)?.let {
                    ProbeTarget(it, node.probeHost ?: node.host, node.probePort, node.probeIp)
                } }
            }
            .filterNotNull()
            .distinctBy { it.key }
            .flatMap { target ->
                if (relayTargets == null) sequenceOf(target)
                else relayTargets[target.key].orEmpty().asSequence()
                    .map { (host, port) -> ProbeTarget(target.key, host, port) }
            }
            .distinct()
            .take(64)
            .toList()
        batchProbe?.let { measure ->
            // The privileged batch owns the socket deadline; allow bounded IPC startup/teardown too.
            val samples = withTimeoutOrNull(if (startup) 1_500L else locationTimeoutMillis + 1_000L) {
                runInterruptible(Dispatchers.IO) { measure(targets, startup) }
            }.orEmpty()
            val keys = targets.mapTo(mutableSetOf()) { it.key }
            return@coroutineScope samples.filter { (key, latency) -> key in keys && latency in 1..3000 }
        }
        targets.map { (targetKey, rawHost, port, probeIp) ->
                val host = rawHost.trim()
                async {
                    fun remainingMillis() = ((deadlineNanos - System.nanoTime()) / 1_000_000L).coerceAtLeast(0)
                    val budget = if (startup) remainingMillis() else locationTimeoutMillis.coerceAtLeast(1L)
                    val latency = withTimeoutOrNull(budget) {
                        probeSlots.withPermit {
                            runInterruptible(Dispatchers.IO) {
                                if (port == null && probeIp == null) null else {
                                    val samples = List(if (startup) 1 else tcpAttempts.coerceIn(1, LATENCY_TCP_ATTEMPTS)) {
                                        val timeout = if (startup) minOf(LATENCY_TCP_TIMEOUT_MS.toLong(), remainingMillis()).toInt() else LATENCY_TCP_TIMEOUT_MS
                                        if (timeout <= 0) null else {
                                            val tcp = port?.let { tcpConnect(host, it, timeout) }
                                            tcp ?: probeIp?.let { icmpPing(it, 1, 1) }
                                        }
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
