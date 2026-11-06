package dev.kinesis.editor.plugins

import android.content.Context

/**
 * Kokoro voices (the separate `.knsplugin` data pack that extends the official text-to-speech plugin). Returns null
 * while the pack is not installed.
 */
object KokoroTts {
    fun fromStore(context: Context, store: PluginStore): TtsEngine? = null
}
