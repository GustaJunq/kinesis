package dev.kinesis.core.model

import dev.kinesis.core.anim.Animatable
import dev.kinesis.core.anim.anim
import dev.kinesis.core.format.AnimatableSerializer
import dev.kinesis.core.format.ColorSerializer
import dev.kinesis.core.format.Vec2Serializer
import dev.kinesis.core.math.Color
import dev.kinesis.core.math.Vec2
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject

/** Value of one effect parameter; the variant matches [ParamType]. */
sealed interface EffectValue {
    data class Num(val anim: Animatable<Float>) : EffectValue
    data class Col(val anim: Animatable<Color>) : EffectValue
    data class Point(val anim: Animatable<Vec2>) : EffectValue
    data class Flag(val value: Boolean) : EffectValue
    data class Choice(val value: String) : EffectValue
    data class Stops(val value: List<GradientStop>) : EffectValue

    companion object {
        fun default(p: EffectParam): EffectValue = when (p.type) {
            ParamType.NUMBER, ParamType.INTEGER -> Num(anim(p.defaultFloat))
            ParamType.COLOR -> Col(anim(p.defaultColor))
            ParamType.POINT -> Point(anim(p.defaultPoint))
            ParamType.BOOLEAN -> Flag(p.defaultBoolean)
            ParamType.CHOICE -> Choice(p.defaultChoice)
            ParamType.STOPS -> Stops(p.defaultStops)
        }
    }
}

/**
 * A visual effect applied, in order, to the rendered layer (in composition space). [type] is an id of
 * [EffectRegistry]; [params] holds the values that were set — missing parameters take the registry default.
 * Serialized flat: `{"type": "blur", "radius": 12}`.
 */
@Serializable(with = EffectSerializer::class)
data class Effect(
    val type: String,
    val enabled: Boolean = true,
    val params: Map<String, EffectValue> = emptyMap(),
    /** Name given by the user (format v12), used by `effect("name")` in expressions; null = the effect's default name. */
    val name: String? = null,
) {
    val spec: EffectSpec get() = EffectRegistry.require(type)

    fun withName(name: String?) = copy(name = name?.takeIf { it.isNotBlank() })

    fun withEnabled(enabled: Boolean) = copy(enabled = enabled)

    fun value(name: String): EffectValue = params[name] ?: EffectValue.default(spec.param(name))

    fun number(name: String): Animatable<Float> = (value(name) as EffectValue.Num).anim
    fun color(name: String): Animatable<Color> = (value(name) as EffectValue.Col).anim
    fun point(name: String): Animatable<Vec2> = (value(name) as EffectValue.Point).anim
    fun flag(name: String): Boolean = (value(name) as EffectValue.Flag).value
    fun choice(name: String): String = (value(name) as EffectValue.Choice).value
    fun stops(name: String): List<GradientStop> = (value(name) as EffectValue.Stops).value

    fun with(name: String, value: EffectValue): Effect {
        spec.param(name)
        return copy(params = params + (name to value))
    }

    fun withNumber(name: String, a: Animatable<Float>) = with(name, EffectValue.Num(a))
    fun withColor(name: String, a: Animatable<Color>) = with(name, EffectValue.Col(a))
    fun withPoint(name: String, a: Animatable<Vec2>) = with(name, EffectValue.Point(a))

    companion object {
        /** A new effect with every parameter at its default. */
        fun create(type: String): Effect = Effect(EffectRegistry.require(type).id)

        /** Convenience for code and tests: `Effect.of("blur", "radius" to 6f)`. */
        fun of(type: String, vararg values: Pair<String, Any>): Effect {
            var e = create(type)
            for ((name, v) in values) {
                e = e.with(
                    name,
                    when (v) {
                        is Number -> EffectValue.Num(anim(v.toFloat()))
                        is Color -> EffectValue.Col(anim(v))
                        is Vec2 -> EffectValue.Point(anim(v))
                        is Boolean -> EffectValue.Flag(v)
                        is String -> EffectValue.Choice(v)
                        is EffectValue -> v
                        else -> throw IllegalArgumentException("unsupported value for $type.$name: $v")
                    },
                )
            }
            return e
        }
    }
}

/** Writes and reads effects through [EffectRegistry]. Values equal to the default are not written. */
object EffectSerializer : KSerializer<Effect> {
    override val descriptor: SerialDescriptor = JsonElement.serializer().descriptor

    private val floatAnim = AnimatableSerializer(Float.serializer())
    private val colorAnim = AnimatableSerializer(ColorSerializer)
    private val pointAnim = AnimatableSerializer(Vec2Serializer)
    private val stopsSerializer = ListSerializer(GradientStop.serializer())

    override fun serialize(encoder: Encoder, value: Effect) {
        val json = (encoder as JsonEncoder).json
        val spec = value.spec
        val obj = buildJsonObject {
            put("type", JsonPrimitive(value.type))
            if (!value.enabled) put("enabled", JsonPrimitive(false))
            value.name?.let { put("name", JsonPrimitive(it)) }
            for (p in spec.params) {
                val v = value.params[p.name] ?: continue
                if (v == EffectValue.default(p)) continue
                val e: JsonElement = when (v) {
                    is EffectValue.Num -> json.encodeToJsonElement(floatAnim, if (p.type == ParamType.INTEGER) roundInts(v.anim) else v.anim)
                    is EffectValue.Col -> json.encodeToJsonElement(colorAnim, v.anim)
                    is EffectValue.Point -> json.encodeToJsonElement(pointAnim, v.anim)
                    is EffectValue.Flag -> JsonPrimitive(v.value)
                    is EffectValue.Choice -> JsonPrimitive(v.value)
                    is EffectValue.Stops -> json.encodeToJsonElement(stopsSerializer, v.value)
                }
                put(p.name, e)
            }
        }
        encoder.encodeJsonElement(obj)
    }

    private fun roundInts(a: Animatable<Float>): Animatable<Float> =
        a.copy(value = Math.round(a.value).toFloat(), keyframes = a.keyframes.map { it.copy(value = Math.round(it.value).toFloat()) })

    override fun deserialize(decoder: Decoder): Effect {
        val json = (decoder as JsonDecoder).json
        val obj = decoder.decodeJsonElement() as? JsonObject ?: throw SerializationException("an effect must be an object")
        val type = (obj["type"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: throw SerializationException("effect is missing \"type\"")
        val spec = EffectRegistry.get(type)
            ?: throw SerializationException("unknown effect type '$type'. Valid types: ${EffectRegistry.all.joinToString { it.id }}")
        val enabled = obj["enabled"]?.let { (it as? JsonPrimitive)?.booleanOrNull ?: throw SerializationException("$type.enabled must be true or false") } ?: true
        val name = obj["name"]?.let { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content ?: throw SerializationException("$type.name must be a string") }
            ?.trim()?.takeIf { it.isNotEmpty() }
        val params = LinkedHashMap<String, EffectValue>()
        for (p in spec.params) {
            val e = obj[p.name] ?: continue
            params[p.name] = try {
                when (p.type) {
                    ParamType.NUMBER, ParamType.INTEGER -> EffectValue.Num(json.decodeFromJsonElement(floatAnim, e))
                    ParamType.COLOR -> EffectValue.Col(json.decodeFromJsonElement(colorAnim, e))
                    ParamType.POINT -> EffectValue.Point(json.decodeFromJsonElement(pointAnim, e))
                    ParamType.BOOLEAN -> EffectValue.Flag((e as? JsonPrimitive)?.booleanOrNull ?: throw SerializationException("expected true or false"))
                    ParamType.CHOICE -> {
                        val s = (e as? JsonPrimitive)?.takeIf { it.isString }?.content
                        if (s == null || p.choices.none { it.id == s }) {
                            throw SerializationException("expected one of ${p.choices.joinToString { it.id }}")
                        }
                        EffectValue.Choice(s)
                    }
                    ParamType.STOPS -> EffectValue.Stops(json.decodeFromJsonElement(stopsSerializer, e))
                }
            } catch (ex: SerializationException) {
                throw SerializationException("$type.${p.name}: ${ex.message}", ex)
            }
        }
        return Effect(type, enabled, params, name)
    }
}
