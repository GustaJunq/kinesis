package dev.kinesis.core.edit

import dev.kinesis.core.model.Asset
import dev.kinesis.core.model.KnsProject
import dev.kinesis.core.model.Layer
import dev.kinesis.core.model.LayerTree
import dev.kinesis.core.model.LayerType
import dev.kinesis.core.model.MediaContent
import dev.kinesis.core.model.Transform2D
import kotlin.math.max
import kotlin.math.min

/**
 * Pre-rendering a heavy layer: render it alone (transparent background) into a video with packed alpha, then
 * show that video instead of the layer. The original layer stays in the project, hidden, so it can be edited and
 * rendered again.
 */
object PreRender {

    /** Composition time span rendered for [layerId] (its visible span inside the composition), or null. */
    fun range(project: KnsProject, layerId: String): ClosedFloatingPointRange<Double>? {
        val layer = LayerTree.find(project, layerId) ?: return null
        val offset = LayerTree.containerOffset(project, layerId)
        var start = offset + layer.start
        var end = offset + layer.end
        // Groups clip their children to their own span.
        for (g in LayerTree.ancestors(project, layerId)) {
            val group = LayerTree.find(project, g) ?: continue
            val gOffset = LayerTree.containerOffset(project, g)
            start = max(start, gOffset + group.start)
            end = min(end, gOffset + group.end)
        }
        start = max(start, 0.0)
        end = min(end, project.composition.duration)
        return if (end - start > 1e-6) start..end else null
    }

    /** Whether [layer] draws something that can be pre-rendered. */
    fun canPreRender(project: KnsProject, layerId: String): Boolean {
        val layer = LayerTree.find(project, layerId) ?: return false
        return layer.type.isVisual && layer.type != LayerType.AUDIO && range(project, layerId) != null
    }

    /**
     * The project with only [layerId] visible: other drawn layers are hidden, while the groups holding it, its
     * own children, cameras, lights and null objects (which shape how it looks) stay.
     */
    fun isolate(project: KnsProject, layerId: String): KnsProject {
        val keep = HashSet<String>()
        keep += layerId
        keep += LayerTree.ancestors(project, layerId)
        LayerTree.find(project, layerId)?.let { l -> if (l.type == LayerType.GROUP) LayerTree.walk(l.layers) { c, _ -> keep += c.id } }
        fun strip(layers: List<Layer>): List<Layer> = layers.map { l ->
            val shaping = l.type == LayerType.CAMERA || l.type == LayerType.LIGHT || l.type == LayerType.NULL
            val hidden = if (l.id in keep || shaping) l else l.copy(visible = false)
            if (hidden.type == LayerType.GROUP) hidden.copy(layers = strip(hidden.layers)) else hidden
        }
        return project.copy(layers = strip(project.layers))
    }

    /**
     * Adds a video layer showing [asset] (the pre-render of [layerId] over [range], packed alpha) at the top level,
     * just above the top-level layer that holds [layerId], and hides [layerId]. Group transforms and parents were
     * rendered into the video, so the new layer has none.
     */
    fun replace(project: KnsProject, layerId: String, asset: Asset, range: ClosedFloatingPointRange<Double>, name: String): Pair<KnsProject, String> {
        val layer = LayerTree.find(project, layerId) ?: error("No layer $layerId")
        val topId = LayerTree.ancestors(project, layerId).firstOrNull() ?: layerId
        val index = project.layers.indexOfFirst { it.id == topId }.let { if (it < 0) project.layers.size else it + 1 }
        val withAsset = if (project.assets.any { it.id == asset.id }) project else project.copy(assets = project.assets + asset)
        val base = Factory.video(withAsset, range.start, asset, name)
        val video = base.copy(
            duration = range.endInclusive - range.start,
            video = MediaContent(asset.id, alphaPacked = true),
            transform = Transform2D(),
            label = layer.label,
        )
        val hidden = LayerTree.update(withAsset, layerId) { it.copy(visible = false) }
        return LayerTree.insert(hidden, null, index, video) to video.id
    }
}
