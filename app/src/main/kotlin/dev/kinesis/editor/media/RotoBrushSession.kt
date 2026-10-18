package dev.kinesis.editor.media

import android.content.Context
import android.graphics.Bitmap
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.ByteBufferExtractor
import com.google.mediapipe.tasks.components.containers.NormalizedKeypoint
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.interactivesegmenter.InteractiveSegmenter
import dev.kinesis.core.model.RotoBrush
import dev.kinesis.core.model.RotoStroke
import dev.kinesis.core.video.I420Frame
import dev.kinesis.core.video.MaskTools
import dev.kinesis.core.video.ObjectSegmenter
import dev.kinesis.core.video.RotoOps
import dev.kinesis.core.video.RotoPropagator
import dev.kinesis.core.video.Yuv
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * "Which object is under this point?" on the device, with MediaPipe's Interactive Segmenter and its Magic Touch model
 * (Apache-2.0 library and model, see the third-party licenses). The model takes ONE point per question, so several seeds
 * are asked one by one and the answers joined; it finds any kind of object, not only people.
 */
class InteractiveObjectSegmenter(context: Context) : ObjectSegmenter, AutoCloseable {
    private val segmenter: InteractiveSegmenter = InteractiveSegmenter.createFromOptions(
        context.applicationContext,
        InteractiveSegmenter.InteractiveSegmenterOptions.builder()
            .setBaseOptions(BaseOptions.builder().setModelAssetPath(MODEL).build())
            .setOutputConfidenceMasks(true)
            .setOutputCategoryMask(false)
            .build(),
    )
    private var bitmap: Bitmap? = null

    override fun segment(frame: IntArray, w: Int, h: Int, seeds: List<IntArray>): FloatArray {
        val bmp = bitmap?.takeIf { it.width == w && it.height == h } ?: Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { bitmap?.recycle(); bitmap = it }
        bmp.setPixels(frame, 0, w, 0, 0, w, h)
        val input = BitmapImageBuilder(bmp).build()
        val out = FloatArray(w * h)
        try {
            for (seed in seeds) {
                val roi = InteractiveSegmenter.RegionOfInterest.create(NormalizedKeypoint.create((seed[0] + 0.5f) / w, (seed[1] + 0.5f) / h))
                val result = segmenter.segment(input, roi)
                val masks = result.confidenceMasks().orElse(null) ?: continue
                try {
                    val m = if (masks.size >= 2) masks[1] else masks.firstOrNull() ?: continue
                    var one = FloatArray(m.width * m.height)
                    ByteBufferExtractor.extract(m).asFloatBuffer().get(one)
                    if (m.width != w || m.height != h) one = MaskTools.resize(one, m.width, m.height, w, h)
                    // The object must contain the point that was asked about; a mask that does not is the background\'s.
                    if (masks.size < 2 && one[seed[1] * w + seed[0]] < 0.5f) one = FloatArray(one.size) { 1f - one[it] }
                    if (one[seed[1] * w + seed[0]] < 0.5f) continue
                    for (i in out.indices) if (one[i] > out[i]) out[i] = one[i]
                } finally {
                    masks.forEach { it.close() }
                }
            }
        } finally {
            input.close()
        }
        return out
    }

    override fun close() {
        runCatching { segmenter.close() }
        bitmap?.recycle()
        bitmap = null
    }

    companion object {
        const val MODEL = "models/magic_touch.tflite"
    }
}

/** Fixed-size records in a file: frames and masks live on disk so a long clip does not fill the memory of a small phone. */
internal class RecordStore(private val file: File, private val recordBytes: Int, val count: Int) : AutoCloseable {
    private val raf = RandomAccessFile(file, "rw").also { it.setLength(recordBytes.toLong() * count) }

    @Synchronized fun write(index: Int, bytes: ByteArray) {
        raf.seek(index.toLong() * recordBytes)
        raf.write(bytes, 0, recordBytes)
    }

    @Synchronized fun read(index: Int, into: ByteArray) {
        raf.seek(index.toLong() * recordBytes)
        raf.readFully(into, 0, recordBytes)
    }

    override fun close() {
        runCatching { raf.close() }
        file.delete()
    }
}

/**
 * One Roto Brush editing session on a stretch of a video: the frames are decoded once (small, upright) so scrubbing and
 * painting are instant, the masks found so far are kept per frame, and the painted strokes are followed from frame to
 * frame by [RotoPropagator]. [render] writes the packed-alpha video.
 */
class RotoBrushSession(
    private val context: Context,
    private val source: File,
    val startUs: Long,
    endUs: Long,
    private val workDir: File,
) : AutoCloseable {
    private val trackInfo: VideoTrackInfo = YuvVideoReader(source).use { it.info }
    val fps: Double = trackInfo.frameRate
    val frameCount: Int = min(MAX_FRAMES, ceil((endUs - startUs) / 1e6 * fps).toInt().coerceAtLeast(1))
    val endUs: Long = min(endUs, startUs + (frameCount / fps * 1e6).toLong())

    /** Upright size of the working frames and masks. */
    val width: Int
    val height: Int
    private val step: Int = ceil(max(trackInfo.width, trackInfo.height) / WORK_SIDE.toDouble()).toInt().coerceAtLeast(1)
    private val rawW = trackInfo.width / step
    private val rawH = (trackInfo.height / step) and 1.inv()

    init {
        val (rw, rh) = MaskTools.rotatedSize(rawW, rawH, trackInfo.rotation)
        width = rw
        height = rh and 1.inv()
        workDir.mkdirs()
    }

    /** 0 = no cut-out yet, 1 = followed from a painted frame, 2 = followed but the object was lost (a guess), 3 = painted. */
    val status = ByteArray(frameCount)
    private val frames = RecordStore(File(workDir, "frames.bin"), width * height * 4, frameCount)
    private val masks = RecordStore(File(workDir, "masks.bin"), width * height, frameCount)
    private var prepared = false

    fun timeOfFrame(i: Int): Double = i / fps
    fun frameOfTime(seconds: Double): Int = (seconds * fps).roundToInt().coerceIn(0, frameCount - 1)

    /** Decodes the whole stretch into the frame store. Returns false when cancelled. */
    fun prepare(progress: (Float) -> Unit, cancelled: () -> Boolean): Boolean {
        if (prepared) return true
        YuvVideoReader(source).use { reader ->
            val src = I420Frame(trackInfo.width, trackInfo.height)
            val raw = IntArray(rawW * rawH)
            val upright = IntArray(width * height)
            val bytes = ByteBuffer.allocate(width * height * 4)
            var last = -1
            val span = (endUs - startUs).coerceAtLeast(1L)
            reader.read(startUs, endUs) { frame, pts ->
                if (cancelled()) return@read false
                frame.toArrays(src)
                Yuv.i420ToArgb(src.y, trackInfo.width, src.u, src.chromaWidth, src.v, src.chromaWidth, rawW * step, rawH * step, step, raw)
                val rotated = MaskTools.rotateArgb(raw, rawW, rawH, trackInfo.rotation)
                System.arraycopy(rotated, 0, upright, 0, upright.size)
                bytes.clear(); bytes.asIntBuffer().put(upright)
                val index = (((pts - startUs) / 1e6) * fps).roundToInt().coerceIn(0, frameCount - 1)
                // Frames the file skips repeat the previous one; a repeated slot keeps its first picture.
                for (k in (last + 1)..index) frames.write(k, bytes.array())
                last = max(last, index)
                progress(((pts - startUs).toFloat() / span).coerceIn(0f, 1f))
                true
            }
            if (cancelled()) return false
            if (last < 0) throw IOException("no frames could be read")
            for (k in (last + 1) until frameCount) frames.write(k, bytes.array())
        }
        prepared = true
        return true
    }

    /** The picture of frame [i], upright ARGB, [width]×[height]. */
    fun frame(i: Int, into: IntArray) {
        val bytes = ByteArray(width * height * 4)       // local: the screen and a running step may both read
        frames.read(i.coerceIn(0, frameCount - 1), bytes)
        ByteBuffer.wrap(bytes).asIntBuffer().get(into)
    }

    /** The cut-out of frame [i] (0..1), or null when there is none yet. */
    fun mask(i: Int): FloatArray? {
        if (status[i].toInt() == 0) return null
        val bytes = ByteArray(width * height)
        masks.read(i, bytes)
        return FloatArray(bytes.size) { (bytes[it].toInt() and 0xFF) / 255f }
    }

    private fun storeMask(i: Int, mask: FloatArray, state: Int) {
        val bytes = ByteArray(mask.size) { (mask[it].coerceIn(0f, 1f) * 255f + 0.5f).toInt().toByte() }
        masks.write(i, bytes)
        status[i] = state.toByte()
    }

    /**
     * Follows the painted strokes through the stretch. [strokes] are the strokes of each painted frame; [changed] is the
     * frame the person just painted (only it and the frames after it up to the next painted one are redone), or null to
     * redo everything. Returns false when cancelled.
     */
    fun propagate(strokes: Map<Int, List<RotoStroke>>, changed: Int?, progress: (Float) -> Unit, cancelled: () -> Boolean): Boolean {
        val painted = strokes.filterValues { it.isNotEmpty() }.keys.filter { it in 0 until frameCount }
        // Frames that lost their strokes are no longer keys: their old results are stale.
        if (changed == null || changed !in painted) {
            for (i in 0 until frameCount) if (status[i].toInt() == 3 && i !in painted) status[i] = 0
        }
        val segments = if (changed != null && changed in painted) RotoOps.after(painted, changed, 0, frameCount - 1) else RotoOps.plan(painted, 0, frameCount - 1)
        if (segments.isEmpty()) {
            if (changed == null) for (i in 0 until frameCount) status[i] = 0
            progress(1f)
            return true
        }
        val total = segments.sumOf { 1 + RotoOps.framesOf(it).size }.coerceAtLeast(1)
        var done = 0
        val pixels = IntArray(width * height)
        InteractiveObjectSegmenter(context).use { model ->
            val follower = RotoPropagator(model, width, height)
            for (segment in segments) {
                if (cancelled()) return false
                val key = segment.start
                frame(key, pixels)
                val existing = mask(key)?.takeIf { status[key].toInt() != 0 }
                val onlyRemoves = strokes[key].orEmpty().none { it.mode == dev.kinesis.core.model.RotoMode.ADD }
                val keyResult = if (onlyRemoves && existing != null) {
                    RotoPropagator.Result(RotoOps.applyStrokes(existing, strokes[key].orEmpty(), width, height), true)
                } else {
                    follower.painted(pixels, strokes[key].orEmpty())
                }
                storeMask(key, keyResult.mask, 3)
                progress(++done / total.toFloat())
                var previous = keyResult.mask
                for (i in RotoOps.framesOf(segment)) {
                    if (cancelled()) return false
                    frame(i, pixels)
                    val r = follower.next(pixels, previous)
                    storeMask(i, r.mask, if (r.confident) 1 else 2)
                    previous = r.mask
                    progress(++done / total.toFloat())
                }
            }
        }
        return true
    }

    /**
     * Cuts out one painted frame at once (a single question to the network), so painting answers right away. The frames
     * that depended on it, up to the next painted frame, lose their cut-out until [propagate] runs again.
     */
    fun paintOne(frame: Int, strokes: Map<Int, List<RotoStroke>>) {
        val mine = strokes[frame].orEmpty()
        val pixels = IntArray(width * height)
        if (mine.isEmpty()) {
            status[frame] = 0
        } else {
            frame(frame, pixels)
            val existing = mask(frame)
            val onlyRemoves = mine.none { it.mode == dev.kinesis.core.model.RotoMode.ADD }
            val result = if (onlyRemoves && existing != null) {
                RotoPropagator.Result(RotoOps.applyStrokes(existing, mine, width, height), true)
            } else {
                InteractiveObjectSegmenter(context).use { RotoPropagator(it, width, height).painted(pixels, mine) }
            }
            storeMask(frame, result.mask, 3)
        }
        val painted = strokes.filterValues { it.isNotEmpty() }.keys.sorted()
        val next = painted.firstOrNull { it > frame } ?: frameCount
        for (i in frame + 1 until next) status[i] = 0
        if (painted.isEmpty() || frame <= painted.first()) for (i in 0 until frame) if (status[i].toInt() != 3) status[i] = 0
    }

    /** How many frames have a cut-out. */
    val cutCount: Int get() = status.count { it.toInt() != 0 }

    /** Writes the packed-alpha video of the stretch from the masks. Null when cancelled. */
    fun render(target: File, progress: (Float) -> Unit, cancelled: () -> Boolean): PackedAlpha.Result? {
        if (status.all { it.toInt() == 0 }) throw IOException("nothing is cut out yet")
        return PackedAlpha.render(
            source, target, startUs, endUs,
            { index, _, w, h ->
                val i = index.coerceIn(0, frameCount - 1)
                // A frame without a cut-out takes the nearest one.
                val j = (0 until frameCount).minByOrNull { k -> if (status[k].toInt() == 0) Int.MAX_VALUE else kotlin.math.abs(k - i) } ?: i
                val m = mask(j) ?: FloatArray(width * height)
                if (width == w && height == h) m else MaskTools.resize(m, width, height, w, h)
            },
            progress, cancelled,
        )
    }

    /** The recipe to save with the layer. */
    fun recipe(assetId: String, sourceStartSeconds: Double, strokes: Map<Int, List<RotoStroke>>): RotoBrush = RotoBrush(
        source = assetId,
        sourceStart = sourceStartSeconds,
        duration = (endUs - startUs) / 1e6,
        frames = strokes.filterValues { it.isNotEmpty() }.toSortedMap().map { (i, s) -> dev.kinesis.core.model.RotoFrame(timeOfFrame(i), s) }.take(RotoBrush.MAX_FRAMES),
    )

    override fun close() {
        frames.close()
        masks.close()
    }

    companion object {
        /** Longest stretch of video handled at once, in frames (the frames and masks are kept on disk). */
        const val MAX_FRAMES = 360

        /** Frames are decoded at about this size on the long side for painting and for the network. */
        const val WORK_SIDE = 512
    }
}
