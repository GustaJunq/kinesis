package dev.kinesis.core

import dev.kinesis.core.geom.TextExtrusion
import dev.kinesis.core.math.Vec2
import dev.kinesis.core.nativebridge.NativeCore
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * The Rust text extrusion must give the same geometry as the Kotlin one. Runs when `native/` was built for this
 * machine (`cargo build --release` in native/); otherwise it is skipped and the Kotlin path is what is tested.
 */
class NativeParityTest {
    private fun circle(r: Float, n: Int, wobble: Float = 0f, seed: Int = 0) =
        List(n) { i -> val a = 2 * PI * i / n; Vec2((cos(a) * (r + wobble * sin(a * 7 + seed))).toFloat(), (sin(a) * r).toFloat()) }

    private fun rect(x0: Float, y0: Float, x1: Float, y1: Float) = listOf(Vec2(x0, y0), Vec2(x1, y0), Vec2(x1, y1), Vec2(x0, y1))

    private val letterE = listOf(
        Vec2(0f, 0f), Vec2(50f, 0f), Vec2(100f, 0f), Vec2(100f, 20f), Vec2(25f, 20f), Vec2(25f, 40f), Vec2(80f, 40f),
        Vec2(80f, 60f), Vec2(25f, 60f), Vec2(25f, 80f), Vec2(25f, 80.00001f), Vec2(100f, 80f), Vec2(100f, 100f),
        Vec2(60f, 100f), Vec2(0f, 100f), Vec2(0f, 70f), Vec2(0.00002f, 50f), Vec2(0f, 30f),
    )

    private val cases: Map<String, List<List<Vec2>>> = mapOf(
        "rect" to listOf(rect(0f, 0f, 100f, 50f)),
        "ring" to listOf(circle(100f, 48), circle(60f, 48)),
        "wobbly glyph with hole" to listOf(circle(100f, 240, 6f, 3), circle(50f, 120).reversed()),
        "two letters" to listOf(letterE, letterE.map { Vec2(it.x + 200f, it.y) }),
        "letter E reversed" to listOf(letterE.reversed()),
        "O inside O" to listOf(circle(100f, 64), circle(70f, 64), circle(40f, 64), circle(20f, 64)),
        "two holes" to listOf(rect(0f, 0f, 300f, 100f), rect(20f, 20f, 100f, 80f), rect(150f, 20f, 250f, 80f)),
    )

    @Test
    fun rustAndKotlinExtrudeTheSameGeometry() {
        assumeTrue("native library not built for this machine", NativeCore.available)
        for ((name, contours) in cases) for (bevel in listOf(0f, 5f)) {
            val jvm = TextExtrusion.extrudeJvm(contours, 40f, bevel)
            val rust = NativeCore.extrudeText(contours, 40f, bevel, 3)!!
            assertEquals("$name/$bevel vertices", jvm.vertexCount, rust.vertexCount)
            assertEquals("$name/$bevel triangles", jvm.triangleCount, rust.triangleCount)
            assertEquals("$name/$bevel indices", jvm.indices.toList(), rust.indices.toList())
            for (i in jvm.positions.indices) assertEquals("$name/$bevel position $i", jvm.positions[i], rust.positions[i], 1e-3f)
            for (i in jvm.normals.indices) assertEquals("$name/$bevel normal $i", jvm.normals[i], rust.normals[i], 1e-3f)
            for (i in jvm.uvs.indices) assertEquals("$name/$bevel uv $i", jvm.uvs[i], rust.uvs[i], 1e-3f)
        }
    }

    @Test
    fun emptyInputGivesAnEmptyMeshOnBothPaths() {
        assumeTrue(NativeCore.available)
        assertEquals(0, NativeCore.extrudeText(emptyList(), 10f, 0f, 3)!!.vertexCount)
        assertEquals(0f, abs(NativeCore.extrudeText(listOf(listOf(Vec2(0f, 0f), Vec2(1f, 0f), Vec2(2f, 0f))), 10f, 0f, 3)!!.vertexCount.toFloat()), 0f)
    }
}
