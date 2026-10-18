package dev.kinesis.core

import dev.kinesis.core.anim.Animatable
import dev.kinesis.core.anim.Easing
import dev.kinesis.core.anim.Keyframe
import dev.kinesis.core.anim.at
import dev.kinesis.core.edit.Factory
import dev.kinesis.core.edit.RealCamera
import dev.kinesis.core.edit.RealCamera.Axes
import dev.kinesis.core.edit.RealCamera.Sample
import dev.kinesis.core.math.Color
import dev.kinesis.core.math.Vec3
import dev.kinesis.core.model.CameraAim
import dev.kinesis.core.scene.SceneEvaluator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

class RealCameraTest {
    private fun rotX(deg: Double) = Math.toRadians(deg).let { doubleArrayOf(1.0, 0.0, 0.0, 0.0, cos(it), -sin(it), 0.0, sin(it), cos(it)) }
    private fun rotY(deg: Double) = Math.toRadians(deg).let { doubleArrayOf(cos(it), 0.0, sin(it), 0.0, 1.0, 0.0, -sin(it), 0.0, cos(it)) }
    private fun rotZ(deg: Double) = Math.toRadians(deg).let { doubleArrayOf(cos(it), -sin(it), 0.0, sin(it), cos(it), 0.0, 0.0, 0.0, 1.0) }

    /** Some arbitrary starting orientation of the phone in the world. */
    private val r0 = RealCamera.multiply(rotZ(40.0), RealCamera.multiply(rotX(70.0), rotY(-15.0)))

    /** The phone turned by [m] about its own axes since the calibration. */
    private fun turned(m: DoubleArray) = RealCamera.multiply(r0, m)

    private fun rotation(m: DoubleArray, base: Vec3 = Vec3.ZERO, intensity: Float = 1f, axes: Axes = Axes()) =
        RealCamera.cameraRotation(base, r0, turned(m), intensity, axes)

    @Test
    fun notMovingKeepsTheCameraWhereItWas() {
        val base = Vec3(10f, -20f, 5f)
        val e = RealCamera.cameraRotation(base, r0, r0, 1f)
        assertEquals(base.x, e.x, 1e-3f); assertEquals(base.y, e.y, 1e-3f); assertEquals(base.z, e.z, 1e-3f)
    }

    @Test
    fun turningThePhoneTurnsTheCameraTheSameWay() {
        // Pan left: the camera looks left, which is a negative rotation about Y in the Kinesis axes (+x is right).
        val pan = rotation(rotY(30.0))
        assertEquals(-30f, pan.y, 0.01f); assertEquals(0f, pan.x, 0.01f); assertEquals(0f, pan.z, 0.01f)
        // Tilt the phone up: the camera looks up (negative y is up), a positive rotation about X.
        val tilt = rotation(rotX(20.0))
        assertEquals(20f, tilt.x, 0.01f); assertEquals(0f, tilt.y, 0.01f)
        // The forward direction really ends up where the phone points.
        val forward = dev.kinesis.core.math.Mat4.rotationXYZ(pan).transformVector(Vec3(0f, 0f, 1f))
        assertTrue(forward.x < -0.45f && abs(forward.y) < 1e-3f)
        val up = dev.kinesis.core.math.Mat4.rotationXYZ(tilt).transformVector(Vec3(0f, 0f, 1f))
        assertTrue(up.y < -0.3f)
        // Roll: the phone turns about the axis the lens looks along.
        assertEquals(-25f, rotation(rotZ(25.0)).z, 0.01f)
    }

    @Test
    fun theTurnIsRelativeToTheCalibrationAndAddedToTheCamera() {
        val base = Vec3(0f, 90f, 0f)         // the camera already looked to the right
        val e = RealCamera.cameraRotation(base, r0, turned(rotY(30.0)), 1f)
        assertEquals(60f, e.y, 0.01f)        // 90 - 30
    }

    @Test
    fun intensityScalesTheAngleAndAxesCanBeLocked() {
        assertEquals(-60f, rotation(rotY(30.0), intensity = 2f).y, 0.01f)
        assertEquals(-15f, rotation(rotY(30.0), intensity = 0.5f).y, 0.01f)
        assertEquals(0f, rotation(rotY(30.0), intensity = 0f).y, 0.01f)
        val both = RealCamera.multiply(rotY(30.0), rotX(20.0))
        val noYaw = rotation(both, axes = Axes(y = false))
        assertEquals(0f, noYaw.y, 0.01f)
        assertTrue(abs(noYaw.x) > 10f)
        val noPitch = rotation(both, axes = Axes(x = false))
        assertEquals(0f, noPitch.x, 0.01f)
        assertTrue(abs(noPitch.y) > 10f)
    }

    @Test
    fun quaternionMatrixMatchesAxisRotations() {
        val half = Math.toRadians(30.0) / 2
        val m = RealCamera.matrix(0.0, sin(half), 0.0, cos(half))     // 30° about y
        val expected = rotY(30.0)
        for (i in 0 until 9) assertEquals(expected[i], m[i], 1e-9)
    }

    @Test
    fun gyroscopeIntegrationTurnsAboutTheDeviceAxis() {
        var r = RealCamera.identity()
        repeat(100) { r = RealCamera.integrateGyro(r, 0.0, Math.toRadians(45.0), 0.0, 0.01) }       // 45°/s for 1 s about y
        val expected = rotY(45.0)
        for (i in 0 until 9) assertEquals(expected[i], r[i], 1e-6)
    }

    @Test
    fun positionFollowsAPushAndSettlesBack() {
        val tracker = RealCamera.PositionTracker()
        var last = Vec3.ZERO
        var peakZ = 0f
        // Pushing the phone forward (away from the user = -z of the device) moves the camera along +z.
        for (i in 0 until 30) { last = tracker.update(r0, r0, 0.0, 0.0, -2.0, 0.02, 1f); peakZ = maxOf(peakZ, last.z) }
        assertTrue(peakZ > 5f)
        repeat(600) { last = tracker.update(r0, r0, 0.0, 0.0, 0.0, 0.02, 1f) }
        assertTrue("drifts back to rest: $last", abs(last.z) < 0.5f)
        // Sideways to the right (+x of the device) is +x of the camera.
        tracker.reset()
        repeat(20) { last = tracker.update(r0, r0, 1.5, 0.0, 0.0, 0.02, 1f) }
        assertTrue(last.x > 1f)
        // Up on the screen is -y of the camera (y is down).
        tracker.reset()
        repeat(20) { last = tracker.update(r0, r0, 0.0, 1.5, 0.0, 0.02, 1f) }
        assertTrue(last.y < -1f)
    }

    private fun wave(n: Int, fps: Double, noise: Float = 0f) = (0 until n).map { i ->
        val t = i / fps
        val jitter = if (noise == 0f) 0f else noise * (if (i % 2 == 0) 1f else -1f)
        Sample(t, Vec3((20 * sin(t * 2)).toFloat() + jitter, (40 * sin(t)).toFloat() - jitter, 0f))
    }

    @Test
    fun unwrapMakesAnglesContinuous() {
        val raw = listOf(170f, 178f, -176f, -170f).mapIndexed { i, y -> Sample(i / 30.0, Vec3(0f, y, 0f)) }
        val ys = RealCamera.unwrap(raw).map { it.rotation.y }
        assertEquals(listOf(170f, 178f, 184f, 190f), ys)
    }

    @Test
    fun smoothingRemovesJitterWithoutMovingTheCurve() {
        val noisy = wave(300, 30.0, noise = 1.5f)
        val smooth = RealCamera.smooth(noisy, 0.5f, 30.0)
        val clean = wave(300, 30.0)
        fun err(a: List<Sample>) = a.indices.drop(20).dropLast(20).maxOf { abs(a[it].rotation.x - clean[it].rotation.x) }
        assertTrue("before ${err(noisy)} after ${err(smooth)}", err(smooth) < err(noisy) / 3)
        // Unchanged when there is nothing to smooth.
        assertTrue(RealCamera.smooth(noisy, 0f, 30.0) === noisy)
    }

    @Test
    fun simplifyKeepsTheShapeWithFarFewerKeyframes() {
        val samples = wave(300, 30.0)
        val simple = RealCamera.simplify(samples, 0.4f)
        assertTrue("${simple.size} keyframes", simple.size < 60)
        assertEquals(samples.first().time, simple.first().time, 0.0)
        assertEquals(samples.last().time, simple.last().time, 0.0)
        // Every original sample stays within the tolerance of the simplified curve (linear between keyframes).
        for (s in samples) {
            val b = simple.indexOfFirst { it.time >= s.time }.coerceAtLeast(1)
            val a = simple[b - 1]; val c = simple[b]
            val t = if (c.time > a.time) ((s.time - a.time) / (c.time - a.time)).toFloat() else 0f
            val x = a.rotation.x + (c.rotation.x - a.rotation.x) * t
            assertEquals(s.rotation.x, x, 0.45f)
        }
        // A straight line collapses to its two ends.
        val line = (0 until 100).map { Sample(it / 30.0, Vec3(it * 0.2f, 0f, 0f)) }
        assertEquals(2, RealCamera.simplify(line, 0.1f).size)
    }

    @Test
    fun keyframesFromATakeStartAtTheLayerTimeAndAreLinear() {
        val keys = RealCamera.keyframes(wave(90, 30.0), 30.0, RealCamera.Processing(smoothing = 0.2f), timeOffset = 1.0)
        assertEquals(-1.0, keys.rotation.first().time, 1e-9)
        assertTrue(keys.rotation.all { it.easing == Easing.LINEAR })
        assertTrue(keys.position == null)
    }

    @Test
    fun splicingReplacesOnlyTheRecordedStretch() {
        val old = Animatable(
            Vec3.ZERO,
            listOf(0.0, 1.0, 2.0, 3.0, 4.0).map { Keyframe(it, Vec3(it.toFloat() * 10, 0f, 0f)) },
        )
        val fresh = listOf(Keyframe(1.2, Vec3(5f, 5f, 5f)), Keyframe(2.5, Vec3(6f, 6f, 6f)))
        val out = RealCamera.splice(old, fresh)
        assertEquals(listOf(0.0, 1.0, 1.2, 2.5, 3.0, 4.0), out.keyframes.map { it.time })
        assertEquals(Vec3(5f, 5f, 5f), out.keyframes[2].value)
        assertEquals(Vec3(30f, 0f, 0f), out.at(3.0))
        // A first take on a static property just becomes keyframes.
        val first = RealCamera.splice(Animatable(Vec3(1f, 2f, 3f)), fresh)
        assertEquals(2, first.keyframes.size)
    }

    @Test
    fun applyingATakeSwitchesATargetCameraToItsOwnRotation() {
        val p = Factory.newProject("t", 1080, 1920, 30.0, 4.0, Color.parse("#000000"), null, "test")
        val cam = Factory.camera(p, 0.0, "cam")
        val aimed = dev.kinesis.core.edit.LayerProps.withCameraAim(cam, CameraAim.TARGET, 0.0, p.composition.height)
        val keys = RealCamera.keyframes(wave(60, 30.0), 30.0, RealCamera.Processing())
        val done = RealCamera.applyTo(aimed, keys, p.composition.height, 0.0)
        assertEquals(CameraAim.ROTATION, done.camera!!.aim)
        assertTrue(done.camera!!.rotation.isAnimated)
        assertNotNull(done.camera!!.position)
        // The recorded rotation really drives the rendered camera.
        val project = p.copy(layers = listOf(done.copy(duration = 4.0)))
        val at1 = SceneEvaluator.evaluateCamera(project, 1.0)
        val at0 = SceneEvaluator.evaluateCamera(project, 0.0)
        assertFalse(at1.view == at0.view)
    }

    @Test
    fun theLivePoseDrivesThePreviewWithoutTouchingTheProject() {
        val p = Factory.newProject("t", 1080, 1920, 30.0, 4.0, Color.parse("#000000"), null, "test")
        val cam = Factory.camera(p, 0.0, "cam").copy(duration = 4.0)
        val project = p.copy(layers = listOf(cam))
        val pose = RealCamera.LivePose(cam.id, Vec3(0f, 25f, 0f))
        val shown = RealCamera.withLivePose(project, pose)
        assertEquals(25f, shown.layers[0].camera!!.rotation.value.y, 0f)
        assertEquals(0f, project.layers[0].camera!!.rotation.value.y, 0f)
        assertFalse(SceneEvaluator.evaluateCamera(shown, 0.0).view == SceneEvaluator.evaluateCamera(project, 0.0).view)
    }
}
