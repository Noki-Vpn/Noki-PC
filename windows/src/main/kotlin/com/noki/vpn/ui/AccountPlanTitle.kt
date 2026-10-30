package com.noki.vpn.ui
import androidx.compose.runtime.*
import androidx.compose.animation.core.*
import androidx.compose.material3.Text
import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
private fun accountDp(value: Float, scale: Float) = (value * scale).dp
private fun accountSp(value: Float, scale: Float) = (value * scale).sp
internal fun accountPlanTitleShimmerColors(
    baseColor: Color,
    style: AccountPlanTitleStyle,
): List<Color> {
    if (style == AccountPlanTitleStyle.PremiumMetallic) {
        return listOf(
            Color(0xFF9DA9B4),
            Color(0xFFAEB9C3),
            Color(0xFFF7F9FA),
            Color(0xFFC5CDD4),
            Color(0xFF9DA9B4),
        )
    }
    fun highlight(amount: Float) = Color(
        red = baseColor.red + (1f - baseColor.red) * amount,
        green = baseColor.green + (1f - baseColor.green) * amount,
        blue = baseColor.blue + (1f - baseColor.blue) * amount,
        alpha = baseColor.alpha,
    )
    return listOf(
        baseColor,
        highlight(0.20f),
        highlight(0.93f),
        highlight(0.42f),
        baseColor,
    )
}

private class AccountPlanTitleShimmerBrush(
    private val position: Float,
    private val baseColor: Color,
    private val style: AccountPlanTitleStyle,
) : ShaderBrush() {
    override fun createShader(size: Size): Shader {
        val center = Offset(
            x = position * size.width,
            y = position * size.height,
        )
        val halfAxis = Offset(
            x = size.width * 0.55f,
            y = size.height * 0.55f,
        )
        return LinearGradientShader(
            from = center - halfAxis,
            to = center + halfAxis,
            colors = accountPlanTitleShimmerColors(baseColor, style),
            colorStops = listOf(0.00f, 0.32f, 0.50f, 0.68f, 1.00f),
            tileMode = TileMode.Clamp,
        )
    }
}

@Composable
internal fun AccountPlanTitle(
    presentation: AccountPresentation,
    scale: Float,
) {
    val density = LocalDensity.current
    val planColor = Color(presentation.planColorArgb)
    val titleStyle = if (presentation.planTitleStyle != AccountPlanTitleStyle.BackendColor) {
        val transition = rememberInfiniteTransition(label = "accountPremiumTitleShimmer")
        val shimmerPosition by transition.animateFloat(
            initialValue = -0.45f,
            targetValue = 1.45f,
            animationSpec = infiniteRepeatable(
                animation = keyframes {
                    durationMillis = 2800
                    -0.45f at 0
                    -0.45f at 350 using LinearEasing
                    1.45f at 2050
                    1.45f at 2800
                },
                repeatMode = RepeatMode.Restart,
            ),
            label = "accountPremiumTitleShimmerPosition",
        )
        TextStyle(
            brush = remember(shimmerPosition, presentation.planColorArgb, presentation.planTitleStyle) {
                AccountPlanTitleShimmerBrush(
                    position = shimmerPosition,
                    baseColor = planColor,
                    style = presentation.planTitleStyle,
                )
            },
            shadow = Shadow(
                color = Color.Black.copy(alpha = 0.18f),
                offset = Offset(0f, with(density) { accountDp(0.6f, scale).toPx() }),
                blurRadius = with(density) { accountDp(1.2f, scale).toPx() },
            ),
        )
    } else {
        TextStyle(color = planColor)
    }
    Text(
        text = presentation.planTitle,
        style = titleStyle,
        fontFamily = ManropeFontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = accountSp(27f, scale),
        lineHeight = accountSp(32f, scale),
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Ellipsis,
    )
}

