package dev.kinesis.core.alight

import dev.kinesis.core.anim.anim
import dev.kinesis.core.edit.Factory
import dev.kinesis.core.edit.Ids
import dev.kinesis.core.edit.TimelineOps
import dev.kinesis.core.math.Color
import dev.kinesis.core.math.Vec2
import dev.kinesis.core.model.Asset
import dev.kinesis.core.model.AssetType
import dev.kinesis.core.model.FillSpec
import dev.kinesis.core.model.ImageContent
import dev.kinesis.core.model.KnsProject
import dev.kinesis.core.model.Layer
import dev.kinesis.core.model.LayerType
import dev.kinesis.core.model.Mask
import dev.kinesis.core.model.MediaContent
import dev.kinesis.core.model.ShapeContent
import dev.kinesis.core.model.ShapeKind
import dev.kinesis.core.model.Transform2D
import java.time.Instant
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * The result of a conversion: the project, what happened to each part of the Alight project, and which asset file
 * ([media]: path inside the project, such as `assets/image_1.png`) has to be filled with which Alight media (its `uri`).
 */
class AlightConversion(val project: KnsProject, val report: ImportReport, val media: Map<String, String>)

/**
 * Maps the [AlightScene] to a [KnsProject]. Media that [available] does not list becomes a placeholder rectangle named
 * after the file, so the layout is kept and the person can swap the real file in later.
 *
 * Geometry (see [AlightLayer]): Alight's picture box is twice the `size`, the media covers that box (`fill`), and a
 * layer's `location` is where its centre goes. Kinesis measures a picture in the pixels of the file, so scale, anchor
 * and parent offsets are converted with the cover factor `f = box / file`, and the part of the picture that sticks out
 * of the box is cut with a rectangle mask.
 */
object AlightConverter {
    private val PLACEHOLDER = Color(0.42f, 0.42f, 0.48f)

    fun convert(scene: AlightScene, available: Set<String> = emptySet(), generator: String = "Kinesis Editor"): AlightConversion {
        val report = ImportReport()
        val now = Instant.now().toString()
        var project = Factory.newProject(
            title = scene.title.ifBlank { "Projeto do Alight" }, width = scene.width, height = scene.height, fps = scene.fps,
            duration = scene.totalTimeMs / 1000.0, background = argb(scene.background), nowIso = now,
            generator = "$generator (importado do Alight Motion)",
        )
        val byId = scene.layers.associateBy { it.id }
        val mediaByUri = scene.media.associateBy { it.uri }
        val assets = LinkedHashMap<String, Asset>() // uri -> asset
        val assetUris = LinkedHashMap<String, String>() // asset path -> uri
        val taken = HashSet<String>()
        val kinesisId = HashMap<String, String>()
        val out = ArrayList<Layer>()

        fun assetFor(uri: String, m: AlightMedia): Asset = assets.getOrPut(uri) {
            val type = if (m.kind == AlightMediaKind.VIDEO) AssetType.VIDEO else if (m.kind == AlightMediaKind.AUDIO) AssetType.AUDIO else AssetType.IMAGE
            val prefix = when (type) { AssetType.VIDEO -> "video"; AssetType.AUDIO -> "audio"; else -> "image" }
            val id = "${prefix}_${assets.size + 1}"
            val ext = m.filename.substringAfterLast('.', "").lowercase().filter { it.isLetterOrDigit() }.take(5).ifEmpty { if (type == AssetType.VIDEO) "mp4" else "png" }
            Asset(
                id = id, type = type, path = "assets/$id.$ext", name = m.filename, mimeType = m.mimeType.ifBlank { null },
                width = m.width, height = m.height, duration = m.durationUs?.let { it / 1_000_000.0 },
            ).also { assetUris[it.path] = uri }
        }

        /** Size of the file behind a layer, when its media is available. */
        fun fileOf(l: AlightLayer): AlightMedia? = l.mediaUri?.takeIf { it in available }?.let { mediaByUri[it] }?.takeIf { (it.width ?: 0) > 0 && (it.height ?: 0) > 0 }

        /** Cover factor: pixels of the box per pixel of the file (1 for shapes and placeholders). */
        fun factor(l: AlightLayer): Double {
            val f = fileOf(l) ?: return 1.0
            val box = l.size ?: return 1.0
            val rw = 2 * box[0]; val rh = 2 * box[1]
            return if (l.mediaFillMode == "fit") min(rw / f.width!!, rh / f.height!!) else max(rw / f.width!!, rh / f.height!!)
        }

        for (l in scene.layers) {
            when (l.kind) {
                AlightLayerKind.EMBED -> { report.add(ReportKind.UNSUPPORTED, "layer:${l.label}", "pré-composição (embedScene) ainda não é convertida"); continue }
                AlightLayerKind.AUDIO -> { report.add(ReportKind.UNSUPPORTED, "layer:${l.label}", "áudio ainda não é convertido"); continue }
                AlightLayerKind.SHAPE -> Unit
            }
            val box = l.size
            if (box == null || box.size < 2 || box[0] <= 0 || box[1] <= 0) {
                report.add(ReportKind.UNSUPPORTED, "layer:${l.label}", "forma sem tamanho (property size) válido: ignorada")
                continue
            }
            val rw = 2 * box[0]; val rh = 2 * box[1]
            val parentSrc = l.parentId?.let { byId[it] }
            val parentKid = parentSrc?.let { kinesisId[it.id] }
            if (l.parentId != null && parentKid == null) report.add(ReportKind.WARNING, "layer:${l.label}", "camada-pai ${l.parentId} não foi convertida: a camada ficou sem pai")
            val fp = if (parentKid != null) factor(parentSrc!!) else 1.0

            val tr = l.transform
            val scale = vec(tr.scale, 2, doubleArrayOf(1.0, 1.0), l, "scale", report)
            val rot = vec(tr.rotation, 1, doubleArrayOf(0.0), l, "rotation", report)[0]
            val pivot = vec(tr.pivot, 2, doubleArrayOf(0.0, 0.0), l, "pivot", report)
            val loc = vec(tr.location, 3, doubleArrayOf(0.0, 0.0, 0.0), l, "location", report)
            val opacity = vec(tr.opacity, 1, doubleArrayOf(1.0), l, "opacity", report)[0].coerceIn(0.0, 1.0)
            val f: Double

            val media = l.mediaUri?.let { mediaByUri[it] }
            val common: Layer
            val file = if (l.fillType == "media") fileOf(l) else null
            if (l.fillType == "media" && media == null) {
                report.add(ReportKind.WARNING, "layer:${l.label}", "mídia ${l.mediaUri} não está declarada no projeto: placeholder")
            }
            val id = Ids.newId(TimelineOps.prefixFor(LayerType.SHAPE), taken).also { taken += it }
            when {
                l.fillType == "color" -> {
                    f = 1.0
                    common = Layer(
                        id = id, type = LayerType.SHAPE, name = l.label,
                        shape = ShapeContent(kind = ShapeKind.RECTANGLE, size = anim(Vec2(rw.toFloat(), rh.toFloat())), fill = FillSpec(color = anim(Color.WHITE))),
                    )
                    report.add(ReportKind.APPROXIMATED, "layer:${l.label}", "a cor da forma não está no XML: usado branco")
                }
                file != null && media != null -> {
                    f = factor(l)
                    val asset = assetFor(l.mediaUri!!, media)
                    val isVideo = asset.type == AssetType.VIDEO
                    val cropW = rw / f; val cropH = rh / f
                    val crop = abs(cropW - file.width!!) > 0.5 || abs(cropH - file.height!!) > 0.5
                    common = Layer(
                        id = id, type = if (isVideo) LayerType.VIDEO else LayerType.IMAGE, name = l.label,
                        image = if (isVideo) null else ImageContent(asset.id),
                        video = if (isVideo) MediaContent(asset.id) else null,
                        masks = if (crop) listOf(Mask(size = anim(Vec2(cropW.toFloat(), cropH.toFloat())))) else emptyList(),
                    )
                    if (isVideo && (l.inTimeMs != null || l.outTimeMs != null || (l.speed != null && abs(l.speed - 1.0) > 1e-6))) {
                        report.add(ReportKind.APPROXIMATED, "layer:${l.label}", "retime do vídeo (inTime/outTime/speed) ainda não é aplicado")
                    }
                }
                else -> {
                    f = 1.0
                    common = Layer(
                        id = id, type = LayerType.SHAPE, name = l.label,
                        shape = ShapeContent(kind = ShapeKind.RECTANGLE, size = anim(Vec2(rw.toFloat(), rh.toFloat())), fill = FillSpec(color = anim(PLACEHOLDER))),
                    )
                    report.add(ReportKind.MISSING_MEDIA, "layer:${l.label}", "mídia \"${media?.filename ?: l.mediaUri}\" não encontrada: placeholder")
                }
            }

            // Alight: centre at `location`, rotation and scale around `pivot` (which does not move the layer). Kinesis:
            // position is where the anchor lands, so the anchor goes to location + R·S·pivot.
            val a = Math.toRadians(rot)
            val sx = scale[0] * pivot[0]; val sy = scale[1] * pivot[1]
            val px = loc[0] + sx * cos(a) - sy * sin(a)
            val py = loc[1] + sx * sin(a) + sy * cos(a)
            val position = if (parentKid != null) Vec2((px / fp).toFloat(), (py / fp).toFloat())
            else Vec2((px - scene.width / 2.0).toFloat(), (py - scene.height / 2.0).toFloat())
            val layer = common.copy(
                start = l.startMs / 1000.0, duration = (l.endMs - l.startMs) / 1000.0, parent = parentKid,
                transform = Transform2D(
                    anchor = anim(Vec2((pivot[0] / f).toFloat(), (pivot[1] / f).toFloat())),
                    position = anim(position),
                    scale = anim(Vec2((scale[0] * f).toFloat(), (scale[1] * f).toFloat())),
                    rotation = anim(rot.toFloat()),
                    opacity = anim(opacity.toFloat()),
                ),
            )
            kinesisId[l.id] = layer.id
            out += layer
            report.add(ReportKind.CONVERTED, "layer:${l.label}", "convertida")
        }

        project = project.copy(layers = out, assets = assets.values.toList())
        return AlightConversion(project, report, assetUris)
    }

    /** Static value, or the first keyframe of an animated property (keyframes are converted in a later step). */
    private fun vec(p: AlightProp?, n: Int, default: DoubleArray, l: AlightLayer, name: String, report: ImportReport): DoubleArray {
        val v = when {
            p == null -> return default
            p.static != null -> p.static
            p.keyframes.isNotEmpty() -> {
                report.add(ReportKind.APPROXIMATED, "layer:${l.label}/$name", "animado: usado só o primeiro keyframe por enquanto")
                p.keyframes.first().value
            }
            else -> return default
        }
        return DoubleArray(n) { v.getOrElse(it) { default.getOrElse(it) { 0.0 } } }
    }

    /** `#aarrggbb` (Alight) to a colour; anything else is black. */
    fun argb(text: String): Color {
        val h = text.removePrefix("#")
        val v = h.toLongOrNull(16) ?: return Color.BLACK
        return when (h.length) {
            8 -> Color(((v shr 16) and 0xff) / 255f, ((v shr 8) and 0xff) / 255f, (v and 0xff) / 255f, ((v shr 24) and 0xff) / 255f)
            6 -> Color(((v shr 16) and 0xff) / 255f, ((v shr 8) and 0xff) / 255f, (v and 0xff) / 255f)
            else -> Color.BLACK
        }
    }
}
