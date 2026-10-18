package dev.kinesis.editor.sensors

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import dev.kinesis.core.edit.RealCamera
import kotlin.math.sqrt

/**
 * The orientation of the phone for Real Camera. Prefers the game rotation vector (gyroscope + accelerometer, no
 * compass, so no jumps near magnets), then the rotation vector, then a gyroscope integrated here. Optionally the linear
 * acceleration, for the (rough) position.
 */
class RealCameraSensor(context: Context) : SensorEventListener {
    enum class Source { GAME_ROTATION_VECTOR, ROTATION_VECTOR, GYROSCOPE }

    private val manager = context.applicationContext.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    val source: Source? = sourceOf(manager)
    private val orientationSensor: Sensor? = when (source) {
        Source.GAME_ROTATION_VECTOR -> manager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
        Source.ROTATION_VECTOR -> manager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        Source.GYROSCOPE -> manager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        null -> null
    }
    private val accelerationSensor: Sensor? = manager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)

    /** Latest orientation, device → world (3×3, row-major), or null before the first reading. */
    @Volatile var matrix: DoubleArray? = null
        private set

    /** Latest linear acceleration (m/s², device axes), or null. */
    @Volatile var acceleration: DoubleArray? = null
        private set

    val hasAcceleration: Boolean get() = accelerationSensor != null
    private var lastGyroNs = 0L
    private var running = false

    /** Starts listening. [withAcceleration] also reads the linear acceleration. */
    fun start(withAcceleration: Boolean): Boolean {
        val sensor = orientationSensor ?: return false
        if (running) stop()
        matrix = if (source == Source.GYROSCOPE) RealCamera.identity() else null
        acceleration = null
        lastGyroNs = 0L
        val ok = manager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME)
        if (withAcceleration && accelerationSensor != null) manager.registerListener(this, accelerationSensor, SensorManager.SENSOR_DELAY_GAME)
        running = ok
        return ok
    }

    fun stop() {
        manager.unregisterListener(this)
        running = false
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_GAME_ROTATION_VECTOR, Sensor.TYPE_ROTATION_VECTOR -> {
                val v = event.values
                val x = v[0].toDouble(); val y = v[1].toDouble(); val z = v[2].toDouble()
                val w = if (v.size > 3) v[3].toDouble() else sqrt((1 - x * x - y * y - z * z).coerceAtLeast(0.0))
                matrix = RealCamera.matrix(x, y, z, w)
            }
            Sensor.TYPE_GYROSCOPE -> {
                if (lastGyroNs != 0L) {
                    val dt = (event.timestamp - lastGyroNs) / 1e9
                    if (dt in 1e-4..0.5) matrix = RealCamera.integrateGyro(matrix ?: RealCamera.identity(), event.values[0].toDouble(), event.values[1].toDouble(), event.values[2].toDouble(), dt)
                }
                lastGyroNs = event.timestamp
            }
            Sensor.TYPE_LINEAR_ACCELERATION -> acceleration = doubleArrayOf(event.values[0].toDouble(), event.values[1].toDouble(), event.values[2].toDouble())
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    companion object {
        private fun sourceOf(m: SensorManager): Source? = when {
            m.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR) != null -> Source.GAME_ROTATION_VECTOR
            m.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR) != null -> Source.ROTATION_VECTOR
            m.getDefaultSensor(Sensor.TYPE_GYROSCOPE) != null -> Source.GYROSCOPE
            else -> null
        }

        /** Whether this phone can drive a camera at all (the option is hidden when it cannot). */
        fun isAvailable(context: Context): Boolean {
            val m = context.applicationContext.getSystemService(Context.SENSOR_SERVICE) as? SensorManager ?: return false
            return sourceOf(m) != null
        }
    }
}
