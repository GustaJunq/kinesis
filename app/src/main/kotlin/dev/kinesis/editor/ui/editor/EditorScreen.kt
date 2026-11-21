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
import androidx.compose.ui.layout.onSizeChanged
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
import dev.kinesis.core.edit.KeyframeNav
import dev.kinesis.core.edit.Markers
import dev.kinesis.core.model.LayerTree
import dev.kinesis.core.model.LayerType
import dev.kinesis.core.model.PathPoint
import dev.kinesis.core.model.PathSpec
import dev.kinesis.core.model.ShapeKind
import dev.kinesis.core.model.StrokeSpec
import dev.kinesis.core.model.updateModel3D
import dev.kinesis.core.model.updateObject3D
import dev.kinesis.core.model.updateShape
import dev.kinesis.core.model.updateText
import dev.kinesis.editor.R
import dev.kinesis.editor.ui.settings.AppSettings
import dev.kinesis.editor.ui.common.formatTime
import dev.kinesis.editor.ui.theme.KColors
import dev.kinesis.editor.ui.theme.KShapes

/** Free space left and right of the preview: the camera / editor-view pill sits in the right one. */
private val PREVIEW_GUTTER = 8.dp
private val PREVIEW_RADIUS = 24.dp

/** The editor, in its own palette (black around the preview, near-black panels, one purple for what is active). */
@Composable
fun EditorScreen(projectId: String, onClose: () -> Unit) {
    dev.kinesis.editor.ui.theme.EditorTheme { EditorContent(projectId, onClose) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditorContent(projectId: String, onClose: () -> Unit) {
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
    var showCaptions by remember { mutableStateOf(false) }
    var showPlugins by remember { mutableStateOf(false) }
    var showElements by remember { mutableStateOf(false) }
    var showAudio by remember { mutableStateOf(false) }
    var stabilizeTarget by remember { mutableStateOf<String?>(null) }
    var showRealCamera by remember { mutableStateOf(false) }
    var editingMarker by remember { mutableStateOf<String?>(null) }
    var rotoRequest by remember { mutableStateOf<RotoRequest?>(null) }
    val mediaJob by vm.mediaJob.collectAsState()
    var curveTarget by remember { mutableStateOf<CurveTarget?>(null) }
    var showGraph by remember { mutableStateOf(false) }
    var renderError by remember { mutableStateOf<String?>(null) }
    var tool3D by remember { mutableStateOf(Tool3D.MOVE) }
    /** The properties of the selected layer, docked where the shortcut bar is. */
    var propsDocked by remember { mutableStateOf(false) }
    var fullscreen by rememberSaveable { mutableStateOf(false) }
    var addTab by remember { mutableStateOf<AddTab?>(null) }
    var drawMode by remember { mutableStateOf<DrawMode?>(null) }
    /** Share of the preview in the space it splits with the timeline (the handle between them changes it). */
    var previewShare by rememberSaveable { mutableStateOf(0.56f) }
    var textureTarget by remember { mutableStateOf<String?>(null) }
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(vm) { vm.notices.collect { snackbar.showSnackbar(it.text) } }
    // A notice about the previous layer goes away when another layer is picked (it would cover the layer editor).
    LaunchedEffect(vm.selection.collectAsState().value) { snackbar.currentSnackbarData?.dismiss() }
    DisposableEffect(vm) { onDispose { vm.pause(); vm.flushSave() } }
    val lifecycle = androidx.compose.ui.platform.LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, vm) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, e ->
            if (e == androidx.lifecycle.Lifecycle.Event.ON_STOP) vm.onBackground()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

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
                if (target != null) vm.updateLayer(target) { l ->
                    l.updateText { t -> t.copy(font = t.font.copy(asset = asset.id)) }
                        .updateObject3D { o -> o.copy(text = (o.text ?: dev.kinesis.core.model.Text3D()).let { t3 -> t3.copy(font = t3.font.copy(asset = asset.id)) }) }
                }
            }) { _, _ -> null }
        }
    }
    val pickTexture = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        val target = textureTarget
        textureTarget = null
        if (uri != null && target == ENVIRONMENT_TARGET) {
            vm.importMedia(uri, AssetType.IMAGE, onImported = { asset ->
                vm.edit { p -> p.copy(composition = p.composition.copy(environment = p.composition.environment.copy(asset = asset.id))) }
            }) { _, _ -> null }
        } else if (uri != null && target != null) {
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
            AddAction.TEXT3D -> vm.addLayer(Factory.text3d(p, t, context.getString(R.string.default_text), context.getString(R.string.prim_text3d)))
            AddAction.NULL_2D -> vm.addLayer(Factory.nullObject(p, t, false, context.getString(R.string.layer_null)))
            AddAction.NULL_3D -> vm.addLayer(Factory.nullObject(p, t, true, context.getString(R.string.null_3d)))
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
        LaunchedEffect(selection) { if (selection == null) propsDocked = false }
        // Back closes what is open in the editor before it leaves it: drawing, full screen, the docked properties, the selection.
        androidx.activity.compose.BackHandler(enabled = drawMode != null || fullscreen || propsDocked || selection != null) {
            when {
                drawMode != null -> drawMode = null
                fullscreen -> fullscreen = false
                propsDocked -> propsDocked = false
                else -> vm.select(null)
            }
        }
        Column(Modifier.fillMaxSize()) {
            EditorTopBar(
                title = project.meta.title,
                onClose = { vm.flushSave(); onClose() },
                onRename = { vm.rename(it.ifBlank { project.meta.title }) },
                onSettings = { vm.pause(); showSettings = true },
                onExport = { vm.pause(); showExport = true },
            )
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp), color = KColors.Primary)

            // Preview and timeline share the space between the bars; the handle between them moves the split.
            var splitPx by remember { mutableStateOf(1f) }
            val density = androidx.compose.ui.platform.LocalDensity.current
            // The timeline keeps room for at least four layers; the preview keeps a usable minimum too.
            val fixedPx = with(density) { (SPLIT_HANDLE_HEIGHT + TRANSPORT_BAR_HEIGHT + 2.dp).toPx() }
            val sharedPx = (splitPx - fixedPx).coerceAtLeast(1f)
            val maxShare = (1f - with(density) { TIMELINE_MIN_HEIGHT.toPx() } / sharedPx).coerceIn(0.05f, 0.95f)
            val minShare = (with(density) { 120.dp.toPx() } / sharedPx).coerceIn(0.05f, maxShare)
            val share = previewShare.coerceIn(minShare, maxShare)
            Column(Modifier.weight(1f).fillMaxWidth().onSizeChanged { splitPx = it.height.toFloat().coerceAtLeast(1f) }) {
                Box(Modifier.weight(if (fullscreen) 1f else share).fillMaxWidth()) {
                    PreviewArea(
                        vm = vm, project = project, time = time, playing = playing, selection = selection,
                        mediaRevision = mediaRevision, tool3D = tool3D, onTool3D = { tool3D = it },
                        renderError = renderError, onRenderError = { renderError = it },
                        drawMode = drawMode,
                        onDrawn = { pts, closed -> drawMode = null; addDrawing(vm, context, pts, closed) },
                        onDrawCancel = { drawMode = null },
                    )
                }
                if (!fullscreen) {
                    SplitHandle(onDrag = { dy -> previewShare = (share + dy / sharedPx).coerceIn(minShare, maxShare) })
                }
                PanelDivider()
                TransportBar(
                    playing = playing,
                    canUndo = canUndo,
                    canRedo = canRedo,
                    hasSelection = selectedLayer != null,
                    fullscreen = fullscreen,
                    onUndo = { vm.undo() },
                    onRedo = { vm.redo() },
                    onStart = { vm.pause(); vm.seek(0.0) },
                    onPlay = { vm.togglePlay() },
                    onEnd = { vm.pause(); vm.seek(project.composition.duration) },
                    onMarker = {
                        // A marker already at the playhead is edited instead of duplicated.
                        val here = Markers.at(project, time, window = 0.5 / project.composition.fps)
                        if (here != null) editingMarker = here.id else vm.addMarkerAtPlayhead()
                    },
                    onDuplicate = { vm.duplicateSelected() },
                    onFullscreen = { fullscreen = !fullscreen },
                )
                if (!fullscreen) {
                    Timeline(
                        vm, project, time, playing, selection, multi,
                        onEditMarker = { editingMarker = it },
                        onClipTap = { id -> vm.select(id); propsFocus = null; propsDocked = true },
                        onOpenLayers = { vm.pause(); showLayers = true },
                        modifier = Modifier.weight(1f - share).fillMaxWidth().background(KColors.Bar),
                    )
                }
            }

            if (!fullscreen) {
                PanelDivider()
                if (selectedLayer != null) {
                    SelectionActions(
                        propertiesOpen = propsDocked,
                        onProperties = { vm.pause(); propsFocus = null; propsDocked = !propsDocked },
                        onEffects = if (selectedLayer.type.isVisual) ({ vm.pause(); showEffects = true }) else null,
                        hasKeyframeHere = vm.hasKeyframeAtPlayhead(project, time),
                        onAddKeyframe = { vm.toggleKeyframeAtPlayhead() },
                        onSplit = { vm.splitSelected() },
                        onGroup = { vm.groupSelection() },
                        onDelete = { vm.deleteSelection() },
                        loop = loop,
                        onLoop = { vm.loopPlayback.value = !loop },
                        onAudio = if (selectedLayer.media != null) ({ showAudio = true }) else null,
                        onExtractAudio = if (vm.canExtractAudio(selectedLayer)) ({ vm.extractAudio(selectedLayer.id) }) else null,
                        onRemoveBackground = if (vm.canRemoveBackground(selectedLayer)) ({ vm.removeBackground(selectedLayer.id) }) else null,
                        onStabilize = if (vm.canStabilize(selectedLayer)) ({ stabilizeTarget = selectedLayer.id }) else null,
                        onCutoutBrush = if (vm.canCutoutBrush(selectedLayer)) ({
                            vm.pause()
                            rotoRequest = vm.rotoRequest(selectedLayer.id) ?: run { vm.notifyUser(context.getString(R.string.roto_open_failed), true); null }
                        }) else null,
                        onRealCamera = if (selectedLayer.type == LayerType.CAMERA && vm.realCamera.available) ({ vm.pause(); showRealCamera = true }) else null,
                        onClose = { vm.select(null) },
                    )
                    if (propsDocked) {
                        PanelDivider()
                        // The properties take the place of the shortcut bar while a layer is selected.
                        Column(Modifier.fillMaxWidth().height(boxMaxHeight * 0.45f).background(KColors.Sheet)) {
                            PropertiesPanel(
                                vm, project, time, selection,
                                onEditCurve = { curveTarget = it },
                                onPickTexture = { id -> textureTarget = id; pickTexture.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                                onPickFont = { pickFont.launch(arrayOf("font/*", "application/x-font-ttf", "application/x-font-otf", "application/octet-stream", "*/*")) },
                                modifier = Modifier.weight(1f),
                                onAddEffect = { showEffects = true },
                                focusSection = propsFocus,
                            )
                            CurveEditors(vm, project, curveTarget, showGraph, onShowGraph = { showGraph = it }, onClose = { curveTarget = null })
                        }
                    }
                } else {
                    ShortcutBar(
                        onShape = { vm.pause(); addTab = AddTab.SHAPE },
                        onMedia = { vm.pause(); addTab = AddTab.MEDIA },
                        onAudio = { vm.pause(); addTab = AddTab.AUDIO },
                        onText = { vm.pause(); handleAdd(AddAction.TEXT) },
                        onElement = { vm.pause(); addTab = AddTab.OBJECT },
                        onAdd = { vm.pause(); addTab = AddTab.SHAPE },
                    )
                }
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = EDITOR_BOTTOM_HEIGHT + 8.dp))

        if (showSettings) {
            ModalBottomSheet(onDismissRequest = { showSettings = false; propsFocus = null }, containerColor = KColors.Sheet, shape = KShapes.Sheet) {
                Column(Modifier.fillMaxWidth().height(sheetHeight)) {
                    PropertiesPanel(
                        vm, project, time, null,
                        onEditCurve = { curveTarget = it },
                        onPickTexture = { id -> textureTarget = id; pickTexture.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                        onPickFont = { pickFont.launch(arrayOf("font/*", "application/x-font-ttf", "application/x-font-otf", "application/octet-stream", "*/*")) },
                        modifier = Modifier.weight(1f),
                        onAddEffect = {},
                        focusSection = null,
                    )
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
    addTab?.let { tab ->
        AddMenu(
            tab,
            AddMenuActions(
                onAction = { addTab = null; handleAdd(it) },
                onShape = { preset ->
                    addTab = null
                    val p = vm.current
                    val size = minOf(p.composition.width, p.composition.height) * 0.35f
                    vm.addLayer(preset.apply(Factory.shape(p, vm.time.value, preset.kind, context.getString(preset.label)), size))
                },
                onElements = { addTab = null; showElements = true },
                onCaptions = { addTab = null; showCaptions = true },
                onPlugins = { addTab = null; showPlugins = true },
                onFreehand = { addTab = null; vm.select(null); fullscreen = false; drawMode = DrawMode.FREEHAND },
                onVector = { addTab = null; vm.select(null); fullscreen = false; drawMode = DrawMode.VECTOR },
            ),
            onDismiss = { addTab = null },
        )
    }
    val effectsTarget = selection?.let { LayerTree.find(project, it) }
    if (showEffects && effectsTarget != null) {
        AddEffectSheet(
            effectsTarget,
            onDismiss = { showEffects = false },
            onAdd = { type ->
                vm.updateLayer(effectsTarget.id) { it.copy(effects = it.effects + Effect.create(type)) }
                showEffects = false
                propsFocus = "effects"
                propsDocked = true
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
                propsDocked = true
            },
        )
    }
    if (showAudio) {
        val target = selection?.let { LayerTree.find(project, it) }
        if (target?.media != null) AudioSheet(vm, project, target, onDismiss = { showAudio = false }) else showAudio = false
    }
    stabilizeTarget?.let { id ->
        StabilizeDialog(onStart = { settings -> stabilizeTarget = null; vm.stabilize(id, settings) }, onDismiss = { stabilizeTarget = null })
    }
    rotoRequest?.let { CutoutBrushScreen(vm, it, onClose = { rotoRequest = null }) }
    if (showRealCamera) RealCameraSheet(vm, project, onDismiss = { showRealCamera = false })
    mediaJob?.let { MediaJobDialog(it, onCancel = { vm.cancelMediaJob() }) }
    if (showCaptions) CaptionsSheet(vm, project, onDismiss = { showCaptions = false })
    if (showPlugins) PluginsSheet(vm, project, selection, onDismiss = { showPlugins = false })
    if (showElements) ElementsSheet(vm, project, onDismiss = { showElements = false })
    editingMarker?.let { id ->
        val marker = project.markers.firstOrNull { it.id == id }
        if (marker == null) editingMarker = null else MarkerDialog(vm, marker, project.composition.fps, time, onDismiss = { editingMarker = null })
    }
    if (showExport) ExportDialog(vm, project, exportState, onDismiss = { showExport = false })
}

/** The curve editor (or the graph editor) of the keyframe picked in the properties, under them. */
@Composable
private fun CurveEditors(vm: EditorViewModel, project: dev.kinesis.core.model.KnsProject, target: CurveTarget?, showGraph: Boolean, onShowGraph: (Boolean) -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    target ?: return
    val layer = LayerTree.find(project, target.layerId)
    val prop = layer?.let { LayerProps.find(it, target.propId) }
    val animatable = layer?.let { l -> prop?.get(l) }
    val keyframe = animatable?.keyframes?.getOrNull(target.keyIndex)
    if (layer == null || prop == null || animatable == null || keyframe == null) {
        LaunchedEffect(target) { onShowGraph(false); onClose() }
    } else if (showGraph) {
        GraphEditorPanel(vm, layer, prop, stringResource(propLabelRes(prop.labelKey)), onClose = { onShowGraph(false) })
    } else {
        CurveEditorPanel(
            easing = keyframe.easing,
            loop = animatable.loop,
            onEasing = { vm.setEasing(layer.id, prop, target.keyIndex, it) },
            onLoop = { vm.setLoop(layer.id, prop, it) },
            onClose = onClose,
            saved = AppSettings.savedEasings,
            onSave = { AppSettings.saveEasing(context, it) },
            onDeleteSaved = { AppSettings.deleteEasing(context, it) },
            clipboard = vm.easingClipboard,
            onCopy = { vm.copyEasing(it) },
            onApplyAll = { vm.setEasingAll(layer.id, prop, it) },
            onGraph = { onShowGraph(true) },
        )
    }
}

/**
 * A drawn path becomes a shape layer: the points are kept relative to their centre, which becomes the layer's
 * position. A closed path is filled; an open one (a stroke by hand, an open vector path) is a line.
 */
private fun addDrawing(vm: EditorViewModel, context: android.content.Context, points: List<Vec2>, closed: Boolean) {
    if (points.size < 2) return
    val p = vm.current
    val cx = points.sumOf { it.x.toDouble() }.toFloat() / points.size
    val cy = points.sumOf { it.y.toDouble() }.toFloat() / points.size
    val base = Factory.shape(p, vm.time.value, ShapeKind.PATH, context.getString(R.string.layer_drawing))
    vm.addLayer(
        base.updateShape { c ->
            c.copy(
                path = PathSpec(closed = closed, points = points.map { PathPoint(Vec2(it.x - cx, it.y - cy)) }),
                fill = if (closed) c.fill else FillSpec(enabled = false, color = c.fill.color),
                stroke = if (closed) c.stroke else StrokeSpec(color = c.fill.color, width = anim(10f)),
            )
        }.let { it.copy(transform = it.transform.copy(position = anim(Vec2(cx, cy)))) },
    )
}
