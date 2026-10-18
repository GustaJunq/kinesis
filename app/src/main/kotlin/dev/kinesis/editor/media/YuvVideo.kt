package dev.kinesis.editor.media

import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** A planar YUV 4:2:0 frame in direct buffers (what the native code reads in place): strides are [width] and [chromaWidth]. */
class DirectI420(val width: Int, val height: Int) {
    val chromaWidth = (width + 1) / 2
    val chromaHeight = (height + 1) / 2
    val y: ByteBuffer = ByteBuffer.allocateDirect(width * height)
    val u: ByteBuffer = ByteBuffer.allocateDirect(chromaWidth * chromaHeight)
    val v: ByteBuffer = ByteBuffer.allocateDirect(chromaWidth * chromaHeight)

    /** The three planes as plain arrays (for the pure-Kotlin colour conversion). */
    fun toArrays(into: dev.kinesis.core.video.I420Frame) {
        y.rewind(); y.get(into.y)
        u.rewind(); u.get(into.u)
        v.rewind(); v.get(into.v)
    }

    fun fromArrays(from: dev.kinesis.core.video.I420Frame) {
        y.rewind(); y.put(from.y)
        u.rewind(); u.put(from.u)
        v.rewind(); v.put(from.v)
    }
}

/** What the video track of a file looks like. Width and height are the decoded size rounded down to even numbers. */
class VideoTrackInfo(val width: Int, val height: Int, val rotation: Int, val frameRate: Double, val durationUs: Long, val mime: String)

/** Copies between MediaCodec images and [DirectI420], honouring row and pixel strides and the crop rectangle. */
internal object ImageCopy {
    fun toDirect(image: Image, out: DirectI420) {
        val crop = image.cropRect
        copyPlane(image.planes[0], crop.left, crop.top, out.width, out.height, out.y, out.width)
        copyPlane(image.planes[1], crop.left / 2, crop.top / 2, out.chromaWidth, out.chromaHeight, out.u, out.chromaWidth)
        copyPlane(image.planes[2], crop.left / 2, crop.top / 2, out.chromaWidth, out.chromaHeight, out.v, out.chromaWidth)
    }

    private fun copyPlane(p: Image.Plane, left: Int, top: Int, w: Int, h: Int, dst: ByteBuffer, dstStride: Int) {
        val src = p.buffer
        val rs = p.rowStride; val ps = p.pixelStride
        val row = ByteArray(max(w * ps, 1))
        for (r in 0 until h) {
            val start = (top + r) * rs + left * ps
            val avail = min(row.size, src.limit() - start)
            if (avail <= 0) break
            src.position(start)
            src.get(row, 0, avail)
            dst.position(r * dstStride)
            if (ps == 1) dst.put(row, 0, min(w, avail))
            else for (c in 0 until w) if (c * ps < avail) dst.put(r * dstStride + c, row[c * ps])
        }
        dst.rewind()
    }

    fun fromDirect(frame: DirectI420, image: Image) {
        putPlane(frame.y, frame.width, frame.width, frame.height, image.planes[0])
        putPlane(frame.u, frame.chromaWidth, frame.chromaWidth, frame.chromaHeight, image.planes[1])
        putPlane(frame.v, frame.chromaWidth, frame.chromaWidth, frame.chromaHeight, image.planes[2])
    }

    private fun putPlane(src: ByteBuffer, srcStride: Int, w: Int, h: Int, p: Image.Plane) {
        val dst = p.buffer
        val rs = p.rowStride; val ps = p.pixelStride
        val row = ByteArray(w)
        for (r in 0 until h) {
            src.position(r * srcStride)
            src.get(row, 0, w)
            val start = r * rs
            if (ps == 1) {
                dst.position(start)
                dst.put(row, 0, w)
            } else {
                for (c in 0 until w) dst.put(start + c * ps, row[c])
            }
        }
        src.rewind()
    }
}

/** Decodes the video track of [source] into [DirectI420] frames, in presentation order. */
class YuvVideoReader(private val source: File) : AutoCloseable {
    private val extractor = MediaExtractor()
    private var codec: MediaCodec? = null
    val info: VideoTrackInfo

    init {
        extractor.setDataSource(source.path)
        val track = (0 until extractor.trackCount).firstOrNull {
            extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
        } ?: throw IOException("no video track")
        extractor.selectTrack(track)
        val f = extractor.getTrackFormat(track)
        val w = f.getInteger(MediaFormat.KEY_WIDTH)
        val h = f.getInteger(MediaFormat.KEY_HEIGHT)
        info = VideoTrackInfo(
            width = w and 1.inv(), height = h and 1.inv(),
            rotation = if (f.containsKey(MediaFormat.KEY_ROTATION)) f.getInteger(MediaFormat.KEY_ROTATION) else 0,
            frameRate = if (f.containsKey(MediaFormat.KEY_FRAME_RATE)) f.getInteger(MediaFormat.KEY_FRAME_RATE).toDouble().coerceAtLeast(1.0) else 30.0,
            durationUs = if (f.containsKey(MediaFormat.KEY_DURATION)) f.getLong(MediaFormat.KEY_DURATION) else 0L,
            mime = f.getString(MediaFormat.KEY_MIME).orEmpty(),
        )
        f.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
        codec = MediaCodec.createDecoderByType(info.mime).also {
            it.configure(f, null, null, 0)
            it.start()
        }
    }

    /**
     * Decodes from [startUs] up to [endUs] and calls [onFrame] with each frame (the same [DirectI420] object each time:
     * copy what you need) and its presentation time. Stop early by returning false.
     */
    fun read(startUs: Long, endUs: Long, onFrame: (DirectI420, Long) -> Boolean) {
        val c = codec ?: throw IOException("closed")
        extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        c.flush()
        val bufInfo = MediaCodec.BufferInfo()
        val frame = DirectI420(info.width, info.height)
        var inputDone = false
        var outputDone = false
        while (!outputDone) {
            if (!inputDone) {
                val i = c.dequeueInputBuffer(10_000)
                if (i >= 0) {
                    val n = extractor.readSampleData(c.getInputBuffer(i)!!, 0)
                    if (n < 0 || extractor.sampleTime > endUs) {
                        c.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        c.queueInputBuffer(i, 0, n, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }
            val o = c.dequeueOutputBuffer(bufInfo, 10_000)
            if (o >= 0) {
                val pts = bufInfo.presentationTimeUs
                var keepGoing = true
                if (bufInfo.size > 0 && pts >= startUs && pts < endUs) {
                    val image = c.getOutputImage(o)
                    if (image != null) {
                        try { ImageCopy.toDirect(image, frame) } finally { image.close() }
                        keepGoing = onFrame(frame, pts)
                    }
                }
                c.releaseOutputBuffer(o, false)
                if ((bufInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0 || pts >= endUs || !keepGoing) outputDone = true
            }
        }
    }

    override fun close() {
        runCatching { codec?.stop() }
        runCatching { codec?.release() }
        codec = null
        runCatching { extractor.release() }
    }
}

/**
 * Encodes [DirectI420] frames as H.264 into an MP4 and, when [audioSource] is given, copies its AAC/AMR audio
 * (between [audioStartUs] and [audioEndUs], re-based to 0) without re-encoding.
 */
class YuvVideoWriter(
    private val target: File,
    val width: Int,
    val height: Int,
    frameRate: Double,
    rotation: Int,
    private val audioSource: File? = null,
    private val audioStartUs: Long = 0L,
    private val audioEndUs: Long = Long.MAX_VALUE,
    bitsPerPixel: Double = 0.1,
) : AutoCloseable {
    private val codec: MediaCodec
    private val muxer = MediaMuxer(target.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    private var videoTrack = -1
    private var audioTrack = -1
    private var audioFormat: MediaFormat? = null
    private var muxerStarted = false
    private val bufInfo = MediaCodec.BufferInfo()
    private var released = false

    init {
        muxer.setOrientationHint(rotation)
        val fmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            setInteger(MediaFormat.KEY_BIT_RATE, (width * height * frameRate * bitsPerPixel).toLong().coerceIn(1_500_000L, 40_000_000L).toInt())
            setInteger(MediaFormat.KEY_FRAME_RATE, frameRate.roundToInt().coerceIn(1, 120))
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT709)
            setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
            setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_SDR_VIDEO)
        }
        codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).also {
            it.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            it.start()
        }
        audioSource?.let { audioFormat = openAudioFormat(it) }
    }

    private fun openAudioFormat(file: File): MediaFormat? {
        val ex = MediaExtractor()
        return try {
            ex.setDataSource(file.path)
            (0 until ex.trackCount).map { ex.getTrackFormat(it) }.firstOrNull { f ->
                val mime = f.getString(MediaFormat.KEY_MIME).orEmpty()
                mime == MediaFormat.MIMETYPE_AUDIO_AAC || mime == MediaFormat.MIMETYPE_AUDIO_AMR_NB || mime == MediaFormat.MIMETYPE_AUDIO_AMR_WB
            }
        } catch (e: Exception) {
            null
        } finally {
            runCatching { ex.release() }
        }
    }

    /** True when the source's sound was (or will be) carried over. */
    val hasAudio: Boolean get() = audioFormat != null

    fun writeFrame(frame: DirectI420, ptsUs: Long) {
        while (true) {
            val i = codec.dequeueInputBuffer(10_000)
            if (i >= 0) {
                val image = codec.getInputImage(i) ?: throw IOException("encoder gave no input image")
                try { ImageCopy.fromDirect(frame, image) } finally { image.close() }
                codec.queueInputBuffer(i, 0, frame.width * frame.height * 3 / 2, ptsUs, 0)
                break
            }
            drain(false)
        }
        drain(false)
    }

    private fun drain(end: Boolean) {
        var idle = 0
        while (true) {
            val o = codec.dequeueOutputBuffer(bufInfo, if (end) 10_000 else 0)
            when {
                o == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!end) return
                    // Flushing at the end: wait for the encoder, but not forever (30 s).
                    if (++idle > 3000) throw IOException("the video encoder did not finish")
                }
                o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    videoTrack = muxer.addTrack(codec.outputFormat)
                    audioFormat?.let { audioTrack = muxer.addTrack(it) }
                    muxer.start()
                    muxerStarted = true
                }
                o >= 0 -> {
                    val data = codec.getOutputBuffer(o)!!
                    if ((bufInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) bufInfo.size = 0
                    if (bufInfo.size > 0 && muxerStarted) {
                        data.position(bufInfo.offset); data.limit(bufInfo.offset + bufInfo.size)
                        muxer.writeSampleData(videoTrack, data, bufInfo)
                    }
                    codec.releaseOutputBuffer(o, false)
                    if ((bufInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) return
                }
            }
        }
    }

    /** Flushes the encoder, copies the audio and closes the file. */
    fun finish() {
        val i = codec.dequeueInputBuffer(1_000_000)
        if (i >= 0) codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
        drain(true)
        if (muxerStarted && audioTrack >= 0) copyAudio()
        close()
    }

    private fun copyAudio() {
        val src = audioSource ?: return
        val ex = MediaExtractor()
        try {
            ex.setDataSource(src.path)
            val track = (0 until ex.trackCount).first { idx ->
                val mime = ex.getTrackFormat(idx).getString(MediaFormat.KEY_MIME).orEmpty()
                mime == MediaFormat.MIMETYPE_AUDIO_AAC || mime == MediaFormat.MIMETYPE_AUDIO_AMR_NB || mime == MediaFormat.MIMETYPE_AUDIO_AMR_WB
            }
            ex.selectTrack(track)
            ex.seekTo(audioStartUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            val buf = ByteBuffer.allocate(512 * 1024)
            val info = MediaCodec.BufferInfo()
            while (true) {
                buf.clear()
                val n = ex.readSampleData(buf, 0)
                if (n < 0) break
                val t = ex.sampleTime
                if (t >= audioEndUs) break
                if (t >= audioStartUs) {
                    info.set(0, n, t - audioStartUs, ex.sampleFlags and MediaCodec.BUFFER_FLAG_KEY_FRAME)
                    muxer.writeSampleData(audioTrack, buf, info)
                }
                ex.advance()
            }
        } finally {
            runCatching { ex.release() }
        }
    }

    override fun close() {
        if (released) return
        released = true
        runCatching { codec.stop() }
        runCatching { codec.release() }
        if (muxerStarted) runCatching { muxer.stop() }
        runCatching { muxer.release() }
    }
}
