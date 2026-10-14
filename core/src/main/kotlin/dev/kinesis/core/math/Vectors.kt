package dev.kinesis.core.math

import dev.kinesis.core.format.Vec2Serializer
import dev.kinesis.core.format.Vec3Serializer
import kotlinx.serialization.Serializable
import kotlin.math.sqrt

/** 2D vector in composition pixels (origin at the composition centre, +x right, +y down). */
@Serializable(with = Vec2Serializer::class)
data class Vec2(val x: Float, val y: Float) {
    operator fun plus(o: Vec2) = Vec2(x + o.x, y + o.y)
    operator fun minus(o: Vec2) = Vec2(x - o.x, y - o.y)
    operator fun times(s: Float) = Vec2(x * s, y * s)
    operator fun unaryMinus() = Vec2(-x, -y)
    fun length(): Float = sqrt(x * x + y * y)

    companion object {
        val ZERO = Vec2(0f, 0f)
        val ONE = Vec2(1f, 1f)
    }
}

/** 3D vector in pixels (+x right, +y down, +z away from the viewer). */
@Serializable(with = Vec3Serializer::class)
data class Vec3(val x: Float, val y: Float, val z: Float) {
    operator fun plus(o: Vec3) = Vec3(x + o.x, y + o.y, z + o.z)
    operator fun minus(o: Vec3) = Vec3(x - o.x, y - o.y, z - o.z)
    operator fun times(s: Float) = Vec3(x * s, y * s, z * s)
    operator fun unaryMinus() = Vec3(-x, -y, -z)
    fun dot(o: Vec3): Float = x * o.x + y * o.y + z * o.z
    fun cross(o: Vec3) = Vec3(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x)
    fun length(): Float = sqrt(x * x + y * y + z * z)
    fun normalized(): Vec3 {
        val l = length()
        return if (l < 1e-9f) this else Vec3(x / l, y / l, z / l)
    }

    companion object {
        val ZERO = Vec3(0f, 0f, 0f)
        val ONE = Vec3(1f, 1f, 1f)
    }
}

fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t
fun lerp(a: Vec2, b: Vec2, t: Float) = Vec2(lerp(a.x, b.x, t), lerp(a.y, b.y, t))
fun lerp(a: Vec3, b: Vec3, t: Float) = Vec3(lerp(a.x, b.x, t), lerp(a.y, b.y, t), lerp(a.z, b.z, t))

fun Float.clamp(min: Float, max: Float): Float = if (this < min) min else if (this > max) max else this
fun Double.clamp(min: Double, max: Double): Double = if (this < min) min else if (this > max) max else this
