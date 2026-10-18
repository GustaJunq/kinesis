package dev.kinesis.editor.render

import android.content.res.AssetManager
import android.graphics.Bitmap
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.util.Log
import dev.kinesis.core.model.KnsProject
import dev.kinesis.core.edit.RealCamera
import dev.kinesis.core.scene.CameraState
import dev.kinesis.core.scene.SceneEvaluator
import dev.kinesis.core.scene.TimeSlice
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.min
import kotlin.math.roundToInt

/** What the preview should show. Replaced atomically from the UI thread. */
data class PreviewState(
    val project: KnsProject,
    val time: Double,
    val playing: Boolean,
    /** Changes when the project's media files change, to flush GPU caches. */
    val mediaRevision: Int,
    /** 3D quality of the preview: 0 low, 1 medium, 2 high (shadow map size, depth-of-field taps). */
    val quality3D: Int = 1,
    /** Preview resolution relative to the on-screen size: 1, 0.5 or 0.25 (export is never affected). */
    val scale: Float = 1f,
    /** The editor view's camera: when set, 3D layers are drawn from it instead of the scene's camera. */
    val editorCamera: CameraState? = null,
    /** Real Camera: the pose being recorded, drawn instead of the camera's own until the take is applied. */
    val livePose: RealCamera.LivePose? = null,
)

/** Letterboxed area of the composition inside the view, in view pixels (origin top-left). */
data class ViewportRect(val left: Float, val top: Float, val width: Float, val height: Float) {
    companion object {
        fun fit(viewW: Int, viewH: Int, compW: Int, compH: Int, padding: Float): ViewportRect {
            val aw = (viewW - 2 * padding).coerceAtLeast(1f)
            val ah = (viewH - 2 * padding).coerceAtLeast(1f)
            val s = min(aw / compW, ah / compH)
            val w = compW * s
            val h = compH * s
            return ViewportRect((viewW - w) / 2f, (viewH - h) / 2f, w, h)
        }
    }
}

/**
 * GLSurfaceView renderer for the editor viewport. Renders the composition at (at most) the on-screen size
 * and letterboxes it on the editor background.
 */
class PreviewRenderer(
    private val assets: AssetManager,
    private val files: AssetFiles,
    private val padding: Float,
    private val proxies: (String) -> VideoProxy? = { null },
    /** Low-memory phones keep fewer TimeSlice frames and strips. */
    private val lowMemory: Boolean = false,
    private val onError: (String) -> Unit,
) : GLSurfaceView.Renderer {

    /** Asks for another pass while TimeSlice still has frames to fetch (set from the UI thread). */
    @Volatile
    var onRefine: () -> Unit = {}

    @Volatile
    var state: PreviewState? = null

    /** Called on the GL thread with a rendered thumbnail when [requestThumbnail] was used. */
    @Volatile
    private var thumbnailRequest: Pair<Int, (Bitmap) -> Unit>? = null

    private var resources: RenderResources? = null
    private var compositor: Compositor? = null
    private var viewW = 1
    private var viewH = 1
    private var mediaRevision = -1
    private var failed = false

    // Rendered frames of the current project version (cleared by any edit): replaying or scrubbing back over
    // frames already seen costs a copy instead of a render.
    private data class FrameKey(val frame: Long, val width: Int, val height: Int, val playing: Boolean, val quality3D: Int, val editorView: Int)
    private val frameCache = LinkedHashMap<FrameKey, RenderTarget>(64, 0.75f, true)
    private var cacheProject: KnsProject? = null
    private var cacheBytes = 0L

    fun requestThumbnail(maxSize: Int, callback: (Bitmap) -> Unit) {
        thumbnailRequest = maxSize to callback
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        // A new EGL context: everything GPU-side must be recreated.
        resources = null
        compositor = null
        frameCache.clear()
        cacheBytes = 0
        failed = false
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        viewW = width
        viewH = height
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glViewport(0, 0, viewW, viewH)
        GLES30.glClearColor(BG_R, BG_G, BG_B, 1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)
        val s = state ?: return
        if (failed) return
        try {
            val res = resources ?: RenderResources(assets, files, proxies).also { resources = it }
            val comp = compositor ?: Compositor(res).also { compositor = it }
            if (s.mediaRevision != mediaRevision) {
                if (mediaRevision >= 0) {
                    res.images.release()
                    res.videos.release()
                    res.meshes.release()
                }
                mediaRevision = s.mediaRevision
                clearCache()
            }
            if (cacheProject !== s.project) {
                clearCache()
                cacheProject = s.project
            }
            val p = s.project
            val vp = ViewportRect.fit(viewW, viewH, p.composition.width, p.composition.height, padding)
            // Render at the on-screen size (never above the composition size); lighter while playing.
            val quality = (if (s.playing) 0.75f else 1f) * s.scale.coerceIn(0.1f, 1f)
            val outW = min(p.composition.width.toFloat(), vp.width * quality).roundToInt().coerceAtLeast(16)
            val outH = (outW * p.composition.height.toFloat() / p.composition.width).roundToInt().coerceAtLeast(16)
            val key = FrameKey(Math.round(s.time * p.composition.fps), outW, outH, s.playing, s.quality3D, (s.editorCamera?.view?.m?.contentHashCode() ?: 0) * 31 + (s.livePose?.rotation?.hashCode() ?: 0))
            val cached = frameCache[key]
            val shown = if (cached != null) cached else {
                val scene = SceneEvaluator.evaluate(s.livePose?.let { RealCamera.withLivePose(p, it) } ?: p, s.time).let { sc -> s.editorCamera?.let(sc::withCamera) ?: sc }
                val samples = if (s.playing) min(3, p.composition.motionBlur.samples) else p.composition.motionBlur.samples
                val (shadowSize, dofSamples) = RenderSettings.quality3D(s.quality3D)
                val cacheBytes = if (lowMemory) SLICE_CACHE_LOW else SLICE_CACHE
                val budget = TimeSlice.previewBudget(lowMemory, s.playing, outW.toLong() * outH * 4, cacheBytes)
                val settings = RenderSettings(
                    motionBlurSamples = samples, preview = s.playing, shadowMapSize = shadowSize, dofSamples = dofSamples,
                    timeSliceBudget = budget, timeSliceCacheBytes = cacheBytes,
                )
                val target = comp.render(p, scene, outW, outH, settings)
                if (s.livePose != null) {
                    target       // a take in progress: these frames are not kept
                } else if (res.sliceDegraded) {
                    // TimeSlice used stand-ins for frames it has not fetched yet: show it, do not keep it, and finish when idle.
                    if (!s.playing && res.sliceProgress) onRefine()
                    target
                } else {
                    remember(key, target)
                }
            }
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
            comp.present(shown, vp.left.roundToInt(), (viewH - vp.top - vp.height).roundToInt(), vp.width.roundToInt(), vp.height.roundToInt())

            thumbnailRequest?.let { (maxSize, callback) ->
                thumbnailRequest = null
                val s2 = min(1f, maxSize.toFloat() / maxOf(p.composition.width, p.composition.height))
                val tw = (p.composition.width * s2).roundToInt().coerceAtLeast(16)
                val th = (p.composition.height * s2).roundToInt().coerceAtLeast(16)
                val thumbScene = SceneEvaluator.evaluate(p, min(1.0, p.composition.duration / 2))
                val t = comp.render(p, thumbScene, tw, th, RenderSettings(motionBlurSamples = 1))
                callback(comp.readPixels(t))
                GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
            }
        } catch (e: Throwable) {
            Log.e("Kinesis", "preview rendering failed", e)
            failed = true
            onError(e.message ?: e.javaClass.simpleName)
        }
    }

    /** Keeps a copy of [target] for [key]; returns what to show (the copy, or [target] if it cannot be kept). */
    private fun remember(key: FrameKey, target: RenderTarget): RenderTarget {
        val bytes = target.width.toLong() * target.height * 4
        if (bytes > MAX_CACHE_BYTES / 8) return target
        while (cacheBytes + bytes > MAX_CACHE_BYTES && frameCache.isNotEmpty()) {
            val eldest = frameCache.entries.iterator().next()
            frameCache.remove(eldest.key)
            cacheBytes -= eldest.value.width.toLong() * eldest.value.height * 4
            eldest.value.release()
        }
        val copy = try {
            RenderTarget(target.width, target.height, withDepth = false)
        } catch (e: Throwable) {
            return target
        }
        GLES30.glBindFramebuffer(GLES30.GL_READ_FRAMEBUFFER, target.framebuffer)
        GLES30.glBindFramebuffer(GLES30.GL_DRAW_FRAMEBUFFER, copy.framebuffer)
        GLES30.glBlitFramebuffer(0, 0, target.width, target.height, 0, 0, copy.width, copy.height, GLES30.GL_COLOR_BUFFER_BIT, GLES30.GL_NEAREST)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        frameCache[key] = copy
        cacheBytes += bytes
        return copy
    }

    private fun clearCache() {
        resources?.slices?.clear()
        frameCache.values.forEach { it.release() }
        frameCache.clear()
        cacheBytes = 0
    }

    /** Releases GPU resources; call on the GL thread (queueEvent) before the view goes away. */
    fun release() {
        clearCache()
        cacheProject = null
        compositor?.release()
        resources?.release()
        compositor = null
        resources = null
    }

    /** Clears an error so the next frame is attempted again (e.g. after the user changed something). */
    fun retry() {
        failed = false
    }

    companion object {
        /** GPU memory for cached preview frames. */
        private const val MAX_CACHE_BYTES = 96L * 1024 * 1024
        /** GPU memory for the frames of TimeSlice (less on low-memory phones). */
        private const val SLICE_CACHE = 64L * 1024 * 1024
        private const val SLICE_CACHE_LOW = 24L * 1024 * 1024
        const val BG_R = 0.055f
        const val BG_G = 0.059f
        const val BG_B = 0.075f
    }
}
