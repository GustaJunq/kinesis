package dev.kinesis.editor.media

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer

/**
 * Copies the sound track of a video into an `.m4a` file without decoding or re-encoding it: the compressed samples
 * are read with [MediaExtractor] and written as they are with [MediaMuxer]. Only codecs the MP4 muxer can carry
 * (AAC, AMR) are supported; anything else is reported, never silently converted.
 */
object AudioExtractor {
    sealed class Result {
        data object NoAudio : Result()
        data class Unsupported(val mime: String) : Result()
        data object Done : Result()
    }

    private val SUPPORTED = setOf(MediaFormat.MIMETYPE_AUDIO_AAC, MediaFormat.MIMETYPE_AUDIO_AMR_NB, MediaFormat.MIMETYPE_AUDIO_AMR_WB)

    /** Blocking; call off the main thread. [target] is written only when the result is [Result.Done]. */
    fun extract(source: File, target: File): Result {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(source.path)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return Result.NoAudio
            val format = extractor.getTrackFormat(track)
            val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
            if (mime !in SUPPORTED) return Result.Unsupported(mime)
            extractor.selectTrack(track)

            target.parentFile?.mkdirs()
            val muxer = MediaMuxer(target.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            var started = false
            try {
                val out = muxer.addTrack(format)
                muxer.start()
                started = true
                val size = if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) else 0
                val buffer = ByteBuffer.allocate(maxOf(size, 512 * 1024))
                val info = MediaCodec.BufferInfo()
                var written = 0
                while (true) {
                    buffer.clear()
                    val n = extractor.readSampleData(buffer, 0)
                    if (n < 0) break
                    info.set(0, n, extractor.sampleTime, extractor.sampleFlags and MediaCodec.BUFFER_FLAG_KEY_FRAME)
                    muxer.writeSampleData(out, buffer, info)
                    written++
                    extractor.advance()
                }
                if (written == 0) return Result.NoAudio
            } finally {
                if (started) runCatching { muxer.stop() }
                runCatching { muxer.release() }
            }
            return Result.Done
        } finally {
            runCatching { extractor.release() }
        }
    }
}
