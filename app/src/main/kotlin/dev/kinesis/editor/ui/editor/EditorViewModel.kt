package dev.kinesis.editor.ui.editor

import dev.kinesis.editor.ui.settings.AppSettings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.kinesis.editor.R
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
import dev.kinesis.core.edit.KeyframeClip
import dev.kinesis.core.edit.KeyframeClipboard
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
import dev.kinesis.core.edit.PreRender
import dev.kinesis.core.edit.MediaSwap
import dev.kinesis.core.model.LayerType
import dev.kinesis.editor.media.BackgroundRemoval
import dev.kinesis.editor.media.VideoStabilizer
import dev.kinesis.editor.media.ExportResolution
import dev.kinesis.editor.media.ExportQuality
import dev.kinesis.editor.media.ExportFailure
import dev.kinesis.editor.media.ExportSettings
import dev.kinesis.editor.media.PcmCache
import dev.kinesis.editor.media.PreviewAudio
import dev.kinesis.editor.media.VideoExporter
import dev.kinesis.editor.media.VideoGallery
import dev.kinesis.editor.media.VideoProxies
import dev.kinesis.editor.render.AssetFiles
import dev.kinesis.editor.speech.SpeechModels
import dev.kinesis.editor.speech.Transcriber
import dev.kinesis.editor.speech.WhisperModel
import dev.kinesis.core.edit.Captions
import dev.kinesis.core.edit.Markers
import dev.kinesis.core.model.updateMedia
import dev.kinesis.editor.media.AudioExtractor
import dev.kinesis.core.edit.CaptionSegment
import dev.kinesis.core.edit.CaptionStyle
import dev.kinesis.core.nativebridge.NativeCore
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
    /** [etaSeconds]: estimated time left, once known. [preRender]: rendering one layer instead of the video. */
    data class Running(val progress: Float, val etaSeconds: Double? = null, val preRender: Boolean = false) : ExportUiState()
    data class Done(val file: File, val galleryUri: Uri?) : ExportUiState()
    /** Where it stopped ([time], [layerId]) when known; the finished blocks are kept for the next try. */
    data class Failed(
        val message: String,
        val time: Double? = null,
        val layerId: String? = null,
        val layerName: String? = null,
        val outOfMemory: Boolean = false,
    ) : ExportUiState()
}

/** What the captions panel is doing. [progress] is 0..1, or null while unknown. */
sealed class CaptionUiState {
    data object Idle : CaptionUiState()
    data class Downloading(val model: WhisperModel, val progress: Float?) : CaptionUiState()
    data class Transcribing(val progress: Float?) : CaptionUiState()
    data class Failed(val message: String) : CaptionUiState()
}

/** A long clip operation (background removal, stabilization). [progress] is 0..1. */
data class MediaJobUi(val kind: Kind, val progress: Float, val cancelling: Boolean = false) {
    enum class Kind { BACKGROUND, STABILIZE }
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
    /** Light copies of heavy videos for the preview. */
    val proxies: VideoProxies
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
        proxies = VideoProxies(File(app.cacheDir, "proxies/$projectId"), app.assets, files, pcm)
        mixer = AudioMixer(pcm).also { it.setProject(loaded) }
        previewAudio = PreviewAudio(mixer)
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { pcm.prepare(loaded) }
            _audioRevision.value++
        }
        if (error == null) {
            store.beginSession(projectId)
            // A version of the project as it was opened, to go back to.
            viewModelScope.launch(Dispatchers.IO) { runCatching { store.snapshot(projectId, loaded) } }
            buildProxies()
        }
    }

    // ------------------------------------------------------------------ preview proxies

    /** Makes missing proxies of heavy videos in the background (one at a time); the preview switches when ready. */
    fun buildProxies() {
        if (!AppSettings.useProxies || loadError != null) return
        val snapshot = _project.value
        viewModelScope.launch(proxyDispatcher) {
            proxies.build(snapshot) { withMain { _mediaRevision.value++ } }
        }
    }

    private fun withMain(block: () -> Unit) {
        viewModelScope.launch(Dispatchers.Main) { block() }
    }

    // ------------------------------------------------------------------ versions and safety copies

    private var lastSnapshot = System.currentTimeMillis()
    private var lastMirror = 0L
    @Volatile
    private var mirrorPending = false

    /** Saved versions of this project (newest first). */
    fun snapshots(): List<ProjectStore.Snapshot> = store.snapshots(projectId)

    /** Replaces the document by a saved version (the current state is saved as a version first, and can be undone). */
    fun restoreSnapshot(snapshot: ProjectStore.Snapshot) {
        val current = _project.value
        viewModelScope.launch(Dispatchers.IO) {
            try {
                store.snapshot(projectId, current)
                val restored = store.loadSnapshot(snapshot)
                withContext(Dispatchers.Main) {
                    edit { restored }
                    notify(text(R.string.version_restored))
                }
            } catch (e: Exception) {
                notify(e.message ?: "Cannot restore", true)
            }
        }
    }

    /** After a save: a version every few minutes and the copy in Documents (throttled, in the background). */
    private fun afterSave(project: KnsProject) {
        val now = System.currentTimeMillis()
        if (now - lastSnapshot > SNAPSHOT_INTERVAL_MS) {
            lastSnapshot = now
            runCatching { store.snapshot(projectId, project) }
        }
        mirrorPending = true
        if (now - lastMirror > MIRROR_INTERVAL_MS) mirrorNow()
    }

    private fun mirrorNow() {
        if (!AppSettings.mirrorToDocuments || !mirrorPending || loadError != null) return
        mirrorPending = false
        lastMirror = System.currentTimeMillis()
        val id = projectId
        val st = store
        backgroundScope.launch {
            try {
                st.mirrorToDocuments(id)
            } catch (e: Exception) {
                Log.w("Kinesis", "copy to Documents failed", e)
            }
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
                val saved = _project.value
                store.save(projectId, saved)
                afterSave(saved)
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

    /** The app went to the background (it may be killed there): save now and refresh the copy in Documents. */
    fun onBackground() {
        saveJob?.cancel()
        saveJob = null
        viewModelScope.launch(Dispatchers.IO) {
            saveNow()
            lastMirror = 0L
            mirrorNow()
        }
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

    /** Half a frame: the playhead counts as "on" a keyframe within this distance (so a keyframe can be removed without landing on it). */
    private fun keyTolerance(p: KnsProject) = 0.5 / p.composition.fps

    /** Whether the selected layer has a keyframe, on any of its properties, at (or within half a frame of) the playhead. */
    fun hasKeyframeAtPlayhead(p: KnsProject = current, time: Double = _time.value): Boolean {
        val layer = _selection.value?.let { LayerTree.find(p, it) } ?: return false
        val t = time - LayerTree.containerOffset(p, layer.id) - layer.start
        return dev.kinesis.core.edit.KeyframeNav.hasKeyframeNear(layer, t, keyTolerance(p))
    }

    /**
     * The "+ ◆" button: removes the keyframes of the stop at the playhead (every property that has one there) or, when
     * there is none, adds keyframes at the playhead. One undo step either way.
     */
    fun toggleKeyframeAtPlayhead() {
        val layer = selectedLayer() ?: return
        val tolerance = keyTolerance(current)
        updateLayer(layer.id) { l -> dev.kinesis.core.edit.KeyframeNav.toggle(l, localTime(l), tolerance) }
    }

    // ------------------------------------------------------------------ markers

    fun addMarker(time: Double) = edit { Markers.add(it, time).first }
    fun addMarkerAtPlayhead() = addMarker(_time.value)
    fun renameMarker(id: String, name: String) = edit { Markers.rename(it, id, name) }
    fun moveMarker(id: String, time: Double) = edit { Markers.move(it, id, time) }
    fun recolorMarker(id: String, color: String?) = edit { Markers.recolor(it, id, color) }
    fun removeMarker(id: String) = edit { Markers.remove(it, id) }

    // ------------------------------------------------------------------ audio

    /** Edits the audio/video clip content of [layerId] (volume, fades, equalizer); gestures coalesce with [coalesceKey]. */
    fun updateMedia(layerId: String, coalesceKey: String? = null, transform: (dev.kinesis.core.model.MediaContent) -> dev.kinesis.core.model.MediaContent) =
        updateLayer(layerId, coalesceKey) { l -> l.updateMedia(transform) }

    /** Plays only [layerId] in the preview (the audio panel auditions one clip); null plays everything again. */
    fun setAudioSolo(layerId: String?) {
        mixer.solo = layerId
    }

    /** Starts the preview at the clip (or where the playhead is, when it is inside the clip). */
    fun previewClip(layerId: String) {
        val p = current
        val l = LayerTree.find(p, layerId) ?: return
        val start = LayerTree.containerOffset(p, layerId) + l.start
        val end = start + l.duration
        if (_playing.value) { pause(); return }
        if (_time.value < start || _time.value >= end - p.composition.frameDuration) _time.value = start
        play()
    }

    /** Video layers that have a sound track to take out. */
    fun canExtractAudio(layer: Layer?): Boolean =
        layer?.type == dev.kinesis.core.model.LayerType.VIDEO && current.asset(layer.video?.asset)?.hasAudio != false

    /**
     * Takes the sound track of a video layer out into an independent audio layer (above it, same place and trim). The
     * track is copied without re-encoding into an `.m4a` of the project; the video file is not touched, and the
     * video layer is muted so the sound is not heard twice. One undo step.
     */
    fun extractAudio(layerId: String) {
        val layer = LayerTree.find(current, layerId) ?: return
        val media = layer.video ?: return
        val asset = current.asset(media.asset) ?: return
        if (asset.hasAudio == false) { notify(text(R.string.audio_none_in_video), true); return }
        val source = files.file(asset.id)?.takeIf { it.isFile } ?: return
        val app = getApplication<Application>()
        _busy.value = true
        viewModelScope.launch {
            val tmp = File(app.cacheDir, "extract/${projectId}_${System.nanoTime()}.m4a").also { it.parentFile?.mkdirs() }
            try {
                when (val r = withContext(Dispatchers.IO) { AudioExtractor.extract(source, tmp) }) {
                    is AudioExtractor.Result.NoAudio -> notify(text(R.string.audio_none_in_video), true)
                    is AudioExtractor.Result.Unsupported -> notify(text(R.string.audio_unsupported_format, r.mime), true)
                    is AudioExtractor.Result.Done -> {
                        val newAsset = withContext(Dispatchers.IO) { store.importMedia(projectId, current, Uri.fromFile(tmp), AssetType.AUDIO) }
                        val name = text(R.string.audio_layer_name, layerDisplayName(layer))
                        var newId: String? = null
                        edit { p ->
                            val here = LayerTree.find(p, layerId) ?: return@edit p
                            val loc = LayerTree.locate(p, layerId) ?: return@edit p
                            val base = dev.kinesis.core.edit.Factory.audio(p, here.start, newAsset, name)
                            val audioLayer = base.copy(
                                duration = here.duration,
                                audio = dev.kinesis.core.model.MediaContent(
                                    newAsset.id, sourceStart = media.sourceStart, speed = media.speed, volume = media.volume,
                                    fadeIn = media.fadeIn, fadeOut = media.fadeOut, eq = media.eq,
                                ),
                            )
                            newId = audioLayer.id
                            val withAsset = if (p.assets.any { it.id == newAsset.id }) p else p.copy(assets = p.assets + newAsset)
                            val muted = LayerTree.update(withAsset, layerId) { v -> v.updateMedia { it.copy(muted = true) } }
                            LayerTree.insert(muted, loc.containerId, loc.index + 1, audioLayer)
                        }
                        newId?.let { select(it) }
                        _mediaRevision.value++
                        withContext(Dispatchers.IO) { runCatching { pcm.ensure(newAsset.id) } }
                        _audioRevision.value++
                        notify(text(R.string.audio_extracted))
                    }
                }
            } catch (e: Exception) {
                Log.w("Kinesis", "audio extraction failed", e)
                notify(text(R.string.audio_extract_failed), true)
            } finally {
                tmp.delete()
                _busy.value = false
            }
        }
    }

    fun clearKeyframes(layerId: String, prop: AnimProp<*>) =
        updateLayer(layerId) { l -> prop.clearKeyframes(l, localTime(l)) }

    /** Sets (or removes, with a null [source]) the expression of a property; the code is kept when [enabled] is false. */
    fun setExpression(layerId: String, prop: AnimProp<*>, source: String?, enabled: Boolean = true, coalesceKey: String? = null) =
        updateLayer(layerId, coalesceKey) { l -> withExpression(l, prop, source, enabled) }

    private fun <T> withExpression(l: Layer, prop: AnimProp<T>, source: String?, enabled: Boolean): Layer =
        prop.set(l, prop.get(l).copy(expression = source, expressionEnabled = enabled))

    /** Runs [source] as if it were the expression of [prop] at the playhead; null when it is valid. */
    fun diagnoseExpression(layerId: String, prop: AnimProp<*>, source: String): dev.kinesis.core.expr.ExpressionError? {
        val p = current
        val layer = LayerTree.find(p, layerId) ?: return null
        val siblings = LayerTree.siblings(p, layerId).associateBy { it.id }
        val containerTime = _time.value - LayerTree.containerOffset(p, layerId)
        return dev.kinesis.core.expr.ExpressionResolver.diagnose(layer, prop, source, siblings, containerTime, p.composition)
    }

    /** Same for the selector expression of a text property animator. */
    fun diagnoseTextExpression(layerId: String, animatorIndex: Int, source: String): dev.kinesis.core.expr.ExpressionError? {
        val p = current
        val layer = LayerTree.find(p, layerId) ?: return null
        val siblings = LayerTree.siblings(p, layerId).associateBy { it.id }
        val containerTime = _time.value - LayerTree.containerOffset(p, layerId)
        return dev.kinesis.core.expr.TextExpression.diagnose(layer, animatorIndex, source, siblings, containerTime, p.composition)
    }

    fun setLoop(layerId: String, prop: AnimProp<*>, mode: dev.kinesis.core.anim.LoopMode) =
        updateLayer(layerId) { l -> withLoop(l, prop, mode) }

    private fun <T> withLoop(l: Layer, prop: AnimProp<T>, mode: dev.kinesis.core.anim.LoopMode): Layer =
        prop.set(l, prop.get(l).copy(loop = mode))

    fun setEasing(layerId: String, prop: AnimProp<*>, index: Int, easing: Easing) =
        updateLayer(layerId) { l -> prop.setEasing(l, index, easing) }

    /** Applies [easing] to every keyframe segment of [prop]. */
    fun setEasingAll(layerId: String, prop: AnimProp<*>, easing: Easing) =
        updateLayer(layerId) { l -> prop.get(l).keyframes.indices.fold(l) { acc, i -> prop.setEasing(acc, i, easing) } }

    // ------------------------------------------------------------------ clipboards (kept while the editor is open)

    /** A copied curve, pasted onto other keyframes, properties or layers. */
    var easingClipboard by mutableStateOf<Easing?>(null)
        private set

    fun copyEasing(easing: Easing) {
        easingClipboard = easing
        notify(text(R.string.curve_copied))
    }

    /** Copied keyframes (see [KeyframeClipboard]). */
    var keyframeClip by mutableStateOf<KeyframeClip?>(null)
        private set

    /** Copies the keyframes of every animated property of [layerId], or only of [propIds]. */
    fun copyKeyframes(layerId: String, propIds: Set<String>? = null) {
        val layer = LayerTree.find(current, layerId) ?: return
        val clip = KeyframeClipboard.copy(layer, propIds)
        if (clip == null) {
            notify(text(R.string.keyframes_nothing_to_copy))
        } else {
            keyframeClip = clip
            notify(text(R.string.keyframes_copied, clip.entries.size))
        }
    }

    /** Pastes the copied keyframes into [layerId] starting at the playhead. */
    fun pasteKeyframes(layerId: String) {
        val clip = keyframeClip ?: return
        var count = 0
        updateLayer(layerId) { l ->
            val (pasted, n) = KeyframeClipboard.paste(l, clip, localTime(l))
            count = n
            pasted
        }
        notify(if (count == 0) text(R.string.keyframes_paste_none) else text(R.string.keyframes_pasted, count))
    }

    private fun text(id: Int, vararg args: Any): String = getApplication<Application>().getString(id, *args)

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
                if (type == AssetType.VIDEO) buildProxies()
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
                val eta = Eta()
                withContext(exportDispatcher) {
                    VideoExporter(app.assets, files, pcm).export(
                        snapshot, settings, out, { p -> _export.value = ExportUiState.Running(p, eta.update(p)) }, { exportCancelled },
                        workDir = File(app.cacheDir, "export/$projectId"),
                    )
                }
                val uri = withContext(Dispatchers.IO) { VideoGallery.save(app, out, out.name) }
                _export.value = ExportUiState.Done(out, uri)
            } catch (e: ExportCancelled) {
                _export.value = ExportUiState.Idle
            } catch (e: Throwable) {
                Log.e("Kinesis", "export failed", e)
                _export.value = failure(e)
            }
        }
    }

    private fun failure(e: Throwable): ExportUiState.Failed {
        if (e !is ExportFailure) return ExportUiState.Failed(e.message ?: e.javaClass.simpleName)
        val layer = e.layerId?.let { LayerTree.find(current, it) }
        return ExportUiState.Failed(e.message ?: "", e.time, layer?.id, layer?.name, e.outOfMemory)
    }

    /** Whether [layerId] can be replaced by a pre-rendered video. */
    fun canPreRender(layerId: String): Boolean = PreRender.canPreRender(current, layerId)

    // ------------------------------------------------------------------ background removal and stabilization

    private val _mediaJob = MutableStateFlow<MediaJobUi?>(null)
    val mediaJob: StateFlow<MediaJobUi?> = _mediaJob.asStateFlow()
    @Volatile private var mediaJobCancelled = false
    private var mediaJobTask: Job? = null

    fun canRemoveBackground(layer: Layer?): Boolean = layer != null && MediaSwap.canSwap(layer)
    fun canStabilize(layer: Layer?): Boolean = layer?.type == LayerType.VIDEO && layer.video != null && VideoStabilizer.supported

    fun cancelMediaJob() {
        if (_mediaJob.value != null) {
            mediaJobCancelled = true
            _mediaJob.value = _mediaJob.value?.copy(cancelling = true)
        }
    }

    /**
     * Cuts the person out of an image or video layer on the device. A copy with transparency is placed above the
     * layer and the original is hidden (one undo step brings it back).
     */
    fun removeBackground(layerId: String) {
        if (_mediaJob.value != null) return
        val layer = LayerTree.find(current, layerId) ?: return
        val isVideo = layer.type == LayerType.VIDEO
        val media = layer.video
        val assetId = layer.image?.asset ?: media?.asset ?: return
        val asset = current.asset(assetId) ?: return
        val source = files.file(asset.id)?.takeIf { it.isFile } ?: return
        val app = getApplication<Application>()
        pause()
        mediaJobCancelled = false
        _mediaJob.value = MediaJobUi(MediaJobUi.Kind.BACKGROUND, 0f)
        mediaJobTask = viewModelScope.launch {
            val tmp = File(app.cacheDir, "cutout/${projectId}_${System.nanoTime()}.${if (isVideo) "mp4" else "png"}").also { it.parentFile?.mkdirs() }
            try {
                val result = withContext(Dispatchers.IO) {
                    if (isVideo && media != null) {
                        val startUs = (media.sourceStart * 1_000_000).toLong().coerceAtLeast(0L)
                        val endUs = startUs + (layer.duration * media.speed * 1_000_000).toLong().coerceAtLeast(100_000L)
                        BackgroundRemoval.removeFromVideo(app, source, tmp, startUs, endUs, { p -> _mediaJob.value = MediaJobUi(MediaJobUi.Kind.BACKGROUND, p, mediaJobCancelled) }, { mediaJobCancelled })
                    } else {
                        BackgroundRemoval.removeFromImage(app, source, tmp)
                    }
                }
                if (result == null) return@launch
                val newAsset = withContext(Dispatchers.IO) { store.importMedia(projectId, current, Uri.fromFile(tmp), if (isVideo) AssetType.VIDEO else AssetType.IMAGE) }
                val name = text(R.string.cutout_layer_name, layerDisplayName(layer))
                var newId: String? = null
                edit { p ->
                    LayerTree.find(p, layerId) ?: return@edit p
                    MediaSwap.replace(
                        p, layerId, newAsset, name, sourceStart = if (isVideo) 0.0 else null, scale = result.scale,
                        alphaPacked = if (isVideo) true else null, muted = if (isVideo) !result.hasAudio || media?.muted == true else null,
                    ).also { newId = it.second }.first
                }
                newId?.let { select(it) }
                _mediaRevision.value++
                notify(text(R.string.cutout_done))
            } catch (e: Throwable) {
                Log.w("Kinesis", "background removal failed", e)
                notify(text(R.string.cutout_failed, e.message ?: e.javaClass.simpleName), true)
            } finally {
                tmp.delete()
                _mediaJob.value = null
            }
        }
    }

    /** Stabilizes a video layer with vid.stab. A stabilized copy is placed above the layer; the original is hidden. */
    fun stabilize(layerId: String, settings: VideoStabilizer.Settings) {
        if (_mediaJob.value != null) return
        val layer = LayerTree.find(current, layerId) ?: return
        val media = layer.video ?: return
        val asset = current.asset(media.asset) ?: return
        val source = files.file(asset.id)?.takeIf { it.isFile } ?: return
        val app = getApplication<Application>()
        pause()
        mediaJobCancelled = false
        _mediaJob.value = MediaJobUi(MediaJobUi.Kind.STABILIZE, 0f)
        mediaJobTask = viewModelScope.launch {
            val tmp = File(app.cacheDir, "stabilize/${projectId}_${System.nanoTime()}.mp4").also { it.parentFile?.mkdirs() }
            try {
                val finished = withContext(Dispatchers.IO) {
                    VideoStabilizer.stabilize(source, tmp, settings, { p -> _mediaJob.value = MediaJobUi(MediaJobUi.Kind.STABILIZE, p, mediaJobCancelled) }, { mediaJobCancelled })
                }
                if (!finished) return@launch
                val newAsset = withContext(Dispatchers.IO) { store.importMedia(projectId, current, Uri.fromFile(tmp), AssetType.VIDEO) }
                val name = text(R.string.stabilized_layer_name, layerDisplayName(layer))
                var newId: String? = null
                edit { p ->
                    LayerTree.find(p, layerId) ?: return@edit p
                    MediaSwap.replace(p, layerId, newAsset, name).also { newId = it.second }.first
                }
                newId?.let { select(it) }
                _mediaRevision.value++
                notify(text(R.string.stabilize_done))
            } catch (e: Throwable) {
                Log.w("Kinesis", "stabilization failed", e)
                notify(text(R.string.stabilize_failed, e.message ?: e.javaClass.simpleName), true)
            } finally {
                tmp.delete()
                _mediaJob.value = null
            }
        }
    }

    /**
     * Renders [layerId] alone into a video with transparency and shows that video instead (the layer stays,
     * hidden). Lightens heavy layers (many effects, 3D) for export. Undoable.
     */
    fun preRenderLayer(layerId: String) {
        if (_export.value is ExportUiState.Running) return
        val snapshot = current
        val range = PreRender.range(snapshot, layerId) ?: return
        val layer = LayerTree.find(snapshot, layerId) ?: return
        pause()
        exportCancelled = false
        val app = getApplication<Application>()
        _export.value = ExportUiState.Running(0f, preRender = true)
        exportJob = viewModelScope.launch {
            val tmp = File(app.cacheDir, "prerender/${layer.id}.mp4").apply { parentFile?.mkdirs() }
            try {
                val eta = Eta()
                withContext(exportDispatcher) {
                    VideoExporter(app.assets, files, pcm).export(
                        PreRender.isolate(snapshot, layerId), ExportSettings(ExportResolution.ORIGINAL, ExportQuality.HIGH, includeAudio = false), tmp,
                        { p -> _export.value = ExportUiState.Running(p, eta.update(p), preRender = true) }, { exportCancelled },
                        workDir = File(app.cacheDir, "export/$projectId-layer"), range = range, packAlpha = true,
                    )
                }
                val asset = withContext(Dispatchers.IO) { store.importMedia(projectId, current, Uri.fromFile(tmp), AssetType.VIDEO) }
                val name = text(R.string.prerender_layer_name, layer.name.ifBlank { layer.type.name.lowercase() })
                var newId: String? = null
                edit { p -> PreRender.replace(p, layerId, asset, range, name).also { newId = it.second }.first }
                newId?.let { select(it) }
                _mediaRevision.value++
                _export.value = ExportUiState.Idle
                notify(text(R.string.prerender_done))
            } catch (e: ExportCancelled) {
                _export.value = ExportUiState.Idle
            } catch (e: Throwable) {
                Log.e("Kinesis", "pre-render failed", e)
                _export.value = failure(e)
            } finally {
                tmp.delete()
            }
        }
    }

    fun cancelExport() {
        exportCancelled = true
    }

    fun dismissExport() {
        if (_export.value !is ExportUiState.Running) _export.value = ExportUiState.Idle
    }

    // ------------------------------------------------------------------ captions (Whisper, offline)

    val speechModels = SpeechModels(app)
    private val _captionState = MutableStateFlow<CaptionUiState>(CaptionUiState.Idle)
    val captionState: StateFlow<CaptionUiState> = _captionState.asStateFlow()
    private var captionJob: Job? = null
    @Volatile
    private var captionCancelled = false
    @Volatile
    private var whisper: NativeCore.Whisper? = null

    /** Whether this build can recognise speech at all (the native library with Whisper is part of it). */
    val speechSupported: Boolean get() = Transcriber.supported

    /** Downloads a model once (needs a connection); afterwards recognition works offline. */
    fun downloadModel(model: WhisperModel) {
        if (captionJob?.isActive == true) return
        captionCancelled = false
        _captionState.value = CaptionUiState.Downloading(model, 0f)
        captionJob = viewModelScope.launch(speechDispatcher) {
            try {
                speechModels.download(model, { p -> _captionState.value = CaptionUiState.Downloading(model, p) }, { captionCancelled })
                _captionState.value = CaptionUiState.Idle
            } catch (e: Exception) {
                _captionState.value = if (captionCancelled) CaptionUiState.Idle else CaptionUiState.Failed(text(R.string.captions_download_failed))
            }
        }
    }

    fun importModel(uri: Uri) {
        viewModelScope.launch(speechDispatcher) {
            val ok = speechModels.import(uri)
            _captionState.value = if (ok) CaptionUiState.Idle else CaptionUiState.Failed(text(R.string.captions_bad_model))
        }
    }

    /** The layers whose speech can be recognised: video and audio layers that make sound. */
    fun speechSources(p: KnsProject = current): List<Layer> {
        val ids = dev.kinesis.core.scene.AudioPlan.clips(p).map { it.layerId }.toSet()
        return LayerTree.all(p).filter { it.id in ids }
    }

    /** Recognises the speech of [layerId] and adds the captions as a group of text layers above everything. */
    fun transcribe(layerId: String, language: String) {
        if (captionJob?.isActive == true) return
        val model = speechModels.best() ?: return
        val clip = dev.kinesis.core.scene.AudioPlan.clips(current).firstOrNull { it.layerId == layerId } ?: return
        captionCancelled = false
        _captionState.value = CaptionUiState.Transcribing(null)
        captionJob = viewModelScope.launch(speechDispatcher) {
            try {
                val pcmData = pcm.ensure(clip.assetId)
                if (pcmData == null) {
                    _captionState.value = CaptionUiState.Failed(text(R.string.captions_no_audio))
                    return@launch
                }
                val segments = Transcriber.transcribe(
                    model, pcmData, clip, language, maxChars = 42,
                    onProgress = { p -> if (!captionCancelled) _captionState.value = CaptionUiState.Transcribing(p / 100f) },
                    handle = { w -> whisper = w },
                )
                whisper = null
                when {
                    captionCancelled -> _captionState.value = CaptionUiState.Idle
                    segments == null -> _captionState.value = CaptionUiState.Failed(NativeCore.Whisper.error.ifBlank { text(R.string.captions_failed) })
                    segments.isEmpty() -> _captionState.value = CaptionUiState.Failed(text(R.string.captions_none_found))
                    else -> withContext(Dispatchers.Main) {
                        edit { Captions.add(it, segments, CaptionStyle(), text(R.string.captions)).first }
                        _captionState.value = CaptionUiState.Idle
                    }
                }
            } catch (e: Throwable) {
                whisper = null
                Log.w("Kinesis", "transcription failed", e)
                _captionState.value = if (captionCancelled) CaptionUiState.Idle else CaptionUiState.Failed(
                    if (e is OutOfMemoryError) text(R.string.captions_memory) else text(R.string.captions_failed),
                )
            }
        }
    }

    fun cancelCaptionJob() {
        captionCancelled = true
        whisper?.cancel()
    }

    fun dismissCaptionState() {
        if (_captionState.value is CaptionUiState.Failed) _captionState.value = CaptionUiState.Idle
    }

    /** Replaces the captions of [groupId] (text and interval edits, split, merge, delete). */
    fun setCaptions(groupId: String, segments: List<CaptionSegment>, coalesceKey: String? = null) =
        edit(coalesceKey) { Captions.replaceSegments(it, groupId, segments) }

    fun setCaptionStyle(groupId: String, style: CaptionStyle, coalesceKey: String? = null) =
        edit(coalesceKey) { Captions.applyStyle(it, groupId, style) }

    fun importSrt(groupId: String?, srt: String) {
        val segments = Captions.parseSrt(srt)
        if (segments.isEmpty()) { notify(text(R.string.captions_srt_empty), true); return }
        edit { p -> if (groupId != null && LayerTree.find(p, groupId) != null) Captions.replaceSegments(p, groupId, segments) else Captions.add(p, segments, CaptionStyle(), text(R.string.captions)).first }
    }

    override fun onCleared() {
        captionCancelled = true
        whisper?.cancel()
        previewAudio.stop()
        playJob?.cancel()
        exportCancelled = true
        proxies.cancelled = true
        if (saveJob?.isActive == true) {
            saveJob?.cancel()
            runBlocking(Dispatchers.IO) { saveNow() }
        }
        if (loadError == null) {
            store.endSession(projectId)
            lastMirror = 0L
            mirrorNow()
        }
        exportDispatcher.close()
        super.onCleared()
    }
}

private const val MIN_DURATION = 0.5
private const val SNAPSHOT_INTERVAL_MS = 3 * 60_000L
private const val MIRROR_INTERVAL_MS = 2 * 60_000L

/** Time left from the progress so far (ignores progress that was already there, e.g. resumed blocks). */
private class Eta {
    private var startNs = 0L
    private var startProgress = -1f

    fun update(progress: Float): Double? {
        val now = System.nanoTime()
        if (startProgress < 0f) {
            startNs = now
            startProgress = progress
            return null
        }
        val done = progress - startProgress
        val elapsed = (now - startNs) / 1e9
        if (done < 0.02f || elapsed < 2.0) return null
        return elapsed / done * (1f - progress)
    }
}

/** Proxies are made one at a time, across editors. */
private val speechDispatcher = Executors.newSingleThreadExecutor { r -> Thread(r, "kinesis-speech").apply { isDaemon = true } }.asCoroutineDispatcher()
private val proxyDispatcher = Executors.newSingleThreadExecutor { r -> Thread(r, "kinesis-proxies").apply { isDaemon = true } }.asCoroutineDispatcher()

/** Work that must finish after the editor closes (the copy in Documents). */
private val backgroundScope = kotlinx.coroutines.CoroutineScope(Dispatchers.IO + kotlinx.coroutines.SupervisorJob())
