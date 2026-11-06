package dev.kinesis.core

import dev.kinesis.core.anim.Easing
import dev.kinesis.core.anim.EasingPreset
import dev.kinesis.core.format.AureaImport
import dev.kinesis.core.format.KnsprojJson
import dev.kinesis.core.format.ProjectValidator
import dev.kinesis.core.model.AssetType
import dev.kinesis.core.model.Layer
import dev.kinesis.core.model.LayerType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `.aureaproj` written by Aurea 0.4.6 (layer section v55): "The oldest buh in the buh", 1440×1080 at 60 fps with
 * 15 compositions, nested precompositions, parents, a GLB model, blur, markers and media. The media files of the
 * test copy are placeholders; `project.aurea` and `manifest.txt` are the original ones.
 */
class AureaV55ImportTest {
    private fun sample() = javaClass.getResourceAsStream("/aurea/the-oldest-buh.aureaproj")!!.readBytes()

    private fun read(): Pair<AureaImport.Result, Map<String, ByteArray>> {
        val stored = linkedMapOf<String, ByteArray>()
        val r = AureaImport.read(sample().inputStream(), media = { entry, data -> stored[entry] = data.readBytes() })
        return r to stored
    }

    private fun all(layers: List<Layer>): List<Layer> = layers.flatMap { listOf(it) + all(it.layers) }

    @Test
    fun compositionAndMetadata() {
        val p = read().first.project
        assertEquals("The oldest buh in the buh", p.meta.title)
        assertEquals(1440, p.composition.width)
        assertEquals(1080, p.composition.height)
        assertEquals(60.0, p.composition.fps, 0.0)
        assertEquals(1027 / 60.0, p.composition.duration, 1e-9)
    }

    @Test
    fun projectIsValidAndRoundTrips() {
        val (r, _) = read()
        val report = ProjectValidator.validate(r.project)
        assertTrue(report.errors.joinToString { "${it.path}: ${it.message}" }, report.isValid)
        val again = KnsprojJson.decode(KnsprojJson.encode(r.project))
        assertEquals(r.project, again)
    }

    @Test
    fun mediaIsMappedToAssets() {
        val (r, stored) = read()
        val p = r.project
        // 9 media files in the zip, the duplicated GLB counts once; the unused mp4 is not an asset.
        assertEquals(9, stored.size)
        val types = p.assets.groupBy { it.type }.mapValues { it.value.size }
        assertEquals(1, types[AssetType.MODEL])
        assertEquals(1, types[AssetType.VIDEO])
        assertEquals(6, types[AssetType.IMAGE])
        assertTrue(p.assets.all { it.path.startsWith("assets/") })
        // Every asset is backed by a stored zip entry.
        for (a in p.assets) assertTrue(a.path, r.media[a.path] in stored)
        val glb = p.assets.single { it.type == AssetType.MODEL }
        assertTrue(r.media[glb.path]!!.endsWith(".glb"))
        // Image sizes come from the media pool.
        val head = p.assets.first { it.name == "169 Sem Título_20261105224950.png" }
        assertEquals(742, head.width); assertEquals(678, head.height)
        // The audio is a content:// file that is not in the archive.
        assertTrue(r.warnings.any { it.startsWith("mídia ausente") && it.contains("ssstik") })
    }

    @Test
    fun layersOfTheMainComposition() {
        val p = read().first.project
        val names = p.layers.map { it.name }
        // 23 layers, minus the audio whose file is missing, plus the camera added for the model.
        assertEquals(23, p.layers.size)
        assertTrue(LayerType.CAMERA in p.layers.map { it.type })
        assertFalse(names.any { it.startsWith("ssstik") })
        val groups = p.layers.filter { it.type == LayerType.GROUP }
        assertEquals(8, groups.size)
        // The white flashes ("Quadrado" and its copies, z = -657) are the nearest layers, so they are drawn last (on top).
        val visual = p.layers.filter { it.type != LayerType.CAMERA }
        assertTrue(visual.takeLast(4).all { it.type == LayerType.SHAPE && it.name.startsWith("Quadrado") })
        // Painter's order keeps the Aurea list order for equal depth: the watermark is drawn after the background.
        assertTrue(names.indexOf("body") < names.indexOf("161 Sem Título_20261023211414.png"))
    }

    @Test
    fun hiddenWatermark() {
        val (r, _) = read()
        val watermark = r.project.layers.single { it.name == "161 Sem Título_20261023211414.png" }
        assertFalse(watermark.visible)
        assertTrue(r.warnings.any { it.contains("oculta") && it.contains("161 Sem Título") })
    }

    @Test
    fun transformsAndKeyframes() {
        val p = read().first.project
        val body = p.layers.single { it.name == "body" }
        // Aurea (772.66, 1070.10) from the top-left = (52.66, 530.10) from the centre.
        val pos = body.transform.position
        assertEquals(52.66f, pos.value.x, 0.01f)
        assertEquals(9, pos.keyframes.size)
        assertEquals(530.10f - 70.05f, pos.keyframes[1].value.y, 0.05f)
        assertEquals(13 / 60.0, pos.keyframes[1].time, 1e-9)
        assertTrue(pos.keyframes[0].easing is Easing.CubicBezier)
        // Anchor from the image's top-left (404, 672) to its centre (742×678).
        assertEquals(404.06f - 371f, body.transform.anchor.value.x, 0.01f)
        assertEquals(1.5929f, body.transform.scale.value.x, 1e-3f)
        // Rotation keyframes with the "ease" curve (code 8) become easeOutExpo.
        val rot = body.transform.rotation
        assertEquals(Easing.Preset(EasingPreset.EASE_OUT_EXPO), rot.keyframes[0].easing)
        assertEquals(20f, rot.keyframes[1].value, 1e-4f)
        assertTrue(read().first.warnings.any { it.contains("easeOutExpo") })
    }

    @Test
    fun keyframeTimesFollowTheLayerOffset() {
        val p = read().first.project
        // "Quadrado copia" at 192..230 (offset 90) keeps the keyframes of the original at Aurea frames 0, 25, 90, 103
        // and 128: they sit 90 frames earlier in the layer's time, so 90, 103 and 128 are 0, 13 and 38 frames in.
        val copy = p.layers.first { it.name == "Quadrado copia" && kotlin.math.abs(it.start - 192 / 60.0) < 1e-9 }
        assertEquals(listOf(-1.5, -65 / 60.0, 0.0, 13 / 60.0, 38 / 60.0), copy.transform.opacity.keyframes.map { it.time })
        assertEquals(0f, copy.transform.opacity.keyframes[2].value, 0f)
        assertEquals(1f, copy.transform.opacity.keyframes[3].value, 0f)
        assertEquals(1440f, copy.shape!!.size.value.x, 0f)
        assertEquals(1080f, copy.shape!!.size.value.y, 0f)
    }

    @Test
    fun precompositionsBecomeGroups() {
        val p = read().first.project
        val g13 = p.layers.single { it.name == "Pré-composição 13" }
        assertEquals(205 / 60.0, g13.start, 1e-9)
        val g12 = g13.layers.single()
        assertEquals(LayerType.GROUP, g12.type)
        // Group children live in the group's time: comp 13 starts its child at frame 205 with offset 205.
        assertEquals(0.0, g12.start, 1e-9)
        assertEquals(2, g12.layers.size)
        // Mirrored with a negative x scale, as in Aurea.
        assertTrue(g12.transform.scale.value.x < 0f)
        // Precompositions are clipped to their frame.
        assertEquals(1440f, g12.masks.single().size.value.x, 0f)
    }

    @Test
    fun parentsAreMeasuredFromTheParentCentre() {
        val p = read().first.project
        val nul = p.layers.single { it.type == LayerType.NULL }
        val g2 = p.layers.single { it.name == "Pré-composição 2" }
        assertEquals(nul.id, g2.parent)
        // 1366.33 in the null's space (100×100, measured from its top-left) = 1316.33 from its centre.
        assertEquals(1316.33f, g2.transform.position.value.x, 0.01f)
        val head = g2.layers.single { it.parent != null }
        val bodyCopy = g2.layers.single { it.name == "body copia" }
        assertEquals(bodyCopy.id, head.parent)
    }

    @Test
    fun modelBlurMarkersAndWarnings() {
        val (r, _) = read()
        val p = r.project
        val models = all(p.layers).filter { it.type == LayerType.MODEL3D }
        assertEquals(4, models.size)
        val book = p.layers.single { it.type == LayerType.MODEL3D }
        assertEquals(0.45f * 1080, book.model3d!!.size.value, 1e-3f)
        assertEquals(-424.94f, book.transform3d.position.value.z, 0.01f)
        assertEquals(9, book.transform3d.rotation.keyframes.size)
        val blurred = p.layers.single { it.name == "Pré-composição 2" }
        assertEquals("blur", blurred.effects.single().type)
        assertEquals(40f, blurred.effects.single().number("radius").value, 1e-4f)
        assertEquals(33, p.markers.size)
        assertEquals("#F7C34F", p.markers.first().color)
        assertEquals(13 / 60.0, p.markers[1].time, 1e-9)
        val w = r.warnings
        assertTrue(w.any { it.contains("0xf83f97a7") && it.contains("djlv58x") })
        assertTrue(w.any { it.contains("0x26370f8d") })
        assertTrue(w.any { it.contains("composição(ões) não usada(s)") && it.contains("Pré-composição 1") })
        assertTrue(w.any { it.contains("seção 6") })
        assertNotNull(w.firstOrNull { it.contains("c38ad49d") && it.contains("0x1000e") })
    }
}
