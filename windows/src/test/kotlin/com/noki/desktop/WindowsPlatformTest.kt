package com.noki.desktop

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import javax.crypto.KeyAgreement
import java.security.KeyFactory
import java.security.spec.NamedParameterSpec
import java.security.spec.XECPrivateKeySpec
import java.security.spec.XECPublicKeySpec
import java.math.BigInteger
import java.util.Base64
import com.noki.vpn.AvatarCropRequest
import java.awt.Color
import java.awt.image.BufferedImage
import javax.imageio.ImageIO

class WindowsPlatformTest {
    @Test fun `startup task XML uses Windows UTF16 LE and preserves executable paths`() {
        val executable = java.nio.file.Path.of("C:/Apps/Ноки & VPN/Noki.exe")
        val sid = "S-1-5-21-100-200-300-1001"
        for (enabled in listOf(false, true)) {
            val bytes = WindowsShell.startupTaskXml(executable, sid, enabled)
            assertEquals(0xff, bytes[0].toInt() and 0xff)
            assertEquals(0xfe, bytes[1].toInt() and 0xff)
            val xml = javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(bytes.inputStream())
            assertEquals(executable.toString(), xml.getElementsByTagName("Command").item(0).textContent)
            assertEquals(executable.parent.toString(), xml.getElementsByTagName("WorkingDirectory").item(0).textContent)
            assertEquals(sid, xml.getElementsByTagName("UserId").item(0).textContent)
            assertEquals(enabled.toString(), xml.getElementsByTagName("Enabled").item(0).textContent)
        }
    }
    @Test fun `guard helper rejects a different Windows identity before touching storage or filters`() {
        val sid = WindowsNetworkGuard.currentUserSid()
        assertTrue(sid.startsWith("S-1-"))
        WindowsNetworkGuard.requireCurrentUser(sid)
        assertTrue(runCatching { WindowsNetworkGuard.requireCurrentUser("invalid-identity") }.isFailure)
        val directory = Files.createTempDirectory("noki-guard-identity")
        try {
            val untouched = directory.resolve("untouched")
            val error = runCatching { runNetworkGuard(arrayOf("on", untouched.toString(), "guard-result-test.json", "invalid-identity", "unused-core", "unused-client")) }.exceptionOrNull()
            assertTrue(error is IllegalStateException)
            assertFalse(Files.exists(untouched))
        } finally { Files.delete(directory) }
    }
    @Test fun `avatar export keeps crop rotation and clamps movement without blank edges`() {
        val sourcePixels = BufferedImage(200, 200, BufferedImage.TYPE_INT_RGB)
        sourcePixels.createGraphics().let { graphics ->
            try {
                graphics.color = Color.RED; graphics.fillRect(0, 0, 100, 100)
                graphics.color = Color.GREEN; graphics.fillRect(100, 0, 100, 100)
                graphics.color = Color.BLUE; graphics.fillRect(0, 100, 100, 100)
                graphics.color = Color.YELLOW; graphics.fillRect(100, 100, 100, 100)
            } finally { graphics.dispose() }
        }
        val source = sourcePixels.toSkiaTestImage()
        val crop = AvatarCropRequest("unused", 200f, 200f, 200f, 1f, 0f, 0f)
        val centered = croppedAvatar(source, crop)
        assertEquals(512, centered.width)
        assertEquals(Color.RED.rgb, centered.getRGB(64, 64))
        assertEquals(Color.YELLOW.rgb, centered.getRGB(448, 448))
        val rotated = croppedAvatar(source, crop.copy(rotationQuarterTurns = 1))
        assertEquals(Color.BLUE.rgb, rotated.getRGB(64, 64))
        assertEquals(Color.RED.rgb, rotated.getRGB(448, 64))
        val moved = croppedAvatar(source, crop.copy(cropScale = 2f, cropOffsetX = 100_000f, cropOffsetY = 100_000f))
        for (point in listOf(1 to 1, 510 to 1, 1 to 510, 510 to 510))
            assertNotEquals(Color.BLACK.rgb, moved.getRGB(point.first, point.second))
    }

    @Test fun `avatar file decoder bounds decoded dimensions and rejects oversized files`() {
        val directory = Files.createTempDirectory("noki-avatar-test")
        try {
            val image = BufferedImage(5000, 32, BufferedImage.TYPE_INT_RGB)
            for (format in listOf("png", "jpeg")) {
                val file = directory.resolve("wide.$format")
                assertTrue(ImageIO.write(image, format, file.toFile()))
                loadAvatarImage(file).use { decoded ->
                    assertEquals(1250, decoded.width)
                    assertEquals(8, decoded.height)
                    assertEquals(Color.BLACK.rgb, decoded.getRGB(0, 0))
                }
            }
            val file = directory.resolve("wide.png")
            val oversized = directory.resolve("too-large.png")
            Files.copy(file, oversized)
            java.io.RandomAccessFile(oversized.toFile(), "rw").use { it.setLength(5_000_000) }
            loadAvatarImage(oversized).close()
            java.io.RandomAccessFile(oversized.toFile(), "rw").use { it.setLength(5_000_001) }
            assertEquals("avatar_file_too_large", runCatching { loadAvatarImage(oversized) }.exceptionOrNull()?.message)
            image.flush()
        } finally { Files.list(directory).use { it.forEach(Files::delete) }; Files.delete(directory) }
    }

    @Test fun `avatar decoder rejects compressed pixel bombs before decoding and checks actual format`() {
        val directory = Files.createTempDirectory("noki-avatar-pixels")
        try {
            val file = directory.resolve("huge.png")
            val pixels = BufferedImage(8192, 8192, BufferedImage.TYPE_BYTE_BINARY)
            try { assertTrue(ImageIO.write(pixels, "png", file.toFile())) } finally { pixels.flush() }
            assertTrue(Files.size(file) < 5_000_000)
            assertEquals("avatar_dimensions_too_large", runCatching { loadAvatarImage(file) }.exceptionOrNull()?.message)
            val renamedGif = directory.resolve("actually-gif.png")
            val image = BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB)
            try { assertTrue(ImageIO.write(image, "gif", renamedGif.toFile())) } finally { image.flush() }
            assertEquals("avatar_format_unsupported", runCatching { loadAvatarImage(renamedGif) }.exceptionOrNull()?.message)
            assertTrue(avatarFileError(com.noki.vpn.data.AppLanguage.RU, IllegalArgumentException("avatar_dimensions_too_large")).contains("пиксел"))
            assertTrue(avatarFileError(com.noki.vpn.data.AppLanguage.EN, IllegalArgumentException("avatar_format_unsupported")).contains("PNG"))
        } finally { Files.list(directory).use { it.forEach(Files::delete) }; Files.delete(directory) }
    }

    @Test fun `WFP native structures match the Windows x64 ABI without changing filters`() {
        assertEquals(200, WindowsNetworkGuard.Filter().size())
        assertEquals(40, WindowsNetworkGuard.Condition().size())
        assertEquals(72, WindowsNetworkGuard.SubLayer().size())
        assertEquals(16, WindowsNetworkGuard.Value().size())
        assertEquals(16, WindowsNetworkGuard.Blob().size())
        for ((type, number) in listOf(1 to 6, 1 to 17, 2 to 53)) {
            val value = WindowsNetworkGuard.Value().apply {
                this.type = type
                this.value.setType(java.lang.Long.TYPE)
                this.value.number = number.toLong()
            }
            value.write()
            assertEquals(type, value.pointer.getInt(0))
            assertEquals(number, if (type == 1) value.pointer.getByte(8).toInt().and(0xff) else value.pointer.getShort(8).toInt().and(0xffff))
        }
        WindowsNetworkGuard().use { it.enabled() }
    }

    @Test fun `WFP security descriptor blob preserves the user SID through native decoding`() {
        val security = com.sun.jna.Native.load("advapi32", WindowsNetworkGuard.Security::class.java)
        val descriptor = com.sun.jna.ptr.PointerByReference()
        val length = com.sun.jna.ptr.IntByReference()
        val decoded = com.sun.jna.ptr.PointerByReference()
        val sid = WindowsNetworkGuard.currentUserSid()
        assertTrue(security.ConvertStringSecurityDescriptorToSecurityDescriptorW(
            com.sun.jna.WString("D:(A;;CC;;;$sid)"), 1, descriptor, length))
        try {
            val blob = WindowsNetworkGuard.Blob().apply { size = length.value; data = descriptor.value; write() }
            assertTrue(blob.pointer.getInt(0) > 0)
            val readDescriptor = blob.pointer.getPointer(8)
            assertEquals(descriptor.value, readDescriptor)
            val convert = com.sun.jna.Function.getFunction("advapi32", "ConvertSecurityDescriptorToStringSecurityDescriptorW", com.sun.jna.Function.ALT_CONVENTION)
            assertNotEquals(0, convert.invokeInt(arrayOf(readDescriptor, 1, 4, decoded, null)))
            assertTrue(decoded.value.getWideString(0).contains(sid))
            java.lang.ref.Reference.reachabilityFence(blob)
        } finally {
            decoded.value?.let { com.sun.jna.platform.win32.Kernel32.INSTANCE.LocalFree(it) }
            com.sun.jna.platform.win32.Kernel32.INSTANCE.LocalFree(descriptor.value)
        }
    }

    @Test fun `persisted raw WireGuard key pair matches X25519 scalar multiplication`() {
        val directory = Files.createTempDirectory("noki-wg-test")
        try {
            val first = WindowsVault(directory).wireGuardKeyPair()
            val restored = WindowsVault(directory).wireGuardKeyPair()
            assertArrayEquals(first.first, restored.first)
            assertEquals(first.second, restored.second)
            val factory = KeyFactory.getInstance("X25519")
            val key = factory.generatePrivate(XECPrivateKeySpec(NamedParameterSpec.X25519, first.first))
            val base = factory.generatePublic(XECPublicKeySpec(NamedParameterSpec.X25519, BigInteger.valueOf(9)))
            val agreement = KeyAgreement.getInstance("X25519").apply { init(key); doPhase(base, true) }
            assertArrayEquals(Base64.getDecoder().decode(first.second), agreement.generateSecret())
            first.first.fill(0); restored.first.fill(0)
        } finally { Files.list(directory).use { it.forEach(Files::delete) }; Files.delete(directory) }
    }

    @Test fun `geosite decoder preserves domain kinds and rejects missing and truncated input`() {
        fun varint(value: Int): ByteArray {
            var rest = value
            val output = java.io.ByteArrayOutputStream()
            while (rest >= 128) { output.write((rest and 127) or 128); rest = rest ushr 7 }
            output.write(rest)
            return output.toByteArray()
        }
        fun field(number: Int, bytes: ByteArray): ByteArray = varint((number shl 3) or 2) + varint(bytes.size) + bytes
        fun domain(kind: Int, name: String) = field(2, byteArrayOf(8, kind.toByte()) + field(2, name.toByteArray()))
        val russian = field(1, field(1, "category-ru".toByteArray()) + (0..70).fold(byteArrayOf()) { bytes, i -> bytes + domain(2, "resource$i.ru") })
        val input = russian + field(1, field(1, "test".toByteArray()) + domain(2, "example.com") + domain(3, "full.example.com"))
        val rules = com.noki.vpn.vpn.GeositeAsset.resolveDomains(input, setOf("geosite:test")).getValue("geosite:test")
        assertEquals("example.com", rules.getJSONArray("domain_suffix").getString(0))
        assertEquals("full.example.com", rules.getJSONArray("domain").getString(0))
        assertTrue(runCatching { com.noki.vpn.vpn.GeositeAsset.resolveDomains(input, setOf("geosite:absent")) }.isFailure)
        assertTrue(runCatching { com.noki.vpn.vpn.GeositeAsset.resolveDomains(input.copyOf(input.size - 1), setOf("geosite:test")) }.isFailure)
    }
}

internal fun BufferedImage.toSkiaTestImage(): org.jetbrains.skia.Image = java.io.ByteArrayOutputStream().let { bytes ->
    check(ImageIO.write(this, "png", bytes))
    org.jetbrains.skia.Image.makeFromEncoded(bytes.toByteArray())
}

internal fun org.jetbrains.skia.Image.getRGB(x: Int, y: Int): Int = org.jetbrains.skia.Bitmap().use { pixels ->
    check(pixels.allocN32Pixels(width, height)); check(readPixels(pixels)); pixels.getColor(x, y)
}
