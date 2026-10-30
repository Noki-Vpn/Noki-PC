package com.noki.vpn.data
import java.util.Locale

object ServerLocationMapper {
fun latencyTargetKey(location: ServerLocation): String? =
    latencyTargetKey(location.code, location.host)

fun latencyTargetKey(server: VpnServer): String? =
    latencyTargetKey(server.id, "${server.host}:${server.probePort ?: 0}")

fun latencyTargetKey(node: RuForeignNode): String = "ru:${node.id}"

fun withLatencies(location: ServerLocation, samples: Map<String, Int>): ServerLocation {
    val measured = location.servers.map { server -> server.copy(latencyMs = latencyTargetKey(server)?.let(samples::get)) }
    return location.copy(
        servers = measured,
        latencyMs = if (measured.isNotEmpty()) measured.filter { it.isOnline }.mapNotNull { it.latencyMs }.minOrNull()
            else latencyTargetKey(location)?.let(samples::get),
    )
}

fun latencyTargetKey(codeValue: String, hostValue: String): String? {
    val code = codeValue.trim().uppercase(Locale.ROOT)
    val host = hostValue.trim().lowercase(Locale.ROOT)
    if (code.isBlank() || host.isBlank()) return null
    return "$code\u0000$host"
}

    fun mapLocations(
        locations: List<BackendLocation>,
        language: AppLanguage,
        clientLatencyByTarget: Map<String, Int>,
    ): List<ServerLocation> {
        return locations
            .filter { it.countryCode.isNotBlank() }
            .groupBy { it.countryCode.trim().uppercase(Locale.ROOT) }
            .map { (countryCode, members) ->
            val onlineMembers = members.filter { it.isOnline }
            val selectableMembers = onlineMembers.ifEmpty { members }
            val representative = selectableMembers.minByOrNull { it.loadPercent ?: Int.MAX_VALUE }
                ?: members.first()
            val targetKey = latencyTargetKey(countryCode, representative.entryHost)
            val servers = members.flatMap { it.servers }.distinctBy { it.id }.map { server ->
                server.copy(latencyMs = latencyTargetKey(server)?.let(clientLatencyByTarget::get))
            }
            val capacities = onlineMembers.mapNotNull { it.capacityMbps }
            val downloads = onlineMembers.mapNotNull { it.downloadMbps }
            val uploads = onlineMembers.mapNotNull { it.uploadMbps }
            ServerLocation(
                code = countryCode,
                countryCode = countryCode,
                country = members.firstNotNullOfOrNull { location ->
                    (if (language == AppLanguage.RU) location.nameRu else location.nameEn)
                        ?.trim()?.takeIf { it.isNotEmpty() }
                } ?: Locale("", countryCode).getDisplayCountry(Locale(language.tag)).ifBlank { countryCode },
                city = "",
                host = representative.entryHost,
                capacityMbps = capacities.takeIf { it.isNotEmpty() }?.sum(),
                downloadMbps = downloads.takeIf { it.isNotEmpty() }?.sum(),
                uploadMbps = uploads.takeIf { it.isNotEmpty() }?.sum(),
                latencyMs = if (servers.isNotEmpty()) servers.filter { it.isOnline }.mapNotNull { it.latencyMs }.minOrNull()
                    else targetKey?.let(clientLatencyByTarget::get),
                loadPercent = onlineMembers.mapNotNull { it.loadPercent }.minOrNull(),
                isOnline = if (servers.isNotEmpty()) servers.any { it.isOnline } else onlineMembers.isNotEmpty(),
                servers = servers,
            )
        }.sortedBy { it.country.lowercase(Locale.ROOT) }
    }

}
