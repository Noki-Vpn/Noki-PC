package com.noki.vpn.vpn

import com.noki.vpn.data.NokiBackendConfig

object VpnProbePlanPolicy {
    const val PER_TARGET_TIMEOUT_MILLIS = 4_000L
    const val TOTAL_TIMEOUT_MILLIS = 10_000L
    const val STARTUP_PER_TARGET_TIMEOUT_MILLIS = 1_500L
    const val STARTUP_TOTAL_TIMEOUT_MILLIS = 3_000L

    data class ProbeTarget(
        val key: String,
        val url: String,
    )

    private val targets = listOf(
        ProbeTarget(
            key = "api",
            url = NokiBackendConfig.backendProbeHealthUrl,
        ),
        ProbeTarget(
            key = "cloudflare",
            url = "https://cp.cloudflare.com/generate_204",
        ),
        ProbeTarget(
            key = "gstatic",
            url = "https://www.gstatic.com/generate_204",
        ),
    )

    @Suppress("UNUSED_PARAMETER")
    fun connectedTargets(recovery: Boolean): List<ProbeTarget> {
        return targets
    }

    fun candidateTargets(): List<ProbeTarget> = targets
}
