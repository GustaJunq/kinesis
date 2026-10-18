package dev.kinesis.editor.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Redo
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.CenterFocusStrong
import androidx.compose.material.icons.outlined.PersonOff
import androidx.compose.material.icons.outlined.ClosedCaption
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.ContentCut
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Audiotrack
import androidx.compose.material.icons.outlined.Category
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material.icons.outlined.SkipNext
import androidx.compose.material.icons.outlined.SkipPrevious
import androidx.compose.material.icons.outlined.Timeline
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material.icons.outlined.ViewInAr
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.kinesis.editor.R
import dev.kinesis.editor.ui.common.PrimaryButton
import dev.kinesis.editor.ui.common.formatTime
import dev.kinesis.editor.ui.theme.KColors
import dev.kinesis.editor.ui.theme.KDims
import dev.kinesis.editor.ui.theme.KShapes
import dev.kinesis.editor.ui.theme.KType
import java.util.Locale

/** Close, title with "W×H · fps · duration" (tap the title to rename, the details for composition settings), export. */
@Composable
fun EditorTopBar(
    title: String,
    width: Int,
    height: Int,
    fps: Double,
    duration: Double,
    onClose: () -> Unit,
    onRename: () -> Unit,
    onSettings: () -> Unit,
    onCaptions: () -> Unit,
    onExport: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().height(KDims.TopBar).background(KColors.Bar).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onClose, modifier = Modifier.size(KDims.MinTouch)) {
            Icon(Icons.Outlined.Close, stringResource(R.string.close), tint = KColors.TextPrimary)
        }
        Column(Modifier.weight(1f).padding(horizontal = 4.dp)) {
            Text(
                title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.clickable(onClickLabel = stringResource(R.string.edit_title), role = Role.Button, onClick = onRename),
            )
            val fpsText = if (fps == fps.toInt().toDouble()) fps.toInt().toString() else String.format(Locale.US, "%.2f", fps)
            Text(
                "${width}×$height · $fpsText fps · ${String.format(Locale.getDefault(), "%.1f", duration)} s",
                style = KType.MonoSmall, color = KColors.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.clickable(onClickLabel = stringResource(R.string.settings), role = Role.Button, onClick = onSettings),
            )
        }
        IconButton(onClick = onCaptions, modifier = Modifier.size(KDims.MinTouch)) {
            Icon(Icons.Outlined.ClosedCaption, stringResource(R.string.captions), tint = KColors.TextPrimary)
        }
        PrimaryButton(stringResource(R.string.export), onExport, Modifier.padding(end = 8.dp), compact = true)
    }
}

/** Timecode, previous/next keyframe, play, + keyframe, undo, redo. */
@Composable
fun EditorTransport(
    time: Double,
    fps: Double,
    playing: Boolean,
    canUndo: Boolean,
    canRedo: Boolean,
    hasSelection: Boolean,
    /** Whether the selected layer has a keyframe at the playhead (the "+ ◆" button then removes it). */
    hasKeyframeHere: Boolean,
    /** Previous / next only need something to jump to: a selected layer's keyframes or the markers. */
    canNavigate: Boolean,
    onPrevKeyframe: () -> Unit,
    onNextKeyframe: () -> Unit,
    onPlay: () -> Unit,
    onAddKeyframe: () -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().height(KDims.MinTouch + 4.dp).background(KColors.Bar).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(formatTime(time, fps), style = KType.MonoLarge, color = KColors.TextPrimary, modifier = Modifier.widthIn(min = 76.dp))
        Spacer(Modifier.weight(1f))
        TransportIcon(Icons.Outlined.SkipPrevious, R.string.prev_keyframe, onPrevKeyframe, enabled = canNavigate)
        Box(
            Modifier.size(KDims.MinTouch).clip(CircleShape).background(KColors.ChipActive).clickable(role = Role.Button, onClick = onPlay),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (playing) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                stringResource(if (playing) R.string.pause else R.string.play),
                tint = KColors.OnChipActive, modifier = Modifier.size(28.dp),
            )
        }
        TransportIcon(Icons.Outlined.SkipNext, R.string.next_keyframe, onNextKeyframe, enabled = canNavigate)
        Spacer(Modifier.weight(1f))
        // One button for both: "+ ◆" adds a keyframe at the playhead; when there is one it turns into a filled "− ◆" that removes it.
        val label = stringResource(if (hasKeyframeHere) R.string.remove_keyframe else R.string.add_keyframe)
        val fg = when {
            !hasSelection -> KColors.Disabled
            hasKeyframeHere -> KColors.OnAccent
            else -> KColors.Keyframe
        }
        Row(
            Modifier.heightIn(min = 36.dp).clip(KShapes.Pill)
                .background(if (hasSelection && hasKeyframeHere) KColors.Keyframe else androidx.compose.ui.graphics.Color.Transparent)
                .border(1.dp, if (hasSelection) KColors.Keyframe else KColors.LineStrong, KShapes.Pill)
                .clickable(enabled = hasSelection, role = Role.Button, onClickLabel = label, onClick = onAddKeyframe)
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(if (hasKeyframeHere) Icons.Filled.Remove else Icons.Filled.Add, label, tint = fg, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
            Text("◆", color = fg, style = MaterialTheme.typography.labelSmall)
        }
        TransportIcon(Icons.AutoMirrored.Outlined.Undo, R.string.undo, onUndo, enabled = canUndo)
        TransportIcon(Icons.AutoMirrored.Outlined.Redo, R.string.redo, onRedo, enabled = canRedo)
    }
}

@Composable
private fun TransportIcon(icon: ImageVector, label: Int, onClick: () -> Unit, enabled: Boolean = true) {
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(KDims.MinTouch)) {
        Icon(icon, stringResource(label), tint = if (enabled) KColors.TextPrimary else KColors.Disabled, modifier = Modifier.size(22.dp))
    }
}

enum class EditorTool { LAYERS, TEXT, SHAPES, MEDIA, MODEL3D, EFFECTS, MOTION, AUDIO, CAMERA }

private class ToolSpec(val tool: EditorTool, val icon: ImageVector, val label: Int)

private val TOOLS = listOf(
    ToolSpec(EditorTool.LAYERS, Icons.Outlined.Layers, R.string.tool_layers),
    ToolSpec(EditorTool.TEXT, Icons.Outlined.TextFields, R.string.tool_text),
    ToolSpec(EditorTool.SHAPES, Icons.Outlined.Category, R.string.tool_shapes),
    ToolSpec(EditorTool.MEDIA, Icons.Outlined.Image, R.string.tool_media),
    ToolSpec(EditorTool.MODEL3D, Icons.Outlined.ViewInAr, R.string.tool_3d),
    ToolSpec(EditorTool.EFFECTS, Icons.Outlined.AutoAwesome, R.string.effects),
    ToolSpec(EditorTool.MOTION, Icons.Outlined.Timeline, R.string.tool_motion),
    ToolSpec(EditorTool.AUDIO, Icons.Outlined.Audiotrack, R.string.tool_audio),
    ToolSpec(EditorTool.CAMERA, Icons.Outlined.CameraAlt, R.string.tool_camera),
)

/** Scrollable tool bar; [enabled] tells which tools need a selected layer. */
@Composable
fun EditorToolBar(enabled: (EditorTool) -> Boolean, onTool: (EditorTool) -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(KDims.ToolBar).background(KColors.Bar).horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        for (spec in TOOLS) {
            val on = enabled(spec.tool)
            val tint = if (on) KColors.TextSoft else KColors.Disabled
            Column(
                Modifier.widthIn(min = 64.dp).heightIn(min = KDims.MinTouch).clip(KShapes.Tile)
                    .clickable(enabled = on, role = Role.Button) { onTool(spec.tool) }.padding(horizontal = 8.dp, vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(spec.icon, null, tint = tint, modifier = Modifier.size(24.dp))
                Spacer(Modifier.height(2.dp))
                Text(stringResource(spec.label), color = tint, style = MaterialTheme.typography.labelSmall, maxLines = 1)
            }
        }
    }
}

/** Actions on the selected layer (shown only while a layer is selected). */
@Composable
fun SelectionActions(
    onProperties: () -> Unit,
    onSplit: () -> Unit,
    onDuplicate: () -> Unit,
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
) {
    Row(
        Modifier.fillMaxWidth().background(KColors.Bar).horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ActionChip(Icons.Outlined.Tune, R.string.tab_properties, onProperties, accent = true)
        ActionChip(Icons.Outlined.ContentCut, R.string.split, onSplit)
        ActionChip(Icons.Outlined.ContentCopy, R.string.duplicate, onDuplicate)
        ActionChip(Icons.Outlined.CreateNewFolder, R.string.group, onGroup)
        if (onAudio != null) ActionChip(Icons.Outlined.GraphicEq, R.string.audio_panel, onAudio)
        if (onExtractAudio != null) ActionChip(Icons.Outlined.MusicNote, R.string.extract_audio, onExtractAudio)
        if (onRemoveBackground != null) ActionChip(Icons.Outlined.PersonOff, R.string.remove_background, onRemoveBackground)
        if (onStabilize != null) ActionChip(Icons.Outlined.CenterFocusStrong, R.string.stabilize, onStabilize)
        ActionChip(Icons.Outlined.Delete, R.string.delete, onDelete, danger = true)
        ActionChip(Icons.Outlined.Repeat, R.string.loop, onLoop, accent = loop)
    }
}

@Composable
private fun ActionChip(icon: ImageVector, label: Int, onClick: () -> Unit, accent: Boolean = false, danger: Boolean = false) {
    val fg = when {
        danger -> KColors.Danger
        accent -> KColors.OnChipActive
        else -> KColors.TextSoft
    }
    Row(
        Modifier.heightIn(min = KDims.MinTouch).clip(KShapes.Pill).background(if (accent) KColors.ChipActive else KColors.Field)
            .clickable(role = Role.Button, onClick = onClick).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = fg, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(stringResource(label), color = fg, style = MaterialTheme.typography.labelMedium, maxLines = 1)
    }
}
