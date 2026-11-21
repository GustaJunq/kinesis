package dev.kinesis.editor.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.OpenWith
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.outlined.Diamond
import androidx.compose.material.icons.outlined.Grid3x3
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material.icons.outlined.ViewInAr
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import dev.kinesis.core.model.EditorViewMode
import dev.kinesis.editor.ui.settings.AppSettings
import dev.kinesis.editor.ui.theme.KType
import kotlin.math.roundToInt
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.kinesis.core.model.KnsProject
import dev.kinesis.core.model.LayerTree
import dev.kinesis.editor.R
import dev.kinesis.editor.ui.theme.KColors
import dev.kinesis.editor.ui.theme.KShapes

/** Height of [SplitHandle]. */
val SPLIT_HANDLE_HEIGHT = 24.dp

/** The timeline never gets smaller than four layers and its ruler. */
val TIMELINE_MIN_HEIGHT = 240.dp

/** A 1 px hairline between panels. */
@Composable
fun PanelDivider() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(KColors.Line))
}

/** The bar between the preview and the timeline: drag it up or down to give one more room than the other. */
@Composable
fun SplitHandle(onDrag: (Float) -> Unit) {
    val drag by rememberUpdatedState(onDrag)
    val label = stringResource(R.string.resize_panels)
    Box(
        Modifier.fillMaxWidth().height(SPLIT_HANDLE_HEIGHT).background(KColors.Bar)
            .semantics { contentDescription = label }
            .pointerInput(Unit) {
                detectVerticalDragGestures { change, dy ->
                    change.consume()
                    drag(dy)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.width(56.dp).height(5.dp).clip(KShapes.Pill).background(KColors.TextSecondary))
    }
}

/**
 * The preview on the dark backdrop. Floating buttons at its right: guides, overlays, the 3D editor view, the camera
 * and the canvas zoom (a pinch away from the selected layer zooms; tap the percentage to fit again).
 */
@Composable
fun PreviewArea(
    vm: EditorViewModel,
    project: KnsProject,
    time: Double,
    playing: Boolean,
    selection: String?,
    mediaRevision: Int,
    tool3D: Tool3D,
    onTool3D: (Tool3D) -> Unit,
    renderError: String?,
    onRenderError: (String?) -> Unit,
    drawMode: DrawMode? = null,
    onDrawn: (List<dev.kinesis.core.math.Vec2>, Boolean) -> Unit = { _, _ -> },
    onDrawCancel: () -> Unit = {},
) {
    val selectedLayer = selection?.let { LayerTree.find(project, it) }
    val context = LocalContext.current
    val view by vm.editorView.collectAsState()
    var showGrid by rememberSaveable { mutableStateOf(false) }
    var showOverlays by rememberSaveable { mutableStateOf(true) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    Box(Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 4.dp).clip(RoundedCornerShape(16.dp)).background(KColors.Background)) {
        if (project.layers.isEmpty() && drawMode == null) {
            Text(stringResource(R.string.no_layers_now), color = KColors.White, fontSize = 14.sp, modifier = Modifier.align(Alignment.Center))
        } else {
            Viewport(
                vm, project, time, playing, selection, mediaRevision, tool3D, onRenderError = { onRenderError(it) }, modifier = Modifier.fillMaxSize(),
                canvasZoom = zoom, canvasPan = pan, onCanvasView = { z, p -> zoom = z; pan = p },
                showGrid = showGrid, showOverlays = showOverlays,
                drawMode = drawMode, onDrawn = onDrawn, onDrawCancel = onDrawCancel,
            )
            renderError?.let { msg ->
                Text(
                    stringResource(R.string.render_error, msg), color = KColors.White, fontSize = 12.sp,
                    modifier = Modifier.align(Alignment.TopCenter).padding(8.dp).background(KColors.Danger.copy(alpha = 0.85f)).padding(8.dp)
                        .clickable { onRenderError(null) },
                )
            }
            RealCameraOverlay(vm, Modifier.matchParentSize())
            if (selectedLayer?.hasTransform3D == true) {
                IconButton(
                    onClick = { onTool3D(if (tool3D == Tool3D.MOVE) Tool3D.ROTATE else Tool3D.MOVE) },
                    modifier = Modifier.align(Alignment.TopStart).padding(8.dp).background(KColors.Bar.copy(alpha = 0.85f), KShapes.Tile),
                ) {
                    Icon(
                        if (tool3D == Tool3D.MOVE) Icons.Filled.OpenWith else Icons.Filled.ScreenRotation,
                        stringResource(if (tool3D == Tool3D.MOVE) R.string.tool_move else R.string.tool_rotate),
                        tint = KColors.Accent,
                    )
                }
            }
        }
        // Scrolls when the preview is short (properties docked, small split).
        Column(
            Modifier.align(Alignment.CenterEnd).padding(end = 8.dp).verticalScroll(androidx.compose.foundation.rememberScrollState()).padding(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            FloatingToggle(Icons.Outlined.Grid3x3, stringResource(R.string.view_guides), showGrid) { showGrid = !showGrid }
            FloatingToggle(
                if (showOverlays) Icons.Outlined.Visibility else Icons.Outlined.VisibilityOff,
                stringResource(R.string.view_overlays), showOverlays,
            ) { showOverlays = !showOverlays }
            FloatingToggle(Icons.Outlined.ViewInAr, stringResource(R.string.view_editor), view.mode == EditorViewMode.EDITOR) {
                vm.setViewMode(if (view.mode == EditorViewMode.EDITOR) EditorViewMode.CAMERA else EditorViewMode.EDITOR)
            }
            FloatingToggle(Icons.Outlined.Videocam, stringResource(R.string.enter_camera), view.mode == EditorViewMode.INSIDE) {
                if (view.mode == EditorViewMode.INSIDE) vm.setViewMode(EditorViewMode.CAMERA) else vm.enterCamera()
            }
            if (view.mode == EditorViewMode.INSIDE) {
                FloatingToggle(Icons.Outlined.Diamond, stringResource(R.string.auto_keyframe), AppSettings.autoKeyframe) {
                    AppSettings.setAutoKeyframe(context, !AppSettings.autoKeyframe)
                }
            }
            val fit = stringResource(R.string.view_fit)
            Box(
                Modifier.height(36.dp).widthIn(min = 48.dp).clip(KShapes.Pill).background(KColors.SurfaceAlt)
                    .clickable(role = Role.Button, onClickLabel = fit) { zoom = 1f; pan = Offset.Zero }
                    .padding(horizontal = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text("${(zoom * 100).roundToInt()}%", style = KType.MonoSmall, color = KColors.TextPrimary)
            }
        }
    }
}

/** A round button over the preview; filled with the accent while its option is on. */
@Composable
private fun FloatingToggle(icon: ImageVector, label: String, on: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(40.dp).clip(CircleShape).background(if (on) KColors.Primary else KColors.SurfaceAlt)
            .clickable(role = Role.Switch, onClickLabel = label, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, tint = if (on) KColors.OnPrimary else KColors.TextPrimary, modifier = Modifier.size(20.dp))
    }
}
