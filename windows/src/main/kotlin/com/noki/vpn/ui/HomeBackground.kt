package com.noki.vpn.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
@Composable
internal fun HomeVector(
    resId: Int,
    modifier: Modifier,
) {
    androidx.compose.foundation.Image(
        painter = nokiPainterResource(resId),
        contentDescription = null,
        modifier = modifier,
        contentScale = androidx.compose.ui.layout.ContentScale.FillBounds,
    )
}

@Composable
internal fun HomeQuickSettingsPill(
    text: String,
    iconResId: Int?,
    modifier: Modifier,
    scale: Float,
    backdrop: LayerBackdrop?,
    liveGlassEnabled: Boolean,
    onClick: () -> Unit,
) {
    val glassScale = 1f
    HomeSettingsGlassSurface(
        modifier = modifier,
        backdrop = backdrop,
        liveGlassEnabled = liveGlassEnabled,
        onClick = onClick,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = settingsDp(20f, glassScale)),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (iconResId != null) {
                androidx.compose.foundation.Image(
                    painter = nokiPainterResource(iconResId),
                    contentDescription = null,
                    modifier = Modifier
                        .width(designDp(19f, scale))
                        .height(designDp(15f, scale)),
                )
                Spacer(modifier = Modifier.width(designDp(10f, scale)))
            }
            Text(
                text = text,
                color = HomeTextPrimary,
                fontFamily = ManropeFontFamily,
                fontWeight = FontWeight.Normal,
                fontSize = designSp(14f, scale),
                lineHeight = designSp(16.8f, scale),
                letterSpacing = 0.sp,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                style = TextStyle(platformStyle = nokiPlatformTextStyle(includeFontPadding = false)),
            )
        }
    }
}

@Composable
internal fun HomeSettingsGlassSurface(
    modifier: Modifier,
    backdrop: LayerBackdrop?,
    liveGlassEnabled: Boolean,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (pressed) 0.985f else 1f,
        animationSpec = spring(
            dampingRatio = 0.72f,
            stiffness = 520f,
        ),
        label = "homeSettingsGlassPress",
    )
    val glassScale = 1f
    val shape = RoundedCornerShape(settingsDp(20f, glassScale))
    Box(
        modifier = modifier
            .nokiGlassSurface(
                shape = shape,
                backdrop = backdrop,
                liveGlassEnabled = liveGlassEnabled,
                scale = glassScale,
                shadowAlpha = if (liveGlassEnabled) 0.05f else NokiUiKitPolicy.simpleSurfaceShadowAlpha,
                surfaceColor = SettingsTextMuted.copy(alpha = 0.05f),
                simpleSurfaceColor = SettingsBgSoft,
                blurAndLensEnabled = false,
                backdropShadowsEnabled = false,
                dropShadowRadiusDp = 4f,
                layerBlock = if (onClick != null) {
                    {
                        scaleX = pressScale
                        scaleY = pressScale
                    }
                } else {
                    null
                },
            )
            .then(
                if (onClick != null) {
                    Modifier.clickable(
                        interactionSource = interactionSource,
                        indication = null,
                        onClick = onClick,
                    )
                } else {
                    Modifier
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

internal fun Modifier.homeServerDropdownSheetGlassSurface(
    shape: Shape,
    backdrop: Backdrop?,
    liveGlassEnabled: Boolean,
): Modifier {
    return this
        .clip(shape)
        .then(
            if (liveGlassEnabled && backdrop != null) {
                Modifier.drawBackdrop(
                    backdrop = backdrop,
                    shape = { shape },
                    effects = {
                        vibrancy()
                        blur(NokiUiKitPolicy.homeServerDropdownSheetBlurDp.dp.toPx())
                        lens(
                            NokiUiKitPolicy.homeServerDropdownSheetLensRadiusDp.dp.toPx(),
                            NokiUiKitPolicy.homeServerDropdownSheetLensRefractionDp.dp.toPx(),
                            chromaticAberration = false,
                        )
                    },
                    highlight = null,
                    shadow = null,
                    innerShadow = null,
                    onDrawSurface = {
                        drawRect(
                            HomeBgLighter.copy(
                                alpha = NokiUiKitPolicy.homeServerDropdownSheetSurfaceAlpha,
                            ),
                        )
                    },
                )
            } else {
                Modifier
                    .background(
                        glassSurfaceColor(
                            HomeBgLighter.copy(alpha = NokiUiKitPolicy.homeServerDropdownSheetSurfaceAlpha),
                            liveGlassEnabled,
                            SettingsBgSoft,
                        ),
                        shape,
                    )
            },
        )
}

internal fun Modifier.homeServerDropdownSheetShadowLayer(
    shape: Shape,
    scale: Float,
): Modifier {
    return this
        .offset(y = settingsDp(NokiUiKitPolicy.homeServerDropdownSheetShadowOffsetYDp, scale))
        .shadow(
            elevation = settingsDp(NokiUiKitPolicy.homeServerDropdownSheetShadowBlurDp, scale),
            shape = shape,
            ambientColor = Color.Black.copy(alpha = NokiUiKitPolicy.homeServerDropdownSheetShadowAlpha),
            spotColor = Color.Black.copy(alpha = NokiUiKitPolicy.homeServerDropdownSheetShadowAlpha),
        )
}
