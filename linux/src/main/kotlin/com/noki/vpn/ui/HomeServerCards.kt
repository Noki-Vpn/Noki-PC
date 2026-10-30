package com.noki.vpn.ui

import androidx.compose.runtime.getValue
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import com.noki.vpn.data.AppLanguage
import com.noki.vpn.data.ServerLocation
import com.noki.vpn.data.ServerSelectionMode
import com.noki.vpn.data.UserProfile
import com.noki.vpn.R
import com.kyant.backdrop.backdrops.LayerBackdrop
import kotlin.math.max

internal fun homeSelectedLocation(
    locations: List<ServerLocation>,
    profile: UserProfile,
): ServerLocation? {
    fun byCode(code: String?): ServerLocation? {
        if (code.isNullOrBlank()) return null
        return locations.firstOrNull { location ->
            location.code.equals(code, ignoreCase = true) ||
                location.countryCode.equals(code, ignoreCase = true)
        }
    }

    return when (profile.serverSelectionMode) {
        ServerSelectionMode.SERVER ->
            locations.firstOrNull { location ->
                location.servers.any { server -> server.id == profile.selectedNodeId }
            } ?: byCode(profile.selectedServerCode)
                ?: byCode(profile.selectedCountryCode)
        ServerSelectionMode.AUTO ->
            byCode(profile.selectedServerCode)
                ?: byCode(profile.actualCountryCode)
                ?: byCode(profile.selectedCountryCode)
        ServerSelectionMode.COUNTRY ->
            byCode(profile.selectedCountryCode)
                ?: byCode(profile.selectedServerCode)
    }
}

@Composable
internal fun HomeBackground(
    liveGlassEnabled: Boolean,
    showCenterShade: Boolean = true,
) {
    val backgroundModifier = Modifier
        .fillMaxSize()
        .background(homeBackgroundColor(liveGlassEnabled))
    if (!shouldDrawHomeBackgroundEffects(liveGlassEnabled)) {
        Box(modifier = backgroundModifier) {
            Image(
                painter = nokiPainterResource(R.drawable.simple_topography),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        return
    }

    Box(
        modifier = backgroundModifier
            .drawWithCache {
                val w = size.width
                val h = size.height
                val wide = max(w, h)
                val baseGradient = Brush.verticalGradient(
                    colorStops = arrayOf(
                        0.00f to HomeBgBase,
                        0.58f to HomeBgBase,
                        0.84f to Color(0xFF072832),
                        1.00f to Color(0xFF063D4A),
                    ),
                )
                val lowerBlob = Brush.radialGradient(
                    colors = listOf(
                        Color(0xFF087F8D).copy(alpha = 0.52f),
                        Color(0xFF075F70).copy(alpha = 0.34f),
                        Color.Transparent,
                    ),
                    center = Offset(w * 0.50f, h * 1.08f),
                    radius = wide * 0.54f,
                )
                val leftAccentBlob = Brush.radialGradient(
                    colors = listOf(
                        HomeAccentPrimary.copy(alpha = 0.14f),
                        Color(0xFF0A6B78).copy(alpha = 0.16f),
                        Color.Transparent,
                    ),
                    center = Offset(w * 0.28f, h * 1.14f),
                    radius = wide * 0.32f,
                )
                val rightDeepBlob = Brush.radialGradient(
                    colors = listOf(
                        Color(0xFF0A5968).copy(alpha = 0.22f),
                        Color.Transparent,
                    ),
                    center = Offset(w * 0.78f, h * 1.12f),
                    radius = wide * 0.36f,
                )
                val centerShade = Brush.radialGradient(
                    colors = listOf(
                        Color.Black.copy(alpha = 0.00f),
                        Color.Black.copy(alpha = 0.18f),
                        Color.Transparent,
                    ),
                    center = Offset(w * 0.50f, h * 0.47f),
                    radius = wide * 0.68f,
                )
                val topBottomShade = Brush.verticalGradient(
                    colorStops = arrayOf(
                        0.00f to Color.Black.copy(alpha = 0.16f),
                        0.36f to Color.Transparent,
                        1.00f to Color.Black.copy(alpha = 0.04f),
                    ),
                )
                onDrawBehind {
                    drawRect(brush = baseGradient)
                    drawRect(brush = lowerBlob)
                    drawRect(brush = leftAccentBlob)
                    drawRect(brush = rightDeepBlob)
                    if (showCenterShade) {
                        drawRect(brush = centerShade)
                    }
                    drawRect(brush = topBottomShade)
                }
            },
    )
}

@Composable
internal fun HomeLocationCard(
    modifier: Modifier,
    scale: Float,
    language: AppLanguage,
    locations: List<ServerLocation>,
    selectedServerCode: String,
    expanded: Boolean,
    backdrop: LayerBackdrop?,
    liveGlassEnabled: Boolean,
    onToggle: () -> Unit,
    country: String,
    subtitle: String? = null,
) {
    val headerHeight = designDp(NokiUiKitPolicy.homeLocationHeightDp, scale)
    val selectedLocation = locations.firstOrNull { it.code.equals(selectedServerCode, true) }
    val arrowRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing),
        label = "serverArrowRotation",
    )
    HomeSettingsGlassSurface(
        modifier = modifier
            .fillMaxWidth()
            .height(headerHeight),
        backdrop = backdrop,
        liveGlassEnabled = liveGlassEnabled,
        onClick = onToggle,
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(designDp(NokiUiKitPolicy.homeLocationPaddingDp, scale)),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(designDp(20f, scale)),
        ) {
            if (selectedLocation != null) {
                CountryFlagMarker(
                    location = selectedLocation,
                    scale = scale,
                    sizeDp = NokiUiKitPolicy.homeLocationFlagSizeDp,
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(designDp(NokiUiKitPolicy.homeLocationFlagSizeDp, scale))
                        .clip(RoundedCornerShape(percent = 50))
                        .background(HomeStroke.copy(alpha = 0.28f)),
                )
            }
            Column(modifier = Modifier.weight(1f)) {
              Text(
                text = country,
                color = HomeTextPrimary,
                fontFamily = ManropeFontFamily,
                fontWeight = FontWeight.Normal,
                fontSize = designSp(24f, scale),
                letterSpacing = 0.24.sp,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
                style = TextStyle(platformStyle = nokiPlatformTextStyle(includeFontPadding = true)),
              )
              if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    color = HomeTextSecondary,
                    fontFamily = ManropeFontFamily,
                    fontSize = designSp(13f, scale),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
              }
            }
            HomeDropdownArrowButton(
                modifier = Modifier
                    .size(designDp(50f, scale)),
                scale = scale,
                arrowRotation = arrowRotation,
                backdrop = backdrop,
                liveGlassEnabled = liveGlassEnabled,
            )
        }
    }
}
