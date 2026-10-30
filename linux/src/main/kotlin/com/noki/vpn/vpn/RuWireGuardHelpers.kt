package com.noki.vpn.vpn
import com.noki.vpn.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
suspend fun measureRuRelayRtt(
    catalog: RuRelayCatalog,
    probe: suspend (RuRelayCatalogEntry) -> Int?,
): List<RuRelayRttSample> = coroutineScope {
    val slots = Semaphore(6)
    val targets = catalog.relays.filter { it.available && it.pairReady && it.probeHost != null && it.probePort != null }.take(32)
    val deadlineNanos = System.nanoTime() + 2_500_000_000L
    val samples = targets.map { relay ->
            async {
                val remainingMillis = ((deadlineNanos - System.nanoTime()) / 1_000_000L).coerceAtLeast(0)
                withTimeoutOrNull(remainingMillis) { slots.withPermit {
                    val rtt = probe(relay)
                    rtt?.takeIf { it in 1..3000 }?.let { RuRelayRttSample(relay.id, it) }
                } }
            }
        }.awaitAll().filterNotNull()
    samples
}

suspend fun awaitRuWireGuardReady(
    initial: BackendRuWireGuardSession,
    poll: suspend () -> BackendRuWireGuardSession,
    pause: suspend () -> Unit = { delay(2_000) },
    timeoutMillis: Long = 30_000L,
): BackendRuWireGuardSession.Ready = withTimeout(timeoutMillis.coerceAtLeast(1)) {
    var response = initial
    repeat(15) {
        if (response is BackendRuWireGuardSession.Ready) return@withTimeout response
        pause()
        response = poll()
        check(response.identity.hasSameAuthorization(initial.identity)) { "ru_wireguard_session_changed" }
    }
    response as? BackendRuWireGuardSession.Ready
        ?: throw IllegalStateException("ru_wireguard_session_timeout")
}
