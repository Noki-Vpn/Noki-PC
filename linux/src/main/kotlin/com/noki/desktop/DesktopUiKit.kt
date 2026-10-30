package com.noki.desktop

import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.graphics.shadow.DropShadowPainter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.platform.Font
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.noki.vpn.data.AccentPalette
import com.noki.vpn.data.AppLanguage
import com.noki.vpn.ui.ManropeFontFamily
import com.noki.vpn.ui.nokiPainterResource
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.highlight.Highlight

/** Figma 1994:6020 and 1994:6079; existing controls and resources keep their owners. */
internal object DesktopUiKit {
    val Canvas = Color(0xFF131313)
    val Surface = Color(0xFF272727)
    val NestedSurface = Color(0xFF3C3C3C)
    val Glass = Color(0xFF878787).copy(alpha = .15f)
    val Text = Color(0xFFF4FBFF)
    val Muted = Color(0xFF878787)
    val MetricUnit = Color(0xFF9FB6C5)
    val Accent = Color(0xFF7AE7C7)
    val Selected = Color(0xFF7AE7C7).copy(alpha = .8f)
    val Error = Color(0xFFFF6B6B)
    const val ControlHighlight = .16f
    val Shape = RoundedCornerShape(10.dp)
    val PanelShape = RoundedCornerShape(20.dp)
    val WindowShape = RoundedCornerShape(8.dp)
    val ConnectionShadow = Shadow(radius = 6.dp, spread = 0.dp,
        color = Color.Black.copy(alpha = .15f), offset = DpOffset(0.dp, 4.dp))
    val MenuShape = RoundedCornerShape(14.dp)
    val LeadingControlShape = RoundedCornerShape(topStart = 20.dp, bottomStart = 20.dp, topEnd = 14.dp, bottomEnd = 14.dp)
    val TrailingControlShape = RoundedCornerShape(topStart = 14.dp, bottomStart = 14.dp, topEnd = 20.dp, bottomEnd = 20.dp)
    val HomePanelWidth = 354.dp
    const val SectionTitleSize = 22
    fun sceneFits(width: Dp, height: Dp) = width >= 760.dp && height >= 540.dp
    val MetricFont = FontFamily(Font("noki/desktop/InterVariable.ttf", FontWeight.Normal))
}

internal data class DesktopStyle(
    val backdrop: LayerBackdrop? = null,
    val accent: Color = DesktopUiKit.Accent,
    val selected: Color = DesktopUiKit.Selected,
)

internal val LocalDesktopStyle = staticCompositionLocalOf { DesktopStyle() }
internal val LocalDesktopLanguage = staticCompositionLocalOf { AppLanguage.RU }
internal val LocalPcCardColor = staticCompositionLocalOf { DesktopUiKit.Surface }

internal fun Modifier.pcConnectionShadow(): Modifier = drawWithCache {
    val path = Path().apply { addOutline(DesktopUiKit.PanelShape.createOutline(size, layoutDirection, this@drawWithCache)) }
    val shadow = DropShadowPainter(DesktopUiKit.PanelShape, DesktopUiKit.ConnectionShadow)
    onDrawWithContent {
        // Match the design's unchecked "Show behind transparent areas" option.
        clipPath(path, ClipOp.Difference) { with(shadow) { draw(size) } }
        drawContent()
    }
}

@Composable internal fun DesktopTheme(
    backdrop: LayerBackdrop?,
    palette: AccentPalette = AccentPalette.GREEN,
    language: AppLanguage = AppLanguage.RU,
    content: @Composable () -> Unit,
) {
    val accent = Color(palette.argb)
    val selected = if (palette == AccentPalette.GREEN) DesktopUiKit.Selected else accent.copy(alpha = .8f)
    val typography = Typography().let { defaults -> defaults.copy(
        bodyLarge = defaults.bodyLarge.copy(fontFamily = ManropeFontFamily),
        bodyMedium = defaults.bodyMedium.copy(fontFamily = ManropeFontFamily),
        bodySmall = defaults.bodySmall.copy(fontFamily = ManropeFontFamily),
        labelLarge = defaults.labelLarge.copy(fontFamily = ManropeFontFamily),
        titleLarge = defaults.titleLarge.copy(fontFamily = ManropeFontFamily),
    ) }
    CompositionLocalProvider(LocalDesktopStyle provides DesktopStyle(backdrop, accent, selected), LocalDesktopLanguage provides language) {
        MaterialTheme(colorScheme = darkColorScheme(
            primary = accent, onPrimary = DesktopUiKit.Canvas,
            background = DesktopUiKit.Canvas, onBackground = DesktopUiKit.Text,
            surface = DesktopUiKit.Surface, onSurface = DesktopUiKit.Text,
            onSurfaceVariant = DesktopUiKit.MetricUnit, outline = DesktopUiKit.Muted,
            error = DesktopUiKit.Error,
        ), typography = typography, content = content)
    }
}

@Composable internal fun Modifier.pcGlass(
    color: Color = DesktopUiKit.Glass,
    shape: Shape = DesktopUiKit.Shape,
    blurRadius: Dp = 35.dp,
    lightIntensity: Float = .12f,
    rimWidth: Dp = 1.dp,
    depth: Dp = 20.dp,
    refraction: Float = .8f,
): Modifier {
    val style = LocalDesktopStyle.current
    return then(if (style.backdrop != null) {
        Modifier.drawBackdrop(
            backdrop = style.backdrop,
            shape = { shape },
            effects = {
                blur(blurRadius.toPx())
                val lensDepth = minOf(depth.toPx(), size.minDimension * .45f)
                if (shape is CornerBasedShape) lens(lensDepth, lensDepth * refraction, depthEffect = true, chromaticAberration = true)
            },
            highlight = { Highlight(width = rimWidth, alpha = lightIntensity) },
            shadow = null,
            onDrawSurface = { drawRect(color) },
        )
    } else Modifier.clip(shape).background(color.copy(alpha = if (color.alpha < .2f) 1f else color.alpha).let {
        if (color.alpha == 0f) Color.Transparent else if (color.alpha < .2f) Color(0xFF202020) else it
    }))
}

@Composable internal fun Modifier.pcCard(color: Color = LocalPcCardColor.current, shape: Shape = DesktopUiKit.PanelShape): Modifier =
    clip(shape).background(color)

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable internal fun Modifier.pcHorizontalScroll(): Modifier {
    val scroll = rememberScrollState()
    val distance = with(LocalDensity.current) { 48.dp.toPx() }
    return onPointerEvent(PointerEventType.Scroll, PointerEventPass.Initial) { event ->
        val delta = event.changes.sumOf { (it.scrollDelta.x + it.scrollDelta.y).toDouble() }.toFloat() * distance
        if (scroll.dispatchRawDelta(delta) != 0f) event.changes.forEach { it.consume() }
    }.horizontalScroll(scroll)
}

@Composable internal fun Modifier.pcClickable(enabled: Boolean = true, role: Role = Role.Button, shape: Shape = DesktopUiKit.PanelShape,
    hoverColor: Color = Color.White.copy(alpha = .045f), onClick: () -> Unit): Modifier {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    return alpha(if (enabled) 1f else .45f).clip(shape).hoverable(interaction, enabled = enabled).clickable(interactionSource = interaction, indication = null,
        enabled = enabled, role = role, onClick = onClick).drawWithContent {
        if (enabled && hovered) drawRect(hoverColor)
        drawContent()
    }
}

@Composable internal fun PcSurface(
    modifier: Modifier = Modifier,
    color: Color = DesktopUiKit.Glass,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit,
) = Box(modifier.pcGlass(color, DesktopUiKit.PanelShape)
    .then(if (onClick != null) Modifier.pcClickable(enabled = enabled, shape = DesktopUiKit.PanelShape, onClick = onClick) else Modifier), content = content)

@Composable internal fun PcText(
    text: String,
    modifier: Modifier = Modifier,
    size: Int = 14,
    color: Color = DesktopUiKit.Text,
    maxLines: Int = Int.MAX_VALUE,
    metric: Boolean = false,
    weight: FontWeight = FontWeight.Normal,
    lineHeight: Int? = null,
    textAlign: androidx.compose.ui.text.style.TextAlign = androidx.compose.ui.text.style.TextAlign.Unspecified,
) = Text(text.replace('\u2014', '-').replace('\u2013', '-'), modifier,
    style = TextStyle.Default, color = color, textAlign = textAlign,
    fontFamily = if (metric) DesktopUiKit.MetricFont else ManropeFontFamily,
    fontWeight = weight, fontSize = size.sp, lineHeight = (lineHeight?.toFloat() ?: size * 1.4f).sp,
    maxLines = maxLines, overflow = TextOverflow.Ellipsis)

@Composable internal fun pcPainter(name: String): Painter = nokiPainterResource("noki/desktop/$name.svg")

@Composable internal fun PcIcon(name: String, description: String?, modifier: Modifier = Modifier.size(24.dp)) =
    Image(pcPainter(name), description, modifier)

@Composable internal fun PcButton(
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    selected: Boolean = false,
    danger: Boolean = false,
    compact: Boolean = false,
    glass: Boolean = true,
    glassColor: Color = LocalPcCardColor.current,
    glassBlurRadius: Dp = 4.dp,
    glassLightIntensity: Float = DesktopUiKit.ControlHighlight,
    shape: Shape = DesktopUiKit.MenuShape,
    iconResource: Int? = null,
    iconName: String? = null,
    iconVector: ImageVector? = null,
    iconSize: Dp = 24.dp,
    scale: Float = 1f,
    textColor: Color = DesktopUiKit.Text,
    onClick: () -> Unit,
) {
    val style = LocalDesktopStyle.current
    val color = when {
        danger -> DesktopUiKit.Error.copy(alpha = .8f)
        selected -> style.selected
        else -> if (glass) glassColor else LocalPcCardColor.current
    }
    Box(modifier.heightIn(min = ((if (compact) 35 else 44) * scale).dp)
        .then(if (glass) Modifier.pcGlass(color, shape, glassBlurRadius, glassLightIntensity) else Modifier.pcCard(color, shape))
        .pcClickable(enabled = enabled, shape = shape, onClick = onClick).padding(horizontal = ((if (compact) 12 else 14) * scale).dp),
        contentAlignment = Alignment.Center) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(((if (compact) 8 else 12) * scale).dp)) {
            iconResource?.let { Image(com.noki.vpn.ui.nokiPainterResource(it), null, Modifier.size(iconSize * scale)) }
            iconName?.let { PcIcon(it, null, Modifier.size(iconSize * scale)) }
            iconVector?.let { Icon(it, null, Modifier.size(iconSize * scale), tint = textColor) }
            if (label.isNotEmpty()) PcText(label, size = ((if (compact) 12 else 14) * scale).toInt(), maxLines = 1, color = textColor)
        }
    }
}

@Composable internal fun PcIconButton(resource: Int, label: String, enabled: Boolean = true, danger: Boolean = false, onClick: () -> Unit) {
    Box(Modifier.size(40.dp).pcGlass(if (danger) DesktopUiKit.Error.copy(alpha = .16f) else LocalPcCardColor.current, DesktopUiKit.Shape, 4.dp, DesktopUiKit.ControlHighlight)
        .pcClickable(enabled = enabled, shape = DesktopUiKit.Shape, onClick = onClick).semantics { contentDescription = label }, contentAlignment = Alignment.Center) {
        Image(com.noki.vpn.ui.nokiPainterResource(resource), null, Modifier.size(20.dp))
    }
}

@Composable internal fun PcInput(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    password: Boolean = false,
    enabled: Boolean = true,
    singleLine: Boolean = true,
    error: Boolean = false,
    shape: Shape = DesktopUiKit.MenuShape,
    textAlign: TextAlign = TextAlign.Start,
    fontSize: Int = 14,
    contentPadding: PaddingValues = PaddingValues(14.dp),
    onSubmit: (() -> Unit)? = null,
) {
    val style = LocalDesktopStyle.current
    var editingValue by remember { mutableStateOf(TextFieldValue(value)) }
    val fieldValue = if (editingValue.text == value) editingValue else TextFieldValue(value, TextRange(value.length))
    BasicTextField(fieldValue, { editingValue = it; if (it.text != value) onValueChange(it.text) },
        modifier.onPreviewKeyEvent {
            if (onSubmit != null && fieldValue.composition == null && it.key == Key.Enter && it.type == KeyEventType.KeyDown &&
                !it.isShiftPressed && !it.isCtrlPressed && !it.isAltPressed && !it.isMetaPressed) {
                onSubmit()
                true
            } else false
        }.heightIn(min = 48.dp).pcCard(shape = shape)
        .then(if (error) Modifier.border(1.dp, DesktopUiKit.Error, shape) else Modifier)
        .padding(contentPadding).semantics { contentDescription = label },
        enabled = enabled, singleLine = singleLine,
        textStyle = TextStyle(color = DesktopUiKit.Text, fontFamily = ManropeFontFamily, fontSize = fontSize.sp, lineHeight = 20.sp, textAlign = textAlign),
        cursorBrush = SolidColor(style.accent),
        visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = if (password) KeyboardType.Password else KeyboardType.Text),
        decorationBox = { inner -> Box(Modifier.fillMaxWidth(), contentAlignment = if (textAlign == TextAlign.Center) Alignment.Center else Alignment.CenterStart) {
            if (value.isEmpty()) PcText(label, size = fontSize, lineHeight = 20, color = DesktopUiKit.Muted)
            inner()
        } })
}

@Composable internal fun PcToggle(label: String, value: Boolean, enabled: Boolean = true, detail: String? = null, onChange: (Boolean) -> Unit) {
    val style = LocalDesktopStyle.current
    val russian = LocalDesktopLanguage.current == AppLanguage.RU
    Row(Modifier.fillMaxWidth().heightIn(min = if (detail == null) 64.dp else 76.dp).pcCard(shape = DesktopUiKit.MenuShape)
        .pcClickable(enabled = enabled, role = Role.Switch, shape = DesktopUiKit.MenuShape, onClick = { onChange(!value) })
        .semantics { stateDescription = if (value) { if (russian) "Включено" else "On" } else { if (russian) "Выключено" else "Off" } }
        .padding(horizontal = 20.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            PcText(label, size = 14, lineHeight = 20)
            detail?.let { PcText(it, size = 12, lineHeight = 16, color = Color(0xFFB9B9B9)) }
        }
        Spacer(Modifier.width(16.dp))
        Box(Modifier.size(34.dp, 20.dp).background(if (value) style.selected else DesktopUiKit.Muted, CircleShape)) {
            Box(Modifier.align(if (value) Alignment.CenterEnd else Alignment.CenterStart)
                .padding(horizontal = 3.dp).size(14.dp).background(DesktopUiKit.Text, CircleShape))
        }
    }
}

@Composable internal fun PcDialog(
    title: String,
    onDismiss: () -> Unit,
    confirmLabel: String? = null,
    enabled: Boolean = true,
    danger: Boolean = false,
    onConfirm: () -> Unit = {},
    dismissLabel: String? = null,
    fullScreen: Boolean = false,
    scrollContent: Boolean = true,
    spacing: Dp = 16.dp,
    width: Dp = 520.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val russian = LocalDesktopLanguage.current == AppLanguage.RU
    Popup(onDismissRequest = onDismiss, properties = PopupProperties(focusable = true,
        usePlatformDefaultWidth = false, usePlatformInsets = false), onPreviewKeyEvent = {
        if (it.key == Key.Escape && it.type == KeyEventType.KeyDown) { onDismiss(); true } else false
    }) {
    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .55f)).clickable(
        interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss),
        contentAlignment = Alignment.Center) {
        Column((if (fullScreen) Modifier.padding(16.dp).fillMaxSize() else Modifier.heightIn(max = maxHeight - 32.dp).width(minOf(width, maxWidth - 32.dp)))
            .pcCard(DesktopUiKit.Surface).border(.5.dp, Color.White.copy(alpha = .12f), DesktopUiKit.PanelShape)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = {})
            .padding(24.dp), verticalArrangement = Arrangement.spacedBy(spacing)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.End) {
                if (title.isNotEmpty()) PcText(title, Modifier.weight(1f), size = 20)
                Box(Modifier.size(40.dp).pcClickable(shape = DesktopUiKit.Shape, onClick = onDismiss), contentAlignment = Alignment.Center) {
                    PcIcon("dialog-close", if (russian) "Закрыть" else "Close", Modifier.size(20.dp))
                }
            }
            CompositionLocalProvider(LocalPcCardColor provides DesktopUiKit.NestedSurface) {
                Column(Modifier.weight(1f, fill = fullScreen).then(if (scrollContent) Modifier.verticalScroll(rememberScrollState()) else Modifier),
                    verticalArrangement = Arrangement.spacedBy(spacing), content = content)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CompositionLocalProvider(LocalPcCardColor provides DesktopUiKit.NestedSurface) {
                PcButton(dismissLabel ?: if (russian) "Отмена" else "Cancel", Modifier.weight(1f), onClick = onDismiss)
                if (confirmLabel != null) PcButton(confirmLabel, Modifier.weight(1f), enabled = enabled,
                    selected = !danger, danger = danger, onClick = onConfirm)
                }
            }
        }
    }
    }
}
