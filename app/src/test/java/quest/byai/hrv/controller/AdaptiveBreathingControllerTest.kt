package quest.byai.hrv.controller

import quest.byai.hrv.domain.ResonanceObservation
import org.junit.Assert.assertEquals
import org.junit.Test

class AdaptiveBreathingControllerTest {
    private val controller = AdaptiveBreathingController()

    @Test
    fun `holds when confidence is insufficient`() {
        val state = ControllerState()

        val decision = controller.next(state, observation(score = 70.0, confidence = 0.3))

        assertEquals(ControllerAction.HOLD, decision.action)
        assertEquals(6.0, decision.nextRate, 0.001)
        assertEquals("Confidence 30% < 55%; holding 6.0 breaths/min", decision.reason)
    }

    @Test
    fun `establishes baseline then explores slower`() {
        val decision = controller.next(ControllerState(), observation(score = 70.0))

        assertEquals(ControllerAction.EXPLORE, decision.action)
        assertEquals(5.8, decision.nextRate, 0.001)
        assertEquals(70.0, decision.state.acceptedScore!!, 0.001)
        assertEquals("Baseline 6.0 scored 70.0; next 5.8 breaths/min", decision.reason)
    }

    @Test
    fun `accepts a meaningful improvement and keeps direction`() {
        val baseline = controller.next(ControllerState(), observation(score = 70.0))

        val improved = controller.next(baseline.state, observation(score = 76.0, rate = 5.8))

        assertEquals(ControllerAction.ACCEPT_AND_EXPLORE, improved.action)
        assertEquals(5.8, improved.state.acceptedRate, 0.001)
        assertEquals(5.6, improved.nextRate, 0.001)
        assertEquals("New baseline 5.8 scored 76.0 (was 6.0 at 70.0); next 5.6 breaths/min", improved.reason)
    }

    @Test
    fun `reverses with a fine step when candidate is worse`() {
        val baseline = controller.next(ControllerState(), observation(score = 70.0))

        val worse = controller.next(baseline.state, observation(score = 68.0, rate = 5.8))

        assertEquals(ControllerAction.REVERT_AND_EXPLORE, worse.action)
        assertEquals(6.1, worse.nextRate, 0.001)
        assertEquals(0.1, worse.state.step, 0.001)
        assertEquals("Kept baseline 6.0 at 70.0; 5.8 scored 68.0; next 6.1 breaths/min", worse.reason)
    }

    private fun observation(
        score: Double,
        confidence: Double = 0.8,
        rate: Double = 6.0,
    ) = ResonanceObservation(
        breathsPerMinute = rate,
        usableDataFraction = 0.99,
        durationSeconds = 90.0,
        targetAmplitudeBpm = 8.0,
        waveformRegularity = 0.9,
        spectralConcentration = 0.8,
        dominantFrequencyHz = rate / 60.0,
        frequencyErrorHz = 0.0,
        peakToTroughBpm = 16.0,
        rmssdMs = 60.0,
        sdnnMs = 80.0,
        score = score,
        confidence = confidence,
        isQualified = true,
    )
}
