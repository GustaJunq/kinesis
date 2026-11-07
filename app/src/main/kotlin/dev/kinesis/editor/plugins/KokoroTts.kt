package dev.kinesis.editor.plugins

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import dev.kinesis.core.tts.KokoroVocab
import dev.kinesis.core.tts.PortuguesePhonemizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.LongBuffer
import java.util.Locale

/** The files of the Kokoro voices, downloaded from Hugging Face on request (the only thing in the app that needs the internet besides the caption model). */
object KokoroFiles {
    private const val BASE = "https://huggingface.co/onnx-community/Kokoro-82M-v1.0-ONNX/resolve/main/"

    class Part(val remote: String, val local: String, val bytes: Long)

    /** Model (int8, 92 MB) and the three Brazilian Portuguese voices. Sizes are checked after the download. */
    val parts = listOf(
        Part("onnx/model_quantized.onnx", "model.onnx", 92_361_116L),
        Part("voices/pf_dora.bin", "pf_dora.bin", 522_240L),
        Part("voices/pm_alex.bin", "pm_alex.bin", 522_240L),
        Part("voices/pm_santa.bin", "pm_santa.bin", 522_240L),
    )
    val totalBytes get() = parts.sumOf { it.bytes }

    fun dir(context: Context) = File(context.filesDir, "kokoro")

    fun isInstalled(context: Context): Boolean = parts.all { File(dir(context), it.local).let { f -> f.isFile && f.length() == it.bytes } }

    fun delete(context: Context) {
        dir(context).deleteRecursively()
    }

    /**
     * Downloads what is missing (each file resumes where it stopped). [progress] gets the fraction done. Throws
     * [IOException] on network trouble or a file with the wrong size.
     */
    suspend fun download(context: Context, progress: (Float) -> Unit, cancelled: () -> Boolean = { false }) = withContext(Dispatchers.IO) {
        val dir = dir(context).apply { mkdirs() }
        var done = 0L
        for (p in parts) {
            val target = File(dir, p.local)
            if (target.isFile && target.length() == p.bytes) { done += p.bytes; progress(done.toFloat() / totalBytes); continue }
            val part = File(dir, p.local + ".part")
            var have = if (part.isFile) part.length() else 0L
            if (have > p.bytes) { part.delete(); have = 0 }
            val conn = URL(BASE + p.remote).openConnection() as HttpURLConnection
            try {
                conn.connectTimeout = 20_000
                conn.readTimeout = 30_000
                conn.instanceFollowRedirects = true
                if (have > 0) conn.setRequestProperty("Range", "bytes=$have-")
                val code = conn.responseCode
                if (code == 200 && have > 0) { part.delete(); have = 0 } // the server ignored the range
                if (code != 200 && code != 206) throw IOException("HTTP $code at ${p.remote}")
                conn.inputStream.use { input ->
                    java.io.FileOutputStream(part, have > 0).use { out ->
                        val buf = ByteArray(64 * 1024)
                        var read = have
                        while (true) {
                            if (cancelled()) throw IOException("cancelled")
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            read += n
                            progress((done + read).toFloat() / totalBytes)
                        }
                    }
                }
            } finally {
                conn.disconnect()
            }
            if (part.length() != p.bytes) throw IOException("${p.remote}: incomplete download (${part.length()} of ${p.bytes} bytes)")
            if (!part.renameTo(target)) throw IOException("cannot store ${p.local}")
            done += p.bytes
        }
        progress(1f)
    }
}

/**
 * Kokoro-82M (Apache-2.0) text to speech with ONNX Runtime on the phone. The Brazilian Portuguese voices are
 * `pf_dora`, `pm_alex` and `pm_santa`. Text becomes phonemes with [PortuguesePhonemizer], the model turns them into
 * 24 kHz audio. Long text is cut at sentence ends and each piece is run separately.
 */
class KokoroTts(private val context: Context) : TtsEngine {
    override val id = "kokoro"
    override val name = "Kokoro (offline)"
    private val dir = KokoroFiles.dir(context)
    private var env: OrtEnvironment? = null
    private var session: OrtSession? = null

    private val voiceNames = mapOf("pf_dora" to "Dora (feminina)", "pm_alex" to "Alex (masculina)", "pm_santa" to "Santa (masculina)")

    override suspend fun voices(): List<TtsVoice> =
        voiceNames.filter { File(dir, "${it.key}.bin").isFile }.map { (id, label) -> TtsVoice(this.id, id, "Português (Brasil) · $label", Locale("pt", "BR")) }

    private fun open(): OrtSession {
        session?.let { return it }
        val e = OrtEnvironment.getEnvironment()
        val opts = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(Runtime.getRuntime().availableProcessors().coerceIn(1, 4))
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        }
        return e.createSession(File(dir, "model.onnx").path, opts).also { env = e; session = it }
    }

    private fun style(voice: String, tokens: Int): FloatArray {
        val bytes = File(dir, "$voice.bin").readBytes()
        val floats = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        val rows = floats.capacity() / 256
        val row = tokens.coerceIn(0, rows - 1)
        return FloatArray(256) { floats.get(row * 256 + it) }
    }

    override suspend fun synthesize(text: String, voice: TtsVoice, speed: Float, out: File) = withContext(Dispatchers.Default) {
        val pieces = PortuguesePhonemizer.chunks(text)
        check(pieces.isNotEmpty()) { "não há o que falar" }
        val s = open()
        val e = env!!
        val samples = ArrayList<FloatArray>()
        for (piece in pieces) {
            val ids = KokoroVocab.encode(piece).let { if (it.size > KokoroVocab.MAX_TOKENS) it.copyOf(KokoroVocab.MAX_TOKENS) else it }
            if (ids.isEmpty()) continue
            val input = LongArray(ids.size + 2).also { for (i in ids.indices) it[i + 1] = ids[i].toLong() }
            val tIds = OnnxTensor.createTensor(e, LongBuffer.wrap(input), longArrayOf(1, input.size.toLong()))
            val tStyle = OnnxTensor.createTensor(e, FloatBuffer.wrap(style(voice.id, ids.size)), longArrayOf(1, 256))
            val tSpeed = OnnxTensor.createTensor(e, FloatBuffer.wrap(floatArrayOf(speed.coerceIn(0.5f, 2f))), longArrayOf(1))
            try {
                s.run(mapOf("input_ids" to tIds, "style" to tStyle, "speed" to tSpeed)).use { r ->
                    @Suppress("UNCHECKED_CAST")
                    samples += (r[0].value as Array<FloatArray>)[0]
                }
            } finally {
                tIds.close(); tStyle.close(); tSpeed.close()
            }
        }
        check(samples.isNotEmpty()) { "o modelo não gerou áudio" }
        writeWav(out, samples)
    }

    /** 16-bit mono WAV at 24 kHz, with a short pause between the pieces. */
    private fun writeWav(out: File, pieces: List<FloatArray>) {
        val rate = 24_000
        val gap = rate / 5
        val total = pieces.sumOf { it.size } + gap * (pieces.size - 1)
        out.parentFile?.mkdirs()
        java.io.DataOutputStream(out.outputStream().buffered()).use { o ->
            fun le32(v: Int) { o.write(v and 0xff); o.write((v shr 8) and 0xff); o.write((v shr 16) and 0xff); o.write((v shr 24) and 0xff) }
            fun le16(v: Int) { o.write(v and 0xff); o.write((v shr 8) and 0xff) }
            o.writeBytes("RIFF"); le32(36 + total * 2); o.writeBytes("WAVE")
            o.writeBytes("fmt "); le32(16); le16(1); le16(1); le32(rate); le32(rate * 2); le16(2); le16(16)
            o.writeBytes("data"); le32(total * 2)
            for ((i, p) in pieces.withIndex()) {
                if (i > 0) repeat(gap) { le16(0) }
                for (f in p) le16((f.coerceIn(-1f, 1f) * 32767f).toInt())
            }
        }
    }

    override fun close() {
        runCatching { session?.close() }
        session = null
    }
}
