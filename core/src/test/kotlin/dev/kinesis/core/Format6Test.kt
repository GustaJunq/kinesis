package dev.kinesis.core

import dev.kinesis.core.anim.anim
import dev.kinesis.core.format.KnsprojJson
import dev.kinesis.core.format.ProjectValidator
import dev.kinesis.core.math.Vec3
import dev.kinesis.core.model.CameraContent
import dev.kinesis.core.model.Composition
import dev.kinesis.core.model.DepthOfField
import dev.kinesis.core.model.Environment
import dev.kinesis.core.model.KnsProject
import dev.kinesis.core.model.Layer
import dev.kinesis.core.model.LayerType
import dev.kinesis.core.model.LightContent
import dev.kinesis.core.model.LightKind
import dev.kinesis.core.model.Material
import dev.kinesis.core.model.Object3DContent
import dev.kinesis.core.model.Primitive
import dev.kinesis.core.model.Shading
import dev.kinesis.core.model.Text3D
import dev.kinesis.core.scene.Node3D
import dev.kinesis.core.scene.SceneEvaluator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Format v6 fields reach the render model. */
class Format6Test {
    private fun project(vararg layers: Layer, env: Environment = Environment()) =
        KnsProject(composition = Composition(1000, 2000, 30.0, 10.0, environment = env), layers = layers.toList())

    @Test
    fun textShadingShadowsDofAndEnvironmentAreEvaluated() {
        val text = Layer(
            "t", LayerType.OBJECT3D, duration = 10.0,
            object3d = Object3DContent(Primitive.TEXT, size = anim(999f), material = Material(shading = Shading.FLAT), text = Text3D("Hi", depth = 30f)),
        )
        val cam = Layer("c", LayerType.CAMERA, duration = 10.0, camera = CameraContent(dof = DepthOfField(focusDistance = anim(1800f), range = anim(100f), blur = anim(12f))))
        val light = Layer("l", LayerType.LIGHT, duration = 10.0, light = LightContent(LightKind.DIRECTIONAL, castShadows = true, shadowDarkness = anim(0.4f)))
        val scene = SceneEvaluator.evaluate(project(text, cam, light, env = Environment(intensity = 2f)), 1.0)
        val node = scene.nodes.single() as Node3D
        assertEquals("Hi", node.text3d!!.content)
        assertEquals(Shading.FLAT, node.material!!.shading)
        // Text is built in pixels: `size` does not scale it.
        assertEquals(Vec3(1f, 0f, 0f), node.model.transformVector(Vec3(1f, 0f, 0f)))
        assertEquals(1800f, scene.camera.dof!!.focusDistance, 0f)
        assertEquals(12f, scene.camera.dof!!.blur, 0f)
        assertTrue(scene.lights.single().castShadows)
        assertEquals(0.4f, scene.lights.single().shadowDarkness, 0f)
        assertEquals(2f, scene.environment.intensity, 0f)
    }

    @Test
    fun disabledOrZeroBlurDofIsOff() {
        val off = Layer("c", LayerType.CAMERA, duration = 10.0, camera = CameraContent(dof = DepthOfField(enabled = false)))
        assertNull(SceneEvaluator.evaluate(project(off), 0.0).camera.dof)
        val zero = Layer("c", LayerType.CAMERA, duration = 10.0, camera = CameraContent(dof = DepthOfField(blur = anim(0f))))
        assertNull(SceneEvaluator.evaluate(project(zero), 0.0).camera.dof)
    }

    @Test
    fun roundTripsAndValidates() {
        val p = TestProjects.full()
        val text = KnsprojJson.encode(p)
        for (needle in listOf("\"shading\": \"toon\"", "\"castShadows\": true", "\"dof\"", "\"environment\"", "\"primitive\": \"text\"", "\"alphaPacked\": true")) {
            assertTrue(needle, text.contains(needle))
        }
        assertEquals(text, KnsprojJson.encode(KnsprojJson.decode(text)))
        val bad = project(Layer("t", LayerType.OBJECT3D, duration = 1.0, object3d = Object3DContent(Primitive.TEXT, text = Text3D(depth = 10f, bevel = 6f))))
        assertTrue(ProjectValidator.validate(bad).errors.any { it.code == "text3d.range" })
        val missing = project(env = Environment(asset = "nope"))
        assertTrue(ProjectValidator.validate(missing).errors.any { it.path == "composition.environment.asset" })
    }
}
