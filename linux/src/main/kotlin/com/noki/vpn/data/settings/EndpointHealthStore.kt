package com.noki.vpn.data

import org.json.JSONArray
import org.json.JSONObject

internal object EndpointHealthStore {
    fun decode(raw: String?): Map<String, EndpointHealth> {
        if (raw.isNullOrBlank()) return emptyMap()
        return runCatching {
            val array = JSONArray(raw)
            buildMap {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val code = item.optString("code").takeIf { it.isNotBlank() } ?: continue
                    put(code, EndpointHealth(
                        score = item.optInt("score", 70).coerceIn(0, 100),
                        successCount = item.optInt("successCount", 0).coerceAtLeast(0),
                        failureCount = item.optInt("failureCount", 0).coerceAtLeast(0),
                        cooldownUntilMillis = item.optLong("cooldownUntilMillis", 0L).coerceAtLeast(0L),
                        lastUpdatedAtMillis = item.optLong("lastUpdatedAtMillis", 0L).coerceAtLeast(0L),
                        latencyEwmaMs = item.optLong("latencyEwmaMs", -1L).takeIf { it >= 0L },
                        latencyUpdatedAtMillis = item.optLong("latencyUpdatedAtMillis", 0L).coerceAtLeast(0L),
                    ))
                }
            }
        }.getOrDefault(emptyMap())
    }

    fun encode(health: Map<String, EndpointHealth>): String = JSONArray().apply {
        health.toSortedMap().forEach { (code, state) ->
            if (code.isBlank()) return@forEach
            put(JSONObject()
                .put("code", code)
                .put("score", state.score.coerceIn(0, 100))
                .put("successCount", state.successCount.coerceAtLeast(0))
                .put("failureCount", state.failureCount.coerceAtLeast(0))
                .put("cooldownUntilMillis", state.cooldownUntilMillis.coerceAtLeast(0L))
                .put("lastUpdatedAtMillis", state.lastUpdatedAtMillis.coerceAtLeast(0L))
                .put("latencyEwmaMs", state.latencyEwmaMs ?: -1L)
                .put("latencyUpdatedAtMillis", state.latencyUpdatedAtMillis.coerceAtLeast(0L)))
        }
    }.toString()
}
