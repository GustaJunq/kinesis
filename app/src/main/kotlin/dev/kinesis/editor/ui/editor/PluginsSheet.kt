@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package dev.kinesis.editor.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.kinesis.core.model.KnsProject
import dev.kinesis.core.plugin.PluginCommand
import dev.kinesis.core.plugin.Preset
import dev.kinesis.core.plugin.PresetKind
import dev.kinesis.core.plugin.Presets
import dev.kinesis.editor.R
import dev.kinesis.editor.plugins.InstalledPlugin
import dev.kinesis.editor.plugins.KokoroTts
import dev.kinesis.editor.plugins.OfficialPlugins
import dev.kinesis.editor.plugins.PluginStore
import dev.kinesis.editor.plugins.SystemTts
import dev.kinesis.editor.plugins.TtsEngine
import dev.kinesis.editor.plugins.TtsVoice
import dev.kinesis.editor.ui.common.LabeledSlider
import dev.kinesis.editor.ui.common.Lucide
import dev.kinesis.editor.ui.common.OverlineLabel
import dev.kinesis.editor.ui.common.PillChip
import dev.kinesis.editor.ui.common.PrimaryButton
import dev.kinesis.editor.ui.common.SecondaryButton
import dev.kinesis.editor.ui.plugins.PluginsScreen
import dev.kinesis.editor.ui.theme.KColors
import dev.kinesis.editor.ui.theme.KDims
import dev.kinesis.editor.ui.theme.KShapes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import java.io.File

/** The editor's plugin panel: text to speech, the presets and the commands of the active plugins. */
@Composable
fun PluginsSheet(vm: EditorViewModel, project: KnsProject, selection: String?, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val store = remember { PluginStore(context) }
    var revision by remember { mutableStateOf(0) }
    var presets by remember { mutableStateOf<List<Preset>>(emptyList()) }
    var commands by remember { mutableStateOf<List<Pair<InstalledPlugin, PluginCommand>>>(emptyList()) }
    var ttsAvailable by remember { mutableStateOf(false) }
    var showManager by remember { mutableStateOf(false) }
    var showTts by remember { mutableStateOf(false) }
    var running by remember { mutableStateOf<Pair<InstalledPlugin, PluginCommand>?>(null) }
    LaunchedEffect(revision) {
        withContext(Dispatchers.IO) {
            presets = store.presets()
            commands = store.commands()
            ttsAvailable = store.find(OfficialPlugins.TTS_ID)?.active == true
        }
    }
    val layer = selection?.let { dev.kinesis.core.model.LayerTree.find(project, it) }

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = KColors.Sheet, shape = KShapes.Sheet) {
        LazyColumn(Modifier.fillMaxWidth().fillMaxHeight(0.8f).padding(horizontal = KDims.Gutter), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item { Text(stringResource(R.string.plugins), style = MaterialTheme.typography.titleMedium) }
            if (ttsAvailable) {
                item {
                    PrimaryButton(stringResource(R.string.tts_title), { showTts = true }, Modifier.fillMaxWidth(), icon = Lucide.Music)
                }
            }
            if (commands.isNotEmpty()) {
                item { OverlineLabel(stringResource(R.string.plugin_commands)) }
                commands.forEach { (p, c) ->
                    item {
                        Row(
                            Modifier.fillMaxWidth().clip(KShapes.Tile).background(KColors.Card).clickable { running = p to c }.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Lucide.Plug, null, tint = KColors.Accent, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Column(Modifier.weight(1f)) {
                                Text(c.title, style = MaterialTheme.typography.bodyMedium)
                                Text(p.manifest.name, style = MaterialTheme.typography.bodySmall, color = KColors.TextSecondary)
                            }
                        }
                    }
                }
            }
            listOf(
                PresetKind.ANIMATION to R.string.preset_animations,
                PresetKind.TEXT_STYLE to R.string.preset_text_styles,
                PresetKind.TRANSITION to R.string.preset_transitions,
            ).forEach { (kind, label) ->
                val list = presets.filter { it.kind == kind }
                if (list.isNotEmpty()) {
                    item { OverlineLabel(stringResource(label)) }
                    item {
                        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            list.forEach { pr ->
                                PillChip(pr.name, false, { vm.applyPreset(pr) }, enabled = layer != null && Presets.appliesTo(pr, layer))
                            }
                        }
                    }
                }
            }
            if (presets.isNotEmpty() && layer == null) {
                item { Text(stringResource(R.string.plugin_select_layer), color = KColors.TextSecondary, style = MaterialTheme.typography.bodySmall) }
            }
            if (presets.isEmpty() && commands.isEmpty() && !ttsAvailable) {
                item { Text(stringResource(R.string.plugins_none), color = KColors.TextSecondary, style = MaterialTheme.typography.bodySmall) }
            }
            item { SecondaryButton(stringResource(R.string.plugins_manage), { showManager = true }, Modifier.fillMaxWidth(), icon = Lucide.Settings, pill = true) }
            item { Spacer(Modifier.padding(8.dp)) }
        }
    }
    if (showManager) PluginsScreen(onDismiss = { showManager = false; revision++ })
    if (showTts) TtsDialog(vm, onDismiss = { showTts = false })
    running?.let { (p, c) ->
        if (c.params.isEmpty()) {
            LaunchedEffect(c) { vm.runPluginCommand(p, c, emptyMap()); running = null; onDismiss() }
        } else {
            CommandDialog(c, onRun = { params -> running = null; vm.runPluginCommand(p, c, params); onDismiss() }, onDismiss = { running = null })
        }
    }
}

/** The form of a command's parameters. */
@Composable
private fun CommandDialog(command: PluginCommand, onRun: (Map<String, JsonElement>) -> Unit, onDismiss: () -> Unit) {
    val values = remember { mutableStateMapOf<String, JsonElement>().apply { command.params.forEach { put(it.id, it.default) } } }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = KColors.Sheet,
        shape = KShapes.Card,
        title = { Text(command.title, style = MaterialTheme.typography.titleMedium) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                command.params.forEach { p ->
                    val v = values[p.id]
                    when (p.type) {
                        "number" -> {
                            val min = p.min; val max = p.max
                            val current = (v as? JsonPrimitive)?.doubleOrNull ?: min ?: 0.0
                            if (min != null && max != null) {
                                LabeledSlider(p.label, current.toFloat(), min.toFloat()..max.toFloat(), { values[p.id] = JsonPrimitive(it.toDouble()) }, {})
                            } else {
                                var text by remember { mutableStateOf(current.toString()) }
                                OutlinedTextField(text, { text = it; it.toDoubleOrNull()?.let { d -> values[p.id] = JsonPrimitive(d) } }, label = { Text(p.label) },
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true)
                            }
                        }
                        "boolean" -> Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox((v as? JsonPrimitive)?.booleanOrNull == true, { values[p.id] = JsonPrimitive(it) }, colors = CheckboxDefaults.colors(checkedColor = KColors.Accent))
                            Text(p.label)
                        }
                        "choice" -> Column {
                            Text(p.label, style = MaterialTheme.typography.bodySmall, color = KColors.TextSecondary)
                            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                p.options.forEach { o -> PillChip(o, (v as? JsonPrimitive)?.content == o, { values[p.id] = JsonPrimitive(o) }) }
                            }
                        }
                        else -> OutlinedTextField((v as? JsonPrimitive)?.content.orEmpty(), { values[p.id] = JsonPrimitive(it) }, label = { Text(p.label) })
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onRun(values.toMap()) }) { Text(stringResource(R.string.plugin_run), color = KColors.Accent) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel), color = KColors.TextSoft) } },
    )
}

/**
 * Text to speech (official plugin): text, voice and speed; the audio becomes an audio layer at the playhead. Voices
 * come from the phone and, when the Kokoro pack is installed, from Kokoro. Everything runs offline.
 */
@Composable
fun TtsDialog(vm: EditorViewModel, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val engines = remember {
        val store = PluginStore(context)
        listOfNotNull<TtsEngine>(KokoroTts.fromStore(context, store), SystemTts(context))
    }
    DisposableEffect(Unit) { onDispose { engines.forEach { runCatching { it.close() } } } }
    var voices by remember { mutableStateOf<List<TtsVoice>?>(null) }
    var voice by remember { mutableStateOf<TtsVoice?>(null) }
    var text by remember { mutableStateOf("") }
    var speed by remember { mutableStateOf(1f) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        val all = engines.flatMap { e -> runCatching { e.voices() }.getOrDefault(emptyList()) }
        voices = all
        voice = all.firstOrNull { it.locale.language == "pt" } ?: all.firstOrNull()
    }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        containerColor = KColors.Sheet,
        shape = KShapes.Card,
        title = { Text(stringResource(R.string.tts_title), style = MaterialTheme.typography.titleMedium) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(text, { text = it.take(4000) }, label = { Text(stringResource(R.string.tts_text)) }, modifier = Modifier.fillMaxWidth().heightIn(min = 100.dp))
                OverlineLabel(stringResource(R.string.tts_voice))
                val vs = voices
                when {
                    vs == null -> Text(stringResource(R.string.tts_loading), color = KColors.TextSecondary)
                    vs.isEmpty() -> Text(stringResource(R.string.tts_no_voices), color = KColors.TextSecondary, style = MaterialTheme.typography.bodySmall)
                    else -> Column(Modifier.heightIn(max = 220.dp).verticalScroll(rememberScrollState()).border(1.dp, KColors.Line, KShapes.Tile)) {
                        vs.forEach { v ->
                            val engine = engines.first { it.id == v.engine }
                            Row(
                                Modifier.fillMaxWidth().background(if (v == voice) KColors.Field else KColors.Sheet).clickable { voice = v }.padding(10.dp),
                            ) {
                                Column {
                                    Text(v.label, style = MaterialTheme.typography.bodyMedium, color = if (v == voice) KColors.Accent else KColors.TextPrimary)
                                    Text(engine.name, style = MaterialTheme.typography.bodySmall, color = KColors.TextSecondary)
                                }
                            }
                        }
                    }
                }
                LabeledSlider(stringResource(R.string.tts_speed), speed, 0.5f..2f, { speed = (it * 20).toInt() / 20f }, {}, suffix = "×")
                error?.let { Text(it, color = KColors.Danger, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(enabled = !busy && text.isNotBlank() && voice != null, onClick = {
                val v = voice ?: return@TextButton
                busy = true
                error = null
                scope.launch {
                    val out = File(context.cacheDir, "tts/fala_${System.currentTimeMillis()}.wav")
                    try {
                        engines.first { it.id == v.engine }.synthesize(text.trim(), v, speed, out)
                        val name = context.getString(R.string.tts_layer_name, text.trim().take(24))
                        vm.addSpeech(out, name) { onDismiss() }
                    } catch (e: Exception) {
                        error = e.message ?: e.javaClass.simpleName
                        withContext(Dispatchers.IO) { out.delete() }
                    } finally {
                        busy = false
                    }
                }
            }) { Text(stringResource(if (busy) R.string.tts_generating else R.string.tts_generate), color = KColors.Accent) }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text(stringResource(R.string.cancel), color = KColors.TextSoft) } },
    )
}
