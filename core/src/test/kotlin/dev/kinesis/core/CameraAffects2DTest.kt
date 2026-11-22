package dev.kinesis.core

import dev.kinesis.core.anim.anim
import dev.kinesis.core.math.Vec2
import dev.kinesis.core.math.Vec3
import dev.kinesis.core.model.CameraAim
import dev.kinesis.core.model.CameraContent
import dev.kinesis.core.model.Composition
import dev.kinesis.core.model.KnsProject
import dev.kinesis.core.model.Layer
import dev.kinesis.core.model.LayerType
import dev.kinesis.core.model.ShapeContent
import dev.kinesis.core.model.Transform2D
import dev.kinesis.core.scene.Node2D
import dev.kinesis.core.scene.SceneEvaluator
import org.junit.Assert.assertEquals
import org.junit.Test

class CameraAffects2DTest {
    private val comp = Composition(1000, 1000, 30.0, 5.0)
    private val d = SceneEvaluator.defaultCameraDistance(comp.height, CameraContent.DEFAULT_FOV)
    private val shape = Layer("s", LayerType.SHAPE, duration = 5.0, shape = ShapeContent(), transform = Transform2D(position = anim(Vec2(600f, 400f))))

    private fun camera(c: CameraContent) = Layer("c", LayerType.CAMERA, duration = 5.0, camera = c)

    /** Where the shape's origin lands on screen. */
    private fun origin(vararg extra: Layer): Vec2 {
        val node = SceneEvaluator.evaluate(KnsProject(composition = comp, layers = extra.toList() + shape), 1.0).nodes.filterIsInstance<Node2D>().single()
        return node.matrix.apply(Vec2.ZERO)
    }

    private fun assertNear(e: Vec2, a: Vec2) {
        assertEquals(e.x, a.x, 0.05f)
        assertEquals(e.y, a.y, 0.05f)
    }

    @Test
    fun offByDefault() {
        val moved = camera(CameraContent(position = anim(Vec3(100f, 0f, -d)), target = anim(Vec3(100f, 0f, 0f))))
        assertNear(Vec2(600f, 400f), origin(moved))
    }

    @Test
    fun defaultCameraIsIdentity() {
        assertNear(Vec2(600f, 400f), origin(camera(CameraContent(affects2D = true))))
    }

    @Test
    fun panMovesTheLayersTheOtherWay() {
        val c = CameraContent(position = anim(Vec3(100f, -50f, -d)), target = anim(Vec3(100f, -50f, 0f)), affects2D = true)
        assertNear(Vec2(500f, 450f), origin(camera(c)))
    }

    @Test
    fun dollyInZooms() {
        // Half the distance: everything twice as big around the centre (500, 500).
        val c = CameraContent(position = anim(Vec3(0f, 0f, -d / 2f)), affects2D = true)
        assertNear(Vec2(700f, 300f), origin(camera(c)))
    }

    @Test
    fun rollTurnsThePlane() {
        // Rolling the camera 90° turns the picture by -90° around the centre.
        val c = CameraContent(aim = CameraAim.ROTATION, rotation = anim(Vec3(0f, 0f, 90f)), affects2D = true)
        val p = origin(camera(c))
        assertEquals(100f * 100f + 100f * 100f, (p.x - 500f) * (p.x - 500f) + (p.y - 500f) * (p.y - 500f), 2f)
    }
}
