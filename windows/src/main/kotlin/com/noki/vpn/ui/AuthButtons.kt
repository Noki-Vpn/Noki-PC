package com.noki.vpn.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.backdrops.LayerBackdrop

@Composable
internal fun AuthSecondaryButton(
    text: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    containerColor: Color = NokiBgLighter,
    contentColor: Color = NokiTextPrimary,
    borderColor: Color? = null,
    cornerRadius: Dp = 16.dp,
    textFontSize: TextUnit = authSp(14f),
    backdrop: LayerBackdrop? = null,
    liveGlassEnabled: Boolean = false,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(cornerRadius)
    val interactionSource = remember { MutableInteractionSource() }
    val clickAnimator = rememberAuthButtonClickAnimator()
    Box(
        modifier = modifier
            .nokiGlassSurface(
                shape = shape,
                backdrop = backdrop,
                liveGlassEnabled = liveGlassEnabled,
                surfaceColor = authButtonSurfaceColor(containerColor),
                layerBlock = {
                    scaleX = clickAnimator.scale.value
                    scaleY = clickAnimator.scale.value
                },
            )
            .then(borderColor?.let { Modifier.border(1.dp, it, shape) } ?: Modifier)
            .clickable(
                enabled = enabled,
                interactionSource = interactionSource,
                indication = null,
                onClick = { clickAnimator.runClickAnimation(then = onClick) },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = if (enabled) contentColor else contentColor.copy(alpha = 0.55f),
            style = TextStyle(
                fontSize = textFontSize,
                lineHeight = textFontSize,
                fontFamily = ManropeFontFamily,
                fontWeight = FontWeight.Normal,
                letterSpacing = authSp(0.14f),
                textAlign = TextAlign.Center,
                platformStyle = nokiPlatformTextStyle(includeFontPadding = false),
            ),
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
internal fun AuthPrimaryButton(
    text: String,
    loading: Boolean,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    containerColor: Color = NokiAccentPrimary,
    disabledContainerColor: Color = NokiTextMuted,
    contentColor: Color = NokiBgBase,
    disabledContentColor: Color = NokiTextSecondary,
    borderColor: Color? = null,
    textFontSize: TextUnit? = authSp(16f),
    textFontWeight: FontWeight = FontWeight.Medium,
    useCommonButtonTextStyle: Boolean = true,
    cornerRadius: Dp = 20.dp,
    backdrop: LayerBackdrop? = null,
    liveGlassEnabled: Boolean = false,
    onClick: () -> Unit,
) {
    val metrics = nokiAdaptiveMetrics(nokiScreenSize().width)
    val buttonTextSize = textFontSize ?: authSp(20f)
    val buttonLineHeight = textFontSize ?: authSp(20f)
    val backgroundColor = if (enabled) containerColor else disabledContainerColor
    val textColor = if (enabled) contentColor else disabledContentColor
    val shape = RoundedCornerShape(cornerRadius)
    val interactionSource = remember { MutableInteractionSource() }
    val clickAnimator = rememberAuthButtonClickAnimator()

    Box(
        modifier = modifier
            .nokiGlassSurface(
                shape = shape,
                backdrop = backdrop,
                liveGlassEnabled = liveGlassEnabled,
                surfaceColor = authButtonSurfaceColor(backgroundColor),
                layerBlock = {
                    scaleX = clickAnimator.scale.value
                    scaleY = clickAnimator.scale.value
                },
            )
            .then(borderColor?.let { Modifier.border(1.dp, it, shape) } ?: Modifier)
            .clickable(
                enabled = enabled && !loading,
                interactionSource = interactionSource,
                indication = null,
                onClick = {
                    clickAnimator.runClickAnimation(
                        enabled = enabled && !loading,
                        then = onClick,
                    )
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(metrics.dp(22f)),
                strokeWidth = metrics.dp(2f),
                color = textColor,
            )
        } else {
            if (useCommonButtonTextStyle) {
                Text(
                    text = text,
                    color = textColor,
                    style = MaterialTheme.typography.labelLarge,
                    fontSize = textFontSize ?: TextUnit.Unspecified,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                )
            } else {
                Text(
                    text = text,
                    style = TextStyle(
                        color = textColor,
                        fontSize = buttonTextSize,
                        lineHeight = buttonLineHeight,
                        fontFamily = ManropeFontFamily,
                        fontWeight = textFontWeight,
                        letterSpacing = authSp(0.2f),
                        textAlign = TextAlign.Center,
                        platformStyle = nokiPlatformTextStyle(includeFontPadding = false),
                    ),
                )
            }
        }
    }
}

@Composable
internal fun AuthInlineInfo(
    message: String?,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = message != null,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = modifier,
    ) {
        Text(
            text = message.orEmpty(),
            modifier = Modifier.fillMaxWidth(),
            color = NokiTextSecondary,
            fontSize = authSp(11f),
            lineHeight = authSp(13f),
            fontFamily = ManropeFontFamily,
            fontWeight = FontWeight.Normal,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
internal fun AuthInlineError(
    error: String?,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = error != null,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = modifier,
    ) {
        Text(
            text = error.orEmpty(),
            modifier = Modifier.fillMaxWidth(),
            color = NokiError,
            fontSize = authSp(11f),
            lineHeight = authSp(13f),
            fontFamily = ManropeFontFamily,
            fontWeight = FontWeight.Normal,
            textAlign = TextAlign.Start,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

internal val NokiTextPrimary = Color(0xFFF4FBFF)
internal val NokiTextSecondary = Color(0xFF9FB6C5)
internal val NokiTextMuted = Color(0xFF6E8797)
internal val NokiBgBase = Color(0xFF07111A)
internal val NokiBgLighter = Color(0xFF0D1B2A)
internal val NokiBgSoft = Color(0xFF132635)
internal val NokiAccentPrimary = Color(0xFF7AE7C7)
internal val NokiAccentStrong = Color(0xFF42D6A4)
internal val NokiStroke = Color(0xFF29404E)
internal val NokiError = Color(0xFFFF6B6B)
internal const val AuthBgLighterButtonSurfaceAlpha = 0.6f

internal fun authButtonSurfaceColor(color: Color): Color {
    val alpha = if (color == NokiBgLighter) AuthBgLighterButtonSurfaceAlpha else 1f
    return color.copy(alpha = alpha)
}

@Composable
internal fun authSp(value: Float): TextUnit {
    val metrics = nokiAdaptiveMetrics(nokiScreenSize().width)
    return with(LocalDensity.current) { metrics.dp(value).toSp() }
}
