package dev.kinesis.core

import dev.kinesis.core.edit.Factory
import dev.kinesis.core.format.KnsprojJson
import dev.kinesis.core.format.ProjectValidator
import dev.kinesis.core.math.Color
import dev.kinesis.core.math.Vec2
import dev.kinesis.core.math.Vec3
import dev.kinesis.core.model.EditorView
import dev.kinesis.core.model.EditorViewMode
import dev.kinesis.core.scene.EditorCamera
import dev.kinesis.core.scene.SceneEvaluator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class EditorCameraTest {
    private val project = Factory.newProject("t", 1080, 1920, 30.0, 4.0, Color.parse("#000000"), null, "test")

    @Test
    fun theOriginProjectsToTheCentreWhateverTheOrbit() {
        for (yaw in listOf(-120f, 0f, 45f, 170f)) for (pitch in listOf(-60f, 0f, 30f, 80f)) {
            val cam = EditorCamera.state(EditorView(yaw = yaw, pitch = pitch, distance = 3000f), project.composition)
            val p = cam.project(Vec3.ZERO)!!
            assertEquals("yaw=$yaw pitch=$pitch", 0f, p.x, 0.5f)
            assertEquals(0f, p.y, 0.5f)
        }
    }

    @Test
    fun straightOnViewMatchesTheDefaultCamera() {
        val comp = project.composition
        val dist = SceneEvaluator.defaultCameraDistance(comp.height, 40f)
        val editor = EditorCamera.state(EditorView(yaw = 0f, pitch = 0f, distance = dist), comp)
        val scene = SceneEvaluator.evaluateCamera(project, 0.0)
        val a = editor.project(Vec3(300f, -200f, 0f))!!
        val b = scene.project(Vec3(300f, -200f, 0f))!!
        assertEquals(b.x, a.x, 0.5f)
        assertEquals(b.y, a.y, 0.5f)
    }

    @Test
    fun orbitFollowsTheFingerAndKeepsPitchInRange() {
        val v = EditorView(yaw = 0f, pitch = 0f)
        val right = EditorCamera.orbit(v, 100f, 0f)
        assertTrue(right.yaw < 0f)            // dragging right turns the scene to the right: the camera goes left
        assertEquals(0f, right.pitch, 1e-6f)
        val steep = EditorCamera.orbit(v, 0f, 100000f)
        assertEquals(EditorCamera.MAX_PITCH, steep.pitch, 1e-6f)
        assertEquals(-EditorCamera.MAX_PITCH, EditorCamera.orbit(v, 0f, -100000f).pitch, 1e-6f)
        assertTrue(abs(EditorCamera.orbit(EditorView(yaw = 179f), -100f, 0f).yaw) <= 180f)
    }

    @Test
    fun panMovesTheSceneWithTheFinger() {
        val comp = project.composition
        val v = EditorView(yaw = 0f, pitch = 0f, distance = 3000f)
        val before = EditorCamera.state(v, comp).project(Vec3.ZERO)!!
        val moved = EditorCamera.pan(v, 80f, 0f, comp.height)
        val after = EditorCamera.state(moved, comp).project(Vec3.ZERO)!!
        assertEquals(before.x + 80f, after.x, 3f)   // the origin follows the finger by about the same number of pixels
        val down = EditorCamera.state(EditorCamera.pan(v, 0f, 60f, comp.height), comp).project(Vec3.ZERO)!!
        assertEquals(before.y + 60f, down.y, 3f)
    }

    @Test
    fun zoomChangesTheDistanceWithinLimits() {
        val v = EditorView(distance = 1000f)
        assertEquals(500f, EditorCamera.zoom(v, 2f).distance, 1e-3f)
        assertEquals(EditorCamera.MIN_DISTANCE, EditorCamera.zoom(v, 1000f).distance, 1e-3f)
        assertEquals(EditorCamera.MAX_DISTANCE, EditorCamera.zoom(EditorView(distance = 190000f), 0.05f).distance, 1e-3f)
    }

    @Test
    fun frustumOfTheDefaultCamera() {
        val comp = project.composition
        val cam = SceneEvaluator.evaluateCamera(project, 0.0)
        val depth = 800f
        val f = EditorCamera.frustum(cam, comp.aspect, depth)
        assertEquals(cam.position.z, f.apex.z, 1e-3f)
        // The frame sits `depth` in front of the camera, centred, with the camera's aspect ratio.
        val tl = f.corners[0]; val br = f.corners[2]
        assertEquals(cam.position.z + depth, tl.z, 1e-2f)
        assertEquals(-(br.x - tl.x) / 2f, tl.x, 1e-2f)
        assertEquals(comp.aspect, (br.x - tl.x) / (br.y - tl.y), 1e-3f)
        assertTrue(tl.y < br.y)     // +y is down: the top-left corner is above the bottom-right one
        assertTrue(f.upTip.y < tl.y)
    }

    @Test
    fun withCameraKeepsTheFrameAndChangesOnlyTheCamera() {
        val scene = SceneEvaluator.evaluate(project, 1.0)
        val editor = EditorCamera.state(EditorCamera.default(project.composition), project.composition)
        val other = scene.withCamera(editor)
        assertEquals(scene.time, other.time, 0.0)
        assertTrue(other.camera === editor)
        assertEquals(scene.nodes, other.nodes)
    }

    @Test
    fun editorViewIsSavedWithTheProjectAndOldProjectsOpenWithout() {
        val view = EditorView(mode = EditorViewMode.EDITOR, target = Vec3(10f, 20f, 30f), yaw = 40f, pitch = -10f, distance = 1234f, fov = 50f)
        val saved = KnsprojJson.encode(project.copy(editorView = view))
        assertEquals(view, KnsprojJson.decode(saved).editorView)
        // A v8 document has no editorView and opens as v9 unchanged.
        val v8 = KnsprojJson.encode(project).replace("\"formatVersion\": 9", "\"formatVersion\": 8")
        val opened = KnsprojJson.decode(v8)
        assertNull(opened.editorView)
        assertEquals(9, opened.formatVersion)
        assertEquals(project.composition, opened.composition)
    }

    @Test
    fun validatorRejectsABrokenView() {
        assertTrue(ProjectValidator.validate(project.copy(editorView = EditorView())).isValid)
        for (bad in listOf(EditorView(distance = 1f), EditorView(pitch = 95f), EditorView(fov = 0f), EditorView(yaw = Float.NaN))) {
            val report = ProjectValidator.validate(project.copy(editorView = bad))
            assertFalse(bad.toString(), report.isValid)
            assertEquals("editorView", report.errors.first().code)
        }
        assertNotNull(EditorCamera.problem(EditorView(distance = 0f)))
        assertEquals(Vec2.ZERO, Vec2.ZERO)
    }
}
