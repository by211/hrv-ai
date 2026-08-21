package quest.byai.hrv.ui

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import quest.byai.hrv.data.BreathingSoundStyle
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/** Plays gentle, distinct synthesized cues without requiring bundled audio assets. */
internal class BreathingSoundPlayer(style: BreathingSoundStyle) {
    private val inhaleTrack = createTrack(synthesizeCue(style, inhaling = true))
    private val exhaleTrack = createTrack(synthesizeCue(style, inhaling = false))

    fun play(inhaling: Boolean) {
        val track = if (inhaling) inhaleTrack else exhaleTrack
        if (track.playState == AudioTrack.PLAYSTATE_PLAYING) track.stop()
        track.reloadStaticData()
        track.play()
    }

    fun release() {
        inhaleTrack.release()
        exhaleTrack.release()
    }

    private fun createTrack(samples: ShortArray): AudioTrack = AudioTrack.Builder()
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build(),
        )
        .setAudioFormat(
            AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(SAMPLE_RATE_HZ)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build(),
        )
        .setTransferMode(AudioTrack.MODE_STATIC)
        .setBufferSizeInBytes(samples.size * Short.SIZE_BYTES)
        .build()
        .also { it.write(samples, 0, samples.size) }

    private fun synthesizeCue(style: BreathingSoundStyle, inhaling: Boolean): ShortArray = when (style) {
        BreathingSoundStyle.GENTLE_CHIMES -> synthesizeGlide(
            startFrequencyHz = if (inhaling) 392.0 else 523.25,
            endFrequencyHz = if (inhaling) 523.25 else 349.23,
        )
        BreathingSoundStyle.OCEAN_SWELL -> synthesizeOceanSwell(inhaling)
        BreathingSoundStyle.SINGING_BOWLS -> synthesizeSingingBowl(inhaling)
    }

    private fun synthesizeGlide(startFrequencyHz: Double, endFrequencyHz: Double): ShortArray {
        var phase = 0.0
        return createSamples(CUE_DURATION_MS) { progress ->
            val frequencyHz = startFrequencyHz + (endFrequencyHz - startFrequencyHz) * progress
            phase += 2.0 * PI * frequencyHz / SAMPLE_RATE_HZ
            val envelope = sin(PI * progress).let { it * it }
            (sin(phase) + 0.18 * sin(phase * 2.0)) * envelope * 0.18
        }
    }

    private fun synthesizeOceanSwell(inhaling: Boolean): ShortArray {
        var phase = 0.0
        return createSamples(OCEAN_DURATION_MS) { progress ->
            val shapedProgress = if (inhaling) progress else 1.0 - progress
            val frequencyHz = 145.0 + 95.0 * shapedProgress
            phase += 2.0 * PI * frequencyHz / SAMPLE_RATE_HZ
            val swell = sin(PI * progress).let { it * it }
            val wash = sin(phase) + 0.35 * sin(phase * 1.013) + 0.12 * sin(phase * 2.0)
            wash * swell * 0.12
        }
    }

    private fun synthesizeSingingBowl(inhaling: Boolean): ShortArray {
        val baseFrequencyHz = if (inhaling) 440.0 else 293.66
        return createSamples(BOWL_DURATION_MS) { progress ->
            val elapsedSeconds = progress * BOWL_DURATION_MS / 1_000.0
            val envelope = (1.0 - exp(-18.0 * progress)) * exp(-2.8 * progress)
            val fundamental = sin(2.0 * PI * baseFrequencyHz * elapsedSeconds)
            val overtones = 0.32 * sin(2.0 * PI * baseFrequencyHz * 2.01 * elapsedSeconds) +
                0.14 * sin(2.0 * PI * baseFrequencyHz * 3.9 * elapsedSeconds)
            (fundamental + overtones) * envelope * 0.20
        }
    }

    private fun createSamples(durationMs: Int, sample: (Double) -> Double): ShortArray {
        val sampleCount = SAMPLE_RATE_HZ * durationMs / 1_000
        return ShortArray(sampleCount) { sampleIndex ->
            val progress = sampleIndex.toDouble() / sampleCount
            (sample(progress).coerceIn(-1.0, 1.0) * Short.MAX_VALUE).toInt().toShort()
        }
    }

    private companion object {
        const val SAMPLE_RATE_HZ = 22_050
        const val CUE_DURATION_MS = 800
        const val OCEAN_DURATION_MS = 1_300
        const val BOWL_DURATION_MS = 1_500
    }
}
