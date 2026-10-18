package dev.kinesis.editor.ui.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.kinesis.core.audio.EqPresets
import dev.kinesis.core.audio.StereoEq
import dev.kinesis.core.model.EqBand
import dev.kinesis.core.model.KnsProject
import dev.kinesis.core.model.Layer
import dev.kinesis.editor.R
import dev.kinesis.editor.media.MIX_RATE
import dev.kinesis.editor.ui.common.LabeledSlider
import dev.kinesis.editor.ui.common.OverlineLabel
import dev.kinesis.editor.ui.common.PillChip
import dev.kinesis.editor.ui.common.SecondaryButton
import dev.kinesis.editor.ui.theme.KColors
import dev.kinesis.editor.ui.theme.KDims
import dev.kinesis.editor.ui.theme.KShapes
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow

/** Range of the equalizer graph, in dB (the model allows more; the graph keeps points easy to reach). */
private const val GRAPH_DB = 18f
private const val MIN_HZ = EqBand.MIN_FREQUENCY
private const val MAX_HZ = EqBand.MAX_FREQUENCY

private fun xOfFrequency(f: Float, width: Float) = width * (ln(f / MIN_HZ) / ln(MAX_HZ / MIN_HZ))
private fun frequencyOfX(x: Float, width: Float) = (MIN_HZ * (MAX_HZ / MIN_HZ).pow((x / width).coerceIn(0f, 1f))).coerceIn(MIN_HZ, MAX_HZ)
private fun yOfGain(db: Float, height: Float) = height / 2 - db / GRAPH_DB * (height / 2)
private fun gainOfY(y: Float, height: Float) = ((height / 2 - y) / (height / 2) * GRAPH_DB).coerceIn(-GRAPH_DB, GRAPH_DB)

/**
 * Audio panel of a video or audio layer: waveform, volume, fades and a graphical equalizer. Everything is stored in
 * the clip (non-destructive); the play button auditions this clip alone with the equalizer applied as the points
 * are moved, and the exported video has the same sound.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AudioSheet(vm: EditorViewModel, project: KnsProject, layer: Layer, onDismiss: () -> Unit) {
    val media = layer.media ?: return
    val playing by vm.playing.collectAsState()
    val audioRevision by vm.audioRevision.collectAsState()
    val pcm = remember(media.asset, audioRevision) { vm.pcmOf(media.asset) }
    val key = "audio:${layer.id}"

    // The preview plays this clip alone while the sheet is open.
    DisposableEffect(layer.id) {
        vm.setAudioSolo(layer.id)
        onDispose {
            vm.setAudioSolo(null)
            vm.pause()
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = KColors.Sheet, shape = KShapes.Sheet) {
        LazyColumn(
            Modifier.fillMaxWidth().fillMaxHeight(0.86f).padding(horizontal = KDims.Gutter),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(layerDisplayName(layer), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    SecondaryButton(
                        stringResource(if (playing) R.string.pause else R.string.play), { vm.previewClip(layer.id) },
                        icon = if (playing) Icons.Outlined.Pause else Icons.Outlined.PlayArrow, pill = true,
                    )
                }
            }
            item {
                val from = media.sourceStart
                val to = media.sourceStart + layer.duration * media.speed
                Canvas(Modifier.fillMaxWidth().height(64.dp).clip(KShapes.Tile).background(KColors.Background)) {
                    if (pcm == null) return@Canvas
                    val total = pcm.limit() / 2
                    if (total <= 0) return@Canvas
                    val mid = size.height / 2
                    val w = size.width.toInt()
                    for (x in 0 until w step 2) {
                        val t0 = from + (to - from) * x / w
                        val t1 = from + (to - from) * (x + 2) / w
                        val f0 = (t0 * MIX_RATE).toInt().coerceIn(0, total - 1)
                        val f1 = (t1 * MIX_RATE).toInt().coerceIn(f0 + 1, total)
                        val step = ((f1 - f0) / 64).coerceAtLeast(1)
                        var peak = 0
                        var f = f0
                        while (f < f1) { peak = max(peak, abs(pcm.get(f * 2).toInt())); f += step }
                        val h = peak / 32768f * mid
                        if (h > 0.5f) drawLine(KColors.Accent.copy(alpha = 0.8f), Offset(x.toFloat(), mid - h), Offset(x.toFloat(), mid + h), strokeWidth = 1.5f)
                    }
                }
            }

            // ---- volume, mute, fades
            item { OverlineLabel(stringResource(R.string.audio_level)) }
            item {
                if (media.volume.isAnimated) {
                    Text(stringResource(R.string.audio_volume_animated), color = KColors.TextSecondary, style = MaterialTheme.typography.bodySmall)
                } else {
                    LabeledSlider(
                        stringResource(R.string.prop_volume), media.volume.value, 0f..2f,
                        { v -> vm.updateMedia(layer.id, "$key:vol") { it.copy(volume = dev.kinesis.core.anim.anim(v)) } }, { vm.endGesture() },
                        display = 100f, suffix = "%", min = 0f, max = 4f,
                    )
                }
            }
            item {
                val maxFade = (layer.duration / 2).coerceAtMost(10.0).toFloat().coerceAtLeast(0.1f)
                LabeledSlider(stringResource(R.string.fade_in), media.fadeIn.toFloat(), 0f..maxFade, { v -> vm.updateMedia(layer.id, "$key:fi") { it.copy(fadeIn = v.toDouble()) } }, { vm.endGesture() }, suffix = " s", min = 0f)
            }
            item {
                val maxFade = (layer.duration / 2).coerceAtMost(10.0).toFloat().coerceAtLeast(0.1f)
                LabeledSlider(stringResource(R.string.fade_out), media.fadeOut.toFloat(), 0f..maxFade, { v -> vm.updateMedia(layer.id, "$key:fo") { it.copy(fadeOut = v.toDouble()) } }, { vm.endGesture() }, suffix = " s", min = 0f)
            }
            item {
                PillChip(stringResource(R.string.mute), media.muted, { vm.updateMedia(layer.id) { it.copy(muted = !it.muted) } })
            }

            // ---- equalizer: drag the points
            item { OverlineLabel(stringResource(R.string.equalizer)) }
            item {
                EqEditor(
                    stored = media.eq,
                    onChange = { bands -> vm.updateMedia(layer.id, "$key:eq") { it.copy(eq = EqPresets.normalize(bands)) } },
                    onFinished = { vm.endGesture() },
                )
            }
            item {
                TextButton(onClick = { vm.updateMedia(layer.id) { it.copy(eq = emptyList()) } }, enabled = media.eq.isNotEmpty()) { Text(stringResource(R.string.eq_reset)) }
            }
            item { androidx.compose.foundation.layout.Spacer(Modifier.padding(bottom = 24.dp)) }
        }
    }
}

/**
 * The equalizer as a graph: each band is a point (x = frequency on a log scale, 20 Hz–20 kHz; y = gain in dB), the
 * line is the combined response, redrawn while a point moves. Tap a point to select it and set its Q (bandwidth)
 * below the graph; dragging starting away from every point scrolls the sheet as usual.
 */
@Composable
private fun EqEditor(stored: List<EqBand>, onChange: (List<EqBand>) -> Unit, onFinished: () -> Unit) {
    // Local copy so the curve follows the finger at once; the project catches up through [onChange].
    var bands by remember { mutableStateOf(EqPresets.editable(stored)) }
    // Follow outside changes (restore defaults, undo) but not our own pushes, whose stored form equals ours.
    androidx.compose.runtime.LaunchedEffect(stored) {
        if (EqPresets.normalize(bands) != stored) bands = EqPresets.editable(stored)
    }
    var selected by remember { mutableStateOf(1) }
    val current by rememberUpdatedState(bands)
    val push by rememberUpdatedState(onChange)
    val done by rememberUpdatedState(onFinished)
    val colors = listOf(KColors.Accent, KColors.Keyframe, KColors.Teal, KColors.Danger, KColors.Selection)
    val labelPaint = remember { android.graphics.Paint().apply { isAntiAlias = true } }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(190.dp)
                .clip(KShapes.Tile)
                .background(KColors.Background)
                .pointerInput(Unit) {
                    val grab = 28.dp.toPx()
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val w = size.width.toFloat(); val h = size.height.toFloat()
                        val hit = current.indices.minByOrNull { i ->
                            val b = current[i]
                            hypot(down.position.x - xOfFrequency(b.frequency, w), down.position.y - yOfGain(b.gain.coerceIn(-GRAPH_DB, GRAPH_DB), h))
                        }?.takeIf { i ->
                            val b = current[i]
                            hypot(down.position.x - xOfFrequency(b.frequency, w), down.position.y - yOfGain(b.gain.coerceIn(-GRAPH_DB, GRAPH_DB), h)) <= grab
                        } ?: return@awaitEachGesture      // not on a point: leave the gesture to the list (scrolling)
                        down.consume()
                        selected = hit
                        var moved = false
                        drag(down.id) { change ->
                            change.consume()
                            moved = true
                            val next = current.toMutableList()
                            next[hit] = next[hit].copy(
                                frequency = frequencyOfX(change.position.x, w),
                                gain = gainOfY(change.position.y, h),
                            )
                            bands = next
                            push(next)
                        }
                        if (moved) done()
                    }
                },
        ) {
            val w = size.width; val h = size.height
            for (db in listOf(-12f, -6f, 0f, 6f, 12f)) {
                drawLine(if (db == 0f) KColors.LineStrong else KColors.Line, Offset(0f, yOfGain(db, h)), Offset(w, yOfGain(db, h)), strokeWidth = 1f)
            }
            labelPaint.color = KColors.TextSecondary.toArgb()
            labelPaint.textSize = 10.sp.toPx()
            for ((f, label) in listOf(100f to "100", 1000f to "1k", 10000f to "10k")) {
                val x = xOfFrequency(f, w)
                drawLine(KColors.Line, Offset(x, 0f), Offset(x, h), strokeWidth = 1f)
                drawContext.canvas.nativeCanvas.drawText(label, x + 3.dp.toPx(), h - 4.dp.toPx(), labelPaint)
            }
            for (db in listOf(-12f, 12f)) {
                drawContext.canvas.nativeCanvas.drawText("%+d".format(db.toInt()), 4.dp.toPx(), yOfGain(db, h) - 3.dp.toPx(), labelPaint)
            }
            // Combined response, live.
            val path = Path()
            val n = 160
            for (i in 0..n) {
                val f = MIN_HZ * (MAX_HZ / MIN_HZ).pow(i / n.toFloat())
                val db = StereoEq.responseDb(bands, f, MIX_RATE).coerceIn(-GRAPH_DB, GRAPH_DB)
                if (i == 0) path.moveTo(0f, yOfGain(db, h)) else path.lineTo(w * i / n, yOfGain(db, h))
            }
            drawPath(path, KColors.Accent, style = Stroke(2.5.dp.toPx()))
            // The points.
            for ((i, b) in bands.withIndex()) {
                val c = Offset(xOfFrequency(b.frequency, w), yOfGain(b.gain.coerceIn(-GRAPH_DB, GRAPH_DB), h))
                val col = colors[i % colors.size]
                if (i == selected) drawCircle(col.copy(alpha = 0.25f), 20.dp.toPx(), c)
                drawCircle(col, 11.dp.toPx(), c)
                drawCircle(KColors.Background, 4.dp.toPx(), c)
            }
        }
        // Selected band: its numbers and Q (bandwidth), a slider on a log scale (0.1 .. 10).
        val i = selected.coerceIn(0, bands.lastIndex)
        val b = bands[i]
        Text(
            "${i + 1} · ${formatHz(b.frequency)} · ${"%+.1f".format(b.gain)} dB",
            style = MaterialTheme.typography.labelMedium, color = KColors.TextSecondary,
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Q", style = MaterialTheme.typography.bodySmall, color = KColors.TextSecondary)
            val lo = ln(EqBand.MIN_Q); val hi = ln(EqBand.MAX_Q)
            Slider(
                value = ((ln(b.q) - lo) / (hi - lo)).coerceIn(0f, 1f),
                onValueChange = { v ->
                    val next = current.toMutableList()
                    next[i] = next[i].copy(q = kotlin.math.exp(lo + v * (hi - lo)))
                    bands = next
                    push(next)
                },
                onValueChangeFinished = { done() },
                modifier = Modifier.weight(1f),
            )
            Text("%.1f".format(b.q), style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(end = 4.dp))
        }
    }
}

private fun formatHz(f: Float): String = if (f >= 1000f) "%.2f kHz".format(f / 1000f) else "%d Hz".format(f.toInt())
