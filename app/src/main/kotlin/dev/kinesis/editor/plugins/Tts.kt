package dev.kinesis.editor.plugins

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import java.io.File
import java.util.Locale
import java.util.UUID

/** A voice of a text-to-speech engine. [id] is unique inside its engine. */
data class TtsVoice(val engine: String, val id: String, val label: String, val locale: Locale)

/** A speech synthesizer that runs on the phone and writes a WAV file. */
interface TtsEngine : AutoCloseable {
    val id: String
    val name: String
    suspend fun voices(): List<TtsVoice>

    /** Speaks [text] with [voice] at [speed] (1 = normal) into [out] (WAV). */
    suspend fun synthesize(text: String, voice: TtsVoice, speed: Float, out: File)
}

/**
 * The phone's own speech synthesis (Android TextToSpeech), restricted to voices installed on the device: voices that
 * need the network are never offered.
 */
class SystemTts(context: Context) : TtsEngine {
    override val id = "system"
    override val name = "Vozes do aparelho"
    private val ready = CompletableDeferred<Boolean>()
    private val done = HashMap<String, CompletableDeferred<Boolean>>()
    private val tts = TextToSpeech(context.applicationContext) { status -> ready.complete(status == TextToSpeech.SUCCESS) }

    init {
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit
            override fun onDone(utteranceId: String?) { synchronized(done) { done.remove(utteranceId) }?.complete(true) }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) { synchronized(done) { done.remove(utteranceId) }?.complete(false) }
            override fun onError(utteranceId: String?, errorCode: Int) { synchronized(done) { done.remove(utteranceId) }?.complete(false) }
        })
    }

    private suspend fun awaitReady(): Boolean = withTimeout(10_000) { ready.await() }

    override suspend fun voices(): List<TtsVoice> {
        if (!awaitReady()) return emptyList()
        val all = runCatching { tts.voices }.getOrNull().orEmpty()
            .filter { !it.isNetworkConnectionRequired && it.features?.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) != true }
        // Brazilian Portuguese first, then other Portuguese voices, then the rest.
        fun rank(v: Voice) = when {
            v.locale.language == "pt" && v.locale.country == "BR" -> 0
            v.locale.language == "pt" -> 1
            else -> 2
        }
        return all.sortedWith(compareBy<Voice>({ rank(it) }, { it.locale.toLanguageTag() }, { it.name })).map { v ->
            TtsVoice(id, v.name, "${v.locale.getDisplayName(Locale.getDefault())} · ${v.name.substringAfterLast('#').substringAfterLast('-').take(24)}", v.locale)
        }
    }

    override suspend fun synthesize(text: String, voice: TtsVoice, speed: Float, out: File) {
        check(awaitReady()) { "a síntese de voz do aparelho não está disponível" }
        val v = tts.voices.firstOrNull { it.name == voice.id } ?: error("voz não encontrada")
        tts.voice = v
        tts.setSpeechRate(speed.coerceIn(0.25f, 4f))
        val utterance = UUID.randomUUID().toString()
        val signal = CompletableDeferred<Boolean>()
        synchronized(done) { done[utterance] = signal }
        out.parentFile?.mkdirs()
        val r = tts.synthesizeToFile(text, Bundle(), out, utterance)
        check(r == TextToSpeech.SUCCESS) { "a síntese não começou" }
        val ok = withTimeout(120_000) { signal.await() }
        check(ok && out.length() > 44) { "a síntese falhou" }
    }

    override fun close() {
        runCatching { tts.shutdown() }
    }
}
