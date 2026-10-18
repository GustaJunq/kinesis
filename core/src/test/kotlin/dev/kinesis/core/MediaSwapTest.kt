package dev.kinesis.core

import dev.kinesis.core.anim.Animatable
import dev.kinesis.core.anim.Keyframe
import dev.kinesis.core.edit.Factory
import dev.kinesis.core.edit.MediaSwap
import dev.kinesis.core.format.ProjectValidator
import dev.kinesis.core.math.Color
import dev.kinesis.core.math.Vec2
import dev.kinesis.core.model.Asset
import dev.kinesis.core.model.AssetType
import dev.kinesis.core.model.LayerTree
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Swapping a layer's media for a processed copy (background removed, stabilized). */
class MediaSwapTest {
    private val video = Asset("vid1", AssetType.VIDEO, "assets/a.mp4", width = 1920, height = 1080, duration = 4.0, hasAudio = true)
    private val cut = Asset("vid2", AssetType.VIDEO, "assets/b.mp4", width = 2560, height = 720, duration = 4.0, hasAudio = true)

    private fun base(): Pair<dev.kinesis.core.model.KnsProject, String> {
        val p0 = Factory.newProject("t", 1080, 1920, 24.0, 10.0, Color.parse("#000000"), null, "test")
        val p1 = p0.copy(assets = listOf(video))
        val layer = Factory.video(p1, 1.0, video, "clip")
        val withKeys = layer.copy(transform = layer.transform.copy(scale = Animatable(Vec2(1f, 1f), listOf(Keyframe(0.0, Vec2(1f, 1f)), Keyframe(2.0, Vec2(2f, 2f))))))
        return p1.copy(layers = listOf(withKeys)) to withKeys.id
    }

    @Test
    fun copyGoesAboveTheOriginalWhichIsHidden() {
        val (p, id) = base()
        val (q, newId) = MediaSwap.replace(p, id, cut, "clip (cutout)", sourceStart = 0.0, scale = 0.5f, alphaPacked = true)
        assertEquals(listOf(id, newId), q.layers.map { it.id })
        assertFalse(LayerTree.find(q, id)!!.visible)
        val n = LayerTree.find(q, newId)!!
        assertTrue(n.visible)
        assertTrue(n.video!!.alphaPacked)
        assertEquals("vid2", n.video!!.asset)
        assertEquals(1.0, n.start, 1e-9)
        // The scale (and its keyframes) shrinks to make up for the smaller file.
        assertEquals(0.5f, n.transform.scale.value.x, 1e-6f)
        assertEquals(1f, n.transform.scale.keyframes.last().value.x, 1e-6f)
        assertEquals(true, q.assets.any { it.id == "vid2" })
        assertTrue(ProjectValidator.validate(q).errors.toString(), ProjectValidator.validate(q).isValid)
    }

    @Test
    fun keepsMediaSettingsWhenNothingIsOverridden() {
        val (p, id) = base()
        val withSettings = LayerTree.update(p, id) { it.copy(video = it.video!!.copy(sourceStart = 0.7, speed = 2.0)) }
        val (q, newId) = MediaSwap.replace(withSettings, id, cut, "stabilized")
        val v = LayerTree.find(q, newId)!!.video!!
        assertEquals(0.7, v.sourceStart, 1e-9)
        assertEquals(2.0, v.speed, 1e-9)
        assertFalse(v.alphaPacked)
    }
}
