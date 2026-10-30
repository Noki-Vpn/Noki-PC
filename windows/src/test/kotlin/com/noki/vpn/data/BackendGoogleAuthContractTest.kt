package com.noki.vpn.data

import org.junit.Assert.assertEquals
import org.junit.Test

class BackendGoogleAuthContractTest {
    @Test
    fun `Google login payload contains opaque token and optional device id only`() {
        val payload = BackendGoogleAuthContract.loginPayload(
            idToken = "opaque-google-token",
            deviceId = "device-1",
        )

        assertEquals("opaque-google-token", payload.getString("id_token"))
        assertEquals("device-1", payload.getString("device_id"))
        assertEquals(setOf("id_token", "device_id"), payload.keys().asSequence().toSet())
    }
}
