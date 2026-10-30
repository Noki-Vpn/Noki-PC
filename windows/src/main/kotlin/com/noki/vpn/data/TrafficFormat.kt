package com.noki.vpn.data

import java.util.Locale

internal object TrafficFormat {
    // The backend's traffic_limit_gb and byte counters use binary units.
    const val BYTES_PER_GB = 1024.0 * 1024.0 * 1024.0

    data class Amount(val value: String, val unit: String) {
        val label: String get() = "$value $unit"
    }

    fun gigabytes(value: Double?, language: AppLanguage): Amount {
        val valid = value?.takeIf { it.isFinite() && it >= 0.0 }
        val terabytes = valid != null && valid >= 1024.0
        val unit = if (language == AppLanguage.RU) {
            if (terabytes) "ТБ" else "ГБ"
        } else {
            if (terabytes) "TB" else "GB"
        }
        return Amount(number(valid?.let { if (terabytes) it / 1024.0 else it }, language), unit)
    }

    fun bytes(value: Long, language: AppLanguage): Amount {
        val gb = value.coerceAtLeast(0L) / BYTES_PER_GB
        if (gb >= 1.0) return gigabytes(gb, language)
        return Amount(number(gb * 1024.0, language), if (language == AppLanguage.RU) "МБ" else "MB")
    }

    private fun number(value: Double?, language: AppLanguage): String {
        if (value == null) return "--"
        val raw = String.format(Locale.US, when {
            value >= 100.0 -> "%.0f"
            value >= 10.0 -> "%.1f"
            else -> "%.2f"
        }, value)
        val trimmed = if (raw.contains('.')) raw.trimEnd('0').trimEnd('.') else raw
        return if (language == AppLanguage.RU) trimmed.replace('.', ',') else trimmed
    }
}
