package quest.byai.hrv.sensor

import quest.byai.hrv.domain.HeartRateSample
import quest.byai.hrv.domain.SensorConnectionState
import quest.byai.hrv.domain.SensorDevice
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

data class SensorDiagnostics(
    val selectedDeviceId: String? = null,
    val selectedDeviceName: String? = null,
    val heartRateSampleCount: Long = 0,
    val rrIntervalCount: Long = 0,
    val lastHeartRateBpm: Int? = null,
    val lastRrMs: Int? = null,
    val lastSampleElapsedRealtimeMs: Long? = null,
    val contactStatus: Boolean? = null,
    val rrStreamConfirmed: Boolean = false,
    val recoveryCount: Int = 0,
    val lastEvent: String = "Sensor idle",
    val lastError: String? = null,
) {
    fun withSample(sample: HeartRateSample): SensorDiagnostics {
        val hasRrIntervals = sample.rrAvailable && sample.rrIntervalsMs.isNotEmpty()
        return copy(
            selectedDeviceId = sample.deviceId,
            heartRateSampleCount = heartRateSampleCount + 1,
            rrIntervalCount = rrIntervalCount + sample.rrIntervalsMs.size,
            lastHeartRateBpm = sample.heartRateBpm,
            lastRrMs = sample.rrIntervalsMs.lastOrNull() ?: lastRrMs,
            lastSampleElapsedRealtimeMs = sample.receivedElapsedRealtimeNanos / 1_000_000L,
            contactStatus = sample.contactStatus,
            rrStreamConfirmed = rrStreamConfirmed || hasRrIntervals,
            lastEvent = if (hasRrIntervals) {
                "Heart rate and R-R data received"
            } else {
                "Heart rate received; waiting for R-R data"
            },
            lastError = null,
        )
    }
}

interface HeartRateSensor {
    val connectionState: StateFlow<SensorConnectionState>
    val samples: Flow<HeartRateSample>
    val batteryLevel: StateFlow<Int?>
    val errorMessage: StateFlow<String?>
    val diagnostics: StateFlow<SensorDiagnostics>

    fun scan(): Flow<SensorDevice>
    fun stopScan()
    fun connect(deviceId: String)
    fun disconnect()
    fun close()
}
