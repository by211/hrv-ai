package quest.byai.hrv.sensor

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HeartRateMeasurementParserTest {
    @Test
    fun `parses 8-bit heart rate contact and one RR interval`() {
        val result = HeartRateMeasurementParser.parse(
            byteArrayOf(
                0x16,
                60,
                0x00,
                0x04,
            ),
        )

        requireNotNull(result)
        assertEquals(60, result.heartRateBpm)
        assertEquals(listOf(1_000), result.rrIntervalsMs)
        assertTrue(result.rrAvailable)
        assertEquals(true, result.contactStatus)
    }

    @Test
    fun `parses 16-bit heart rate energy and multiple RR intervals`() {
        val result = HeartRateMeasurementParser.parse(
            byteArrayOf(
                0x19,
                0x2C,
                0x01,
                0x34,
                0x12,
                0x00,
                0x04,
                0x33,
                0x03,
            ),
        )

        requireNotNull(result)
        assertEquals(300, result.heartRateBpm)
        assertEquals(listOf(1_000, 800), result.rrIntervalsMs)
        assertTrue(result.rrAvailable)
        assertNull(result.contactStatus)
    }

    @Test
    fun `parses a measurement without RR support`() {
        val result = HeartRateMeasurementParser.parse(byteArrayOf(0x00, 72))

        requireNotNull(result)
        assertEquals(72, result.heartRateBpm)
        assertTrue(result.rrIntervalsMs.isEmpty())
        assertFalse(result.rrAvailable)
        assertNull(result.contactStatus)
    }

    @Test
    fun `rejects truncated fields without throwing`() {
        assertNull(HeartRateMeasurementParser.parse(byteArrayOf()))
        assertNull(HeartRateMeasurementParser.parse(byteArrayOf(0x01, 0x2C)))
        assertNull(HeartRateMeasurementParser.parse(byteArrayOf(0x08, 60, 0x01)))
    }
}
