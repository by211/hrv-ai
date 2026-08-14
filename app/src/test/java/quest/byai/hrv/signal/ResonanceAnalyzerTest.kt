package quest.byai.hrv.signal

import quest.byai.hrv.domain.BreathingCue
import quest.byai.hrv.domain.RrQualityFlag
import quest.byai.hrv.domain.RrSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class ResonanceAnalyzerTest {
    private val analyzer = ResonanceAnalyzer()

    @Test
    fun `identifies a clean six breath per minute response`() {
        val samples = syntheticRrSamples(
            frequencyHz = 0.1,
            amplitudeBpm = 8.0,
            durationSeconds = 100.0,
        )

        val observation = analyzer.analyze(samples, BreathingCue(6.0))

        assertTrue(observation.isQualified)
        assertEquals(0.1, observation.dominantFrequencyHz, 0.006)
        assertTrue(observation.targetAmplitudeBpm > 6.0)
        assertTrue(observation.waveformRegularity > 0.70)
        assertTrue(observation.score > 70.0)
    }

    @Test
    fun `correct cue scores higher than a mismatched cue`() {
        val samples = syntheticRrSamples(
            frequencyHz = 0.1,
            amplitudeBpm = 7.0,
            durationSeconds = 100.0,
        )

        val correct = analyzer.analyze(samples, BreathingCue(6.0))
        val mismatched = analyzer.analyze(samples, BreathingCue(5.0))

        assertTrue(correct.score > mismatched.score + 20.0)
    }

    @Test
    fun `rejects a short evaluation window`() {
        val samples = syntheticRrSamples(
            frequencyHz = 0.1,
            amplitudeBpm = 8.0,
            durationSeconds = 35.0,
        )

        val observation = analyzer.analyze(samples, BreathingCue(6.0))

        assertFalse(observation.isQualified)
        assertEquals("Evaluation window is too short", observation.rejectionReason)
    }

    @Test
    fun `classifier flags and interpolates one isolated missed beat`() {
        val raw = syntheticRrSamples(0.1, 5.0, 80.0).toMutableList()
        val index = raw.size / 2
        raw[index] = raw[index].copy(rawRrMs = 1_900, analysisRrMs = 1_900.0)

        val classified = RrArtifactClassifier().classify(raw)

        assertTrue(RrQualityFlag.ABRUPT_DEVIATION in classified[index].qualityFlags)
        assertTrue(classified[index].analysisRrMs != null)
        assertTrue(classified[index].analysisRrMs!! < 1_200.0)
    }

    private fun syntheticRrSamples(
        frequencyHz: Double,
        amplitudeBpm: Double,
        durationSeconds: Double,
        meanHeartRateBpm: Double = 65.0,
    ): List<RrSample> {
        val samples = mutableListOf<RrSample>()
        var elapsedMs = 0.0
        while (elapsedMs < durationSeconds * 1_000.0) {
            val elapsedSeconds = elapsedMs / 1_000.0
            val heartRate = meanHeartRateBpm + amplitudeBpm * sin(2.0 * PI * frequencyHz * elapsedSeconds)
            val rrMs = (60_000.0 / heartRate).toInt()
            elapsedMs += rrMs
            samples += RrSample(
                elapsedRealtimeMs = elapsedMs.toLong(),
                rawRrMs = rrMs,
            )
        }
        return samples
    }
}
