package com.noki.vpn.data

import java.util.Locale
import java.util.UUID

object VpnProfileValidator {
    fun isUsable(
        settings: StoredSettings,
    ): Boolean = rejectionReason(settings) == null

    fun rejectionReason(settings: StoredSettings): String? {
        return rejectionReason(
            profile = settings.profile,
            advancedSettings = settings.advancedSettings,
            selectedLocationCode = settings.userProfile.selectedServerCode,
            endpointOptions = settings.endpointOptions,
        )
    }

    fun isUsable(
        profile: VlessProfile,
        advancedSettings: AdvancedSettings,
        selectedLocationCode: String = "",
        endpointOptions: List<VpnEndpointOption> = emptyList(),
    ): Boolean = rejectionReason(profile, advancedSettings, selectedLocationCode, endpointOptions) == null

    private fun rejectionReason(
        profile: VlessProfile,
        advancedSettings: AdvancedSettings,
        selectedLocationCode: String,
        endpointOptions: List<VpnEndpointOption>,
    ): String? {
        if (advancedSettings.ruRelayEnabled) {
            return "ru_relay_requires_wireguard_coordinator"
        }
        if (!EndpointTransportPolicy.isDirectProfile(profile)) return "direct_transport_unsupported"
        val multiHop = profile.multiHop
        if (advancedSettings.multiHop.enabled) {
            if (multiHop == null || multiHop.selection != advancedSettings.multiHop) return "multihop_runtime_missing"
            val hops = runCatching { multiHop.selection.explicitNodeIds() }.getOrNull()
            if (multiHop.version != 2 || hops != (multiHop.entryNodeId to multiHop.exitNodeId) ||
                !multiHop.policyHash.matches(Regex("[0-9a-f]{64}"))) return "multihop_policy_invalid"
            if (profile.host != multiHop.entryIp || profile.host.isBlank() || profile.port.toIntOrNull() !in 20000..29999) {
                return "multihop_entry_invalid"
            }
        } else if (multiHop != null) return "multihop_runtime_unexpected"
        val expectedSecurity = advancedSettings.effectiveProtocol.name.lowercase(Locale.ROOT)
        val normalizedSecurity = profile.security.lowercase(Locale.ROOT)
        if (expectedSecurity == "nokinv") {
            if (!profile.proxyType.equals("nokinv", true)) return "protocol_mismatch"
        } else if (expectedSecurity != "auto" &&
            (profile.proxyType.equals("nokinv", true) || normalizedSecurity != expectedSecurity)) return "protocol_mismatch"
        if (advancedSettings.gamingModeEnabled && profile.nokinvMode != "gaming") return "gaming_profile_invalid"

        if (!advancedSettings.gamingModeEnabled && advancedSettings.endpointSelectionMode == EndpointSelectionMode.MANUAL) {
            val manualGroupKey = EndpointGroupPolicy.resolveManualGroupKey(
                settings = advancedSettings,
                endpointOptions = endpointOptions,
                profile = profile,
            )
            if (manualGroupKey.isNotBlank()) {
                val profileGroupKey = EndpointGroupPolicy.groupKey(profile)
                if (profileGroupKey != manualGroupKey) return "manual_group_mismatch"
                val endpointOption = endpointOptions.firstOrNull { it.code == profile.endpointCode }
                if (profile.proxyType.equals("nokinv", true) && endpointOption != null &&
                    EndpointGroupPolicy.manualOptions(listOf(endpointOption)).none {
                        EndpointGroupPolicy.groupKey(it) == profileGroupKey
                    }) return "manual_group_mismatch"
            } else {
                val manualCode = advancedSettings.manualEndpointCode.trim()
                if (manualCode.isBlank() || profile.endpointCode != manualCode) return "manual_endpoint_mismatch"
            }
        }

        val endpointOption = endpointOptions.firstOrNull { it.code == profile.endpointCode }
        val selectedCode = selectedLocationCode.trim()
        if (endpointOption != null &&
            selectedCode.isNotBlank() &&
            endpointOption.locationCode.isNotBlank() &&
            !endpointOption.locationCode.equals(selectedCode, ignoreCase = true)
        ) {
            return "location_mismatch"
        }

        if (profile.host.isBlank()) return "missing_host"
        if (profile.port.isBlank()) return "missing_port"
        if (profile.proxyType.equals("nokinv", true)) {
            if (!EndpointSecurityPolicy.isValidNokiNvCredential(profile.nokinvToken, profile.nokinvCredentialGeneration)) return "invalid_credential"
        } else if (profile.proxyType.equals("naive", true)) {
            if (!EndpointSecurityPolicy.isValidNaiveCredential(profile.vpnUsername) ||
                !EndpointSecurityPolicy.isValidNaiveCredential(profile.uuid)) return "invalid_credential"
        } else if (profile.proxyType.equals("hysteria", true)) {
            if (profile.uuid.isBlank()) return "invalid_credential"
        } else if (runCatching { UUID.fromString(profile.uuid) }.isFailure) return "invalid_credential"
        return if (EndpointSecurityPolicy.isAllowedProfile(profile)) null else "security_policy_rejected"
    }
}
