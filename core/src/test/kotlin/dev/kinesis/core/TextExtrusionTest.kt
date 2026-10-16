package dev.kinesis.core

import dev.kinesis.core.geom.Mesh
import dev.kinesis.core.geom.TextExtrusion
import dev.kinesis.core.math.Vec2
import dev.kinesis.core.math.Vec3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** Extruded text geometry: caps with holes, side walls and bevels. */
class TextExtrusionTest {

    private fun rect(x0: Float, y0: Float, x1: Float, y1: Float) = listOf(Vec2(x0, y0), Vec2(x1, y0), Vec2(x1, y1), Vec2(x0, y1))
    private fun circle(r: Float, n: Int = 48, cx: Float = 0f, cy: Float = 0f) =
        List(n) { i -> Vec2(cx + r * cos(2 * PI * i / n).toFloat(), cy + r * sin(2 * PI * i / n).toFloat()) }

    /** Area of the triangles of the front cap (normal -z), projected on xy. */
    private fun capArea(m: Mesh, nz: Float): Float {
        var s = 0f
        for (t in m.indices.indices step 3) {
            val a = m.indices[t]; val b = m.indices[t + 1]; val c = m.indices[t + 2]
            if (m.normals[a * 3 + 2] != nz || m.normals[b * 3 + 2] != nz || m.normals[c * 3 + 2] != nz) continue
            val ax = m.positions[a * 3]; val ay = m.positions[a * 3 + 1]
            val bx = m.positions[b * 3]; val by = m.positions[b * 3 + 1]
            val cx = m.positions[c * 3]; val cy = m.positions[c * 3 + 1]
            s += abs((bx - ax) * (cy - ay) - (by - ay) * (cx - ax)) / 2f
        }
        return s
    }

    /** Every triangle faces the side its vertex normals point to. */
    private fun assertConsistentWinding(m: Mesh) {
        for (t in m.indices.indices step 3) {
            val i = intArrayOf(m.indices[t], m.indices[t + 1], m.indices[t + 2])
            val p = i.map { Vec3(m.positions[it * 3], m.positions[it * 3 + 1], m.positions[it * 3 + 2]) }
            val f = (p[1] - p[0]).cross(p[2] - p[0])
            if (f.length() < 1e-4f) continue
            val n = i.map { Vec3(m.normals[it * 3], m.normals[it * 3 + 1], m.normals[it * 3 + 2]) }.reduce { a, b -> a + b }
            assertTrue("triangle $t faces away from its normals", f.dot(n) > 0f)
        }
    }

    @Test
    fun squareBecomesABox() {
        val m = TextExtrusion.extrude(listOf(rect(0f, 0f, 100f, 50f)), depth = 40f, bevel = 0f)
        val (min, max) = m.bounds()
        assertEquals(Vec3(0f, 0f, -20f), min)
        assertEquals(Vec3(100f, 50f, 20f), max)
        assertEquals(5000f, capArea(m, -1f), 0.01f)
        assertEquals(5000f, capArea(m, 1f), 0.01f)
        assertConsistentWinding(m)
    }

    @Test
    fun holesAreLeftOpenWhateverTheWinding() {
        // A ring like the letter O, with the hole given in the same winding as the outline.
        val m = TextExtrusion.extrude(listOf(circle(100f), circle(60f)), depth = 30f, bevel = 0f)
        val expected = (PI * (100.0 * 100 - 60.0 * 60)).toFloat()
        assertEquals(expected, capArea(m, -1f), expected * 0.02f)
        assertConsistentWinding(m)
        // Two holes side by side (like the letter B) and a separate island inside a hole (like ® or a nested glyph).
        val b = TextExtrusion.extrude(
            listOf(rect(0f, 0f, 100f, 200f), rect(20f, 20f, 80f, 90f), rect(20f, 110f, 80f, 180f), rect(40f, 40f, 60f, 70f)),
            depth = 10f, bevel = 0f,
        )
        assertEquals(100f * 200f - 2 * 60f * 70f + 20f * 30f, capArea(b, -1f), 1f)
        assertConsistentWinding(b)
    }

    @Test
    fun concaveOutlinesTriangulateCorrectly() {
        // The letter E as one concave polygon.
        val e = listOf(
            Vec2(0f, 0f), Vec2(100f, 0f), Vec2(100f, 20f), Vec2(25f, 20f), Vec2(25f, 40f), Vec2(80f, 40f), Vec2(80f, 60f),
            Vec2(25f, 60f), Vec2(25f, 80f), Vec2(100f, 80f), Vec2(100f, 100f), Vec2(0f, 100f),
        )
        val m = TextExtrusion.extrude(listOf(e), depth = 20f, bevel = 0f)
        assertEquals(TextExtrusion.signedArea(e).let { abs(it) }, capArea(m, -1f), 0.01f)
        assertConsistentWinding(m)
    }

    @Test
    fun bevelInsetsTheFacesAndKeepsTheDepth() {
        val m = TextExtrusion.extrude(listOf(rect(0f, 0f, 100f, 100f)), depth = 40f, bevel = 5f, bevelSteps = 3)
        val (min, max) = m.bounds()
        assertEquals(-20f, min.z, 1e-4f); assertEquals(20f, max.z, 1e-4f)
        assertEquals(0f, min.x, 1e-4f); assertEquals(100f, max.x, 1e-4f)
        // The front face is the outline inset by the bevel.
        assertEquals(90f * 90f, capArea(m, -1f), 0.5f)
        assertConsistentWinding(m)
        // Bevels larger than half the depth are clamped.
        val clamped = TextExtrusion.extrude(listOf(rect(0f, 0f, 100f, 100f)), depth = 10f, bevel = 50f)
        assertEquals(10f, clamped.bounds().let { it.second.z - it.first.z }, 1e-4f)
    }

    @Test
    fun emptyOrDegenerateInputGivesAnEmptyMesh() {
        assertEquals(0, TextExtrusion.extrude(emptyList(), 10f, 0f).vertexCount)
        assertEquals(0, TextExtrusion.extrude(listOf(listOf(Vec2(0f, 0f), Vec2(1f, 0f), Vec2(2f, 0f))), 10f, 0f).vertexCount)
    }
}
