package dev.kinesis.core.edit

import dev.kinesis.core.anim.Animatable
import dev.kinesis.core.anim.at
import dev.kinesis.core.math.Affine2D
import dev.kinesis.core.math.Mat4
import dev.kinesis.core.math.Vec2
import dev.kinesis.core.math.Vec3
import dev.kinesis.core.model.CameraAim
import dev.kinesis.core.model.KnsProject
import dev.kinesis.core.model.Layer
import dev.kinesis.core.model.LayerTree
import dev.kinesis.core.model.LayerType
import dev.kinesis.core.model.Transform2D
import dev.kinesis.core.scene.SceneEvaluator
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt
import kotlin.random.Random

/** Generates short unique ids such as `text_k3f9a2`. */
object Ids {
    private const val ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789"

    fun newId(prefix: String, taken: Set<String>, random: Random = Random.Default): String {
        while (true) {
            val suffix = (1..6).map { ALPHABET[random.nextInt(ALPHABET.length)] }.joinToString("")
            val id = "${prefix}_$suffix"
            if (id !in taken) return id
        }
    }
}

/** Timeline editing operations. Times passed in are composition (absolute) seconds unless stated otherwise. */
object TimelineOps {

    /** Shortest allowed layer duration. */
    const val MIN_DURATION = 1.0 / 60.0

    fun addLayer(project: KnsProject, layer: Layer, containerId: String? = null, index: Int = -1): KnsProject =
        LayerTree.insert(project, containerId, index, layer)

    fun delete(project: KnsProject, ids: Set<String>): KnsProject = LayerTree.remove(project, ids)

    /** Moves the layer so it starts at container-local [newStart]. Keyframes move with it. */
    fun move(project: KnsProject, id: String, newStart: Double): KnsProject =
        LayerTree.update(project, id) { it.copy(start = newStart) }

    /**
     * Moves the layer's in-point by [delta] seconds (positive = later) without moving its content:
     * keyframes, text animators, media in-point and group children keep their absolute timing.
     * Non-destructive: trimming back restores what was hidden.
     */
    fun trimStart(project: KnsProject, id: String, delta: Double): KnsProject =
        LayerTree.update(project, id) { trimStartLayer(it, delta) }

    private fun trimStartLayer(layer: Layer, rawDelta: Double): Layer {
        var d = rawDelta.coerceAtMost(layer.duration - MIN_DURATION)
        layer.media?.let { m -> d = d.coerceAtLeast(-m.sourceStart / m.speed) }
        if (d == 0.0) return layer
        val shifted = shiftContent(layer, -d)
        return shifted.copy(
            start = layer.start + d,
            duration = layer.duration - d,
            video = shifted.video?.let { it.copy(sourceStart = (it.sourceStart + d * it.speed).coerceAtLeast(0.0)) },
            audio = shifted.audio?.let { it.copy(sourceStart = (it.sourceStart + d * it.speed).coerceAtLeast(0.0)) },
        )
    }

    /** Shifts everything inside a layer that is expressed in layer-local time by [dt] seconds. */
    private fun shiftContent(layer: Layer, dt: Double): Layer {
        val shifted = layer.mapAnimatables(object : AnimMapper {
            override fun <T> map(anim: Animatable<T>, interpolate: (T, T, Float) -> T) = anim.mapTimes { it + dt }
        })
        return shifted.copy(
            text = shifted.text?.let { t -> t.copy(animators = t.animators.map { it.copy(start = it.start + dt) }) },
            layers = shifted.layers.map { it.copy(start = it.start + dt) },
        )
    }

    /** Sets the duration (out-point) of a layer. */
    fun trimEnd(project: KnsProject, id: String, newDuration: Double): KnsProject =
        LayerTree.update(project, id) { it.copy(duration = newDuration.coerceAtLeast(MIN_DURATION)) }

    /**
     * Splits a layer at composition time [time]. The original keeps the first part; returns the project and the
     * id of the new second part, or null if [time] is not inside the layer.
     */
    fun split(project: KnsProject, id: String, time: Double, newId: String? = null): Pair<KnsProject, String>? {
        val layer = LayerTree.find(project, id) ?: return null
        val loc = LayerTree.locate(project, id) ?: return null
        val local = time - LayerTree.containerOffset(project, id) - layer.start
        if (local < MIN_DURATION || local > layer.duration - MIN_DURATION) return null
        val taken = LayerTree.allIds(project)
        val secondId = newId ?: Ids.newId(prefixFor(layer.type), taken)
        val first = layer.copy(duration = local, transitionOut = null)
        val second = reassignIds(trimStartLayer(layer, local), taken + secondId, rootId = secondId)
            .copy(transitionIn = null, name = layer.name)
        var p = LayerTree.update(project, id) { first }
        p = LayerTree.insert(p, loc.containerId, loc.index + 1, second)
        return p to secondId
    }

    /** Duplicates a layer (and, for groups, all of its children) right above the original. */
    fun duplicate(project: KnsProject, id: String): Pair<KnsProject, String>? {
        val layer = LayerTree.find(project, id) ?: return null
        val loc = LayerTree.locate(project, id) ?: return null
        val taken = LayerTree.allIds(project)
        val copyId = Ids.newId(prefixFor(layer.type), taken)
        val copy = reassignIds(layer, taken + copyId, rootId = copyId).copy(name = copyName(layer.name))
        return LayerTree.insert(project, loc.containerId, loc.index + 1, copy) to copyId
    }

    /** Gives [layer] the id [rootId] and fresh ids to all descendants, remapping references inside the copy. */
    fun reassignIds(layer: Layer, taken: Set<String>, rootId: String): Layer {
        val used = HashSet(taken)
        val mapping = HashMap<String, String>()
        fun assign(l: Layer, forced: String?) {
            val nid = forced ?: Ids.newId(prefixFor(l.type), used)
            used += nid
            mapping[l.id] = nid
            l.layers.forEach { assign(it, null) }
        }
        assign(layer, rootId)
        fun rewrite(l: Layer, isRoot: Boolean): Layer = l.copy(
            id = mapping.getValue(l.id),
            // The root keeps references to its own siblings; descendants point at copied siblings.
            parent = if (isRoot) l.parent else l.parent?.let { mapping[it] ?: it },
            matte = if (isRoot) l.matte else l.matte?.let { m -> m.copy(layer = mapping[m.layer] ?: m.layer) },
            layers = l.layers.map { rewrite(it, false) },
        )
        return rewrite(layer, true)
    }

    /** Moves a layer to [newIndex] within its container (painter's order; higher = on top). */
    fun reorder(project: KnsProject, id: String, newIndex: Int): KnsProject {
        val loc = LayerTree.locate(project, id) ?: return project
        return LayerTree.updateContainer(project, loc.containerId) { list ->
            val m = list.toMutableList()
            val l = m.removeAt(loc.index)
            m.add(newIndex.coerceIn(0, m.size), l)
            m
        }
    }

    /**
     * Groups sibling layers [ids] into a new group placed where the top-most of them was. References that would
     * cross the group boundary (parent/matte) are removed. Returns the project and the group id.
     */
    fun group(project: KnsProject, ids: Set<String>, name: String = "Group"): Pair<KnsProject, String>? {
        if (ids.isEmpty()) return null
        val locs = ids.map { LayerTree.locate(project, it) ?: return null }
        val containerId = locs.first().containerId
        if (locs.any { it.containerId != containerId }) return null
        val siblings = LayerTree.container(project, containerId)
        val members = siblings.filter { it.id in ids }
        val start = members.minOf { it.start }
        val end = members.maxOf { it.end }
        val groupId = Ids.newId("group", LayerTree.allIds(project))
        val children = members.map { l ->
            l.copy(
                start = l.start - start,
                parent = l.parent?.takeIf { it in ids },
                matte = l.matte?.takeIf { it.layer in ids },
            )
        }
        val group = Layer(id = groupId, type = LayerType.GROUP, name = name, start = start, duration = end - start, layers = children)
        val insertAt = siblings.indexOfLast { it.id in ids } - (members.size - 1)
        val p = LayerTree.updateContainer(project, containerId) { list ->
            val rest = list.filter { it.id !in ids }.map { l ->
                l.copy(parent = l.parent?.takeUnless { it in ids }, matte = l.matte?.takeUnless { it.layer in ids })
            }.toMutableList()
            rest.add(insertAt.coerceIn(0, rest.size), group)
            rest
        }
        return p to groupId
    }

    /**
     * Moves the children of a group into the group's container. Their timing is preserved. The group's own
     * transform, opacity and effects are dropped (see [isUngroupLossless]).
     */
    fun ungroup(project: KnsProject, groupId: String): KnsProject {
        val group = LayerTree.find(project, groupId) ?: return project
        if (group.type != LayerType.GROUP) return project
        val loc = LayerTree.locate(project, groupId) ?: return project
        val children = group.layers.map { it.copy(start = it.start + group.start) }
        return LayerTree.updateContainer(project, loc.containerId) { list ->
            val m = list.toMutableList()
            m.removeAt(loc.index)
            m.addAll(loc.index, children)
            m
        }
    }

    fun isUngroupLossless(group: Layer): Boolean =
        group.transform == Transform2D() && group.effects.isEmpty() && group.masks.isEmpty() && group.matte == null &&
            group.transitionIn == null && group.transitionOut == null

    /** Whether [parent] can be the parent of [child]: same dimension, and not a camera, light or audio layer. */
    fun canParent(child: Layer, parent: Layer): Boolean =
        child.id != parent.id && child.is3DLayer == parent.is3DLayer &&
            parent.type != LayerType.CAMERA && parent.type != LayerType.LIGHT && parent.type != LayerType.AUDIO

    /**
     * Sets (or clears) the parent of a layer **without moving it on screen**: the layer's own transform (or, for
     * cameras and lights, its position/orientation) is recomputed so that at composition time [time] the layer is
     * exactly where it was. Animated properties keep their animation, shifted by the difference at [time], so
     * the layer is only exact at [time] and follows the new parent from then on.
     */
    fun setParent(project: KnsProject, id: String, parentId: String?, time: Double): KnsProject {
        val layer = LayerTree.find(project, id) ?: return project
        if (parentId == id || parentId == layer.parent) return project
        val siblings = LayerTree.siblings(project, id).associateBy { it.id }
        if (parentId != null) {
            val parent = siblings[parentId] ?: return project
            if (!canParent(layer, parent)) return project
            // Refuse cycles.
            var cur: Layer? = parent
            while (cur != null) {
                if (cur.id == id) return project
                cur = cur.parent?.let { siblings[it] }
            }
        }
        val local = layer.localTime(time - LayerTree.containerOffset(project, id))
        val updated = LayerTree.update(project, id) { it.copy(parent = parentId) }
        return if (layer.is3DLayer) {
            val before = SceneEvaluator.parentSpace3D(project, id, time)
            val after = SceneEvaluator.parentSpace3D(updated, id, time)?.inverse()
            if (before == null || after == null) updated
            else LayerTree.update(updated, id) { keepWorld3D(it, after * before, local, project.composition.height) }
        } else {
            val before = SceneEvaluator.parentSpace2D(project, id, time)
            val after = SceneEvaluator.parentSpace2D(updated, id, time)?.inverse()
            if (before == null || after == null) updated
            else LayerTree.update(updated, id) { keepWorld2D(it, after * before, local) }
        }
    }

    /** [layer]'s transform after its parent space changed by [delta] (new parent space⁻¹ · old parent space). */
    private fun keepWorld2D(layer: Layer, delta: Affine2D, local: Double): Layer {
        if (!layer.hasTransform2D) return layer
        val tr = layer.transform
        val anchor = tr.anchor.at(local)
        val old = Affine2D.layer(tr.position.at(local), anchor, tr.scale.at(local), tr.rotation.at(local))
        val m = delta * old * Affine2D.translate(anchor)
        val sx = sqrt(m.a * m.a + m.b * m.b).coerceAtLeast(1e-6f)
        val det = m.a * m.d - m.b * m.c
        val scale = Vec2(sx, det / sx)
        val rotation = Math.toDegrees(atan2(m.b, m.a).toDouble()).toFloat()
        return layer.copy(
            transform = tr.copy(
                position = tr.position.shifted(tr.position.at(local), Vec2(m.tx, m.ty)),
                rotation = tr.rotation.shifted(tr.rotation.at(local), rotation),
                scale = tr.scale.scaled(tr.scale.at(local), scale),
            ),
        )
    }

    private fun keepWorld3D(layer: Layer, delta: Mat4, local: Double, compHeight: Int): Layer {
        return when {
            layer.hasTransform3D -> {
                val tr = layer.transform3d
                val anchor = tr.anchor.at(local)
                val old = Mat4.layer(tr.position.at(local), anchor, tr.scale.at(local), tr.rotation.at(local))
                val d = (delta * old * Mat4.translation(anchor)).decompose()
                layer.copy(
                    transform3d = tr.copy(
                        position = tr.position.shifted(tr.position.at(local), d.translation),
                        rotation = tr.rotation.shifted(tr.rotation.at(local), d.rotation),
                        scale = tr.scale.scaled(tr.scale.at(local), d.scale),
                    ),
                )
            }
            layer.type == LayerType.CAMERA -> {
                val cam = layer.camera ?: return layer
                val pos = cam.position?.at(local) ?: Vec3(0f, 0f, -SceneEvaluator.defaultCameraDistance(compHeight, cam.fov.at(local)))
                val position = (cam.position ?: Animatable(pos)).shifted(pos, delta.transformPoint(pos))
                if (cam.aim == CameraAim.ROTATION) {
                    val r = Mat4.rotationXYZ(cam.rotation.at(local))
                    val turned = Mat4(delta.m.copyOf().also { it[12] = 0f; it[13] = 0f; it[14] = 0f }) * r
                    val e = turned.decompose().rotation
                    layer.copy(camera = cam.copy(position = position, rotation = cam.rotation.shifted(cam.rotation.at(local), e)))
                } else {
                    val tg = cam.target.at(local)
                    layer.copy(camera = cam.copy(position = position, target = cam.target.shifted(tg, delta.transformPoint(tg))))
                }
            }
            layer.type == LayerType.LIGHT -> {
                val light = layer.light ?: return layer
                val pos = light.position.at(local)
                val tg = light.target.at(local)
                layer.copy(light = light.copy(position = light.position.shifted(pos, delta.transformPoint(pos)), target = light.target.shifted(tg, delta.transformPoint(tg))))
            }
            else -> layer
        }
    }

    /** Static → [new]; animated → every keyframe moved by `new - old` so the value at the sampled time becomes [new]. */
    private fun Animatable<Float>.shifted(old: Float, new: Float): Animatable<Float> =
        if (!isAnimated) withStatic(new) else shift { it + (new - old) }

    private fun Animatable<Vec2>.shifted(old: Vec2, new: Vec2): Animatable<Vec2> =
        if (!isAnimated) withStatic(new) else (new - old).let { d -> shift { it + d } }

    private fun Animatable<Vec3>.shifted(old: Vec3, new: Vec3): Animatable<Vec3> =
        if (!isAnimated) withStatic(new) else (new - old).let { d -> shift { it + d } }

    private fun Animatable<Vec2>.scaled(old: Vec2, new: Vec2): Animatable<Vec2> =
        if (!isAnimated) withStatic(new) else {
            val k = Vec2(ratio(new.x, old.x), ratio(new.y, old.y))
            shift { Vec2(it.x * k.x, it.y * k.y) }
        }

    private fun Animatable<Vec3>.scaled(old: Vec3, new: Vec3): Animatable<Vec3> =
        if (!isAnimated) withStatic(new) else {
            val k = Vec3(ratio(new.x, old.x), ratio(new.y, old.y), ratio(new.z, old.z))
            shift { Vec3(it.x * k.x, it.y * k.y, it.z * k.z) }
        }

    private fun ratio(new: Float, old: Float) = if (abs(old) < 1e-6f) 1f else new / old

    private fun <T> Animatable<T>.shift(f: (T) -> T): Animatable<T> =
        copy(value = f(value), keyframes = keyframes.map { it.copy(value = f(it.value)) })

    /** Top-level times that clips and the playhead snap to: composition bounds and layer edges. */
    fun snapPoints(project: KnsProject, excludeId: String? = null): List<Double> {
        val pts = sortedSetOf(0.0, project.composition.duration)
        for (l in project.layers) {
            if (l.id == excludeId) continue
            pts += l.start
            pts += l.end
        }
        return pts.toList()
    }

    fun prefixFor(type: LayerType): String = when (type) {
        LayerType.TEXT -> "text"
        LayerType.SHAPE -> "shape"
        LayerType.IMAGE -> "image"
        LayerType.VIDEO -> "video"
        LayerType.AUDIO -> "audio"
        LayerType.GROUP -> "group"
        LayerType.OBJECT3D -> "obj"
        LayerType.MODEL3D -> "model"
        LayerType.CAMERA -> "camera"
        LayerType.LIGHT -> "light"
        LayerType.NULL -> "null"
    }

    private fun copyName(name: String): String = if (name.isBlank()) "" else "$name copy"
}
