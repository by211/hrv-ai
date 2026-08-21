package quest.byai.hrv.data

import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HistoryArchiveExporterTest {
    @Test
    fun `archive includes complete history and escapes CSV text`() {
        val archive = HistoryArchiveExporter.build(
            sessions = listOf(
                SessionEntity(
                    id = 1,
                    type = "FIXED",
                    startedAtEpochMs = 1_000,
                    endedAtEpochMs = 61_000,
                    status = "COMPLETE",
                    initialRate = 6.0,
                    finalRate = 5.8,
                    inhaleFraction = 0.4,
                    durationSeconds = 60,
                    averageHeartRate = 60.0,
                    rmssdMs = 50.0,
                    sdnnMs = 40.0,
                    eliteHrvScore = 60.184969,
                    lnRmssd = 3.912023,
                    eliteArtifactPercent = 2.5,
                    resonanceScore = 75.0,
                    confidence = 0.8,
                    usableDataFraction = 0.95,
                    ease = 4,
                    notes = "note, \"quoted\"\nnext line",
                    analysisVersion = 3,
                ),
            ),
            rrSamples = listOf(
                RrSampleEntity(
                    id = 10,
                    sessionId = 1,
                    elapsedRealtimeMs = 2_000,
                    rawRrMs = 1_000,
                    analysisRrMs = 1_000.0,
                    qualityFlags = "",
                    heartRateBpm = 60,
                    contactStatus = true,
                ),
            ),
            hrvMeasurements = listOf(
                HrvMeasurementEntity(
                    id = 20,
                    sessionId = 1,
                    elapsedRealtimeMs = 2_000,
                    windowDurationMs = 15_000,
                    rrIntervalCount = 15,
                    rmssdMs = 50.0,
                    lnRmssd = 3.912023,
                    displayedHrvScore = 60.0,
                    unroundedHrvScore = 60.184969,
                ),
            ),
            breathingSegments = listOf(
                BreathingSegmentEntity(
                    id = 30,
                    sessionId = 1,
                    startedAtElapsedMs = 0,
                    breathsPerMinute = 6.0,
                    inhaleFraction = 0.4,
                    controllerAction = "START",
                    reason = "start, then hold",
                ),
            ),
            analysisWindows = listOf(
                AnalysisWindowEntity(
                    id = 40,
                    sessionId = 1,
                    endedAtElapsedMs = 75_000,
                    breathsPerMinute = 6.0,
                    score = 75.0,
                    confidence = 0.8,
                    targetAmplitudeBpm = 6.0,
                    regularity = 0.9,
                    spectralConcentration = 0.8,
                    dominantFrequencyHz = 0.1,
                    usableDataFraction = 0.95,
                    qualified = true,
                    rejectionReason = null,
                ),
            ),
        )
        val entries = unzip(archive)

        assertEquals(
            setOf(
                "README.txt",
                "sessions.csv",
                "raw_rr_samples.csv",
                "hrv_measurements.csv",
                "breathing_segments.csv",
                "analysis_windows.csv",
            ),
            entries.keys,
        )
        assertContains(entries.getValue("sessions.csv"), "60.184969")
        assertContains(entries.getValue("sessions.csv"), "3.912023")
        assertContains(entries.getValue("sessions.csv"), "\"note, \"\"quoted\"\"\nnext line\"")
        assertContains(entries.getValue("raw_rr_samples.csv"), "10,1,2000,1000,1000.0")
        assertContains(entries.getValue("hrv_measurements.csv"), "20,1,2000,15000,15,50.0,3.912023,60.0,60.184969")
        assertContains(entries.getValue("breathing_segments.csv"), "\"start, then hold\"")
        assertTrue(archive.isNotEmpty())
    }

    private fun unzip(archive: ByteArray): Map<String, String> = buildMap {
        ZipInputStream(ByteArrayInputStream(archive)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                put(entry.name, zip.readBytes().toString(Charsets.UTF_8))
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
    }
}
