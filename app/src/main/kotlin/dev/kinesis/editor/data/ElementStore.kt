package dev.kinesis.editor.data

import android.content.Context
import dev.kinesis.core.elements.ElementBackground
import dev.kinesis.core.elements.Elements
import dev.kinesis.core.format.KnsprojJson
import dev.kinesis.core.model.Asset
import dev.kinesis.core.model.KnsProject
import java.io.File
import java.util.UUID

data class ElementSummary(
    val id: String,
    val name: String,
    val background: ElementBackground,
    val width: Int,
    val height: Int,
    val duration: Double,
    val thumbnail: File?,
)

/**
 * Elements kept on the device: `files/elements/<id>/` holds `project.json` (the element, a normal project whose
 * background says how it is shown), its `assets/` and a thumbnail. Everything is local.
 */
class ElementStore(context: Context) {
    private val root = File(context.filesDir, "elements").apply { mkdirs() }

    fun dir(id: String) = File(root, id)
    private fun projectFile(id: String) = File(dir(id), "project.json")
    fun assetFile(id: String, asset: Asset) = File(dir(id), asset.path)

    fun list(): List<ElementSummary> = root.listFiles { f -> f.isDirectory && !f.name.startsWith(".") }.orEmpty().mapNotNull { d ->
        val p = runCatching { KnsprojJson.decode(File(d, "project.json").readText()) }.getOrNull() ?: return@mapNotNull null
        ElementSummary(
            d.name, p.meta.title, ElementBackground.of(p.composition.background) ?: ElementBackground.TRANSPARENT,
            p.composition.width, p.composition.height, p.composition.duration, File(d, "thumbnail.jpg").takeIf { it.isFile },
        )
    }.sortedBy { it.name.lowercase() }

    fun load(id: String): KnsProject = KnsprojJson.decode(projectFile(id).readText())

    /**
     * Saves [project] (with its media files, found by [files]) as a new element called [name].
     */
    fun save(project: KnsProject, name: String, background: ElementBackground, files: (Asset) -> File?, thumbnail: File?): String {
        val id = UUID.randomUUID().toString()
        val tmp = File(root, ".new-$id").apply { mkdirs() }
        try {
            // Every media file goes along (some are referenced from places other than the layer content).
            val assets = project.assets
            for (a in assets) {
                val src = files(a)?.takeIf { it.isFile } ?: continue
                val dst = File(tmp, a.path)
                dst.parentFile?.mkdirs()
                src.copyTo(dst, overwrite = true)
            }
            val element = Elements.asElement(project, background).let {
                it.copy(meta = it.meta.copy(title = name), assets = assets.filter { a -> File(tmp, a.path).isFile })
            }
            File(tmp, "project.json").writeText(KnsprojJson.encode(element))
            thumbnail?.takeIf { it.isFile }?.copyTo(File(tmp, "thumbnail.jpg"), overwrite = true)
            if (!tmp.renameTo(dir(id))) error("cannot store the element")
            return id
        } finally {
            if (tmp.exists()) tmp.deleteRecursively()
        }
    }

    fun rename(id: String, name: String) {
        val p = load(id)
        projectFile(id).writeText(KnsprojJson.encode(p.copy(meta = p.meta.copy(title = name))))
    }

    fun setBackground(id: String, background: ElementBackground) {
        projectFile(id).writeText(KnsprojJson.encode(Elements.asElement(load(id), background)))
    }

    fun delete(id: String) {
        dir(id).deleteRecursively()
    }
}
