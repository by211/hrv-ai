package quest.byai.hrv.controller

import quest.byai.hrv.domain.ResonanceObservation
import kotlin.math.round

data class ControllerConfig(
    val minimumRate: Double = 4.5,
    val maximumRate: Double = 7.0,
    val explorationStep: Double = 0.2,
    val fineStep: Double = 0.1,
    val minimumConfidence: Double = 0.55,
    val requiredScoreImprovement: Double = 3.0,
)

data class ControllerState(
    val acceptedRate: Double = 6.0,
    val commandedRate: Double = 6.0,
    val acceptedScore: Double? = null,
    val direction: Int = -1,
    val pendingCandidate: Boolean = false,
    val step: Double = 0.2,
    val reversals: Int = 0,
)

enum class ControllerAction {
    HOLD,
    EXPLORE,
    ACCEPT_AND_EXPLORE,
    REVERT_AND_EXPLORE,
}

data class ControllerDecision(
    val action: ControllerAction,
    val nextRate: Double,
    val reason: String,
    val state: ControllerState,
)

class AdaptiveBreathingController(
    private val config: ControllerConfig = ControllerConfig(),
) {
    fun next(
        state: ControllerState,
        observation: ResonanceObservation,
    ): ControllerDecision {
        if (!observation.isQualified || observation.confidence < config.minimumConfidence) {
            val reason = observation.rejectionReason ?: "Confidence ${formatPercent(observation.confidence)} < ${formatPercent(config.minimumConfidence)}"
            return ControllerDecision(
                ControllerAction.HOLD,
                state.commandedRate,
                "$reason; holding ${formatRate(state.commandedRate)} breaths/min",
                state,
            )
        }

        if (state.acceptedScore == null) {
            val candidateRate = candidateFrom(state.acceptedRate, state.direction, state.step)
            val nextState = state.copy(
                commandedRate = candidateRate,
                acceptedScore = observation.score,
                pendingCandidate = candidateRate != state.acceptedRate,
            )
            return ControllerDecision(
                if (candidateRate == state.acceptedRate) ControllerAction.HOLD else ControllerAction.EXPLORE,
                candidateRate,
                "Baseline ${formatRate(state.acceptedRate)} scored ${formatScore(observation.score)}; next ${formatRate(candidateRate)} breaths/min",
                nextState,
            )
        }

        if (!state.pendingCandidate) {
            val candidateRate = candidateFrom(state.acceptedRate, state.direction, state.step)
            val nextState = state.copy(commandedRate = candidateRate, pendingCandidate = candidateRate != state.acceptedRate)
            return ControllerDecision(
                if (candidateRate == state.acceptedRate) ControllerAction.HOLD else ControllerAction.EXPLORE,
                candidateRate,
                "Baseline ${formatRate(state.acceptedRate)} scored ${formatScore(state.acceptedScore)}; next ${formatRate(candidateRate)} breaths/min",
                nextState,
            )
        }

        val improvement = observation.score - state.acceptedScore
        return if (improvement >= config.requiredScoreImprovement) {
            val acceptedRate = state.commandedRate
            val candidateRate = candidateFrom(acceptedRate, state.direction, state.step)
            val nextState = state.copy(
                acceptedRate = acceptedRate,
                commandedRate = candidateRate,
                acceptedScore = observation.score,
                pendingCandidate = candidateRate != acceptedRate,
            )
            ControllerDecision(
                ControllerAction.ACCEPT_AND_EXPLORE,
                candidateRate,
                "New baseline ${formatRate(acceptedRate)} scored ${formatScore(observation.score)} (was ${formatRate(state.acceptedRate)} at ${formatScore(state.acceptedScore)}); next ${formatRate(candidateRate)} breaths/min",
                nextState,
            )
        } else {
            val reversedDirection = -state.direction
            val reversalCount = state.reversals + 1
            val nextStep = if (reversalCount >= 1) config.fineStep else state.step
            val candidateRate = candidateFrom(state.acceptedRate, reversedDirection, nextStep)
            val nextState = state.copy(
                commandedRate = candidateRate,
                direction = reversedDirection,
                pendingCandidate = candidateRate != state.acceptedRate,
                step = nextStep,
                reversals = reversalCount,
            )
            ControllerDecision(
                ControllerAction.REVERT_AND_EXPLORE,
                candidateRate,
                "Kept baseline ${formatRate(state.acceptedRate)} at ${formatScore(state.acceptedScore)}; ${formatRate(state.commandedRate)} scored ${formatScore(observation.score)}; next ${formatRate(candidateRate)} breaths/min",
                nextState,
            )
        }
    }

    private fun candidateFrom(rate: Double, direction: Int, step: Double): Double {
        val candidate = round((rate + direction * step) * 10.0) / 10.0
        val bounded = candidate.coerceIn(config.minimumRate, config.maximumRate)
        if (bounded != rate) return bounded
        val opposite = round((rate - direction * step) * 10.0) / 10.0
        return opposite.coerceIn(config.minimumRate, config.maximumRate)
    }

    private fun formatRate(rate: Double): String = "%.1f".format(rate)
    private fun formatScore(score: Double): String = "%.1f".format(score)
    private fun formatPercent(value: Double): String = "${(value * 100).toInt()}%"
}
