package com.noki.vpn.ui

import com.noki.vpn.AppUiState
import com.noki.vpn.SettingsPreparedStatePolicy
import com.noki.vpn.data.AppLanguage
import com.noki.vpn.data.DailyStats
import com.noki.vpn.data.TrafficFormat
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

internal enum class AccountPlanTitleStyle {
    BackendColor,
    BackendMetallic,
    PremiumMetallic,
}

internal data class AccountPresentation(
    val username: String,
    val email: String,
    val avatarUri: String?,
    val isInvitedDevice: Boolean,
    val planTitle: String,
    val planColorArgb: Long,
    val planTitleStyle: AccountPlanTitleStyle,
    val isFree: Boolean,
    val expirationDateLabel: String,
    val usageLabel: String,
    val remainingPercent: Int,
    val remainingFraction: Float,
    val remainingExplanation: String,
    val usedExplanation: String,
    val settingsTraffic: SettingsTrafficPresentation,
)

internal sealed interface SettingsTrafficPresentation {
    data class FreeUpgrade(
        val usageLabel: String,
        val actionLabel: String,
    ) : SettingsTrafficPresentation

    data class PaidStats(
        val title: String,
        val detailsLabel: String,
        val todayLabel: String,
        val todayCaption: String,
        val lastThirtyDaysLabel: String,
        val lastThirtyDaysCaption: String,
    ) : SettingsTrafficPresentation
}

internal object AccountPresentationPolicy {
    fun prepare(
        state: AppUiState,
        today: LocalDate = LocalDate.now(),
    ): AccountPresentation {
        val language = state.personalizationSettings.language
        val settings = SettingsPreparedStatePolicy.prepare(state)
        val isFree = SettingsPreparedStatePolicy.isFreePlan(state)
        val used = validTraffic(state.userProfile.trafficUsedGb)
        val limit = validTraffic(state.userProfile.trafficLimitGb)
        val unlimited = !isFree && limit == null
        val remainingFraction = when {
            unlimited -> 1f
            limit == null || limit <= 0.0 -> 0f
            used == null -> 1f
            else -> (1.0 - used / limit).coerceIn(0.0, 1.0).toFloat()
        }
        val remainingPercent = (remainingFraction * 100f).roundToInt().coerceIn(0, 100)
        val usedAmount = TrafficFormat.gigabytes(used, language)
        val limitAmount = if (unlimited) TrafficFormat.Amount("∞", usedAmount.unit)
            else TrafficFormat.gigabytes(limit, language)
        val usageLabel = "${usedAmount.label} / ${limitAmount.label}"
        val todayBytes = aggregateUsage(state.dailyStats, today, today)
        val lastThirtyDaysBytes = aggregateUsage(state.dailyStats, today.minusDays(29), today)

        val settingsTraffic = if (isFree) {
            SettingsTrafficPresentation.FreeUpgrade(
                usageLabel = usageLabel,
                actionLabel = tr(language, "Перейти на Plus", "Go Plus"),
            )
        } else {
            SettingsTrafficPresentation.PaidStats(
                title = tr(language, "Статистика использования", "Usage statistics"),
                detailsLabel = tr(language, "Подробнее >", "Details >"),
                todayLabel = TrafficFormat.gigabytes(todayBytes / TrafficFormat.BYTES_PER_GB, language).label,
                todayCaption = tr(language, "сегодня", "today"),
                lastThirtyDaysLabel = TrafficFormat.gigabytes(lastThirtyDaysBytes / TrafficFormat.BYTES_PER_GB, language).label,
                lastThirtyDaysCaption = tr(language, "за 30 дней", "in 30 days"),
            )
        }

        return AccountPresentation(
            username = settings.username,
            email = settings.email,
            avatarUri = settings.avatarUri,
            isInvitedDevice = settings.isInvitedDevice,
            planTitle = settings.planTitle,
            planColorArgb = settings.profileGradientColorArgb,
            planTitleStyle = planTitleStyle(state),
            isFree = isFree,
            expirationDateLabel = expirationDateLabel(
                state.userProfile.subscriptionExpiresAt,
                isFree,
            ),
            usageLabel = usageLabel,
            remainingPercent = remainingPercent,
            remainingFraction = remainingFraction,
            remainingExplanation = tr(
                language,
                "Осталось: $remainingPercent% трафика",
                "Remaining: $remainingPercent% traffic",
            ),
            usedExplanation = tr(
                language,
                "Израсходовано: ${usedAmount.label} из ${limitAmount.label}",
                "Used: ${usedAmount.label} of ${limitAmount.label}",
            ),
            settingsTraffic = settingsTraffic,
        )
    }

    internal fun aggregateUsage(
        stats: List<DailyStats>,
        start: LocalDate,
        end: LocalDate,
    ): Long = stats.asSequence()
        .mapNotNull { item ->
            runCatching { LocalDate.parse(item.date) }.getOrNull()?.let { it to item.totalBytes }
        }
        .filter { (date, _) -> !date.isBefore(start) && !date.isAfter(end) }
        .sumOf { (_, bytes) -> bytes.coerceAtLeast(0L) }

    private fun planTitleStyle(state: AppUiState): AccountPlanTitleStyle {
        return if (SettingsPreparedStatePolicy.isFreePlan(state)) {
            AccountPlanTitleStyle.BackendColor
        } else if (SettingsPreparedStatePolicy.currentPlanTier(state) == "premium") {
            AccountPlanTitleStyle.PremiumMetallic
        } else {
            AccountPlanTitleStyle.BackendMetallic
        }
    }

    private fun expirationDateLabel(expiresAt: String?, isFree: Boolean): String {
        if (isFree) return "--"
        val instant = parseInstant(expiresAt) ?: return "--"
        val date = instant.atZone(ZoneId.systemDefault()).toLocalDate()
        return date.format(DateTimeFormatter.ofPattern("dd.MM.yyyy"))
    }

    private fun parseInstant(value: String?): Instant? {
        if (value.isNullOrBlank()) return null
        return runCatching { OffsetDateTime.parse(value).toInstant() }
            .getOrElse { runCatching { Instant.parse(value) }.getOrNull() }
    }

    private fun validTraffic(value: Double?): Double? =
        value?.takeIf { it.isFinite() && it >= 0.0 }

    private fun tr(language: AppLanguage, russian: String, english: String): String =
        if (language == AppLanguage.RU) russian else english

}
