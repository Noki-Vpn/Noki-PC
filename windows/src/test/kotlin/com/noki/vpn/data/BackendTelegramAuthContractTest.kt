package com.noki.vpn.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackendTelegramAuthContractTest {
    @Test
    fun `Telegram native transport payloads contain PKCE material only`() {
        val start = BackendTelegramAuthContract.nativeStartPayload("A".repeat(43), "S".repeat(43))
        val token = BackendTelegramAuthContract.nativeTokenPayload(
            code = "authorization-code",
            codeVerifier = "B".repeat(43),
        )

        assertEquals(setOf("code_challenge", "client_state"), start.keys().asSequence().toSet())
        assertEquals("A".repeat(43), start.getString("code_challenge"))
        assertEquals("S".repeat(43), start.getString("client_state"))
        assertEquals(setOf("code", "code_verifier"), token.keys().asSequence().toSet())
        assertEquals("authorization-code", token.getString("code"))
        assertEquals("B".repeat(43), token.getString("code_verifier"))
        assertEquals(
            "tg://resolve?domain=oauth&startapp=opaque",
            BackendTelegramAuthContract.parseTelegramUrl(
                JSONObject().put(
                    "telegram_url",
                    "tg://resolve?domain=oauth&startapp=opaque",
                ),
            ),
        )
        assertEquals(
            "opaque-id-token",
            BackendTelegramAuthContract.parseIdToken(
                JSONObject().put("id_token", "opaque-id-token"),
            ),
        )
    }

    @Test
    fun `Telegram browser transport contains only challenge or opaque state with verifier`() {
        val start = BackendTelegramAuthContract.nativeStartPayload("C".repeat(43), "T".repeat(43))
        val token = BackendTelegramAuthContract.browserTokenPayload(
            state = "S".repeat(43),
            codeVerifier = "D".repeat(43),
        )

        assertEquals(setOf("code_challenge", "client_state"), start.keys().asSequence().toSet())
        assertEquals("T".repeat(43), start.getString("client_state"))
        assertEquals(setOf("state", "code_verifier"), token.keys().asSequence().toSet())
        assertEquals("S".repeat(43), token.getString("state"))
        assertEquals("D".repeat(43), token.getString("code_verifier"))
        assertEquals(
            "https://oauth.telegram.org/auth?state=opaque",
            BackendTelegramAuthContract.parseAuthorizationUrl(
                JSONObject().put(
                    "authorization_url",
                    "https://oauth.telegram.org/auth?state=opaque",
                ),
            ),
        )
    }

    @Test
    fun `Telegram login payload contains opaque token and optional device id only`() {
        val payload = BackendTelegramAuthContract.loginPayload(
            idToken = "opaque-telegram-token",
            deviceId = "device-1",
        )

        assertEquals("opaque-telegram-token", payload.getString("id_token"))
        assertEquals("device-1", payload.getString("device_id"))
        assertEquals(setOf("id_token", "device_id"), payload.keys().asSequence().toSet())
    }

    @Test
    fun `bootstrap user parser maps account security capabilities`() {
        val user = BackendUserResponseParser.parse(
            JSONObject(
                """
                {
                  "id":"user-1",
                  "username":"telegram_user",
                  "email":"tg_12345678901234@a.noki",
                  "avatar_url":null,
                  "is_active":true,
                  "is_admin":false,
                  "has_real_email":false,
                  "has_password":false,
                  "telegram_linked":true
                }
                """.trimIndent(),
            ),
        )

        assertFalse(user.hasRealEmail)
        assertFalse(user.hasPassword)
        assertTrue(user.telegramLinked)
    }
}
