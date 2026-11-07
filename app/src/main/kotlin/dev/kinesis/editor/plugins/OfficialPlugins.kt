package dev.kinesis.editor.plugins

import dev.kinesis.core.plugin.PluginManifest
import dev.kinesis.core.plugin.PluginPermission
import dev.kinesis.core.plugin.PluginType

/**
 * Plugins compiled with the app (Kotlin). They come installed, can be uninstalled and reinstalled from the plugin
 * list, and are the only ones that may use the device's speech synthesis or add media files.
 */
object OfficialPlugins {
    const val TTS_ID = "dev.kinesis.tts"
    const val KOKORO_ID = "dev.kinesis.tts.kokoro"

    val tts = PluginManifest(
        id = TTS_ID,
        name = "Texto para fala",
        version = "1.0.0",
        author = "Kinesis",
        type = PluginType.NATIVE,
        permissions = listOf(PluginPermission.SPEECH, PluginPermission.MEDIA_WRITE, PluginPermission.PROJECT_WRITE),
        minKinesisVersion = "0.2.0",
        description = "Digite um texto, escolha a voz e a velocidade: o áudio vira uma camada na timeline. Usa as vozes " +
            "offline do aparelho e, se instaladas, as vozes Kokoro. Tudo roda no aparelho.",
    )

    val kokoro = PluginManifest(
        id = KOKORO_ID,
        name = "Vozes Kokoro",
        version = "1.0.0",
        author = "Kinesis (modelo Kokoro-82M, Apache-2.0)",
        type = PluginType.NATIVE,
        permissions = listOf(PluginPermission.DOWNLOAD),
        minKinesisVersion = "0.2.0",
        description = "Vozes neurais em português do Brasil (Dora, Alex e Santa) para o Texto para fala. O modelo (cerca de 93 MB) " +
            "é baixado uma vez do Hugging Face; depois tudo roda offline no aparelho. A geração é mais lenta que a voz do sistema.",
    )

    val all: List<PluginManifest> = listOf(tts, kokoro)
}
