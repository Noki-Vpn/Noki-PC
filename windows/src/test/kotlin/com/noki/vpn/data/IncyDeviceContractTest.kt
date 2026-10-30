package com.noki.vpn.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class IncyDeviceContractTest {
    @Test
    fun `accepts only official incy crypt1 links`() {
        val value = "incy://crypt1/AAECAwQFBgcICQoLNyIQL3rDwRZqnyoD8pGKSLXP6o8NdSXQVSSALNbbUyIr__tWGFUexdIfKvvmDnuDGbmBvuppfNef6aKNZUwOm4c-Sg"
        assertEquals(value, IncyImportLink.parse(value).value)
        assertThrows(IllegalArgumentException::class.java) { IncyImportLink.parse("https://example.test/sub") }
        assertThrows(IllegalArgumentException::class.java) { IncyImportLink.parse("vless://secret") }
        assertThrows(IllegalArgumentException::class.java) { IncyImportLink.parse("incy://add/secret") }
        assertThrows(IllegalArgumentException::class.java) { IncyImportLink.parse("incy://crypt1/short") }
        assertThrows(IllegalArgumentException::class.java) { IncyImportLink.parse("incy://crypt1/abc=") }
    }
}
