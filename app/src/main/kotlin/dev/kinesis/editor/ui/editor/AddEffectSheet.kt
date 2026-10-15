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
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Animation
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
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
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.kinesis.editor.ui.theme.KBrushes
import dev.kinesis.editor.ui.theme.KColors
import dev.kinesis.editor.ui.theme.KDims
import dev.kinesis.editor.ui.theme.KShapes
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
 * "Add effect" full-screen page: a two-column grid with the 13 categories; a category opens its effects (plus shortcuts
 * to the mask, track matte, transition and text animator editors). The search box looks through every effect by name.
 * Tiles show the effect rendered by the real renderer over the sample image, cropped to fit, with the name on a scrim.
 */
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
    val current = EffectCategory.entries.firstOrNull { it.id == category }
    val inCategory = current != null && query.isEmpty()

    Dialog(
        onDismissRequest = { if (inCategory) category = null else onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false),
    ) {
        Column(Modifier.fillMaxSize().background(KColors.Background).safeDrawingPadding()) {
            // Header: back or close, title
            Row(Modifier.fillMaxWidth().height(KDims.TopBar).background(KColors.Bar).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { if (inCategory) category = null else onDismiss() }, modifier = Modifier.size(KDims.MinTouch)) {
                    if (inCategory) Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.back))
                    else Icon(Icons.Outlined.Close, stringResource(R.string.close))
                }
                Text(
                    if (inCategory) current!!.label(pt) else stringResource(R.string.add_effect),
                    style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            // Search
            BasicSearchField(query, { query = it }, Modifier.fillMaxWidth().padding(horizontal = KDims.Gutter, vertical = 10.dp))
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
                Modifier.fillMaxWidth().background(KColors.Bar).padding(horizontal = KDims.Gutter, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.Layers, null, tint = KColors.TextSecondary, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Text(
                    stringResource(R.string.fx_apply_to, layerDisplayName(layer)),
                    style = MaterialTheme.typography.bodyMedium, color = KColors.TextSoft, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun BasicSearchField(value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.height(KDims.MinTouch).clip(KShapes.Pill).background(KColors.Field).padding(start = 14.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.Search, null, tint = KColors.TextSecondary, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (value.isEmpty()) Text(stringResource(R.string.fx_search), color = KColors.TextSecondary, style = MaterialTheme.typography.bodyMedium)
            BasicTextField(
                value = value,
                onValueChange = onChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = KColors.TextPrimary),
                cursorBrush = SolidColor(KColors.Accent),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (value.isNotEmpty()) IconButton(onClick = { onChange("") }, modifier = Modifier.size(KDims.MinTouch)) {
            Icon(Icons.Outlined.Close, stringResource(R.string.clear), tint = KColors.TextSoft)
        }
    }
}

@Composable
private fun CategoryGrid(pt: Boolean, onOpen: (EffectCategory) -> Unit) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        contentPadding = PaddingValues(KDims.Gutter, 4.dp, KDims.Gutter, 16.dp),
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
        contentPadding = PaddingValues(KDims.Gutter, 4.dp, KDims.Gutter, 16.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(shortcuts, key = { "sc:" + it.id }) { s ->
            val reason = shortcutUnavailableReason(s, layer)
            ShortcutTile(s, pt, reason?.let { stringResource(it) }) { onShortcut(s) }
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

/** Image tile: example preview cropped to the tile, name centred on a dark scrim. */
@Composable
private fun TileFrame(enabled: Boolean, title: String, subtitle: String, onClick: () -> Unit, background: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(1.3f)
            .clip(KShapes.Tile)
            .background(KColors.Field)
            .border(if (pressed) 2.dp else 1.dp, if (pressed) KColors.Accent else KColors.Line, KShapes.Tile)
            .clickable(interactionSource = source, indication = androidx.compose.material3.ripple(), enabled = enabled, role = Role.Button, onClick = onClick)
            .alpha(if (enabled) 1f else 0.5f),
    ) {
        background()
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(KBrushes.ImageScrim).padding(horizontal = 8.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = KColors.White, textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, style = MaterialTheme.typography.labelSmall, color = KColors.TextSoft, textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun Tile(previewKey: String, title: String, subtitle: String, enabled: Boolean = true, onClick: () -> Unit) {
    val image = rememberEffectPreview(previewKey)
    TileFrame(enabled, title, subtitle, onClick) {
        if (image != null) {
            // Crop (cover), never stretch.
            Image(image, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            CircularProgressIndicator(Modifier.align(Alignment.Center).size(22.dp), color = KColors.TextSecondary, strokeWidth = 2.dp)
        }
    }
}

@Composable
private fun ShortcutTile(s: EffectShortcut, pt: Boolean, unavailable: String?, onClick: () -> Unit) {
    val icon: ImageVector = when (s) {
        EffectShortcut.MASK -> Icons.Outlined.Layers
        EffectShortcut.TRACK_MATTE -> Icons.Outlined.SwapHoriz
        EffectShortcut.TRANSITIONS -> Icons.Outlined.Animation
        EffectShortcut.TEXT_ANIMATORS -> Icons.Outlined.TextFields
    }
    TileFrame(unavailable == null, if (pt) s.namePt else s.nameEn, unavailable ?: stringResource(R.string.fx_shortcut), onClick) {
        Box(Modifier.fillMaxSize().background(KColors.ChipActive), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = KColors.OnChipActive, modifier = Modifier.size(36.dp).padding(bottom = 0.dp))
        }
    }
}
