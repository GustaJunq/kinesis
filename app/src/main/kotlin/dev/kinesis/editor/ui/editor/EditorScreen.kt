package dev.kinesis.editor.ui.editor

import android.app.Application
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.OpenWith
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Button
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material.icons.filled.AutoAwesome
import dev.kinesis.core.model.Effect
import dev.kinesis.core.model.EffectShortcut
import dev.kinesis.core.model.Mask
import dev.kinesis.editor.ui.common.TextInputDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.kinesis.core.anim.anim
import dev.kinesis.core.edit.Factory
import dev.kinesis.core.edit.LayerProps
import dev.kinesis.core.edit.keyframeTimes
import dev.kinesis.core.math.Vec2
import dev.kinesis.core.model.AssetType
import dev.kinesis.core.model.FillSpec
import dev.kinesis.core.model.LayerTree
import dev.kinesis.core.model.PathPoint
import dev.kinesis.core.model.PathSpec
import dev.kinesis.core.model.ShapeKind
import dev.kinesis.core.model.StrokeSpec
import dev.kinesis.core.model.updateModel3D
import dev.kinesis.core.model.updateObject3D
import dev.kinesis.core.model.updateShape
import dev.kinesis.core.model.updateText
import dev.kinesis.editor.R
import dev.kinesis.editor.ui.common.formatTime
import dev.kinesis.editor.ui.theme.KColors
import dev.kinesis.editor.ui.theme.KShapes

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(projectId: String, onClose: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as Application
    val factory = remember(projectId) {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = EditorViewModel(app, projectId) as T
        }
    }
    val vm: EditorViewModel = viewModel(key = "editor-$projectId", factory = factory)

    val project by vm.project.collectAsState()
    val time by vm.time.collectAsState()
    val playing by vm.playing.collectAsState()
    val selection by vm.selection.collectAsState()
    val multi by vm.multiSelection.collectAsState()
    val canUndo by vm.canUndo.collectAsState()
    val canRedo by vm.canRedo.collectAsState()
    val mediaRevision by vm.mediaRevision.collectAsState()
    val busy by vm.busy.collectAsState()
    val exportState by vm.exportState.collectAsState()
    val loop by vm.loopPlayback.collectAsState()

    var showLayers by remember { mutableStateOf(false) }
    var showProps by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var addSection by remember { mutableStateOf<AddSection?>(null) }
    var showEffects by remember { mutableStateOf(false) }
    var propsFocus by remember { mutableStateOf<String?>(null) }
    var showExport by remember { mutableStateOf(false) }
    var curveTarget by remember { mutableStateOf<CurveTarget?>(null) }
    var renderError by remember { mutableStateOf<String?>(null) }
    var tool3D by remember { mutableStateOf(Tool3D.MOVE) }
    var textureTarget by remember { mutableStateOf<String?>(null) }
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(vm) { vm.notices.collect { snackbar.showSnackbar(it.text) } }
    DisposableEffect(vm) { onDispose { vm.pause(); vm.flushSave() } }

    val loadError = vm.loadError
    if (loadError != null) {
        Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Text(stringResource(R.string.project_unreadable), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Text(loadError, color = KColors.TextSecondary, textAlign = TextAlign.Center)
            Spacer(Modifier.height(16.dp))
            dev.kinesis.editor.ui.common.PrimaryButton(stringResource(R.string.close), onClose)
        }
        return
    }

    val layerName = { res: Int -> context.getString(res) }
    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) vm.importMedia(uri, AssetType.IMAGE) { p, a -> Factory.image(p, vm.time.value, a, a.name ?: layerName(R.string.layer_image)) }
    }
    val pickVideo = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) vm.importMedia(uri, AssetType.VIDEO) { p, a -> Factory.video(p, vm.time.value, a, a.name ?: layerName(R.string.layer_video)) }
    }
    val pickAudio = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importMedia(uri, AssetType.AUDIO) { p, a -> Factory.audio(p, vm.time.value, a, a.name ?: layerName(R.string.layer_audio)) }
    }
    val pickModel = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importMedia(uri, AssetType.MODEL) { p, a -> Factory.model3d(p, vm.time.value, a, a.name ?: layerName(R.string.layer_model3d)) }
    }
    val pickFont = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val target = vm.selection.value
        if (uri != null) {
            vm.importMedia(uri, AssetType.FONT, onImported = { asset ->
                if (target != null) vm.updateLayer(target) { l -> l.updateText { t -> t.copy(font = t.font.copy(asset = asset.id)) } }
            }) { _, _ -> null }
        }
    }
    val pickTexture = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        val target = textureTarget
        textureTarget = null
        if (uri != null && target != null) {
            vm.importMedia(uri, AssetType.IMAGE, onImported = { asset ->
                vm.updateLayer(target) { l ->
                    l.updateObject3D { o -> o.copy(material = o.material.copy(texture = asset.id)) }
                        .updateModel3D { m -> m.copy(material = m.material?.copy(texture = asset.id)) }
                }
            }) { _, _ -> null }
        }
    }

    fun handleAdd(action: AddAction) {
        addSection = null
        val p = vm.current
        val t = vm.time.value
        when (action) {
            AddAction.TEXT -> vm.addLayer(Factory.text(p, t, context.getString(R.string.default_text), context.getString(R.string.layer_text)))
            AddAction.RECTANGLE -> vm.addLayer(Factory.shape(p, t, ShapeKind.RECTANGLE, context.getString(R.string.shape_rectangle)))
            AddAction.ELLIPSE -> vm.addLayer(Factory.shape(p, t, ShapeKind.ELLIPSE, context.getString(R.string.shape_ellipse)))
            AddAction.POLYGON -> vm.addLayer(Factory.shape(p, t, ShapeKind.POLYGON, context.getString(R.string.shape_polygon)))
            AddAction.STAR -> vm.addLayer(Factory.shape(p, t, ShapeKind.STAR, context.getString(R.string.shape_star)))
            AddAction.LINE -> {
                val base = Factory.shape(p, t, ShapeKind.PATH, context.getString(R.string.shape_line))
                val half = p.composition.width * 0.3f
                vm.addLayer(
                    base.updateShape { c ->
                        c.copy(
                            path = PathSpec(closed = false, points = listOf(PathPoint(Vec2(-half, 0f)), PathPoint(Vec2(half, 0f)))),
                            fill = FillSpec(enabled = false),
                            stroke = StrokeSpec(color = c.fill.color, width = anim(12f)),
                        )
                    },
                )
            }
            AddAction.IMAGE -> pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            AddAction.VIDEO -> pickVideo.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly))
            AddAction.AUDIO -> pickAudio.launch(arrayOf("audio/*"))
            AddAction.MODEL -> pickModel.launch(arrayOf("model/gltf-binary", "model/gltf+json", "application/octet-stream", "*/*"))
            AddAction.CAMERA -> vm.addLayer(Factory.camera(p, t, context.getString(R.string.layer_camera)))
            else -> {
                primitiveFor(action)?.let { vm.addLayer(Factory.object3d(p, t, it, context.getString(primitiveRes(it)))) }
                lightFor(action)?.let { vm.addLayer(Factory.light(p, t, it, context.getString(lightKindRes(it)))) }
            }
        }
    }

    val selectedLayer = selection?.let { LayerTree.find(project, it) }

    BoxWithConstraints(Modifier.fillMaxSize().background(KColors.Background)) {
        val boxMaxHeight = maxHeight
        val sheetHeight = boxMaxHeight * 0.62f
        Column(Modifier.fillMaxSize()) {
            EditorTopBar(
                title = project.meta.title,
                width = project.composition.width,
                height = project.composition.height,
                fps = project.composition.fps,
                duration = project.composition.duration,
                onClose = { vm.flushSave(); onClose() },
                onRename = { renaming = true },
                onSettings = { vm.pause(); showSettings = true },
                onExport = { vm.pause(); showExport = true },
            )
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp), color = KColors.Accent)

            // Viewport
            Box(Modifier.weight(1f).fillMaxWidth()) {
                Viewport(
                    vm, project, time, playing, selection, mediaRevision, tool3D,
                    onRenderError = { renderError = it },
                    modifier = Modifier.fillMaxSize(),
                )
                renderError?.let { msg ->
                    Text(
                        stringResource(R.string.render_error, msg), color = KColors.White, fontSize = 12.sp,
                        modifier = Modifier.align(Alignment.TopCenter).padding(8.dp).background(KColors.Danger.copy(alpha = 0.85f)).padding(8.dp)
                            .clickable { renderError = null },
                    )
                }
                if (selectedLayer?.type?.isRendered3D == true) {
                    IconButton(
                        onClick = { tool3D = if (tool3D == Tool3D.MOVE) Tool3D.ROTATE else Tool3D.MOVE },
                        modifier = Modifier.align(Alignment.TopEnd).padding(8.dp).background(KColors.Bar.copy(alpha = 0.85f), KShapes.Tile),
                    ) {
                        Icon(
                            if (tool3D == Tool3D.MOVE) Icons.Filled.OpenWith else Icons.Filled.ScreenRotation,
                            stringResource(if (tool3D == Tool3D.MOVE) R.string.tool_move else R.string.tool_rotate),
                            tint = KColors.Accent,
                        )
                    }
                }
            }

            // Timeline (always visible)
            Timeline(vm, project, time, playing, selection, multi, Modifier.fillMaxWidth().height(boxMaxHeight * 0.30f))

            EditorTransport(
                time = time,
                fps = project.composition.fps,
                playing = playing,
                canUndo = canUndo,
                canRedo = canRedo,
                hasSelection = selectedLayer != null,
                onPrevKeyframe = {
                    val l = selectedLayer
                    if (l == null) vm.seek(0.0) else {
                        val offset = LayerTree.containerOffset(project, l.id) + l.start
                        l.keyframeTimes().map { it + offset }.lastOrNull { it < time - 1e-4 }?.let { vm.seek(it) } ?: vm.seek(0.0)
                    }
                },
                onNextKeyframe = {
                    val l = selectedLayer
                    if (l != null) {
                        val offset = LayerTree.containerOffset(project, l.id) + l.start
                        l.keyframeTimes().map { it + offset }.firstOrNull { it > time + 1e-4 }?.let { vm.seek(it) }
                    }
                },
                onPlay = { vm.togglePlay() },
                onAddKeyframe = { vm.addKeyframeAtPlayhead() },
                onUndo = { vm.undo() },
                onRedo = { vm.redo() },
            )

            if (selectedLayer != null) {
                SelectionActions(
                    onProperties = { vm.pause(); propsFocus = null; showProps = true },
                    onSplit = { vm.splitSelected() },
                    onDuplicate = { vm.duplicateSelected() },
                    onGroup = { vm.groupSelection() },
                    onDelete = { vm.deleteSelection() },
                    loop = loop,
                    onLoop = { vm.loopPlayback.value = !loop },
                )
            }
            EditorToolBar(
                enabled = { tool ->
                    when (tool) {
                        EditorTool.EFFECTS -> selectedLayer?.type?.isVisual == true
                        EditorTool.MOTION -> selectedLayer != null
                        else -> true
                    }
                },
                onTool = { tool ->
                    vm.pause()
                    when (tool) {
                        EditorTool.LAYERS -> showLayers = true
                        EditorTool.TEXT -> handleAdd(AddAction.TEXT)
                        EditorTool.SHAPES -> addSection = AddSection.SHAPES
                        EditorTool.MEDIA -> addSection = AddSection.MEDIA
                        EditorTool.MODEL3D -> addSection = AddSection.MODEL3D
                        EditorTool.AUDIO -> handleAdd(AddAction.AUDIO)
                        EditorTool.CAMERA -> addSection = AddSection.SCENE
                        EditorTool.EFFECTS -> showEffects = true
                        EditorTool.MOTION -> { propsFocus = null; showProps = true }
                    }
                },
            )
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = 80.dp))

        if (showProps || showSettings) {
            ModalBottomSheet(onDismissRequest = { showProps = false; showSettings = false; propsFocus = null }, containerColor = KColors.Sheet, shape = KShapes.Sheet) {
                Column(Modifier.fillMaxWidth().height(sheetHeight)) {
                    PropertiesPanel(
                        vm, project, time, if (showSettings) null else selection,
                        onEditCurve = { curveTarget = it },
                        onPickTexture = { id -> textureTarget = id; pickTexture.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                        onPickFont = { pickFont.launch(arrayOf("font/*", "application/x-font-ttf", "application/x-font-otf", "application/octet-stream", "*/*")) },
                        modifier = Modifier.weight(1f),
                        onAddEffect = { showProps = false; showEffects = true },
                        focusSection = propsFocus,
                    )
                    curveTarget?.let { target ->
                        val layer = LayerTree.find(project, target.layerId)
                        val prop = layer?.let { LayerProps.find(it, target.propId) }
                        val animatable = layer?.let { l -> prop?.get(l) }
                        val keyframe = animatable?.keyframes?.getOrNull(target.keyIndex)
                        if (layer == null || prop == null || animatable == null || keyframe == null) {
                            curveTarget = null
                        } else {
                            CurveEditorPanel(
                                easing = keyframe.easing,
                                loop = animatable.loop,
                                onEasing = { vm.setEasing(layer.id, prop, target.keyIndex, it) },
                                onLoop = { vm.setLoop(layer.id, prop, it) },
                                onClose = { curveTarget = null },
                            )
                        }
                    }
                }
            }
        }
        if (showLayers) {
            ModalBottomSheet(onDismissRequest = { showLayers = false }, containerColor = KColors.Sheet, shape = KShapes.Sheet) {
                Box(Modifier.fillMaxWidth().height(sheetHeight)) { LayersPanel(vm, project, selection, multi, Modifier.fillMaxSize()) }
            }
        }
    }

    if (renaming) {
        TextInputDialog(stringResource(R.string.rename), project.meta.title, onDismiss = { renaming = false }) { vm.rename(it.ifBlank { project.meta.title }); renaming = false }
    }
    addSection?.let { section -> AddSheet(section, onDismiss = { addSection = null }, onAdd = ::handleAdd) }
    val effectsTarget = selection?.let { LayerTree.find(project, it) }
    if (showEffects && effectsTarget != null) {
        AddEffectSheet(
            effectsTarget,
            onDismiss = { showEffects = false },
            onAdd = { type ->
                vm.updateLayer(effectsTarget.id) { it.copy(effects = it.effects + Effect.create(type)) }
                showEffects = false
                propsFocus = "effects"
                showProps = true
            },
            onShortcut = { shortcut ->
                showEffects = false
                if (shortcut == EffectShortcut.MASK && effectsTarget.masks.isEmpty()) {
                    vm.updateLayer(effectsTarget.id) { it.copy(masks = listOf(Mask())) }
                }
                propsFocus = when (shortcut) {
                    EffectShortcut.MASK -> "masks"
                    EffectShortcut.TRACK_MATTE -> "matte"
                    EffectShortcut.TRANSITIONS -> "transitions"
                    EffectShortcut.TEXT_ANIMATORS -> "animators"
                }
                showProps = true
            },
        )
    }
    if (showExport) ExportDialog(vm, project, exportState, onDismiss = { showExport = false })
}
