package quest.byai.hrv.sensor

internal data class SensorFailure(
    val userMessage: String,
    val technicalDetails: String,
)

internal fun Throwable.toSensorFailure(fallback: String): SensorFailure {
    val causes = generateSequence(this) { it.cause }
        .take(8)
        .toList()
    val disconnected = causes.any { cause ->
        cause.javaClass.simpleName.contains("disconnected", ignoreCase = true) ||
            cause.message.orEmpty().contains("disconnected", ignoreCase = true)
    }
    val meaningfulCause = causes.asReversed().firstOrNull { cause ->
        val message = cause.message
        !message.isNullOrBlank() && !message.equals("Channel closed due to error", ignoreCase = true)
    } ?: causes.last()
    val causeName = meaningfulCause.javaClass.simpleName.ifBlank {
        meaningfulCause.javaClass.name.substringAfterLast('.')
    }
    val causeDescription = meaningfulCause.message?.takeIf(String::isNotBlank) ?: causeName
    val userMessage = when {
        disconnected -> "$fallback: sensor disconnected"
        else -> "$fallback: $causeDescription"
    }
    val technicalDetails = causes.joinToString(" caused by ") { cause ->
        val name = cause.javaClass.simpleName.ifBlank { cause.javaClass.name.substringAfterLast('.') }
        cause.message?.takeIf(String::isNotBlank)?.let { "$name: $it" } ?: name
    }
    return SensorFailure(userMessage, technicalDetails)
}
