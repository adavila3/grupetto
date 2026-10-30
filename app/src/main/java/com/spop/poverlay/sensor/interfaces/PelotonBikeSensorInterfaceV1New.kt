package com.spop.poverlay.sensor.interfaces

import android.content.Context
import com.spop.poverlay.sensor.BoundBikeService
import com.spop.poverlay.sensor.BikeServiceDiagnostics
import com.spop.poverlay.sensor.BikeServiceLostException
import com.spop.poverlay.sensor.bindBikeService
import com.spop.poverlay.sensor.reconnectingFlow
import com.spop.poverlay.sensor.v1legacy.SensorDataSensor
import com.spop.poverlay.sensor.v1new.V1BikeService
import com.spop.poverlay.sensor.v1new.V1NewCombinedSensor
import com.spop.poverlay.sensor.v1new.V1ServiceCandidate
import com.spop.poverlay.sensor.v1new.discoverV1BikeServices
import com.spop.poverlay.sensor.v1new.selectV1BikeServices
import com.spop.poverlay.sensor.v1new.validateV1ServiceDescriptor
import com.spop.poverlay.util.windowed
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.emitAll
import kotlin.coroutines.CoroutineContext

/**
 * Original Bike telemetry: prefer Affernet callbacks, falling back to SensorData reads
 * only when Android cannot find an enabled service advertising the Affernet V1 protocol.
 * Service package/class names are discovered, and the Binder contract is checked before use.
 */
class PelotonBikeSensorInterfaceV1New(val context: Context) : SensorInterface, CoroutineScope {
    companion object {
        /**
         * Resistance is filtered with a moving window since it occasionally spikes
         * The last few resistance readings will grouped, and the lowest reading will be shown
         *
         * The spikes are likely a limitation of ADC accuracy
         */
        const val ResistanceMovingAverageWindowSize = 3
    }
    
    private val job = SupervisorJob()
    override val coroutineContext: CoroutineContext = job + Dispatchers.IO
    private val diagnostics = BikeServiceDiagnostics(context)

    fun stop() {
        job.cancel()
    }

    // Discovery and connection rerun whenever a session ends, so a crashed or updated bike
    // service (or one that wasn't ready yet) is picked up again without restarting Grupetto.
    private val combinedSensorState = reconnectingFlow<SensorInterface>(diagnostics::warn) { connectOnce(it) }
        .shareIn(this, SharingStarted.Lazily, 1)

    /**
     * One discovery and connection pass. Returns true if a service delivered telemetry. Only the
     * first failed pass of an outage collects full diagnostics; retries just log a warning.
     */
    private suspend fun FlowCollector<SensorInterface>.connectOnce(consecutiveFailures: Int): Boolean {
        var delivered = false
        val report: suspend (String) -> Unit =
            if (consecutiveFailures == 0) diagnostics::capture else { message -> diagnostics.warn(message) }
        try {
            val discovered = discoverV1BikeServices(context)
            discovered.forEach {
                diagnostics.record("Discovered ${it.protocol}: ${it.component} process=${it.processName} " +
                    "uid=${it.uid} permission=${it.permission ?: "none"} accessible=${it.accessible} system=${it.systemApp}")
            }
            val candidates = selectV1BikeServices(discovered)
            if (candidates.isEmpty()) {
                report("No accessible supported original Bike telemetry service; candidates=${discovered.size}")
            }
            for (candidate in candidates) {
                try {
                    emitAll(telemetry(candidate).onEach { delivered = true })
                } catch (e: BikeServiceLostException) {
                    // Start over from discovery: the service may come back under a new process
                    // or version, and it should win over the SensorData fallback again.
                    diagnostics.warn("${candidate.component}: ${e.message}")
                    return delivered
                } catch (e: TimeoutCancellationException) {
                    report("No connection/valid telemetry from ${candidate.component} before timeout")
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    report("${candidate.component}: ${e.javaClass.simpleName}: ${e.message}")
                }
                // telemetry() has already unbound before another compatible provider is attempted.
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            report("Bike service discovery failed: ${e.javaClass.simpleName}: ${e.message}")
        }
        return delivered
    }

    private fun telemetry(candidate: V1ServiceCandidate) = flow<SensorInterface> {
        var binding: BoundBikeService? = null
        try {
            val bound = withTimeout(5_000) {
                bindBikeService(context, candidate::createIntent).also { binding = it }
            }
            val binder = bound.binder
            val descriptor = binder.interfaceDescriptor
            diagnostics.record("Connected service=${binding?.componentName?.flattenToShortString()} " +
                "protocol=${candidate.protocol} binder=$descriptor; awaiting telemetry")
            validateV1ServiceDescriptor(candidate.protocol, descriptor)
            coroutineScope {
                when (candidate.protocol) {
                    V1BikeService.Affernet -> {
                        @Suppress("DEPRECATION")
                        val version = context.packageManager.getPackageInfo(candidate.packageName, 0).versionCode
                        val localCalibration = (context.applicationContext as? com.spop.poverlay.GrupettoApplication)?.grupettoCalibration
                            ?.takeIf { candidate.packageName == "com.onepeloton.affernetservice" && version == 2040490 }
                        val sensor = V1NewCombinedSensor(binder, powerCalibration = localCalibration)
                        val verification = localCalibration?.let { model -> launch {
                            while (isActive) {
                                runCatching { com.spop.poverlay.calibration.readActiveLxx(binder) }
                                    .onSuccess { model.verifyBaseline(it) }.onFailure { model.clearVerification() }
                                delay(5000)
                            }
                        } }
                        try {
                            sensor.start()
                            awaitTelemetry(sensor, candidate)
                            emit(sensor)
                            throw BikeServiceLostException(bound.awaitLoss())
                        } finally {
                            verification?.cancel()
                            localCalibration?.clearVerification()
                            sensor.stop()
                        }
                    }
                    V1BikeService.SensorData -> {
                        val sensor = SensorDataSensor(binder) {
                            diagnostics.capture("SensorData connected at ${candidate.component}, but a full read cycle returned no valid telemetry")
                        }
                        val polling = launch { sensor.run() }
                        try {
                            awaitTelemetry(sensor, candidate)
                            emit(sensor)
                            throw BikeServiceLostException(bound.awaitLoss())
                        } finally {
                            polling.cancel()
                            sensor.close()
                        }
                    }
                }
            }
        } finally {
            binding?.close()
        }
    }

    private suspend fun awaitTelemetry(sensor: SensorInterface, candidate: V1ServiceCandidate) {
        withTimeout(6_000) { combine(sensor.power, sensor.cadence, sensor.resistance) { _, _, _ -> Unit }.first() }
        diagnostics.record("Telemetry verified from ${candidate.component}: power, cadence, resistance")
    }
    override val power: Flow<Float> = combinedSensorState.flatMapLatest { it.power }.shareLatest()
    override val nativePower: Flow<Float> = combinedSensorState.flatMapLatest { it.nativePower }.shareLatest()
    override val displayPower: Flow<com.spop.poverlay.sensor.power.PowerDisplay> =
        combinedSensorState.flatMapLatest { it.displayPower }.shareLatest()

    override val cadence: Flow<Float> = combinedSensorState.flatMapLatest { it.cadence }
        .shareLatest()

    override val resistance: Flow<Float> = combinedSensorState.flatMapLatest { it.resistance }
        .windowed(ResistanceMovingAverageWindowSize, 1, true) { readings ->
            // Resistance sensor occasionally spikes for a single reading
            // So take the least of the last few readings
            readings.minOf { it }
        }
        .shareLatest()

    private fun <T> Flow<T>.shareLatest() = buffer(64, BufferOverflow.DROP_OLDEST)
        .shareIn(this@PelotonBikeSensorInterfaceV1New, SharingStarted.WhileSubscribed(), 1)
}
