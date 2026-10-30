package com.noki.vpn.vpn
import com.noki.vpn.data.DomainRulePolicy
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CharsetDecoder
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject
object GeositeAsset {
    const val MAX_BYTES = 32 * 1024 * 1024
    private var validatedDigest: String? = null
    fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }

    @Synchronized
    fun isValid(bytes: ByteArray): Boolean = runCatching {
        if (sha256(bytes) != validatedDigest) resolveDomains(bytes, emptySet())
        true
    }.getOrDefault(false)

    /** Decode Xray GeoSiteList bytes into native inline fields, retaining attribute conjunction. */
    @Synchronized
    fun resolveDomains(bytes: ByteArray, selectors: Set<String>, requireRussianResources: Boolean = true): Map<String, JSONObject> {
        require(bytes.size in (if (requireRussianResources) 1024 else 1)..MAX_BYTES) { "geosite_size_invalid" }
        val digest = sha256(bytes)
        val validated = requireRussianResources && digest == validatedDigest
        val requested = selectors.associateWith { selector ->
            require(selector.startsWith("geosite:") && DomainRulePolicy.normalize(selector) == selector) { "geosite_selector_invalid" }
            selector.substringAfter(':').split('@').also { parts -> require(parts.all(String::isNotBlank)) { "geosite_selector_invalid" } }
        }
        val selected = requested.mapValues { linkedMapOf<String, LinkedHashSet<String>>() }
        val seen = HashSet<String>()
        val validator = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        val characters = CharBuffer.allocate(256)
        val textInput = ByteBuffer.wrap(bytes)
        var russianDomains = 0
        fields(bytes, 0, bytes.size) site@{ field, wire, siteStart, siteEnd ->
            require(field == 1 && wire == 2) { "geosite_list_invalid" }
            var siteCode: String? = null
            fields(bytes, siteStart, siteEnd) { nestedField, nestedWire, codeStart, codeEnd ->
                if (nestedField == 1) {
                    require(nestedWire == 2 && siteCode == null) { "geosite_code_invalid" }
                    siteCode = text(bytes, codeStart, codeEnd).lowercase(Locale.ROOT)
                }
            }
            val code = requireNotNull(siteCode) { "geosite_code_invalid" }
            require(code.isNotBlank() && seen.add(code)) { "geosite_code_invalid" }
            val wanted = requested.filterValues { it.first() == code }
            if (validated && wanted.isEmpty()) return@site
            val needsAttributes = wanted.values.any { it.size > 1 }
            fields(bytes, siteStart, siteEnd) domain@{ nestedField, nestedWire, domainStart, domainEnd ->
                if (nestedField != 2) return@domain
                require(nestedWire == 2) { "geosite_domain_invalid" }
                var type = 0
                var value: String? = null
                var valuePresent = false
                val attributes = if (needsAttributes) HashSet<String>() else null
                fields(bytes, domainStart, domainEnd) { domainField, domainWire, valueStart, valueEnd ->
                    when (domainField) {
                        1 -> {
                            require(domainWire == 0) { "geosite_domain_type_invalid" }
                            val enum = varint(bytes, valueStart, valueEnd) { number, _ -> number }
                            require(enum in 0L..3L) { "geosite_domain_type_unsupported" }
                            type = enum.toInt()
                        }
                        2 -> {
                            require(domainWire == 2) { "geosite_domain_invalid" }
                            if (wanted.isEmpty()) require(nonBlankText(textInput, valueStart, valueEnd, validator, characters)) { "geosite_domain_invalid" }
                            else value = text(bytes, valueStart, valueEnd).also { require(it.isNotBlank()) { "geosite_domain_invalid" } }
                            valuePresent = true
                        }
                        3 -> {
                            require(domainWire == 2) { "geosite_attribute_invalid" }
                            var key: String? = null
                            var keyPresent = false
                            fields(bytes, valueStart, valueEnd) { attributeField, attributeWire, attributeStart, attributeEnd ->
                                when (attributeField) {
                                    1 -> {
                                        require(attributeWire == 2) { "geosite_attribute_invalid" }
                                        if (attributes == null) require(nonBlankText(textInput, attributeStart, attributeEnd, validator, characters)) { "geosite_attribute_invalid" }
                                        else key = text(bytes, attributeStart, attributeEnd).also { require(it.isNotBlank()) { "geosite_attribute_invalid" } }
                                        keyPresent = true
                                    }
                                    2, 3 -> require(attributeWire == 0) { "geosite_attribute_invalid" }
                                }
                            }
                            require(keyPresent) { "geosite_attribute_invalid" }
                            if (attributes != null) attributes += requireNotNull(key) { "geosite_attribute_invalid" }
                        }
                    }
                }
                require(valuePresent) { "geosite_domain_invalid" }
                if (code == "category-ru") russianDomains++
                if (wanted.isEmpty()) return@domain
                val domain = requireNotNull(value) { "geosite_domain_invalid" }
                val nativeField = when (type) { 0 -> "domain_keyword"; 1 -> "domain_regex"; 2 -> "domain_suffix"; else -> "domain" }
                for ((selector, parts) in wanted) {
                    // Xray matches attribute keys, including keys whose bool/int value is false/zero.
                    if (parts.drop(1).all { attributes?.contains(it) == true }) selected.getValue(selector)
                        .getOrPut(nativeField) { linkedSetOf() }.add(domain)
                }
            }
        }
        require(!requireRussianResources || validated || russianDomains > 0) { "geosite_category_ru_empty" }
        require(requested.values.all { it.first() in seen }) { "geosite_tag_unknown" }
        if (requireRussianResources) validatedDigest = digest
        return selected.mapValues { (_, fields) -> JSONObject().apply { fields.forEach { (key, values) -> put(key, JSONArray(values)) } } }
    }

    fun resolveCustom(bytes: ByteArray, category: String): JSONObject {
        require(bytes.size in 1..MAX_BYTES)
        val selector = category.trim().lowercase(Locale.ROOT).removePrefix("geosite:").removePrefix("geoip:")
        require(selector.isNotBlank()) { "Для .dat укажите категорию, например ru или youtube" }
        val code = selector.substringBefore('@')
        var result: JSONObject? = null
        val available = mutableListOf<String>()
        fields(bytes, 0, bytes.size) entry@{ field, wire, start, end ->
            require(field == 1 && wire == 2) { "Некорректный файл .dat" }
            var name = ""
            fields(bytes, start, end) { key, kind, from, to -> if (key == 1) {
                require(kind == 2); name = text(bytes, from, to).lowercase(Locale.ROOT)
            } }
            if (available.size < 24) available += name
            if (name != code) return@entry
            require(result == null) { "Повторная категория .dat" }
            var geoip = false
            var reverse = false
            val cidrs = JSONArray()
            fields(bytes, start, end) { key, kind, from, to ->
                if (key == 3 && kind == 0) reverse = varint(bytes, from, to) { value, _ -> value != 0L }
                if (key == 2) {
                    require(kind == 2)
                    var address: ByteArray? = null
                    var prefix = 0L
                    var invalidPrefix = false
                    fields(bytes, from, to) { part, type, first, last ->
                        if (part == 1 && type == 2) address = bytes.copyOfRange(first, last)
                        if (part == 2 && type == 0) prefix = varint(bytes, first, last) { value, _ -> value }
                        if (part == 2 && type != 0) invalidPrefix = true
                    }
                    address?.let {
                        geoip = true
                        require(!invalidPrefix && it.size in listOf(4, 16) && prefix in 0L..(it.size * 8L)) { "Некорректная сеть GeoIP" }
                        cidrs.put(java.net.InetAddress.getByAddress(it).hostAddress + "/" + prefix)
                    }
                }
            }
            result = if (geoip) {
                require('@' !in selector)
                JSONObject().put("ip_cidr", cidrs).also { if (reverse) it.put("invert", true) }
            } else resolveDomains(bytes, setOf("geosite:$selector"), requireRussianResources = false).getValue("geosite:$selector")
        }
        return requireNotNull(result) { "Категория $selector не найдена. Доступны: ${available.joinToString(", ")}" }
    }

    private fun text(bytes: ByteArray, start: Int, end: Int): String = StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes, start, end - start)).toString()

    private fun nonBlankText(input: ByteBuffer, start: Int, end: Int, decoder: CharsetDecoder, characters: CharBuffer): Boolean {
        decoder.reset()
        input.limit(end).position(start)
        var nonBlank = false
        while (true) {
            characters.clear()
            val result = decoder.decode(input, characters, true)
            if (result.isError) result.throwException()
            if (!nonBlank) {
                characters.flip()
                while (characters.hasRemaining()) if (!characters.get().isWhitespace()) { nonBlank = true; break }
            }
            if (result.isUnderflow) return nonBlank
        }
    }

    private inline fun fields(bytes: ByteArray, start: Int, end: Int, read: (Int, Int, Int, Int) -> Unit) {
        var pos = start
        while (pos < end) {
            val tag = varint(bytes, pos, end) { number, afterTag -> pos = afterTag; number }
            require(tag in 1L..0xffffffffL && tag ushr 3 != 0L) { "geosite_field_invalid" }
            val field = (tag ushr 3).toInt()
            val wire = (tag and 7).toInt()
            var valueStart = pos
            when (wire) {
                0 -> pos = varint(bytes, pos, end) { _, afterValue -> afterValue }
                1 -> pos += 8
                2 -> {
                    val length = varint(bytes, pos, end) { number, afterLength -> valueStart = afterLength; number }
                    require(length >= 0 && length <= end - valueStart) { "geosite_length_invalid" }
                    pos = valueStart + length.toInt()
                }
                5 -> pos += 4
                else -> error("Invalid geosite wire type")
            }
            require(pos <= end)
            read(field, wire, valueStart, pos)
        }
    }

    private inline fun <T> varint(bytes: ByteArray, start: Int, end: Int, read: (Long, Int) -> T): T {
        var value = 0L
        var pos = start
        for (shift in 0..63 step 7) {
            require(pos < end)
            val byte = bytes[pos++].toInt() and 0xff
            require(shift < 63 || byte <= 1) { "geosite_varint_invalid" }
            value = value or ((byte and 0x7f).toLong() shl shift)
            if (byte < 128) return read(value, pos)
        }
        error("Invalid geosite varint")
    }
}
