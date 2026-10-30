package com.noki.vpn.data

object AppDiagnosticRedactor {
    private val authorizationBearerPattern =
        Regex("""(?i)(Authorization\s*:\s*Bearer\s+)([^;\s,]+)""")
    private val jsonSecretPattern =
        Regex(
            "\"(?i:(access_token|refresh_token|token|password|private_key|secret|uuid|device_key|device_nonce|device_signature|vpn_secret))\"\\s*:\\s*\"([^\"]*)\"",
        )
    private val keyValueSecretPattern =
        Regex(
            """(?i)\b(access_token|refresh_token|token|password|private_key|secret|uuid|device_key|device_nonce|device_signature|vpn_secret)\b\s*[:=]\s*([^;\s,]+)""",
        )
    private val uuidPattern =
        Regex("""\b[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\b""")

    fun redact(value: String): String {
        if (value.isBlank()) return value
        return value
            .replace(authorizationBearerPattern) { match ->
                "${match.groupValues[1]}<redacted>"
            }
            .replace(jsonSecretPattern) { match ->
                "\"${match.groupValues[1]}\":\"<redacted>\""
            }
            .replace(keyValueSecretPattern) { match ->
                "${match.groupValues[1]}=<redacted>"
            }
            .replace(uuidPattern, "<redacted>")
    }

    fun redactNullable(value: String?): String? =
        value?.let(::redact)
}
