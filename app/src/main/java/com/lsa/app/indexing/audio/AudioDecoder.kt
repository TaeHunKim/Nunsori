package com.lsa.app.indexing.audio

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import com.lsa.app.indexing.SkipItem
import java.io.BufferedOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Decodes the first audio track of any media file the platform can read into a raw 16 kHz mono 16-bit PCM file. */
object AudioDecoder {
    /** Longest recording that is processed; the rest is ignored. */
    const val MAX_MS = 4L * 60 * 60 * 1000

    /** Returns the duration in milliseconds of what was written to [out]. */
    fun decode(context: Context, uri: Uri, out: File, maxMs: Long = MAX_MS): Long {
        val extractor = MediaExtractor()
        try {
            if (uri.scheme == "file") extractor.setDataSource(uri.path!!) else extractor.setDataSource(context, uri, null)
        } catch (e: Exception) {
            extractor.release(); throw SkipItem("cannot open media: ${e.message}")
        }
        var codec: MediaCodec? = null
        try {
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: throw SkipItem("no audio track")
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            codec.configure(format, null, null, 0)
            codec.start()
            return pump(extractor, codec, out, maxMs)
        } finally {
            runCatching { codec?.stop() }; runCatching { codec?.release() }; extractor.release()
        }
    }

    private fun pump(extractor: MediaExtractor, codec: MediaCodec, out: File, maxMs: Long): Long {
        val info = MediaCodec.BufferInfo()
        var resampler: StreamingResampler? = null
        var channels = 1
        var float = false
        var samples = 0L
        val maxSamples = maxMs * Pcm.RATE / 1000
        var inputDone = false
        var outputDone = false
        val scratch = ArrayList<Short>(8192)
        BufferedOutputStream(out.outputStream(), 1 shl 16).use { sink ->
            val bytes = ByteBuffer.allocate(1 shl 16).order(ByteOrder.LITTLE_ENDIAN)
            fun flush() {
                bytes.clear()
                for (s in scratch) { if (!bytes.hasRemaining()) { sink.write(bytes.array(), 0, bytes.position()); bytes.clear() }; bytes.putShort(s) }
                sink.write(bytes.array(), 0, bytes.position())
                samples += scratch.size; scratch.clear()
            }
            while (!outputDone && samples < maxSamples) {
                if (!inputDone) {
                    val i = codec.dequeueInputBuffer(10_000)
                    if (i >= 0) {
                        val buf = codec.getInputBuffer(i)!!
                        val n = extractor.readSampleData(buf, 0)
                        if (n < 0) { codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true }
                        else { codec.queueInputBuffer(i, 0, n, extractor.sampleTime, 0); extractor.advance() }
                    }
                }
                when (val o = codec.dequeueOutputBuffer(info, 10_000)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val f = codec.outputFormat
                        channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        float = f.containsKey(MediaFormat.KEY_PCM_ENCODING) && f.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                        resampler = StreamingResampler(f.getInteger(MediaFormat.KEY_SAMPLE_RATE), channels)
                    }
                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    else -> if (o >= 0) {
                        if (info.size > 0 && resampler != null) {
                            val buf = codec.getOutputBuffer(o)!!.apply { position(info.offset); limit(info.offset + info.size); order(ByteOrder.nativeOrder()) }
                            val frames: Int
                            val data: FloatArray
                            if (float) {
                                val fb = buf.asFloatBuffer(); data = FloatArray(fb.remaining()); fb.get(data); frames = data.size / channels
                            } else {
                                val sb = buf.asShortBuffer(); data = FloatArray(sb.remaining()) { sb.get(it) / 32768f }; frames = data.size / channels
                            }
                            resampler!!.process(data, frames, scratch)
                            if (scratch.size >= 16_384) flush()
                        }
                        outputDone = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        codec.releaseOutputBuffer(o, false)
                    }
                }
            }
            flush()
        }
        return samples * 1000 / Pcm.RATE
    }
}
