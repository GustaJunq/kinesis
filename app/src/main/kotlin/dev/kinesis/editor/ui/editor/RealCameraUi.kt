package dev.kinesis.editor.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.kinesis.core.model.KnsProject
import dev.kinesis.core.model.LayerTree
import dev.kinesis.editor.R
import dev.kinesis.editor.ui.common.LabeledSlider
import dev.kinesis.editor.ui.common.OverlineLabel
import dev.kinesis.editor.ui.common.PillChip
import dev.kinesis.editor.ui.theme.KColors
import dev.kinesis.editor.ui.theme.KDims
import dev.kinesis.editor.ui.theme.KShapes

/** Setup and review of a Real Camera take. It leaves the screen while recording so the preview can be seen. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RealCameraSheet(vm: EditorViewModel, project: KnsProject, onDismiss: () -> Unit) {
    val controller = vm.realCamera
    val state by controller.state.collectAsState()
    val settings by controller.settings.collectAsState()
    if (state is RealCameraState.Countdown || state is RealCameraState.Recording) return
    val target = controller.targetId()?.let { LayerTree.find(project, it) }

    ModalBottomSheet(
        onDismissRequest = {
            if (state is RealCameraState.Review) controller.accept()
            controller.dismissError()
            onDismiss()
        },
        containerColor = KColors.Sheet, shape = KShapes.Sheet,
    ) {
        LazyColumn(Modifier.fillMaxWidth().padding(horizontal = KDims.Gutter), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                Text(stringResource(R.string.real_camera), style = MaterialTheme.typography.titleMedium)
                Text(
                    stringResource(R.string.real_camera_hint, target?.let { layerDisplayName(it) } ?: stringResource(R.string.layer_camera)),
                    style = MaterialTheme.typography.bodySmall, color = KColors.TextSecondary,
                )
            }
            (state as? RealCameraState.Failed)?.let { failed ->
                item { Text(failed.message, color = KColors.Danger, style = MaterialTheme.typography.bodySmall) }
            }
            (state as? RealCameraState.Review)?.let { review ->
                item {
                    Text(stringResource(R.string.real_camera_result, review.samples, review.keyframes), color = KColors.Accent, style = MaterialTheme.typography.bodyMedium)
                }
            }
            item { OverlineLabel(stringResource(R.string.real_camera_follow)) }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PillChip("X", settings.axes.x, { controller.update(settings.copy(axes = settings.axes.copy(x = !settings.axes.x))) })
                    PillChip("Y", settings.axes.y, { controller.update(settings.copy(axes = settings.axes.copy(y = !settings.axes.y))) })
                    PillChip("Z", settings.axes.z, { controller.update(settings.copy(axes = settings.axes.copy(z = !settings.axes.z))) })
                }
                Text(stringResource(R.string.real_camera_axes_hint), style = MaterialTheme.typography.bodySmall, color = KColors.TextSecondary)
            }
            item {
                LabeledSlider(
                    stringResource(R.string.real_camera_intensity), settings.intensity, 0.1f..3f,
                    { controller.update(settings.copy(intensity = it)) }, {}, suffix = "×",
                )
            }
            item { OverlineLabel(stringResource(R.string.real_camera_curve)) }
            item {
                LabeledSlider(
                    stringResource(R.string.real_camera_smoothing), settings.smoothing, 0f..1f,
                    { controller.update(settings.copy(smoothing = it)) }, {}, display = 100f, suffix = "%",
                )
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.real_camera_simplify))
                        Text(stringResource(R.string.real_camera_simplify_hint), style = MaterialTheme.typography.bodySmall, color = KColors.TextSecondary)
                    }
                    Switch(settings.simplify, { controller.update(settings.copy(simplify = it)) })
                }
            }
            if (settings.simplify) item {
                LabeledSlider(
                    stringResource(R.string.real_camera_tolerance), settings.toleranceDegrees, 0.05f..3f,
                    { controller.update(settings.copy(toleranceDegrees = it)) }, {}, suffix = "°",
                )
            }
            item { OverlineLabel(stringResource(R.string.real_camera_options)) }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.real_camera_calibrate_late))
                        Text(stringResource(R.string.real_camera_calibrate_late_hint), style = MaterialTheme.typography.bodySmall, color = KColors.TextSecondary)
                    }
                    Switch(settings.calibrateAtStart, { controller.update(settings.copy(calibrateAtStart = it)) })
                }
            }
            if (controller.hasAcceleration) item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.real_camera_position))
                        Text(stringResource(R.string.real_camera_position_hint), style = MaterialTheme.typography.bodySmall, color = KColors.TextSecondary)
                    }
                    Switch(settings.recordPosition, { controller.update(settings.copy(recordPosition = it)) })
                }
            }
            item {
                Row(Modifier.fillMaxWidth().padding(bottom = 18.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (state is RealCameraState.Review) {
                        TextButton(onClick = { controller.discard(); onDismiss() }) { Text(stringResource(R.string.real_camera_discard)) }
                        Box(Modifier.weight(1f))
                        TextButton(onClick = { controller.start() }) { Text(stringResource(R.string.real_camera_again)) }
                        Button(onClick = { controller.accept(); onDismiss() }) { Text(stringResource(R.string.real_camera_keep)) }
                    } else {
                        Box(Modifier.weight(1f))
                        Button(
                            onClick = { controller.start() },
                            colors = ButtonDefaults.buttonColors(containerColor = KColors.Danger),
                        ) { Text(stringResource(R.string.real_camera_record)) }
                    }
                }
            }
        }
    }
}

/** Countdown and stop button over the preview while a take runs. */
@Composable
fun RealCameraOverlay(vm: EditorViewModel, modifier: Modifier = Modifier) {
    val controller = vm.realCamera
    val state by controller.state.collectAsState()
    when (val s = state) {
        is RealCameraState.Countdown -> Box(modifier, contentAlignment = Alignment.Center) {
            Text(s.count.toString(), fontSize = 120.sp, fontWeight = FontWeight.Bold, color = KColors.White.copy(alpha = 0.92f))
            TextButton(onClick = { controller.cancelCountdown() }, modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp)) {
                Text(stringResource(R.string.cancel))
            }
        }
        is RealCameraState.Recording -> Box(modifier, contentAlignment = Alignment.BottomCenter) {
            Row(
                Modifier.padding(bottom = 14.dp).background(KColors.Bar.copy(alpha = 0.9f), KShapes.Tile).padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(Modifier.size(12.dp).background(KColors.Danger, CircleShape))
                Text(stringResource(R.string.real_camera_recording, "%.1f".format(s.seconds)), color = KColors.TextPrimary)
                Button(onClick = { controller.stop() }, colors = ButtonDefaults.buttonColors(containerColor = KColors.Danger)) {
                    Text(stringResource(R.string.real_camera_stop))
                }
            }
        }
        else -> Unit
    }
}
