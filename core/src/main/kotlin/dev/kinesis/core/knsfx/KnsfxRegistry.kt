package dev.kinesis.core.knsfx

import dev.kinesis.core.model.EffectRegistry
import dev.kinesis.core.model.KnsProject
import dev.kinesis.core.model.Layer
import java.util.concurrent.ConcurrentHashMap

/**
 * The `.knsfx` effects known to this run of the app: the ones installed in the library and the ones embedded in the
 * projects that were opened. An installed package wins over an embedded one with the same id.
 */
object KnsfxRegistry {
    private val packages = ConcurrentHashMap<String, KnsfxPackage>()
    private val library = ConcurrentHashMap.newKeySet<String>()

    fun get(id: String): KnsfxPackage? = packages[id]

    fun bytes(id: String): ByteArray? = packages[id]?.bytes

    /** Installed packages (the "Efeitos customizados" list). */
    fun installed(): List<KnsfxPackage> = library.mapNotNull { packages[it] }.sortedBy { it.manifest.name.lowercase() }

    fun isInstalled(id: String) = id in library

    /** Registers an installed package (replacing what was there). */
    fun install(pkg: KnsfxPackage) {
        packages[pkg.id] = pkg
        library += pkg.id
        EffectRegistry.registerCustom(pkg.spec)
    }

    /** Removes an installed package. Projects that embed it register their copy again when opened. */
    fun uninstall(id: String) {
        library -= id
        packages.remove(id)
        EffectRegistry.unregisterCustom(id)
    }

    /**
     * Registers a package embedded in a project, unless one with that id is already known. Invalid packages are
     * ignored (the effects that use them stay in the project, untouched, and are not rendered). Returns the package
     * that is now registered for that id, or null.
     */
    fun registerEmbedded(bytes: ByteArray): KnsfxPackage? {
        val pkg = runCatching { KnsfxPackage.read(bytes) }.getOrNull() ?: return null
        packages.putIfAbsent(pkg.id, pkg)?.let { return it }
        EffectRegistry.registerCustom(pkg.spec)
        return pkg
    }

    /** Ids of the custom effects used anywhere in [project] (groups included). */
    fun usedIn(project: KnsProject): Set<String> {
        val out = LinkedHashSet<String>()
        fun visit(layers: List<Layer>) {
            for (l in layers) {
                for (e in l.effects) if (EffectRegistry.isCustomId(e.type)) out += e.type
                visit(l.layers)
            }
        }
        visit(project.layers)
        return out
    }
}
