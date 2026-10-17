package dev.kinesis.core.bench

import dev.kinesis.core.format.KnsprojJson
import dev.kinesis.core.geom.Primitives
import dev.kinesis.core.geom.TextExtrusion
import dev.kinesis.core.math.Mat4
import dev.kinesis.core.math.Vec2
import dev.kinesis.core.scene.SceneEvaluator
import org.junit.Assume.assumeTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Baseline timings of the Kotlin core's heavy paths, to decide what is worth moving to Rust.
 * Only runs with `KINESIS_BENCH=1`; prints one `BENCH name median_ms` line per workload.
 */
class CoreBench {
    private fun median(runs: Int, warmup: Int, block: () -> Unit): Double {
        repeat(warmup) { block() }
        val t = DoubleArray(runs) { val s = System.nanoTime(); block(); (System.nanoTime() - s) / 1e6 }
        t.sort()
        return t[runs / 2]
    }

    private fun report(name: String, ms: Double) = println("BENCH $name %.3f".format(ms))

    private fun project(layers: List<String>) = KnsprojJson.decode(
        """{"format":"knsproj","formatVersion":7,"composition":{"width":1080,"height":1920,"fps":30,"duration":10},"layers":[${layers.joinToString(",")}]}""",
    )

    private fun keyed(i: Int) = """{"id":"l$i","type":"shape","duration":10,"shape":{},"transform":{
        "position":{"keyframes":[{"time":0,"value":[0,$i],"easing":"easeInOut"},{"time":5,"value":[400,${i * 2}],"easing":"easeOutCubic"},{"time":10,"value":[0,$i]}]},
        "rotation":{"keyframes":[{"time":0,"value":0},{"time":10,"value":360}]},
        "opacity":{"keyframes":[{"time":0,"value":0},{"time":1,"value":1}]}}}"""

    private fun expr(i: Int) = """{"id":"e$i","type":"shape","duration":10,"shape":{},"transform":{
        "position":{"value":[0,0],"expression":"[Math.sin(time*2+$i)*100, wiggle(2,30)[1]]"},
        "rotation":{"value":0,"expression":"time*45+$i"}}}"""

    /** A GLB with one grid mesh of [n]×[n] quads (positions, normals, uint32 indices). */
    private fun glb(n: Int): ByteArray {
        val verts = (n + 1) * (n + 1)
        val bb = java.nio.ByteBuffer.allocate(verts * 24 + n * n * 6 * 4).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        for (y in 0..n) for (x in 0..n) bb.putFloat(x.toFloat()).putFloat(y.toFloat()).putFloat(((x * y) % 7).toFloat())
        for (i in 0 until verts) bb.putFloat(0f).putFloat(0f).putFloat(1f)
        for (y in 0 until n) for (x in 0 until n) {
            val a = y * (n + 1) + x; val b = a + 1; val c = a + n + 1; val d = c + 1
            for (v in intArrayOf(a, c, b, b, c, d)) bb.putInt(v)
        }
        val bin = bb.array()
        val json = """{"asset":{"version":"2.0"},"scene":0,"scenes":[{"nodes":[0]}],"nodes":[{"mesh":0}],
          "meshes":[{"primitives":[{"attributes":{"POSITION":0,"NORMAL":1},"indices":2}]}],
          "buffers":[{"byteLength":${bin.size}}],
          "bufferViews":[{"buffer":0,"byteOffset":0,"byteLength":${verts * 12}},{"buffer":0,"byteOffset":${verts * 12},"byteLength":${verts * 12}},{"buffer":0,"byteOffset":${verts * 24},"byteLength":${n * n * 24}}],
          "accessors":[{"bufferView":0,"componentType":5126,"count":$verts,"type":"VEC3","min":[0,0,0],"max":[$n,$n,6]},{"bufferView":1,"componentType":5126,"count":$verts,"type":"VEC3"},{"bufferView":2,"componentType":5125,"count":${n * n * 6},"type":"SCALAR"}]}"""
        val j = json.toByteArray().let { it + ByteArray((4 - it.size % 4) % 4) { ' '.code.toByte() } }
        val out = java.nio.ByteBuffer.allocate(12 + 8 + j.size + 8 + bin.size).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        out.putInt(0x46546C67).putInt(2).putInt(12 + 8 + j.size + 8 + bin.size)
        out.putInt(j.size).putInt(0x4E4F534A).put(j)
        out.putInt(bin.size).putInt(0x004E4942).put(bin)
        return out.array()
    }

    @Test
    fun bench() {
        assumeTrue(System.getenv("KINESIS_BENCH") == "1")
        val keyed = project((0 until 500).map(::keyed))
        report("scene_eval_500_keyframed_layers_x30_frames", median(15, 5) { for (f in 0 until 30) SceneEvaluator.evaluate(keyed, f / 3.0) })
        val exprs = project((0 until 100).map(::expr))
        report("scene_eval_100_expression_layers_x30_frames", median(7, 2) { for (f in 0 until 30) SceneEvaluator.evaluate(exprs, f / 3.0) })

        val glyphs = (0 until 24).map { g ->
            val outer = (0 until 240).map { i -> val a = 2 * PI * i / 240; Vec2((cos(a) * (100 + 6 * sin(a * 7 + g))).toFloat(), (sin(a) * 100).toFloat()) }
            val hole = (0 until 120).map { i -> val a = -2 * PI * i / 120; Vec2((cos(a) * 50).toFloat(), (sin(a) * 50).toFloat()) }
            listOf(outer, hole)
        }
        report("text_extrude_24_glyphs_bevel_kotlin", median(9, 3) { for (c in glyphs) TextExtrusion.extrudeJvm(c, 40f, 6f) })
        if (dev.kinesis.core.nativebridge.NativeCore.available) {
            report("text_extrude_24_glyphs_bevel_rust_via_jni", median(9, 3) { for (c in glyphs) TextExtrusion.extrude(c, 40f, 6f) })
        }

        val sphere = Primitives.sphere(256, 256)
        val m = Mat4.rotationY(17f)
        report("mesh_transform_${sphere.vertexCount}_vertices", median(25, 8) { sphere.transformed(m) })
        val model = glb(500)
        java.io.File(System.getProperty("java.io.tmpdir"), "bench-500.glb").writeBytes(model)
        report("gltf_load_500x500_grid_${model.size / 1024}KiB", median(15, 5) { dev.kinesis.core.geom.GltfLoader.load(model) })
        report("mesh_build_sphere_256x256", median(15, 5) { Primitives.sphere(256, 256) })
    }
}
