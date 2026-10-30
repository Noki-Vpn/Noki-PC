package com.noki.vpn.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import com.noki.vpn.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@Composable
internal fun AuthInputField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    keyboardType: KeyboardType,
    modifier: Modifier = Modifier,
    isPassword: Boolean = false,
    enabled: Boolean = true,
    textFontSize: TextUnit = authSp(14f),
    autofillContentType: ContentType? = null,
) {
    val metrics = nokiAdaptiveMetrics(nokiScreenSize().width)
    val interactionSource = remember { MutableInteractionSource() }
    val passwordIconInteractionSource = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(metrics.dp(20f))
    val placeholderFontSize = authSp(14f)
    val line12 = authSp(12f)
    var passwordVisible by remember { mutableStateOf(false) }

    Box(
        modifier = modifier
            .background(NokiBgLighter, shape)
            .border(1.dp, NokiStroke, shape),
    ) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            enabled = enabled,
            modifier = Modifier
                .fillMaxSize()
                .then(if (autofillContentType != null) Modifier.semantics { contentType = autofillContentType } else Modifier)
                .padding(start = metrics.dp(19f), end = if (isPassword) metrics.dp(61f) else metrics.dp(19f)),
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
            visualTransformation = if (isPassword && !passwordVisible) PasswordVisualTransformation() else VisualTransformation.None,
            interactionSource = interactionSource,
            cursorBrush = SolidColor(NokiAccentStrong),
            textStyle = TextStyle(
                color = NokiTextPrimary,
                fontSize = textFontSize,
                lineHeight = line12,
                fontFamily = ManropeFontFamily,
                fontWeight = FontWeight.Normal,
                letterSpacing = authSp(0.14f),
                platformStyle = nokiPlatformTextStyle(includeFontPadding = false),
            ),
            decorationBox = { innerTextField ->
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    if (value.isEmpty()) {
                        Text(
                            text = placeholder,
                            color = NokiTextMuted,
                            fontSize = placeholderFontSize,
                            lineHeight = line12,
                            fontFamily = ManropeFontFamily,
                            fontWeight = FontWeight.Normal,
                            letterSpacing = authSp(0.14f),
                            maxLines = 1,
                            overflow = TextOverflow.Clip,
                        )
                    }
                    innerTextField()
                }
            },
        )
        if (isPassword) {
            PasswordVisibilityIcon(
                visible = passwordVisible,
                tint = NokiTextMuted,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = metrics.dp(20f))
                    .size(width = metrics.dp(22f), height = metrics.dp(17f))
                    .clickable(
                        interactionSource = passwordIconInteractionSource,
                        indication = null,
                        onClick = { passwordVisible = !passwordVisible },
                    ),
            )
        }
    }
}

@Composable
internal fun PasswordVisibilityIcon(
    visible: Boolean,
    tint: Color,
    modifier: Modifier = Modifier,
) {
    val metrics = nokiAdaptiveMetrics(nokiScreenSize().width)
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painter = nokiPainterResource(
                if (visible) {
                    R.drawable.auth_visibility_eye_open
                } else {
                    R.drawable.auth_visibility_eye_closed
                },
            ),
            contentDescription = null,
            colorFilter = ColorFilter.tint(tint),
            modifier = Modifier
                .size(
                    width = metrics.dp(23.5f),
                    height = if (visible) metrics.dp(18.5f) else metrics.dp(12.5f),
                )
                .offset(y = if (visible) 0.dp else metrics.dp(2f)),
        )
    }
}

@Composable
internal fun AuthFieldLabel(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        modifier = modifier,
        color = NokiTextPrimary,
        fontSize = authSp(13f),
        lineHeight = authSp(16f),
        fontFamily = ManropeFontFamily,
        fontWeight = FontWeight.Normal,
        letterSpacing = authSp(0.13f),
        maxLines = 1,
        overflow = TextOverflow.Clip,
        style = TextStyle(platformStyle = nokiPlatformTextStyle(includeFontPadding = false)),
    )
}

@Composable
internal fun AuthRequirementText(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        modifier = modifier,
        color = NokiTextSecondary,
        fontSize = authSp(11f),
        lineHeight = authSp(13f),
        fontFamily = ManropeFontFamily,
        fontWeight = FontWeight.Normal,
        letterSpacing = authSp(0.11f),
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        style = TextStyle(platformStyle = nokiPlatformTextStyle(includeFontPadding = false)),
    )
}

@Composable
internal fun rememberAuthButtonClickAnimator(): AuthButtonClickAnimator {
    val animationScope = rememberCoroutineScope()
    val scale = remember { Animatable(1f) }
    return remember(animationScope, scale) {
        AuthButtonClickAnimator(
            scale = scale,
            animationScope = animationScope,
        )
    }
}

internal class AuthButtonClickAnimator(
    val scale: Animatable<Float, AnimationVector1D>,
    private val animationScope: CoroutineScope,
) {
    private val clickGate = AuthButtonClickGate()

    fun runClickAnimation(
        enabled: Boolean = true,
        then: () -> Unit,
    ) {
        if (!enabled || !clickGate.tryStart()) return
        animationScope.launch {
            try {
                scale.stop()
                scale.snapTo(1f)
                scale.animateTo(
                    targetValue = 1.08f,
                    animationSpec = spring(
                        dampingRatio = 0.7f,
                        stiffness = 900f,
                    ),
                )
                then()
                scale.animateTo(
                    targetValue = 1f,
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioMediumBouncy,
                        stiffness = Spring.StiffnessMediumLow,
                    ),
                )
            } finally {
                clickGate.finish()
            }
        }
    }
}

internal class AuthButtonClickGate {
    private var running = false

    fun tryStart(): Boolean {
        if (running) return false
        running = true
        return true
    }

    fun finish() {
        running = false
    }
}

@Composable
internal fun Modifier.authFormScroll(maxHeight: Dp, contentBottom: Dp): Modifier {
    val scrollState = rememberScrollState()
    val density = LocalDensity.current
    val imeBottom = authImeBottom()
    LaunchedEffect(maxHeight, contentBottom, imeBottom, scrollState.maxValue) {
        scrollState.scrollTo(with(density) {
            authKeyboardShift(maxHeight, imeBottom, contentBottom).roundToPx()
        })
    }
    return imePadding().verticalScroll(scrollState)
}

@Composable
internal fun authImeBottom(): Dp {
    val density = LocalDensity.current
    return with(density) { WindowInsets.ime.getBottom(this).toDp() }
}

internal fun authKeyboardShift(
    maxHeight: Dp,
    imeBottom: Dp,
    contentBottom: Dp,
): Dp {
    if (imeBottom <= 0.dp) return 0.dp
    val targetBottom = maxHeight - imeBottom - 5.dp
    return (contentBottom - targetBottom).coerceAtLeast(0.dp)
}
