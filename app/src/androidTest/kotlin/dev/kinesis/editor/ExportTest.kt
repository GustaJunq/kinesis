package dev.kinesis.editor

import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.kinesis.core.edit.Factory
import dev.kinesis.core.model.Asset
import dev.kinesis.core.model.AssetType
import dev.kinesis.core.model.KnsProject
import dev.kinesis.core.scene.SceneEvaluator
import dev.kinesis.editor.media.ExportQuality
import dev.kinesis.editor.media.ExportResolution
import dev.kinesis.editor.media.ExportSettings
import dev.kinesis.editor.media.PcmCache
import dev.kinesis.editor.media.VideoExporter
import dev.kinesis.editor.render.AssetFiles
import dev.kinesis.editor.render.Compositor
import dev.kinesis.editor.render.RenderResources
import dev.kinesis.editor.render.RenderSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import kotlin.math.PI
import kotlin.math.sin

/** Exports MP4s with the real encoders and decodes them back. */
@RunWith(AndroidJUnit4::class)
class ExportTest {
    private val app = InstrumentationRegistry.getInstrumentation().targetContext
    private val dir = File(app.cacheDir, "export-test").apply { deleteRecursively(); mkdirs() }

    private fun writeTone(file: File, seconds: Double) {
        val rate = 44_100
        val frames = (seconds * rate).toInt()
        DataOutputStream(FileOutputStream(file).buffered()).use { out ->
            fun le32(v: Int) { out.write(v and 0xFF); out.write((v shr 8) and 0xFF); out.write((v shr 16) and 0xFF); out.write((v shr 24) and 0xFF) }
            fun le16(v: Int) { out.write(v and 0xFF); out.write((v shr 8) and 0xFF) }
            out.writeBytes("RIFF"); le32(36 + frames * 4); out.writeBytes("WAVE")
            out.writeBytes("fmt "); le32(16); le16(1); le16(2); le32(rate); le32(rate * 4); le16(4); le16(16)
            out.writeBytes("data"); le32(frames * 4)
            for (i in 0 until frames) {
                val s = (sin(2 * PI * 440.0 * i / rate) * 12000).toInt()
                le16(s); le16(s)
            }
        }
    }

    private fun export(project: KnsProject, files: AssetFiles, name: String): File {
        val out = File(dir, "$name.mp4")
        val exporter = VideoExporter(app.assets, files, PcmCache(File(dir, "pcm-$name"), files))
        exporter.export(project, ExportSettings(ExportResolution.P720, ExportQuality.STANDARD, includeAudio = true), out, {}, { false })
        return out
    }

    @Test
    fun exportsVideoWithAudioAndReimportsIt() {
        val tone = File(dir, "tone.wav").also { writeTone(it, 2.5) }
        val base = loadExample("basicos/3d-camera-orbita")
        var project = base.copy(composition = base.composition.copy(duration = 2.0))
        val audioAsset = Asset("tone", AssetType.AUDIO, "assets/tone.wav", duration = 2.5)
        project = project.copy(assets = listOf(audioAsset))
        project = project.copy(layers = project.layers + Factory.audio(project, 0.0, audioAsset, "Tone"))
        val files = AssetFiles { id -> if (id == "tone") tone else null }

        val started = System.nanoTime()
        val mp4 = export(project, files, "cube")
        android.util.Log.i("KTEST", "export took ${(System.nanoTime() - started) / 1_000_000} ms, ${mp4.length()} bytes")
        assertTrue("output exists", mp4.isFile && mp4.length() > 10_000)

        val r = MediaMetadataRetriever()
        r.setDataSource(mp4.path)
        val duration = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong()
        assertTrue("duration $duration ms", duration in 1800..2300)
        assertEquals("720", r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH))
        assertEquals("1280", r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT))
        assertEquals("yes", r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO))
        val frame = r.getFrameAtTime(1_000_000, MediaMetadataRetriever.OPTION_CLOSEST)
        assertNotNull(frame)
        Shots.log("export-frame@1.0", frame!!)
        r.release()

        val ex = MediaExtractor()
        ex.setDataSource(mp4.path)
        val mimes = (0 until ex.trackCount).map { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME) }
        ex.release()
        assertTrue(mimes.toString(), mimes.contains("video/avc") && mimes.contains("audio/mp4a-latm"))

        // Use the export as a video layer: exercises the MediaCodec → OES texture path.
        var p2 = Factory.newProject("video", 720, 1280, 30.0, 2.0, dev.kinesis.core.math.Color.BLACK, null, "test")
        val clip = Asset("clip", AssetType.VIDEO, "assets/clip.mp4", width = 720, height = 1280, duration = 2.0, hasAudio = true)
        p2 = p2.copy(assets = listOf(clip))
        val video = Factory.video(p2, 0.0, clip, "Clip")
        p2 = p2.copy(layers = listOf(video.copy(transform = video.transform.copy(rotation = dev.kinesis.core.anim.anim(8f))), Factory.text(p2, 0.0, "over video", "t")))
        val videoFiles = AssetFiles { id -> if (id == "clip") mp4 else null }
        PbufferContext().use {
            val res = RenderResources(app.assets, videoFiles)
            val comp = Compositor(res)
            for (t in listOf(0.5, 1.5, 0.2)) {
                // The emulator's software GL can present a decoded frame late; retry before judging it empty.
                var bmp = comp.readPixels(comp.render(p2, SceneEvaluator.evaluate(p2, t), 360, 640, RenderSettings(1)))
                var attempt = 0
                while (differingFraction(bmp, android.graphics.Color.BLACK) <= 0.2f && attempt++ < 4) {
                    Thread.sleep(250)
                    bmp = comp.readPixels(comp.render(p2, SceneEvaluator.evaluate(p2, t), 360, 640, RenderSettings(1)))
                }
                Shots.log("video-layer@$t", bmp)
                assertTrue("video frame at $t is empty", differingFraction(bmp, android.graphics.Color.BLACK) > 0.2f)
            }
            comp.release()
            res.release()
        }

        // And re-export the project that contains the video (decode + encode + audio from the video track).
        val second = export(p2, videoFiles, "reexport")
        assertTrue(second.isFile && second.length() > 10_000)
    }

    /** A cancelled export keeps its finished blocks and the next export of the same project resumes from them. */
    @Test
    fun cancelledExportResumesFromItsBlocks() {
        var p = Factory.newProject("blocks", 360, 640, 15.0, 11.0, dev.kinesis.core.math.Color.parse("#203040"), null, "test")
        p = p.copy(layers = listOf(Factory.text(p, 0.0, "blocks", "t").copy(duration = 11.0)))
        val files = AssetFiles { null }
        val work = File(dir, "work")
        val out = File(dir, "blocks.mp4")
        val exporter = VideoExporter(app.assets, files, PcmCache(File(dir, "pcm-blocks"), files))
        val settings = ExportSettings(ExportResolution.ORIGINAL, ExportQuality.STANDARD, includeAudio = false)
        var last = 0f
        try {
            exporter.export(p, settings, out, { last = it }, { last > 0.5f }, workDir = work)
            throw AssertionError("export was not cancelled")
        } catch (e: dev.kinesis.editor.media.ExportCancelled) {
            // expected
        }
        val kept = work.walk().filter { it.name.startsWith("video-") && it.name.endsWith(".mp4") }.count()
        assertTrue("blocks kept: $kept", kept >= 1)
        var firstReport = -1f
        exporter.export(p, settings, out, { if (firstReport < 0f) firstReport = it }, { false }, workDir = work)
        assertTrue("resumed at $firstReport", firstReport >= 0.3f)
        val r = MediaMetadataRetriever()
        r.setDataSource(out.path)
        val duration = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong()
        assertTrue("duration $duration ms", duration in 10_500..11_500)
        assertNotNull(r.getFrameAtTime(10_000_000, MediaMetadataRetriever.OPTION_CLOSEST))
        r.release()
        assertTrue("blocks removed after success", work.walk().none { it.name.startsWith("video-") })
    }

    /** Pre-rendering a layer: colour on the left half, alpha on the right, then drawn back as a packed video. */
    @Test
    fun packedAlphaPreRender() {
        var p = Factory.newProject("packed", 360, 640, 15.0, 2.0, dev.kinesis.core.math.Color.parse("#FF0000"), null, "test")
        val star0 = Factory.shape(p, 0.0, dev.kinesis.core.model.ShapeKind.STAR, "star")
        val star = star0.copy(shape = star0.shape!!.copy(fill = star0.shape!!.fill.copy(color = dev.kinesis.core.anim.anim(dev.kinesis.core.math.Color.parse("#FFFF00")))))
        p = p.copy(layers = listOf(star))
        val files = AssetFiles { null }
        val out = File(dir, "packed.mp4")
        val exporter = VideoExporter(app.assets, files, PcmCache(File(dir, "pcm-packed"), files))
        val range = dev.kinesis.core.edit.PreRender.range(p, star.id)!!
        exporter.export(dev.kinesis.core.edit.PreRender.isolate(p, star.id), ExportSettings(ExportResolution.ORIGINAL, ExportQuality.HIGH, includeAudio = false), out, {}, { false }, range = range, packAlpha = true)
        val r = MediaMetadataRetriever()
        r.setDataSource(out.path)
        assertEquals("720", r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH))
        val frame = r.getFrameAtTime(500_000, MediaMetadataRetriever.OPTION_CLOSEST)!!
        r.release()
        Shots.log("packed-frame", frame)
        // Alpha half: white in the middle of the star, black in the corner (the red background is not rendered).
        assertTrue(android.graphics.Color.red(frame.getPixel(540, 320)) > 200)
        assertTrue(android.graphics.Color.red(frame.getPixel(370, 10)) < 40)

        // Shown as a packed video over a blue background: the star, and blue around it.
        var p2 = Factory.newProject("over", 360, 640, 15.0, 2.0, dev.kinesis.core.math.Color.parse("#0000FF"), null, "test")
        val asset = Asset("pre", AssetType.VIDEO, "assets/pre.mp4", width = 720, height = 640, duration = 2.0)
        p2 = dev.kinesis.core.edit.PreRender.replace(p2.copy(layers = listOf(star)), star.id, asset, range, "pre").first
        val videoFiles = AssetFiles { id -> if (id == "pre") out else null }
        PbufferContext().use {
            val res = RenderResources(app.assets, videoFiles)
            val comp = Compositor(res)
            var bmp = comp.readPixels(comp.render(p2, SceneEvaluator.evaluate(p2, 0.5), 360, 640, RenderSettings(1)))
            var attempt = 0
            while (android.graphics.Color.blue(bmp.getPixel(180, 320)) > 200 && attempt++ < 4) {
                Thread.sleep(250)
                bmp = comp.readPixels(comp.render(p2, SceneEvaluator.evaluate(p2, 0.5), 360, 640, RenderSettings(1)))
            }
            Shots.log("packed-over-blue", bmp)
            assertTrue("corner stays blue", android.graphics.Color.blue(bmp.getPixel(5, 5)) > 200)
            assertTrue("star drawn in the middle", android.graphics.Color.blue(bmp.getPixel(180, 320)) < 120)
            comp.release()
            res.release()
        }
    }
}
