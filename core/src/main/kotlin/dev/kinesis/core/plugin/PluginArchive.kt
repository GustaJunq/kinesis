package dev.kinesis.core.plugin

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.zip.ZipException
import java.util.zip.ZipInputStream

/**
 * `.knsplugin`: a zip with `manifest.json` at the root, the JSON preset files, the script and assets.
 *
 * Reading checks everything before anything is installed: safe paths only (no absolute paths, `..` or backslashes),
 * size limits, a valid manifest, every file the manifest names present, presets and script that parse.
 */
object PluginArchive {
    const val EXTENSION = "knsplugin"
    const val MAX_TOTAL_BYTES = 512L * 1024 * 1024
    const val MAX_FILES = 2000
    private const val MAX_TEXT_BYTES = 4L * 1024 * 1024

    class Package(
        val manifest: PluginManifest,
        val presets: List<Preset>,
        val script: String?,
        /** Text files (`.js`, `.json`, `.txt`) by path; readable by the script. */
        val texts: Map<String, String>,
        /** Every file path in the package with its size. */
        val files: Map<String, Long>,
    ) {
        val totalBytes get() = files.values.sum()
    }

    fun isSafePath(path: String): Boolean =
        path.isNotBlank() && path.length <= 200 && !path.startsWith("/") && !path.contains('\\') && !path.contains(':') &&
            path.split('/').none { it == ".." || it == "." || it.isEmpty() } && path.none { it.code < 32 }

    private fun isText(path: String) = path.endsWith(".json") || path.endsWith(".js") || path.endsWith(".txt") || path.endsWith(".md")

    /**
     * Reads a package. [store] receives every file (path and data) as it is read, for the caller to write into a
     * staging directory; the caller keeps the result only when this returns without an exception.
     */
    fun read(input: InputStream, store: ((path: String, data: InputStream) -> Unit)? = null): Package {
        val texts = HashMap<String, String>()
        val files = LinkedHashMap<String, Long>()
        var total = 0L
        try {
            ZipInputStream(input).use { zip ->
                while (true) {
                    val e = zip.nextEntry ?: break
                    if (e.isDirectory) continue
                    val name = e.name
                    if (!isSafePath(name)) throw PluginException("caminho inválido no pacote: \"$name\"")
                    if (name in files) throw PluginException("arquivo repetido no pacote: \"$name\"")
                    if (files.size >= MAX_FILES) throw PluginException("pacote com arquivos demais")
                    val counting = object : java.io.FilterInputStream(zip) {
                        var n = 0L
                        override fun read(): Int = super.read().also { if (it >= 0) count(1) }
                        override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { if (it > 0) count(it.toLong()) }
                        private fun count(k: Long) {
                            n += k
                            if (total + n > MAX_TOTAL_BYTES) throw PluginException("pacote grande demais (limite ${MAX_TOTAL_BYTES / 1024 / 1024} MB)")
                        }
                        override fun close() = Unit
                    }
                    if (isText(name) || name == PluginManifest.FILE) {
                        val buf = ByteArrayOutputStream()
                        val b = ByteArray(16 * 1024)
                        while (true) {
                            val r = counting.read(b); if (r < 0) break
                            if (counting.n > MAX_TEXT_BYTES) throw PluginException("arquivo de texto grande demais: \"$name\"")
                            buf.write(b, 0, r)
                        }
                        val bytes = buf.toByteArray()
                        texts[name] = String(bytes, Charsets.UTF_8)
                        store?.invoke(name, bytes.inputStream())
                    } else {
                        if (store != null) store(name, counting) else counting.skip(Long.MAX_VALUE)
                        while (counting.read() >= 0) Unit
                    }
                    files[name] = counting.n
                    total += counting.n
                }
            }
        } catch (e: ZipException) {
            throw PluginException("pacote .knsplugin inválido: ${e.message}")
        }
        val manifestText = texts[PluginManifest.FILE] ?: throw PluginException("o pacote não tem ${PluginManifest.FILE} na raiz")
        val manifest = PluginManifest.parse(manifestText).getOrThrow()
        if (manifest.type == PluginType.NATIVE) throw PluginException("plugins nativos só existem dentro do app")
        val missing = (manifest.presets + listOfNotNull(manifest.script)).filter { it !in files }
        if (missing.isNotEmpty()) throw PluginException("arquivos citados no manifesto e ausentes: ${missing.joinToString()}")
        val presets = manifest.presets.flatMap { path ->
            when (val r = Presets.parse(manifest.id, texts[path] ?: throw PluginException("\"$path\" deve ser JSON"))) {
                is PluginResult.Ok -> r.value
                is PluginResult.Err -> throw PluginException("$path: " + r.errors.joinToString("; "))
            }
        }
        val script = manifest.script?.let { texts[it] ?: throw PluginException("o script deve ser um arquivo .js") }
        if (script != null) {
            // Syntax check now, so a broken script is not installed.
            try {
                ScriptSandbox.run(script + "\nfunction __kinesisCheck(){return null}", "__kinesisCheck", "{}", limits = ScriptLimits(maxMillis = 3000))
            } catch (e: PluginScriptError) {
                throw PluginException("erro no script: ${e.message}")
            }
            manifest.commands.forEach { c ->
                if (!Regex("function\\s+${Regex.escape(c.function)}\\s*\\(|\\b${Regex.escape(c.function)}\\s*=").containsMatchIn(script)) {
                    throw PluginException("a função \"${c.function}\" do comando \"${c.title}\" não está no script")
                }
            }
        }
        return Package(manifest, presets, script, texts.filterKeys { it != PluginManifest.FILE }, files)
    }
}
