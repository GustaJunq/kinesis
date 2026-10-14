package dev.kinesis.editor.render

import android.graphics.BitmapFactory
import android.opengl.GLES30
import android.util.Log
import dev.kinesis.core.geom.GltfLoader
import dev.kinesis.core.geom.Mesh
import dev.kinesis.core.geom.ModelMaterial
import dev.kinesis.core.geom.Primitives
import dev.kinesis.core.math.Mat4
import dev.kinesis.core.model.Primitive

/** A mesh uploaded to vertex/index buffers (positions at location 0, normals 1, uvs 2). */
class GpuMesh(mesh: Mesh) {
    private val vao: Int
    private val buffers = IntArray(4)
    val indexCount = mesh.indices.size

    init {
        val ids = IntArray(1)
        GLES30.glGenVertexArrays(1, ids, 0)
        vao = ids[0]
        GLES30.glGenBuffers(4, buffers, 0)
        GLES30.glBindVertexArray(vao)
        attribute(0, buffers[0], mesh.positions, 3)
        attribute(1, buffers[1], mesh.normals, 3)
        attribute(2, buffers[2], if (mesh.uvs.size == mesh.vertexCount * 2) mesh.uvs else FloatArray(mesh.vertexCount * 2), 2)
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, buffers[3])
        GLES30.glBufferData(GLES30.GL_ELEMENT_ARRAY_BUFFER, mesh.indices.size * 4, Gl.intBuffer(mesh.indices), GLES30.GL_STATIC_DRAW)
        GLES30.glBindVertexArray(0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, 0)
    }

    private fun attribute(location: Int, buffer: Int, data: FloatArray, size: Int) {
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, buffer)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, data.size * 4, Gl.floatBuffer(data), GLES30.GL_STATIC_DRAW)
        GLES30.glEnableVertexAttribArray(location)
        GLES30.glVertexAttribPointer(location, size, GLES30.GL_FLOAT, false, size * 4, 0)
    }

    fun draw() {
        if (indexCount == 0) return
        GLES30.glBindVertexArray(vao)
        GLES30.glDrawElements(GLES30.GL_TRIANGLES, indexCount, GLES30.GL_UNSIGNED_INT, 0)
        GLES30.glBindVertexArray(0)
    }

    fun release() {
        GLES30.glDeleteVertexArrays(1, intArrayOf(vao), 0)
        GLES30.glDeleteBuffers(4, buffers, 0)
    }
}

/** A glTF model on the GPU: parts with their materials, normalized to a unit cube in Kinesis axes. */
class GpuModel(val parts: List<Part>, val normalization: Mat4, private val textures: List<Int>) {
    class Part(val mesh: GpuMesh, val material: ModelMaterial, val texture: Int)

    fun release() {
        parts.forEach { it.mesh.release() }
        textures.forEach { Gl.deleteTexture(it) }
    }
}

class MeshCache(private val files: AssetFiles) {
    private val primitives = HashMap<Primitive, GpuMesh>()
    private val models = HashMap<String, GpuModel?>()

    fun primitive(p: Primitive): GpuMesh = primitives.getOrPut(p) { GpuMesh(Primitives.mesh(p)) }

    fun model(assetId: String): GpuModel? = models.getOrPut(assetId) { load(assetId) }

    private fun load(assetId: String): GpuModel? {
        val file = files.file(assetId)?.takeIf { it.isFile } ?: return null
        return try {
            val data = GltfLoader.load(file.readBytes())
            val textures = data.images.map { img ->
                if (img.bytes.isEmpty()) 0
                else BitmapFactory.decodeByteArray(img.bytes, 0, img.bytes.size)?.let { bmp ->
                    uploadBitmap(0, bmp).also { bmp.recycle() }
                } ?: 0
            }
            val parts = data.parts.map { part ->
                val material = data.materials.getOrElse(part.materialIndex) { ModelMaterial() }
                GpuModel.Part(GpuMesh(part.mesh), material, material.baseColorTexture?.let { textures.getOrNull(it) } ?: 0)
            }
            GpuModel(parts, data.normalization(), textures.filter { it != 0 })
        } catch (e: Exception) {
            Log.w("Kinesis", "cannot load model $assetId", e)
            null
        }
    }

    fun release() {
        primitives.values.forEach { it.release() }
        primitives.clear()
        models.values.forEach { it?.release() }
        models.clear()
    }
}
