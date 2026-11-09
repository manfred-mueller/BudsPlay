package org.manfredmueller.budsplay

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

/**
 * HeartbeatGenerator erzeugt einen synthetischen Herzschlag-Sound per AudioTrack.
 *
 * Verbesserungen gegenüber v1:
 *   - Heart Rate Variability (HRV): ±6% zufällige Intervall-Variation pro Schlag
 *     → klingt organisch statt mechanisch
 *   - Exponential-Decay-Hüllkurve statt Hanning: schneller Anstieg, weicher Ausklang
 *     → näher am echten Herzschlagklang
 *   - Subtile Amplitudenvariation pro Schlag (±8%)
 *     → kein monotoner Dauerton
 *
 * BPM-Bereich: 30–60 BPM
 *   30–45 BPM → Tiefer Schlaf / starke Entspannung
 *   45–55 BPM → Meditation / Selbsthypnose
 *   55–60 BPM → Ruhezustand
 */
class HeartbeatGenerator {

    companion object {
        const val SAMPLE_RATE = 44100
        const val DEFAULT_BPM = 40
        const val MIN_BPM     = 30
        const val MAX_BPM     = 60

        // Heart Rate Variability: maximale Abweichung vom Schlag-Intervall
        private const val HRV_FACTOR = 0.06   // ±6%
        // Amplitudenvariation pro Schlag
        private const val AMP_VARIATION = 0.08 // ±8%
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Zustand
    // ─────────────────────────────────────────────────────────────────────────

    var isEnabled: Boolean = false
    var bpm: Int = DEFAULT_BPM
    var volume: Float = 0.3f
        set(value) {
            field = value.coerceIn(0f, 1f)
            audioTrack?.setVolume(field)
        }

    private var audioTrack: AudioTrack? = null
    private var generatorThread: Thread? = null
    @Volatile private var isRunning = false

    val isPlaying: Boolean get() = isRunning

    // ─────────────────────────────────────────────────────────────────────────
    // Starten / Stoppen
    // ─────────────────────────────────────────────────────────────────────────

    fun start() {
        if (isRunning) stop()

        val bufferSize = AudioTrack.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_STEREO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(SAMPLE_RATE * 2 * 2)

        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build()
            )
            .setBufferSizeInBytes(bufferSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        audioTrack?.setVolume(volume)
        audioTrack?.play()
        isRunning = true

        generatorThread = Thread {
            generateAndStream()
        }.apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        isRunning = false
        generatorThread?.interrupt()
        generatorThread = null
        try {
            audioTrack?.stop()
            audioTrack?.release()
        } catch (e: Exception) { /* ignore */ }
        audioTrack = null
    }

    fun release() = stop()

    // ─────────────────────────────────────────────────────────────────────────
    // Herzschlag-Generierung
    // ─────────────────────────────────────────────────────────────────────────

    private fun generateAndStream() {
        val rng = Random(System.currentTimeMillis())

        while (isRunning && !Thread.currentThread().isInterrupted) {
            val baseSamples = (SAMPLE_RATE * 60.0 / bpm).toInt()

            // HRV: Intervall leicht variieren — kein mechanisches Ticken
            val hrvOffset = (baseSamples * HRV_FACTOR * (rng.nextDouble() * 2.0 - 1.0)).toInt()
            val beatIntervalSamples = (baseSamples + hrvOffset).coerceAtLeast(SAMPLE_RATE / 2)

            // Amplitudenvariation: jeder Schlag minimal anders laut
            val ampVariation = 1.0 + AMP_VARIATION * (rng.nextDouble() * 2.0 - 1.0)

            val cycleBuffer = ShortArray(beatIntervalSamples * 2)  // Stereo

            // Lub — tiefer dumpfer Hauptschlag
            addHeartImpulse(cycleBuffer, offset = 0,
                freq = 80.0, durationMs = 110,
                amplitudeFactor = 1.0 * ampVariation)

            // Dub — Echo-Schlag, etwas höher und kürzer
            addHeartImpulse(cycleBuffer, offset = (SAMPLE_RATE * 0.13).toInt(),
                freq = 115.0, durationMs = 75,
                amplitudeFactor = 0.55 * ampVariation)

            try {
                audioTrack?.write(cycleBuffer, 0, cycleBuffer.size)
            } catch (e: Exception) {
                break
            }
        }
    }

    /**
     * Fügt einen Herzschlag-Impuls in den Buffer ein.
     * Verwendet eine Exponential-Decay-Hüllkurve:
     *   - Schneller Anstieg (10% der Dauer)
     *   - Weicher exponentieller Ausklang (90% der Dauer)
     * Das klingt näher am echten Herzschlag als eine symmetrische Hanning-Kurve.
     */
    private fun addHeartImpulse(
        buffer: ShortArray,
        offset: Int,
        freq: Double,
        durationMs: Int,
        amplitudeFactor: Double = 1.0
    ) {
        val durationSamples = (SAMPLE_RATE * durationMs / 1000.0).toInt()
        val twoPi = 2.0 * PI
        val attackSamples = (durationSamples * 0.10).toInt().coerceAtLeast(1)
        val decayRate = 5.0 / durationSamples  // Abklingkonstante

        for (i in 0 until durationSamples) {
            val sampleIndex = offset + i
            if (sampleIndex * 2 + 1 >= buffer.size) break

            val t = i.toDouble() / SAMPLE_RATE

            // Hüllkurve: linearer Anstieg + exponentieller Abfall
            val envelope = if (i < attackSamples) {
                i.toDouble() / attackSamples
            } else {
                exp(-decayRate * (i - attackSamples))
            }

            val sample = (sin(twoPi * freq * t) * envelope *
                         Short.MAX_VALUE * amplitudeFactor).toInt()
                         .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                         .toShort()

            buffer[sampleIndex * 2]     = sample
            buffer[sampleIndex * 2 + 1] = sample
        }
    }
}
