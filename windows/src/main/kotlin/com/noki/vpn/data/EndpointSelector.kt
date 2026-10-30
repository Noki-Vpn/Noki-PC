package com.noki.vpn.data

object EndpointSelector {
    fun profileFromCandidate(
        session: BackendVpnSession,
        candidate: BackendEndpointCandidate,
    ): VlessProfile {
        session.multiHop?.let { route ->
            require(candidate.nodeId == route.exitNodeId && candidate.entryHost == route.entryIp &&
                candidate.connectIp == route.entryIp && candidate.entryPort in 20000..29999) { "multihop_exit_mismatch" }
        }
        return profileFromCandidate(candidate, session.vpnSecret, session.vpnUsername).copy(
            remark = "Noki ${candidate.locationName.ifBlank { session.locationName }}",
            proxyType = candidate.proxyType.ifBlank { session.proxyType },
            multiHop = session.multiHop,
            nokinvToken = if (EndpointTransportPolicy.isNokiNv(candidate)) session.nokinvToken else "",
            nokinvCredentialGeneration = if (EndpointTransportPolicy.isNokiNv(candidate)) session.nokinvCredentialGeneration else 0,
        )
    }

    fun profileFromCandidate(candidate: BackendEndpointCandidate, vpnSecret: String, vpnUsername: String = ""): VlessProfile =
        VlessProfile(
            remark = "Noki ${candidate.locationName}",
            endpointCode = candidate.code,
            proxyType = candidate.proxyType,
            transport = candidate.normalizedTransport(),
            transportMode = candidate.transportMode.orEmpty(),
            host = candidate.connectionHost(),
            port = candidate.entryPort.toString(),
            uuid = if (EndpointTransportPolicy.isNokiNv(candidate)) "" else vpnSecret,
            vpnUsername = if (EndpointTransportPolicy.isNaive(candidate)) vpnUsername else "",
            nokinvToken = candidate.nokinvToken,
            nokinvCredentialGeneration = candidate.nokinvCredentialGeneration,
            flow = candidate.flow.orEmpty(),
            security = candidate.security,
            fingerprint = candidate.fingerprint ?: "chrome",
            serverName = candidate.serverName,
            requestHost = candidate.requestHost.orEmpty(),
            path = candidate.path.orEmpty(),
            alpn = candidate.alpn.orEmpty(),
            allowInsecure = false,
            enableMux = candidate.enableMux,
            randomUserAgent = candidate.randomUserAgent,
            publicKey = candidate.publicKey.orEmpty(),
            shortId = candidate.shortId.orEmpty(),
            spiderX = "/",
        )

    fun optionsFromSession(session: BackendVpnSession): List<VpnEndpointOption> {
        return session.endpointCandidates
            .filter { !it.canaryOnly }
            .filter(EndpointSecurityPolicy::isAllowedCandidate)
            .filter(EndpointTransportPolicy::isDirectCandidate)
            .map { candidate ->
            val option = VpnEndpointOption(
                code = candidate.code,
                nodeId = candidate.nodeId,
                label = candidate.label,
                locationCode = candidate.locationCode,
                locationName = candidate.locationName,
                host = candidate.entryHost,
                port = candidate.entryPort,
                proxyType = candidate.proxyType,
                transport = candidate.normalizedTransport(),
                transportMode = candidate.transportMode.orEmpty(),
                security = candidate.security,
                canaryOnly = candidate.canaryOnly,
                priority = candidate.priority,
            )
            option.copy(endpointGroupKey = EndpointGroupPolicy.groupKey(option))
        }
    }
}
