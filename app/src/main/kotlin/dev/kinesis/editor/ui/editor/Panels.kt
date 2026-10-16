package dev.kinesis.editor.ui.editor

import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.filled.Crop54
import androidx.compose.material.icons.filled.DonutLarge
import androidx.compose.material.icons.filled.Hexagon
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Square
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Token
import androidx.compose.material.icons.filled.ViewInAr
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import dev.kinesis.core.anim.Easing
import dev.kinesis.core.anim.EasingPreset
import dev.kinesis.core.model.KnsProject
import dev.kinesis.core.model.Layer
import dev.kinesis.core.model.LayerTree
import dev.kinesis.core.model.LayerType
import dev.kinesis.core.model.LightKind
import dev.kinesis.core.model.Primitive
import dev.kinesis.core.model.ShapeKind
import dev.kinesis.editor.R
import dev.kinesis.editor.media.ExportQuality
import dev.kinesis.editor.media.ExportResolution
import dev.kinesis.editor.media.ExportSettings
import dev.kinesis.editor.ui.common.ChoiceChips
import dev.kinesis.editor.ui.theme.KColors
import dev.kinesis.editor.ui.theme.KDims
import dev.kinesis.editor.ui.theme.KShapes
import dev.kinesis.editor.ui.common.OverlineLabel
import dev.kinesis.editor.ui.common.PillChip
import dev.kinesis.editor.ui.theme.KType
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.material.icons.outlined.Close
import androidx.compose.foundation.layout.heightIn
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

// ---------------------------------------------------------------------------------------- layers / hierarchy

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LayersPanel(vm: EditorViewModel, project: KnsProject, selection: String?, multiSelection: Set<String>, modifier: Modifier = Modifier) {
    val rows = remember(project) {
        val out = ArrayList<Pair<Layer, Int>>()
        fun visit(layers: List<Layer>, depth: Int) {
            for (l in layers.asReversed()) {
                out += l to depth
                if (l.type == LayerType.GROUP) visit(l.layers, depth + 1)
            }
        }
        visit(project.layers, 0)
        out
    }
    if (rows.isEmpty()) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.no_layers_hint), color = KColors.TextSecondary, textAlign = TextAlign.Center, modifier = Modifier.padding(24.dp))
        }
        return
    }
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 4.dp)) {
        items(rows, key = { it.first.id }) { (layer, depth) ->
            val selected = layer.id == selection
            val parentName = layer.parent?.let { pid -> LayerTree.find(project, pid)?.let { layerDisplayName(it) } }
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .background(if (selected) KColors.SurfaceHigh else if (layer.id in multiSelection && multiSelection.size > 1) KColors.AccentSoft else Color.Transparent)
                    .combinedClickable(onClick = { vm.select(layer.id) }, onLongClick = { vm.select(layer.id, additive = true) })
                    .padding(start = (12 + depth * 18).dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(layerIcon(layer), null, tint = layerColor(layer.type), modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(layerDisplayName(layer), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = if (layer.visible) KColors.TextPrimary else KColors.TextSecondary)
                    if (parentName != null) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.AutoMirrored.Filled.ArrowForward, null, tint = KColors.TextSecondary, modifier = Modifier.size(11.dp))
                            Text(" $parentName", fontSize = 11.sp, color = KColors.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                IconButton(onClick = { vm.moveInStack(layer.id, up = true) }, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Filled.KeyboardArrowUp, stringResource(R.string.move_up), tint = KColors.TextSecondary)
                }
                IconButton(onClick = { vm.moveInStack(layer.id, up = false) }, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Filled.KeyboardArrowDown, stringResource(R.string.move_down), tint = KColors.TextSecondary)
                }
                IconButton(onClick = { vm.updateLayer(layer.id) { it.copy(locked = !it.locked) } }, modifier = Modifier.size(32.dp)) {
                    Icon(if (layer.locked) Icons.Filled.Lock else Icons.Filled.LockOpen, stringResource(R.string.lock), tint = KColors.TextSecondary, modifier = Modifier.size(16.dp))
                }
                IconButton(onClick = { vm.updateLayer(layer.id) { it.copy(visible = !it.visible) } }, modifier = Modifier.size(32.dp)) {
                    Icon(if (layer.visible) Icons.Filled.Visibility else Icons.Filled.VisibilityOff, stringResource(R.string.visibility), tint = KColors.TextSecondary, modifier = Modifier.size(16.dp))
                }
            }
        }
    }
}

fun layerIcon(layer: Layer): ImageVector = when (layer.type) {
    LayerType.TEXT -> Icons.Filled.TextFields
    LayerType.SHAPE -> when (layer.shape?.kind) {
        ShapeKind.ELLIPSE -> Icons.Filled.Circle
        ShapeKind.STAR -> Icons.Filled.Star
        ShapeKind.POLYGON -> Icons.Filled.Hexagon
        ShapeKind.PATH -> Icons.Filled.Remove
        else -> Icons.Filled.Square
    }
    LayerType.IMAGE -> Icons.Filled.Image
    LayerType.VIDEO -> Icons.Filled.Movie
    LayerType.AUDIO -> Icons.Filled.Audiotrack
    LayerType.GROUP -> Icons.Filled.Category
    LayerType.OBJECT3D -> Icons.Filled.ViewInAr
    LayerType.MODEL3D -> Icons.Filled.Token
    LayerType.CAMERA -> Icons.Filled.CameraAlt
    LayerType.LIGHT -> Icons.Filled.Lightbulb
    LayerType.NULL -> Icons.Filled.CenterFocusStrong
}

// ---------------------------------------------------------------------------------------- add menu

enum class AddAction {
    TEXT, RECTANGLE, ELLIPSE, POLYGON, STAR, LINE, IMAGE, VIDEO, AUDIO,
    CUBE, SPHERE, CYLINDER, CONE, TORUS, PLANE, TEXT3D, NULL_2D, NULL_3D, MODEL, CAMERA, LIGHT_DIRECTIONAL, LIGHT_POINT, LIGHT_AMBIENT,
}

private class AddItem(val action: AddAction, val label: Int, val icon: ImageVector)

/** Which part of the add menu the toolbar opened. */
enum class AddSection { SHAPES, MEDIA, MODEL3D, SCENE }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddSheet(section: AddSection, onDismiss: () -> Unit, onAdd: (AddAction) -> Unit) {
    val all = listOf(
        R.string.add_basics to listOf(
            AddItem(AddAction.RECTANGLE, R.string.shape_rectangle, Icons.Filled.Crop54),
            AddItem(AddAction.ELLIPSE, R.string.shape_ellipse, Icons.Outlined.Circle),
            AddItem(AddAction.POLYGON, R.string.shape_polygon, Icons.Filled.Hexagon),
            AddItem(AddAction.STAR, R.string.shape_star, Icons.Filled.Star),
            AddItem(AddAction.LINE, R.string.shape_line, Icons.Filled.Remove),
            AddItem(AddAction.NULL_2D, R.string.layer_null, Icons.Filled.CenterFocusStrong),
        ),
        R.string.add_media to listOf(
            AddItem(AddAction.IMAGE, R.string.layer_image, Icons.Filled.Image),
            AddItem(AddAction.VIDEO, R.string.layer_video, Icons.Filled.Movie),
            AddItem(AddAction.AUDIO, R.string.layer_audio, Icons.Filled.Audiotrack),
        ),
        R.string.add_3d to listOf(
            AddItem(AddAction.CUBE, R.string.prim_cube, Icons.Filled.ViewInAr),
            AddItem(AddAction.SPHERE, R.string.prim_sphere, Icons.Filled.Circle),
            AddItem(AddAction.CYLINDER, R.string.prim_cylinder, Icons.Filled.Token),
            AddItem(AddAction.CONE, R.string.prim_cone, Icons.Filled.Category),
            AddItem(AddAction.TORUS, R.string.prim_torus, Icons.Filled.DonutLarge),
            AddItem(AddAction.PLANE, R.string.prim_plane, Icons.Filled.Square),
            AddItem(AddAction.TEXT3D, R.string.prim_text3d, Icons.Filled.TextFields),
            AddItem(AddAction.NULL_3D, R.string.null_3d, Icons.Filled.CenterFocusStrong),
            AddItem(AddAction.MODEL, R.string.layer_model3d, Icons.Filled.Token),
        ),
        R.string.add_scene to listOf(
            AddItem(AddAction.CAMERA, R.string.layer_camera, Icons.Filled.CameraAlt),
            AddItem(AddAction.LIGHT_DIRECTIONAL, R.string.light_directional, Icons.Filled.WbSunny),
            AddItem(AddAction.LIGHT_POINT, R.string.light_point, Icons.Filled.Lightbulb),
            AddItem(AddAction.LIGHT_AMBIENT, R.string.light_ambient, Icons.Filled.LightMode),
        ),
    )
    val groups = listOf(all[section.ordinal])
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = KColors.Sheet, shape = KShapes.Sheet) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(84.dp),
            contentPadding = PaddingValues(16.dp, 0.dp, 16.dp, 32.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            for ((title, items) in groups) {
                item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                    OverlineLabel(stringResource(title).uppercase(), Modifier.padding(top = 8.dp), color = KColors.Accent)
                }
                items(items) { item ->
                    Column(
                        Modifier
                            .clip(KShapes.Tile)
                            .background(KColors.Field)
                            .combinedClickableCompat { onAdd(item.action) }
                            .heightIn(min = KDims.MinTouch)
                            .padding(vertical = 12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Icon(item.icon, null, tint = KColors.TextPrimary)
                        Spacer(Modifier.height(6.dp))
                        Text(stringResource(item.label), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
private fun Modifier.combinedClickableCompat(onClick: () -> Unit): Modifier = this.combinedClickable(onClick = onClick)

fun primitiveFor(action: AddAction): Primitive? = when (action) {
    AddAction.CUBE -> Primitive.CUBE
    AddAction.SPHERE -> Primitive.SPHERE
    AddAction.CYLINDER -> Primitive.CYLINDER
    AddAction.CONE -> Primitive.CONE
    AddAction.TORUS -> Primitive.TORUS
    AddAction.PLANE -> Primitive.PLANE
    else -> null
}

fun lightFor(action: AddAction): LightKind? = when (action) {
    AddAction.LIGHT_DIRECTIONAL -> LightKind.DIRECTIONAL
    AddAction.LIGHT_POINT -> LightKind.POINT
    AddAction.LIGHT_AMBIENT -> LightKind.AMBIENT
    else -> null
}

// ---------------------------------------------------------------------------------------- curve editor

private val CURVE_PRESETS = listOf(
    EasingPreset.LINEAR, EasingPreset.HOLD, EasingPreset.EASE_IN, EasingPreset.EASE_OUT, EasingPreset.EASE_IN_OUT,
    EasingPreset.EASE_IN_CUBIC, EasingPreset.EASE_OUT_CUBIC, EasingPreset.EASE_IN_OUT_CUBIC,
    EasingPreset.EASE_OUT_EXPO, EasingPreset.EASE_IN_OUT_EXPO, EasingPreset.EASE_IN_BACK, EasingPreset.EASE_OUT_BACK,
    EasingPreset.EASE_IN_OUT_BACK, EasingPreset.EASE_OUT_BOUNCE, EasingPreset.EASE_OUT_ELASTIC,
)

/**
 * Bézier curve editor for the segment that starts at a keyframe, shown inline under the property list.
 * Dragging a handle or picking a preset applies the easing; the loop chips set the property's loop mode.
 */
@Composable
fun CurveEditorPanel(
    easing: Easing,
    loop: dev.kinesis.core.anim.LoopMode,
    onEasing: (Easing) -> Unit,
    onLoop: (dev.kinesis.core.anim.LoopMode) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var current by remember(easing) { mutableStateOf(easing) }
    var handles by remember(easing) { mutableStateOf(easing.bezierOrNull() ?: Easing.CubicBezier(0.42f, 0f, 0.58f, 1f)) }
    // The last wave, so switching to Cyclic and back keeps its settings.
    var wave by remember(easing) { mutableStateOf((easing as? Easing.Cyclic) ?: Easing.Cyclic()) }
    val transition = rememberInfiniteTransition(label = "preview")
    val phase by transition.animateFloat(0f, 1f, infiniteRepeatable(tween(1400, easing = LinearEasing), RepeatMode.Restart), label = "phase")
    val latest by rememberUpdatedState(onEasing)

    Column(
        modifier.fillMaxWidth().background(KColors.Card).padding(horizontal = KDims.Gutter, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OverlineLabel(stringResource(R.string.easing), Modifier.weight(1f))
            androidx.compose.material3.IconButton(onClick = onClose, modifier = Modifier.size(KDims.MinTouch)) {
                Icon(androidx.compose.material.icons.Icons.Outlined.Close, stringResource(R.string.close), tint = KColors.TextSoft)
            }
        }
        // y range -0.5..1.5 so overshooting curves fit.
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(150.dp)
                .clip(KShapes.Tile)
                .background(KColors.Background)
                .pointerInput(Unit) {
                    var dragging = 0
                    detectDragGestures(
                        onDragStart = { o ->
                            val w = size.width.toFloat(); val h = size.height.toFloat()
                            fun pos(x: Float, y: Float) = Offset(x * w, h - (y + 0.5f) / 2f * h)
                            val cy = current as? Easing.Cyclic
                            if (cy != null) {
                                // 3 = the crest handle (offset), 4 = the trough handle (number of waves).
                                val top = pos(cy.peak.coerceIn(0f, 1f), 1f)
                                val bottom = pos(cy.trough.coerceIn(0f, 1f), 0f)
                                dragging = if (hypot(o.x - top.x, o.y - top.y) < hypot(o.x - bottom.x, o.y - bottom.y)) 3 else 4
                            } else {
                                val h1 = pos(handles.x1, handles.y1)
                                val h2 = pos(handles.x2, handles.y2)
                                dragging = if (hypot(o.x - h1.x, o.y - h1.y) < hypot(o.x - h2.x, o.y - h2.y)) 1 else 2
                            }
                        },
                        onDragEnd = { latest(current) },
                        onDragCancel = { latest(current) },
                    ) { change, _ ->
                        change.consume()
                        val w = size.width.toFloat(); val h = size.height.toFloat()
                        val x = (change.position.x / w).coerceIn(0f, 1f)
                        val cy = current as? Easing.Cyclic
                        if (cy != null) {
                            wave = if (dragging == 3) {
                                // The first crest follows the finger and the wave keeps its frequency, so the offset moves.
                                val off = x - 0.5f / cy.cycles
                                cy.copy(offset = if (abs(off) < 0.02f) 0f else ((off * 100).roundToInt() / 100f).coerceIn(-1f, 1f))
                            } else {
                                // The trough after the crest ends one full wave: its distance from the start sets the quantity.
                                val span = (x - cy.offset).coerceAtLeast(1f / Easing.Cyclic.MAX_CYCLES)
                                cy.copy(cycles = ((1f / span * 2f).roundToInt() / 2f).coerceIn(Easing.Cyclic.MIN_CYCLES, Easing.Cyclic.MAX_CYCLES))
                            }
                            current = wave
                            return@detectDragGestures
                        }
                        val y = ((h - change.position.y) / h * 2f - 0.5f).coerceIn(-0.5f, 1.5f)
                        handles = if (dragging == 1) handles.copy(x1 = x, y1 = y) else handles.copy(x2 = x, y2 = y)
                        current = handles
                    }
                },
        ) {
            val w = size.width; val h = size.height
            fun pos(x: Float, y: Float) = Offset(x * w, h - (y + 0.5f) / 2f * h)
            // Grid: 0 and 1 levels.
            drawLine(KColors.LineStrong, pos(0f, 0f), pos(1f, 0f))
            drawLine(KColors.LineStrong, pos(0f, 1f), pos(1f, 1f))
            drawLine(KColors.LineStrong, pos(0f, 0f), pos(0f, 1f))
            drawLine(KColors.LineStrong, pos(1f, 0f), pos(1f, 1f))
            val path = Path()
            val cyc = current as? Easing.Cyclic
            val samples = if (cyc != null) (cyc.cycles * 32f).toInt().coerceIn(120, 1600) else 120
            for (i in 0..samples) {
                val u = i / samples.toFloat()
                val p = pos(u, current.transform(u))
                if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
            }
            drawPath(path, KColors.Accent, style = Stroke(3.dp.toPx()))
            if (cyc != null) {
                // Top handle: first crest (offset). Bottom handle: the trough after it (quantity of waves).
                val top = pos(cyc.peak.coerceIn(0f, 1f), 1f)
                val bottom = pos(cyc.trough.coerceIn(0f, 1f), 0f)
                drawLine(KColors.Keyframe.copy(alpha = 0.5f), top, pos(top.x / w, 0f), strokeWidth = 2f)
                drawCircle(KColors.Keyframe, 9.dp.toPx(), top)
                drawCircle(KColors.Keyframe, 9.dp.toPx(), bottom)
            }
            val hb = current.bezierOrNull()
            if (hb != null) {
                drawLine(KColors.Keyframe, pos(0f, 0f), pos(hb.x1, hb.y1), strokeWidth = 2f)
                drawLine(KColors.Keyframe, pos(1f, 1f), pos(hb.x2, hb.y2), strokeWidth = 2f)
                drawCircle(KColors.Keyframe, 8.dp.toPx(), pos(hb.x1, hb.y1))
                drawCircle(KColors.Keyframe, 8.dp.toPx(), pos(hb.x2, hb.y2))
            }
            // Preview dot
            val v = current.transform(phase)
            drawCircle(KColors.White, 6.dp.toPx(), pos(phase, v))
            drawCircle(KColors.Teal, 7.dp.toPx(), Offset(w - 10.dp.toPx(), pos(0f, v).y))
        }
        androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            item {
                PillChip(
                    stringResource(R.string.easing_cyclic),
                    selected = current is Easing.Cyclic,
                    onClick = {
                        current = wave
                        onEasing(wave)
                    },
                )
            }
            items(CURVE_PRESETS) { p ->
                PillChip(
                    easingLabel(p),
                    selected = (current as? Easing.Preset)?.preset == p,
                    onClick = {
                        val e = Easing.Preset(p)
                        current = e
                        p.bezier?.let { handles = it }
                        onEasing(e)
                    },
                )
            }
        }
        val b = current.bezierOrNull()
        val cyclic = current as? Easing.Cyclic
        Text(
            when {
                cyclic != null -> stringResource(R.string.cyclic_info, fmt(cyclic.offset), fmt(cyclic.cycles))
                b != null -> "cubic-bezier(${fmt(b.x1)}, ${fmt(b.y1)}, ${fmt(b.x2)}, ${fmt(b.y2)})"
                else -> (current as? Easing.Preset)?.preset?.id ?: ""
            },
            style = KType.Mono, color = KColors.TextSoft,
        )
        if (cyclic != null) Text(stringResource(R.string.cyclic_hint), fontSize = 12.sp, color = KColors.TextSecondary)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OverlineLabel(stringResource(R.string.loop), Modifier.padding(end = 6.dp))
            PillChip(stringResource(R.string.loop_none), loop == dev.kinesis.core.anim.LoopMode.NONE, { onLoop(dev.kinesis.core.anim.LoopMode.NONE) })
            PillChip(stringResource(R.string.loop_cycle), loop == dev.kinesis.core.anim.LoopMode.CYCLE, { onLoop(dev.kinesis.core.anim.LoopMode.CYCLE) })
            PillChip(stringResource(R.string.loop_pingpong), loop == dev.kinesis.core.anim.LoopMode.PINGPONG, { onLoop(dev.kinesis.core.anim.LoopMode.PINGPONG) })
        }
    }
}

private fun fmt(v: Float) = ((v * 100).roundToInt() / 100f).toString()

// ---------------------------------------------------------------------------------------- export

@Composable
fun ExportDialog(vm: EditorViewModel, project: KnsProject, state: ExportUiState, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var resolution by remember { mutableStateOf(ExportResolution.ORIGINAL) }
    var quality by remember { mutableStateOf(ExportQuality.HIGH) }
    var audio by remember { mutableStateOf(true) }
    val hasAudio = remember(project) { dev.kinesis.core.scene.AudioPlan.clips(project).isNotEmpty() }
    AlertDialog(
        onDismissRequest = { if (state !is ExportUiState.Running) onDismiss() },
        title = { Text(stringResource(R.string.export_video)) },
        confirmButton = {
            when (state) {
                is ExportUiState.Idle -> Button(onClick = { vm.startExport(ExportSettings(resolution, quality, audio && hasAudio)) }) { Text(stringResource(R.string.export)) }
                is ExportUiState.Running -> TextButton(onClick = { vm.cancelExport() }) { Text(stringResource(R.string.cancel)) }
                is ExportUiState.Done -> Button(onClick = {
                    val uri = state.galleryUri ?: FileProvider.getUriForFile(context, context.packageName + ".files", state.file)
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "video/mp4"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    try {
                        context.startActivity(Intent.createChooser(send, null))
                    } catch (e: ActivityNotFoundException) {
                        // Nothing can share video; the file is still saved.
                    }
                }) { Text(stringResource(R.string.share)) }
                is ExportUiState.Failed -> TextButton(onClick = { vm.dismissExport() }) { Text(stringResource(R.string.try_again)) }
            }
        },
        dismissButton = {
            if (state !is ExportUiState.Running) TextButton(onClick = { vm.dismissExport(); onDismiss() }) { Text(stringResource(R.string.close)) }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                when (state) {
                    is ExportUiState.Idle -> {
                        val comp = project.composition
                        Text(stringResource(R.string.resolution), style = MaterialTheme.typography.labelMedium, color = KColors.TextSecondary)
                        ChoiceChips(
                            listOf(
                                ExportResolution.ORIGINAL to "${comp.width}×${comp.height}",
                                ExportResolution.P720 to "720p", ExportResolution.P1080 to "1080p", ExportResolution.P2160 to "4K",
                            ),
                            resolution, { resolution = it },
                        )
                        Text(stringResource(R.string.quality), style = MaterialTheme.typography.labelMedium, color = KColors.TextSecondary)
                        ChoiceChips(
                            listOf(ExportQuality.STANDARD to stringResource(R.string.quality_standard), ExportQuality.HIGH to stringResource(R.string.quality_high), ExportQuality.MAXIMUM to stringResource(R.string.quality_max)),
                            quality, { quality = it },
                        )
                        if (hasAudio) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(stringResource(R.string.include_audio), modifier = Modifier.weight(1f))
                                Switch(checked = audio, onCheckedChange = { audio = it })
                            }
                        }
                        Text(stringResource(R.string.export_info, "%.1f".format(comp.duration), comp.fps.roundToInt()), fontSize = 12.sp, color = KColors.TextSecondary)
                    }
                    is ExportUiState.Running -> {
                        Text(stringResource(R.string.exporting))
                        LinearProgressIndicator(progress = { state.progress }, modifier = Modifier.fillMaxWidth())
                        Text("${(state.progress * 100).roundToInt()}%", color = KColors.TextSecondary)
                    }
                    is ExportUiState.Done -> {
                        Text(stringResource(R.string.export_done))
                        Text(
                            if (state.galleryUri != null) stringResource(R.string.export_saved_gallery) else stringResource(R.string.export_saved_file, state.file.path),
                            fontSize = 12.sp, color = KColors.TextSecondary,
                        )
                    }
                    is ExportUiState.Failed -> {
                        Text(stringResource(R.string.export_failed), color = KColors.Danger)
                        Text(state.message, fontSize = 12.sp, color = KColors.TextSecondary)
                    }
                }
            }
        },
    )
}
