package com.noki.vpn

import java.util.Locale

object InviteCodeFormatter {
    fun format(value: String): String {
        val compact = value
            .uppercase(Locale.ROOT)
            .filter { it in 'A'..'Z' || it in '0'..'9' }
            .take(10)
        return compact.chunked(5).joinToString("-")
    }

    fun extract(value: String): String {
        val trimmed = value.trim()
        val knownPayloadPrefix = "noki://invite/"
        val payload = when {
            trimmed.startsWith(knownPayloadPrefix, ignoreCase = true) ->
                trimmed.substringAfter(knownPayloadPrefix)
            trimmed.startsWith("noki-invite:", ignoreCase = true) ->
                trimmed.substringAfter(":")
            else -> trimmed
        }
        return format(payload)
    }
}
