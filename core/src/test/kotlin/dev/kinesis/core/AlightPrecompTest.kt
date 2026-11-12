package dev.kinesis.core

import dev.kinesis.core.alight.AlightConverter
import dev.kinesis.core.alight.AlightLayer
import dev.kinesis.core.alight.AlightLayerKind
import dev.kinesis.core.alight.AlightParser
import dev.kinesis.core.alight.AlightScene
import dev.kinesis.core.alight.ReportKind
import dev.kinesis.core.format.ProjectValidator
import dev.kinesis.core.model.Layer
import dev.kinesis.core.model.LayerTree
import dev.kinesis.core.model.LayerType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Step 4 of the Alight Motion importer: pre-compositions (embedScene), parents, repeated ids and retime. */
class AlightPrecompTest {
    private fun fixture() = AlightParser.scan(javaClass.getResourceAsStream("/alight/novo-projeto-185.xml")!!.readBytes()).scene

    // An opaque scene background becomes one extra "Fundo" rectangle inside its group.
    private fun opaque(bg: String?) = bg != null && bg.length == 9 && !bg.startsWith("#00")
    private fun count(layers: List<AlightLayer>): Int = layers.filter { it.kind != AlightLayerKind.AUDIO }
        .sumOf { 1 + (it.scene?.let { s -> count(s.layers) + (if (opaque(s.background)) 1 else 0) } ?: 0) }
    private fun countOut(layers: List<Layer>): Int = layers.sumOf { 1 + countOut(it.layers) }

    @Test
    fun everyNestedLayerIsConvertedAndIdsAreUnique() {
        val s = fixture()
        val p = AlightConverter.convert(s, s.media.map { it.uri }.toSet()).project
        // 29 embedded scenes and 54 shapes (the audio is left for step 5): nothing is lost on the way.
        assertEquals(count(s.layers), countOut(p.layers))
        val ids = LayerTree.all(p).map { it.id }
        assertEquals("ids repeat after the conversion", ids.size, ids.toSet().size)
        assertTrue(ProjectValidator.validate(p).errors.toString(), ProjectValidator.validate(p).isValid)
    }

    @Test
    fun embeddedScenesBecomeClippedGroups() {
        val s = fixture(); val p = AlightConverter.convert(s, s.media.map { it.uri }.toSet()).project
        val groups = p.layers.filter { it.type == LayerType.GROUP }
        assertEquals(3, groups.size)
        val g = groups.first()
        assertEquals("Grupo 5", g.name)
        assertEquals(1.9, g.start, 1e-9); assertEquals(6.899, g.duration, 1e-9)
        // The group is as big as its scene (2591 x 1423) and shows only that frame.
        assertEquals(2591f, g.masks.single().size.value.x, 0f); assertEquals(1423f, g.masks.single().size.value.y, 0f)
        assertTrue(g.layers.isNotEmpty())
        // Children keep the order of the file: the first one is the lowest.
        val src = s.layers.first { it.kind == AlightLayerKind.EMBED }.scene!!
        assertEquals(src.layers.filter { it.kind != AlightLayerKind.AUDIO }.map { it.label }, g.layers.map { it.name })
    }

    private fun scene(body: String) = AlightParser.scan(
        "<scene title=\"t\" width=\"1000\" height=\"1000\" fps=\"30\" totalTime=\"10000\" bgcolor=\"#ff000000\">$body</scene>".toByteArray(),
    ).scene

    private fun rect(id: String, start: Int, end: Int, extra: String = "", parent: String? = null) =
        """<shape id="$id" label="s$id" startTime="$start" endTime="$end" ${if (parent != null) "parent=\"$parent\"" else ""} fillType="color" mediaFillMode="fill" s=".rect">
             <transform><location value="10,20,0"/>$extra</transform><property name="size" type="vec2" value="5,5"/></shape>"""

    /** `speed` and `inTime` retime what is inside: times are measured from where the group starts, at 1/speed. */
    @Test
    fun retimeShiftsAndScalesTheContent() {
        val s = scene(
            """<embedScene id="e" label="E" startTime="1000" endTime="5000" fillType="intrinsic" mediaFillMode="fill" inTime="1000" speed="2.0">
                 <scene title="" width="400" height="300" fps="30" totalTime="9000" bgcolor="#00000000">${rect("a", 3000, 5000)}</scene></embedScene>""",
        )
        val g = AlightConverter.convert(s).project.layers.single()
        assertEquals(1.0, g.start, 1e-9); assertEquals(4.0, g.duration, 1e-9)
        val c = g.layers.single()
        assertEquals(1.0, c.start, 1e-9)       // (3 s - inTime 1 s) / 2
        assertEquals(1.0, c.duration, 1e-9)    // 2 s / 2
    }

    @Test
    fun speedsOfNestedPrecompositionsMultiply() {
        val s = scene(
            """<embedScene id="o" label="O" startTime="0" endTime="4000" fillType="intrinsic" mediaFillMode="fill" speed="2.0">
                 <scene title="" width="400" height="300" fps="30" totalTime="9000" bgcolor="#00000000">
                   <embedScene id="i" label="I" startTime="0" endTime="8000" fillType="intrinsic" mediaFillMode="fill" speed="2.0">
                     <scene title="" width="200" height="200" fps="30" totalTime="9000" bgcolor="#00000000">${rect("a", 0, 4000)}</scene></embedScene>
                 </scene></embedScene>""",
        )
        val outer = AlightConverter.convert(s).project.layers.single()
        val inner = outer.layers.single()
        assertEquals(4.0, inner.duration, 1e-9)            // 8 s at speed 2 (outer)
        assertEquals(1.0, inner.layers.single().duration, 1e-9) // 4 s at 2 x 2
    }

    /** The same ids appear in every copy of a pre-composition: each copy resolves its parents among its own layers. */
    @Test
    fun repeatedIdsKeepTheirOwnParents() {
        fun copy(label: String) = """<embedScene id="$label" label="$label" startTime="0" endTime="2000" fillType="intrinsic" mediaFillMode="fill">
              <scene title="" width="400" height="300" fps="30" totalTime="2000" bgcolor="#00000000">${rect("1", 0, 2000)}${rect("2", 0, 2000, parent = "1")}</scene></embedScene>"""
        val p = AlightConverter.convert(scene(copy("A") + copy("B"))).project
        val (a, b) = p.layers
        for (g in listOf(a, b)) {
            assertEquals(2, g.layers.size)
            assertEquals(g.layers[0].id, g.layers[1].parent)
        }
        assertTrue(a.layers[0].id != b.layers[0].id)
        assertTrue(ProjectValidator.validate(p).isValid)
    }

    @Test
    fun linkedPrecompositionsAreReported() {
        val s = scene(
            """<embedScene id="e" label="E" startTime="0" endTime="2000" fillType="intrinsic" mediaFillMode="fill" link="abc">
                 <scene title="" width="400" height="300" fps="30" totalTime="2000" bgcolor="#00000000">${rect("a", 0, 2000)}</scene></embedScene>""",
        )
        assertTrue(AlightConverter.convert(s).report.entries.any { it.kind == ReportKind.APPROXIMATED && it.message.contains("link") })
    }
}
