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
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import dev.kinesis.core.edit.TimelineOps
import dev.kinesis.core.edit.keyframeTimes
import dev.kinesis.core.model.KnsProject
import dev.kinesis.core.model.Layer
import dev.kinesis.core.model.LayerTree
import dev.kinesis.core.model.LayerType
import dev.kinesis.editor.ui.common.formatTime
import dev.kinesis.editor.ui.theme.KColors
import kotlin.math.abs
import kotlin.math.roundToInt

/** A visible timeline row: a layer, its nesting depth and the absolute start of its container. */
private data class Row(val layer: Layer, val depth: Int, val containerOffset: Double, val containerId: String?)

private val HEADER_WIDTH = 120.dp
private val ROW_HEIGHT = 44.dp
private val RULER_HEIGHT = 28.dp

fun layerColor(type: LayerType): Color = when (type) {
    LayerType.TEXT -> KColors.ClipText
    LayerType.SHAPE -> KColors.ClipShape
    LayerType.IMAGE -> KColors.ClipImage
    LayerType.VIDEO -> KColors.ClipVideo
    LayerType.AUDIO -> KColors.ClipAudio
    LayerType.GROUP -> KColors.ClipGroup
    LayerType.OBJECT3D, LayerType.MODEL3D -> KColors.Clip3D
    LayerType.CAMERA, LayerType.LIGHT -> KColors.ClipCamera
}

@Composable
fun Timeline(
    vm: EditorViewModel,
    project: KnsProject,
    time: Double,
    playing: Boolean,
    selection: String?,
    multiSelection: Set<String>,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    var pxPerSecond by remember { mutableFloatStateOf(with(density) { 72.dp.toPx() }) }
    var scrollPx by remember { mutableFloatStateOf(0f) }
    val expanded = remember { mutableStateMapOf<String, Boolean>() }
    val duration = project.composition.duration

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

    BoxWithConstraints(modifier.background(KColors.Background)) {
        val trackWidthPx = with(density) { (maxWidth - HEADER_WIDTH).toPx() }.coerceAtLeast(1f)
        val maxScroll = (duration.toFloat() * pxPerSecond - trackWidthPx * 0.6f).coerceAtLeast(0f)
        fun clampScroll(v: Float) = v.coerceIn(0f, maxScroll)
        val currentPx by rememberUpdatedState(pxPerSecond)

        // Keep the playhead visible while playing.
        LaunchedEffect(time, playing) {
            if (playing) {
                val x = time.toFloat() * pxPerSecond - scrollPx
                if (x > trackWidthPx * 0.85f || x < 0f) scrollPx = clampScroll(time.toFloat() * pxPerSecond - trackWidthPx * 0.15f)
            }
        }

        Column(
            Modifier
                .fillMaxSize()
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
                                    val centroidX = event.calculateCentroid().x - HEADER_WIDTH.toPx()
                                    val t = (centroidX + scrollPx) / currentPx
                                    val newPx = (currentPx * zoom).coerceIn(8f, 2000f)
                                    pxPerSecond = newPx
                                    scrollPx = (t * newPx - centroidX).coerceAtLeast(0f)
                                }
                                event.changes.forEach { it.consume() }
                            }
                        }
                    }
                },
        ) {
            // Ruler
            Row(Modifier.fillMaxWidth().height(RULER_HEIGHT)) {
                Box(Modifier.width(HEADER_WIDTH).fillMaxHeight().background(KColors.Surface), contentAlignment = Alignment.CenterStart) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            formatTime(time, project.composition.fps),
                            fontSize = 11.sp, color = KColors.TextPrimary, modifier = Modifier.padding(start = 8.dp).weight(1f),
                        )
                        IconButton(onClick = {
                            pxPerSecond = (trackWidthPx / duration.toFloat() * 0.95f).coerceIn(8f, 2000f)
                            scrollPx = 0f
                        }, modifier = Modifier.size(28.dp)) {
                            Icon(Icons.Filled.FitScreen, null, tint = KColors.TextSecondary, modifier = Modifier.size(16.dp))
                        }
                    }
                }
                Ruler(
                    duration = duration, pxPerSecond = pxPerSecond, scrollPx = scrollPx, fps = project.composition.fps,
                    onSeek = { vm.seek(it) },
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                )
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
                            pxPerSecond = pxPerSecond,
                            scrollPx = scrollPx,
                            onScroll = { dx -> scrollPx = clampScroll(scrollPx - dx) },
                        )
                    }
                    item { Spacer(Modifier.height(48.dp)) }
                }
                // Playhead
                Canvas(Modifier.fillMaxSize()) {
                    val x = HEADER_WIDTH.toPx() + time.toFloat() * pxPerSecond - scrollPx
                    if (x >= HEADER_WIDTH.toPx()) drawLine(KColors.Playhead, Offset(x, 0f), Offset(x, size.height), strokeWidth = 2.dp.toPx())
                }
            }
        }
    }
}

@Composable
private fun Ruler(duration: Double, pxPerSecond: Float, scrollPx: Float, fps: Double, onSeek: (Double) -> Unit, modifier: Modifier) {
    val seek by rememberUpdatedState(onSeek)
    val currentScroll by rememberUpdatedState(scrollPx)
    val currentPx by rememberUpdatedState(pxPerSecond)
    val density = LocalDensity.current
    Canvas(
        modifier
            .background(KColors.Surface)
            .clipToBounds()
            .pointerInput(Unit) { detectTapGestures { seek(((it.x + currentScroll) / currentPx).toDouble()) } }
            .pointerInput(Unit) {
                detectHorizontalDragGestures { change, _ ->
                    change.consume()
                    seek(((change.position.x + currentScroll) / currentPx).toDouble())
                }
            },
    ) {
        val minGap = with(density) { 56.dp.toPx() }
        val steps = listOf(1.0 / fps, 0.1, 0.25, 0.5, 1.0, 2.0, 5.0, 10.0, 15.0, 30.0, 60.0, 120.0)
        val major = steps.firstOrNull { it * pxPerSecond >= minGap } ?: 300.0
        val minor = major / 4
        val paint = android.graphics.Paint().apply {
            color = android.graphics.Color.argb(200, 158, 161, 179)
            textSize = 10.sp.toPx()
            isAntiAlias = true
        }
        var t = (scrollPx / pxPerSecond / minor).toInt() * minor
        while (t <= duration + 1e-9) {
            val x = (t * pxPerSecond - scrollPx).toFloat()
            if (x > size.width) break
            val isMajor = abs(t / major - (t / major).roundToInt()) < 1e-6
            drawLine(
                Color(0xFF555A70), Offset(x, size.height), Offset(x, size.height - if (isMajor) 12.dp.toPx() else 6.dp.toPx()),
                strokeWidth = 1f,
            )
            if (isMajor) drawContext.canvas.nativeCanvas.drawText(formatTime(t), x + 3.dp.toPx(), 12.dp.toPx(), paint)
            t += minor
        }
        val endX = (duration * pxPerSecond - scrollPx).toFloat()
        drawLine(KColors.Danger.copy(alpha = 0.6f), Offset(endX, 0f), Offset(endX, size.height), strokeWidth = 2f)
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
    pxPerSecond: Float,
    scrollPx: Float,
    onScroll: (Float) -> Unit,
) {
    val layer = row.layer
    val density = LocalDensity.current
    val currentPx by rememberUpdatedState(pxPerSecond)
    val scroll by rememberUpdatedState(onScroll)
    var reorderAccum by remember { mutableFloatStateOf(0f) }
    val rowHeightPx = with(density) { ROW_HEIGHT.toPx() }

    Row(
        Modifier
            .fillMaxWidth()
            .height(ROW_HEIGHT)
            .background(if (selected) KColors.SurfaceHigh else Color.Transparent),
    ) {
        // Header: name, visibility, group expansion; long-press drag reorders.
        Row(
            Modifier
                .width(HEADER_WIDTH)
                .fillMaxHeight()
                .background(KColors.Surface)
                .pointerInput(layer.id) {
                    detectTapGestures(
                        onTap = { vm.select(layer.id) },
                        onLongPress = { vm.select(layer.id, additive = true) },
                    )
                }
                .pointerInput(layer.id) {
                    detectDragGesturesAfterLongPress(
                        onDragStart = { reorderAccum = 0f },
                        onDragEnd = {
                            val steps = (reorderAccum / rowHeightPx).roundToInt()
                            if (steps != 0) {
                                val loc = LayerTree.locate(vm.current, layer.id)
                                // Rows are drawn top-first, so dragging down moves the layer lower in the stack.
                                if (loc != null) vm.reorder(layer.id, loc.index - steps)
                            }
                        },
                    ) { change, drag ->
                        change.consume()
                        reorderAccum += drag.y
                    }
                }
                .padding(start = (6 + row.depth * 12).dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (layer.type == LayerType.GROUP) {
                Icon(
                    if (expanded) Icons.Filled.ExpandMore else Icons.Filled.ChevronRight, null,
                    tint = KColors.TextSecondary,
                    modifier = Modifier.size(18.dp).pointerInput(Unit) { detectTapGestures { onToggleExpand() } },
                )
            }
            Box(Modifier.size(8.dp).clip(RoundedCornerShape(2.dp)).background(layerColor(layer.type)))
            Spacer(Modifier.width(6.dp))
            Text(
                layerDisplayName(layer), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = if (layer.visible) KColors.TextPrimary else KColors.TextSecondary,
                modifier = Modifier.weight(1f),
            )
            if (layer.locked) Icon(Icons.Filled.Lock, null, tint = KColors.TextSecondary, modifier = Modifier.size(12.dp))
            Icon(
                if (layer.visible) Icons.Filled.Visibility else Icons.Filled.VisibilityOff, null,
                tint = KColors.TextSecondary,
                modifier = Modifier
                    .padding(horizontal = 6.dp)
                    .size(16.dp)
                    .pointerInput(layer.id) { detectTapGestures { vm.updateLayer(layer.id) { it.copy(visible = !it.visible) } } },
            )
        }
        // Track
        Box(
            Modifier
                .weight(1f)
                .fillMaxHeight()
                .clipToBounds()
                .pointerInput(layer.id) {
                    detectHorizontalDragGestures { change, dx ->
                        change.consume()
                        scroll(dx)
                    }
                }
                .pointerInput(layer.id) { detectTapGestures { vm.select(layer.id) } },
        ) {
            ClipView(vm, project, row, selected, inMulti, pxPerSecond, scrollPx, currentPx)
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
    pxPerSecond: Float,
    scrollPx: Float,
    pxNow: Float,
) {
    val layer = row.layer
    val density = LocalDensity.current
    val absStart = row.containerOffset + layer.start
    val x = (absStart * pxPerSecond - scrollPx).toFloat()
    val w = (layer.duration * pxPerSecond).toFloat().coerceAtLeast(with(density) { 6.dp.toPx() })
    val handleW = with(density) { 14.dp.toPx() }
    val px by rememberUpdatedState(pxNow)
    val color = layerColor(layer.type)
    val comp = project.composition

    fun snap(t: Double, exclude: String): Double {
        val local = comp.snap(t + row.containerOffset) - row.containerOffset
        val tolerance = with(density) { 8.dp.toPx() } / px
        val candidates = TimelineOps.snapPoints(vm.current, exclude).map { it - row.containerOffset } + (vm.time.value - row.containerOffset)
        return candidates.minByOrNull { abs(it - t) }?.takeIf { abs(it - t) <= tolerance } ?: local
    }

    Box(
        Modifier
            .offset { IntOffset(x.roundToInt(), with(density) { 6.dp.roundToPx() }) }
            .width(with(density) { w.toDp() })
            .height(ROW_HEIGHT - 12.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(if (layer.visible) color.copy(alpha = if (selected) 1f else 0.75f) else color.copy(alpha = 0.3f))
            .border(
                width = if (selected || inMulti) 2.dp else 0.dp,
                color = if (selected) Color.White else if (inMulti) KColors.Keyframe else Color.Transparent,
                shape = RoundedCornerShape(6.dp),
            )
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
                        mode = when {
                            current.locked -> -1
                            pos.x < handleW -> 1
                            pos.x > size.width - handleW -> 2
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
        Text(
            layerDisplayName(layer), fontSize = 11.sp, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.align(Alignment.CenterStart).padding(horizontal = 16.dp),
        )
        if (selected) {
            // Trim handles
            Box(Modifier.align(Alignment.CenterStart).width(4.dp).fillMaxHeight().background(Color.White.copy(alpha = 0.85f)))
            Box(Modifier.align(Alignment.CenterEnd).width(4.dp).fillMaxHeight().background(Color.White.copy(alpha = 0.85f)))
            // Keyframes of every property of the layer
            for (kt in layer.keyframeTimes()) {
                if (kt < -1e-6 || kt > layer.duration + 1e-6) continue
                KeyframeDiamond(vm, layer, kt, pxPerSecond = pxPerSecond, absLayerStart = absStart)
            }
        }
    }
}

@Composable
private fun KeyframeDiamond(vm: EditorViewModel, layer: Layer, localTime: Double, pxPerSecond: Float, absLayerStart: Double) {
    val density = LocalDensity.current
    val x = (localTime * pxPerSecond).toFloat() - with(density) { 7.dp.toPx() }
    val px by rememberUpdatedState(pxPerSecond)
    Box(
        Modifier
            .offset { IntOffset(x.roundToInt(), with(density) { 9.dp.roundToPx() }) }
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
        Box(Modifier.size(9.dp).rotate(45f).background(KColors.Keyframe).border(1.dp, Color.Black.copy(alpha = 0.5f)))
    }
}

/** Name shown for a layer (falls back to its content). */
fun layerDisplayName(layer: Layer): String = layer.name.ifBlank {
    when (layer.type) {
        LayerType.TEXT -> layer.text?.content?.lineSequence()?.firstOrNull()?.take(24) ?: "Text"
        else -> layer.type.name.lowercase().replaceFirstChar { it.uppercase() }
    }
}
