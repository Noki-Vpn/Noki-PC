package com.noki.vpn.vpn

object VpnReadinessPolicy {
    enum class Failure(val code: String) {
        CoreStart("core_start_error"),
        Probe("runtime_readiness_error"),
    }

    fun accept(delayMs: Long?): Boolean = delayMs != null && delayMs > 0L

    fun failureReason(started: Boolean, delayMs: Long?): Failure? = when {
        !started -> Failure.CoreStart
        !accept(delayMs) -> Failure.Probe
        else -> null
    }
}
