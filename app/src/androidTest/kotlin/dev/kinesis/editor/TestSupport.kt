package dev.kinesis.editor

import android.graphics.Bitmap
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.util.Base64
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import dev.kinesis.core.format.KnsprojJson
import dev.kinesis.core.model.KnsProject
import java.io.ByteArrayOutputStream

/** Offscreen EGL context (1×1 pbuffer) for GL tests on the instrumentation thread. */
class PbufferContext : AutoCloseable {
    private val display: EGLDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
    private val context: EGLContext
    private val surface: EGLSurface

    init {
        val v = IntArray(2)
        check(EGL14.eglInitialize(display, v, 0, v, 1)) { "eglInitialize" }
        val attribs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGLExt.EGL_OPENGL_ES3_BIT_KHR, EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT, EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val n = IntArray(1)
        check(EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, n, 0) && n[0] > 0) { "no ES3 pbuffer config" }
        context = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0)
        surface = EGL14.eglCreatePbufferSurface(display, configs[0], intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0)
        check(EGL14.eglMakeCurrent(display, surface, surface, context)) { "eglMakeCurrent" }
    }

    override fun close() {
        EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        EGL14.eglDestroySurface(display, surface)
        EGL14.eglDestroyContext(display, context)
        EGL14.eglReleaseThread()
    }
}

object Shots {
    /**
     * Writes a small JPEG of [bitmap] to logcat as base64 chunks (`KSHOT|name|index|count|data`), so CI logs can be
     * turned back into images for review.
     */
    fun log(name: String, bitmap: Bitmap, maxSize: Int = 360) {
        val scale = minOf(1f, maxSize.toFloat() / maxOf(bitmap.width, bitmap.height))
        val small = Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt().coerceAtLeast(1), (bitmap.height * scale).toInt().coerceAtLeast(1), true)
        val out = ByteArrayOutputStream()
        small.compress(Bitmap.CompressFormat.JPEG, 70, out)
        val b64 = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        val chunks = b64.chunked(3000)
        chunks.forEachIndexed { i, c -> Log.i("KSHOT", "KSHOT|$name|$i|${chunks.size}|$c") }
    }
}

fun loadExample(name: String): KnsProject {
    val ctx = InstrumentationRegistry.getInstrumentation().context
    val text = ctx.assets.open("$name/project.json").bufferedReader().use { it.readText() }
    return KnsprojJson.decode(text)
}

/** Fraction of sampled pixels that differ noticeably from [argb]. */
fun differingFraction(bitmap: Bitmap, argb: Int): Float {
    var diff = 0
    var total = 0
    val r0 = (argb shr 16) and 0xFF; val g0 = (argb shr 8) and 0xFF; val b0 = argb and 0xFF
    for (y in 0 until bitmap.height step 4) for (x in 0 until bitmap.width step 4) {
        val p = bitmap.getPixel(x, y)
        val d = kotlin.math.abs(((p shr 16) and 0xFF) - r0) + kotlin.math.abs(((p shr 8) and 0xFF) - g0) + kotlin.math.abs((p and 0xFF) - b0)
        if (d > 24) diff++
        total++
    }
    return diff.toFloat() / total
}
