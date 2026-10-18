package dev.kinesis.editor.ui.editor

import android.app.Application
import dev.kinesis.core.anim.at
import dev.kinesis.core.edit.LayerProps
import dev.kinesis.core.edit.RealCamera
import dev.kinesis.core.edit.RealCamera.Axes
import dev.kinesis.core.math.Vec3
import dev.kinesis.core.model.CameraAim
import dev.kinesis.core.model.Layer
import dev.kinesis.core.model.LayerTree
import dev.kinesis.core.model.LayerType
import dev.kinesis.editor.R
import dev.kinesis.editor.sensors.RealCameraSensor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** What the person chose for the take (kept while the app is open). */
data class RealCameraSettings(
    /** Multiplies the angle the phone turns. */
    val intensity: Float = 1f,
    val axes: Axes = Axes(),
    /** 0 = as recorded, 1 = very smooth. */
    val smoothing: Float = 0.25f,
    val simplify: Boolean = true,
    val toleranceDegrees: Float = 0.4f,
    /** Also move the camera with the phone (rough: acceleration only). */
    val recordPosition: Boolean = false,
    /** Calibrate when the countdown ends instead of when "Record" is tapped. */
    val calibrateAtStart: Boolean = false,
) {
    val processing get() = RealCamera.Processing(smoothing, simplify, toleranceDegrees)
}

sealed class RealCameraState {
    data object Idle : RealCameraState()
    data class Countdown(val count: Int) : RealCameraState()
    data class Recording(val seconds: Double) : RealCameraState()

    /** The take is recorded and already applied to the camera; settings can still change the result. */
    data class Review(val samples: Int, val keyframes: Int) : RealCameraState()
    data class Failed(val message: String) : RealCameraState()
}

/**
 * Real Camera: records the phone's orientation while the timeline plays and turns it into keyframes of a 3D camera.
 * The live pose only drives the preview ([pose]); the project changes once, when the take stops, so recording does not
 * flood the history or the disk. The take is one undo step.
 */
class RealCameraController(private val vm: EditorViewModel, private val app: Application, private val scope: CoroutineScope) {
    private val _state = MutableStateFlow<RealCameraState>(RealCameraState.Idle)
    val state: StateFlow<RealCameraState> = _state.asStateFlow()
    private val _settings = MutableStateFlow(RealCameraSettings())
    val settings: StateFlow<RealCameraSettings> = _settings.asStateFlow()
    private val _pose = MutableStateFlow<RealCamera.LivePose?>(null)

    /** The camera pose while recording, for the preview. */
    val pose: StateFlow<RealCamera.LivePose?> = _pose.asStateFlow()

    /** Whether this phone has a sensor that can drive the camera; the option is hidden otherwise. */
    val available: Boolean by lazy { RealCameraSensor.isAvailable(app) }
    val hasAcceleration: Boolean get() = sensor.hasAcceleration
    private val sensor by lazy { RealCameraSensor(app) }

    private var job: Job? = null
    private val samples = ArrayList<RealCamera.Sample>()
    private var baseline: Layer? = null
    private var layerId: String? = null
    private var layerStart = 0.0
    private var localStart = 0.0
    private var basePosition: Vec3? = null
    private var applied = false

    fun update(settings: RealCameraSettings) {
        _settings.value = settings
        if (_state.value is RealCameraState.Review) reprocess()
    }

    /** The camera a take goes into: the selected camera layer, else the active camera, else a new one. */
    fun targetId(): String? {
        val selected = vm.selection.value?.let { LayerTree.find(vm.current, it) }
        if (selected?.type == LayerType.CAMERA) return selected.id
        return vm.activeCameraId()
    }

    fun start() {
        val s = _state.value
        if (s is RealCameraState.Countdown || s is RealCameraState.Recording) return
        if (s is RealCameraState.Review) accept()
        if (!available) { _state.value = RealCameraState.Failed(app.getString(R.string.real_camera_unavailable)); return }
        val id = targetId() ?: vm.ensureCameraLayer()
        val cfg = _settings.value
        if (!sensor.start(cfg.recordPosition && sensor.hasAcceleration)) {
            _state.value = RealCameraState.Failed(app.getString(R.string.real_camera_unavailable))
            return
        }
        job = scope.launch {
            try {
                record(id, cfg)
            } finally {
                sensor.stop()
                _pose.value = null
            }
        }
    }

    private suspend fun record(id: String, cfg: RealCameraSettings) {
        // Wait for the first reading of the sensor.
        var waited = 0
        while (sensor.matrix == null && waited < 100) { delay(10); waited++ }
        var r0 = sensor.matrix?.copyOf()
        if (r0 == null) { _state.value = RealCameraState.Failed(app.getString(R.string.real_camera_unavailable)); return }
        for (n in 3 downTo 1) {
            _state.value = RealCameraState.Countdown(n)
            delay(1000)
        }
        if (cfg.calibrateAtStart) r0 = sensor.matrix?.copyOf() ?: r0

        val project = vm.current
        val comp = project.composition
        if (vm.time.value >= comp.duration - comp.frameDuration) vm.seek(0.0)
        val layer = LayerTree.find(project, id) ?: return
        val t0 = vm.time.value
        layerStart = LayerTree.containerOffset(project, id) + layer.start
        localStart = t0 - layerStart
        // The camera as it is when the take starts, with its own rotation (a camera aimed at a target is converted).
        val converted = LayerProps.withCameraAim(LayerProps.withExplicitCameraPosition(layer, comp.height), CameraAim.ROTATION, localStart, comp.height)
        val cam = converted.camera ?: return
        val baseRotation = cam.rotation.at(localStart)
        basePosition = cam.position?.at(localStart)
        baseline = layer
        layerId = id
        samples.clear()
        applied = false
        val tracker = RealCamera.PositionTracker()
        var lastNs = System.nanoTime()
        var lastFrame = Int.MIN_VALUE

        vm.play()
        while (scope.isActive) {
            val playing = vm.playing.value
            val t = vm.time.value
            val frame = (t * comp.fps).roundToInt()
            if (frame != lastFrame) {
                lastFrame = frame
                val m = sensor.matrix ?: r0
                val rotation = RealCamera.cameraRotation(baseRotation, r0, m, cfg.intensity, cfg.axes)
                var offset: Vec3? = null
                if (cfg.recordPosition && sensor.hasAcceleration) {
                    val now = System.nanoTime()
                    val dt = ((now - lastNs) / 1e9).coerceIn(0.001, 0.2)
                    lastNs = now
                    val a = sensor.acceleration ?: doubleArrayOf(0.0, 0.0, 0.0)
                    offset = tracker.update(r0, m, a[0], a[1], a[2], dt, cfg.intensity)
                }
                val time = frame / comp.fps
                samples += RealCamera.Sample(time, rotation, offset)
                _pose.value = RealCamera.LivePose(id, rotation, offset?.let { (basePosition ?: Vec3.ZERO) + it })
                _state.value = RealCameraState.Recording(time - t0)
            }
            if (!playing) break
            delay(3)
        }
        vm.pause()
        if (samples.size < 2) {
            _state.value = RealCameraState.Idle
            vm.notifyUser(app.getString(R.string.real_camera_too_short), true)
            return
        }
        reprocess()
    }

    /** Stops the take (the timeline pauses; recording ends and the result is applied). */
    fun stop() {
        if (_state.value is RealCameraState.Recording) vm.pause()
    }

    fun cancelCountdown() {
        if (_state.value is RealCameraState.Countdown) {
            job?.cancel()
            _state.value = RealCameraState.Idle
        }
    }

    /** Builds the keyframes from the raw samples with the current settings and puts them in the camera. */
    private fun reprocess() {
        val id = layerId ?: return
        val base = baseline ?: return
        val cfg = _settings.value
        val comp = vm.current.composition
        val keys = RealCamera.keyframes(
            samples, comp.fps, cfg.processing, timeOffset = layerStart,
            basePosition = if (samples.all { it.position != null }) basePosition else null,
        )
        vm.edit("realcamera:$id") { p -> LayerTree.update(p, id) { RealCamera.applyTo(base, keys, comp.height, localStart) } }
        applied = true
        _state.value = RealCameraState.Review(samples.size, keys.rotation.size)
    }

    /** Keeps the take. */
    fun accept() {
        if (applied) vm.endGesture()
        clear()
    }

    /** Throws the take away: the camera goes back to how it was. */
    fun discard() {
        if (applied) {
            vm.endGesture()
            vm.undo()
        }
        clear()
    }

    fun dismissError() {
        if (_state.value is RealCameraState.Failed) _state.value = RealCameraState.Idle
    }

    private fun clear() {
        samples.clear()
        baseline = null
        layerId = null
        applied = false
        _state.value = RealCameraState.Idle
    }
}
