package dev.kinesis.editor.ui.editor

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.kinesis.core.anim.Easing
import dev.kinesis.core.anim.KeyframeOps
import dev.kinesis.core.edit.AnimMapper
import dev.kinesis.core.edit.AnimProp
import dev.kinesis.core.edit.History
import dev.kinesis.core.edit.TimelineOps
import dev.kinesis.core.edit.mapAnimatables
import dev.kinesis.core.anim.Animatable
import dev.kinesis.core.model.Asset
import dev.kinesis.core.model.AssetType
import dev.kinesis.core.model.KnsProject
import dev.kinesis.core.model.Layer
import dev.kinesis.core.model.LayerTree
import dev.kinesis.editor.data.ProjectStore
import dev.kinesis.editor.media.AudioMixer
import dev.kinesis.editor.media.ExportCancelled
import dev.kinesis.editor.media.ExportSettings
import dev.kinesis.editor.media.PcmCache
import dev.kinesis.editor.media.PreviewAudio
import dev.kinesis.editor.media.VideoExporter
import dev.kinesis.editor.media.VideoGallery
import dev.kinesis.editor.render.AssetFiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.abs

sealed class ExportUiState {
    data object Idle : ExportUiState()
    data class Running(val progress: Float) : ExportUiState()
    data class Done(val file: File, val galleryUri: Uri?) : ExportUiState()
    data class Failed(val message: String) : ExportUiState()
}

/** Short user-facing notices (shown in a snackbar). */
data class Notice(val text: String, val isError: Boolean = false)

class EditorViewModel(app: Application, val projectId: String) : AndroidViewModel(app) {
    val store = ProjectStore(app)

    private val _project: MutableStateFlow<KnsProject>
    val project: StateFlow<KnsProject>
    val loadError: String?

    private val history: History<KnsProject>
    private val _canUndo = MutableStateFlow(false)
    private val _canRedo = MutableStateFlow(false)
    val canUndo: StateFlow<Boolean> = _canUndo.asStateFlow()
    val canRedo: StateFlow<Boolean> = _canRedo.asStateFlow()

    /** Playhead in composition seconds. */
    private val _time = MutableStateFlow(0.0)
    val time: StateFlow<Double> = _time.asStateFlow()
    private val _playing = MutableStateFlow(false)
    val playing: StateFlow<Boolean> = _playing.asStateFlow()
    val loopPlayback = MutableStateFlow(true)

    /** Primary selected layer and the full multi-selection (used for grouping). */
    private val _selection = MutableStateFlow<String?>(null)
    val selection: StateFlow<String?> = _selection.asStateFlow()
    private val _multiSelection = MutableStateFlow<Set<String>>(emptySet())
    val multiSelection: StateFlow<Set<String>> = _multiSelection.asStateFlow()

    /** Bumped when asset files change so the renderer reloads them. */
    private val _mediaRevision = MutableStateFlow(0)
    val mediaRevision: StateFlow<Int> = _mediaRevision.asStateFlow()

    /** Bumped when decoded audio becomes available (timeline waveforms). */
    private val _audioRevision = MutableStateFlow(0)
    val audioRevision: StateFlow<Int> = _audioRevision.asStateFlow()

    private val _notices = MutableSharedFlow<Notice>(extraBufferCapacity = 8)
    val notices: SharedFlow<Notice> = _notices

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _export = MutableStateFlow<ExportUiState>(ExportUiState.Idle)
    val exportState: StateFlow<ExportUiState> = _export.asStateFlow()

    val files: AssetFiles
    private val pcm: PcmCache
    private val mixer: AudioMixer
    private val previewAudio: PreviewAudio
    private var saveJob: Job? = null
    private val saveLock = Any()

    @Volatile
    private var dirty = false
    private var playJob: Job? = null
    private var exportJob: Job? = null
    @Volatile
    private var exportCancelled = false
    private val exportDispatcher = Executors.newSingleThreadExecutor { r -> Thread(r, "kinesis-export") }.asCoroutineDispatcher()

    init {
        var error: String? = null
        val loaded = try {
            fitDuration(store.load(projectId))
        } catch (e: Exception) {
            Log.e("Kinesis", "cannot load project", e)
            error = e.message ?: e.javaClass.simpleName
            KnsProject()
        }
        loadError = error
        _project = MutableStateFlow(loaded)
        project = _project.asStateFlow()
        history = History(loaded)
        files = store.assetFiles(projectId) { _project.value }
        pcm = PcmCache(File(app.cacheDir, "pcm/$projectId"), files)
        mixer = AudioMixer(pcm).also { it.setProject(loaded) }
        previewAudio = PreviewAudio(mixer)
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { pcm.prepare(loaded) }
            _audioRevision.value++
        }
    }

    val current: KnsProject get() = _project.value

    // ------------------------------------------------------------------ editing core

    /**
     * Applies an edit. Edits sharing a non-null [coalesceKey] (one drag gesture) become a single undo step;
     * call [endGesture] when the gesture finishes.
     */
    fun edit(coalesceKey: String? = null, transform: (KnsProject) -> KnsProject) {
        val next = try {
            transform(_project.value)
        } catch (e: Exception) {
            Log.w("Kinesis", "edit failed", e)
            notify(e.message ?: "Edit failed", true)
            return
        }
        // The video ends when its last layer ends (unless the edit itself set the duration).
        val prev = _project.value
        val fitted = if (next.layers != prev.layers && next.composition.duration == prev.composition.duration) fitDuration(next) else next
        if (fitted == prev) return
        history.push(fitted, coalesceKey)
        apply(fitted)
    }

    /** Sets the composition duration to the end of the last top-level layer (empty projects keep theirs). */
    private fun fitDuration(p: KnsProject): KnsProject {
        if (p.layers.isEmpty()) return p
        val end = p.layers.maxOf { it.end }.coerceAtLeast(MIN_DURATION)
        return if (end == p.composition.duration) p else p.copy(composition = p.composition.copy(duration = end))
    }

    fun endGesture() = history.commit()

    fun undo() {
        history.undo()?.let { apply(it) }
    }

    fun redo() {
        history.redo()?.let { apply(it) }
    }

    private fun apply(p: KnsProject) {
        _project.value = p
        dirty = true
        _canUndo.value = history.canUndo
        _canRedo.value = history.canRedo
        mixer.setProject(p)
        if (_selection.value != null && LayerTree.find(p, _selection.value!!) == null) select(null)
        if (_time.value > p.composition.duration) _time.value = p.composition.duration
        scheduleSave()
    }

    private fun scheduleSave() {
        saveJob?.cancel()
        saveJob = viewModelScope.launch(Dispatchers.IO) {
            delay(700)
            saveNow()
        }
    }

    /** Writes the project if it changed since the last save. Serialized: saves never run concurrently. */
    private fun saveNow() {
        synchronized(saveLock) {
            // Never overwrite a project we could not read.
            if (loadError != null || !dirty) return
            dirty = false
            try {
                store.save(projectId, _project.value)
            } catch (e: Exception) {
                dirty = true
                Log.e("Kinesis", "save failed", e)
                notify("Could not save: ${e.message}", true)
            }
        }
    }

    fun flushSave() {
        saveJob?.cancel()
        saveJob = null
        viewModelScope.launch(Dispatchers.IO) { saveNow() }
    }

    fun saveThumbnail(bitmap: Bitmap) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val out = ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
                store.saveThumbnail(projectId, out.toByteArray())
            } catch (e: Exception) {
                Log.w("Kinesis", "thumbnail failed", e)
            } finally {
                bitmap.recycle()
            }
        }
    }

    private fun notify(text: String, error: Boolean = false) {
        _notices.tryEmit(Notice(text, error))
    }

    /** Decoded 48 kHz stereo PCM of an asset, or null while it is not ready. */
    fun pcmOf(assetId: String): java.nio.ShortBuffer? = pcm.get(assetId)

    // ------------------------------------------------------------------ time & playback

    fun seek(t: Double) {
        val clamped = t.coerceIn(0.0, current.composition.duration)
        _time.value = clamped
        if (_playing.value) {
            previewAudio.start(clamped, current.composition.duration)
            restartClock()
        }
    }

    /** Moves the playhead by whole frames. */
    fun step(frames: Int) {
        val comp = current.composition
        seek(comp.snap(_time.value) + frames * comp.frameDuration)
    }

    fun togglePlay() = if (_playing.value) pause() else play()

    fun play() {
        if (_playing.value) return
        if (_time.value >= current.composition.duration - current.composition.frameDuration) _time.value = 0.0
        _playing.value = true
        previewAudio.start(_time.value, current.composition.duration)
        restartClock()
    }

    private fun restartClock() {
        playJob?.cancel()
        val startNs = System.nanoTime()
        val t0 = _time.value
        playJob = viewModelScope.launch {
            while (isActive && _playing.value) {
                val duration = current.composition.duration
                val t = t0 + (System.nanoTime() - startNs) / 1e9
                if (t >= duration) {
                    if (loopPlayback.value) {
                        _time.value = 0.0
                        previewAudio.start(0.0, duration)
                        restartClock()
                        return@launch
                    }
                    _time.value = duration
                    pause()
                    return@launch
                }
                _time.value = t
                delay(8)
            }
        }
    }

    fun pause() {
        _playing.value = false
        playJob?.cancel()
        previewAudio.stop()
        _time.value = current.composition.snap(_time.value)
    }

    // ------------------------------------------------------------------ selection

    fun select(id: String?, additive: Boolean = false) {
        if (additive && id != null) {
            val set = _multiSelection.value.toMutableSet()
            if (!set.add(id)) set.remove(id)
            _multiSelection.value = set
            _selection.value = id.takeIf { it in set } ?: set.lastOrNull()
        } else {
            _selection.value = id
            _multiSelection.value = setOfNotNull(id)
        }
    }

    fun selectedLayer(p: KnsProject = current): Layer? = _selection.value?.let { LayerTree.find(p, it) }

    /** Layer-local time of [layer] at the playhead. */
    fun localTime(layer: Layer, p: KnsProject = current): Double =
        _time.value - LayerTree.containerOffset(p, layer.id) - layer.start

    // ------------------------------------------------------------------ layer operations

    fun addLayer(layer: Layer) {
        val sel = selectedLayer()
        // New layers go on top of the selected layer's container, or on top of everything.
        val container = sel?.let { LayerTree.locate(current, it.id)?.containerId }
        val adjusted = if (container != null) layer.copy(start = layer.start - LayerTree.containerOffset(current, sel.id)) else layer
        edit { TimelineOps.addLayer(it, adjusted, container) }
        select(layer.id)
    }

    fun updateLayer(id: String, coalesceKey: String? = null, transform: (Layer) -> Layer) =
        edit(coalesceKey) { LayerTree.update(it, id, transform) }

    fun deleteSelection() {
        val ids = _multiSelection.value.ifEmpty { setOfNotNull(_selection.value) }
        if (ids.isEmpty()) return
        edit { TimelineOps.delete(it, ids) }
        select(null)
    }

    fun duplicateSelected() {
        val id = _selection.value ?: return
        var newId: String? = null
        edit { p -> TimelineOps.duplicate(p, id)?.also { newId = it.second }?.first ?: p }
        newId?.let { select(it) }
    }

    fun splitSelected() {
        val id = _selection.value ?: return
        var newId: String? = null
        edit { p -> TimelineOps.split(p, id, _time.value)?.also { newId = it.second }?.first ?: p }
        if (newId == null) notify("Move the playhead inside the clip to split it") else select(newId)
    }

    fun groupSelection() {
        val ids = _multiSelection.value.ifEmpty { setOfNotNull(_selection.value) }
        if (ids.isEmpty()) return
        var gid: String? = null
        edit { p -> TimelineOps.group(p, ids)?.also { gid = it.second }?.first ?: p }
        if (gid == null) notify("Only layers in the same group can be grouped") else select(gid)
    }

    fun ungroup(id: String) {
        edit { TimelineOps.ungroup(it, id) }
        select(null)
    }

    fun moveLayerTime(id: String, newStart: Double, coalesceKey: String) =
        edit(coalesceKey) { TimelineOps.move(it, id, newStart) }

    fun trimLayerStart(id: String, delta: Double, coalesceKey: String) =
        edit(coalesceKey) { TimelineOps.trimStart(it, id, delta) }

    fun trimLayerEnd(id: String, newDuration: Double, coalesceKey: String) =
        edit(coalesceKey) { TimelineOps.trimEnd(it, id, newDuration) }

    fun reorder(id: String, newIndex: Int) = edit { TimelineOps.reorder(it, id, newIndex) }

    /** Moves a layer one step up (towards the top of the stack) or down. */
    fun moveInStack(id: String, up: Boolean) {
        val loc = LayerTree.locate(current, id) ?: return
        reorder(id, loc.index + if (up) 1 else -1)
    }

    fun setParent(id: String, parentId: String?) = edit { TimelineOps.setParent(it, id, parentId, _time.value) }

    // ------------------------------------------------------------------ keyframes

    fun <T> setProperty(layerId: String, prop: AnimProp<T>, value: T, coalesceKey: String? = null) =
        updateLayer(layerId, coalesceKey) { l -> prop.setValue(l, localTime(l), value) }

    fun toggleKeyframe(layerId: String, prop: AnimProp<*>) =
        updateLayer(layerId) { l -> prop.toggleKeyframe(l, localTime(l)) }

    /** Keyframes the transform of the selected layer at the playhead: every animated property, or the whole transform if none is. */
    fun addKeyframeAtPlayhead() {
        val layer = selectedLayer() ?: return
        val transform = dev.kinesis.core.edit.LayerProps.sections(layer).firstOrNull()?.props ?: return
        val targets = transform.filter { it.isAnimated(layer) }.ifEmpty { transform.take(4) }
        updateLayer(layer.id) { l ->
            val t = localTime(l)
            targets.fold(l) { acc, prop -> if (prop.hasKeyframeAt(acc, t)) acc else prop.toggleKeyframe(acc, t) }
        }
    }

    fun clearKeyframes(layerId: String, prop: AnimProp<*>) =
        updateLayer(layerId) { l -> prop.clearKeyframes(l, localTime(l)) }

    fun setLoop(layerId: String, prop: AnimProp<*>, mode: dev.kinesis.core.anim.LoopMode) =
        updateLayer(layerId) { l -> withLoop(l, prop, mode) }

    private fun <T> withLoop(l: Layer, prop: AnimProp<T>, mode: dev.kinesis.core.anim.LoopMode): Layer =
        prop.set(l, prop.get(l).copy(loop = mode))

    fun setEasing(layerId: String, prop: AnimProp<*>, index: Int, easing: Easing) =
        updateLayer(layerId) { l -> prop.setEasing(l, index, easing) }

    /** Moves every keyframe of a layer that sits at local time [from] to [to] (timeline diamonds). */
    fun moveKeyframesAt(layerId: String, from: Double, to: Double, coalesceKey: String) =
        updateLayer(layerId, coalesceKey) { l ->
            l.mapAnimatables(object : AnimMapper {
                override fun <T> map(anim: Animatable<T>, interpolate: (T, T, Float) -> T): Animatable<T> {
                    val idx = anim.keyframes.indexOfFirst { abs(it.time - from) <= Animatable.KEYFRAME_EPSILON }
                    return if (idx >= 0) KeyframeOps.move(anim, idx, to) else anim
                }
            })
        }

    // ------------------------------------------------------------------ media

    /** Copies a picked file into the project and adds the layer built by [makeLayer] (null = asset only). */
    fun importMedia(uri: Uri, type: AssetType, onImported: (Asset) -> Unit = {}, makeLayer: (KnsProject, Asset) -> Layer?) {
        viewModelScope.launch {
            _busy.value = true
            try {
                val asset = withContext(Dispatchers.IO) { store.importMedia(projectId, current, uri, type) }
                var layerId: String? = null
                edit { p ->
                    val withAsset = p.copy(assets = p.assets + asset)
                    val layer = makeLayer(withAsset, asset)
                    layerId = layer?.id
                    if (layer != null) TimelineOps.addLayer(withAsset, layer) else withAsset
                }
                layerId?.let { select(it) }
                _mediaRevision.value++
                onImported(asset)
                if (type == AssetType.VIDEO || type == AssetType.AUDIO) {
                    withContext(Dispatchers.IO) { runCatching { pcm.ensure(asset.id) } }
                    _audioRevision.value++
                }
            } catch (e: Exception) {
                Log.w("Kinesis", "import failed", e)
                notify("Import failed: ${e.message ?: e.javaClass.simpleName}", true)
            } finally {
                _busy.value = false
            }
        }
    }

    fun updateComposition(transform: (dev.kinesis.core.model.Composition) -> dev.kinesis.core.model.Composition) =
        edit { it.copy(composition = transform(it.composition)) }

    fun rename(title: String) = edit { it.copy(meta = it.meta.copy(title = title)) }

    // ------------------------------------------------------------------ export

    fun startExport(settings: ExportSettings) {
        if (_export.value is ExportUiState.Running) return
        pause()
        exportCancelled = false
        val snapshot = current
        val app = getApplication<Application>()
        _export.value = ExportUiState.Running(0f)
        exportJob = viewModelScope.launch {
            try {
                val dir = File(app.getExternalFilesDir(android.os.Environment.DIRECTORY_MOVIES) ?: app.filesDir, "Kinesis").apply { mkdirs() }
                val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
                val safeTitle = snapshot.meta.title.replace(Regex("[^A-Za-z0-9_-]+"), "_").trim('_').ifEmpty { "kinesis" }.take(40)
                val out = File(dir, "$safeTitle-$stamp.mp4")
                withContext(exportDispatcher) {
                    VideoExporter(app.assets, files, pcm).export(snapshot, settings, out, { p -> _export.value = ExportUiState.Running(p) }) { exportCancelled }
                }
                val uri = withContext(Dispatchers.IO) { VideoGallery.save(app, out, out.name) }
                _export.value = ExportUiState.Done(out, uri)
            } catch (e: ExportCancelled) {
                _export.value = ExportUiState.Idle
            } catch (e: Throwable) {
                Log.e("Kinesis", "export failed", e)
                _export.value = ExportUiState.Failed(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    fun cancelExport() {
        exportCancelled = true
    }

    fun dismissExport() {
        if (_export.value !is ExportUiState.Running) _export.value = ExportUiState.Idle
    }

    override fun onCleared() {
        previewAudio.stop()
        playJob?.cancel()
        exportCancelled = true
        if (saveJob?.isActive == true) {
            saveJob?.cancel()
            runBlocking(Dispatchers.IO) { saveNow() }
        }
        exportDispatcher.close()
        super.onCleared()
    }
}

private const val MIN_DURATION = 0.5
