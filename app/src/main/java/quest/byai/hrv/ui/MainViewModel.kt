package quest.byai.hrv.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import quest.byai.hrv.AppContainer
import quest.byai.hrv.data.SessionEntity
import quest.byai.hrv.data.UserSettings
import quest.byai.hrv.domain.SensorConnectionState
import quest.byai.hrv.domain.SensorDevice
import quest.byai.hrv.domain.SessionStatus
import quest.byai.hrv.domain.SessionType
import quest.byai.hrv.domain.UserFeedback
import quest.byai.hrv.session.SessionEngine
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

enum class AppScreen {
    HOME,
    SENSOR,
    SESSION_SETUP,
    CALIBRATION,
    SESSION,
    SUMMARY,
    HISTORY,
    SETTINGS,
}

data class SessionDraft(
    val type: SessionType = SessionType.FIXED,
    val durationSeconds: Long = 600,
    val rate: Double = 6.0,
    val inhaleFraction: Double = 0.5,
)

class MainViewModel(private val container: AppContainer) : ViewModel() {
    private val engine = SessionEngine(container.repository, container.preferences)
    val session = engine.snapshot
    val connectionState = container.sensor.connectionState
    val batteryLevel = container.sensor.batteryLevel
    val sensorError = container.sensor.errorMessage
    val sensorDiagnostics = container.sensor.diagnostics
    val settings = container.preferences.settings.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        UserSettings(),
    )
    val sessions = container.repository.sessions.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        emptyList(),
    )

    private val mutableScreen = MutableStateFlow(AppScreen.HOME)
    val screen = mutableScreen.asStateFlow()

    private val mutableDevices = MutableStateFlow<List<SensorDevice>>(emptyList())
    val devices = mutableDevices.asStateFlow()

    private val mutableDraft = MutableStateFlow(SessionDraft())
    val draft = mutableDraft.asStateFlow()

    private var scanJob: Job? = null

    init {
        viewModelScope.launch { container.repository.cancelInterruptedSessions() }
        viewModelScope.launch {
            container.sensor.samples.collect { sample -> engine.onHeartRateSample(sample) }
        }
        viewModelScope.launch {
            while (isActive) {
                engine.tick()
                delay(250)
            }
        }
        viewModelScope.launch {
            engine.snapshot.collect { snapshot ->
                if (
                    mutableScreen.value == AppScreen.SESSION &&
                    !snapshot.isActive &&
                    snapshot.status in setOf(SessionStatus.COMPLETE, SessionStatus.CANCELLED)
                ) {
                    mutableScreen.value = AppScreen.SUMMARY
                }
            }
        }
    }

    fun completeOnboarding() = viewModelScope.launch { container.preferences.completeOnboarding() }

    fun navigate(screen: AppScreen) {
        mutableScreen.value = screen
    }

    fun openSessionSetup(type: SessionType) {
        mutableDraft.value = SessionDraft(
            type = type,
            durationSeconds = if (type == SessionType.CALIBRATION) 120 else 600,
            rate = settings.value.preferredRate,
        )
        mutableScreen.value = if (type == SessionType.CALIBRATION) AppScreen.CALIBRATION else AppScreen.SESSION_SETUP
    }

    fun updateDraft(
        rate: Double = mutableDraft.value.rate,
        durationSeconds: Long = mutableDraft.value.durationSeconds,
        inhaleFraction: Double = mutableDraft.value.inhaleFraction,
    ) {
        mutableDraft.value = mutableDraft.value.copy(
            rate = rate,
            durationSeconds = durationSeconds,
            inhaleFraction = inhaleFraction,
        )
    }

    fun startCalibrationRate(rate: Double) {
        mutableDraft.value = SessionDraft(
            type = SessionType.CALIBRATION,
            durationSeconds = 120,
            rate = rate,
            inhaleFraction = 0.5,
        )
        startSession()
    }

    fun startSession() = viewModelScope.launch {
        val currentDraft = mutableDraft.value
        engine.start(
            type = currentDraft.type,
            durationSeconds = currentDraft.durationSeconds,
            rate = currentDraft.rate,
            inhaleFraction = currentDraft.inhaleFraction,
        )
        mutableScreen.value = AppScreen.SESSION
    }

    fun stopSession() = viewModelScope.launch { engine.stop() }

    fun pauseSession() = viewModelScope.launch { engine.pause() }

    fun resumeSession() = viewModelScope.launch { engine.resume() }

    fun finishSummary(feedback: UserFeedback) = viewModelScope.launch {
        engine.applyFeedback(feedback)
        engine.reset()
        mutableScreen.value = if (draft.value.type == SessionType.CALIBRATION) {
            AppScreen.CALIBRATION
        } else {
            AppScreen.HOME
        }
    }

    fun scanForSensors() {
        scanJob?.cancel()
        mutableDevices.value = emptyList()
        scanJob = viewModelScope.launch {
            container.sensor.scan()
                .catch { }
                .collect { device ->
                    mutableDevices.value = (mutableDevices.value + device)
                        .distinctBy { it.deviceId }
                        .sortedByDescending { it.rssi }
                }
        }
    }

    fun stopScan() {
        scanJob?.cancel()
        scanJob = null
        container.sensor.stopScan()
    }

    fun connect(device: SensorDevice) {
        stopScan()
        container.sensor.connect(device.deviceId)
        viewModelScope.launch { container.preferences.saveDevice(device.deviceId) }
    }

    fun reconnectSavedSensor() {
        settings.value.savedDeviceId?.let(container.sensor::connect)
    }

    fun disconnectSensor() {
        container.sensor.disconnect()
        viewModelScope.launch { container.preferences.clearDevice() }
    }

    fun setSoundEnabled(enabled: Boolean) = viewModelScope.launch {
        container.preferences.setSoundEnabled(enabled)
    }

    fun setHapticsEnabled(enabled: Boolean) = viewModelScope.launch {
        container.preferences.setHapticsEnabled(enabled)
    }

    suspend fun exportSession(sessionId: Long): String = container.repository.exportCsv(sessionId)

    fun deleteSession(sessionId: Long) = viewModelScope.launch {
        container.repository.deleteSession(sessionId)
    }

    fun deleteAllData() = viewModelScope.launch {
        container.repository.deleteAllSessions()
    }

    fun calibrationSessions(): List<SessionEntity> = sessions.value.filter {
        it.type == SessionType.CALIBRATION.name && it.status == SessionStatus.COMPLETE.name
    }

    override fun onCleared() {
        container.sensor.close()
        super.onCleared()
    }
}

class MainViewModelFactory(private val container: AppContainer) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = MainViewModel(container) as T
}
