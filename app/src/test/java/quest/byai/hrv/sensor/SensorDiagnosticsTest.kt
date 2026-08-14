package quest.byai.hrv.sensor

import quest.byai.hrv.domain.HeartRateSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SensorDiagnosticsTest {
    @Test
    fun `counts heart rate without falsely confirming the RR stream`() {
        val diagnostics = SensorDiagnostics().withSample(
            sample(rrAvailable = false, rrIntervalsMs = emptyList()),
        )

        assertEquals(1L, diagnostics.heartRateSampleCount)
        assertEquals(0L, diagnostics.rrIntervalCount)
        assertFalse(diagnostics.rrStreamConfirmed)
        assertEquals("Heart rate received; waiting for R-R data", diagnostics.lastEvent)
    }

    @Test
    fun `counts all intervals and confirms the RR stream`() {
        val diagnostics = SensorDiagnostics().withSample(
            sample(rrAvailable = true, rrIntervalsMs = listOf(980, 1_020)),
        )

        assertEquals(1L, diagnostics.heartRateSampleCount)
        assertEquals(2L, diagnostics.rrIntervalCount)
        assertEquals(1_020, diagnostics.lastRrMs)
        assertTrue(diagnostics.rrStreamConfirmed)
        assertEquals("Heart rate and R-R data received", diagnostics.lastEvent)
    }

    private fun sample(rrAvailable: Boolean, rrIntervalsMs: List<Int>) = HeartRateSample(
        deviceId = "ABC123",
        receivedElapsedRealtimeNanos = 12_345_000_000L,
        heartRateBpm = 62,
        rrIntervalsMs = rrIntervalsMs,
        rrAvailable = rrAvailable,
        contactStatus = true,
    )
}
