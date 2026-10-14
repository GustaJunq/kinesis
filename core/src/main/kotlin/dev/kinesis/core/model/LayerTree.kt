package dev.kinesis.core.model

/** Location of a layer in the tree: the id of its group (null = top level) and its index there. */
data class LayerLocation(val containerId: String?, val index: Int, val depth: Int)

/** Helpers to navigate and rewrite the immutable layer tree (top-level layers + nested groups). */
object LayerTree {

    /** Depth-first walk (painter's order inside each container). */
    fun walk(layers: List<Layer>, depth: Int = 0, containerId: String? = null, visit: (Layer, LayerLocation) -> Unit) {
        layers.forEachIndexed { i, layer ->
            visit(layer, LayerLocation(containerId, i, depth))
            if (layer.type == LayerType.GROUP) walk(layer.layers, depth + 1, layer.id, visit)
        }
    }

    fun all(project: KnsProject): List<Layer> = buildList { walk(project.layers) { l, _ -> add(l) } }

    fun find(project: KnsProject, id: String): Layer? = find(project.layers, id)

    fun find(layers: List<Layer>, id: String): Layer? {
        for (l in layers) {
            if (l.id == id) return l
            if (l.type == LayerType.GROUP) find(l.layers, id)?.let { return it }
        }
        return null
    }

    fun locate(project: KnsProject, id: String): LayerLocation? {
        var result: LayerLocation? = null
        walk(project.layers) { l, loc -> if (l.id == id && result == null) result = loc }
        return result
    }

    /** Layers in the same container as [id] (including itself). */
    fun siblings(project: KnsProject, id: String): List<Layer> {
        val loc = locate(project, id) ?: return emptyList()
        return container(project, loc.containerId)
    }

    /** Children of group [containerId], or top-level layers when null. */
    fun container(project: KnsProject, containerId: String?): List<Layer> =
        if (containerId == null) project.layers else find(project, containerId)?.layers ?: emptyList()

    /** Absolute (composition) start time of the container holding [id]. */
    fun containerOffset(project: KnsProject, id: String): Double {
        var offset = 0.0
        var loc = locate(project, id)
        while (loc?.containerId != null) {
            val group = find(project, loc.containerId!!) ?: break
            offset += group.start
            loc = locate(project, group.id)
        }
        return offset
    }

    /** Ids of the groups containing [id], outermost first. */
    fun ancestors(project: KnsProject, id: String): List<String> {
        val result = ArrayList<String>()
        var loc = locate(project, id)
        while (loc?.containerId != null) {
            result.add(0, loc.containerId!!)
            loc = locate(project, loc.containerId!!)
        }
        return result
    }

    /** Replaces layer [id] with [transform] applied to it, anywhere in the tree. */
    fun update(project: KnsProject, id: String, transform: (Layer) -> Layer): KnsProject =
        project.copy(layers = update(project.layers, id, transform))

    fun update(layers: List<Layer>, id: String, transform: (Layer) -> Layer): List<Layer> {
        var changed = false
        val result = layers.map { l ->
            when {
                l.id == id -> { changed = true; transform(l) }
                l.type == LayerType.GROUP && l.layers.isNotEmpty() -> {
                    val children = update(l.layers, id, transform)
                    if (children !== l.layers) { changed = true; l.copy(layers = children) } else l
                }
                else -> l
            }
        }
        return if (changed) result else layers
    }

    /** Replaces the list of children of [containerId] (or the top-level list). */
    fun updateContainer(project: KnsProject, containerId: String?, transform: (List<Layer>) -> List<Layer>): KnsProject =
        if (containerId == null) project.copy(layers = transform(project.layers))
        else update(project, containerId) { it.copy(layers = transform(it.layers)) }

    /** Removes the layers in [ids] wherever they are. References to them (parent/matte) are cleared. */
    fun remove(project: KnsProject, ids: Set<String>): KnsProject {
        fun strip(layers: List<Layer>): List<Layer> = layers.filter { it.id !in ids }.map { l ->
            var x = l
            if (x.parent in ids) x = x.copy(parent = null)
            if (x.matte?.layer in ids) x = x.copy(matte = null)
            if (x.type == LayerType.GROUP) x = x.copy(layers = strip(x.layers))
            x
        }
        return project.copy(layers = strip(project.layers))
    }

    /** Inserts [layer] into [containerId] at [index] (clamped; -1 = on top). */
    fun insert(project: KnsProject, containerId: String?, index: Int, layer: Layer): KnsProject =
        updateContainer(project, containerId) { list ->
            val i = if (index < 0 || index > list.size) list.size else index
            list.toMutableList().apply { add(i, layer) }
        }

    fun allIds(project: KnsProject): Set<String> = all(project).mapTo(HashSet()) { it.id }

    /** Chain of parents of [layer] inside [siblings], nearest first. Stops on cycles. */
    fun parentChain(layer: Layer, siblings: List<Layer>): List<Layer> {
        val byId = siblings.associateBy { it.id }
        val chain = ArrayList<Layer>()
        val seen = HashSet<String>().apply { add(layer.id) }
        var cur = layer.parent?.let { byId[it] }
        while (cur != null && seen.add(cur.id)) {
            chain += cur
            cur = cur.parent?.let { byId[it] }
        }
        return chain
    }
}

// Lenses for editing a layer's content object (no-ops when the layer has none). They also spare callers in
// other modules from needing smart casts on these properties.
fun Layer.updateText(f: (TextContent) -> TextContent): Layer = text?.let { copy(text = f(it)) } ?: this
fun Layer.updateShape(f: (ShapeContent) -> ShapeContent): Layer = shape?.let { copy(shape = f(it)) } ?: this
fun Layer.updateObject3D(f: (Object3DContent) -> Object3DContent): Layer = object3d?.let { copy(object3d = f(it)) } ?: this
fun Layer.updateModel3D(f: (Model3DContent) -> Model3DContent): Layer = model3d?.let { copy(model3d = f(it)) } ?: this
fun Layer.updateCamera(f: (CameraContent) -> CameraContent): Layer = camera?.let { copy(camera = f(it)) } ?: this
fun Layer.updateLight(f: (LightContent) -> LightContent): Layer = light?.let { copy(light = f(it)) } ?: this

/** Updates the media of a video or audio layer. */
fun Layer.updateMedia(f: (MediaContent) -> MediaContent): Layer = when {
    video != null -> copy(video = f(video))
    audio != null -> copy(audio = f(audio))
    else -> this
}
