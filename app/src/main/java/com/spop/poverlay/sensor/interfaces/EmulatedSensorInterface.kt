package com.spop.poverlay.sensor.interfaces

import android.os.SystemClock
import com.spop.poverlay.control.BikeControl
import com.spop.poverlay.control.BikeSample
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

enum class EmulatedModel(val label: String) {
    Bike("Bike"), BikePlus("Bike+"), CrossTrainer("CrossTrainer"), Tread("Tread")
}

/**
 * How often the hardware sensor path for [this] model publishes a sample. Bike+ and the G700
 * CrossTrainer poll the bike service every 200 ms (BikePlusCombinedSensor). The original Bike
 * and the Tread receive service callbacks whose rate has not been measured yet, so they use
 * the same period until it is.
 */
val EmulatedModel.defaultSamplePeriodMs: Long
    get() = 200L

/**
 * Explicit developer emulation. Never binds a Peloton service or writes a physical motor.
 *
 * Mirrors how the real sensor paths publish: every sample re-emits every value, unchanged or
 * not (the hardware flows are MutableSharedFlows without deduplication), and speed/incline come
 * from the same sources as on the device. Emulation therefore exercises the same downstream
 * load as a real ride, which matters when profiling the overlay.
 */
class EmulatedSensorInterface(
    val model: EmulatedModel,
    private val samplePeriodMs: Long = model.defaultSamplePeriodMs
) : SensorInterface {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    override val deviceType = if (model == EmulatedModel.Tread) DeviceType.Tread else DeviceType.Bike

    private fun sensorFlow() = MutableSharedFlow<Float>(
        replay = 1,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    private val mutablePower = sensorFlow()
    private val mutableCadence = sensorFlow()
    private val mutableResistance = sensorFlow()
    private val mutableSpeed = sensorFlow()
    private val mutableIncline = sensorFlow()

    override val power: Flow<Float> = mutablePower.asSharedFlow()
    override val cadence: Flow<Float> = mutableCadence.asSharedFlow()
    override val resistance: Flow<Float> = mutableResistance.asSharedFlow()

    // Bikes derive speed from power and report no incline (the SensorInterface defaults);
    // only the Tread reports both directly.
    override val speed: Flow<Float> =
        if (deviceType == DeviceType.Tread) mutableSpeed.asSharedFlow() else super.speed
    override val incline: Flow<Float> =
        if (deviceType == DeviceType.Tread) mutableIncline.asSharedFlow() else super.incline

    @Volatile private var currentResistance = if (deviceType == DeviceType.Tread) 0f else 40f

    override val bikeControl = if (model == EmulatedModel.BikePlus || model == EmulatedModel.CrossTrainer)
        BikeControl(true, SystemClock::elapsedRealtime) { currentResistance = it.toFloat(); true } else null

    init {
        bikeControl?.acceptSample(BikeSample(150f, 80f, currentResistance.toInt(), SystemClock.elapsedRealtime()))
        bikeControl?.localSimulation()
        scope.launch {
            var phase = 0f
            while (isActive) {
                phase += .1f
                val variation = kotlin.math.sin(phase)
                val resistance = currentResistance
                val power = if (deviceType == DeviceType.Bike) resistance * 3.75f + variation else 150f + variation
                val cadence = if (deviceType == DeviceType.Bike) 80f + variation else 0f
                mutablePower.tryEmit(power)
                mutableCadence.tryEmit(cadence)
                mutableResistance.tryEmit(resistance)
                if (deviceType == DeviceType.Tread) {
                    mutableSpeed.tryEmit(6.2f + variation * .01f)
                    mutableIncline.tryEmit(3f)
                }
                bikeControl?.acceptSample(BikeSample(power, cadence, resistance.toInt(), SystemClock.elapsedRealtime()))
                bikeControl?.tick()
                delay(samplePeriodMs)
            }
        }
    }
    fun stop() { bikeControl?.stop(); scope.cancel() }
}
