package dev.kinesis.editor.ui.editor

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ArrowRight
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.FirstPage
import androidx.compose.material.icons.filled.LastPage
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.outlined.Animation
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.BorderStyle
import androidx.compose.material.icons.outlined.CenterFocusStrong
import androidx.compose.material.icons.outlined.CropFree
import androidx.compose.material.icons.outlined.FormatColorFill
import androidx.compose.material.icons.outlined.FormatItalic
import androidx.compose.material.icons.outlined.Gradient
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.OpenInFull
import androidx.compose.material.icons.outlined.OpenWith
import androidx.compose.material.icons.outlined.Opacity
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Rotate90DegreesCw
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.kinesis.core.anim.KeyframeOps
import dev.kinesis.core.edit.AnimProp
import dev.kinesis.core.edit.FloatKind
import dev.kinesis.core.edit.LayerProps
import dev.kinesis.core.edit.PropUnit
import dev.kinesis.core.edit.Vec2Kind
import dev.kinesis.core.edit.Vec3Kind
import dev.kinesis.core.math.Vec2
import dev.kinesis.core.math.Vec3
import dev.kinesis.core.model.BlendMode
import dev.kinesis.core.model.Effect
import dev.kinesis.core.model.Gradient
import dev.kinesis.core.model.GradientStop
import dev.kinesis.core.model.KnsProject
import dev.kinesis.core.model.Layer
import dev.kinesis.core.model.LayerTree
import dev.kinesis.core.model.LayerType
import dev.kinesis.core.model.ShapeKind
import dev.kinesis.core.model.StrokeCap
import dev.kinesis.core.model.StrokeJoin
import dev.kinesis.core.model.updateMedia
import dev.kinesis.core.model.updateShape
import dev.kinesis.core.model.updateText
import dev.kinesis.editor.R
import dev.kinesis.editor.ui.common.ChoiceChips
import dev.kinesis.editor.ui.common.ColorPickerDialog
import dev.kinesis.editor.ui.common.KeyframeButton
import dev.kinesis.editor.ui.common.Lucide
import dev.kinesis.editor.ui.common.ScrubNumber
import dev.kinesis.editor.ui.common.TextInputDialog
import dev.kinesis.editor.ui.theme.KColors
import kotlin.math.ceil
import kotlin.math.floor
import dev.kinesis.core.math.Color as KColor

/** Pages of the layer editor: a grid of categories, each opening its own page. */
enum class EditPage { HOME, PROPS, COLOR, BORDER, BLEND, MOVE, SHAPE, TEXT, CONTENT, EFFECTS, MASK, TRANSITION, LAYER }

private val TileShape = RoundedCornerShape(10.dp)

/** The page a shortcut opens (see [propertyItemKeys]). */
private fun pageOf(focus: String?, layer: Layer): EditPage = when (focus) {
    "effects" -> EditPage.EFFECTS
    "masks", "matte" -> EditPage.BLEND
    "transitions" -> EditPage.BLEND
    "animators", "content" -> if (layer.type == LayerType.TEXT) EditPage.TEXT else if (layer.type == LayerType.SHAPE) EditPage.SHAPE else EditPage.CONTENT
    "layer" -> EditPage.BLEND
    else -> EditPage.HOME
}

private fun colorPropId(layer: Layer): String? =
    listOf("shape.fill.color", "text.color", "object3d.material.color", "model3d.material.color", "light.color")
        .firstOrNull { LayerProps.find(layer, it) != null }

private fun opacityProp(layer: Layer): AnimProp<*>? =
    LayerProps.find(layer, "transform.opacity") ?: LayerProps.find(layer, "transform3d.opacity")

/** Position, rotation, scale and anchor of the layer (2D or 3D), in that order; missing ones are skipped. */
private fun moveProps(layer: Layer): List<Pair<String, AnimProp<*>>> {
    val prefix = if (LayerProps.find(layer, "transform3d.position") != null) "transform3d" else "transform"
    return listOf("position", "rotation", "scale", "skew", "anchor").mapNotNull { n -> LayerProps.find(layer, "$prefix.$n")?.let { n to it } }
}

/** Layer editing: a grid of categories (color, border, blending, motion, ...), each opening a page with a rail on its left. */
@Composable
fun LayerEditor(
    vm: EditorViewModel,
    project: KnsProject,
    time: Double,
    layer: Layer,
    onEditCurve: (CurveTarget) -> Unit,
    onPickTexture: (String) -> Unit,
    onPickFont: () -> Unit,
    onAddEffect: () -> Unit,
    focusSection: String?,
    modifier: Modifier = Modifier,
) {
    var page by rememberSaveable(layer.id, focusSection) { mutableStateOf(pageOf(focusSection, layer)) }
    val local = time - LayerTree.containerOffset(project, layer.id) - layer.start
    BackHandler(enabled = page != EditPage.HOME) { page = EditPage.HOME }
    val back = { page = EditPage.HOME }
    Box(modifier.fillMaxSize()) {
        when (page) {
            EditPage.HOME -> HomeGrid(vm, layer) { page = it }
            EditPage.COLOR -> {
                val prop = colorPropId(layer)?.let { LayerProps.find(layer, it) }
                SubPage(vm, layer, local, prop, onEditCurve, back) { if (prop != null) ColorPage(vm, layer, local, LayerProps.typed(prop)) }
            }
            EditPage.BORDER -> {
                val width = LayerProps.find(layer, "shape.stroke.width") ?: LayerProps.find(layer, "text.stroke.width")
                SubPage(vm, layer, local, width, onEditCurve, back) { BorderPage(vm, layer, local) }
            }
            EditPage.BLEND -> SubPage(vm, layer, local, opacityProp(layer), onEditCurve, back) {
                BlendPage(vm, layer, local) {
                    if (layer.type.is2DVisual) { MasksBlock(vm, layer, local, onEditCurve); MatteBlock(vm, project, layer) }
                    if (layer.type.isVisual) TransitionsBlock(vm, layer)
                    LayerHeader(vm, layer)
                    LayerSettings(vm, project, layer)
                }
            }
            EditPage.MOVE -> MovePage(vm, project, layer, local, onEditCurve, back)
            EditPage.SHAPE -> SubPage(vm, layer, local, null, onEditCurve, back) { ScrollPage { ShapePage(vm, layer, local, onEditCurve) } }
            EditPage.TEXT -> SubPage(vm, layer, local, null, onEditCurve, back) {
                ScrollPage {
                    ContentControls(vm, project, layer, onPickTexture, onPickFont)
                    PropList(vm, layer, local, onEditCurve) { it.startsWith("text.") && !it.startsWith("text.stroke.") && it != "text.color" }
                    TextAnimatorsBlock(vm, layer)
                    TextExpressionAnimatorsBlock(vm, layer)
                }
            }
            EditPage.CONTENT -> SubPage(vm, layer, local, null, onEditCurve, back) {
                ScrollPage {
                    ContentControls(vm, project, layer, onPickTexture, onPickFont)
                    val skip = setOfNotNull(colorPropId(layer))
                    PropList(vm, layer, local, onEditCurve) { id ->
                        id !in skip && listOf("transform", "text.", "shape.", "effects.", "masks.").none { id.startsWith(it) }
                    }
                }
            }
            EditPage.PROPS -> SubPage(vm, layer, local, null, onEditCurve, back) {
                ScrollPage { PropList(vm, layer, local, onEditCurve) { id -> !id.startsWith("effects.") && !id.startsWith("masks.") } }
            }
            EditPage.EFFECTS -> SubPage(vm, layer, local, null, onEditCurve, back) { ScrollPage { EffectsBlock(vm, layer, local, onEditCurve, onAddEffect) } }
            EditPage.MASK -> SubPage(vm, layer, local, null, onEditCurve, back) { ScrollPage { MasksBlock(vm, layer, local, onEditCurve); MatteBlock(vm, project, layer) } }
            EditPage.TRANSITION -> SubPage(vm, layer, local, null, onEditCurve, back) { ScrollPage { TransitionsBlock(vm, layer) } }
            EditPage.LAYER -> SubPage(vm, layer, local, null, onEditCurve, back) { ScrollPage { LayerHeader(vm, layer); LayerSettings(vm, project, layer) } }
        }
    }
}

// ---------------------------------------------------------------------------------------- home grid

private class Tile(val icon: ImageVector, val label: Int, val page: EditPage)

private fun tilesFor(layer: Layer): List<Tile> = buildList {
    if (colorPropId(layer) != null) add(Tile(Icons.Outlined.FormatColorFill, R.string.le_color_fill, EditPage.COLOR))
    if (layer.type.is2DVisual) add(Tile(Icons.Outlined.BorderStyle, R.string.le_border_shadow, EditPage.BORDER))
    if (moveProps(layer).isNotEmpty()) add(Tile(Lucide.Move3d, R.string.le_motion, EditPage.MOVE))
    when (layer.type) {
        LayerType.SHAPE -> add(Tile(Lucide.Shapes, R.string.le_edit_shape, EditPage.SHAPE))
        LayerType.TEXT -> add(Tile(Lucide.Type, R.string.le_edit_text, EditPage.TEXT))
        else -> add(Tile(Lucide.Component, R.string.le_content, EditPage.CONTENT))
    }
    if (layer.type.isVisual) add(Tile(Lucide.Sparkles, R.string.effects, EditPage.EFFECTS))
    // Opacity, blending, masks, transitions and the layer's settings share one page.
    add(Tile(Lucide.Layers, R.string.le_opacity_more, EditPage.BLEND))
}

@Composable
private fun HomeGrid(vm: EditorViewModel, layer: Layer, onOpen: (EditPage) -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // Quick actions: icon only, all the same weight.
        Row(Modifier.fillMaxWidth().height(52.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            val media = layer.media
            if (media != null) QuickTile(Icons.Filled.Speed, stringResource(R.string.speed)) { onOpen(EditPage.CONTENT) }
            QuickTile(Icons.Filled.FirstPage, stringResource(R.string.le_trim_start)) { vm.trimStartToPlayhead(layer.id) }
            QuickTile(Icons.Filled.ContentCut, stringResource(R.string.split)) { vm.splitSelected() }
            QuickTile(Icons.Filled.LastPage, stringResource(R.string.le_trim_end)) { vm.trimEndToPlayhead(layer.id) }
            if (media != null) {
                QuickTile(
                    if (media.muted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
                    stringResource(if (media.muted) R.string.le_unmute else R.string.mute),
                    active = media.muted,
                ) { vm.updateLayer(layer.id) { l -> l.updateMedia { it.copy(muted = !it.muted) } } }
            }
        }
        for (row in tilesFor(layer).chunked(3)) {
            Row(Modifier.fillMaxWidth().height(76.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (t in row) CategoryTile(t.icon, stringResource(t.label)) { onOpen(t.page) }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.QuickTile(icon: ImageVector, description: String, active: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier.weight(1f).fillMaxHeight().clip(TileShape).background(KColors.SurfaceAlt).clickable(onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) { Icon(icon, null, tint = if (active) KColors.Accent else KColors.TextPrimary, modifier = Modifier.size(24.dp)) }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.CategoryTile(icon: ImageVector, label: String, onClick: () -> Unit) {
    Column(
        Modifier.weight(1f).fillMaxHeight().clip(TileShape).background(KColors.SurfaceAlt).clickable(onClick = onClick).padding(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(icon, null, tint = KColors.TextPrimary, modifier = Modifier.size(26.dp))
        Spacer(Modifier.height(6.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = KColors.TextSoft, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

// ---------------------------------------------------------------------------------------- page frame

/** A page: back, keyframe and curve on a rail at the left ([keyProp] is the property they act on), the content on the right. */
@Composable
private fun SubPage(
    vm: EditorViewModel, layer: Layer, local: Double, keyProp: AnimProp<*>?, onEditCurve: (CurveTarget) -> Unit, onBack: () -> Unit,
    rightRail: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    Row(Modifier.fillMaxSize()) {
        Column(Modifier.width(56.dp).fillMaxHeight().padding(top = 4.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            IconButton(onClick = onBack) { Icon(Lucide.ArrowLeft, stringResource(R.string.back), tint = KColors.TextPrimary) }
            if (keyProp != null) {
                val anim = keyProp.get(layer)
                val hasKey = keyProp.hasKeyframeAt(layer, local)
                KeyframeButton(
                    hasKey = hasKey, animated = anim.isAnimated,
                    contentDescription = stringResource(if (hasKey) R.string.remove_keyframe else R.string.add_keyframe),
                    onClick = { vm.toggleKeyframe(layer.id, keyProp) },
                    onLongClick = if (anim.isAnimated) ({ vm.clearKeyframes(layer.id, keyProp) }) else null,
                )
                val segment = KeyframeOps.segmentStartIndex(anim, local)
                IconButton(
                    onClick = { onEditCurve(CurveTarget(layer.id, keyProp.id, segment)) },
                    enabled = anim.isAnimated && segment >= 0 && segment < anim.keyframes.size - 1,
                ) {
                    Icon(Icons.Filled.ShowChart, stringResource(R.string.easing), tint = if (anim.isAnimated) KColors.TextPrimary else KColors.Disabled)
                }
            }
        }
        Box(Modifier.width(1.dp).fillMaxHeight().background(KColors.Line))
        Box(Modifier.weight(1f).fillMaxHeight()) { content() }
        if (rightRail != null) {
            Box(Modifier.width(1.dp).fillMaxHeight().background(KColors.Line))
            Column(Modifier.width(60.dp).fillMaxHeight().verticalScroll(rememberScrollState()).padding(vertical = 4.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) { rightRail() }
        }
    }
}

@Composable
private fun ScrollPage(content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp).padding(bottom = 32.dp)) { content() }
}

@Composable
private fun PropList(vm: EditorViewModel, layer: Layer, local: Double, onEditCurve: (CurveTarget) -> Unit, include: (String) -> Boolean) {
    for (section in LayerProps.sections(layer)) for (p in section.props) if (include(p.id)) PropRow(vm, layer, p, local, onEditCurve)
}

/** Segmented control: equal cells, the selected one raised with its label in the accent colour. */
@Composable
private fun Segmented(labels: List<String>, selected: Int, modifier: Modifier = Modifier, icons: List<ImageVector>? = null, onSelect: (Int) -> Unit) {
    Row(modifier.fillMaxWidth().height(44.dp).clip(TileShape).background(KColors.SurfaceAlt)) {
        labels.forEachIndexed { i, label ->
            val on = i == selected
            Box(
                Modifier.weight(1f).fillMaxHeight().background(if (on) KColors.Line else Color.Transparent).clickable { onSelect(i) }
                    .semantics { contentDescription = label },
                contentAlignment = Alignment.Center,
            ) {
                val tint = if (on) KColors.Accent else KColors.TextSoft
                if (icons != null) Icon(icons[i], null, tint = tint, modifier = Modifier.size(22.dp))
                else Text(label, style = MaterialTheme.typography.labelLarge, color = tint, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

// ---------------------------------------------------------------------------------------- color

private val PRESET_COLORS = listOf(
    "#FF4D4D", "#F5A623", "#F8E45C", "#2ECC40", "#22D3EE", "#4353FF", "#D946EF",
    "#FFFFFF", "#BDBDBD", "#8A8A8A", "#4A4A4A", "#000000",
)

/** Colours the user saved; they sit right after black in the swatch grid. */
private object SavedColors {
    private const val PREFS = "kinesis_editor"
    private const val KEY = "saved_colors"
    fun load(ctx: Context): List<String> =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "").orEmpty().split(',').filter { KColor.parseOrNull(it) != null }
    fun save(ctx: Context, list: List<String>) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, list.joinToString(",")).apply()
    }
}

private fun KColor.compose() = Color(r.coerceIn(0f, 1f), g.coerceIn(0f, 1f), b.coerceIn(0f, 1f), a.coerceIn(0f, 1f))

/** Text colour readable on [c] (black on light colours, white on dark ones). */
private fun onColor(c: KColor): Color = if (0.2126f * c.r + 0.7152f * c.g + 0.0722f * c.b > 0.45f || c.a < 0.4f) Color.Black else Color.White

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ColorPage(vm: EditorViewModel, layer: Layer, local: Double, prop: AnimProp<KColor>) {
    val ctx = LocalContext.current
    val id = layer.id
    val key = "color:$id:${prop.id}"
    val color = prop.valueAt(layer, local)
    var saved by remember { mutableStateOf(SavedColors.load(ctx)) }
    var picking by remember { mutableStateOf(false) }
    var editingHex by remember { mutableStateOf(false) }
    fun set(c: KColor) { vm.setProperty(id, prop, c, key); vm.endGesture() }

    Column(Modifier.fillMaxSize().padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val shape = layer.shape
        if (shape != null && prop.id == "shape.fill.color") {
            val mode = when { !shape.fill.enabled -> 0; shape.fill.gradient != null -> 2; else -> 1 }
            Segmented(
                listOf(stringResource(R.string.none), stringResource(R.string.le_solid), stringResource(R.string.gradient)), mode,
                icons = listOf(Icons.Outlined.Block, Icons.Outlined.FormatColorFill, Icons.Outlined.Gradient),
            ) { m ->
                vm.updateLayer(id) { l ->
                    l.updateShape { c ->
                        when (m) {
                            0 -> c.copy(fill = c.fill.copy(enabled = false))
                            1 -> c.copy(fill = c.fill.copy(enabled = true, gradient = null))
                            else -> {
                                val size = c.size.value
                                val g = c.fill.gradient ?: Gradient(
                                    start = Vec2(-size.x / 2, 0f), end = Vec2(size.x / 2, 0f),
                                    stops = listOf(GradientStop(0f, c.fill.color.value), GradientStop(1f, KColor.parse("#4FD1C5"))),
                                )
                                c.copy(fill = c.fill.copy(enabled = true, gradient = g))
                            }
                        }
                    }
                }
            }
            if (mode == 0) return@Column
            if (mode == 2) {
                Column(Modifier.verticalScroll(rememberScrollState())) { GradientEditor(vm, id, shape.fill.gradient ?: return@Column) }
                return@Column
            }
        }
        Row(Modifier.weight(1f).fillMaxWidth().clip(TileShape).background(KColors.Background)) {
            Column(Modifier.weight(1f).fillMaxHeight()) {
                // Current colour; tap to type a hex value.
                Box(
                    Modifier.fillMaxWidth().height(44.dp).background(color.compose()).clickable { editingHex = true }
                        .semantics { contentDescription = "hex" },
                    contentAlignment = Alignment.Center,
                ) { Text("${color.toHex().uppercase()} (${Math.round(color.a * 100)}%)", style = MaterialTheme.typography.titleMedium, color = onColor(color)) }
                val swatches = PRESET_COLORS + saved
                LazyVerticalGrid(
                    GridCells.Adaptive(40.dp), Modifier.weight(1f).fillMaxWidth().padding(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(swatches.size) { i ->
                        val hex = swatches[i]
                        val c = KColor.parse(hex)
                        val isSaved = i >= PRESET_COLORS.size
                        val selected = c.toHex().equals(color.toHex(), ignoreCase = true)
                        Box(
                            Modifier.aspectRatio(1f).clip(RoundedCornerShape(8.dp)).background(c.compose())
                                .then(if (selected) Modifier.border(2.dp, KColors.Primary, RoundedCornerShape(8.dp)) else Modifier.border(1.dp, KColors.Line, RoundedCornerShape(8.dp)))
                                .combinedClickable(
                                    onClick = { set(c) },
                                    onLongClick = if (isSaved) ({ saved = saved - hex; SavedColors.save(ctx, saved) }) else null,
                                ),
                        )
                    }
                }
            }
            Box(Modifier.width(1.dp).fillMaxHeight().background(KColors.Line))
            Column(Modifier.width(56.dp).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
                IconButton(onClick = { picking = true }, modifier = Modifier.weight(1f).fillMaxWidth()) {
                    Icon(Icons.Outlined.Palette, stringResource(R.string.le_custom_color), tint = KColors.TextPrimary)
                }
                Box(Modifier.fillMaxWidth().height(1.dp).background(KColors.Line))
                IconButton(
                    onClick = { val hex = color.toHex(); if (hex !in saved) { saved = saved + hex; SavedColors.save(ctx, saved) } },
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                ) { Icon(Icons.Filled.Add, stringResource(R.string.le_save_color), tint = KColors.TextPrimary) }
            }
        }
        if (saved.isNotEmpty()) Text(stringResource(R.string.le_saved_hint), style = MaterialTheme.typography.bodySmall, color = KColors.TextSecondary)
    }
    if (picking) {
        ColorPickerDialog(
            color,
            onDismiss = { picking = false; vm.endGesture() },
            onChange = { vm.setProperty(id, prop, it, key) },
            onConfirm = { set(it); picking = false },
        )
    }
    if (editingHex) {
        TextInputDialog(stringResource(R.string.le_hex), color.toHex(), onDismiss = { editingHex = false }) { s ->
            val t = s.trim()
            KColor.parseOrNull(if (t.startsWith("#")) t else "#$t")?.let { set(it) }
            editingHex = false
        }
    }
}

// ---------------------------------------------------------------------------------------- border & shadow

@Composable
private fun BorderPage(vm: EditorViewModel, layer: Layer, local: Double) {
    val id = layer.id
    ScrollPage {
        Spacer(Modifier.height(6.dp))
        val shape = layer.shape
        val text = layer.text
        if (shape != null || text != null) {
            val on = shape?.stroke?.enabled ?: (text?.stroke != null)
            Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.stroke), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Switch(checked = on, onCheckedChange = { v ->
                    vm.updateLayer(id) { l ->
                        if (shape != null) l.updateShape { c -> c.copy(stroke = c.stroke.copy(enabled = v)) }
                        else if (v) LayerProps.withTextStroke(l) else l.updateText { c -> c.copy(stroke = null) }
                    }
                }, colors = SwitchDefaults.colors(checkedTrackColor = KColors.Primary, checkedThumbColor = KColors.OnPrimary))
            }
            if (on) {
                val width = LayerProps.find(layer, if (shape != null) "shape.stroke.width" else "text.stroke.width")
                if (width != null) RulerRow(vm, layer, local, LayerProps.typed(width), unitsPerTick = 0.5f)
                val colorProp = LayerProps.find(layer, if (shape != null) "shape.stroke.color" else "text.stroke.color")
                if (colorProp != null) InlineColorRow(vm, layer, local, LayerProps.typed(colorProp))
                if (shape != null) {
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        val caps = listOf(StrokeCap.BUTT, StrokeCap.ROUND, StrokeCap.SQUARE)
                        Segmented(
                            listOf(stringResource(R.string.cap_butt), stringResource(R.string.cap_round), stringResource(R.string.cap_square)),
                            caps.indexOf(shape.stroke.cap), Modifier.weight(1f),
                        ) { i -> vm.updateLayer(id) { it.updateShape { c -> c.copy(stroke = c.stroke.copy(cap = caps[i])) } } }
                        val joins = listOf(StrokeJoin.MITER, StrokeJoin.ROUND, StrokeJoin.BEVEL)
                        Segmented(
                            listOf(stringResource(R.string.join_miter), stringResource(R.string.join_round), stringResource(R.string.join_bevel)),
                            joins.indexOf(shape.stroke.join), Modifier.weight(1f),
                        ) { i -> vm.updateLayer(id) { it.updateShape { c -> c.copy(stroke = c.stroke.copy(join = joins[i])) } } }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Box(Modifier.fillMaxWidth().height(1.dp).background(KColors.Line))
        }
        // Shadow: the drop-shadow effect, switched on and off here; its settings live in the effects page.
        val shadowIndex = layer.effects.indexOfFirst { it.type == "dropShadow" }
        Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.le_shadow), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Switch(checked = shadowIndex >= 0, onCheckedChange = { v ->
                vm.updateLayer(id) { l ->
                    if (v) l.copy(effects = l.effects + Effect.create("dropShadow")) else l.copy(effects = l.effects.filter { it.type != "dropShadow" })
                }
            }, colors = SwitchDefaults.colors(checkedTrackColor = KColors.Primary, checkedThumbColor = KColors.OnPrimary))
        }
        if (shadowIndex >= 0) {
            val p = "effects.$shadowIndex."
            PropList(vm, layer, local, { }) { it.startsWith(p) }
        }
    }
}

@Composable
private fun InlineColorRow(vm: EditorViewModel, layer: Layer, local: Double, prop: AnimProp<KColor>) {
    val c = prop.valueAt(layer, local)
    var picking by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().height(48.dp).clip(TileShape).background(KColors.SurfaceAlt).clickable { picking = true }.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.le_color), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Text(c.toHex().uppercase(), style = MaterialTheme.typography.bodyMedium, color = KColors.TextSoft)
        Spacer(Modifier.width(10.dp))
        Box(Modifier.size(28.dp).clip(RoundedCornerShape(6.dp)).background(c.compose()).border(1.dp, KColors.LineStrong, RoundedCornerShape(6.dp)))
    }
    if (picking) {
        val key = "color:${layer.id}:${prop.id}"
        ColorPickerDialog(
            c,
            onDismiss = { picking = false; vm.endGesture() },
            onChange = { vm.setProperty(layer.id, prop, it, key) },
            onConfirm = { vm.setProperty(layer.id, prop, it, key); vm.endGesture(); picking = false },
        )
    }
}

/** A ruler that slides under a fixed centre line, next to the typed value. */
@Composable
private fun RulerRow(vm: EditorViewModel, layer: Layer, local: Double, prop: AnimProp<Float>, unitsPerTick: Float) {
    val kind = prop.kind as? FloatKind
    val min = kind?.min ?: 0f
    val max = kind?.max ?: Float.POSITIVE_INFINITY
    val display = kind?.display ?: 1f
    val value = prop.valueAt(layer, local)
    val key = "ruler:${layer.id}:${prop.id}"
    Row(Modifier.fillMaxWidth().height(64.dp), verticalAlignment = Alignment.CenterVertically) {
        TickRuler(value, min, max, unitsPerTick, { vm.setProperty(layer.id, prop, it, key) }, { vm.endGesture() }, Modifier.weight(1f))
        Spacer(Modifier.width(8.dp))
        Box(Modifier.width(84.dp).height(52.dp).clip(TileShape).background(KColors.Background), contentAlignment = Alignment.Center) {
            ScrubNumber(value, { vm.setProperty(layer.id, prop, it, key) }, { vm.endGesture() }, Modifier.padding(horizontal = 6.dp), step = unitsPerTick, display = display, min = min, max = max, suffix = if (kind?.unit == PropUnit.PERCENT) "%" else "")
        }
    }
}

@Composable
private fun TickRuler(value: Float, min: Float, max: Float, unitsPerTick: Float, onChange: (Float) -> Unit, onDone: () -> Unit, modifier: Modifier = Modifier) {
    val current by rememberUpdatedState(value)
    val line = KColors.TextSecondary
    val center = KColors.Primary
    Canvas(
        modifier.fillMaxHeight().pointerInput(min, max, unitsPerTick) {
            val tick = 10.dp.toPx()
            detectHorizontalDragGestures(onDragEnd = onDone, onDragCancel = onDone) { change, dx ->
                change.consume()
                onChange((current - dx / tick * unitsPerTick).coerceIn(min, max))
            }
        },
    ) {
        val tick = 10.dp.toPx()
        val cx = size.width / 2
        val offset = value / unitsPerTick * tick
        val first = floor((offset - cx) / tick).toInt()
        val last = ceil((offset + cx) / tick).toInt()
        for (k in first..last) {
            val x = cx + k * tick - offset
            val major = k % 5 == 0
            val h = size.height * if (major) 0.7f else 0.4f
            val fade = (1f - kotlin.math.abs(x - cx) / cx).coerceIn(0.15f, 1f)
            drawLine(line.copy(alpha = fade), Offset(x, (size.height - h) / 2), Offset(x, (size.height + h) / 2), strokeWidth = if (major) 1.5.dp.toPx() else 1.dp.toPx())
        }
        drawLine(center, Offset(cx, 0f), Offset(cx, size.height), strokeWidth = 2.dp.toPx())
    }
}

// ---------------------------------------------------------------------------------------- blending & opacity

@Composable
private fun BlendPage(vm: EditorViewModel, layer: Layer, local: Double, more: @Composable () -> Unit = {}) {
    val id = layer.id
    ScrollPage {
        Spacer(Modifier.height(6.dp))
        opacityProp(layer)?.let { raw ->
            val prop = LayerProps.typed<Float>(raw)
            val kind = prop.kind as? FloatKind
            val v = prop.valueAt(layer, local)
            val key = "opacity:$id"
            Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Opacity, stringResource(R.string.opacity), tint = KColors.TextSoft)
                Slider(
                    value = v.coerceIn(0f, 1f), onValueChange = { vm.setProperty(id, prop, it, key) }, onValueChangeFinished = { vm.endGesture() },
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                    colors = SliderDefaults.colors(thumbColor = KColors.Primary, activeTrackColor = KColors.Primary, inactiveTrackColor = KColors.Line),
                )
                Box(Modifier.width(76.dp).height(48.dp).clip(TileShape).background(KColors.Background), contentAlignment = Alignment.Center) {
                    ScrubNumber(v, { vm.setProperty(id, prop, it, key) }, { vm.endGesture() }, Modifier.padding(horizontal = 6.dp), step = 0.01f, display = kind?.display ?: 100f, min = 0f, max = 1f, suffix = "%")
                }
            }
        }
        val pick = { b: BlendMode -> vm.updateLayer(id) { it.copy(blendMode = b) } }
        if (layer.type.isVisual) BlendRow(stringResource(blendRes(BlendMode.NORMAL)), layer.blendMode == BlendMode.NORMAL) { pick(BlendMode.NORMAL) }
        if (layer.type.isVisual) {
            BlendGroup(stringResource(R.string.le_darken), listOf(BlendMode.MULTIPLY), layer.blendMode, pick)
            BlendGroup(stringResource(R.string.le_lighten), listOf(BlendMode.ADD, BlendMode.SCREEN), layer.blendMode, pick)
        }
        more()
    }
}

@Composable
private fun BlendRow(label: String, selected: Boolean, indent: Boolean = false, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(top = 6.dp, start = if (indent) 24.dp else 0.dp).height(48.dp).clip(TileShape).background(KColors.SurfaceAlt)
            .clickable(onClick = onClick).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        if (selected) Icon(Icons.Filled.CheckCircle, null, tint = KColors.Primary)
    }
}

@Composable
private fun BlendGroup(label: String, modes: List<BlendMode>, current: BlendMode, onPick: (BlendMode) -> Unit) {
    var open by rememberSaveable(label) { mutableStateOf(current in modes) }
    Row(
        Modifier.fillMaxWidth().padding(top = 6.dp).height(48.dp).clip(TileShape).background(KColors.SurfaceAlt).clickable { open = !open }.padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(if (open) Icons.Filled.ArrowDropDown else Icons.Filled.ArrowRight, null, tint = KColors.TextSecondary)
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f).padding(start = 4.dp))
        if (current in modes && !open) Text(stringResource(blendRes(current)), style = MaterialTheme.typography.bodyMedium, color = KColors.Accent)
    }
    if (open) for (m in modes) BlendRow(stringResource(blendRes(m)), current == m, indent = true) { onPick(m) }
}

// ---------------------------------------------------------------------------------------- motion & transform

@Composable
private fun MovePage(vm: EditorViewModel, project: KnsProject, layer: Layer, local: Double, onEditCurve: (CurveTarget) -> Unit, onBack: () -> Unit) {
    val tools = moveProps(layer)
    var tool by rememberSaveable(layer.id) { mutableStateOf(0) }
    val (name, raw) = tools[tool.coerceIn(0, tools.lastIndex)]
    SubPage(vm, layer, local, raw, onEditCurve, onBack, rightRail = {
        tools.forEachIndexed { i, (n, _) ->
            val on = i == tool
            Box(
                Modifier.size(46.dp).clip(TileShape).background(if (on) KColors.Background else Color.Transparent).clickable { tool = i }
                    .semantics { contentDescription = n },
                contentAlignment = Alignment.Center,
            ) { Icon(toolIcon(n), toolLabel(n), tint = if (on) KColors.Accent else KColors.TextPrimary) }
        }
    }) {
        val prop = LayerProps.typed<Any>(raw)
        val value = prop.valueAt(layer, local)
        val comps = componentsOf(value) ?: floatArrayOf()
        val (step, display, suffix) = when (val k = raw.kind) {
            is FloatKind -> Triple(1f, k.display, if (k.unit == PropUnit.DEG) "°" else "")
            is Vec2Kind -> if (k.unit == PropUnit.PERCENT) Triple(0.01f, k.display, "%") else Triple(2f, k.display, "")
            is Vec3Kind -> when (k.unit) { PropUnit.PERCENT -> Triple(0.01f, k.display, "%"); PropUnit.DEG -> Triple(1f, k.display, "°"); else -> Triple(2f, k.display, "") }
            else -> Triple(1f, 1f, "")
        }
        val key = "move:${layer.id}:${raw.id}"
        fun current(): Pair<Layer, Any?>? {
            val l = LayerTree.find(vm.current, layer.id) ?: return null
            return l to prop.valueAt(l, vm.localTime(l))
        }
        val set = { v: Any -> vm.setProperty(layer.id, prop, v, key) }
        val done = { vm.endGesture() }
        when {
            name == "rotation" && value is Float -> RotationDial(value, display, { set(it) }, done)
            name == "scale" && value is Vec2 -> ScaleControls(value, step, display, suffix, { set(it) }, done)
            name == "skew" && value is Vec2 -> SkewControls(value, { set(it) }, done)
            else -> Column(Modifier.fillMaxSize().padding(8.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)) {
                    val axes = if (comps.size == 1) listOf(toolLabel(name)) else listOf("X", "Y", "Z")
                    comps.forEachIndexed { i, c ->
                        ValueBox(c, axes[i], step, display, suffix, { x -> val (_, v) = current() ?: return@ValueBox; set(withComponent(v, i, x) as Any) }, done)
                    }
                }
                // Drag pad: one screen pixel moves the layer about one composition pixel.
                val bracket = KColors.TextSecondary
                Box(
                    Modifier.weight(1f).fillMaxWidth().padding(top = 8.dp)
                        .drawBehind {
                            val l = 18.dp.toPx(); val w = 2.dp.toPx()
                            for ((x, y) in listOf(0f to 0f, size.width to 0f, 0f to size.height, size.width to size.height)) {
                                val sx = if (x == 0f) 1f else -1f; val sy = if (y == 0f) 1f else -1f
                                drawLine(bracket, Offset(x, y), Offset(x + sx * l, y), w)
                                drawLine(bracket, Offset(x, y), Offset(x, y + sy * l), w)
                            }
                        }
                        .pointerInput(raw.id, layer.id) {
                            detectDragGestures(onDragEnd = done, onDragCancel = done) { change, d ->
                                change.consume()
                                val (_, v) = current() ?: return@detectDragGestures
                                val next = dragged(name, v, d.x, d.y, 1f) ?: return@detectDragGestures
                                set(next)
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) { Text(stringResource(dragHint(name)), style = MaterialTheme.typography.bodyLarge, color = KColors.TextSecondary, textAlign = TextAlign.Center) }
            }
        }
    }
}

@Composable
private fun ValueBox(value: Float, label: String, step: Float, display: Float, suffix: String, onChange: (Float) -> Unit, onDone: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.width(84.dp).height(40.dp).clip(TileShape).background(KColors.SurfaceAlt), contentAlignment = Alignment.Center) {
            ScrubNumber(value, onChange, onDone, Modifier.padding(horizontal = 4.dp), step = step, display = display, suffix = suffix, tint = KColors.Accent)
        }
        Text(label, style = MaterialTheme.typography.labelSmall, color = KColors.TextSecondary, maxLines = 1, softWrap = false)
    }
}

/** A dial: drag the knob around the ring; the arc shows the angle, the value in the middle can be typed. */
@Composable
private fun RotationDial(value: Float, display: Float, onChange: (Float) -> Unit, onDone: () -> Unit) {
    val current by rememberUpdatedState(value)
    val track = KColors.Line
    val arc = KColors.Primary
    val knob = KColors.TextPrimary
    Box(Modifier.fillMaxSize().padding(8.dp), contentAlignment = Alignment.Center) {
        Canvas(
            Modifier.fillMaxHeight().aspectRatio(1f).pointerInput(Unit) {
                var last = 0f
                fun angleOf(p: Offset): Float = Math.toDegrees(kotlin.math.atan2((p.y - size.height / 2f).toDouble(), (p.x - size.width / 2f).toDouble())).toFloat()
                detectDragGestures(
                    onDragStart = { last = angleOf(it) },
                    onDragEnd = onDone, onDragCancel = onDone,
                ) { change, _ ->
                    change.consume()
                    val a = angleOf(change.position)
                    var d = a - last
                    if (d > 180f) d -= 360f
                    if (d < -180f) d += 360f
                    last = a
                    onChange(current + d)
                }
            },
        ) {
            val stroke = 4.dp.toPx()
            val r = size.minDimension / 2f - 14.dp.toPx()
            val c = Offset(size.width / 2f, size.height / 2f)
            drawCircle(track, r, c, style = androidx.compose.ui.graphics.drawscope.Stroke(stroke))
            val sweep = ((value % 360f) + 360f) % 360f
            drawArc(arc, -90f, sweep, false, Offset(c.x - r, c.y - r), androidx.compose.ui.geometry.Size(r * 2, r * 2), style = androidx.compose.ui.graphics.drawscope.Stroke(stroke))
            val rad = Math.toRadians((value - 90f).toDouble())
            drawCircle(knob, 12.dp.toPx(), Offset(c.x + r * kotlin.math.cos(rad).toFloat(), c.y + r * kotlin.math.sin(rad).toFloat()))
        }
        Box(Modifier.width(120.dp).height(52.dp).clip(TileShape).background(KColors.SurfaceAlt), contentAlignment = Alignment.Center) {
            ScrubNumber(value, onChange, onDone, Modifier.padding(horizontal = 6.dp), step = 1f, display = display, suffix = "°", tint = KColors.Accent)
        }
    }
}

/** Skew X and Y, each with its value and a ruler of its own. */
@Composable
private fun SkewControls(value: Vec2, onChange: (Vec2) -> Unit, onDone: () -> Unit) {
    val cur by rememberUpdatedState(value)
    fun setX(x: Float) = onChange(Vec2(x.coerceIn(-80f, 80f), cur.y))
    fun setY(y: Float) = onChange(Vec2(cur.x, y.coerceIn(-80f, 80f)))
    Column(Modifier.fillMaxSize().padding(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)) {
            ValueBox(value.x, stringResource(R.string.le_skew_x), 0.5f, 1f, "°", ::setX, onDone)
            ValueBox(value.y, stringResource(R.string.le_skew_y), 0.5f, 1f, "°", ::setY, onDone)
        }
        Spacer(Modifier.height(6.dp))
        TickRuler(value.x, -80f, 80f, 0.5f, ::setX, onDone, Modifier.weight(1f).fillMaxWidth())
        Box(Modifier.fillMaxWidth().height(1.dp).background(KColors.Line))
        TickRuler(value.y, -80f, 80f, 0.5f, ::setY, onDone, Modifier.weight(1f).fillMaxWidth())
    }
}

/** Width and height with a link between them: linked, one ruler scales both; unlinked, one ruler each. */
@Composable
private fun ScaleControls(value: Vec2, step: Float, display: Float, suffix: String, onChange: (Vec2) -> Unit, onDone: () -> Unit) {
    var linked by rememberSaveable { mutableStateOf(true) }
    val cur by rememberUpdatedState(value)
    fun setX(x: Float) = onChange(if (linked && cur.x != 0f) Vec2(x, cur.y * x / cur.x) else if (linked) Vec2(x, x) else Vec2(x, cur.y))
    fun setY(y: Float) = onChange(if (linked && cur.y != 0f) Vec2(cur.x * y / cur.y, y) else if (linked) Vec2(y, y) else Vec2(cur.x, y))
    Column(Modifier.fillMaxSize().padding(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.Top) {
            ValueBox(value.x, stringResource(R.string.le_width), step, display, suffix, ::setX, onDone)
            Box(
                Modifier.size(36.dp).clip(TileShape).background(if (linked) KColors.Line else KColors.SurfaceAlt).clickable { linked = !linked }
                    .semantics { contentDescription = "link" },
                contentAlignment = Alignment.Center,
            ) { Icon(if (linked) Icons.Filled.Link else Icons.Filled.LinkOff, null, tint = if (linked) KColors.Accent else KColors.TextSoft) }
            ValueBox(value.y, stringResource(R.string.le_height), step, display, suffix, ::setY, onDone)
        }
        Spacer(Modifier.height(6.dp))
        if (linked) {
            TickRuler(value.x, 0f, 40f, 0.01f, ::setX, onDone, Modifier.weight(1f).fillMaxWidth())
        } else {
            TickRuler(value.x, 0f, 40f, 0.01f, ::setX, onDone, Modifier.weight(1f).fillMaxWidth())
            Box(Modifier.fillMaxWidth().height(1.dp).background(KColors.Line))
            TickRuler(value.y, 0f, 40f, 0.01f, ::setY, onDone, Modifier.weight(1f).fillMaxWidth())
        }
    }
}

private fun toolIcon(name: String): ImageVector = when (name) {
    "position" -> Icons.Outlined.OpenWith
    "rotation" -> Icons.Outlined.Rotate90DegreesCw
    "scale" -> Icons.Outlined.OpenInFull
    "skew" -> Icons.Outlined.FormatItalic
    else -> Icons.Outlined.CenterFocusStrong
}

@Composable
private fun toolLabel(name: String): String = stringResource(
    when (name) { "position" -> R.string.le_position; "rotation" -> R.string.le_rotation; "scale" -> R.string.le_scale; "skew" -> R.string.prop_skew; else -> R.string.le_anchor },
)

private fun dragHint(name: String): Int = when (name) {
    "position" -> R.string.le_drag_move
    "rotation" -> R.string.le_drag_rotate
    "scale" -> R.string.le_drag_scale
    else -> R.string.le_drag_anchor
}

/** The value after a drag of ([dx], [dy]) pixels on the pad; [unitsPerPx] maps pad pixels to composition pixels. */
private fun dragged(name: String, v: Any?, dx: Float, dy: Float, unitsPerPx: Float): Any? = when (name) {
    "rotation" -> when (v) {
        is Float -> v + dx * 0.5f
        is Vec3 -> Vec3(v.x + dy * 0.5f, v.y + dx * 0.5f, v.z)
        else -> null
    }
    "scale" -> {
        val d = dx * 0.004f
        when (v) {
            is Vec2 -> { val nx = (v.x + d).coerceAtLeast(0f); Vec2(nx, if (v.x != 0f) v.y * nx / v.x else nx) }
            is Vec3 -> { val nx = (v.x + d).coerceAtLeast(0f); val r = if (v.x != 0f) nx / v.x else 1f; Vec3(nx, v.y * r, v.z * r) }
            else -> null
        }
    }
    else -> when (v) {
        is Vec2 -> Vec2(v.x + dx * unitsPerPx, v.y + dy * unitsPerPx)
        is Vec3 -> Vec3(v.x + dx * unitsPerPx, v.y + dy * unitsPerPx, v.z)
        else -> null
    }
}

// ---------------------------------------------------------------------------------------- edit shape

@Composable
private fun ShapePage(vm: EditorViewModel, layer: Layer, local: Double, onEditCurve: (CurveTarget) -> Unit) {
    val s = layer.shape ?: return
    val id = layer.id
    Spacer(Modifier.height(8.dp))
    val kinds = ShapeKind.entries.filter { it != ShapeKind.PATH || s.kind == ShapeKind.PATH }
    ChoiceChips(kinds.map { it to stringResource(shapeKindRes(it)) }, s.kind, { k -> vm.updateLayer(id) { it.updateShape { c -> c.copy(kind = k) } } })
    if (s.kind == ShapeKind.PATH) PathPointsEditor(vm, id, s.path ?: dev.kinesis.core.model.PathSpec())
    PropList(vm, layer, local, onEditCurve) { it.startsWith("shape.") && !it.startsWith("shape.fill.") && !it.startsWith("shape.stroke.") }
}
