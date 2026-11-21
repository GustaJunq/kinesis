package dev.kinesis.editor.effects

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.FileOpen
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.kinesis.core.knsfx.KnsfxException
import dev.kinesis.core.knsfx.KnsfxPackage
import dev.kinesis.core.knsfx.KnsfxRegistry
import dev.kinesis.editor.R
import dev.kinesis.editor.ui.editor.label
import dev.kinesis.editor.ui.theme.KColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Imports a `.knsfx` from [uri]: checks it (manifest, params, script, shader compile), asks before replacing an
 * installed effect with the same id, installs it and reports with [onDone] (a message for the snackbar, or null).
 */
@Composable
fun KnsfxImportDialog(uri: Uri, onDone: (String?) -> Unit) {
    val context = LocalContext.current
    var pkg by remember(uri) { mutableStateOf<KnsfxPackage?>(null) }
    var error by remember(uri) { mutableStateOf<KnsfxException?>(null) }
    LaunchedEffect(uri) {
        val result = withContext(Dispatchers.IO) {
            runCatching { KnsfxLibrary.ensureLoaded(context); KnsfxLibrary.inspect(context, uri) }
        }
        result.onSuccess { p ->
            if (!KnsfxRegistry.isInstalled(p.id)) {
                withContext(Dispatchers.IO) { KnsfxLibrary.install(context, p) }
                onDone(context.getString(R.string.fx_import_done, p.manifest.name))
            } else {
                pkg = p
            }
        }.onFailure { e -> error = e as? KnsfxException ?: KnsfxException(e.message ?: e.javaClass.simpleName) }
    }
    val failure = error
    val replacing = pkg
    when {
        failure != null -> AlertDialog(
            onDismissRequest = { onDone(null) },
            title = { Text(stringResource(R.string.fx_import_failed)) },
            text = {
                Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                    Text(failure.message.orEmpty())
                    failure.log?.let { log ->
                        Spacer(Modifier.height(10.dp))
                        Text(stringResource(R.string.fx_import_log), style = MaterialTheme.typography.labelMedium, color = KColors.TextSecondary)
                        Text(
                            log, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(KColors.SurfaceAlt).padding(8.dp)
                                .horizontalScroll(rememberScrollState()),
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = { onDone(null) }) { Text(stringResource(R.string.ok)) } },
        )
        replacing != null -> {
            val installed = KnsfxRegistry.get(replacing.id)?.manifest?.version ?: "?"
            AlertDialog(
                onDismissRequest = { onDone(null) },
                title = { Text(stringResource(R.string.fx_import_title)) },
                text = { Text(stringResource(R.string.fx_import_replace, replacing.manifest.name, installed, replacing.manifest.version)) },
                confirmButton = {
                    TextButton(onClick = {
                        KnsfxLibrary.install(context, replacing)
                        onDone(context.getString(R.string.fx_import_done, replacing.manifest.name))
                    }) { Text(stringResource(R.string.fx_import_replace_ok)) }
                },
                dismissButton = { TextButton(onClick = { onDone(null) }) { Text(stringResource(R.string.cancel)) } },
            )
        }
        else -> AlertDialog(
            onDismissRequest = {},
            title = { Text(stringResource(R.string.fx_import_title)) },
            text = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp))
                    Text(stringResource(R.string.fx_import_checking))
                }
            },
            confirmButton = {},
        )
    }
}

/** "Efeitos customizados": the import button and the installed effects (thumb, name, author, category), removable. */
@Composable
fun CustomEffectsSection(pt: Boolean, revision: Int, onImport: () -> Unit, onAdd: (String) -> Unit, onRemoved: () -> Unit) {
    val context = LocalContext.current
    var confirm by remember { mutableStateOf<KnsfxPackage?>(null) }
    val installed = remember(revision) { KnsfxRegistry.installed() }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.fx_custom), style = MaterialTheme.typography.titleSmall, color = KColors.TextPrimary)
        OutlinedButton(onClick = onImport, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Outlined.FileOpen, null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.fx_import_knsfx))
        }
        if (installed.isEmpty()) {
            Text(stringResource(R.string.fx_custom_empty), style = MaterialTheme.typography.bodySmall, color = KColors.TextSecondary)
        }
        for (p in installed) {
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(KColors.SurfaceAlt).clickable { onAdd(p.id) }.padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val thumb = remember(p.sha256) { p.thumb?.let { b -> runCatching { BitmapFactory.decodeByteArray(b, 0, b.size)?.asImageBitmap() }.getOrNull() } }
                Box(Modifier.size(48.dp).clip(RoundedCornerShape(8.dp)).background(KColors.Line), contentAlignment = Alignment.Center) {
                    if (thumb != null) Image(thumb, null, contentScale = ContentScale.Crop, modifier = Modifier.size(48.dp))
                    else Icon(Icons.Outlined.AutoAwesome, null, tint = KColors.TextSoft)
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(p.manifest.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val by = if (p.manifest.author.isNotEmpty()) stringResource(R.string.fx_by, p.manifest.author) + " · " else ""
                    Text(
                        by + p.spec.category.label(pt) + " · v" + p.manifest.version,
                        style = MaterialTheme.typography.bodySmall, color = KColors.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                IconButton(onClick = { confirm = p }) { Icon(Icons.Outlined.Delete, stringResource(R.string.fx_custom_remove), tint = KColors.TextSecondary) }
            }
        }
    }
    confirm?.let { p ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            text = { Text(stringResource(R.string.fx_custom_remove_q, p.manifest.name)) },
            confirmButton = { TextButton(onClick = { KnsfxLibrary.remove(context, p.id); confirm = null; onRemoved() }) { Text(stringResource(R.string.fx_custom_remove)) } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}
