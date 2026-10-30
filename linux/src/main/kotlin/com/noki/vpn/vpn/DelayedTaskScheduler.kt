package com.noki.vpn.vpn

internal interface DelayedTaskScheduler {
    fun schedule(owner: Any, delayMillis: Long, task: () -> Unit)

    fun cancel(owner: Any)
}
