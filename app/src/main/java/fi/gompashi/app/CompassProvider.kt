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

/** Device orientation in degrees: compass heading plus tilt (pitch forward/back, roll left/right). */
data class DeviceOrientation(
    val azimuth: Float,
    val pitch: Float,
    val roll: Float,
)

/** Emits device orientation. No emissions if the device has no rotation-vector sensor. */
class CompassProvider(context: Context) {
    private val sensorManager =
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val rotationSensor: Sensor? =
        sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

    val hasCompass: Boolean get() = rotationSensor != null

    fun orientationFlow(): Flow<DeviceOrientation> = callbackFlow {
        val sensor = rotationSensor
        if (sensor == null) {
            close()
            return@callbackFlow
        }
        val rotationMatrix = FloatArray(9)
        val orientation = FloatArray(3)
        var azimuth = Float.NaN
        var pitch = 0f
        var roll = 0f

        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                // Phone held flat (screen up, like a map): the screen plane equals the
                // real-world horizontal plane, so the raw azimuth is the compass heading
                // of the device's top edge. Pitch/roll give the small tilt used for the
                // bottle's 3D parallax.
                SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                SensorManager.getOrientation(rotationMatrix, orientation)
                val rawAz = ((Math.toDegrees(orientation[0].toDouble()) + 360.0) % 360.0).toFloat()
                val rawPitch = Math.toDegrees(orientation[1].toDouble()).toFloat()
                val rawRoll = Math.toDegrees(orientation[2].toDouble()).toFloat()

                azimuth = if (azimuth.isNaN()) rawAz else lowPassAngle(rawAz, azimuth)
                pitch = lowPass(rawPitch, pitch)
                roll = lowPass(rawRoll, roll)
                trySend(DeviceOrientation(azimuth, pitch, roll))
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }

        sensorManager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_GAME)
        awaitClose { sensorManager.unregisterListener(listener) }
    }.conflate()

    /** Plain low-pass for tilt values (no wraparound). */
    private fun lowPass(target: Float, prev: Float, alpha: Float = 0.1f): Float =
        prev + alpha * (target - prev)

    /** Angular low-pass that handles the 0/360 wraparound. */
    private fun lowPassAngle(target: Float, prev: Float, alpha: Float = 0.15f): Float {
        var diff = target - prev
        if (diff > 180f) diff -= 360f
        if (diff < -180f) diff += 360f
        return ((prev + alpha * diff) + 360f) % 360f
    }
}
