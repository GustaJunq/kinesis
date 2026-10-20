package dev.kinesis.editor

import android.opengl.GLES30
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.kinesis.core.anim.anim
import dev.kinesis.core.edit.Factory
import dev.kinesis.core.math.Color
import dev.kinesis.core.math.Vec2
import dev.kinesis.core.model.Effect
import dev.kinesis.core.model.KnsProject
import dev.kinesis.core.model.ShapeKind
import dev.kinesis.core.scene.SceneEvaluator
import dev.kinesis.editor.render.AssetFiles
import dev.kinesis.editor.render.Compositor
import dev.kinesis.editor.render.RenderResources
import dev.kinesis.editor.render.RenderSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/** Copy Background on the real GLES pipeline: the layer shows the picture below it, so effects after it act on that picture. */
@RunWith(AndroidJUnit4::class)
class CopyBackgroundRenderTest {
    private val app = InstrumentationRegistry.getInstrumentation().targetContext

    /** A red full-frame rectangle under a green star that carries [effects]. */
    private fun scene(effects: List<Effect>): KnsProject {
        val p = Factory.newProject("copybg", 480, 480, 30.0, 4.0, Color.parse("#202030"), null, "test")
        val red = Factory.shape(p, 0.0, ShapeKind.RECTANGLE, "red").let {
            it.copy(shape = it.shape!!.copy(size = anim(Vec2(480f, 480f)), fill = it.shape!!.fill.copy(color = anim(Color.parse("#FF0000")), gradient = null)))
        }
        val star = Factory.shape(p, 0.0, ShapeKind.STAR, "star").let {
            it.copy(
                shape = it.shape!!.copy(fill = it.shape!!.fill.copy(color = anim(Color.parse("#00FF00")), gradient = null)),
                effects = effects,
                transform = it.transform.copy(scale = anim(Vec2(0.8f, 0.8f))),
            )
        }
        return p.copy(layers = listOf(red, star))
    }

    private fun frame(project: KnsProject): IntArray = PbufferContext().use {
        val res = RenderResources(app.assets, AssetFiles { null })
        val comp = Compositor(res)
        try {
            val target = comp.render(project, SceneEvaluator.evaluate(project, 1.0), 240, 240, RenderSettings(motionBlurSamples = 1))
            assertEquals("GL error", GLES30.GL_NO_ERROR, GLES30.glGetError())
            val bmp = comp.readPixels(target)
            IntArray(bmp.width * bmp.height).also { bmp.getPixels(it, 0, bmp.width, 0, 0, bmp.width, bmp.height) }
        } finally {
            comp.release()
            res.release()
        }
    }

    private fun channel(px: Int, shift: Int) = (px ushr shift) and 0xFF

    private fun center(f: IntArray) = f[120 * 240 + 120]
    private fun corner(f: IntArray) = f[4 * 240 + 4]

    /** The star is a window onto the background: without other effects its centre has the red of the picture around it. */
    @Test
    fun theStarShowsThePictureBelowIt() {
        val plain = frame(scene(emptyList()))
        val copied = frame(scene(listOf(Effect.create("copyBackground"))))
        assertTrue("the star was not green to begin with", channel(center(plain), 8) > 200 && channel(center(plain), 16) < 60)
        assertTrue("the star was not replaced by the background", channel(center(copied), 16) > 200 && channel(center(copied), 8) < 60)
    }

    /** Effects after it act on the copied pixels, only inside the star. */
    @Test
    fun laterEffectsActOnTheBackgroundInsideTheLayer() {
        val f = frame(scene(listOf(Effect.create("copyBackground"), Effect.create("invert"))))
        // Outside the star the red stays red; inside it is inverted (cyan).
        assertTrue("outside is not red", channel(corner(f), 16) > 200 && channel(corner(f), 8) < 60)
        assertTrue("inside is not inverted", channel(center(f), 16) < 60 && channel(center(f), 8) > 200)
    }

    /** `amount` 0 leaves the layer as it is (up to the rounding of drawing it through an effect pass). */
    @Test
    fun amountZeroKeepsTheLayer() {
        val zero = frame(scene(listOf(Effect.of("copyBackground", "amount" to 0f))))
        val plain = frame(scene(emptyList()))
        val different = zero.indices.count { i ->
            listOf(0, 8, 16, 24).any { shift -> abs(channel(zero[i], shift) - channel(plain[i], shift)) > 8 }
        }
        assertTrue("amount 0 changed $different pixels", different < zero.size / 100)
    }
}
