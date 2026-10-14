package dev.kinesis.core.format

import dev.kinesis.core.anim.Animatable
import dev.kinesis.core.anim.Easing
import dev.kinesis.core.anim.EasingPreset
import dev.kinesis.core.anim.Keyframe
import dev.kinesis.core.anim.LoopMode
import dev.kinesis.core.math.Color
import dev.kinesis.core.math.Vec2
import dev.kinesis.core.math.Vec3
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.put

private fun JsonElement.number(what: String): Float {
    val p = this as? JsonPrimitive
    if (p == null || p.isString) throw SerializationException("$what: expected a number, got $this")
    return p.floatOrNull?.takeIf { it.isFinite() } ?: throw SerializationException("$what: expected a finite number, got $this")
}

private fun Float.toJson(): JsonPrimitive {
    // Write integral values without a trailing ".0" to keep files tidy.
    val asLong = toLong()
    return if (asLong.toFloat() == this && kotlin.math.abs(this) < 1e9f) JsonPrimitive(asLong) else JsonPrimitive(this)
}

private fun Double.toJson(): JsonPrimitive {
    val asLong = toLong()
    return if (asLong.toDouble() == this && kotlin.math.abs(this) < 1e12) JsonPrimitive(asLong) else JsonPrimitive(this)
}

/** Writes integral numbers without a trailing ".0" (e.g. `6` instead of `6.0`). */
internal fun tidy(e: JsonElement): JsonElement = when {
    e is JsonPrimitive && !e.isString -> {
        val d = e.doubleOrNull
        if (d != null && d.isFinite() && d == kotlin.math.floor(d) && kotlin.math.abs(d) < 1e12) JsonPrimitive(d.toLong()) else e
    }
    e is JsonArray -> JsonArray(e.map(::tidy))
    else -> e
}

/** `[x, y]` */
object Vec2Serializer : KSerializer<Vec2> {
    override val descriptor: SerialDescriptor = JsonArray.serializer().descriptor
    override fun serialize(encoder: Encoder, value: Vec2) =
        (encoder as JsonEncoder).encodeJsonElement(toJson(value))

    override fun deserialize(decoder: Decoder): Vec2 = fromJson((decoder as JsonDecoder).decodeJsonElement())

    fun toJson(v: Vec2): JsonElement = buildJsonArray { add(v.x.toJson()); add(v.y.toJson()) }
    fun fromJson(e: JsonElement): Vec2 {
        val arr = e as? JsonArray ?: throw SerializationException("expected [x, y], got $e")
        if (arr.size != 2) throw SerializationException("expected [x, y] with 2 numbers, got ${arr.size}")
        return Vec2(arr[0].number("x"), arr[1].number("y"))
    }
}

/** `[x, y, z]` */
object Vec3Serializer : KSerializer<Vec3> {
    override val descriptor: SerialDescriptor = JsonArray.serializer().descriptor
    override fun serialize(encoder: Encoder, value: Vec3) =
        (encoder as JsonEncoder).encodeJsonElement(toJson(value))

    override fun deserialize(decoder: Decoder): Vec3 = fromJson((decoder as JsonDecoder).decodeJsonElement())

    fun toJson(v: Vec3): JsonElement = buildJsonArray { add(v.x.toJson()); add(v.y.toJson()); add(v.z.toJson()) }
    fun fromJson(e: JsonElement): Vec3 {
        val arr = e as? JsonArray ?: throw SerializationException("expected [x, y, z], got $e")
        if (arr.size != 3) throw SerializationException("expected [x, y, z] with 3 numbers, got ${arr.size}")
        return Vec3(arr[0].number("x"), arr[1].number("y"), arr[2].number("z"))
    }
}

/** `"#RRGGBB"` or `"#RRGGBBAA"` */
object ColorSerializer : KSerializer<Color> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("dev.kinesis.Color", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: Color) = encoder.encodeString(value.toHex())
    override fun deserialize(decoder: Decoder): Color {
        val s = decoder.decodeString()
        return Color.parseOrNull(s) ?: throw SerializationException("invalid colour '$s' (expected #RRGGBB or #RRGGBBAA)")
    }
}

/** Preset name (`"easeOutCubic"`) or `{"cubicBezier": [x1, y1, x2, y2]}`. */
object EasingSerializer : KSerializer<Easing> {
    override val descriptor: SerialDescriptor = JsonElement.serializer().descriptor
    override fun serialize(encoder: Encoder, value: Easing) =
        (encoder as JsonEncoder).encodeJsonElement(toJson(value))

    override fun deserialize(decoder: Decoder): Easing = fromJson((decoder as JsonDecoder).decodeJsonElement())

    fun toJson(e: Easing): JsonElement = when (e) {
        is Easing.Preset -> JsonPrimitive(e.preset.id)
        is Easing.CubicBezier -> buildJsonObject {
            put("cubicBezier", buildJsonArray { add(e.x1.toJson()); add(e.y1.toJson()); add(e.x2.toJson()); add(e.y2.toJson()) })
        }
    }

    fun fromJson(e: JsonElement): Easing {
        if (e is JsonPrimitive && e.isString) {
            val preset = EasingPreset.fromId(e.content)
                ?: throw SerializationException("unknown easing '${e.content}'. Valid presets: ${EasingPreset.entries.joinToString { it.id }}")
            return Easing.Preset(preset)
        }
        if (e is JsonObject && "cubicBezier" in e) {
            val arr = e["cubicBezier"]?.jsonArray ?: throw SerializationException("cubicBezier must be [x1, y1, x2, y2]")
            if (arr.size != 4) throw SerializationException("cubicBezier must have 4 numbers")
            val x1 = arr[0].number("x1"); val y1 = arr[1].number("y1"); val x2 = arr[2].number("x2"); val y2 = arr[3].number("y2")
            if (x1 !in 0f..1f || x2 !in 0f..1f) throw SerializationException("cubicBezier x1 and x2 must be between 0 and 1")
            return Easing.CubicBezier(x1, y1, x2, y2)
        }
        throw SerializationException("easing must be a preset name or {\"cubicBezier\": [x1, y1, x2, y2]}, got $e")
    }
}

/**
 * An [Animatable] is written as its plain value when static, or as
 * `{"keyframes": [{"time": 0, "value": ..., "easing": "easeOut"}], "loop": "cycle"}` when animated.
 */
class AnimatableSerializer<T>(private val valueSerializer: KSerializer<T>) : KSerializer<Animatable<T>> {
    override val descriptor: SerialDescriptor = JsonElement.serializer().descriptor

    override fun serialize(encoder: Encoder, value: Animatable<T>) {
        val json = encoder as JsonEncoder
        if (!value.isAnimated) {
            json.encodeJsonElement(tidy(json.json.encodeToJsonElement(valueSerializer, value.value)))
            return
        }
        val element = buildJsonObject {
            put("keyframes", buildJsonArray {
                value.keyframes.forEach { k ->
                    add(buildJsonObject {
                        put("time", k.time.toJson())
                        put("value", tidy(json.json.encodeToJsonElement(valueSerializer, k.value)))
                        if (k.easing != Easing.LINEAR) put("easing", EasingSerializer.toJson(k.easing))
                    })
                }
            })
            if (value.loop != LoopMode.NONE) put("loop", value.loop.id)
        }
        json.encodeJsonElement(element)
    }

    override fun deserialize(decoder: Decoder): Animatable<T> {
        val json = decoder as JsonDecoder
        val element = json.decodeJsonElement()
        if (element is JsonObject && "keyframes" in element) {
            val loop = (element["loop"] as? JsonPrimitive)?.content?.let {
                LoopMode.fromId(it) ?: throw SerializationException("unknown loop mode '$it' (none, cycle, pingpong)")
            } ?: LoopMode.NONE
            val arr = element["keyframes"] as? JsonArray ?: throw SerializationException("keyframes must be an array")
            if (arr.isEmpty()) throw SerializationException("keyframes must not be empty (use a plain value for static properties)")
            val keys = arr.mapIndexed { i, ke ->
                val obj = ke as? JsonObject ?: throw SerializationException("keyframes[$i] must be an object")
                val time = (obj["time"] as? JsonPrimitive)?.doubleOrNull?.takeIf { it.isFinite() }
                    ?: throw SerializationException("keyframes[$i].time must be a number (seconds from the layer start)")
                val v = obj["value"] ?: throw SerializationException("keyframes[$i].value is missing")
                val easing = obj["easing"]?.takeIf { it != JsonNull }?.let { EasingSerializer.fromJson(it) } ?: Easing.LINEAR
                Keyframe(time, json.json.decodeFromJsonElement(valueSerializer, v), easing)
            }
            for (i in 1 until keys.size) {
                if (keys[i].time < keys[i - 1].time) throw SerializationException("keyframes must be sorted by time (keyframes[$i])")
            }
            return Animatable(keys.first().value, keys, loop)
        }
        return Animatable(json.json.decodeFromJsonElement(valueSerializer, element))
    }
}
