package dev.kinesis.core.edit

import dev.kinesis.core.anim.Animatable
import dev.kinesis.core.anim.Keyframe
import dev.kinesis.core.model.Asset
import dev.kinesis.core.model.ImageContent
import dev.kinesis.core.model.KnsProject
import dev.kinesis.core.model.Layer
import dev.kinesis.core.model.LayerTree
import dev.kinesis.core.model.LayerType
import dev.kinesis.core.model.RotoBrush
import dev.kinesis.core.math.Vec2

/**
 * Shows a processed version of an image or video layer (background removed, stabilized) instead of the original:
 * a copy of the layer pointing at the new [Asset] goes right above it and the original stays, hidden, so the
 * operation is undone by one undo and the original can be shown again.
 */
object MediaSwap {
    /** The kinds of layer that can be processed. */
    fun canSwap(layer: Layer): Boolean = (layer.type == LayerType.IMAGE && layer.image != null) || (layer.type == LayerType.VIDEO && layer.video != null)

    /**
     * @param sourceStart in-point of the layer in the new file (seconds); videos only.
     * @param scale factor for the layer's scale when the new file has fewer pixels than the original.
     * @param alphaPacked the new video holds colour and alpha side by side.
     * @param muted whether the new copy is silent (the new file has no sound).
     * @param roto the Roto Brush recipe of the new video, when it is a cut-out made by hand.
     * @return the project and the id of the new layer.
     */
    fun replace(
        project: KnsProject, layerId: String, asset: Asset, name: String,
        sourceStart: Double? = null, scale: Float = 1f, alphaPacked: Boolean? = null, muted: Boolean? = null,
        roto: RotoBrush? = null,
    ): Pair<KnsProject, String> {
        val layer = LayerTree.find(project, layerId) ?: error("No layer $layerId")
        require(canSwap(layer)) { "Layer $layerId is not an image or a video" }
        val loc = LayerTree.locate(project, layerId) ?: error("No layer $layerId")
        val withAsset = if (project.assets.any { it.id == asset.id }) project else project.copy(assets = project.assets + asset)
        val id = Ids.newId(TimelineOps.prefixFor(layer.type), LayerTree.allIds(withAsset))
        val s = layer.transform.scale
        val scaled = if (scale == 1f) s else Animatable(
            value = s.value * scale,
            keyframes = s.keyframes.map { Keyframe(it.time, it.value * scale, it.easing) },
            loop = s.loop, expression = s.expression, expressionEnabled = s.expressionEnabled,
        )
        val copy = layer.copy(
            id = id, name = name, transform = layer.transform.copy(scale = scaled),
            image = layer.image?.let { ImageContent(asset.id) },
            video = layer.video?.let { v ->
                v.copy(
                    asset = asset.id,
                    sourceStart = sourceStart ?: v.sourceStart,
                    alphaPacked = alphaPacked ?: v.alphaPacked,
                    muted = muted ?: v.muted,
                    roto = roto ?: v.roto,
                )
            },
        )
        val hidden = LayerTree.update(withAsset, layerId) { it.copy(visible = false) }
        return LayerTree.insert(hidden, loc.containerId, loc.index + 1, copy) to id
    }
}
