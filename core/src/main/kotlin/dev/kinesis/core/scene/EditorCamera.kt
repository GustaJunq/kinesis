package dev.kinesis.core.scene

import dev.kinesis.core.math.Mat4
import dev.kinesis.core.math.Vec3
import dev.kinesis.core.model.Composition
import dev.kinesis.core.model.EditorView
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.tan

/**
 * The free camera of the editor viewport and its gizmo. All of it is editor state: nothing here is part of what the
 * video renders. Orbit conventions follow the scene: +x right, +y down, +z away; yaw 0 / pitch 0 looks along +z.
 */
object EditorCamera {
    const val MIN_DISTANCE = 50f
    const val MAX_DISTANCE = 200_000f
    const val MAX_PITCH = 89f
    private const val ORBIT_DEGREES_PER_PIXEL = 0.3f

    /** A view that shows the whole composition frame from a little above and to the side. */
    fun default(comp: Composition): EditorView =
        EditorView(distance = SceneEvaluator.defaultCameraDistance(comp.height, 40f) * 1.15f)

    fun position(v: EditorView): Vec3 {
        val yaw = Math.toRadians(v.yaw.toDouble())
        val pitch = Math.toRadians(v.pitch.toDouble())
        val r = v.distance
        return v.target + Vec3((r * cos(pitch) * sin(yaw)).toFloat(), (-r * sin(pitch)).toFloat(), (-r * cos(pitch) * cos(yaw)).toFloat())
    }

    fun state(v: EditorView, comp: Composition): CameraState {
        val position = position(v)
        val up = Vec3(0f, -1f, 0f)
        val view = Mat4.lookAt(position, v.target, up)
        val projection = Mat4.perspective(v.fov.coerceIn(1f, 170f), comp.aspect, SceneEvaluator.NEAR_PLANE, SceneEvaluator.FAR_PLANE)
        return CameraState(position, v.target, v.fov, view, projection, comp.width, comp.height, null, up)
    }

    /** Turns the scene with the finger: dragging right/down by (dx, dy) pixels. */
    fun orbit(v: EditorView, dx: Float, dy: Float): EditorView = v.copy(
        yaw = wrap(v.yaw - dx * ORBIT_DEGREES_PER_PIXEL),
        pitch = (v.pitch + dy * ORBIT_DEGREES_PER_PIXEL).coerceIn(-MAX_PITCH, MAX_PITCH),
    )

    /** Slides the scene with the finger by (dx, dy) composition pixels on screen (the target moves the other way). */
    fun pan(v: EditorView, dx: Float, dy: Float, compHeight: Int): EditorView {
        val f = (v.target - position(v)).normalized()
        val up = Vec3(0f, -1f, 0f)
        var right = f.cross(up).normalized()
        if (right.length() < 1e-6f) right = Vec3(1f, 0f, 0f)
        val screenUp = right.cross(f)
        val perPixel = 2f * v.distance * tan(Math.toRadians(v.fov / 2.0)).toFloat() / max(1, compHeight)
        return v.copy(target = v.target - right * (dx * perPixel) + screenUp * (dy * perPixel))
    }

    /** Dolly: [factor] > 1 comes closer. */
    fun zoom(v: EditorView, factor: Float): EditorView =
        v.copy(distance = (v.distance / factor.coerceIn(0.05f, 20f)).coerceIn(MIN_DISTANCE, MAX_DISTANCE))

    /** Points the view at [point], keeping the distance. */
    fun focus(v: EditorView, point: Vec3): EditorView = v.copy(target = point)

    private fun wrap(deg: Float): Float {
        var d = deg % 360f
        if (d > 180f) d -= 360f
        if (d < -180f) d += 360f
        return d
    }

    /** Whether the view is usable (finite numbers, sensible ranges); the validator reports the first problem. */
    fun problem(v: EditorView): String? = when {
        listOf(v.target.x, v.target.y, v.target.z, v.yaw, v.pitch, v.distance, v.fov).any { !it.isFinite() } -> "editorView values must be finite numbers"
        v.distance < MIN_DISTANCE || v.distance > MAX_DISTANCE -> "editorView.distance must be between $MIN_DISTANCE and $MAX_DISTANCE"
        abs(v.pitch) > MAX_PITCH -> "editorView.pitch must be between -$MAX_PITCH and $MAX_PITCH"
        v.fov < 1f || v.fov > 170f -> "editorView.fov must be between 1 and 170"
        else -> null
    }

    /** The scene camera drawn as a pyramid: its [apex] and the four corners of the frame at [depth], clockwise from top-left. */
    class Frustum(val apex: Vec3, val corners: List<Vec3>, val upTip: Vec3, val forward: Vec3)

    fun frustum(camera: CameraState, aspect: Float, depth: Float): Frustum {
        val f = (camera.target - camera.position).normalized()
        var right = f.cross(camera.up).normalized()
        if (right.length() < 1e-6f) right = Vec3(1f, 0f, 0f)
        val up = right.cross(f)
        val halfH = depth * tan(Math.toRadians(camera.fov.coerceIn(1f, 170f) / 2.0)).toFloat()
        val halfW = halfH * aspect
        val c = camera.position + f * depth
        val corners = listOf(
            c - right * halfW + up * halfH,
            c + right * halfW + up * halfH,
            c + right * halfW - up * halfH,
            c - right * halfW - up * halfH,
        )
        return Frustum(camera.position, corners, c + up * (halfH * 1.35f), f)
    }

    /** The composition frame on the z = 0 plane, clockwise from top-left. */
    fun compositionFrame(comp: Composition): List<Vec3> {
        val w = comp.width / 2f
        val h = comp.height / 2f
        return listOf(Vec3(-w, -h, 0f), Vec3(w, -h, 0f), Vec3(w, h, 0f), Vec3(-w, h, 0f))
    }

    /** A sensible size for the gizmo of the camera: some of the way from the camera to the target. */
    fun gizmoDepth(camera: CameraState, comp: Composition): Float =
        min(max((camera.position - camera.target).length() * 0.25f, comp.height * 0.2f), comp.height * 0.8f)
}
