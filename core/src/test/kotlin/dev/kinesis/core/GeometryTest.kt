package dev.kinesis.core

import dev.kinesis.core.geom.GltfException
import dev.kinesis.core.geom.GltfLoader
import dev.kinesis.core.geom.Primitives
import dev.kinesis.core.math.Vec3
import dev.kinesis.core.model.Primitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.sqrt

class GeometryTest {

    @Test
    fun primitivesFitTheUnitCubeAndHaveOutwardNormals() {
        for (p in Primitive.entries.filter { it != Primitive.EMPTY && it != Primitive.TEXT }) {
            val m = Primitives.mesh(p)
            assertTrue("$p has triangles", m.triangleCount > 0)
            val (min, max) = m.bounds()
            for (v in listOf(min.x, min.y, min.z)) assertTrue("$p min $min", v >= -0.5001f)
            for (v in listOf(max.x, max.y, max.z)) assertTrue("$p max $max", v <= 0.5001f)
            assertTrue("$p indices in range", m.indices.all { it in 0 until m.vertexCount })
            for (i in 0 until m.vertexCount) {
                val n = Vec3(m.normals[i * 3], m.normals[i * 3 + 1], m.normals[i * 3 + 2])
                assertEquals("$p normal length", 1f, n.length(), 1e-3f)
            }
            // Triangle winding agrees with vertex normals (front faces are counter-clockwise from outside).
            for (t in m.indices.indices step 3) {
                fun v(i: Int) = Vec3(m.positions[i * 3], m.positions[i * 3 + 1], m.positions[i * 3 + 2])
                fun n(i: Int) = Vec3(m.normals[i * 3], m.normals[i * 3 + 1], m.normals[i * 3 + 2])
                val a = m.indices[t]; val b = m.indices[t + 1]; val c = m.indices[t + 2]
                val face = (v(b) - v(a)).cross(v(c) - v(a))
                assertTrue("$p triangle ${t / 3} winding", face.dot(n(a) + n(b) + n(c)) >= -1e-6f)
            }
        }
        val plane = Primitives.plane()
        assertEquals(-1f, plane.normals[2], 0f) // faces the default camera
    }

    @Test
    fun sphereIsRound() {
        val m = Primitives.sphere()
        for (i in 0 until m.vertexCount) {
            val r = sqrt(m.positions[i * 3] * m.positions[i * 3] + m.positions[i * 3 + 1] * m.positions[i * 3 + 1] + m.positions[i * 3 + 2] * m.positions[i * 3 + 2])
            assertEquals(0.5f, r, 1e-4f)
        }
    }

    /** Builds a GLB with one triangle under a translated, scaled node and a red metallic material. */
    private fun triangleGlb(extensionsRequired: String = ""): ByteArray {
        val bin = ByteBuffer.allocate(9 * 4 + 3 * 2 + 2).order(ByteOrder.LITTLE_ENDIAN)
        floatArrayOf(0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f, 0f).forEach { bin.putFloat(it) }
        shortArrayOf(0, 1, 2).forEach { bin.putShort(it) }
        val binBytes = bin.array()
        val json = """
            {"asset":{"version":"2.0"}$extensionsRequired,
             "scene":0,"scenes":[{"nodes":[0]}],
             "nodes":[{"children":[1],"translation":[10,0,0]},{"mesh":0,"scale":[2,2,2]}],
             "meshes":[{"primitives":[{"attributes":{"POSITION":0},"indices":1,"material":0}]}],
             "materials":[{"pbrMetallicRoughness":{"baseColorFactor":[1,0,0,1],"metallicFactor":0.8,"roughnessFactor":0.3}}],
             "buffers":[{"byteLength":${binBytes.size}}],
             "bufferViews":[{"buffer":0,"byteOffset":0,"byteLength":36},{"buffer":0,"byteOffset":36,"byteLength":6}],
             "accessors":[{"bufferView":0,"componentType":5126,"count":3,"type":"VEC3","min":[0,0,0],"max":[1,1,0]},
                          {"bufferView":1,"componentType":5123,"count":3,"type":"SCALAR"}]}
        """.trimIndent().toByteArray()
        val jsonPadded = json + ByteArray((4 - json.size % 4) % 4) { ' '.code.toByte() }
        val out = ByteArrayOutputStream()
        val total = 12 + 8 + jsonPadded.size + 8 + binBytes.size
        fun int(v: Int) = out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array())
        int(0x46546C67); int(2); int(total)
        int(jsonPadded.size); int(0x4E4F534A); out.write(jsonPadded)
        int(binBytes.size); int(0x004E4942); out.write(binBytes)
        return out.toByteArray()
    }

    @Test
    fun loadsGlbWithHierarchyAndMaterial() {
        val model = GltfLoader.load(triangleGlb())
        assertEquals(1, model.parts.size)
        val mesh = model.parts[0].mesh
        assertEquals(1, mesh.triangleCount)
        // translation(10) * scale(2) applied to (1, 0, 0)
        assertEquals(12f, mesh.positions[3], 1e-5f)
        assertEquals(2f, mesh.positions[7], 1e-5f)
        // Normals were generated (the file has none): +z for a CCW triangle in the XY plane.
        assertEquals(1f, mesh.normals[2], 1e-5f)
        val mat = model.materials[model.parts[0].materialIndex]
        assertEquals(1f, mat.baseColor.r, 0f)
        assertEquals(0.8f, mat.metallic, 1e-6f)
        assertEquals(0.3f, mat.roughness, 1e-6f)

        // Normalization fits the model into a unit cube centred on the origin.
        val norm = model.normalization()
        val (min, max) = model.bounds()
        val a = norm.transformPoint(min); val b = norm.transformPoint(max)
        assertEquals(1f, maxOf(abs(a.x - b.x), abs(a.y - b.y), abs(a.z - b.z)), 1e-4f)
        assertEquals(Vec3.ZERO.x, norm.transformPoint((min + max) * 0.5f).x, 1e-4f)
    }

    @Test
    fun rejectsCompressedOrInvalidModels() {
        try {
            GltfLoader.load(triangleGlb(""","extensionsRequired":["KHR_draco_mesh_compression"]"""))
            fail("draco accepted")
        } catch (e: GltfException) {
            assertTrue(e.message!!.contains("KHR_draco_mesh_compression"))
        }
        try {
            GltfLoader.load("hello".toByteArray())
            fail("garbage accepted")
        } catch (e: GltfException) {
            // ok
        }
        try {
            GltfLoader.load("""{"asset":{"version":"2.0"},"buffers":[{"uri":"model.bin","byteLength":4}]}""".toByteArray())
            fail("external buffer accepted")
        } catch (e: GltfException) {
            assertTrue(e.message!!.contains("External buffer"))
        }
    }
}
