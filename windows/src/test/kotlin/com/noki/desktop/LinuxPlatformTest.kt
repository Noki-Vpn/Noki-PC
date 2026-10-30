package com.noki.desktop

import org.junit.Assert.*
import org.junit.Test

class LinuxPlatformTest {
    @Test fun coreIdentityRejectsMissingProcessesAndReusedPids() {
        assertFalse(LinuxPlatform.coreMatches(Int.MAX_VALUE, 1))
        assertFalse(LinuxPlatform.coreMatches(ProcessHandle.current().pid().toInt(), -1))
    }

    @Test fun physicalLatencyNeverMeasuresTheTunnelAsPhysicalNetwork() {
        assertNull(LinuxPlatform.tcpLatency("invalid.example", 443, 500, 123, tunnelPresent = true))
    }
    @Test fun resolverFileNeverReusesManagedOrLoopbackDns() {
        assertEquals(listOf("192.168.0.1"), LinuxPlatform.resolverAddresses(
            "# nameserver 8.8.8.8\nnameserver 127.0.0.53\nnameserver ::1\nnameserver 172.19.0.2\nnameserver\t192.168.0.1 # router\nnameserver host.example\n"))
    }
    @Test fun encryptedVaultAuthenticatesKeyAndCiphertext() {
        val key = ByteArray(32) { it.toByte() }
        val clear = "private account token".toByteArray()
        val first = LinuxPlatform.crypt(clear, key, true)
        val second = LinuxPlatform.crypt(clear, key, true)
        assertFalse(first.contentEquals(second))
        assertArrayEquals(clear, LinuxPlatform.crypt(first, key, false))
        assertThrows(Exception::class.java) { LinuxPlatform.crypt(first, ByteArray(32), false) }
        first[first.lastIndex] = (first.last().toInt() xor 1).toByte()
        assertThrows(Exception::class.java) { LinuxPlatform.crypt(first, key, false) }
        assertThrows(IllegalArgumentException::class.java) { LinuxPlatform.crypt(byteArrayOf(1), key, false) }
    }
}
