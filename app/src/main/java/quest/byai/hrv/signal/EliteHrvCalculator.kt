package quest.byai.hrv.signal

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.sqrt

data class EliteHrvResult(
    val rmssdMs: Double,
    val lnRmssd: Double,
    val score: Double,
    val unroundedScore: Double,
    val sdnnMs: Double? = null,
    val nn50: Int? = null,
    val pnn50: Double? = null,
    val meanRrMs: Double? = null,
    val averageHeartRateBpm: Double? = null,
    val minimumHeartRateBpm: Double? = null,
    val maximumHeartRateBpm: Double? = null,
    val durationMs: Double? = null,
    val artifactPercent: Double = 0.0,
    val correctedArtifactPercent: Double = 0.0,
    val artifactCount: Int = 0,
    val correctedArtifactCount: Int = 0,
    val correctedRrMs: List<Double> = emptyList(),
)

internal data class EliteCorrectedRrSeries(
    val deletion: List<Double>,
    val zeroOrder: List<Double>,
    val linear: List<Double>,
    val artifactPercent: Double,
    val correctedArtifactPercent: Double,
    val artifactCount: Int,
    val correctedArtifactCount: Int,
)

class EliteHrvCalculator {
    fun calculateLive(rrIntervalsMs: List<Double>): EliteHrvResult? {
        val rollingWindow = selectTrailingWindow(rrIntervalsMs, LIVE_WINDOW_MS)
        val cleaned = cleanLiveRrIntervals(rollingWindow) ?: return null
        val rmssd = rmssd(cleaned)
        val lnRmssd = if (rmssd >= MINIMUM_RMSSD_FOR_LOG_MS) ln(rmssd) else 0.0
        return EliteHrvResult(
            rmssdMs = rmssd,
            lnRmssd = lnRmssd,
            score = displayScore(lnRmssd),
            unroundedScore = ELITE_SCORE_SCALE * lnRmssd,
            correctedRrMs = cleaned,
        )
    }

    fun calculateCompleted(rrIntervalsMs: List<Double>): EliteHrvResult? {
        if (rrIntervalsMs.size < 2) return null
        val corrected = correctCompletedRrIntervals(rrIntervalsMs) ?: return null
        return calculateTimeDomain(corrected)
    }

    internal fun convertRawTicksToMilliseconds(rawRrTicks: Int): Double =
        rawRrTicks * MILLISECONDS_PER_RAW_TICK

    internal fun displayScore(lnRmssd: Double): Double {
        if (!lnRmssd.isFinite() || lnRmssd < MINIMUM_LOG_FOR_INTEGER_SCORE) return MINIMUM_DISPLAY_SCORE
        return round((ELITE_SCORE_SCALE * lnRmssd).coerceIn(MINIMUM_INTEGER_SCORE, MAXIMUM_DISPLAY_SCORE))
    }

    internal fun selectTrailingWindow(rrIntervalsMs: List<Double>, windowMs: Double): List<Double> {
        var accumulatedMs = 0.0
        val selected = ArrayDeque<Double>()
        for (index in rrIntervalsMs.lastIndex downTo 0) {
            val rrMs = rrIntervalsMs[index]
            selected.addFirst(rrMs)
            accumulatedMs += rrMs
            if (accumulatedMs >= windowMs) break
        }
        return selected.toList()
    }

    internal fun cleanLiveRrIntervals(rrIntervalsMs: List<Double>): List<Double>? {
        if (rrIntervalsMs.isEmpty()) return null
        val absoluteFiltered = rrIntervalsMs.filter { it > LIVE_MINIMUM_RR_MS && it < LIVE_MAXIMUM_RR_MS }
        if (absoluteFiltered.size <= 1) return null

        val sorted = absoluteFiltered.sorted()
        val firstQuartile = sorted[floor(sorted.size / 4.0).toInt()]
        val thirdQuartile = sorted[floor(3.0 * sorted.size / 4.0).toInt()]
        val interquartileRange = thirdQuartile - firstQuartile
        val lowerBound = firstQuartile - LIVE_IQR_MULTIPLIER * interquartileRange
        val upperBound = thirdQuartile + LIVE_IQR_MULTIPLIER * interquartileRange
        val distributionFiltered = absoluteFiltered.filter { it in lowerBound..upperBound }
        if (distributionFiltered.size <= 1) return null

        val corrected = distributionFiltered.toMutableList()
        val timestamps = startTimestamps(corrected)
        val replacedIndexes = mutableSetOf<Int>()
        liveWindows(timestamps).forEach { window ->
            val indexes = timestamps.indices.filter { timestamps[it] in window.startMs..window.endMs }
            if (indexes.isEmpty()) return@forEach
            val windowMean = indexes.map(corrected::get).average()
            indexes.forEach { index ->
                val rrMs = corrected[index]
                if (rrMs < windowMean - LIVE_LOCAL_DEVIATION_MS || rrMs > windowMean + LIVE_LOCAL_DEVIATION_MS) {
                    if (!replacedIndexes.add(index)) return@forEach
                    corrected[index] = if (index == 0 || index == corrected.lastIndex) {
                        windowMean
                    } else {
                        interpolateBetweenImmediateNeighbors(timestamps, corrected, index)
                    }
                }
            }
        }
        return corrected
    }

    internal fun zeroOrderInterpolate(rrIntervalsMs: List<Double>, artifactIndexes: Set<Int>): List<Double> {
        if (artifactIndexes.isEmpty()) return rrIntervalsMs.toList()
        val corrected = rrIntervalsMs.toMutableList()
        val timestamps = midpointTimestamps(rrIntervalsMs)
        artifactIndexes.sorted().forEach { artifactIndex ->
            val closestCleanIndexes = timestamps.indices
                .asSequence()
                .filter { it != artifactIndex && it !in artifactIndexes }
                .sortedBy { abs(timestamps[it] - timestamps[artifactIndex]) }
                .take(ZERO_ORDER_NEIGHBOR_COUNT)
                .toList()
            require(closestCleanIndexes.isNotEmpty()) { "Not enough clean R-R intervals for zero-order correction" }
            corrected[artifactIndex] = closestCleanIndexes.map(corrected::get).average()
        }
        return corrected
    }

    internal fun linearInterpolate(rrIntervalsMs: List<Double>, artifactIndexes: Set<Int>): List<Double> {
        if (artifactIndexes.isEmpty()) return rrIntervalsMs.toList()
        val corrected = rrIntervalsMs.toMutableList()
        val timestamps = midpointTimestamps(rrIntervalsMs)
        artifactIndexes.sorted().forEach { artifactIndex ->
            val closestCleanIndexes = timestamps.indices
                .asSequence()
                .filter { it != artifactIndex && it !in artifactIndexes }
                .sortedBy { abs(timestamps[it] - timestamps[artifactIndex]) }
                .take(LINEAR_NEIGHBOR_COUNT)
                .toList()
            require(closestCleanIndexes.size == LINEAR_NEIGHBOR_COUNT) {
                "Not enough clean R-R intervals for linear correction"
            }
            val firstIndex = closestCleanIndexes[0]
            val secondIndex = closestCleanIndexes[1]
            val firstTime = timestamps[firstIndex]
            val secondTime = timestamps[secondIndex]
            corrected[artifactIndex] = corrected[firstIndex] +
                (corrected[secondIndex] - corrected[firstIndex]) /
                (secondTime - firstTime) *
                (timestamps[artifactIndex] - firstTime)
        }
        return corrected
    }

    internal fun calculateTimeDomain(series: EliteCorrectedRrSeries): EliteHrvResult {
        val rmssd = rmssd(series.zeroOrder)
        val lnRmssd = if (rmssd > MINIMUM_RMSSD_FOR_LOG_MS) ln(rmssd) else 0.0
        val differences = series.linear.zipWithNext { first, second -> second - first }
        val nn50 = differences.count { abs(it) > NN50_THRESHOLD_MS }
        val pnn50 = if (differences.isEmpty()) 0.0 else nn50.toDouble() / differences.size * 100.0
        val meanRr = series.deletion.average()
        return EliteHrvResult(
            rmssdMs = rmssd,
            lnRmssd = lnRmssd,
            score = displayScore(lnRmssd),
            unroundedScore = ELITE_SCORE_SCALE * lnRmssd,
            sdnnMs = sampleStandardDeviation(series.deletion),
            nn50 = nn50,
            pnn50 = pnn50,
            meanRrMs = meanRr,
            averageHeartRateBpm = series.deletion.map { MILLISECONDS_PER_MINUTE / it }.average(),
            minimumHeartRateBpm = MILLISECONDS_PER_MINUTE / series.deletion.max(),
            maximumHeartRateBpm = MILLISECONDS_PER_MINUTE / series.deletion.min(),
            durationMs = series.linear.sum(),
            artifactPercent = series.artifactPercent,
            correctedArtifactPercent = series.correctedArtifactPercent,
            artifactCount = series.artifactCount,
            correctedArtifactCount = series.correctedArtifactCount,
            correctedRrMs = series.deletion,
        )
    }

    private fun correctCompletedRrIntervals(rrIntervalsMs: List<Double>): EliteCorrectedRrSeries? {
        val detection = detectCompletedArtifacts(rrIntervalsMs)
        val processedRr = detection.processedRrMs
        val artifactIndexes = selectArtifactIndexes(processedRr, detection.flags)
        if (artifactIndexes.size >= processedRr.size - 1) return null
        val deletion = processedRr.filterIndexed { index, _ -> index !in artifactIndexes }
        val zeroOrder = zeroOrderInterpolate(processedRr, artifactIndexes)
        val linear = linearInterpolate(processedRr, artifactIndexes)
        val correctedCount = detection.flags.count { it.isArtifact && it.corrected }
        val uncorrectedCount = detection.flags.count { it.isArtifact && !it.corrected }
        val artifactCount = correctedCount + uncorrectedCount
        val artifactPercent = artifactCount.toDouble() / processedRr.size * 100.0
        val correctedPercent = if (artifactCount == 0) 0.0 else correctedCount.toDouble() / artifactCount * 100.0
        return EliteCorrectedRrSeries(
            deletion = deletion,
            zeroOrder = zeroOrder,
            linear = linear,
            artifactPercent = artifactPercent,
            correctedArtifactPercent = correctedPercent,
            artifactCount = artifactCount,
            correctedArtifactCount = correctedCount,
        )
    }

    private fun detectCompletedArtifacts(rrIntervalsMs: List<Double>): DetectionResult {
        if (rrIntervalsMs.size <= COMPLETED_WINDOW_THRESHOLD) return detectWindow(rrIntervalsMs)
        var windowCount = ceil(rrIntervalsMs.size / COMPLETED_WINDOW_SIZE.toDouble()).toInt()
        if (rrIntervalsMs.size - COMPLETED_WINDOW_SIZE * (windowCount - 1) < COMPLETED_MINIMUM_FINAL_WINDOW) {
            windowCount -= 1
        }
        val processed = mutableListOf<Double>()
        val flags = mutableListOf<ArtifactFlag>()
        repeat(windowCount) { windowIndex ->
            val start = windowIndex * COMPLETED_WINDOW_SIZE
            val end = if (windowIndex == windowCount - 1) {
                rrIntervalsMs.size
            } else {
                min((windowIndex + 1) * COMPLETED_WINDOW_SIZE, rrIntervalsMs.size)
            }
            val windowResult = detectWindow(rrIntervalsMs.subList(start, end))
            processed += windowResult.processedRrMs
            flags += windowResult.flags
        }
        return DetectionResult(processed, flags)
    }

    private fun detectWindow(rrIntervalsMs: List<Double>): DetectionResult {
        val processed = rrIntervalsMs.toMutableList()
        val flags = MutableList(processed.size) { ArtifactFlag() }
        val threshold = adaptiveDifferenceThreshold(processed)
        var index = 0
        while (index < processed.size - 2) {
            if (abs(processed[index + 1] - processed[index]) <= threshold) {
                index += 1
                continue
            }
            flags[index + 1] = ArtifactFlag(isArtifact = true)
            if (index < 2 || abs(processed[index - 1] - processed[index - 2]) > threshold) {
                index += 1
                continue
            }

            if (processed[index + 1] > processed[index]) {
                correctPossibleMissedBeat(processed, flags, index, threshold)
            } else {
                correctPossibleExtraBeat(processed, flags, index, threshold)
            }
            index += 1
        }
        return DetectionResult(processed, flags)
    }

    private fun correctPossibleMissedBeat(
        rrIntervalsMs: MutableList<Double>,
        flags: MutableList<ArtifactFlag>,
        index: Int,
        threshold: Double,
    ) {
        if (index + 3 >= rrIntervalsMs.size) return
        if (abs(rrIntervalsMs[index + 3] - rrIntervalsMs[index + 2]) > threshold) return
        val halfInterval = floor(rrIntervalsMs[index + 1] / 2.0)
        val differsFromPrevious = abs(halfInterval - rrIntervalsMs[index]) > threshold
        val differsFromNext = abs(halfInterval - rrIntervalsMs[index + 2]) > threshold
        if (
            differsFromPrevious &&
            differsFromNext &&
            halfInterval < rrIntervalsMs[index] &&
            halfInterval < rrIntervalsMs[index + 2]
        ) {
            flags[index + 1] = ArtifactFlag()
            return
        }
        if (differsFromPrevious || differsFromNext) return

        val correctedFlag = ArtifactFlag(isArtifact = true, type = "long", corrected = true)
        rrIntervalsMs.removeAt(index + 1)
        rrIntervalsMs.add(index + 1, halfInterval)
        rrIntervalsMs.add(index + 2, halfInterval)
        flags[index + 1] = correctedFlag
        flags.add(index + 2, correctedFlag.copy())
    }

    private fun correctPossibleExtraBeat(
        rrIntervalsMs: MutableList<Double>,
        flags: MutableList<ArtifactFlag>,
        index: Int,
        threshold: Double,
    ) {
        if (index + 4 >= rrIntervalsMs.size) return
        if (abs(rrIntervalsMs[index + 4] - rrIntervalsMs[index + 3]) > threshold) return
        val removeOffset: Int
        val previousReferenceOffset: Int
        val nextReferenceOffset: Int
        if (rrIntervalsMs[index] < rrIntervalsMs[index + 2]) {
            removeOffset = 0
            previousReferenceOffset = -1
            nextReferenceOffset = 2
        } else {
            removeOffset = 2
            previousReferenceOffset = 0
            nextReferenceOffset = 3
        }
        val combinedInterval = rrIntervalsMs[index + 1] + rrIntervalsMs[index + removeOffset]
        val differsFromPrevious = abs(combinedInterval - rrIntervalsMs[index + previousReferenceOffset]) > threshold
        val differsFromNext = abs(combinedInterval - rrIntervalsMs[index + nextReferenceOffset]) > threshold
        if (
            differsFromPrevious &&
            differsFromNext &&
            combinedInterval > rrIntervalsMs[index + previousReferenceOffset] &&
            combinedInterval > rrIntervalsMs[index + nextReferenceOffset]
        ) {
            flags[index + 1] = ArtifactFlag()
            return
        }
        if (differsFromPrevious || differsFromNext) return

        flags[index + 1] = ArtifactFlag(isArtifact = true, type = "short", corrected = true)
        rrIntervalsMs[index + 1] = combinedInterval
        rrIntervalsMs.removeAt(index + removeOffset)
        flags.removeAt(index + removeOffset)
    }

    private fun selectArtifactIndexes(
        rrIntervalsMs: List<Double>,
        flags: List<ArtifactFlag>,
    ): Set<Int> {
        val suspiciousTriplets = flags.indices
            .filter { flags[it].isArtifact && !flags[it].corrected }
            .map { index ->
                listOf(
                    index,
                    (index - 1).coerceAtLeast(0),
                    (index + 1).coerceAtMost(rrIntervalsMs.lastIndex),
                )
            }
        val excludedFromCleanMean = mutableSetOf<Int>()
        flags.indices.forEach { index ->
            if (flags[index].isArtifact && !flags[index].corrected) {
                excludedFromCleanMean += index
                if (index > 0) excludedFromCleanMean += index - 1
            }
        }
        val cleanValues = rrIntervalsMs.filterIndexed { index, _ -> index !in excludedFromCleanMean }
        val cleanMean = cleanValues.takeIf(List<Double>::isNotEmpty)?.average() ?: rrIntervalsMs.average()
        val selected = mutableSetOf<Int>()
        suspiciousTriplets.forEach { triplet ->
            val ranked = triplet.distinct().sortedByDescending { abs(rrIntervalsMs[it] - cleanMean) }
            if (ranked.isEmpty()) return@forEach
            selected += ranked[0]
            ranked.drop(1).forEach { candidate ->
                if (
                    abs(rrIntervalsMs[candidate] - rrIntervalsMs[ranked[0]]) <
                    abs(rrIntervalsMs[candidate] - cleanMean)
                ) {
                    selected += candidate
                }
            }
        }
        rrIntervalsMs.indices.filterTo(selected) { isAbsoluteArtifact(rrIntervalsMs[it]) }

        selected.sorted().zipWithNext().forEach { (first, second) ->
            val gap = second - first
            if (gap in 2 until ARTIFACT_RUN_MAXIMUM_GAP) {
                for (index in first + 1 until second) {
                    if (abs(rrIntervalsMs[index] - rrIntervalsMs[first]) < abs(rrIntervalsMs[index] - cleanMean)) {
                        selected += index
                    }
                }
            }
        }
        return selected
    }

    private fun adaptiveDifferenceThreshold(rrIntervalsMs: List<Double>): Double {
        val differences = rrIntervalsMs.zipWithNext { first, second -> abs(second - first) }
        val rrHalfInterquartileRange = (percentile(rrIntervalsMs, 0.75) - percentile(rrIntervalsMs, 0.25)) / 2.0
        val differenceHalfInterquartileRange =
            (percentile(differences, 0.75) - percentile(differences, 0.25)) / 2.0
        val distributionComponent = 3.32 * rrHalfInterquartileRange
        val medianComponent = (median(rrIntervalsMs) - 2.9 * differenceHalfInterquartileRange) / 3.0
        return (distributionComponent + medianComponent) / 2.0
    }

    private fun liveWindows(timestamps: List<Double>): List<LiveWindow> {
        if (timestamps.isEmpty()) return emptyList()
        val firstTimestamp = timestamps.first()
        val lastTimestamp = timestamps.last()
        val span = lastTimestamp - firstTimestamp
        val windowCount = max(1, ceil(span / LIVE_WINDOW_STEP_MS).toInt())
        return List(windowCount) { index ->
            var start = firstTimestamp + index * LIVE_WINDOW_STEP_MS
            var end = start + LIVE_CLEANING_WINDOW_MS
            if (end > lastTimestamp) {
                start -= end - lastTimestamp
                end = lastTimestamp
            }
            LiveWindow(start, end)
        }
    }

    private fun interpolateBetweenImmediateNeighbors(
        timestamps: List<Double>,
        rrIntervalsMs: List<Double>,
        index: Int,
    ): Double {
        val previousIndex = index - 1
        val nextIndex = index + 1
        val interpolated = rrIntervalsMs[previousIndex] +
            (rrIntervalsMs[nextIndex] - rrIntervalsMs[previousIndex]) *
            ((timestamps[index] - timestamps[previousIndex]) /
                (timestamps[nextIndex] - timestamps[previousIndex]))
        return round(interpolated)
    }

    private fun startTimestamps(rrIntervalsMs: List<Double>): List<Double> {
        var elapsedMs = 0.0
        return rrIntervalsMs.map { rrMs ->
            val timestamp = elapsedMs
            elapsedMs += rrMs
            timestamp
        }
    }

    private fun midpointTimestamps(rrIntervalsMs: List<Double>): List<Double> {
        var elapsedMs = 0.0
        return rrIntervalsMs.map { rrMs ->
            val timestamp = elapsedMs + rrMs / 2.0
            elapsedMs += rrMs
            timestamp
        }
    }

    private fun rmssd(rrIntervalsMs: List<Double>): Double {
        if (rrIntervalsMs.size < 2) return 0.0
        return sqrt(rrIntervalsMs.zipWithNext { first, second -> (second - first).pow(2) }.average())
    }

    private fun sampleStandardDeviation(values: List<Double>): Double {
        if (values.size < 2) return 0.0
        val mean = values.average()
        return sqrt(values.sumOf { (it - mean).pow(2) } / (values.size - 1))
    }

    private fun percentile(values: List<Double>, probability: Double): Double {
        if (values.isEmpty()) return 0.0
        val sorted = values.sorted()
        val position = (sorted.size - 1) * probability.coerceIn(0.0, 1.0)
        val lowerIndex = floor(position).toInt()
        val upperIndex = min(lowerIndex + 1, sorted.lastIndex)
        val fraction = position % 1.0
        return sorted[lowerIndex] * (1.0 - fraction) + sorted[upperIndex] * fraction
    }

    private fun median(values: List<Double>): Double {
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[middle] else (sorted[middle - 1] + sorted[middle]) / 2.0
    }

    private fun isAbsoluteArtifact(rrMs: Double): Boolean =
        rrMs < COMPLETED_MINIMUM_RR_MS || rrMs > COMPLETED_MAXIMUM_RR_MS

    private data class LiveWindow(val startMs: Double, val endMs: Double)
    private data class DetectionResult(
        val processedRrMs: List<Double>,
        val flags: List<ArtifactFlag>,
    )

    private data class ArtifactFlag(
        val isArtifact: Boolean = false,
        val type: String = "",
        val corrected: Boolean = false,
    )

    private companion object {
        const val MILLISECONDS_PER_RAW_TICK = 0.9765625
        const val MILLISECONDS_PER_MINUTE = 60_000.0
        const val LIVE_WINDOW_MS = 15_000.0
        const val LIVE_CLEANING_WINDOW_MS = 30_000.0
        const val LIVE_WINDOW_STEP_MS = 15_000.0
        const val LIVE_MINIMUM_RR_MS = 120.0
        const val LIVE_MAXIMUM_RR_MS = 4_000.0
        const val COMPLETED_MINIMUM_RR_MS = 120.0
        const val COMPLETED_MAXIMUM_RR_MS = 4_000.0
        const val LIVE_IQR_MULTIPLIER = 3.0
        const val LIVE_LOCAL_DEVIATION_MS = 450.0
        const val MINIMUM_RMSSD_FOR_LOG_MS = 1.0
        const val MINIMUM_LOG_FOR_INTEGER_SCORE = 0.1
        const val MINIMUM_DISPLAY_SCORE = 0.1
        const val MINIMUM_INTEGER_SCORE = 1.0
        const val MAXIMUM_DISPLAY_SCORE = 100.0
        const val ELITE_SCORE_SCALE = 15.384615384615385
        const val NN50_THRESHOLD_MS = 50.0
        const val ZERO_ORDER_NEIGHBOR_COUNT = 3
        const val LINEAR_NEIGHBOR_COUNT = 2
        const val ARTIFACT_RUN_MAXIMUM_GAP = 10
        const val COMPLETED_WINDOW_THRESHOLD = 316
        const val COMPLETED_WINDOW_SIZE = 256
        const val COMPLETED_MINIMUM_FINAL_WINDOW = 60
    }
}
