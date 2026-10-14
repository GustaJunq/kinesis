package dev.kinesis.core

import dev.kinesis.core.TestProjects.keys
import dev.kinesis.core.anim.Easing
import dev.kinesis.core.anim.anim
import dev.kinesis.core.edit.History
import dev.kinesis.core.edit.LayerProps
import dev.kinesis.core.edit.TimelineOps
import dev.kinesis.core.edit.keyframeTimes
import dev.kinesis.core.math.Vec2
import dev.kinesis.core.model.Composition
import dev.kinesis.core.model.KnsProject
import dev.kinesis.core.model.Layer
import dev.kinesis.core.model.LayerTree
import dev.kinesis.core.model.LayerType
import dev.kinesis.core.model.MediaContent
import dev.kinesis.core.model.ShapeContent
import dev.kinesis.core.model.Transform2D
import dev.kinesis.core.model.Transition
import dev.kinesis.core.model.TransitionType
import dev.kinesis.core.format.ProjectValidator
import dev.kinesis.core.scene.Content2D
import dev.kinesis.core.scene.GroupNode
import dev.kinesis.core.scene.Node2D
import dev.kinesis.core.scene.RenderNode
import dev.kinesis.core.scene.SceneEvaluator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EditingTest {

    private val animated = Layer(
        "a", LayerType.SHAPE, start = 1.0, duration = 4.0,
        transform = Transform2D(
            position = keys(0.0 to Vec2(-200f, 0f), 2.0 to Vec2(200f, 100f), 4.0 to Vec2(0f, 0f), easing = Easing.EASE_IN_OUT),
            opacity = keys(0.0 to 0f, 1.0 to 1f),
        ),
        shape = ShapeContent(),
    )

    private fun project(vararg layers: Layer) = KnsProject(composition = Composition(1080, 1920, 30.0, 10.0), layers = layers.toList())

    /** Flattened (layer id → screen position of its local origin, opacity) at time t. */
    private fun snapshot(p: KnsProject, t: Double): Map<String, Pair<Vec2, Float>> {
        val out = HashMap<String, Pair<Vec2, Float>>()
        fun visit(nodes: List<RenderNode>): Unit = nodes.forEach { n ->
            when (n) {
                is Node2D -> out[n.layerId] = n.matrix.apply(Vec2.ZERO) to n.opacity
                is GroupNode -> visit(n.children)
                else -> Unit
            }
        }
        visit(SceneEvaluator.evaluate(p, t).nodes)
        return out
    }

    private fun assertSameVisual(before: KnsProject, after: KnsProject, idMap: (String) -> String = { it }) {
        var t = 0.0
        while (t < before.composition.duration) {
            val a = snapshot(before, t)
            val b = snapshot(after, t)
            assertEquals("visible layer count at $t", a.size, b.size)
            for ((id, v) in a) {
                val other = b[id] ?: b.entries.firstOrNull { idMap(it.key) == id }?.value
                assertNotNull("layer $id at $t", other)
                assertEquals("x of $id at $t", v.first.x, other!!.first.x, 1e-2f)
                assertEquals("y of $id at $t", v.first.y, other.first.y, 1e-2f)
                assertEquals("opacity of $id at $t", v.second, other.second, 1e-4f)
            }
            t += 1.0 / 30.0
        }
    }

    @Test
    fun splitKeepsTheAnimationIdentical() {
        val p = project(animated)
        val (split, secondId) = TimelineOps.split(p, "a", 2.7)!!
        assertEquals(2, split.layers.size)
        assertEquals(1.7, split.layers[0].duration, 1e-9)
        assertEquals(2.7, split.layers[1].start, 1e-9)
        assertEquals(secondId, split.layers[1].id)
        assertSameVisual(p, split) { if (it == secondId) "a" else it }
        assertTrue(ProjectValidator.validate(split).isValid)
    }

    @Test
    fun splitOutsideTheLayerDoesNothing() {
        assertNull(TimelineOps.split(project(animated), "a", 0.5))
        assertNull(TimelineOps.split(project(animated), "a", 5.0))
    }

    @Test
    fun splitMovesTransitionsToTheOuterEdges() {
        val l = animated.copy(transitionIn = Transition(TransitionType.FADE), transitionOut = Transition(TransitionType.ZOOM_IN))
        val (p, _) = TimelineOps.split(project(l), "a", 3.0)!!
        assertNotNull(p.layers[0].transitionIn); assertNull(p.layers[0].transitionOut)
        assertNull(p.layers[1].transitionIn); assertNotNull(p.layers[1].transitionOut)
    }

    @Test
    fun splitVideoAdvancesSourceInPoint() {
        val v = Layer("v", LayerType.VIDEO, start = 2.0, duration = 6.0, video = MediaContent("vid", sourceStart = 1.0, speed = 2.0))
        val (p, id) = TimelineOps.split(project(v), "v", 5.0)!!
        val second = LayerTree.find(p, id)!!
        assertEquals(1.0 + 3.0 * 2.0, second.video!!.sourceStart, 1e-9)
        val before = SceneEvaluator.evaluate(project(v), 6.0).nodes.single() as Node2D
        val after = SceneEvaluator.evaluate(p, 6.0).nodes.single() as Node2D
        assertEquals((before.content as Content2D.Video).sourceTime, (after.content as Content2D.Video).sourceTime, 1e-9)
    }

    @Test
    fun trimStartIsNonDestructive() {
        val p = project(animated)
        val trimmed = TimelineOps.trimStart(p, "a", 1.0)
        assertEquals(2.0, trimmed.layers[0].start, 1e-9)
        assertEquals(3.0, trimmed.layers[0].duration, 1e-9)
        // Same picture from the new in-point on.
        for (t in listOf(2.0, 2.5, 3.3, 4.9)) assertEquals(snapshot(p, t), snapshot(trimmed, t))
        // Trimming back restores the original exactly.
        assertEquals(p, TimelineOps.trimStart(trimmed, "a", -1.0))
    }

    @Test
    fun duplicateCreatesFreshIdsAndRemapsChildren() {
        val group = Layer(
            "g", LayerType.GROUP, duration = 5.0,
            layers = listOf(
                Layer("c1", LayerType.SHAPE, shape = ShapeContent()),
                Layer("c2", LayerType.SHAPE, parent = "c1", shape = ShapeContent()),
            ),
        )
        val (p, copyId) = TimelineOps.duplicate(project(group), "g")!!
        val copy = LayerTree.find(p, copyId)!!
        assertEquals(1, p.layers.indexOfFirst { it.id == copyId })
        val ids = LayerTree.allIds(p)
        assertEquals(6, ids.size)
        assertNotEquals("c1", copy.layers[0].id)
        assertEquals(copy.layers[0].id, copy.layers[1].parent)
        assertTrue(ProjectValidator.validate(p).isValid)
    }

    @Test
    fun groupAndUngroupKeepTiming() {
        val a = Layer("a", LayerType.SHAPE, start = 1.0, duration = 2.0, shape = ShapeContent())
        val b = Layer("b", LayerType.SHAPE, start = 2.5, duration = 3.0, shape = ShapeContent(), parent = "x")
        val x = Layer("x", LayerType.SHAPE, duration = 10.0, shape = ShapeContent())
        val p = project(x, a, b)
        val (grouped, gid) = TimelineOps.group(p, setOf("a", "b"))!!
        val g = LayerTree.find(grouped, gid)!!
        assertEquals(1.0, g.start, 1e-9)
        assertEquals(4.5, g.duration, 1e-9)
        assertEquals(listOf("x", gid), grouped.layers.map { it.id })
        assertNull("reference across the group boundary is dropped", g.layers[1].parent)
        assertTrue(ProjectValidator.validate(grouped).isValid)
        assertTrue(TimelineOps.isUngroupLossless(g))

        val un = TimelineOps.ungroup(grouped, gid)
        assertEquals(listOf("x", "a", "b"), un.layers.map { it.id })
        assertEquals(2.5, LayerTree.find(un, "b")!!.start, 1e-9)
    }

    @Test
    fun groupRefusesLayersFromDifferentContainers() {
        val g = Layer("g", LayerType.GROUP, layers = listOf(Layer("in", LayerType.SHAPE, shape = ShapeContent())))
        val out = Layer("out", LayerType.SHAPE, shape = ShapeContent())
        assertNull(TimelineOps.group(project(g, out), setOf("in", "out")))
    }

    @Test
    fun reorderAndDelete() {
        val p = project(Layer("1", LayerType.SHAPE, shape = ShapeContent()), Layer("2", LayerType.SHAPE, shape = ShapeContent()), Layer("3", LayerType.SHAPE, shape = ShapeContent(), parent = "1"))
        assertEquals(listOf("2", "3", "1"), TimelineOps.reorder(p, "1", 2).layers.map { it.id })
        val deleted = TimelineOps.delete(p, setOf("1"))
        assertEquals(listOf("2", "3"), deleted.layers.map { it.id })
        assertNull("parent reference removed", deleted.layers[1].parent)
    }

    @Test
    fun setParentKeepsTheLayerInPlace() {
        val parent = Layer("p", LayerType.SHAPE, duration = 10.0, shape = ShapeContent(), transform = Transform2D(position = anim(Vec2(300f, 100f)), rotation = anim(45f), scale = anim(Vec2(2f, 2f))))
        val child = Layer("c", LayerType.SHAPE, duration = 10.0, shape = ShapeContent(), transform = Transform2D(position = anim(Vec2(-50f, 80f)), rotation = anim(10f)))
        val p = project(parent, child)
        val before = SceneEvaluator.evaluate(p, 0.0).nodes.first { it.layerId == "c" } as Node2D
        val parented = TimelineOps.setParent(p, "c", "p", 0.0)
        val after = SceneEvaluator.evaluate(parented, 0.0).nodes.first { it.layerId == "c" } as Node2D
        for (pt in listOf(Vec2.ZERO, Vec2(100f, 0f), Vec2(0f, 100f))) {
            val a = before.matrix.apply(pt); val b = after.matrix.apply(pt)
            assertEquals(a.x, b.x, 0.05f); assertEquals(a.y, b.y, 0.05f)
        }
        assertEquals("p", LayerTree.find(parented, "c")!!.parent)
        // Cycles are refused.
        assertEquals(parented, TimelineOps.setParent(parented, "p", "c", 0.0))
    }

    @Test
    fun propertyLensesEditKeyframes() {
        val prop = LayerProps.typed<Float>(LayerProps.find(animated, "transform.rotation")!!)
        var l = prop.toggleKeyframe(animated, 0.0)
        l = prop.setValue(l, 2.0, 90f)
        assertEquals(45f, prop.valueAt(l, 1.0), 1e-3f)
        assertTrue(prop.hasKeyframeAt(l, 2.0))
        assertEquals(listOf(0.0, 1.0, 2.0, 4.0), l.keyframeTimes())
        // Every layer type exposes its sections without crashing.
        for (layer in dev.kinesis.core.model.LayerTree.all(TestProjects.full())) {
            for (p in LayerProps.all(layer)) assertNotNull(p.get(layer))
        }
    }

    @Test
    fun pathEditing() {
        var path = dev.kinesis.core.model.PathSpec(closed = false, points = listOf(dev.kinesis.core.model.PathPoint(Vec2(0f, 0f)), dev.kinesis.core.model.PathPoint(Vec2(100f, 0f))))
        path = dev.kinesis.core.edit.PathOps.insertAfter(path, 0)
        assertEquals(Vec2(50f, 0f), path.points[1].point)
        path = dev.kinesis.core.edit.PathOps.insertAfter(path, 2)
        assertEquals(Vec2(150f, 0f), path.points[3].point)
        path = dev.kinesis.core.edit.PathOps.movePoint(path, 1, Vec2(50f, -60f))
        path = dev.kinesis.core.edit.PathOps.smooth(path, 1)
        assertEquals(100f / 6f, path.points[1].outTangent.x, 1e-4f)
        assertEquals(0f, path.points[1].outTangent.y, 1e-4f)
        assertEquals(-path.points[1].outTangent.x, path.points[1].inTangent.x, 1e-5f)
        assertTrue(dev.kinesis.core.edit.PathOps.isSmooth(path.points[1]))
        path = dev.kinesis.core.edit.PathOps.corner(path, 1)
        assertFalse(dev.kinesis.core.edit.PathOps.isSmooth(path.points[1]))
        path = dev.kinesis.core.edit.PathOps.removePoint(path, 0)
        assertEquals(3, path.points.size)
        // Paths never drop below two points.
        repeat(5) { path = dev.kinesis.core.edit.PathOps.removePoint(path, 0) }
        assertEquals(2, path.points.size)
    }

    @Test
    fun historyUndoRedoAndCoalescing() {
        val h = History(0)
        h.push(1)
        h.push(2, coalesceKey = "drag")
        h.push(3, coalesceKey = "drag")
        h.push(4, coalesceKey = "drag")
        assertEquals(4, h.present)
        assertEquals(1, h.undo())
        assertEquals(0, h.undo())
        assertFalse(h.canUndo)
        assertEquals(1, h.redo())
        h.push(9)
        assertFalse("a new edit clears redo", h.canRedo)
        h.commit()
        h.push(9) // equal state is ignored
        assertEquals(2, h.undoDepth)
    }
}
