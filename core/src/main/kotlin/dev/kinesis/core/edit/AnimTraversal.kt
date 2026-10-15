package dev.kinesis.core.edit

import dev.kinesis.core.anim.Animatable
import dev.kinesis.core.anim.Interpolators
import dev.kinesis.core.math.Color
import dev.kinesis.core.math.Vec2
import dev.kinesis.core.math.Vec3
import dev.kinesis.core.model.EffectValue
import dev.kinesis.core.model.Layer
import dev.kinesis.core.model.Material

/** Transforms an animatable of any value type; [interpolate] is the matching interpolator. */
interface AnimMapper {
    fun <T> map(anim: Animatable<T>, interpolate: (T, T, Float) -> T): Animatable<T>
}

/** Applies [m] to every animatable property of a layer (not to its children). */
fun Layer.mapAnimatables(m: AnimMapper): Layer {
    fun f(a: Animatable<Float>) = m.map(a, Interpolators.float)
    fun v2(a: Animatable<Vec2>) = m.map(a, Interpolators.vec2)
    fun v3(a: Animatable<Vec3>) = m.map(a, Interpolators.vec3)
    fun c(a: Animatable<Color>) = m.map(a, Interpolators.color)
    fun mat(x: Material) = x.copy(color = c(x.color), metallic = f(x.metallic), roughness = f(x.roughness), emissive = c(x.emissive))

    return copy(
        transform = transform.copy(
            anchor = v2(transform.anchor), position = v2(transform.position), scale = v2(transform.scale),
            rotation = f(transform.rotation), opacity = f(transform.opacity),
        ),
        transform3d = transform3d.copy(
            anchor = v3(transform3d.anchor), position = v3(transform3d.position), rotation = v3(transform3d.rotation),
            scale = v3(transform3d.scale), opacity = f(transform3d.opacity),
        ),
        masks = masks.map {
            it.copy(position = v2(it.position), size = v2(it.size), roundness = f(it.roundness), feather = f(it.feather), opacity = f(it.opacity))
        },
        effects = effects.map { e ->
            e.copy(params = e.params.mapValues { (_, v) ->
                when (v) {
                    is EffectValue.Num -> EffectValue.Num(f(v.anim))
                    is EffectValue.Col -> EffectValue.Col(c(v.anim))
                    is EffectValue.Point -> EffectValue.Point(v2(v.anim))
                    else -> v
                }
            })
        },
        text = text?.let { t ->
            t.copy(
                fontSize = f(t.fontSize), color = c(t.color), letterSpacing = f(t.letterSpacing),
                stroke = t.stroke?.let { s -> s.copy(color = c(s.color), width = f(s.width)) },
            )
        },
        shape = shape?.let { s ->
            s.copy(
                size = v2(s.size), roundness = f(s.roundness), sides = f(s.sides), innerRatio = f(s.innerRatio),
                fill = s.fill.copy(color = c(s.fill.color)),
                stroke = s.stroke.copy(color = c(s.stroke.color), width = f(s.stroke.width)),
                trim = s.trim.copy(start = f(s.trim.start), end = f(s.trim.end), offset = f(s.trim.offset)),
            )
        },
        video = video?.let { it.copy(volume = f(it.volume)) },
        audio = audio?.let { it.copy(volume = f(it.volume)) },
        object3d = object3d?.let { it.copy(size = f(it.size), material = mat(it.material)) },
        model3d = model3d?.let { it.copy(size = f(it.size), material = it.material?.let(::mat)) },
        camera = camera?.let { it.copy(position = it.position?.let(::v3), target = v3(it.target), fov = f(it.fov)) },
        light = light?.let {
            it.copy(color = c(it.color), intensity = f(it.intensity), position = v3(it.position), target = v3(it.target), radius = f(it.radius))
        },
    )
}

/** Collects every animatable of a layer (used for keyframe overviews in the timeline). */
fun Layer.allAnimatables(): List<Animatable<*>> {
    val out = ArrayList<Animatable<*>>()
    mapAnimatables(object : AnimMapper {
        override fun <T> map(anim: Animatable<T>, interpolate: (T, T, Float) -> T): Animatable<T> {
            out += anim
            return anim
        }
    })
    return out
}

/** Sorted, de-duplicated keyframe times (layer-local) across all properties of a layer. */
fun Layer.keyframeTimes(): List<Double> {
    val times = allAnimatables().flatMap { a -> a.keyframes.map { it.time } }.sorted()
    val out = ArrayList<Double>()
    for (t in times) if (out.isEmpty() || t - out.last() > Animatable.KEYFRAME_EPSILON) out += t
    return out
}
