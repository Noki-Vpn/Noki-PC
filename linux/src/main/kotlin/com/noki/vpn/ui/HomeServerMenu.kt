package com.noki.vpn.ui

import androidx.compose.runtime.getValue
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import com.noki.vpn.data.AppLanguage
import com.noki.vpn.data.ServerLocation
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.colorControls
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.Shadow
import com.kyant.backdrop.shadow.InnerShadow
import java.util.Locale

internal data class HomeServerMenuEntry(
    val key: String,
    val location: ServerLocation,
)

internal fun serverMenuLocations(locations: List<ServerLocation>): List<HomeServerMenuEntry> {
    return locations.map { location ->
        HomeServerMenuEntry(
            key = location.code,
            location = location,
        )
    }
}

@Composable
internal fun HomeDropdownArrowButton(
    modifier: Modifier,
    scale: Float,
    arrowRotation: Float,
    backdrop: LayerBackdrop?,
    liveGlassEnabled: Boolean,
) {
    val shape = RoundedCornerShape(percent = 50)
    Box(
        modifier = modifier
            .nokiGlassSurface(
                shape = shape,
                backdrop = backdrop,
                liveGlassEnabled = liveGlassEnabled,
                scale = 1f,
                elevationDp = 14f,
                shadowAlpha = if (liveGlassEnabled) 0.05f else NokiUiKitPolicy.simpleSurfaceShadowAlpha,
                highlightAlpha = 0.30f,
                surfaceColor = SettingsTextMuted.copy(alpha = 0.05f),
                simpleSurfaceColor = SettingsBgSoft,
                blurAndLensEnabled = false,
                backdropShadowsEnabled = false,
                dropShadowRadiusDp = 4f,
            ),
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    rotationZ = arrowRotation
                },
        ) {
            val strokeWidth = designDp(2.2f, scale).toPx()
            val arrowHalfWidth = designDp(6.2f, scale).toPx()
            val arrowHalfHeight = designDp(4.0f, scale).toPx()
            val arrowCenterY = center.y + designDp(1f, scale).toPx()
            drawLine(
                color = HomeAccentPrimary,
                start = Offset(center.x - arrowHalfWidth, arrowCenterY - arrowHalfHeight),
                end = Offset(center.x, arrowCenterY + arrowHalfHeight),
                strokeWidth = strokeWidth,
                cap = StrokeCap.Round,
            )
            drawLine(
                color = HomeAccentPrimary,
                start = Offset(center.x, arrowCenterY + arrowHalfHeight),
                end = Offset(center.x + arrowHalfWidth, arrowCenterY - arrowHalfHeight),
                strokeWidth = strokeWidth,
                cap = StrokeCap.Round,
            )
        }
    }
}

@Composable
internal fun HomeServerMenuItem(
    location: ServerLocation?,
    language: AppLanguage,
    scale: Float,
    animateOnlineIndicator: Boolean,
    onClick: () -> Unit,
    title: String? = null,
    selected: Boolean = false,
    latencyMs: Int? = location?.latencyMs,
    online: Boolean = location?.isOnline ?: true,
    expanded: Boolean = false,
    onExpand: (() -> Unit)? = null,
) {
    val isServerRow = location != null && title != null
    val countryText = title ?: location?.country.orEmpty()
    val latencyText = latencyMs?.let { "$it " + tr(language, "мс", "ms") } ?: "—"
    val markerSize = if (isServerRow) 32f else 46f
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (pressed) 0.985f else 1f,
        animationSpec = spring(
            dampingRatio = 0.72f,
            stiffness = 520f,
        ),
        label = "homeServerMenuItemPress",
    )
    val arrowRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        label = "countryServersArrow",
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer { scaleX = pressScale; scaleY = pressScale },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .heightIn(min = designDp(if (isServerRow) 68f else NokiUiKitPolicy.homeServerItemHeightDp, scale))
                .semantics { this.selected = selected }
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    enabled = online,
                    role = Role.RadioButton,
                    onClick = onClick,
                )
                .padding(start = designDp(18f, scale), end = designDp(if (onExpand == null) 18f else 0f, scale))
                .padding(vertical = designDp(12f, scale)),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (location != null && !isServerRow) {
                CountryFlagMarker(location = location, scale = scale, sizeDp = markerSize)
            } else {
                Box(
                    modifier = Modifier
                        .size(designDp(markerSize, scale))
                        .clip(RoundedCornerShape(designDp(14f, scale)))
                        .background(if (isServerRow) Color.Transparent else HomeAccentPrimary.copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = if (isServerRow) Icons.Rounded.Dns else Icons.Rounded.AutoAwesome,
                        contentDescription = null,
                        tint = if (isServerRow && !selected) HomeTextSecondary else HomeAccentPrimary,
                        modifier = Modifier.size(designDp(if (isServerRow) 21f else 24f, scale)),
                    )
                }
            }
            Spacer(modifier = Modifier.width(designDp(14f, scale)))
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(designDp(4f, scale)),
            ) {
                Text(
                    text = countryText,
                    color = if (online) HomeTextPrimary else HomeTextSecondary,
                    fontFamily = ManropeFontFamily,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    fontSize = designSp(if (isServerRow) 17f else NokiUiKitPolicy.homeServerItemNameTextSp, scale),
                    letterSpacing = 0.sp,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                    style = TextStyle(platformStyle = nokiPlatformTextStyle(includeFontPadding = false)),
                )
            }
            Spacer(modifier = Modifier.width(designDp(10f, scale)))
            if (location != null) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(designDp(6f, scale))) {
                    Text(
                        text = latencyText,
                        color = if (online && latencyMs != null) HomeAccentPrimary else HomeTextSecondary,
                        fontFamily = ManropeFontFamily,
                        fontWeight = FontWeight.Normal,
                        fontSize = designSp(14f, scale),
                        letterSpacing = 0.sp,
                        maxLines = 1,
                        softWrap = false,
                        textAlign = TextAlign.Right,
                        style = TextStyle(platformStyle = nokiPlatformTextStyle(includeFontPadding = false)),
                    )
                    Box(Modifier.size(designDp(14f, scale))) {
                        OnlineStatusIndicator(online = online, animate = animateOnlineIndicator)
                    }
                }
            }
        }
        if (onExpand != null) {
            val expandLabel = tr(language, "Серверы: ", "Servers: ") + countryText
            val expandedLabel = if (expanded) tr(language, "Развернуто", "Expanded") else tr(language, "Свернуто", "Collapsed")
            Box(
                modifier = Modifier.size(designDp(48f, scale).coerceAtLeast(48.dp))
                    .semantics { stateDescription = expandedLabel }
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        role = Role.Button,
                        onClick = onExpand,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Rounded.ExpandMore,
                    contentDescription = expandLabel,
                    tint = HomeTextSecondary,
                    modifier = Modifier.size(designDp(24f, scale)).graphicsLayer { rotationZ = arrowRotation },
                )
            }
        }
    }
}

internal fun Modifier.homeServerItemGlassSurface(
    shape: Shape,
    backdrop: Backdrop?,
    liveGlassEnabled: Boolean,
    scale: Float,
    selected: Boolean = false,
    layerBlock: GraphicsLayerScope.() -> Unit = {},
): Modifier {
    val surfaceColor = if (selected) {
        HomeAccentPrimary.copy(alpha = 0.13f)
    } else {
        SettingsTextMuted.copy(alpha = NokiUiKitPolicy.homeServerLazyItemSurfaceAlpha)
    }
    val shadowAlpha = if (liveGlassEnabled) 0.22f else NokiUiKitPolicy.simpleSurfaceShadowAlpha
    return this
        .shadow(
            elevation = settingsDp(10f, scale),
            shape = shape,
            clip = false,
            ambientColor = Color.Black.copy(alpha = shadowAlpha),
            spotColor = Color.Black.copy(alpha = shadowAlpha),
        )
        .then(
            if (liveGlassEnabled && backdrop != null) {
                Modifier.drawBackdrop(
                    backdrop = backdrop,
                    shape = { shape },
                    effects = {
                        colorControls(saturation = 1.18f, contrast = 1.06f, brightness = 0.05f)
                        blur(settingsDp(7f, scale).toPx())
                        lens(settingsDp(6f, scale).toPx(), settingsDp(7f, scale).toPx())
                    },
                    highlight = {
                        Highlight(
                            width = settingsDp(0.5f, scale),
                            blurRadius = settingsDp(0.25f, scale),
                            alpha = 0.18f,
                        )
                    },
                    shadow = { Shadow(alpha = 0.16f) },
                    innerShadow = { InnerShadow(radius = settingsDp(6f, scale), alpha = 0.24f) },
                    layerBlock = layerBlock,
                    onDrawSurface = { drawRect(surfaceColor) },
                )
            } else {
                Modifier.graphicsLayer(layerBlock).clip(shape).background(
                    glassSurfaceColor(
                        surfaceColor,
                        liveGlassEnabled,
                        androidx.compose.ui.graphics.lerp(
                            SettingsBgSoft,
                            if (selected) HomeAccentPrimary else HomeTextPrimary,
                            if (selected) 0.18f else 0.07f,
                        ),
                    ),
                    shape,
                )
            },
        )
}

@Composable
internal fun CountryFlagMarker(
    location: ServerLocation,
    scale: Float,
    sizeDp: Float = 46f,
) {
    val resourceName = countryFlagResourceName(location)
    val resourceId = nokiRawResourceId(resourceName)
    val markerSize = designDp(sizeDp, scale)
    Box(
        modifier = Modifier
            .size(markerSize)
            .clip(RoundedCornerShape(percent = 50))
            .background(HomeStroke.copy(alpha = 0.28f)),
        contentAlignment = Alignment.Center,
    ) {
        if (resourceId != 0) {
            FigmaSvgAsset(
                resId = resourceId,
                viewportWidth = 512,
                viewportHeight = 512,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Text(
                text = "??",
                color = HomeTextPrimary,
                fontFamily = ManropeFontFamily,
                fontWeight = FontWeight.SemiBold,
                fontSize = designSp(15f, scale),
                letterSpacing = 0.sp,
                maxLines = 1,
                softWrap = false,
                textAlign = TextAlign.Center,
                style = TextStyle(platformStyle = nokiPlatformTextStyle(includeFontPadding = true)),
            )
        }
    }
}

@Composable
internal fun OnlineStatusIndicator(
    online: Boolean,
    animate: Boolean,
) {
    if (!online) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawCircle(
                color = HomeTextSecondary.copy(alpha = 0.56f),
                radius = size.minDimension * 0.18f,
                center = center,
            )
        }
        return
    }

    if (!animate) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawCircle(
                color = HomeAccentPrimary,
                radius = size.minDimension * 0.2f,
                center = center,
            )
        }
        return
    }

    val transition = rememberInfiniteTransition(label = "serverOnlinePulse")
    val pulseProgress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1600, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "serverOnlinePulseProgress",
    )

    Canvas(modifier = Modifier.fillMaxSize()) {
        val baseRadius = size.minDimension * 0.2f
        val maxRadius = size.minDimension * 0.48f

        fun drawPulse(phase: Float) {
            val radius = baseRadius + (maxRadius - baseRadius) * phase
            val alpha = (1f - phase).coerceIn(0f, 1f) * 0.24f
            if (alpha > 0.01f) {
                drawCircle(
                    color = HomeAccentPrimary.copy(alpha = alpha),
                    radius = radius,
                    center = center,
                )
            }
        }

        drawPulse(pulseProgress)
        drawPulse((pulseProgress + 0.5f) % 1f)
        drawCircle(
            color = HomeAccentPrimary.copy(alpha = 0.18f),
            radius = size.minDimension * 0.32f,
            center = center,
        )
        drawCircle(
            color = HomeAccentPrimary,
            radius = baseRadius,
            center = center,
        )
    }
}

internal fun countryMarkerCode(location: ServerLocation): String? {
    return location.countryCode
        .trim()
        .uppercase(Locale.ROOT)
        .takeIf { it.length == 2 && it.all { char -> char in 'A'..'Z' } }
}

internal fun countryFlagResourceName(location: ServerLocation): String? =
    countryMarkerCode(location)?.let { code -> "flag_${code.lowercase(Locale.ROOT)}" }
