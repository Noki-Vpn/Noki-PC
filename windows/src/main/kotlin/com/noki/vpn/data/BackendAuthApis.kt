package com.noki.vpn.data

interface AuthFlowApi {
    suspend fun login(
        email: String,
        password: String,
        deviceId: String? = null,
    ): BackendAuthTokens

    suspend fun register(
        username: String,
        email: String,
        password: String,
        verificationCode: String,
    )
}

interface TelegramAuthApi {
    suspend fun telegramLogin(
        idToken: String,
        deviceId: String? = null,
    ): BackendAuthTokens
}

interface GoogleAuthApi {
    suspend fun googleLogin(
        idToken: String,
        deviceId: String? = null,
    ): BackendAuthTokens
}

interface TelegramOidcApi {
    suspend fun startTelegramOidc(codeChallenge: String, clientState: String): String

    suspend fun startTelegramBrowserOidc(codeChallenge: String, clientState: String): String

    suspend fun exchangeTelegramOidcCode(
        code: String,
        codeVerifier: String,
    ): String

    suspend fun exchangeTelegramBrowserOidc(
        state: String,
        codeVerifier: String,
    ): String
}

interface AuthRefreshApi {
    suspend fun refreshAuthToken(
        refreshToken: String,
        deviceId: String?,
        requestId: String?,
    ): BackendAuthTokens

    suspend fun revokeRefreshToken(refreshToken: String) = Unit
}

interface PasswordRecoveryApi {
    suspend fun sendPasswordRecoveryCode(email: String): Int

    suspend fun verifyPasswordRecoveryCode(
        email: String,
        verificationCode: String,
    )

    suspend fun resetPassword(
        email: String,
        verificationCode: String,
        newPassword: String,
    )
}

interface AccountApi {
    suspend fun deleteAccount(token: String)
}

interface AccountSecurityApi {
    suspend fun sendAccountEmailCode(
        token: String,
        email: String,
        currentDeviceId: String?,
        currentDeviceKey: String?,
        currentEmail: String?,
        currentVerificationCode: String?,
    ): Int

    suspend fun changeAccountEmail(
        token: String,
        email: String,
        verificationCode: String,
        currentDeviceId: String?,
        currentDeviceKey: String?,
        currentEmail: String?,
        currentVerificationCode: String?,
    ): BackendUser

    suspend fun changeAccountPassword(
        token: String,
        currentPassword: String?,
        newPassword: String,
        currentDeviceId: String?,
        currentDeviceKey: String?,
    ): BackendAccountPasswordChange

    suspend fun changeAccountUsername(
        token: String,
        username: String,
        currentDeviceId: String?,
        currentDeviceKey: String?,
    ): BackendUser

    suspend fun linkTelegramAccount(
        token: String,
        idToken: String,
        currentDeviceId: String?,
        currentDeviceKey: String?,
    ): BackendUser

    suspend fun unlinkTelegramAccount(
        token: String,
        currentDeviceId: String?,
        currentDeviceKey: String?,
    ): BackendUser
}
