package dev.kinesis.core.format

import dev.kinesis.core.anim.Animatable
import dev.kinesis.core.anim.Easing
import dev.kinesis.core.anim.EasingPreset
import dev.kinesis.core.anim.Keyframe
import dev.kinesis.core.anim.anim
import dev.kinesis.core.edit.Factory
import dev.kinesis.core.edit.Ids
import dev.kinesis.core.edit.TimelineOps
import dev.kinesis.core.math.Color
import dev.kinesis.core.math.Vec2
import dev.kinesis.core.math.Vec3
import dev.kinesis.core.model.Asset
import dev.kinesis.core.model.AssetType
import dev.kinesis.core.model.CameraAim
import dev.kinesis.core.model.CameraContent
import dev.kinesis.core.model.Effect
import dev.kinesis.core.model.EffectValue
import dev.kinesis.core.model.FillSpec
import dev.kinesis.core.model.ImageContent
import dev.kinesis.core.model.KnsProject
import dev.kinesis.core.model.Layer
import dev.kinesis.core.model.LayerType
import dev.kinesis.core.model.Marker
import dev.kinesis.core.model.Mask
import dev.kinesis.core.model.MediaContent
import dev.kinesis.core.model.Model3DContent
import dev.kinesis.core.model.NullContent
import dev.kinesis.core.model.ShapeContent
import dev.kinesis.core.model.ShapeKind
import dev.kinesis.core.model.Transform2D
import dev.kinesis.core.model.Transform3D
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Instant
import kotlin.math.abs
import kotlin.math.atan

/**
 * Reader of the Aurea project format written by Aurea 0.4.x (layer section version 55 and later).
 *
 * Section 2 holds the compositions one after the other; each composition has a header (index, name, size, fps,
 * length) followed by its layers and a footer with the markers. Section 4 is the media pool. Every field is
 * described in `docs/aureaproj.md`. What has no Kinesis equivalent, or was not identified, is reported as a warning.
 */
internal object AureaV55 {

    // Layer kinds.
    const val KIND_VIDEO = 1
    const val KIND_IMAGE = 2
    const val KIND_AUDIO = 3
    const val KIND_SHAPE = 5
    const val KIND_NULL = 6
    const val KIND_MODEL = 10
    const val KIND_PRECOMP = 12

    // Animated channels.
    const val CH_POSITION = 0 // 0..2: x, y, z
    const val CH_SCALE = 3 // 3..5
    const val CH_ROTATION = 6 // 6..8: x, y, z (degrees)
    const val CH_ANCHOR = 9 // 9..11
    const val CH_OPACITY = 12
    const val CH_EFFECT = 31
    const val CH_VOLUME = 32

    // Keyframe interpolation codes.
    const val INTERP_LINEAR = 1
    const val INTERP_BEZIER = 2
    const val INTERP_EXPO = 8

    // Flags (u32 at +55 after the layer name).
    const val FLAG_HIDDEN = 0x04

    // Effects.
    const val FX_BLUR = 0x59008a59L

    /** Blur radius in Kinesis for each unit of the Aurea blur amount (measured on the reference render). */
    const val BLUR_UNIT = 0.4f

    /** A model is scaled so its largest side is this fraction of the composition height (as Aurea shows it). */
    const val MODEL_SIZE = 0.45f

    /** Aurea's 3D camera sits this many composition heights in front of the plane z = 0. */
    const val CAMERA_DISTANCE = 1.2f

    /** Content size of a null object (its default anchor is 50, 50). */
    const val NULL_SIZE = 100f

    private const val TRACK_HEADER = 18
    private const val KEYFRAME_SIZE = 40
    private const val EFFECT_PARAM_SIZE = 29

    class Kf(val frame: Long, val value: Float, val interp: Int, val bezier: FloatArray)
    class Track(val channel: Int, val effect: Int, val param: Int, val static: Float, val keys: List<Kf>)
    class Fx(val type: Long, val enabled: Boolean, val params: List<Float>)

    class LayerRec(
        val kind: Int, val name: String, val start: Long, val end: Long, val srcOffset: Long,
        val parent: Int, val id: Int, val flags: Int, val media: Int, val comp: Int,
        val position: Vec3, val scale: Vec3, val rotation: Vec3, val anchor: Vec3, val opacity: Float, val f138: Float,
        val tracks: List<Track>, val effects: List<Fx>, val masks: Int, val shapeSize: Vec2?, val modelMedia: Int?,
    )

    class Comp(
        val index: Int, val name: String, val width: Int, val height: Int, val fps: Double, val frames: Long,
        val layers: List<LayerRec>, val markers: List<AureaMarker>?,
    )

    class AureaMarker(val frame: Long, val argb: Int, val name: String)

    class PoolItem(val index: Int, val kind: Int, val name: String, val path: String, val width: Int, val height: Int)

    class Converted(val project: KnsProject, val warnings: List<String>, val media: Map<String, String>)

    // ------------------------------------------------------------------ parsing

    private class Buf(val d: ByteArray) {
        private val b = ByteBuffer.wrap(d).order(ByteOrder.LITTLE_ENDIAN)
        val size get() = d.size
        fun ok(at: Int, n: Int) = at >= 0 && at + n <= d.size
        fun u8(at: Int) = d[at].toInt() and 0xff
        fun u16(at: Int) = b.getShort(at).toInt() and 0xffff
        fun i32(at: Int) = b.getInt(at)
        fun u32(at: Int) = b.getInt(at).toLong() and 0xffffffffL
        fun i64(at: Int) = b.getLong(at)
        fun f32(at: Int) = b.getFloat(at)
        fun f64(at: Int) = b.getDouble(at)
        fun vec3(at: Int) = Vec3(f32(at), f32(at + 4), f32(at + 8))

        /** u32-length UTF-8 string of [min]..[max] bytes without control characters, or null. */
        fun str(at: Int, min: Int = 0, max: Int = 4096): Pair<String, Int>? {
            if (!ok(at, 4)) return null
            val n = u32(at)
            if (n < min || n > max || !ok(at + 4, n.toInt())) return null
            val text = runCatching {
                Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(d, at + 4, n.toInt())).toString()
            }.getOrNull() ?: return null
            if (text.any { it.code < 32 }) return null
            return text to (at + 4 + n.toInt())
        }
    }

    /** Parses the tracks block at [at]; returns null when it is not a plausible block. */
    private fun tracksAt(s: Buf, at: Int): Pair<List<Track>, Int>? {
        if (!s.ok(at, 4)) return null
        val n = s.u32(at)
        if (n > 64) return null
        var o = at + 4
        val out = ArrayList<Track>()
        repeat(n.toInt()) {
            if (!s.ok(o, TRACK_HEADER)) return null
            val ch = s.u16(o); val eff = s.i32(o + 2); val par = s.u32(o + 6); val st = s.f32(o + 10); val k = s.u32(o + 14)
            if (k > 5000 || ch > 255 || par > 255) return null
            o += TRACK_HEADER
            if (!s.ok(o, (k * KEYFRAME_SIZE).toInt())) return null
            val keys = (0 until k.toInt()).map { j ->
                val p = o + j * KEYFRAME_SIZE
                Kf(s.i64(p), s.f32(p + 8), s.u8(p + 12), FloatArray(4) { s.f32(p + 13 + 4 * it) })
            }
            if (keys.any { it.frame !in 0..100_000_000 || !it.value.isFinite() }) return null
            o += k.toInt() * KEYFRAME_SIZE
            out += Track(ch, eff, par.toInt(), st, keys)
        }
        return out to o
    }

    /** True when a layer record starts at [p]: `u32 1`, kind, name, sane times and a parsable tracks block. */
    private fun isLayerHead(s: Buf, p: Int): Boolean {
        if (!s.ok(p, 9) || s.u32(p) != 1L || s.u8(p + 4) !in 1..14) return false
        val (name, b) = s.str(p + 5, 1, 200) ?: return false
        if (name.isBlank() || !s.ok(b, 160)) return false
        val start = s.i64(b); val end = s.i64(b + 8)
        if (start !in 0..10_000_000 || end !in start..10_000_000) return false
        return tracksAt(s, b + 143) != null
    }

    private fun effectsAt(s: Buf, at: Int): Pair<List<Fx>, Int>? {
        if (!s.ok(at, 4)) return null
        val n = s.u32(at)
        if (n > 32) return null
        var o = at + 4
        val out = ArrayList<Fx>()
        repeat(n.toInt()) {
            if (!s.ok(o, 14)) return null
            val type = s.u32(o + 4); val enabled = s.u8(o + 8) != 0; val pc = s.u32(o + 10)
            if (pc > 64) return null
            o += 14
            if (!s.ok(o, pc.toInt() * EFFECT_PARAM_SIZE + 16)) return null
            val params = (0 until pc.toInt()).map { s.f32(o + it * EFFECT_PARAM_SIZE) }
            o += pc.toInt() * EFFECT_PARAM_SIZE + 16
            out += Fx(type, enabled, params)
        }
        return out to o
    }

    private fun readLayer(s: Buf, p: Int, next: Int): LayerRec {
        val kind = s.u8(p + 4)
        val (name, b) = s.str(p + 5, 1, 200)!!
        val (tracks, tail) = tracksAt(s, b + 143)!!
        val fx = effectsAt(s, tail)
        val effects = fx?.first.orEmpty()
        // After the effects: a count (masks, never seen non-zero), the text of the layer and the fixed fields.
        val fixed = fx?.second ?: tail
        val masks = if (s.ok(fixed, 4)) s.u32(fixed).toInt() else 0
        val textEnd = s.str(fixed + 4)?.second
        val shapeSize = if (kind == KIND_SHAPE && textEnd != null && s.ok(textEnd + 90, 8)) {
            Vec2(s.f32(textEnd + 90), s.f32(textEnd + 94)).takeIf { it.x.isFinite() && it.y.isFinite() && it.x > 0 && it.y > 0 }
        } else null
        val modelMedia = if (kind == KIND_MODEL && textEnd != null && s.ok(textEnd + 227, 4) && textEnd + 231 <= next) s.i32(textEnd + 227) else null
        return LayerRec(
            kind = kind, name = name, start = s.i64(b), end = s.i64(b + 8), srcOffset = s.i64(b + 16),
            parent = s.i32(b + 43), id = s.i32(b + 51), flags = s.i32(b + 55), media = s.i32(b + 61), comp = s.i32(b + 69),
            position = s.vec3(b + 78), scale = s.vec3(b + 90), rotation = s.vec3(b + 102), anchor = s.vec3(b + 114),
            opacity = s.f32(b + 126), f138 = s.f32(b + 138),
            tracks = tracks, effects = effects, masks = masks, shapeSize = shapeSize, modelMedia = modelMedia,
        )
    }

    /** Composition header at [p]: index (u32), 1 (u32), name, width, height (u32), fps (f64), length in frames (u32). */
    private fun compHeader(s: Buf, p: Int, index: Int): Triple<String, IntArray, Pair<Double, Int>>? {
        if (!s.ok(p, 12) || s.u32(p) != index.toLong() || s.u32(p + 4) != 1L) return null
        val (name, b) = s.str(p + 8, 1, 200) ?: return null
        if (!s.ok(b, 20)) return null
        val w = s.u32(b); val h = s.u32(b + 4); val fps = s.f64(b + 8)
        if (w !in 16..16384 || h !in 16..16384 || !(fps in 1.0..240.0)) return null
        return Triple(name, intArrayOf(w.toInt(), h.toInt(), s.u32(b + 16).toInt()), fps to (b + 20))
    }

    private val FOOTER_SIGNATURE = byteArrayOf(0x66, 0x66, 0x66, 0x3f, 0xa6.toByte(), 0x9b.toByte(), 0xc4.toByte(), 0x3a)

    /** Markers in the composition footer: count (u32) at +135, then frame (u64), colour (ARGB u32), name, u32. */
    private fun readMarkers(s: Buf, from: Int, until: Int, frames: Long): List<AureaMarker>? {
        var sig = -1
        for (i in (until - FOOTER_SIGNATURE.size) downTo from) {
            if ((FOOTER_SIGNATURE.indices).all { s.d[i + it] == FOOTER_SIGNATURE[it] }) { sig = i; break }
        }
        if (sig < 5) return null
        if (!s.ok(sig + 130, 4)) return null
        val count = s.u32(sig + 130)
        if (count > 10_000) return null
        var o = sig + 134
        val out = ArrayList<AureaMarker>()
        repeat(count.toInt()) {
            if (!s.ok(o, 16)) return null
            val frame = s.i64(o); val argb = s.i32(o + 8)
            val (name, e) = s.str(o + 12) ?: return null
            if (frame !in 0..maxOf(frames, 1) * 4 || !s.ok(e, 4)) return null
            out += AureaMarker(frame, argb, name)
            o = e + 4
        }
        return out
    }

    fun parseCompositions(section: ByteArray): List<Comp> {
        val s = Buf(section)
        data class Head(val at: Int, val index: Int, val name: String, val wh: IntArray, val fps: Double, val body: Int)
        val heads = ArrayList<Head>()
        var at = 0
        while (at < s.size - 32) {
            val h = compHeader(s, at, heads.size)
            if (h != null) {
                heads += Head(at, heads.size, h.first, h.second, h.third.first, h.third.second)
                at = h.third.second
            } else at++
        }
        if (heads.isEmpty()) throw AureaFormatException("nenhuma composição encontrada")
        return heads.mapIndexed { i, h ->
            val end = heads.getOrNull(i + 1)?.at ?: s.size
            val starts = ArrayList<Int>()
            var p = h.body
            while (p < end - 9) {
                if (isLayerHead(s, p)) {
                    starts += p
                    val (_, b) = s.str(p + 5, 1, 200)!!
                    p = tracksAt(s, b + 143)!!.second
                } else p++
            }
            val layers = starts.mapIndexed { j, lp -> readLayer(s, lp, starts.getOrNull(j + 1) ?: end) }
            val markers = readMarkers(s, starts.lastOrNull() ?: h.body, end, h.wh[2].toLong())
            Comp(h.index, h.name, h.wh[0], h.wh[1], h.fps, h.wh[2].toLong(), layers, markers)
        }
    }

    /** Media pool: count, then per item index (u32), 1 (u32), kind (u8: 1 video, 2 audio, 3 image, 5 model), name, path, 128 bytes (width and height as u32 at +47), name, 30 bytes. */
    fun parsePool(section: ByteArray): List<PoolItem> {
        val s = Buf(section)
        if (!s.ok(0, 4)) return emptyList()
        val n = s.u32(0)
        if (n > 10_000) return emptyList()
        var p = 4
        val out = ArrayList<PoolItem>()
        repeat(n.toInt()) {
            if (!s.ok(p, 13)) return out
            val index = s.i32(p); val kind = s.u8(p + 8)
            val (name, a) = s.str(p + 9) ?: return out
            val (path, b) = s.str(a) ?: ("" to a + 4)
            if (!s.ok(b, 128)) return out
            val w = s.i32(b + 47); val h = s.i32(b + 51)
            val (_, c) = s.str(b + 128) ?: ("" to b + 132)
            out += PoolItem(index, kind, name, path, w.coerceAtLeast(0), h.coerceAtLeast(0))
            p = c + 30
        }
        return out
    }

    // ------------------------------------------------------------------ conversion

    private class Ctx(
        val comps: List<Comp>,
        val pool: Map<Int, PoolItem>,
        val assets: MutableMap<String, Asset>,
        val mediaEntryOf: (PoolItem) -> String?,
        val warnings: MutableList<String>,
        val taken: MutableSet<String>,
    ) {
        val usedComps = HashSet<Int>()
        val missingMedia = LinkedHashSet<String>()
        var approximatedEasing = false
        var resampled = 0
        var hasModel = false
        val unknownChannels = LinkedHashMap<Int, MutableSet<String>>()
        val unknownEffects = LinkedHashMap<String, MutableList<String>>()
        val assetForEntry = HashMap<String, Asset>()
        fun warnOnce(w: String) { if (w !in warnings) warnings += w }
        fun newId(type: LayerType) = Ids.newId(TimelineOps.prefixFor(type), taken).also { taken += it }
    }

    fun convert(
        sections: List<AureaImport.Section>, manifest: String?, mediaEntries: Set<String>, generator: String,
        meta: Triple<String, String?, String?>?,
    ): Converted {
        val warnings = mutableListOf<String>()
        val compsSection = sections.first { it.type == AureaImport.SECTION_COMPOSITIONS }
        val comps = parseCompositions(compsSection.body)
        val pool = sections.firstOrNull { it.type == AureaImport.SECTION_MODELS }?.let { parsePool(it.body) }.orEmpty()
        val known = setOf(AureaImport.SECTION_PROJECT, AureaImport.SECTION_COMPOSITIONS, AureaImport.SECTION_MODELS)
        sections.filter { it.type !in known }.forEach {
            warnings += "seção ${it.type} do arquivo (${it.body.size} bytes, conteúdo não identificado) não foi importada"
        }
        if (compsSection.version != 55) warnings += "seção de camadas na versão ${compsSection.version} (testado com a 55): confira o resultado"

        // manifest: "media <entry>\t<original path>"
        val manifestMedia = manifest.orEmpty().lines().filter { it.startsWith("media ") }.map { line ->
            val rest = line.removePrefix("media ")
            val tab = rest.indexOf('\t')
            if (tab >= 0) rest.substring(0, tab) to rest.substring(tab + 1) else rest to ""
        }
        fun entryFor(item: PoolItem): String? {
            val byPath = manifestMedia.firstOrNull { it.second.isNotEmpty() && it.second == item.path }?.first
            val byNumber = manifestMedia.firstOrNull { it.first.substringAfterLast('/').substringBefore('-').toIntOrNull() == item.index + 1 }?.first
            return (byPath ?: byNumber)?.takeIf { it in mediaEntries }
        }

        val main = comps.first()
        val now = Instant.now().toString()
        val ctx = Ctx(comps, pool.associateBy { it.index }, linkedMapOf(), ::entryFor, warnings, HashSet())
        ctx.usedComps += main.index
        val layers = convertComp(ctx, main, depth = 0)

        var project = Factory.newProject(
            title = meta?.first ?: main.name, width = main.width, height = main.height, fps = main.fps,
            duration = main.frames.coerceAtLeast(1) / main.fps, background = Color.BLACK, nowIso = now,
            generator = "$generator (importado do Aurea)",
        )
        project = project.copy(meta = project.meta.copy(createdAt = meta?.second ?: now, modifiedAt = meta?.third ?: now))

        val extra = ArrayList<Layer>()
        if (ctx.hasModel) {
            val h = main.height.toFloat()
            val fov = Math.toDegrees(2 * atan(0.5 / CAMERA_DISTANCE)).toFloat()
            extra += Layer(
                id = ctx.newId(LayerType.CAMERA), type = LayerType.CAMERA, name = "Câmera (Aurea)",
                start = 0.0, duration = project.composition.duration,
                camera = CameraContent(position = anim(Vec3(0f, 0f, -CAMERA_DISTANCE * h)), target = anim(Vec3.ZERO), aim = CameraAim.TARGET, fov = anim(fov)),
            )
            warnings += "objetos 3D: o tamanho do modelo e a câmera reproduzem a vista do Aurea de forma aproximada"
        }

        val markers = main.markers?.mapIndexed { i, m ->
            Marker(id = "marker_${i + 1}", time = m.frame / main.fps, name = m.name, color = "#%06X".format(m.argb and 0xffffff))
        }
        if (main.markers == null) warnings += "marcadores da composição principal não puderam ser lidos"

        val unused = comps.filter { it.index !in ctx.usedComps }
        if (unused.isNotEmpty()) {
            warnings += "${unused.size} composição(ões) não usada(s) na principal não foram importadas: " + unused.joinToString(", ") { "\"${it.name}\"" }
        }
        if (ctx.missingMedia.isNotEmpty()) {
            warnings += "mídia ausente no arquivo (as camadas que a usam foram ignoradas): " + ctx.missingMedia.joinToString(", ") { "\"$it\"" }
        }
        if (ctx.approximatedEasing) warnings += "curvas \"ease\" do Aurea (código 8) foram aproximadas por easeOutExpo"
        if (ctx.resampled > 0) warnings += "${ctx.resampled} propriedade(s) com canais x/y/z animados em tempos diferentes foram combinadas (valores reamostrados nos keyframes)"
        ctx.unknownChannels.forEach { (ch, names) ->
            warnings += "canal animado $ch (desconhecido) ignorado em: " + names.joinToString(", ") { "\"$it\"" }
        }
        ctx.unknownEffects.forEach { (desc, names) ->
            warnings += "efeito $desc não tem equivalente e foi ignorado em: " + names.joinToString(", ") { "\"$it\"" }
        }
        warnings += "configurações de rodapé das composições (valores não identificados) não foram importadas"
        if (layers.isEmpty()) warnings += "nenhuma camada pôde ser convertida"

        val media = ctx.assetForEntry.entries.associate { (entry, asset) -> asset.path to entry }
        project = project.copy(layers = layers + extra, assets = ctx.assets.values.toList(), markers = markers.orEmpty())
        return Converted(project, warnings.distinct(), media)
    }

    private fun assetFor(ctx: Ctx, index: Int, layerName: String): Asset? {
        val item = ctx.pool[index]
        if (item == null) { ctx.missingMedia += "mídia #$index de \"$layerName\""; return null }
        val entry = ctx.mediaEntryOf(item)
        if (entry == null) { ctx.missingMedia += item.name; return null }
        ctx.assetForEntry[entry]?.let { return it }
        val type = when (item.kind) {
            1 -> AssetType.VIDEO
            2 -> AssetType.AUDIO
            3 -> AssetType.IMAGE
            5 -> AssetType.MODEL
            else -> { ctx.warnOnce("mídia \"${item.name}\" de tipo ${item.kind} desconhecido foi ignorada"); return null }
        }
        val file = entry.substringAfterLast('/')
        val ext = file.substringAfterLast('.', "").lowercase()
        val base = file.substringBeforeLast('.').replace(Regex("[^A-Za-z0-9._-]+"), "_").trim('_').take(60).ifEmpty { "media" }
        var path = "assets/$base${if (ext.isNotEmpty()) ".$ext" else ""}"
        var n = 2
        while (ctx.assets.values.any { it.path == path }) path = "assets/$base-${n++}${if (ext.isNotEmpty()) ".$ext" else ""}"
        val prefix = when (type) { AssetType.IMAGE -> "img"; AssetType.VIDEO -> "vid"; AssetType.AUDIO -> "aud"; AssetType.MODEL -> "mdl"; AssetType.FONT -> "font" }
        var id = "${prefix}_${ctx.assets.size + 1}"
        while (ctx.assets.containsKey(id)) id += "x"
        val asset = Asset(
            id = id, type = type, path = path, name = item.name, mimeType = mimeOf(ext),
            width = item.width.takeIf { it > 0 && type != AssetType.MODEL && type != AssetType.AUDIO },
            height = item.height.takeIf { it > 0 && type != AssetType.MODEL && type != AssetType.AUDIO },
        )
        ctx.assets[id] = asset
        ctx.assetForEntry[entry] = asset
        return asset
    }

    private fun mimeOf(ext: String): String? = when (ext) {
        "png" -> "image/png"; "jpg", "jpeg" -> "image/jpeg"; "webp" -> "image/webp"; "gif" -> "image/gif"
        "mp4", "m4v" -> "video/mp4"; "webm" -> "video/webm"; "mov" -> "video/quicktime"
        "mp3" -> "audio/mpeg"; "m4a", "aac" -> "audio/mp4"; "wav" -> "audio/wav"; "ogg" -> "audio/ogg"
        "glb" -> "model/gltf-binary"; "gltf" -> "model/gltf+json"
        else -> null
    }

    /** Content size of [rec] in pixels (what its anchor is measured from), or null when it has none. */
    private fun contentSize(ctx: Ctx, rec: LayerRec): Vec2? = when (rec.kind) {
        KIND_IMAGE, KIND_VIDEO -> ctx.pool[rec.media]?.let { Vec2(it.width.toFloat(), it.height.toFloat()) }
        KIND_SHAPE -> rec.shapeSize
        KIND_PRECOMP -> ctx.comps.getOrNull(rec.comp)?.let { Vec2(it.width.toFloat(), it.height.toFloat()) }
        KIND_NULL -> Vec2(NULL_SIZE, NULL_SIZE)
        else -> null
    }

    private fun convertComp(ctx: Ctx, comp: Comp, depth: Int): List<Layer> {
        val byAureaId = HashMap<Int, String>()
        val converted = ArrayList<Pair<LayerRec, Layer>>()
        for (rec in comp.layers) {
            val layer = convertLayer(ctx, comp, rec, depth) ?: continue
            byAureaId[rec.id] = layer.id
            converted += rec to layer
        }
        val recById = comp.layers.associateBy { it.id }
        val out = converted.map { (rec, layer) ->
            if (rec.parent < 0) return@map layer
            val pid = byAureaId[rec.parent]
            val parentRec = recById[rec.parent]
            if (pid == null || parentRec == null) {
                ctx.warnOnce("\"${rec.name}\": camada-pai não encontrada, ficou sem pai")
                return@map layer
            }
            // Aurea measures a child's position from the parent's top-left corner; Kinesis from its centre.
            val half = contentSize(ctx, parentRec)?.let { Vec2(it.x / 2f, it.y / 2f) } ?: Vec2.ZERO
            val half3 = Vec3(half.x, half.y, 0f)
            if (layer.is3DLayer) {
                val t = layer.transform3d
                layer.copy(parent = pid, transform3d = t.copy(position = t.position.map { Vec3(it.x + comp.width / 2f - half3.x, it.y + comp.height / 2f - half3.y, it.z) }))
            } else {
                val t = layer.transform
                // Undo the composition centring done for top-level positions, then measure from the parent's centre.
                layer.copy(parent = pid, transform = t.copy(position = t.position.map { Vec2(it.x + comp.width / 2f - half.x, it.y + comp.height / 2f - half.y) }))
            }
        }
        // Aurea draws the layers sorted by depth: larger z first (farther), keeping the list order for equal z.
        val z = converted.associate { (rec, layer) -> layer.id to rec.position.z }
        return out.withIndex().sortedWith(compareByDescending<IndexedValue<Layer>> { z[it.value.id] ?: 0f }.thenBy { it.index }).map { it.value }
    }

    private fun <T> Animatable<T>.map(f: (T) -> T): Animatable<T> =
        Animatable(f(value), keyframes.map { it.copy(value = f(it.value)) })

    private fun convertLayer(ctx: Ctx, comp: Comp, rec: LayerRec, depth: Int): Layer? {
        val fps = comp.fps
        val start = rec.start / fps
        val duration = (rec.end - rec.start).coerceAtLeast(1) / fps
        val base = rec.start - rec.srcOffset // composition frame of the layer's local time zero
        fun time(frame: Long) = (base + frame - rec.start) / fps

        val tracks = rec.tracks.filter { it.keys.isNotEmpty() }
        fun track(ch: Int) = tracks.firstOrNull { it.channel == ch && it.effect < 0 }
        tracks.filter { it.channel !in 0..12 && it.channel != CH_EFFECT }.forEach {
            ctx.unknownChannels.getOrPut(it.channel) { LinkedHashSet() } += rec.name
        }
        rec.tracks.filter { it.channel == CH_VOLUME && it.keys.isEmpty() && rec.kind != KIND_VIDEO && rec.kind != KIND_AUDIO }.forEach {
            ctx.unknownChannels.getOrPut(it.channel) { LinkedHashSet() } += rec.name
        }

        fun easing(k: Kf): Easing = when (k.interp) {
            INTERP_LINEAR -> Easing.LINEAR
            INTERP_BEZIER -> {
                val b = k.bezier
                if (b.all { it.isFinite() } && b[0] in 0f..1f && b[2] in 0f..1f) Easing.CubicBezier(b[0], b[1], b[2], b[3]) else Easing.LINEAR
            }
            INTERP_EXPO -> { ctx.approximatedEasing = true; Easing.Preset(EasingPreset.EASE_OUT_EXPO) }
            else -> { ctx.warnOnce("interpolação ${k.interp} do Aurea (não identificada) virou linear"); Easing.LINEAR }
        }

        /** Value of a channel at a composition-relative [frame] (frames of the track's own time base). */
        fun sample(t: Track, frame: Double): Float {
            val k = t.keys
            if (frame <= k.first().frame) return k.first().value
            if (frame >= k.last().frame) return k.last().value
            val i = k.indexOfLast { it.frame <= frame }
            val a = k[i]; val b = k[i + 1]
            val u = ((frame - a.frame) / (b.frame - a.frame)).toFloat()
            val e = if (a.interp == INTERP_EXPO) Easing.Preset(EasingPreset.EASE_OUT_EXPO) else if (a.interp == INTERP_BEZIER) easing(a) else Easing.LINEAR
            return a.value + (b.value - a.value) * e.transform(u)
        }

        fun scalar(ch: Int, static: Float, f: (Float) -> Float = { it }): Animatable<Float> {
            val t = track(ch) ?: return anim(f(static))
            val keys = t.keys.map { Keyframe(time(it.frame), f(it.value), easing(it)) }
            return Animatable(keys.first().value, keys)
        }

        /** Combines up to three scalar channels into one vector property. */
        fun <V> vector(chs: List<Int>, statics: List<Float>, make: (FloatArray) -> V): Animatable<V> {
            val ts = chs.map { track(it) }
            if (ts.all { it == null }) return anim0(make(statics.toFloatArray()))
            val animated = ts.filterNotNull()
            val frames = animated.flatMap { t -> t.keys.map { it.frame } }.distinct().sorted()
            val sameTimes = animated.all { t -> t.keys.map { it.frame } == frames }
            val sameEase = sameTimes && frames.indices.all { i -> animated.map { it.keys[i].interp to it.keys[i].bezier.toList() }.distinct().size == 1 }
            if (!sameEase) ctx.resampled++
            val keys = frames.map { fr ->
                val values = FloatArray(chs.size) { c -> ts[c]?.let { sample(it, fr.toDouble()) } ?: statics[c] }
                val owner = animated.firstOrNull { t -> t.keys.any { it.frame == fr } }!!
                Keyframe(time(fr), make(values), easing(owner.keys.first { it.frame == fr }))
            }
            return Animatable(keys.first().value, keys)
        }

        // Effects: blur is converted, the rest is reported.
        val effects = ArrayList<Effect>()
        rec.effects.forEachIndexed { i, fx ->
            if (fx.type == FX_BLUR) {
                val p0 = fx.params.firstOrNull() ?: 0f
                val t = tracks.firstOrNull { it.channel == CH_EFFECT && it.effect == i && it.param == 0 }
                val radius = if (t == null) anim(p0 * BLUR_UNIT) else t.keys.map { Keyframe(time(it.frame), it.value * BLUR_UNIT, easing(it)) }.let { Animatable(it.first().value, it) }
                effects += Effect("blur", fx.enabled, mapOf("radius" to EffectValue.Num(radius)))
            } else {
                val animatedParams = tracks.filter { it.channel == CH_EFFECT && it.effect == i }.map { "p${it.param}" }
                val desc = "0x%08x (parâmetros %s%s)".format(
                    fx.type, fx.params.joinToString(", ") { if (it == it.toInt().toFloat()) it.toInt().toString() else "%.2f".format(it) },
                    if (animatedParams.isEmpty()) "" else "; animados: ${animatedParams.joinToString(", ")}",
                )
                ctx.unknownEffects.getOrPut(desc) { mutableListOf() } += rec.name
            }
        }
        if (rec.masks != 0) ctx.warnOnce("\"${rec.name}\": ${rec.masks} máscara(s) do Aurea não foram importadas")

        val hidden = rec.flags and FLAG_HIDDEN != 0
        if (hidden) ctx.warnings += "\"${rec.name}\" está oculta no Aurea e foi importada oculta"
        val otherFlags = rec.flags and 0xffff and FLAG_HIDDEN.inv()
        if (otherFlags != 0 || (rec.flags ushr 16) != 1) ctx.warnings += "\"${rec.name}\": opções 0x%x da camada não identificadas".format(rec.flags)
        if (abs(rec.f138 - 1f) > 1e-4f) ctx.warnings += "\"${rec.name}\": valor 138 = ${rec.f138} não identificado"

        val w2 = comp.width / 2f; val h2 = comp.height / 2f
        val size = contentSize(ctx, rec)
        val half = size?.let { Vec2(it.x / 2f, it.y / 2f) } ?: Vec2.ZERO

        fun transform2D(): Transform2D {
            if (track(CH_ROTATION) != null || track(CH_ROTATION + 1) != null || abs(rec.rotation.x) > 1e-3f || abs(rec.rotation.y) > 1e-3f) {
                ctx.warnings += "\"${rec.name}\": rotação em X/Y de camada 2D não tem equivalente e foi ignorada"
            }
            if (track(CH_POSITION + 2) != null) ctx.warnings += "\"${rec.name}\": profundidade (z) animada de camada 2D ignorada (vale só a ordem)"
            return Transform2D(
                position = vector(listOf(0, 1), listOf(rec.position.x, rec.position.y)) { Vec2(it[0] - w2, it[1] - h2) },
                anchor = vector(listOf(9, 10), listOf(rec.anchor.x, rec.anchor.y)) { Vec2(it[0] - half.x, it[1] - half.y) },
                scale = vector(listOf(3, 4), listOf(rec.scale.x, rec.scale.y)) { Vec2(it[0], it[1]) },
                rotation = scalar(8, rec.rotation.z),
                opacity = scalar(CH_OPACITY, rec.opacity) { it.coerceIn(0f, 1f) },
            )
        }

        fun base(type: LayerType) = Layer(
            id = ctx.newId(type), type = type, name = rec.name, start = start, duration = duration,
            visible = !hidden, effects = effects,
        )

        return when (rec.kind) {
            KIND_IMAGE -> {
                val a = assetFor(ctx, rec.media, rec.name) ?: return null
                base(LayerType.IMAGE).copy(transform = transform2D(), image = ImageContent(a.id))
            }
            KIND_VIDEO -> {
                val a = assetFor(ctx, rec.media, rec.name) ?: return null
                base(LayerType.VIDEO).copy(transform = transform2D(), video = media(ctx, rec, a.id, fps))
            }
            KIND_AUDIO -> {
                val a = assetFor(ctx, rec.media, rec.name) ?: return null
                base(LayerType.AUDIO).copy(audio = media(ctx, rec, a.id, fps), effects = emptyList())
            }
            KIND_SHAPE -> {
                val s = rec.shapeSize ?: Vec2(comp.width.toFloat(), comp.height.toFloat()).also {
                    ctx.warnings += "\"${rec.name}\": tamanho da forma não lido, usado o da composição"
                }
                base(LayerType.SHAPE).copy(
                    transform = transform2D(),
                    shape = ShapeContent(kind = ShapeKind.RECTANGLE, size = anim(s), fill = FillSpec(color = anim(Color.WHITE))),
                )
            }
            KIND_NULL -> base(LayerType.NULL).copy(transform = transform2D(), nullObject = NullContent(threeD = false))
            KIND_MODEL -> {
                val idx = rec.modelMedia ?: rec.media
                val a = assetFor(ctx, idx, rec.name) ?: return null
                ctx.hasModel = true
                base(LayerType.MODEL3D).copy(
                    transform3d = Transform3D(
                        position = vector(listOf(0, 1, 2), listOf(rec.position.x, rec.position.y, rec.position.z)) { Vec3(it[0] - w2, it[1] - h2, it[2]) },
                        rotation = vector(listOf(6, 7, 8), listOf(rec.rotation.x, rec.rotation.y, rec.rotation.z)) { Vec3(it[0], it[1], it[2]) },
                        scale = vector(listOf(3, 4, 5), listOf(rec.scale.x, rec.scale.y, rec.scale.z)) { Vec3(it[0], it[1], it[2]) },
                        anchor = vector(listOf(9, 10, 11), listOf(rec.anchor.x, rec.anchor.y, rec.anchor.z)) { Vec3(it[0], it[1], it[2]) },
                        opacity = scalar(CH_OPACITY, rec.opacity) { it.coerceIn(0f, 1f) },
                    ),
                    model3d = Model3DContent(asset = a.id, size = anim(MODEL_SIZE * comp.height)),
                )
            }
            KIND_PRECOMP -> {
                val sub = ctx.comps.getOrNull(rec.comp)
                if (sub == null || depth > 16) {
                    ctx.warnings += "\"${rec.name}\": pré-composição ${rec.comp} não encontrada"
                    return null
                }
                ctx.usedComps += sub.index
                // Children live in the precomposition's own time: shift them so they are relative to this layer.
                val shift = rec.srcOffset / sub.fps
                val children = convertComp(ctx, sub, depth + 1).map { it.copy(start = it.start - shift) }
                base(LayerType.GROUP).copy(
                    transform = transform2D(),
                    layers = children,
                    // A precomposition shows only what is inside its frame.
                    masks = listOf(Mask(size = anim(Vec2(sub.width.toFloat(), sub.height.toFloat())))),
                )
            }
            else -> {
                ctx.warnings += "camada \"${rec.name}\" de tipo ${rec.kind} ainda não é suportada e foi ignorada"
                null
            }
        }
    }

    private fun media(ctx: Ctx, rec: LayerRec, asset: String, fps: Double): MediaContent {
        if (rec.srcOffset < 0) ctx.warnings += "\"${rec.name}\": a mídia começa depois do início da camada; o trecho inicial ficou ajustado"
        val volume = rec.tracks.firstOrNull { it.channel == CH_VOLUME }?.let { t ->
            ctx.warnOnce("canal 32 das camadas de mídia foi importado como volume (significado não confirmado)")
            if (t.keys.isEmpty()) anim(t.static.coerceIn(0f, 4f))
            else Animatable(t.keys.first().value, t.keys.map { Keyframe((it.frame - rec.srcOffset) / fps, it.value.coerceIn(0f, 4f)) })
        } ?: anim(1f)
        return MediaContent(asset = asset, sourceStart = rec.srcOffset.coerceAtLeast(0) / fps, volume = volume)
    }

    private fun <V> anim0(v: V): Animatable<V> = Animatable(v)
}
