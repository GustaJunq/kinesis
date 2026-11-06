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
            "offline do aparelho; com o pacote Kokoro instalado, usa também as vozes do Kokoro. Tudo roda no aparelho.",
    )

    val all: List<PluginManifest> = listOf(tts)
}
