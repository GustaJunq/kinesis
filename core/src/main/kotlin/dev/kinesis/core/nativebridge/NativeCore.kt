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
}
