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

    var tab by rememberSaveable { mutableIntStateOf(0) }
    var showAdd by remember { mutableStateOf(false) }
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
            Button(onClick = onClose) { Text(stringResource(R.string.close)) }
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
        showAdd = false
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
        val panelHeight = maxHeight * 0.42f
        Column(Modifier.fillMaxSize()) {
            // Top bar
            Row(Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { vm.flushSave(); onClose() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) }
                Text(project.meta.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                IconButton(onClick = { vm.undo() }, enabled = canUndo) { Icon(Icons.AutoMirrored.Filled.Undo, stringResource(R.string.undo)) }
                IconButton(onClick = { vm.redo() }, enabled = canRedo) { Icon(Icons.AutoMirrored.Filled.Redo, stringResource(R.string.redo)) }
                Button(onClick = { vm.pause(); showExport = true }, modifier = Modifier.padding(end = 4.dp)) {
                    Icon(Icons.Filled.FileUpload, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.export))
                }
            }
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
                        stringResource(R.string.render_error, msg), color = Color.White, fontSize = 12.sp,
                        modifier = Modifier.align(Alignment.TopCenter).padding(8.dp).background(KColors.Danger.copy(alpha = 0.85f)).padding(8.dp)
                            .clickable { renderError = null },
                    )
                }
            }

            // Transport
            Row(Modifier.fillMaxWidth().height(48.dp).background(KColors.Surface).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { vm.seek(0.0) }) { Icon(Icons.Filled.SkipPrevious, stringResource(R.string.go_to_start)) }
                IconButton(onClick = {
                    val l = selectedLayer ?: return@IconButton
                    val offset = LayerTree.containerOffset(project, l.id) + l.start
                    l.keyframeTimes().map { it + offset }.lastOrNull { it < time - 1e-4 }?.let { vm.seek(it) }
                }, enabled = selectedLayer != null) { Icon(Icons.Filled.FastRewind, stringResource(R.string.prev_keyframe)) }
                IconButton(onClick = { vm.step(-1) }) { Text("−1", fontSize = 12.sp) }
                IconButton(onClick = { vm.togglePlay() }) {
                    Icon(if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, stringResource(R.string.play), tint = KColors.Accent, modifier = Modifier.size(30.dp))
                }
                IconButton(onClick = { vm.step(1) }) { Text("+1", fontSize = 12.sp) }
                IconButton(onClick = {
                    val l = selectedLayer ?: return@IconButton
                    val offset = LayerTree.containerOffset(project, l.id) + l.start
                    l.keyframeTimes().map { it + offset }.firstOrNull { it > time + 1e-4 }?.let { vm.seek(it) }
                }, enabled = selectedLayer != null) { Icon(Icons.Filled.FastForward, stringResource(R.string.next_keyframe)) }
                Text(
                    "${formatTime(time, project.composition.fps)} / ${formatTime(project.composition.duration, project.composition.fps)}",
                    fontSize = 12.sp, color = KColors.TextSecondary, modifier = Modifier.weight(1f).padding(start = 4.dp), maxLines = 1,
                )
                if (selectedLayer?.type?.isRendered3D == true) {
                    IconButton(onClick = { tool3D = if (tool3D == Tool3D.MOVE) Tool3D.ROTATE else Tool3D.MOVE }) {
                        Icon(
                            if (tool3D == Tool3D.MOVE) Icons.Filled.OpenWith else Icons.Filled.ScreenRotation,
                            stringResource(if (tool3D == Tool3D.MOVE) R.string.tool_move else R.string.tool_rotate),
                            tint = KColors.Teal,
                        )
                    }
                }
                IconButton(onClick = { vm.loopPlayback.value = !loop }) {
                    Icon(Icons.Filled.Repeat, stringResource(R.string.loop), tint = if (loop) KColors.Accent else KColors.TextSecondary)
                }
            }

            // Panels
            TabRow(
                selectedTabIndex = tab,
                containerColor = KColors.Surface,
                indicator = { positions -> TabRowDefaults.SecondaryIndicator(Modifier.tabIndicatorOffset(positions[tab]), color = KColors.Accent) },
            ) {
                listOf(R.string.tab_timeline, R.string.tab_properties, R.string.tab_layers).forEachIndexed { i, res ->
                    Tab(selected = tab == i, onClick = { tab = i }, text = { Text(stringResource(res), fontSize = 13.sp) })
                }
            }
            Box(Modifier.fillMaxWidth().height(panelHeight)) {
                when (tab) {
                    0 -> Timeline(vm, project, time, playing, selection, multi, Modifier.fillMaxSize())
                    1 -> PropertiesPanel(
                        vm, project, time, selection,
                        onEditCurve = { curveTarget = it },
                        onPickTexture = { id -> textureTarget = id; pickTexture.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                        onPickFont = { pickFont.launch(arrayOf("font/*", "application/x-font-ttf", "application/x-font-otf", "application/octet-stream", "*/*")) },
                        modifier = Modifier.fillMaxSize(),
                    )
                    else -> LayersPanel(vm, project, selection, multi, Modifier.fillMaxSize())
                }
            }

            // Actions
            Row(
                Modifier.fillMaxWidth().height(56.dp).background(KColors.Surface).padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                ActionButton(Icons.Filled.Add, stringResource(R.string.add), true, highlight = true) { vm.pause(); showAdd = true }
                ActionButton(Icons.Filled.ContentCut, stringResource(R.string.split), selectedLayer != null) { vm.splitSelected() }
                ActionButton(Icons.Filled.ContentCopy, stringResource(R.string.duplicate), selectedLayer != null) { vm.duplicateSelected() }
                ActionButton(Icons.Filled.CreateNewFolder, stringResource(R.string.group), selectedLayer != null) { vm.groupSelection() }
                ActionButton(Icons.Filled.Delete, stringResource(R.string.delete), selectedLayer != null) { vm.deleteSelection() }
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = 64.dp))
    }

    if (showAdd) AddSheet(onDismiss = { showAdd = false }, onAdd = ::handleAdd)
    if (showExport) ExportDialog(vm, project, exportState, onDismiss = { showExport = false })
    curveTarget?.let { target ->
        val layer = LayerTree.find(project, target.layerId)
        val prop = layer?.let { LayerProps.find(it, target.propId) }
        val keyframe = prop?.get(layer)?.keyframes?.getOrNull(target.keyIndex)
        if (layer == null || prop == null || keyframe == null) {
            curveTarget = null
        } else {
            CurveEditorDialog(keyframe.easing, onDismiss = { curveTarget = null }) { easing ->
                vm.setEasing(layer.id, prop, target.keyIndex, easing)
                curveTarget = null
            }
        }
    }
}

@Composable
private fun ActionButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, enabled: Boolean, highlight: Boolean = false, onClick: () -> Unit) {
    Column(
        Modifier.width(64.dp).clickable(enabled = enabled, onClick = onClick).padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val tint = when {
            !enabled -> KColors.TextSecondary.copy(alpha = 0.4f)
            highlight -> KColors.Accent
            else -> KColors.TextPrimary
        }
        Icon(icon, null, tint = tint, modifier = Modifier.size(22.dp))
        Text(label, fontSize = 10.sp, color = tint, maxLines = 1)
    }
}
