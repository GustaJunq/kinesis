package dev.kinesis.editor.plugins

import android.content.Context
import dev.kinesis.core.plugin.PluginArchive
import dev.kinesis.core.plugin.PluginCommand
import dev.kinesis.core.plugin.PluginException
import dev.kinesis.core.plugin.PluginManifest
import dev.kinesis.core.plugin.PluginPermission
import dev.kinesis.core.plugin.PluginResult
import dev.kinesis.core.plugin.PluginType
import dev.kinesis.core.plugin.Preset
import dev.kinesis.core.plugin.Presets
import dev.kinesis.editor.BuildConfig
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import java.io.File
import java.io.InputStream
import java.util.UUID

/** A plugin as the app sees it: installed from a package or official (compiled with the app). */
data class InstalledPlugin(
    val manifest: PluginManifest,
    val official: Boolean,
    val installed: Boolean,
    val enabled: Boolean,
    val sizeBytes: Long = 0,
) {
    val id get() = manifest.id
    val active get() = installed && enabled
}

/** A package that was read and checked, waiting for the user to confirm the permissions. */
class StagedPlugin(val pkg: PluginArchive.Package, internal val dir: File, val replaces: InstalledPlugin?)

/**
 * Plugins on the device: `files/plugins/<id>/` holds each package as it came (manifest, presets, script, assets);
 * `files/plugins/state.json` holds which are disabled and which official plugins were uninstalled.
 */
class PluginStore(private val context: Context) {
    private val root = File(context.filesDir, "plugins").apply { mkdirs() }
    private val stateFile = File(root, "state.json")
    private val json = Json { prettyPrint = true }

    private data class State(val disabled: Set<String>, val removedOfficial: Set<String>)

    private fun state(): State = runCatching {
        val o = json.parseToJsonElement(stateFile.readText()) as JsonObject
        fun set(k: String) = (o[k] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content }?.toSet().orEmpty()
        State(set("disabled"), set("removedOfficial"))
    }.getOrDefault(State(emptySet(), emptySet()))

    private fun save(s: State) {
        val o = JsonObject(
            mapOf(
                "disabled" to JsonArray(s.disabled.sorted().map { JsonPrimitive(it) }),
                "removedOfficial" to JsonArray(s.removedOfficial.sorted().map { JsonPrimitive(it) }),
            ),
        )
        val tmp = File(root, "state.json.tmp")
        tmp.writeText(json.encodeToString(JsonObject.serializer(), o))
        tmp.renameTo(stateFile)
    }

    fun dir(id: String) = File(root, id)

    private fun readManifest(dir: File): PluginManifest? =
        runCatching { (PluginManifest.parse(File(dir, PluginManifest.FILE).readText()) as? PluginResult.Ok)?.value }.getOrNull()

    /** Official plugins first, then the installed packages by name. */
    fun list(): List<InstalledPlugin> {
        val s = state()
        val official = OfficialPlugins.all.map { m ->
            val installed = if (m.id == OfficialPlugins.KOKORO_ID) KokoroFiles.isInstalled(context) else m.id !in s.removedOfficial
            InstalledPlugin(m, official = true, installed = installed, enabled = m.id !in s.disabled, sizeBytes = if (m.id == OfficialPlugins.KOKORO_ID && installed) KokoroFiles.totalBytes else 0)
        }
        val packages = root.listFiles { f -> f.isDirectory && !f.name.startsWith(".") }.orEmpty().mapNotNull { d ->
            val m = readManifest(d) ?: return@mapNotNull null
            InstalledPlugin(m, official = false, installed = true, enabled = m.id !in s.disabled, sizeBytes = d.walkTopDown().filter { it.isFile }.sumOf { it.length() })
        }.sortedBy { it.manifest.name.lowercase() }
        return official + packages
    }

    fun find(id: String) = list().firstOrNull { it.id == id }

    /** Reads and checks a `.knsplugin` into a staging directory. Throws [PluginException] with the reasons. */
    fun stage(input: InputStream): StagedPlugin {
        val dir = File(root, ".staging-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            val pkg = PluginArchive.read(input) { path, data ->
                val f = File(dir, path)
                if (!f.canonicalPath.startsWith(dir.canonicalPath + File.separator)) throw PluginException("caminho inválido: $path")
                f.parentFile?.mkdirs()
                f.outputStream().use { data.copyTo(it, 64 * 1024) }
            }
            val m = pkg.manifest
            if (!m.supports(BuildConfig.VERSION_NAME)) {
                throw PluginException("este plugin precisa do Kinesis ${m.minKinesisVersion} ou mais novo (este é o ${BuildConfig.VERSION_NAME})")
            }
            if (OfficialPlugins.all.any { it.id == m.id }) throw PluginException("o id ${m.id} é de um plugin oficial")
            if (m.type == PluginType.DATA) {
                val host = m.extends ?: throw PluginException("pacote de dados sem o campo \"extends\"")
                if (OfficialPlugins.all.none { it.id == host }) throw PluginException("o plugin $host deste pacote não existe")
            }
            return StagedPlugin(pkg, dir, find(m.id))
        } catch (e: Exception) {
            dir.deleteRecursively()
            throw e
        }
    }

    fun discard(staged: StagedPlugin) {
        staged.dir.deleteRecursively()
    }

    /** Installs a staged package (replacing an older copy) and enables it. */
    fun install(staged: StagedPlugin) {
        val target = dir(staged.pkg.manifest.id)
        val old = File(root, ".old-${UUID.randomUUID()}")
        if (target.exists() && !target.renameTo(old)) throw PluginException("não foi possível substituir a versão instalada")
        if (!staged.dir.renameTo(target)) {
            old.renameTo(target)
            throw PluginException("não foi possível instalar")
        }
        old.deleteRecursively()
        val s = state()
        save(s.copy(disabled = s.disabled - staged.pkg.manifest.id))
    }

    fun setEnabled(id: String, enabled: Boolean) {
        val s = state()
        save(s.copy(disabled = if (enabled) s.disabled - id else s.disabled + id))
    }

    /** Removes a package, or marks an official plugin as uninstalled (it can be reinstalled from the list). */
    fun uninstall(id: String) {
        if (id == OfficialPlugins.KOKORO_ID) {
            KokoroFiles.delete(context)
        } else if (OfficialPlugins.all.any { it.id == id }) {
            val s = state()
            save(s.copy(removedOfficial = s.removedOfficial + id))
        } else {
            dir(id).deleteRecursively()
            val s = state()
            save(s.copy(disabled = s.disabled - id))
        }
    }

    fun reinstallOfficial(id: String) {
        val s = state()
        save(s.copy(removedOfficial = s.removedOfficial - id, disabled = s.disabled - id))
    }

    /** Presets of the active plugins. Broken preset files are skipped. */
    fun presets(): List<Preset> = list().filter { it.active && !it.official }.flatMap { p ->
        p.manifest.presets.flatMap { path ->
            val text = runCatching { File(dir(p.id), path).readText() }.getOrNull() ?: return@flatMap emptyList()
            (Presets.parse(p.id, text) as? PluginResult.Ok)?.value.orEmpty()
        }
    }

    /** Script commands of the active plugins. */
    fun commands(): List<Pair<InstalledPlugin, PluginCommand>> =
        list().filter { it.active && it.manifest.type == PluginType.SCRIPT }.flatMap { p -> p.manifest.commands.map { p to it } }

    fun script(p: InstalledPlugin): String = File(dir(p.id), p.manifest.script ?: throw PluginException("plugin sem script")).readText()

    /** The text files of a plugin (what `kinesis.readText` may read). */
    fun texts(p: InstalledPlugin): Map<String, String> {
        val d = dir(p.id)
        return d.walkTopDown().filter { it.isFile && (it.extension in setOf("json", "js", "txt", "md")) && it.length() < 4 * 1024 * 1024 }
            .associate { it.relativeTo(d).invariantSeparatorsPath to it.readText() }
    }

    /** Installed data packages that extend the official plugin [hostId]. */
    fun dataFor(hostId: String): List<InstalledPlugin> =
        list().filter { it.active && it.manifest.type == PluginType.DATA && it.manifest.extends == hostId }

    @Suppress("unused")
    private fun JsonPrimitive.bool() = booleanOrNull

    companion object {
        fun describe(p: PluginPermission): Int = when (p) {
            PluginPermission.PROJECT_READ -> dev.kinesis.editor.R.string.perm_project_read
            PluginPermission.PROJECT_WRITE -> dev.kinesis.editor.R.string.perm_project_write
            PluginPermission.MEDIA_WRITE -> dev.kinesis.editor.R.string.perm_media_write
            PluginPermission.SPEECH -> dev.kinesis.editor.R.string.perm_speech
            PluginPermission.DOWNLOAD -> dev.kinesis.editor.R.string.perm_download
        }
    }
}
