package dev.kinesis.core

import dev.kinesis.core.anim.Animatable
import dev.kinesis.core.anim.Keyframe
import dev.kinesis.core.anim.anim
import dev.kinesis.core.anim.at
import dev.kinesis.core.edit.Factory
import dev.kinesis.core.edit.LayerProps
import dev.kinesis.core.edit.TimelineOps
import dev.kinesis.core.format.KnsprojJson
import dev.kinesis.core.format.ProjectValidator
import dev.kinesis.core.math.Mat4
import dev.kinesis.core.math.Vec2
import dev.kinesis.core.math.Vec3
import dev.kinesis.core.model.CameraAim
import dev.kinesis.core.model.CameraContent
import dev.kinesis.core.model.Composition
import dev.kinesis.core.model.KnsProject
import dev.kinesis.core.model.Layer
import dev.kinesis.core.model.LayerTree
import dev.kinesis.core.model.LayerType
import dev.kinesis.core.model.NullContent
import dev.kinesis.core.model.Object3DContent
import dev.kinesis.core.model.ShapeContent
import dev.kinesis.core.model.Transform2D
import dev.kinesis.core.model.Transform3D
import dev.kinesis.core.scene.Node2D
import dev.kinesis.core.scene.Node3D
import dev.kinesis.core.scene.NullNode
import dev.kinesis.core.scene.SceneEvaluator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Null layers, the parent operation (no jumping) and cameras aimed by rotation. */
class NullAndCameraTest {

    private fun project(vararg layers: Layer) = KnsProject(composition = Composition(1000, 2000, 30.0, 10.0), layers = layers.toList())

    private fun shape(id: String, parent: String? = null, transform: Transform2D = Transform2D()) =
        Layer(id, LayerType.SHAPE, duration = 10.0, parent = parent, transform = transform, shape = ShapeContent())

    private fun nul2(id: String, transform: Transform2D = Transform2D(), parent: String? = null) =
        Layer(id, LayerType.NULL, duration = 10.0, parent = parent, transform = transform, nullObject = NullContent())

    private fun nul3(id: String, transform: Transform3D = Transform3D(), parent: String? = null) =
        Layer(id, LayerType.NULL, duration = 10.0, parent = parent, transform3d = transform, nullObject = NullContent(threeD = true))

    private fun cube(id: String, parent: String? = null, transform: Transform3D = Transform3D()) =
        Layer(id, LayerType.OBJECT3D, duration = 10.0, parent = parent, transform3d = transform, object3d = Object3DContent(size = anim(100f)))

    private fun assertVec(expected: Vec3, actual: Vec3, eps: Float = 0.05f) {
        assertEquals("x", expected.x, actual.x, eps); assertEquals("y", expected.y, actual.y, eps); assertEquals("z", expected.z, actual.z, eps)
    }

    private fun node2D(p: KnsProject, id: String, t: Double = 0.0) = SceneEvaluator.evaluate(p, t).nodes.first { it.layerId == id } as Node2D
    private fun node3D(p: KnsProject, id: String, t: Double = 0.0) = SceneEvaluator.evaluate(p, t).nodes.first { it.layerId == id } as Node3D

    // ------------------------------------------------------------------ null layers

    @Test
    fun a2DNullMovesItsChildrenAndDrawsNothing() {
        val nul = nul2("n", Transform2D(position = anim(Vec2(200f, 100f)), rotation = anim(90f)))
        val child = shape("c", "n", Transform2D(position = anim(Vec2(50f, 0f))))
        val scene = SceneEvaluator.evaluate(project(nul, child), 0.0)
        // Only an editor marker for the null plus the shape; the null has no content to render.
        assertEquals(setOf("n", "c"), scene.nodes.map { it.layerId }.toSet())
        assertTrue(scene.nodes.first { it.layerId == "n" } is NullNode)
        val p = (scene.nodes.first { it.layerId == "c" } as Node2D).matrix.apply(Vec2.ZERO)
        // The child's +50 x is rotated by the null's 90° to +50 y, then moved to the null's position.
        assertEquals(200f, p.x, 1e-3f); assertEquals(150f, p.y, 1e-3f)
        val marker = scene.nodes.first { it.layerId == "n" } as NullNode
        assertFalse(marker.threeD)
        assertEquals(200f, marker.matrix.apply(Vec2.ZERO).x, 1e-3f)
    }

    @Test
    fun a3DNullMovesCamerasLightsAndObjects() {
        val nul = nul3("n", Transform3D(position = anim(Vec3(100f, 0f, 0f)), rotation = anim(Vec3(0f, 90f, 0f))))
        val c = cube("c", "n", Transform3D(position = anim(Vec3(200f, 0f, 0f))))
        val scene = SceneEvaluator.evaluate(project(nul, c), 0.0)
        val marker = scene.nodes.first { it.layerId == "n" } as NullNode
        assertTrue(marker.threeD)
        // +x rotated by 90° around Y goes to -z, then the null's translation.
        assertVec(Vec3(100f, 0f, -200f), (scene.nodes.first { it.layerId == "c" } as Node3D).model.transformPoint(Vec3.ZERO))

        val cam = Layer("cam", LayerType.CAMERA, duration = 10.0, parent = "n", camera = CameraContent(position = anim(Vec3(0f, 0f, -500f))))
        val camScene = SceneEvaluator.evaluate(project(nul, cam), 0.0)
        // The camera is carried by the null: rotated -500 z → -x, plus the translation.
        assertVec(Vec3(-400f, 0f, 0f), camScene.camera.position)
    }

    @Test
    fun parentRulesRespectTheNullDimension() {
        fun codes(vararg layers: Layer) = ProjectValidator.validate(project(*layers)).errors.map { it.code }
        assertTrue(codes(shape("c", "n"), nul2("n")).isEmpty())
        assertTrue(codes(cube("c", "n"), nul3("n")).isEmpty())
        assertEquals(listOf("layer.parentKind"), codes(shape("c", "n"), nul3("n")))
        assertEquals(listOf("layer.parentKind"), codes(cube("c", "n"), nul2("n")))
        // Cameras and lights can ride a 3D null but never be a parent.
        assertTrue(codes(Layer("cam", LayerType.CAMERA, duration = 10.0, parent = "n", camera = CameraContent()), nul3("n")).isEmpty())
        assertEquals(listOf("layer.parentKind"), codes(cube("c", "cam"), Layer("cam", LayerType.CAMERA, duration = 10.0, camera = CameraContent())))
        // A null may have a null parent of the same dimension.
        assertTrue(codes(nul2("a", parent = "b"), nul2("b")).isEmpty())
    }

    @Test
    fun nullLayersRoundTripThroughJsonAndOldFilesStillOpen() {
        val p = project(nul2("n"), nul3("m"), shape("c", "n"))
        val text = KnsprojJson.encode(p)
        assertTrue(text.contains("\"type\": \"null\""))
        assertTrue(text.contains("\"null\": {"))
        val back = KnsprojJson.decode(text)
        assertEquals(text, KnsprojJson.encode(back))
        assertTrue(LayerTree.find(back, "m")!!.is3DLayer)
        // A version 3 document (no null layers, cameras aimed by target) opens unchanged.
        val v3 = text.replace("\"formatVersion\": 6", "\"formatVersion\": 3")
        assertEquals(6, KnsprojJson.decode(v3).formatVersion)
    }

    // ------------------------------------------------------------------ parent keeps the layer in place

    @Test
    fun parentingA3DObjectDoesNotMoveIt() {
        val nul = nul3("n", Transform3D(position = anim(Vec3(300f, -100f, 50f)), rotation = anim(Vec3(10f, 40f, 20f)), scale = anim(Vec3(2f, 2f, 2f))))
        val c = cube("c", transform = Transform3D(position = anim(Vec3(-50f, 80f, 10f)), rotation = anim(Vec3(5f, 15f, 25f))))
        val p = project(nul, c)
        val before = node3D(p, "c").model
        val parented = TimelineOps.setParent(p, "c", "n", 0.0)
        assertEquals("n", LayerTree.find(parented, "c")!!.parent)
        val after = node3D(parented, "c").model
        for (pt in listOf(Vec3.ZERO, Vec3(0.5f, 0f, 0f), Vec3(0f, 0.5f, 0f), Vec3(0f, 0f, 0.5f))) {
            assertVec(before.transformPoint(pt), after.transformPoint(pt))
        }
        // And clearing the parent keeps it in place as well.
        val freed = TimelineOps.setParent(parented, "c", null, 0.0)
        assertNull(LayerTree.find(freed, "c")!!.parent)
        for (pt in listOf(Vec3.ZERO, Vec3(0.5f, 0.2f, 0f))) assertVec(before.transformPoint(pt), node3D(freed, "c").model.transformPoint(pt))
    }

    @Test
    fun parentingAnAnimatedLayerKeepsItsAnimationAndDoesNotJump() {
        val nul = nul2("n", Transform2D(position = anim(Vec2(300f, 100f))))
        val moving = shape(
            "c",
            transform = Transform2D(
                position = Animatable(Vec2(0f, 0f), listOf(Keyframe(0.0, Vec2(0f, 0f)), Keyframe(2.0, Vec2(100f, 0f)))),
            ),
        )
        val p = project(nul, moving)
        val parented = TimelineOps.setParent(p, "c", "n", 1.0)
        val tr = LayerTree.find(parented, "c")!!.transform
        assertTrue("still animated", tr.position.isAnimated)
        for (t in listOf(1.0)) {
            val a = node2D(p, "c", t).matrix.apply(Vec2.ZERO); val b = node2D(parented, "c", t).matrix.apply(Vec2.ZERO)
            assertEquals(a.x, b.x, 0.05f); assertEquals(a.y, b.y, 0.05f)
        }
        // From then on it follows the parent: the parent's own motion carries the child.
        val nul2 = nul.copy(transform = Transform2D(position = Animatable(Vec2(300f, 100f), listOf(Keyframe(0.0, Vec2(300f, 100f)), Keyframe(2.0, Vec2(500f, 100f))))))
        val moved = parented.copy(layers = parented.layers.map { if (it.id == "n") nul2 else it })
        assertEquals(node2D(moved, "c", 0.0).matrix.apply(Vec2.ZERO).x, node2D(parented, "c", 0.0).matrix.apply(Vec2.ZERO).x, 0.05f)
        assertEquals(200f, node2D(moved, "c", 2.0).matrix.apply(Vec2.ZERO).x - node2D(parented, "c", 2.0).matrix.apply(Vec2.ZERO).x, 0.05f)
    }

    @Test
    fun parentingTo2DAndToTheWrongDimensionIsHandled() {
        val p = project(nul3("n3"), nul2("n2"), shape("c"), cube("o"))
        assertEquals(p, TimelineOps.setParent(p, "c", "n3", 0.0))
        assertEquals(p, TimelineOps.setParent(p, "o", "n2", 0.0))
        assertEquals(p, TimelineOps.setParent(p, "o", "missing", 0.0))
        assertEquals("n2", LayerTree.find(TimelineOps.setParent(p, "c", "n2", 0.0), "c")!!.parent)
    }

    @Test
    fun parentingACameraKeepsTheView() {
        val nul = nul3("n", Transform3D(position = anim(Vec3(200f, 0f, 0f)), rotation = anim(Vec3(0f, 30f, 0f))))
        for (aim in CameraAim.entries) {
            val cam = Layer(
                "cam", LayerType.CAMERA, duration = 10.0,
                camera = CameraContent(position = anim(Vec3(100f, -50f, -1500f)), target = anim(Vec3(40f, 10f, 0f)), rotation = anim(Vec3(5f, 10f, 0f)), aim = aim),
            )
            val p = project(nul, cam)
            val before = SceneEvaluator.evaluate(p, 0.0).camera
            val after = SceneEvaluator.evaluate(TimelineOps.setParent(p, "cam", "n", 0.0), 0.0).camera
            assertVec(before.position, after.position)
            for (pt in listOf(Vec3.ZERO, Vec3(300f, 100f, 200f))) {
                val a = before.project(pt)!!; val b = after.project(pt)!!
                assertEquals("$aim x", a.x, b.x, 0.2f); assertEquals("$aim y", a.y, b.y, 0.2f)
            }
        }
    }

    // ------------------------------------------------------------------ camera: position and rotation are independent

    private fun rotCamera(position: Vec3, rotation: Vec3) = project(
        Layer("cam", LayerType.CAMERA, duration = 10.0, camera = CameraContent(position = anim(position), rotation = anim(rotation), aim = CameraAim.ROTATION)),
    )

    @Test
    fun zeroRotationLooksAlongPlusZLikeTheDefaultCamera() {
        val d = SceneEvaluator.defaultCameraDistance(2000, CameraContent.DEFAULT_FOV)
        val a = SceneEvaluator.evaluate(rotCamera(Vec3(0f, 0f, -d), Vec3.ZERO), 0.0).camera
        val default = SceneEvaluator.evaluate(project(), 0.0).camera
        for (pt in listOf(Vec3.ZERO, Vec3(300f, -400f, 0f), Vec3(-100f, 200f, 500f))) {
            val x = a.project(pt)!!; val y = default.project(pt)!!
            assertEquals(y.x, x.x, 0.01f); assertEquals(y.y, x.y, 0.01f)
        }
    }

    @Test
    fun movingTheCameraDoesNotTurnItAndTurningItDoesNotMoveIt() {
        val base = SceneEvaluator.evaluate(rotCamera(Vec3(0f, 0f, -2000f), Vec3(0f, 20f, 0f)), 0.0).camera
        val moved = SceneEvaluator.evaluate(rotCamera(Vec3(500f, 300f, -1000f), Vec3(0f, 20f, 0f)), 0.0).camera
        val turned = SceneEvaluator.evaluate(rotCamera(Vec3(0f, 0f, -2000f), Vec3(0f, 60f, 0f)), 0.0).camera
        // The viewing direction (the row of the view matrix that holds -forward) only changes with rotation.
        fun forward(c: dev.kinesis.core.scene.CameraState) = Vec3(-c.view[2, 0], -c.view[2, 1], -c.view[2, 2])
        assertVec(forward(base), forward(moved), 1e-4f)
        assertTrue((forward(base) - forward(turned)).length() > 0.3f)
        assertVec(Vec3(0f, 0f, -2000f), turned.position, 1e-4f)
        assertVec(Vec3(500f, 300f, -1000f), moved.position, 1e-4f)
        // Yaw 90° looks along +x.
        val side = SceneEvaluator.evaluate(rotCamera(Vec3.ZERO, Vec3(0f, 90f, 0f)), 0.0).camera
        assertVec(Vec3(1f, 0f, 0f), forward(side), 1e-4f)
    }

    @Test
    fun switchingTheAimKeepsTheViewInBothDirections() {
        val target = Layer(
            "cam", LayerType.CAMERA, duration = 10.0,
            camera = CameraContent(position = anim(Vec3(300f, -200f, -1800f)), target = anim(Vec3(-120f, 60f, 100f))),
        )
        val p = project(target)
        val rotated = p.copy(layers = listOf(LayerProps.withCameraAim(target, CameraAim.ROTATION, 0.0, 2000)))
        assertEquals(CameraAim.ROTATION, rotated.layers[0].camera!!.aim)
        val back = rotated.copy(layers = listOf(LayerProps.withCameraAim(rotated.layers[0], CameraAim.TARGET, 0.0, 2000)))
        val a = SceneEvaluator.evaluate(p, 0.0).camera
        for (q in listOf(rotated, back)) {
            val b = SceneEvaluator.evaluate(q, 0.0).camera
            for (pt in listOf(Vec3.ZERO, Vec3(200f, 100f, 300f), Vec3(-300f, -100f, 0f))) {
                val x = a.project(pt)!!; val y = b.project(pt)!!
                assertEquals(x.x, y.x, 0.2f); assertEquals(x.y, y.y, 0.2f)
            }
        }
    }

    @Test
    fun cameraPropertiesFollowTheAim() {
        fun ids(l: Layer) = LayerProps.all(l).map { it.id }
        val factory = Factory.camera(project(), 0.0, "Camera")
        assertEquals(CameraAim.ROTATION, factory.camera!!.aim)
        assertEquals(listOf("camera.position", "camera.rotation", "camera.fov"), ids(factory))
        val target = factory.copy(camera = factory.camera!!.copy(aim = CameraAim.TARGET))
        assertEquals(listOf("camera.position", "camera.target", "camera.fov"), ids(target))
        assertEquals(listOf("transform.position", "transform.scale", "transform.rotation", "transform.anchor"), ids(Factory.nullObject(project(), 0.0, false, "Null")))
        assertEquals(listOf("transform3d.position", "transform3d.rotation", "transform3d.scale", "transform3d.anchor"), ids(Factory.nullObject(project(), 0.0, true, "Null")))
    }

    @Test
    fun eulerDecompositionInvertsTheRotationMatrix() {
        for (r in listOf(Vec3(10f, 20f, 30f), Vec3(-70f, 45f, 170f), Vec3(0f, 0f, 0f), Vec3(30f, 90f, 40f))) {
            val m = Mat4.rotationXYZ(r)
            val d = (Mat4.translation(1f, 2f, 3f) * m * Mat4.scale(2f, 3f, 4f)).decompose()
            assertVec(Vec3(1f, 2f, 3f), d.translation, 1e-4f)
            assertVec(Vec3(2f, 3f, 4f), d.scale, 1e-4f)
            val again = Mat4.rotationXYZ(d.rotation)
            for (i in 0 until 16) assertEquals("$r[$i]", m.m[i], again.m[i], 2e-3f)
        }
    }
}
