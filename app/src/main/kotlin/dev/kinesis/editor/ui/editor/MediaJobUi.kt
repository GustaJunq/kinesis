package dev.kinesis.editor.ui.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.kinesis.editor.R
import dev.kinesis.editor.media.VideoStabilizer
import dev.kinesis.editor.ui.common.LabeledSlider
import dev.kinesis.editor.ui.theme.KColors
import kotlin.math.roundToInt

/** Progress of a background removal or stabilization, with a cancel button. Not dismissable while it runs. */
@Composable
fun MediaJobDialog(job: MediaJobUi, onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = {},
        title = { Text(stringResource(if (job.kind == MediaJobUi.Kind.BACKGROUND) R.string.cutout_running else R.string.stabilize_running)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                LinearProgressIndicator(progress = { job.progress }, modifier = Modifier.fillMaxWidth())
                Text("${(job.progress * 100).roundToInt()}%", fontSize = 12.sp, color = KColors.TextSecondary)
                Text(
                    stringResource(if (job.kind == MediaJobUi.Kind.BACKGROUND) R.string.cutout_hint else R.string.stabilize_hint),
                    fontSize = 12.sp, color = KColors.TextSecondary,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onCancel, enabled = !job.cancelling) {
                Text(stringResource(if (job.cancelling) R.string.cancelling else R.string.cancel))
            }
        },
    )
}

/** Options of the stabilization: how much to smooth the camera path and whether to zoom in to hide the borders. */
@Composable
fun StabilizeDialog(onStart: (VideoStabilizer.Settings) -> Unit, onDismiss: () -> Unit) {
    var smoothing by remember { mutableFloatStateOf(12f) }
    var shakiness by remember { mutableFloatStateOf(5f) }
    var autoZoom by remember { mutableStateOf(true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.stabilize)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                LabeledSlider(stringResource(R.string.stabilize_smoothing), smoothing, 1f..30f, { smoothing = it }, {})
                Text(stringResource(R.string.stabilize_smoothing_hint), fontSize = 12.sp, color = KColors.TextSecondary)
                LabeledSlider(stringResource(R.string.stabilize_shakiness), shakiness, 1f..10f, { shakiness = it }, {})
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.stabilize_autozoom))
                        Text(stringResource(R.string.stabilize_autozoom_hint), fontSize = 12.sp, color = KColors.TextSecondary)
                    }
                    Switch(checked = autoZoom, onCheckedChange = { autoZoom = it })
                }
                Text(stringResource(R.string.stabilize_license), style = MaterialTheme.typography.bodySmall, color = KColors.TextSecondary)
            }
        },
        confirmButton = {
            Button(onClick = { onStart(VideoStabilizer.Settings(smoothing.roundToInt(), autoZoom, shakiness.roundToInt())) }) { Text(stringResource(R.string.stabilize_start)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
