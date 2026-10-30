package com.noki.vpn.ui
import androidx.compose.ui.text.TextStyle

import androidx.compose.foundation.background
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.colorControls
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow

internal object NokiSettingsActionGlassStyle {
    const val elevationDp: Float = 8f
    const val liveShadowAlpha: Float = 0.22f
    const val disabledShadowAlpha: Float = NokiUiKitPolicy.simpleSurfaceShadowAlpha
    const val highlightAlpha: Float = 0.25f
    const val surfaceAlpha: Float = 0.80f
    const val saturation: Float = 1.18f
    const val contrast: Float = 1.06f
    const val brightness: Float = 0.05f

    val surfaceColor: Color
        get() = SettingsBgLighter.copy(alpha = surfaceAlpha)
}

internal fun glassSurfaceColor(
    color: Color,
    liveGlassEnabled: Boolean,
    simpleColor: Color = color,
): Color = if (liveGlassEnabled) color else simpleColor.copy(alpha = 1f)

internal fun Modifier.nokiGlassSurface(
    shape: Shape,
    backdrop: LayerBackdrop?,
    exportedBackdrop: LayerBackdrop? = null,
    liveGlassEnabled: Boolean,
    scale: Float = 1f,
    elevationDp: Float = 10f,
    shadowAlpha: Float = if (liveGlassEnabled) 0.22f else NokiUiKitPolicy.simpleSurfaceShadowAlpha,
    highlightAlpha: Float = 0.18f,
    highlightWidthDp: Float = 0.5f,
    highlightBlurDp: Float = highlightWidthDp / 2f,
    plainHighlight: Boolean = false,
    saturation: Float = 1.18f,
    contrast: Float = 1.06f,
    brightness: Float = 0.05f,
    blurRadiusDp: Float = 7f,
    lensRadiusDp: Float = 6f,
    lensRefractionDp: Float = 7f,
    chromaticAberration: Boolean = false,
    innerShadowAlpha: Float = 0.24f,
    surfaceColor: Color = SettingsBgSoft.copy(alpha = 0.18f),
    simpleSurfaceColor: Color = surfaceColor,
    blurAndLensEnabled: Boolean = true,
    backdropShadowsEnabled: Boolean = true,
    dropShadowRadiusDp: Float? = null,
    layerBlock: (GraphicsLayerScope.() -> Unit)? = null,
): Modifier {
    return this
        .then(
            if ((!liveGlassEnabled || backdrop == null) && layerBlock != null) {
                Modifier.graphicsLayer(layerBlock)
            } else {
                Modifier
            },
        )
        .then(
            if (dropShadowRadiusDp != null) {
                Modifier.dropShadow(shape) {
                    radius = settingsDp(dropShadowRadiusDp, scale).toPx()
                    spread = 0f
                    offset = Offset(0f, settingsDp(1f, scale).toPx())
                    color = Color.Black.copy(alpha = shadowAlpha)
                }
            } else {
                Modifier.shadow(
                    elevation = settingsDp(elevationDp, scale),
                    shape = shape,
                    clip = false,
                    ambientColor = Color.Black.copy(alpha = shadowAlpha),
                    spotColor = Color.Black.copy(alpha = shadowAlpha),
                )
            },
        )
        .then(
            if (liveGlassEnabled && backdrop != null) {
                Modifier.drawBackdrop(
                    backdrop = backdrop,
                    exportedBackdrop = exportedBackdrop,
                    shape = { shape },
                    effects = {
                        colorControls(
                            saturation = saturation,
                            contrast = contrast,
                            brightness = brightness,
                        )
                        if (blurAndLensEnabled) {
                            if (blurRadiusDp > 0f) {
                                blur(settingsDp(blurRadiusDp, scale).toPx())
                            }
                            if (lensRadiusDp > 0f || lensRefractionDp > 0f) {
                                lens(
                                    settingsDp(lensRadiusDp, scale).toPx(),
                                    settingsDp(lensRefractionDp, scale).toPx(),
                                    chromaticAberration = chromaticAberration,
                                )
                            }
                        }
                    },
                    highlight = {
                        val highlight = if (plainHighlight) Highlight.Plain else Highlight.Default
                        highlight.copy(
                            width = settingsDp(highlightWidthDp, scale),
                            blurRadius = settingsDp(highlightBlurDp, scale),
                            alpha = highlightAlpha,
                        )
                    },
                    shadow = if (backdropShadowsEnabled) {
                        { Shadow(alpha = 0.16f) }
                    } else {
                        null
                    },
                    innerShadow = if (backdropShadowsEnabled) {
                        {
                            InnerShadow(
                                radius = settingsDp(6f, scale),
                                alpha = innerShadowAlpha,
                            )
                        }
                    } else {
                        null
                    },
                    layerBlock = layerBlock,
                    onDrawSurface = {
                        drawRect(surfaceColor)
                    },
                )
            } else {
                Modifier
                    .clip(shape)
                    .background(
                        glassSurfaceColor(surfaceColor, liveGlassEnabled, simpleSurfaceColor),
                        shape,
                    )
            },
        )
}

internal fun Modifier.nokiSettingsPanelGlassSurface(
    shape: Shape,
    backdrop: LayerBackdrop?,
    exportedBackdrop: LayerBackdrop? = null,
    liveGlassEnabled: Boolean,
    scale: Float = 1f,
    elevationDp: Float = 8f,
    shadowAlpha: Float = 0.25f,
    highlightAlpha: Float = 0.18f,
    brightness: Float = -0.01f,
    blurRadiusDp: Float = 7f,
    lensRadiusDp: Float = 6f,
    lensRefractionDp: Float = 7f,
    chromaticAberration: Boolean = false,
    innerShadowAlpha: Float = 0.24f,
    surfaceColor: Color = SettingsBgLighter.copy(alpha = 0.80f),
    blurAndLensEnabled: Boolean = true,
    layerBlock: (GraphicsLayerScope.() -> Unit)? = null,
): Modifier {
    return nokiGlassSurface(
        shape = shape,
        backdrop = backdrop,
        exportedBackdrop = exportedBackdrop,
        liveGlassEnabled = liveGlassEnabled,
        scale = scale,
        elevationDp = elevationDp,
        shadowAlpha = shadowAlpha,
        highlightAlpha = highlightAlpha,
        saturation = 1.18f,
        contrast = 1.06f,
        brightness = brightness,
        blurRadiusDp = blurRadiusDp,
        lensRadiusDp = lensRadiusDp,
        lensRefractionDp = lensRefractionDp,
        chromaticAberration = chromaticAberration,
        innerShadowAlpha = innerShadowAlpha,
        surfaceColor = surfaceColor,
        blurAndLensEnabled = blurAndLensEnabled,
        layerBlock = layerBlock,
    )
}

internal fun Modifier.nokiSettingsActionGlassSurface(
    shape: Shape,
    backdrop: LayerBackdrop?,
    liveGlassEnabled: Boolean,
    scale: Float = 1f,
    elevationDp: Float = NokiSettingsActionGlassStyle.elevationDp,
    shadowAlpha: Float = if (liveGlassEnabled) {
        NokiSettingsActionGlassStyle.liveShadowAlpha
    } else {
        NokiSettingsActionGlassStyle.disabledShadowAlpha
    },
    highlightAlpha: Float = NokiSettingsActionGlassStyle.highlightAlpha,
    surfaceColor: Color = NokiSettingsActionGlassStyle.surfaceColor,
    blurAndLensEnabled: Boolean = true,
    layerBlock: (GraphicsLayerScope.() -> Unit)? = null,
): Modifier {
    return nokiGlassSurface(
        shape = shape,
        backdrop = backdrop,
        liveGlassEnabled = liveGlassEnabled,
        scale = scale,
        elevationDp = elevationDp,
        shadowAlpha = shadowAlpha,
        highlightAlpha = highlightAlpha,
        saturation = NokiSettingsActionGlassStyle.saturation,
        contrast = NokiSettingsActionGlassStyle.contrast,
        brightness = NokiSettingsActionGlassStyle.brightness,
        surfaceColor = surfaceColor,
        blurAndLensEnabled = blurAndLensEnabled,
        layerBlock = layerBlock,
    )
}

internal fun Modifier.nokiTintedActionSurface(
    shape: Shape,
    color: Color,
    backdrop: LayerBackdrop?,
    liveGlassEnabled: Boolean,
): Modifier = then(
    if (liveGlassEnabled && backdrop != null) {
        Modifier.drawBackdrop(
            backdrop = backdrop,
            shape = { shape },
            effects = { vibrancy() },
            highlight = {
                Highlight.Default.copy(
                    width = 0.5.dp,
                    blurRadius = 0.25.dp,
                    alpha = 0.18f,
                )
            },
            onDrawSurface = {
                drawRect(color, blendMode = BlendMode.Hue)
                drawRect(color.copy(alpha = 0.75f))
            },
        )
    } else {
        Modifier.background(color, shape)
    },
)

internal val SettingsBgBase = Color(0xFF07111A)
internal val SettingsBgLighter = Color(0xFF0D1B2A)
internal val SettingsBgSoft = Color(0xFF132635)
internal val SettingsTextPrimary = Color(0xFFF4FBFF)
internal val SettingsTextSecondary = Color(0xFF9FB6C5)
internal val SettingsTextMuted = Color(0xFF6E8797)
internal val SettingsAccentPrimary = Color(0xFF7AE7C7)
internal val SettingsAccentSecondary = Color(0xFF8CC8FF)
internal val SettingsAccentStrong = Color(0xFF42D6A4)
internal val SettingsStroke = Color(0xFF29404E)
internal val SettingsError = Color(0xFFFF6B6B)
internal val SettingsNoFontPaddingTextStyle = TextStyle(
    platformStyle = nokiPlatformTextStyle(includeFontPadding = false),
)

