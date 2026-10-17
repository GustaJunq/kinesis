package dev.kinesis.editor.ui.editor

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.kinesis.core.edit.CaptionSegment
import dev.kinesis.core.edit.CaptionStyle
import dev.kinesis.core.edit.Captions
import dev.kinesis.core.model.KnsProject
import dev.kinesis.editor.R
import dev.kinesis.editor.speech.WhisperModel
import dev.kinesis.editor.ui.common.ChoiceChips
import dev.kinesis.editor.ui.common.ColorPickerDialog
import dev.kinesis.editor.ui.common.ColorSwatch
import dev.kinesis.editor.ui.common.LabeledSlider
import dev.kinesis.editor.ui.common.OverlineLabel
import dev.kinesis.editor.ui.common.PillChip
import dev.kinesis.editor.ui.common.PrimaryButton
import dev.kinesis.editor.ui.common.ScrubNumber
import dev.kinesis.editor.ui.common.SecondaryButton
import dev.kinesis.editor.ui.theme.KColors
import dev.kinesis.editor.ui.theme.KDims
import dev.kinesis.editor.ui.theme.KShapes

private val LANGUAGES = listOf("auto" to "Auto", "pt" to "Português", "en" to "English", "es" to "Español", "fr" to "Français", "de" to "Deutsch", "it" to "Italiano")

/** Automatic captions: recognise speech on the device (Whisper), then edit text, timing and look of the result. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CaptionsSheet(vm: EditorViewModel, project: KnsProject, onDismiss: () -> Unit) {
    val state by vm.captionState.collectAsState()
    val time by vm.time.collectAsState()
    val context = LocalContext.current
    val sources = remember(project) { vm.speechSources(project) }
    var source by remember(sources) { mutableStateOf(sources.firstOrNull()?.id) }
    var language by remember { mutableStateOf("auto") }
    var modelRevision by remember { mutableStateOf(0) }
    val groups = Captions.groupIds(project)
    var group by remember(groups) { mutableStateOf(groups.firstOrNull()) }
    val hasModel = remember(modelRevision, state) { vm.speechModels.best() != null }

    val importModel = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) { vm.importModel(uri); modelRevision++ } }
    val importSrt = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val text = runCatching { context.contentResolver.openInputStream(uri)?.bufferedReader()?.readText() }.getOrNull()
            if (text != null) vm.importSrt(group, text)
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = KColors.Sheet, shape = KShapes.Sheet) {
        LazyColumn(
            Modifier.fillMaxWidth().fillMaxHeight(0.86f).padding(horizontal = KDims.Gutter),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item { Text(stringResource(R.string.captions), style = MaterialTheme.typography.titleMedium) }

            // ---- generate
            item { OverlineLabel(stringResource(R.string.captions_generate)) }
            if (!vm.speechSupported) {
                item { Text(stringResource(R.string.captions_unsupported), color = KColors.TextSecondary, style = MaterialTheme.typography.bodySmall) }
            } else {
                if (sources.isEmpty()) {
                    item { Text(stringResource(R.string.captions_no_source), color = KColors.TextSecondary, style = MaterialTheme.typography.bodySmall) }
                } else {
                    item {
                        Text(stringResource(R.string.captions_source), style = MaterialTheme.typography.labelMedium, color = KColors.TextSecondary)
                        ChoiceChips(sources.map { it.id to it.name.ifBlank { it.id } }, source ?: "", { source = it })
                    }
                    item {
                        Text(stringResource(R.string.captions_language), style = MaterialTheme.typography.labelMedium, color = KColors.TextSecondary)
                        ChoiceChips(LANGUAGES, language, { language = it })
                    }
                }
                item {
                    when (val st = state) {
                        is CaptionUiState.Downloading -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(stringResource(R.string.captions_downloading, st.model.id), style = MaterialTheme.typography.bodySmall)
                            if (st.progress != null) LinearProgressIndicator(progress = { st.progress }, Modifier.fillMaxWidth(), color = KColors.Accent)
                            else LinearProgressIndicator(Modifier.fillMaxWidth(), color = KColors.Accent)
                            TextButton(onClick = { vm.cancelCaptionJob() }) { Text(stringResource(R.string.cancel)) }
                        }
                        is CaptionUiState.Transcribing -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(stringResource(R.string.captions_transcribing), style = MaterialTheme.typography.bodySmall)
                            if (st.progress != null && st.progress > 0f) LinearProgressIndicator(progress = { st.progress }, Modifier.fillMaxWidth(), color = KColors.Accent)
                            else LinearProgressIndicator(Modifier.fillMaxWidth(), color = KColors.Accent)
                            TextButton(onClick = { vm.cancelCaptionJob() }) { Text(stringResource(R.string.cancel)) }
                        }
                        else -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (st is CaptionUiState.Failed) Text(st.message, color = KColors.Danger, style = MaterialTheme.typography.bodySmall)
                            if (!hasModel) {
                                Text(stringResource(R.string.captions_need_model), style = MaterialTheme.typography.bodySmall, color = KColors.TextSecondary)
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    for (m in WhisperModel.entries) {
                                        SecondaryButton(stringResource(R.string.captions_download_model, m.id, m.sizeMb), { vm.dismissCaptionState(); vm.downloadModel(m) })
                                    }
                                }
                                TextButton(onClick = { importModel.launch(arrayOf("*/*")) }) { Text(stringResource(R.string.captions_import_model)) }
                            } else {
                                PrimaryButton(
                                    stringResource(R.string.captions_transcribe), { source?.let { vm.dismissCaptionState(); vm.transcribe(it, language) } },
                                    enabled = source != null,
                                )
                            }
                        }
                    }
                }
            }

            // ---- edit
            item { OverlineLabel(stringResource(R.string.captions_edit)) }
            if (groups.isEmpty()) {
                item { Text(stringResource(R.string.captions_empty), color = KColors.TextSecondary, style = MaterialTheme.typography.bodySmall) }
                item { TextButton(onClick = { importSrt.launch(arrayOf("*/*")) }) { Text(stringResource(R.string.captions_import_srt)) } }
            } else {
                val g = group ?: groups.first()
                val segments = Captions.segments(project, g)
                val style = Captions.style(project, g)
                if (groups.size > 1) item { ChoiceChips(groups.mapIndexed { i, id -> id to "${stringResource(R.string.captions)} ${i + 1}" }, g, { group = it }) }
                item { CaptionStyleEditor(vm, g, style) }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        TextButton(onClick = { importSrt.launch(arrayOf("*/*")) }) { Text(stringResource(R.string.captions_import_srt)) }
                        TextButton(onClick = {
                            val send = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, Captions.toSrt(segments)) }
                            runCatching { context.startActivity(Intent.createChooser(send, null)) }
                        }) { Text(stringResource(R.string.captions_export_srt)) }
                        TextButton(onClick = { vm.edit { dev.kinesis.core.model.LayerTree.remove(it, setOf(g)) } }) { Text(stringResource(R.string.captions_delete_all)) }
                    }
                }
                itemsIndexed(segments, key = { i, _ -> "$g/$i" }) { i, seg ->
                    CaptionRow(
                        seg, i, time,
                        onChange = { new -> vm.setCaptions(g, segments.toMutableList().also { it[i] = new }, "cap_$i") },
                        onFinished = { vm.endGesture() },
                        onDelete = { vm.setCaptions(g, segments.filterIndexed { j, _ -> j != i }) },
                        onSplit = { vm.setCaptions(g, Captions.split(segments, i, time)) },
                        onMerge = if (i + 1 < segments.size) ({ vm.setCaptions(g, Captions.merge(segments, i)) }) else null,
                    )
                }
            }
            item { Spacer(Modifier.padding(bottom = 24.dp)) }
        }
    }
}

@Composable
private fun CaptionRow(
    seg: CaptionSegment,
    index: Int,
    playhead: Double,
    onChange: (CaptionSegment) -> Unit,
    onFinished: () -> Unit,
    onDelete: () -> Unit,
    onSplit: () -> Unit,
    onMerge: (() -> Unit)?,
) {
    var text by remember(seg.text) { mutableStateOf(seg.text) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("${index + 1}", color = KColors.TextSecondary, style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(24.dp))
            ScrubNumber(seg.start.toFloat(), { onChange(seg.copy(start = it.toDouble())) }, onFinished, Modifier.width(76.dp), step = 0.1f, min = 0f, suffix = " s")
            Text("→", color = KColors.TextSecondary)
            ScrubNumber(seg.end.toFloat(), { onChange(seg.copy(end = it.toDouble())) }, onFinished, Modifier.width(76.dp), step = 0.1f, min = 0f, suffix = " s")
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onDelete) { Text(stringResource(R.string.delete)) }
        }
        OutlinedTextField(
            value = text,
            onValueChange = { text = it; onChange(seg.copy(text = it)) },
            modifier = Modifier.fillMaxWidth(),
            minLines = 1,
            maxLines = 4,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            if (playhead > seg.start + 0.05 && playhead < seg.end - 0.05) TextButton(onClick = onSplit) { Text(stringResource(R.string.captions_split)) }
            if (onMerge != null) TextButton(onClick = onMerge) { Text(stringResource(R.string.captions_merge)) }
        }
    }
}

@Composable
private fun CaptionStyleEditor(vm: EditorViewModel, groupId: String, style: CaptionStyle) {
    var colorPicker by remember { mutableStateOf<String?>(null) }
    fun set(s: CaptionStyle) = vm.setCaptionStyle(groupId, s, "capstyle")
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        LabeledSlider(stringResource(R.string.captions_size), style.size, 0.02f..0.2f, { set(style.copy(size = it)) }, { vm.endGesture() }, display = 100f, suffix = "%", min = 0.02f, max = 0.2f)
        LabeledSlider(stringResource(R.string.captions_position), style.bottom, 0.02f..0.9f, { set(style.copy(bottom = it)) }, { vm.endGesture() }, display = 100f, suffix = "%", min = 0.02f, max = 0.9f)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.captions_text_color), style = MaterialTheme.typography.bodySmall, color = KColors.TextSecondary)
            ColorSwatch(style.color, { colorPicker = "text" })
            PillChip(stringResource(R.string.captions_outline), style.outline != null, { vm.setCaptionStyle(groupId, style.copy(outline = if (style.outline != null) null else dev.kinesis.core.math.Color(0f, 0f, 0f, 1f))) })
            style.outline?.let { ColorSwatch(it, { colorPicker = "outline" }) }
        }
        ChoiceChips(
            listOf(500 to stringResource(R.string.captions_weight_medium), 800 to stringResource(R.string.captions_weight_bold), 900 to stringResource(R.string.captions_weight_black)),
            style.weight, { vm.setCaptionStyle(groupId, style.copy(weight = it)) },
        )
    }
    when (colorPicker) {
        "text" -> ColorPickerDialog(style.color, { colorPicker = null }, { set(style.copy(color = it)) }, { vm.endGesture(); colorPicker = null })
        "outline" -> style.outline?.let { o -> ColorPickerDialog(o, { colorPicker = null }, { set(style.copy(outline = it)) }, { vm.endGesture(); colorPicker = null }) }
    }
}
