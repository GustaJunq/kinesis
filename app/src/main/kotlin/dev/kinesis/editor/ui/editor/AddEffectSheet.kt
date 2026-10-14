package dev.kinesis.editor.ui.editor

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Animation
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.kinesis.core.model.EffectCategory
import dev.kinesis.core.model.EffectRegistry
import dev.kinesis.core.model.EffectShortcut
import dev.kinesis.core.model.EffectSpec
import dev.kinesis.core.model.EffectTarget
import dev.kinesis.core.model.Layer
import dev.kinesis.core.model.LayerType
import dev.kinesis.editor.R
import dev.kinesis.editor.ui.theme.KColors
import java.text.Normalizer

/** Effect shown on the tile of each category. */
private fun representative(c: EffectCategory): String = when (c) {
    EffectCategory.ALL -> "gradientMap"
    EffectCategory.TEXT -> EffectPreviews.TEXT_KEY
    EffectCategory.KEYING -> "lumaKey"
    else -> EffectRegistry.inCategory(c).first().id
}

/** Lower-case, accent-free text for search. */
private fun fold(s: String) = Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").lowercase()

/** Why an effect or shortcut cannot be used on [layer], or null when it can. */
fun effectUnavailableReason(spec: EffectSpec, layer: Layer): Int? =
    if (spec.target == EffectTarget.LAYER_3D && !layer.type.isRendered3D) R.string.fx_only_3d else null

fun shortcutUnavailableReason(s: EffectShortcut, layer: Layer): Int? = when (s) {
    EffectShortcut.TEXT_ANIMATORS -> if (layer.type != LayerType.TEXT) R.string.fx_only_text else null
    EffectShortcut.MASK, EffectShortcut.TRACK_MATTE -> if (!layer.type.is2DVisual) R.string.fx_only_2d else null
    EffectShortcut.TRANSITIONS -> null
}

/**
 * "Add effect" screen: a two-column grid with the 13 categories; a category opens its effects (plus shortcuts to
 * the mask, track matte, transition and text animator editors). The search box looks through every effect by name.
 * Tiles show the effect rendered by the real renderer over the sample image.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddEffectSheet(
    layer: Layer,
    onDismiss: () -> Unit,
    onAdd: (String) -> Unit,
    onShortcut: (EffectShortcut) -> Unit,
) {
    val pt = isPortuguese()
    var category by rememberSaveable { mutableStateOf<String?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val current = EffectCategory.entries.firstOrNull { it.id == category }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, containerColor = KColors.Surface) {
        Column(Modifier.fillMaxWidth().fillMaxSize(0.92f)) {
            // Header: back (inside a category), title, close
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (current != null && query.isEmpty()) {
                    IconButton(onClick = { category = null }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) }
                } else {
                    Spacer(Modifier.width(12.dp))
                }
                Text(
                    if (current != null && query.isEmpty()) current.label(pt) else stringResource(R.string.add_effect),
                    style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, stringResource(R.string.close)) }
            }
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                singleLine = true,
                placeholder = { Text(stringResource(R.string.fx_search)) },
                leadingIcon = { Icon(Icons.Filled.Search, null) },
                trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Filled.Close, stringResource(R.string.clear)) } },
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = KColors.Accent, cursorColor = KColors.Accent),
            )
            Spacer(Modifier.height(8.dp))
            Box(Modifier.weight(1f)) {
                val q = fold(query.trim())
                when {
                    q.isNotEmpty() -> {
                        val found = EffectRegistry.all.filter { s -> fold(s.namePt).contains(q) || fold(s.nameEn).contains(q) || fold(s.id).contains(q) }
                        val shortcuts = EffectShortcut.entries.filter { fold(it.namePt).contains(q) || fold(it.nameEn).contains(q) }
                        if (found.isEmpty() && shortcuts.isEmpty()) {
                            Text(stringResource(R.string.fx_no_results), color = KColors.TextSecondary, modifier = Modifier.padding(24.dp))
                        } else {
                            EffectGrid(found, shortcuts, layer, pt, onAdd, onShortcut)
                        }
                    }
                    current == null -> CategoryGrid(pt) { category = it.id }
                    else -> EffectGrid(EffectRegistry.inCategory(current), EffectRegistry.shortcuts(current), layer, pt, onAdd, onShortcut)
                }
            }
            // Footer: which layer receives the effect
            Row(
                Modifier.fillMaxWidth().background(KColors.SurfaceHigh).padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.Layers, null, tint = KColors.TextSecondary, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(R.string.fx_apply_to, layerDisplayName(layer)),
                    style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun CategoryGrid(pt: Boolean, onOpen: (EffectCategory) -> Unit) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        contentPadding = PaddingValues(16.dp, 4.dp, 16.dp, 16.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(EffectCategory.entries, key = { it.id }) { c ->
            val count = EffectRegistry.inCategory(c).size + EffectRegistry.shortcuts(c).size
            Tile(
                previewKey = representative(c),
                title = c.label(pt),
                subtitle = stringResource(R.string.fx_count, count),
                onClick = { onOpen(c) },
            )
        }
    }
}

@Composable
private fun EffectGrid(
    effects: List<EffectSpec>,
    shortcuts: List<EffectShortcut>,
    layer: Layer,
    pt: Boolean,
    onAdd: (String) -> Unit,
    onShortcut: (EffectShortcut) -> Unit,
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        contentPadding = PaddingValues(16.dp, 4.dp, 16.dp, 16.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        if (shortcuts.isNotEmpty()) {
            items(shortcuts, key = { "sc:" + it.id }) { s ->
                val reason = shortcutUnavailableReason(s, layer)
                ShortcutTile(s, pt, reason?.let { stringResource(it) }) { onShortcut(s) }
            }
            if (effects.isNotEmpty()) item(span = { GridItemSpan(maxLineSpan) }) { Spacer(Modifier.height(2.dp)) }
        }
        items(effects, key = { it.id }) { s ->
            val reason = effectUnavailableReason(s, layer)
            Tile(
                previewKey = s.id,
                title = s.label(pt),
                subtitle = reason?.let { stringResource(it) } ?: s.category.label(pt),
                enabled = reason == null,
                onClick = { onAdd(s.id) },
            )
        }
    }
}

@Composable
private fun Tile(previewKey: String, title: String, subtitle: String, enabled: Boolean = true, onClick: () -> Unit) {
    Column(
        Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(KColors.SurfaceHigh)
            .clickable(enabled = enabled, onClick = onClick)
            .alpha(if (enabled) 1f else 0.45f),
    ) {
        val image = rememberEffectPreview(previewKey)
        Box(Modifier.fillMaxWidth().aspectRatio(1.4f).background(KColors.SurfaceHighest), contentAlignment = Alignment.Center) {
            if (image != null) {
                // Crop (cover), never stretch.
                Image(image, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
        }
        Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, fontSize = 11.sp, color = KColors.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun ShortcutTile(s: EffectShortcut, pt: Boolean, unavailable: String?, onClick: () -> Unit) {
    val icon: ImageVector = when (s) {
        EffectShortcut.MASK -> Icons.Filled.Layers
        EffectShortcut.TRACK_MATTE -> Icons.Filled.SwapHoriz
        EffectShortcut.TRANSITIONS -> Icons.Filled.Animation
        EffectShortcut.TEXT_ANIMATORS -> Icons.Filled.TextFields
    }
    Column(
        Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(KColors.SurfaceHigh)
            .clickable(enabled = unavailable == null, onClick = onClick)
            .alpha(if (unavailable == null) 1f else 0.45f),
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(1.4f).background(KColors.AccentSoft), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = KColors.TextPrimary, modifier = Modifier.size(36.dp))
        }
        Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            Text(if (pt) s.namePt else s.nameEn, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(unavailable ?: stringResource(R.string.fx_shortcut), fontSize = 11.sp, color = KColors.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
