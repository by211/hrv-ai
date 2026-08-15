package quest.byai.hrv.sensor

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.polar.androidcommunications.api.ble.model.DisInfo
import quest.byai.hrv.domain.HeartRateSample
import quest.byai.hrv.domain.SensorConnectionState
import quest.byai.hrv.domain.SensorDevice
import com.polar.sdk.api.PolarBleApi
import com.polar.sdk.api.PolarBleApiCallback
import com.polar.sdk.api.PolarBleApiDefaultImpl
import com.polar.sdk.api.model.PolarDeviceInfo
import com.polar.sdk.api.model.PolarHealthThermometerData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class PolarHeartRateSensor(context: Context) : HeartRateSensor {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val api = PolarBleApiDefaultImpl.defaultImplementation(
        context.applicationContext,
        setOf(
            PolarBleApi.PolarBleSdkFeature.FEATURE_HR,
            PolarBleApi.PolarBleSdkFeature.FEATURE_BATTERY_INFO,
            PolarBleApi.PolarBleSdkFeature.FEATURE_DEVICE_INFO,
        ),
    )

    private val mutableConnectionState = MutableStateFlow(SensorConnectionState.DISCONNECTED)
    override val connectionState = mutableConnectionState.asStateFlow()

    private val mutableSamples = MutableSharedFlow<HeartRateSample>(extraBufferCapacity = 128)
    override val samples = mutableSamples.asSharedFlow()

    private val mutableBatteryLevel = MutableStateFlow<Int?>(null)
    override val batteryLevel = mutableBatteryLevel.asStateFlow()

    private val mutableErrorMessage = MutableStateFlow<String?>(null)
    override val errorMessage = mutableErrorMessage.asStateFlow()

    private val mutableDiagnostics = MutableStateFlow(SensorDiagnostics())
    override val diagnostics = mutableDiagnostics.asStateFlow()

    private var connectedDeviceId: String? = null
    private var streamStartedForDeviceId: String? = null
    private var heartRateStreamJob: Job? = null
    private var reconnectJob: Job? = null

    init {
        api.setAutomaticReconnection(true)
        api.setApiLogger { message ->
            Log.d(SDK_LOG_TAG, message)
            if (
                message.contains("error", ignoreCase = true) ||
                message.contains("fail", ignoreCase = true) ||
                message.contains("disconnect", ignoreCase = true) ||
                message.contains("gatt", ignoreCase = true)
            ) {
                mutableDiagnostics.value = mutableDiagnostics.value.copy(
                    lastSdkLog = message.take(MAX_DIAGNOSTIC_MESSAGE_LENGTH),
                )
            }
        }
        api.setApiCallback(object : PolarBleApiCallback() {
            override fun blePowerStateChanged(powered: Boolean) {
                if (powered) recordEvent("Bluetooth powered on")
                else recordError("Bluetooth powered off")
            }

            override fun deviceConnecting(polarDeviceInfo: PolarDeviceInfo) {
                mutableConnectionState.value = SensorConnectionState.CONNECTING
                mutableDiagnostics.value = mutableDiagnostics.value.copy(
                    selectedDeviceId = polarDeviceInfo.deviceId,
                    selectedDeviceName = polarDeviceInfo.name,
                    lastEvent = "Connecting to sensor",
                )
                recordEvent("Connecting to sensor", polarDeviceInfo.deviceId)
            }

            override fun deviceConnected(polarDeviceInfo: PolarDeviceInfo) {
                connectedDeviceId = polarDeviceInfo.deviceId
                mutableConnectionState.value = SensorConnectionState.CONNECTED
                mutableErrorMessage.value = null
                mutableDiagnostics.value = mutableDiagnostics.value.copy(
                    selectedDeviceId = polarDeviceInfo.deviceId,
                    selectedDeviceName = polarDeviceInfo.name,
                    rrStreamConfirmed = false,
                    lastEvent = "Bluetooth connected; waiting for heart-rate service",
                    lastError = null,
                    lastErrorDetails = null,
                )
                recordEvent("Bluetooth connected; waiting for heart-rate service", polarDeviceInfo.deviceId)
            }

            override fun deviceDisconnected(polarDeviceInfo: PolarDeviceInfo) {
                streamStartedForDeviceId = null
                val recovering = connectedDeviceId != null
                mutableConnectionState.value = if (!recovering) {
                    SensorConnectionState.DISCONNECTED
                } else {
                    SensorConnectionState.RECOVERING
                }
                mutableDiagnostics.value = mutableDiagnostics.value.copy(
                    rrStreamConfirmed = false,
                    recoveryCount = mutableDiagnostics.value.recoveryCount + if (recovering) 1 else 0,
                    lastEvent = if (recovering) "Sensor disconnected; automatic recovery requested" else "Sensor disconnected",
                )
                recordEvent(mutableDiagnostics.value.lastEvent, polarDeviceInfo.deviceId)
            }

            override fun batteryLevelReceived(identifier: String, level: Int) {
                mutableBatteryLevel.value = level
                recordEvent("Battery level received: $level%", identifier)
            }

            override fun disInformationReceived(identifier: String, disInfo: DisInfo) {
                recordEvent("Device information received: ${disInfo.key}", identifier)
            }

            override fun htsNotificationReceived(
                identifier: String,
                data: PolarHealthThermometerData,
            ) = Unit

            override fun bleSdkFeatureReady(
                identifier: String,
                feature: PolarBleApi.PolarBleSdkFeature,
            ) {
                if (feature == PolarBleApi.PolarBleSdkFeature.FEATURE_HR) {
                    recordEvent("Heart-rate service ready", identifier)
                    startHrStream(identifier)
                }
            }

            override fun bleSdkFeaturesReadiness(
                identifier: String,
                ready: List<PolarBleApi.PolarBleSdkFeature>,
                unavailable: List<PolarBleApi.PolarBleSdkFeature>,
            ) {
                if (PolarBleApi.PolarBleSdkFeature.FEATURE_HR in ready) startHrStream(identifier)
                if (PolarBleApi.PolarBleSdkFeature.FEATURE_HR in unavailable) {
                    recordError(
                        fallbackMessage = "This device does not provide the BLE heart-rate service",
                        deviceId = identifier,
                    )
                }
            }
        })
    }

    override fun scan(): Flow<SensorDevice> {
        mutableConnectionState.value = SensorConnectionState.SCANNING
        mutableErrorMessage.value = null
        recordEvent("Scanning for Polar sensors")
        return api.searchForDevice("Polar")
            .map { device ->
                recordEvent("Found ${device.name} at ${device.rssi} dBm", device.deviceId)
                SensorDevice(
                    deviceId = device.deviceId,
                    name = device.name,
                    rssi = device.rssi,
                    isConnectable = device.isConnectable,
                )
            }
            .catch { error ->
                recordError("Unable to scan for sensors", error = error)
                throw error
            }
    }

    override fun connect(deviceId: String) {
        reconnectJob?.cancel()
        requestConnection(deviceId, "Connection requested")
    }

    override fun reconnect(deviceId: String) {
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            mutableConnectionState.value = SensorConnectionState.RECOVERING
            mutableErrorMessage.value = null
            recordEvent("Resetting Bluetooth connection", deviceId)
            api.setAutomaticReconnection(false)
            try {
                connectedDeviceId = null
                streamStartedForDeviceId = null
                heartRateStreamJob?.cancelAndJoin()
                heartRateStreamJob = null
                runCatching { api.disconnectFromDevice(deviceId) }
                    .onFailure { Log.d(LOG_TAG, "No active connection to close [device=$deviceId]", it) }
                delay(RECONNECT_RESET_DELAY_MS)
            } finally {
                api.setAutomaticReconnection(true)
            }
            requestConnection(deviceId, "Reset complete; connection requested")
        }
    }

    private fun requestConnection(deviceId: String, event: String) {
        try {
            mutableConnectionState.value = SensorConnectionState.CONNECTING
            mutableErrorMessage.value = null
            connectedDeviceId = deviceId
            mutableDiagnostics.value = SensorDiagnostics(
                selectedDeviceId = deviceId,
                recoveryCount = mutableDiagnostics.value.recoveryCount,
                lastEvent = event,
            )
            recordEvent(event, deviceId)
            api.connectToDevice(deviceId)
        } catch (error: Exception) {
            recordError("Unable to connect to $deviceId", deviceId, error)
        }
    }

    override fun stopScan() {
        if (mutableConnectionState.value == SensorConnectionState.SCANNING) {
            mutableConnectionState.value = SensorConnectionState.DISCONNECTED
            recordEvent("Sensor scan stopped")
        }
    }

    override fun disconnect() {
        reconnectJob?.cancel()
        reconnectJob = null
        heartRateStreamJob?.cancel()
        heartRateStreamJob = null
        val deviceId = connectedDeviceId
        connectedDeviceId = null
        streamStartedForDeviceId = null
        if (deviceId != null) runCatching { api.disconnectFromDevice(deviceId) }
        mutableConnectionState.value = SensorConnectionState.DISCONNECTED
        mutableDiagnostics.value = mutableDiagnostics.value.copy(
            rrStreamConfirmed = false,
            lastEvent = "Disconnected by user",
        )
        recordEvent("Disconnected by user", deviceId)
    }

    override fun close() {
        disconnect()
        scope.cancel()
        api.shutDown()
    }

    private fun startHrStream(deviceId: String) {
        if (streamStartedForDeviceId == deviceId) return
        streamStartedForDeviceId = deviceId
        recordEvent("Starting heart-rate stream", deviceId)
        heartRateStreamJob = scope.launch {
            api.startHrStreaming(deviceId)
                .catch { error ->
                    streamStartedForDeviceId = null
                    recordError("Heart-rate stream stopped", deviceId, error, recovering = true)
                }
                .collect { data ->
                    mutableConnectionState.value = SensorConnectionState.STREAMING
                    data.samples.forEach { sample ->
                        val receivedElapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
                        val diagnostics = mutableDiagnostics.value
                        val heartRateSample = HeartRateSample(
                            deviceId = deviceId,
                            receivedElapsedRealtimeNanos = receivedElapsedRealtimeNanos,
                            heartRateBpm = sample.hr,
                            rrIntervalsMs = sample.rrsMs,
                            rrAvailable = sample.rrAvailable,
                            contactStatus = sample.contactStatus.takeIf { sample.contactStatusSupported },
                        )
                        mutableDiagnostics.value = diagnostics.withSample(heartRateSample)
                        val hasRrIntervals = heartRateSample.rrAvailable && heartRateSample.rrIntervalsMs.isNotEmpty()
                        if (diagnostics.heartRateSampleCount == 0L || (!diagnostics.rrStreamConfirmed && hasRrIntervals)) {
                            recordEvent(mutableDiagnostics.value.lastEvent, deviceId)
                        }
                        mutableSamples.emit(heartRateSample)
                    }
                }
        }
    }

    private fun recordEvent(message: String, deviceId: String? = connectedDeviceId) {
        mutableDiagnostics.value = mutableDiagnostics.value.copy(lastEvent = message)
        Log.d(LOG_TAG, "$message${deviceId?.let { " [device=$it]" }.orEmpty()}")
    }

    private fun recordError(
        fallbackMessage: String,
        deviceId: String? = connectedDeviceId,
        error: Throwable? = null,
        recovering: Boolean = false,
    ) {
        val failure = error?.toSensorFailure(fallbackMessage)
        val message = failure?.userMessage ?: fallbackMessage
        mutableConnectionState.value = if (recovering) SensorConnectionState.RECOVERING else SensorConnectionState.FAILED
        mutableErrorMessage.value = message
        mutableDiagnostics.value = mutableDiagnostics.value.copy(
            rrStreamConfirmed = false,
            lastEvent = message,
            lastError = message,
            lastErrorDetails = failure?.technicalDetails,
        )
        Log.e(LOG_TAG, "$message${deviceId?.let { " [device=$it]" }.orEmpty()}", error)
    }

    private companion object {
        const val LOG_TAG = "PolarHeartRateSensor"
        const val SDK_LOG_TAG = "PolarBleSdk"
        const val MAX_DIAGNOSTIC_MESSAGE_LENGTH = 500
        const val RECONNECT_RESET_DELAY_MS = 1_000L
    }
}
