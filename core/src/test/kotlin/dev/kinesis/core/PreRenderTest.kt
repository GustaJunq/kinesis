package dev.kinesis.core

import dev.kinesis.core.edit.PreRender
import dev.kinesis.core.format.ProjectValidator
import dev.kinesis.core.math.Color
import dev.kinesis.core.model.Asset
import dev.kinesis.core.model.AssetType
import dev.kinesis.core.model.KnsProject
import dev.kinesis.core.model.Layer
import dev.kinesis.core.model.LayerTree
import dev.kinesis.core.model.LayerType
import dev.kinesis.core.model.LightContent
import dev.kinesis.core.model.ShapeContent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PreRenderTest {
    private val inner = Layer("inner", LayerType.SHAPE, start = 1.0, duration = 10.0, shape = ShapeContent())
    private val sibling = Layer("sib", LayerType.SHAPE, duration = 10.0, shape = ShapeContent())
    private val group = Layer("g", LayerType.GROUP, start = 2.0, duration = 5.0, layers = listOf(sibling, inner))
    private val other = Layer("other", LayerType.SHAPE, duration = 10.0, shape = ShapeContent())
    private val light = Layer("light", LayerType.LIGHT, duration = 10.0, light = LightContent())
    private val project = Factory0.project(listOf(other, group, light))

    @Test
    fun rangeIsTheVisibleSpanInsideGroupsAndComposition() {
        // inner starts at 2 + 1 = 3 and is clipped by the group's end (7).
        assertEquals(3.0..7.0, PreRender.range(project, "inner"))
        assertEquals(0.0..8.0, PreRender.range(project, "other"))
        assertNull(PreRender.range(project, "missing"))
        assertFalse(PreRender.canPreRender(project, "light"))
        assertTrue(PreRender.canPreRender(project, "inner"))
    }

    @Test
    fun isolateHidesOnlyOtherDrawnLayers() {
        val solo = PreRender.isolate(project, "inner")
        assertFalse(LayerTree.find(solo, "other")!!.visible)
        assertFalse(LayerTree.find(solo, "sib")!!.visible)
        assertTrue(LayerTree.find(solo, "g")!!.visible)
        assertTrue(LayerTree.find(solo, "inner")!!.visible)
        assertTrue(LayerTree.find(solo, "light")!!.visible)
    }

    @Test
    fun replaceAddsAPackedVideoAboveTheTopLevelHolder() {
        val asset = Asset("pre", AssetType.VIDEO, "assets/pre.mp4", width = 2 * 1080, height = 1920, duration = 4.0)
        val range = PreRender.range(project, "inner")!!
        val (out, id) = PreRender.replace(project, "inner", asset, range, "inner (pre-render)")
        assertEquals(listOf("other", "g", id, "light"), out.layers.map { it.id })
        val video = LayerTree.find(out, id)!!
        assertEquals(3.0, video.start, 1e-9)
        assertEquals(4.0, video.duration, 1e-9)
        assertTrue(video.video!!.alphaPacked)
        assertFalse(LayerTree.find(out, "inner")!!.visible)
        assertTrue(out.assets.any { it.id == "pre" })
        assertEquals(emptyList<Any>(), ProjectValidator.validate(out).errors)
    }
}

private object Factory0 {
    fun project(layers: List<Layer>): KnsProject {
        val p = dev.kinesis.core.edit.Factory.newProject("t", 1080, 1920, 30.0, 8.0, Color.parse("#000000"), null, "test")
        return p.copy(layers = layers)
    }
}
