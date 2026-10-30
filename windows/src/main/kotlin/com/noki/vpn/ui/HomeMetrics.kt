package com.noki.vpn.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.noki.vpn.R
import com.noki.vpn.data.AppLanguage
import com.noki.vpn.data.ServerLocation
import com.noki.vpn.data.VpnConnectionState
import com.kyant.backdrop.backdrops.LayerBackdrop
import java.util.Locale
import kotlinx.coroutines.delay
@Composable
internal fun HomeMetricsPanel(
    modifier: Modifier,
    scale: Float,
    language: AppLanguage,
    metrics: HomeMetricsSnapshot,
    backdrop: LayerBackdrop?,
    liveGlassEnabled: Boolean,
) {
    val speedUnit = "Mb/s"
    val traffic = com.noki.vpn.data.TrafficFormat.bytes(metrics.sessionBytes ?: 0L, AppLanguage.EN)
    Row(
        modifier = modifier
            .fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(
            space = designDp(NokiUiKitPolicy.homeMetricsColumnGapDp, scale),
            alignment = Alignment.CenterHorizontally,
        ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HomeMetricColumn(
            top = HomeMetricRowUi(
                title = tr(language, "Отдача", "Upload"),
                value = metrics.upload ?: "--",
                unit = speedUnit,
                icon = HomeMetricIcon.Upload,
            ),
            bottom = HomeMetricRowUi(
                title = tr(language, "Загрузка", "Download"),
                value = metrics.download ?: "--",
                unit = speedUnit,
                icon = HomeMetricIcon.Download,
            ),
            scale = scale,
            backdrop = backdrop,
            liveGlassEnabled = liveGlassEnabled,
        )
        HomeMetricColumn(
            top = HomeMetricRowUi(
                title = tr(language, "Задержка", "Latency"),
                value = metrics.latency ?: "--",
                unit = "ms",
                icon = HomeMetricIcon.Latency,
            ),
            bottom = HomeMetricRowUi(
                title = tr(language, "Трафик", "Traffic"),
                value = traffic.value.takeIf { metrics.sessionBytes != null } ?: "--",
                unit = traffic.unit.replace('B', 'b'),
                icon = HomeMetricIcon.Traffic,
            ),
            scale = scale,
            backdrop = backdrop,
            liveGlassEnabled = liveGlassEnabled,
        )
    }
}

@Composable
internal fun HomeMetricColumn(
    top: HomeMetricRowUi,
    bottom: HomeMetricRowUi,
    scale: Float,
    backdrop: LayerBackdrop?,
    liveGlassEnabled: Boolean,
) {
    HomeSettingsGlassSurface(
        modifier = Modifier
            .width(designDp(NokiUiKitPolicy.homeMetricsColumnWidthDp, scale))
            .height(designDp(NokiUiKitPolicy.homeMetricsHeightDp, scale)),
        backdrop = backdrop,
        liveGlassEnabled = liveGlassEnabled,
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize()) {
                HomeMetricSegment(
                    row = top,
                    scale = scale,
                )
                HomeMetricSegment(
                    row = bottom,
                    scale = scale,
                )
            }
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .fillMaxWidth()
                    .height(designDp(1f, scale))
                    .padding(horizontal = designDp(NokiUiKitPolicy.homeMetricsDividerInsetDp, scale))
                    .background(HomeStroke),
            )
        }
    }
}

@Composable
internal fun HomeMetricSegment(
    row: HomeMetricRowUi,
    scale: Float,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(designDp(NokiUiKitPolicy.homeMetricsRowHeightDp, scale))
            .padding(
                horizontal = designDp(15f, scale),
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HomeMetricIconCanvas(
            icon = row.icon,
            modifier = Modifier
                .size(designDp(14f, scale))
                .offset(y = metricIconOffsetY(row.icon, scale)),
            scale = scale,
        )
        Spacer(modifier = Modifier.width(metricIconGap(row.icon, scale)))
        Text(
            text = row.title,
            color = HomeTextSecondary,
            fontFamily = ManropeFontFamily,
            fontWeight = FontWeight.Normal,
            fontSize = designSp(12f, scale),
            letterSpacing = 0.12.sp,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
            style = TextStyle(platformStyle = nokiPlatformTextStyle(includeFontPadding = false)),
            modifier = Modifier.weight(1f),
        )
        Spacer(modifier = Modifier.width(metricValueGap(row.icon, scale)))
        Text(
            text = row.value,
            color = HomeTextPrimary,
            fontFamily = ManropeFontFamily,
            fontWeight = FontWeight.Normal,
            fontSize = designSp(14f, scale),
            letterSpacing = 0.14.sp,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
            style = TextStyle(platformStyle = nokiPlatformTextStyle(includeFontPadding = false)),
        )
        Spacer(modifier = Modifier.width(designDp(4f, scale)))
        Text(
            text = row.unit,
            color = HomeTextSecondary,
            fontFamily = ManropeFontFamily,
            fontWeight = FontWeight.Normal,
            fontSize = designSp(12f, scale),
            letterSpacing = 0.12.sp,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
            style = TextStyle(platformStyle = nokiPlatformTextStyle(includeFontPadding = false)),
        )
    }
}

internal fun metricIconGap(icon: HomeMetricIcon, scale: Float): Dp {
    return when (icon) {
        HomeMetricIcon.Upload,
        HomeMetricIcon.Download -> designDp(8f, scale)
        HomeMetricIcon.Latency -> designDp(6f, scale)
        HomeMetricIcon.Traffic -> designDp(7f, scale)
    }
}

internal fun metricIconOffsetY(icon: HomeMetricIcon, scale: Float): Dp {
    return when (icon) {
        HomeMetricIcon.Latency -> -designDp(2f, scale)
        HomeMetricIcon.Upload,
        HomeMetricIcon.Download,
        HomeMetricIcon.Traffic -> 0.dp
    }
}

internal fun metricValueGap(icon: HomeMetricIcon, scale: Float): Dp {
    return when (icon) {
        HomeMetricIcon.Upload,
        HomeMetricIcon.Download -> designDp(8f, scale)
        HomeMetricIcon.Latency -> designDp(10f, scale)
        HomeMetricIcon.Traffic -> designDp(12f, scale)
    }
}

@Composable
internal fun HomeMetricIconCanvas(
    icon: HomeMetricIcon,
    modifier: Modifier,
    scale: Float,
) {
    Canvas(modifier = modifier) {
        when (icon) {
            HomeMetricIcon.Upload -> drawTrafficArrow(up = true, scale = scale)
            HomeMetricIcon.Download -> drawTrafficArrow(up = false, scale = scale)
            HomeMetricIcon.Latency -> drawLatencyBars(scale = scale)
            HomeMetricIcon.Traffic -> drawSessionTraffic(scale = scale)
        }
    }
}

internal fun DrawScope.drawTrafficArrow(up: Boolean, scale: Float) {
    val stroke = designDp(1.5f, scale).toPx()
    val color = HomeAccentPrimary
    val topY = size.height * 0.18f
    val bottomY = size.height * 0.82f
    val shaftStart = if (up) bottomY else topY
    val shaftEnd = if (up) topY else bottomY
    drawLine(
        color = color,
        start = Offset(size.width * 0.5f, shaftStart),
        end = Offset(size.width * 0.5f, shaftEnd),
        strokeWidth = stroke,
        cap = StrokeCap.Round,
    )
    val headY = shaftEnd
    val wingY = if (up) headY + size.height * 0.22f else headY - size.height * 0.22f
    drawLine(
        color = color,
        start = Offset(size.width * 0.5f, headY),
        end = Offset(size.width * 0.24f, wingY),
        strokeWidth = stroke,
        cap = StrokeCap.Round,
    )
    drawLine(
        color = color,
        start = Offset(size.width * 0.5f, headY),
        end = Offset(size.width * 0.76f, wingY),
        strokeWidth = stroke,
        cap = StrokeCap.Round,
    )
}

internal fun DrawScope.drawLatencyBars(scale: Float) {
    val barWidth = designDp(2.2f, scale).toPx()
    val corner = CornerRadius(barWidth / 2f, barWidth / 2f)
    val bars = listOf(0.30f, 0.50f, 0.72f)
    bars.forEachIndexed { index, heightFraction ->
        val x = size.width * (0.22f + index * 0.26f)
        val barHeight = size.height * heightFraction
        drawRoundRect(
            color = HomeAccentPrimary,
            topLeft = Offset(x, size.height - barHeight),
            size = Size(barWidth, barHeight),
            cornerRadius = corner,
        )
    }
}

internal fun DrawScope.drawSessionTraffic(scale: Float) {
    val stroke = designDp(1.5f, scale).toPx()
    fun arrow(x: Float, up: Boolean) {
        val topY = size.height * 0.18f
        val bottomY = size.height * 0.82f
        val headY = if (up) topY else bottomY
        val wingY = if (up) topY + size.height * 0.22f else bottomY - size.height * 0.22f
        drawLine(HomeAccentPrimary, Offset(x, if (up) bottomY else topY), Offset(x, headY), stroke, StrokeCap.Round)
        drawLine(HomeAccentPrimary, Offset(x, headY), Offset(x - size.width * 0.16f, wingY), stroke, StrokeCap.Round)
        drawLine(HomeAccentPrimary, Offset(x, headY), Offset(x + size.width * 0.16f, wingY), stroke, StrokeCap.Round)
    }
    arrow(size.width * 0.34f, up = true)
    arrow(size.width * 0.66f, up = false)
}

@Composable
internal fun HomePowerButton(
    modifier: Modifier,
    scale: Float,
    connectionState: VpnConnectionState,
    backdrop: LayerBackdrop?,
    liveGlassEnabled: Boolean,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(designDp(20f, scale))
    val interactionSource = remember { MutableInteractionSource() }
    val buttonColor = when (connectionState) {
        VpnConnectionState.CONNECTED -> HomeAccentPrimary
        VpnConnectionState.CONNECTING -> HomeAccentPrimary
        VpnConnectionState.FAILED -> HomeWarning
        VpnConnectionState.DISCONNECTED -> HomeWarning
    }

    Box(
        modifier = modifier
            .nokiTintedActionSurface(
                shape = shape,
                color = buttonColor,
                backdrop = backdrop,
                liveGlassEnabled = liveGlassEnabled,
            )
            .clip(shape)
            .clickable(
                interactionSource = interactionSource,
                indication = ripple(color = Color.White),
                onClick = onClick,
            ),
    ) {
        HomeVector(
            resId = R.drawable.home_power_icon_vector,
            modifier = Modifier
                .offset(
                    x = designDp(76f, scale),
                    y = designDp(29f, scale),
                )
                .width(designDp(28f, scale))
                .height(designDp(30f, scale)),
        )
    }
}

@Composable
internal fun HomeStatusCard(
    modifier: Modifier,
    scale: Float,
    backdrop: LayerBackdrop?,
    liveGlassEnabled: Boolean,
    timeLabel: String,
    statusLabel: String,
) {
    HomeSettingsGlassSurface(
        modifier = modifier,
        backdrop = backdrop,
        liveGlassEnabled = liveGlassEnabled,
    ) {
        Column(
            modifier = Modifier.width(designDp(150f, scale)),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = timeLabel,
                color = HomeTextPrimary,
                fontFamily = ManropeFontFamily,
                fontWeight = FontWeight.Bold,
                fontSize = designSp(18f, scale),
                letterSpacing = 0.18.sp,
                textAlign = TextAlign.Center,
                maxLines = 1,
                softWrap = false,
                style = TextStyle(platformStyle = nokiPlatformTextStyle(includeFontPadding = true)),
                modifier = Modifier.width(designDp(150f, scale)),
            )
            Box(modifier = Modifier.height(1.dp))
            Text(
                text = statusLabel,
                color = HomeTextPrimary,
                fontFamily = ManropeFontFamily,
                fontWeight = FontWeight.Normal,
                fontSize = designSp(11f, scale),
                letterSpacing = 0.11.sp,
                textAlign = TextAlign.Center,
                maxLines = 2,
                softWrap = true,
                overflow = TextOverflow.Ellipsis,
                style = TextStyle(platformStyle = nokiPlatformTextStyle(includeFontPadding = true)),
                modifier = Modifier.width(designDp(150f, scale)),
            )
        }
    }
}

@Composable
internal fun rememberConnectionTimeLabel(
    connectionState: VpnConnectionState,
    connectedAtMillis: Long?,
    enabled: Boolean,
    nowMillis: () -> Long = System::currentTimeMillis,
): String {
    var label by remember { mutableStateOf("--:--") }

    LaunchedEffect(connectionState, connectedAtMillis, enabled, nowMillis) {
        if (!enabled || connectionState != VpnConnectionState.CONNECTED || connectedAtMillis == null) {
            label = "--:--"
            return@LaunchedEffect
        }

        while (true) {
            label = formatConnectionDuration(nowMillis() - connectedAtMillis)
            kotlinx.coroutines.delay(1_000)
        }
    }

    return label
}

internal fun formatConnectionDuration(durationMillis: Long): String {
    val totalSeconds = durationMillis.coerceAtLeast(0L) / 1_000L
    val totalMinutes = totalSeconds / 60L
    val totalHours = totalMinutes / 60L
    val seconds = totalSeconds % 60L
    val minutes = totalMinutes % 60L
    if (totalHours >= 24L) {
        val days = totalHours / 24L
        val hours = totalHours % 24L
        return "%02d:%02d:%02d:%02d".format(Locale.US, days, hours, minutes, seconds)
    }
    if (totalHours >= 1L) {
        return "%02d:%02d:%02d".format(Locale.US, totalHours, minutes, seconds)
    }
    return "%02d:%02d".format(Locale.US, minutes, seconds)
}

internal fun Double.formatMetricValue(): String {
    return if (this < 10.0) {
        String.format(Locale.US, "%.2f", this).trimEnd('0').trimEnd('.')
    } else {
        String.format(Locale.US, "%.1f", this).trimEnd('0').trimEnd('.')
    }
}

internal fun designPx(value: Float, scale: Float): Float = value * scale

internal fun designDp(value: Float, scale: Float): Dp = designPx(value, scale).dp

internal fun homeConnectionStatusLabel(
    language: AppLanguage,
    state: VpnConnectionState,
    failureReason: String,
): String =
    when (state) {
        VpnConnectionState.DISCONNECTED -> tr(language, "Не подключено", "Not connected")
        VpnConnectionState.CONNECTING -> tr(language, "Подключение", "Connecting")
        VpnConnectionState.CONNECTED -> tr(language, "Подключено", "Connected")
        VpnConnectionState.FAILED -> failureReason.ifBlank {
            tr(language, "Ошибка подключения", "Connection failed")
        }
    }

@Composable
internal fun designSp(value: Float, scale: Float) = with(LocalDensity.current) {
    (value * scale).dp.toSp()
}
