package dev.kinesis.editor.ui.editor

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.kinesis.core.math.Vec2
import dev.kinesis.core.model.RotoMode
import dev.kinesis.core.model.RotoStroke
import dev.kinesis.core.video.RotoOps
import dev.kinesis.editor.R
import dev.kinesis.editor.media.RotoBrushSession
import dev.kinesis.editor.ui.common.PillChip
import dev.kinesis.editor.ui.common.kSliderColors
import dev.kinesis.editor.ui.theme.KColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max
import kotlin.math.min

private sealed class RotoBusy(val label: Int) {
    data object Preparing : RotoBusy(R.string.roto_preparing)
    data object Propagating : RotoBusy(R.string.roto_propagating)
    data object Rendering : RotoBusy(R.string.roto_rendering)

    /** One painted frame is being cut out: quick, shown as a thin bar, not a dialog. */
    data object Painting : RotoBusy(R.string.roto_painting)
}

/**
 * The Roto Brush: paint over the frame to say what to keep (add) and what not (remove); the object is followed from the
 * painted frames to the rest of the stretch. Painting any frame again corrects it and the frames after it. The result is a
 * packed-alpha layer, and the strokes stay in the project so it can be reopened.
 */
@Composable
fun RotoBrushScreen(vm: EditorViewModel, request: RotoRequest, onClose: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val work = remember { File(context.cacheDir, "roto/${vm.projectId}_${System.nanoTime()}") }
    val session = remember { runCatching { RotoBrushSession(context, request.sourceFile, request.startUs, request.endUs, work) }.getOrNull() }
    if (session == null) {
        AlertDialog(
            onDismissRequest = onClose, confirmButton = { TextButton(onClick = onClose) { Text(stringResource(R.string.close)) } },
            text = { Text(stringResource(R.string.roto_open_failed)) },
        )
        return
    }
    // `cancelled` stops the step that is running; `closing` ends the session (the screen is leaving).
    var cancelled by remember { mutableStateOf(false) }
    var closing by remember { mutableStateOf(false) }
    val strokes = remember { mutableStateMapOf<Int, List<RotoStroke>>() }
    var frame by remember { mutableIntStateOf(0) }
    var revision by remember { mutableIntStateOf(0) }
    var tool by remember { mutableStateOf(RotoMode.ADD) }
    var brush by remember { mutableStateOf(0.06f) }
    var view by remember { mutableStateOf(RotoOps.View.OVERLAY) }
    var busy by remember { mutableStateOf<RotoBusy?>(RotoBusy.Preparing) }
    var progress by remember { mutableStateOf(0f) }
    var error by remember { mutableStateOf<String?>(null) }
    var live by remember { mutableStateOf<List<Vec2>>(emptyList()) }

    DisposableEffect(Unit) {
        onDispose {
            closing = true
            session.close()
            work.deleteRecursively()
        }
    }

    // Decode the stretch once, then follow the strokes that were saved with the layer, if any.
    LaunchedEffect(session) {
        val ok = withContext(Dispatchers.Default) { session.prepare({ progress = it }, { cancelled || closing }) }
        if (!ok) { closing = true; onClose(); return@LaunchedEffect }
        request.initial.forEach { (time, list) -> strokes[session.frameOfTime(time)] = list }
        if (strokes.isNotEmpty()) {
            busy = RotoBusy.Propagating
            progress = 0f
            withContext(Dispatchers.Default) {
                runCatching { session.propagate(strokes.toMap(), null, { progress = it }, { cancelled || closing }) }.onFailure { error = it.message }
            }
            frame = strokes.keys.min()
        }
        revision++
        busy = null
    }

    fun propagate(changed: Int?) {
        if (busy != null) return
        busy = RotoBusy.Propagating
        cancelled = false
        progress = 0f
        scope.launch {
            withContext(Dispatchers.Default) {
                runCatching { session.propagate(strokes.toMap(), changed, { progress = it }, { cancelled || closing }) }.onFailure { error = it.message ?: it.javaClass.simpleName }
            }
            revision++
            busy = null
        }
    }

    fun paintFrame(f: Int) {
        if (busy != null) return
        busy = RotoBusy.Painting
        progress = 0f
        val snapshot = strokes.toMap()
        scope.launch {
            withContext(Dispatchers.Default) { runCatching { session.paintOne(f, snapshot) }.onFailure { error = it.message ?: it.javaClass.simpleName } }
            revision++
            busy = null
        }
    }

    fun commit(points: List<Vec2>) {
        if (points.isEmpty()) return
        strokes[frame] = strokes[frame].orEmpty() + RotoStroke(tool, brush, points)
        paintFrame(frame)
    }

    fun undoStroke() {
        val list = strokes[frame].orEmpty()
        if (list.isEmpty()) return
        if (list.size == 1) strokes.remove(frame) else strokes[frame] = list.dropLast(1)
        paintFrame(frame)
    }

    fun clearFrame() {
        if (strokes.remove(frame) != null) paintFrame(frame)
    }

    fun apply() {
        if (busy != null) return
        if (session.cutCount == 0) { error = context.getString(R.string.roto_nothing); return }
        busy = RotoBusy.Rendering
        cancelled = false
        progress = 0f
        scope.launch {
            val tmp = File(context.cacheDir, "roto/out_${System.nanoTime()}.mp4").also { it.parentFile?.mkdirs() }
            try {
                val result = withContext(Dispatchers.Default) { session.render(tmp, { progress = it }, { cancelled || closing }) }
                if (result != null) {
                    vm.finishRoto(request, session.recipe(request.sourceAssetId, request.sourceStartSeconds, strokes.toMap()), tmp, result)
                    onClose()
                }
            } catch (e: Throwable) {
                error = e.message ?: e.javaClass.simpleName
            } finally {
                tmp.delete()
                busy = null
            }
        }
    }

    Dialog(onDismissRequest = {}, properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnBackPress = false)) {
        Column(Modifier.fillMaxSize().background(KColors.Background)) {
            // Top bar
            Row(Modifier.fillMaxWidth().background(KColors.Bar).padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { closing = true; onClose() }) { Text(stringResource(R.string.cancel)) }
                Text(stringResource(R.string.roto_brush), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                Button(onClick = { apply() }, enabled = busy == null && session.cutCount > 0) { Text(stringResource(R.string.roto_apply)) }
            }
            // View modes
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PillChip(stringResource(R.string.roto_view_final), view == RotoOps.View.FINAL, { view = RotoOps.View.FINAL })
                PillChip(stringResource(R.string.roto_view_overlay), view == RotoOps.View.OVERLAY, { view = RotoOps.View.OVERLAY })
                PillChip(stringResource(R.string.roto_view_mask), view == RotoOps.View.MASK, { view = RotoOps.View.MASK })
            }
            // The frame and the brush
            RotoCanvas(
                session, frame, revision, view, tool, brush, live, strokes[frame].orEmpty(),
                onLive = { live = it }, onCommit = { points -> live = emptyList(); commit(points) },
                modifier = Modifier.weight(1f).fillMaxWidth(),
            )
            // Frames that have a cut-out
            RotoStrip(session, frame, revision, strokes.keys, onSeek = { frame = it }, modifier = Modifier.fillMaxWidth().height(34.dp).padding(horizontal = 12.dp))
            Text(
                stringResource(R.string.roto_frame_info, frame + 1, session.frameCount, session.cutCount),
                Modifier.padding(horizontal = 14.dp, vertical = 2.dp), fontSize = 12.sp, color = KColors.TextSecondary,
            )
            // Tools
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PillChip(stringResource(R.string.roto_add), tool == RotoMode.ADD, { tool = RotoMode.ADD })
                PillChip(stringResource(R.string.roto_remove), tool == RotoMode.REMOVE, { tool = RotoMode.REMOVE })
                Box(Modifier.weight(1f))
                TextButton(onClick = { frame = max(0, frame - 1) }) { Text("◀") }
                TextButton(onClick = { frame = min(session.frameCount - 1, frame + 1) }) { Text("▶") }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.roto_brush_size), fontSize = 12.sp, color = KColors.TextSecondary)
                Slider(
                    value = brush, onValueChange = { brush = it }, valueRange = RotoBrushRange, colors = kSliderColors(),
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                )
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { undoStroke() }, enabled = busy == null && strokes[frame].orEmpty().isNotEmpty()) { Text(stringResource(R.string.roto_undo_stroke)) }
                TextButton(onClick = { clearFrame() }, enabled = busy == null && strokes[frame].orEmpty().isNotEmpty()) { Text(stringResource(R.string.roto_clear_frame)) }
                Box(Modifier.weight(1f))
                Button(onClick = { propagate(if (strokes.isNotEmpty()) strokes.keys.filter { it <= frame }.maxOrNull() else null) }, enabled = busy == null && strokes.isNotEmpty()) {
                    Text(stringResource(R.string.roto_follow))
                }
            }
        }
    }

    busy?.let { b ->
        if (b == RotoBusy.Painting) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        } else {
            AlertDialog(
                onDismissRequest = {},
                title = { Text(stringResource(b.label)) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                        Text(stringResource(R.string.roto_hint), fontSize = 12.sp, color = KColors.TextSecondary)
                    }
                },
                confirmButton = { TextButton(onClick = { if (b == RotoBusy.Preparing) closing = true else cancelled = true }) { Text(stringResource(R.string.cancel)) } },
            )
        }
    }
    error?.let { msg ->
        AlertDialog(
            onDismissRequest = { error = null }, confirmButton = { TextButton(onClick = { error = null }) { Text(stringResource(R.string.close)) } },
            text = { Text(stringResource(R.string.roto_failed, msg)) },
        )
    }
}

private val RotoBrushRange = 0.01f..0.3f

/** The frame (as chosen view) with the strokes of this frame and the stroke being drawn. Strokes are normalised to the frame. */
@Composable
private fun RotoCanvas(
    session: RotoBrushSession, frame: Int, revision: Int, view: RotoOps.View, tool: RotoMode, brush: Float,
    live: List<Vec2>, saved: List<RotoStroke>, onLive: (List<Vec2>) -> Unit, onCommit: (List<Vec2>) -> Unit, modifier: Modifier,
) {
    val w = session.width
    val h = session.height
    val pixels = remember(session) { IntArray(w * h) }
    val composed = remember(session) { IntArray(w * h) }
    val bitmap = remember(session) { Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888) }
    val image = remember(frame, revision, view) {
        session.frame(frame, pixels)
        RotoOps.compose(pixels, session.mask(frame), view, w, h, composed)
        bitmap.setPixels(composed, 0, w, 0, 0, w, h)
        bitmap.copy(Bitmap.Config.ARGB_8888, false).asImageBitmap()
    }
    val currentTool by rememberUpdatedState(tool)
    val currentBrush by rememberUpdatedState(brush)
    var area by remember { mutableStateOf(IntSize(1, 1)) }

    Canvas(
        modifier
            .background(Color.Black)
            .pointerInput(session, frame) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val size = this.size
                    val scale = min(size.width / w.toFloat(), size.height / h.toFloat())
                    val left = (size.width - w * scale) / 2f
                    val top = (size.height - h * scale) / 2f
                    fun norm(o: Offset) = Vec2(((o.x - left) / (w * scale)).coerceIn(0f, 1f), ((o.y - top) / (h * scale)).coerceIn(0f, 1f))
                    val points = ArrayList<Vec2>()
                    points += norm(down.position)
                    onLive(points.toList())
                    drag(down.id) { change ->
                        change.consume()
                        val p = norm(change.position)
                        val last = points.last()
                        if (kotlin.math.hypot((p.x - last.x) * w, (p.y - last.y) * h) >= 1.5f && points.size < 1500) {
                            points += p
                            onLive(points.toList())
                        }
                    }
                    onCommit(points.toList())
                }
            },
    ) {
        area = IntSize(size.width.toInt(), size.height.toInt())
        val scale = min(size.width / w, size.height / h)
        val dw = (w * scale).toInt()
        val dh = (h * scale).toInt()
        val left = ((size.width - dw) / 2f).toInt()
        val top = ((size.height - dh) / 2f).toInt()
        drawImage(image, dstOffset = IntOffset(left, top), dstSize = IntSize(dw, dh), filterQuality = FilterQuality.Low)
        fun draw(points: List<Vec2>, mode: RotoMode, size01: Float) {
            if (points.isEmpty()) return
            val color = if (mode == RotoMode.ADD) Color(0xFF3DDC84).copy(alpha = 0.55f) else Color(0xFFFF5252).copy(alpha = 0.55f)
            val width = max(2f, size01 * min(dw, dh))
            if (points.size == 1) {
                drawCircle(color, width / 2f, Offset(left + points[0].x * dw, top + points[0].y * dh))
            } else {
                val path = Path().apply {
                    moveTo(left + points[0].x * dw, top + points[0].y * dh)
                    for (i in 1 until points.size) lineTo(left + points[i].x * dw, top + points[i].y * dh)
                }
                drawPath(path, color, style = Stroke(width, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
        }
        saved.forEach { draw(it.points, it.mode, it.size) }
        draw(live, currentTool, currentBrush)
    }
}

/** One tick per frame: no cut-out, followed, followed but lost (a guess), painted. Tap or drag to go to a frame. */
@Composable
private fun RotoStrip(session: RotoBrushSession, frame: Int, revision: Int, painted: Set<Int>, onSeek: (Int) -> Unit, modifier: Modifier) {
    val count = session.frameCount
    Canvas(
        modifier
            .background(KColors.Card, RoundedCornerShape(6.dp))
            .pointerInput(count) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    fun at(x: Float) = ((x / size.width) * count).toInt().coerceIn(0, count - 1)
                    onSeek(at(down.position.x))
                    drag(down.id) { change -> change.consume(); onSeek(at(change.position.x)) }
                }
            },
    ) {
        val tick = size.width / count
        // `revision` makes this redraw when the cut-outs change.
        @Suppress("UNUSED_VARIABLE") val unused = revision
        for (i in 0 until count) {
            val color = when (session.status[i].toInt()) {
                3 -> Color.White
                1 -> KColors.Accent
                2 -> Color(0xFFFFB74D)
                else -> KColors.Line
            }
            drawRect(color, Offset(i * tick, size.height * 0.28f), Size(max(1f, tick - 0.5f), size.height * 0.44f))
        }
        painted.forEach { drawRect(Color(0xFF3DDC84), Offset(it * tick, size.height * 0.1f), Size(max(2f, tick), size.height * 0.14f)) }
        drawRect(Color.White, Offset(frame * tick - 1f, 0f), Size(max(2f, tick) + 2f, size.height), style = Stroke(2f))
    }
}
