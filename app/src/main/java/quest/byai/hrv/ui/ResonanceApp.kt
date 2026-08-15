package quest.byai.hrv.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.Lifecycle
import quest.byai.hrv.data.SessionEntity
import quest.byai.hrv.data.UserSettings
import quest.byai.hrv.domain.SensorConnectionState
import quest.byai.hrv.domain.SensorDevice
import quest.byai.hrv.domain.SessionStatus
import quest.byai.hrv.domain.SessionType
import quest.byai.hrv.domain.UserFeedback
import quest.byai.hrv.session.SessionSnapshot
import quest.byai.hrv.sensor.SensorDiagnostics
import quest.byai.hrv.R
import quest.byai.hrv.BuildConfig
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

@Composable
fun ResonanceApp(viewModel: MainViewModel) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val screen by viewModel.screen.collectAsStateWithLifecycle()
    val connectionState by viewModel.connectionState.collectAsStateWithLifecycle()
    val batteryLevel by viewModel.batteryLevel.collectAsStateWithLifecycle()
    val sensorError by viewModel.sensorError.collectAsStateWithLifecycle()
    val sensorDiagnostics by viewModel.sensorDiagnostics.collectAsStateWithLifecycle()
    val devices by viewModel.devices.collectAsStateWithLifecycle()
    val session by viewModel.session.collectAsStateWithLifecycle()
    val draft by viewModel.draft.collectAsStateWithLifecycle()
    val sessions by viewModel.sessions.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        if (grants.values.all { it }) viewModel.scanForSensors()
    }

    fun scanWithPermission() {
        val permissions = arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        if (permissions.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }) {
            viewModel.scanForSensors()
        } else {
            permissionLauncher.launch(permissions)
        }
    }

    if (!settings.onboardingComplete) {
        OnboardingScreen(viewModel::completeOnboarding)
        return
    }

    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { viewModel.pauseSession() }
    LifecycleEventEffect(Lifecycle.Event.ON_START) { viewModel.resumeSession() }

    AnimatedContent(targetState = screen, label = "screen") { target ->
        when (target) {
            AppScreen.HOME -> HomeScreen(
                connectionState = connectionState,
                batteryLevel = batteryLevel,
                rrStreamConfirmed = sensorDiagnostics.rrStreamConfirmed,
                preferredRate = settings.preferredRate,
                onSensor = { viewModel.navigate(AppScreen.SENSOR) },
                onFixed = { viewModel.openSessionSetup(SessionType.FIXED) },
                onAdaptive = { viewModel.openSessionSetup(SessionType.ADAPTIVE) },
                onCalibration = { viewModel.openSessionSetup(SessionType.CALIBRATION) },
                onHistory = { viewModel.navigate(AppScreen.HISTORY) },
                onSettings = { viewModel.navigate(AppScreen.SETTINGS) },
            )
            AppScreen.SENSOR -> SensorScreen(
                connectionState = connectionState,
                batteryLevel = batteryLevel,
                error = sensorError,
                diagnostics = sensorDiagnostics,
                devices = devices,
                savedDeviceId = settings.savedDeviceId,
                onBack = { viewModel.stopScan(); viewModel.navigate(AppScreen.HOME) },
                onScan = ::scanWithPermission,
                onConnect = viewModel::connect,
                onReconnect = viewModel::reconnectSavedSensor,
                onDisconnect = viewModel::disconnectSensor,
            )
            AppScreen.SESSION_SETUP -> SessionSetupScreen(
                draft = draft,
                connected = connectionState == SensorConnectionState.STREAMING && sensorDiagnostics.rrStreamConfirmed,
                onBack = { viewModel.navigate(AppScreen.HOME) },
                onRate = { viewModel.updateDraft(rate = it) },
                onDuration = { viewModel.updateDraft(durationSeconds = it) },
                onRatio = { viewModel.updateDraft(inhaleFraction = it) },
                onStart = viewModel::startSession,
                onSensor = { viewModel.navigate(AppScreen.SENSOR) },
            )
            AppScreen.CALIBRATION -> CalibrationScreen(
                sessions = viewModel.calibrationSessions(),
                connected = connectionState == SensorConnectionState.STREAMING && sensorDiagnostics.rrStreamConfirmed,
                onBack = { viewModel.navigate(AppScreen.HOME) },
                onStartRate = viewModel::startCalibrationRate,
                onSensor = { viewModel.navigate(AppScreen.SENSOR) },
            )
            AppScreen.SESSION -> ActiveSessionScreen(
                snapshot = session,
                settings = settings,
                onStop = viewModel::stopSession,
            )
            AppScreen.SUMMARY -> SummaryScreen(
                snapshot = session,
                onDone = viewModel::finishSummary,
                exporter = viewModel::exportSession,
            )
            AppScreen.HISTORY -> HistoryScreen(
                sessions = sessions,
                onBack = { viewModel.navigate(AppScreen.HOME) },
                exporter = viewModel::exportSession,
                onDelete = viewModel::deleteSession,
            )
            AppScreen.SETTINGS -> SettingsScreen(
                settings = settings,
                connected = connectionState == SensorConnectionState.STREAMING,
                onBack = { viewModel.navigate(AppScreen.HOME) },
                onSound = viewModel::setSoundEnabled,
                onHaptics = viewModel::setHapticsEnabled,
                onDisconnect = viewModel::disconnectSensor,
                onDeleteAll = viewModel::deleteAllData,
            )
        }
    }
}

@Composable
private fun OnboardingScreen(onContinue: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(28.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(22.dp)) {
            Icon(
                Icons.Default.Favorite,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(52.dp),
            )
            Text("Breathe with your physiology", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.SemiBold)
            Text(
                "HRV AI uses beat-to-beat timing from a Polar H9 to guide slow breathing and estimate how strongly your heart-rate wave follows the cue.",
                style = MaterialTheme.typography.bodyLarge,
            )
            InformationCard(
                "Wellness, not diagnosis",
                "This app does not diagnose stress, heart rhythm problems, or disease. Stop and seek medical care for chest pain, fainting, or significant shortness of breath.",
            )
            InformationCard(
                "Breathe softly",
                "Slow does not mean maximally deep. If you feel dizzy, tingly, air hungry, or your heart pounds, stop and return to comfortable natural breathing.",
            )
            Text(
                "Your measurements stay on this phone unless you explicitly export them.",
                color = MaterialTheme.colorScheme.secondary,
            )
        }
        Button(onClick = onContinue, modifier = Modifier.fillMaxWidth()) {
            Text("I understand — continue")
        }
    }
}

@Composable
private fun HomeScreen(
    connectionState: SensorConnectionState,
    batteryLevel: Int?,
    rrStreamConfirmed: Boolean,
    preferredRate: Double,
    onSensor: () -> Unit,
    onFixed: () -> Unit,
    onAdaptive: () -> Unit,
    onCalibration: () -> Unit,
    onHistory: () -> Unit,
    onSettings: () -> Unit,
) {
    Scaffold(
        topBar = {
            Row(
                Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 20.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("HRV AI", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
                Row {
                    IconButton(onClick = onHistory) { Icon(Icons.Default.History, "Session history") }
                    IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, "Settings") }
                }
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                SensorStatusCard(connectionState, batteryLevel, rrStreamConfirmed, onSensor)
            }
            item {
                Text("Practice", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            }
            item {
                PracticeCard(
                    title = "Adaptive session",
                    description = "Starts near ${"%.1f".format(preferredRate)} breaths/min and cautiously tests nearby rates.",
                    button = "Start adaptive",
                    onClick = onAdaptive,
                )
            }
            item {
                PracticeCard(
                    title = "Fixed session",
                    description = "Choose a breathing rate and ratio. The cue stays constant for the entire session.",
                    button = "Choose pace",
                    onClick = onFixed,
                )
            }
            item {
                PracticeCard(
                    title = "Find your starting rhythm",
                    description = "Compare five two-minute rates. Complete them across comparable days and rate ease after each.",
                    button = "Open calibration",
                    onClick = onCalibration,
                )
            }
            item {
                InformationCard(
                    "A good response is not just a big HRV number",
                    "The app looks for a large, regular heart-rate wave concentrated near the breathing cue, and ignores windows with poor signal.",
                )
            }
        }
    }
}

@Composable
private fun SensorStatusCard(
    state: SensorConnectionState,
    batteryLevel: Int?,
    rrStreamConfirmed: Boolean,
    onClick: () -> Unit,
) {
    val sensorReady = state == SensorConnectionState.STREAMING && rrStreamConfirmed
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Icon(Icons.Default.Bluetooth, null, tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f)) {
                Text(if (sensorReady) "Polar sensor ready" else "Connect Polar H9", fontWeight = FontWeight.SemiBold)
                Text(
                    if (sensorReady) "Receiving R-R intervals${batteryLevel?.let { " · $it% battery" }.orEmpty()}"
                    else if (state == SensorConnectionState.STREAMING) "Receiving heart rate; waiting for R-R intervals"
                    else state.name.lowercase().replace('_', ' '),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Text("Manage", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
private fun PracticeCard(title: String, description: String, button: String, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(description, color = MaterialTheme.colorScheme.secondary)
            FilledTonalButton(onClick = onClick) { Text(button) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SensorScreen(
    connectionState: SensorConnectionState,
    batteryLevel: Int?,
    error: String?,
    diagnostics: SensorDiagnostics,
    devices: List<SensorDevice>,
    savedDeviceId: String?,
    onBack: () -> Unit,
    onScan: () -> Unit,
    onConnect: (SensorDevice) -> Unit,
    onReconnect: () -> Unit,
    onDisconnect: () -> Unit,
) {
    ScreenScaffold("Polar sensor", onBack) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                InformationCard("Before connecting", "Wear the strap and wet both electrode areas. Close Polar Flow, Elite HRV, or another app currently using the H9 Bluetooth connection.")
            }
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Status: ${connectionState.name.lowercase().replace('_', ' ')}", fontWeight = FontWeight.SemiBold)
                        batteryLevel?.let { Text("Battery: $it%") }
                        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        if (connectionState == SensorConnectionState.STREAMING) {
                            OutlinedButton(onClick = onDisconnect) { Text("Disconnect") }
                        } else {
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Button(onClick = onScan) { Text("Scan nearby") }
                                if (savedDeviceId != null) {
                                    OutlinedButton(onClick = onReconnect) { Text("Reset & reconnect") }
                                }
                            }
                        }
                    }
                }
            }
            item { SensorDiagnosticsCard(connectionState, diagnostics, batteryLevel) }
            if (connectionState == SensorConnectionState.SCANNING) {
                item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            }
            items(devices, key = { it.deviceId }) { device ->
                Card(
                    onClick = { onConnect(device) },
                    enabled = device.isConnectable,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(device.name, fontWeight = FontWeight.SemiBold)
                            Text(
                                "ID ${device.deviceId} · ${device.rssi} dBm · ${if (device.isConnectable) "connectable" else "busy/unavailable"}",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Text(if (device.isConnectable) "Connect" else "Unavailable", color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    }
}

@Composable
private fun SensorDiagnosticsCard(
    connectionState: SensorConnectionState,
    diagnostics: SensorDiagnostics,
    batteryLevel: Int?,
) {
    val context = LocalContext.current
    var diagnosticsCopied by remember { mutableStateOf(false) }
    val lastSampleAgeSeconds = diagnostics.lastSampleElapsedRealtimeMs?.let { timestamp ->
        ((SystemClock.elapsedRealtime() - timestamp).coerceAtLeast(0L) / 1_000L)
    }
    val diagnosticReport = remember(connectionState, diagnostics, batteryLevel) {
        buildSensorDiagnosticReport(connectionState, diagnostics, batteryLevel)
    }
    LaunchedEffect(diagnosticsCopied) {
        if (diagnosticsCopied) {
            kotlinx.coroutines.delay(2_000)
            diagnosticsCopied = false
        }
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Connection diagnostics", fontWeight = FontWeight.SemiBold)
            Text("Device: ${diagnostics.selectedDeviceName ?: "—"} (${diagnostics.selectedDeviceId ?: "none"})")
            Text("HR samples: ${diagnostics.heartRateSampleCount} · R-R intervals: ${diagnostics.rrIntervalCount}")
            Text("Last HR: ${diagnostics.lastHeartRateBpm?.let { "$it bpm" } ?: "—"} · Last R-R: ${diagnostics.lastRrMs?.let { "$it ms" } ?: "—"}")
            Text("Last sample: ${lastSampleAgeSeconds?.let { "$it seconds ago" } ?: "none"}")
            Text("R-R stream confirmed: ${if (diagnostics.rrStreamConfirmed) "yes" else "no"}")
            Text("Contact: ${diagnostics.contactStatus?.let { if (it) "detected" else "lost" } ?: "unknown"} · Battery: ${batteryLevel?.let { "$it%" } ?: "unknown"}")
            Text("Recoveries: ${diagnostics.recoveryCount}")
            Text("Last event: ${diagnostics.lastEvent}")
            diagnostics.lastError?.let { Text("Last error: $it", color = MaterialTheme.colorScheme.error) }
            diagnostics.lastErrorDetails?.let {
                Text("Cause: $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            diagnostics.lastSdkLog?.let {
                Text("Last SDK event: $it", style = MaterialTheme.typography.bodySmall)
            }
            OutlinedButton(
                onClick = {
                    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(
                        ClipData.newPlainText("HRV AI sensor diagnostics", diagnosticReport),
                    )
                    diagnosticsCopied = true
                },
            ) {
                Text(if (diagnosticsCopied) "Diagnostics copied" else "Copy diagnostics")
            }
            Text(
                "Logcat tags: PolarHeartRateSensor, PolarBleSdk",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.secondary,
            )
        }
    }
}

private fun buildSensorDiagnosticReport(
    connectionState: SensorConnectionState,
    diagnostics: SensorDiagnostics,
    batteryLevel: Int?,
): String = buildString {
    appendLine("HRV AI ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
    appendLine("Phone: ${Build.MANUFACTURER} ${Build.MODEL}; Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
    appendLine("State: ${connectionState.name}")
    appendLine("Device: ${diagnostics.selectedDeviceName ?: "unknown"} (${diagnostics.selectedDeviceId ?: "none"})")
    appendLine("HR samples: ${diagnostics.heartRateSampleCount}; R-R intervals: ${diagnostics.rrIntervalCount}")
    appendLine("Last HR: ${diagnostics.lastHeartRateBpm ?: "none"}; last R-R: ${diagnostics.lastRrMs ?: "none"}")
    appendLine("R-R confirmed: ${diagnostics.rrStreamConfirmed}; contact: ${diagnostics.contactStatus ?: "unknown"}; battery: ${batteryLevel ?: "unknown"}")
    appendLine("Recoveries: ${diagnostics.recoveryCount}")
    appendLine("Last event: ${diagnostics.lastEvent}")
    appendLine("Last error: ${diagnostics.lastError ?: "none"}")
    appendLine("Cause: ${diagnostics.lastErrorDetails ?: "none"}")
    append("Last SDK event: ${diagnostics.lastSdkLog ?: "none"}")
}

@Composable
private fun SessionSetupScreen(
    draft: SessionDraft,
    connected: Boolean,
    onBack: () -> Unit,
    onRate: (Double) -> Unit,
    onDuration: (Long) -> Unit,
    onRatio: (Double) -> Unit,
    onStart: () -> Unit,
    onSensor: () -> Unit,
) {
    ScreenScaffold(if (draft.type == SessionType.ADAPTIVE) "Adaptive session" else "Fixed session", onBack) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(22.dp),
        ) {
            if (draft.type == SessionType.ADAPTIVE) {
                InformationCard("Slow adaptation", "The app waits for a qualified response before testing 0.1–0.2 breaths/minute nearby. It holds steady when confidence is low.")
            }
            SettingSection("Starting rate", "${"%.1f".format(draft.rate)} breaths/min") {
                Slider(
                    value = draft.rate.toFloat(),
                    onValueChange = { onRate((it * 10).roundToInt() / 10.0) },
                    valueRange = 4.5f..7.0f,
                    steps = 24,
                )
                Text("${formatSeconds(60.0 / draft.rate * draft.inhaleFraction)} inhale · ${formatSeconds(60.0 / draft.rate * (1.0 - draft.inhaleFraction))} exhale")
            }
            SettingSection("Session length", "${draft.durationSeconds / 60} minutes") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(3L, 5L, 10L, 15L).forEach { minutes ->
                        AssistChip(
                            onClick = { onDuration(minutes * 60) },
                            label = { Text("$minutes min") },
                            leadingIcon = if (draft.durationSeconds == minutes * 60) {
                                { Icon(Icons.Default.CheckCircle, null, Modifier.size(18.dp)) }
                            } else null,
                        )
                    }
                }
            }
            SettingSection("Inhale / exhale", if (draft.inhaleFraction == 0.5) "50 / 50" else "40 / 60") {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    FilledTonalButton(onClick = { onRatio(0.5) }) { Text("50 / 50") }
                    OutlinedButton(onClick = { onRatio(0.4) }) { Text("40 / 60") }
                }
            }
            Spacer(Modifier.weight(1f, fill = false))
            if (!connected) {
                InformationCard("Sensor required", "Connect a Polar H9 and confirm that R-R intervals are streaming before starting.")
                OutlinedButton(onClick = onSensor, modifier = Modifier.fillMaxWidth()) { Text("Connect sensor") }
            }
            Button(onClick = onStart, enabled = connected, modifier = Modifier.fillMaxWidth()) {
                Text("Start session")
            }
        }
    }
}

@Composable
private fun CalibrationScreen(
    sessions: List<SessionEntity>,
    connected: Boolean,
    onBack: () -> Unit,
    onStartRate: (Double) -> Unit,
    onSensor: () -> Unit,
) {
    val rates = listOf(7.0, 6.5, 6.0, 5.5, 5.0)
    ScreenScaffold("Calibration", onBack) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                InformationCard(
                    "Compare deliberately",
                    "Each test lasts two minutes at a 50/50 ratio. Rest naturally for at least two minutes between tests. Repeat promising rates on comparable days.",
                )
            }
            if (!connected) {
                item { OutlinedButton(onClick = onSensor, modifier = Modifier.fillMaxWidth()) { Text("Connect Polar sensor") } }
            }
            items(rates) { rate ->
                val matching = sessions.filter { kotlin.math.abs(it.initialRate - rate) < 0.01 }
                val best = matching.maxByOrNull { it.resonanceScore ?: 0.0 }
                Card(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("${"%.1f".format(rate)} breaths/min", fontWeight = FontWeight.SemiBold)
                            Text("${formatSeconds(30.0 / rate)} in · ${formatSeconds(30.0 / rate)} out", style = MaterialTheme.typography.bodySmall)
                            if (best != null) {
                                Text(
                                    "Best score ${best.resonanceScore?.roundToInt() ?: 0} · ease ${best.ease ?: "not rated"} · ${matching.size} test${if (matching.size == 1) "" else "s"}",
                                    color = MaterialTheme.colorScheme.primary,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                        Button(onClick = { onStartRate(rate) }, enabled = connected) { Text("Test") }
                    }
                }
            }
            item {
                val ranked = sessions.filter { it.ease != null && it.resonanceScore != null }
                    .sortedWith(compareByDescending<SessionEntity> { it.ease }.thenByDescending { it.resonanceScore })
                ranked.firstOrNull()?.let { best ->
                    InformationCard("Current leader", "${"%.1f".format(best.initialRate)} breaths/min combines your recorded ease (${best.ease}/5) with a resonance score of ${best.resonanceScore?.roundToInt()}.")
                }
            }
        }
    }
}

@Composable
private fun ActiveSessionScreen(
    snapshot: SessionSnapshot,
    settings: UserSettings,
    onStop: () -> Unit,
) {
    val view = LocalView.current
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
    Column(
        Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(formatDuration(snapshot.targetDurationSeconds - snapshot.elapsedSeconds), style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = onStop) { Text("End") }
        }
        Spacer(Modifier.height(12.dp))
        Text(
            when (snapshot.status) {
                SessionStatus.SETTLING -> "Settling"
                SessionStatus.SIGNAL_PAUSED -> "Signal interrupted"
                else -> if (snapshot.type == SessionType.ADAPTIVE) "Finding your rhythm" else "Follow the cue"
            },
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.weight(0.4f))
        BreathingPacer(
            rate = snapshot.currentRate,
            inhaleFraction = snapshot.inhaleFraction,
            hapticsEnabled = settings.hapticsEnabled,
            soundEnabled = settings.soundEnabled,
        )
        Spacer(Modifier.weight(0.5f))
        Text("${"%.1f".format(snapshot.currentRate)} breaths/min", style = MaterialTheme.typography.titleMedium)
        Text(snapshot.controllerMessage, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.secondary, modifier = Modifier.padding(horizontal = 24.dp))
        Spacer(Modifier.height(20.dp))
        HeartRateWave(snapshot.recentHeartRates, Modifier.fillMaxWidth().height(100.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(snapshot.currentHeartRate?.let { "$it bpm" } ?: "— bpm", fontWeight = FontWeight.Medium)
            Text(snapshot.signalMessage, color = if (snapshot.signalMessage == "Signal good") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun BreathingPacer(
    rate: Double,
    inhaleFraction: Double,
    hapticsEnabled: Boolean,
    soundEnabled: Boolean,
) {
    val nowElapsedMs by produceState(SystemClock.elapsedRealtime(), rate, inhaleFraction) {
        while (true) {
            withFrameNanos { value = SystemClock.elapsedRealtime() }
        }
    }
    val cycleMs = 60_000.0 / rate
    val phase = (nowElapsedMs % cycleMs) / cycleMs
    val inhaling = phase < inhaleFraction
    val rawExpansion = if (inhaling) phase / inhaleFraction else 1.0 - (phase - inhaleFraction) / (1.0 - inhaleFraction)
    val easedExpansion = rawExpansion * rawExpansion * (3.0 - 2.0 * rawExpansion)
    val size = (150.0 + 120.0 * easedExpansion).dp
    val label = if (inhaling) "Inhale" else "Exhale"
    val haptics = LocalHapticFeedback.current
    val toneGenerator = remember { ToneGenerator(AudioManager.STREAM_MUSIC, 35) }
    var previousLabel by remember { mutableStateOf(label) }
    DisposableEffect(Unit) { onDispose { toneGenerator.release() } }
    LaunchedEffect(label) {
        if (label != previousLabel) {
            if (hapticsEnabled) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            if (soundEnabled) toneGenerator.startTone(
                if (inhaling) ToneGenerator.TONE_PROP_BEEP else ToneGenerator.TONE_PROP_BEEP2,
                120,
            )
            previousLabel = label
        }
    }
    Box(
        Modifier.size(280.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(size)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer)
                .semantics { contentDescription = "$label breathing cue" },
            contentAlignment = Alignment.Center,
        ) {
            Text(label, fontSize = 25.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun HeartRateWave(values: List<Int>, modifier: Modifier = Modifier) {
    val lineColor = MaterialTheme.colorScheme.primary
    Canvas(modifier.semantics { contentDescription = "Recent heart-rate waveform" }) {
        if (values.size < 2) return@Canvas
        val minimum = values.min().toFloat()
        val maximum = values.max().toFloat()
        val range = (maximum - minimum).coerceAtLeast(1f)
        val path = Path()
        values.forEachIndexed { index, value ->
            val x = size.width * index / (values.size - 1)
            val y = size.height - size.height * (value - minimum) / range
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, lineColor, style = Stroke(width = 4f, cap = StrokeCap.Round))
    }
}

@Composable
private fun SummaryScreen(
    snapshot: SessionSnapshot,
    onDone: (UserFeedback) -> Unit,
    exporter: suspend (Long) -> String,
) {
    var ease by remember { mutableIntStateOf(3) }
    var dizzy by remember { mutableStateOf(false) }
    var tingling by remember { mutableStateOf(false) }
    var airHunger by remember { mutableStateOf(false) }
    var pounding by remember { mutableStateOf(false) }
    val observation = snapshot.latestObservation

    Column(
        Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Icon(Icons.Default.CheckCircle, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(46.dp))
        Text(if (snapshot.status == SessionStatus.CANCELLED) "Session ended" else "Session complete", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MetricCard("Time", formatDuration(snapshot.elapsedSeconds), Modifier.weight(1f))
            MetricCard("Final pace", "${"%.1f".format(snapshot.currentRate)}", Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MetricCard("Resonance", observation?.score?.roundToInt()?.toString() ?: "—", Modifier.weight(1f))
            MetricCard("Usable data", observation?.let { "${(it.usableDataFraction * 100).roundToInt()}%" } ?: "—", Modifier.weight(1f))
        }
        observation?.rejectionReason?.let { InformationCard("Low-confidence summary", it) }
        Text("How easy was this pace?", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            (1..5).forEach { value ->
                FilledTonalButton(onClick = { ease = value }, modifier = Modifier.size(52.dp), contentPadding = PaddingValues(0.dp)) {
                    Text(value.toString(), fontWeight = if (ease == value) FontWeight.Bold else FontWeight.Normal)
                }
            }
        }
        Text("Any discomfort?", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        FeedbackCheck("Dizziness", dizzy) { dizzy = it }
        FeedbackCheck("Tingling", tingling) { tingling = it }
        FeedbackCheck("Air hunger", airHunger) { airHunger = it }
        FeedbackCheck("Pounding heart", pounding) { pounding = it }
        snapshot.sessionId?.let { ExportButton(it, exporter, Modifier.fillMaxWidth()) }
        Button(
            onClick = {
                onDone(UserFeedback(ease, dizzy, tingling, airHunger, pounding))
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Save and continue") }
    }
}

@Composable
private fun MetricCard(label: String, value: String, modifier: Modifier = Modifier) {
    Card(modifier) {
        Column(Modifier.padding(16.dp)) {
            Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
        }
    }
}

@Composable
private fun FeedbackCheck(label: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked, onChecked)
        Text(label)
    }
}

@Composable
private fun HistoryScreen(
    sessions: List<SessionEntity>,
    onBack: () -> Unit,
    exporter: suspend (Long) -> String,
    onDelete: (Long) -> Unit,
) {
    ScreenScaffold("History", onBack) { padding ->
        if (sessions.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("No sessions yet", color = MaterialTheme.colorScheme.secondary)
            }
        } else {
            LazyColumn(
                Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(sessions, key = { it.id }) { session ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Column {
                                    Text(session.type.lowercase().replaceFirstChar { it.uppercase() }, fontWeight = FontWeight.SemiBold)
                                    Text(formatDate(session.startedAtEpochMs), style = MaterialTheme.typography.bodySmall)
                                }
                                Text("${"%.1f".format(session.finalRate)} bpm", color = MaterialTheme.colorScheme.primary)
                            }
                            Text("${formatDuration(session.durationSeconds)} · score ${session.resonanceScore?.roundToInt() ?: "—"} · ease ${session.ease ?: "—"}")
                            Row {
                                ExportButton(session.id, exporter)
                                IconButton(onClick = { onDelete(session.id) }) { Icon(Icons.Default.Delete, "Delete session") }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ExportButton(
    sessionId: Long,
    exporter: suspend (Long) -> String,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var content by remember { mutableStateOf("") }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null && content.isNotEmpty()) {
            context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(content) }
        }
    }
    OutlinedButton(
        onClick = {
            scope.launch {
                content = exporter(sessionId)
                launcher.launch("resonance-session-$sessionId.csv")
            }
        },
        modifier = modifier,
    ) {
        Icon(Icons.Default.Download, null, Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text("Export raw data")
    }
}

@Composable
private fun SettingsScreen(
    settings: UserSettings,
    connected: Boolean,
    onBack: () -> Unit,
    onSound: (Boolean) -> Unit,
    onHaptics: (Boolean) -> Unit,
    onDisconnect: () -> Unit,
    onDeleteAll: () -> Unit,
) {
    val context = LocalContext.current
    val privacyPolicyUrl = stringResource(R.string.privacy_policy_url)
    var confirmDelete by remember { mutableStateOf(false) }
    ScreenScaffold("Settings", onBack) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SwitchRow("Sound cues", "A short tone at inhale and exhale transitions", settings.soundEnabled, onSound)
            SwitchRow("Haptic cues", "A subtle phone vibration at phase transitions", settings.hapticsEnabled, onHaptics)
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Text("Sensor", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(settings.savedDeviceId?.let { "Saved Polar device: $it" } ?: "No saved sensor")
            if (connected) OutlinedButton(onClick = onDisconnect) { Text("Disconnect and forget sensor") }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Text("Data", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text("Measurements are stored only in this app's private database unless you export them.")
            OutlinedButton(
                onClick = {
                    context.startActivity(Intent(Intent.ACTION_VIEW, privacyPolicyUrl.toUri()))
                },
            ) {
                Icon(Icons.Default.Info, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Privacy policy")
            }
            OutlinedButton(onClick = { confirmDelete = true }) {
                Icon(Icons.Default.Delete, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Delete all session data")
            }
            InformationCard("Version 0.1", "Analysis version 1. This is an experimental wellness and biofeedback app, not a medical device.")
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete all sessions?") },
            text = { Text("This permanently removes all R-R measurements, summaries, and calibration results stored by the app.") },
            confirmButton = {
                TextButton(onClick = { onDeleteAll(); confirmDelete = false }) { Text("Delete all") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun SwitchRow(
    title: String,
    description: String,
    checked: Boolean,
    onChecked: (Boolean) -> Unit,
) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Medium)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
        }
        Switch(checked, onChecked)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ScreenScaffold(
    title: String,
    onBack: () -> Unit,
    content: @Composable (PaddingValues) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                },
            )
        },
        content = content,
    )
}

@Composable
private fun InformationCard(title: String, body: String) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.Default.Info, null, tint = MaterialTheme.colorScheme.primary)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, fontWeight = FontWeight.SemiBold)
                Text(body, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun SettingSection(title: String, value: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(value, color = MaterialTheme.colorScheme.primary)
        }
        content()
    }
}

private fun formatSeconds(seconds: Double): String = "%.1fs".format(seconds)

private fun formatDuration(seconds: Long): String {
    val safeSeconds = seconds.coerceAtLeast(0)
    return "%d:%02d".format(safeSeconds / 60, safeSeconds % 60)
}

private fun formatDate(epochMs: Long): String = DateTimeFormatter.ofPattern("MMM d, yyyy · h:mm a")
    .withZone(ZoneId.systemDefault())
    .format(Instant.ofEpochMilli(epochMs))
