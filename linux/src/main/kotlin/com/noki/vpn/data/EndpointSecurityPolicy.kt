package com.noki.vpn.data

import java.util.Locale

object EndpointSecurityPolicy {

    fun isValidNaiveCredential(value: String): Boolean =
        value.isNotBlank() && value.toByteArray(Charsets.UTF_8).size <= 4096 && value.none { it.code < 32 || it.code == 127 }

    fun isValidNokiNvCredential(token: String, generation: Int): Boolean {
        val decoded = runCatching { java.util.Base64.getUrlDecoder().decode(token) }.getOrNull()
        return generation > 0 && decoded?.size == 32 &&
            java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(decoded) == token
    }

    fun isAllowedCandidate(candidate: BackendEndpointCandidate): Boolean {
        if (candidate.allowInsecure) return false
        if (candidate.entryHost.isBlank() || candidate.entryPort !in 1..65535 || candidate.enableMux || candidate.randomUserAgent) return false

        val proxyType = candidate.proxyType.lowercase(Locale.ROOT)
        val security = candidate.security.lowercase(Locale.ROOT)
        val transport = candidate.normalizedTransport()
        val mode = candidate.transportMode.orEmpty().lowercase(Locale.ROOT).trim()
        if (!isSupportedTransport(proxyType, security, transport, mode)) return false

        return candidate.serverName.isNotBlank() &&
            (security != "reality" || candidate.hasRealityMaterial()) &&
            (transport != "xhttp" || candidate.path.orEmpty().isNotBlank())
    }

    fun isAllowedProfile(profile: VlessProfile): Boolean {
        if (profile.allowInsecure) return false
        if (profile.host.isBlank() || profile.port.toIntOrNull() !in 1..65535 || profile.enableMux || profile.randomUserAgent) return false

        val proxyType = profile.proxyType.lowercase(Locale.ROOT)
        val security = profile.security.lowercase(Locale.ROOT)
        val transport = normalizedProfileTransport(profile.transport)
        val mode = profile.transportMode.lowercase(Locale.ROOT).trim()
        if (!isSupportedTransport(proxyType, security, transport, mode)) return false

        if (proxyType == "nokinv" && (!isValidNokiNvCredential(profile.nokinvToken, profile.nokinvCredentialGeneration) ||
                profile.nokinvMode !in setOf("balanced", "gaming") || profile.flow.isNotBlank() ||
                profile.publicKey.isNotBlank() || profile.shortId.isNotBlank() || profile.path !in setOf("", "/tunnel"))) return false
        if (proxyType != "nokinv" && (profile.nokinvToken.isNotEmpty() || profile.nokinvCredentialGeneration != 0)) return false
        if (proxyType == "naive" && (!isValidNaiveCredential(profile.vpnUsername) || !isValidNaiveCredential(profile.uuid) ||
                profile.flow.isNotBlank() || profile.publicKey.isNotBlank() || profile.shortId.isNotBlank() ||
                profile.requestHost.isNotBlank() || profile.path !in setOf("", "/") || profile.alpn.isNotBlank())) return false
        if (proxyType != "naive" && profile.vpnUsername.isNotEmpty()) return false
        return profile.serverName.isNotBlank() &&
            (security != "reality" || (profile.publicKey.isNotBlank() && profile.shortId.isNotBlank())) &&
            (transport != "xhttp" || profile.path.isNotBlank())
    }

    fun isAllowedOption(option: VpnEndpointOption): Boolean =
        option.host.isNotBlank() && option.port in 1..65535 && isSupportedTransport(
            option.proxyType.lowercase(Locale.ROOT), option.security.lowercase(Locale.ROOT),
            normalizedProfileTransport(option.transport), option.transportMode.lowercase(Locale.ROOT).trim(),
        )

    private fun isSupportedTransport(proxyType: String, security: String, transport: String, mode: String): Boolean =
        when (proxyType) {
            "vless" -> when (security) {
                "reality" -> (transport == "tcp" && mode.isBlank()) || (transport == "xhttp" && mode == "stream-up")
                "tls" -> transport == "tcp" && mode.isBlank()
                else -> false
            }
            "hysteria" -> security == "tls" && transport == "hysteria" && mode.isBlank()
            "nokinv" -> security == "tls" && transport == "nokinv" && mode in setOf("", "quic")
            "naive" -> security == "tls" && transport == "naive" && mode.isBlank()
            else -> false
        }

    private fun BackendEndpointCandidate.hasRealityMaterial(): Boolean {
        return serverName.isNotBlank() &&
            !publicKey.isNullOrBlank() &&
            !shortId.isNullOrBlank()
    }

    private fun normalizedProfileTransport(value: String): String {
        return when (value.lowercase(Locale.ROOT).trim()) {
            "raw" -> "tcp"
            "hysteria2", "hy2" -> "hysteria"
            "" -> "tcp"
            else -> value.lowercase(Locale.ROOT).trim()
        }
    }
}
