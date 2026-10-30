package com.noki.vpn.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.json.JSONObject

internal class BackendAuthClient(
    private val jsonApi: BackendJsonApi,
) : AuthFlowApi, TelegramAuthApi, GoogleAuthApi, TelegramOidcApi, AuthRefreshApi, PasswordRecoveryApi {

    override suspend fun register(
        username: String,
        email: String,
        password: String,
        verificationCode: String,
    ) {
        val payload = JSONObject()
            .put("username", username)
            .put("email", email)
            .put("password", password)
            .put("verification_code", verificationCode)
        postJson("/auth/register", payload)
    }

    suspend fun sendRegistrationCode(email: String): Int {
        val payload = JSONObject()
            .put("email", email)
            .put("purpose", "registration")
        return postJson("/auth/email-code/send", payload).optInt("cooldown_seconds", 60)
    }

    suspend fun verifyRegistrationCode(
        email: String,
        verificationCode: String,
    ) {
        val payload = JSONObject()
            .put("email", email)
            .put("purpose", "registration")
            .put("verification_code", verificationCode)
        postJson("/auth/email-code/verify", payload)
    }

    suspend fun checkRegistrationUsernameAvailable(username: String): Boolean = withContext(Dispatchers.IO) {
        val url = jsonApi.apiUrl("/auth/registration/username/check")
            .toHttpUrl()
            .newBuilder()
            .addQueryParameter("username", username)
            .build()
        val request = Request.Builder()
            .url(url)
            .get()
            .build()
        jsonApi.execute(request).optBoolean("available", false)
    }

    override suspend fun sendPasswordRecoveryCode(email: String): Int {
        val payload = JSONObject()
            .put("email", email)
            .put("purpose", "password_recovery")
        return postJson("/auth/email-code/send", payload).optInt("cooldown_seconds", 60)
    }

    override suspend fun verifyPasswordRecoveryCode(
        email: String,
        verificationCode: String,
    ) {
        val payload = JSONObject()
            .put("email", email)
            .put("purpose", "password_recovery")
            .put("verification_code", verificationCode)
        postJson("/auth/email-code/verify", payload)
    }

    override suspend fun resetPassword(
        email: String,
        verificationCode: String,
        newPassword: String,
    ) {
        val payload = JSONObject()
            .put("email", email)
            .put("verification_code", verificationCode)
            .put("new_password", newPassword)
        postJson("/auth/password-recovery/reset", payload)
    }

    override suspend fun login(
        email: String,
        password: String,
        deviceId: String?,
    ): BackendAuthTokens {
        val payload = JSONObject()
            .put("email", email)
            .put("password", password)
        deviceId?.takeIf { it.isNotBlank() }?.let { payload.put("device_id", it) }
        val response = postJson("/auth/login", payload)
        return response.toBackendAuthTokens()
    }

    override suspend fun telegramLogin(
        idToken: String,
        deviceId: String?,
    ): BackendAuthTokens {
        val response = postJson(
            path = "/auth/telegram/login",
            payload = BackendTelegramAuthContract.loginPayload(idToken, deviceId),
        )
        return BackendTelegramAuthContract.parseTokens(response)
    }

    override suspend fun googleLogin(
        idToken: String,
        deviceId: String?,
    ): BackendAuthTokens {
        val response = postJson(
            path = "/auth/google/login",
            payload = BackendGoogleAuthContract.loginPayload(idToken, deviceId),
        )
        return BackendGoogleAuthContract.parseTokens(response)
    }

    override suspend fun startTelegramOidc(codeChallenge: String, clientState: String): String =
        BackendTelegramAuthContract.parseTelegramUrl(
            postJson(
                path = "/auth/telegram/native/start",
                payload = BackendTelegramAuthContract.nativeStartPayload(codeChallenge, clientState),
            ),
        )

    override suspend fun startTelegramBrowserOidc(codeChallenge: String, clientState: String): String =
        BackendTelegramAuthContract.parseAuthorizationUrl(
            postJson(
                path = "/auth/telegram/browser/start",
                payload = BackendTelegramAuthContract.nativeStartPayload(codeChallenge, clientState),
            ),
        )

    override suspend fun exchangeTelegramOidcCode(
        code: String,
        codeVerifier: String,
    ): String = BackendTelegramAuthContract.parseIdToken(
        postJson(
            path = "/auth/telegram/native/token",
            payload = BackendTelegramAuthContract.nativeTokenPayload(code, codeVerifier),
        ),
    )

    override suspend fun exchangeTelegramBrowserOidc(
        state: String,
        codeVerifier: String,
    ): String = BackendTelegramAuthContract.parseIdToken(
        postJson(
            path = "/auth/telegram/browser/token",
            payload = BackendTelegramAuthContract.browserTokenPayload(state, codeVerifier),
        ),
    )

    override suspend fun refreshAuthToken(
        refreshToken: String,
        deviceId: String?,
        requestId: String?,
    ): BackendAuthTokens {
        val payload = JSONObject()
            .put("refresh_token", refreshToken)
        deviceId?.takeIf { it.isNotBlank() }?.let { payload.put("device_id", it) }
        requestId?.takeIf { it.isNotBlank() }?.let { payload.put("request_id", it) }
        return postJson("/auth/refresh", payload).toBackendAuthTokens()
    }

    override suspend fun revokeRefreshToken(refreshToken: String) {
        postJson(
            "/auth/logout",
            JSONObject().put("refresh_token", refreshToken),
        )
    }


    private suspend fun postJson(path: String, payload: JSONObject): JSONObject =
        jsonApi.post(path, payload)
}
