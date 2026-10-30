package com.noki.vpn.data

interface VpnSessionApi {
    suspend fun serverLocations(token: String, deviceId: String?, deviceKey: String?): List<BackendLocation>
    suspend fun vpnAccess(
        token: String,
        deviceId: String?,
        deviceKey: String?,
    ): BackendVpnAccess

    suspend fun registerDevice(
        token: String,
        deviceKey: String?,
        deviceId: String?,
        deviceName: String,
        publicKey: String,
        deviceClaims: List<String>,
        platform: String,
    ): BackendDevice

    suspend fun createDeviceChallenge(
        token: String,
        deviceId: String,
    ): BackendDeviceChallenge

    suspend fun createVpnSession(
        token: String,
        deviceId: String,
        deviceKey: String?,
        deviceNonce: String,
        deviceSignature: String,
        countryCode: String?,
        locationCode: String?,
        excludeLocationCode: String?,
        profileCode: String,
        nodeId: String? = null,
        multiHop: MultiHopSettings? = null,
    ): BackendVpnSession

    suspend fun createRuWireGuardSession(
        token: String,
        request: RuWireGuardSessionRequest,
    ): BackendRuWireGuardSession
}

data class VpnSessionSelection(
    val countryCode: String,
    val locationCode: String? = null,
    val excludeLocationCode: String? = null,
    val nodeId: String? = null,
    val excludedNodeIds: Set<String> = emptySet(),
)

interface TemporaryVpnApi {
    suspend fun createTemporaryVpnChallenge(
        publicKey: String,
        deviceKey: String,
        deviceName: String,
        platform: String,
    ): BackendTemporaryVpnChallenge

    suspend fun createTemporaryVpnSession(
        publicKey: String,
        nonce: String,
        signature: String,
        deviceKey: String,
    ): BackendTemporaryVpnSession

    suspend fun revokeTemporaryVpnSession(
        sessionId: String,
        controlToken: String,
    )
}
