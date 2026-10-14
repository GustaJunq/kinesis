package dev.kinesis.editor

import android.opengl.GLES30
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.kinesis.core.anim.anim
import dev.kinesis.core.edit.Factory
import dev.kinesis.core.math.Color
import dev.kinesis.core.model.Effect
import dev.kinesis.core.model.KnsProject
import dev.kinesis.core.model.Mask
import dev.kinesis.core.model.MaskShape
import dev.kinesis.core.model.Primitive
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

/** Renders projects with the real GLES pipeline and checks the pixels. */
@RunWith(AndroidJUnit4::class)
class RenderTest {
    private val app = InstrumentationRegistry.getInstrumentation().targetContext

    private fun render(project: KnsProject, times: List<Double>, name: String, files: AssetFiles = AssetFiles { null }) {
        PbufferContext().use {
            val res = RenderResources(app.assets, files)
            val comp = Compositor(res)
            val w = 360
            val h = (w * project.composition.height.toFloat() / project.composition.width).toInt()
            for (t in times) {
                val target = comp.render(project, SceneEvaluator.evaluate(project, t), w, h, RenderSettings(motionBlurSamples = 4))
                assertEquals("GL error after rendering $name at $t", GLES30.GL_NO_ERROR, GLES30.glGetError())
                val bmp = comp.readPixels(target)
                Shots.log("$name@$t", bmp)
                val changed = differingFraction(bmp, project.composition.background.toArgb())
                assertTrue("$name at $t looks empty ($changed of pixels differ from the background)", changed > 0.02f)
            }
            comp.release()
            res.release()
        }
    }

    @Test
    fun orbitCamera3d() = render(loadExample("basicos/3d-camera-orbita"), listOf(0.2, 1.5, 3.0), "3d-camera-orbita")

    @Test
    fun kineticTitle() = render(loadExample("receitas/titulo-cinetico"), listOf(0.5, 2.5), "titulo-cinetico")

    @Test
    fun introLogoGlow() = render(loadExample("receitas/intro-logo-glow"), listOf(1.0, 3.0), "intro-logo-glow")

    /** Every effect, mask and primitive on one frame: catches shader/uniform problems. */
    @Test
    fun effectsMasksAndPrimitives() {
        var p = Factory.newProject("fx", 1080, 1080, 30.0, 4.0, Color.parse("#202030"), null, "test")
        val effects = listOf(
            Effect.Blur(radius = anim(6f)), Effect.ColorAdjust(saturation = anim(0.5f)), Effect.Tint(amount = anim(0.4f)),
            Effect.DropShadow(), Effect.Glow(), Effect.Invert(amount = anim(0.2f)), Effect.Pixelate(size = anim(4f)),
            Effect.ChromaticAberration(), Effect.Vignette(),
        )
        effects.forEachIndexed { i, e ->
            val s = Factory.shape(p, 0.0, if (i % 2 == 0) ShapeKind.STAR else ShapeKind.ELLIPSE, "s$i")
            val x = (i % 3 - 1) * 330f
            val y = (i / 3 - 1) * 330f
            p = p.copy(layers = p.layers + s.copy(
                effects = listOf(e),
                masks = if (i == 4) listOf(Mask(shape = MaskShape.ELLIPSE, size = anim(dev.kinesis.core.math.Vec2(200f, 200f)), feather = anim(30f))) else emptyList(),
                transform = s.transform.copy(position = anim(dev.kinesis.core.math.Vec2(x, y)), scale = anim(dev.kinesis.core.math.Vec2(0.6f, 0.6f))),
            ))
        }
        Primitive.entries.filter { it != Primitive.EMPTY }.forEachIndexed { i, prim ->
            val o = Factory.object3d(p, 0.0, prim, prim.name)
            p = p.copy(layers = p.layers + o.copy(transform3d = o.transform3d.copy(position = anim(dev.kinesis.core.math.Vec3((i - 2.5f) * 170f, 380f, 0f)), scale = anim(dev.kinesis.core.math.Vec3(0.4f, 0.4f, 0.4f)))))
        }
        p = p.copy(layers = p.layers + Factory.text(p, 0.0, "Kinesis", "t"))
        render(p, listOf(1.0), "effects")
    }
}
