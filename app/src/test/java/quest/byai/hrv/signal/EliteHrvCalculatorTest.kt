package quest.byai.hrv.signal

import kotlin.math.abs
import kotlin.math.ln
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class EliteHrvCalculatorTest {
    private val calculator = EliteHrvCalculator()

    @Test
    fun `converts raw 1024 Hz RR units without premature rounding`() {
        assertClose(500.0, calculator.convertRawTicksToMilliseconds(512))
        assertClose(799.8046875, calculator.convertRawTicksToMilliseconds(819))
        assertClose(1_000.0, calculator.convertRawTicksToMilliseconds(1_024))
    }

    @Test
    fun `display transform reproduces Elite low threshold scaling and clamp`() {
        assertClose(0.1, calculator.displayScore(0.0))
        assertClose(0.1, calculator.displayScore(0.099))
        assertClose(2.0, calculator.displayScore(0.1))
        assertClose(60.0, calculator.displayScore(ln(50.0)))
        assertClose(100.0, calculator.displayScore(7.0))
    }

    @Test
    fun `known clean sequence produces Elite completed metrics`() {
        val series = listOf(1_000.0, 1_100.0, 900.0, 1_000.0)
        val result = calculator.calculateTimeDomain(
            EliteCorrectedRrSeries(
                deletion = series,
                zeroOrder = series,
                linear = series,
                artifactPercent = 0.0,
                correctedArtifactPercent = 0.0,
                artifactCount = 0,
                correctedArtifactCount = 0,
            ),
        )

        assertClose(141.4213562373095, result.rmssdMs)
        assertClose(4.951743776268064, result.lnRmssd)
        assertClose(76.0, result.score)
        assertClose(76.18067348104714, result.unroundedScore)
        assertClose(81.64965809277261, assertNotNull(result.sdnnMs))
        assertEquals(3, result.nn50)
        assertClose(100.0, assertNotNull(result.pnn50))
        assertClose(1_000.0, assertNotNull(result.meanRrMs))
        assertClose(60.303030303030305, assertNotNull(result.averageHeartRateBpm))
        assertClose(54.54545454545455, assertNotNull(result.minimumHeartRateBpm))
        assertClose(66.66666666666667, assertNotNull(result.maximumHeartRateBpm))
        assertClose(4_000.0, assertNotNull(result.durationMs))
    }

    @Test
    fun `completed metrics use Elite specific corrected series`() {
        val result = calculator.calculateTimeDomain(
            EliteCorrectedRrSeries(
                deletion = listOf(1_000.0, 1_100.0, 900.0, 1_000.0),
                zeroOrder = listOf(1_000.0, 1_050.0, 1_000.0, 1_050.0),
                linear = listOf(1_000.0, 1_050.0, 999.0),
                artifactPercent = 0.0,
                correctedArtifactPercent = 0.0,
                artifactCount = 0,
                correctedArtifactCount = 0,
            ),
        )

        assertClose(81.64965809277261, assertNotNull(result.sdnnMs))
        assertClose(50.0, result.rmssdMs)
        assertEquals(1, result.nn50)
        assertClose(50.0, assertNotNull(result.pnn50))
        assertClose(3_049.0, assertNotNull(result.durationMs))
    }

    @Test
    fun `pNN50 comparison is strictly greater than fifty milliseconds`() {
        val series = listOf(1_000.0, 1_050.0, 999.0)
        val result = calculator.calculateTimeDomain(
            EliteCorrectedRrSeries(series, series, series, 0.0, 0.0, 0, 0),
        )

        assertEquals(1, result.nn50)
        assertClose(50.0, assertNotNull(result.pnn50))
    }

    @Test
    fun `live window includes the RR interval that crosses fifteen seconds`() {
        val selected = calculator.selectTrailingWindow(
            listOf(4_000.0, 4_000.0, 4_000.0, 4_000.0, 4_000.0),
            15_000.0,
        )

        assertEquals(listOf(4_000.0, 4_000.0, 4_000.0, 4_000.0), selected)
    }

    @Test
    fun `live cleaner removes broad IQR outlier`() {
        val cleaned = calculator.cleanLiveRrIntervals(
            listOf(1_000.0, 1_000.0, 2_000.0, 1_000.0, 1_000.0),
        )

        assertEquals(listOf(1_000.0, 1_000.0, 1_000.0, 1_000.0), cleaned)
    }

    @Test
    fun `live cleaner replaces local deviation over 450 milliseconds`() {
        val cleaned = assertNotNull(
            calculator.cleanLiveRrIntervals(
                listOf(
                    800.0,
                    1_200.0,
                    800.0,
                    1_200.0,
                    800.0,
                    1_700.0,
                    800.0,
                    1_200.0,
                    800.0,
                    1_200.0,
                    800.0,
                    1_200.0,
                ),
            ),
        )

        assertClose(800.0, cleaned[5])
    }

    @Test
    fun `zero and linear correction use nearest clean heartbeat timestamps`() {
        val raw = listOf(900.0, 2_000.0, 1_100.0, 1_200.0)
        val zeroOrder = calculator.zeroOrderInterpolate(raw, setOf(1))
        val linear = calculator.linearInterpolate(raw, setOf(1))

        assertClose(1_066.6666666666667, zeroOrder[1])
        assertClose(996.6666666666666, linear[1])
    }

    @Test
    fun `live RMSSD fifty maps to displayed score sixty`() {
        val repeating = buildList {
            repeat(8) {
                add(1_000.0)
                add(1_050.0)
            }
        }
        val result = assertNotNull(calculator.calculateLive(repeating))

        assertClose(50.0, result.rmssdMs)
        assertClose(60.0, result.score)
    }

    @Test
    fun `completed clean recording preserves Elite RMSSD and unrounded score`() {
        val repeating = buildList {
            repeat(20) {
                add(1_000.0)
                add(1_050.0)
            }
        }
        val result = assertNotNull(calculator.calculateCompleted(repeating))

        assertClose(50.0, result.rmssdMs)
        assertClose(3.912023005428146, result.lnRmssd)
        assertClose(60.0, result.score)
        assertClose(60.18496931427917, result.unroundedScore)
        assertEquals(0, result.artifactCount)
    }

    @Test
    fun `completed recording rejects absolute RR artifact without losing result`() {
        val recording = buildList {
            repeat(20) {
                add(1_000.0)
                add(1_050.0)
            }
        }.toMutableList().apply { this[20] = 5_000.0 }

        val result = assertNotNull(calculator.calculateCompleted(recording))

        assertTrue(result.artifactCount >= 1)
        assertTrue(result.artifactPercent > 0.0)
        assertTrue(result.rmssdMs.isFinite())
        assertTrue(result.score in 0.0..100.0)
    }

    private fun assertClose(expected: Double, actual: Double, tolerance: Double = 1e-9) {
        assertTrue(abs(expected - actual) <= tolerance, "Expected $expected but was $actual")
    }
}
