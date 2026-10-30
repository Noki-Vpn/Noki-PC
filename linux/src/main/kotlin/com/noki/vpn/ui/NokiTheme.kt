package com.noki.vpn.ui

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import com.noki.vpn.R
import com.noki.vpn.data.AccentPalette

private val NokiTypography = Typography().run {
    copy(
        displayLarge = displayLarge.copy(fontFamily = ManropeFontFamily),
        displayMedium = displayMedium.copy(fontFamily = ManropeFontFamily),
        displaySmall = displaySmall.copy(fontFamily = ManropeFontFamily),
        headlineLarge = headlineLarge.copy(fontFamily = ManropeFontFamily),
        headlineMedium = headlineMedium.copy(fontFamily = ManropeFontFamily),
        headlineSmall = headlineSmall.copy(fontFamily = ManropeFontFamily),
        titleLarge = titleLarge.copy(fontFamily = ManropeFontFamily),
        titleMedium = titleMedium.copy(fontFamily = ManropeFontFamily),
        titleSmall = titleSmall.copy(fontFamily = ManropeFontFamily),
        bodyLarge = bodyLarge.copy(fontFamily = ManropeFontFamily),
        bodyMedium = bodyMedium.copy(fontFamily = ManropeFontFamily),
        bodySmall = bodySmall.copy(fontFamily = ManropeFontFamily),
        labelLarge = labelLarge.copy(fontFamily = ManropeFontFamily),
        labelMedium = labelMedium.copy(fontFamily = ManropeFontFamily),
        labelSmall = labelSmall.copy(fontFamily = ManropeFontFamily),
    )
}

@Composable
fun NokiTheme(
    accentPalette: AccentPalette = AccentPalette.GREEN,
    content: @Composable () -> Unit,
) {
    val accent = Color(accentPalette.argb)
    val scheme = darkScheme(accent)
    val currentDensity = LocalDensity.current
    val configuration = nokiScreenSize()
    val typographyScale = minOf(
        configuration.width.value / 412f,
        configuration.height.value / 917f,
    ).coerceIn(0.84f, 1f)
    val typography = NokiTypography.scaled(typographyScale)
    CompositionLocalProvider(
        LocalDensity provides Density(
            density = currentDensity.density,
            fontScale = 1f,
        ),
    ) {
        MaterialTheme(
            colorScheme = scheme,
            typography = typography,
        ) {
            ProvideTextStyle(
                value = typography.bodyMedium,
                content = content,
            )
        }
    }
}

private fun Typography.scaled(scale: Float): Typography {
    return copy(
        displayLarge = displayLarge.scaled(scale),
        displayMedium = displayMedium.scaled(scale),
        displaySmall = displaySmall.scaled(scale),
        headlineLarge = headlineLarge.scaled(scale),
        headlineMedium = headlineMedium.scaled(scale),
        headlineSmall = headlineSmall.scaled(scale),
        titleLarge = titleLarge.scaled(scale),
        titleMedium = titleMedium.scaled(scale),
        titleSmall = titleSmall.scaled(scale),
        bodyLarge = bodyLarge.scaled(scale),
        bodyMedium = bodyMedium.scaled(scale),
        bodySmall = bodySmall.scaled(scale),
        labelLarge = labelLarge.scaled(scale),
        labelMedium = labelMedium.scaled(scale),
        labelSmall = labelSmall.scaled(scale),
    )
}

private fun TextStyle.scaled(scale: Float): TextStyle {
    return copy(
        fontSize = fontSize.scaled(scale),
        lineHeight = lineHeight.scaled(scale),
        letterSpacing = letterSpacing.scaled(scale),
    )
}

private fun TextUnit.scaled(scale: Float): TextUnit {
    return if (this == TextUnit.Unspecified) this else (value * scale).sp
}

private fun darkScheme(accent: Color): ColorScheme {
    return darkColorScheme(
        primary = accent,
        onPrimary = Color(0xFF07111A),
        secondary = Color(0xFF8CC8FF),
        onSecondary = Color(0xFF07111A),
        tertiary = Color(0xFF42D6A4),
        background = Color(0xFF07111A),
        onBackground = Color(0xFFF4FBFF),
        surface = Color(0xFF0D1B2A),
        onSurface = Color(0xFFF4FBFF),
        surfaceVariant = Color(0xFF132635),
        onSurfaceVariant = Color(0xFF9FB6C5),
        outline = Color(0x14FFFFFF),
        outlineVariant = Color(0x336E8797),
        error = Color(0xFFFF6B6B),
        onError = Color.White,
    )
}
