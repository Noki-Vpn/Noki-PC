package com.noki.vpn.ui
import com.noki.vpn.data.*
import java.time.*
import java.time.temporal.TemporalAdjusters
import java.util.Locale

internal enum class StatsPeriod {
    TODAY,
    WEEK,
    MONTH,
    YEAR,
}

internal data class StatsAggregate(
    val rxBytes: Long = 0L,
    val txBytes: Long = 0L,
    val onlineSeconds: Long = 0L,
    val sessions: Int = 0,
    val pingSumMs: Long = 0L,
    val pingSamples: Int = 0,
) {
    val totalBytes: Long
        get() = (rxBytes + txBytes).coerceAtLeast(0L)

    fun averagePingLabel(): String {
        if (pingSamples <= 0) return "--"
        return "${(pingSumMs / pingSamples).coerceAtLeast(0L)} ms"
    }
}

internal data class StatsChartBar(
    val label: String,
    val bytes: Long,
    val isToday: Boolean,
)

internal fun statsPeriodLabel(period: StatsPeriod, language: AppLanguage): String {
    return when (period) {
        StatsPeriod.TODAY -> tr(language, "За сегодня", "Today")
        StatsPeriod.WEEK -> tr(language, "За неделю", "This week")
        StatsPeriod.MONTH -> tr(language, "За месяц", "This month")
        StatsPeriod.YEAR -> tr(language, "За год", "This year")
    }
}

internal fun aggregateDailyStats(
    stats: List<DailyStats>,
    period: StatsPeriod,
    today: LocalDate,
): StatsAggregate {
    val start = when (period) {
        StatsPeriod.TODAY -> today
        StatsPeriod.WEEK -> today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        StatsPeriod.MONTH -> today.withDayOfMonth(1)
        StatsPeriod.YEAR -> today.withDayOfYear(1)
    }
    return stats
        .asSequence()
        .mapNotNull { day -> parseDailyStatsDate(day)?.let { it to day } }
        .filter { (date, _) -> !date.isBefore(start) && !date.isAfter(today) }
        .fold(StatsAggregate()) { acc, (_, day) ->
            acc.copy(
                rxBytes = acc.rxBytes + day.rxBytes,
                txBytes = acc.txBytes + day.txBytes,
                onlineSeconds = acc.onlineSeconds + day.onlineSeconds,
                sessions = acc.sessions + day.sessions,
                pingSumMs = acc.pingSumMs + day.pingSumMs,
                pingSamples = acc.pingSamples + day.pingSamples,
            )
        }
}

internal fun buildWeeklyTrafficBars(
    stats: List<DailyStats>,
    today: LocalDate,
    language: AppLanguage,
): List<StatsChartBar> {
    val statsByDate = stats.associateBy { it.date }
    val monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    return (0L..6L).map { offset ->
        val date = monday.plusDays(offset)
        val dayStats = statsByDate[date.toString()]
        StatsChartBar(
            label = weekdayLabel(date.dayOfWeek, language),
            bytes = ((dayStats?.rxBytes ?: 0L) + (dayStats?.txBytes ?: 0L)).coerceAtLeast(0L),
            isToday = date == today,
        )
    }
}

internal fun weekdayLabel(dayOfWeek: DayOfWeek, language: AppLanguage): String {
    if (language == AppLanguage.EN) {
        return when (dayOfWeek) {
            DayOfWeek.MONDAY -> "Mon"
            DayOfWeek.TUESDAY -> "Tue"
            DayOfWeek.WEDNESDAY -> "Wed"
            DayOfWeek.THURSDAY -> "Thu"
            DayOfWeek.FRIDAY -> "Fri"
            DayOfWeek.SATURDAY -> "Sat"
            DayOfWeek.SUNDAY -> "Sun"
        }
    }
    return when (dayOfWeek) {
        DayOfWeek.MONDAY -> "Пн"
        DayOfWeek.TUESDAY -> "Вт"
        DayOfWeek.WEDNESDAY -> "Ср"
        DayOfWeek.THURSDAY -> "Чт"
        DayOfWeek.FRIDAY -> "Пт"
        DayOfWeek.SATURDAY -> "Сб"
        DayOfWeek.SUNDAY -> "Вс"
    }
}

internal fun parseDailyStatsDate(stats: DailyStats): LocalDate? {
    return runCatching { LocalDate.parse(stats.date) }.getOrNull()
}

internal fun formatStatsBytes(bytes: Long, language: AppLanguage): String =
    com.noki.vpn.data.TrafficFormat.bytes(bytes, language).label

fun formatStatsDuration(seconds: Long, language: AppLanguage): String {
    val totalSeconds = seconds.coerceAtLeast(0L)
    val totalMinutes = totalSeconds / 60L
    val totalHours = totalMinutes / 60L
    val secondsPart = totalSeconds % 60L
    val minutesPart = totalMinutes % 60L
    val totalDays = totalHours / 24L
    if (totalDays >= 30L) {
        val months = (totalDays / 30L).coerceAtLeast(1L)
        return if (language == AppLanguage.RU) "$months мес." else "$months mo"
    }
    if (totalDays >= 1L) {
        return if (language == AppLanguage.RU) "$totalDays д." else "$totalDays d"
    }
    if (totalHours >= 1L) {
        return "%02d:%02d".format(Locale.US, totalHours, minutesPart)
    }
    return "%02d:%02d".format(Locale.US, minutesPart, secondsPart)
}
