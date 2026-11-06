package dev.kinesis.core

import dev.kinesis.core.edit.Factory
import dev.kinesis.core.elements.ElementBackground
import dev.kinesis.core.elements.Elements
import dev.kinesis.core.format.KnsprojJson
import dev.kinesis.core.format.ProjectValidator
import dev.kinesis.core.math.Color
import dev.kinesis.core.model.Asset
import dev.kinesis.core.model.AssetType
import dev.kinesis.core.model.ImageContent
import dev.kinesis.core.model.LayerTree
import dev.kinesis.core.model.LayerType
import dev.kinesis.core.model.Matte
import dev.kinesis.core.model.ShapeKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ElementsTest {
    private fun element(bg: ElementBackground) = run {
        var p = Factory.newProject("logo", 400, 300, 30.0, 2.0, Color.BLACK, null, "t")
        val img = Asset("img_1", AssetType.IMAGE, "assets/logo.png", width = 200, height = 100)
        p = p.copy(assets = listOf(img))
        val a = Factory.shape(p, 0.0, ShapeKind.STAR, "estrela")
        p = p.copy(layers = listOf(a))
        val pic = Factory.text(p, 0.0, "x").copy(type = LayerType.IMAGE, text = null, image = ImageContent("img_1"), parent = a.id, matte = Matte(a.id))
        Elements.asElement(p.copy(layers = listOf(a, pic)), bg)
    }

    @Test
    fun insertingCopiesLayersAndMediaWithNewIds() {
        val el = element(ElementBackground.GREEN)
        // The target already uses the same ids and path.
        var target = Factory.newProject("alvo", 1080, 1920, 30.0, 10.0, Color.BLACK, null, "t")
        target = target.copy(assets = listOf(Asset("img_1", AssetType.IMAGE, "assets/logo.png", width = 10, height = 10)))
        target = target.copy(layers = el.layers.map { it.copy(parent = null, matte = null, type = LayerType.SHAPE, image = null, shape = el.layers[0].shape) })
        val r = Elements.insert(target, el, "Logo", at = 1.5)
        val p = r.project
        assertTrue(ProjectValidator.validate(p).errors.toString(), ProjectValidator.validate(p).isValid)
        val g = r.layer
        assertEquals(LayerType.GROUP, g.type)
        assertEquals(1.5, g.start, 0.0)
        assertEquals(2.0, g.duration, 0.0)
        // Green background at the bottom, then the element's layers.
        assertEquals(3, g.layers.size)
        assertEquals(ElementBackground.GREEN.color, g.layers[0].shape!!.fill.color.value)
        val star = g.layers[1]; val pic = g.layers[2]
        assertEquals(star.id, pic.parent)
        assertEquals(star.id, pic.matte!!.layer)
        assertEquals(LayerTree.all(p).size, LayerTree.allIds(p).size)
        // The picture points to a copy of the media with a new id and path.
        val copy = p.asset(pic.image!!.asset)!!
        assertEquals("assets/logo-2.png", copy.path)
        assertEquals("img_1", r.copiedAssets[copy]!!.id)
        assertEquals(2, p.assets.size)
        KnsprojJson.decode(KnsprojJson.encode(p))
    }

    @Test
    fun transparentElementsHaveNoBackgroundLayer() {
        val el = element(ElementBackground.TRANSPARENT)
        assertEquals(ElementBackground.TRANSPARENT, ElementBackground.of(el.composition.background))
        val target = Factory.newProject("alvo", 1080, 1920, 30.0, 10.0, Color.BLACK, null, "t")
        val g = Elements.insert(target, el, "Logo", 0.0).layer
        assertEquals(2, g.layers.size)
        assertEquals(400f, g.masks.single().size.value.x, 0f)
        assertNull(ElementBackground.of(Color(0.3f, 0.3f, 0.3f)))
    }
}
