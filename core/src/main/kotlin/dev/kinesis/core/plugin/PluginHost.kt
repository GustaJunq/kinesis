package dev.kinesis.core.plugin

import dev.kinesis.core.format.KnsprojJson
import dev.kinesis.core.format.ProjectValidator
import dev.kinesis.core.model.KnsProject
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** What a script command produced: a changed project (or null) and a message for the user. */
data class CommandOutcome(val project: KnsProject?, val message: String?, val log: List<String>)

/**
 * Prepares the input of a script command and checks its output. The script function receives
 * `{ apiVersion, time, selection, params, project? }` (`project` only with `project.read`) and may return
 * `{ project?, message? }` (`project` only with `project.write`). A returned project must be valid and keep the same
 * assets: plugins cannot add, remove or point to files.
 */
object PluginHost {

    fun input(manifest: PluginManifest, project: KnsProject, selection: List<String>, time: Double, params: Map<String, JsonElement>): String {
        val o = buildJsonObject {
            put("apiVersion", ScriptSandbox.API_VERSION)
            put("time", time)
            put("selection", buildJsonArray { if (manifest.has(PluginPermission.PROJECT_READ)) selection.forEach { add(JsonPrimitive(it)) } })
            put("params", JsonObject(params))
            if (manifest.has(PluginPermission.PROJECT_READ)) put("project", KnsprojJson.encodeToElement(project))
        }
        return o.toString()
    }

    /** Interprets [resultJson], the text a command returned, against the [before] project. Throws [PluginException]. */
    fun outcome(manifest: PluginManifest, before: KnsProject, resultJson: String, log: List<String> = emptyList()): CommandOutcome {
        val result = runCatching { KnsprojJson.json.parseToJsonElement(resultJson) }.getOrElse { throw PluginException("resultado do plugin não é JSON") }
        if (result is JsonNull) return CommandOutcome(null, null, log)
        val o = result as? JsonObject ?: throw PluginException("o plugin deve devolver um objeto ({ project, message })")
        val message = (o["message"] as? JsonPrimitive)?.content?.take(500)
        val p = o["project"] ?: return CommandOutcome(null, message, log)
        if (p is JsonNull) return CommandOutcome(null, message, log)
        if (!manifest.has(PluginPermission.PROJECT_WRITE)) throw PluginException("o plugin tentou alterar o projeto sem a permissão project.write")
        val changed = runCatching { KnsprojJson.decodeElement(p) }.getOrElse { throw PluginException("projeto devolvido inválido: ${it.message}") }
        if (changed.assets != before.assets) throw PluginException("plugins não podem adicionar, remover ou alterar arquivos de mídia")
        val report = ProjectValidator.validate(changed)
        if (!report.isValid) {
            throw PluginException("o plugin gerou um projeto inválido:\n" + report.errors.take(5).joinToString("\n") { "${it.path}: ${it.message}" })
        }
        return CommandOutcome(changed, message, log)
    }

    /** Default values of the command parameters, as JSON. */
    fun defaults(command: PluginCommand): Map<String, JsonElement> = command.params.associate { it.id to it.default }
}
