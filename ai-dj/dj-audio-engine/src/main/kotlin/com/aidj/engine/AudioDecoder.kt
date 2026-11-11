package com.aidj.engine

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.nio.ByteOrder
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

class DecodeException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Decodes any format Android's MediaCodec supports (MP3, AAC/M4A, FLAC, WAV, OGG/Opus, ...) to:
 *  - 16-bit stereo PCM written straight into a native track buffer (for playback), and/or
 *  - a 22.05 kHz mono float signal for analysis.
 * DRM-protected content is rejected (MediaExtractor reports it and we never try to bypass it).
 */
class AudioDecoder(private val context: Context) {

    class Result(val handle: TrackHandle?, val frames: Long, val sampleRate: Int, val analysisMono: FloatArray?, val analysisRate: Int)

    fun decode(
        uri: Uri,
        engine: NativeEngine?,
        wantAnalysis: Boolean,
        maxSeconds: Double = 15 * 60.0,
        cancelled: () -> Boolean = { false },
        progress: (Float) -> Unit = {},
    ): Result {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, uri, null)
        } catch (e: Exception) {
            throw DecodeException("Cannot open audio: ${e.message}", e)
        }
        try {
            if (extractor.drmInitData != null) throw DecodeException("This file is DRM-protected and cannot be played or processed")
            var trackIndex = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) { trackIndex = i; format = f; break }
            }
            if (trackIndex < 0 || format == null) throw DecodeException("No audio track found")
            extractor.selectTrack(trackIndex)
            val mime = format.getString(MediaFormat.KEY_MIME)!!
            val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L
            var sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val durationSec = min(maxSeconds, if (durationUs > 0) durationUs / 1e6 else maxSeconds)

            val codec = try { MediaCodec.createDecoderByType(mime) } catch (e: Exception) {
                throw DecodeException("No decoder for $mime", e)
            }
            codec.configure(format, null, null, 0)
            codec.start()

            var handle: TrackHandle? = null
            var capacity = 0L
            fun ensureHandle() {
                if (engine == null || handle != null) return
                capacity = ceil((durationSec + 3.0) * sampleRate).toLong()
                handle = engine.allocTrack(capacity, sampleRate) ?: throw DecodeException("Not enough memory for this track")
            }
            var framesOut = 0L

            // Analysis down-mix to 22.05 kHz with box-filter decimation.
            val aRate = 22050
            var analysis: FloatArray? = null
            var aCount = 0
            var aAcc = 0.0; var aN = 0; var aPhase = 0.0
            var aStep = sampleRate.toDouble() / aRate
            fun ensureAnalysis() {
                if (!wantAnalysis || analysis != null) return
                analysis = FloatArray(ceil((durationSec + 3.0) * aRate).toInt())
                aStep = sampleRate.toDouble() / aRate
            }

            try {
            var pcmEncoding = AudioFormat.ENCODING_PCM_16BIT
            val info = MediaCodec.BufferInfo()
            var inputDone = false; var outputDone = false
            val maxFrames = (maxSeconds * sampleRate).toLong()
            while (!outputDone) {
                if (cancelled()) throw DecodeException("Cancelled")
                if (!inputDone) {
                    val ii = codec.dequeueInputBuffer(10_000)
                    if (ii >= 0) {
                        val buf = codec.getInputBuffer(ii)!!
                        val n = extractor.readSampleData(buf, 0)
                        if (n < 0) {
                            codec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true
                        } else {
                            codec.queueInputBuffer(ii, 0, n, extractor.sampleTime, 0); extractor.advance()
                        }
                    }
                }
                val oi = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val of = codec.outputFormat
                        sampleRate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        if (of.containsKey(MediaFormat.KEY_PCM_ENCODING)) pcmEncoding = of.getInteger(MediaFormat.KEY_PCM_ENCODING)
                        if (handle != null && handle!!.sampleRate != sampleRate) throw DecodeException("Sample rate changed mid-stream")
                    }
                    oi >= 0 -> {
                        ensureHandle(); ensureAnalysis()
                        val out = codec.getOutputBuffer(oi)!!
                        out.position(info.offset); out.limit(info.offset + info.size)
                        out.order(ByteOrder.nativeOrder())
                        val isFloat = pcmEncoding == AudioFormat.ENCODING_PCM_FLOAT
                        val bytesPerSample = if (isFloat) 4 else 2
                        val frames = info.size / (bytesPerSample * channels)
                        val fb = if (isFloat) out.asFloatBuffer() else null
                        val sb = if (!isFloat) out.asShortBuffer() else null
                        val h = handle
                        for (f in 0 until frames) {
                            if (framesOut >= maxFrames || (h != null && framesOut >= capacity)) break
                            val l: Float; val r: Float
                            if (isFloat) {
                                l = fb!!.get(f * channels); r = if (channels > 1) fb.get(f * channels + 1) else l
                            } else {
                                l = sb!!.get(f * channels) / 32768f; r = if (channels > 1) sb.get(f * channels + 1) / 32768f else l
                            }
                            if (h != null) {
                                val i = (framesOut * 2).toInt()
                                h.pcm.put(i, (max(-1f, min(1f, l)) * 32767f).toInt().toShort())
                                h.pcm.put(i + 1, (max(-1f, min(1f, r)) * 32767f).toInt().toShort())
                            }
                            val a = analysis
                            if (a != null) {
                                aAcc += (l + r) * 0.5; aN++; aPhase += 1.0
                                if (aPhase >= aStep) {
                                    aPhase -= aStep
                                    if (aCount < a.size) a[aCount++] = (aAcc / aN).toFloat()
                                    aAcc = 0.0; aN = 0
                                }
                            }
                            framesOut++
                        }
                        codec.releaseOutputBuffer(oi, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                        if (framesOut >= maxFrames || (handle != null && framesOut >= capacity)) outputDone = true
                        if (durationSec > 0) progress((framesOut / sampleRate.toDouble() / durationSec).toFloat().coerceIn(0f, 1f))
                    }
                }
            }
            codec.stop(); codec.release()
            if (framesOut < sampleRate) throw DecodeException("Decoded audio is too short")
            handle?.let { engine!!.finalizeTrack(it, framesOut) }
            return Result(handle, framesOut, sampleRate, analysis?.copyOf(aCount), aRate)
            } catch (e: Exception) {
                // Never leak native memory or a codec on failure/cancel.
                runCatching { codec.stop() }; runCatching { codec.release() }
                handle?.let { engine?.releaseTrack(it.id) }
                throw if (e is DecodeException) e else DecodeException("Decoding failed: ${e.message}", e)
            }
        } finally {
            extractor.release()
        }
    }
}
