package com.noki.vpn.ui
import androidx.compose.runtime.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
private val DevicesBgLighter = com.noki.desktop.DesktopUiKit.Text
private val DevicesAccentSecondary = com.noki.desktop.DesktopUiKit.NestedSurface
private fun devicesDp(value: Float, scale: Float) = (value * scale).dp
@Composable
internal fun PhoneGlyph(scale: Float) {
    Box(
        modifier = Modifier
            .width(devicesDp(18f, scale))
            .height(devicesDp(30f, scale))
            .clip(RoundedCornerShape(devicesDp(4f, scale)))
            .background(DevicesBgLighter),
    ) {
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .offset(y = devicesDp(4f, scale))
                .width(devicesDp(6f, scale))
                .height(devicesDp(2f, scale))
                .clip(RoundedCornerShape(devicesDp(1f, scale)))
                .background(DevicesAccentSecondary),
        )
    }
}

@Composable
internal fun MonitorGlyph(scale: Float) {
    Box(
        modifier = Modifier
            .width(devicesDp(27f, scale))
            .height(devicesDp(25f, scale)),
    ) {
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .width(devicesDp(26f, scale))
                .height(devicesDp(16f, scale))
                .clip(RoundedCornerShape(devicesDp(3f, scale)))
                .background(DevicesBgLighter),
        )
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .width(devicesDp(14f, scale))
                .height(devicesDp(4f, scale))
                .clip(RoundedCornerShape(devicesDp(2f, scale)))
                .background(DevicesBgLighter),
        )
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .offset(y = devicesDp(7f, scale))
                .width(devicesDp(5f, scale))
                .height(devicesDp(5f, scale))
                .background(DevicesBgLighter),
        )
    }
}

