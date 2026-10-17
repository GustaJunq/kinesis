package dev.kinesis.editor.speech

import android.content.Context
import android.net.Uri
import dev.kinesis.core.edit.CaptionSegment
import dev.kinesis.core.edit.SpeechAudio
import dev.kinesis.core.nativebridge.NativeCore
import dev.kinesis.core.scene.AudioClip
import dev.kinesis.editor.media.MIX_RATE
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ShortBuffer

/** The ggml models of whisper.cpp that Kinesis offers. Bigger = more accurate and slower. */
enum class WhisperModel(val id: String, val file: String, val sizeMb: Int) {
    TINY("tiny", "ggml-tiny.bin", 75),
    BASE("base", "ggml-base.bin", 142),
    SMALL("small", "ggml-small-q5_1.bin", 181),
}

/**
 * Model files in the app's private storage. The only network access of the whole app is [download], started by the
 * user to fetch a model once; a model can also be imported from a file, and after that everything works offline.
 */
class SpeechModels(context: Context) {
    private val dir = File(context.filesDir, "models").also { it.mkdirs() }
    private val resolver = context.contentResolver

    fun file(model: WhisperModel) = File(dir, model.file)

    /** A model that is ready to use: a downloaded one (the most accurate first) or an imported one. */
    fun best(): File? =
        WhisperModel.entries.reversed().map(::file).firstOrNull { it.isFile && it.length() > MIN_MODEL_BYTES }
            ?: File(dir, IMPORTED).takeIf { it.isFile && it.length() > MIN_MODEL_BYTES }

    fun isInstalled(model: WhisperModel) = file(model).let { it.isFile && it.length() > MIN_MODEL_BYTES }

    fun delete(model: WhisperModel) { file(model).delete() }

    /**
     * Downloads [model] from the whisper.cpp model repository (resumable: an interrupted download continues).
     * Blocking: call off the main thread. Returns normally when done, throws [IOException] otherwise.
     */
    fun download(model: WhisperModel, progress: (Float) -> Unit, cancelled: () -> Boolean) {
        val target = file(model)
        val part = File(dir, model.file + ".part")
        var done = if (part.isFile) part.length() else 0L
        val conn = (URL("$BASE_URL/${model.file}").openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            if (done > 0) setRequestProperty("Range", "bytes=$done-")
        }
        try {
            val code = conn.responseCode
            if (code == HttpURLConnection.HTTP_OK && done > 0) { part.delete(); done = 0 } // server ignored the range
            else if (code != HttpURLConnection.HTTP_OK && code != HttpURLConnection.HTTP_PARTIAL) throw IOException("HTTP $code")
            val total = done + conn.contentLengthLong.coerceAtLeast(0)
            FileOutputStream(part, done > 0).use { out ->
                conn.inputStream.use { input ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        if (cancelled()) throw IOException("cancelled")
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (total > 0) progress((done.toFloat() / total).coerceIn(0f, 1f))
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
        if (part.length() < MIN_MODEL_BYTES || !hasGgmlMagic(part)) { part.delete(); throw IOException("not a ggml model") }
        if (!part.renameTo(target)) throw IOException("cannot save the model")
    }

    /** Copies a model file chosen by the user ([uri]) into the app. Returns false when it is not a ggml model. */
    fun import(uri: Uri): Boolean {
        val tmp = File(dir, "$IMPORTED.tmp")
        try {
            resolver.openInputStream(uri)?.use { input -> FileOutputStream(tmp).use { input.copyTo(it) } } ?: return false
            if (tmp.length() < MIN_MODEL_BYTES || !hasGgmlMagic(tmp)) return false
            return tmp.renameTo(File(dir, IMPORTED))
        } catch (e: IOException) {
            return false
        } finally {
            tmp.delete()
        }
    }

    private fun hasGgmlMagic(f: File): Boolean {
        val head = ByteArray(4)
        f.inputStream().use { if (it.read(head) != 4) return false }
        // ggml files start with the magic "ggml" (0x67676d6c little-endian, written as "lmgg").
        return String(head, Charsets.ISO_8859_1) == "lmgg"
    }

    companion object {
        private const val BASE_URL = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main"
        private const val IMPORTED = "ggml-imported.bin"
        private const val MIN_MODEL_BYTES = 10L * 1024 * 1024
    }
}

/** Speech recognition of one clip of the timeline. */
object Transcriber {
    val supported: Boolean get() = NativeCore.speechSupported

    class Result(val segments: List<CaptionSegment>)

    /**
     * Recognises the speech of [clip] ([pcm]: the 48 kHz stereo cache of its asset) and returns the captions placed on
     * the timeline. Blocking; [onProgress] gets 0..100 from a polling thread. Null when cancelled or on failure
     * ([NativeCore.Whisper.error] says why).
     */
    fun transcribe(
        model: File,
        pcm: ShortBuffer,
        clip: AudioClip,
        language: String,
        maxChars: Int,
        onProgress: (Int) -> Unit,
        handle: (NativeCore.Whisper) -> Unit,
    ): List<CaptionSegment>? {
        val from = clip.sourceStart
        val to = clip.sourceStart + (clip.compEnd - clip.compStart) * clip.speed
        val audio = SpeechAudio.to16kMono(pcm, MIX_RATE, from, to)
        if (audio.size < SpeechAudio.RATE / 4) return emptyList()
        NativeCore.Whisper.load(model.absolutePath)?.use { w ->
            handle(w)
            val poll = Thread {
                try {
                    while (!Thread.currentThread().isInterrupted) { onProgress(w.progress()); Thread.sleep(300) }
                } catch (_: InterruptedException) {
                }
            }.apply { isDaemon = true; start() }
            val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 6) - 1
            val heard = try { w.transcribe(audio, language, false, threads, maxChars) } finally { poll.interrupt() }
            heard ?: return null
            // Whisper's times start at 0 for the first sample we gave it, which is source time `from`.
            val recognised = heard.map { CaptionSegment(from + it.start, from + it.end, it.text) }
            return dev.kinesis.core.edit.Captions.fromRecognised(clip, recognised)
        }
        return null
    }
}
