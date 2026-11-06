package dev.kinesis.core.plugin

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull

/** What a plugin is made of. */
enum class PluginType(val id: String) {
    /** Declarative JSON presets only (animations, text styles, transitions). */
    PRESETS("presets"),

    /** Commands written in JavaScript, run in the isolated script engine. May also carry presets. */
    SCRIPT("script"),

    /** Official plugin compiled with the app (Kotlin). Cannot be installed from a package. */
    NATIVE("native"),

    /** Data for an official plugin (for example the Kokoro voices); no code. */
    DATA("data"),
    ;

    companion object {
        fun fromId(id: String?) = entries.firstOrNull { it.id == id }
    }
}

/**
 * What a plugin may do. There is no permission for the network, the file system outside the plugin, other apps or
 * the device: those are never reachable from a plugin.
 */
enum class PluginPermission(val id: String, val nativeOnly: Boolean = false) {
    /** Read the open project (layers, keyframes, assets list) and the selection. */
    PROJECT_READ("project.read"),

    /** Change the open project. Every change is validated and can be undone. */
    PROJECT_WRITE("project.write"),

    /** Add media files (for example generated audio) to the project. Official plugins only. */
    MEDIA_WRITE("media.write", nativeOnly = true),

    /** Use the speech synthesis of the device (offline voices). Official plugins only. */
    SPEECH("speech", nativeOnly = true),
    ;

    companion object {
        fun fromId(id: String) = entries.firstOrNull { it.id == id }
    }
}

/** A user-facing input of a command, shown as a form before it runs. */
data class CommandParam(
    val id: String,
    val type: String, // number | text | boolean | choice
    val label: String,
    val default: JsonElement = JsonNull,
    val min: Double? = null,
    val max: Double? = null,
    val options: List<String> = emptyList(),
)

/** A command a script plugin adds to the editor: [function] of the script runs when it is chosen. */
data class PluginCommand(val id: String, val title: String, val function: String, val params: List<CommandParam> = emptyList())

data class PluginManifest(
    val id: String,
    val name: String,
    val version: String,
    val author: String,
    val type: PluginType,
    val permissions: List<PluginPermission>,
    val minKinesisVersion: String,
    val description: String = "",
    /** Preset files inside the package. */
    val presets: List<String> = emptyList(),
    /** Script file inside the package (type `script`). */
    val script: String? = null,
    val commands: List<PluginCommand> = emptyList(),
    /** Plugin that this one provides data for (type `data`). */
    val extends: String? = null,
) {
    companion object {
        const val FORMAT = "kinesis-plugin"
        const val FORMAT_VERSION = 1
        const val FILE = "manifest.json"
        private val ID = Regex("^[a-z][a-z0-9_]*(\\.[a-z0-9_]+)+$")
        private val VERSION = Regex("^\\d+\\.\\d+\\.\\d+([-+][0-9A-Za-z.-]+)?$")
        private val NAME_ID = Regex("^[A-Za-z_][A-Za-z0-9_]*$")

        private val json = Json { ignoreUnknownKeys = true }

        /** Parses and checks a manifest. Errors are in Portuguese, ready to show. */
        fun parse(text: String): PluginResult<PluginManifest> {
            val root = runCatching { json.parseToJsonElement(text) }.getOrNull() as? JsonObject
                ?: return PluginResult.Err(listOf("manifest.json não é um objeto JSON válido"))
            val errors = mutableListOf<String>()
            fun str(key: String, required: Boolean = true): String? {
                val v = (root[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
                if (v.isNullOrBlank()) { if (required) errors += "campo \"$key\" ausente"; return null }
                return v
            }
            if (str("format") != FORMAT) errors += "\"format\" deve ser \"$FORMAT\""
            val fv = (root["formatVersion"] as? JsonPrimitive)?.intOrNull
            if (fv == null || fv > FORMAT_VERSION) errors += "\"formatVersion\" deve ser $FORMAT_VERSION (ou menor)"
            val id = str("id")?.also { if (!ID.matches(it) || it.length > 100) errors += "id \"$it\" inválido (use algo como com.autor.nome)" }
            val name = str("name")?.also { if (it.length > 60) errors += "nome com mais de 60 caracteres" }
            val version = str("version")?.also { if (!VERSION.matches(it)) errors += "versão \"$it\" inválida (use x.y.z)" }
            val author = str("author")
            val min = str("minKinesisVersion")?.also { if (!VERSION.matches(it)) errors += "minKinesisVersion \"$it\" inválida" }
            val type = str("type")?.let { t -> PluginType.fromId(t) ?: run { errors += "tipo \"$t\" desconhecido"; null } }
            val perms = (root["permissions"] as? JsonArray ?: JsonArray(emptyList())).mapNotNull { e ->
                val p = (e as? JsonPrimitive)?.content ?: return@mapNotNull null
                PluginPermission.fromId(p) ?: run {
                    errors += if (p.startsWith("net") || p.contains("internet") || p.contains("http")) {
                        "o Kinesis funciona offline: não existe permissão de rede (\"$p\")"
                    } else "permissão \"$p\" desconhecida"
                    null
                }
            }.distinct()
            if (type != null && type != PluginType.NATIVE) {
                perms.filter { it.nativeOnly }.forEach { errors += "a permissão \"${it.id}\" é só para plugins oficiais" }
            }
            val presets = (root["presets"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content }.orEmpty()
            val script = str("script", required = false)
            val commands = (root["commands"] as? JsonArray)?.mapNotNull { e ->
                val o = e as? JsonObject ?: return@mapNotNull null.also { errors += "comando inválido" }
                val cid = (o["id"] as? JsonPrimitive)?.content
                val fn = (o["function"] as? JsonPrimitive)?.content ?: cid
                val title = (o["title"] as? JsonPrimitive)?.content ?: cid
                if (cid == null || fn == null || !NAME_ID.matches(fn) || title.isNullOrBlank()) {
                    errors += "comando sem id, título ou função válidos"; return@mapNotNull null
                }
                val params = (o["params"] as? JsonArray)?.mapNotNull { pe ->
                    val po = pe as? JsonObject ?: return@mapNotNull null
                    val pid = (po["id"] as? JsonPrimitive)?.content ?: return@mapNotNull null.also { errors += "parâmetro sem id em \"$cid\"" }
                    val ptype = (po["type"] as? JsonPrimitive)?.content ?: "text"
                    if (ptype !in setOf("number", "text", "boolean", "choice")) errors += "parâmetro \"$pid\": tipo \"$ptype\" desconhecido"
                    CommandParam(
                        id = pid, type = ptype, label = (po["label"] as? JsonPrimitive)?.content ?: pid,
                        default = po["default"] ?: JsonNull,
                        min = (po["min"] as? JsonPrimitive)?.doubleOrNull, max = (po["max"] as? JsonPrimitive)?.doubleOrNull,
                        options = (po["options"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content }.orEmpty(),
                    )
                }.orEmpty()
                PluginCommand(cid, title, fn, params)
            }.orEmpty()
            when (type) {
                PluginType.PRESETS -> if (presets.isEmpty()) errors += "plugin de presets sem arquivos em \"presets\""
                PluginType.SCRIPT -> {
                    if (script == null) errors += "plugin de script sem o campo \"script\""
                    if (commands.isEmpty()) errors += "plugin de script sem comandos"
                }
                else -> Unit
            }
            (presets + listOfNotNull(script)).forEach { if (!PluginArchive.isSafePath(it)) errors += "caminho \"$it\" inválido" }
            if (errors.isNotEmpty()) return PluginResult.Err(errors)
            return PluginResult.Ok(
                PluginManifest(
                    id = id!!, name = name!!, version = version!!, author = author!!, type = type!!, permissions = perms,
                    minKinesisVersion = min!!, description = str("description", required = false).orEmpty(),
                    presets = presets, script = script, commands = commands, extends = str("extends", required = false),
                ),
            )
        }

        /** Compares two `x.y.z` versions (suffixes such as `-hotfix` are ignored). */
        fun compareVersions(a: String, b: String): Int {
            fun parts(v: String) = v.split('-', '+').first().split('.').map { it.toIntOrNull() ?: 0 }
            val x = parts(a); val y = parts(b)
            for (i in 0 until maxOf(x.size, y.size)) {
                val c = (x.getOrElse(i) { 0 }).compareTo(y.getOrElse(i) { 0 })
                if (c != 0) return c
            }
            return 0
        }
    }

    /** True when this plugin runs on Kinesis [appVersion]. */
    fun supports(appVersion: String) = compareVersions(appVersion, minKinesisVersion) >= 0

    fun has(p: PluginPermission) = p in permissions
}

sealed class PluginResult<out T> {
    data class Ok<T>(val value: T) : PluginResult<T>()
    data class Err(val errors: List<String>) : PluginResult<Nothing>()

    fun getOrThrow(): T = when (this) {
        is Ok -> value
        is Err -> throw PluginException(errors.joinToString("\n"))
    }
}

class PluginException(message: String) : Exception(message)

internal fun JsonElement?.asBoolean(): Boolean? = (this as? JsonPrimitive)?.booleanOrNull
