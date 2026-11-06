package dev.kinesis.core

import dev.kinesis.core.export.GifEncoder
import dev.kinesis.core.export.MovWriter
import dev.kinesis.core.export.PngSequenceWriter
import dev.kinesis.core.export.WebmWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.util.zip.ZipInputStream
import javax.imageio.ImageIO

/**
 * The export containers. When ffmpeg/ffprobe are installed the files are also opened by them (what other editors
 * use underneath), checking codec, size, frame count and that the alpha channel survives.
 */
class ExportFormatsTest {
    private val w = 64
    private val h = 48
    private val tmp = Files.createTempDirectory("kns-export").toFile()

    private fun has(tool: String) = runCatching { ProcessBuilder(tool, "-version").redirectErrorStream(true).start().waitFor() == 0 }.getOrDefault(false)

    private fun run(vararg cmd: String): String {
        val p = ProcessBuilder(*cmd).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        assertEquals(out, 0, p.waitFor())
        return out
    }

    /** A moving opaque square on a transparent background, with a half-transparent band. */
    private fun frame(i: Int): IntArray = IntArray(w * h) { k ->
        val x = k % w; val y = k / w
        when {
            x in (i * 4)..(i * 4 + 15) && y in 10..30 -> 0xFFFF3020.toInt()
            y > 40 -> 0x800020FF.toInt()
            else -> 0x00000000
        }
    }

    private fun png(argb: IntArray): ByteArray {
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
        img.setRGB(0, 0, w, h, argb, 0, w)
        return ByteArrayOutputStream().also { ImageIO.write(img, "png", it) }.toByteArray()
    }

    private fun probe(f: File) = run("ffprobe", "-v", "error", "-count_frames", "-show_streams", "-show_format", f.path)

    @Test
    fun gifDecodesWithTransparency() {
        val f = File(tmp, "a.gif")
        GifEncoder(f.outputStream(), w, h, colors = 64).use { g -> repeat(8) { g.addFrame(frame(it), 4) } }
        val readers = ImageIO.getImageReadersByFormatName("gif").next()
        readers.input = ImageIO.createImageInputStream(f)
        assertEquals(8, readers.getNumImages(true))
        val first = readers.read(0)
        assertEquals(0, first.getRGB(0, 0) ushr 24)
        assertEquals(0xFF, first.getRGB(2, 20) ushr 24)
        val red = first.getRGB(5, 20)
        assertTrue("red: ${Integer.toHexString(red)}", ((red shr 16) and 0xff) > 200 && ((red shr 8) and 0xff) < 90)
        if (has("ffprobe")) {
            val p = probe(f)
            assertTrue(p, p.contains("codec_name=gif") && p.contains("nb_read_frames=8"))
        }
    }

    @Test
    fun gifLzwHandlesLargeNoisyFrames() {
        val big = 300
        val rnd = java.util.Random(1)
        val f = File(tmp, "noise.gif")
        GifEncoder(f.outputStream(), big, big, colors = 256, dither = false).use { g ->
            g.addFrame(IntArray(big * big) { 0xFF000000.toInt() or rnd.nextInt(0xFFFFFF) }, 10)
        }
        val img = ImageIO.read(f)
        assertEquals(big, img.width)
    }

    @Test
    fun movWithPngFramesKeepsAlpha() {
        val f = File(tmp, "a.mov")
        MovWriter(f, w, h, 30.0).use { m -> repeat(6) { m.addFrame(png(frame(it))) } }
        assumeTrue(has("ffprobe") && has("ffmpeg"))
        val p = probe(f)
        assertTrue(p, p.contains("codec_name=png") && p.contains("width=$w") && p.contains("nb_read_frames=6") && p.contains("pix_fmt=rgba"))
        assertTrue(p, p.contains("r_frame_rate=30/1"))
        val out = File(tmp, "mov-frame.png")
        run("ffmpeg", "-v", "error", "-y", "-i", f.path, "-frames:v", "1", out.path)
        val img = ImageIO.read(out)
        assertEquals(0, img.getRGB(0, 0) ushr 24)
        assertEquals(0x80, img.getRGB(0, 45) ushr 24)
    }

    @Test
    fun movNtscRate() {
        val f = File(tmp, "b.mov")
        MovWriter(f, w, h, 30000.0 / 1001).use { m -> repeat(3) { m.addFrame(png(frame(it))) } }
        assumeTrue(has("ffprobe"))
        assertTrue(probe(f).contains("r_frame_rate=30000/1001"))
    }

    /** VP8 frames from an IVF file. */
    private fun ivf(f: File): List<ByteArray> {
        val b = ByteBuffer.wrap(f.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        var o = b.getShort(6).toInt()
        val out = ArrayList<ByteArray>()
        while (o + 12 <= b.limit()) {
            val n = b.getInt(o); o += 12
            out += ByteArray(n).also { b.position(o); b.get(it) }
            o += n
        }
        return out
    }

    @Test
    fun webmWithAlphaIsReadByLibvpx() {
        assumeTrue(has("ffmpeg") && has("ffprobe") && run("ffmpeg", "-hide_banner", "-encoders").contains("libvpx "))
        // Colour and alpha streams encoded separately, as the app does with MediaCodec.
        val dir = File(tmp, "seq").apply { mkdirs() }
        repeat(10) { i -> File(dir, "f%02d.png".format(i)).writeBytes(png(frame(i))) }
        val color = File(tmp, "c.ivf"); val alpha = File(tmp, "a.ivf")
        run("ffmpeg", "-v", "error", "-y", "-framerate", "25", "-i", "$dir/f%02d.png", "-vf", "format=yuv420p", "-c:v", "libvpx", "-g", "5", "-b:v", "500k", color.path)
        run("ffmpeg", "-v", "error", "-y", "-framerate", "25", "-i", "$dir/f%02d.png", "-vf", "alphaextract,format=yuv420p", "-c:v", "libvpx", "-g", "5", "-b:v", "500k", alpha.path)
        val c = ivf(color); val a = ivf(alpha)
        assertEquals(10, c.size)
        val f = File(tmp, "a.webm")
        WebmWriter(f, w, h, 25.0).use { wr -> c.indices.forEach { i -> wr.addFrame(c[i], a[i], keyFrame = (c[i][0].toInt() and 1) == 0) } }
        val p = probe(f)
        assertTrue(p, p.contains("codec_name=vp8") && p.contains("nb_read_frames=10") && p.lowercase().contains("alpha_mode=1"))
        assertTrue(p, p.contains("duration=0.4"))
        val out = File(tmp, "webm-frame.png")
        run("ffmpeg", "-v", "error", "-y", "-c:v", "libvpx", "-i", f.path, "-frames:v", "1", "-pix_fmt", "rgba", out.path)
        val img = ImageIO.read(out)
        assertTrue(img.colorModel.hasAlpha())
        assertTrue("corner alpha ${img.getRGB(0, 0) ushr 24}", (img.getRGB(0, 0) ushr 24) < 30)
        assertTrue((img.getRGB(5, 20) ushr 24) > 220)
        // Seeking into the middle uses the cues and the key frames.
        run("ffmpeg", "-v", "error", "-y", "-ss", "0.25", "-c:v", "libvpx", "-i", f.path, "-frames:v", "1", File(tmp, "seek.png").path)
    }

    @Test
    fun pngSequenceZip() {
        val f = File(tmp, "seq.zip")
        PngSequenceWriter(f.outputStream(), "clip", 24.0).use { s -> repeat(3) { s.addFrame(png(frame(it))) } }
        val names = ZipInputStream(f.inputStream()).use { z -> generateSequence { z.nextEntry }.map { it.name }.toList() }
        assertEquals(listOf("clip_00000.png", "clip_00001.png", "clip_00002.png", "LEIA-ME.txt"), names)
    }
}
