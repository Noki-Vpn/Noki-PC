package com.noki.vpn.data

import org.json.JSONObject

data class RuRelayCatalogEntry(
    val id: String,
    val name: String,
    val countryCode: String,
    val available: Boolean,
    val unavailableReason: String?,
    val probeHost: String? = null,
    val probePort: Int? = null,
    val pairReady: Boolean = false,
)

/** Eligible physical overlay participants; no location or proxy listener is invented. */
data class RuForeignNode(val id: String, val name: String, val countryCode: String, val latencyMs: Int? = null)

data class RuRelayCatalog(
    val exitNodeId: String?,
    val relays: List<RuRelayCatalogEntry>,
    val entryNodeId: String? = null,
    val routeVersion: Int = 1,
    val foreignNodes: List<RuForeignNode> = emptyList(),
)

object RuRelayCatalogJsonParser {
    fun parse(json: JSONObject, expectedExitNodeId: String?, expectedEntryNodeId: String? = null,
              expectedRouteVersion: Int = 1): RuRelayCatalog {
        require(expectedRouteVersion == 1 && json.opt("route_version") == expectedRouteVersion) {
            "ru_route_version_invalid"
        }
        require(json.has("entry_node_id") && json.has("exit_node_id")) { "ru_route_entry_missing" }
        val entryNodeId = if (json.isNull("entry_node_id")) null else canonicalRuUuid(json.getString("entry_node_id"))
        val exitNodeId = if (json.isNull("exit_node_id")) null else canonicalRuUuid(json.getString("exit_node_id"))
        require(exitNodeId == expectedExitNodeId?.let(::canonicalRuUuid) &&
            entryNodeId == expectedEntryNodeId?.let(::canonicalRuUuid) &&
            (entryNodeId == null || entryNodeId != exitNodeId) && (exitNodeId != null || entryNodeId == null)) {
            "ru_relay_catalog_route_mismatch"
        }
        val items = json.getJSONArray("relays")
        val relays = (0 until items.length()).map { index ->
            val item = items.getJSONObject(index)
            val id = canonicalRuUuid(item.getString("id"))
            require(id != entryNodeId && id != exitNodeId) { "ru_route_identity_invalid" }
            val name = item.getString("name").trim()
            val countryCode = item.getString("country_code").uppercase()
            val available = item.opt("available") as? Boolean
                ?: throw IllegalArgumentException("ru_relay_catalog_entry_invalid")
            val pairReady = if (item.has("pair_ready")) item.opt("pair_ready") as? Boolean
                ?: throw IllegalArgumentException("ru_relay_catalog_entry_invalid") else false
            val unavailableReason = item.optString("unavailable_reason")
                .takeIf { it.isNotBlank() && it != "null" }
            val probeHost = item.optString("probe_host").trim().takeIf { value ->
                available && pairReady && value.split('.').let { parts ->
                    parts.size == 4 && parts.all { part ->
                        part.isNotEmpty() && part.all(Char::isDigit) &&
                            part.toIntOrNull()?.let { it in 0..255 } == true
                    }
                }
            }
            require(name.isNotEmpty() && countryCode == "RU") { "ru_relay_catalog_entry_invalid" }
            require(available || unavailableReason != null) { "ru_relay_catalog_reason_missing" }
            val probePort = item.optInt("probe_port", 0).takeIf { probeHost != null && it in 1..65535 }
            RuRelayCatalogEntry(id, name, countryCode, available, unavailableReason, probeHost, probePort, pairReady)
        }
        require(relays.map { it.id }.toSet().size == relays.size) { "ru_relay_catalog_duplicate" }
        require(exitNodeId != null || relays.isEmpty()) { "ru_relay_catalog_route_mismatch" }
        require(exitNodeId != null || json.optJSONArray("foreign_nodes") != null) { "ru_foreign_nodes_missing" }
        val foreignItems = json.optJSONArray("foreign_nodes")
        val foreignNodes = (0 until (foreignItems?.length() ?: 0)).map { index ->
            val item = foreignItems!!.getJSONObject(index)
            val id = canonicalRuUuid(item.getString("id"))
            val name = item.getString("name").trim()
            val country = item.getString("country_code").uppercase()
            require(name.isNotEmpty() && country.isNotBlank() && country != "RU") {
                "ru_foreign_node_invalid"
            }
            RuForeignNode(id, name, country)
        }
        require(foreignNodes.map { it.id }.toSet().size == foreignNodes.size) { "ru_foreign_node_duplicate" }
        return RuRelayCatalog(exitNodeId, relays, entryNodeId, expectedRouteVersion, foreignNodes)
    }
}
