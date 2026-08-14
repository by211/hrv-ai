package quest.byai.hrv.session

import quest.byai.hrv.domain.RrQualityFlag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RrTimestampReconstructorTest {
    @Test
    fun `reconstructs every interval when one notification contains multiple beats`() {
        val reconstructor = RrTimestampReconstructor()

        val samples = reconstructor.reconstruct(
            receiveElapsedMs = 10_000,
            rrIntervalsMs = listOf(900, 1_000, 1_100),
            contactStatus = true,
        )

        assertEquals(listOf(7_900L, 8_900L, 10_000L), samples.map { it.elapsedRealtimeMs })
        assertEquals(listOf(900, 1_000, 1_100), samples.map { it.rawRrMs })
    }

    @Test
    fun `marks a gap and contact loss without inventing a replacement beat`() {
        val reconstructor = RrTimestampReconstructor()
        reconstructor.reconstruct(1_000, listOf(1_000), true)

        val sample = reconstructor.reconstruct(6_000, listOf(1_000), false).single()

        assertTrue(RrQualityFlag.BLE_GAP in sample.qualityFlags)
        assertTrue(RrQualityFlag.CONTACT_LOST in sample.qualityFlags)
        assertEquals(null, sample.analysisRrMs)
    }
}
