package dev.kinesis.core.edit

import dev.kinesis.core.anim.Animatable
import dev.kinesis.core.anim.Easing
import dev.kinesis.core.anim.Interpolators
import dev.kinesis.core.anim.Keyframe
import dev.kinesis.core.anim.at
import dev.kinesis.core.math.Mat4
import dev.kinesis.core.math.Vec3
import dev.kinesis.core.model.CameraAim
import dev.kinesis.core.model.Layer
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Real Camera: the 3D camera follows the phone. This file holds everything that does not need Android: turning the
 * phone's orientation into the camera's rotation, intensity and axis locks, a leaky integrator for the optional
 * position, and turning the recorded samples into a clean set of keyframes.
 *
 * Matrices are 3×3, row-major, mapping device axes to world axes (what `SensorManager.getRotationMatrixFromVector`
 * returns). Device axes: x right, y up the screen, z out of the screen; the rear camera looks along −z. The Kinesis
 * camera looks along +z with x right and y down, so the two frames differ by C = diag(1, −1, −1).
 */
object RealCamera {
    /** The orientation of the phone as a rotation matrix from a unit quaternion (x, y, z, w). */
    fun matrix(x: Double, y: Double, z: Double, w: Double): DoubleArray {
        val n = sqrt(x * x + y * y + z * z + w * w).takeIf { it > 1e-12 } ?: return identity()
        val qx = x / n; val qy = y / n; val qz = z / n; val qw = w / n
        return doubleArrayOf(
            1 - 2 * (qy * qy + qz * qz), 2 * (qx * qy - qz * qw), 2 * (qx * qz + qy * qw),
            2 * (qx * qy + qz * qw), 1 - 2 * (qx * qx + qz * qz), 2 * (qy * qz - qx * qw),
            2 * (qx * qz - qy * qw), 2 * (qy * qz + qx * qw), 1 - 2 * (qx * qx + qy * qy),
        )
    }

    fun identity() = doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)

    fun multiply(a: DoubleArray, b: DoubleArray): DoubleArray {
        val r = DoubleArray(9)
        for (i in 0..2) for (j in 0..2) r[i * 3 + j] = a[i * 3] * b[j] + a[i * 3 + 1] * b[3 + j] + a[i * 3 + 2] * b[6 + j]
        return r
    }

    fun transpose(a: DoubleArray) = doubleArrayOf(a[0], a[3], a[6], a[1], a[4], a[7], a[2], a[5], a[8])

    /** The rotation matrix of the Kinesis camera for Euler angles in degrees (X, then Y, then Z). */
    fun matrixOfEuler(e: Vec3): DoubleArray {
        val m = Mat4.rotationXYZ(e)
        return doubleArrayOf(
            m[0, 0].toDouble(), m[0, 1].toDouble(), m[0, 2].toDouble(),
            m[1, 0].toDouble(), m[1, 1].toDouble(), m[1, 2].toDouble(),
            m[2, 0].toDouble(), m[2, 1].toDouble(), m[2, 2].toDouble(),
        )
    }

    /** Euler angles (degrees, X then Y then Z) of a rotation matrix. */
    fun eulerOf(m: DoubleArray): Vec3 = Mat4.eulerXYZ(
        m[0].toFloat(), m[3].toFloat(), m[6].toFloat(),
        m[1].toFloat(), m[4].toFloat(), m[7].toFloat(),
        m[2].toFloat(), m[5].toFloat(), m[8].toFloat(),
    )

    /** How the phone has turned since the calibration, in the axes of the Kinesis camera: Q = C · (R0ᵀ · R) · C. */
    fun relative(r0: DoubleArray, r: DoubleArray): DoubleArray {
        val d = multiply(transpose(r0), r)
        return doubleArrayOf(d[0], -d[1], -d[2], -d[3], d[4], d[5], -d[6], d[7], d[8])
    }

    /** Scales the angle of a rotation about its own axis by [factor] (1 = unchanged, 0 = none, 2 = twice as much). */
    fun scaled(q: DoubleArray, factor: Double): DoubleArray {
        if (abs(factor - 1.0) < 1e-9) return q
        val trace = q[0] + q[4] + q[8]
        val angle = acos(((trace - 1) / 2).coerceIn(-1.0, 1.0))
        if (angle < 1e-9) return identity()
        var ax: Double; var ay: Double; var az: Double
        if (abs(angle - Math.PI) < 1e-4) {
            // Half a turn: the axis comes from the diagonal.
            ax = sqrt(max(0.0, (q[0] + 1) / 2)); ay = sqrt(max(0.0, (q[4] + 1) / 2)); az = sqrt(max(0.0, (q[8] + 1) / 2))
            if (q[1] < 0) ay = -ay
            if (q[2] < 0) az = -az
        } else {
            val s = 2 * sin(angle)
            ax = (q[7] - q[5]) / s; ay = (q[2] - q[6]) / s; az = (q[3] - q[1]) / s
        }
        val n = sqrt(ax * ax + ay * ay + az * az).takeIf { it > 1e-12 } ?: return identity()
        ax /= n; ay /= n; az /= n
        val a = angle * factor
        val c = kotlin.math.cos(a); val s = sin(a); val t = 1 - c
        return doubleArrayOf(
            t * ax * ax + c, t * ax * ay - s * az, t * ax * az + s * ay,
            t * ax * ay + s * az, t * ay * ay + c, t * ay * az - s * ax,
            t * ax * az - s * ay, t * ay * az + s * ax, t * az * az + c,
        )
    }

    /** Which axes of the camera follow the phone. */
    data class Axes(val x: Boolean = true, val y: Boolean = true, val z: Boolean = true) {
        val all: Boolean get() = x && y && z
    }

    /**
     * Camera rotation (Euler degrees) for the phone orientation [r], given the orientation [r0] at the calibration and the
     * camera\'s own rotation [base] at that moment. [intensity] multiplies the angle turned; a locked axis of the movement
     * stays at its calibration value.
     */
    fun cameraRotation(base: Vec3, r0: DoubleArray, r: DoubleArray, intensity: Float, axes: Axes = Axes()): Vec3 {
        var q = scaled(relative(r0, r), intensity.toDouble())
        if (!axes.all) {
            val e = eulerOf(q)
            q = matrixOfEuler(Vec3(if (axes.x) e.x else 0f, if (axes.y) e.y else 0f, if (axes.z) e.z else 0f))
        }
        return eulerOf(multiply(matrixOfEuler(base), q))
    }

    /** Integrates the angular velocity [wx], [wy], [wz] (rad/s, device axes) over [dt] seconds into [r] (device → world). */
    fun integrateGyro(r: DoubleArray, wx: Double, wy: Double, wz: Double, dt: Double): DoubleArray {
        val angle = sqrt(wx * wx + wy * wy + wz * wz) * dt
        if (angle < 1e-12) return r
        val inv = 1 / sqrt(wx * wx + wy * wy + wz * wz)
        val ax = wx * inv; val ay = wy * inv; val az = wz * inv
        val c = kotlin.math.cos(angle); val s = sin(angle); val t = 1 - c
        val step = doubleArrayOf(
            t * ax * ax + c, t * ax * ay - s * az, t * ax * az + s * ay,
            t * ax * ay + s * az, t * ay * ay + c, t * ay * az - s * ax,
            t * ax * az - s * ay, t * ay * az + s * ax, t * az * az + c,
        )
        return multiply(r, step)       // the turn happens in the device\'s own axes
    }

    /**
     * Optional position: a leaky double integration of the acceleration (gravity removed, m/s², device axes), so it
     * follows short pushes and pulls of the phone and drifts back to rest instead of running away. Output in pixels, in
     * the camera axes of the calibration. Rough by nature: phones cannot measure position this way for long.
     */
    class PositionTracker(private val pixelsPerMeter: Float = 900f, private val velocityDecay: Double = 0.45, private val positionDecay: Double = 1.6) {
        private var vx = 0.0; private var vy = 0.0; private var vz = 0.0
        private var px = 0.0; private var py = 0.0; private var pz = 0.0

        fun reset() {
            vx = 0.0; vy = 0.0; vz = 0.0; px = 0.0; py = 0.0; pz = 0.0
        }

        fun update(r0: DoubleArray, r: DoubleArray, ax: Double, ay: Double, az: Double, dt: Double, intensity: Float): Vec3 {
            // Device acceleration → initial device axes → Kinesis camera axes.
            val d = multiply(transpose(r0), r)
            val ix = d[0] * ax + d[1] * ay + d[2] * az
            val iy = d[3] * ax + d[4] * ay + d[5] * az
            val iz = d[6] * ax + d[7] * ay + d[8] * az
            val cx = ix; val cy = -iy; val cz = -iz
            vx = (vx + cx * dt) * exp(-dt / velocityDecay); vy = (vy + cy * dt) * exp(-dt / velocityDecay); vz = (vz + cz * dt) * exp(-dt / velocityDecay)
            px = (px + vx * dt) * exp(-dt / positionDecay); py = (py + vy * dt) * exp(-dt / positionDecay); pz = (pz + vz * dt) * exp(-dt / positionDecay)
            val k = pixelsPerMeter * intensity
            return Vec3((px * k).toFloat(), (py * k).toFloat(), (pz * k).toFloat())
        }
    }

    /** A moment of the take: the camera rotation (and position offset, when recorded) at a composition time. */
    class Sample(val time: Double, val rotation: Vec3, val position: Vec3? = null)

    /** What to do with the raw samples before they become keyframes. */
    data class Processing(
        /** 0 = as recorded, 1 = very smooth. */
        val smoothing: Float = 0.25f,
        /** Reduce the keyframes while keeping the shape of the curve. */
        val simplify: Boolean = true,
        /** Largest error the simplification may add, in degrees (pixels for the position, ×[POSITION_TOLERANCE_FACTOR]). */
        val toleranceDegrees: Float = 0.4f,
    )

    const val POSITION_TOLERANCE_FACTOR = 8f

    /** Euler angles jump by 360° when they wrap; this makes each axis continuous. */
    fun unwrap(samples: List<Sample>): List<Sample> {
        if (samples.isEmpty()) return samples
        val out = ArrayList<Sample>(samples.size)
        var prev = samples[0].rotation
        out += samples[0]
        for (i in 1 until samples.size) {
            val s = samples[i]
            fun fix(v: Float, p: Float): Float {
                var x = v
                while (x - p > 180f) x -= 360f
                while (x - p < -180f) x += 360f
                return x
            }
            val e = Vec3(fix(s.rotation.x, prev.x), fix(s.rotation.y, prev.y), fix(s.rotation.z, prev.z))
            out += Sample(s.time, e, s.position)
            prev = e
        }
        return out
    }

    /** Gaussian smoothing without delay (each point is averaged with its neighbours on both sides). */
    fun smooth(samples: List<Sample>, strength: Float, fps: Double): List<Sample> {
        if (samples.size < 3 || strength <= 0.001f) return samples
        val sigma = strength.coerceIn(0f, 1f) * fps * 0.15       // in samples
        val radius = (sigma * 3).toInt().coerceAtLeast(1)
        val weights = DoubleArray(2 * radius + 1) { exp(-0.5 * ((it - radius) / sigma).let { v -> v * v }) }
        val hasPosition = samples.all { it.position != null }
        return samples.indices.map { i ->
            var wsum = 0.0
            var rx = 0.0; var ry = 0.0; var rz = 0.0
            var qx = 0.0; var qy = 0.0; var qz = 0.0
            for (k in -radius..radius) {
                val j = (i + k).coerceIn(0, samples.size - 1)
                val w = weights[k + radius]
                wsum += w
                val r = samples[j].rotation
                rx += r.x * w; ry += r.y * w; rz += r.z * w
                samples[j].position?.let { p -> qx += p.x * w; qy += p.y * w; qz += p.z * w }
            }
            Sample(
                samples[i].time,
                Vec3((rx / wsum).toFloat(), (ry / wsum).toFloat(), (rz / wsum).toFloat()),
                if (hasPosition) Vec3((qx / wsum).toFloat(), (qy / wsum).toFloat(), (qz / wsum).toFloat()) else null,
            )
        }
    }

    /** Ramer–Douglas–Peucker over all channels: keeps the fewest samples whose straight lines stay within [tolerance]. */
    fun simplify(samples: List<Sample>, tolerance: Float): List<Sample> {
        if (samples.size <= 2) return samples
        val keep = BooleanArray(samples.size)
        keep[0] = true; keep[samples.size - 1] = true
        val stack = ArrayDeque<IntArray>()
        stack.addLast(intArrayOf(0, samples.size - 1))
        while (stack.isNotEmpty()) {
            val (a, b) = stack.removeLast()
            var worst = -1
            var worstErr = tolerance
            for (i in a + 1 until b) {
                val err = deviation(samples[a], samples[b], samples[i])
                if (err > worstErr) { worstErr = err; worst = i }
            }
            if (worst >= 0) {
                keep[worst] = true
                stack.addLast(intArrayOf(a, worst)); stack.addLast(intArrayOf(worst, b))
            }
        }
        return samples.filterIndexed { i, _ -> keep[i] }
    }

    private fun deviation(a: Sample, b: Sample, s: Sample): Float {
        val span = b.time - a.time
        val t = if (span > 1e-9) ((s.time - a.time) / span).toFloat() else 0f
        var err = 0f
        err = max(err, abs(a.rotation.x + (b.rotation.x - a.rotation.x) * t - s.rotation.x))
        err = max(err, abs(a.rotation.y + (b.rotation.y - a.rotation.y) * t - s.rotation.y))
        err = max(err, abs(a.rotation.z + (b.rotation.z - a.rotation.z) * t - s.rotation.z))
        val pa = a.position; val pb = b.position; val ps = s.position
        if (pa != null && pb != null && ps != null) {
            val k = 1f / POSITION_TOLERANCE_FACTOR
            err = max(err, abs(pa.x + (pb.x - pa.x) * t - ps.x) * k)
            err = max(err, abs(pa.y + (pb.y - pa.y) * t - ps.y) * k)
            err = max(err, abs(pa.z + (pb.z - pa.z) * t - ps.z) * k)
        }
        return err
    }

    /** The result of processing a take: keyframes for `camera.rotation` and, when recorded, for `camera.position`. */
    class Keys(val rotation: List<Keyframe<Vec3>>, val position: List<Keyframe<Vec3>>?)

    /** Raw samples → keyframes (times are local to the layer after subtracting [timeOffset]). */
    fun keyframes(samples: List<Sample>, fps: Double, processing: Processing, timeOffset: Double = 0.0, basePosition: Vec3? = null): Keys {
        var s = unwrap(samples)
        s = smooth(s, processing.smoothing, fps)
        if (processing.simplify) s = simplify(s, processing.toleranceDegrees)
        val rotation = s.map { Keyframe(it.time - timeOffset, it.rotation, Easing.LINEAR) }
        val position = if (s.all { it.position != null }) s.map { Keyframe(it.time - timeOffset, (basePosition ?: Vec3.ZERO) + it.position!!, Easing.LINEAR) } else null
        return Keys(rotation, position)
    }

    /**
     * Puts [keys] (local times) into [anim], replacing what it had between the first and the last of them. What is
     * outside that range stays, so re-recording a stretch does not disturb the rest.
     */
    fun splice(anim: Animatable<Vec3>, keys: List<Keyframe<Vec3>>): Animatable<Vec3> {
        if (keys.isEmpty()) return anim
        val from = keys.first().time
        val to = keys.last().time
        val kept = anim.keyframes.filter { it.time < from - 1e-6 || it.time > to + 1e-6 }.toMutableList()
        kept += keys
        kept.sortBy { it.time }
        val dedup = ArrayList<Keyframe<Vec3>>()
        for (k in kept) if (dedup.isNotEmpty() && abs(dedup.last().time - k.time) < 1e-6) dedup[dedup.size - 1] = k else dedup += k
        return anim.copy(value = dedup.first().value, keyframes = dedup)
    }

    /**
     * Writes a processed take into the camera layer: the camera switches to its own rotation (when it aimed at a target)
     * and the keyframes replace the stretch they cover.
     */
    fun applyTo(layer: Layer, keys: Keys, compHeight: Int, localNow: Double): Layer {
        var l = LayerProps.withExplicitCameraPosition(layer, compHeight)
        l = LayerProps.withCameraAim(l, CameraAim.ROTATION, localNow, compHeight)
        val cam = l.camera ?: return l
        var camera = cam.copy(rotation = splice(cam.rotation, keys.rotation))
        keys.position?.let { p -> camera = camera.copy(position = splice(camera.position ?: Animatable(p.first().value), p)) }
        return l.copy(camera = camera)
    }

    /** The pose of the camera while recording, before it is written to the project: the preview draws with it. */
    class LivePose(val layerId: String, val rotation: Vec3, val position: Vec3? = null)

    /** The project as the preview should draw it while recording: [pose] replaces the camera's rotation (and position). */
    fun withLivePose(project: dev.kinesis.core.model.KnsProject, pose: LivePose): dev.kinesis.core.model.KnsProject =
        dev.kinesis.core.model.LayerTree.update(project, pose.layerId) { l ->
            val cam = l.camera ?: return@update l
            l.copy(
                camera = cam.copy(
                    aim = CameraAim.ROTATION,
                    rotation = dev.kinesis.core.anim.anim(pose.rotation),
                    position = pose.position?.let { dev.kinesis.core.anim.anim(it) } ?: cam.position,
                ),
            )
        }

    /** Linear interpolation helper for tests and previews. */
    fun lerp(a: Vec3, b: Vec3, t: Float): Vec3 = Interpolators.vec3(a, b, min(1f, max(0f, t)))

    /** Angle in degrees of a rotation matrix (for tests). */
    fun angleOf(q: DoubleArray): Double = Math.toDegrees(atan2(sqrt(max(0.0, 1 - ((q[0] + q[4] + q[8] - 1) / 2).let { it * it })), (q[0] + q[4] + q[8] - 1) / 2))
}
