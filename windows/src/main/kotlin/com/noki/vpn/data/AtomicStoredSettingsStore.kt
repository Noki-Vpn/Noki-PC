package com.noki.vpn.data

data class AuthRefreshCommitResult(
    val settings: StoredSettings,
    val committed: Boolean,
)

data class AuthSessionCommitResult(
    val settings: StoredSettings,
    val previousRefreshTokenStaged: Boolean,
)

internal interface AtomicStoredSettingsStore {
    fun load(): StoredSettings

    fun <R> updateAndReturn(transform: (StoredSettings) -> Pair<StoredSettings, R>): R

    fun updateSettings(transform: (StoredSettings) -> StoredSettings): StoredSettings =
        updateAndReturn { current -> transform(current).let { it to it } }
}

internal interface AtomicAuthSettingsStore : AtomicStoredSettingsStore {
    fun clearAuthAndStageRefreshToken(): StoredSettings

    fun commitRefreshedAuth(
        expectedSession: StoredSettings,
        tokens: BackendAuthTokens,
        refreshToken: String,
    ): AuthRefreshCommitResult

    fun replaceAuthAndStagePreviousRefreshToken(tokens: BackendAuthTokens): AuthSessionCommitResult
}

internal fun StoredSettings.hasSameAuthSessionAs(other: StoredSettings): Boolean =
    isAuthenticated == other.isAuthenticated &&
        backendAccessToken == other.backendAccessToken &&
        backendRefreshToken == other.backendRefreshToken &&
        backendAccessTokenExpiresInSeconds == other.backendAccessTokenExpiresInSeconds &&
        backendRefreshExpiresAt == other.backendRefreshExpiresAt &&
        backendDeviceId == other.backendDeviceId
