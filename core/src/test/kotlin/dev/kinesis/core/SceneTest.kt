package dev.kinesis.core

import dev.kinesis.core.TestProjects.keys
import dev.kinesis.core.anim.anim
import dev.kinesis.core.math.Affine2D
import dev.kinesis.core.math.Mat4
import dev.kinesis.core.math.Vec2
import dev.kinesis.core.math.Vec3
import dev.kinesis.core.model.CameraContent
import dev.kinesis.core.model.Composition
import dev.kinesis.core.model.KnsProject
import dev.kinesis.core.model.Layer
import dev.kinesis.core.model.LayerType
import dev.kinesis.core.model.Matte
import dev.kinesis.core.model.MatteMode
import dev.kinesis.core.model.Object3DContent
import dev.kinesis.core.model.ShapeContent
import dev.kinesis.core.model.TextAnimator
import dev.kinesis.core.model.TextAnimatorMode
import dev.kinesis.core.model.TextAnimatorType
import dev.kinesis.core.model.TextOrder
import dev.kinesis.core.model.TextUnit
import dev.kinesis.core.model.Transform2D
import dev.kinesis.core.model.Transform3D
import dev.kinesis.core.model.Transition
import dev.kinesis.core.model.TransitionType
import dev.kinesis.core.scene.AudioPlan
import dev.kinesis.core.scene.GroupNode
import dev.kinesis.core.scene.Node2D
import dev.kinesis.core.scene.Node3D
import dev.kinesis.core.scene.SceneEvaluator
import dev.kinesis.core.scene.TextAnimation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SceneTest {

    private fun shape(id: String, start: Double = 0.0, duration: Double = 10.0, transform: Transform2D = Transform2D(), parent: String? = null) =
        Layer(id, LayerType.SHAPE, start = start, duration = duration, transform = transform, parent = parent, shape = ShapeContent())

    private fun project(vararg layers: Layer) = KnsProject(composition = Composition(1000, 2000, 30.0, 10.0), layers = layers.toList())

    private fun assertPoint(expected: Vec2, actual: Vec2, eps: Float = 1e-3f) {
        assertEquals("x", expected.x, actual.x, eps)
        assertEquals("y", expected.y, actual.y, eps)
    }

    @Test
    fun layerMatrixAppliesAnchorScaleRotationPosition() {
        val l = shape("a", transform = Transform2D(position = anim(Vec2(100f, 50f)), anchor = anim(Vec2(10f, 0f)), scale = anim(Vec2(2f, 2f)), rotation = anim(90f)))
        val node = SceneEvaluator.evaluate(project(l), 0.0).nodes.single() as Node2D
        // The anchor lands on the position.
        assertPoint(Vec2(100f, 50f), node.matrix.apply(Vec2(10f, 0f)))
        // +x in local space points down (+y) after a 90° clockwise rotation, scaled by 2.
        assertPoint(Vec2(100f, 70f), node.matrix.apply(Vec2(20f, 0f)))
    }

    @Test
    fun inactiveAndHiddenLayersAreSkipped() {
        val p = project(shape("a", start = 2.0, duration = 1.0), shape("b").copy(visible = false))
        assertTrue(SceneEvaluator.evaluate(p, 1.0).nodes.isEmpty())
        assertEquals(1, SceneEvaluator.evaluate(p, 2.5).nodes.size)
        assertTrue(SceneEvaluator.evaluate(p, 3.0).nodes.isEmpty())
    }

    @Test
    fun parentingInheritsTransformButNotOpacity() {
        val parent = shape("p", transform = Transform2D(position = keys(0.0 to Vec2(0f, 0f), 1.0 to Vec2(100f, 0f)), rotation = anim(90f), opacity = anim(0.2f)))
        val child = shape("c", parent = "p", transform = Transform2D(position = anim(Vec2(50f, 0f))))
        val nodes = SceneEvaluator.evaluate(project(parent, child), 1.0).nodes
        val c = nodes.first { it.layerId == "c" } as Node2D
        assertPoint(Vec2(100f, 50f), c.matrix.apply(Vec2.ZERO))
        assertEquals(1f, c.opacity, 0f)
    }

    @Test
    fun groupTimingIsRelativeAndTransformIsConcatenated() {
        val group = Layer(
            "g", LayerType.GROUP, start = 2.0, duration = 5.0,
            transform = Transform2D(position = anim(Vec2(100f, 0f))),
            layers = listOf(shape("inner", start = 1.0, duration = 1.0, transform = Transform2D(position = keys(0.0 to Vec2.ZERO, 1.0 to Vec2(0f, 100f))))),
        )
        val p = project(group)
        assertTrue((SceneEvaluator.evaluate(p, 2.5).nodes.single() as GroupNode).children.isEmpty())
        val g = SceneEvaluator.evaluate(p, 3.5).nodes.single() as GroupNode
        val inner = g.children.single() as Node2D
        assertPoint(Vec2(100f, 50f), inner.matrix.apply(Vec2.ZERO))
        assertTrue(g.isPassThrough)
        assertTrue(SceneEvaluator.evaluate(p, 7.5).nodes.isEmpty())
    }

    @Test
    fun defaultCameraMapsTheZeroPlaneOneToOne() {
        val p = project()
        val cam = SceneEvaluator.evaluateCamera(p, 0.0)
        assertPoint(Vec2(0f, 0f), cam.project(Vec3(0f, 0f, 0f))!!, 0.01f)
        assertPoint(Vec2(300f, -400f), cam.project(Vec3(300f, -400f, 0f))!!, 0.05f)
        // Further away objects get smaller.
        val far = cam.project(Vec3(300f, 0f, 1000f))!!
        assertTrue(far.x in 1f..299f)
    }

    @Test
    fun cameraLayerAndParentedObjects() {
        val cam = Layer("cam", LayerType.CAMERA, duration = 10.0, camera = CameraContent(position = anim(Vec3(0f, 0f, -1000f)), fov = anim(90f)))
        val pivot = Layer("pivot", LayerType.OBJECT3D, duration = 10.0, object3d = Object3DContent(dev.kinesis.core.model.Primitive.EMPTY), transform3d = Transform3D(rotation = anim(Vec3(0f, 90f, 0f))))
        val cube = Layer("cube", LayerType.OBJECT3D, duration = 10.0, parent = "pivot", object3d = Object3DContent(size = anim(100f)), transform3d = Transform3D(position = anim(Vec3(200f, 0f, 0f))))
        val scene = SceneEvaluator.evaluate(project(cam, pivot, cube), 0.0)
        assertEquals(90f, scene.camera.fov, 0f)
        val node = scene.nodes.single() as Node3D // the empty is not drawn
        val center = node.model.transformPoint(Vec3.ZERO)
        // Rotating +x by 90° around Y moves it to -z.
        assertEquals(0f, center.x, 1e-3f)
        assertEquals(-200f, center.z, 1e-3f)
        // Size scales the unit primitive.
        val corner = node.model.transformPoint(Vec3(0.5f, 0f, 0f))
        assertEquals(50f, (corner - center).length(), 1e-3f)
    }

    @Test
    fun matteSourceIsHiddenAndAttached() {
        val matte = shape("m")
        val target = shape("t").copy(matte = Matte("m", MatteMode.ALPHA))
        val nodes = SceneEvaluator.evaluate(project(matte, target), 1.0).nodes
        assertEquals(listOf("t"), nodes.map { it.layerId })
        assertEquals("m", (nodes.single() as Node2D).matte!!.source.layerId)

        // Without the matte source on screen, an alpha matte hides the layer and an inverted one does not.
        val late = shape("m", start = 5.0, duration = 1.0)
        assertTrue(SceneEvaluator.evaluate(project(late, target), 1.0).nodes.isEmpty())
        val inv = target.copy(matte = Matte("m", MatteMode.ALPHA_INVERTED))
        val n = SceneEvaluator.evaluate(project(late, inv), 1.0).nodes.single() as Node2D
        assertNull(n.matte)
    }

    @Test
    fun transitionsAffectOpacityAndPosition() {
        val l = shape("a", duration = 4.0).copy(
            transitionIn = Transition(TransitionType.SLIDE_LEFT, 1.0, easing = dev.kinesis.core.anim.Easing.LINEAR, distance = 200f),
            transitionOut = Transition(TransitionType.FADE, 1.0, easing = dev.kinesis.core.anim.Easing.LINEAR),
        )
        val p = project(l)
        val start = SceneEvaluator.evaluate(p, 0.0).nodes.single() as Node2D
        assertEquals(0f, start.opacity, 1e-4f)
        assertPoint(Vec2(200f, 0f), start.matrix.apply(Vec2.ZERO))
        val half = SceneEvaluator.evaluate(p, 0.5).nodes.single() as Node2D
        assertPoint(Vec2(100f, 0f), half.matrix.apply(Vec2.ZERO))
        val mid = SceneEvaluator.evaluate(p, 2.0).nodes.single() as Node2D
        assertEquals(1f, mid.opacity, 1e-4f)
        val ending = SceneEvaluator.evaluate(p, 3.5).nodes.single() as Node2D
        assertEquals(0.5f, ending.opacity, 1e-4f)
    }

    @Test
    fun textAnimatorsStaggerUnits() {
        val a = TextAnimator(TextAnimatorType.FADE, TextAnimatorMode.IN, TextUnit.CHARACTER, start = 0.0, stagger = 1.0, unitDuration = 1.0, easing = dev.kinesis.core.anim.Easing.LINEAR)
        val s = TextAnimation.evaluate("ab c", listOf(a), 1.5)
        assertEquals(1f, s[0].opacity, 1e-4f) // a: fully in
        assertEquals(0.5f, s[1].opacity, 1e-4f) // b: halfway
        assertEquals(0f, s[3].opacity, 1e-4f) // c: third visible unit, not started

        val (words, count) = TextAnimation.unitIndices("hello big  world", TextUnit.WORD)
        assertEquals(3, count)
        assertEquals(2, words.last())

        val typewriter = a.copy(type = TextAnimatorType.TYPEWRITER, order = TextOrder.BACKWARD)
        val t = TextAnimation.evaluate("abc", listOf(typewriter), 0.5)
        assertEquals(listOf(0f, 0f, 1f), t.map { it.opacity })

        val slide = a.copy(type = TextAnimatorType.SLIDE_UP, amount = 40f)
        assertEquals(40f, TextAnimation.evaluate("x", listOf(slide), 0.0)[0].dy, 1e-4f)
    }

    @Test
    fun audioPlanUsesGroupOffsetsAndFades() {
        val p = TestProjects.full()
        val clips = AudioPlan.clips(p)
        assertEquals(setOf("masked", "music"), clips.map { it.layerId }.toSet())
        val music = clips.first { it.layerId == "music" }
        assertEquals(0f, music.gainAt(0.0), 1e-4f)
        assertEquals(0.5f, music.gainAt(0.5), 1e-4f)
        assertEquals(1f, music.gainAt(5.0), 1e-4f)
        assertEquals(0.5f, music.gainAt(9.0), 1e-4f)
        val clip = clips.first { it.layerId == "masked" }
        assertEquals(0.5 + 1.5 * 2.0, clip.sourceTime(2.0), 1e-9)
        assertEquals(0f, clip.gainAt(5.0), 0f)
    }

    @Test
    fun fullFixtureEvaluatesAtEveryFrame() {
        val p = TestProjects.full()
        for (frame in 0 until p.composition.frameCount) {
            val scene = SceneEvaluator.evaluate(p, p.composition.frameTime(frame))
            assertNotNull(scene.camera)
        }
    }

    @Test
    fun parentSpaceQueryMatchesEvaluation() {
        val parent = shape("p", transform = Transform2D(position = anim(Vec2(10f, 20f)), rotation = anim(30f)))
        val child = shape("c", parent = "p", transform = Transform2D(position = anim(Vec2(5f, 0f))))
        val p = project(parent, child)
        val space = SceneEvaluator.parentSpace2D(p, "c", 0.0)!!
        val node = SceneEvaluator.evaluate(p, 0.0).nodes.first { it.layerId == "c" } as Node2D
        assertPoint(node.matrix.apply(Vec2.ZERO), space.apply(Vec2(5f, 0f)))
        assertEquals(Affine2D.IDENTITY, SceneEvaluator.parentSpace2D(p, "p", 0.0))
        assertEquals(Mat4.identity(), SceneEvaluator.parentSpace3D(TestProjects.full(), "orbit", 0.0))
    }
}
