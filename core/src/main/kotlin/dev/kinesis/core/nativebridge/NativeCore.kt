package dev.kinesis.core.nativebridge

import dev.kinesis.core.geom.Mesh
import dev.kinesis.core.math.Vec2

/**
 * JNI bridge to `libkinesis_native` (the Rust crate in `native/`). The library is optional: when it is not on the
 * library path (unit tests on a plain JVM, a build without it) [available] is false and callers keep using the
 * Kotlin implementations, so nothing depends on it being present.
 */
object NativeCore {
    /** Test hook: `-Dkinesis.native=off` keeps the Kotlin path even when the library is present. */
    val available: Boolean by lazy {
        if (System.getProperty("kinesis.native") == "off") return@lazy false
        try {
            System.loadLibrary("kinesis_native")
            true
        } catch (e: UnsatisfiedLinkError) {
            false
        } catch (e: SecurityException) {
            false
        }
    }

    @JvmStatic
    private external fun extrudeText(points: FloatArray, counts: IntArray, depth: Float, bevel: Float, bevelSteps: Int): Array<Any?>?

    /**
     * Rust version of `TextExtrusion.extrude`: one flat array of points and one of contour lengths go in (no per-point
     * objects cross the boundary), four primitive arrays come out. Null when the library is missing or rejects the input.
     */
    fun extrudeText(contours: List<List<Vec2>>, depth: Float, bevel: Float, bevelSteps: Int): Mesh? {
        if (!available) return null
        val total = contours.sumOf { it.size }
        val points = FloatArray(total * 2)
        val counts = IntArray(contours.size)
        var at = 0
        for ((i, c) in contours.withIndex()) {
            counts[i] = c.size
            for (p in c) { points[at++] = p.x; points[at++] = p.y }
        }
        val r = try {
            extrudeText(points, counts, depth, bevel, bevelSteps)
        } catch (e: UnsatisfiedLinkError) {
            return null
        } ?: return null
        @Suppress("UNCHECKED_CAST")
        return Mesh(r[0] as FloatArray, r[1] as FloatArray, r[2] as FloatArray, r[3] as IntArray)
    }

    // ------------------------------------------------------------------ speech recognition (whisper.cpp)

    @JvmStatic private external fun whisperSupported(): Boolean
    @JvmStatic private external fun whisperLoad(path: String): Long
    @JvmStatic private external fun whisperError(): String
    @JvmStatic private external fun whisperTranscribe(handle: Long, pcm: FloatArray, language: String, translate: Boolean, threads: Int, maxChars: Int): Array<Any?>?
    @JvmStatic private external fun whisperCancel(handle: Long)
    @JvmStatic private external fun whisperProgress(handle: Long): Int
    @JvmStatic private external fun whisperFree(handle: Long)

    /** True when the library is loaded and was built with the speech engine. */
    val speechSupported: Boolean by lazy { available && try { whisperSupported() } catch (e: UnsatisfiedLinkError) { false } }

    /** A loaded Whisper model. Not thread-safe except for [cancel] and [progress], which may be called while [transcribe] runs. */
    class Whisper private constructor(private var handle: Long) : AutoCloseable {
        /** Hundredths of a second → seconds; [start]/[end] are seconds of the audio that was passed in. */
        class Segment(val start: Double, val end: Double, val text: String)

        /** [language]: ISO code ("pt", "en"...) or "auto". [maxChars] splits long sentences into caption-sized pieces. Null on failure or cancellation. */
        fun transcribe(pcm: FloatArray, language: String, translate: Boolean, threads: Int, maxChars: Int): List<Segment>? {
            check(handle != 0L) { "closed" }
            val r = whisperTranscribe(handle, pcm, language, translate, threads, maxChars) ?: run { lastError = whisperError(); return null }
            val t0 = r[0] as LongArray; val t1 = r[1] as LongArray
            @Suppress("UNCHECKED_CAST") val text = r[2] as Array<String>
            return List(text.size) { Segment(t0[it] / 100.0, t1[it] / 100.0, text[it]) }
        }

        fun cancel() { if (handle != 0L) whisperCancel(handle) }

        /** 0..100 */
        fun progress(): Int = if (handle != 0L) whisperProgress(handle) else 0

        override fun close() {
            val h = handle
            handle = 0L
            if (h != 0L) whisperFree(h)
        }

        /** The message of the last failure of this process' Whisper calls. */
        var lastError: String = ""
            private set

        companion object {
            /** Loads the ggml model at [path]; null (with [error]) when the library or the file is not usable. */
            fun load(path: String): Whisper? {
                if (!speechSupported) { error = "speech recognition is not part of this build"; return null }
                val h = whisperLoad(path)
                if (h == 0L) { error = whisperError(); return null }
                return Whisper(h)
            }

            @Volatile var error: String = ""
                private set
        }
    }

    // ------------------------------------------------------------------ video stabilization (vid.stab, GPL-2.0-or-later)

    @JvmStatic private external fun stabSupported(): Boolean
    @JvmStatic private external fun stabNew(width: Int, height: Int, shakiness: Int, accuracy: Int): Long
    @JvmStatic private external fun stabDetect(handle: Long, y: java.nio.ByteBuffer, ys: Int, u: java.nio.ByteBuffer, us: Int, v: java.nio.ByteBuffer, vs: Int): Int
    @JvmStatic private external fun stabPrepare(handle: Long, smoothing: Int, zoom: Double, optzoom: Int, cropBlack: Boolean): Int
    @JvmStatic private external fun stabApply(
        handle: Long,
        sy: java.nio.ByteBuffer, sys: Int, su: java.nio.ByteBuffer, sus: Int, sv: java.nio.ByteBuffer, svs: Int,
        dy: java.nio.ByteBuffer, dys: Int, du: java.nio.ByteBuffer, dus: Int, dv: java.nio.ByteBuffer, dvs: Int,
    ): Int
    @JvmStatic private external fun stabFree(handle: Long)

    /** True when the library is loaded and was built with vid.stab. */
    val stabilizerSupported: Boolean by lazy { available && try { stabSupported() } catch (e: UnsatisfiedLinkError) { false } }

    /** One YUV 4:2:0 plane in a direct buffer, [stride] bytes per row. */
    class YuvPlane(val buffer: java.nio.ByteBuffer, val stride: Int)

    /** A frame in three planes. The buffers must be direct (the native code reads them in place). */
    class YuvFrame(val y: YuvPlane, val u: YuvPlane, val v: YuvPlane)

    /**
     * Two-pass video stabilization with vid.stab: call [detect] for every frame in order, then [prepare], then
     * [apply] for every frame in the same order. Frames are YUV 4:2:0, even width and height. Not thread-safe.
     */
    class Stabilizer private constructor(private var handle: Long) : AutoCloseable {
        /** 0 on success. */
        fun detect(f: YuvFrame): Int = stabDetect(handle, f.y.buffer, f.y.stride, f.u.buffer, f.u.stride, f.v.buffer, f.v.stride)

        /**
         * [smoothing]: frames each side used for the camera path; [zoom]: percent added; [autoZoom]: zoom in just enough
         * to hide the borders; [cropBlack]: borders black (true) or kept from earlier frames (false).
         */
        fun prepare(smoothing: Int, zoom: Double, autoZoom: Boolean, cropBlack: Boolean): Int =
            stabPrepare(handle, smoothing, zoom, if (autoZoom) 1 else 0, cropBlack)

        fun apply(src: YuvFrame, dst: YuvFrame): Int = stabApply(
            handle, src.y.buffer, src.y.stride, src.u.buffer, src.u.stride, src.v.buffer, src.v.stride,
            dst.y.buffer, dst.y.stride, dst.u.buffer, dst.u.stride, dst.v.buffer, dst.v.stride,
        )

        override fun close() {
            val h = handle
            handle = 0L
            if (h != 0L) stabFree(h)
        }

        companion object {
            /** Null when the library has no vid.stab or the size is unusable (odd, smaller than 16). */
            fun create(width: Int, height: Int, shakiness: Int = 5, accuracy: Int = 9): Stabilizer? {
                if (!stabilizerSupported) return null
                val h = stabNew(width, height, shakiness, accuracy)
                return if (h == 0L) null else Stabilizer(h)
            }
        }
    }

    /** The message of the last failed native call (speech or stabilization). */
    fun lastNativeError(): String = if (available) try { whisperError() } catch (e: UnsatisfiedLinkError) { "" } else ""
}
