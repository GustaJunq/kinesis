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

/** Back, project settings, the title with "W×H · fps" (tap the title to rename), and Export. */
@Composable
fun EditorTopBar(
    title: String,
    width: Int,
    height: Int,
    fps: Double,
    onClose: () -> Unit,
    onRename: () -> Unit,
    onSettings: () -> Unit,
    onExport: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().height(KDims.TopBar).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircleButton(Lucide.ArrowLeft, stringResource(R.string.close), onClose)
        CircleButton(Lucide.Settings, stringResource(R.string.settings), onSettings)
        Column(Modifier.weight(1f).padding(horizontal = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.clickable(onClickLabel = stringResource(R.string.edit_title), role = Role.Button, onClick = onRename),
            )
            val fpsText = if (fps == fps.toInt().toDouble()) fps.toInt().toString() else String.format(Locale.US, "%.2f", fps)
            Text("${width}×$height · $fpsText fps", style = KType.MonoSmall, color = KColors.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Box(
            Modifier.heightIn(min = KDims.MinTouch).clickable(role = Role.Button, onClick = onExport),
            contentAlignment = Alignment.Center,
        ) {
            Row(
                Modifier.height(40.dp).clip(KShapes.Pill).background(KColors.Primary).padding(horizontal = 18.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.export), color = KColors.White, style = MaterialTheme.typography.labelLarge, maxLines = 1)
            }
        }
    }
}

/** Marker, undo · timecode · redo, duplicate and the switch of the view pill: round buttons aligned with the edges of the timeline. */
@Composable
fun EditorControls(
    time: Double,
    fps: Double,
    canUndo: Boolean,
    canRedo: Boolean,
    hasSelection: Boolean,
    onMarker: () -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onDuplicate: () -> Unit,
    viewPillShown: Boolean,
    onToggleViewPill: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = TIMELINE_SIDE - 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircleButton(Lucide.Bookmark, stringResource(R.string.add_marker), onMarker)
        Spacer(Modifier.weight(1f))
        CircleButton(Lucide.Undo2, stringResource(R.string.undo), onUndo, enabled = canUndo)
        Text(
            formatTime(time, fps), style = KType.MonoLarge, color = KColors.TextPrimary,
            modifier = Modifier.widthIn(min = 72.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        CircleButton(Lucide.Redo2, stringResource(R.string.redo), onRedo, enabled = canRedo)
        Spacer(Modifier.weight(1f))
        CircleButton(Lucide.Copy, stringResource(R.string.duplicate), onDuplicate, enabled = hasSelection)
        CircleButton(
            if (viewPillShown) Lucide.PanelRightClose else Lucide.PanelRightOpen,
            stringResource(if (viewPillShown) R.string.hide_view_pill else R.string.show_view_pill), onToggleViewPill,
        )
    }
}

enum class EditorTool { LAYERS, SHAPES, TEXT, MEDIA, MODEL3D, EFFECTS, MOTION, AUDIO, CAMERA, CAPTIONS }

private class ToolSpec(val tool: EditorTool, val icon: ImageVector, val label: Int)

private val TOOLS = listOf(
    ToolSpec(EditorTool.LAYERS, Lucide.Layers, R.string.tool_layers),
    ToolSpec(EditorTool.SHAPES, Lucide.Shapes, R.string.tool_shapes),
    ToolSpec(EditorTool.TEXT, Lucide.Type, R.string.tool_text),
    ToolSpec(EditorTool.MEDIA, Lucide.Image, R.string.tool_media),
    ToolSpec(EditorTool.MODEL3D, Lucide.Box, R.string.tool_3d),
    ToolSpec(EditorTool.EFFECTS, Lucide.Sparkles, R.string.effects),
    ToolSpec(EditorTool.MOTION, Lucide.Activity, R.string.tool_motion),
    ToolSpec(EditorTool.AUDIO, Lucide.Music, R.string.tool_audio),
    ToolSpec(EditorTool.CAMERA, Lucide.Camera, R.string.tool_camera),
    ToolSpec(EditorTool.CAPTIONS, Lucide.Captions, R.string.captions),
)

private val TRANSPORT_HEIGHT = 56.dp
private val TRANSPORT_GAP = 8.dp
private val BAR_HEIGHT = 64.dp

/** Total height of [EditorBottomBar]: the three round buttons, the gap, the tool bar and the scroll indicator. */
val EDITOR_BOTTOM_HEIGHT = TRANSPORT_HEIGHT + TRANSPORT_GAP + BAR_HEIGHT + 10.dp

/**
 * Previous · play · next as three separate round buttons above the tool bar. The tool bar (skewed, its active item too)
 * scrolls sideways and says so: its last item fades out, a chevron sits at the end and a thin indicator runs below.
 * [active] is the tool whose panel is open (or the first one).
 */
@Composable
fun EditorBottomBar(
    playing: Boolean,
    canNavigate: Boolean,
    active: EditorTool,
    enabled: (EditorTool) -> Boolean,
    onPrev: () -> Unit,
    onPlay: () -> Unit,
    onNext: () -> Unit,
    onTool: (EditorTool) -> Unit,
) {
    val scroll = rememberScrollState()
    var viewport by remember { mutableIntStateOf(0) }
    val sideInset = KSkew.Bar.inset(BAR_HEIGHT)
    Column(Modifier.fillMaxWidth().height(EDITOR_BOTTOM_HEIGHT), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(
            Modifier.height(TRANSPORT_HEIGHT),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            CircleButton(Lucide.SkipBack, stringResource(R.string.prev_keyframe), onPrev, enabled = canNavigate)
            Box(
                Modifier.size(TRANSPORT_HEIGHT).clickable(
                    role = Role.Button, onClickLabel = stringResource(if (playing) R.string.pause else R.string.play), onClick = onPlay,
                ),
                contentAlignment = Alignment.Center,
            ) {
                Box(Modifier.size(52.dp).clip(CircleShape).background(KColors.Primary), contentAlignment = Alignment.Center) {
                    Icon(
                        if (playing) Lucide.Pause else Lucide.Play, stringResource(if (playing) R.string.pause else R.string.play),
                        tint = KColors.White, modifier = Modifier.size(26.dp),
                    )
                }
            }
            CircleButton(Lucide.SkipForward, stringResource(R.string.next_keyframe), onNext, enabled = canNavigate)
        }
        Spacer(Modifier.height(TRANSPORT_GAP))
        // Tool bar
        Box(
            Modifier.fillMaxWidth().height(BAR_HEIGHT).padding(horizontal = 4.dp).testTag("tool-bar")
                .clip(KSkew.Bar).background(KColors.SurfaceAlt).onSizeChanged { viewport = it.width },
        ) {
            Row(
                Modifier.fillMaxHeight().horizontalScroll(scroll).padding(start = sideInset + 4.dp, end = sideInset + 36.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                for (spec in TOOLS) ToolItem(spec, spec.tool == active, enabled(spec.tool)) { onTool(spec.tool) }
            }
            val fadeAlpha by animateFloatAsState(if (scroll.canScrollForward) 1f else 0f, label = "toolbar-fade")
            if (fadeAlpha > 0f) {
                Box(
                    Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(88.dp)
                        .background(Brush.horizontalGradient(listOf(Color.Transparent, KColors.SurfaceAlt.copy(alpha = fadeAlpha)))),
                )
                // Only a hint: it takes no touches, so the tools under it can still be tapped.
                Icon(
                    Lucide.ChevronRight, null, tint = KColors.TextPrimary.copy(alpha = fadeAlpha),
                    modifier = Modifier.align(Alignment.CenterEnd).padding(end = sideInset + 12.dp).size(22.dp),
                )
            }
        }
        // Scroll indicator
        val maxScroll = scroll.maxValue
        if (maxScroll > 0 && viewport > 0) {
            val track = 64.dp
            val fraction = (viewport.toFloat() / (viewport + maxScroll)).coerceIn(0.1f, 1f)
            val progress = scroll.value.toFloat() / maxScroll
            Box(Modifier.padding(top = 5.dp).width(track).height(3.dp).clip(KShapes.Pill).background(KColors.LineStrong)) {
                Box(
                    Modifier.offset(x = track * ((1f - fraction) * progress)).width(track * fraction).fillMaxHeight()
                        .clip(KShapes.Pill).background(KColors.Primary),
                )
            }
        }
    }
}

@Composable
private fun ToolItem(spec: ToolSpec, active: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val fg = when {
        !enabled -> KColors.Disabled
        active -> KColors.White
        else -> KColors.TextPrimary
    }
    // The skewed pill is only the background; the icon and the label stay upright.
    Box(
        Modifier.widthIn(min = 68.dp).fillMaxHeight().clip(KSkew.Pill)
            .background(if (active) KColors.Primary else Color.Transparent)
            .clickable(enabled = enabled, role = Role.Tab, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Column(Modifier.padding(horizontal = 14.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Icon(spec.icon, null, tint = fg, modifier = Modifier.size(22.dp))
            Spacer(Modifier.height(2.dp))
            Text(stringResource(spec.label), color = fg, style = MaterialTheme.typography.labelSmall, maxLines = 1)
        }
    }
}

/** Actions on the selected layer (shown only while a layer is selected). */
@Composable
fun SelectionActions(
    onProperties: () -> Unit,
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
    onRotoBrush: (() -> Unit)? = null,
) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ActionChip(Icons.Outlined.Tune, R.string.tab_properties, onProperties, accent = true)
        KeyframeChip(hasKeyframeHere, onAddKeyframe)
        ActionChip(Icons.Outlined.ContentCut, R.string.split, onSplit)
        ActionChip(Icons.Outlined.CreateNewFolder, R.string.group, onGroup)
        if (onAudio != null) ActionChip(Icons.Outlined.GraphicEq, R.string.audio_panel, onAudio)
        if (onExtractAudio != null) ActionChip(Icons.Outlined.MusicNote, R.string.extract_audio, onExtractAudio)
        if (onRemoveBackground != null) ActionChip(Icons.Outlined.PersonOff, R.string.remove_background, onRemoveBackground)
        if (onRotoBrush != null) ActionChip(Icons.Outlined.Brush, R.string.roto_brush, onRotoBrush)
        if (onStabilize != null) ActionChip(Icons.Outlined.CenterFocusStrong, R.string.stabilize, onStabilize)
        if (onRealCamera != null) ActionChip(Icons.Outlined.CameraAlt, R.string.real_camera, onRealCamera)
        ActionChip(Icons.Outlined.Delete, R.string.delete, onDelete, danger = true)
        ActionChip(Icons.Outlined.Repeat, R.string.loop, onLoop, accent = loop)
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
