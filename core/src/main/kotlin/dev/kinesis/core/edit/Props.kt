package dev.kinesis.core.edit

import dev.kinesis.core.anim.Animatable
import dev.kinesis.core.anim.Easing
import dev.kinesis.core.anim.Interpolators
import dev.kinesis.core.anim.KeyframeOps
import dev.kinesis.core.anim.anim
import dev.kinesis.core.math.Color
import dev.kinesis.core.math.Vec2
import dev.kinesis.core.math.Vec3
import dev.kinesis.core.model.CameraContent
import dev.kinesis.core.model.Effect
import dev.kinesis.core.model.Layer
import dev.kinesis.core.model.LayerType
import dev.kinesis.core.model.Material
import dev.kinesis.core.model.ShapeKind
import dev.kinesis.core.model.StrokeSpec
import dev.kinesis.core.scene.SceneEvaluator

enum class PropUnit { NONE, PX, DEG, PERCENT, EM, MULTIPLIER }

sealed interface PropKind

/**
 * Numeric property. Values are stored as-is and shown multiplied by [display] (e.g. opacity 0..1 shown as 0..100 %).
 * [sliderMin]/[sliderMax] are in stored units.
 */
data class FloatKind(
    val sliderMin: Float,
    val sliderMax: Float,
    val unit: PropUnit = PropUnit.NONE,
    val min: Float = Float.NEGATIVE_INFINITY,
    val max: Float = Float.POSITIVE_INFINITY,
    val display: Float = 1f,
) : PropKind

data class Vec2Kind(val unit: PropUnit = PropUnit.PX, val display: Float = 1f, val sliderMin: Float = -2000f, val sliderMax: Float = 2000f) : PropKind
data class Vec3Kind(val unit: PropUnit = PropUnit.PX, val display: Float = 1f, val sliderMin: Float = -2000f, val sliderMax: Float = 2000f) : PropKind
data object ColorKind : PropKind

/**
 * Lens onto one animatable property of a layer. [id] is stable (e.g. `transform.position`, `effects.0.radius`)
 * and doubles as a label key in the UI.
 */
class AnimProp<T>(
    val id: String,
    val labelKey: String,
    val kind: PropKind,
    val interpolate: (T, T, Float) -> T,
    val get: (Layer) -> Animatable<T>,
    val set: (Layer, Animatable<T>) -> Layer,
) {
    fun valueAt(layer: Layer, localTime: Double): T = get(layer).sample(localTime, interpolate)

    /** Sets the value at [localTime]: updates/creates a keyframe if the property is animated, else the static value. */
    fun setValue(layer: Layer, localTime: Double, value: T): Layer = set(layer, KeyframeOps.setValue(get(layer), localTime, value))

    /** Adds a keyframe with the current value at [localTime], or removes the existing one. */
    fun toggleKeyframe(layer: Layer, localTime: Double): Layer = set(layer, KeyframeOps.toggle(get(layer), localTime, interpolate))

    fun clearKeyframes(layer: Layer, localTime: Double): Layer = set(layer, KeyframeOps.clear(get(layer), localTime, interpolate))

    fun setEasing(layer: Layer, index: Int, easing: Easing): Layer = set(layer, KeyframeOps.setEasing(get(layer), index, easing))

    fun moveKeyframe(layer: Layer, index: Int, newTime: Double): Layer = set(layer, KeyframeOps.move(get(layer), index, newTime))

    fun removeKeyframe(layer: Layer, index: Int, localTime: Double): Layer = set(layer, KeyframeOps.removeAt(get(layer), index, localTime, interpolate))

    fun hasKeyframeAt(layer: Layer, localTime: Double): Boolean = get(layer).keyframeIndexAt(localTime) >= 0

    fun isAnimated(layer: Layer): Boolean = get(layer).isAnimated
}

class PropSection(val id: String, val props: List<AnimProp<*>>)

/** Catalogue of the animatable properties of each layer type, grouped in sections for the properties panel. */
object LayerProps {

    private fun fprop(id: String, kind: FloatKind, get: (Layer) -> Animatable<Float>, set: (Layer, Animatable<Float>) -> Layer, label: String = id) =
        AnimProp(id, label, kind, Interpolators.float, get, set)

    private fun v2prop(id: String, kind: Vec2Kind, get: (Layer) -> Animatable<Vec2>, set: (Layer, Animatable<Vec2>) -> Layer, label: String = id) =
        AnimProp(id, label, kind, Interpolators.vec2, get, set)

    private fun v3prop(id: String, kind: Vec3Kind, get: (Layer) -> Animatable<Vec3>, set: (Layer, Animatable<Vec3>) -> Layer, label: String = id) =
        AnimProp(id, label, kind, Interpolators.vec3, get, set)

    private fun cprop(id: String, get: (Layer) -> Animatable<Color>, set: (Layer, Animatable<Color>) -> Layer, label: String = id) =
        AnimProp(id, label, ColorKind, Interpolators.color, get, set)

    private val OPACITY = FloatKind(0f, 1f, PropUnit.PERCENT, 0f, 1f, display = 100f)
    private val ROTATION = FloatKind(-360f, 360f, PropUnit.DEG)

    fun transform2D(): PropSection = PropSection(
        "transform",
        listOf(
            v2prop("transform.position", Vec2Kind(), { it.transform.position }, { l, a -> l.copy(transform = l.transform.copy(position = a)) }),
            v2prop("transform.scale", Vec2Kind(PropUnit.PERCENT, 100f, 0f, 4f), { it.transform.scale }, { l, a -> l.copy(transform = l.transform.copy(scale = a)) }),
            fprop("transform.rotation", ROTATION, { it.transform.rotation }, { l, a -> l.copy(transform = l.transform.copy(rotation = a)) }),
            fprop("transform.opacity", OPACITY, { it.transform.opacity }, { l, a -> l.copy(transform = l.transform.copy(opacity = a)) }),
            v2prop("transform.anchor", Vec2Kind(), { it.transform.anchor }, { l, a -> l.copy(transform = l.transform.copy(anchor = a)) }),
        ),
    )

    fun transform3D(): PropSection = PropSection(
        "transform3d",
        listOf(
            v3prop("transform3d.position", Vec3Kind(), { it.transform3d.position }, { l, a -> l.copy(transform3d = l.transform3d.copy(position = a)) }),
            v3prop("transform3d.rotation", Vec3Kind(PropUnit.DEG, 1f, -360f, 360f), { it.transform3d.rotation }, { l, a -> l.copy(transform3d = l.transform3d.copy(rotation = a)) }),
            v3prop("transform3d.scale", Vec3Kind(PropUnit.PERCENT, 100f, 0f, 4f), { it.transform3d.scale }, { l, a -> l.copy(transform3d = l.transform3d.copy(scale = a)) }),
            fprop("transform3d.opacity", OPACITY, { it.transform3d.opacity }, { l, a -> l.copy(transform3d = l.transform3d.copy(opacity = a)) }),
            v3prop("transform3d.anchor", Vec3Kind(), { it.transform3d.anchor }, { l, a -> l.copy(transform3d = l.transform3d.copy(anchor = a)) }),
        ),
    )

    private fun materialProps(prefix: String, get: (Layer) -> Material, set: (Layer, Material) -> Layer) = listOf(
        cprop("$prefix.color", { get(it).color }, { l, a -> set(l, get(l).copy(color = a)) }, "material.color"),
        fprop("$prefix.metallic", FloatKind(0f, 1f, PropUnit.PERCENT, 0f, 1f, 100f), { get(it).metallic }, { l, a -> set(l, get(l).copy(metallic = a)) }, "material.metallic"),
        fprop("$prefix.roughness", FloatKind(0f, 1f, PropUnit.PERCENT, 0f, 1f, 100f), { get(it).roughness }, { l, a -> set(l, get(l).copy(roughness = a)) }, "material.roughness"),
        cprop("$prefix.emissive", { get(it).emissive }, { l, a -> set(l, get(l).copy(emissive = a)) }, "material.emissive"),
    )

    /** Sections for [layer], in display order. */
    fun sections(layer: Layer): List<PropSection> = buildList {
        when (layer.type) {
            LayerType.TEXT -> {
                add(transform2D())
                add(
                    PropSection(
                        "text",
                        buildList {
                            add(fprop("text.fontSize", FloatKind(8f, 400f, PropUnit.PX, 1f), { it.text!!.fontSize }, { l, a -> l.copy(text = l.text!!.copy(fontSize = a)) }))
                            add(cprop("text.color", { it.text!!.color }, { l, a -> l.copy(text = l.text!!.copy(color = a)) }))
                            add(fprop("text.letterSpacing", FloatKind(-0.2f, 1f, PropUnit.EM), { it.text!!.letterSpacing }, { l, a -> l.copy(text = l.text!!.copy(letterSpacing = a)) }))
                            if (layer.text?.stroke != null) {
                                add(cprop("text.stroke.color", { it.text!!.stroke!!.color }, { l, a -> l.copy(text = l.text!!.copy(stroke = l.text.stroke!!.copy(color = a))) }, "stroke.color"))
                                add(fprop("text.stroke.width", FloatKind(0f, 40f, PropUnit.PX, 0f), { it.text!!.stroke!!.width }, { l, a -> l.copy(text = l.text!!.copy(stroke = l.text.stroke!!.copy(width = a))) }, "stroke.width"))
                            }
                        },
                    ),
                )
            }
            LayerType.SHAPE -> {
                add(transform2D())
                val kind = layer.shape?.kind
                add(
                    PropSection(
                        "shape",
                        buildList {
                            if (kind != ShapeKind.PATH) {
                                add(v2prop("shape.size", Vec2Kind(PropUnit.PX, 1f, 0f, 2000f), { it.shape!!.size }, { l, a -> l.copy(shape = l.shape!!.copy(size = a)) }))
                            }
                            if (kind == ShapeKind.RECTANGLE || kind == ShapeKind.POLYGON || kind == ShapeKind.STAR) {
                                add(fprop("shape.roundness", FloatKind(0f, 300f, PropUnit.PX, 0f), { it.shape!!.roundness }, { l, a -> l.copy(shape = l.shape!!.copy(roundness = a)) }))
                            }
                            if (kind == ShapeKind.POLYGON || kind == ShapeKind.STAR) {
                                add(fprop("shape.sides", FloatKind(3f, 24f, PropUnit.NONE, 3f, 64f), { it.shape!!.sides }, { l, a -> l.copy(shape = l.shape!!.copy(sides = a)) }))
                            }
                            if (kind == ShapeKind.STAR) {
                                add(fprop("shape.innerRatio", FloatKind(0f, 1f, PropUnit.PERCENT, 0f, 1f, 100f), { it.shape!!.innerRatio }, { l, a -> l.copy(shape = l.shape!!.copy(innerRatio = a)) }))
                            }
                            add(cprop("shape.fill.color", { it.shape!!.fill.color }, { l, a -> l.copy(shape = l.shape!!.copy(fill = l.shape.fill.copy(color = a))) }, "fill.color"))
                            add(cprop("shape.stroke.color", { it.shape!!.stroke.color }, { l, a -> l.copy(shape = l.shape!!.copy(stroke = l.shape.stroke.copy(color = a))) }, "stroke.color"))
                            add(fprop("shape.stroke.width", FloatKind(0f, 60f, PropUnit.PX, 0f), { it.shape!!.stroke.width }, { l, a -> l.copy(shape = l.shape!!.copy(stroke = l.shape.stroke.copy(width = a))) }, "stroke.width"))
                        },
                    ),
                )
                add(
                    PropSection(
                        "trim",
                        listOf(
                            fprop("shape.trim.start", FloatKind(0f, 1f, PropUnit.PERCENT, 0f, 1f, 100f), { it.shape!!.trim.start }, { l, a -> l.copy(shape = l.shape!!.copy(trim = l.shape.trim.copy(start = a))) }),
                            fprop("shape.trim.end", FloatKind(0f, 1f, PropUnit.PERCENT, 0f, 1f, 100f), { it.shape!!.trim.end }, { l, a -> l.copy(shape = l.shape!!.copy(trim = l.shape.trim.copy(end = a))) }),
                            fprop("shape.trim.offset", FloatKind(-1f, 1f, PropUnit.PERCENT, display = 100f), { it.shape!!.trim.offset }, { l, a -> l.copy(shape = l.shape!!.copy(trim = l.shape.trim.copy(offset = a))) }),
                        ),
                    ),
                )
            }
            LayerType.IMAGE, LayerType.GROUP -> add(transform2D())
            LayerType.VIDEO -> {
                add(transform2D())
                add(PropSection("audio", listOf(fprop("video.volume", FloatKind(0f, 2f, PropUnit.PERCENT, 0f, 4f, 100f), { it.video!!.volume }, { l, a -> l.copy(video = l.video!!.copy(volume = a)) }, "media.volume"))))
            }
            LayerType.AUDIO -> add(PropSection("audio", listOf(fprop("audio.volume", FloatKind(0f, 2f, PropUnit.PERCENT, 0f, 4f, 100f), { it.audio!!.volume }, { l, a -> l.copy(audio = l.audio!!.copy(volume = a)) }, "media.volume"))))
            LayerType.OBJECT3D -> {
                add(transform3D())
                add(
                    PropSection(
                        "object3d",
                        listOf(fprop("object3d.size", FloatKind(10f, 1500f, PropUnit.PX, 0f), { it.object3d!!.size }, { l, a -> l.copy(object3d = l.object3d!!.copy(size = a)) }, "object.size")) +
                            materialProps("object3d.material", { it.object3d!!.material }, { l, m -> l.copy(object3d = l.object3d!!.copy(material = m)) }),
                    ),
                )
            }
            LayerType.MODEL3D -> {
                add(transform3D())
                add(
                    PropSection(
                        "model3d",
                        listOf(fprop("model3d.size", FloatKind(10f, 1500f, PropUnit.PX, 0f), { it.model3d!!.size }, { l, a -> l.copy(model3d = l.model3d!!.copy(size = a)) }, "object.size")) +
                            (if (layer.model3d?.material != null) materialProps("model3d.material", { it.model3d!!.material!! }, { l, m -> l.copy(model3d = l.model3d!!.copy(material = m)) }) else emptyList()),
                    ),
                )
            }
            LayerType.CAMERA -> add(
                PropSection(
                    "camera",
                    buildList {
                        if (layer.camera?.position != null) {
                            add(v3prop("camera.position", Vec3Kind(PropUnit.PX, 1f, -5000f, 5000f), { it.camera!!.position!! }, { l, a -> l.copy(camera = l.camera!!.copy(position = a)) }))
                        }
                        add(v3prop("camera.target", Vec3Kind(), { it.camera!!.target }, { l, a -> l.copy(camera = l.camera!!.copy(target = a)) }))
                        add(fprop("camera.fov", FloatKind(10f, 120f, PropUnit.DEG, 1f, 170f), { it.camera!!.fov }, { l, a -> l.copy(camera = l.camera!!.copy(fov = a)) }))
                    },
                ),
            )
            LayerType.LIGHT -> add(
                PropSection(
                    "light",
                    listOf(
                        cprop("light.color", { it.light!!.color }, { l, a -> l.copy(light = l.light!!.copy(color = a)) }),
                        fprop("light.intensity", FloatKind(0f, 3f, PropUnit.MULTIPLIER, 0f), { it.light!!.intensity }, { l, a -> l.copy(light = l.light!!.copy(intensity = a)) }),
                        v3prop("light.position", Vec3Kind(PropUnit.PX, 1f, -5000f, 5000f), { it.light!!.position }, { l, a -> l.copy(light = l.light!!.copy(position = a)) }),
                        v3prop("light.target", Vec3Kind(), { it.light!!.target }, { l, a -> l.copy(light = l.light!!.copy(target = a)) }),
                        fprop("light.radius", FloatKind(100f, 10000f, PropUnit.PX, 1f), { it.light!!.radius }, { l, a -> l.copy(light = l.light!!.copy(radius = a)) }),
                    ),
                ),
            )
        }
        layer.effects.forEachIndexed { i, e -> add(PropSection("effects.$i", effectProps(i, e))) }
        layer.masks.forEachIndexed { i, _ -> add(PropSection("masks.$i", maskProps(i))) }
    }

    /** Every animatable property of [layer] (all sections flattened). */
    fun all(layer: Layer): List<AnimProp<*>> = sections(layer).flatMap { it.props }

    fun find(layer: Layer, id: String): AnimProp<*>? = all(layer).firstOrNull { it.id == id }

    private fun effectProps(i: Int, e: Effect): List<AnimProp<*>> {
        fun upd(l: Layer, f: (Effect) -> Effect) = l.copy(effects = l.effects.mapIndexed { j, x -> if (j == i) f(x) else x })
        fun <E : Effect> eff(l: Layer): E {
            @Suppress("UNCHECKED_CAST")
            return l.effects[i] as E
        }
        val p = "effects.$i"
        return when (e) {
            is Effect.Blur -> listOf(fprop("$p.radius", FloatKind(0f, 100f, PropUnit.PX, 0f), { eff<Effect.Blur>(it).radius }, { l, a -> upd(l) { (it as Effect.Blur).copy(radius = a) } }, "effect.radius"))
            is Effect.ColorAdjust -> listOf(
                fprop("$p.brightness", FloatKind(-1f, 1f, PropUnit.PERCENT, -1f, 1f, 100f), { eff<Effect.ColorAdjust>(it).brightness }, { l, a -> upd(l) { (it as Effect.ColorAdjust).copy(brightness = a) } }, "effect.brightness"),
                fprop("$p.contrast", FloatKind(-1f, 1f, PropUnit.PERCENT, -1f, 1f, 100f), { eff<Effect.ColorAdjust>(it).contrast }, { l, a -> upd(l) { (it as Effect.ColorAdjust).copy(contrast = a) } }, "effect.contrast"),
                fprop("$p.saturation", FloatKind(-1f, 1f, PropUnit.PERCENT, -1f, 1f, 100f), { eff<Effect.ColorAdjust>(it).saturation }, { l, a -> upd(l) { (it as Effect.ColorAdjust).copy(saturation = a) } }, "effect.saturation"),
                fprop("$p.hue", FloatKind(-180f, 180f, PropUnit.DEG), { eff<Effect.ColorAdjust>(it).hue }, { l, a -> upd(l) { (it as Effect.ColorAdjust).copy(hue = a) } }, "effect.hue"),
            )
            is Effect.Tint -> listOf(
                cprop("$p.color", { eff<Effect.Tint>(it).color }, { l, a -> upd(l) { (it as Effect.Tint).copy(color = a) } }, "effect.color"),
                fprop("$p.amount", FloatKind(0f, 1f, PropUnit.PERCENT, 0f, 1f, 100f), { eff<Effect.Tint>(it).amount }, { l, a -> upd(l) { (it as Effect.Tint).copy(amount = a) } }, "effect.amount"),
            )
            is Effect.DropShadow -> listOf(
                cprop("$p.color", { eff<Effect.DropShadow>(it).color }, { l, a -> upd(l) { (it as Effect.DropShadow).copy(color = a) } }, "effect.color"),
                fprop("$p.opacity", OPACITY, { eff<Effect.DropShadow>(it).opacity }, { l, a -> upd(l) { (it as Effect.DropShadow).copy(opacity = a) } }, "effect.opacity"),
                fprop("$p.angle", FloatKind(0f, 360f, PropUnit.DEG), { eff<Effect.DropShadow>(it).angle }, { l, a -> upd(l) { (it as Effect.DropShadow).copy(angle = a) } }, "effect.angle"),
                fprop("$p.distance", FloatKind(0f, 100f, PropUnit.PX), { eff<Effect.DropShadow>(it).distance }, { l, a -> upd(l) { (it as Effect.DropShadow).copy(distance = a) } }, "effect.distance"),
                fprop("$p.softness", FloatKind(0f, 100f, PropUnit.PX, 0f), { eff<Effect.DropShadow>(it).softness }, { l, a -> upd(l) { (it as Effect.DropShadow).copy(softness = a) } }, "effect.softness"),
            )
            is Effect.Glow -> listOf(
                cprop("$p.color", { eff<Effect.Glow>(it).color }, { l, a -> upd(l) { (it as Effect.Glow).copy(color = a) } }, "effect.color"),
                fprop("$p.radius", FloatKind(0f, 100f, PropUnit.PX, 0f), { eff<Effect.Glow>(it).radius }, { l, a -> upd(l) { (it as Effect.Glow).copy(radius = a) } }, "effect.radius"),
                fprop("$p.intensity", FloatKind(0f, 3f, PropUnit.MULTIPLIER, 0f), { eff<Effect.Glow>(it).intensity }, { l, a -> upd(l) { (it as Effect.Glow).copy(intensity = a) } }, "effect.intensity"),
            )
            is Effect.Invert -> listOf(fprop("$p.amount", FloatKind(0f, 1f, PropUnit.PERCENT, 0f, 1f, 100f), { eff<Effect.Invert>(it).amount }, { l, a -> upd(l) { (it as Effect.Invert).copy(amount = a) } }, "effect.amount"))
            is Effect.Pixelate -> listOf(fprop("$p.size", FloatKind(1f, 100f, PropUnit.PX, 1f), { eff<Effect.Pixelate>(it).size }, { l, a -> upd(l) { (it as Effect.Pixelate).copy(size = a) } }, "effect.size"))
            is Effect.ChromaticAberration -> listOf(
                fprop("$p.amount", FloatKind(0f, 40f, PropUnit.PX), { eff<Effect.ChromaticAberration>(it).amount }, { l, a -> upd(l) { (it as Effect.ChromaticAberration).copy(amount = a) } }, "effect.amount"),
                fprop("$p.angle", FloatKind(0f, 360f, PropUnit.DEG), { eff<Effect.ChromaticAberration>(it).angle }, { l, a -> upd(l) { (it as Effect.ChromaticAberration).copy(angle = a) } }, "effect.angle"),
            )
            is Effect.Vignette -> listOf(
                fprop("$p.amount", FloatKind(0f, 1f, PropUnit.PERCENT, 0f, 1f, 100f), { eff<Effect.Vignette>(it).amount }, { l, a -> upd(l) { (it as Effect.Vignette).copy(amount = a) } }, "effect.amount"),
                fprop("$p.softness", FloatKind(0.01f, 1f, PropUnit.PERCENT, 0.01f, 1f, 100f), { eff<Effect.Vignette>(it).softness }, { l, a -> upd(l) { (it as Effect.Vignette).copy(softness = a) } }, "effect.softness"),
            )
        }
    }

    private fun maskProps(i: Int): List<AnimProp<*>> {
        fun upd(l: Layer, f: (dev.kinesis.core.model.Mask) -> dev.kinesis.core.model.Mask) = l.copy(masks = l.masks.mapIndexed { j, m -> if (j == i) f(m) else m })
        val p = "masks.$i"
        return listOf(
            v2prop("$p.position", Vec2Kind(), { it.masks[i].position }, { l, a -> upd(l) { m -> m.copy(position = a) } }, "mask.position"),
            v2prop("$p.size", Vec2Kind(PropUnit.PX, 1f, 0f, 2000f), { it.masks[i].size }, { l, a -> upd(l) { m -> m.copy(size = a) } }, "mask.size"),
            fprop("$p.roundness", FloatKind(0f, 300f, PropUnit.PX, 0f), { it.masks[i].roundness }, { l, a -> upd(l) { m -> m.copy(roundness = a) } }, "mask.roundness"),
            fprop("$p.feather", FloatKind(0f, 200f, PropUnit.PX, 0f), { it.masks[i].feather }, { l, a -> upd(l) { m -> m.copy(feather = a) } }, "mask.feather"),
            fprop("$p.opacity", OPACITY, { it.masks[i].opacity }, { l, a -> upd(l) { m -> m.copy(opacity = a) } }, "mask.opacity"),
        )
    }

    /** Converts a camera with automatic position into one with an explicit (animatable) position. */
    fun withExplicitCameraPosition(layer: Layer, compHeight: Int): Layer {
        val cam = layer.camera ?: CameraContent()
        if (cam.position != null) return layer
        val d = SceneEvaluator.defaultCameraDistance(compHeight, cam.fov.value)
        return layer.copy(camera = cam.copy(position = anim(Vec3(0f, 0f, -d))))
    }

    /** Adds a stroke so text stroke properties can be edited. */
    fun withTextStroke(layer: Layer): Layer =
        if (layer.text == null || layer.text.stroke != null) layer
        else layer.copy(text = layer.text.copy(stroke = StrokeSpec(color = anim(Color.BLACK), width = anim(4f))))

    @Suppress("UNCHECKED_CAST")
    fun <T> typed(prop: AnimProp<*>): AnimProp<T> = prop as AnimProp<T>
}
