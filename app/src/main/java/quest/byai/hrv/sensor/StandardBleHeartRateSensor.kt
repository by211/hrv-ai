package quest.byai.hrv.sensor

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import android.os.SystemClock
import android.util.Log
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import quest.byai.hrv.domain.HeartRateSample
import quest.byai.hrv.domain.SensorConnectionState
import quest.byai.hrv.domain.SensorDevice

@SuppressLint("MissingPermission")
class StandardBleHeartRateSensor(context: Context) : HeartRateSensor {
    private val applicationContext = context.applicationContext
    private val bluetoothManager = applicationContext.getSystemService(BluetoothManager::class.java)
    private val bluetoothAdapter get() = bluetoothManager.adapter
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val discoveredDevices = ConcurrentHashMap<String, BluetoothDevice>()
    private val connectionLock = Any()

    private val mutableConnectionState = MutableStateFlow(SensorConnectionState.DISCONNECTED)
    override val connectionState = mutableConnectionState.asStateFlow()

    private val mutableSamples = MutableSharedFlow<HeartRateSample>(extraBufferCapacity = SAMPLE_BUFFER_CAPACITY)
    override val samples = mutableSamples.asSharedFlow()

    private val mutableBatteryLevel = MutableStateFlow<Int?>(null)
    override val batteryLevel = mutableBatteryLevel.asStateFlow()

    private val mutableErrorMessage = MutableStateFlow<String?>(null)
    override val errorMessage = mutableErrorMessage.asStateFlow()

    private val mutableDiagnostics = MutableStateFlow(SensorDiagnostics())
    override val diagnostics = mutableDiagnostics.asStateFlow()

    private var activeGatt: BluetoothGatt? = null
    private var activeGeneration = 0L
    private var targetDevice: BluetoothDevice? = null
    private var connectionJob: Job? = null
    private var sampleWatchdogJob: Job? = null
    private var recoveryAttempt = 0

    override fun scan(): Flow<SensorDevice> = callbackFlow {
        val adapter = bluetoothAdapter
        val scanner = adapter.bluetoothLeScanner
        if (!adapter.isEnabled || scanner == null) {
            val error = IllegalStateException("Bluetooth is off or unavailable")
            recordError(error.message.orEmpty(), error.javaClass.simpleName)
            close(error)
            return@callbackFlow
        }

        mutableConnectionState.value = SensorConnectionState.SCANNING
        mutableErrorMessage.value = null
        recordEvent("Scanning for Bluetooth heart-rate sensors")
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val device = result.device
                val deviceId = device.address
                discoveredDevices[deviceId] = device
                val deviceName = result.scanRecord?.deviceName
                    ?: runCatching { device.name }.getOrNull()
                    ?: "Heart-rate sensor"
                trySend(
                    SensorDevice(
                        deviceId = deviceId,
                        name = deviceName,
                        rssi = result.rssi,
                        isConnectable = result.isConnectable,
                    ),
                )
                recordBleEvent("Found $deviceName at ${result.rssi} dBm", deviceId)
            }

            override fun onScanFailed(errorCode: Int) {
                val details = "Android BLE scan error $errorCode"
                recordError("Unable to scan for sensors", details)
                close(IllegalStateException(details))
            }
        }
        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(HEART_RATE_SERVICE_UUID))
            .build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        runCatching { scanner.startScan(listOf(filter), settings, callback) }
            .onFailure { error ->
                recordError("Unable to start sensor scan", error.diagnosticDescription())
                close(error)
            }
        awaitClose {
            runCatching { scanner.stopScan(callback) }
            if (mutableConnectionState.value == SensorConnectionState.SCANNING) {
                mutableConnectionState.value = SensorConnectionState.DISCONNECTED
                recordEvent("Sensor scan stopped")
            }
        }
    }

    override fun stopScan() {
        if (mutableConnectionState.value == SensorConnectionState.SCANNING) {
            mutableConnectionState.value = SensorConnectionState.DISCONNECTED
            recordEvent("Sensor scan stopping")
        }
    }

    override fun connect(deviceId: String) {
        val device = resolveDevice(deviceId) ?: return
        recoveryAttempt = 0
        beginFreshConnection(device, "Connection requested", countAsRecovery = false)
    }

    override fun reconnect(deviceId: String) {
        val device = resolveDevice(deviceId) ?: return
        recoveryAttempt = 0
        beginFreshConnection(device, "Resetting and reconnecting", countAsRecovery = true)
    }

    override fun disconnect() {
        connectionJob?.cancel()
        connectionJob = null
        sampleWatchdogJob?.cancel()
        sampleWatchdogJob = null
        targetDevice = null
        recoveryAttempt = 0
        closeActiveGatt()
        mutableConnectionState.value = SensorConnectionState.DISCONNECTED
        mutableErrorMessage.value = null
        mutableDiagnostics.value = mutableDiagnostics.value.copy(
            rrStreamConfirmed = false,
            lastEvent = "Disconnected by user",
            lastError = null,
            lastErrorDetails = null,
        )
        recordEvent("Disconnected by user")
    }

    override fun close() {
        disconnect()
        scope.cancel()
    }

    private fun resolveDevice(deviceId: String): BluetoothDevice? {
        discoveredDevices[deviceId]?.let { return it }
        if (!android.bluetooth.BluetoothAdapter.checkBluetoothAddress(deviceId)) {
            recordError(
                message = "Saved sensor address is from the previous connection method; scan and select the sensor once",
                details = "Invalid Bluetooth address: $deviceId",
            )
            return null
        }
        return runCatching { bluetoothAdapter.getRemoteDevice(deviceId) }
            .onFailure { recordError("Unable to access saved sensor", it.diagnosticDescription()) }
            .getOrNull()
    }

    private fun beginFreshConnection(device: BluetoothDevice, event: String, countAsRecovery: Boolean) {
        connectionJob?.cancel()
        connectionJob = scope.launch {
            targetDevice = device
            mutableConnectionState.value = if (countAsRecovery) {
                SensorConnectionState.RECOVERING
            } else {
                SensorConnectionState.CONNECTING
            }
            mutableErrorMessage.value = null
            val deviceName = runCatching { device.name }.getOrNull() ?: "Heart-rate sensor"
            val priorRecoveryCount = mutableDiagnostics.value.recoveryCount
            mutableDiagnostics.value = SensorDiagnostics(
                selectedDeviceId = device.address,
                selectedDeviceName = deviceName,
                recoveryCount = priorRecoveryCount + if (countAsRecovery) 1 else 0,
                lastEvent = event,
            )
            recordBleEvent(event, device.address)
            val hadActiveGatt = closeActiveGatt()
            if (hadActiveGatt) delay(GATT_RESET_DELAY_MS)
            if (targetDevice?.address == device.address) openGatt(device)
        }
    }

    private fun openGatt(device: BluetoothDevice) {
        val generation = synchronized(connectionLock) {
            activeGeneration += 1
            activeGeneration
        }
        mutableConnectionState.value = SensorConnectionState.CONNECTING
        recordBleEvent("Opening fresh GATT connection", device.address)
        val callback = createGattCallback(generation, device)
        val gatt = runCatching {
            device.connectGatt(applicationContext, false, callback, BluetoothDevice.TRANSPORT_LE)
        }.onFailure {
            recordError("Unable to open Bluetooth connection", it.diagnosticDescription())
        }.getOrNull() ?: return

        synchronized(connectionLock) {
            if (activeGeneration == generation) {
                activeGatt = gatt
            } else {
                gatt.close()
            }
        }
    }

    private fun createGattCallback(generation: Long, device: BluetoothDevice) = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (!isCurrent(generation)) {
                gatt.close()
                return
            }
            val statusDetails = "GATT connection state=$newState status=$status"
            recordBleEvent(statusDetails, device.address)
            when {
                status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED -> {
                    mutableConnectionState.value = SensorConnectionState.CONNECTED
                    mutableErrorMessage.value = null
                    mutableDiagnostics.value = mutableDiagnostics.value.copy(
                        selectedDeviceId = device.address,
                        selectedDeviceName = runCatching { device.name }.getOrNull() ?: "Heart-rate sensor",
                        rrStreamConfirmed = false,
                        lastEvent = "Bluetooth connected; discovering heart-rate service",
                        lastError = null,
                        lastErrorDetails = null,
                    )
                    if (!gatt.discoverServices()) {
                        recoverFromFailure(device, "Service discovery could not start", statusDetails)
                    }
                }

                newState == BluetoothProfile.STATE_DISCONNECTED -> {
                    clearGattIfCurrent(generation, gatt)
                    recoverFromFailure(device, "Sensor disconnected", statusDetails)
                }

                status != BluetoothGatt.GATT_SUCCESS -> {
                    clearGattIfCurrent(generation, gatt)
                    recoverFromFailure(device, "Bluetooth connection failed", statusDetails)
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (!isCurrent(generation)) return
            val details = "GATT services discovered status=$status"
            recordBleEvent(details, device.address)
            if (status != BluetoothGatt.GATT_SUCCESS) {
                recoverFromFailure(device, "Unable to discover sensor services", details)
                return
            }

            val heartRateService = gatt.getService(HEART_RATE_SERVICE_UUID)
            val heartRateCharacteristic = heartRateService?.getCharacteristic(HEART_RATE_MEASUREMENT_UUID)
            val notificationDescriptor = heartRateCharacteristic?.getDescriptor(CLIENT_CHARACTERISTIC_CONFIG_UUID)
            if (heartRateCharacteristic == null || notificationDescriptor == null) {
                recordError(
                    "This sensor does not expose standard heart-rate notifications",
                    "Missing service $HEART_RATE_SERVICE_UUID, characteristic $HEART_RATE_MEASUREMENT_UUID, or CCCD",
                )
                closeActiveGatt()
                return
            }
            if (!gatt.setCharacteristicNotification(heartRateCharacteristic, true)) {
                recoverFromFailure(device, "Unable to enable heart-rate notifications", "setCharacteristicNotification returned false")
                return
            }
            val writeResult = gatt.writeDescriptor(
                notificationDescriptor,
                BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE,
            )
            if (writeResult != BluetoothStatusCodes.SUCCESS) {
                recoverFromFailure(
                    device,
                    "Unable to subscribe to heart-rate notifications",
                    "CCCD write start result=$writeResult",
                )
            } else {
                recordBleEvent("Enabling standard heart-rate notifications", device.address)
            }
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (!isCurrent(generation) || descriptor.uuid != CLIENT_CHARACTERISTIC_CONFIG_UUID) return
            val details = "Heart-rate notification descriptor status=$status"
            recordBleEvent(details, device.address)
            if (status != BluetoothGatt.GATT_SUCCESS) {
                recoverFromFailure(device, "Heart-rate notification subscription failed", details)
                return
            }
            mutableConnectionState.value = SensorConnectionState.CONNECTED
            mutableDiagnostics.value = mutableDiagnostics.value.copy(
                lastEvent = "Heart-rate notifications enabled; waiting for samples",
                lastError = null,
                lastErrorDetails = null,
            )
            startSampleWatchdog(generation, device)
            gatt.getService(BATTERY_SERVICE_UUID)
                ?.getCharacteristic(BATTERY_LEVEL_UUID)
                ?.let(gatt::readCharacteristic)
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            if (!isCurrent(generation) || characteristic.uuid != HEART_RATE_MEASUREMENT_UUID) return
            val measurement = HeartRateMeasurementParser.parse(value)
            if (measurement == null) {
                recordError("Invalid heart-rate notification", "Payload bytes=${value.size}")
                return
            }
            recoveryAttempt = 0
            mutableConnectionState.value = SensorConnectionState.STREAMING
            val sample = HeartRateSample(
                deviceId = device.address,
                receivedElapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos(),
                heartRateBpm = measurement.heartRateBpm,
                rrIntervalsMs = measurement.rrIntervalsMs,
                rrAvailable = measurement.rrAvailable,
                contactStatus = measurement.contactStatus,
            )
            val previousDiagnostics = mutableDiagnostics.value
            mutableDiagnostics.value = previousDiagnostics.withSample(sample)
            if (
                previousDiagnostics.heartRateSampleCount == 0L ||
                (!previousDiagnostics.rrStreamConfirmed && measurement.rrIntervalsMs.isNotEmpty())
            ) {
                recordBleEvent(mutableDiagnostics.value.lastEvent, device.address)
            }
            if (!mutableSamples.tryEmit(sample)) {
                recordError("Heart-rate sample buffer is full", "Dropped notification from ${device.address}")
            }
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int,
        ) {
            if (!isCurrent(generation) || characteristic.uuid != BATTERY_LEVEL_UUID) return
            if (status == BluetoothGatt.GATT_SUCCESS) {
                mutableBatteryLevel.value = value.firstOrNull()?.toInt()?.and(0xFF)
                recordBleEvent("Battery level received", device.address)
            } else {
                recordBleEvent("Battery read failed status=$status", device.address)
            }
        }
    }

    private fun startSampleWatchdog(generation: Long, device: BluetoothDevice) {
        sampleWatchdogJob?.cancel()
        val notificationStartMs = SystemClock.elapsedRealtime()
        sampleWatchdogJob = scope.launch {
            while (isActive && isCurrent(generation)) {
                delay(WATCHDOG_CHECK_INTERVAL_MS)
                val lastSampleMs = mutableDiagnostics.value.lastSampleElapsedRealtimeMs ?: notificationStartMs
                val sampleAgeMs = SystemClock.elapsedRealtime() - lastSampleMs
                if (sampleAgeMs >= SAMPLE_TIMEOUT_MS) {
                    recoverFromFailure(
                        device,
                        "Heart-rate notifications stopped",
                        "No sample received for ${sampleAgeMs / 1_000L} seconds",
                    )
                    break
                }
            }
        }
    }

    private fun recoverFromFailure(device: BluetoothDevice, message: String, details: String) {
        if (targetDevice?.address != device.address) return
        sampleWatchdogJob?.cancel()
        sampleWatchdogJob = null
        if (recoveryAttempt >= MAX_AUTOMATIC_RECOVERY_ATTEMPTS) {
            recordError(
                "$message after $MAX_AUTOMATIC_RECOVERY_ATTEMPTS recovery attempts",
                details,
            )
            closeActiveGatt()
            return
        }

        recoveryAttempt += 1
        val delayMs = RECOVERY_DELAYS_MS[recoveryAttempt - 1]
        mutableConnectionState.value = SensorConnectionState.RECOVERING
        mutableErrorMessage.value = "$message; retrying"
        mutableDiagnostics.value = mutableDiagnostics.value.copy(
            rrStreamConfirmed = false,
            recoveryCount = mutableDiagnostics.value.recoveryCount + 1,
            lastEvent = "$message; recovery $recoveryAttempt/$MAX_AUTOMATIC_RECOVERY_ATTEMPTS",
            lastError = message,
            lastErrorDetails = details,
            lastBleEvent = details,
        )
        Log.w(LOG_TAG, "$message; retrying in ${delayMs}ms [device=${device.address}] [$details]")
        connectionJob?.cancel()
        connectionJob = scope.launch {
            closeActiveGatt()
            delay(GATT_RESET_DELAY_MS + delayMs)
            if (targetDevice?.address == device.address) openGatt(device)
        }
    }

    private fun closeActiveGatt(): Boolean {
        sampleWatchdogJob?.cancel()
        sampleWatchdogJob = null
        val gatt = synchronized(connectionLock) {
            activeGeneration += 1
            activeGatt.also { activeGatt = null }
        } ?: return false
        runCatching { gatt.disconnect() }
        runCatching { gatt.close() }
        recordBleEvent("Closed previous GATT connection")
        return true
    }

    private fun clearGattIfCurrent(generation: Long, gatt: BluetoothGatt) {
        synchronized(connectionLock) {
            if (activeGeneration == generation) {
                activeGeneration += 1
                if (activeGatt === gatt) activeGatt = null
            }
        }
        runCatching { gatt.close() }
    }

    private fun isCurrent(generation: Long): Boolean = synchronized(connectionLock) {
        activeGeneration == generation
    }

    private fun recordEvent(message: String, deviceId: String? = targetDevice?.address) {
        mutableDiagnostics.value = mutableDiagnostics.value.copy(lastEvent = message)
        Log.d(LOG_TAG, "$message${deviceId?.let { " [device=$it]" }.orEmpty()}")
    }

    private fun recordBleEvent(message: String, deviceId: String? = targetDevice?.address) {
        mutableDiagnostics.value = mutableDiagnostics.value.copy(
            lastEvent = message,
            lastBleEvent = message.take(MAX_DIAGNOSTIC_MESSAGE_LENGTH),
        )
        Log.d(LOG_TAG, "$message${deviceId?.let { " [device=$it]" }.orEmpty()}")
    }

    private fun recordError(message: String, details: String? = null) {
        mutableConnectionState.value = SensorConnectionState.FAILED
        mutableErrorMessage.value = message
        mutableDiagnostics.value = mutableDiagnostics.value.copy(
            rrStreamConfirmed = false,
            lastEvent = message,
            lastError = message,
            lastErrorDetails = details,
            lastBleEvent = details?.take(MAX_DIAGNOSTIC_MESSAGE_LENGTH) ?: mutableDiagnostics.value.lastBleEvent,
        )
        Log.e(LOG_TAG, "$message${details?.let { ": $it" }.orEmpty()}")
    }

    private fun Throwable.diagnosticDescription(): String {
        val type = javaClass.simpleName.ifBlank { javaClass.name.substringAfterLast('.') }
        return message?.takeIf(String::isNotBlank)?.let { "$type: $it" } ?: type
    }

    private companion object {
        val HEART_RATE_SERVICE_UUID: UUID = UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb")
        val HEART_RATE_MEASUREMENT_UUID: UUID = UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb")
        val BATTERY_SERVICE_UUID: UUID = UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb")
        val BATTERY_LEVEL_UUID: UUID = UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb")
        val CLIENT_CHARACTERISTIC_CONFIG_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        val RECOVERY_DELAYS_MS = longArrayOf(0L, 1_000L, 3_000L)

        const val LOG_TAG = "BleHeartRateSensor"
        const val SAMPLE_BUFFER_CAPACITY = 128
        const val MAX_AUTOMATIC_RECOVERY_ATTEMPTS = 3
        const val MAX_DIAGNOSTIC_MESSAGE_LENGTH = 500
        const val GATT_RESET_DELAY_MS = 750L
        const val WATCHDOG_CHECK_INTERVAL_MS = 3_000L
        const val SAMPLE_TIMEOUT_MS = 12_000L
    }
}
