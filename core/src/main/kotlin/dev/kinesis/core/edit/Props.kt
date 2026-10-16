package dev.kinesis.core.edit

import dev.kinesis.core.anim.Animatable
import dev.kinesis.core.anim.Easing
import dev.kinesis.core.anim.Interpolators
import dev.kinesis.core.anim.KeyframeOps
import dev.kinesis.core.anim.anim
import dev.kinesis.core.anim.at
import dev.kinesis.core.math.Color
import dev.kinesis.core.math.Mat4
import dev.kinesis.core.math.Vec2
import dev.kinesis.core.math.Vec3
import dev.kinesis.core.model.CameraAim
import dev.kinesis.core.model.CameraContent
import dev.kinesis.core.model.DepthOfField
import dev.kinesis.core.model.Effect
import dev.kinesis.core.model.EffectParam
import dev.kinesis.core.model.EffectRegistry
import dev.kinesis.core.model.ParamType
import dev.kinesis.core.model.ParamUnit
import dev.kinesis.core.model.Layer
import dev.kinesis.core.model.LayerType
import dev.kinesis.core.model.Material
import dev.kinesis.core.model.ShapeKind
import dev.kinesis.core.model.StrokeSpec
import dev.kinesis.core.scene.SceneEvaluator

enum class PropUnit {
    NONE, PX, DEG, PERCENT, EM, MULTIPLIER, SECONDS, HERTZ, FPS, DEG_PER_SECOND, EV;

    companion object {
        fun of(u: ParamUnit): PropUnit = when (u) {
            ParamUnit.NONE -> NONE
            ParamUnit.PX -> PX
            ParamUnit.DEG -> DEG
            ParamUnit.PERCENT -> PERCENT
            ParamUnit.MULTIPLIER -> MULTIPLIER
            ParamUnit.SECONDS -> SECONDS
            ParamUnit.HERTZ -> HERTZ
            ParamUnit.FPS -> FPS
            ParamUnit.DEG_PER_SECOND -> DEG_PER_SECOND
            ParamUnit.EV -> EV
        }
    }
}

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
    /** Values are whole numbers (counts, seeds). */
    val integer: Boolean = false,
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

    fun transform2D(withOpacity: Boolean = true): PropSection = PropSection(
        "transform",
        listOfNotNull<AnimProp<*>>(
            v2prop("transform.position", Vec2Kind(), { it.transform.position }, { l, a -> l.copy(transform = l.transform.copy(position = a)) }),
            v2prop("transform.scale", Vec2Kind(PropUnit.PERCENT, 100f, 0f, 4f), { it.transform.scale }, { l, a -> l.copy(transform = l.transform.copy(scale = a)) }),
            fprop("transform.rotation", ROTATION, { it.transform.rotation }, { l, a -> l.copy(transform = l.transform.copy(rotation = a)) }),
            if (withOpacity) fprop("transform.opacity", OPACITY, { it.transform.opacity }, { l, a -> l.copy(transform = l.transform.copy(opacity = a)) }) else null,
            v2prop("transform.anchor", Vec2Kind(), { it.transform.anchor }, { l, a -> l.copy(transform = l.transform.copy(anchor = a)) }),
        ),
    )

    fun transform3D(withOpacity: Boolean = true): PropSection = PropSection(
        "transform3d",
        listOfNotNull<AnimProp<*>>(
            v3prop("transform3d.position", Vec3Kind(), { it.transform3d.position }, { l, a -> l.copy(transform3d = l.transform3d.copy(position = a)) }),
            v3prop("transform3d.rotation", Vec3Kind(PropUnit.DEG, 1f, -360f, 360f), { it.transform3d.rotation }, { l, a -> l.copy(transform3d = l.transform3d.copy(rotation = a)) }),
            v3prop("transform3d.scale", Vec3Kind(PropUnit.PERCENT, 100f, 0f, 4f), { it.transform3d.scale }, { l, a -> l.copy(transform3d = l.transform3d.copy(scale = a)) }),
            if (withOpacity) fprop("transform3d.opacity", OPACITY, { it.transform3d.opacity }, { l, a -> l.copy(transform3d = l.transform3d.copy(opacity = a)) }) else null,
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
            LayerType.NULL -> add(if (layer.is3DLayer) transform3D(withOpacity = false) else transform2D(withOpacity = false))
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
                        val cam = layer.camera
                        if (cam?.position != null) {
                            add(v3prop("camera.position", Vec3Kind(PropUnit.PX, 1f, -5000f, 5000f), { it.camera!!.position!! }, { l, a -> l.copy(camera = l.camera!!.copy(position = a)) }))
                        }
                        if (cam?.aim == CameraAim.ROTATION) {
                            add(v3prop("camera.rotation", Vec3Kind(PropUnit.DEG, 1f, -360f, 360f), { it.camera!!.rotation }, { l, a -> l.copy(camera = l.camera!!.copy(rotation = a)) }))
                        } else {
                            add(v3prop("camera.target", Vec3Kind(), { it.camera!!.target }, { l, a -> l.copy(camera = l.camera!!.copy(target = a)) }))
                        }
                        add(fprop("camera.fov", FloatKind(10f, 120f, PropUnit.DEG, 1f, 170f), { it.camera!!.fov }, { l, a -> l.copy(camera = l.camera!!.copy(fov = a)) }))
                        if (cam?.dof != null) {
                            fun dof(l: Layer, f: (DepthOfField) -> DepthOfField) = l.copy(camera = l.camera!!.copy(dof = f(l.camera.dof!!)))
                            add(fprop("camera.dof.focusDistance", FloatKind(100f, 8000f, PropUnit.PX, 1f), { it.camera!!.dof!!.focusDistance }, { l, a -> dof(l) { it.copy(focusDistance = a) } }))
                            add(fprop("camera.dof.range", FloatKind(0f, 3000f, PropUnit.PX, 0f), { it.camera!!.dof!!.range }, { l, a -> dof(l) { it.copy(range = a) } }))
                            add(fprop("camera.dof.blur", FloatKind(0f, 80f, PropUnit.PX, 0f, 200f), { it.camera!!.dof!!.blur }, { l, a -> dof(l) { it.copy(blur = a) } }))
                        }
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
                    ) + (
                        if (layer.light?.castShadows == true) listOf(
                            fprop("light.shadowDarkness", FloatKind(0f, 1f, PropUnit.PERCENT, 0f, 1f, 100f), { it.light!!.shadowDarkness }, { l, a -> l.copy(light = l.light!!.copy(shadowDarkness = a)) }),
                        ) else emptyList()
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

    /**
     * Animatable parameters of the effect at index [i], built from [EffectRegistry]. Label keys are
     * `fx.<type>.<param>`; the UI resolves them with [EffectRegistry]. Static parameters (switches, choices,
     * gradient stops) are edited directly on the [Effect].
     */
    private fun effectProps(i: Int, e: Effect): List<AnimProp<*>> {
        val spec = EffectRegistry.get(e.type) ?: return emptyList()
        fun upd(l: Layer, f: (Effect) -> Effect) = l.copy(effects = l.effects.mapIndexed { j, x -> if (j == i) f(x) else x })
        return spec.params.filter { it.type.animatable }.map { p ->
            val id = "effects.$i.${p.name}"
            val label = "fx.${spec.id}.${p.name}"
            when (p.type) {
                ParamType.COLOR -> cprop(id, { it.effects[i].color(p.name) }, { l, a -> upd(l) { it.withColor(p.name, a) } }, label)
                ParamType.POINT -> v2prop(id, Vec2Kind(PropUnit.PX, 1f, p.sliderMin ?: -2000f, p.sliderMax ?: 2000f), { it.effects[i].point(p.name) }, { l, a -> upd(l) { it.withPoint(p.name, a) } }, label)
                else -> fprop(id, floatKind(p), { it.effects[i].number(p.name) }, { l, a -> upd(l) { it.withNumber(p.name, a) } }, label)
            }
        }
    }

    fun floatKind(p: EffectParam): FloatKind = FloatKind(
        sliderMin = p.sliderMin ?: p.min ?: 0f,
        sliderMax = p.sliderMax ?: p.max ?: 100f,
        unit = PropUnit.of(p.unit),
        min = p.min ?: Float.NEGATIVE_INFINITY,
        max = p.max ?: Float.POSITIVE_INFINITY,
        display = p.unit.display,
        integer = p.type == ParamType.INTEGER,
    )

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

    /**
     * Switches how a camera is aimed (point of interest ↔ own rotation) without changing the view at layer time
     * [local]. The values of the aim that stops being used are discarded; the new ones start static.
     */
    fun withCameraAim(layer: Layer, aim: CameraAim, local: Double, compHeight: Int): Layer {
        val cam0 = layer.camera ?: return layer
        if (cam0.aim == aim) return layer
        val explicit = withExplicitCameraPosition(layer, compHeight)
        val cam = explicit.camera!!
        val pos = cam.position!!.at(local)
        val distance = SceneEvaluator.defaultCameraDistance(compHeight, cam.fov.at(local))
        return explicit.copy(
            camera = if (aim == CameraAim.ROTATION) {
                val view = Mat4.lookAt(pos, cam.target.at(local), Vec3(0f, -1f, 0f))
                // View rows are right, up and -forward; the camera's own axes are right, down and forward.
                val e = Mat4.eulerXYZ(
                    view[0, 0], view[0, 1], view[0, 2],
                    -view[1, 0], -view[1, 1], -view[1, 2],
                    -view[2, 0], -view[2, 1], -view[2, 2],
                )
                cam.copy(aim = CameraAim.ROTATION, rotation = anim(e))
            } else {
                val forward = Mat4.rotationXYZ(cam.rotation.at(local)).transformVector(Vec3(0f, 0f, 1f))
                cam.copy(aim = CameraAim.TARGET, target = anim(pos + forward * distance))
            },
        )
    }

    /** Adds a stroke so text stroke properties can be edited. */
    fun withTextStroke(layer: Layer): Layer =
        if (layer.text == null || layer.text.stroke != null) layer
        else layer.copy(text = layer.text.copy(stroke = StrokeSpec(color = anim(Color.BLACK), width = anim(4f))))

    @Suppress("UNCHECKED_CAST")
    fun <T> typed(prop: AnimProp<*>): AnimProp<T> = prop as AnimProp<T>
}
