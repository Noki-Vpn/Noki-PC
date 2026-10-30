package com.noki.desktop
import com.noki.vpn.data.*
import com.noki.vpn.data.EndpointHealthScope.storageKey
internal object DesktopRouteHealthScope {
    fun routeKey(
        networkKind: EndpointRankingPolicy.NetworkKind,
        runtime: BackendMultiHopSession,
        endpointCode: String,
    ): String = "${networkKind.storageKey()}|mh|${runtime.entryNodeId}|${runtime.exitNodeId}|${endpointCode.trim()}"

    fun forRoute(
        raw: Map<String, EndpointHealth>,
        networkKind: EndpointRankingPolicy.NetworkKind,
        runtime: BackendMultiHopSession,
    ): Map<String, EndpointHealth> {
        val prefix = "${networkKind.storageKey()}|mh|${runtime.entryNodeId}|${runtime.exitNodeId}|"
        return raw.mapNotNull { (key, value) ->
            if (key.startsWith(prefix)) key.removePrefix(prefix) to value else null
        }.toMap()
    }

}
