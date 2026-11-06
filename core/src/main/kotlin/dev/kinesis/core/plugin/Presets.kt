package dev.kinesis.core.plugin

import dev.kinesis.core.anim.Animatable
import dev.kinesis.core.anim.Easing
import dev.kinesis.core.anim.EasingPreset
import dev.kinesis.core.anim.Keyframe
import dev.kinesis.core.anim.at
import dev.kinesis.core.format.KnsprojJson
import dev.kinesis.core.math.Vec2
import dev.kinesis.core.model.Layer
import dev.kinesis.core.model.LayerType
import dev.kinesis.core.model.TextContent
import dev.kinesis.core.model.Transition
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.floatOrNull

enum class PresetKind(val id: String) {
    ANIMATION("animation"), TEXT_STYLE("textStyle"), TRANSITION("transition");

    companion object {
        fun fromId(id: String?) = entries.firstOrNull { it.id == id }
    }
}

/** One keyframe of an animation preset: [t] seconds after the point where the preset is applied. */
data class PresetKey(val t: Double, val values: List<Float>, val easing: Easing)

/**
 * A declarative preset. Animations hold keyframes per property: `opacity` (absolute, 0..1), `position` (pixels added
 * to the layer's position), `scale` (factors applied to its scale) and `rotation` (degrees added). Text styles hold
 * fields of the text content (`fontSize`, `color`, `letterSpacing`, `lineHeight`, `align`, `stroke`, ...). Transitions
 * hold `in` and/or `out` as written in `.knsproj`.
 */
data class Preset(
    val pluginId: String,
    val id: String,
    val name: String,
    val kind: PresetKind,
    val tracks: Map<String, List<PresetKey>> = emptyMap(),
    val style: JsonObject? = null,
    val transitionIn: Transition? = null,
    val transitionOut: Transition? = null,
) {
    val key get() = "$pluginId/$id"
    val duration: Double get() = tracks.values.flatten().maxOfOrNull { it.t } ?: 0.0
}

object Presets {
    val ANIMATED = setOf("opacity", "position", "scale", "rotation")
    private val TEXT_FIELDS = setOf("font", "fontSize", "color", "align", "lineHeight", "letterSpacing", "maxWidth", "stroke", "animators", "propertyAnimators")
    private val json = Json { ignoreUnknownKeys = true }

    /** Parses a preset file (`{"presets": [...]}`) of plugin [pluginId]. */
    fun parse(pluginId: String, text: String): PluginResult<List<Preset>> {
        val root = runCatching { json.parseToJsonElement(text) }.getOrNull() as? JsonObject
            ?: return PluginResult.Err(listOf("arquivo de presets não é um objeto JSON"))
        val list = root["presets"] as? JsonArray ?: return PluginResult.Err(listOf("arquivo de presets sem a lista \"presets\""))
        val errors = mutableListOf<String>()
        val out = list.mapIndexedNotNull { i, e ->
            val o = e as? JsonObject ?: return@mapIndexedNotNull null.also { errors += "preset $i não é um objeto" }
            val id = (o["id"] as? JsonPrimitive)?.content
            val name = (o["name"] as? JsonPrimitive)?.content ?: id
            val kind = PresetKind.fromId((o["kind"] as? JsonPrimitive)?.content)
            if (id.isNullOrBlank() || name.isNullOrBlank() || kind == null) {
                errors += "preset $i: \"id\", \"name\" e \"kind\" (animation, textStyle ou transition) são obrigatórios"
                return@mapIndexedNotNull null
            }
            when (kind) {
                PresetKind.ANIMATION -> {
                    val props = o["properties"] as? JsonObject
                    if (props == null || props.isEmpty()) { errors += "preset \"$id\": sem \"properties\""; return@mapIndexedNotNull null }
                    val tracks = props.mapNotNull { (prop, v) ->
                        if (prop !in ANIMATED) { errors += "preset \"$id\": propriedade \"$prop\" não suportada"; return@mapNotNull null }
                        val keys = (v as? JsonArray)?.mapNotNull { k -> key(k, prop) }
                        if (keys.isNullOrEmpty() || keys.size != (v as JsonArray).size) {
                            errors += "preset \"$id\": keyframes de \"$prop\" inválidos"; return@mapNotNull null
                        }
                        prop to keys.sortedBy { it.t }
                    }.toMap()
                    Preset(pluginId, id, name, kind, tracks = tracks)
                }
                PresetKind.TEXT_STYLE -> {
                    val style = o["style"] as? JsonObject
                    if (style == null || style.keys.any { it !in TEXT_FIELDS }) {
                        errors += "preset \"$id\": \"style\" deve ter só campos de texto (${TEXT_FIELDS.joinToString()})"
                        return@mapIndexedNotNull null
                    }
                    // The style must decode on top of a default text.
                    if (runCatching { applyStyle(TextContent(), style) }.isFailure) {
                        errors += "preset \"$id\": \"style\" com valores inválidos"; return@mapIndexedNotNull null
                    }
                    Preset(pluginId, id, name, kind, style = style)
                }
                PresetKind.TRANSITION -> {
                    fun tr(k: String) = o[k]?.let { runCatching { KnsprojJson.json.decodeFromJsonElement(Transition.serializer(), it) }.getOrNull() ?: run { errors += "preset \"$id\": \"$k\" inválido"; null } }
                    val tin = tr("in"); val tout = tr("out")
                    if (tin == null && tout == null) { errors += "preset \"$id\": transição sem \"in\" nem \"out\""; return@mapIndexedNotNull null }
                    Preset(pluginId, id, name, kind, transitionIn = tin, transitionOut = tout)
                }
            }
        }
        val dup = out.groupBy { it.id }.filter { it.value.size > 1 }.keys
        dup.forEach { errors += "preset \"$it\" repetido" }
        return if (errors.isEmpty()) PluginResult.Ok(out) else PluginResult.Err(errors)
    }

    private fun key(e: JsonElement, prop: String): PresetKey? {
        val o = e as? JsonObject ?: return null
        val t = (o["t"] as? JsonPrimitive)?.doubleOrNull ?: return null
        if (t < 0 || t > 600) return null
        val v = o["v"]
        val values = when (v) {
            is JsonPrimitive -> listOfNotNull(v.floatOrNull)
            is JsonArray -> v.mapNotNull { (it as? JsonPrimitive)?.floatOrNull }
            else -> emptyList()
        }
        val need = if (prop == "position" || prop == "scale") 2 else 1
        if (values.size != need || values.any { !it.isFinite() }) return null
        return PresetKey(t, values, easing(o["easing"]) ?: return null)
    }

    private fun easing(e: JsonElement?): Easing? = when (e) {
        null -> Easing.LINEAR
        is JsonPrimitive -> EasingPreset.fromId(e.content)?.let { Easing.Preset(it) }
        is JsonArray -> e.mapNotNull { (it as? JsonPrimitive)?.floatOrNull }.takeIf { it.size == 4 && it[0] in 0f..1f && it[2] in 0f..1f }
            ?.let { Easing.CubicBezier(it[0], it[1], it[2], it[3]) }
        else -> null
    }

    /** True when [preset] can be applied to [layer]. */
    fun appliesTo(preset: Preset, layer: Layer): Boolean = when (preset.kind) {
        PresetKind.ANIMATION -> layer.hasTransform2D
        PresetKind.TEXT_STYLE -> layer.type == LayerType.TEXT && layer.text != null
        PresetKind.TRANSITION -> layer.type.isVisual
    }

    /**
     * Applies [preset] to [layer]. For animations, [at] is the layer time of the first keyframe; keyframes the preset
     * covers replace the layer's own keyframes in that span.
     */
    fun apply(preset: Preset, layer: Layer, at: Double = 0.0): Layer {
        require(appliesTo(preset, layer)) { "o preset \"${preset.name}\" não se aplica a esta camada" }
        return when (preset.kind) {
            PresetKind.ANIMATION -> {
                var tr = layer.transform
                val end = at + preset.duration
                fun <T> merge(a: Animatable<T>, keys: List<Keyframe<T>>): Animatable<T> {
                    val kept = a.keyframes.filter { it.time < at - 1e-6 || it.time > end + 1e-6 }
                    val all = (kept + keys).sortedBy { it.time }
                    return Animatable(all.first().value, all)
                }
                preset.tracks.forEach { (prop, keys) ->
                    when (prop) {
                        "opacity" -> tr = tr.copy(opacity = merge(tr.opacity, keys.map { Keyframe(at + it.t, it.values[0].coerceIn(0f, 1f), it.easing) }))
                        "rotation" -> tr = tr.copy(rotation = merge(tr.rotation, keys.map { Keyframe(at + it.t, tr.rotation.at(at + it.t) + it.values[0], it.easing) }))
                        "position" -> tr = tr.copy(position = merge(tr.position, keys.map { k ->
                            val base = tr.position.at(at + k.t); Keyframe(at + k.t, Vec2(base.x + k.values[0], base.y + k.values[1]), k.easing)
                        }))
                        "scale" -> tr = tr.copy(scale = merge(tr.scale, keys.map { k ->
                            val base = tr.scale.at(at + k.t); Keyframe(at + k.t, Vec2(base.x * k.values[0], base.y * k.values[1]), k.easing)
                        }))
                    }
                }
                layer.copy(transform = tr)
            }
            PresetKind.TEXT_STYLE -> layer.copy(text = applyStyle(layer.text!!, preset.style!!))
            PresetKind.TRANSITION -> layer.copy(
                transitionIn = preset.transitionIn ?: layer.transitionIn,
                transitionOut = preset.transitionOut ?: layer.transitionOut,
            )
        }
    }

    private fun applyStyle(text: TextContent, style: JsonObject): TextContent {
        val base = KnsprojJson.json.encodeToJsonElement(TextContent.serializer(), text) as JsonObject
        val merged = JsonObject(base + style.filterKeys { it != "content" })
        return KnsprojJson.json.decodeFromJsonElement(TextContent.serializer(), merged)
    }
}
