package dev.kinesis.core.knsfx

import dev.kinesis.core.model.EffectCategory
import dev.kinesis.core.model.EffectParam
import dev.kinesis.core.model.EffectSpec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/** A `.knsfx` package that cannot be used; [message] is shown to the user as is. */
class KnsfxException(message: String, val log: String? = null) : Exception(message)

/** Version of the running app, compared with `minKinesis` (set by the app at start-up). */
object KinesisVersion {
    @Volatile var current: String = "0.2.6"

    /** Compares dotted versions numerically ("0.2.10" > "0.2.9"); a suffix such as "-hotfix" is ignored. */
    fun compare(a: String, b: String): Int {
        fun parts(v: String) = v.trim().removePrefix("v").split('.').map { p -> p.takeWhile { it.isDigit() }.toIntOrNull() ?: 0 }
        val x = parts(a)
        val y = parts(b)
        for (i in 0 until maxOf(x.size, y.size)) {
            val c = (x.getOrElse(i) { 0 }).compareTo(y.getOrElse(i) { 0 })
            if (c != 0) return c
        }
        return 0
    }
}

/** `manifest.json` of a `.knsfx` package (see `docs/knsfx.md`). Unknown fields are ignored. */
data class KnsfxManifest(
    val formatVersion: Int,
    val id: String,
    val name: String,
    val description: String = "",
    val author: String = "",
    val version: String = "1.0.0",
    val category: String = "",
    val tags: List<String> = emptyList(),
    val thumb: String? = null,
    val minKinesis: String? = null,
    val params: String? = null,
    val script: String? = null,
    val shader: String? = null,
) {
    companion object {
        const val FORMAT = "knsfx"
        const val FORMAT_VERSION = 1
        private val ID = Regex("^[a-z][a-z0-9_]*(\\.[a-z0-9_]+)+$", RegexOption.IGNORE_CASE)

        /** Parses and checks a manifest. [appVersion] is compared with `minKinesis`. */
        fun parse(text: String, appVersion: String = KinesisVersion.current): KnsfxManifest {
            val obj = try {
                Json.parseToJsonElement(text) as? JsonObject
            } catch (e: Exception) {
                null
            } ?: throw KnsfxException("manifest.json não é um objeto JSON válido")
            fun str(name: String): String? = (obj[name] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.trim()?.ifEmpty { null }
            if (str("format") != FORMAT) throw KnsfxException("manifest.json: \"format\" precisa ser \"$FORMAT\"")
            val fv = (obj["formatVersion"] as? JsonPrimitive)?.intOrNull ?: throw KnsfxException("manifest.json: falta \"formatVersion\"")
            if (fv < 1) throw KnsfxException("manifest.json: \"formatVersion\" inválido")
            if (fv > FORMAT_VERSION) throw KnsfxException("Este efeito usa o formato knsfx $fv; esta versão do Kinesis lê até o $FORMAT_VERSION. Atualize o app.")
            val id = str("id") ?: throw KnsfxException("manifest.json: falta \"id\"")
            if (!ID.matches(id) || id.length > 120) throw KnsfxException("manifest.json: \"id\" precisa ser um domínio reverso, como dev.autor.efeito (recebido: \"$id\")")
            val name = str("name") ?: throw KnsfxException("manifest.json: falta \"name\"")
            val script = str("script")
            val shader = str("shader")
            if (script == null && shader == null) throw KnsfxException("manifest.json: o efeito precisa de \"script\" ou \"shader\" (ou dos dois)")
            val min = str("minKinesis")
            if (min != null && KinesisVersion.compare(min, appVersion) > 0) {
                throw KnsfxException("O efeito \"$name\" precisa do Kinesis $min ou mais novo (este é o $appVersion).")
            }
            for ((field, path) in listOf("params" to str("params"), "script" to script, "shader" to shader, "thumb" to str("thumb"))) {
                if (path != null && !isSafePath(path)) throw KnsfxException("manifest.json: caminho inseguro em \"$field\": \"$path\"")
            }
            val tags = (obj["tags"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }.orEmpty()
            return KnsfxManifest(
                formatVersion = fv, id = id, name = name.take(80), description = str("description").orEmpty().take(500),
                author = str("author").orEmpty().take(80), version = str("version") ?: "1.0.0", category = str("category").orEmpty(),
                tags = tags.take(20), thumb = str("thumb"), minKinesis = min, params = str("params"), script = script, shader = shader,
            )
        }

        /** Relative path inside the ZIP: no absolute paths, no `..`, no backslashes or drive letters. */
        fun isSafePath(path: String): Boolean =
            path.isNotBlank() && path.length <= 200 && !path.startsWith("/") && !path.contains('\\') && !path.contains(':') &&
                path.split('/').none { it == ".." || it == "." || it.isEmpty() } && path.none { it.code < 32 }
    }
}

/**
 * A read and checked `.knsfx` package. [bytes] is the package as received (embedded in projects that use it);
 * [sha256] identifies the exact contents.
 */
class KnsfxPackage(
    val manifest: KnsfxManifest,
    val params: List<EffectParam>,
    val script: String?,
    val shader: String?,
    val thumb: ByteArray?,
    val bytes: ByteArray,
) {
    val id: String get() = manifest.id
    val sha256: String by lazy { MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) } }

    /** The effect as the rest of the app sees it (picker, properties, project format). */
    val spec: EffectSpec by lazy {
        EffectSpec(
            id = manifest.id,
            category = categoryOf(manifest.category),
            namePt = manifest.name, nameEn = manifest.name,
            descriptionPt = manifest.description, descriptionEn = manifest.description,
            params = params,
            knsfx = this,
        )
    }

    companion object {
        const val EXTENSION = "knsfx"
        const val MAX_BYTES = 16 * 1024 * 1024
        const val MAX_FILES = 64
        private const val MAX_TEXT = 1024 * 1024

        fun categoryOf(name: String): EffectCategory = when (name.lowercase()) {
            "transform", "motion", "move" -> EffectCategory.MOTION
            "color", "colour", "light", "colorlight" -> EffectCategory.COLOR_LIGHT
            "blur", "sharpen", "blursharpen" -> EffectCategory.BLUR_SHARPEN
            "distort", "distortion" -> EffectCategory.DISTORT
            "stylize", "style" -> EffectCategory.STYLIZE
            "glitch" -> EffectCategory.GLITCH
            "draw", "edges", "drawedges" -> EffectCategory.DRAW_EDGES
            "procedural", "generate" -> EffectCategory.PROCEDURAL
            "keying", "matte", "key" -> EffectCategory.KEYING
            "time", "transition" -> EffectCategory.TIME
            else -> EffectCategory.UTILITY
        }

        /**
         * Reads a package from its bytes and checks everything that can be checked without a GPU: the manifest, the
         * parameters, the paths (no zip-slip), the sizes and the script's syntax. Throws [KnsfxException].
         */
        fun read(bytes: ByteArray, appVersion: String = KinesisVersion.current): KnsfxPackage {
            if (bytes.size > MAX_BYTES) throw KnsfxException("O arquivo .knsfx passa de ${MAX_BYTES / 1024 / 1024} MB")
            val files = LinkedHashMap<String, ByteArray>()
            try {
                ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                    while (true) {
                        val e = zip.nextEntry ?: break
                        if (e.isDirectory) continue
                        if (!KnsfxManifest.isSafePath(e.name)) throw KnsfxException("Caminho inseguro dentro do .knsfx: \"${e.name}\"")
                        if (files.size >= MAX_FILES) throw KnsfxException("O .knsfx tem arquivos demais")
                        val out = ByteArrayOutputStream()
                        val buf = ByteArray(16 * 1024)
                        var total = 0
                        while (true) {
                            val n = zip.read(buf)
                            if (n < 0) break
                            total += n
                            if (total > MAX_BYTES) throw KnsfxException("O conteúdo do .knsfx passa de ${MAX_BYTES / 1024 / 1024} MB")
                            out.write(buf, 0, n)
                        }
                        files[e.name] = out.toByteArray()
                    }
                }
            } catch (e: KnsfxException) {
                throw e
            } catch (e: Exception) {
                throw KnsfxException("O arquivo não é um .knsfx (ZIP) válido: ${e.message}")
            }
            val manifestBytes = files["manifest.json"] ?: throw KnsfxException("O .knsfx não tem manifest.json na raiz")
            val manifest = KnsfxManifest.parse(manifestBytes.toString(Charsets.UTF_8), appVersion)
            fun text(path: String?, what: String): String? {
                path ?: return null
                val b = files[path] ?: throw KnsfxException("O manifest aponta para \"$path\" ($what), mas o arquivo não está no .knsfx")
                if (b.size > MAX_TEXT) throw KnsfxException("\"$path\" é grande demais")
                return b.toString(Charsets.UTF_8)
            }
            val params = text(manifest.params, "parâmetros")?.let { KnsfxParams.parse(it) }.orEmpty()
            val script = text(manifest.script, "script")
            val shader = text(manifest.shader, "shader")
            if (script != null) KnsfxScripts.check(script)
            if (shader != null) KnsfxShader.checkUniforms(shader, params)
            val thumb = manifest.thumb?.let { files[it] ?: throw KnsfxException("O manifest aponta para a miniatura \"$it\", mas ela não está no .knsfx") }
            return KnsfxPackage(manifest, params, script, shader, thumb, bytes)
        }
    }
}
