package quest.byai.hrv.data

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object HistoryArchiveExporter {
    fun build(
        sessions: List<SessionEntity>,
        rrSamples: List<RrSampleEntity>,
        hrvMeasurements: List<HrvMeasurementEntity>,
        breathingSegments: List<BreathingSegmentEntity>,
        analysisWindows: List<AnalysisWindowEntity>,
    ): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            zip.writeEntry("README.txt", archiveReadme())
            zip.writeEntry("sessions.csv", sessionsCsv(sessions))
            zip.writeEntry("raw_rr_samples.csv", rrSamplesCsv(rrSamples))
            zip.writeEntry("hrv_measurements.csv", hrvMeasurementsCsv(hrvMeasurements))
            zip.writeEntry("breathing_segments.csv", breathingSegmentsCsv(breathingSegments))
            zip.writeEntry("analysis_windows.csv", analysisWindowsCsv(analysisWindows))
        }
        return output.toByteArray()
    }

    private fun archiveReadme(): String = """
        HRV AI complete history export

        sessions.csv contains one summary row per session, including completed RMSSD, lnRMSSD,
        unrounded HRV score, resonance score, confidence, and feedback.

        raw_rr_samples.csv contains every stored raw R-R interval and its analysis value/quality flags.

        hrv_measurements.csv contains the rolling 15-second HRV/RMSSD series recorded approximately
        every two seconds during sessions. Sessions recorded before this feature was added have no rows.

        breathing_segments.csv contains every commanded breathing-rate segment and controller reason.

        analysis_windows.csv contains every resonance-analysis window and its component metrics.
    """.trimIndent() + "\n"

    private fun sessionsCsv(sessions: List<SessionEntity>): String = csv(
        header = listOf(
            "id", "type", "started_epoch_ms", "ended_epoch_ms", "status", "initial_rate_bpm",
            "final_rate_bpm", "inhale_fraction", "duration_seconds", "average_heart_rate_bpm",
            "rmssd_ms", "sdnn_ms", "ln_rmssd", "hrv_score_unrounded", "hrv_artifact_percent",
            "resonance_score", "confidence", "usable_data_fraction", "ease", "symptom_flags",
            "notes", "analysis_version",
        ),
        rows = sessions.map { session ->
            listOf(
                session.id, session.type, session.startedAtEpochMs, session.endedAtEpochMs,
                session.status, session.initialRate, session.finalRate, session.inhaleFraction,
                session.durationSeconds, session.averageHeartRate, session.rmssdMs, session.sdnnMs,
                session.lnRmssd, session.eliteHrvScore, session.eliteArtifactPercent,
                session.resonanceScore, session.confidence, session.usableDataFraction, session.ease,
                session.symptomFlags, session.notes, session.analysisVersion,
            )
        },
    )

    private fun rrSamplesCsv(samples: List<RrSampleEntity>): String = csv(
        header = listOf(
            "id", "session_id", "elapsed_ms", "raw_rr_ms", "analysis_rr_ms", "quality_flags",
            "heart_rate_bpm", "contact_status",
        ),
        rows = samples.map { sample ->
            listOf(
                sample.id, sample.sessionId, sample.elapsedRealtimeMs, sample.rawRrMs,
                sample.analysisRrMs, sample.qualityFlags, sample.heartRateBpm, sample.contactStatus,
            )
        },
    )

    private fun hrvMeasurementsCsv(measurements: List<HrvMeasurementEntity>): String = csv(
        header = listOf(
            "id", "session_id", "elapsed_ms", "window_duration_ms", "rr_interval_count",
            "rmssd_ms", "ln_rmssd", "hrv_score_displayed", "hrv_score_unrounded",
        ),
        rows = measurements.map { measurement ->
            listOf(
                measurement.id, measurement.sessionId, measurement.elapsedRealtimeMs,
                measurement.windowDurationMs, measurement.rrIntervalCount, measurement.rmssdMs,
                measurement.lnRmssd, measurement.displayedHrvScore, measurement.unroundedHrvScore,
            )
        },
    )

    private fun breathingSegmentsCsv(segments: List<BreathingSegmentEntity>): String = csv(
        header = listOf(
            "id", "session_id", "started_elapsed_ms", "ended_elapsed_ms", "breaths_per_minute",
            "inhale_fraction", "controller_action", "reason",
        ),
        rows = segments.map { segment ->
            listOf(
                segment.id, segment.sessionId, segment.startedAtElapsedMs, segment.endedAtElapsedMs,
                segment.breathsPerMinute, segment.inhaleFraction, segment.controllerAction, segment.reason,
            )
        },
    )

    private fun analysisWindowsCsv(windows: List<AnalysisWindowEntity>): String = csv(
        header = listOf(
            "id", "session_id", "ended_elapsed_ms", "breaths_per_minute", "resonance_score",
            "confidence", "target_amplitude_bpm", "regularity", "spectral_concentration",
            "dominant_frequency_hz", "usable_data_fraction", "qualified", "rejection_reason",
        ),
        rows = windows.map { window ->
            listOf(
                window.id, window.sessionId, window.endedAtElapsedMs, window.breathsPerMinute,
                window.score, window.confidence, window.targetAmplitudeBpm, window.regularity,
                window.spectralConcentration, window.dominantFrequencyHz, window.usableDataFraction,
                window.qualified, window.rejectionReason,
            )
        },
    )

    private fun csv(header: List<String>, rows: List<List<Any?>>): String = buildString {
        appendLine(header.joinToString(",", transform = ::escapeCsv))
        rows.forEach { row -> appendLine(row.joinToString(",") { escapeCsv(it?.toString().orEmpty()) }) }
    }

    private fun escapeCsv(value: String): String {
        if (value.none { it == ',' || it == '"' || it == '\n' || it == '\r' }) return value
        return "\"${value.replace("\"", "\"\"")}\""
    }

    private fun ZipOutputStream.writeEntry(name: String, content: String) {
        putNextEntry(ZipEntry(name))
        write(content.toByteArray(Charsets.UTF_8))
        closeEntry()
    }
}
