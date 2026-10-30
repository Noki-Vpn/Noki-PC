package com.noki.vpn.data

import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalDateTime

internal object DailyStatsStore {
    fun decode(raw: String?, today: LocalDate = LocalDate.now()): List<DailyStats> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val json = array.optJSONObject(index) ?: continue
                    val date = json.optString("date").takeIf { it.isNotBlank() } ?: continue
                    fun hours(key: String): List<Long> {
                        val values = json.optJSONArray(key) ?: return emptyList()
                        if (date != today.toString() || values.length() != 24) return emptyList()
                        return List(24) { values.optLong(it, 0L).coerceAtLeast(0L) }
                    }
                    add(DailyStats(
                        date = date,
                        rxBytes = json.optLong("rxBytes", 0L).coerceAtLeast(0L),
                        txBytes = json.optLong("txBytes", 0L).coerceAtLeast(0L),
                        onlineSeconds = json.optLong("onlineSeconds", 0L).coerceAtLeast(0L),
                        sessions = json.optInt("sessions", 0).coerceAtLeast(0),
                        pingSumMs = json.optLong("pingSumMs", 0L).coerceAtLeast(0L),
                        pingSamples = json.optInt("pingSamples", 0).coerceAtLeast(0),
                        hourlyRxBytes = hours("hourlyRxBytes"),
                        hourlyTxBytes = hours("hourlyTxBytes"),
                    ))
                }
            }
        }.getOrDefault(emptyList()).let { prune(it, today) }
    }

    fun encode(stats: List<DailyStats>): String = JSONArray().apply {
        stats.sortedBy { it.date }.forEach { day ->
            put(JSONObject()
                .put("date", day.date)
                .put("rxBytes", day.rxBytes)
                .put("txBytes", day.txBytes)
                .put("onlineSeconds", day.onlineSeconds)
                .put("sessions", day.sessions)
                .put("pingSumMs", day.pingSumMs)
                .put("pingSamples", day.pingSamples).also {
                    if (day.hourlyRxBytes.isNotEmpty()) it.put("hourlyRxBytes", JSONArray(day.hourlyRxBytes))
                    if (day.hourlyTxBytes.isNotEmpty()) it.put("hourlyTxBytes", JSONArray(day.hourlyTxBytes))
                })
        }
    }.toString()

    fun prune(stats: List<DailyStats>, today: LocalDate = LocalDate.now()): List<DailyStats> {
        val cutoff = today.minusDays(RETENTION_DAYS)
        return stats.filter { day ->
            runCatching { !LocalDate.parse(day.date).isBefore(cutoff) }.getOrDefault(false)
        }.map { day ->
            if (day.date != today.toString() && (day.hourlyRxBytes.isNotEmpty() || day.hourlyTxBytes.isNotEmpty()))
                day.copy(hourlyRxBytes = emptyList(), hourlyTxBytes = emptyList()) else day
        }.sortedBy { it.date }
    }

    fun recordTraffic(stats: List<DailyStats>, rx: Long, tx: Long, onlineSeconds: Long,
        sampledAt: LocalDateTime, includeHourly: Boolean): List<DailyStats> {
        require(rx >= 0 && tx >= 0 && onlineSeconds >= 0)
        val date = sampledAt.toLocalDate().toString()
        val day = stats.firstOrNull { it.date == date } ?: DailyStats(date)
        fun hours(values: List<Long>, bytes: Long): List<Long> = if (!includeHourly) values else
            List(24) { (values.getOrNull(it) ?: 0L) + if (it == sampledAt.hour) bytes else 0L }
        val updated = day.copy(rxBytes = day.rxBytes + rx, txBytes = day.txBytes + tx,
            onlineSeconds = day.onlineSeconds + onlineSeconds,
            hourlyRxBytes = hours(day.hourlyRxBytes, rx), hourlyTxBytes = hours(day.hourlyTxBytes, tx))
        return stats.mapNotNull { previous ->
            when {
                previous.date == date -> null
                previous.hourlyRxBytes.isNotEmpty() || previous.hourlyTxBytes.isNotEmpty() ->
                    previous.copy(hourlyRxBytes = emptyList(), hourlyTxBytes = emptyList())
                else -> previous
            }
        } + updated
    }

    private const val RETENTION_DAYS = 370L
}
