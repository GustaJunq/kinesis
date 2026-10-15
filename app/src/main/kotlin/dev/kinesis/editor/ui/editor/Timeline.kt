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
import dev.kinesis.core.edit.TimelineOps
import dev.kinesis.core.edit.keyframeTimes
import dev.kinesis.core.model.KnsProject
import dev.kinesis.core.model.Layer
import dev.kinesis.core.model.LayerTree
import dev.kinesis.core.model.LayerType
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

private val HEADER_WIDTH = 80.dp
private val ROW_HEIGHT = 44.dp
private val RULER_HEIGHT = 28.dp

fun layerColor(type: LayerType): Color = when (type) {
    LayerType.TEXT -> KColors.ClipTextStart
    LayerType.SHAPE -> KColors.ClipPink
    LayerType.IMAGE -> KColors.ClipAmber
    LayerType.VIDEO -> KColors.ClipViolet
    LayerType.AUDIO -> KColors.ClipTeal
    LayerType.GROUP -> KColors.ClipTextSecondary
    LayerType.OBJECT3D, LayerType.MODEL3D -> KColors.ClipTextSecondary
    LayerType.CAMERA, LayerType.LIGHT -> KColors.ClipViolet
}

/** Text on top of a clip of this type. */
private fun onClipColor(type: LayerType): Color = if (type == LayerType.IMAGE) KColors.OnClipAmber else KColors.White

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
    val audioRevision by vm.audioRevision.collectAsState()

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
                Box(Modifier.width(HEADER_WIDTH).fillMaxHeight().background(KColors.Bar), contentAlignment = Alignment.Center) {
                    IconButton(onClick = {
                        pxPerSecond = (trackWidthPx / duration.toFloat() * 0.95f).coerceIn(8f, 2000f)
                        scrollPx = 0f
                    }, modifier = Modifier.size(KDims.MinTouch)) {
                        Icon(Icons.Filled.FitScreen, stringResource(R.string.fit_timeline), tint = KColors.TextSecondary, modifier = Modifier.size(18.dp))
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
                            trackWidthPx = trackWidthPx,
                            audioRevision = audioRevision,
                            onScroll = { dx -> scrollPx = clampScroll(scrollPx - dx) },
                        )
                    }
                    item { Spacer(Modifier.height(48.dp)) }
                }
            }
        }
        // Playhead: white line with a round cap on the ruler.
        Canvas(Modifier.fillMaxSize()) {
            val x = HEADER_WIDTH.toPx() + time.toFloat() * pxPerSecond - scrollPx
            if (x >= HEADER_WIDTH.toPx()) {
                drawLine(KColors.Playhead, Offset(x, 0f), Offset(x, size.height), strokeWidth = 2.dp.toPx())
                drawCircle(KColors.Playhead, 5.dp.toPx(), Offset(x, 6.dp.toPx()))
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
            .background(KColors.Bar)
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
            color = KColors.TextSecondary.toArgb()
            typeface = android.graphics.Typeface.MONOSPACE
            textSize = 10.sp.toPx()
            isAntiAlias = true
        }
        var t = (scrollPx / pxPerSecond / minor).toInt() * minor
        while (t <= duration + 1e-9) {
            val x = (t * pxPerSecond - scrollPx).toFloat()
            if (x > size.width) break
            val isMajor = abs(t / major - (t / major).roundToInt()) < 1e-6
            drawLine(
                KColors.LineStrong, Offset(x, size.height), Offset(x, size.height - if (isMajor) 12.dp.toPx() else 6.dp.toPx()),
                strokeWidth = 1f,
            )
            if (isMajor) drawContext.canvas.nativeCanvas.drawText(secondsLabel(t), x + 3.dp.toPx(), 12.dp.toPx(), paint)
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
    trackWidthPx: Float,
    audioRevision: Int,
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
            .background(if (selected) KColors.Field.copy(alpha = 0.6f) else Color.Transparent),
    ) {
        // Header: name, visibility, group expansion; long-press drag reorders.
        Row(
            Modifier
                .width(HEADER_WIDTH)
                .fillMaxHeight()
                .background(KColors.Bar)
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
            Text(
                layerDisplayName(layer), style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = if (layer.visible) KColors.TextSoft else KColors.Disabled,
                modifier = Modifier.weight(1f),
            )
            if (layer.locked) Icon(Icons.Outlined.Lock, stringResource(R.string.layer_locked), tint = KColors.TextSecondary, modifier = Modifier.size(12.dp))
            Icon(
                if (layer.visible) Icons.Outlined.Visibility else Icons.Outlined.VisibilityOff,
                stringResource(if (layer.visible) R.string.hide_layer else R.string.show_layer),
                tint = KColors.TextSecondary,
                modifier = Modifier
                    .padding(horizontal = 4.dp)
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
            ClipView(vm, project, row, selected, inMulti, pxPerSecond, scrollPx, currentPx, trackWidthPx, audioRevision)
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
    trackWidthPx: Float,
    audioRevision: Int,
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
            .clip(KShapes.Clip)
            .background(if (layer.type == LayerType.TEXT) KBrushes.ClipText else SolidColor(color), alpha = if (layer.visible) 1f else 0.3f)
            .border(
                width = if (selected || inMulti) 1.5.dp else 0.dp,
                color = if (selected) KColors.White else if (inMulti) KColors.Keyframe else Color.Transparent,
                shape = KShapes.Clip,
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
        val media = layer.media
        val pcm = remember(media?.asset, audioRevision) { media?.let { vm.pcmOf(it.asset) } }
        if (media != null && pcm != null && !media.muted) {
            Waveform(pcm, media.sourceStart, media.speed, pxPerSecond, visibleFrom = -x, visibleTo = trackWidthPx - x)
        }
        Text(
            layerDisplayName(layer), style = MaterialTheme.typography.labelSmall, color = onClipColor(layer.type), maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.align(Alignment.CenterStart).padding(horizontal = 16.dp),
        )
        if (selected) {
            // Trim handles
            Box(Modifier.align(Alignment.CenterStart).padding(start = 2.dp).width(4.dp).height(18.dp).clip(KShapes.Pill).background(KColors.White))
            Box(Modifier.align(Alignment.CenterEnd).padding(end = 2.dp).width(4.dp).height(18.dp).clip(KShapes.Pill).background(KColors.White))
        }
        // Keyframes of every property of the layer
        for (kt in layer.keyframeTimes()) {
            if (kt < -1e-6 || kt > layer.duration + 1e-6) continue
            KeyframeDiamond(vm, layer, kt, pxPerSecond = pxPerSecond, absLayerStart = absStart)
        }
    }
}

/** Peak envelope of decoded audio, drawn only for the visible part of the clip. */
@Composable
private fun Waveform(pcm: java.nio.ShortBuffer, sourceStart: Double, speed: Double, pxPerSecond: Float, visibleFrom: Float, visibleTo: Float) {
    Canvas(Modifier.fillMaxSize()) {
        val total = pcm.limit() / 2
        if (total <= 0) return@Canvas
        val mid = size.height / 2
        val from = visibleFrom.coerceAtLeast(0f).toInt()
        val to = visibleTo.coerceAtMost(size.width).toInt()
        val color = KColors.White.copy(alpha = 0.35f)
        for (x in from until to step 2) {
            val t0 = sourceStart + x / pxPerSecond * speed
            val t1 = sourceStart + (x + 2) / pxPerSecond * speed
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
        Box(Modifier.size(9.dp).rotate(45f).background(KColors.Keyframe).border(1.dp, KColors.Background.copy(alpha = 0.6f)))
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
