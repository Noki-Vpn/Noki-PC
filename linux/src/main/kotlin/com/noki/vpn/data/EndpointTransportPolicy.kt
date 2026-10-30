package com.noki.vpn.data

object EndpointTransportPolicy {
    fun isDirectCandidate(candidate: BackendEndpointCandidate): Boolean = isDirectTransport(
        candidate.proxyType, candidate.security, candidate.normalizedTransport(), candidate.normalizedTransportMode(),
    )

    fun isDirectProfile(profile: VlessProfile): Boolean = isDirectTransport(
        profile.proxyType, profile.security, profile.transport, profile.transportMode,
    )

    fun isDirectOption(option: VpnEndpointOption): Boolean = isDirectTransport(
        option.proxyType, option.security, option.transport, option.transportMode,
    )

    private fun isDirectTransport(proxyType: String, security: String, transport: String, mode: String): Boolean =
        proxyType.equals("nokinv", true) || proxyType.equals("hysteria", true) ||
            (proxyType.equals("naive", true) && security.equals("tls", true) && transport.equals("naive", true) && mode.isBlank()) ||
            (proxyType.equals("vless", true) && security.equals("reality", true) &&
                ((transport.equals("tcp", true) && mode.isBlank()) ||
                    (transport.equals("xhttp", true) && (mode.isBlank() || mode.equals("stream-up", true)))))

    fun isNokiNv(candidate: BackendEndpointCandidate): Boolean = candidate.proxyType.equals("nokinv", true)

    fun isNaive(candidate: BackendEndpointCandidate): Boolean = candidate.proxyType.equals("naive", true)

    fun matchesProtocol(candidate: BackendEndpointCandidate, protocol: VpnProtocol): Boolean = when (protocol) {
        VpnProtocol.AUTO -> true
        VpnProtocol.NOKINV -> isNokiNv(candidate)
        VpnProtocol.REALITY -> candidate.security.equals("reality", true)
        VpnProtocol.TLS -> isHysteria(candidate)
        VpnProtocol.WIREGUARD -> false
    }

    fun isHysteria(candidate: BackendEndpointCandidate): Boolean {
        return candidate.proxyType.equals("hysteria", ignoreCase = true) ||
            candidate.normalizedTransport() == "hysteria"
    }

    fun requiresTcpPrecheck(candidate: BackendEndpointCandidate): Boolean {
        return !isHysteria(candidate) && !isNokiNv(candidate)
    }
}
