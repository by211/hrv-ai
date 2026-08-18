package quest.byai.hrv.sensor

internal data class ParsedHeartRateMeasurement(
    val heartRateBpm: Int,
    val rrIntervalsMs: List<Int>,
    val rrAvailable: Boolean,
    val contactStatus: Boolean?,
)

internal object HeartRateMeasurementParser {
    fun parse(value: ByteArray): ParsedHeartRateMeasurement? {
        if (value.size < 2) return null

        val flags = value[0].toInt() and 0xFF
        var offset = 1
        val heartRateBpm = if (flags and HEART_RATE_FORMAT_UINT16_FLAG != 0) {
            readUInt16LittleEndian(value, offset)?.also { offset += 2 } ?: return null
        } else {
            (value.getOrNull(offset)?.toInt()?.and(0xFF))?.also { offset += 1 } ?: return null
        }

        if (flags and ENERGY_EXPENDED_PRESENT_FLAG != 0) {
            if (readUInt16LittleEndian(value, offset) == null) return null
            offset += 2
        }

        val rrAvailable = flags and RR_INTERVAL_PRESENT_FLAG != 0
        val rrIntervalsMs = if (rrAvailable) {
            buildList {
                while (offset + 1 < value.size) {
                    val rrUnits = readUInt16LittleEndian(value, offset) ?: break
                    add(((rrUnits * MILLISECONDS_PER_SECOND) + RR_UNITS_PER_SECOND / 2) / RR_UNITS_PER_SECOND)
                    offset += 2
                }
            }
        } else {
            emptyList()
        }
        val contactStatus = if (flags and SENSOR_CONTACT_SUPPORTED_FLAG != 0) {
            flags and SENSOR_CONTACT_DETECTED_FLAG != 0
        } else {
            null
        }

        return ParsedHeartRateMeasurement(
            heartRateBpm = heartRateBpm,
            rrIntervalsMs = rrIntervalsMs,
            rrAvailable = rrAvailable,
            contactStatus = contactStatus,
        )
    }

    private fun readUInt16LittleEndian(value: ByteArray, offset: Int): Int? {
        val lowByte = value.getOrNull(offset)?.toInt()?.and(0xFF) ?: return null
        val highByte = value.getOrNull(offset + 1)?.toInt()?.and(0xFF) ?: return null
        return lowByte or (highByte shl 8)
    }

    private const val HEART_RATE_FORMAT_UINT16_FLAG = 0x01
    private const val SENSOR_CONTACT_DETECTED_FLAG = 0x02
    private const val SENSOR_CONTACT_SUPPORTED_FLAG = 0x04
    private const val ENERGY_EXPENDED_PRESENT_FLAG = 0x08
    private const val RR_INTERVAL_PRESENT_FLAG = 0x10
    private const val MILLISECONDS_PER_SECOND = 1_000
    private const val RR_UNITS_PER_SECOND = 1_024
}
