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

/**
 * Emits the cumulative number of steps detected since collection started, using the
 * hardware step-detector sensor. No emissions if the device lacks the sensor (or the
 * ACTIVITY_RECOGNITION permission was not granted).
 */
class StepProvider(context: Context) {
    private val sensorManager =
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val stepSensor: Sensor? =
        sensorManager.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)

    val hasSensor: Boolean get() = stepSensor != null

    fun stepFlow(): Flow<Int> = callbackFlow {
        val sensor = stepSensor
        if (sensor == null) {
            close()
            return@callbackFlow
        }
        var count = 0
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                // TYPE_STEP_DETECTOR fires one event (value 1.0) per detected step.
                count += 1
                trySend(count)
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }
        sensorManager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_NORMAL)
        awaitClose { sensorManager.unregisterListener(listener) }
    }.conflate()
}
