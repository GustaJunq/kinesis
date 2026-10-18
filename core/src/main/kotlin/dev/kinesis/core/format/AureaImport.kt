package dev.kinesis.core.format

import dev.kinesis.core.anim.Animatable
import dev.kinesis.core.anim.Easing
import dev.kinesis.core.anim.Keyframe
import dev.kinesis.core.anim.anim
import dev.kinesis.core.edit.Factory
import dev.kinesis.core.edit.Ids
import dev.kinesis.core.edit.TimelineOps
import dev.kinesis.core.math.Color
import dev.kinesis.core.math.Vec3
import dev.kinesis.core.model.CameraAim
import dev.kinesis.core.model.CameraContent
import dev.kinesis.core.model.Text3D
import dev.kinesis.core.model.TextAlign
import dev.kinesis.core.model.KnsProject
import dev.kinesis.core.model.Layer
import dev.kinesis.core.model.LayerType
import dev.kinesis.core.model.Material
import dev.kinesis.core.model.Object3DContent
import dev.kinesis.core.model.Primitive
import dev.kinesis.core.model.Transform3D
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Instant
import java.util.zip.CRC32
import java.util.zip.ZipInputStream
import kotlin.math.min

/** The `.aureaproj` could not be read. */
class AureaFormatException(message: String) : Exception(message)

/**
 * Imports projects of the Aurea editor (`.aureaproj`, binary format 2.0.0, app 0.2.x) as a [KnsProject].
 *
 * The format is not documented: what is read here was mapped from sample files (see `docs/aureaproj.md`).
 * Only the known parts are converted; everything else is reported in [Result.warnings] instead of guessed.
 *
 * Container: a zip with `manifest.txt` and `project.aurea`. `project.aurea` starts with a 64-byte header
 * (magic `UREA`), followed by an index of 40-byte section entries, each with a CRC32 of its body.
 */
object AureaImport {

    const val PROJECT_ENTRY = "project.aurea"
    const val MANIFEST_ENTRY = "manifest.txt"
    private const val MAX_PROJECT_BYTES = 64L * 1024 * 1024

    const val SECTION_PROJECT = 1
    const val SECTION_COMPOSITIONS = 2
    const val SECTION_MODELS = 4

    /** Layer kind byte of a 3D object: a shape (cube, ...) or a 3D text; section 4 tells which. */
    private const val KIND_SHAPE_3D = 0x0a

    /** Layer kind byte of a camera. */
    private const val KIND_CAMERA = 0x08

    /**
     * Font size in Kinesis pixels for each unit of the text size stored by Aurea (100 for a new text). Measured on a
     * project opened in both apps, so the size is approximate.
     */
    private const val TEXT_UNIT = 3.45f

    class Result(val project: KnsProject, val warnings: List<String>)

    class Section(val type: Int, val version: Int, val body: ByteArray)

    /** True when [firstEntryName], the name of the first zip entry, belongs to an `.aureaproj`. */
    fun isAureaEntry(firstEntryName: String?): Boolean = firstEntryName == PROJECT_ENTRY || firstEntryName == MANIFEST_ENTRY

    /** Reads an `.aureaproj` archive. Media inside the archive is not imported yet. */
    fun read(input: InputStream, generator: String = "Kinesis Editor"): Result {
        var data: ByteArray? = null
        val others = mutableListOf<String>()
        try {
            ZipInputStream(input).use { zip ->
                while (true) {
                    val e = zip.nextEntry ?: break
                    when {
                        e.isDirectory -> Unit
                        e.name == PROJECT_ENTRY -> data = readLimited(zip)
                        e.name != MANIFEST_ENTRY -> others += e.name
                    }
                }
            }
        } catch (e: java.util.zip.ZipException) {
            throw AureaFormatException("pacote zip inválido: ${e.message}")
        }
        val bytes = data ?: throw AureaFormatException("não é um projeto do Aurea (falta $PROJECT_ENTRY)")
        val result = convert(parseSections(bytes), generator)
        val warnings = result.warnings.toMutableList()
        if (others.isNotEmpty()) warnings += "${others.size} arquivo(s) de mídia do projeto ainda não são importados"
        return Result(result.project, warnings)
    }

    fun read(bytes: ByteArray, generator: String = "Kinesis Editor"): Result = read(ByteArrayInputStream(bytes), generator)

    /** Parses the header and the section index of `project.aurea`, checking every CRC32. */
    fun parseSections(data: ByteArray): List<Section> {
        val b = le(data)
        if (data.size < 64 || data[0] != 'U'.code.toByte() || data[1] != 'R'.code.toByte() ||
            data[2] != 'E'.code.toByte() || data[3] != 'A'.code.toByte()
        ) throw AureaFormatException("assinatura UREA ausente")
        val major = b.getShort(4).toInt() and 0xffff
        if (major != 1) throw AureaFormatException("versão de contêiner $major não suportada")
        val count = b.getInt(8)
        val index = b.getInt(12)
        if (count !in 1..64 || index < 64 || index.toLong() + count * 40L > data.size) throw AureaFormatException("índice de seções inválido")
        return (0 until count).map { i ->
            val o = index + 40 * i
            val type = b.getShort(o).toInt() and 0xffff
            val version = b.getShort(o + 2).toInt() and 0xffff
            val offset = b.getLong(o + 6)
            val size = b.getLong(o + 14)
            val crc = b.getLong(o + 30) and 0xffffffffL
            if (offset < 0 || size < 0 || offset + size > data.size) throw AureaFormatException("seção $type fora do arquivo")
            val body = data.copyOfRange(offset.toInt(), (offset + size).toInt())
            if (CRC32().apply { update(body) }.value != crc) throw AureaFormatException("seção $type corrompida (CRC)")
            Section(type, version, body)
        }
    }

    private fun convert(sections: List<Section>, generator: String): Result {
        val warnings = mutableListOf<String>()
        fun section(type: Int) = sections.firstOrNull { it.type == type }

        val meta = section(SECTION_PROJECT)?.let { readProjectMeta(it.body) }
        val comps = section(SECTION_COMPOSITIONS) ?: throw AureaFormatException("projeto sem composição")
        if (comps.version != 46) warnings += "seção de camadas na versão ${comps.version} (testado com a 46): confira o resultado"
        val comp = readComposition(comps.body)

        val now = Instant.now().toString()
        var project = Factory.newProject(
            title = meta?.title ?: comp.name.ifBlank { "Projeto do Aurea" },
            width = comp.width, height = comp.height, fps = comp.fps, duration = comp.frames / comp.fps,
            background = Color.BLACK, nowIso = now, generator = "$generator (importado do Aurea)",
        )
        project = project.copy(meta = project.meta.copy(createdAt = meta?.createdAt ?: now, modifiedAt = meta?.modifiedAt ?: now))

        val models = section(SECTION_MODELS)?.body
        val shapes = models?.let { readShapeSpecs(it) }.orEmpty()
        val texts = models?.let { readTextSpecs(it) }.orEmpty()
        models?.let { m ->
            // Each kind of object has its own string version; only these were tested.
            val tested = mapOf("shape3d" to 1, "text3d" to 2)
            Regex("aurea-(shape3d|text3d):v(\\d+)").findAll(String(m, Charsets.ISO_8859_1))
                .map { it.groupValues[1] to it.groupValues[2].toInt() }.distinct()
                .filter { (kind, v) -> tested[kind] != v }
                .forEach { (kind, v) -> warnings += "objeto $kind na versão v$v (testado com v${tested[kind]}): confira o resultado" }
        }
        val layers = mutableListOf<Layer>()
        var approximateText = false
        for (rec in findLayers(comps.body, comp.layersStart)) {
            val taken = (layers.map { it.id } + project.layers.map { it.id }).toSet()
            val start = rec.startFrame / comp.fps
            val duration = ((rec.endFrame - rec.startFrame).coerceAtLeast(1)) / comp.fps
            // Aurea: pixels from the top-left corner. Kinesis: pixels from the centre.
            val centred = Vec3(rec.position.x - comp.width / 2f, rec.position.y - comp.height / 2f, rec.position.z)
            if (rec.kind == KIND_CAMERA) {
                // Aimed straight ahead; the field of view makes the plane z = 0 map one to one, as in the Aurea view.
                val distance = kotlin.math.abs(rec.position.z).coerceAtLeast(1f)
                val fov = Math.toDegrees(2 * kotlin.math.atan((comp.height / 2f) / distance.toDouble())).toFloat().coerceIn(10f, 120f)
                val ch = rec.channels
                fun animated(c: Channel, offset: Float): Animatable<Float> =
                    if (c.v0 == c.v1) anim(c.v0 + offset) else
                        Animatable(c.v0 + offset, listOf(Keyframe(0.0, c.v0 + offset, c.ease), Keyframe(c.frame1 / comp.fps, c.v1 + offset)))
                fun vec(a: Animatable<Float>, b: Animatable<Float>, c: Animatable<Float>): Animatable<Vec3> {
                    val times = (a.keyframes + b.keyframes + c.keyframes).map { it.time }.distinct().sorted()
                    if (times.isEmpty()) return anim(Vec3(a.value, b.value, c.value))
                    val easing = (a.keyframes + b.keyframes + c.keyframes).firstOrNull()?.easing ?: Easing.LINEAR
                    fun at(t: Double) = Vec3(a.sample(t) { x, y, u -> x + (y - x) * u }, b.sample(t) { x, y, u -> x + (y - x) * u }, c.sample(t) { x, y, u -> x + (y - x) * u })
                    return Animatable(at(times.first()), times.mapIndexed { i, t -> Keyframe(t, at(t), if (i == 0) easing else Easing.LINEAR) })
                }
                val (rotation, position) = if (ch == null) anim(Vec3.ZERO) to anim(centred) else
                    vec(animated(ch[0], 0f), animated(ch[1], 0f), animated(ch[2], 0f)) to
                        vec(animated(ch[3], -comp.width / 2f), animated(ch[4], -comp.height / 2f), animated(ch[5], 0f))
                layers += Layer(
                    id = Ids.newId(TimelineOps.prefixFor(LayerType.CAMERA), taken),
                    type = LayerType.CAMERA, name = rec.name, start = start, duration = duration,
                    camera = CameraContent(position = position, aim = CameraAim.ROTATION, rotation = rotation, fov = anim(fov)),
                )
                continue
            }
            if (rec.kind != KIND_SHAPE_3D) {
                warnings += "camada \"${rec.name}\" (tipo ${rec.kind}) ainda não é suportada e foi ignorada"
                continue
            }
            val spec = shapes[rec.name]
            val text = if (spec == null) texts.firstOrNull { it.content == rec.content } else null
            if (text != null) {
                val fontSize = (rec.textSize?.takeIf { it in 1f..10_000f } ?: 100f) * TEXT_UNIT
                approximateText = true
                layers += Layer(
                    id = Ids.newId(TimelineOps.prefixFor(LayerType.OBJECT3D), taken),
                    type = LayerType.OBJECT3D, name = rec.name, start = start, duration = duration,
                    transform3d = Transform3D(position = anim(centred), scale = anim(rec.scale)),
                    object3d = Object3DContent(
                        primitive = Primitive.TEXT,
                        material = Material(color = anim(text.color), metallic = anim(text.metallic), roughness = anim(text.roughness), emissive = anim(text.emissive)),
                        text = Text3D(
                            content = text.content, fontSize = fontSize,
                            depth = (fontSize * text.depth).coerceIn(1f, 2000f),
                            bevel = (fontSize * text.bevel).coerceAtLeast(0f),
                            align = text.align,
                        ),
                    ),
                )
                continue
            }
            val primitive = when (spec?.primitive) {
                null, "cube" -> Primitive.CUBE
                "sphere" -> Primitive.SPHERE
                "cylinder" -> Primitive.CYLINDER
                "cone" -> Primitive.CONE
                "torus" -> Primitive.TORUS
                "plane" -> Primitive.PLANE
                else -> { warnings += "forma 3D \"${spec.primitive}\" virou cubo"; Primitive.CUBE }
            }
            if (spec == null) warnings += "\"${rec.name}\": objeto 3D não reconhecido, importado como cubo"
            if (spec != null && spec.faceColors.distinct().size > 1) {
                warnings += "\"${rec.name}\": o Kinesis usa uma cor por objeto; foi usada a da primeira face"
            }
            layers += Layer(
                id = Ids.newId(TimelineOps.prefixFor(LayerType.OBJECT3D), taken),
                type = LayerType.OBJECT3D,
                name = rec.name,
                start = start,
                duration = duration,
                transform3d = Transform3D(position = anim(centred), scale = anim(rec.scale)),
                object3d = Object3DContent(
                    primitive = primitive,
                    size = anim(min(comp.width, comp.height) * 0.35f),
                    material = Material(color = anim(spec?.faceColors?.firstOrNull() ?: Factory.PALETTE[0]), roughness = anim(0.45f)),
                ),
            )
        }
        if (approximateText) warnings += "texto 3D: o tamanho, a fonte e o chanfro são aproximados (a fonte do Kinesis é a padrão)"
        if (layers.isEmpty()) warnings += "nenhuma camada pôde ser convertida"
        warnings += "rotação e keyframes dos objetos, efeitos e o campo de visão da câmera do Aurea ainda não são importados"
        return Result(project.copy(layers = layers), warnings)
    }

    // ------------------------------------------------------------------ sections

    private class Meta(val title: String, val createdAt: String?, val modifiedAt: String?)

    private fun readProjectMeta(s: ByteArray): Meta? {
        val b = le(s)
        val title = readString(s, 0) ?: return null
        val after = 4 + title.second.size
        // Title, 8 unknown bytes, then created and modified times in epoch milliseconds.
        fun time(at: Int): String? = if (at + 8 <= s.size) b.getLong(at).takeIf { it in 1_000_000_000_000L..10_000_000_000_000L }?.let { Instant.ofEpochMilli(it).toString() } else null
        return Meta(title.first, time(after + 8), time(after + 16))
    }

    private class Comp(val name: String, val width: Int, val height: Int, val fps: Double, val frames: Int, val layersStart: Int)

    /** Composition header: unknown prefix, name, width, height (u32), fps (f64), length in frames (u32). */
    private fun readComposition(s: ByteArray): Comp {
        val b = le(s)
        for (at in 0 until min(s.size - 4, 256)) {
            val str = readString(s, at) ?: continue
            if (str.first.isBlank()) continue
            val o = at + 4 + str.second.size
            if (o + 20 > s.size) continue
            val w = b.getInt(o); val h = b.getInt(o + 4); val fps = b.getDouble(o + 8); val frames = b.getInt(o + 16)
            if (w in 16..16384 && h in 16..16384 && fps in 1.0..240.0 && frames in 1..10_000_000) {
                return Comp(str.first, w, h, fps, frames, o + 20)
            }
        }
        throw AureaFormatException("cabeçalho da composição não encontrado")
    }

    private class LayerRecord(
        val kind: Int, val name: String, val startFrame: Int, val endFrame: Int, val position: Vec3, val scale: Vec3,
        /** The text a 3D object carries (a new cube carries the default "Texto"), and the text size that follows it. */
        val content: String? = null, val textSize: Float? = null,
        /** Camera only: the six animated channels (rotation x, y, z, then position x, y, z), when they could be read. */
        val channels: List<Channel>? = null,
    )

    /** Two keyframes of an Aurea channel: value at the start, value at [frame1], and the Bézier of the first segment. */
    private class Channel(val v0: Float, val v1: Float, val frame1: Int, val ease: Easing.CubicBezier)

    /**
     * Layer records start with id (u32, > 0), kind (u8) and the name. Relative to the end of the name:
     * start frame (u32) at +4, end frame (u32) at +8, position xyz (f32) at +78, scale xyz (f32) at +90. Kind 0x0a is a
     * 3D object (shape or text) and 0x08 a camera.
     */
    private fun findLayers(s: ByteArray, from: Int): List<LayerRecord> {
        val b = le(s)
        val out = mutableListOf<LayerRecord>()
        var at = from
        while (at + 9 < s.size) {
            val id = b.getInt(at)
            val kind = s[at + 4].toInt() and 0xff
            val str = if (id in 1..1_000_000 && kind in 1..64) readString(s, at + 5) else null
            val end = if (str != null) at + 9 + str.second.size else -1
            if (str == null || str.first.isBlank() || end + 102 > s.size) { at++; continue }
            fun f(o: Int) = b.getFloat(end + o)
            val scale = Vec3(f(90), f(94), f(98))
            val sane = listOf(f(78), f(82), f(86), scale.x, scale.y, scale.z).all { it.isFinite() && kotlin.math.abs(it) < 1e7f }
            if (!sane) { at++; continue }
            // 3D objects: u32-length text at +155, then its colour (4 bytes) and, 8 bytes after the text, the size (f32).
            val content = readString(s, end + 155)
            val sizeAt = content?.let { end + 159 + it.second.size + 8 }
            val textSize = if (sizeAt != null && sizeAt + 4 <= s.size) b.getFloat(sizeAt).takeIf { it.isFinite() } else null
            out += LayerRecord(
                kind, str.first, b.getInt(end + 4), b.getInt(end + 8), Vec3(f(78), f(82), f(86)), scale, content?.first, textSize,
                channels = if (kind == KIND_CAMERA) readCameraChannels(s, end + 102) else null,
            )
            at = end + 102
        }
        return out
    }

    /**
     * A camera carries, after its scale, one block per channel (rotation x, y, z, position x, y, z): the value at the
     * first keyframe, a tag (02) and the Bézier of the segment (x1 y1 x2 y2 as f32), then the frame of the second
     * keyframe (a u8 after `01`, 90 here) and its value followed by a tag (01) and another Bézier. Located by those
     * tag + Bézier patterns, since the blocks are not aligned. Only two-keyframe channels were seen.
     */
    private fun readCameraChannels(s: ByteArray, from: Int): List<Channel>? {
        val b = le(s)
        val limit = min(s.size, from + 900)
        fun floatAt(i: Int) = b.getFloat(i)
        fun isBezier(i: Int, tag: Int) = i + 17 <= limit && (s[i].toInt() and 0xff) == tag &&
            (1..4).all { floatAt(i + 4 * it - 3).let { v -> v.isFinite() && v in -1f..2f } } && floatAt(i + 13) == 1f
        val first = (from + 4 until limit - 17).filter { isBezier(it, 2) }
        val channels = mutableListOf<Channel>()
        var at = from
        for (i in first) {
            if (i < at) continue
            val second = (i + 17 until minOf(i + 60, limit - 17)).firstOrNull { isBezier(it, 1) } ?: continue
            val frameAt = (i + 17 until second).firstOrNull { (s[it].toInt() and 0xff) == 1 && it + 1 < second } ?: continue
            val v0 = floatAt(i - 4); val v1 = floatAt(second - 4)
            if (!v0.isFinite() || !v1.isFinite()) continue
            channels += Channel(v0, v1, s[frameAt + 1].toInt() and 0xff, Easing.CubicBezier(floatAt(i + 1), floatAt(i + 5), floatAt(i + 9), floatAt(i + 13)))
            at = second + 17
        }
        return channels.takeIf { it.size == 6 }
    }

    private class ShapeSpec(val primitive: String?, val faceColors: List<Color>)

    /**
     * 3D shapes: the record has the layer name, a `aurea-shape3d:v1;key=value;...` string (c0..c5 are face colours
     * in RRGGBBAA) and later the primitive name (`cube`, ...).
     */
    private fun readShapeSpecs(s: ByteArray): Map<String, ShapeSpec> {
        val out = linkedMapOf<String, ShapeSpec>()
        var at = 0
        while (at + 4 < s.size) {
            val name = readString(s, at)
            val spec = name?.let { readString(s, at + 4 + it.second.size) }
            if (name == null || spec == null || !spec.first.startsWith("aurea-shape3d:")) { at++; continue }
            val fields = spec.first.removePrefix("aurea-shape3d:").split(';').mapNotNull {
                val i = it.indexOf('='); if (i > 0) it.substring(0, i) to it.substring(i + 1) else null
            }.toMap()
            val colors = (0..5).mapNotNull { fields["c$it"]?.let(::rgba) }
            val tail = at + 8 + name.second.size + spec.second.size
            val primitive = (tail until min(s.size - 4, tail + 400)).firstNotNullOfOrNull { o ->
                readString(s, o)?.first?.takeIf { it in setOf("cube", "sphere", "cylinder", "cone", "torus", "plane") }
            }
            out.putIfAbsent(name.first, ShapeSpec(primitive, colors))
            at = tail
        }
        return out
    }

    private class TextSpec(
        val content: String, val depth: Float, val bevel: Float, val align: TextAlign,
        val color: Color, val metallic: Float, val roughness: Float, val emissive: Color,
    )

    /**
     * 3D texts: `aurea-text3d:v2;d=<depth>;a=<align>;c=<RRGGBBAA>;bv=<style><size>/…;m0=<RRGGBB>/<metallic>/<roughness>/…;
     * m0e=<RRGGBB>/<strength>;…;t=<text>`. Depth and bevel are fractions of the font size. A text appears once for each
     * time it was edited, so the same text may be listed more than once (the first one is used).
     */
    private fun readTextSpecs(s: ByteArray): List<TextSpec> {
        val out = mutableListOf<TextSpec>()
        var at = 0
        while (at + 4 < s.size) {
            val str = readString(s, at)
            if (str == null || !str.first.startsWith("aurea-text3d:")) { at++; continue }
            val body = str.first.removePrefix("aurea-text3d:")
            // `t=` is the last field and the text may hold anything, so it is taken from its first appearance after the others.
            val tAt = body.indexOf(";t=")
            val content = if (tAt >= 0) body.substring(tAt + 3) else ""
            val fields = (if (tAt >= 0) body.substring(0, tAt) else body).split(';').mapNotNull {
                val i = it.indexOf('='); if (i > 0) it.substring(0, i) to it.substring(i + 1) else null
            }.toMap()
            fun num(v: String?, default: Float) = v?.toFloatOrNull() ?: default
            val material = fields["m0"]?.split('/').orEmpty()
            val color = (fields["c"]?.let(::rgba)) ?: material.getOrNull(0)?.let { rgba(it + "ff") } ?: Color.WHITE
            val emissive = fields["m0e"]?.split('/')?.firstOrNull()?.let { rgba(it + "ff") } ?: Color.BLACK
            // bv = <style digit><size>/<size>/…: the digit glued to the first number is the style.
            val bevel = fields["bv"]?.split('/')?.firstOrNull()?.drop(1)?.toFloatOrNull() ?: 0f
            out += TextSpec(
                content = content, depth = num(fields["d"], 0.25f), bevel = bevel,
                align = when (fields["a"]) { "0" -> TextAlign.LEFT; "2" -> TextAlign.RIGHT; else -> TextAlign.CENTER },
                color = material.getOrNull(0)?.let { rgba(it + "ff") } ?: color,
                metallic = num(material.getOrNull(1), 0f).coerceIn(0f, 1f),
                roughness = num(material.getOrNull(2), 0.5f).coerceIn(0f, 1f),
                emissive = emissive,
            )
            at += 4 + str.second.size
        }
        return out
    }

    // ------------------------------------------------------------------ helpers

    private fun rgba(hex: String): Color? {
        if (hex.length != 8) return null
        val v = hex.toLongOrNull(16) ?: return null
        return Color(((v shr 24) and 0xff) / 255f, ((v shr 16) and 0xff) / 255f, ((v shr 8) and 0xff) / 255f, (v and 0xff) / 255f)
    }

    /** A u32-length-prefixed printable UTF-8 string at [at], or null. */
    private fun readString(s: ByteArray, at: Int): Pair<String, ByteArray>? {
        if (at < 0 || at + 4 > s.size) return null
        val n = le(s).getInt(at)
        if (n !in 1..4096 || at + 4 + n > s.size) return null
        val raw = s.copyOfRange(at + 4, at + 4 + n)
        val text = runCatching { Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(raw)).toString() }.getOrNull() ?: return null
        if (text.any { it.code < 32 }) return null
        return text to raw
    }

    private fun le(a: ByteArray): ByteBuffer = ByteBuffer.wrap(a).order(ByteOrder.LITTLE_ENDIAN)

    private fun readLimited(input: InputStream): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val n = input.read(buf); if (n < 0) break
            total += n
            if (total > MAX_PROJECT_BYTES) throw AureaFormatException("$PROJECT_ENTRY grande demais")
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }
}
