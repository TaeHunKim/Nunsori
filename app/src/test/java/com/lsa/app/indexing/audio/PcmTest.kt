package com.lsa.app.indexing.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

class PcmTest {
    private fun sine(freq: Double, rate: Int, seconds: Double, amp: Double = 0.5): FloatArray =
        FloatArray((rate * seconds).toInt()) { (amp * sin(2 * PI * freq * it / rate)).toFloat() }

    private fun resample(input: FloatArray, srcRate: Int, channels: Int = 1): ShortArray {
        val out = ArrayList<Short>()
        val r = StreamingResampler(srcRate, channels)
        // feed in odd-sized blocks to prove state is carried across calls
        var pos = 0
        while (pos < input.size) { val n = minOf(1001 * channels, input.size - pos); r.process(input.copyOfRange(pos, pos + n), n / channels, out); pos += n }
        return out.toShortArray()
    }

    @Test fun lengthFollowsTheRateRatio() {
        val out = resample(sine(440.0, 44100, 2.0), 44100)
        assertEquals(32000.0, out.size.toDouble(), 3.0)
    }

    @Test fun sixteenKhzPassesThroughUnchanged() {
        val input = sine(300.0, 16000, 0.5)
        val out = resample(input, 16000)
        assertEquals(input.size, out.size)
        for (i in input.indices step 97) assertEquals(input[i] * 32767.0, out[i].toDouble(), 2.0)
    }

    @Test fun stereoIsAveragedToMono() {
        val frames = 1600
        val interleaved = FloatArray(frames * 2) { if (it % 2 == 0) 0.8f else 0.2f }
        val out = resample(interleaved, 16000, channels = 2)
        assertEquals(frames, out.size)
        assertEquals(0.5 * 32767, out[100].toDouble(), 5.0)
    }

    @Test fun toneKeepsItsFrequencyWhenDownsampled() {
        val out = resample(sine(1000.0, 48000, 1.0), 48000)
        var crossings = 0
        for (i in 1 until out.size) if (out[i - 1] < 0 && out[i] >= 0) crossings++
        assertEquals("1 kHz tone = about 1000 rising zero crossings per second", 1000.0, crossings.toDouble(), 6.0)
    }

    @Test fun loudnessIsPreservedForSpeechBandSignals() {
        val out = resample(sine(500.0, 44100, 1.0, amp = 0.5), 44100)
        val peak = out.maxOf { abs(it.toInt()) }
        assertTrue("peak $peak", peak in 15000..16800)
    }

    @Test fun upsamplingAlsoWorks() = assertEquals(16000.0, resample(sine(200.0, 8000, 1.0), 8000).size.toDouble(), 3.0)

    @Test fun wavHeaderIsValid() {
        val pcm = ByteArray(3200) { 1 }
        val wav = Pcm.toWav(pcm)
        assertEquals(44 + 3200, wav.size)
        assertEquals("RIFF", String(wav, 0, 4)); assertEquals("WAVE", String(wav, 8, 4)); assertEquals("data", String(wav, 36, 4))
        val bb = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(36 + 3200, bb.getInt(4)); assertEquals(1, bb.getShort(20).toInt()); assertEquals(1, bb.getShort(22).toInt())
        assertEquals(16000, bb.getInt(24)); assertEquals(32000, bb.getInt(28)); assertEquals(16, bb.getShort(34).toInt()); assertEquals(3200, bb.getInt(40))
    }

    @Test fun rmsOfSilenceAndSignal() {
        assertEquals(0.0, Pcm.rms(ByteArray(1000)), 0.0)
        val pcm = ByteBuffer.allocate(2000).order(ByteOrder.LITTLE_ENDIAN).apply { repeat(1000) { putShort(16384) } }.array()
        assertEquals(0.5, Pcm.rms(pcm), 0.001)
    }

    @Test fun timeAndByteOffsetsAreInverse() {
        assertEquals(32000L, Pcm.msToByte(1000)); assertEquals(1000L, Pcm.byteToMs(32000))
        assertEquals(0L, Pcm.msToByte(0))
    }

    @Test fun floatConversion() {
        val pcm = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putShort(16384).putShort(-32768).array()
        val f = Pcm.toFloats(pcm)
        assertEquals(0.5f, f[0], 1e-4f); assertEquals(-1f, f[1], 1e-4f)
    }
}
