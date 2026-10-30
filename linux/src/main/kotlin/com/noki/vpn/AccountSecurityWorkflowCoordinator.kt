package com.noki.vpn

internal class AccountSecurityWorkflowCoordinator {
    private var generation = 0L

    fun begin(): Long = ++generation

    fun accepts(owner: Long): Boolean = generation == owner

    fun invalidate() {
        generation += 1L
    }
}
