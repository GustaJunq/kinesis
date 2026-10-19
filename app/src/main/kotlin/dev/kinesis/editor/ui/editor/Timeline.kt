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
val TIMELINE_SIDE = 16.dp
private val ROW_HEIGHT = 48.dp
private val RULER_HEIGHT = 36.dp
private val LANE_RADIUS = 10.dp

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

/** Timeline clips are indigo; sound is red. */
fun clipColor(type: LayerType): Color = if (type == LayerType.AUDIO) KColors.ClipAudio else KColors.ClipIndigo

/** Text on top of a clip of this type. */
private fun onClipColor(@Suppress("UNUSED_PARAMETER") type: LayerType): Color = KColors.White

@Composable
fun Timeline(
    vm: EditorViewModel,
    project: KnsProject,
    time: Double,
    playing: Boolean,
    selection: String?,
    multiSelection: Set<String>,
    /** Opens the marker editor (a tap on the ruler's flag or a long press). */
    onEditMarker: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val context = LocalContext.current
    var pxPerSecond by remember { mutableFloatStateOf(with(density) { 72.dp.toPx() }) }
    var scrollPx by remember { mutableFloatStateOf(0f) }
    val expanded = remember { mutableStateMapOf<String, Boolean>() }
    val duration = project.composition.duration
    val audioRevision by vm.audioRevision.collectAsState()
    val scale = TimelineScale(pxPerSecond, scrollPx)
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
        val trackWidthPx = with(density) { (maxWidth - TIMELINE_SIDE * 2).toPx() }.coerceAtLeast(1f)
        fun clampScroll(v: Float) = scale.scrolledTo(v, duration, trackWidthPx).scrollPx
        val currentScale by rememberUpdatedState(scale)

        // Keep the playhead visible while playing.
        LaunchedEffect(time, playing) {
            if (playing) {
                val x = scale.xOf(time)
                if (x > trackWidthPx * 0.85f || x < 0f) scrollPx = clampScroll(time.toFloat() * pxPerSecond - trackWidthPx * 0.15f)
            }
        }

        Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = TIMELINE_SIDE)
                .pointerInput(Unit) {
                    // Two-finger pinch zooms the time axis around the fingers.
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        while (true) {
                            val event = awaitPointerEvent()
                            if (event.changes.none { it.pressed }) break
                            if (event.changes.count { it.pressed } >= 2) {
                                val zoom = event.calculateZoom()
                                if (zoom != 1f) {
                                    val centroidX = event.calculateCentroid().x - TIMELINE_SIDE.toPx()
                                    val zoomed = currentScale.zoomedAround(centroidX, zoom)
                                    pxPerSecond = zoomed.pxPerSecond
                                    scrollPx = zoomed.scrollPx
                                }
                                event.changes.forEach { it.consume() }
                            }
                        }
                    }
                },
        ) {
            // Ruler, with the "fit the timeline" button at its end
            Box(Modifier.fillMaxWidth().height(RULER_HEIGHT)) {
                Ruler(
                    duration = duration, scale = scale, fps = project.composition.fps, markers = project.markers,
                    markerHitPx = markerHitPx,
                    onSeek = { vm.seek(it) },
                    onAddMarker = { vm.addMarker(it) },
                    onEditMarker = onEditMarker,
                    modifier = Modifier.fillMaxSize(),
                )
                Row(Modifier.align(Alignment.CenterEnd), verticalAlignment = Alignment.CenterVertically) {
                    val names = AppSettings.showLayerNames
                    IconButton(
                        onClick = { AppSettings.setShowLayerNames(context, !names) },
                        modifier = Modifier.size(width = 44.dp, height = RULER_HEIGHT),
                    ) {
                        Icon(
                            if (names) Icons.Outlined.Label else Icons.Outlined.LabelOff,
                            stringResource(if (names) R.string.hide_layer_names else R.string.show_layer_names),
                            tint = KColors.TextSecondary, modifier = Modifier.size(18.dp),
                        )
                    }
                    IconButton(
                        onClick = {
                            val fit = TimelineScale.fit(duration, trackWidthPx)
                            pxPerSecond = fit.pxPerSecond
                            scrollPx = 0f
                        },
                        modifier = Modifier.size(width = 44.dp, height = RULER_HEIGHT),
                    ) {
                        Icon(Icons.Filled.FitScreen, stringResource(R.string.fit_timeline), tint = KColors.TextSecondary, modifier = Modifier.size(18.dp))
                    }
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
                            onScroll = { dx -> scrollPx = clampScroll(scrollPx - dx) },
                        )
                    }
                    item { Spacer(Modifier.height(48.dp)) }
                }
            }
        }
        // Playhead: a dark line with a round cap, across the ruler and every lane; markers as thin purple guides.
        Canvas(Modifier.fillMaxSize()) {
            for (m in project.markers) {
                val mx = sidePx + scale.xOf(m.time)
                if (mx >= sidePx && mx <= size.width - sidePx) {
                    drawLine(markerColor(m).copy(alpha = 0.5f), Offset(mx, RULER_HEIGHT.toPx()), Offset(mx, size.height), strokeWidth = 1.dp.toPx())
                }
            }
            val x = sidePx + scale.xOf(time)
            if (x >= sidePx && x <= size.width - sidePx) {
                drawLine(KColors.Playhead, Offset(x, 0f), Offset(x, size.height), strokeWidth = 2.dp.toPx())
                drawCircle(KColors.Playhead, 5.dp.toPx(), Offset(x, 6.dp.toPx()))
            }
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
    onAddMarker: (Double) -> Unit,
    onEditMarker: (String) -> Unit,
    modifier: Modifier,
) {
    val seek by rememberUpdatedState(onSeek)
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
                detectHorizontalDragGestures { change, _ ->
                    change.consume()
                    seek(currentScale.timeAt(change.position.x))
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
        var t = (scrollPx / pxPerSecond / minor).toInt() * minor
        while (t <= duration + 1e-9) {
            val x = scale.xOf(t)
            if (x > size.width) break
            val isMajor = abs(t / major - (t / major).roundToInt()) < 1e-6
            drawLine(
                KColors.LineStrong, Offset(x, size.height), Offset(x, size.height - if (isMajor) 12.dp.toPx() else 6.dp.toPx()),
                strokeWidth = 1f,
            )
            if (isMajor) drawContext.canvas.nativeCanvas.drawText(secondsLabel(t), x + 3.dp.toPx(), 12.dp.toPx(), paint)
            t += minor
        }
        val endX = scale.xOf(duration)
        drawLine(KColors.Danger.copy(alpha = 0.6f), Offset(endX, 0f), Offset(endX, size.height), strokeWidth = 2f)
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
            drawLine(col, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1.5.dp.toPx())
            val label = m.name.take(12)
            val labelW = if (label.isEmpty()) 0f else flag.measureText(label) + 8.dp.toPx()
            drawRoundRect(col, Offset(x, 0f), androidx.compose.ui.geometry.Size(maxOf(labelW, 9.dp.toPx()), 12.dp.toPx()), androidx.compose.ui.geometry.CornerRadius(3.dp.toPx()))
            if (label.isNotEmpty()) drawContext.canvas.nativeCanvas.drawText(label, x + 4.dp.toPx(), 9.5.dp.toPx(), flag)
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
    onScroll: (Float) -> Unit,
) {
    val layer = row.layer
    val scroll by rememberUpdatedState(onScroll)
    // One lane per layer across the whole width; the clip inside it has the layer's real start and end. The name is only
    // on the clip. Nested layers start further in. A tap selects; dragging the lane scrolls the time axis.
    Box(
        Modifier
            .fillMaxWidth()
            .height(ROW_HEIGHT)
            .padding(vertical = 2.dp)
            .clip(RoundedCornerShape(LANE_RADIUS))
            .background(KColors.Lane)
            .clipToBounds()
            .pointerInput(layer.id) {
                detectHorizontalDragGestures { change, dx ->
                    change.consume()
                    scroll(dx)
                }
            }
            .pointerInput(layer.id) { detectTapGestures { vm.select(layer.id) } },
    ) {
        ClipView(vm, project, row, selected, inMulti, expanded, onToggleExpand, scale, trackWidthPx, audioRevision)
    }
}

@Composable
private fun ClipView(
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
                color = if (selected) KColors.Primary else if (inMulti) KColors.Keyframe else Color.Transparent,
                shape = shape,
            )
            .pointerInput(layer.id) { detectTapGestures(onTap = { vm.select(layer.id) }, onLongPress = { vm.select(layer.id, additive = true) }) }
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
        Row(Modifier.align(Alignment.CenterStart).padding(start = (12 + row.depth * 10).dp, end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            if (layer.type == LayerType.GROUP) {
                Icon(
                    if (expanded) Icons.Filled.ExpandMore else Icons.Filled.ChevronRight, null,
                    tint = onClipColor(layer.type),
                    modifier = Modifier.size(20.dp).pointerInput(Unit) { detectTapGestures { onToggleExpand() } },
                )
            }
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
