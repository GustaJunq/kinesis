package dev.kinesis.editor

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.opengl.GLES30
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.kinesis.core.format.AureaImport
import dev.kinesis.core.format.ProjectValidator
import dev.kinesis.core.scene.SceneEvaluator
import dev.kinesis.editor.render.AssetFiles
import dev.kinesis.editor.render.Compositor
import dev.kinesis.editor.render.RenderResources
import dev.kinesis.editor.render.RenderSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.abs

/**
 * Renders the imported Aurea project "The oldest buh in the buh" and compares it with frames of the video Aurea
 * exported from it (`aurea/ref_<frame>.jpg`, 360×270). The test copy carries the three pictures used in the first
 * 103 frames; the other media files are placeholders, so only that part is compared.
 */
@RunWith(AndroidJUnit4::class)
class AureaRenderTest {
    private val test = InstrumentationRegistry.getInstrumentation().context
    private val app = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun importedProjectLooksLikeTheAureaRender() {
        val dir = File(app.cacheDir, "aurea-render").apply { deleteRecursively(); mkdirs() }
        val stored = HashMap<String, File>()
        val result = test.assets.open("aurea/the-oldest-buh-render.aureaproj").use { input ->
            AureaImport.read(input, "test") { entry, data ->
                val f = File(dir, entry.substringAfterLast('/'))
                f.outputStream().use { data.copyTo(it) }
                stored[entry] = f
            }
        }
        val project = result.project
        assertTrue(ProjectValidator.validate(project).isValid)
        result.warnings.forEach { Log.i("KTEST", "aurea warning: $it") }
        // Only real pictures (PNG signature) are handed to the renderer.
        val files = project.assets.associate { a -> a.id to stored[result.media[a.path]]?.takeIf { it.readBytes().take(4) == listOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte()) } }

        PbufferContext().use {
            val res = RenderResources(app.assets, AssetFiles { id -> files[id] })
            val comp = Compositor(res)
            try {
                val diffs = listOf(20, 50, 75, 95).map { frame ->
                    val target = comp.render(project, SceneEvaluator.evaluate(project, frame / 60.0), 360, 270, RenderSettings(motionBlurSamples = 1))
                    assertEquals("GL error", GLES30.GL_NO_ERROR, GLES30.glGetError())
                    val got = comp.readPixels(target)
                    val ref = test.assets.open("aurea/ref_$frame.jpg").use { BitmapFactory.decodeStream(it) }
                    Shots.log("aurea_$frame", got)
                    Shots.log("aurea_ref_$frame", ref)
                    val d = meanDifference(got, ref)
                    Log.i("KTEST", "aurea frame $frame: mean difference ${"%.1f".format(d)}")
                    d
                }
                // Same pictures, same places: the remaining difference comes from approximated curves and blur.
                diffs.forEach { assertTrue("frame differs too much from the Aurea render: $diffs", it < 40f) }
            } finally {
                comp.release()
                res.release()
            }
        }
    }

    /** Mean absolute RGB difference (0..255) over a grid of pixels. */
    private fun meanDifference(a: Bitmap, b: Bitmap): Float {
        val bb = if (b.width == a.width && b.height == a.height) b else Bitmap.createScaledBitmap(b, a.width, a.height, true)
        var sum = 0L
        var n = 0
        for (y in 0 until a.height step 3) for (x in 0 until a.width step 3) {
            val p = a.getPixel(x, y); val q = bb.getPixel(x, y)
            sum += abs(((p shr 16) and 0xff) - ((q shr 16) and 0xff)) + abs(((p shr 8) and 0xff) - ((q shr 8) and 0xff)) + abs((p and 0xff) - (q and 0xff))
            n += 3
        }
        return sum.toFloat() / n
    }
}
