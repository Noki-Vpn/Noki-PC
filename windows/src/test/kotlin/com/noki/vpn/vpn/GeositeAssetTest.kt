package com.noki.vpn.vpn
import org.junit.Assert.*
import org.junit.Test
class GeositeAssetTest {
    @Test
    fun verifiedDigestDoesNotReuseMutableMatchesOrTrustChangedBytes() {
        val bytes = asset("CATEGORY-RU" to listOf(domain(2, "ru.example")), "SAMPLE" to listOf(domain(3, "sample.example")))
        assertTrue(GeositeAsset.isValid(bytes))
        val first = GeositeAsset.resolveDomains(bytes, setOf("geosite:sample")).getValue("geosite:sample")
        first.getJSONArray("domain").put("mutated.example")
        assertEquals("[\"sample.example\"]", GeositeAsset.resolveDomains(bytes, setOf("geosite:sample")).getValue("geosite:sample").getJSONArray("domain").toString())
        val invalid = asset("CATEGORY-RU" to listOf(domain(2, "ru.example")), "SAMPLE" to listOf(domain(4, "sample.example")))
        assertFalse(GeositeAsset.isValid(invalid))
        assertTrue(GeositeAsset.isValid(bytes))
    }
    @Test
    fun bundledRussianResourcesIncludeWholeTldsAndServicesOutsideRu() {
        val bytes = checkNotNull(javaClass.getResourceAsStream("/noki/vpn/geosite.dat")).use { it.readBytes() }
        val russian = GeositeAsset.resolveDomains(bytes, setOf("geosite:category-ru")).getValue("geosite:category-ru")
        val suffixes = russian.getJSONArray("domain_suffix").toList().toSet()
        assertTrue(suffixes.containsAll(listOf("ru", "su", "xn--p1ai", "moscow")))
        assertTrue(suffixes.containsAll(listOf("vk.com", "yandex.com", "yandex.pro", "koronapay.com", "sberbank.com", "ngenix.net")))
        assertTrue(russian.getJSONArray("domain").toList().contains("activation-v2.kaspersky.com"))
        assertTrue(suffixes.intersect(setOf("com", "net", "org", "google.com", "youtube.com", "telegram.org")).isEmpty())
        assertFalse(russian.has("ip_cidr"))
    }

    @Test
    fun unsupportedDomainTypeCannotReplaceTheVerifiedAsset() {
        assertFalse(GeositeAsset.isValid(asset("CATEGORY-RU" to listOf(domain(4, "ru.example")))))
    }

    @Test
    fun everyProtobufDomainTypeResolvesToItsNativeMeaning() {
        val bytes = asset("CATEGORY-RU" to listOf(
            domain(0, "keyword"), domain(1, "^Case[.]"), domain(2, "suffix.example"), domain(3, "exact.example"),
        ))
        val fields = GeositeAsset.resolveDomains(bytes, setOf("geosite:category-ru")).getValue("geosite:category-ru")
        assertEquals("[\"keyword\"]", fields.getJSONArray("domain_keyword").toString())
        assertEquals("[\"^Case[.]\"]", fields.getJSONArray("domain_regex").toString())
        assertEquals("[\"suffix.example\"]", fields.getJSONArray("domain_suffix").toString())
        assertEquals("[\"exact.example\"]", fields.getJSONArray("domain").toString())
    }

    @Test
    fun attributeConjunctionUsesKeyPresenceIncludingFalseAndZeroValues() {
        val both = listOf(attribute("cn", 2, 0), attribute("ads", 3, 0))
        val bytes = asset("CATEGORY-RU" to listOf(domain(2, "ru.example")), "SAMPLE" to listOf(
            domain(3, "both.example", both), domain(3, "one.example", listOf(attribute("cn", 2, 1))),
            domain(3, "neither.example"),
        ))
        val resolved = GeositeAsset.resolveDomains(bytes, setOf("geosite:sample@cn@ads", "geosite:sample@missing"))
        assertEquals("[\"both.example\"]", resolved.getValue("geosite:sample@cn@ads").getJSONArray("domain").toString())
        assertEquals(0, resolved.getValue("geosite:sample@missing").length())
    }

    @Test
    fun unknownTagsMalformedUtf8AndTruncatedLengthsFailClosed() {
        val bytes = asset("CATEGORY-RU" to listOf(domain(2, "ru.example")))
        assertThrows(IllegalArgumentException::class.java) { GeositeAsset.resolveDomains(bytes, setOf("geosite:unknown")) }
        assertThrows(IllegalArgumentException::class.java) { GeositeAsset.resolveDomains(bytes, setOf("geosite:category-ru@")) }
        val malformed = field(1, 2) + field(2, byteArrayOf(0xc3.toByte(), 0x28))
        assertFalse(GeositeAsset.isValid(asset("CATEGORY-RU" to listOf(malformed))))
        assertFalse(GeositeAsset.isValid(bytes.copyOf(bytes.size - 1)))
        assertFalse(GeositeAsset.isValid(bytes + byteArrayOf(0x0a, 0xff.toByte(), 0xff.toByte())))
    }

    @Test
    fun unselectedCategoriesStillValidateDomainTextAttributesAndWireBounds() {
        val malformedUtf8 = byteArrayOf(0xc3.toByte(), 0x28)
        val invalidDomains = listOf(
            domain(4, "unsupported.example"),
            domain(2, "\u00a0\u2003".repeat(300)),
            field(1, 2L),
            field(1, 2L) + field(2, 1L),
            field(1, 2L) + field(2, "x".repeat(600).toByteArray() + malformedUtf8),
            domain(2, "valid.example") + field(3, field(2, 0L)),
            domain(2, "valid.example") + field(3, field(1, malformedUtf8)),
            domain(2, "valid.example") + field(3, field(1, "\u2003".repeat(300).toByteArray())),
            domain(2, "valid.example") + field(3, field(1, "key".toByteArray()) + field(2, "wrong-wire".toByteArray())),
            domain(2, "valid.example") + byteArrayOf(0x1a, 0xff.toByte(), 0xff.toByte()),
        )
        for (invalid in invalidDomains) {
            val bytes = asset("CATEGORY-RU" to listOf(domain(2, "ru.example")), "UNSELECTED" to listOf(invalid))
            for (selectors in listOf(emptySet(), setOf("geosite:category-ru"), setOf("geosite:unselected"))) {
                assertThrows(Exception::class.java) { GeositeAsset.resolveDomains(bytes, selectors) }
            }
        }
    }

    @Test
    fun selectedOutputAndStrictValidationSurviveLongUnselectedUtf8Text() {
        val russian = listOf(domain(0, "keyword"), domain(1, "^Case[.]"), domain(2, "suffix.example"), domain(3, "exact.example"))
        val sample = listOf(domain(3, "both.example", listOf(attribute("cn", 2, 0), attribute("ads", 3, 0))),
            domain(3, "one.example", listOf(attribute("cn", 2, 1))))
        val selectors = setOf("geosite:category-ru", "geosite:sample@cn@ads", "geosite:sample@missing")
        val expected = GeositeAsset.resolveDomains(asset("CATEGORY-RU" to russian, "SAMPLE" to sample), selectors)
        val longUtf8 = "x".repeat(255) + "\uD83D\uDE00" + "\u2003".repeat(300)
        val bytes = asset("CATEGORY-RU" to russian, "SAMPLE" to sample, "UNSELECTED" to listOf(
            domain(2, longUtf8, listOf(attribute(longUtf8, 2, 0))),
        ))
        val actual = GeositeAsset.resolveDomains(bytes, selectors)
        assertEquals(expected.keys, actual.keys)
        for (selector in selectors) assertTrue(expected.getValue(selector).similar(actual.getValue(selector)))
        assertTrue(GeositeAsset.resolveDomains(bytes, emptySet()).isEmpty())
        assertFalse(GeositeAsset.isValid(bytes.copyOf(bytes.size - 1)))
    }

    @Test fun customDatImportsGeositeWithoutRussianCategoryAndGeoIpNetworks() {
        val custom = asset("CUSTOM" to listOf(domain(2, "custom.example")))
        assertEquals("custom.example", GeositeAsset.resolveCustom(custom, "custom").getJSONArray("domain_suffix").getString(0))
        assertFalse(GeositeAsset.isValid(custom))
        val ipv4 = field(1, byteArrayOf(192.toByte(), 0, 2, 0)) + field(2, 24L)
        val ipv6 = field(1, java.net.InetAddress.getByName("2001:db8::").address) + field(2, 32L)
        val geoip = field(1, field(1, "TEST".toByteArray()) + field(2, ipv4) + field(2, ipv6) + field(3, 1L))
        val imported = GeositeAsset.resolveCustom(geoip, "test")
        assertEquals("192.0.2.0/24", imported.getJSONArray("ip_cidr").getString(0))
        assertTrue(imported.getJSONArray("ip_cidr").getString(1).endsWith("/32"))
        assertTrue(imported.getBoolean("invert"))
        assertThrows(IllegalArgumentException::class.java) { GeositeAsset.resolveCustom(geoip, "missing") }
        assertThrows(IllegalArgumentException::class.java) { GeositeAsset.resolveCustom(geoip.copyOf(geoip.size - 1), "test") }
        val invalid = field(1, field(1, "TEST".toByteArray()) + field(2,
            field(1, byteArrayOf(192.toByte(), 0, 2, 0)) + field(2, byteArrayOf(24))))
        assertThrows(IllegalArgumentException::class.java) { GeositeAsset.resolveCustom(invalid, "test") }
    }

    companion object {
        internal fun asset(vararg sites: Pair<String, List<ByteArray>>): ByteArray {
            val padding = "PADDING" to List(80) { domain(2, "padding.example") }
            return (sites.toList() + padding).fold(byteArrayOf()) { result, (code, domains) ->
                result + field(1, field(1, code.toByteArray()) + domains.fold(byteArrayOf()) { nested, value -> nested + field(2, value) })
            }
        }

        internal fun domain(type: Int, value: String, attributes: List<ByteArray> = emptyList()): ByteArray =
            field(1, type.toLong()) + field(2, value.toByteArray()) +
                attributes.fold(byteArrayOf()) { result, attribute -> result + field(3, attribute) }

        private fun attribute(key: String, valueField: Int, value: Long): ByteArray =
            field(1, key.toByteArray()) + field(valueField, value)

        private fun field(number: Int, value: ByteArray): ByteArray = varint((number.toLong() shl 3) or 2) + varint(value.size.toLong()) + value
        private fun field(number: Int, value: Long): ByteArray = varint(number.toLong() shl 3) + varint(value)
        private fun varint(value: Long): ByteArray {
            var remaining = value
            val bytes = ArrayList<Byte>()
            do {
                val next = remaining and 0x7f
                remaining = remaining ushr 7
                bytes += (next or if (remaining != 0L) 0x80 else 0).toByte()
            } while (remaining != 0L)
            return bytes.toByteArray()
        }
    }
}
