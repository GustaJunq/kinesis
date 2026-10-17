package dev.kinesis.editor.ui.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.background
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.kinesis.core.audio.EqPresets
import dev.kinesis.core.audio.StereoEq
import dev.kinesis.core.model.EqBand
import dev.kinesis.core.model.EqBandType
import dev.kinesis.core.model.KnsProject
import dev.kinesis.core.model.Layer
import dev.kinesis.editor.R
import dev.kinesis.editor.media.MIX_RATE
import dev.kinesis.editor.ui.common.LabeledSlider
import dev.kinesis.editor.ui.common.OverlineLabel
import dev.kinesis.editor.ui.common.PillChip
import dev.kinesis.editor.ui.common.ScrubNumber
import dev.kinesis.editor.ui.common.SecondaryButton
import dev.kinesis.editor.ui.theme.KColors
import dev.kinesis.editor.ui.theme.KDims
import dev.kinesis.editor.ui.theme.KShapes
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow

/**
 * Audio panel of a video or audio layer: waveform, volume, fades and a parametric equalizer. Everything is stored
 * in the clip (non-destructive) and heard in the preview while the project plays, and in the exported video.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AudioSheet(vm: EditorViewModel, project: KnsProject, layer: Layer, onDismiss: () -> Unit) {
    val media = layer.media ?: return
    val playing by vm.playing.collectAsState()
    val audioRevision by vm.audioRevision.collectAsState()
    val pcm = remember(media.asset, audioRevision) { vm.pcmOf(media.asset) }
    val bands = EqPresets.editable(media.eq)
    val key = "audio:${layer.id}"

    fun setBands(new: List<EqBand>, coalesce: Boolean) =
        vm.updateMedia(layer.id, if (coalesce) "$key:eq" else null) { it.copy(eq = EqPresets.normalize(new)) }

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = KColors.Sheet, shape = KShapes.Sheet) {
        LazyColumn(
            Modifier.fillMaxWidth().fillMaxHeight(0.86f).padding(horizontal = KDims.Gutter),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text(layerDisplayName(layer), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    // Play from here to hear the changes while the sheet is open.
                    SecondaryButton(
                        stringResource(if (playing) R.string.pause else R.string.play), { vm.togglePlay() },
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

            // ---- equalizer
            item { OverlineLabel(stringResource(R.string.equalizer)) }
            item {
                androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    val presets = listOf(
                        R.string.eq_flat to EqPresets.flat(), R.string.eq_bass_boost to EqPresets.bassBoost(),
                        R.string.eq_treble_boost to EqPresets.trebleBoost(), R.string.eq_vocal to EqPresets.vocal(),
                    )
                    items(presets.size) { i ->
                        val (label, preset) = presets[i]
                        PillChip(stringResource(label), media.eq == EqPresets.normalize(preset), { setBands(preset, false) })
                    }
                }
            }
            item { EqCurve(bands) }
            itemsIndexed(bands, key = { i, _ -> "eq$i" }) { i, b ->
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        when (b.type) { EqBandType.LOW_SHELF -> "⌊"; EqBandType.HIGH_SHELF -> "⌉"; else -> "◠" },
                        color = KColors.TextSecondary, modifier = Modifier.width(18.dp),
                    )
                    ScrubNumber(b.frequency, { v -> setBands(bands.toMutableList().also { it[i] = b.copy(frequency = v.coerceIn(EqBand.MIN_FREQUENCY, EqBand.MAX_FREQUENCY)) }, true) }, { vm.endGesture() },
                        Modifier.weight(1f), step = max(b.frequency / 40f, 1f), min = EqBand.MIN_FREQUENCY, max = EqBand.MAX_FREQUENCY, suffix = " Hz")
                    ScrubNumber(b.gain, { v -> setBands(bands.toMutableList().also { it[i] = b.copy(gain = v.coerceIn(-EqBand.MAX_GAIN, EqBand.MAX_GAIN)) }, true) }, { vm.endGesture() },
                        Modifier.weight(1f), step = 0.5f, min = -EqBand.MAX_GAIN, max = EqBand.MAX_GAIN, suffix = " dB")
                    ScrubNumber(b.q, { v -> setBands(bands.toMutableList().also { it[i] = b.copy(q = v.coerceIn(EqBand.MIN_Q, EqBand.MAX_Q)) }, true) }, { vm.endGesture() },
                        Modifier.weight(0.8f), step = 0.1f, min = EqBand.MIN_Q, max = EqBand.MAX_Q, suffix = " Q")
                }
            }
            item {
                TextButton(onClick = { setBands(EqPresets.flat(), false) }, enabled = media.eq.isNotEmpty()) { Text(stringResource(R.string.eq_reset)) }
            }
            item { androidx.compose.foundation.layout.Spacer(Modifier.padding(bottom = 24.dp)) }
        }
    }
}

/** Frequency response of the bands, 20 Hz–20 kHz (log) by ±18 dB. */
@Composable
private fun EqCurve(bands: List<EqBand>) {
    Canvas(Modifier.fillMaxWidth().height(110.dp).clip(KShapes.Tile).background(KColors.Background)) {
        val w = size.width; val h = size.height
        fun y(db: Float) = h / 2 - db / 18f * (h / 2)
        for (db in listOf(-12f, -6f, 0f, 6f, 12f)) {
            drawLine(if (db == 0f) KColors.LineStrong else KColors.Line, Offset(0f, y(db)), Offset(w, y(db)), strokeWidth = 1f)
        }
        for (f in listOf(100f, 1000f, 10000f)) {
            val x = w * (ln(f / 20f) / ln(1000f))
            drawLine(KColors.Line, Offset(x, 0f), Offset(x, h), strokeWidth = 1f)
        }
        val path = Path()
        val n = 120
        for (i in 0..n) {
            val f = 20f * 1000f.pow(i / n.toFloat())
            val db = StereoEq.responseDb(bands, f, MIX_RATE).coerceIn(-18f, 18f)
            if (i == 0) path.moveTo(0f, y(db)) else path.lineTo(w * i / n, y(db))
        }
        drawPath(path, KColors.Accent, style = Stroke(2.5.dp.toPx()))
    }
}
