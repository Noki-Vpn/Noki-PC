package com.noki.vpn.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
internal val HomeBgBase = Color(0xFF07111A)
internal val HomeSimpleBg = Color(0xFF080B10)
internal val HomeBgLighter = Color(0xFF0D1B2A)
internal val HomeTextPrimary = Color(0xFFF4FBFF)
internal val HomeTextSecondary = Color(0xFF9FB6C5)
internal val HomeAccentPrimary = Color(0xFF7AE7C7)
internal val HomeAccentStrong = Color(0xFF42D6A4)
internal val HomeWarning = Color(0xFFFF6B6B)
internal val HomeStroke = Color(0xFF29404E)
internal val HomeNoFontPaddingTextStyle = TextStyle(platformStyle = nokiPlatformTextStyle(includeFontPadding = true))
internal const val HOME_SERVER_DROPDOWN_EXIT_DURATION_MS = 220
internal const val HOME_SERVER_DROPDOWN_EXIT_FADE_DELAY_MS = 140
internal const val HOME_SERVER_DROPDOWN_EXIT_FADE_DURATION_MS = 80
internal const val HOME_SERVER_DROPDOWN_BACKDROP_KEEP_ALIVE_MS = 260L
internal const val HOME_SERVER_DROPDOWN_FIRST_ITEM_TOP_DP = 68f
internal const val HOME_SERVER_DROPDOWN_HANDLE_VISIBLE_ALPHA = 0.96f
internal const val HOME_SERVER_DROPDOWN_HANDLE_FADE_START_FRACTION = 0.5f

internal fun shouldDrawHomeBackgroundEffects(liveGlassEnabled: Boolean): Boolean = liveGlassEnabled

internal fun homeBackgroundColor(liveGlassEnabled: Boolean): Color =
    if (liveGlassEnabled) HomeBgBase else HomeSimpleBg

internal data class HomeMetricsSnapshot(
    val download: String?,
    val upload: String?,
    val latency: String?,
    val sessionBytes: Long?,
)

internal enum class HomeMetricIcon {
    Upload,
    Download,
    Latency,
    Traffic,
}

internal data class HomeMetricRowUi(
    val title: String,
    val value: String,
    val unit: String,
    val icon: HomeMetricIcon,
)

internal data class HomeDeviceTrafficSnapshot(
    val downloadMbps: String? = null,
    val uploadMbps: String? = null,
    val sessionBytes: Long? = null,
)
