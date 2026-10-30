package com.noki.vpn.data

import java.util.Locale

object EndpointHealthScope {
    fun key(
        networkKind: EndpointRankingPolicy.NetworkKind,
        endpointCode: String,
    ): String = "${networkKind.storageKey()}|${endpointCode.trim()}"

    fun forNetwork(
        raw: Map<String, EndpointHealth>,
        networkKind: EndpointRankingPolicy.NetworkKind,
    ): Map<String, EndpointHealth> {
        val prefix = "${networkKind.storageKey()}|"
        val scoped = raw.mapNotNull { (key, value) ->
            if (key.startsWith(prefix)) key.removePrefix(prefix).takeIf { '|' !in it }?.let { it to value }
                else null
        }.toMap().toMutableMap()

        raw.forEach { (key, value) ->
            if (!isScopedKey(key) && key !in scoped) {
                scoped[key] = value
            }
        }
        return scoped
    }

    fun EndpointRankingPolicy.NetworkKind.storageKey(): String {
        return name.lowercase(Locale.ROOT)
    }

    private fun isScopedKey(key: String): Boolean {
        return EndpointRankingPolicy.NetworkKind.entries.any { kind ->
            key.startsWith("${kind.storageKey()}|")
        }
    }
}
