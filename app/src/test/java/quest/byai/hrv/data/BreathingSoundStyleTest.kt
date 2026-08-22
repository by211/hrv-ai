package quest.byai.hrv.data

import kotlin.test.Test
import kotlin.test.assertEquals

class BreathingSoundStyleTest {
    @Test
    fun `missing and removed gentle chime preference migrate to relaxed breathing`() {
        assertEquals(BreathingSoundStyle.RELAXED_BREATHING, BreathingSoundStyle.fromStoredName(null))
        assertEquals(
            BreathingSoundStyle.RELAXED_BREATHING,
            BreathingSoundStyle.fromStoredName("GENTLE_CHIMES"),
        )
    }

    @Test
    fun `current saved styles remain selected`() {
        BreathingSoundStyle.entries.forEach { style ->
            assertEquals(style, BreathingSoundStyle.fromStoredName(style.name))
        }
    }
}
