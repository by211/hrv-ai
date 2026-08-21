package quest.byai.hrv.data

import quest.byai.hrv.domain.ResonanceObservation
import quest.byai.hrv.domain.RrSample
import quest.byai.hrv.domain.SessionStatus
import quest.byai.hrv.domain.SessionType
import quest.byai.hrv.domain.UserFeedback
import kotlinx.coroutines.flow.Flow
import quest.byai.hrv.signal.EliteHrvResult

class SessionRepository(private val dao: ResonanceDao) {
    val sessions: Flow<List<SessionEntity>> = dao.observeSessions()

    suspend fun startSession(
        type: SessionType,
        rate: Double,
        inhaleFraction: Double,
    ): Long = dao.insertSession(
        SessionEntity(
            type = type.name,
            startedAtEpochMs = System.currentTimeMillis(),
            status = SessionStatus.PREPARING.name,
            initialRate = rate,
            finalRate = rate,
            inhaleFraction = inhaleFraction,
        ),
    )

    suspend fun addRrSamples(
        sessionId: Long,
        samples: List<RrSample>,
        heartRateBpm: Int,
        contactStatus: Boolean?,
    ) {
        if (samples.isEmpty()) return
        dao.insertRrSamples(samples.map { sample ->
            RrSampleEntity(
                sessionId = sessionId,
                elapsedRealtimeMs = sample.elapsedRealtimeMs,
                rawRrMs = sample.rawRrMs,
                analysisRrMs = sample.analysisRrMs,
                qualityFlags = sample.qualityFlags.joinToString("|") { it.name },
                heartRateBpm = heartRateBpm,
                contactStatus = contactStatus,
            )
        })
    }

    suspend fun addSegment(
        sessionId: Long,
        elapsedMs: Long,
        rate: Double,
        inhaleFraction: Double,
        action: String,
        reason: String,
    ) = dao.insertBreathingSegment(
        BreathingSegmentEntity(
            sessionId = sessionId,
            startedAtElapsedMs = elapsedMs,
            breathsPerMinute = rate,
            inhaleFraction = inhaleFraction,
            controllerAction = action,
            reason = reason,
        ),
    )

    suspend fun addObservation(sessionId: Long, elapsedMs: Long, observation: ResonanceObservation) {
        dao.insertAnalysisWindow(
            AnalysisWindowEntity(
                sessionId = sessionId,
                endedAtElapsedMs = elapsedMs,
                breathsPerMinute = observation.breathsPerMinute,
                score = observation.score,
                confidence = observation.confidence,
                targetAmplitudeBpm = observation.targetAmplitudeBpm,
                regularity = observation.waveformRegularity,
                spectralConcentration = observation.spectralConcentration,
                dominantFrequencyHz = observation.dominantFrequencyHz,
                usableDataFraction = observation.usableDataFraction,
                qualified = observation.isQualified,
                rejectionReason = observation.rejectionReason,
            ),
        )
    }

    suspend fun addHrvMeasurement(
        sessionId: Long,
        elapsedMs: Long,
        windowDurationMs: Long,
        hrv: EliteHrvResult,
    ) {
        dao.insertHrvMeasurement(
            HrvMeasurementEntity(
                sessionId = sessionId,
                elapsedRealtimeMs = elapsedMs,
                windowDurationMs = windowDurationMs,
                rrIntervalCount = hrv.correctedRrMs.size,
                rmssdMs = hrv.rmssdMs,
                lnRmssd = hrv.lnRmssd,
                displayedHrvScore = hrv.score,
                unroundedHrvScore = hrv.unroundedScore,
            ),
        )
    }

    suspend fun finishSession(
        sessionId: Long,
        finalRate: Double,
        durationSeconds: Long,
        averageHeartRate: Double?,
        observation: ResonanceObservation?,
        eliteHrv: EliteHrvResult?,
        feedback: UserFeedback?,
        cancelled: Boolean = false,
    ) {
        val existing = dao.getSession(sessionId) ?: return
        dao.updateSession(
            existing.copy(
                endedAtEpochMs = System.currentTimeMillis(),
                status = if (cancelled) SessionStatus.CANCELLED.name else SessionStatus.COMPLETE.name,
                finalRate = finalRate,
                durationSeconds = durationSeconds,
                averageHeartRate = averageHeartRate,
                rmssdMs = eliteHrv?.rmssdMs ?: observation?.rmssdMs,
                sdnnMs = eliteHrv?.sdnnMs ?: observation?.sdnnMs,
                eliteHrvScore = eliteHrv?.unroundedScore,
                lnRmssd = eliteHrv?.lnRmssd,
                eliteArtifactPercent = eliteHrv?.artifactPercent,
                resonanceScore = observation?.score,
                confidence = observation?.confidence,
                usableDataFraction = observation?.usableDataFraction,
                ease = feedback?.ease,
                symptomFlags = "",
            ),
        )
    }

    suspend fun applyFeedback(sessionId: Long, feedback: UserFeedback) {
        val existing = dao.getSession(sessionId) ?: return
        dao.updateSession(
            existing.copy(
                ease = feedback.ease,
                symptomFlags = "",
            ),
        )
    }

    suspend fun exportCsv(sessionId: Long): String {
        val session = dao.getSession(sessionId) ?: return ""
        val samples = dao.getRrSamples(sessionId)
        return buildString {
            appendLine("# session_id,${session.id}")
            appendLine("# type,${session.type}")
            appendLine("# started_epoch_ms,${session.startedAtEpochMs}")
            appendLine("# analysis_version,${session.analysisVersion}")
            appendLine("# elite_hrv_score,${session.eliteHrvScore ?: ""}")
            appendLine("# ln_rmssd,${session.lnRmssd ?: ""}")
            appendLine("# rmssd_ms,${session.rmssdMs ?: ""}")
            appendLine("# sdnn_ms,${session.sdnnMs ?: ""}")
            appendLine("# elite_artifact_percent,${session.eliteArtifactPercent ?: ""}")
            appendLine("elapsed_ms,raw_rr_ms,analysis_rr_ms,quality_flags,heart_rate_bpm,contact_status")
            samples.forEach { sample ->
                appendLine(
                    listOf(
                        sample.elapsedRealtimeMs,
                        sample.rawRrMs,
                        sample.analysisRrMs ?: "",
                        sample.qualityFlags,
                        sample.heartRateBpm,
                        sample.contactStatus ?: "",
                    ).joinToString(","),
                )
            }
        }
    }

    suspend fun exportAllHistory(): ByteArray = HistoryArchiveExporter.build(
        sessions = dao.getAllSessions(),
        rrSamples = dao.getAllRrSamples(),
        hrvMeasurements = dao.getAllHrvMeasurements(),
        breathingSegments = dao.getAllBreathingSegments(),
        analysisWindows = dao.getAllAnalysisWindows(),
    )

    suspend fun deleteSession(sessionId: Long) = dao.deleteSession(sessionId)
    suspend fun deleteAllSessions() = dao.deleteAllSessions()
    suspend fun cancelInterruptedSessions() = dao.cancelInterruptedSessions(System.currentTimeMillis())

}
