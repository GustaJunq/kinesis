package dev.kinesis.editor.ui.editor

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Remove
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.graphics.Brush
import dev.kinesis.core.model.Effect
import dev.kinesis.core.model.EffectRegistry
import dev.kinesis.core.model.EffectValue
import dev.kinesis.core.model.GradientStop
import dev.kinesis.core.model.ParamType
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.kinesis.core.anim.Easing
import dev.kinesis.core.anim.EasingPreset
import dev.kinesis.core.anim.KeyframeOps
import dev.kinesis.core.anim.anim
import dev.kinesis.core.edit.AnimProp
import dev.kinesis.core.edit.ColorKind
import dev.kinesis.core.edit.Factory
import dev.kinesis.core.edit.FloatKind
import dev.kinesis.core.edit.LayerProps
import dev.kinesis.core.edit.PropUnit
import dev.kinesis.core.edit.Vec2Kind
import dev.kinesis.core.edit.Vec3Kind
import dev.kinesis.core.math.Vec2
import dev.kinesis.core.math.Vec3
import dev.kinesis.core.model.AssetType
import dev.kinesis.core.model.BlendMode
import dev.kinesis.core.model.Composition
import dev.kinesis.core.model.Gradient
import dev.kinesis.core.model.GradientType
import dev.kinesis.core.model.KnsProject
import dev.kinesis.core.model.Layer
import dev.kinesis.core.model.LayerTree
import dev.kinesis.core.model.LayerType
import dev.kinesis.core.model.LightKind
import dev.kinesis.core.model.Mask
import dev.kinesis.core.model.MaskMode
import dev.kinesis.core.model.MaskShape
import dev.kinesis.core.model.Material
import dev.kinesis.core.model.Matte
import dev.kinesis.core.model.MatteMode
import dev.kinesis.core.model.Primitive
import dev.kinesis.core.model.ShapeKind
import dev.kinesis.core.model.StrokeCap
import dev.kinesis.core.model.StrokeJoin
import dev.kinesis.core.model.TextAlign
import dev.kinesis.core.model.TextAnimator
import dev.kinesis.core.model.TextAnimatorMode
import dev.kinesis.core.model.TextAnimatorType
import dev.kinesis.core.model.TextOrder
import dev.kinesis.core.model.TextUnit
import dev.kinesis.core.model.Transition
import dev.kinesis.core.model.TransitionType
import dev.kinesis.core.model.updateCamera
import dev.kinesis.core.model.CameraAim
import dev.kinesis.core.model.updateLight
import dev.kinesis.core.model.updateMedia
import dev.kinesis.core.model.updateModel3D
import dev.kinesis.core.model.updateObject3D
import dev.kinesis.core.model.updateShape
import dev.kinesis.core.model.updateText
import dev.kinesis.editor.R
import dev.kinesis.editor.ui.common.ChoiceChips
import dev.kinesis.editor.ui.common.ColorPickerDialog
import dev.kinesis.editor.ui.common.ColorSwatch
import dev.kinesis.editor.ui.common.KeyframeButton
import dev.kinesis.editor.ui.common.LabeledSlider
import dev.kinesis.editor.ui.common.OverlineLabel
import dev.kinesis.editor.ui.common.PillChip
import dev.kinesis.editor.ui.theme.KDims
import androidx.compose.material.icons.outlined.Code
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.saveable.rememberSaveable
import dev.kinesis.editor.ui.common.ScrubNumber
import dev.kinesis.editor.ui.common.TextInputDialog
import dev.kinesis.editor.ui.theme.KColors
import dev.kinesis.core.math.Color as KColor

/** Curve-editor target: a keyframe segment of one property. */
data class CurveTarget(val layerId: String, val propId: String, val keyIndex: Int)

private val SYSTEM_FONTS = listOf(
    "sans-serif", "sans-serif-medium", "sans-serif-light", "sans-serif-condensed", "sans-serif-black",
    "serif", "monospace", "serif-monospace", "casual", "cursive", "sans-serif-smallcaps",
)

enum class PropTab(val label: Int) {
    TRANSFORM(R.string.ptab_transform), TEXT(R.string.ptab_text), EFFECTS(R.string.effects), MASK(R.string.ptab_mask), TRANSITION(R.string.ptab_transition),
}

/** Tabs offered for [layer] (the text tab only for text layers, masks only for 2D layers, ...). */
fun propTabs(layer: Layer): List<PropTab> = buildList {
    add(PropTab.TRANSFORM)
    if (layer.type == LayerType.TEXT) add(PropTab.TEXT)
    if (layer.type.isVisual) add(PropTab.EFFECTS)
    if (layer.type.is2DVisual) add(PropTab.MASK)
    if (layer.type.isVisual) add(PropTab.TRANSITION)
}

/** The tab that contains the panel item [key] (see [propertyItemKeys]). */
private fun tabOfItem(key: String?): PropTab? = when (key) {
    "effects" -> PropTab.EFFECTS
    "masks", "matte" -> PropTab.MASK
    "transitions" -> PropTab.TRANSITION
    "animators", "content" -> PropTab.TEXT
    else -> null
}

private fun isTextSection(id: String) = id == "text"

@Composable
fun PropertiesPanel(
    vm: EditorViewModel,
    project: KnsProject,
    time: Double,
    selection: String?,
    onEditCurve: (CurveTarget) -> Unit,
    onPickTexture: (String) -> Unit,
    onPickFont: () -> Unit,
    modifier: Modifier = Modifier,
    onAddEffect: () -> Unit = {},
    /** Item key to open when the panel shows (e.g. "masks", "transitions"); see [propertyItemKeys]. */
    focusSection: String? = null,
) {
    val layer = selection?.let { LayerTree.find(project, it) }
    if (layer == null) {
        LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(KDims.Gutter, 8.dp, KDims.Gutter, 64.dp)) {
            item { CompositionSettings(vm, project) }
        }
        return
    }
    val tabs = propTabs(layer)
    var tab by rememberSaveable(layer.id, focusSection) { mutableStateOf(tabOfItem(focusSection)?.takeIf { it in tabs } ?: PropTab.TRANSFORM) }
    if (tab !in tabs) tab = PropTab.TRANSFORM
    val listState = rememberLazyListState()
    val local = time - LayerTree.containerOffset(project, layer.id) - layer.start

    Column(modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = KDims.Gutter, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            for (entry in tabs) PillChip(stringResource(entry.label), entry == tab, { tab = entry })
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState, contentPadding = PaddingValues(KDims.Gutter, 4.dp, KDims.Gutter, 64.dp)) {
            when (tab) {
                PropTab.TRANSFORM -> {
                    item(key = "header") { LayerHeader(vm, layer) }
                    if (layer.type != LayerType.TEXT) item(key = "content") { ContentControls(vm, project, layer, onPickTexture, onPickFont) }
                    for (section in LayerProps.sections(layer)) {
                        if (section.id.startsWith("effects.") || section.id.startsWith("masks.") || isTextSection(section.id)) continue
                        item(key = "section:${section.id}") {
                            SectionTitle(stringResource(sectionTitleRes(section.id)))
                            section.props.forEach { PropRow(vm, layer, it, local, onEditCurve) }
                        }
                    }
                    item(key = "layer") { LayerSettings(vm, project, layer) }
                }
                PropTab.TEXT -> {
                    item(key = "content") { ContentControls(vm, project, layer, onPickTexture, onPickFont) }
                    for (section in LayerProps.sections(layer)) {
                        if (!isTextSection(section.id)) continue
                        item(key = "section:${section.id}") {
                            SectionTitle(stringResource(sectionTitleRes(section.id)))
                            section.props.forEach { PropRow(vm, layer, it, local, onEditCurve) }
                        }
                    }
                    item(key = "animators") { TextAnimatorsBlock(vm, layer) }
                    item(key = "expressionAnimators") { TextExpressionAnimatorsBlock(vm, layer) }
                }
                PropTab.EFFECTS -> item(key = "effects") { EffectsBlock(vm, layer, local, onEditCurve, onAddEffect) }
                PropTab.MASK -> {
                    item(key = "masks") { MasksBlock(vm, layer, local, onEditCurve) }
                    item(key = "matte") { MatteBlock(vm, project, layer) }
                }
                PropTab.TRANSITION -> item(key = "transitions") { TransitionsBlock(vm, layer) }
            }
        }
    }
}

/** Keys of the panel items for [layer] (used to choose the tab a shortcut opens). */
fun propertyItemKeys(layer: Layer): List<String> = buildList {
    add("header")
    add("content")
    for (section in LayerProps.sections(layer)) {
        if (section.id.startsWith("effects.") || section.id.startsWith("masks.")) continue
        add("section:${section.id}")
    }
    if (layer.type.isVisual) add("effects")
    if (layer.type.is2DVisual) { add("masks"); add("matte") }
    if (layer.type == LayerType.TEXT) add("animators")
    if (layer.type.isVisual) add("transitions")
    add("layer")
}

@Composable
fun SectionTitle(text: String, trailing: @Composable () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        OverlineLabel(text.uppercase(), Modifier.weight(1f), color = KColors.Accent)
        trailing()
    }
}

@Composable
private fun SmallLabel(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = KColors.TextSecondary, modifier = modifier, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().height(40.dp), verticalAlignment = Alignment.CenterVertically) {
        SmallLabel(label, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange, colors = SwitchDefaults.colors(checkedTrackColor = KColors.Accent))
    }
}

// ---------------------------------------------------------------------------------------- animatable rows

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PropRow(vm: EditorViewModel, layer: Layer, prop: AnimProp<*>, local: Double, onEditCurve: (CurveTarget) -> Unit) {
    val anim = prop.get(layer)
    val animated = anim.isAnimated
    val hasKey = prop.hasKeyframeAt(layer, local)
    val segment = KeyframeOps.segmentStartIndex(anim, local)
    val key = "prop:${layer.id}:${prop.id}"
    var showExpression by rememberSaveable(layer.id, prop.id) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SmallLabel(propLabel(prop.labelKey), Modifier.weight(1f))
            if (animated) {
                IconButton(
                    onClick = { onEditCurve(CurveTarget(layer.id, prop.id, segment)) },
                    enabled = segment >= 0 && segment < anim.keyframes.size - 1,
                    modifier = Modifier.size(KDims.MinTouch),
                ) { Icon(Icons.Filled.ShowChart, stringResource(R.string.easing), tint = KColors.TextSoft, modifier = Modifier.size(20.dp)) }
            }
            // Expression: opens the editor (creating `value` as the first expression); accent when it is active.
            val expressionOn = anim.hasActiveExpression
            IconButton(
                onClick = {
                    if (anim.expression == null) vm.setExpression(layer.id, prop, "value") else showExpression = !showExpression
                    if (anim.expression == null) showExpression = true
                },
                modifier = Modifier.size(KDims.MinTouch),
            ) {
                Icon(
                    Icons.Outlined.Code, stringResource(R.string.expr_open),
                    tint = when {
                        expressionOn -> KColors.Accent
                        anim.expression != null -> KColors.Disabled
                        else -> KColors.TextSecondary
                    },
                    modifier = Modifier.size(20.dp),
                )
            }
            // Keyframe toggle: filled amber diamond = keyframe here, outline = none (amber outline when animated).
            KeyframeButton(
                hasKey = hasKey,
                animated = animated,
                contentDescription = stringResource(if (hasKey) R.string.remove_keyframe else R.string.add_keyframe),
                onClick = { vm.toggleKeyframe(layer.id, prop) },
                onLongClick = if (animated) ({ vm.clearKeyframes(layer.id, prop) }) else null,
            )
        }
        val expressionSource = anim.expression
        if (expressionSource != null && showExpression) {
            val error = remember(expressionSource, vm.current) { vm.diagnoseExpression(layer.id, prop, expressionSource) }
            ExpressionEditor(
                source = expressionSource,
                enabled = anim.expressionEnabled,
                error = error,
                presets = PROPERTY_PRESETS,
                onSource = { code -> vm.setExpression(layer.id, prop, code, anim.expressionEnabled, "expr:${layer.id}:${prop.id}") },
                onEnabled = { on -> vm.setExpression(layer.id, prop, expressionSource, on) },
                onRemove = { vm.setExpression(layer.id, prop, null); showExpression = false },
                modifier = Modifier.padding(vertical = 4.dp),
            )
        }
        when (val kind = prop.kind) {
            is FloatKind -> {
                val p = LayerProps.typed<Float>(prop)
                LabeledSliderNoLabel(
                    value = p.valueAt(layer, local),
                    range = kind.sliderMin..kind.sliderMax,
                    display = kind.display,
                    suffix = unitSuffix(kind.unit),
                    min = kind.min, max = kind.max,
                    onChange = { vm.setProperty(layer.id, p, if (kind.integer) Math.round(it).toFloat() else it, key) },
                    onDone = { vm.endGesture() },
                )
            }
            is Vec2Kind -> {
                val p = LayerProps.typed<Vec2>(prop)
                val v = p.valueAt(layer, local)
                val step = if (kind.unit == PropUnit.PERCENT) 0.01f else 2f
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AxisField("X", v.x, step, kind.display, unitSuffix(kind.unit), Modifier.weight(1f), { vm.setProperty(layer.id, p, Vec2(it, p.valueAt(layer, local).y), key) }) { vm.endGesture() }
                    AxisField("Y", v.y, step, kind.display, unitSuffix(kind.unit), Modifier.weight(1f), { vm.setProperty(layer.id, p, Vec2(p.valueAt(layer, local).x, it), key) }) { vm.endGesture() }
                    if (kind.unit == PropUnit.PERCENT) {
                        // Uniform scale shortcut
                        AxisField("XY", v.x, step, kind.display, unitSuffix(kind.unit), Modifier.weight(1f), {
                            val cur = p.valueAt(layer, local)
                            val ratio = if (cur.x != 0f) cur.y / cur.x else 1f
                            vm.setProperty(layer.id, p, Vec2(it, it * ratio), key)
                        }) { vm.endGesture() }
                    }
                }
            }
            is Vec3Kind -> {
                val p = LayerProps.typed<Vec3>(prop)
                val v = p.valueAt(layer, local)
                val step = when (kind.unit) { PropUnit.PERCENT -> 0.01f; PropUnit.DEG -> 1f; else -> 2f }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    AxisField("X", v.x, step, kind.display, unitSuffix(kind.unit), Modifier.weight(1f), { val c = p.valueAt(layer, local); vm.setProperty(layer.id, p, Vec3(it, c.y, c.z), key) }, tint = KColors.AxisX) { vm.endGesture() }
                    AxisField("Y", v.y, step, kind.display, unitSuffix(kind.unit), Modifier.weight(1f), { val c = p.valueAt(layer, local); vm.setProperty(layer.id, p, Vec3(c.x, it, c.z), key) }, tint = KColors.AxisY) { vm.endGesture() }
                    AxisField("Z", v.z, step, kind.display, unitSuffix(kind.unit), Modifier.weight(1f), { val c = p.valueAt(layer, local); vm.setProperty(layer.id, p, Vec3(c.x, c.y, it), key) }, tint = KColors.AxisZ) { vm.endGesture() }
                }
            }
            ColorKind -> {
                val p = LayerProps.typed<KColor>(prop)
                val c = p.valueAt(layer, local)
                var picking by remember { mutableStateOf(false) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ColorSwatch(c, { picking = true })
                    Spacer(Modifier.width(8.dp))
                    Text(c.toHex(), fontSize = 12.sp, color = KColors.TextSecondary)
                }
                if (picking) {
                    ColorPickerDialog(
                        c,
                        onDismiss = { picking = false; vm.endGesture() },
                        onChange = { vm.setProperty(layer.id, p, it, key) },
                        onConfirm = { vm.setProperty(layer.id, p, it, key); vm.endGesture(); picking = false },
                    )
                }
            }
        }
    }
}

@Composable
private fun AxisField(
    axis: String, value: Float, step: Float, display: Float, suffix: String, modifier: Modifier,
    onChange: (Float) -> Unit, tint: Color = KColors.TextSecondary, onDone: () -> Unit,
) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(axis, fontSize = 11.sp, color = tint, modifier = Modifier.padding(end = 4.dp))
        ScrubNumber(value, onChange, onDone, Modifier.weight(1f), step = step, display = display, suffix = suffix)
    }
}

@Composable
private fun LabeledSliderNoLabel(
    value: Float, range: ClosedFloatingPointRange<Float>, display: Float, suffix: String, min: Float, max: Float,
    onChange: (Float) -> Unit, onDone: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        androidx.compose.material3.Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = { onChange(it.coerceIn(min, max)) },
            onValueChangeFinished = onDone,
            valueRange = range,
            modifier = Modifier.weight(1f).height(32.dp),
            colors = androidx.compose.material3.SliderDefaults.colors(thumbColor = KColors.Accent, activeTrackColor = KColors.Accent),
        )
        Spacer(Modifier.width(6.dp))
        ScrubNumber(value, onChange, onDone, Modifier.width(72.dp), step = (range.endInclusive - range.start) / 100f, display = display, min = min, max = max, suffix = suffix)
    }
}

private fun unitSuffix(u: PropUnit) = when (u) {
    PropUnit.DEG -> "°"
    PropUnit.PERCENT -> "%"
    PropUnit.PX -> ""
    PropUnit.EM -> "em"
    PropUnit.MULTIPLIER -> "×"
    PropUnit.NONE -> ""
    PropUnit.SECONDS -> " s"
    PropUnit.HERTZ -> " Hz"
    PropUnit.FPS -> " fps"
    PropUnit.DEG_PER_SECOND -> "°/s"
    PropUnit.EV -> " EV"
}

// ---------------------------------------------------------------------------------------- header & content

@Composable
private fun LayerHeader(vm: EditorViewModel, layer: Layer) {
    var renaming by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Box(Modifier.size(10.dp).clip(RoundedCornerShape(3.dp)).background(layerColor(layer.type)))
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f).clickable { renaming = true }) {
            Text(layerDisplayName(layer), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(stringResource(layerTypeRes(layer.type)), style = MaterialTheme.typography.bodySmall, color = KColors.TextSecondary)
        }
        TextButton(onClick = { vm.updateLayer(layer.id) { it.copy(locked = !it.locked) } }) {
            Text(stringResource(if (layer.locked) R.string.unlock else R.string.lock), fontSize = 12.sp)
        }
    }
    if (renaming) {
        TextInputDialog(stringResource(R.string.rename), layer.name, onDismiss = { renaming = false }) { name ->
            vm.updateLayer(layer.id) { it.copy(name = name) }
            renaming = false
        }
    }
}

@Composable
private fun ContentControls(vm: EditorViewModel, project: KnsProject, layer: Layer, onPickTexture: (String) -> Unit, onPickFont: () -> Unit) {
    val id = layer.id
    Column(Modifier.fillMaxWidth()) {
        when (layer.type) {
            LayerType.TEXT -> {
                val t = layer.text ?: return@Column
                var editing by remember { mutableStateOf(false) }
                SectionTitle(stringResource(R.string.section_text))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(KColors.SurfaceHigh)
                        .clickable { editing = true }
                        .padding(10.dp),
                ) { Text(t.content, maxLines = 4, overflow = TextOverflow.Ellipsis) }
                if (editing) {
                    TextInputDialog(stringResource(R.string.edit_text), t.content, onDismiss = { editing = false }, multiline = true) { s ->
                        vm.updateLayer(id) { it.updateText { c -> c.copy(content = s) } }
                        editing = false
                    }
                }
                Spacer(Modifier.height(8.dp))
                SmallLabel(stringResource(R.string.font))
                val fontAssets = project.assets.filter { it.type == AssetType.FONT }
                val fontOptions = SYSTEM_FONTS.map { ("family:$it") to it } + fontAssets.map { ("asset:${it.id}") to (it.name ?: it.id) }
                val selectedFont = t.font.asset?.let { "asset:$it" } ?: "family:${t.font.family}"
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ChoiceChips(fontOptions, selectedFont, { opt ->
                        vm.updateLayer(id) { l ->
                            l.updateText { c ->
                                val f = c.font
                                c.copy(font = if (opt.startsWith("asset:")) f.copy(asset = opt.removePrefix("asset:")) else f.copy(family = opt.removePrefix("family:"), asset = null))
                            }
                        }
                    }, Modifier.weight(1f))
                    IconButton(onClick = onPickFont) { Icon(Icons.Filled.Add, stringResource(R.string.import_font)) }
                }
                LabeledSlider(stringResource(R.string.font_weight), t.font.weight.toFloat(), 100f..900f, { w ->
                    val snapped = ((w / 100f).toInt().coerceIn(1, 9)) * 100
                    vm.updateLayer(id, "weight:$id") { it.updateText { c -> c.copy(font = c.font.copy(weight = snapped)) } }
                }, { vm.endGesture() })
                SwitchRow(stringResource(R.string.italic), t.font.italic) { v -> vm.updateLayer(id) { it.updateText { c -> c.copy(font = c.font.copy(italic = v)) } } }
                SmallLabel(stringResource(R.string.alignment))
                ChoiceChips(
                    listOf(TextAlign.LEFT to stringResource(R.string.align_left), TextAlign.CENTER to stringResource(R.string.align_center), TextAlign.RIGHT to stringResource(R.string.align_right)),
                    t.align, { a -> vm.updateLayer(id) { it.updateText { c -> c.copy(align = a) } } },
                )
                LabeledSlider(stringResource(R.string.line_height), t.lineHeight, 0.6f..3f, { v -> vm.updateLayer(id, "lh:$id") { it.updateText { c -> c.copy(lineHeight = v) } } }, { vm.endGesture() }, min = 0.3f)
                LabeledSlider(stringResource(R.string.wrap_width), t.maxWidth, 0f..project.composition.width.toFloat(), { v -> vm.updateLayer(id, "mw:$id") { it.updateText { c -> c.copy(maxWidth = v) } } }, { vm.endGesture() }, min = 0f)
                SwitchRow(stringResource(R.string.outline), t.stroke != null) { on ->
                    vm.updateLayer(id) { l -> if (on) LayerProps.withTextStroke(l) else l.updateText { c -> c.copy(stroke = null) } }
                }
            }
            LayerType.SHAPE -> {
                val s = layer.shape ?: return@Column
                SectionTitle(stringResource(R.string.section_shape))
                val kinds = ShapeKind.entries.filter { it != ShapeKind.PATH || s.kind == ShapeKind.PATH }
                ChoiceChips(kinds.map { it to stringResource(shapeKindRes(it)) }, s.kind, { k -> vm.updateLayer(id) { it.updateShape { c -> c.copy(kind = k) } } })
                if (s.kind == ShapeKind.PATH) PathPointsEditor(vm, id, s.path ?: dev.kinesis.core.model.PathSpec())
                SwitchRow(stringResource(R.string.fill), s.fill.enabled) { v -> vm.updateLayer(id) { it.updateShape { c -> c.copy(fill = c.fill.copy(enabled = v)) } } }
                if (s.fill.enabled) {
                    SwitchRow(stringResource(R.string.gradient), s.fill.gradient != null) { on ->
                        vm.updateLayer(id) { l ->
                            l.updateShape { c ->
                                val size = c.size.value
                                val g = if (on) Gradient(
                                    start = Vec2(-size.x / 2, 0f), end = Vec2(size.x / 2, 0f),
                                    stops = listOf(GradientStop(0f, c.fill.color.value), GradientStop(1f, KColor.parse("#4FD1C5"))),
                                ) else null
                                c.copy(fill = c.fill.copy(gradient = g))
                            }
                        }
                    }
                    s.fill.gradient?.let { g -> GradientEditor(vm, id, g) }
                }
                SwitchRow(stringResource(R.string.stroke), s.stroke.enabled) { v -> vm.updateLayer(id) { it.updateShape { c -> c.copy(stroke = c.stroke.copy(enabled = v)) } } }
                if (s.stroke.enabled) {
                    ChoiceChips(
                        listOf(StrokeCap.BUTT to stringResource(R.string.cap_butt), StrokeCap.ROUND to stringResource(R.string.cap_round), StrokeCap.SQUARE to stringResource(R.string.cap_square)),
                        s.stroke.cap, { cap -> vm.updateLayer(id) { it.updateShape { c -> c.copy(stroke = c.stroke.copy(cap = cap)) } } },
                    )
                    Spacer(Modifier.height(4.dp))
                    ChoiceChips(
                        listOf(StrokeJoin.MITER to stringResource(R.string.join_miter), StrokeJoin.ROUND to stringResource(R.string.join_round), StrokeJoin.BEVEL to stringResource(R.string.join_bevel)),
                        s.stroke.join, { j -> vm.updateLayer(id) { it.updateShape { c -> c.copy(stroke = c.stroke.copy(join = j)) } } },
                    )
                }
            }
            LayerType.IMAGE -> {
                val asset = project.asset(layer.image?.asset)
                SectionTitle(stringResource(R.string.layer_image))
                SmallLabel("${asset?.name ?: asset?.id ?: "?"} · ${asset?.width ?: "?"}×${asset?.height ?: "?"}")
                OutlinedButton(onClick = {
                    asset?.let { a -> vm.updateLayer(id) { it.copy(transform = it.transform.copy(scale = anim(Factory.fitScale(project, a)))) } }
                }) { Text(stringResource(R.string.fit_to_frame)) }
            }
            LayerType.VIDEO, LayerType.AUDIO -> MediaControls(vm, project, layer)
            LayerType.OBJECT3D -> {
                val o = layer.object3d ?: return@Column
                SectionTitle(stringResource(R.string.section_object))
                ChoiceChips(Primitive.entries.map { it to stringResource(primitiveRes(it)) }, o.primitive, { p -> vm.updateLayer(id) { it.updateObject3D { c -> c.copy(primitive = p) } } })
                if (o.primitive != Primitive.EMPTY) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SmallLabel(stringResource(R.string.texture) + ": " + (o.material.texture?.let { t -> project.asset(t)?.name ?: t } ?: "—"), Modifier.weight(1f))
                        TextButton(onClick = { onPickTexture(id) }) { Text(stringResource(R.string.choose)) }
                        if (o.material.texture != null) {
                            TextButton(onClick = { vm.updateLayer(id) { it.updateObject3D { c -> c.copy(material = c.material.copy(texture = null)) } } }) {
                                Text(stringResource(R.string.remove))
                            }
                        }
                    }
                }
            }
            LayerType.MODEL3D -> {
                val m = layer.model3d ?: return@Column
                SectionTitle(stringResource(R.string.section_object))
                SmallLabel(project.asset(m.asset)?.name ?: m.asset)
                SwitchRow(stringResource(R.string.override_material), m.material != null) { on ->
                    vm.updateLayer(id) { it.updateModel3D { c -> c.copy(material = if (on) Material() else null) } }
                }
            }
            LayerType.NULL -> {
                SectionTitle(stringResource(R.string.layer_null))
                SmallLabel(stringResource(if (layer.is3DLayer) R.string.null_hint_3d else R.string.null_hint_2d))
            }
            LayerType.CAMERA -> {
                val c = layer.camera ?: return@Column
                SectionTitle(stringResource(R.string.layer_camera))
                ChoiceChips(
                    listOf(CameraAim.ROTATION to stringResource(R.string.camera_aim_rotation), CameraAim.TARGET to stringResource(R.string.camera_aim_target)),
                    c.aim,
                    { a -> vm.updateLayer(id) { LayerProps.withCameraAim(it, a, vm.localTime(it), project.composition.height) } },
                )
                if (c.position == null) {
                    SmallLabel(stringResource(R.string.camera_auto_hint))
                    OutlinedButton(onClick = { vm.updateLayer(id) { LayerProps.withExplicitCameraPosition(it, project.composition.height) } }) {
                        Text(stringResource(R.string.camera_free))
                    }
                } else {
                    SmallLabel(stringResource(if (c.aim == CameraAim.ROTATION) R.string.camera_rotation_hint else R.string.camera_orbit_hint))
                    TextButton(onClick = { vm.updateLayer(id) { it.updateCamera { c -> c.copy(position = null) } } }) { Text(stringResource(R.string.camera_reset)) }
                }
            }
            LayerType.LIGHT -> {
                val l = layer.light ?: return@Column
                SectionTitle(stringResource(R.string.layer_light))
                ChoiceChips(LightKind.entries.map { it to stringResource(lightKindRes(it)) }, l.kind, { k -> vm.updateLayer(id) { it.updateLight { c -> c.copy(kind = k) } } })
            }
            LayerType.GROUP -> {
                SectionTitle(stringResource(R.string.layer_group))
                SmallLabel(stringResource(R.string.group_children, layer.layers.size))
                OutlinedButton(onClick = { vm.ungroup(id) }) { Text(stringResource(R.string.ungroup)) }
            }
        }
    }
}

@Composable
private fun PathPointsEditor(vm: EditorViewModel, id: String, path: dev.kinesis.core.model.PathSpec) {
    fun upd(key: String? = null, f: (dev.kinesis.core.model.PathSpec) -> dev.kinesis.core.model.PathSpec) =
        vm.updateLayer(id, key) { l -> l.updateShape { sh -> sh.copy(path = f(sh.path ?: dev.kinesis.core.model.PathSpec())) } }
    SmallLabel(stringResource(R.string.path_hint))
    SwitchRow(stringResource(R.string.closed_path), path.closed) { v -> upd { it.copy(closed = v) } }
    path.points.forEachIndexed { i, p ->
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("${i + 1}", fontSize = 11.sp, color = KColors.TextSecondary, modifier = Modifier.width(18.dp))
            AxisField("X", p.point.x, 2f, 1f, "", Modifier.weight(1f), { v -> upd("pt:$id:$i") { pth -> dev.kinesis.core.edit.PathOps.movePoint(pth, i, Vec2(v, pth.points[i].point.y)) } }) { vm.endGesture() }
            AxisField("Y", p.point.y, 2f, 1f, "", Modifier.weight(1f), { v -> upd("pt:$id:$i") { pth -> dev.kinesis.core.edit.PathOps.movePoint(pth, i, Vec2(pth.points[i].point.x, v)) } }) { vm.endGesture() }
            val smooth = dev.kinesis.core.edit.PathOps.isSmooth(p)
            TextButton(onClick = { upd { pth -> if (smooth) dev.kinesis.core.edit.PathOps.corner(pth, i) else dev.kinesis.core.edit.PathOps.smooth(pth, i) } }) {
                Text(stringResource(if (smooth) R.string.point_smooth else R.string.point_corner), fontSize = 11.sp)
            }
            IconButton(onClick = { upd { pth -> dev.kinesis.core.edit.PathOps.removePoint(pth, i) } }, enabled = path.points.size > 2, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Filled.Delete, stringResource(R.string.delete), tint = KColors.TextSecondary, modifier = Modifier.size(18.dp))
            }
        }
    }
    OutlinedButton(onClick = { upd { pth -> dev.kinesis.core.edit.PathOps.insertAfter(pth, pth.points.lastIndex) } }) { Text(stringResource(R.string.add_point)) }
}

@Composable
private fun GradientEditor(vm: EditorViewModel, id: String, g: Gradient) {
    var picking by remember { mutableStateOf(-1) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ChoiceChips(
            listOf(GradientType.LINEAR to stringResource(R.string.gradient_linear), GradientType.RADIAL to stringResource(R.string.gradient_radial)),
            g.type, { t ->
                vm.updateLayer(id) { l ->
                    l.updateShape { c ->
                        val size = c.size.value
                        val ng = if (t == GradientType.RADIAL) g.copy(type = t, start = Vec2.ZERO, end = Vec2(size.x / 2, 0f)) else g.copy(type = t, start = Vec2(-size.x / 2, 0f), end = Vec2(size.x / 2, 0f))
                        c.copy(fill = c.fill.copy(gradient = ng))
                    }
                }
            },
            Modifier.weight(1f),
        )
        g.stops.forEachIndexed { i, stop -> ColorSwatch(stop.color, { picking = i }) }
    }
    if (picking >= 0) {
        val i = picking
        ColorPickerDialog(g.stops[i].color, onDismiss = { picking = -1 }, onChange = {}, onConfirm = { c ->
            vm.updateLayer(id) { l ->
                l.updateShape { sc ->
                    val gr = sc.fill.gradient
                    if (gr == null) sc else sc.copy(fill = sc.fill.copy(gradient = gr.copy(stops = gr.stops.mapIndexed { j, st -> if (j == i) st.copy(color = c) else st })))
                }
            }
            picking = -1
        })
    }
}

@Composable
private fun MediaControls(vm: EditorViewModel, project: KnsProject, layer: Layer) {
    val m = layer.media ?: return
    val id = layer.id
    val isVideo = layer.type == LayerType.VIDEO
    fun upd(l: Layer, f: (dev.kinesis.core.model.MediaContent) -> dev.kinesis.core.model.MediaContent) = l.updateMedia(f)
    SectionTitle(stringResource(if (isVideo) R.string.layer_video else R.string.layer_audio))
    SmallLabel(project.asset(m.asset)?.let { a -> "${a.name ?: a.id} · ${a.duration?.let { d -> "%.1f s".format(d) } ?: ""}" } ?: m.asset)
    LabeledSlider(stringResource(R.string.speed), m.speed.toFloat(), 0.25f..4f, { v -> vm.updateLayer(id, "speed:$id") { upd(it) { c -> c.copy(speed = v.toDouble()) } } }, { vm.endGesture() }, suffix = "×", min = 0.05f)
    Row(verticalAlignment = Alignment.CenterVertically) {
        SmallLabel(stringResource(R.string.source_start), Modifier.weight(1f))
        ScrubNumber(m.sourceStart.toFloat(), { v -> vm.updateLayer(id, "src:$id") { upd(it) { c -> c.copy(sourceStart = v.toDouble()) } } }, { vm.endGesture() }, Modifier.width(96.dp), step = 0.1f, min = 0f, suffix = " s")
    }
    SwitchRow(stringResource(R.string.mute), m.muted) { v -> vm.updateLayer(id) { upd(it) { c -> c.copy(muted = v) } } }
    LabeledSlider(stringResource(R.string.fade_in), m.fadeIn.toFloat(), 0f..5f, { v -> vm.updateLayer(id, "fi:$id") { upd(it) { c -> c.copy(fadeIn = v.toDouble()) } } }, { vm.endGesture() }, suffix = " s", min = 0f)
    LabeledSlider(stringResource(R.string.fade_out), m.fadeOut.toFloat(), 0f..5f, { v -> vm.updateLayer(id, "fo:$id") { upd(it) { c -> c.copy(fadeOut = v.toDouble()) } } }, { vm.endGesture() }, suffix = " s", min = 0f)
}

// ---------------------------------------------------------------------------------------- effects & masks

@Composable
private fun EffectsBlock(vm: EditorViewModel, layer: Layer, local: Double, onEditCurve: (CurveTarget) -> Unit, onAddEffect: () -> Unit) {
    val pt = isPortuguese()
    SectionTitle(stringResource(R.string.section_effects)) {
        IconButton(onClick = onAddEffect, modifier = Modifier.size(32.dp)) { Icon(Icons.Filled.Add, stringResource(R.string.add_effect)) }
    }
    if (layer.effects.isEmpty()) {
        OutlinedButton(onClick = onAddEffect, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.add_effect)) }
    }
    val section = { i: Int -> LayerProps.sections(layer).firstOrNull { it.id == "effects.$i" } }
    layer.effects.forEachIndexed { i, e ->
        val spec = EffectRegistry.get(e.type) ?: return@forEachIndexed
        fun upd(f: (Effect) -> Effect) = vm.updateLayer(layer.id) { l -> l.copy(effects = l.effects.mapIndexed { j, x -> if (j == i) f(x) else x }) }
        fun move(to: Int) = vm.updateLayer(layer.id) { l ->
            val list = l.effects.toMutableList()
            list.add(to, list.removeAt(i))
            l.copy(effects = list)
        }
        Card {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(spec.label(pt), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(spec.category.label(pt), fontSize = 11.sp, color = KColors.TextSecondary, maxLines = 1)
                }
                IconButton(onClick = { move(i - 1) }, enabled = i > 0, modifier = Modifier.size(32.dp)) { Icon(Icons.Filled.KeyboardArrowUp, stringResource(R.string.move_up)) }
                IconButton(onClick = { move(i + 1) }, enabled = i < layer.effects.lastIndex, modifier = Modifier.size(32.dp)) { Icon(Icons.Filled.KeyboardArrowDown, stringResource(R.string.move_down)) }
                Switch(checked = e.enabled, onCheckedChange = { on -> upd { it.withEnabled(on) } })
                IconButton(onClick = { vm.updateLayer(layer.id) { l -> l.copy(effects = l.effects.filterIndexed { j, _ -> j != i }) } }) {
                    Icon(Icons.Filled.Delete, stringResource(R.string.delete), tint = KColors.TextSecondary)
                }
            }
            effectUnavailableReason(spec, layer)?.let { Text(stringResource(it), fontSize = 12.sp, color = KColors.Keyframe) }
            val props = section(i)?.props.orEmpty()
            for (param in spec.params) {
                if (!param.isVisible { name -> e.choice(name) }) continue
                when (param.type) {
                    ParamType.CHOICE -> {
                        SmallLabel(param.label(pt))
                        ChoiceChips(param.choices.map { it.id to it.label(pt) }, e.choice(param.name), { v -> upd { it.with(param.name, EffectValue.Choice(v)) } })
                        Spacer(Modifier.height(4.dp))
                    }
                    ParamType.BOOLEAN -> SwitchRow(param.label(pt), e.flag(param.name)) { v -> upd { it.with(param.name, EffectValue.Flag(v)) } }
                    ParamType.STOPS -> StopsEditor(vm, param.label(pt), e.stops(param.name)) { stops -> upd { it.with(param.name, EffectValue.Stops(stops)) } }
                    else -> props.firstOrNull { it.id == "effects.$i.${param.name}" }?.let { PropRow(vm, layer, it, local, onEditCurve) }
                }
            }
        }
    }
}

/** Gradient stops: a preview bar, one swatch per stop, add (up to 8) and remove (down to 2). */
@Composable
private fun StopsEditor(vm: EditorViewModel, label: String, stops: List<GradientStop>, onChange: (List<GradientStop>) -> Unit) {
    var editing by remember { mutableStateOf<Int?>(null) }
    SmallLabel(label)
    Box(
        Modifier.fillMaxWidth().height(16.dp).clip(RoundedCornerShape(4.dp)).background(
            Brush.horizontalGradient(colorStops = stops.map { it.offset to Color(it.color.r, it.color.g, it.color.b, it.color.a) }.toTypedArray()),
        ),
    )
    Row(Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        stops.forEachIndexed { k, st -> ColorSwatch(st.color, { editing = k }) }
        Spacer(Modifier.weight(1f))
        IconButton(onClick = { onChange(stops.dropLast(1).mapIndexed { k, st -> st.copy(offset = k / (stops.size - 2f)) }) }, enabled = stops.size > 2, modifier = Modifier.size(32.dp)) {
            Icon(Icons.Filled.Remove, stringResource(R.string.fx_stop_remove))
        }
        IconButton(onClick = {
            val n = stops.size + 1
            onChange((stops + stops.last()).mapIndexed { k, st -> st.copy(offset = k / (n - 1f)) })
        }, enabled = stops.size < 8, modifier = Modifier.size(32.dp)) {
            Icon(Icons.Filled.Add, stringResource(R.string.fx_stop_add))
        }
    }
    editing?.let { k ->
        if (k < stops.size) {
            ColorPickerDialog(
                stops[k].color,
                onDismiss = { editing = null; vm.endGesture() },
                onChange = { c -> onChange(stops.mapIndexed { j, st -> if (j == k) st.copy(color = c) else st }) },
                onConfirm = { c -> onChange(stops.mapIndexed { j, st -> if (j == k) st.copy(color = c) else st }); vm.endGesture(); editing = null },
            )
        }
    }
}

@Composable
private fun MasksBlock(vm: EditorViewModel, layer: Layer, local: Double, onEditCurve: (CurveTarget) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    SectionTitle(stringResource(R.string.section_masks)) {
        Box {
            IconButton(onClick = { menu = true }, modifier = Modifier.size(32.dp), enabled = layer.masks.size < Mask.MAX_MASKS) {
                Icon(Icons.Filled.Add, stringResource(R.string.add_mask))
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                listOf(MaskShape.RECTANGLE to R.string.shape_rectangle, MaskShape.ELLIPSE to R.string.shape_ellipse).forEach { (shape, label) ->
                    DropdownMenuItem(text = { Text(stringResource(label)) }, onClick = {
                        menu = false
                        vm.updateLayer(layer.id) { it.copy(masks = it.masks + Mask(shape = shape)) }
                    })
                }
            }
        }
    }
    val sections = LayerProps.sections(layer).filter { it.id.startsWith("masks.") }
    layer.masks.forEachIndexed { i, m ->
        fun upd(f: (Mask) -> Mask) = vm.updateLayer(layer.id) { l -> l.copy(masks = l.masks.mapIndexed { j, x -> if (j == i) f(x) else x }) }
        Card {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.mask_n, i + 1), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Switch(checked = m.enabled, onCheckedChange = { on -> upd { it.copy(enabled = on) } })
                IconButton(onClick = { vm.updateLayer(layer.id) { l -> l.copy(masks = l.masks.filterIndexed { j, _ -> j != i }) } }) {
                    Icon(Icons.Filled.Delete, stringResource(R.string.delete), tint = KColors.TextSecondary)
                }
            }
            ChoiceChips(listOf(MaskShape.RECTANGLE to stringResource(R.string.shape_rectangle), MaskShape.ELLIPSE to stringResource(R.string.shape_ellipse)), m.shape, { s -> upd { it.copy(shape = s) } })
            Spacer(Modifier.height(4.dp))
            ChoiceChips(MaskMode.entries.map { it to stringResource(maskModeRes(it)) }, m.mode, { mode -> upd { it.copy(mode = mode) } })
            SwitchRow(stringResource(R.string.invert), m.inverted) { v -> upd { it.copy(inverted = v) } }
            sections.firstOrNull { it.id == "masks.$i" }?.props?.forEach { PropRow(vm, layer, it, local, onEditCurve) }
        }
    }
}

@Composable
private fun MatteBlock(vm: EditorViewModel, project: KnsProject, layer: Layer) {
    val siblings = LayerTree.siblings(project, layer.id).filter { it.id != layer.id && it.type.isVisual }
    SectionTitle(stringResource(R.string.track_matte))
    val options = listOf<String?>(null) + siblings.map { it.id }
    ChoiceChips(
        options.map { it to (it?.let { sid -> layerDisplayName(siblings.first { s -> s.id == sid }) } ?: stringResource(R.string.none)) },
        layer.matte?.layer,
        { sel -> vm.updateLayer(layer.id) { it.copy(matte = sel?.let { s -> Matte(s, it.matte?.mode ?: MatteMode.ALPHA) }) } },
    )
    layer.matte?.let { m ->
        Spacer(Modifier.height(4.dp))
        ChoiceChips(MatteMode.entries.map { it to stringResource(matteModeRes(it)) }, m.mode, { mode -> vm.updateLayer(layer.id) { it.copy(matte = m.copy(mode = mode)) } })
    }
}

@Composable
private fun TransitionsBlock(vm: EditorViewModel, layer: Layer) {
    SectionTitle(stringResource(R.string.transitions))
    TransitionEditor(stringResource(R.string.transition_in), layer.transitionIn, layer.duration, { vm.endGesture() }) { t -> vm.updateLayer(layer.id, "trin:${layer.id}") { it.copy(transitionIn = t) } }
    TransitionEditor(stringResource(R.string.transition_out), layer.transitionOut, layer.duration, { vm.endGesture() }) { t -> vm.updateLayer(layer.id, "trout:${layer.id}") { it.copy(transitionOut = t) } }
}

@Composable
private fun TransitionEditor(label: String, t: Transition?, layerDuration: Double, onDone: () -> Unit, onChange: (Transition?) -> Unit) {
    SmallLabel(label)
    val options = listOf<TransitionType?>(null) + TransitionType.entries
    ChoiceChips(options.map { it to (it?.let { tt -> stringResource(transitionRes(tt)) } ?: stringResource(R.string.none)) }, t?.type, { type ->
        onChange(type?.let { (t ?: Transition()).copy(type = it) })
        onDone()
    })
    if (t != null) {
        LabeledSlider(stringResource(R.string.duration), t.duration.toFloat(), 0.05f..(layerDuration.toFloat().coerceAtLeast(0.1f)), { v -> onChange(t.copy(duration = v.toDouble())) }, onDone, suffix = " s", min = 0.05f)
        val easings = listOf(EasingPreset.LINEAR, EasingPreset.EASE_OUT_CUBIC, EasingPreset.EASE_IN_OUT_CUBIC, EasingPreset.EASE_OUT_BACK, EasingPreset.EASE_OUT_EXPO, EasingPreset.EASE_OUT_BOUNCE)
        ChoiceChips(easings.map { (Easing.Preset(it) as Easing) to easingLabel(it) }, t.easing, { e -> onChange(t.copy(easing = e)); onDone() })
    }
    Spacer(Modifier.height(6.dp))
}

@Composable
private fun TextAnimatorsBlock(vm: EditorViewModel, layer: Layer) {
    val t = layer.text ?: return
    var menu by remember { mutableStateOf(false) }
    fun upd(f: (List<TextAnimator>) -> List<TextAnimator>) = vm.updateLayer(layer.id) { l -> l.updateText { c -> c.copy(animators = f(c.animators)) } }
    SectionTitle(stringResource(R.string.text_animators)) {
        Box {
            IconButton(onClick = { menu = true }, modifier = Modifier.size(32.dp)) { Icon(Icons.Filled.Add, stringResource(R.string.add)) }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                TextAnimatorType.entries.forEach { type ->
                    DropdownMenuItem(text = { Text(stringResource(textAnimatorRes(type))) }, onClick = {
                        menu = false
                        upd { it + TextAnimator(type = type, amount = if (type == TextAnimatorType.SCALE) 0f else if (type == TextAnimatorType.WAVE) 20f else 60f, unitDuration = if (type == TextAnimatorType.WAVE) 1.2 else 0.4) }
                    })
                }
            }
        }
    }
    t.animators.forEachIndexed { i, a ->
        fun set(f: (TextAnimator) -> TextAnimator) = vm.updateLayer(layer.id, "ta:${layer.id}:$i") { l -> l.updateText { c -> c.copy(animators = c.animators.mapIndexed { j, x -> if (j == i) f(x) else x }) } }
        Card {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(textAnimatorRes(a.type)), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                IconButton(onClick = { upd { list -> list.filterIndexed { j, _ -> j != i } } }) { Icon(Icons.Filled.Delete, stringResource(R.string.delete), tint = KColors.TextSecondary) }
            }
            if (a.type != TextAnimatorType.WAVE) {
                ChoiceChips(listOf(TextAnimatorMode.IN to stringResource(R.string.mode_in), TextAnimatorMode.OUT to stringResource(R.string.mode_out)), a.mode, { m -> set { it.copy(mode = m) }; vm.endGesture() })
                Spacer(Modifier.height(4.dp))
            }
            ChoiceChips(listOf(TextUnit.CHARACTER to stringResource(R.string.unit_character), TextUnit.WORD to stringResource(R.string.unit_word), TextUnit.LINE to stringResource(R.string.unit_line)), a.unit, { u -> set { it.copy(unit = u) }; vm.endGesture() })
            Spacer(Modifier.height(4.dp))
            ChoiceChips(
                listOf(TextOrder.FORWARD to stringResource(R.string.order_forward), TextOrder.BACKWARD to stringResource(R.string.order_backward), TextOrder.CENTER to stringResource(R.string.order_center), TextOrder.RANDOM to stringResource(R.string.order_random)),
                a.order, { o -> set { it.copy(order = o) }; vm.endGesture() },
            )
            LabeledSlider(stringResource(R.string.start), a.start.toFloat(), 0f..layer.duration.toFloat(), { v -> set { it.copy(start = v.toDouble()) } }, { vm.endGesture() }, suffix = " s")
            LabeledSlider(stringResource(R.string.stagger), a.stagger.toFloat(), 0f..0.5f, { v -> set { it.copy(stagger = v.toDouble()) } }, { vm.endGesture() }, suffix = " s", min = 0f)
            LabeledSlider(stringResource(R.string.unit_duration), a.unitDuration.toFloat(), 0.05f..3f, { v -> set { it.copy(unitDuration = v.toDouble()) } }, { vm.endGesture() }, suffix = " s", min = 0.02f)
            if (a.type != TextAnimatorType.TYPEWRITER && a.type != TextAnimatorType.FADE) {
                LabeledSlider(stringResource(R.string.prop_amount), a.amount, if (a.type == TextAnimatorType.SCALE) 0f..200f else 0f..400f, { v -> set { it.copy(amount = v) } }, { vm.endGesture() })
            }
        }
    }
}

// ---------------------------------------------------------------------------------------- layer & composition

@Composable
private fun LayerSettings(vm: EditorViewModel, project: KnsProject, layer: Layer) {
    val id = layer.id
    SectionTitle(stringResource(R.string.section_layer))
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SmallLabel(stringResource(R.string.start), Modifier.width(56.dp))
        ScrubNumber(layer.start.toFloat(), { v -> vm.moveLayerTime(id, project.composition.snap(v.toDouble()), "start:$id") }, { vm.endGesture() }, Modifier.weight(1f), step = 0.1f, suffix = " s")
        SmallLabel(stringResource(R.string.duration), Modifier.width(64.dp))
        ScrubNumber(layer.duration.toFloat(), { v -> vm.trimLayerEnd(id, project.composition.snap(v.toDouble()).coerceAtLeast(project.composition.frameDuration), "dur:$id") }, { vm.endGesture() }, Modifier.weight(1f), step = 0.1f, min = 0.02f, suffix = " s")
    }
    if (layer.type.isVisual) {
        SmallLabel(stringResource(R.string.blend_mode))
        ChoiceChips(BlendMode.entries.map { it to stringResource(blendRes(it)) }, layer.blendMode, { b -> vm.updateLayer(id) { it.copy(blendMode = b) } })
        SwitchRow(stringResource(R.string.motion_blur), layer.motionBlur) { v -> vm.updateLayer(id) { it.copy(motionBlur = v) } }
    }
    if (layer.type != LayerType.AUDIO) {
        val candidates = LayerTree.siblings(project, id).filter {
            dev.kinesis.core.edit.TimelineOps.canParent(layer, it)
        }
        SmallLabel(stringResource(R.string.parent))
        ChoiceChips(
            (listOf<String?>(null) + candidates.map { it.id }).map { it to (it?.let { pid -> layerDisplayName(candidates.first { c -> c.id == pid }) } ?: stringResource(R.string.none)) },
            layer.parent, { p -> vm.setParent(id, p) },
        )
    }
}

@Composable
private fun CompositionSettings(vm: EditorViewModel, project: KnsProject) {
    val comp = project.composition
    var renaming by remember { mutableStateOf(false) }
    var picking by remember { mutableStateOf(false) }
    fun upd(key: String? = null, f: (Composition) -> Composition) = vm.edit(key) { it.copy(composition = f(it.composition)) }
    Column {
        SectionTitle(stringResource(R.string.section_project))
        Row(Modifier.fillMaxWidth().clickable { renaming = true }.padding(vertical = 8.dp)) {
            Text(project.meta.title, style = MaterialTheme.typography.titleMedium)
        }
        SmallLabel(stringResource(R.string.no_selection_hint))
        SectionTitle(stringResource(R.string.format))
        val presets = listOf("9:16" to (1080 to 1920), "16:9" to (1920 to 1080), "1:1" to (1080 to 1080), "4:5" to (1080 to 1350))
        ChoiceChips(presets.map { it.second to it.first }, comp.width to comp.height, { (w, h) -> upd { it.copy(width = w, height = h) } })
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
            SmallLabel(stringResource(R.string.width))
            ScrubNumber(comp.width.toFloat(), { v -> upd("w") { it.copy(width = ((v / 2).toInt() * 2).coerceIn(16, 8192)) } }, { vm.endGesture() }, Modifier.weight(1f), step = 8f, min = 16f, max = 8192f)
            SmallLabel(stringResource(R.string.height))
            ScrubNumber(comp.height.toFloat(), { v -> upd("h") { it.copy(height = ((v / 2).toInt() * 2).coerceIn(16, 8192)) } }, { vm.endGesture() }, Modifier.weight(1f), step = 8f, min = 16f, max = 8192f)
        }
        SmallLabel(stringResource(R.string.frame_rate))
        ChoiceChips(listOf(24.0 to "24", 25.0 to "25", 30.0 to "30", 50.0 to "50", 60.0 to "60"), comp.fps, { f -> upd { it.copy(fps = f) } })
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
            SmallLabel(stringResource(R.string.duration), Modifier.weight(1f))
            ScrubNumber(comp.duration.toFloat(), { v -> upd("dur") { it.copy(duration = v.toDouble().coerceIn(0.1, 3600.0)) } }, { vm.endGesture() }, Modifier.width(110.dp), step = 0.5f, min = 0.1f, max = 3600f, suffix = " s")
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
            SmallLabel(stringResource(R.string.background), Modifier.weight(1f))
            ColorSwatch(comp.background, { picking = true })
        }
        SectionTitle(stringResource(R.string.motion_blur))
        SwitchRow(stringResource(R.string.enabled), comp.motionBlur.enabled) { v -> upd { it.copy(motionBlur = it.motionBlur.copy(enabled = v)) } }
        LabeledSlider(stringResource(R.string.shutter_angle), comp.motionBlur.shutterAngle, 0f..360f, { v -> upd("sh") { it.copy(motionBlur = it.motionBlur.copy(shutterAngle = v)) } }, { vm.endGesture() }, suffix = "°", min = 0f, max = 720f)
        LabeledSlider(stringResource(R.string.samples), comp.motionBlur.samples.toFloat(), 2f..16f, { v -> upd("smp") { it.copy(motionBlur = it.motionBlur.copy(samples = v.toInt().coerceIn(2, 64))) } }, { vm.endGesture() }, min = 2f, max = 64f)
    }
    if (renaming) {
        TextInputDialog(stringResource(R.string.rename), project.meta.title, onDismiss = { renaming = false }) { vm.rename(it); renaming = false }
    }
    if (picking) {
        ColorPickerDialog(comp.background, onDismiss = { picking = false; vm.endGesture() }, onChange = { c -> upd("bg") { it.copy(background = c) } }, onConfirm = { c ->
            upd("bg") { it.copy(background = c) }
            vm.endGesture()
            picking = false
        })
    }
}

@Composable
private fun Card(content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(KColors.Surface)
            .padding(10.dp),
    ) { content() }
}
