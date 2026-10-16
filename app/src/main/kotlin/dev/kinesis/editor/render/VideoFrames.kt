package dev.kinesis.editor.render

import android.graphics.SurfaceTexture
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.opengl.Matrix
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import java.io.File
import kotlin.math.abs

/**
 * Decodes one video file into an external (OES) texture, frame-accurately. Must be used on a thread with a
 * current EGL context. Frames are decoded on demand: sequential playback decodes forward, jumps seek to the
 * previous sync frame and decode up to the requested time.
 */
class VideoFrameSource private constructor(
    private val extractor: MediaExtractor,
    private val codec: MediaCodec,
    private val surfaceTexture: SurfaceTexture,
    private val surface: Surface,
    val texture: Int,
    /** Display size (rotation applied); for a proxy, the size of the original file (layout never changes). */
    val width: Int,
    val height: Int,
    private val rotation: Int,
    private val durationUs: Long,
    private val frameUs: Long,
) {
    private val lock = Object()
    private var frameAvailable = false
    private var inputDone = false
    private var outputDone = false
    private var currentPts = -1L
    private val info = MediaCodec.BufferInfo()
    private val stMatrix = FloatArray(16)

    /** Texture matrix for the quad shader: SurfaceTexture transform combined with the clip's rotation. */
    val texMatrix = FloatArray(16).also { Matrix.setIdentityM(it, 0) }
    val hasFrame: Boolean get() = currentPts >= 0

    /** Microseconds of the frame on the texture, or -1. */
    val positionUs: Long get() = currentPts

    /** Cost of showing [seconds] next: 0 when decoding forward a little, more when a seek is needed. */
    fun distanceTo(seconds: Double): Long {
        val target = (seconds * 1_000_000.0).toLong()
        if (currentPts < 0) return SEEK_COST
        val ahead = target - currentPts
        return if (ahead in -frameUs / 2..1_500_000L) abs(ahead) else SEEK_COST + abs(ahead)
    }

    init {
        surfaceTexture.setOnFrameAvailableListener({
            synchronized(lock) {
                frameAvailable = true
                lock.notifyAll()
            }
        }, callbackHandler)
    }

    /** Shows the frame at [seconds] (clamped to the clip). Returns false if no frame could be decoded. */
    fun show(seconds: Double): Boolean {
        val target = (seconds * 1_000_000.0).toLong().coerceIn(0L, if (durationUs > 0) durationUs else Long.MAX_VALUE)
        val tolerance = frameUs / 2
        if (currentPts >= 0 && abs(target - currentPts) <= tolerance) return true
        val needsSeek = currentPts < 0 || target < currentPts || target - currentPts > 1_500_000L
        if (needsSeek) seek(target)
        try {
            decodeUntil(target, tolerance)
        } catch (e: Exception) {
            Log.w(TAG, "decode failed", e)
        }
        return hasFrame
    }

    private fun seek(target: Long) {
        extractor.seekTo(target, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        codec.flush()
        inputDone = false
        outputDone = false
    }

    private fun decodeUntil(target: Long, tolerance: Long) {
        if (outputDone) return
        var held = -1
        var heldPts = -1L
        var spins = 0
        while (spins++ < 4000) {
            if (!inputDone) {
                val inIdx = codec.dequeueInputBuffer(2000)
                if (inIdx >= 0) {
                    val buf = codec.getInputBuffer(inIdx)
                    val size = if (buf != null) extractor.readSampleData(buf, 0) else -1
                    if (size < 0) {
                        codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        codec.queueInputBuffer(inIdx, 0, size, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }
            val outIdx = codec.dequeueOutputBuffer(info, 2000)
            if (outIdx < 0) continue
            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                outputDone = true
                codec.releaseOutputBuffer(outIdx, false)
                // Past the last frame: show the last decoded one.
                if (held >= 0) present(held, heldPts)
                return
            }
            val pts = info.presentationTimeUs
            if (pts >= target - tolerance) {
                if (held >= 0) codec.releaseOutputBuffer(held, false)
                present(outIdx, pts)
                return
            }
            if (held >= 0) codec.releaseOutputBuffer(held, false)
            held = outIdx
            heldPts = pts
        }
        if (held >= 0) present(held, heldPts)
    }

    private fun present(index: Int, pts: Long) {
        synchronized(lock) { frameAvailable = false }
        codec.releaseOutputBuffer(index, true)
        synchronized(lock) {
            val deadline = System.nanoTime() + 1_000_000_000L
            while (!frameAvailable) {
                val waitMs = (deadline - System.nanoTime()) / 1_000_000L
                if (waitMs <= 0) break
                lock.wait(waitMs)
            }
            if (!frameAvailable) {
                Log.w(TAG, "frame wait timed out")
                return
            }
            frameAvailable = false
        }
        surfaceTexture.updateTexImage()
        surfaceTexture.getTransformMatrix(stMatrix)
        // Rotate around the texture centre before applying the SurfaceTexture transform.
        val rot = FloatArray(16)
        Matrix.setIdentityM(rot, 0)
        if (rotation != 0) {
            Matrix.translateM(rot, 0, 0.5f, 0.5f, 0f)
            Matrix.rotateM(rot, 0, rotation.toFloat(), 0f, 0f, 1f)
            Matrix.translateM(rot, 0, -0.5f, -0.5f, 0f)
        }
        Matrix.multiplyMM(texMatrix, 0, stMatrix, 0, rot, 0)
        currentPts = pts
    }

    fun release() {
        runCatching { codec.stop() }
        runCatching { codec.release() }
        runCatching { extractor.release() }
        runCatching { surface.release() }
        runCatching { surfaceTexture.release() }
        Gl.deleteTexture(texture)
    }

    companion object {
        private const val TAG = "KinesisVideo"
        private const val SEEK_COST = 10_000_000L
        private val callbackThread by lazy { HandlerThread("kinesis-video").apply { start() } }
        private val callbackHandler by lazy { Handler(callbackThread.looper) }

        /** [logicalSize] replaces the display size (proxies report the size of their original). */
        fun open(file: File, logicalSize: Pair<Int, Int>? = null): VideoFrameSource? {
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(file.path)
                val track = (0 until extractor.trackCount).firstOrNull {
                    extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
                } ?: run { extractor.release(); return null }
                extractor.selectTrack(track)
                val format = extractor.getTrackFormat(track)
                val mime = format.getString(MediaFormat.KEY_MIME)!!
                val w = format.getInteger(MediaFormat.KEY_WIDTH)
                val h = format.getInteger(MediaFormat.KEY_HEIGHT)
                val rotation = readRotation(file, format)
                // We apply the rotation ourselves so the result does not depend on the decoder.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && format.containsKey(MediaFormat.KEY_ROTATION)) {
                    format.setInteger(MediaFormat.KEY_ROTATION, 0)
                }
                val duration = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L
                val fps = if (format.containsKey(MediaFormat.KEY_FRAME_RATE)) runCatching { format.getInteger(MediaFormat.KEY_FRAME_RATE) }.getOrDefault(30) else 30
                val texture = Gl.createExternalTexture()
                val st = SurfaceTexture(texture)
                val surface = Surface(st)
                val codec = MediaCodec.createDecoderByType(mime)
                codec.configure(format, surface, null, 0)
                codec.start()
                val rotated = rotation == 90 || rotation == 270
                return VideoFrameSource(
                    extractor, codec, st, surface, texture,
                    logicalSize?.first ?: if (rotated) h else w, logicalSize?.second ?: if (rotated) w else h, rotation,
                    duration, 1_000_000L / fps.coerceIn(1, 240),
                )
            } catch (e: Exception) {
                Log.w(TAG, "cannot open ${file.name}", e)
                extractor.release()
                return null
            }
        }

        private fun readRotation(file: File, format: MediaFormat): Int {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && format.containsKey(MediaFormat.KEY_ROTATION)) {
                return format.getInteger(MediaFormat.KEY_ROTATION).let { ((it % 360) + 360) % 360 }
            }
            val r = MediaMetadataRetriever()
            return try {
                r.setDataSource(file.path)
                r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            } catch (e: Exception) {
                0
            } finally {
                runCatching { r.release() }
            }
        }
    }
}

/** A lighter copy of a video for the preview, already rotated, with the display size of the original. */
class VideoProxy(val file: File, val width: Int, val height: Int)

/**
 * Video decoders, shared by every clip of the same file: clips cut from one video reuse one decoder (only
 * the frame under the playhead is decoded). A second decoder of a file is opened only when two of its clips
 * are visible at the same time.
 */
class VideoFrames(private val files: AssetFiles, private val proxies: (String) -> VideoProxy? = { null }) {
    private class Slot(val assetId: String, val source: VideoFrameSource, var owner: String, var usedInFrame: Long)

    private val slots = ArrayList<Slot>()
    private val unreadable = HashSet<String>()
    private var frameNo = 0L

    fun beginFrame() {
        frameNo++
    }

    fun frame(layerId: String, assetId: String, seconds: Double): VideoFrameSource? {
        if (assetId in unreadable) return null
        val ofFile = slots.filter { it.assetId == assetId }
        val free = ofFile.filter { it.usedInFrame != frameNo }
        // Cheapest decoder to move to [seconds]; on ties the one this layer used last.
        val best = free.minWithOrNull(compareBy<Slot>({ it.source.distanceTo(seconds) }, { if (it.owner == layerId) 0 else 1 }))
        val slot = when {
            best != null && best.source.distanceTo(seconds) < SEEK_THRESHOLD -> best
            // Cuts of one clip follow each other: one decoder seeks instead of opening another.
            best != null && !overlapLikely(ofFile) -> best
            else -> open(assetId, layerId) ?: best ?: ofFile.minByOrNull { it.source.distanceTo(seconds) } ?: return null
        }
        slot.owner = layerId
        slot.usedInFrame = frameNo
        return slot.source.takeIf { it.show(seconds) }
    }

    /** True when several decoders of a file were busy in the previous frame (clips playing at the same time). */
    private fun overlapLikely(ofFile: List<Slot>) = ofFile.count { it.usedInFrame == frameNo - 1 } > 1

    private fun open(assetId: String, layerId: String): Slot? {
        if (slots.count { it.assetId == assetId } >= MAX_PER_FILE) return null
        val proxy = proxies(assetId)?.takeIf { it.file.isFile }
        val source = if (proxy != null) {
            VideoFrameSource.open(proxy.file, proxy.width to proxy.height) ?: openOriginal(assetId)
        } else {
            openOriginal(assetId)
        }
        if (source == null) {
            if (slots.none { it.assetId == assetId }) unreadable += assetId
            return null
        }
        return Slot(assetId, source, layerId, frameNo).also { slots += it }
    }

    private fun openOriginal(assetId: String): VideoFrameSource? =
        files.file(assetId)?.takeIf { it.isFile }?.let { VideoFrameSource.open(it) }

    /** Closes decoders unused for a while (a couple stay open so scrubbing back is fast). */
    fun endFrame(keepIdle: Int = 2, maxIdleFrames: Long = 90) {
        val idle = slots.filter { it.usedInFrame != frameNo }.sortedBy { it.usedInFrame }
        val excess = (idle.size - keepIdle).coerceAtLeast(0)
        idle.forEachIndexed { i, s ->
            if (i < excess || frameNo - s.usedInFrame > maxIdleFrames) {
                s.source.release()
                slots.remove(s)
            }
        }
    }

    /** Closes the decoders of [assetId] (e.g. when its proxy becomes available). */
    fun evict(assetId: String) {
        slots.filter { it.assetId == assetId }.forEach { it.source.release(); slots.remove(it) }
        unreadable.remove(assetId)
    }

    fun release() {
        slots.forEach { it.source.release() }
        slots.clear()
        unreadable.clear()
    }

    private companion object {
        const val MAX_PER_FILE = 3
        /** Forward decoding below this (µs of distance) beats opening another decoder. */
        const val SEEK_THRESHOLD = 1_500_001L
    }
}
