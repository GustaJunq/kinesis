package dev.kinesis.editor.ui.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.zIndex
import dev.kinesis.editor.ui.common.Lucide
import dev.kinesis.editor.ui.theme.KType
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FitScreen
import androidx.compose.material.icons.outlined.Label
import androidx.compose.material.icons.outlined.LabelOff
import androidx.compose.ui.platform.LocalContext
import dev.kinesis.editor.ui.settings.AppSettings
import androidx.compose.material.icons.outlined.BookmarkAdd
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.runtime.mutableStateOf
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.kinesis.core.edit.Markers
import dev.kinesis.core.edit.TimelineOps
import dev.kinesis.core.edit.TimelineScale
import dev.kinesis.core.edit.keyframeTimes
import dev.kinesis.core.model.KnsProject
import dev.kinesis.core.model.Layer
import dev.kinesis.core.model.LayerTree
import dev.kinesis.core.model.LayerType
import dev.kinesis.core.model.Marker
import dev.kinesis.editor.ui.common.formatTime
import dev.kinesis.editor.R
import dev.kinesis.editor.ui.theme.KBrushes
import dev.kinesis.editor.ui.theme.KColors
import dev.kinesis.editor.ui.theme.KDims
import dev.kinesis.editor.ui.theme.KShapes
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.stringResource
import kotlin.math.abs
import kotlin.math.roundToInt

/** A visible timeline row: a layer, its nesting depth and the absolute start of its container. */
private data class Row(val layer: Layer, val depth: Int, val containerOffset: Double, val containerId: String?)

/** Space left and right of the timeline: the lanes and the controls above them share it. */
val TIMELINE_SIDE = 8.dp
private val ROW_HEIGHT = 48.dp
private val RULER_HEIGHT = 46.dp
private val LANE_RADIUS = 10.dp
/** The fixed column at the left of every row: visibility and the layer's type. */
private val HEAD_WIDTH = 72.dp
/** The ≡ handle at the right end of every lane. */
private val GRIP_WIDTH = 28.dp

fun layerColor(type: LayerType): Color = when (type) {
    LayerType.TEXT -> KColors.ClipTextStart
    LayerType.SHAPE -> KColors.ClipPink
    LayerType.IMAGE -> KColors.ClipAmber
    LayerType.VIDEO -> KColors.ClipViolet
    LayerType.AUDIO -> KColors.ClipTeal
    LayerType.GROUP -> KColors.ClipTextSecondary
    LayerType.OBJECT3D, LayerType.MODEL3D -> KColors.ClipTextSecondary
    LayerType.CAMERA, LayerType.LIGHT -> KColors.ClipViolet
    LayerType.NULL -> KColors.ClipTeal
}

/** Clip colour by kind: text, shapes, pictures and video, sound (with its waveform), groups, 3D and scene objects. */
fun clipColor(type: LayerType): Color = when (type) {
    LayerType.TEXT -> KColors.ClipText
    LayerType.SHAPE, LayerType.OBJECT3D, LayerType.MODEL3D -> KColors.ClipShape
    LayerType.IMAGE, LayerType.VIDEO -> KColors.ClipMedia
    LayerType.AUDIO -> KColors.ClipSound
    LayerType.GROUP -> KColors.ClipGroup
    LayerType.CAMERA, LayerType.LIGHT, LayerType.NULL -> KColors.ClipScene
}

/** Text on top of a clip of this type. */
private fun onClipColor(@Suppress("UNUSED_PARAMETER") type: LayerType): Color = KColors.White

/** Timecode of the ruler: minutes, seconds and frames ("00:01:12"). */
fun formatTimecode(seconds: Double, fps: Double): String {
    val s = seconds.coerceAtLeast(0.0)
    val whole = s.toInt()
    val frame = ((s - whole) * fps).toInt().coerceIn(0, (fps.roundToInt() - 1).coerceAtLeast(0))
    return String.format(java.util.Locale.US, "%02d:%02d:%02d", whole / 60, whole % 60, frame)
}

/**
 * The timeline: the playhead stays in the middle of the lanes and the time runs under it. Dragging the ruler or a lane
 * sideways scrubs; a pinch zooms the time axis. Every row has a fixed head (visibility and the type of the layer, the
 * arrow of a group) and a ≡ handle at the right that moves the layer up or down.
 */
@Composable
fun Timeline(
    vm: EditorViewModel,
    project: KnsProject,
    time: Double,
    @Suppress("UNUSED_PARAMETER") playing: Boolean,
    selection: String?,
    multiSelection: Set<String>,
    /** Opens the marker editor (a tap on the ruler's flag or a long press). */
    onEditMarker: (String) -> Unit,
    /** A tap on a clip: the layer is selected and its properties open. */
    onClipTap: (String) -> Unit = { vm.select(it) },
    /** The layers list (rename, lock, parent...). */
    onOpenLayers: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    var pxPerSecond by remember { mutableFloatStateOf(with(density) { 96.dp.toPx() }) }
    val expanded = remember { mutableStateMapOf<String, Boolean>() }
    val duration = project.composition.duration
    val fps = project.composition.fps
    val audioRevision by vm.audioRevision.collectAsState()
    val markerHitPx = with(density) { 16.dp.toPx() }

    val rows = remember(project, expanded.toMap()) {
        val out = ArrayList<Row>()
        fun visit(layers: List<Layer>, depth: Int, offset: Double, container: String?) {
            for (l in layers.asReversed()) {
                out += Row(l, depth, offset, container)
                if (l.type == LayerType.GROUP && expanded[l.id] == true) visit(l.layers, depth + 1, offset + l.start, l.id)
            }
        }
        visit(project.layers, 0, 0.0, null)
        out
    }

    BoxWithConstraints(modifier) {
        val sidePx = with(density) { TIMELINE_SIDE.toPx() }
        val headPx = with(density) { HEAD_WIDTH.toPx() }
        val trackWidthPx = with(density) { (maxWidth - TIMELINE_SIDE * 2 - HEAD_WIDTH).toPx() }.coerceAtLeast(1f)
        // The playhead is fixed in the middle of the lanes: the scroll follows the time.
        val scale = TimelineScale(pxPerSecond, (time * pxPerSecond).toFloat() - trackWidthPx / 2f)
        val currentPx by rememberUpdatedState(pxPerSecond)
        val scrub: (Float) -> Unit = { dx ->
            if (vm.playing.value) vm.pause()
            vm.seek(vm.time.value - dx / currentPx)
        }

        Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = TIMELINE_SIDE)
                .pointerInput(Unit) {
                    // Two-finger pinch zooms the time axis around the playhead.
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        while (true) {
                            val event = awaitPointerEvent()
                            if (event.changes.none { it.pressed }) break
                            if (event.changes.count { it.pressed } >= 2) {
                                val zoom = event.calculateZoom()
                                if (zoom != 1f) pxPerSecond = (pxPerSecond * zoom).coerceIn(TimelineScale.MIN_PX, TimelineScale.MAX_PX)
                                event.changes.forEach { it.consume() }
                            }
                        }
                    }
                },
        ) {
            Row(Modifier.fillMaxWidth().height(RULER_HEIGHT)) {
                // Corner above the heads: the layers list.
                Box(Modifier.width(HEAD_WIDTH).fillMaxHeight(), contentAlignment = Alignment.Center) {
                    IconButton(onClick = onOpenLayers, modifier = Modifier.size(40.dp)) {
                        Icon(Lucide.Layers, stringResource(R.string.tool_layers), tint = KColors.TextSecondary, modifier = Modifier.size(20.dp))
                    }
                }
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    Ruler(
                        duration = duration, scale = scale, fps = fps, markers = project.markers,
                        markerHitPx = markerHitPx,
                        onSeek = { if (vm.playing.value) vm.pause(); vm.seek(it) },
                        onScrub = scrub,
                        onAddMarker = { vm.addMarker(it) },
                        onEditMarker = onEditMarker,
                        modifier = Modifier.fillMaxSize(),
                    )
                    // Fixed timecode over the playhead.
                    Text(
                        formatTimecode(time, fps), style = KType.MonoLarge, color = KColors.TextPrimary,
                        modifier = Modifier.align(Alignment.TopCenter).padding(top = 2.dp)
                            .clip(RoundedCornerShape(6.dp)).background(KColors.SurfaceAlt).padding(horizontal = 8.dp, vertical = 2.dp),
                    )
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(rows, key = { it.layer.id }) { row ->
                        LayerRow(
                            vm = vm,
                            project = project,
                            row = row,
                            selected = row.layer.id == selection,
                            inMulti = row.layer.id in multiSelection && multiSelection.size > 1,
                            expanded = expanded[row.layer.id] == true,
                            onToggleExpand = { expanded[row.layer.id] = !(expanded[row.layer.id] ?: false) },
                            scale = scale,
                            trackWidthPx = trackWidthPx,
                            audioRevision = audioRevision,
                            onScrub = scrub,
                            onClipTap = onClipTap,
                        )
                    }
                    item { Spacer(Modifier.height(48.dp)) }
                }
            }
        }
        // Playhead in the middle of the lanes; markers as thin guides down the lanes.
        Canvas(Modifier.fillMaxSize()) {
            val laneLeft = sidePx + headPx
            val laneRight = laneLeft + trackWidthPx
            for (m in project.markers) {
                val mx = laneLeft + scale.xOf(m.time)
                if (mx in laneLeft..laneRight) {
                    drawLine(markerColor(m).copy(alpha = 0.5f), Offset(mx, RULER_HEIGHT.toPx()), Offset(mx, size.height), strokeWidth = 1.dp.toPx())
                }
            }
            val x = laneLeft + trackWidthPx / 2f
            drawLine(KColors.Playhead, Offset(x, 20.dp.toPx()), Offset(x, size.height), strokeWidth = 2.dp.toPx())
            drawCircle(KColors.Playhead, 4.dp.toPx(), Offset(x, 26.dp.toPx()))
        }
    }
}

fun markerColor(m: Marker): Color = m.color?.let { runCatching { Color(android.graphics.Color.parseColor(it)) }.getOrNull() } ?: KColors.Primary

private val MARKER_COLORS = listOf("#FFC857", "#FF6B8B", "#4FD1C5", "#5EA8FF", "#9BE564", "#B8A0FF")

/** Rename, recolour, move to the playhead or delete a marker. */
@Composable
internal fun MarkerDialog(vm: EditorViewModel, marker: Marker, fps: Double, playhead: Double, onDismiss: () -> Unit) {
    var name by remember(marker.id) { mutableStateOf(marker.name) }
    AlertDialog(
        onDismissRequest = { vm.renameMarker(marker.id, name); onDismiss() },
        title = { Text(stringResource(R.string.marker)) },
        text = {
            Column(verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true, label = { Text(stringResource(R.string.marker_name)) })
                Text(formatTime(marker.time, fps), style = MaterialTheme.typography.labelMedium, color = KColors.TextSecondary)
                Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
                    for (c in MARKER_COLORS) {
                        val col = Color(android.graphics.Color.parseColor(c))
                        Box(
                            Modifier.size(28.dp).clip(RoundedCornerShape(8.dp)).background(col)
                                .border(if (marker.color == c) 2.dp else 0.dp, KColors.Selection, RoundedCornerShape(8.dp))
                                .pointerInput(c) { detectTapGestures { vm.recolorMarker(marker.id, c) } },
                        )
                    }
                }
                TextButton(onClick = { vm.renameMarker(marker.id, name); vm.moveMarker(marker.id, playhead) }) { Text(stringResource(R.string.marker_move_here)) }
            }
        },
        confirmButton = { TextButton(onClick = { vm.renameMarker(marker.id, name); onDismiss() }) { Text(stringResource(R.string.ok)) } },
        dismissButton = { TextButton(onClick = { vm.removeMarker(marker.id); onDismiss() }) { Text(stringResource(R.string.delete), color = KColors.Danger) } },
    )
}

@Composable
private fun Ruler(
    duration: Double,
    scale: TimelineScale,
    fps: Double,
    markers: List<Marker>,
    markerHitPx: Float,
    onSeek: (Double) -> Unit,
    onScrub: (Float) -> Unit,
    onAddMarker: (Double) -> Unit,
    onEditMarker: (String) -> Unit,
    modifier: Modifier,
) {
    val seek by rememberUpdatedState(onSeek)
    val scrub by rememberUpdatedState(onScrub)
    val addMarker by rememberUpdatedState(onAddMarker)
    val editMarker by rememberUpdatedState(onEditMarker)
    val currentScale by rememberUpdatedState(scale)
    val currentMarkers by rememberUpdatedState(markers)
    val density = LocalDensity.current
    fun markerAt(x: Float): Marker? =
        currentMarkers.filter { abs(currentScale.xOf(it.time) - x) <= markerHitPx }.minByOrNull { abs(currentScale.xOf(it.time) - x) }
    Canvas(
        modifier
            .clipToBounds()
            .pointerInput(Unit) {
                detectTapGestures(
                    // Tapping a marker goes to it; elsewhere it seeks. A long press edits a marker or adds one.
                    onTap = { pos -> markerAt(pos.x)?.let { seek(it.time) } ?: seek(currentScale.timeAt(pos.x)) },
                    onLongPress = { pos ->
                        val m = markerAt(pos.x)
                        if (m != null) editMarker(m.id) else addMarker(currentScale.timeAt(pos.x).coerceIn(0.0, duration))
                    },
                )
            }
            .pointerInput(Unit) {
                // The playhead stays put: dragging the ruler moves the time under it.
                detectHorizontalDragGestures { change, dx ->
                    change.consume()
                    scrub(dx)
                }
            },
    ) {
        val pxPerSecond = scale.pxPerSecond
        val scrollPx = scale.scrollPx
        val minGap = with(density) { 56.dp.toPx() }
        val steps = listOf(1.0 / fps, 0.1, 0.25, 0.5, 1.0, 2.0, 5.0, 10.0, 15.0, 30.0, 60.0, 120.0)
        val major = steps.firstOrNull { it * pxPerSecond >= minGap } ?: 300.0
        val minor = major / 4
        val paint = android.graphics.Paint().apply {
            color = KColors.TextSecondary.toArgb()
            typeface = android.graphics.Typeface.MONOSPACE
            textSize = 10.sp.toPx()
            isAntiAlias = true
        }
        var t = (scrollPx / pxPerSecond / minor).toInt().coerceAtLeast(0) * minor
        while (t <= duration + 1e-9) {
            val x = scale.xOf(t)
            if (x > size.width) break
            val isMajor = abs(t / major - (t / major).roundToInt()) < 1e-6
            drawLine(
                KColors.LineStrong, Offset(x, size.height), Offset(x, size.height - if (isMajor) 12.dp.toPx() else 6.dp.toPx()),
                strokeWidth = 1f,
            )
            if (isMajor) drawContext.canvas.nativeCanvas.drawText(secondsLabel(t), x + 3.dp.toPx(), size.height - 14.dp.toPx(), paint)
            t += minor
        }
        val startX = scale.xOf(0.0)
        if (startX > 0f) drawLine(KColors.LineStrong, Offset(startX, size.height * 0.4f), Offset(startX, size.height), strokeWidth = 2f)
        val endX = scale.xOf(duration)
        drawLine(KColors.Danger.copy(alpha = 0.6f), Offset(endX, size.height * 0.4f), Offset(endX, size.height), strokeWidth = 2f)
        // Markers: a flag on the ruler (with its name when there is room) and a line down the ruler.
        val flag = android.graphics.Paint().apply {
            color = KColors.Background.toArgb()
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            textSize = 9.sp.toPx()
            isAntiAlias = true
        }
        for (m in markers) {
            val x = scale.xOf(m.time)
            if (x < -markerHitPx || x > size.width + markerHitPx) continue
            val col = markerColor(m)
            val top = size.height * 0.45f
            drawLine(col, Offset(x, top), Offset(x, size.height), strokeWidth = 1.5.dp.toPx())
            val label = m.name.take(12)
            val labelW = if (label.isEmpty()) 0f else flag.measureText(label) + 8.dp.toPx()
            drawRoundRect(col, Offset(x, top), androidx.compose.ui.geometry.Size(maxOf(labelW, 9.dp.toPx()), 12.dp.toPx()), androidx.compose.ui.geometry.CornerRadius(3.dp.toPx()))
            if (label.isNotEmpty()) drawContext.canvas.nativeCanvas.drawText(label, x + 4.dp.toPx(), top + 9.5.dp.toPx(), flag)
        }
    }
}

@Composable
private fun LayerRow(
    vm: EditorViewModel,
    project: KnsProject,
    row: Row,
    selected: Boolean,
    inMulti: Boolean,
    expanded: Boolean,
    onToggleExpand: () -> Unit,
    scale: TimelineScale,
    trackWidthPx: Float,
    audioRevision: Int,
    onScrub: (Float) -> Unit,
    onClipTap: (String) -> Unit,
) {
    val layer = row.layer
    val density = LocalDensity.current
    val scrub by rememberUpdatedState(onScrub)
    val rowPx = with(density) { ROW_HEIGHT.toPx() }
    var dragY by remember(layer.id) { mutableFloatStateOf(0f) }
    Row(
        Modifier
            .fillMaxWidth()
            .height(ROW_HEIGHT)
            .zIndex(if (dragY != 0f) 1f else 0f)
            .offset { IntOffset(0, dragY.roundToInt()) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Head: visibility, then the type of the layer (a group shows its arrow).
        Row(
            Modifier.width(HEAD_WIDTH).fillMaxHeight().padding(vertical = 2.dp, horizontal = 2.dp)
                .clip(RoundedCornerShape(topStart = LANE_RADIUS, bottomStart = LANE_RADIUS))
                .background(if (selected) KColors.Primary.copy(alpha = 0.22f) else KColors.Lane)
                .padding(start = (row.depth * 4).coerceAtMost(12).dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            val vis = stringResource(if (layer.visible) R.string.hide_layer else R.string.show_layer)
            Icon(
                if (layer.visible) Icons.Outlined.Visibility else Icons.Outlined.VisibilityOff, vis,
                tint = if (layer.visible) KColors.TextPrimary else KColors.Disabled,
                modifier = Modifier.size(28.dp).clip(CircleShape)
                    .clickable(role = androidx.compose.ui.semantics.Role.Button, onClickLabel = vis) { vm.updateLayer(layer.id) { it.copy(visible = !it.visible) } }
                    .padding(4.dp),
            )
            if (layer.type == LayerType.GROUP) {
                Icon(
                    if (expanded) Icons.Filled.ExpandMore else Icons.Filled.ChevronRight,
                    stringResource(if (expanded) R.string.collapse_group else R.string.expand_group),
                    tint = KColors.TextPrimary,
                    modifier = Modifier.size(28.dp).clip(CircleShape).clickable(onClick = onToggleExpand).padding(2.dp),
                )
            } else {
                Icon(layerIcon(layer), null, tint = clipColor(layer.type).copy(alpha = 1f).let { if (layer.visible) KColors.TextSoft else KColors.Disabled }, modifier = Modifier.size(20.dp))
            }
        }
        // Lane: the clip at its real time; dragging the empty lane scrubs.
        Box(
            Modifier
                .weight(1f)
                .fillMaxHeight()
                .padding(vertical = 2.dp)
                .clip(RoundedCornerShape(topEnd = LANE_RADIUS, bottomEnd = LANE_RADIUS))
                .background(KColors.Lane)
                .clipToBounds()
                .pointerInput(layer.id) {
                    detectHorizontalDragGestures { change, dx ->
                        change.consume()
                        scrub(dx)
                    }
                }
                .pointerInput(layer.id) { detectTapGestures(onTap = { onClipTap(layer.id) }, onLongPress = { vm.select(layer.id, additive = true) }) },
        ) {
            ClipView(vm, project, row, selected, inMulti, scale, trackWidthPx, audioRevision, onClipTap)
            // ≡ handle: drag up or down to move the layer among its siblings.
            val grip = stringResource(R.string.reorder_layer)
            Box(
                Modifier.align(Alignment.CenterEnd).width(GRIP_WIDTH).fillMaxHeight()
                    .background(KColors.Lane.copy(alpha = 0.85f))
                    .semantics { contentDescription = grip }
                    .pointerInput(layer.id) {
                        detectVerticalDragGestures(
                            onDragStart = { dragY = 0f },
                            onDragEnd = {
                                val steps = (dragY / rowPx).roundToInt()
                                dragY = 0f
                                if (steps != 0) {
                                    val loc = LayerTree.locate(vm.current, layer.id)
                                    // Rows list the top layer first: moving down the list lowers the layer in the stack.
                                    if (loc != null) vm.reorder(layer.id, loc.index - steps)
                                }
                            },
                            onDragCancel = { dragY = 0f },
                        ) { change, dy ->
                            change.consume()
                            dragY += dy
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.DragHandle, null, tint = KColors.TextSecondary, modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
private fun ClipView(
    vm: EditorViewModel,
    project: KnsProject,
    row: Row,
    selected: Boolean,
    inMulti: Boolean,
    scale: TimelineScale,
    trackWidthPx: Float,
    audioRevision: Int,
    onClipTap: (String) -> Unit,
) {
    val layer = row.layer
    val density = LocalDensity.current
    val absStart = row.containerOffset + layer.start
    // The clip keeps its real length at every zoom (duration × px/s); only the part inside the track is laid out, so a
    // clip longer than the screen is never squeezed by the track's own width.
    val span = scale.span(absStart, layer.duration, trackWidthPx, minWidth = with(density) { 6.dp.toPx() })
    if (!span.visible) return
    val handleW = with(density) { 14.dp.toPx() }
    val px by rememberUpdatedState(scale.pxPerSecond)
    val cutStart by rememberUpdatedState(span.cutStart)
    val cutEnd by rememberUpdatedState(span.cutEnd)
    val color = clipColor(layer.type)
    val comp = project.composition
    val radius = KDims.ClipRadius
    val shape = RoundedCornerShape(
        topStart = if (span.cutStart) 0.dp else radius, bottomStart = if (span.cutStart) 0.dp else radius,
        topEnd = if (span.cutEnd) 0.dp else radius, bottomEnd = if (span.cutEnd) 0.dp else radius,
    )

    fun snap(t: Double, exclude: String): Double {
        val local = comp.snap(t + row.containerOffset) - row.containerOffset
        val tolerance = with(density) { 8.dp.toPx() } / px
        val candidates = TimelineOps.snapPoints(vm.current, exclude).map { it - row.containerOffset } + (vm.time.value - row.containerOffset)
        return candidates.minByOrNull { abs(it - t) }?.takeIf { abs(it - t) <= tolerance } ?: local
    }

    Box(
        Modifier
            .offset { IntOffset(span.left.roundToInt(), with(density) { 4.dp.roundToPx() }) }
            .width(with(density) { span.width.toDp() })
            .height(ROW_HEIGHT - 12.dp)
            .clip(shape)
            .background(SolidColor(color), alpha = if (layer.visible) 1f else 0.3f)
            .border(
                width = if (selected || inMulti) 2.dp else 0.dp,
                color = if (selected || inMulti) KColors.Primary else Color.Transparent,
                shape = shape,
            )
            .pointerInput(layer.id) { detectTapGestures(onTap = { onClipTap(layer.id) }, onLongPress = { vm.select(layer.id, additive = true) }) }
            .pointerInput(layer.id, selected) {
                var startStart = 0.0
                var accum = 0f
                var key = ""
                var mode = 0 // 0 move, 1 trim start, 2 trim end
                var startDuration = 0.0
                detectDragGestures(
                    onDragStart = { pos ->
                        if (vm.selection.value != layer.id) vm.select(layer.id)
                        val current = LayerTree.find(vm.current, layer.id) ?: return@detectDragGestures
                        startStart = current.start
                        startDuration = current.duration
                        accum = 0f
                        key = "clip:${layer.id}:${System.nanoTime()}"
                        // The handles are the real ends of the clip: when an end is off-screen it cannot be grabbed.
                        mode = when {
                            current.locked -> -1
                            !cutStart && pos.x < handleW -> 1
                            !cutEnd && pos.x > size.width - handleW -> 2
                            else -> 0
                        }
                    },
                    onDragEnd = { vm.endGesture() },
                    onDragCancel = { vm.endGesture() },
                ) { change, drag ->
                    change.consume()
                    if (mode < 0) return@detectDragGestures
                    accum += drag.x
                    val dt = accum / px
                    when (mode) {
                        0 -> vm.moveLayerTime(layer.id, snap(startStart + dt, layer.id), key)
                        1 -> {
                            val target = snap(startStart + dt, layer.id)
                            val current = LayerTree.find(vm.current, layer.id) ?: return@detectDragGestures
                            vm.trimLayerStart(layer.id, target - current.start, key)
                        }
                        2 -> {
                            val end = snap(startStart + startDuration + dt, layer.id)
                            vm.trimLayerEnd(layer.id, end - startStart, key)
                        }
                    }
                }
            },
    ) {
        val media = layer.media
        val pcm = remember(media?.asset, audioRevision) { media?.let { vm.pcmOf(it.asset) } }
        if (media != null && pcm != null && !media.muted) {
            Waveform(pcm, media.sourceStart, media.speed, scale.pxPerSecond, shift = span.shift)
        }
        Row(Modifier.align(Alignment.CenterStart).padding(start = 12.dp, end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            if (AppSettings.showLayerNames) {
                Text(
                    layerDisplayName(layer), style = MaterialTheme.typography.labelSmall, color = onClipColor(layer.type), maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            if (layer.locked) {
                Icon(Icons.Outlined.Lock, stringResource(R.string.layer_locked), tint = onClipColor(layer.type), modifier = Modifier.padding(start = 4.dp).size(12.dp))
            }
        }
        if (selected) {
            // Trim handles, only at the ends that are on screen
            if (!span.cutStart) Box(Modifier.align(Alignment.CenterStart).padding(start = 2.dp).width(4.dp).height(18.dp).clip(KShapes.Pill).background(KColors.Selection))
            if (!span.cutEnd) Box(Modifier.align(Alignment.CenterEnd).padding(end = 2.dp).width(4.dp).height(18.dp).clip(KShapes.Pill).background(KColors.Selection))
        }
        // Keyframes of every property of the layer, placed with the same mapping as the clip and the ruler.
        val reach = with(density) { 14.dp.toPx() }
        for (kt in layer.keyframeTimes()) {
            if (kt < -1e-6 || kt > layer.duration + 1e-6) continue
            val kx = (kt * scale.pxPerSecond).toFloat() - span.shift
            if (kx < -reach || kx > span.width + reach) continue
            KeyframeDiamond(vm, layer, kt, x = kx, pxPerSecond = scale.pxPerSecond, absLayerStart = absStart)
        }
    }
}

/** Peak envelope of decoded audio for the visible part of a clip ([shift] px of the clip are off-screen to the left). */
@Composable
private fun Waveform(pcm: java.nio.ShortBuffer, sourceStart: Double, speed: Double, pxPerSecond: Float, shift: Float) {
    Canvas(Modifier.fillMaxSize()) {
        val total = pcm.limit() / 2
        if (total <= 0) return@Canvas
        val mid = size.height / 2
        val to = size.width.toInt()
        val color = KColors.White.copy(alpha = 0.35f)
        for (x in 0 until to step 2) {
            val clipX = x + shift
            val t0 = sourceStart + clipX / pxPerSecond * speed
            val t1 = sourceStart + (clipX + 2) / pxPerSecond * speed
            val f0 = (t0 * dev.kinesis.editor.media.MIX_RATE).toInt().coerceIn(0, total - 1)
            val f1 = (t1 * dev.kinesis.editor.media.MIX_RATE).toInt().coerceIn(f0 + 1, total)
            val step = ((f1 - f0) / 48).coerceAtLeast(1)
            var peak = 0
            var f = f0
            while (f < f1) {
                val v = kotlin.math.abs(pcm.get(f * 2).toInt())
                if (v > peak) peak = v
                f += step
            }
            val h = peak / 32768f * mid
            if (h > 0.5f) drawLine(color, Offset(x.toFloat(), mid - h), Offset(x.toFloat(), mid + h), strokeWidth = 1.5f)
        }
    }
}

@Composable
private fun KeyframeDiamond(vm: EditorViewModel, layer: Layer, localTime: Double, x: Float, pxPerSecond: Float, absLayerStart: Double) {
    val density = LocalDensity.current
    val left = x - with(density) { 7.dp.toPx() }
    val px by rememberUpdatedState(pxPerSecond)
    Box(
        Modifier
            .offset { IntOffset(left.roundToInt(), with(density) { 11.dp.roundToPx() }) }
            .size(14.dp)
            .pointerInput(layer.id, localTime) {
                var from = localTime
                var accum = 0f
                var key = ""
                detectDragGestures(
                    onDragStart = { from = localTime; accum = 0f; key = "kf:${layer.id}:${System.nanoTime()}" },
                    onDragEnd = { vm.endGesture() },
                    onDragCancel = { vm.endGesture() },
                ) { change, drag ->
                    change.consume()
                    accum += drag.x
                    val comp = vm.current.composition
                    val to = comp.snap(localTime + accum / px + absLayerStart) - absLayerStart
                    if (abs(to - from) > 1e-6) {
                        vm.moveKeyframesAt(layer.id, from, to, key)
                        from = to
                    }
                }
            }
            .pointerInput(layer.id, localTime) { detectTapGestures { vm.seek(absLayerStart + localTime) } },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(9.dp).rotate(45f).background(KColors.White).border(1.dp, KColors.ClipIndigo.copy(alpha = 0.6f)))
    }
}

private fun secondsLabel(t: Double): String =
    if (abs(t - t.roundToInt()) < 1e-6) "${t.roundToInt()}s" else String.format(java.util.Locale.US, "%.1fs", t)

/** Name shown for a layer (falls back to its content). */
fun layerDisplayName(layer: Layer): String = layer.name.ifBlank {
    when (layer.type) {
        LayerType.TEXT -> layer.text?.content?.lineSequence()?.firstOrNull()?.take(24) ?: "Text"
        else -> layer.type.name.lowercase().replaceFirstChar { it.uppercase() }
    }
}
