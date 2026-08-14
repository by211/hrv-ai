package quest.byai.hrv.signal

import quest.byai.hrv.domain.RrQualityFlag
import quest.byai.hrv.domain.RrSample
import kotlin.math.abs

data class ArtifactConfig(
    val minimumRrMs: Int = 300,
    val maximumRrMs: Int = 2_000,
    val localWindowSize: Int = 5,
    val maximumRelativeDeviation: Double = 0.30,
    val maximumInterpolationFraction: Double = 0.05,
)

class RrArtifactClassifier(
    private val config: ArtifactConfig = ArtifactConfig(),
) {
    fun classify(samples: List<RrSample>): List<RrSample> {
        if (samples.isEmpty()) return emptyList()

        val rangeClassified = samples.map { sample ->
            if (sample.rawRrMs !in config.minimumRrMs..config.maximumRrMs) {
                sample.copy(
                    analysisRrMs = null,
                    qualityFlags = sample.qualityFlags + RrQualityFlag.OUT_OF_RANGE,
                )
            } else {
                sample
            }
        }

        val deviationClassified = rangeClassified.mapIndexed { index, sample ->
            if (sample.analysisRrMs == null || sample.qualityFlags.isNotEmpty()) return@mapIndexed sample

            val fromIndex = (index - config.localWindowSize).coerceAtLeast(0)
            val toIndex = (index + config.localWindowSize + 1).coerceAtMost(rangeClassified.size)
            val neighbors = rangeClassified.subList(fromIndex, toIndex)
                .filterIndexed { localIndex, candidate ->
                    fromIndex + localIndex != index && candidate.analysisRrMs != null
                }
                .map { it.rawRrMs }
                .sorted()

            if (neighbors.size < 3) return@mapIndexed sample
            val localMedian = median(neighbors)
            val relativeDeviation = abs(sample.rawRrMs - localMedian) / localMedian
            if (relativeDeviation > config.maximumRelativeDeviation) {
                sample.copy(
                    analysisRrMs = null,
                    qualityFlags = sample.qualityFlags + RrQualityFlag.ABRUPT_DEVIATION,
                )
            } else {
                sample
            }
        }

        val rejectedCount = deviationClassified.count { it.analysisRrMs == null }
        if (rejectedCount == 0) return deviationClassified
        if (rejectedCount.toDouble() / deviationClassified.size > config.maximumInterpolationFraction) {
            return deviationClassified
        }

        return deviationClassified.mapIndexed { index, sample ->
            if (sample.analysisRrMs != null) return@mapIndexed sample
            val previous = deviationClassified.subList(0, index).lastOrNull { it.analysisRrMs != null }
            val next = deviationClassified.subList(index + 1, deviationClassified.size)
                .firstOrNull { it.analysisRrMs != null }
            if (previous == null || next == null) sample
            else sample.copy(analysisRrMs = (previous.analysisRrMs!! + next.analysisRrMs!!) / 2.0)
        }
    }

    private fun median(values: List<Int>): Double {
        val middleIndex = values.size / 2
        return if (values.size % 2 == 0) {
            (values[middleIndex - 1] + values[middleIndex]) / 2.0
        } else {
            values[middleIndex].toDouble()
        }
    }
}
