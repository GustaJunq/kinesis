package dev.kinesis.core.geom

import dev.kinesis.core.math.Color
import dev.kinesis.core.math.Mat4
import dev.kinesis.core.math.Vec3
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import kotlin.math.max

class GltfException(message: String) : Exception(message)

class ModelMaterial(
    val baseColor: Color = Color(0.8f, 0.8f, 0.8f),
    val metallic: Float = 1f,
    val roughness: Float = 1f,
    val emissive: Color = Color.BLACK,
    /** Index into [ModelData.images]. */
    val baseColorTexture: Int? = null,
    val doubleSided: Boolean = false,
    val blend: Boolean = false,
)

class ModelImage(val bytes: ByteArray, val mimeType: String?)

class ModelPart(val mesh: Mesh, val materialIndex: Int)

/** A glTF scene flattened into meshes in model space (node transforms baked into the vertices). */
class ModelData(
    val parts: List<ModelPart>,
    val materials: List<ModelMaterial>,
    val images: List<ModelImage>,
) {
    val triangleCount: Int get() = parts.sumOf { it.mesh.triangleCount }

    fun bounds(): Pair<Vec3, Vec3> {
        var min = Vec3(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY)
        var max = Vec3(Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY)
        for (part in parts) {
            if (part.mesh.vertexCount == 0) continue
            val (a, b) = part.mesh.bounds()
            min = Vec3(minOf(min.x, a.x), minOf(min.y, a.y), minOf(min.z, a.z))
            max = Vec3(maxOf(max.x, b.x), maxOf(max.y, b.y), maxOf(max.z, b.z))
        }
        if (min.x > max.x) return Vec3.ZERO to Vec3.ZERO
        return min to max
    }

    /**
     * Matrix that centres the model, scales its largest dimension to 1 and converts glTF axes
     * (+y up, +z towards the viewer) to Kinesis axes (+y down, +z away): a 180° rotation around X.
     */
    fun normalization(): Mat4 {
        val (min, max) = bounds()
        val size = max - min
        val largest = max(size.x, max(size.y, size.z)).takeIf { it > 1e-9f } ?: 1f
        val center = (min + max) * 0.5f
        return Mat4.rotationX(180f) * Mat4.scale(1f / largest, 1f / largest, 1f / largest) * Mat4.translation(-center)
    }
}

/** Minimal glTF 2.0 / GLB reader: triangle meshes, node hierarchy and metallic-roughness materials. */
object GltfLoader {
    private const val GLB_MAGIC = 0x46546C67
    private const val CHUNK_JSON = 0x4E4F534A
    private const val CHUNK_BIN = 0x004E4942

    private val unsupportedExtensions = setOf("KHR_draco_mesh_compression", "EXT_meshopt_compression", "KHR_mesh_quantization")
    private val json = Json { ignoreUnknownKeys = true }

    /** Loads a `.glb` file or a `.gltf` file whose buffers are embedded as data URIs. */
    fun load(bytes: ByteArray): ModelData {
        if (bytes.size >= 12 && ByteBuffer.wrap(bytes, 0, 4).order(ByteOrder.LITTLE_ENDIAN).int == GLB_MAGIC) return loadGlb(bytes)
        val text = bytes.toString(Charsets.UTF_8).trimStart('﻿', ' ', '\n', '\r', '\t')
        if (text.startsWith("{")) return loadGltf(json.parseToJsonElement(text).jsonObject, null)
        throw GltfException("Not a glTF/GLB file")
    }

    private fun loadGlb(bytes: ByteArray): ModelData {
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        bb.int // magic
        val version = bb.int
        if (version != 2) throw GltfException("Unsupported GLB version $version (only glTF 2.0 is supported)")
        val length = bb.int
        if (length > bytes.size) throw GltfException("Truncated GLB file")
        var gltf: JsonObject? = null
        var bin: ByteBuffer? = null
        while (bb.position() + 8 <= length) {
            val chunkLength = bb.int
            val chunkType = bb.int
            if (chunkLength < 0 || bb.position() + chunkLength > length) throw GltfException("Corrupted GLB chunk")
            val start = bb.position()
            when (chunkType) {
                CHUNK_JSON -> gltf = json.parseToJsonElement(String(bytes, start, chunkLength, Charsets.UTF_8)).jsonObject
                CHUNK_BIN -> if (bin == null) bin = ByteBuffer.wrap(bytes, start, chunkLength).slice().order(ByteOrder.LITTLE_ENDIAN)
            }
            bb.position(start + ((chunkLength + 3) and 3.inv()).coerceAtMost(length - start))
        }
        return loadGltf(gltf ?: throw GltfException("GLB has no JSON chunk"), bin)
    }

    private fun JsonObject.arr(key: String): List<JsonObject> = (this[key] as? JsonArray)?.map { it.jsonObject } ?: emptyList()
    private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull
    private fun JsonObject.float(key: String): Float? = (this[key] as? JsonPrimitive)?.floatOrNull
    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun JsonObject.floats(key: String): FloatArray? = (this[key] as? JsonArray)?.map { (it as JsonPrimitive).floatOrNull ?: 0f }?.toFloatArray()

    private fun loadGltf(root: JsonObject, glbBin: ByteBuffer?): ModelData {
        val asset = root["asset"] as? JsonObject
        val version = asset?.str("version")
        if (version != null && !version.startsWith("2")) throw GltfException("Unsupported glTF version $version")
        (root["extensionsRequired"] as? JsonArray)?.forEach { ext ->
            val name = (ext as? JsonPrimitive)?.content
            if (name in unsupportedExtensions) throw GltfException("The model uses '$name', which is not supported. Export it without compression.")
        }

        val buffers = root.arr("buffers").mapIndexed { i, b ->
            val uri = b.str("uri")
            when {
                uri == null && i == 0 && glbBin != null -> glbBin
                uri != null && uri.startsWith("data:") -> {
                    val comma = uri.indexOf(',')
                    if (comma < 0 || !uri.substring(0, comma).endsWith(";base64")) throw GltfException("Unsupported data URI in buffer $i")
                    ByteBuffer.wrap(Base64.getDecoder().decode(uri.substring(comma + 1))).order(ByteOrder.LITTLE_ENDIAN)
                }
                uri != null -> throw GltfException("External buffer '$uri' is not supported. Use a .glb file or a .gltf with embedded buffers.")
                else -> throw GltfException("Buffer $i has no data")
            }
        }
        val bufferViews = root.arr("bufferViews")
        val accessors = root.arr("accessors")

        fun viewSlice(viewIndex: Int): Triple<ByteBuffer, Int, Int> {
            val view = bufferViews.getOrNull(viewIndex) ?: throw GltfException("Missing bufferView $viewIndex")
            val buffer = buffers.getOrNull(view.int("buffer") ?: 0) ?: throw GltfException("Missing buffer for view $viewIndex")
            val offset = view.int("byteOffset") ?: 0
            val length = view.int("byteLength") ?: throw GltfException("bufferView $viewIndex has no byteLength")
            if (offset < 0 || offset + length > buffer.limit()) throw GltfException("bufferView $viewIndex is out of range")
            return Triple(buffer, offset, view.int("byteStride") ?: 0)
        }

        fun componentCount(type: String?) = when (type) {
            "SCALAR" -> 1; "VEC2" -> 2; "VEC3" -> 3; "VEC4" -> 4; "MAT4" -> 16
            else -> throw GltfException("Unsupported accessor type $type")
        }

        fun componentSize(componentType: Int) = when (componentType) {
            5120, 5121 -> 1; 5122, 5123 -> 2; 5125, 5126 -> 4
            else -> throw GltfException("Unsupported component type $componentType")
        }

        /** Reads an accessor as floats (normalized integers are converted to 0..1 / -1..1). */
        fun readFloats(index: Int): Pair<FloatArray, Int> {
            val acc = accessors.getOrNull(index) ?: throw GltfException("Missing accessor $index")
            if (acc["sparse"] != null) throw GltfException("Sparse accessors are not supported")
            val count = acc.int("count") ?: 0
            val comps = componentCount(acc.str("type"))
            val ctype = acc.int("componentType") ?: 5126
            val normalized = (acc["normalized"] as? JsonPrimitive)?.booleanOrNull ?: false
            val out = FloatArray(count * comps)
            val viewIndex = acc.int("bufferView") ?: return out to comps
            val (buf, viewOffset, stride0) = viewSlice(viewIndex)
            val csize = componentSize(ctype)
            val stride = if (stride0 > 0) stride0 else csize * comps
            val base = viewOffset + (acc.int("byteOffset") ?: 0)
            if (count > 0 && base + (count - 1) * stride + comps * csize > buf.limit()) throw GltfException("Accessor $index is out of range")
            for (i in 0 until count) for (c in 0 until comps) {
                val at = base + i * stride + c * csize
                out[i * comps + c] = when (ctype) {
                    5126 -> buf.getFloat(at)
                    5121 -> (buf.get(at).toInt() and 0xFF).let { if (normalized) it / 255f else it.toFloat() }
                    5123 -> (buf.getShort(at).toInt() and 0xFFFF).let { if (normalized) it / 65535f else it.toFloat() }
                    5120 -> buf.get(at).toInt().let { if (normalized) max(it / 127f, -1f) else it.toFloat() }
                    5122 -> buf.getShort(at).toInt().let { if (normalized) max(it / 32767f, -1f) else it.toFloat() }
                    5125 -> (buf.getInt(at).toLong() and 0xFFFFFFFFL).toFloat()
                    else -> 0f
                }
            }
            return out to comps
        }

        fun readIndices(index: Int): IntArray {
            val acc = accessors.getOrNull(index) ?: throw GltfException("Missing accessor $index")
            val count = acc.int("count") ?: 0
            val ctype = acc.int("componentType") ?: 5125
            val viewIndex = acc.int("bufferView") ?: return IntArray(count)
            val (buf, viewOffset, stride0) = viewSlice(viewIndex)
            val csize = componentSize(ctype)
            val stride = if (stride0 > 0) stride0 else csize
            val base = viewOffset + (acc.int("byteOffset") ?: 0)
            if (count > 0 && base + (count - 1) * stride + csize > buf.limit()) throw GltfException("Index accessor $index is out of range")
            return IntArray(count) { i ->
                val at = base + i * stride
                when (ctype) {
                    5121 -> buf.get(at).toInt() and 0xFF
                    5123 -> buf.getShort(at).toInt() and 0xFFFF
                    5125 -> buf.getInt(at)
                    else -> throw GltfException("Invalid index component type $ctype")
                }
            }
        }

        // Images
        val images = root.arr("images").mapIndexed { i, img ->
            val uri = img.str("uri")
            val viewIndex = img.int("bufferView")
            when {
                viewIndex != null -> {
                    val view = bufferViews.getOrNull(viewIndex) ?: throw GltfException("Missing bufferView for image $i")
                    val (buf, offset, _) = viewSlice(viewIndex)
                    val len = view.int("byteLength") ?: 0
                    val bytes = ByteArray(len)
                    for (k in 0 until len) bytes[k] = buf.get(offset + k)
                    ModelImage(bytes, img.str("mimeType"))
                }
                uri != null && uri.startsWith("data:") -> {
                    val comma = uri.indexOf(',')
                    val header = uri.substring(5, comma.coerceAtLeast(5))
                    ModelImage(Base64.getDecoder().decode(uri.substring(comma + 1)), header.substringBefore(';'))
                }
                else -> ModelImage(ByteArray(0), null) // external images are ignored (rendered untextured)
            }
        }
        val textures = root.arr("textures")

        // Materials
        val materials = root.arr("materials").map { m ->
            val pbr = m["pbrMetallicRoughness"] as? JsonObject
            val bc = pbr?.floats("baseColorFactor") ?: floatArrayOf(1f, 1f, 1f, 1f)
            val em = m.floats("emissiveFactor") ?: floatArrayOf(0f, 0f, 0f)
            val texIndex = (pbr?.get("baseColorTexture") as? JsonObject)?.int("index")
            val imageIndex = texIndex?.let { textures.getOrNull(it)?.int("source") }?.takeIf { images.getOrNull(it)?.bytes?.isNotEmpty() == true }
            ModelMaterial(
                baseColor = Color(bc.getOrElse(0) { 1f }, bc.getOrElse(1) { 1f }, bc.getOrElse(2) { 1f }, bc.getOrElse(3) { 1f }),
                metallic = pbr?.float("metallicFactor") ?: 1f,
                roughness = pbr?.float("roughnessFactor") ?: 1f,
                emissive = Color(em.getOrElse(0) { 0f }, em.getOrElse(1) { 0f }, em.getOrElse(2) { 0f }),
                baseColorTexture = imageIndex,
                doubleSided = (m["doubleSided"] as? JsonPrimitive)?.booleanOrNull ?: false,
                blend = m.str("alphaMode") == "BLEND",
            )
        } + ModelMaterial(metallic = 0f, roughness = 0.6f) // used by primitives without a material
        val defaultMaterial = materials.lastIndex

        // Meshes → parts (in mesh space)
        val meshes = root.arr("meshes").map { mesh ->
            mesh.arr("primitives").mapNotNull { prim ->
                val mode = prim.int("mode") ?: 4
                if (mode !in 4..6) return@mapNotNull null // points and lines are not rendered
                val attrs = prim["attributes"] as? JsonObject ?: return@mapNotNull null
                val posIndex = attrs.int("POSITION") ?: return@mapNotNull null
                val (positions, pc) = readFloats(posIndex)
                if (pc != 3) throw GltfException("POSITION must be VEC3")
                val vertexCount = positions.size / 3
                var indices = prim.int("indices")?.let { readIndices(it) } ?: IntArray(vertexCount) { it }
                indices = when (mode) {
                    5 -> stripToList(indices)
                    6 -> fanToList(indices)
                    else -> indices
                }
                if (indices.any { it < 0 || it >= vertexCount }) throw GltfException("Mesh indices out of range")
                val normals = attrs.int("NORMAL")?.let { readFloats(it).first }?.takeIf { it.size == positions.size }
                    ?: Mesh.computeNormals(positions, indices)
                val uvs = attrs.int("TEXCOORD_0")?.let { readFloats(it).first }?.takeIf { it.size == vertexCount * 2 }
                    ?: FloatArray(vertexCount * 2)
                val material = prim.int("material")?.takeIf { it in 0 until defaultMaterial } ?: defaultMaterial
                ModelPart(Mesh(positions, normals, uvs, indices), material)
            }
        }

        // Scene graph
        val nodes = root.arr("nodes")
        val parts = ArrayList<ModelPart>()
        fun visit(nodeIndex: Int, parent: Mat4, depth: Int) {
            if (depth > 64) throw GltfException("Node hierarchy is too deep or cyclic")
            val node = nodes.getOrNull(nodeIndex) ?: return
            val world = parent * localMatrix(node)
            node.int("mesh")?.let { mi -> meshes.getOrNull(mi)?.forEach { p -> parts += ModelPart(p.mesh.transformed(world), p.materialIndex) } }
            (node["children"] as? JsonArray)?.forEach { c -> (c as? JsonPrimitive)?.intOrNull?.let { visit(it, world, depth + 1) } }
        }
        val sceneIndex = root.int("scene") ?: 0
        val scene = root.arr("scenes").getOrNull(sceneIndex)
        val roots: List<Int> = (scene?.get("nodes") as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.intOrNull }
            ?: run {
                val children = nodes.flatMap { n -> (n["children"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.intOrNull } ?: emptyList() }.toSet()
                nodes.indices.filter { it !in children }
            }
        if (nodes.isEmpty()) {
            meshes.flatten().forEach { parts += it }
        } else {
            roots.forEach { visit(it, Mat4.identity(), 0) }
        }
        if (parts.isEmpty()) throw GltfException("The model contains no triangle meshes")
        return ModelData(parts, materials, images)
    }

    private fun localMatrix(node: JsonObject): Mat4 {
        (node["matrix"] as? JsonArray)?.let { arr ->
            if (arr.size == 16) return Mat4(FloatArray(16) { (arr[it] as JsonPrimitive).floatOrNull ?: 0f })
        }
        val t = (node["translation"] as? JsonArray)?.floats() ?: floatArrayOf(0f, 0f, 0f)
        val r = (node["rotation"] as? JsonArray)?.floats() ?: floatArrayOf(0f, 0f, 0f, 1f)
        val s = (node["scale"] as? JsonArray)?.floats() ?: floatArrayOf(1f, 1f, 1f)
        return Mat4.translation(t[0], t[1], t[2]) * quaternion(r[0], r[1], r[2], r[3]) * Mat4.scale(s[0], s[1], s[2])
    }

    private fun JsonArray.floats(): FloatArray = map { (it as? JsonPrimitive)?.floatOrNull ?: 0f }.toFloatArray()

    private fun quaternion(x: Float, y: Float, z: Float, w: Float): Mat4 {
        val xx = x * x; val yy = y * y; val zz = z * z
        val xy = x * y; val xz = x * z; val yz = y * z
        val wx = w * x; val wy = w * y; val wz = w * z
        return Mat4(
            floatArrayOf(
                1 - 2 * (yy + zz), 2 * (xy + wz), 2 * (xz - wy), 0f,
                2 * (xy - wz), 1 - 2 * (xx + zz), 2 * (yz + wx), 0f,
                2 * (xz + wy), 2 * (yz - wx), 1 - 2 * (xx + yy), 0f,
                0f, 0f, 0f, 1f,
            ),
        )
    }

    private fun stripToList(s: IntArray): IntArray {
        val out = ArrayList<Int>()
        for (i in 0 until s.size - 2) {
            if (i % 2 == 0) { out += s[i]; out += s[i + 1]; out += s[i + 2] } else { out += s[i + 1]; out += s[i]; out += s[i + 2] }
        }
        return out.toIntArray()
    }

    private fun fanToList(f: IntArray): IntArray {
        val out = ArrayList<Int>()
        for (i in 1 until f.size - 1) { out += f[0]; out += f[i]; out += f[i + 1] }
        return out.toIntArray()
    }
}
