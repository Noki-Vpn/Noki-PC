package com.noki.vpn.data

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackendRetryPolicyTest {
    @Test
    fun classifiesOnlyTemporaryFailuresAsRetryable() {
        assertTrue(BackendRetryPolicy.isTransient(IOException("offline")))
        assertTrue(BackendRetryPolicy.isTransient(BackendException("rate", 429)))
        assertTrue(BackendRetryPolicy.isTransient(BackendException("busy", 500)))
        assertFalse(BackendRetryPolicy.isTransient(BackendException("auth", 401)))
        assertFalse(BackendRetryPolicy.isTransient(IllegalArgumentException("bad payload")))
    }

    @Test
    fun serverRetryAfterWinsAndIsCapped() {
        assertEquals(
            5_000L,
            BackendRetryPolicy.delayMillis(BackendException("rate", 429, 5_000L), 0) { 1L },
        )
        assertEquals(
            30_000L,
            BackendRetryPolicy.delayMillis(BackendException("rate", 429, 90_000L), 0) { 1L },
        )
    }

    @Test
    fun localBackoffUsesInjectedJitter() {
        assertEquals(450L, BackendRetryPolicy.delayMillis(IOException("offline"), 0) { it / 2 })
        assertEquals(900L, BackendRetryPolicy.delayMillis(IOException("offline"), 1) { it / 2 })
        assertEquals(1_800L, BackendRetryPolicy.delayMillis(IOException("offline"), 2) { it / 2 })
    }

    @Test
    fun parsesNumericRetryAfterSeconds() {
        assertEquals(5_000L, BackendRetryPolicy.parseRetryAfterMillis("5"))
        assertEquals(null, BackendRetryPolicy.parseRetryAfterMillis("not-a-number"))
    }
}
