package dev.kinesis.editor.ui.plugins

import android.content.ContentResolver
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.kinesis.core.plugin.PluginType
import dev.kinesis.editor.R
import dev.kinesis.editor.plugins.InstalledPlugin
import dev.kinesis.editor.plugins.PluginStore
import dev.kinesis.editor.plugins.StagedPlugin
import dev.kinesis.editor.ui.common.Lucide
import dev.kinesis.editor.ui.common.OverlineLabel
import dev.kinesis.editor.ui.common.PrimaryButton
import dev.kinesis.editor.ui.common.SecondaryButton
import dev.kinesis.editor.ui.theme.KColors
import dev.kinesis.editor.ui.theme.KDims
import dev.kinesis.editor.ui.theme.KShapes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.util.Locale

/** True when [uri] is a plugin package: its name ends in `.knsplugin` or its first zip entry is `manifest.json`. */
fun isPluginPackage(resolver: ContentResolver, uri: Uri): Boolean {
    if (uri.lastPathSegment?.lowercase(Locale.ROOT)?.endsWith(".knsplugin") == true) return true
    // A package is recognised by `manifest.json` among the first entries (it is usually the first or second).
    return runCatching {
        resolver.openInputStream(uri)?.use { raw ->
            java.util.zip.ZipInputStream(BufferedInputStream(raw)).use { z ->
                var n = 0
                var found = false
                while (n++ < 16) {
                    val e = z.nextEntry ?: break
                    if (e.name == "manifest.json") { found = true; break }
                }
                found
            }
        } ?: false
    }.getOrDefault(false)
}

private fun sizeLabel(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> String.format(Locale.ROOT, "%.1f MB", bytes / 1024.0 / 1024.0)
    bytes >= 1024 -> "${bytes / 1024} KB"
    else -> "$bytes B"
}

@Composable
private fun typeLabel(t: PluginType) = stringResource(
    when (t) {
        PluginType.PRESETS -> R.string.plugin_type_presets
        PluginType.SCRIPT -> R.string.plugin_type_script
        PluginType.NATIVE -> R.string.plugin_type_native
        PluginType.DATA -> R.string.plugin_type_data
    },
)

/**
 * Asks before installing [staged]: what it is, who made it, its size and every permission it wants. Plugins never get
 * the network, the file system or other apps, and the dialog says so.
 */
@Composable
fun InstallPluginDialog(staged: StagedPlugin, onInstall: () -> Unit, onCancel: () -> Unit) {
    val m = staged.pkg.manifest
    AlertDialog(
        onDismissRequest = onCancel,
        containerColor = KColors.Sheet,
        shape = KShapes.Card,
        icon = { Icon(Lucide.Plug, null, tint = KColors.Accent) },
        title = { Text(stringResource(if (staged.replaces != null) R.string.plugin_update_title else R.string.plugin_install_title), style = MaterialTheme.typography.titleMedium) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("${m.name} ${m.version}", style = MaterialTheme.typography.titleSmall)
                Text(stringResource(R.string.plugin_by, m.author) + " · " + typeLabel(m.type) + " · " + sizeLabel(staged.pkg.totalBytes), color = KColors.TextSecondary, style = MaterialTheme.typography.bodySmall)
                staged.replaces?.let { Text(stringResource(R.string.plugin_replaces, it.manifest.version), color = KColors.TextSecondary, style = MaterialTheme.typography.bodySmall) }
                if (m.description.isNotBlank()) Text(m.description, color = KColors.TextSoft, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(4.dp))
                OverlineLabel(stringResource(R.string.plugin_permissions))
                if (m.permissions.isEmpty()) {
                    Text(stringResource(R.string.plugin_no_permissions), color = KColors.TextSoft, style = MaterialTheme.typography.bodyMedium)
                } else {
                    m.permissions.forEach { Text("• " + stringResource(PluginStore.describe(it)), color = KColors.TextSoft, style = MaterialTheme.typography.bodyMedium) }
                }
                if (m.type == PluginType.SCRIPT) Text(stringResource(R.string.plugin_script_isolated), color = KColors.TextSecondary, style = MaterialTheme.typography.bodySmall)
                Text(stringResource(R.string.plugin_offline_note), color = KColors.TextSecondary, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = onInstall) { Text(stringResource(R.string.plugin_install), color = KColors.Accent) } },
        dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel), color = KColors.TextSoft) } },
    )
}

/**
 * Installs a plugin package from [uri] after the user accepts its permissions. [onDone] gets a message for a
 * snackbar (installed, or why it was refused).
 */
@Composable
fun PluginInstaller(uri: Uri?, onFinished: (String?) -> Unit) {
    val context = LocalContext.current
    val store = remember { PluginStore(context) }
    val scope = rememberCoroutineScope()
    var staged by remember { mutableStateOf<StagedPlugin?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(uri) {
        if (uri == null) return@LaunchedEffect
        try {
            staged = withContext(Dispatchers.IO) {
                (context.contentResolver.openInputStream(uri) ?: error("cannot open")).use { store.stage(it) }
            }
        } catch (e: Exception) {
            error = e.message ?: e.javaClass.simpleName
        }
    }
    staged?.let { s ->
        InstallPluginDialog(
            s,
            onInstall = {
                staged = null
                scope.launch {
                    val msg = runCatching { withContext(Dispatchers.IO) { store.install(s) } }
                        .fold({ context.getString(R.string.plugin_installed, s.pkg.manifest.name) }, { context.getString(R.string.plugin_install_failed, it.message ?: "") })
                    onFinished(msg)
                }
            },
            onCancel = {
                staged = null
                scope.launch(Dispatchers.IO) { store.discard(s) }
                onFinished(null)
            },
        )
    }
    error?.let { e ->
        AlertDialog(
            onDismissRequest = { error = null; onFinished(null) },
            containerColor = KColors.Sheet,
            shape = KShapes.Card,
            icon = { Icon(Lucide.Plug, null, tint = KColors.Danger) },
            title = { Text(stringResource(R.string.plugin_invalid_title), style = MaterialTheme.typography.titleMedium) },
            text = { Text(e, color = KColors.TextSoft, style = MaterialTheme.typography.bodyMedium) },
            confirmButton = { TextButton(onClick = { error = null; onFinished(null) }) { Text(stringResource(R.string.ok), color = KColors.Accent) } },
        )
    }
}

/** The plugin manager: list, enable/disable, uninstall, reinstall official plugins, install from a file. */
@Composable
fun PluginsScreen(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val store = remember { PluginStore(context) }
    val scope = rememberCoroutineScope()
    var revision by remember { mutableStateOf(0) }
    var plugins by remember { mutableStateOf<List<InstalledPlugin>?>(null) }
    var installUri by remember { mutableStateOf<Uri?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var confirmRemove by remember { mutableStateOf<InstalledPlugin?>(null) }
    LaunchedEffect(revision) { plugins = withContext(Dispatchers.IO) { store.list() } }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) installUri = uri }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(KColors.Background).safeDrawingPadding()) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(KDims.Gutter), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onDismiss) { Icon(Lucide.ArrowLeft, stringResource(R.string.close), tint = KColors.TextPrimary) }
                    Text(stringResource(R.string.plugins), style = MaterialTheme.typography.titleLarge)
                }
                Text(stringResource(R.string.plugins_hint), color = KColors.TextSecondary, style = MaterialTheme.typography.bodySmall)
                PrimaryButton(stringResource(R.string.plugin_install_file), { picker.launch(arrayOf("*/*")) }, Modifier.fillMaxWidth())
                message?.let { Text(it, color = KColors.Accent, style = MaterialTheme.typography.bodySmall) }
                val list = plugins
                if (list == null) {
                    CircularProgressIndicator(color = KColors.Accent)
                } else {
                    list.forEach { p ->
                        PluginCard(
                            p,
                            onToggle = { on -> scope.launch { withContext(Dispatchers.IO) { store.setEnabled(p.id, on) }; revision++ } },
                            onRemove = { confirmRemove = p },
                            onReinstall = { scope.launch { withContext(Dispatchers.IO) { store.reinstallOfficial(p.id) }; revision++ } },
                        )
                    }
                }
            }
        }
        installUri?.let { uri ->
            PluginInstaller(uri) { msg ->
                installUri = null
                message = msg
                revision++
            }
        }
        confirmRemove?.let { p ->
            AlertDialog(
                onDismissRequest = { confirmRemove = null },
                containerColor = KColors.Sheet,
                shape = KShapes.Card,
                title = { Text(stringResource(R.string.plugin_remove_title, p.manifest.name), style = MaterialTheme.typography.titleMedium) },
                text = { Text(stringResource(if (p.official) R.string.plugin_remove_official else R.string.plugin_remove_message), color = KColors.TextSoft) },
                confirmButton = {
                    TextButton(onClick = {
                        confirmRemove = null
                        scope.launch { withContext(Dispatchers.IO) { store.uninstall(p.id) }; revision++ }
                    }) { Text(stringResource(R.string.plugin_uninstall), color = KColors.Danger) }
                },
                dismissButton = { TextButton(onClick = { confirmRemove = null }) { Text(stringResource(R.string.cancel), color = KColors.TextSoft) } },
            )
        }
    }
}

@Composable
private fun PluginCard(p: InstalledPlugin, onToggle: (Boolean) -> Unit, onRemove: () -> Unit, onReinstall: () -> Unit) {
    val m = p.manifest
    Column(
        Modifier.fillMaxWidth().clip(KShapes.Card).background(KColors.Card).border(1.dp, KColors.Line, KShapes.Card).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Lucide.Plug, null, tint = if (p.active) KColors.Accent else KColors.TextSecondary, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(m.name, style = MaterialTheme.typography.titleSmall)
                Text(
                    "${m.version} · ${m.author} · ${typeLabel(m.type)}" + (if (p.official) " · " + stringResource(R.string.plugin_official) else "") +
                        (if (p.sizeBytes > 0) " · " + sizeLabel(p.sizeBytes) else ""),
                    color = KColors.TextSecondary, style = MaterialTheme.typography.bodySmall,
                )
            }
            if (p.installed) {
                Switch(p.enabled, onToggle, colors = SwitchDefaults.colors(checkedTrackColor = KColors.Accent))
            }
        }
        if (m.description.isNotBlank()) Text(m.description, color = KColors.TextSoft, style = MaterialTheme.typography.bodySmall)
        if (m.permissions.isNotEmpty()) {
            Text(m.permissions.joinToString(" · ") { it.id }, color = KColors.TextSecondary, style = MaterialTheme.typography.labelSmall)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (p.installed) {
                SecondaryButton(stringResource(R.string.plugin_uninstall), onRemove, pill = true)
            } else {
                if (p.id == dev.kinesis.editor.plugins.OfficialPlugins.KOKORO_ID) KokoroDownloader(onDone = onReinstall)
                else SecondaryButton(stringResource(R.string.plugin_reinstall), onReinstall, pill = true)
            }
        }
    }
}

/**
 * Downloads the Kokoro voices (model and voices, [KokoroFiles.totalBytes] bytes) from Hugging Face. This is the only
 * place that goes online for them; it says so, shows progress and can be tried again after a failure (the download
 * resumes). [onDone] runs when everything is in place.
 */
@Composable
fun KokoroDownloader(onDone: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var progress by remember { mutableStateOf<Float?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var cancel by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        val p = progress
        if (p == null) {
            Text(stringResource(R.string.kokoro_download_hint, (dev.kinesis.editor.plugins.KokoroFiles.totalBytes / 1_000_000).toInt()), color = KColors.TextSecondary, style = MaterialTheme.typography.bodySmall)
            SecondaryButton(stringResource(R.string.kokoro_download), {
                error = null; cancel = false; progress = 0f
                scope.launch {
                    try {
                        dev.kinesis.editor.plugins.KokoroFiles.download(context, { f -> progress = f }, { cancel })
                        progress = null
                        onDone()
                    } catch (e: Exception) {
                        progress = null
                        error = if (cancel) null else context.getString(R.string.kokoro_download_failed, e.message ?: e.javaClass.simpleName)
                    }
                }
            }, Modifier.fillMaxWidth(), pill = true)
        } else {
            androidx.compose.material3.LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth(), color = KColors.Accent)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${(p * 100).toInt()}%", color = KColors.TextSecondary, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                TextButton(onClick = { cancel = true }) { Text(stringResource(R.string.cancel), color = KColors.TextSoft) }
            }
        }
        error?.let { Text(it, color = KColors.Danger, style = MaterialTheme.typography.bodySmall) }
    }
}
