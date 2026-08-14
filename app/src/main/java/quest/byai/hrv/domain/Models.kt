package quest.byai.hrv.domain

enum class SensorConnectionState {
    DISCONNECTED,
    SCANNING,
    CONNECTING,
    CONNECTED,
    STREAMING,
    RECOVERING,
    FAILED,
}

data class SensorDevice(
    val deviceId: String,
    val name: String,
    val rssi: Int,
    val isConnectable: Boolean,
)

data class HeartRateSample(
    val deviceId: String,
    val receivedElapsedRealtimeNanos: Long,
    val heartRateBpm: Int,
    val rrIntervalsMs: List<Int>,
    val rrAvailable: Boolean,
    val contactStatus: Boolean?,
)

enum class RrQualityFlag {
    OUT_OF_RANGE,
    ABRUPT_DEVIATION,
    CONTACT_LOST,
    BLE_GAP,
}

data class RrSample(
    val elapsedRealtimeMs: Long,
    val rawRrMs: Int,
    val analysisRrMs: Double? = rawRrMs.toDouble(),
    val qualityFlags: Set<RrQualityFlag> = emptySet(),
) {
    val isUsable: Boolean get() = analysisRrMs != null && qualityFlags.isEmpty()
}

data class BreathingCue(
    val breathsPerMinute: Double,
    val inhaleFraction: Double = 0.5,
) {
    init {
        require(breathsPerMinute > 0.0)
        require(inhaleFraction in 0.2..0.8)
    }

    val cycleDurationMs: Long get() = (60_000.0 / breathsPerMinute).toLong()
    val inhaleDurationMs: Long get() = (cycleDurationMs * inhaleFraction).toLong()
    val exhaleDurationMs: Long get() = cycleDurationMs - inhaleDurationMs
}

data class ResonanceObservation(
    val breathsPerMinute: Double,
    val usableDataFraction: Double,
    val durationSeconds: Double,
    val targetAmplitudeBpm: Double,
    val waveformRegularity: Double,
    val spectralConcentration: Double,
    val dominantFrequencyHz: Double,
    val frequencyErrorHz: Double,
    val peakToTroughBpm: Double,
    val rmssdMs: Double,
    val sdnnMs: Double,
    val score: Double,
    val confidence: Double,
    val isQualified: Boolean,
    val rejectionReason: String? = null,
)

enum class SessionType {
    FIXED,
    CALIBRATION,
    ADAPTIVE,
}

enum class SessionStatus {
    PREPARING,
    SETTLING,
    GUIDING,
    SIGNAL_PAUSED,
    USER_PAUSED,
    COMPLETE,
    CANCELLED,
}

data class UserFeedback(
    val ease: Int,
    val dizzy: Boolean = false,
    val tingling: Boolean = false,
    val airHunger: Boolean = false,
    val poundingHeart: Boolean = false,
    val otherDiscomfort: Boolean = false,
) {
    val isComfortable: Boolean
        get() = ease >= 3 && !dizzy && !tingling && !airHunger && !poundingHeart && !otherDiscomfort
}
