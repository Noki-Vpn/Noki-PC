package com.noki.vpn.data

fun interface BackendBootstrapLoader {
    suspend fun bootstrap(
        token: String,
        deviceId: String?,
        deviceKey: String?,
    ): BootstrapPayload
}
