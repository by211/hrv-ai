package quest.byai.hrv.session

import quest.byai.hrv.domain.RrQualityFlag
import quest.byai.hrv.domain.RrSample
import kotlin.math.max

class RrTimestampReconstructor {
    var lastBeatElapsedMs: Long? = null
        private set

    fun reconstruct(
        receiveElapsedMs: Long,
        rrIntervalsMs: List<Int>,
        contactStatus: Boolean?,
    ): List<RrSample> {
        if (rrIntervalsMs.isEmpty()) return emptyList()
        var beatElapsedMs = receiveElapsedMs - rrIntervalsMs.sum()
        return rrIntervalsMs.map { rrMs ->
            beatElapsedMs += rrMs
            val flags = buildSet {
                if (contactStatus == false) add(RrQualityFlag.CONTACT_LOST)
                val previousBeat = lastBeatElapsedMs
                if (previousBeat != null && beatElapsedMs - previousBeat > max(2_500L, rrMs * 3L)) {
                    add(RrQualityFlag.BLE_GAP)
                }
            }
            lastBeatElapsedMs = beatElapsedMs
            RrSample(
                elapsedRealtimeMs = beatElapsedMs,
                rawRrMs = rrMs,
                analysisRrMs = rrMs.toDouble().takeIf { flags.isEmpty() },
                qualityFlags = flags,
            )
        }
    }

    fun reset() {
        lastBeatElapsedMs = null
    }
}
