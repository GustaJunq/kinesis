package dev.kinesis.editor.render

import android.content.res.AssetManager
import android.graphics.Bitmap
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.util.Log
import dev.kinesis.core.model.KnsProject
import dev.kinesis.core.scene.SceneEvaluator
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
    private val onError: (String) -> Unit,
) : GLSurfaceView.Renderer {

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

    fun requestThumbnail(maxSize: Int, callback: (Bitmap) -> Unit) {
        thumbnailRequest = maxSize to callback
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        // A new EGL context: everything GPU-side must be recreated.
        resources = null
        compositor = null
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
            val res = resources ?: RenderResources(assets, files).also { resources = it }
            val comp = compositor ?: Compositor(res).also { compositor = it }
            if (s.mediaRevision != mediaRevision) {
                if (mediaRevision >= 0) {
                    res.images.release()
                    res.videos.release()
                    res.meshes.release()
                }
                mediaRevision = s.mediaRevision
            }
            val p = s.project
            val vp = ViewportRect.fit(viewW, viewH, p.composition.width, p.composition.height, padding)
            // Render at the on-screen size (never above the composition size); lighter while playing.
            val quality = if (s.playing) 0.75f else 1f
            val outW = min(p.composition.width.toFloat(), vp.width * quality).roundToInt().coerceAtLeast(16)
            val outH = (outW * p.composition.height.toFloat() / p.composition.width).roundToInt().coerceAtLeast(16)
            val scene = SceneEvaluator.evaluate(p, s.time)
            val samples = if (s.playing) min(3, p.composition.motionBlur.samples) else p.composition.motionBlur.samples
            val target = comp.render(p, scene, outW, outH, RenderSettings(motionBlurSamples = samples))
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
            comp.present(target, vp.left.roundToInt(), (viewH - vp.top - vp.height).roundToInt(), vp.width.roundToInt(), vp.height.roundToInt())

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

    /** Releases GPU resources; call on the GL thread (queueEvent) before the view goes away. */
    fun release() {
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
        const val BG_R = 0.055f
        const val BG_G = 0.059f
        const val BG_B = 0.075f
    }
}
