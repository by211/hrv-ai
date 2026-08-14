package quest.byai.hrv.signal

import quest.byai.hrv.domain.BreathingCue
import quest.byai.hrv.domain.ResonanceObservation
import quest.byai.hrv.domain.RrSample
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

data class AnalysisConfig(
    val version: Int = 1,
    val resampleHz: Double = 4.0,
    val minimumSamples: Int = 30,
    val minimumDurationSeconds: Double = 45.0,
    val minimumCompleteCycles: Double = 4.0,
    val minimumUsableDataFraction: Double = 0.90,
    val frequencyScanMinimumHz: Double = 0.05,
    val frequencyScanMaximumHz: Double = 0.15,
    val frequencyScanStepHz: Double = 0.0025,
    val targetBandHalfWidthHz: Double = 0.01,
    val amplitudeReferenceBpm: Double = 8.0,
)

class ResonanceAnalyzer(
    private val config: AnalysisConfig = AnalysisConfig(),
) {
    fun analyze(samples: List<RrSample>, cue: BreathingCue): ResonanceObservation {
        if (samples.size < config.minimumSamples) {
            return rejected(cue, samples, "Not enough R-R intervals")
        }

        val sortedSamples = samples.sortedBy { it.elapsedRealtimeMs }
        val durationSeconds = (sortedSamples.last().elapsedRealtimeMs - sortedSamples.first().elapsedRealtimeMs) / 1_000.0
        val usableDataFraction = sortedSamples.count { it.isUsable }.toDouble() / sortedSamples.size
        val usableSamples = sortedSamples.filter { it.analysisRrMs != null }

        if (durationSeconds < config.minimumDurationSeconds) {
            return rejected(cue, samples, "Evaluation window is too short", durationSeconds, usableDataFraction)
        }
        if (durationSeconds * cue.breathsPerMinute / 60.0 < config.minimumCompleteCycles) {
            return rejected(cue, samples, "Not enough complete breathing cycles", durationSeconds, usableDataFraction)
        }
        if (usableDataFraction < config.minimumUsableDataFraction) {
            return rejected(cue, samples, "Signal quality is too low", durationSeconds, usableDataFraction)
        }

        val resampled = resampleHeartRate(usableSamples)
        if (resampled.size < config.minimumSamples) {
            return rejected(cue, samples, "Not enough resampled data", durationSeconds, usableDataFraction)
        }

        val detrended = detrend(resampled.map { it.second })
        val targetFrequencyHz = cue.breathsPerMinute / 60.0
        val targetFit = sineFit(detrended, config.resampleHz, targetFrequencyHz)

        val spectrum = generateSequence(config.frequencyScanMinimumHz) { frequency ->
            (frequency + config.frequencyScanStepHz).takeIf { it <= config.frequencyScanMaximumHz + 1e-9 }
        }.associateWith { frequency -> sineFit(detrended, config.resampleHz, frequency).power }

        val totalPower = spectrum.values.sum().coerceAtLeast(1e-9)
        val targetBandPower = spectrum
            .filterKeys { frequency -> kotlin.math.abs(frequency - targetFrequencyHz) <= config.targetBandHalfWidthHz }
            .values
            .sum()
        val dominantFrequencyHz = spectrum.maxByOrNull { it.value }?.key ?: targetFrequencyHz
        val frequencyErrorHz = kotlin.math.abs(dominantFrequencyHz - targetFrequencyHz)
        val spectralConcentration = (targetBandPower / totalPower).coerceIn(0.0, 1.0)
        val amplitudeComponent = (targetFit.amplitude / config.amplitudeReferenceBpm).coerceIn(0.0, 1.0)
        val frequencyComponent = (1.0 - frequencyErrorHz / 0.05).coerceIn(0.0, 1.0)
        val score = 100.0 * (
            0.40 * amplitudeComponent +
                0.30 * targetFit.regularity +
                0.20 * spectralConcentration +
                0.10 * frequencyComponent
            )

        val cycleConfidence = (durationSeconds * cue.breathsPerMinute / 60.0 / 8.0).coerceIn(0.0, 1.0)
        val confidence = (usableDataFraction * cycleConfidence * (0.5 + 0.5 * targetFit.regularity))
            .coerceIn(0.0, 1.0)
        val rrValues = usableSamples.mapNotNull { it.analysisRrMs }

        return ResonanceObservation(
            breathsPerMinute = cue.breathsPerMinute,
            usableDataFraction = usableDataFraction,
            durationSeconds = durationSeconds,
            targetAmplitudeBpm = targetFit.amplitude,
            waveformRegularity = targetFit.regularity,
            spectralConcentration = spectralConcentration,
            dominantFrequencyHz = dominantFrequencyHz,
            frequencyErrorHz = frequencyErrorHz,
            peakToTroughBpm = targetFit.amplitude * 2.0,
            rmssdMs = rmssd(rrValues),
            sdnnMs = standardDeviation(rrValues),
            score = score.coerceIn(0.0, 100.0),
            confidence = confidence,
            isQualified = confidence >= 0.45,
            rejectionReason = if (confidence >= 0.45) null else "Response confidence is too low",
        )
    }

    private fun resampleHeartRate(samples: List<RrSample>): List<Pair<Double, Double>> {
        if (samples.size < 2) return emptyList()
        val startMs = samples.first().elapsedRealtimeMs.toDouble()
        val endMs = samples.last().elapsedRealtimeMs.toDouble()
        val stepMs = 1_000.0 / config.resampleHz
        val result = mutableListOf<Pair<Double, Double>>()
        var sourceIndex = 0
        var targetMs = startMs

        while (targetMs <= endMs && sourceIndex < samples.lastIndex) {
            while (sourceIndex < samples.lastIndex - 1 && samples[sourceIndex + 1].elapsedRealtimeMs < targetMs) {
                sourceIndex++
            }
            val before = samples[sourceIndex]
            val after = samples[sourceIndex + 1]
            val intervalMs = (after.elapsedRealtimeMs - before.elapsedRealtimeMs).toDouble()
            if (intervalMs > 0.0) {
                val ratio = ((targetMs - before.elapsedRealtimeMs) / intervalMs).coerceIn(0.0, 1.0)
                val beforeHr = 60_000.0 / before.analysisRrMs!!
                val afterHr = 60_000.0 / after.analysisRrMs!!
                result += (targetMs - startMs) / 1_000.0 to beforeHr + (afterHr - beforeHr) * ratio
            }
            targetMs += stepMs
        }
        return result
    }

    private fun detrend(values: List<Double>): List<Double> {
        if (values.size < 2) return values
        val meanX = (values.size - 1) / 2.0
        val meanY = values.average()
        val numerator = values.indices.sumOf { index -> (index - meanX) * (values[index] - meanY) }
        val denominator = values.indices.sumOf { index -> (index - meanX).pow(2) }.coerceAtLeast(1e-9)
        val slope = numerator / denominator
        return values.mapIndexed { index, value -> value - (meanY + slope * (index - meanX)) }
    }

    private fun sineFit(values: List<Double>, sampleRateHz: Double, frequencyHz: Double): SineFit {
        if (values.isEmpty()) return SineFit(0.0, 0.0, 0.0)
        var cosineProjection = 0.0
        var sineProjection = 0.0
        values.forEachIndexed { index, value ->
            val angle = 2.0 * PI * frequencyHz * index / sampleRateHz
            cosineProjection += value * cos(angle)
            sineProjection += value * sin(angle)
        }
        val cosineCoefficient = 2.0 * cosineProjection / values.size
        val sineCoefficient = 2.0 * sineProjection / values.size
        val amplitude = sqrt(cosineCoefficient.pow(2) + sineCoefficient.pow(2))
        val fitted = values.indices.map { index ->
            val angle = 2.0 * PI * frequencyHz * index / sampleRateHz
            cosineCoefficient * cos(angle) + sineCoefficient * sin(angle)
        }
        val totalEnergy = values.sumOf { it.pow(2) }.coerceAtLeast(1e-9)
        val residualEnergy = values.indices.sumOf { index -> (values[index] - fitted[index]).pow(2) }
        val regularity = (1.0 - residualEnergy / totalEnergy).coerceIn(0.0, 1.0)
        return SineFit(amplitude, regularity, amplitude.pow(2))
    }

    private fun rmssd(values: List<Double>): Double {
        if (values.size < 2) return 0.0
        return sqrt(values.zipWithNext().map { (first, second) -> (second - first).pow(2) }.average())
    }

    private fun standardDeviation(values: List<Double>): Double {
        if (values.size < 2) return 0.0
        val mean = values.average()
        return sqrt(values.sumOf { (it - mean).pow(2) } / (values.size - 1))
    }

    private fun rejected(
        cue: BreathingCue,
        samples: List<RrSample>,
        reason: String,
        durationSeconds: Double = if (samples.size > 1) {
            (samples.maxOf { it.elapsedRealtimeMs } - samples.minOf { it.elapsedRealtimeMs }) / 1_000.0
        } else 0.0,
        usableDataFraction: Double = if (samples.isEmpty()) 0.0 else samples.count { it.isUsable }.toDouble() / samples.size,
    ) = ResonanceObservation(
        breathsPerMinute = cue.breathsPerMinute,
        usableDataFraction = usableDataFraction,
        durationSeconds = durationSeconds,
        targetAmplitudeBpm = 0.0,
        waveformRegularity = 0.0,
        spectralConcentration = 0.0,
        dominantFrequencyHz = 0.0,
        frequencyErrorHz = 0.0,
        peakToTroughBpm = 0.0,
        rmssdMs = 0.0,
        sdnnMs = 0.0,
        score = 0.0,
        confidence = 0.0,
        isQualified = false,
        rejectionReason = reason,
    )

    private data class SineFit(
        val amplitude: Double,
        val regularity: Double,
        val power: Double,
    )
}
