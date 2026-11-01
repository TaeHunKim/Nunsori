package com.lsa.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lsa.app.data.db.ItemKind
import com.lsa.app.data.db.SegmentType
import com.lsa.app.indexing.SkipItem
import com.lsa.app.indexing.audio.AudioDecoder
import com.lsa.app.indexing.audio.AudioExtractor
import com.lsa.app.indexing.audio.Cue
import com.lsa.app.indexing.audio.Pcm
import com.lsa.app.indexing.audio.Span
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin

@RunWith(AndroidJUnit4::class)
class AudioExtractorTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private lateinit var dir: File

    @Before fun setUp() { dir = File(ctx.cacheDir, "audio-test").also { it.deleteRecursively(); it.mkdirs() } }
    @After fun tearDown() { dir.deleteRecursively() }

    /** 16 kHz mono 16-bit PCM: a quiet noisy tone, with [silentFrom, silentTo) left at zero. */
    private fun pcm(seconds: Int, silentFrom: Int = -1, silentTo: Int = -1): File {
        val f = File(dir, "x.pcm")
        val bb = ByteBuffer.allocate(seconds * Pcm.BYTES_PER_SECOND).order(ByteOrder.LITTLE_ENDIAN)
        val r = java.util.Random(1)
        for (i in 0 until seconds * Pcm.RATE) {
            val t = i.toDouble() / Pcm.RATE
            val silent = t >= silentFrom && t < silentTo
            bb.putShort(if (silent) 0 else (6000 * sin(2 * PI * 300 * t) + r.nextGaussian() * 300).toInt().toShort())
        }
        f.writeBytes(bb.array()); return f
    }

    private fun cue(s: Int, e: Int, text: String) = Cue(s * 1000L, e * 1000L, text)

    @Test fun transcriptAndAudioWindowsAreBothProduced() = runBlocking {
        val embedder = FakeEmbedder(audio = true)
        val speech = FakeSpeech(spans = listOf(Span(0, 31_000), Span(33_000, 70_000)), cues = listOf(cue(1, 5, "hello there everyone"), cue(10, 15, "second part of the talk"), cue(60, 65, "closing remarks")))
        val sink = CollectingSink()
        AudioExtractor(ctx, embedder, speech, dir).runStages(pcm(70), 70_000, 0, sink)

        val tr = sink.segments.filter { it.type == SegmentType.TRANSCRIPT }
        assertTrue(tr.isNotEmpty()); assertTrue(tr.joinToString { it.text!! }.contains("closing remarks"))
        assertEquals(1_000L, tr.first().startMs); assertEquals(65_000L, tr.last().endMs)
        val clips = sink.segments.filter { it.type == SegmentType.AUDIO_CLIP }
        assertEquals(0L, clips.first().startMs); assertEquals(70_000L, clips.last().endMs)
        assertEquals("first window is cut at the silence (32 s), not at 30 s", 32_000L, clips.first().endMs)
        assertTrue("clips overlap", clips[1].startMs!! < clips[0].endMs!!)
        assertTrue("every clip is below the model's length limit", embedder.audioClips.all { it <= 44 + 36 * 32_000 })
        assertTrue(sink.segments.all { it.embedding != null })
        assertTrue("stage B progress is recorded", sink.lastProgress >= AudioExtractor.STAGE_B)
    }

    @Test fun silentWindowsAreSkippedButProgressStillAdvances() = runBlocking {
        val embedder = FakeEmbedder(audio = true)
        val sink = CollectingSink()
        AudioExtractor(ctx, embedder, FakeSpeech(canTranscribe = false), dir).runStages(pcm(100, silentFrom = 30, silentTo = 90), 100_000, 0, sink)
        val clips = sink.segments.filter { it.type == SegmentType.AUDIO_CLIP }
        assertTrue("the window 50-80 s is pure digital silence and is not embedded", clips.none { it.startMs == 50_000L })
        assertTrue("windows that contain some sound are kept", clips.any { it.startMs == 25_000L })
        assertTrue(clips.size >= 2)
        val expected: Long = AudioExtractor.STAGE_B + (clips.last().ordinal - 1_000_000) + 1L
        assertEquals(expected, sink.lastProgress)
    }

    @Test fun resumingStageBContinuesWithTheNextWindow() = runBlocking {
        val embedder = FakeEmbedder(audio = true)
        val sink = CollectingSink()
        AudioExtractor(ctx, embedder, FakeSpeech(canTranscribe = false), dir).runStages(pcm(100), 100_000, AudioExtractor.STAGE_B + 2, sink)
        val ordinals = sink.segments.map { it.ordinal - 1_000_000 }
        assertTrue("windows 0 and 1 were already done: $ordinals", ordinals.all { it >= 2 })
    }

    @Test fun resumingStageAContinuesRecognitionFromTheCheckpoint() = runBlocking {
        val speech = FakeSpeech(cues = listOf(cue(1, 4, "early words"), cue(20, 24, "later words here")))
        val sink = CollectingSink()
        AudioExtractor(ctx, FakeEmbedder(audio = false), speech, dir).runStages(pcm(40), 40_000, 5_000, sink)
        assertEquals(5_000L, speech.transcribedFrom)
        val text = sink.segments.joinToString { it.text.orEmpty() }
        assertFalse(text.contains("early")); assertTrue(text.contains("later words"))
    }

    @Test fun withoutAnAudioModelOnlyTranscriptsAreMade() = runBlocking {
        val sink = CollectingSink()
        AudioExtractor(ctx, FakeEmbedder(audio = false), FakeSpeech(cues = listOf(cue(1, 3, "just words"))), dir).runStages(pcm(20), 20_000, 0, sink)
        assertTrue(sink.segments.all { it.type == SegmentType.TRANSCRIPT })
    }

    @Test fun withoutRecognitionOnlyAudioClipsAreMade() = runBlocking {
        val sink = CollectingSink()
        AudioExtractor(ctx, FakeEmbedder(audio = true), FakeSpeech(canTranscribe = false), dir).runStages(pcm(20), 20_000, 0, sink)
        assertTrue(sink.segments.isNotEmpty() && sink.segments.all { it.type == SegmentType.AUDIO_CLIP })
    }

    @Test fun nothingToDoWithoutAnyModelIsSkipped() = runBlocking {
        val f = File(dir, "a.wav").also { it.writeBytes(Pcm.toWav(ByteArray(64000))) }
        try { AudioExtractor(ctx, FakeEmbedder(audio = false), FakeSpeech(canTranscribe = false), dir).extract(fileEntry(f, ItemKind.AUDIO), 0, CollectingSink()); fail() }
        catch (e: SkipItem) { assertTrue(e.reason.contains("model")) }
    }

    @Test fun decoderReadsAStereoWavAndConvertsTo16kMono() {
        val rate = 44_100; val frames = rate * 2
        val bb = ByteBuffer.allocate(frames * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until frames) { val v = (8000 * sin(2 * PI * 440 * i / rate)).toInt().toShort(); bb.putShort(v); bb.putShort(v) }
        val wav = File(dir, "stereo.wav")
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).put("RIFF".toByteArray()).putInt(36 + bb.capacity()).put("WAVEfmt ".toByteArray())
            .putInt(16).putShort(1).putShort(2).putInt(rate).putInt(rate * 4).putShort(4).putShort(16).put("data".toByteArray()).putInt(bb.capacity())
        wav.writeBytes(header.array() + bb.array())
        val out = File(dir, "decoded.pcm")
        val ms = AudioDecoder.decode(ctx, android.net.Uri.fromFile(wav), out)
        assertEquals(2000.0, ms.toDouble(), 60.0)
        assertEquals(ms * 32.0, out.length().toDouble(), 400.0)
        assertTrue("signal survived", Pcm.rms(out.readBytes()) > 0.1)
    }

    @Test fun decoderRejectsFilesWithoutAudio() {
        val f = File(dir, "junk.mp3").also { it.writeText("definitely not audio") }
        try { AudioDecoder.decode(ctx, android.net.Uri.fromFile(f), File(dir, "o.pcm")); fail() } catch (_: SkipItem) { }
    }
}
