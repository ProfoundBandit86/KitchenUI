package com.kitchenui.launcher.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kitchenui.launcher.Sky
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * KitchenUI's colors. [light] switches the whole UI between the dark and light themes;
 * it's Compose state, so every screen recolors as soon as it changes.
 */
object K {
    var light by mutableStateOf(false)
    private fun pick(dark: Long, lightC: Long) = Color(if (light) lightC else dark)

    val Bg get() = pick(0xFF121416, 0xFFF4F1EA)
    val Surface get() = pick(0xFF1B1E21, 0xFFFFFFFF)
    val Surface2 get() = pick(0xFF22262A, 0xFFEFEBE3)
    val Border get() = pick(0xFF2A2E33, 0xFFE3DED4)
    val Border2 get() = pick(0xFF3A3F45, 0xFFCDC6BA)
    val Border3 get() = pick(0xFF4A4F55, 0xFFB8B0A3)
    val Box get() = pick(0xFF5A6066, 0xFFA59D90)
    val Text get() = pick(0xFFF3F1EC, 0xFF1B1E21)
    /** Secondary text a step brighter than Muted. */
    val Text2 get() = pick(0xFFC9CCD0, 0xFF3F444A)
    val Text3 get() = pick(0xFFDADCDF, 0xFF2A2E33)
    val Muted get() = pick(0xFFA3A7AD, 0xFF5F646B)
    val Dim get() = pick(0xFF8A8F95, 0xFF8A8F95)
    val Accent get() = pick(0xFFF4A259, 0xFFD9772A)
    /** Text and icons on an Accent fill: dark in both themes. */
    val OnAccent = Color(0xFF121416)
    val Sage get() = pick(0xFF8FC1A9, 0xFF3E7D5F)
    val Blue get() = pick(0xFF7FB2E5, 0xFF2F6CA8)
    val Danger get() = pick(0xFFE58F8F, 0xFFB3423C)
    /** Background for things that need attention (finished timer, update ready). */
    val Warm get() = pick(0xFF3A2A1E, 0xFFFBE4CF)
    val Serif = FontFamily.Serif
}

/**
 * Lays the UI out on a fixed 1600×900 design grid and scales it to fill the screen,
 * so the layout looks the same whatever density Bliss OS reports.
 */
@Composable
fun DesignCanvas(content: @Composable () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize().background(K.Bg), contentAlignment = Alignment.Center) {
        val d = LocalDensity.current
        val wPx = with(d) { maxWidth.toPx() }
        val hPx = with(d) { maxHeight.toPx() }
        val scale = min(wPx / 1600f, hPx / 900f)
        CompositionLocalProvider(
            LocalDensity provides Density(scale, 1f),
            LocalContentColor provides K.Text,
        ) {
            Box(Modifier.size(1600.dp, 900.dp)) { content() }
        }
    }
}

@Composable
fun Card(
    modifier: Modifier = Modifier,
    radius: Dp = 20.dp,
    padding: PaddingValues = PaddingValues(20.dp),
    spacing: Dp = 12.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(radius)
    Column(
        modifier
            .clip(shape)
            .background(K.Surface)
            .border(1.dp, K.Border, shape)
            .padding(padding),
        verticalArrangement = Arrangement.spacedBy(spacing),
        content = content,
    )
}

@Composable
fun Btn(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    height: Dp = 44.dp,
    fontSize: Int = 18,
    icon: Glyph? = null,
) {
    val shape = RoundedCornerShape(height / 3.2f)
    val fg = if (primary) K.OnAccent else K.Text
    Row(
        modifier
            .height(height)
            .clip(shape)
            .then(if (primary) Modifier.background(K.Accent) else Modifier.border(1.dp, K.Border2, shape))
            .clickable(onClick = onClick)
            .padding(horizontal = (height.value * 0.42f).dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
    ) {
        if (icon != null) Icon(icon, fg, (fontSize + 4).dp)
        Text(text, color = fg, fontSize = fontSize.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

@Composable
fun IconBtn(glyph: Glyph, onClick: () -> Unit, modifier: Modifier = Modifier, primary: Boolean = false, size: Dp = 40.dp) {
    val shape = RoundedCornerShape(size / 3.4f)
    val fg = if (primary) K.OnAccent else K.Muted
    Box(
        modifier
            .size(size)
            .clip(shape)
            .then(if (primary) Modifier.background(K.Accent) else Modifier)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(glyph, fg, size * 0.45f) }
}

@Composable
fun CheckBox(checked: Boolean, size: Dp = 26.dp) {
    val shape = RoundedCornerShape(size / 3.4f)
    Box(
        Modifier
            .size(size)
            .clip(shape)
            .then(if (checked) Modifier.background(K.Accent) else Modifier.border(2.dp, K.Box, shape)),
        contentAlignment = Alignment.Center,
    ) { if (checked) Icon(Glyph.CHECK, K.OnAccent, size * 0.65f) }
}

enum class Glyph { PLAY, PAUSE, CLOSE, CHECK, BACK, NEXT, GEAR, GRID, DOWNLOAD, PLUS, CALENDAR, POT, STAR, TV, SEARCH, HEART, BOOK, MAIL, EXPAND, SPLIT, REWIND, FORWARD, SKIP_BACK, SKIP_NEXT, MIC }

@Composable
fun Icon(glyph: Glyph, color: Color, size: Dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val w = this.size.width
        val sw = w * 0.1f
        val stroke = Stroke(width = sw, cap = StrokeCap.Round, join = StrokeJoin.Round)
        fun p(x: Float, y: Float) = Offset(x * w, y * w)
        fun line(x1: Float, y1: Float, x2: Float, y2: Float) =
            drawLine(color, p(x1, y1), p(x2, y2), strokeWidth = sw, cap = StrokeCap.Round)
        when (glyph) {
            Glyph.PLAY -> drawPath(Path().apply {
                moveTo(w * 0.28f, w * 0.18f); lineTo(w * 0.82f, w * 0.5f); lineTo(w * 0.28f, w * 0.82f); close()
            }, color)
            Glyph.PAUSE -> {
                drawRoundRect(color, p(0.24f, 0.18f), Size(w * 0.17f, w * 0.64f), CornerRadius(w * 0.04f))
                drawRoundRect(color, p(0.59f, 0.18f), Size(w * 0.17f, w * 0.64f), CornerRadius(w * 0.04f))
            }
            Glyph.CLOSE -> { line(0.22f, 0.22f, 0.78f, 0.78f); line(0.78f, 0.22f, 0.22f, 0.78f) }
            Glyph.CHECK -> drawPath(Path().apply {
                moveTo(w * 0.18f, w * 0.52f); lineTo(w * 0.4f, w * 0.74f); lineTo(w * 0.82f, w * 0.28f)
            }, color, style = Stroke(width = w * 0.14f, cap = StrokeCap.Round, join = StrokeJoin.Round))
            Glyph.BACK -> drawPath(Path().apply {
                moveTo(w * 0.62f, w * 0.18f); lineTo(w * 0.3f, w * 0.5f); lineTo(w * 0.62f, w * 0.82f)
            }, color, style = stroke)
            Glyph.NEXT -> drawPath(Path().apply {
                moveTo(w * 0.38f, w * 0.18f); lineTo(w * 0.7f, w * 0.5f); lineTo(w * 0.38f, w * 0.82f)
            }, color, style = stroke)
            Glyph.PLUS -> { line(0.5f, 0.2f, 0.5f, 0.8f); line(0.2f, 0.5f, 0.8f, 0.5f) }
            Glyph.GEAR -> {
                drawCircle(color, w * 0.22f, center, style = stroke)
                for (i in 0 until 8) {
                    val a = Math.PI * i / 4
                    val c = center
                    drawLine(
                        color,
                        Offset(c.x + (w * 0.3f) * cos(a).toFloat(), c.y + (w * 0.3f) * sin(a).toFloat()),
                        Offset(c.x + (w * 0.44f) * cos(a).toFloat(), c.y + (w * 0.44f) * sin(a).toFloat()),
                        strokeWidth = sw * 1.4f, cap = StrokeCap.Round,
                    )
                }
            }
            Glyph.GRID -> { for (r in 0..2) for (c in 0..2) drawCircle(color, w * 0.08f, p(0.2f + c * 0.3f, 0.2f + r * 0.3f)) }
            Glyph.DOWNLOAD -> { line(0.5f, 0.15f, 0.5f, 0.62f); line(0.3f, 0.45f, 0.5f, 0.65f); line(0.7f, 0.45f, 0.5f, 0.65f); line(0.2f, 0.85f, 0.8f, 0.85f) }
            Glyph.CALENDAR -> {
                drawRoundRect(color, p(0.14f, 0.22f), Size(w * 0.72f, w * 0.64f), CornerRadius(w * 0.08f), style = stroke)
                line(0.14f, 0.42f, 0.86f, 0.42f); line(0.34f, 0.12f, 0.34f, 0.28f); line(0.66f, 0.12f, 0.66f, 0.28f)
            }
            Glyph.POT -> {
                drawRoundRect(color, p(0.18f, 0.38f), Size(w * 0.64f, w * 0.42f), CornerRadius(w * 0.1f), style = stroke)
                line(0.08f, 0.46f, 0.18f, 0.46f); line(0.82f, 0.46f, 0.92f, 0.46f)
                line(0.36f, 0.16f, 0.36f, 0.26f); line(0.5f, 0.12f, 0.5f, 0.26f); line(0.64f, 0.16f, 0.64f, 0.26f)
            }
            Glyph.TV -> {
                drawRoundRect(color, p(0.1f, 0.26f), Size(w * 0.8f, w * 0.56f), CornerRadius(w * 0.08f), style = stroke)
                line(0.34f, 0.1f, 0.5f, 0.24f); line(0.66f, 0.1f, 0.5f, 0.24f)
            }
            Glyph.SEARCH -> { drawCircle(color, w * 0.26f, p(0.43f, 0.43f), style = stroke); line(0.62f, 0.62f, 0.86f, 0.86f) }
            Glyph.HEART -> drawPath(Path().apply {
                moveTo(w * 0.5f, w * 0.84f)
                cubicTo(w * 0.1f, w * 0.56f, w * 0.06f, w * 0.3f, w * 0.26f, w * 0.2f)
                cubicTo(w * 0.38f, w * 0.14f, w * 0.48f, w * 0.22f, w * 0.5f, w * 0.3f)
                cubicTo(w * 0.52f, w * 0.22f, w * 0.62f, w * 0.14f, w * 0.74f, w * 0.2f)
                cubicTo(w * 0.94f, w * 0.3f, w * 0.9f, w * 0.56f, w * 0.5f, w * 0.84f)
                close()
            }, color)
            Glyph.BOOK -> {
                drawPath(Path().apply {
                    moveTo(w * 0.2f, w * 0.2f); lineTo(w * 0.8f, w * 0.2f); lineTo(w * 0.8f, w * 0.82f); lineTo(w * 0.28f, w * 0.82f)
                    lineTo(w * 0.2f, w * 0.74f); close()
                }, color, style = stroke)
                line(0.36f, 0.38f, 0.66f, 0.38f); line(0.36f, 0.54f, 0.58f, 0.54f)
            }
            Glyph.MAIL -> {
                drawRoundRect(color, p(0.12f, 0.24f), Size(w * 0.76f, w * 0.52f), CornerRadius(w * 0.08f), style = stroke)
                drawPath(Path().apply { moveTo(w * 0.16f, w * 0.3f); lineTo(w * 0.5f, w * 0.54f); lineTo(w * 0.84f, w * 0.3f) }, color, style = stroke)
            }
            Glyph.EXPAND -> {
                line(0.16f, 0.38f, 0.16f, 0.16f); line(0.16f, 0.16f, 0.38f, 0.16f)
                line(0.62f, 0.16f, 0.84f, 0.16f); line(0.84f, 0.16f, 0.84f, 0.38f)
                line(0.16f, 0.62f, 0.16f, 0.84f); line(0.16f, 0.84f, 0.38f, 0.84f)
                line(0.62f, 0.84f, 0.84f, 0.84f); line(0.84f, 0.84f, 0.84f, 0.62f)
            }
            Glyph.SPLIT -> {
                drawRoundRect(color, p(0.12f, 0.2f), Size(w * 0.76f, w * 0.6f), CornerRadius(w * 0.08f), style = stroke)
                line(0.56f, 0.2f, 0.56f, 0.8f)
            }
            Glyph.REWIND, Glyph.FORWARD -> {
                val back = glyph == Glyph.REWIND
                drawArc(color, if (back) 200f else -20f, if (back) 250f else -250f, false, p(0.18f, 0.18f), Size(w * 0.64f, w * 0.64f), style = stroke)
                val tip = if (back) p(0.2f, 0.36f) else p(0.8f, 0.36f)
                drawLine(color, tip, Offset(tip.x, tip.y - w * 0.2f), strokeWidth = sw, cap = StrokeCap.Round)
                drawLine(color, tip, Offset(tip.x + (if (back) w * 0.2f else -w * 0.2f), tip.y), strokeWidth = sw, cap = StrokeCap.Round)
            }
            Glyph.MIC -> {
                drawRoundRect(color, p(0.36f, 0.1f), Size(w * 0.28f, w * 0.48f), CornerRadius(w * 0.14f))
                drawArc(color, 0f, 180f, false, p(0.22f, 0.3f), Size(w * 0.56f, w * 0.44f), style = stroke)
                line(0.5f, 0.74f, 0.5f, 0.88f)
                line(0.36f, 0.88f, 0.64f, 0.88f)
            }
            Glyph.SKIP_BACK -> {
                drawRoundRect(color, p(0.18f, 0.2f), Size(w * 0.12f, w * 0.6f), CornerRadius(w * 0.03f))
                drawPath(Path().apply { moveTo(w * 0.82f, w * 0.2f); lineTo(w * 0.36f, w * 0.5f); lineTo(w * 0.82f, w * 0.8f); close() }, color)
            }
            Glyph.SKIP_NEXT -> {
                drawRoundRect(color, p(0.7f, 0.2f), Size(w * 0.12f, w * 0.6f), CornerRadius(w * 0.03f))
                drawPath(Path().apply { moveTo(w * 0.18f, w * 0.2f); lineTo(w * 0.64f, w * 0.5f); lineTo(w * 0.18f, w * 0.8f); close() }, color)
            }
            Glyph.STAR -> drawPath(Path().apply {
                for (i in 0 until 10) {
                    val rr = if (i % 2 == 0) w * 0.45f else w * 0.2f
                    val a = -Math.PI / 2 + Math.PI * i / 5
                    val x = w / 2 + rr * cos(a).toFloat()
                    val y = w / 2 + rr * sin(a).toFloat()
                    if (i == 0) moveTo(x, y) else lineTo(x, y)
                }
                close()
            }, color)
        }
        Unit
    }
}

@Composable
fun WeatherIcon(sky: Sky, size: Dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val w = this.size.width
        when (sky) {
            Sky.CLEAR -> sun(Offset(w * 0.5f, w * 0.5f), w * 0.2f, w)
            Sky.PARTLY -> { sun(Offset(w * 0.36f, w * 0.34f), w * 0.15f, w); cloud(w, K.Text, 0.08f) }
            Sky.CLOUDY -> cloud(w, K.Text, 0f)
            Sky.FOG -> { for (i in 0..2) drawLine(K.Muted, Offset(w * 0.15f, w * (0.35f + i * 0.17f)), Offset(w * 0.85f, w * (0.35f + i * 0.17f)), strokeWidth = w * 0.07f, cap = StrokeCap.Round) }
            Sky.RAIN -> {
                cloud(w, K.Text, -0.1f)
                for (i in 0..2) drawLine(K.Blue, Offset(w * (0.36f + i * 0.16f), w * 0.72f), Offset(w * (0.31f + i * 0.16f), w * 0.9f), strokeWidth = w * 0.06f, cap = StrokeCap.Round)
            }
            Sky.SNOW -> {
                cloud(w, K.Text, -0.1f)
                for (i in 0..2) drawCircle(K.Text, w * 0.04f, Offset(w * (0.34f + i * 0.16f), w * (0.78f + (i % 2) * 0.08f)))
            }
            Sky.STORM -> {
                cloud(w, K.Muted, -0.1f)
                drawPath(Path().apply {
                    moveTo(w * 0.52f, w * 0.6f); lineTo(w * 0.42f, w * 0.78f); lineTo(w * 0.54f, w * 0.78f); lineTo(w * 0.46f, w * 0.95f)
                }, K.Accent, style = Stroke(width = w * 0.06f, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
        }
        Unit
    }
}

private fun DrawScope.sun(c: Offset, r: Float, w: Float) {
    drawCircle(K.Accent, r, c)
    for (i in 0 until 8) {
        val a = Math.PI * i / 4
        drawLine(
            K.Accent,
            Offset(c.x + r * 1.45f * cos(a).toFloat(), c.y + r * 1.45f * sin(a).toFloat()),
            Offset(c.x + r * 1.95f * cos(a).toFloat(), c.y + r * 1.95f * sin(a).toFloat()),
            strokeWidth = w * 0.05f, cap = StrokeCap.Round,
        )
    }
}

private fun DrawScope.cloud(w: Float, color: Color, dy: Float) {
    val y = w * (0.58f + dy)
    drawCircle(color, w * 0.15f, Offset(w * 0.36f, y))
    drawCircle(color, w * 0.2f, Offset(w * 0.54f, y - w * 0.08f))
    drawCircle(color, w * 0.13f, Offset(w * 0.72f, y + w * 0.02f))
    drawRoundRect(color, Offset(w * 0.22f, y), Size(w * 0.62f, w * 0.15f), CornerRadius(w * 0.075f))
}

@Composable
fun Input(
    value: String,
    onChange: (String) -> Unit,
    hint: String,
    modifier: Modifier = Modifier,
    password: Boolean = false,
    onDone: (() -> Unit)? = null,
) {
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier.height(48.dp).clip(shape).background(K.Surface2).border(1.dp, K.Border2, shape).padding(horizontal = 14.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (value.isEmpty()) Text(hint, color = K.Dim, fontSize = 17.sp, maxLines = 1)
        androidx.compose.foundation.text.BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            textStyle = androidx.compose.ui.text.TextStyle(color = K.Text, fontSize = 17.sp),
            cursorBrush = androidx.compose.ui.graphics.SolidColor(K.Accent),
            visualTransformation = if (password) androidx.compose.ui.text.input.PasswordVisualTransformation()
                else androidx.compose.ui.text.input.VisualTransformation.None,
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                imeAction = if (onDone != null) androidx.compose.ui.text.input.ImeAction.Go else androidx.compose.ui.text.input.ImeAction.Done,
            ),
            keyboardActions = androidx.compose.foundation.text.KeyboardActions(onAny = { onDone?.invoke() }),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
fun SectionTitle(text: String) {
    Text(text.uppercase(), color = K.Accent, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.5.sp)
}
