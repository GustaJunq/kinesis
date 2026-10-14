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
        val base = loadExample("metal-cube")
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
}
