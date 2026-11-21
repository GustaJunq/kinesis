package dev.kinesis.editor.ui.editor

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.outlined.Brush
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.CenterFocusStrong
import androidx.compose.material.icons.outlined.ContentCut
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.PersonOff
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.outlined.Close
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.kinesis.editor.R
import dev.kinesis.editor.ui.common.Lucide
import dev.kinesis.editor.ui.common.formatTime
import dev.kinesis.editor.ui.theme.KColors
import dev.kinesis.editor.ui.theme.KDims
import dev.kinesis.editor.ui.theme.KShapes
import dev.kinesis.editor.ui.theme.KSkew
import dev.kinesis.editor.ui.theme.KType
import java.util.Locale

/** A round lavender button. The touch target is 48 dp; the circle is 40 dp. */
@Composable
fun CircleButton(icon: ImageVector, label: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Box(
        modifier.size(KDims.MinTouch).clickable(enabled = enabled, role = Role.Button, onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(40.dp).clip(CircleShape).background(KColors.SurfaceAlt), contentAlignment = Alignment.Center) {
            Icon(icon, label, tint = if (enabled) KColors.TextPrimary else KColors.Disabled, modifier = Modifier.size(20.dp))
        }
    }
}

/** Back, the project name (edited in place), project settings and export. */
@Composable
fun EditorTopBar(
    title: String,
    onClose: () -> Unit,
    onRename: (String) -> Unit,
    onSettings: () -> Unit,
    onExport: () -> Unit,
) {
    var text by remember(title) { mutableStateOf(title) }
    var focused by remember { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    Row(
        Modifier.fillMaxWidth().height(KDims.TopBar).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircleButton(Lucide.ArrowLeft, stringResource(R.string.close), onClose)
        val label = stringResource(R.string.edit_title)
        BasicTextField(
            value = text,
            onValueChange = { text = it.take(80) },
            singleLine = true,
            textStyle = MaterialTheme.typography.titleMedium.copy(color = KColors.TextPrimary),
            cursorBrush = SolidColor(KColors.Primary),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }),
            modifier = Modifier.weight(1f).padding(horizontal = 8.dp)
                .semantics { contentDescription = label }
                .onFocusChanged { f ->
                    // Saved when the field loses focus (done, back or a tap elsewhere).
                    if (focused && !f.isFocused && text != title) onRename(text)
                    focused = f.isFocused
                },
            decorationBox = { inner ->
                Column {
                    inner()
                    Spacer(Modifier.height(4.dp))
                    Box(Modifier.fillMaxWidth().height(1.dp).background(if (focused) KColors.Primary else KColors.Line))
                }
            },
        )
        CircleButton(Lucide.Settings, stringResource(R.string.settings), onSettings)
        Box(
            Modifier.heightIn(min = KDims.MinTouch).clickable(role = Role.Button, onClick = onExport),
            contentAlignment = Alignment.Center,
        ) {
            Row(
                Modifier.height(40.dp).clip(KShapes.Pill).background(KColors.Primary).padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.export), color = KColors.White, style = MaterialTheme.typography.labelLarge, maxLines = 1)
            }
        }
    }
}

/** Height of [TransportBar]. */
val TRANSPORT_BAR_HEIGHT = 48.dp

/** Height of [ShortcutBar]: the add shortcuts and the round "+". */
val SHORTCUT_BAR_HEIGHT = 68.dp

/** What the snackbar keeps clear of at the bottom. */
val EDITOR_BOTTOM_HEIGHT = SHORTCUT_BAR_HEIGHT

@Composable
private fun TransportIcon(icon: ImageVector, label: String, onClick: () -> Unit, enabled: Boolean = true, tint: Color = KColors.TextPrimary) {
    Box(
        Modifier.size(width = 40.dp, height = TRANSPORT_BAR_HEIGHT)
            .clickable(enabled = enabled, role = Role.Button, onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, label, tint = if (enabled) tint else KColors.Disabled, modifier = Modifier.size(22.dp))
    }
}

/** Undo, redo · go to the start, play, go to the end · marker, duplicate, full screen. */
@Composable
fun TransportBar(
    playing: Boolean,
    canUndo: Boolean,
    canRedo: Boolean,
    hasSelection: Boolean,
    fullscreen: Boolean,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onStart: () -> Unit,
    onPlay: () -> Unit,
    onEnd: () -> Unit,
    onMarker: () -> Unit,
    onDuplicate: () -> Unit,
    onFullscreen: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().height(TRANSPORT_BAR_HEIGHT).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TransportIcon(Lucide.Undo2, stringResource(R.string.undo), onUndo, enabled = canUndo)
        TransportIcon(Lucide.Redo2, stringResource(R.string.redo), onRedo, enabled = canRedo)
        Spacer(Modifier.weight(1f))
        TransportIcon(Lucide.SkipBack, stringResource(R.string.go_to_start), onStart)
        Box(
            Modifier.size(TRANSPORT_BAR_HEIGHT).clickable(
                role = Role.Button, onClickLabel = stringResource(if (playing) R.string.pause else R.string.play), onClick = onPlay,
            ),
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.size(42.dp).clip(CircleShape).background(KColors.Primary), contentAlignment = Alignment.Center) {
                Icon(
                    if (playing) Lucide.Pause else Lucide.Play, stringResource(if (playing) R.string.pause else R.string.play),
                    tint = KColors.White, modifier = Modifier.size(22.dp),
                )
            }
        }
        TransportIcon(Lucide.SkipForward, stringResource(R.string.go_to_end), onEnd)
        Spacer(Modifier.weight(1f))
        TransportIcon(Lucide.Bookmark, stringResource(R.string.add_marker), onMarker)
        TransportIcon(Lucide.Copy, stringResource(R.string.duplicate), onDuplicate, enabled = hasSelection)
        TransportIcon(
            if (fullscreen) Icons.Filled.FullscreenExit else Icons.Filled.Fullscreen,
            stringResource(if (fullscreen) R.string.exit_fullscreen else R.string.fullscreen), onFullscreen,
        )
    }
}

/** Shortcuts to add a shape, media, sound, text or an element, and the round "+" that opens the whole add menu. */
@Composable
fun ShortcutBar(
    onShape: () -> Unit,
    onMedia: () -> Unit,
    onAudio: () -> Unit,
    onText: () -> Unit,
    onElement: () -> Unit,
    onAdd: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().height(SHORTCUT_BAR_HEIGHT).padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier.weight(1f).fillMaxHeight().clip(KShapes.Pill).background(KColors.SurfaceAlt).testTag("tool-bar"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ShortcutItem(Lucide.Shapes, R.string.shortcut_shape, onShape, Modifier.weight(1f))
            ShortcutItem(Lucide.Image, R.string.tool_media, onMedia, Modifier.weight(1f))
            ShortcutItem(Lucide.Music, R.string.tool_audio, onAudio, Modifier.weight(1f))
            ShortcutItem(Lucide.Type, R.string.tool_text, onText, Modifier.weight(1f))
            ShortcutItem(Lucide.Component, R.string.shortcut_element, onElement, Modifier.weight(1f))
        }
        Spacer(Modifier.width(8.dp))
        val add = stringResource(R.string.add)
        Box(
            Modifier.size(54.dp).clip(CircleShape).background(KColors.Primary)
                .clickable(role = Role.Button, onClickLabel = add, onClick = onAdd),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.Add, add, tint = KColors.White, modifier = Modifier.size(28.dp))
        }
    }
}

@Composable
private fun ShortcutItem(icon: ImageVector, label: Int, onClick: () -> Unit, modifier: Modifier) {
    Column(
        modifier.fillMaxHeight().clip(KShapes.Pill).clickable(role = Role.Button, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(icon, null, tint = KColors.TextPrimary, modifier = Modifier.size(20.dp))
        Spacer(Modifier.height(2.dp))
        Text(stringResource(label), color = KColors.TextPrimary, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Actions on the selected layer (shown only while a layer is selected). */
@Composable
fun SelectionActions(
    onProperties: () -> Unit,
    /** Opens the effect picker for the selected layer (visual layers only). */
    onEffects: (() -> Unit)? = null,
    /** Whether the selected layer has a keyframe at the playhead (the "+ ◆" chip then removes it). */
    hasKeyframeHere: Boolean,
    onAddKeyframe: () -> Unit,
    onSplit: () -> Unit,
    onGroup: () -> Unit,
    onDelete: () -> Unit,
    loop: Boolean,
    onLoop: () -> Unit,
    /** Shown only for layers with sound (video, audio): the equalizer and volume panel. */
    onAudio: (() -> Unit)? = null,
    /** Shown only for video layers that have a sound track. */
    onExtractAudio: (() -> Unit)? = null,
    /** Shown for image and video layers: cuts the person out of the background. */
    onRemoveBackground: (() -> Unit)? = null,
    /** Shown for video layers: stabilizes the shake. */
    onStabilize: (() -> Unit)? = null,
    /** Shown for camera layers on phones with a motion sensor: the phone drives the camera. */
    onRealCamera: (() -> Unit)? = null,
    /** Shown for video layers: cut out any object by painting over it. */
    onCutoutBrush: (() -> Unit)? = null,
    /** Clears the selection (back to the shortcut bar). */
    onClose: (() -> Unit)? = null,
) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ActionChip(Icons.Outlined.Tune, R.string.tab_properties, onProperties, accent = true)
        if (onEffects != null) ActionChip(Icons.Outlined.AutoAwesome, R.string.effects, onEffects)
        KeyframeChip(hasKeyframeHere, onAddKeyframe)
        ActionChip(Icons.Outlined.ContentCut, R.string.split, onSplit)
        ActionChip(Icons.Outlined.CreateNewFolder, R.string.group, onGroup)
        if (onAudio != null) ActionChip(Icons.Outlined.GraphicEq, R.string.audio_panel, onAudio)
        if (onExtractAudio != null) ActionChip(Icons.Outlined.MusicNote, R.string.extract_audio, onExtractAudio)
        if (onRemoveBackground != null) ActionChip(Icons.Outlined.PersonOff, R.string.remove_background, onRemoveBackground)
        if (onCutoutBrush != null) ActionChip(Icons.Outlined.Brush, R.string.cutout_brush, onCutoutBrush)
        if (onStabilize != null) ActionChip(Icons.Outlined.CenterFocusStrong, R.string.stabilize, onStabilize)
        if (onRealCamera != null) ActionChip(Icons.Outlined.CameraAlt, R.string.real_camera, onRealCamera)
        ActionChip(Icons.Outlined.Delete, R.string.delete, onDelete, danger = true)
        ActionChip(Icons.Outlined.Repeat, R.string.loop, onLoop, accent = loop)
        if (onClose != null) ActionChip(Icons.Outlined.Close, R.string.deselect, onClose)
    }
}

/** One button for both: "+ ◆" adds a keyframe at the playhead; when there is one it turns into a filled "− ◆" that removes it. */
@Composable
private fun KeyframeChip(hasKeyframeHere: Boolean, onClick: () -> Unit) {
    val label = stringResource(if (hasKeyframeHere) R.string.remove_keyframe else R.string.add_keyframe)
    val fg = if (hasKeyframeHere) KColors.OnAccent else KColors.Keyframe
    Row(
        Modifier.heightIn(min = KDims.MinTouch).clip(KShapes.Pill)
            .background(if (hasKeyframeHere) KColors.Keyframe else Color.Transparent)
            .border(1.dp, KColors.Keyframe, KShapes.Pill)
            .clickable(role = Role.Button, onClickLabel = label, onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(if (hasKeyframeHere) Icons.Filled.Remove else Icons.Filled.Add, label, tint = fg, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(4.dp))
        Text("◆", color = fg, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun ActionChip(icon: ImageVector, label: Int, onClick: () -> Unit, accent: Boolean = false, danger: Boolean = false) {
    val fg = when {
        danger -> KColors.Danger
        accent -> KColors.White
        else -> KColors.TextPrimary
    }
    Row(
        Modifier.heightIn(min = KDims.MinTouch).clip(KShapes.Pill).background(if (accent) KColors.Primary else KColors.SurfaceAlt)
            .clickable(role = Role.Button, onClick = onClick).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = fg, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(stringResource(label), color = fg, style = MaterialTheme.typography.labelMedium, maxLines = 1)
    }
}
