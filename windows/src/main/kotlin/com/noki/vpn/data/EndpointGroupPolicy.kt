package com.noki.vpn.data

import java.util.Locale

object EndpointGroupPolicy {
    fun groupKey(option: VpnEndpointOption): String {
        return groupKey(
            security = option.security,
            transport = normalizeTransport(option.transport),
            transportMode = normalizeTransportMode(option.transport, option.transportMode),
        )
    }

    fun groupKey(candidate: BackendEndpointCandidate): String =
        groupKey(
            security = candidate.security,
            transport = candidate.normalizedTransport(),
            transportMode = candidate.normalizedTransportMode(),
        )

    fun groupKey(profile: VlessProfile): String = groupKey(profile.security, normalizeTransport(profile.transport),
        normalizeTransportMode(profile.transport, profile.transportMode))

    fun protocolOptions(options: List<VpnEndpointOption> = emptyList()): List<VpnEndpointOption> {
        val groups = manualOptions(options).associateBy(::groupKey)
        return listOf(
            VpnEndpointOption(code = "nokinv-quic", proxyType = "nokinv", transport = "nokinv", transportMode = "quic",
                security = "tls", label = "NokiNV"),
            VpnEndpointOption(code = "reality-xhttp", proxyType = "vless", transport = "xhttp", transportMode = "stream-up",
                security = "reality", label = "Reality XHTTP stream-up"),
            VpnEndpointOption(code = "reality-tcp", proxyType = "vless", transport = "tcp",
                security = "reality", label = "Reality / TCP"),
            VpnEndpointOption(code = "hysteria2", proxyType = "hysteria", transport = "hysteria", security = "tls", label = "Hysteria2"),
            VpnEndpointOption(code = "naive", proxyType = "naive", transport = "naive", security = "tls", label = "NaiveProxy"),
        ).map { option -> (groups[groupKey(option)] ?: option).copy(endpointGroupKey = groupKey(option), label = option.label) }
    }

    fun manualOptions(options: List<VpnEndpointOption>): List<VpnEndpointOption> {
        val grouped = linkedMapOf<String, MutableList<VpnEndpointOption>>()
        options.filter { !it.canaryOnly && EndpointSecurityPolicy.isAllowedOption(it) &&
            EndpointTransportPolicy.isDirectOption(it) }.flatMap { option ->
            if (option.proxyType.equals("nokinv", true) && option.transportMode.isBlank()) {
                listOf(option.copy(transportMode = "quic"))
            } else listOf(option)
        }.forEach { option ->
            grouped.getOrPut(groupKey(option)) { mutableListOf() }.add(option)
        }
        return grouped.map { (key, group) ->
            val first = group.first()
            val ports = group.map { it.port }.distinct().sorted()
            first.copy(
                label = displayLabelFor(first),
                endpointGroupKey = key,
                groupSize = group.size,
                groupPorts = ports,
            )
        }
    }

    fun resolveManualGroupKey(
        settings: AdvancedSettings,
        endpointOptions: List<VpnEndpointOption>,
        profile: VlessProfile,
    ): String {
        settings.manualEndpointGroupKey.trim().takeIf { it.isNotBlank() }?.let { return normalizedGroupKey(it) }
        val code = settings.manualEndpointCode.ifBlank { profile.endpointCode }
        return endpointOptions.firstOrNull { it.code == code }?.let { normalizedGroupKey(groupKey(it)) }
            ?: profile.takeIf { it.endpointCode == code && code.isNotBlank() }?.let { normalizedGroupKey(groupKey(it)) }.orEmpty()
    }

    fun settingsAfterSelection(
        settings: AdvancedSettings,
        endpointCode: String,
        endpointOptions: List<VpnEndpointOption>,
    ): AdvancedSettings {
        if (settings.gamingModeEnabled || settings.endpointSelectionMode != EndpointSelectionMode.MANUAL) return settings
        val savedGroup = normalizedGroupKey(settings.manualEndpointGroupKey)
        val groupKey = manualOptions(endpointOptions.filter { it.code == endpointCode }).firstOrNull {
            groupKey(it) == savedGroup
        }?.let(::groupKey) ?: endpointOptions.firstOrNull { it.code == endpointCode }?.let(::groupKey)
            ?: settings.manualEndpointGroupKey
        return settings.copy(
            manualEndpointCode = endpointCode,
            manualEndpointGroupKey = normalizedGroupKey(groupKey),
        )
    }

    fun manualCandidates(
        candidates: List<BackendEndpointCandidate>,
        settings: AdvancedSettings,
    ): List<BackendEndpointCandidate> {
        val targetGroup = normalizedGroupKey(settings.manualEndpointGroupKey).ifBlank {
            candidates.firstOrNull { it.code == settings.manualEndpointCode.trim() }
                ?.let { normalizedGroupKey(groupKey(it)) }
                .orEmpty()
        }
        if (targetGroup.isNotBlank()) {
            val mode = targetGroup.substringAfterLast('|')
            return candidates.map {
                if (it.proxyType.equals("nokinv", true) && it.transportMode.isNullOrBlank() && mode == "quic") {
                    it.copy(transportMode = mode)
                } else it
            }.filter { groupKey(it) == targetGroup }
        }
        val legacyCode = settings.manualEndpointCode.trim()
        return candidates.firstOrNull { it.code == legacyCode }?.let(::listOf) ?: emptyList()
    }

    fun selectManualCandidate(
        candidates: List<BackendEndpointCandidate>,
        settings: AdvancedSettings,
        health: Map<String, EndpointHealth>,
        nowMillis: Long,
        rotationIndex: (String) -> Int,
    ): EndpointRankingPolicy.Selection? {
        val groupCandidates = manualCandidates(candidates, settings)
        return EndpointRankingPolicy.select(
            candidates = groupCandidates,
            health = health,
            networkKind = EndpointRankingPolicy.NetworkKind.OTHER,
            nowMillis = nowMillis,
            rotationIndex = rotationIndex,
        )
    }

    private fun groupKey(
        security: String,
        transport: String,
        transportMode: String,
    ): String = listOf(
        security.lowercase(Locale.ROOT).ifBlank { "tls" },
        transport.lowercase(Locale.ROOT).ifBlank { "tcp" },
        transportMode.lowercase(Locale.ROOT),
    ).joinToString("|")

    fun normalizedGroupKey(raw: String): String {
        val parts = raw.trim().split('|')
        val family = when (parts.size) {
            5 -> parts.subList(1, 4)
            3 -> parts
            else -> return ""
        }
        val transport = normalizeTransport(family[1])
        val mode = normalizeTransportMode(family[1], family[2])
        return groupKey(family[0], transport, if (transport == "nokinv") "quic" else mode)
    }

    fun displayLabelFor(option: VpnEndpointOption): String {
        if (option.proxyType.equals("naive", true)) return "NaiveProxy"
        if (option.proxyType.equals("nokinv", true)) return "NokiNV"
        if (option.proxyType.equals("hysteria", ignoreCase = true) || normalizeTransport(option.transport) == "hysteria") {
            return "Hysteria2 / UDP"
        }
        val security = when (val normalized = option.security.lowercase(Locale.ROOT).ifBlank { "tls" }) {
            "tls" -> "TLS"
            "reality" -> "Reality"
            else -> normalized.replaceFirstChar {
                if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString()
            }
        }
        val transport = normalizeTransport(option.transport)
        val transportLabel = when (transport) {
            "xhttp" -> "XHTTP ${normalizeTransportMode(option.transport, option.transportMode)}".trim()
            "tcp" -> "TCP"
            else -> transport.uppercase(Locale.ROOT)
        }
        return "$security / $transportLabel"
    }

    private fun normalizeTransport(raw: String): String {
        return when (raw.lowercase(Locale.ROOT).trim()) {
            "raw" -> "tcp"
            "hysteria2", "hy2" -> "hysteria"
            "" -> "tcp"
            else -> raw.lowercase(Locale.ROOT).trim()
        }
    }

    private fun normalizeTransportMode(
        transport: String,
        rawMode: String,
    ): String {
        val mode = rawMode.lowercase(Locale.ROOT).trim()
        if (mode.isNotBlank()) return mode
        return if (normalizeTransport(transport) == "xhttp") "stream-up" else ""
    }
}
