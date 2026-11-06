package dev.kinesis.core.elements

import dev.kinesis.core.anim.anim
import dev.kinesis.core.edit.Ids
import dev.kinesis.core.edit.TimelineOps
import dev.kinesis.core.format.KnsprojJson
import dev.kinesis.core.math.Color
import dev.kinesis.core.math.Vec2
import dev.kinesis.core.model.Asset
import dev.kinesis.core.model.FillSpec
import dev.kinesis.core.model.KnsProject
import dev.kinesis.core.model.Layer
import dev.kinesis.core.model.LayerTree
import dev.kinesis.core.model.LayerType
import dev.kinesis.core.model.Mask
import dev.kinesis.core.model.ShapeContent
import dev.kinesis.core.model.ShapeKind
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Background of an element: transparent, or a solid colour (green and blue are chroma-key colours). */
enum class ElementBackground(val id: String, val color: Color) {
    TRANSPARENT("transparent", Color(0f, 0f, 0f, 0f)),
    GREEN("green", Color(0f, 177f / 255f, 64f / 255f)),
    BLUE("blue", Color(0f, 71f / 255f, 187f / 255f)),
    BLACK("black", Color.BLACK),
    WHITE("white", Color.WHITE),
    ;

    companion object {
        fun fromId(id: String?) = entries.firstOrNull { it.id == id } ?: TRANSPARENT

        /** The background a composition colour stands for, or null when it is none of the five. */
        fun of(color: Color): ElementBackground? = entries.firstOrNull {
            if (it == TRANSPARENT) color.a < 0.01f else color.a > 0.99f && kotlin.math.abs(color.r - it.color.r) < 0.01f &&
                kotlin.math.abs(color.g - it.color.g) < 0.01f && kotlin.math.abs(color.b - it.color.b) < 0.01f
        }
    }
}

/**
 * Elements: projects kept as reusable pieces. An element is a normal project whose composition background is one of
 * [ElementBackground]; inserting it into another project makes a group layer with a copy of its layers and media,
 * clipped to the element's frame, with a solid background layer at the bottom unless the background is transparent.
 */
object Elements {

    /** [project] prepared to be kept as an element with [background]. */
    fun asElement(project: KnsProject, background: ElementBackground): KnsProject =
        project.copy(composition = project.composition.copy(background = background.color), editorView = null)

    class Insertion(
        val project: KnsProject,
        /** The new group layer. */
        val layer: Layer,
        /** Assets of the element copied into the project: new asset -> the element's asset it copies. */
        val copiedAssets: Map<Asset, Asset>,
    )

    /**
     * Inserts [element] into [target] as a group named [name] starting at [at] seconds (in [container], or at the top
     * level). Layer and asset ids are renamed so nothing collides; media paths get a unique name in `assets/`.
     */
    fun insert(target: KnsProject, element: KnsProject, name: String, at: Double, container: String? = null): Insertion {
        val takenLayers = LayerTree.allIds(target).toMutableSet()
        val layerIds = HashMap<String, String>()
        fun collect(ls: List<Layer>) {
            for (l in ls) {
                layerIds[l.id] = Ids.newId(TimelineOps.prefixFor(l.type), takenLayers).also { takenLayers += it }
                collect(l.layers)
            }
        }
        collect(element.layers)

        // Assets: new ids and paths, only for the ones the element really uses.
        val used = HashSet<String>()
        LayerTree.all(element).forEach { used += it.referencedAssets() }
        used += element.layers.let { rawAssetRefs(it) }
        val takenAssetIds = target.assets.map { it.id }.toMutableSet()
        val takenPaths = target.assets.map { it.path }.toMutableSet()
        val assetIds = HashMap<String, String>()
        val copied = LinkedHashMap<Asset, Asset>()
        for (a in element.assets.filter { it.id in used }) {
            var id = "${a.id}_e"
            var n = 2
            while (id in takenAssetIds) id = "${a.id}_e${n++}"
            val base = a.path.substringBeforeLast('.')
            val ext = a.path.substringAfterLast('.', "")
            var path = a.path
            n = 2
            while (path in takenPaths) path = "$base-${n++}" + (if (ext.isEmpty()) "" else ".$ext")
            takenAssetIds += id; takenPaths += path
            assetIds[a.id] = id
            copied[a.copy(id = id, path = path)] = a
        }

        fun remap(l: Layer): Layer = l.copy(
            id = layerIds.getValue(l.id),
            parent = l.parent?.let { layerIds[it] },
            matte = l.matte?.let { m -> layerIds[m.layer]?.let { m.copy(layer = it) } },
            layers = l.layers.map(::remap),
        )
        var children = element.layers.map(::remap)
        children = remapAssets(children, assetIds)

        val comp = element.composition
        val size = Vec2(comp.width.toFloat(), comp.height.toFloat())
        val bg = ElementBackground.of(comp.background)
        if (bg != ElementBackground.TRANSPARENT && comp.background.a > 0.01f) {
            val bgId = Ids.newId(TimelineOps.prefixFor(LayerType.SHAPE), takenLayers).also { takenLayers += it }
            children = listOf(
                Layer(
                    id = bgId, type = LayerType.SHAPE, name = "Fundo", start = 0.0, duration = comp.duration,
                    shape = ShapeContent(kind = ShapeKind.RECTANGLE, size = anim(size), fill = FillSpec(color = anim(comp.background))),
                ),
            ) + children
        }
        val groupId = Ids.newId(TimelineOps.prefixFor(LayerType.GROUP), takenLayers)
        val group = Layer(
            id = groupId, type = LayerType.GROUP, name = name, start = at, duration = comp.duration,
            layers = children,
            masks = listOf(Mask(size = anim(size))),
        )
        val withAssets = target.copy(assets = target.assets + copied.keys)
        return Insertion(TimelineOps.addLayer(withAssets, group, container), group, copied)
    }

    private val ASSET_KEYS = setOf("asset", "texture", "source")

    /** Asset ids referenced anywhere in [layers] (including fields [Layer.referencedAssets] does not list). */
    private fun rawAssetRefs(layers: List<Layer>): Set<String> {
        val out = HashSet<String>()
        fun walk(e: JsonElement) {
            when (e) {
                is JsonObject -> e.forEach { (k, v) -> if (k in ASSET_KEYS && v is JsonPrimitive && v.isString) out += v.content else walk(v) }
                is JsonArray -> e.forEach(::walk)
                else -> Unit
            }
        }
        walk(KnsprojJson.json.encodeToJsonElement(ListSerializer(Layer.serializer()), layers))
        return out
    }

    private fun remapAssets(layers: List<Layer>, ids: Map<String, String>): List<Layer> {
        if (ids.isEmpty()) return layers
        fun walk(e: JsonElement): JsonElement = when (e) {
            is JsonObject -> JsonObject(e.mapValues { (k, v) -> if (k in ASSET_KEYS && v is JsonPrimitive && v.isString && v.content in ids) JsonPrimitive(ids.getValue(v.content)) else walk(v) })
            is JsonArray -> JsonArray(e.map(::walk))
            else -> e
        }
        val ser = ListSerializer(Layer.serializer())
        return KnsprojJson.json.decodeFromJsonElement(ser, walk(KnsprojJson.json.encodeToJsonElement(ser, layers)))
    }
}
