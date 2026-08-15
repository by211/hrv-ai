package quest.byai.hrv.sensor

import java.util.concurrent.CancellationException
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

class SensorFailureTest {
    @Test
    fun `unwraps the Polar channel wrapper to a disconnected cause`() {
        val disconnected = BleDisconnectedForTest()
        val wrapper = CancellationException("Channel closed due to error").apply {
            initCause(disconnected)
        }

        val failure = wrapper.toSensorFailure("Heart-rate stream stopped")

        assertEquals("Heart-rate stream stopped: sensor disconnected", failure.userMessage)
        assertContains(failure.technicalDetails, "CancellationException: Channel closed due to error")
        assertContains(failure.technicalDetails, "BleDisconnectedForTest")
    }

    @Test
    fun `uses the deepest useful message for other failures`() {
        val wrapper = IllegalStateException("outer", IllegalArgumentException("GATT status 133"))

        val failure = wrapper.toSensorFailure("Unable to connect")

        assertEquals("Unable to connect: GATT status 133", failure.userMessage)
        assertContains(failure.technicalDetails, "IllegalArgumentException: GATT status 133")
    }

    private class BleDisconnectedForTest : RuntimeException()
}
