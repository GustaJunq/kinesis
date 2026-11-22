package dev.kinesis.core.knsfx

import dev.kinesis.core.math.Color
import dev.kinesis.core.math.Vec2
import dev.kinesis.core.model.EffectChoice
import dev.kinesis.core.model.EffectParam
import dev.kinesis.core.model.ParamType
import dev.kinesis.core.model.ParamUnit
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import javax.xml.parsers.DocumentBuilderFactory

/**
 * `params.xml` of a `.knsfx` package: one element per parameter, in display order.
 *
 * | element | becomes | attributes |
 * |---|---|---|
 * | `selector` | choice | `default`, `style` (radio/dropdown), `<choice label value enableTargets>` |
 * | `spinner`, `slider` | number | `default`, `min`, `max`, `step`, `type="percent"`, `ticks="0.5,1"` |
 * | `angle` | number in degrees | `default`, `min`, `max` |
 * | `checkbox` | on/off | `default="true|false"` |
 * | `color` | colour | `default="#RRGGBB[AA]"` |
 * | `point` | point (vec2) | `default="x,y"` |
 *
 * Every element has `id` (also the shader uniform's name, so a valid GLSL identifier) and `label`; `enabled="false"`
 * and the `enableTargets` of a selector's choices decide which parameters can be edited (see [EffectParam.enabledWhen]).
 */
object KnsfxParams {
    private val ID = Regex("^[A-Za-z_][A-Za-z0-9_]{0,40}$")
    private val RESERVED = setOf("uTexture", "uResolution", "uTime", "uProgress", "vTexCoord", "main")

    fun parse(xml: String): List<EffectParam> {
        // No DTDs at all: closes the door to external entities (XXE) and entity expansion bombs.
        if (xml.contains("<!DOCTYPE", ignoreCase = true) || xml.contains("<!ENTITY", ignoreCase = true)) {
            throw KnsfxException("params.xml não pode ter DOCTYPE nem ENTITY")
        }
        val factory = DocumentBuilderFactory.newInstance()
        runCatching { factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        runCatching { factory.isExpandEntityReferences = false }
        val doc = try {
            factory.newDocumentBuilder().parse(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)))
        } catch (e: Exception) {
            throw KnsfxException("params.xml não é um XML válido: ${e.message}")
        }
        val root = doc.documentElement
        if (root.tagName != "params") throw KnsfxException("params.xml precisa começar com <params>")
        val elements = children(root)
        if (elements.size > 64) throw KnsfxException("params.xml tem parâmetros demais (máximo 64)")

        // First pass: who enables whom. enableTargets of a choice lists the parameters it turns on.
        val enablers = HashMap<String, MutableMap<String, MutableSet<String>>>() // target -> (selector -> values)
        for (el in elements) if (el.tagName == "selector") {
            for (c in children(el)) if (c.tagName == "choice") {
                val targets = c.getAttribute("enableTargets").split(',').map { it.trim() }.filter { it.isNotEmpty() }
                for (t in targets) enablers.getOrPut(t) { HashMap() }.getOrPut(el.getAttribute("id")) { HashSet() } += c.getAttribute("value").trim()
            }
        }

        val out = ArrayList<EffectParam>()
        val ids = HashSet<String>()
        for (el in elements) {
            val id = el.getAttribute("id").trim()
            val where = "params.xml <${el.tagName} id=\"$id\">"
            if (!ID.matches(id)) throw KnsfxException("$where: id inválido (use letras, números e _, começando por letra)")
            if (id in RESERVED) throw KnsfxException("$where: \"$id\" é um nome reservado")
            if (!ids.add(id)) throw KnsfxException("params.xml: id repetido \"$id\"")
            val label = label(el.getAttribute("label"), id)
            val enabledWhen = enablers[id]?.let { m ->
                if (m.size > 1) throw KnsfxException("$where: só um selector pode habilitar este parâmetro")
                m.entries.first().let { it.key to it.value.toSet() }
            } ?: if (el.getAttribute("enabled").trim().equals("false", true)) "" to emptySet<String>() else null
            fun num(name: String): Float? = el.getAttribute(name).trim().takeIf { it.isNotEmpty() }?.let {
                it.toFloatOrNull()?.takeIf { v -> v.isFinite() } ?: throw KnsfxException("$where: $name=\"$it\" não é um número")
            }
            val p = when (el.tagName) {
                "selector" -> {
                    val choices = children(el).filter { it.tagName == "choice" }.map { c ->
                        val v = c.getAttribute("value").trim()
                        if (v.isEmpty()) throw KnsfxException("$where: <choice> sem value")
                        val l = label(c.getAttribute("label"), v)
                        EffectChoice(v, l, l)
                    }
                    if (choices.isEmpty()) throw KnsfxException("$where: o selector precisa de pelo menos um <choice>")
                    if (choices.map { it.id }.toSet().size != choices.size) throw KnsfxException("$where: values repetidos")
                    val def = el.getAttribute("default").trim().ifEmpty { choices.first().id }
                    if (choices.none { it.id == def }) throw KnsfxException("$where: default \"$def\" não é um dos values")
                    EffectParam(id, ParamType.CHOICE, label, label, def, choices = choices, enabledWhen = enabledWhen)
                }
                "spinner", "slider", "angle" -> {
                    val angle = el.tagName == "angle"
                    val min = num("min") ?: if (angle) -3600f else null
                    val max = num("max") ?: if (angle) 3600f else null
                    if (min != null && max != null && min > max) throw KnsfxException("$where: min maior que max")
                    val def = num("default") ?: min ?: 0f
                    if ((min != null && def < min) || (max != null && def > max)) throw KnsfxException("$where: default fora de min/max")
                    val percent = el.getAttribute("type").trim().equals("percent", true)
                    val ticks = el.getAttribute("ticks").split(',').map { it.trim() }.filter { it.isNotEmpty() }.map {
                        it.toFloatOrNull() ?: throw KnsfxException("$where: tick \"$it\" não é um número")
                    }
                    val step = num("step")?.also { if (it <= 0f) throw KnsfxException("$where: step precisa ser maior que zero") }
                    // A huge range (0..1000) makes a useless slider: the slider shows up to the largest tick or 4× the default.
                    val sliderMax = if (max != null && max - (min ?: 0f) > 100f && !angle) maxOf(ticks.maxOrNull() ?: 0f, def * 4f, (min ?: 0f) + 1f).coerceAtMost(max) else max
                    EffectParam(
                        id, ParamType.NUMBER, label, label, def, min, max, sliderMin = if (angle) 0f else min, sliderMax = if (angle) 360f else sliderMax,
                        unit = when { angle -> ParamUnit.DEG; percent -> ParamUnit.PERCENT; else -> ParamUnit.NONE },
                        step = step, ticks = ticks, enabledWhen = enabledWhen,
                    )
                }
                "checkbox" -> {
                    val d = el.getAttribute("default").trim().lowercase()
                    if (d !in setOf("", "true", "false", "1", "0")) throw KnsfxException("$where: default precisa ser true ou false")
                    EffectParam(id, ParamType.BOOLEAN, label, label, d == "true" || d == "1", enabledWhen = enabledWhen)
                }
                "color" -> {
                    val d = el.getAttribute("default").trim().ifEmpty { "#FFFFFF" }
                    val c = Color.parseOrNull(d) ?: throw KnsfxException("$where: cor \"$d\" inválida (use #RRGGBB ou #RRGGBBAA)")
                    EffectParam(id, ParamType.COLOR, label, label, c, enabledWhen = enabledWhen)
                }
                "point" -> {
                    val d = el.getAttribute("default").trim().ifEmpty { "0,0" }.split(',').map { it.trim().toFloatOrNull() }
                    if (d.size != 2 || d.any { it == null }) throw KnsfxException("$where: default de point precisa ser \"x,y\"")
                    EffectParam(id, ParamType.POINT, label, label, Vec2(d[0]!!, d[1]!!), sliderMin = -2000f, sliderMax = 2000f, unit = ParamUnit.PX, enabledWhen = enabledWhen)
                }
                else -> throw KnsfxException("params.xml: tipo \"${el.tagName}\" não suportado (use selector, spinner, slider, checkbox, color, point ou angle)")
            }
            out += p
        }
        // enableTargets must name parameters that exist, and the enabling parameter must be a selector.
        for (target in enablers.keys) if (target !in ids) throw KnsfxException("params.xml: enableTargets cita \"$target\", que não existe")
        return out
    }

    /** `@am:string/...` resources are not supported: the id is used instead. */
    private fun label(raw: String, fallback: String): String = raw.trim().let { if (it.isEmpty() || it.startsWith("@")) fallback else it.take(60) }

    private fun children(e: Element): List<Element> {
        val out = ArrayList<Element>()
        val nodes = e.childNodes
        for (i in 0 until nodes.length) (nodes.item(i) as? Element)?.let { out += it }
        return out
    }
}
