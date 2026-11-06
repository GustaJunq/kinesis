@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package dev.kinesis.editor.ui.editor

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.kinesis.core.elements.ElementBackground
import dev.kinesis.core.model.KnsProject
import dev.kinesis.editor.R
import dev.kinesis.editor.data.ElementStore
import dev.kinesis.editor.data.ElementSummary
import dev.kinesis.editor.ui.common.Lucide
import dev.kinesis.editor.ui.common.OverlineLabel
import dev.kinesis.editor.ui.common.PillChip
import dev.kinesis.editor.ui.common.PrimaryButton
import dev.kinesis.editor.ui.theme.KColors
import dev.kinesis.editor.ui.theme.KDims
import dev.kinesis.editor.ui.theme.KShapes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

@Composable
fun backgroundLabel(bg: ElementBackground) = stringResource(
    when (bg) {
        ElementBackground.TRANSPARENT -> R.string.bg_transparent
        ElementBackground.GREEN -> R.string.bg_green
        ElementBackground.BLUE -> R.string.bg_blue
        ElementBackground.BLACK -> R.string.bg_black
        ElementBackground.WHITE -> R.string.bg_white
    },
)

/** A swatch for a background; transparent is drawn as a checkerboard. */
@Composable
private fun BackgroundSwatch(bg: ElementBackground, size: Int = 18) {
    val c = bg.color
    Box(Modifier.size(size.dp).clip(KShapes.Tile).border(1.dp, KColors.Line, KShapes.Tile)) {
        if (bg == ElementBackground.TRANSPARENT) {
            Row { repeat(2) { i -> Column { repeat(2) { j -> Box(Modifier.size((size / 2).dp).background(if ((i + j) % 2 == 0) Color.LightGray else Color.White)) } } } }
        } else {
            Box(Modifier.size(size.dp).background(Color(c.r, c.g, c.b, c.a)))
        }
    }
}

/** Elements: insert a saved element at the playhead, or keep this project as a new element. */
@Composable
fun ElementsSheet(vm: EditorViewModel, project: KnsProject, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val store = remember { ElementStore(context) }
    val scope = rememberCoroutineScope()
    var revision by remember { mutableStateOf(0) }
    var elements by remember { mutableStateOf<List<ElementSummary>?>(null) }
    var saving by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf<ElementSummary?>(null) }
    LaunchedEffect(revision) { elements = withContext(Dispatchers.IO) { store.list() } }

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = KColors.Sheet, shape = KShapes.Sheet) {
        LazyColumn(Modifier.fillMaxWidth().fillMaxHeight(0.8f).padding(horizontal = KDims.Gutter), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item { Text(stringResource(R.string.elements), style = MaterialTheme.typography.titleMedium) }
            item { Text(stringResource(R.string.elements_hint), color = KColors.TextSecondary, style = MaterialTheme.typography.bodySmall) }
            item { PrimaryButton(stringResource(R.string.element_save_project), { saving = true }, Modifier.fillMaxWidth(), icon = Lucide.Component) }
            item { OverlineLabel(stringResource(R.string.elements_saved)) }
            val list = elements
            if (list != null && list.isEmpty()) {
                item { Text(stringResource(R.string.elements_none), color = KColors.TextSecondary, style = MaterialTheme.typography.bodySmall) }
            }
            items(list.orEmpty(), key = { it.id }) { e ->
                ElementRow(
                    e,
                    onInsert = { vm.insertElement(e.id); onDismiss() },
                    onBackground = { bg -> scope.launch { withContext(Dispatchers.IO) { store.setBackground(e.id, bg) }; revision++ } },
                    onDelete = { confirmDelete = e },
                )
            }
            item { Spacer(Modifier.padding(8.dp)) }
        }
    }
    if (saving) {
        SaveElementDialog(project.meta.title, onDismiss = { saving = false }) { name, bg ->
            saving = false
            vm.saveAsElement(name, bg) { revision++ }
        }
    }
    confirmDelete?.let { e ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            containerColor = KColors.Sheet,
            shape = KShapes.Card,
            title = { Text(stringResource(R.string.element_delete_title, e.name), style = MaterialTheme.typography.titleMedium) },
            text = { Text(stringResource(R.string.element_delete_message), color = KColors.TextSoft) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = null
                    scope.launch { withContext(Dispatchers.IO) { store.delete(e.id) }; revision++ }
                }) { Text(stringResource(R.string.delete), color = KColors.Danger) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text(stringResource(R.string.cancel), color = KColors.TextSoft) } },
        )
    }
}

@Composable
private fun ElementRow(e: ElementSummary, onInsert: () -> Unit, onBackground: (ElementBackground) -> Unit, onDelete: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val thumb = remember(e.thumbnail) { e.thumbnail?.let { runCatching { BitmapFactory.decodeFile(it.path)?.asImageBitmap() }.getOrNull() } }
    Row(
        Modifier.fillMaxWidth().clip(KShapes.Card).background(KColors.Card).clickable(onClick = onInsert).padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(56.dp).clip(KShapes.Tile).background(KColors.Field), contentAlignment = Alignment.Center) {
            if (thumb != null) Image(thumb, null, Modifier.size(56.dp), contentScale = ContentScale.Crop) else Icon(Lucide.Component, null, tint = KColors.TextSecondary)
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(e.name, style = MaterialTheme.typography.bodyMedium)
            Row(verticalAlignment = Alignment.CenterVertically) {
                BackgroundSwatch(e.background, 12)
                Spacer(Modifier.width(6.dp))
                Text(
                    "${backgroundLabel(e.background)} · ${e.width}×${e.height} · ${String.format(Locale.ROOT, "%.1f", e.duration)} s",
                    style = MaterialTheme.typography.bodySmall, color = KColors.TextSecondary,
                )
            }
        }
        Box {
            IconButton(onClick = { menu = true }) { Icon(Lucide.Settings, stringResource(R.string.more), tint = KColors.TextSecondary) }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, containerColor = KColors.Field) {
                ElementBackground.entries.forEach { bg ->
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.element_background_is, backgroundLabel(bg))) },
                        leadingIcon = { BackgroundSwatch(bg) },
                        onClick = { menu = false; onBackground(bg) },
                    )
                }
                DropdownMenuItem(text = { Text(stringResource(R.string.delete), color = KColors.Danger) }, onClick = { menu = false; onDelete() })
            }
        }
    }
}

@Composable
private fun SaveElementDialog(initial: String, onDismiss: () -> Unit, onSave: (String, ElementBackground) -> Unit) {
    var name by remember { mutableStateOf(initial) }
    var bg by remember { mutableStateOf(ElementBackground.TRANSPARENT) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = KColors.Sheet,
        shape = KShapes.Card,
        title = { Text(stringResource(R.string.element_save_project), style = MaterialTheme.typography.titleMedium) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it.take(60) }, label = { Text(stringResource(R.string.element_name)) }, singleLine = true)
                OverlineLabel(stringResource(R.string.element_background))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    ElementBackground.entries.forEach { b -> PillChip(backgroundLabel(b), bg == b, { bg = b }) }
                }
                Text(stringResource(R.string.element_background_hint), color = KColors.TextSecondary, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = { onSave(name.trim(), bg) }) { Text(stringResource(R.string.save), color = KColors.Accent) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel), color = KColors.TextSoft) } },
    )
}
