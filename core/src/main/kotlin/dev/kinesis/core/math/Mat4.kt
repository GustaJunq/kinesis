package dev.kinesis.core.math

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/** Column-major 4x4 matrix (OpenGL layout): element (row, col) lives at `m[col * 4 + row]`. */
class Mat4(val m: FloatArray = identityArray()) {
    init {
        require(m.size == 16) { "Mat4 needs 16 elements" }
    }

    operator fun get(row: Int, col: Int): Float = m[col * 4 + row]

    /** `this * o`: applies [o] first. */
    operator fun times(o: Mat4): Mat4 {
        val r = FloatArray(16)
        for (col in 0 until 4) {
            for (row in 0 until 4) {
                var s = 0f
                for (k in 0 until 4) s += this[row, k] * o[k, col]
                r[col * 4 + row] = s
            }
        }
        return Mat4(r)
    }

    fun transformPoint(p: Vec3): Vec3 {
        val x = m[0] * p.x + m[4] * p.y + m[8] * p.z + m[12]
        val y = m[1] * p.x + m[5] * p.y + m[9] * p.z + m[13]
        val z = m[2] * p.x + m[6] * p.y + m[10] * p.z + m[14]
        val w = m[3] * p.x + m[7] * p.y + m[11] * p.z + m[15]
        return if (abs(w - 1f) > 1e-6f && abs(w) > 1e-9f) Vec3(x / w, y / w, z / w) else Vec3(x, y, z)
    }

    /** Returns the homogeneous result (x, y, z, w) without the perspective divide. */
    fun transformHomogeneous(p: Vec3): FloatArray = floatArrayOf(
        m[0] * p.x + m[4] * p.y + m[8] * p.z + m[12],
        m[1] * p.x + m[5] * p.y + m[9] * p.z + m[13],
        m[2] * p.x + m[6] * p.y + m[10] * p.z + m[14],
        m[3] * p.x + m[7] * p.y + m[11] * p.z + m[15],
    )

    fun transformVector(v: Vec3): Vec3 = Vec3(
        m[0] * v.x + m[4] * v.y + m[8] * v.z,
        m[1] * v.x + m[5] * v.y + m[9] * v.z,
        m[2] * v.x + m[6] * v.y + m[10] * v.z,
    )

    fun translation(): Vec3 = Vec3(m[12], m[13], m[14])

    fun transpose(): Mat4 {
        val r = FloatArray(16)
        for (row in 0 until 4) for (col in 0 until 4) r[row * 4 + col] = this[row, col]
        return Mat4(r)
    }

    fun inverse(): Mat4? {
        val a = m
        val inv = FloatArray(16)
        inv[0] = a[5] * a[10] * a[15] - a[5] * a[11] * a[14] - a[9] * a[6] * a[15] + a[9] * a[7] * a[14] + a[13] * a[6] * a[11] - a[13] * a[7] * a[10]
        inv[4] = -a[4] * a[10] * a[15] + a[4] * a[11] * a[14] + a[8] * a[6] * a[15] - a[8] * a[7] * a[14] - a[12] * a[6] * a[11] + a[12] * a[7] * a[10]
        inv[8] = a[4] * a[9] * a[15] - a[4] * a[11] * a[13] - a[8] * a[5] * a[15] + a[8] * a[7] * a[13] + a[12] * a[5] * a[11] - a[12] * a[7] * a[9]
        inv[12] = -a[4] * a[9] * a[14] + a[4] * a[10] * a[13] + a[8] * a[5] * a[14] - a[8] * a[6] * a[13] - a[12] * a[5] * a[10] + a[12] * a[6] * a[9]
        inv[1] = -a[1] * a[10] * a[15] + a[1] * a[11] * a[14] + a[9] * a[2] * a[15] - a[9] * a[3] * a[14] - a[13] * a[2] * a[11] + a[13] * a[3] * a[10]
        inv[5] = a[0] * a[10] * a[15] - a[0] * a[11] * a[14] - a[8] * a[2] * a[15] + a[8] * a[3] * a[14] + a[12] * a[2] * a[11] - a[12] * a[3] * a[10]
        inv[9] = -a[0] * a[9] * a[15] + a[0] * a[11] * a[13] + a[8] * a[1] * a[15] - a[8] * a[3] * a[13] - a[12] * a[1] * a[11] + a[12] * a[3] * a[9]
        inv[13] = a[0] * a[9] * a[14] - a[0] * a[10] * a[13] - a[8] * a[1] * a[14] + a[8] * a[2] * a[13] + a[12] * a[1] * a[10] - a[12] * a[2] * a[9]
        inv[2] = a[1] * a[6] * a[15] - a[1] * a[7] * a[14] - a[5] * a[2] * a[15] + a[5] * a[3] * a[14] + a[13] * a[2] * a[7] - a[13] * a[3] * a[6]
        inv[6] = -a[0] * a[6] * a[15] + a[0] * a[7] * a[14] + a[4] * a[2] * a[15] - a[4] * a[3] * a[14] - a[12] * a[2] * a[7] + a[12] * a[3] * a[6]
        inv[10] = a[0] * a[5] * a[15] - a[0] * a[7] * a[13] - a[4] * a[1] * a[15] + a[4] * a[3] * a[13] + a[12] * a[1] * a[7] - a[12] * a[3] * a[5]
        inv[14] = -a[0] * a[5] * a[14] + a[0] * a[6] * a[13] + a[4] * a[1] * a[14] - a[4] * a[2] * a[13] - a[12] * a[1] * a[6] + a[12] * a[2] * a[5]
        inv[3] = -a[1] * a[6] * a[11] + a[1] * a[7] * a[10] + a[5] * a[2] * a[11] - a[5] * a[3] * a[10] - a[9] * a[2] * a[7] + a[9] * a[3] * a[6]
        inv[7] = a[0] * a[6] * a[11] - a[0] * a[7] * a[10] - a[4] * a[2] * a[11] + a[4] * a[3] * a[10] + a[8] * a[2] * a[7] - a[8] * a[3] * a[6]
        inv[11] = -a[0] * a[5] * a[11] + a[0] * a[7] * a[9] + a[4] * a[1] * a[11] - a[4] * a[3] * a[9] - a[8] * a[1] * a[7] + a[8] * a[3] * a[5]
        inv[15] = a[0] * a[5] * a[10] - a[0] * a[6] * a[9] - a[4] * a[1] * a[10] + a[4] * a[2] * a[9] + a[8] * a[1] * a[6] - a[8] * a[2] * a[5]
        val det = a[0] * inv[0] + a[1] * inv[4] + a[2] * inv[8] + a[3] * inv[12]
        if (abs(det) < 1e-20f) return null
        val id = 1f / det
        for (i in 0 until 16) inv[i] *= id
        return Mat4(inv)
    }

    /** Upper-left 3x3 inverse-transpose, packed column-major in 9 floats (for normal transforms). */
    fun normalMatrix(): FloatArray {
        val inv = inverse() ?: return floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
        val t = inv.transpose()
        return floatArrayOf(t[0, 0], t[1, 0], t[2, 0], t[0, 1], t[1, 1], t[2, 1], t[0, 2], t[1, 2], t[2, 2])
    }

    override fun equals(other: Any?): Boolean = other is Mat4 && m.contentEquals(other.m)
    override fun hashCode(): Int = m.contentHashCode()
    override fun toString(): String = "Mat4(${m.joinToString()})"

    companion object {
        fun identityArray() = floatArrayOf(1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f)
        fun identity() = Mat4()

        fun translation(x: Float, y: Float, z: Float) = Mat4(
            floatArrayOf(1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f, x, y, z, 1f),
        )

        fun translation(v: Vec3) = translation(v.x, v.y, v.z)

        fun scale(x: Float, y: Float, z: Float) = Mat4(
            floatArrayOf(x, 0f, 0f, 0f, 0f, y, 0f, 0f, 0f, 0f, z, 0f, 0f, 0f, 0f, 1f),
        )

        fun scale(v: Vec3) = scale(v.x, v.y, v.z)

        fun rotationX(degrees: Float): Mat4 {
            val r = Math.toRadians(degrees.toDouble())
            val c = cos(r).toFloat(); val s = sin(r).toFloat()
            return Mat4(floatArrayOf(1f, 0f, 0f, 0f, 0f, c, s, 0f, 0f, -s, c, 0f, 0f, 0f, 0f, 1f))
        }

        fun rotationY(degrees: Float): Mat4 {
            val r = Math.toRadians(degrees.toDouble())
            val c = cos(r).toFloat(); val s = sin(r).toFloat()
            return Mat4(floatArrayOf(c, 0f, -s, 0f, 0f, 1f, 0f, 0f, s, 0f, c, 0f, 0f, 0f, 0f, 1f))
        }

        fun rotationZ(degrees: Float): Mat4 {
            val r = Math.toRadians(degrees.toDouble())
            val c = cos(r).toFloat(); val s = sin(r).toFloat()
            return Mat4(floatArrayOf(c, s, 0f, 0f, -s, c, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f))
        }

        /** Euler rotation applied in X, then Y, then Z order (degrees). */
        fun rotationXYZ(r: Vec3): Mat4 = rotationZ(r.z) * rotationY(r.y) * rotationX(r.x)

        /** 3D layer transform: `T(position) · R(rotation) · S(scale) · T(-anchor)`. */
        fun layer(position: Vec3, anchor: Vec3, scale: Vec3, rotation: Vec3): Mat4 =
            translation(position) * rotationXYZ(rotation) * scale(scale) * translation(-anchor)

        /** Right-handed look-at view matrix (camera looks down its -Z axis), like gluLookAt. */
        fun lookAt(eye: Vec3, target: Vec3, up: Vec3): Mat4 {
            var f = (target - eye).normalized()
            if (f.length() < 1e-6f) f = Vec3(0f, 0f, 1f)
            var s = f.cross(up).normalized()
            if (s.length() < 1e-6f) s = f.cross(Vec3(0f, 0f, 1f)).normalized()
            if (s.length() < 1e-6f) s = Vec3(1f, 0f, 0f)
            val u = s.cross(f)
            return Mat4(
                floatArrayOf(
                    s.x, u.x, -f.x, 0f,
                    s.y, u.y, -f.y, 0f,
                    s.z, u.z, -f.z, 0f,
                    -s.dot(eye), -u.dot(eye), f.dot(eye), 1f,
                ),
            )
        }

        /** OpenGL perspective projection (vertical field of view in degrees). */
        fun perspective(fovYDegrees: Float, aspect: Float, near: Float, far: Float): Mat4 {
            val f = 1f / tan(Math.toRadians(fovYDegrees / 2.0)).toFloat()
            val nf = 1f / (near - far)
            return Mat4(
                floatArrayOf(
                    f / aspect, 0f, 0f, 0f,
                    0f, f, 0f, 0f,
                    0f, 0f, (far + near) * nf, -1f,
                    0f, 0f, 2f * far * near * nf, 0f,
                ),
            )
        }
    }
}
