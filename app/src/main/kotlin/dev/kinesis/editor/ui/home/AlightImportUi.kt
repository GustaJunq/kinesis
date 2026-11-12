package dev.kinesis.editor.ui.home

import android.Manifest
import android.content.ContentResolver
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.kinesis.core.alight.AlightMedia
import dev.kinesis.core.alight.AlightScan
import dev.kinesis.core.alight.ImportReport
import dev.kinesis.core.alight.MediaMatch
import dev.kinesis.core.alight.ReportEntry
import dev.kinesis.core.alight.ReportKind
import dev.kinesis.editor.R
import dev.kinesis.editor.ui.theme.KColors
import dev.kinesis.editor.ui.theme.KShapes

/** Where an Alight Motion import is. */
sealed interface AlightFlow {
    /** The XML was read and the media searched for: [found] has what the phone had, [picked] what the person chose. */
    class Missing(val scan: AlightScan, val found: Map<String, MediaMatch>, val picked: Map<String, Uri>) : AlightFlow {
        /** Media with neither an automatic match nor a picked file. */
        val missing: List<AlightMedia>
            get() = scan.scene.media.filter { m -> m.uri !in picked && found[m.uri]?.automatic != true }
    }

    /** The project exists; [report] says what was converted, approximated, unsupported or missing. */
    class Done(val id: String, val report: ImportReport) : AlightFlow
}

/** The media permissions to ask for when an Alight project is imported (the version of Android decides which). */
fun alightPermissions(): List<String> =
    if (Build.VERSION.SDK_INT >= 33) listOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_AUDIO)
    else listOf(Manifest.permission.READ_EXTERNAL_STORAGE)

/** True for the `.xml` that Alight Motion exports (by name, or by a root `<scene>` element in the first bytes). */
fun isAlightXml(resolver: ContentResolver, uri: Uri): Boolean {
    if (uri.lastPathSegment?.lowercase()?.endsWith(".xml") == true) return true
    return runCatching {
        resolver.openInputStream(uri)?.use { s ->
            val buf = ByteArray(2048)
            val n = s.read(buf)
            val head = if (n > 0) String(buf, 0, n, Charsets.UTF_8) else ""
            head.trimStart().startsWith("<") && head.contains("<scene")
        } ?: false
    }.getOrDefault(false)
}

@Composable
fun AlightImportDialogs(vm: HomeViewModel, onOpen: (String) -> Unit) {
    val flow by vm.alight.collectAsState()
    when (val f = flow) {
        is AlightFlow.Missing -> AlightMissingDialog(f, onPick = vm::alightPick, onImport = vm::alightConvert, onCancel = vm::alightDismiss)
        is AlightFlow.Done -> AlightReportDialog(f.report, onOpen = { vm.alightDismiss(); onOpen(f.id) }, onClose = vm::alightDismiss)
        null -> Unit
    }
}

@Composable
private fun AlightMissingDialog(f: AlightFlow.Missing, onPick: (String, Uri) -> Unit, onImport: () -> Unit, onCancel: () -> Unit) {
    var pickFor by remember { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val key = pickFor
        if (uri != null && key != null) onPick(key, uri)
        pickFor = null
    }
    val missing = f.missing
    val shown = f.scan.scene.media.filter { m -> f.found[m.uri]?.automatic != true }
    AlertDialog(
        onDismissRequest = onCancel,
        containerColor = KColors.Sheet,
        shape = KShapes.Card,
        icon = { Icon(Icons.Outlined.FolderOpen, null, tint = KColors.Accent) },
        title = { Text(stringResource(R.string.alight_missing_title), style = MaterialTheme.typography.titleMedium) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.alight_missing_message), color = KColors.TextSoft, style = MaterialTheme.typography.bodyMedium)
                LazyColumn(Modifier.heightIn(max = 320.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(shown) { m ->
                        val suggestion = f.found[m.uri]?.takeIf { !it.automatic }
                        val chosen = m.uri in f.picked
                        Column(Modifier.fillMaxWidth()) {
                            Text(m.filename, color = KColors.TextPrimary, style = MaterialTheme.typography.bodyMedium)
                            Text(details(m), color = KColors.TextSecondary, style = MaterialTheme.typography.bodySmall)
                            if (suggestion != null && !chosen) {
                                Text(stringResource(R.string.alight_suggestion_note, suggestion.candidate.name), color = KColors.TextSecondary, style = MaterialTheme.typography.bodySmall)
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (chosen) Text(stringResource(R.string.alight_picked), color = KColors.Accent, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(end = 8.dp))
                                TextButton(onClick = { pickFor = m.uri; picker.launch(arrayOf("*/*")) }) { Text(stringResource(R.string.alight_pick), color = KColors.Accent) }
                                if (suggestion != null && !chosen) {
                                    TextButton(onClick = { onPick(m.uri, Uri.parse(suggestion.candidate.key)) }) {
                                        Text(stringResource(R.string.alight_use_suggestion, suggestion.candidate.name), color = KColors.Accent, maxLines = 1)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onImport) {
                Text(
                    if (missing.isEmpty()) stringResource(R.string.alight_import_go) else stringResource(R.string.alight_import_go_placeholders, missing.size),
                    color = KColors.Accent,
                )
            }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel), color = KColors.TextSoft) } },
    )
}

private fun details(m: AlightMedia): String = listOfNotNull(
    m.sizeBytes?.let { "%.1f MB".format(it / 1_048_576.0) },
    if (m.width != null && m.height != null) "${m.width}×${m.height}" else null,
).joinToString(" · ")

@Composable
private fun AlightReportDialog(report: ImportReport, onOpen: () -> Unit, onClose: () -> Unit) {
    // What went fine is only a number; everything the person may want to check is listed.
    val order = listOf(
        ReportKind.MISSING_MEDIA to R.string.alight_kind_missing,
        ReportKind.UNSUPPORTED to R.string.alight_kind_unsupported,
        ReportKind.APPROXIMATED to R.string.alight_kind_approximated,
        ReportKind.WARNING to R.string.alight_kind_warning,
    )
    val rows = order.flatMap { (kind, title) ->
        val entries = report.entries.filter { it.kind == kind }
        if (entries.isEmpty()) emptyList<Any>() else listOf<Any>(title to entries.size) + entries
    }
    AlertDialog(
        onDismissRequest = onClose,
        containerColor = KColors.Sheet,
        shape = KShapes.Card,
        icon = { Icon(Icons.Outlined.FolderOpen, null, tint = KColors.Accent) },
        title = { Text(stringResource(R.string.alight_report_title), style = MaterialTheme.typography.titleMedium) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(
                        R.string.alight_report_summary, report.count(ReportKind.CONVERTED), report.count(ReportKind.APPROXIMATED),
                        report.count(ReportKind.UNSUPPORTED), report.count(ReportKind.MISSING_MEDIA),
                    ),
                    color = KColors.TextSoft, style = MaterialTheme.typography.bodyMedium,
                )
                LazyColumn(Modifier.heightIn(max = 360.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(rows) { row ->
                        if (row is Pair<*, *>) {
                            Text("${stringResource(row.first as Int)} (${row.second})", color = KColors.TextPrimary, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 6.dp))
                        } else {
                            val e = row as ReportEntry
                            Text("• ${e.where.removePrefix("layer:")}\n  ${e.message}", color = KColors.TextSoft, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onOpen) { Text(stringResource(R.string.alight_open_project), color = KColors.Accent) } },
        dismissButton = { TextButton(onClick = onClose) { Text(stringResource(R.string.close), color = KColors.TextSoft) } },
    )
}
