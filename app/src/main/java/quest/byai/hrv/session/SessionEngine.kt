package quest.byai.hrv.session

import android.os.SystemClock
import quest.byai.hrv.controller.AdaptiveBreathingController
import quest.byai.hrv.controller.ControllerAction
import quest.byai.hrv.controller.ControllerState
import quest.byai.hrv.data.AppPreferences
import quest.byai.hrv.data.SessionRepository
import quest.byai.hrv.domain.BreathingCue
import quest.byai.hrv.domain.HeartRateSample
import quest.byai.hrv.domain.ResonanceObservation
import quest.byai.hrv.domain.RrSample
import quest.byai.hrv.domain.SessionStatus
import quest.byai.hrv.domain.SessionType
import quest.byai.hrv.domain.UserFeedback
import quest.byai.hrv.signal.ResonanceAnalyzer
import quest.byai.hrv.signal.RrArtifactClassifier
import quest.byai.hrv.signal.EliteHrvCalculator
import quest.byai.hrv.signal.EliteHrvResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class SessionSnapshot(
    val sessionId: Long? = null,
    val type: SessionType = SessionType.FIXED,
    val status: SessionStatus = SessionStatus.PREPARING,
    val startedAtElapsedMs: Long = 0,
    val targetDurationSeconds: Long = 600,
    val elapsedSeconds: Long = 0,
    val currentRate: Double = 6.0,
    val inhaleFraction: Double = 0.5,
    val currentHeartRate: Int? = null,
    val liveHrv: EliteHrvResult? = null,
    val completedHrv: EliteHrvResult? = null,
    val rrAvailable: Boolean = false,
    val contactStatus: Boolean? = null,
    val signalMessage: String = "Waiting for R-R intervals",
    val controllerMessage: String = "Holding steady",
    val latestObservation: ResonanceObservation? = null,
    val recentHeartRates: List<Int> = emptyList(),
    val isActive: Boolean = false,
)

class SessionEngine(
    private val repository: SessionRepository,
    private val preferences: AppPreferences,
    private val artifactClassifier: RrArtifactClassifier = RrArtifactClassifier(),
    private val analyzer: ResonanceAnalyzer = ResonanceAnalyzer(),
    private val eliteHrvCalculator: EliteHrvCalculator = EliteHrvCalculator(),
    private val controller: AdaptiveBreathingController = AdaptiveBreathingController(),
    private val timestampReconstructor: RrTimestampReconstructor = RrTimestampReconstructor(),
) {
    private companion object {
        const val SETTLING_DURATION_MS = 30_000L
        const val EVALUATION_DURATION_MS = 75_000L
        const val SIGNAL_TIMEOUT_MS = 3_000L
        const val MAX_WAVEFORM_POINTS = 90
    }

    private val mutableSnapshot = MutableStateFlow(SessionSnapshot())
    val snapshot = mutableSnapshot.asStateFlow()

    private val rawRrSamples = mutableListOf<RrSample>()
    private val heartRates = mutableListOf<Int>()
    private var controllerState = ControllerState()
    private var evaluationWindowStartedAtMs: Long = 0
    private var settleUntilElapsedMs: Long = 0
    private var pausedAtElapsedMs: Long? = null

    suspend fun start(
        type: SessionType,
        durationSeconds: Long,
        rate: Double,
        inhaleFraction: Double,
    ) {
        check(!mutableSnapshot.value.isActive) { "A session is already active" }
        val now = SystemClock.elapsedRealtime()
        val sessionId = repository.startSession(type, rate, inhaleFraction)
        repository.addSegment(
            sessionId = sessionId,
            elapsedMs = 0,
            rate = rate,
            inhaleFraction = inhaleFraction,
            action = "START",
            reason = "Session started",
        )
        rawRrSamples.clear()
        heartRates.clear()
        timestampReconstructor.reset()
        controllerState = ControllerState(acceptedRate = rate, commandedRate = rate)
        pausedAtElapsedMs = null
        settleUntilElapsedMs = now + SETTLING_DURATION_MS
        evaluationWindowStartedAtMs = settleUntilElapsedMs
        mutableSnapshot.value = SessionSnapshot(
            sessionId = sessionId,
            type = type,
            status = SessionStatus.SETTLING,
            startedAtElapsedMs = now,
            targetDurationSeconds = durationSeconds,
            currentRate = rate,
            inhaleFraction = inhaleFraction,
            signalMessage = "Settling and checking signal",
            controllerMessage = if (type == SessionType.ADAPTIVE) "Finding your rhythm" else "Holding steady",
            isActive = true,
        )
    }

    suspend fun onHeartRateSample(notification: HeartRateSample) {
        val current = mutableSnapshot.value
        val sessionId = current.sessionId ?: return
        if (!current.isActive || current.status == SessionStatus.USER_PAUSED) return

        heartRates += notification.heartRateBpm
        val reconstructed = if (notification.rrAvailable) {
            timestampReconstructor.reconstruct(
                receiveElapsedMs = notification.receivedElapsedRealtimeNanos / 1_000_000L,
                rrIntervalsMs = notification.rrIntervalsMs,
                contactStatus = notification.contactStatus,
            )
        } else {
            emptyList()
        }
        rawRrSamples += reconstructed
        val liveHrv = eliteHrvCalculator.calculateLive(rawRrSamples.map { it.rawRrMs.toDouble() })
        val classified = artifactClassifier.classify(rawRrSamples).takeLast(reconstructed.size)
        repository.addRrSamples(
            sessionId = sessionId,
            samples = classified.map { sample ->
                sample.copy(
                    elapsedRealtimeMs = (sample.elapsedRealtimeMs - current.startedAtElapsedMs).coerceAtLeast(0),
                )
            },
            heartRateBpm = notification.heartRateBpm,
            contactStatus = notification.contactStatus,
        )

        val recentHeartRates = (current.recentHeartRates + notification.heartRateBpm)
            .takeLast(MAX_WAVEFORM_POINTS)
        val usable = classified.count { it.isUsable }
        val signalMessage = when {
            !notification.rrAvailable || notification.rrIntervalsMs.isEmpty() -> "Heart rate found; waiting for R-R intervals"
            notification.contactStatus == false -> "Check chest-strap contact"
            classified.isNotEmpty() && usable == 0 -> "R-R artifacts detected"
            else -> "Signal good"
        }
        val status = if (signalMessage == "Signal good") current.status else SessionStatus.SIGNAL_PAUSED
        mutableSnapshot.value = current.copy(
            status = status,
            currentHeartRate = notification.heartRateBpm,
            liveHrv = liveHrv,
            rrAvailable = notification.rrAvailable,
            contactStatus = notification.contactStatus,
            signalMessage = signalMessage,
            recentHeartRates = recentHeartRates,
        )
    }

    suspend fun tick(nowElapsedMs: Long = SystemClock.elapsedRealtime()) {
        val current = mutableSnapshot.value
        if (!current.isActive) return
        if (pausedAtElapsedMs != null) return
        val elapsedMs = nowElapsedMs - current.startedAtElapsedMs
        val elapsedSeconds = (elapsedMs / 1_000L).coerceAtLeast(0)

        if (elapsedSeconds >= current.targetDurationSeconds) {
            complete(cancelled = false)
            return
        }

        val signalTimedOut = timestampReconstructor.lastBeatElapsedMs?.let { nowElapsedMs - it > SIGNAL_TIMEOUT_MS } ?: true
        val qualifiedSignal = current.rrAvailable && current.contactStatus != false && !signalTimedOut
        val status = when {
            !qualifiedSignal -> SessionStatus.SIGNAL_PAUSED
            nowElapsedMs < settleUntilElapsedMs -> SessionStatus.SETTLING
            else -> SessionStatus.GUIDING
        }
        mutableSnapshot.value = current.copy(
            elapsedSeconds = elapsedSeconds,
            status = status,
            signalMessage = if (qualifiedSignal) "Signal good" else current.signalMessage,
        )

        if (
            current.type == SessionType.ADAPTIVE &&
            qualifiedSignal &&
            nowElapsedMs >= evaluationWindowStartedAtMs + EVALUATION_DURATION_MS
        ) {
            evaluateAdaptiveWindow(nowElapsedMs)
        }
    }

    suspend fun stop() = complete(cancelled = true)

    suspend fun pause() {
        val current = mutableSnapshot.value
        if (!current.isActive || pausedAtElapsedMs != null) return
        pausedAtElapsedMs = SystemClock.elapsedRealtime()
        current.sessionId?.let { sessionId ->
            repository.addSegment(
                sessionId = sessionId,
                elapsedMs = current.elapsedSeconds * 1_000L,
                rate = current.currentRate,
                inhaleFraction = current.inhaleFraction,
                action = "PAUSE",
                reason = "App left the foreground",
            )
        }
        mutableSnapshot.value = current.copy(
            status = SessionStatus.USER_PAUSED,
            signalMessage = "Session paused while the app is in the background",
        )
    }

    suspend fun resume() {
        val pausedAt = pausedAtElapsedMs ?: return
        val current = mutableSnapshot.value
        if (!current.isActive) {
            pausedAtElapsedMs = null
            return
        }
        val now = SystemClock.elapsedRealtime()
        val pausedDurationMs = now - pausedAt
        pausedAtElapsedMs = null
        settleUntilElapsedMs = now + SETTLING_DURATION_MS
        evaluationWindowStartedAtMs = settleUntilElapsedMs
        timestampReconstructor.reset()
        current.sessionId?.let { sessionId ->
            repository.addSegment(
                sessionId = sessionId,
                elapsedMs = current.elapsedSeconds * 1_000L,
                rate = current.currentRate,
                inhaleFraction = current.inhaleFraction,
                action = "RESUME",
                reason = "App returned to the foreground; settling before evaluation",
            )
        }
        mutableSnapshot.value = current.copy(
            status = SessionStatus.SETTLING,
            startedAtElapsedMs = current.startedAtElapsedMs + pausedDurationMs,
            signalMessage = "Settling and checking signal",
        )
    }

    suspend fun applyFeedback(feedback: UserFeedback) {
        val sessionId = mutableSnapshot.value.sessionId ?: return
        repository.applyFeedback(sessionId, feedback)
    }

    fun reset() {
        if (mutableSnapshot.value.isActive) return
        mutableSnapshot.value = SessionSnapshot()
    }

    private suspend fun evaluateAdaptiveWindow(nowElapsedMs: Long) {
        val current = mutableSnapshot.value
        val sessionId = current.sessionId ?: return
        val windowSamples = artifactClassifier.classify(rawRrSamples)
            .filter { it.elapsedRealtimeMs >= evaluationWindowStartedAtMs }
        val observation = analyzer.analyze(
            samples = windowSamples,
            cue = BreathingCue(current.currentRate, current.inhaleFraction),
        )
        repository.addObservation(
            sessionId = sessionId,
            elapsedMs = nowElapsedMs - current.startedAtElapsedMs,
            observation = observation,
        )
        val decision = controller.next(controllerState, observation)
        controllerState = decision.state

        if (decision.action != ControllerAction.HOLD && decision.nextRate != current.currentRate) {
            repository.addSegment(
                sessionId = sessionId,
                elapsedMs = nowElapsedMs - current.startedAtElapsedMs,
                rate = decision.nextRate,
                inhaleFraction = current.inhaleFraction,
                action = decision.action.name,
                reason = decision.reason,
            )
            if (decision.action == ControllerAction.ACCEPT_AND_EXPLORE) {
                preferences.savePreferredRate(controllerState.acceptedRate)
            }
            settleUntilElapsedMs = nowElapsedMs + SETTLING_DURATION_MS
            evaluationWindowStartedAtMs = settleUntilElapsedMs
        } else {
            evaluationWindowStartedAtMs = nowElapsedMs
        }

        mutableSnapshot.value = current.copy(
            currentRate = decision.nextRate,
            status = if (decision.action == ControllerAction.HOLD) SessionStatus.GUIDING else SessionStatus.SETTLING,
            controllerMessage = decision.reason,
            latestObservation = observation,
        )
    }

    private suspend fun complete(cancelled: Boolean) {
        val current = mutableSnapshot.value
        val sessionId = current.sessionId ?: return
        if (!current.isActive) return
        val now = SystemClock.elapsedRealtime()
        val activeElapsedMs = now - current.startedAtElapsedMs - (pausedAtElapsedMs?.let { now - it } ?: 0L)
        pausedAtElapsedMs = null
        val durationSeconds = (activeElapsedMs / 1_000L)
            .coerceAtMost(current.targetDurationSeconds)
        val classified = artifactClassifier.classify(rawRrSamples)
        val completedHrv = eliteHrvCalculator.calculateCompleted(rawRrSamples.map { it.rawRrMs.toDouble() })
        val summaryObservation = current.latestObservation ?: analyzer.analyze(
            classified,
            BreathingCue(current.currentRate, current.inhaleFraction),
        )
        if (current.latestObservation == null) {
            repository.addObservation(
                sessionId = sessionId,
                elapsedMs = now - current.startedAtElapsedMs,
                observation = summaryObservation,
            )
        }
        repository.finishSession(
            sessionId = sessionId,
            finalRate = current.currentRate,
            durationSeconds = durationSeconds,
            averageHeartRate = heartRates.takeIf { it.isNotEmpty() }?.average(),
            observation = summaryObservation,
            eliteHrv = completedHrv,
            feedback = null,
            cancelled = cancelled,
        )
        mutableSnapshot.value = current.copy(
            status = if (cancelled) SessionStatus.CANCELLED else SessionStatus.COMPLETE,
            elapsedSeconds = durationSeconds,
            latestObservation = summaryObservation,
            liveHrv = completedHrv ?: current.liveHrv,
            completedHrv = completedHrv,
            controllerMessage = if (cancelled) "Session ended early" else "Session complete",
            isActive = false,
        )
    }

}
