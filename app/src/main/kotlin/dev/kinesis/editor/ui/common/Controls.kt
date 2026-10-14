package dev.kinesis.editor.ui.common

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.kinesis.editor.R
import dev.kinesis.editor.ui.theme.KColors
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import dev.kinesis.core.math.Color as KColor

/** Formats a number compactly: integers without decimals, otherwise up to [decimals] places. */
fun formatNumber(v: Float, decimals: Int = 2): String {
    if (abs(v - v.roundToInt()) < 0.0005f) return v.roundToInt().toString()
    return String.format(Locale.US, "%.${decimals}f", v).trimEnd('0').trimEnd('.')
}

fun formatTime(seconds: Double, fps: Double? = null): String {
    val s = seconds.coerceAtLeast(0.0)
    val m = (s / 60).toInt()
    val sec = s - m * 60
    return if (fps != null) {
        val frame = ((sec - sec.toInt()) * fps).roundToInt().coerceAtMost(fps.roundToInt() - 1)
        String.format(Locale.US, "%02d:%02d.%02d", m, sec.toInt(), frame)
    } else {
        String.format(Locale.US, "%d:%05.2f", m, sec)
    }
}

/**
 * A number that changes when dragged horizontally (like desktop motion apps) and can be typed after a tap.
 * Values are shown multiplied by [display].
 */
@Composable
fun ScrubNumber(
    value: Float,
    onChange: (Float) -> Unit,
    onChangeFinished: () -> Unit,
    modifier: Modifier = Modifier,
    step: Float = 1f,
    display: Float = 1f,
    min: Float = Float.NEGATIVE_INFINITY,
    max: Float = Float.POSITIVE_INFINITY,
    suffix: String = "",
    tint: Color = KColors.TextPrimary,
) {
    var editing by remember { mutableStateOf(false) }
    val currentValue by rememberUpdatedState(value)
    val change by rememberUpdatedState(onChange)
    val finished by rememberUpdatedState(onChangeFinished)
    var accum by remember { mutableFloatStateOf(0f) }
    Box(
        modifier
            .height(32.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(KColors.SurfaceHighest)
            .pointerInput(step, min, max) {
                detectHorizontalDragGestures(
                    onDragStart = { accum = currentValue },
                    onDragEnd = { finished() },
                    onDragCancel = { finished() },
                ) { change2, dx ->
                    change2.consume()
                    accum = (accum + dx / density * step / 4f / display).coerceIn(min, max)
                    change(accum)
                }
            }
            .pointerInput(Unit) { detectTapGestures(onTap = { editing = true }) }
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(formatNumber(value * display) + suffix, color = tint, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Clip)
    }
    if (editing) {
        NumberInputDialog(
            initial = value * display,
            onDismiss = { editing = false },
            onConfirm = {
                onChange((it / display).coerceIn(min, max))
                onChangeFinished()
                editing = false
            },
        )
    }
}

@Composable
fun NumberInputDialog(initial: Float, onDismiss: () -> Unit, onConfirm: (Float) -> Unit) {
    var text by remember { mutableStateOf(formatNumber(initial, 3)) }
    val parsed = text.replace(',', '.').toFloatOrNull()
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(enabled = parsed != null, onClick = { parsed?.let(onConfirm) }) { Text(stringResource(R.string.ok)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
        title = { Text(stringResource(R.string.enter_value)) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            )
        },
    )
}

@Composable
fun TextInputDialog(title: String, initial: String, onDismiss: () -> Unit, multiline: Boolean = false, onConfirm: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = { onConfirm(text) }) { Text(stringResource(R.string.ok)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
        title = { Text(title) },
        text = {
            OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = !multiline, minLines = if (multiline) 3 else 1)
        },
    )
}

/** Labelled slider with a scrubbable value. */
@Composable
fun LabeledSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
    onChangeFinished: () -> Unit,
    display: Float = 1f,
    suffix: String = "",
    min: Float = Float.NEGATIVE_INFINITY,
    max: Float = Float.POSITIVE_INFINITY,
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = KColors.TextSecondary, modifier = Modifier.width(92.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = { onChange(it.coerceIn(min, max)) },
            onValueChangeFinished = onChangeFinished,
            valueRange = range,
            modifier = Modifier.weight(1f).height(32.dp),
            colors = SliderDefaults.colors(thumbColor = KColors.Accent, activeTrackColor = KColors.Accent),
        )
        Spacer(Modifier.width(6.dp))
        ScrubNumber(
            value, onChange, onChangeFinished, Modifier.width(64.dp),
            step = (range.endInclusive - range.start) / 100f, display = display, min = min, max = max, suffix = suffix,
        )
    }
}

/** Single-choice chips. */
@Composable
fun <T> ChoiceChips(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit, modifier: Modifier = Modifier) {
    LazyRow(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        items(options) { (value, label) ->
            FilterChip(selected = value == selected, onClick = { onSelect(value) }, label = { Text(label, fontSize = 12.sp) })
        }
    }
}

@Composable
fun ColorSwatch(color: KColor, onClick: () -> Unit, modifier: Modifier = Modifier, size: Int = 28) {
    Box(
        modifier
            .size(size.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(checker())
            .background(Color(color.r, color.g, color.b, color.a))
            .border(1.dp, KColors.Outline, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick),
    )
}

private fun checker() = Brush.linearGradient(listOf(Color(0xFF555555), Color(0xFF999999)))

/** HSV + alpha colour picker with hex input and a palette. */
@Composable
fun ColorPickerDialog(initial: KColor, onDismiss: () -> Unit, onChange: (KColor) -> Unit, onConfirm: (KColor) -> Unit) {
    val hsv = remember { FloatArray(3).also { android.graphics.Color.colorToHSV(initial.toArgb(), it) } }
    var hue by remember { mutableFloatStateOf(hsv[0]) }
    var sat by remember { mutableFloatStateOf(hsv[1]) }
    var value by remember { mutableFloatStateOf(hsv[2]) }
    var alpha by remember { mutableFloatStateOf(initial.a) }
    var hex by remember { mutableStateOf(initial.toHex()) }
    fun current(): KColor {
        val argb = android.graphics.Color.HSVToColor((alpha * 255).roundToInt(), floatArrayOf(hue, sat, value))
        return KColor.fromArgb(argb)
    }
    fun push() {
        val c = current()
        hex = c.toHex()
        onChange(c)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = { onConfirm(current()) }) { Text(stringResource(R.string.ok)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
        title = { Text(stringResource(R.string.color)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                // Saturation / value square
                val hueColor = Color(android.graphics.Color.HSVToColor(floatArrayOf(hue, 1f, 1f)))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(160.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Brush.horizontalGradient(listOf(Color.White, hueColor)))
                        .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black)))
                        .pointerInput(Unit) {
                            fun pick(o: Offset) {
                                sat = (o.x / size.width).coerceIn(0f, 1f)
                                value = 1f - (o.y / size.height).coerceIn(0f, 1f)
                                push()
                            }
                            detectTapGestures { pick(it) }
                        }
                        .pointerInput(Unit) {
                            detectDragGestures { change, _ ->
                                change.consume()
                                sat = (change.position.x / size.width).coerceIn(0f, 1f)
                                value = 1f - (change.position.y / size.height).coerceIn(0f, 1f)
                                push()
                            }
                        },
                ) {
                    Canvas(Modifier.matchParentSize()) {
                        val c = Offset(sat * size.width, (1f - value) * size.height)
                        drawCircle(Color.White, 9.dp.toPx(), c, style = Stroke(2.dp.toPx()))
                    }
                }
                // Hue
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(22.dp)
                        .clip(RoundedCornerShape(11.dp))
                        .background(Brush.horizontalGradient((0..6).map { Color(android.graphics.Color.HSVToColor(floatArrayOf(it * 60f, 1f, 1f))) }))
                        .pointerInput(Unit) {
                            detectTapGestures { hue = (it.x / size.width * 360f).coerceIn(0f, 359.9f); push() }
                        }
                        .pointerInput(Unit) {
                            detectHorizontalDragGestures { change, _ ->
                                change.consume()
                                hue = (change.position.x / size.width * 360f).coerceIn(0f, 359.9f)
                                push()
                            }
                        },
                ) {
                    Canvas(Modifier.matchParentSize()) {
                        drawCircle(Color.White, 9.dp.toPx(), Offset(hue / 360f * size.width, size.height / 2), style = Stroke(2.dp.toPx()))
                    }
                }
                LabeledSlider(stringResource(R.string.opacity), alpha, 0f..1f, { alpha = it; push() }, {}, display = 100f, suffix = "%", min = 0f, max = 1f)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = hex,
                        onValueChange = { t ->
                            hex = t
                            KColor.parseOrNull(t)?.let { c ->
                                val h = FloatArray(3)
                                android.graphics.Color.colorToHSV(c.toArgb(), h)
                                hue = h[0]; sat = h[1]; value = h[2]; alpha = c.a
                                onChange(c)
                            }
                        },
                        singleLine = true,
                        label = { Text("HEX") },
                        modifier = Modifier.weight(1f),
                    )
                }
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(PALETTE) { argb ->
                        Box(
                            Modifier
                                .size(26.dp)
                                .clip(CircleShape)
                                .background(Color(argb))
                                .border(1.dp, KColors.Outline, CircleShape)
                                .clickable {
                                    val h = FloatArray(3)
                                    android.graphics.Color.colorToHSV(argb.toInt(), h)
                                    hue = h[0]; sat = h[1]; value = h[2]; alpha = 1f
                                    push()
                                },
                        )
                    }
                }
            }
        },
    )
}

private val PALETTE = listOf(
    0xFFFFFFFF, 0xFF000000, 0xFF7C5CFF, 0xFF4FD1C5, 0xFFFF6B8B, 0xFFFFC857, 0xFF5EA8FF, 0xFF9BE564,
    0xFFFF8A3D, 0xFF0A2463, 0xFF1E4FBF, 0xFFC0C4CC, 0xFF2B2D42, 0xFFE63946,
)
