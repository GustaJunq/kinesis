package dev.kinesis.editor

import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Build
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.kinesis.core.anim.Animatable
import dev.kinesis.core.anim.Keyframe
import dev.kinesis.core.anim.anim
import dev.kinesis.core.edit.Factory
import dev.kinesis.core.math.Color
import dev.kinesis.core.math.Vec2
import dev.kinesis.core.model.KnsProject
import dev.kinesis.core.model.ShapeKind
import dev.kinesis.editor.media.ExportFormat
import dev.kinesis.editor.media.ExportQuality
import dev.kinesis.editor.media.ExportResolution
import dev.kinesis.editor.media.ExportSettings
import dev.kinesis.editor.media.FrameExporter
import dev.kinesis.editor.render.AssetFiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.zip.ZipInputStream

/** GIF, WebM with alpha, MOV (PNG) and PNG sequence exports on the device's real codecs, read back. */
@RunWith(AndroidJUnit4::class)
class FrameExportTest {
    private val app = InstrumentationRegistry.getInstrumentation().targetContext
    private val dir = File(app.cacheDir, "frame-export-test").apply { deleteRecursively(); mkdirs() }

    /** A red square moving over a transparent background, 1 s at 24 fps, 320×240. */
    private fun project(): KnsProject {
        val p = Factory.newProject("alpha", 320, 240, 24.0, 1.0, Color.parse("#203040"), null, "test")
        val sq = Factory.shape(p, 0.0, ShapeKind.RECTANGLE, "sq").let {
            it.copy(
                duration = 1.0,
                shape = it.shape!!.copy(size = anim(Vec2(100f, 100f)), fill = it.shape!!.fill.copy(color = anim(Color.parse("#FF2020")), gradient = null)),
                transform = it.transform.copy(position = Animatable(Vec2(-80f, 0f), listOf(Keyframe(0.0, Vec2(-80f, 0f)), Keyframe(1.0, Vec2(80f, 0f))))),
            )
        }
        return p.copy(layers = listOf(sq))
    }

    private fun export(format: ExportFormat, transparent: Boolean = true): File {
        val out = File(dir, "out.${format.extension}")
        FrameExporter(app.assets, AssetFiles { null }).export(
            project(), ExportSettings(ExportResolution.ORIGINAL, ExportQuality.STANDARD, false, format, fps = 12.0, transparent = transparent),
            out, {}, { false },
        )
        assertTrue("$format produced nothing", out.length() > 100)
        Log.i("KTEST", "export $format: ${out.length()} bytes")
        return out
    }

    @Test
    fun gifIsTransparentWhereTheBackgroundWas() {
        val f = export(ExportFormat.GIF)
        val bmp = if (Build.VERSION.SDK_INT >= 28) ImageDecoder.decodeBitmap(ImageDecoder.createSource(f)) { d, _, _ -> d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE } else BitmapFactory.decodeFile(f.path)
        assertEquals(320, bmp.width)
        assertEquals(0, bmp.getPixel(5, 5) ushr 24)
        Shots.log("export_gif", bmp)
    }

    @Test
    fun pngSequenceKeepsAlpha() {
        val f = export(ExportFormat.PNG_SEQUENCE)
        val entries = ArrayList<Pair<String, ByteArray>>()
        ZipInputStream(f.inputStream()).use { z -> generateSequence { z.nextEntry }.forEach { e -> entries += e.name to z.readBytes() } }
        val pngs = entries.filter { it.first.endsWith(".png") }
        assertEquals(12, pngs.size)
        val first = BitmapFactory.decodeByteArray(pngs[0].second, 0, pngs[0].second.size)
        assertEquals(0, first.getPixel(5, 5) ushr 24)
        val c = first.getPixel(160 - 80, 120)
        assertEquals(255, c ushr 24)
        assertTrue(((c shr 16) and 0xff) > 200)
    }

    @Test
    fun opaqueExportKeepsTheBackground() {
        val f = export(ExportFormat.PNG_SEQUENCE, transparent = false)
        val png = ZipInputStream(f.inputStream()).use { z -> z.nextEntry; z.readBytes() }
        val bmp = BitmapFactory.decodeByteArray(png, 0, png.size)
        assertEquals(255, bmp.getPixel(5, 5) ushr 24)
    }

    @Test
    fun movHasAPngTrack() {
        val f = export(ExportFormat.MOV)
        val bytes = f.readBytes()
        val text = String(bytes, Charsets.ISO_8859_1)
        assertTrue(text.contains("moov") && text.contains("png ") && text.contains("co64"))
        // 12 PNG signatures, one per frame.
        assertEquals(12, Regex("\u0089PNG").findAll(text).count())
    }

    @Test
    fun webmWithAlphaPlaysBack() {
        val f = export(ExportFormat.WEBM)
        val ex = MediaExtractor()
        try {
            ex.setDataSource(f.path)
            assertEquals(1, ex.trackCount)
            val fmt = ex.getTrackFormat(0)
            assertEquals(MediaFormat.MIMETYPE_VIDEO_VP8, fmt.getString(MediaFormat.KEY_MIME))
            assertEquals(320, fmt.getInteger(MediaFormat.KEY_WIDTH))
            ex.selectTrack(0)
            var n = 0
            while (ex.sampleTrackIndex >= 0) { n++; ex.advance() }
            assertTrue("frames: $n", n >= 11)
        } finally {
            ex.release()
        }
        // The alpha stream travels in BlockAdditional elements (id 0xA5), one per frame.
        val text = String(f.readBytes(), Charsets.ISO_8859_1)
        assertTrue(text.contains("V_VP8"))
        assertTrue(Regex("î\u0081\u0001¥").findAll(text).count() >= 11)
    }
}
