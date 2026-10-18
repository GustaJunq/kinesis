package dev.kinesis.editor.media

import dev.kinesis.core.nativebridge.NativeCore
import java.io.File
import java.io.IOException

/**
 * Video stabilization with vid.stab (GPL-2.0-or-later), run on the device: the first pass measures the shake of every
 * frame, the second applies the smoothed camera path and writes a new MP4 (the sound track is copied, not re-encoded).
 */
object VideoStabilizer {
    class Settings(
        /** Frames on each side used to smooth the camera path: higher = steadier but more "floaty" (1..30). */
        val smoothing: Int = 12,
        /** Zoom in just enough to hide the borders the correction exposes. */
        val autoZoom: Boolean = true,
        /** How shaky the footage is, 1 (little) .. 10 (a lot); more is slower. */
        val shakiness: Int = 5,
    )

    val supported: Boolean get() = NativeCore.stabilizerSupported

    /**
     * Stabilizes [source] into [target] (an MP4). Blocking: call from a background thread. [progress] gets 0..1.
     * Returns false when [cancelled] became true (the partial file is deleted); throws [IOException] on failure.
     */
    fun stabilize(source: File, target: File, settings: Settings, progress: (Float) -> Unit, cancelled: () -> Boolean): Boolean {
        val reader = YuvVideoReader(source)
        try {
            return run(reader, source, target, settings, progress, cancelled)
        } finally {
            reader.close()
        }
    }

    private fun run(reader: YuvVideoReader, source: File, target: File, settings: Settings, progress: (Float) -> Unit, cancelled: () -> Boolean): Boolean {
        val info = reader.info
        val stab = NativeCore.Stabilizer.create(info.width, info.height, settings.shakiness, accuracy = maxOf(settings.shakiness, 9))
            ?: throw IOException("the stabilizer is not available for this video (${info.width}x${info.height})")
        try {
            val total = (info.durationUs / 1_000_000.0 * info.frameRate).coerceAtLeast(1.0)
            var n = 0
            // Pass 1: measure the shake of every frame.
            reader.read(0L, Long.MAX_VALUE) { frame, _ ->
                if (cancelled()) return@read false
                if (stab.detect(yuv(frame)) != 0) throw IOException("motion detection failed (${NativeCore.lastNativeError()})")
                n++
                if (n % 8 == 0) progress((n / total).toFloat().coerceAtMost(1f) * 0.45f)
                true
            }
            if (cancelled()) return false
            if (n < 2) throw IOException("the video has too few frames")
            if (stab.prepare(settings.smoothing.coerceIn(1, 30), 0.0, settings.autoZoom, cropBlack = false) != 0) {
                throw IOException("could not compute the camera path (${NativeCore.lastNativeError()})")
            }

            // Pass 2: apply the smoothed path and encode.
            val out = DirectI420(info.width, info.height)
            val writer = YuvVideoWriter(target, info.width, info.height, info.frameRate, info.rotation, audioSource = source)
            var ok = false
            try {
                var done = 0
                reader.read(0L, Long.MAX_VALUE) { frame, pts ->
                    if (cancelled()) return@read false
                    if (stab.apply(yuv(frame), yuv(out)) != 0) throw IOException("stabilizing a frame failed (${NativeCore.lastNativeError()})")
                    writer.writeFrame(out, pts)
                    done++
                    if (done % 8 == 0) progress(0.45f + (done / n.toFloat()).coerceAtMost(1f) * 0.55f)
                    true
                }
                if (cancelled()) return false
                writer.finish()
                ok = true
            } finally {
                writer.close()
                if (!ok) target.delete()
            }
            progress(1f)
            return true
        } finally {
            stab.close()
        }
    }

    private fun yuv(f: DirectI420) = NativeCore.YuvFrame(
        NativeCore.YuvPlane(f.y, f.width), NativeCore.YuvPlane(f.u, f.chromaWidth), NativeCore.YuvPlane(f.v, f.chromaWidth),
    )
}
