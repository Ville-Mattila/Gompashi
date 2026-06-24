package fi.gompashi.app

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate

/** Emits device azimuth in degrees [0,360), 0 = north. Empty/no emissions if no sensor. */
class CompassProvider(context: Context) {
    private val sensorManager =
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val rotationSensor: Sensor? =
        sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

    val hasCompass: Boolean get() = rotationSensor != null

    fun azimuthFlow(): Flow<Float> = callbackFlow {
        val sensor = rotationSensor
        if (sensor == null) {
            close()
            return@callbackFlow
        }
        val rotationMatrix = FloatArray(9)
        val orientation = FloatArray(3)
        var smoothed = Float.NaN

        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                // Phone held flat (screen up, like a map): the screen plane equals the
                // real-world horizontal plane, so the raw azimuth is the compass heading
                // of the device's top edge — exactly what we rotate the bottle against.
                SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                SensorManager.getOrientation(rotationMatrix, orientation)
                val deg = ((Math.toDegrees(orientation[0].toDouble()) + 360.0) % 360.0).toFloat()
                smoothed = if (smoothed.isNaN()) deg else lowPass(deg, smoothed)
                trySend(smoothed)
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }

        sensorManager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_UI)
        awaitClose { sensorManager.unregisterListener(listener) }
    }.conflate()

    /** Angular low-pass that handles the 0/360 wraparound. */
    private fun lowPass(target: Float, prev: Float, alpha: Float = 0.15f): Float {
        var diff = target - prev
        if (diff > 180f) diff -= 360f
        if (diff < -180f) diff += 360f
        return ((prev + alpha * diff) + 360f) % 360f
    }
}
