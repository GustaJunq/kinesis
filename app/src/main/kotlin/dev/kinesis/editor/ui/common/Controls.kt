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
import dev.kinesis.editor.ui.theme.KBrushes
import dev.kinesis.editor.ui.theme.KColors
import dev.kinesis.editor.ui.theme.KDims
import dev.kinesis.editor.ui.theme.KShapes
import dev.kinesis.editor.ui.theme.KType
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
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
            .height(36.dp)
            .clip(KShapes.Field)
            .background(KColors.Field)
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
        Text(formatNumber(value * display) + suffix, color = tint, style = KType.Mono, maxLines = 1, overflow = TextOverflow.Clip)
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
            modifier = Modifier.weight(1f).height(KDims.MinTouch),
            colors = kSliderColors(),
        )
        Spacer(Modifier.width(6.dp))
        ScrubNumber(
            value, onChange, onChangeFinished, Modifier.width(64.dp),
            step = (range.endInclusive - range.start) / 100f, display = display, min = min, max = max, suffix = suffix,
        )
    }
}

/** Single-choice pill chips (active: ChipActive / OnChipActive). */
@Composable
fun <T> ChoiceChips(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit, modifier: Modifier = Modifier) {
    LazyRow(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        items(options) { (value, label) -> PillChip(label, value == selected, { onSelect(value) }) }
    }
}

/** A pill-shaped chip; 48 dp tall touch target around a 32 dp pill. */
@Composable
fun PillChip(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Box(
        modifier
            .heightIn(min = KDims.MinTouch)
            .semantics { this.selected = selected }
            .clip(KShapes.Pill)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .height(32.dp)
                .clip(KShapes.Pill)
                .background(if (selected) KColors.ChipActive else KColors.Field)
                .border(1.dp, if (selected) KColors.AccentStart else KColors.LineStrong, KShapes.Pill)
                .padding(horizontal = 14.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                label, maxLines = 1, style = MaterialTheme.typography.labelMedium,
                color = when { !enabled -> KColors.Disabled; selected -> KColors.OnChipActive; else -> KColors.TextSoft },
            )
        }
    }
}

@Composable
fun kSliderColors() = SliderDefaults.colors(
    thumbColor = KColors.White,
    activeTrackColor = KColors.AccentStart,
    inactiveTrackColor = KColors.LineStrong,
    disabledThumbColor = KColors.Disabled,
    disabledActiveTrackColor = KColors.Disabled,
)

/** Primary action: pill with the accent gradient. */
@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    enabled: Boolean = true,
    loading: Boolean = false,
    compact: Boolean = false,
) {
    Row(
        modifier
            .heightIn(min = if (compact) 40.dp else 52.dp)
            .clip(KShapes.Pill)
            .background(if (enabled) KBrushes.Accent else androidx.compose.ui.graphics.SolidColor(KColors.Field))
            .clickable(enabled = enabled && !loading, onClick = onClick, role = Role.Button)
            .padding(horizontal = if (compact) 16.dp else 22.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        val fg = if (enabled) KColors.OnAccent else KColors.Disabled
        if (loading) {
            CircularProgressIndicator(Modifier.size(18.dp), color = fg, strokeWidth = 2.dp)
            Spacer(Modifier.width(8.dp))
        } else if (icon != null) {
            Icon(icon, null, tint = fg, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, color = fg, style = if (compact) MaterialTheme.typography.labelLarge else MaterialTheme.typography.titleSmall)
    }
}

/** Secondary action: card-coloured box with a thin outline. */
@Composable
fun SecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    enabled: Boolean = true,
    pill: Boolean = false,
) {
    val shape = if (pill) KShapes.Pill else KShapes.Tile
    Row(
        modifier
            .heightIn(min = KDims.MinTouch)
            .clip(shape)
            .background(KColors.Card)
            .border(1.dp, KColors.LineStrong, shape)
            .clickable(enabled = enabled, onClick = onClick, role = Role.Button)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        val fg = if (enabled) KColors.TextPrimary else KColors.Disabled
        if (icon != null) {
            Icon(icon, null, tint = if (enabled) KColors.TextSoft else KColors.Disabled, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, color = fg, style = MaterialTheme.typography.labelLarge)
    }
}

/**
 * Keyframe toggle: a diamond, filled amber when the property has a keyframe at the playhead, amber outline when it is
 * animated elsewhere, grey outline when static. 48 dp touch target.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun KeyframeButton(hasKey: Boolean, animated: Boolean, contentDescription: String, onClick: () -> Unit, modifier: Modifier = Modifier, onLongClick: (() -> Unit)? = null) {
    Box(
        modifier
            .size(KDims.MinTouch)
            .clip(CircleShape)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick, role = Role.Button)
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        val c = if (hasKey || animated) KColors.Keyframe else KColors.TextSecondary
        Canvas(Modifier.size(14.dp)) {
            val path = androidx.compose.ui.graphics.Path().apply {
                moveTo(size.width / 2, 0f); lineTo(size.width, size.height / 2); lineTo(size.width / 2, size.height); lineTo(0f, size.height / 2); close()
            }
            if (hasKey) drawPath(path, c) else drawPath(path, c, style = Stroke(1.8.dp.toPx(), join = androidx.compose.ui.graphics.StrokeJoin.Round))
        }
    }
}

/** Small uppercase label above a group of controls. */
@Composable
fun OverlineLabel(text: String, modifier: Modifier = Modifier, color: Color = KColors.TextSecondary) {
    Text(text.uppercase(), style = KType.Overline, color = color, modifier = modifier)
}

@Composable
fun ColorSwatch(color: KColor, onClick: () -> Unit, modifier: Modifier = Modifier, size: Int = 28) {
    Box(
        modifier
            .size(size.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(checker())
            .background(Color(color.r, color.g, color.b, color.a))
            .border(1.dp, KColors.LineStrong, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick),
    )
}

private fun checker() = Brush.linearGradient(listOf(KColors.CheckerA, KColors.CheckerB))

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
                                .semantics { contentDescription = KColor.fromArgb(argb.toInt()).toHex() }
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

private val PALETTE = dev.kinesis.editor.ui.theme.CONTENT_PALETTE
