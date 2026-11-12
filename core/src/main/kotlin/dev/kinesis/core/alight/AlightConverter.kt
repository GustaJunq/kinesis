package dev.kinesis.core.alight

import dev.kinesis.core.anim.AnimFloat
import dev.kinesis.core.anim.Animatable
import dev.kinesis.core.anim.Easing
import dev.kinesis.core.anim.Keyframe
import dev.kinesis.core.anim.anim
import dev.kinesis.core.anim.at
import dev.kinesis.core.edit.Factory
import dev.kinesis.core.edit.Ids
import dev.kinesis.core.edit.TimelineOps
import dev.kinesis.core.math.Color
import dev.kinesis.core.math.Vec2
import dev.kinesis.core.model.Asset
import dev.kinesis.core.model.AssetType
import dev.kinesis.core.model.Effect
import dev.kinesis.core.model.EffectValue
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
import kotlin.math.PI
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
        val run = Run(scene, available)
        val project = Factory.newProject(
            title = scene.title.ifBlank { "Projeto do Alight" }, width = scene.width, height = scene.height, fps = scene.fps,
            duration = scene.totalTimeMs / 1000.0, background = argb(scene.background), nowIso = Instant.now().toString(),
            generator = "$generator (importado do Alight Motion)",
        )
        val layers = run.layers(scene, shiftSec = 0.0, speed = 1.0, depth = 0)
        run.finish()
        return AlightConversion(project.copy(layers = layers, assets = run.assets.values.toList()), run.report, run.assetUris)
    }

    private class Run(val root: AlightScene, val available: Set<String>) {
        val report = ImportReport()
        val mediaByUri = root.media.associateBy { it.uri }
        val assets = LinkedHashMap<String, Asset>() // uri -> asset
        val assetUris = LinkedHashMap<String, String>() // asset path -> uri
        val taken = HashSet<String>()
        val stats = EasingStats()
        var layerCount = 0

        /** [asAudio]: an audio layer that plays the sound of a video file gets its own audio asset (same file). */
        fun assetFor(uri: String, m: AlightMedia, asAudio: Boolean = false): Asset = assets.getOrPut(if (asAudio) "audio:$uri" else uri) {
            val type = if (asAudio || m.kind == AlightMediaKind.AUDIO) AssetType.AUDIO else if (m.kind == AlightMediaKind.VIDEO) AssetType.VIDEO else AssetType.IMAGE
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

        /** Cover factor: pixels of the box per pixel of the file (1 for shapes, placeholders and groups). */
        fun factor(l: AlightLayer): Double {
            if (l.kind != AlightLayerKind.SHAPE) return 1.0
            val f = fileOf(l) ?: return 1.0
            val box = l.size ?: return 1.0
            val rw = 2 * box[0]; val rh = 2 * box[1]
            return if (l.mediaFillMode == "fit") min(rw / f.width!!, rh / f.height!!) else max(rw / f.width!!, rh / f.height!!)
        }

        /** Volume of a media layer: a constant 0 mutes it, anything else is the (animated) linear gain. */
        fun volumeOf(l: AlightLayer, durSec: Double): Pair<AnimFloat, Boolean> {
            val g = l.gain ?: return anim(1f) to false
            if (!g.animated && (g.static?.firstOrNull() ?: 1.0) <= 0.0) return anim(1f) to true
            val a = AlightAnimation.build(g, durSec, 1f, stats) { it.getOrElse(0) { 1.0 }.toFloat().coerceAtLeast(0f) + 0f }
            return a to false
        }

        /** An `audio` layer: plays [AlightLayer.inTimeMs] onwards at the layer speed times the enclosing speeds. */
        fun audioLayer(l: AlightLayer, where: String, shiftSec: Double, speed: Double): Layer? {
            val media = l.mediaUri?.let { mediaByUri[it] }
            if (media == null || l.mediaUri !in available) {
                report.add(ReportKind.MISSING_MEDIA, where, "áudio \"${media?.filename ?: l.mediaUri}\" não encontrado: a camada de áudio não foi criada")
                return null
            }
            val dur = (l.endMs - l.startMs) / 1000.0 / speed
            val asset = assetFor(l.mediaUri!!, media, asAudio = true)
            val (vol, muted) = volumeOf(l, dur)
            val own = l.speed?.takeIf { it > 0.0 && it.isFinite() } ?: 1.0
            val id = Ids.newId(TimelineOps.prefixFor(LayerType.AUDIO), taken).also { taken += it }
            report.add(ReportKind.CONVERTED, where, "áudio convertido")
            return Layer(
                id = id, type = LayerType.AUDIO, name = l.label, start = (l.startMs / 1000.0 - shiftSec) / speed, duration = dur,
                audio = MediaContent(asset.id, sourceStart = (l.inTimeMs ?: 0L) / 1000.0, speed = own * speed, volume = vol, muted = muted),
            )
        }

        /** A Kinesis effect with only the parameters that differ from the defaults (the file format leaves those out). */
        fun makeEffect(type: String, colors: Map<String, Color> = emptyMap(), nums: Map<String, AnimFloat> = emptyMap()): Effect {
            var e = Effect.create(type)
            for ((n, c) in colors) {
                val d = (EffectValue.default(e.spec.param(n)) as EffectValue.Col).anim
                val a = anim(c)
                if (a != d) e = e.withColor(n, a)
            }
            for ((n, a) in nums) {
                val d = (EffectValue.default(e.spec.param(n)) as EffectValue.Num).anim
                if (a != d) e = e.withNumber(n, a)
            }
            return e
        }

        /** `bendwarp` → Kinesis `bendWarp`: the amount is the bend angle; the limits give the curved part of the layer's height. */
        fun bendWarp(e: AlightEffect, at: String, dur: Double, boxH: Double): Effect {
            val angle = AlightAnimation.build(e.props["amount"], dur, 0f, stats) { it.getOrElse(0) { 0.0 }.toFloat().coerceIn(-170f, 170f) + 0f }
            val low = (e.props["lowLimit"]?.static?.firstOrNull() ?: 0.25).coerceIn(0.0, 1.0)
            val high = (e.props["highLimit"]?.static?.firstOrNull() ?: 0.75).coerceIn(0.0, 1.0)
            val h = boxH.coerceAtLeast(1.0)
            val length = (abs(high - low) * h).toFloat().coerceAtLeast(1f)
            val offset = (((high + low) / 2 - 0.5) * h).toFloat()
            report.add(ReportKind.APPROXIMATED, at, "bendwarp convertido em bendWarp: ângulo, limites e eixo vertical; originType, offset e oppositeType do Alight não são carregados")
            return makeEffect("bendWarp", nums = mapOf("angle" to angle, "length" to anim(length), "offset" to anim(offset)))
        }

        class Fx(val loc: Animatable<Vec2>, val scale: Animatable<Vec2>, val rot: Animatable<Float>, val effects: List<Effect>, val motionBlur: Boolean)

        private val sampleF: (Animatable<Float>, Double) -> Float = { a, t -> a.at(t) }
        private val sampleV: (Animatable<Vec2>, Double) -> Vec2 = { a, t -> a.at(t) }

        /**
         * The effects of [l] in file order, following `effects_map.json`: mapped to Kinesis effects, baked into the
         * transform ([speed] converts layer time back to Alight time) or reported.
         */
        fun applyEffects(l: AlightLayer, where: String, dur: Double, speed: Double, boxH: Double, loc0: Animatable<Vec2>, scale0: Animatable<Vec2>, rot0: Animatable<Float>): Fx {
            var loc = loc0; var scale = scale0; var rot = rot0
            var motionBlur = false
            val out = ArrayList<Effect>()
            fun num(e: AlightEffect, n: String, def: Double): Double {
                val p = e.props[n] ?: return def
                if (p.animated) report.add(ReportKind.APPROXIMATED, "$where/${e.name}/$n", "$n animado: usado só o primeiro valor")
                return (p.static ?: p.keyframes.firstOrNull()?.value)?.firstOrNull() ?: def
            }
            fun anim1(e: AlightEffect, n: String, def: Float): AnimFloat =
                AlightAnimation.build(e.props[n], dur, def, stats) { it.getOrElse(0) { def.toDouble() }.toFloat() + 0f }
            for (e in l.effects) {
                val at = "$where/effect:${e.name}"
                val rule = AlightEffectsMap.rule(e.name)
                if (rule == null) { report.add(ReportKind.UNSUPPORTED, at, "efeito \"${e.id}\" desconhecido: ignorado"); continue }
                when (rule.action) {
                    "unsupported" -> report.add(ReportKind.UNSUPPORTED, at, "${e.name} não convertido: ${rule.reason ?: "sem equivalente"}")
                    "skip" -> report.add(ReportKind.WARNING, at, rule.reason ?: "${e.name} ignorado")
                    "flag" -> { motionBlur = true; report.add(ReportKind.APPROXIMATED, at, "desfoque de movimento ligado na camada; ${rule.note ?: ""}".trim()) }
                    "map" -> {
                        val fx: Effect? = when (e.name) {
                            "gaussianblur" -> makeEffect("blur", nums = mapOf("radius" to anim1(e, "strength", 0f).mapValues { it * BLUR_PX_PER_STRENGTH }))
                            "hueshift" -> makeEffect("hueShift", nums = mapOf("hue" to anim1(e, "hue", 0f)))
                            "bendwarp" -> bendWarp(e, at, dur, boxH)
                            "swing2" -> {
                                val f = num(e, "freq", 1.0).coerceAtLeast(0.0) * speed
                                if ((e.props["type"]?.static?.firstOrNull() ?: 0.0) != 0.0) report.add(ReportKind.APPROXIMATED, at, "tipo de onda diferente de 0: usada a senoide")
                                makeEffect("swing", nums = mapOf(
                                    "angleA" to anim1(e, "a1", 0f), "angleB" to anim1(e, "a2", 0f),
                                    "frequency" to anim(f.toFloat()), "phase" to anim1(e, "phase", 0f),
                                ))
                            }
                            "scaleassist" -> {
                                val axis = (e.props["axis"]?.static?.firstOrNull() ?: 3.0).toInt()
                                makeEffect("scaleAssist", nums = mapOf("scale" to anim1(e, "scale", 1f))).let { fx0 ->
                                    if (axis == 1) fx0.with("axis", EffectValue.Choice("x")) else if (axis == 2) fx0.with("axis", EffectValue.Choice("y")) else fx0
                                }
                            }
                            "colorize" -> {
                                val t = e.props["tint"]?.static ?: e.props["tint"]?.keyframes?.firstOrNull()?.value
                                val hue = (((t?.getOrNull(0) ?: 0.0) * 360.0) % 360.0 + 360.0) % 360.0
                                makeEffect("colorize", nums = mapOf(
                                    "hue" to anim(hue.toFloat()), "saturation" to anim((t?.getOrNull(1) ?: 1.0).toFloat().coerceIn(0f, 1f)),
                                    "lightness" to anim((t?.getOrNull(2) ?: 0.0).toFloat().coerceIn(-1f, 1f)),
                                ))
                            }
                            "mosaic" -> makeEffect("pixelate", nums = mapOf("size" to anim1(e, "size", 16f).mapValues { it.coerceAtLeast(1f) }))
                            "invert" -> {
                                val on = listOf("invertRed", "invertGreen", "invertBlue").map { e.text[it] != "false" }
                                if (on.all { it }) Effect.create("invert")
                                else if (on.none { it }) null
                                else { report.add(ReportKind.UNSUPPORTED, at, "inversão de só alguns canais (R/G/B) não convertida: o efeito do Kinesis inverte todos"); null }
                            }
                            "solidcolor" -> {
                                if ((e.props["blendMode"]?.static?.firstOrNull() ?: 0.0) != 0.0) report.add(ReportKind.APPROXIMATED, at, "modo de mistura da cor sólida não é carregado")
                                makeEffect("fill", colors = mapOf("color" to argb(e.text["color"] ?: "#ffffffff").let { Color(it.r, it.g, it.b) }), nums = mapOf("amount" to anim1(e, "alpha", 1f)))
                            }
                            else -> { report.add(ReportKind.UNSUPPORTED, at, "${e.name}: regra sem conversão"); null }
                        }
                        if (fx != null) {
                            out += fx
                            report.add(if (e.name == "invert" || e.name == "hueshift") ReportKind.CONVERTED else ReportKind.APPROXIMATED, at, "convertido em ${rule.kinesis}: ${rule.note ?: ""}".trim())
                        }
                    }
                    "bake" -> when (e.name) {
                        "offset" -> {
                            val off = AlightAnimation.build(e.props["offset"], dur, Vec2.ZERO, stats) { Vec2(it.getOrElse(0) { 0.0 }.toFloat() + 0f, it.getOrElse(1) { 0.0 }.toFloat() + 0f) }
                            loc = AlightBake.combine(loc, off, dur, sampleV, sampleV) { a, b -> Vec2(a.x + b.x, a.y + b.y) }
                            report.add(ReportKind.CONVERTED, at, "deslocamento somado à posição da camada")
                            if (e.text["mask"] == "true" || (e.props["feather"]?.static?.firstOrNull() ?: 0.0) != 0.0) report.add(ReportKind.APPROXIMATED, at, "feather/máscara do deslocamento não são carregados")
                        }
                        "oscillate3" -> {
                            val f = num(e, "freq", 1.0).coerceAtLeast(1e-3); val mag = num(e, "mag", 0.0); val ph = Math.toRadians(num(e, "phase", 0.0))
                            val ang = anim1(e, "angle", 0f)
                            if ((e.props["direction"]?.static?.firstOrNull() ?: 0.0) != 0.0) report.add(ReportKind.APPROXIMATED, at, "direção diferente de 0: usado o ângulo")
                            loc = AlightBake.overlay(loc, dur, 1.0 / f, sampleV) { v, t ->
                                val k = mag * sin(2 * PI * f * t * speed + ph); val a = Math.toRadians(ang.at(t).toDouble())
                                Vec2((v.x + k * cos(a)).toFloat(), (v.y + k * sin(a)).toFloat())
                            }
                            report.add(ReportKind.APPROXIMATED, at, "oscilação assada em ${loc.keyframes.size} keyframes de posição (senoide de $mag px, $f Hz; o formato exato do Alight não é documentado)")
                        }
                        else -> report.add(ReportKind.UNSUPPORTED, at, "${e.name}: regra de bake desconhecida")
                    }
                    else -> report.add(ReportKind.WARNING, at, "ação \"${rule.action}\" desconhecida em effects_map.json")
                }
            }
            return Fx(loc, scale, rot, out, motionBlur)
        }

        /** `<shadow>` → `dropShadow` effects. An inside shadow has no Kinesis counterpart. */
        fun shadowEffects(l: AlightLayer, where: String, durSec: Double): List<Effect> = l.shadows.mapIndexedNotNull { i, sh ->
            val at = "$where/shadow${if (l.shadows.size > 1) " ${i + 1}" else ""}"
            if (sh.direction != "outside") { report.add(ReportKind.UNSUPPORTED, at, "sombra \"${sh.direction}\" (interna) não tem equivalente no Kinesis: ignorada"); return@mapIndexedNotNull null }
            val off = sh.offset
            if (off != null && off.animated) report.add(ReportKind.APPROXIMATED, "$at/offset", "deslocamento animado da sombra: usado só o primeiro valor")
            val o = off?.static ?: off?.keyframes?.firstOrNull()?.value
            val ox = o?.getOrNull(0) ?: 0.0; val oy = o?.getOrNull(1) ?: 0.0
            val dist = Math.hypot(ox, oy).toFloat()
            val angle = if (dist > 0f) Math.toDegrees(Math.atan2(oy, ox)).toFloat() else 90f
            val opacity = AlightAnimation.build(sh.opacity, durSec, 1f, stats) { it.getOrElse(0) { 1.0 }.toFloat().coerceIn(0f, 1f) + 0f }
            val size = AlightAnimation.build(sh.size, durSec, 0f, stats) { it.getOrElse(0) { 0.0 }.toFloat().coerceAtLeast(0f) + 0f }
            report.add(ReportKind.APPROXIMATED, at, "sombra convertida em dropShadow: o tamanho do Alight vira a suavidade (o desfoque pode diferir)")
            val c = argb(sh.color)
            makeEffect(
                "dropShadow", colors = mapOf("color" to Color(c.r, c.g, c.b)),
                nums = mapOf("opacity" to opacity, "angle" to anim(angle), "distance" to anim(dist), "softness" to size),
            )
        }

        fun finish() {
            if (stats.elastic > 0) report.add(ReportKind.APPROXIMATED, "easing", "${stats.elastic} keyframes com easing elastic: aproximados por easeOutElastic (os parâmetros do Alight não estão documentados)")
            if (stats.cyclic > 0) report.add(ReportKind.APPROXIMATED, "easing", "${stats.cyclic} keyframes com easing cyclic: ciclos estimados a partir do primeiro pico (0,5 ÷ pico)")
            for (u in stats.unknown) report.add(ReportKind.WARNING, "easing", "easing \"$u\" desconhecido: usado linear")
        }

        /**
         * Layers of [sc] as Kinesis layers. Times become relative to the start of the group that holds them: [shiftSec]
         * is where in the scene's own time the group begins (`inTime`), and [speed] the accumulated playback speed of
         * the enclosing pre-compositions (a layer lasts 1/speed as long). Parent ids are resolved inside this scene
         * instance only, so the ids that repeat across copies of a pre-composition never collide.
         */
        fun layers(sc: AlightScene, shiftSec: Double, speed: Double, depth: Int, path: String = ""): List<Layer> {
            val byId = sc.layers.associateBy { it.id }
            val kinesisId = HashMap<String, String>()
            // A parent may come after its children in the file (an arm listed before the body it hangs from), so every
            // layer's id is reserved before any of them is converted.
            for (l in sc.layers) if (l.kind != AlightLayerKind.AUDIO) {
                kinesisId[l.id] = Ids.newId(TimelineOps.prefixFor(if (l.kind == AlightLayerKind.EMBED) LayerType.GROUP else LayerType.SHAPE), taken).also { taken += it }
            }
            val out = ArrayList<Layer>()
            for (l in sc.layers) {
                val where = "layer:$path${l.label}"
                if (++layerCount > MAX_LAYERS) { report.add(ReportKind.WARNING, where, "limite de $MAX_LAYERS camadas atingido: o resto foi ignorado"); break }
                if (l.kind == AlightLayerKind.AUDIO) { audioLayer(l, where, shiftSec, speed)?.let { out += it }; continue }
                val isEmbed = l.kind == AlightLayerKind.EMBED
                val sub = l.scene
                if (isEmbed && (sub == null || depth >= 24)) { report.add(ReportKind.UNSUPPORTED, where, "pré-composição sem cena válida: ignorada"); continue }
                val boxW: Double; val boxH: Double
                if (isEmbed) { boxW = sub!!.width.toDouble(); boxH = sub.height.toDouble() } else {
                    val box = l.size
                    if (box == null || box.size < 2 || box[0] <= 0 || box[1] <= 0) { report.add(ReportKind.UNSUPPORTED, where, "forma sem tamanho (property size) válido: ignorada"); continue }
                    boxW = 2 * box[0]; boxH = 2 * box[1]
                }
                val parentSrc = l.parentId?.let { byId[it] }
                val parentKid = parentSrc?.let { kinesisId[it.id] }
                if (l.parentId != null && parentKid == null) report.add(ReportKind.WARNING, where, "camada-pai ${l.parentId} não foi convertida: a camada ficou sem pai")
                val fp = if (parentKid != null) factor(parentSrc!!) else 1.0

                val start = (l.startMs / 1000.0 - shiftSec) / speed
                val dur = (l.endMs - l.startMs) / 1000.0 / speed
                val tr = l.transform
                var locA = AlightAnimation.build(tr.location, dur, Vec2.ZERO, stats) { Vec2(it.getOrElse(0) { 0.0 }.toFloat() + 0f, it.getOrElse(1) { 0.0 }.toFloat() + 0f) }
                var scaleA = AlightAnimation.build(tr.scale, dur, Vec2.ONE, stats) { Vec2(it.getOrElse(0) { 1.0 }.toFloat() + 0f, it.getOrElse(1) { 1.0 }.toFloat() + 0f) }
                var rotA = AlightAnimation.build(tr.rotation, dur, 0f, stats) { it.getOrElse(0) { 0.0 }.toFloat() + 0f }
                val opacityA = AlightAnimation.build(tr.opacity, dur, 1f, stats) { it.getOrElse(0) { 1.0 }.toFloat().coerceIn(0f, 1f) }
                val fx = applyEffects(l, where, dur, speed, boxH, locA, scaleA, rotA)
                locA = fx.loc; scaleA = fx.scale; rotA = fx.rot
                val pivotP = tr.pivot
                if (pivotP != null && pivotP.animated) report.add(ReportKind.APPROXIMATED, "$where/pivot", "pivô animado: usado só o primeiro valor")
                val pv = pivotP?.static ?: pivotP?.keyframes?.firstOrNull()?.value
                val pivot = doubleArrayOf(pv?.getOrNull(0) ?: 0.0, pv?.getOrNull(1) ?: 0.0)

                val id = kinesisId.getValue(l.id)
                var f = 1.0
                val common: Layer
                if (isEmbed) {
                    val inSec = (l.inTimeMs ?: 0L) / 1000.0
                    val spd = l.speed?.takeIf { it > 0.0 && it.isFinite() } ?: 1.0
                    val children = layers(sub!!, inSec, speed * spd, depth + 1, "$path${l.label}/").toMutableList()
                    if (argb(sub.background).a > 0.01f) {
                        val bgId = Ids.newId(TimelineOps.prefixFor(LayerType.SHAPE), taken).also { taken += it }
                        children.add(0, Layer(
                            id = bgId, type = LayerType.SHAPE, name = "Fundo", start = 0.0, duration = dur,
                            shape = ShapeContent(kind = ShapeKind.RECTANGLE, size = anim(Vec2(boxW.toFloat(), boxH.toFloat())), fill = FillSpec(color = anim(argb(sub.background)))),
                        ))
                    }
                    if (l.linkId != null) report.add(ReportKind.APPROXIMATED, where, "pré-composição vinculada (link): importada como cópia independente")
                    // A pre-composition shows only what is inside its frame.
                    common = Layer(id = id, type = LayerType.GROUP, name = l.label, layers = children, masks = listOf(Mask(size = anim(Vec2(boxW.toFloat(), boxH.toFloat())))))
                } else {
                    val media = l.mediaUri?.let { mediaByUri[it] }
                    val file = if (l.fillType == "media") fileOf(l) else null
                    if (l.fillType == "media" && media == null) report.add(ReportKind.WARNING, where, "mídia ${l.mediaUri} não está declarada no projeto: placeholder")
                    when {
                        l.fillType == "color" -> {
                            common = Layer(
                                id = id, type = LayerType.SHAPE, name = l.label,
                                shape = ShapeContent(kind = ShapeKind.RECTANGLE, size = anim(Vec2(boxW.toFloat(), boxH.toFloat())), fill = FillSpec(color = anim(Color.WHITE))),
                            )
                            report.add(ReportKind.APPROXIMATED, where, "a cor da forma não está no XML: usado branco")
                        }
                        file != null && media != null -> {
                            f = factor(l)
                            val asset = assetFor(l.mediaUri!!, media)
                            val isVideo = asset.type == AssetType.VIDEO
                            val cropW = boxW / f; val cropH = boxH / f
                            val crop = abs(cropW - file.width!!) > 0.5 || abs(cropH - file.height!!) > 0.5
                            common = Layer(
                                id = id, type = if (isVideo) LayerType.VIDEO else LayerType.IMAGE, name = l.label,
                                image = if (isVideo) null else ImageContent(asset.id),
                                video = if (isVideo) {
                                    val (vol, muted) = volumeOf(l, dur)
                                    val own = l.speed?.takeIf { it > 0.0 && it.isFinite() } ?: 1.0
                                    MediaContent(asset.id, sourceStart = (l.inTimeMs ?: 0L) / 1000.0, speed = own * speed, volume = vol, muted = muted)
                                } else null,
                                masks = if (crop) listOf(Mask(size = anim(Vec2(cropW.toFloat(), cropH.toFloat())))) else emptyList(),
                            )

                        }
                        else -> {
                            common = Layer(
                                id = id, type = LayerType.SHAPE, name = l.label,
                                shape = ShapeContent(kind = ShapeKind.RECTANGLE, size = anim(Vec2(boxW.toFloat(), boxH.toFloat())), fill = FillSpec(color = anim(PLACEHOLDER))),
                            )
                            report.add(ReportKind.MISSING_MEDIA, where, "mídia \"${media?.filename ?: l.mediaUri}\" não encontrada: placeholder")
                        }
                    }
                }

                // Alight: centre at `location`, rotation and scale around `pivot` (which does not move the layer). Kinesis:
                // position is where the anchor lands, so the anchor goes to location + R·S·pivot.
                fun landing(loc: Vec2, sc2: Vec2, rotDeg: Float): Vec2 {
                    val a = Math.toRadians(rotDeg.toDouble())
                    val sx = sc2.x * pivot[0]; val sy = sc2.y * pivot[1]
                    val px = loc.x + sx * cos(a) - sy * sin(a)
                    val py = loc.y + sx * sin(a) + sy * cos(a)
                    return if (parentKid != null) Vec2((px / fp).toFloat(), (py / fp).toFloat())
                    else Vec2((px - sc.width / 2.0).toFloat(), (py - sc.height / 2.0).toFloat())
                }
                val needsBake = (pivot[0] != 0.0 || pivot[1] != 0.0) && (scaleA.keyframes.isNotEmpty() || rotA.keyframes.isNotEmpty())
                val positionA = if (!needsBake) {
                    val s0 = scaleA.value; val r0 = rotA.value
                    locA.mapValues { landing(it, s0, r0) }
                } else {
                    val bake = bakePosition(locA, scaleA, rotA, dur, ::landing)
                    report.add(ReportKind.APPROXIMATED, "$where/location", "posição com pivô e escala/rotação animadas: calculada em ${bake.keyframes.size} keyframes")
                    bake
                }
                if (!isEmbed && l.shapeType != null && l.shapeType != ".rect") {
                    report.add(ReportKind.APPROXIMATED, where, "forma \"${l.shapeType}\" convertida em retângulo")
                }
                val layer = common.copy(
                    start = start, duration = dur, parent = parentKid,
                    effects = common.effects + fx.effects + shadowEffects(l, where, dur),
                    motionBlur = fx.motionBlur,
                    transform = Transform2D(
                        anchor = anim(Vec2((pivot[0] / f).toFloat(), (pivot[1] / f).toFloat())),
                        position = positionA,
                        scale = scaleA.mapValues { Vec2((it.x * f).toFloat(), (it.y * f).toFloat()) },
                        rotation = rotA,
                        opacity = opacityA,
                    ),
                )
                                out += layer
                report.add(ReportKind.CONVERTED, where, if (isEmbed) "pré-composição convertida em grupo (${layer.layers.size} camadas)" else "convertida")
            }
            // A parent that was skipped (no valid size, ...) leaves its children without parent.
            val kept = out.map { it.id }.toSet()
            return out.map { l -> if (l.parent != null && l.parent !in kept) l.copy(parent = null).also { report.add(ReportKind.WARNING, "layer:${path}${l.name}", "a camada-pai não foi convertida: a camada ficou sem pai") } else l }
        }
    }

    private const val MAX_LAYERS = 5000

    /**
     * Alight's blur strength has no documented unit. Calibrated on the fixture's reference frame at 3 s (the blurred
     * background): the smallest error falls at a radius of 50-75 px for strength 0.5, so 140 px per unit of strength.
     */
    private const val BLUR_PX_PER_STRENGTH = 140f

    /**
     * Position when scale or rotation move a pivot that is not the centre: it depends on all three at once, so it is
     * sampled at every keyframe of the three and at a few points between them (linear between samples).
     */
    private fun bakePosition(
        loc: Animatable<Vec2>, scale: Animatable<Vec2>, rot: Animatable<Float>, durationSec: Double,
        landing: (Vec2, Vec2, Float) -> Vec2,
    ): Animatable<Vec2> {
        val marks = (loc.keyframes.map { it.time } + scale.keyframes.map { it.time } + rot.keyframes.map { it.time }).sorted()
        val times = ArrayList<Double>()
        for ((i, t) in marks.withIndex()) {
            if (times.isNotEmpty() && t - times.last() < 2 * Animatable.KEYFRAME_EPSILON) continue
            if (i > 0 && times.isNotEmpty()) {
                val from = times.last()
                val gap = t - from
                val n = min(40, max(1, Math.ceil(gap * 8).toInt()))
                for (k in 1 until n) times += from + gap * k / n
            }
            times += t
        }
        val keys = times.map { t -> Keyframe(t, landing(loc.at(t), scale.at(t), rot.at(t)), Easing.LINEAR) }
        return Animatable(keys.first().value, keys)
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
