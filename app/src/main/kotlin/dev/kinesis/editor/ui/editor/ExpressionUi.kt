package dev.kinesis.editor.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import dev.kinesis.core.anim.Animatable
import dev.kinesis.core.anim.anim
import dev.kinesis.core.expr.ExpressionError
import dev.kinesis.core.math.Vec2
import dev.kinesis.core.model.Layer
import dev.kinesis.core.model.TextPropertyAnimator
import dev.kinesis.core.model.TextUnit
import dev.kinesis.core.model.updateText
import dev.kinesis.editor.R
import dev.kinesis.editor.ui.common.ChoiceChips
import dev.kinesis.editor.ui.common.OverlineLabel
import dev.kinesis.editor.ui.common.PillChip
import dev.kinesis.editor.ui.common.ScrubNumber
import dev.kinesis.editor.ui.common.SecondaryButton
import dev.kinesis.editor.ui.theme.KColors
import dev.kinesis.editor.ui.theme.KDims
import dev.kinesis.editor.ui.theme.KShapes
import dev.kinesis.editor.ui.theme.KType

/** A starting point offered under the editor; choosing one replaces the code, which stays freely editable. */
class ExprPreset(val label: Int, val code: String)

val PROPERTY_PRESETS = listOf(
    ExprPreset(R.string.expr_p_wiggle, "wiggle(2, 30)"),
    ExprPreset(R.string.expr_p_loop_cycle, "loopOut(\"cycle\")"),
    ExprPreset(R.string.expr_p_loop_pingpong, "loopOut(\"pingpong\")"),
    ExprPreset(R.string.expr_p_ramp, "value + time * 20"),
    ExprPreset(R.string.expr_p_posterize, "posterizeTime(8);\nvalue"),
)

/** The expression of the After Effects "Bounce Text" tutorial, usable unchanged on a Scale animator's selector. */
const val BOUNCE_TEXT_EXPRESSION = """delay = 0.030;
myDelay = delay * textIndex;
t = (time - inPoint) - myDelay;

if (t >= 0) {
    freq = 2;
    amplitude = 50;
    decay = 8.0;

    s = amplitude * Math.cos(freq * t * 2 * Math.PI)
        / Math.exp(decay * t);

    [s, s]
} else {
    value
}"""

const val CASCADE_EXPRESSION = "t = (time - inPoint) - 0.05 * textIndex;\nlinear(t, 0, 0.4, 100, 0)"
const val JITTER_EXPRESSION = "seedRandom(textIndex, true);\nrandom(0, 100)"

val TEXT_PRESETS = listOf(
    ExprPreset(R.string.expr_p_bounce, BOUNCE_TEXT_EXPRESSION),
    ExprPreset(R.string.expr_p_cascade, CASCADE_EXPRESSION),
    ExprPreset(R.string.expr_p_jitter, JITTER_EXPRESSION),
)

private val KEYWORDS = setOf(
    "var", "let", "const", "function", "return", "if", "else", "for", "while", "do", "break", "continue", "new", "typeof",
    "in", "of", "true", "false", "null", "undefined", "this", "switch", "case", "default", "throw", "try", "catch",
)
private val BUILTINS = setOf(
    "time", "value", "inPoint", "outPoint", "index", "thisComp", "thisLayer", "thisProperty", "textIndex", "textTotal",
    "selectorValue", "textSelectorValue", "wiggle", "loopOut", "loopIn", "linear", "ease", "easeIn", "easeOut", "posterizeTime",
    "random", "seedRandom", "clamp", "length", "normalize", "valueAtTime", "key", "Math", "add", "sub", "mul", "div", "dot",
    "cross", "degreesToRadians", "radiansToDegrees",
)
private val TOKEN = Regex("""(//[^\n]*|/\*[\s\S]*?(?:\*/|$))|("(?:\\.|[^"\\\n])*"?|'(?:\\.|[^'\\\n])*'?)|(\b\d+(?:\.\d+)?\b)|(\b[A-Za-z_$][\w$]*\b)""")

/** Syntax colours for JavaScript (same text length, so it works as a [VisualTransformation]). */
fun highlightJs(code: String): AnnotatedString = AnnotatedString.Builder(code).apply {
    for (m in TOKEN.findAll(code)) {
        val style = when {
            m.groups[1] != null -> SpanStyle(color = KColors.TextSecondary, fontStyle = FontStyle.Italic)
            m.groups[2] != null -> SpanStyle(color = KColors.Keyframe)
            m.groups[3] != null -> SpanStyle(color = KColors.Pink)
            m.groups[4] != null -> when (m.value) {
                in KEYWORDS -> SpanStyle(color = KColors.Accent)
                in BUILTINS -> SpanStyle(color = KColors.Teal)
                else -> null
            }
            else -> null
        } ?: continue
        addStyle(style, m.range.first, m.range.last + 1)
    }
}.toAnnotatedString()

private object JsHighlight : VisualTransformation {
    override fun filter(text: AnnotatedString) = TransformedText(highlightJs(text.text), OffsetMapping.Identity)
}

/**
 * JavaScript editor for an expression: syntax colours, the error with its line, presets, an on/off switch that keeps
 * the code, and a way back to plain keyframes.
 */
@Composable
fun ExpressionEditor(
    source: String,
    enabled: Boolean,
    error: ExpressionError?,
    presets: List<ExprPreset>,
    onSource: (String) -> Unit,
    onEnabled: (Boolean) -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
    removeLabel: Int = R.string.expr_back,
) {
    var field by remember { mutableStateOf(TextFieldValue(source)) }
    // Follow changes that did not come from typing here (presets, undo).
    LaunchedEffect(source) {
        if (source != field.text) field = TextFieldValue(source, TextRange(source.length))
    }
    Column(
        modifier.fillMaxWidth().clip(KShapes.Tile).background(KColors.Card).border(1.dp, if (error != null && enabled) KColors.Danger else KColors.Line, KShapes.Tile).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OverlineLabel(stringResource(R.string.expr_title), Modifier.weight(1f), color = KColors.Accent)
            Text(stringResource(R.string.expr_enabled), style = MaterialTheme.typography.labelSmall, color = KColors.TextSecondary)
            Spacer(Modifier.width(8.dp))
            Switch(
                checked = enabled, onCheckedChange = onEnabled,
                colors = SwitchDefaults.colors(checkedTrackColor = KColors.Accent, checkedThumbColor = KColors.OnAccent),
            )
        }
        BasicTextField(
            value = field,
            onValueChange = { field = it; if (it.text != source) onSource(it.text) },
            textStyle = KType.Mono.copy(color = if (enabled) KColors.TextPrimary else KColors.Disabled),
            cursorBrush = SolidColor(KColors.Accent),
            visualTransformation = JsHighlight,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, keyboardType = KeyboardType.Ascii),
            modifier = Modifier.fillMaxWidth().heightIn(min = 112.dp).clip(KShapes.Field).background(KColors.Field).padding(10.dp),
            decorationBox = { inner ->
                Box {
                    if (field.text.isEmpty()) Text(stringResource(R.string.expr_placeholder), style = KType.Mono, color = KColors.TextSecondary)
                    inner()
                }
            },
        )
        if (error != null) {
            val message = if (error.line > 0) stringResource(R.string.expr_error_line, error.line, error.reason) else error.reason
            Text(message, style = MaterialTheme.typography.bodySmall, color = KColors.Danger)
        } else {
            Text(stringResource(if (enabled) R.string.expr_valid else R.string.expr_off), style = MaterialTheme.typography.bodySmall, color = KColors.TextSecondary)
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(presets) { preset -> PillChip(stringResource(preset.label), selected = false, onClick = { onSource(preset.code) }) }
        }
        SecondaryButton(stringResource(removeLabel), onRemove, Modifier.fillMaxWidth(), pill = true)
    }
}

// ---------------------------------------------------------------------------------------- text animators (expressions)

/** After Effects style text animators: properties plus an Expression Selector evaluated per character. */
@Composable
fun TextExpressionAnimatorsBlock(vm: EditorViewModel, layer: Layer) {
    val text = layer.text ?: return
    var menu by remember { mutableStateOf(false) }
    fun update(key: String? = null, f: (List<TextPropertyAnimator>) -> List<TextPropertyAnimator>) =
        vm.updateLayer(layer.id, key) { l -> l.updateText { c -> c.copy(propertyAnimators = f(c.propertyAnimators)) } }
    val name = stringResource(R.string.ta_expr_name, text.propertyAnimators.size + 1)
    SectionTitle(stringResource(R.string.ta_expr_title)) {
        Box {
            IconButton(onClick = { menu = true }, modifier = Modifier.size(KDims.MinTouch)) { Icon(Icons.Filled.Add, stringResource(R.string.ta_expr_add), tint = KColors.TextSoft) }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, containerColor = KColors.Field) {
                DropdownMenuItem(text = { Text(stringResource(R.string.expr_p_bounce)) }, onClick = {
                    menu = false
                    update { it + TextPropertyAnimator(name = name, scale = anim(Vec2(0f, 0f)), amount = Animatable(100f, expression = BOUNCE_TEXT_EXPRESSION)) }
                })
                DropdownMenuItem(text = { Text(stringResource(R.string.expr_p_cascade)) }, onClick = {
                    menu = false
                    update { it + TextPropertyAnimator(name = name, opacity = anim(0f), amount = Animatable(100f, expression = CASCADE_EXPRESSION)) }
                })
                DropdownMenuItem(text = { Text(stringResource(R.string.ta_expr_empty)) }, onClick = {
                    menu = false
                    update { it + TextPropertyAnimator(name = name, rotation = anim(20f)) }
                })
            }
        }
    }
    text.propertyAnimators.forEachIndexed { i, a ->
        fun set(f: (TextPropertyAnimator) -> TextPropertyAnimator) = update("pa:${layer.id}:$i") { list -> list.mapIndexed { j, x -> if (j == i) f(x) else x } }
        Column(
            Modifier.fillMaxWidth().padding(vertical = 6.dp).clip(KShapes.Tile).background(KColors.Card).border(1.dp, KColors.Line, KShapes.Tile).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(a.name, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Switch(
                    checked = a.enabled, onCheckedChange = { v -> set { it.copy(enabled = v) }; vm.endGesture() },
                    colors = SwitchDefaults.colors(checkedTrackColor = KColors.Accent, checkedThumbColor = KColors.OnAccent),
                )
                IconButton(onClick = { update { list -> list.filterIndexed { j, _ -> j != i } } }, modifier = Modifier.size(KDims.MinTouch)) {
                    Icon(Icons.Outlined.Delete, stringResource(R.string.delete), tint = KColors.TextSecondary)
                }
            }
            ChoiceChips(
                listOf(TextUnit.CHARACTER to stringResource(R.string.unit_character), TextUnit.WORD to stringResource(R.string.unit_word), TextUnit.LINE to stringResource(R.string.unit_line)),
                a.unit, { u -> set { it.copy(unit = u) }; vm.endGesture() },
            )
            PropertyToggleRow(stringResource(R.string.ta_prop_position), a.position != null,
                { on -> set { it.copy(position = if (on) anim(Vec2(0f, 40f)) else null) }; vm.endGesture() }) {
                val v = a.position!!.value
                AxisPair(v.x, v.y, 2f, "px", { x -> set { it.copy(position = anim(Vec2(x, v.y))) } }, { y -> set { it.copy(position = anim(Vec2(v.x, y))) } }) { vm.endGesture() }
            }
            PropertyToggleRow(stringResource(R.string.ta_prop_scale), a.scale != null,
                { on -> set { it.copy(scale = if (on) anim(Vec2(0f, 0f)) else null) }; vm.endGesture() }) {
                val v = a.scale!!.value
                AxisPair(v.x, v.y, 1f, "%", { x -> set { it.copy(scale = anim(Vec2(x, v.y))) } }, { y -> set { it.copy(scale = anim(Vec2(v.x, y))) } }) { vm.endGesture() }
            }
            PropertyToggleRow(stringResource(R.string.ta_prop_rotation), a.rotation != null,
                { on -> set { it.copy(rotation = if (on) anim(20f) else null) }; vm.endGesture() }) {
                ScrubNumber(a.rotation!!.value, { v -> set { it.copy(rotation = anim(v)) } }, { vm.endGesture() }, Modifier.weight(1f), step = 1f, suffix = "°")
            }
            PropertyToggleRow(stringResource(R.string.ta_prop_opacity), a.opacity != null,
                { on -> set { it.copy(opacity = if (on) anim(0f) else null) }; vm.endGesture() }) {
                ScrubNumber(a.opacity!!.value, { v -> set { it.copy(opacity = anim(v)) } }, { vm.endGesture() }, Modifier.weight(1f), step = 1f, suffix = "%")
            }
            OverlineLabel(stringResource(R.string.ta_amount))
            val expr = a.amount.expression
            if (expr == null) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ScrubNumber(a.amount.value, { v -> set { it.copy(amount = anim(v)) } }, { vm.endGesture() }, Modifier.weight(1f), step = 1f, suffix = "%")
                    SecondaryButton(stringResource(R.string.ta_add_expression), { set { it.copy(amount = a.amount.copy(expression = BOUNCE_TEXT_EXPRESSION)) }; vm.endGesture() }, pill = true)
                }
            } else {
                val error = remember(expr, vm.current, i) { vm.diagnoseTextExpression(layer.id, i, expr) }
                ExpressionEditor(
                    source = expr,
                    enabled = a.amount.expressionEnabled,
                    error = error,
                    presets = TEXT_PRESETS,
                    onSource = { code -> set { it.copy(amount = it.amount.copy(expression = code)) } },
                    onEnabled = { on -> set { it.copy(amount = it.amount.copy(expressionEnabled = on)) }; vm.endGesture() },
                    onRemove = { set { it.copy(amount = it.amount.copy(expression = null, expressionEnabled = true)) }; vm.endGesture() },
                    removeLabel = R.string.ta_remove_expression,
                )
            }
        }
    }
}

@Composable
private fun PropertyToggleRow(label: String, on: Boolean, onToggle: (Boolean) -> Unit, content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        PillChip(label, selected = on, onClick = { onToggle(!on) }, modifier = Modifier.width(104.dp))
        if (on) content() else Spacer(Modifier.weight(1f))
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.AxisPair(
    x: Float, y: Float, step: Float, suffix: String, onX: (Float) -> Unit, onY: (Float) -> Unit, onDone: () -> Unit,
) {
    ScrubNumber(x, onX, onDone, Modifier.weight(1f), step = step, suffix = suffix)
    ScrubNumber(y, onY, onDone, Modifier.weight(1f), step = step, suffix = suffix)
}
