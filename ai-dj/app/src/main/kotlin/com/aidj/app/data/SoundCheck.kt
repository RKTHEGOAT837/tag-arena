package com.aidj.app.data

import android.content.Context
import android.media.AudioManager
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.aidj.app.AppGraph
import com.aidj.core.planning.Param
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin

/**
 * On-device audio self-test: plays a short tone through (1) the standard player (ExoPlayer) and (2) the
 * DJ engine (Oboe/native), and reports what actually happened, including stream state and device volume.
 */
object SoundCheck {
    private fun toneWav(ctx: Context, hz: Double): File {
        val sr = 44100; val n = sr * 3 / 4
        val f = File(ctx.cacheDir, "soundcheck_${hz.toInt()}.wav")
        val bb = ByteBuffer.allocate(44 + n * 4).order(ByteOrder.LITTLE_ENDIAN)
        bb.put("RIFF".toByteArray()).putInt(36 + n * 4).put("WAVE".toByteArray()).put("fmt ".toByteArray())
            .putInt(16).putShort(1).putShort(2).putInt(sr).putInt(sr * 4).putShort(4).putShort(16).put("data".toByteArray()).putInt(n * 4)
        for (i in 0 until n) {
            val env = minOf(1.0, i / 2000.0, (n - i) / 2000.0)
            val v = (0.4 * env * sin(2 * PI * hz * i / sr) * 32767).toInt().toShort()
            bb.putShort(v); bb.putShort(v)
        }
        f.writeBytes(bb.array())
        return f
    }

    suspend fun run(ctx: Context): String {
        val lines = ArrayList<String>()
        val am = ctx.getSystemService(AudioManager::class.java)
        val vol = am.getStreamVolume(AudioManager.STREAM_MUSIC); val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        lines += "Media volume: $vol/$max" + if (vol == 0) " ← it's muted! Turn the volume up." else ""
        // 1) ExoPlayer
        val exoResult = withContext(Dispatchers.Main) {
            val p = ExoPlayer.Builder(ctx).build()
            var error: PlaybackException? = null
            var played = false
            p.addListener(object : Player.Listener {
                override fun onPlayerError(e: PlaybackException) { error = e }
                override fun onIsPlayingChanged(isPlaying: Boolean) { if (isPlaying) played = true }
            })
            p.setMediaItem(MediaItem.fromUri(Uri.fromFile(toneWav(ctx, 660.0))))
            p.prepare(); p.play()
            delay(1200)
            val pos = p.currentPosition
            p.release()
            when {
                error != null -> "Standard player: FAILED – ${error!!.errorCodeName}"
                played && pos > 200 -> "Standard player: OK (played ${pos} ms)"
                else -> "Standard player: didn't start (state unknown)"
            }
        }
        lines += exoResult
        // 2) DJ engine
        val g = AppGraph.get()
        val djResult = withContext(Dispatchers.Default) {
            if (g.brain.status.value.name == "PLAYING") return@withContext "DJ engine: skipped (a DJ set is playing)"
            val native = g.engine
            if (!native.isRunning && !native.start()) return@withContext "DJ engine: FAILED – the audio output stream couldn't open"
            val before = native.readState().framesRendered
            val wav = toneWav(ctx, 880.0)
            val dec = com.aidj.engine.AudioDecoder(ctx).decode(Uri.fromFile(wav), native, wantAnalysis = false)
            val h = dec.handle ?: return@withContext "DJ engine: FAILED – couldn't decode the test tone"
            native.load(1, h, 120.0, 0.0)
            native.resetDeck(1); native.setParam(Param.DECK_VOLUME, 1, 1f); native.setParam(Param.DUCK, -1, 0f)
            native.play(1, 0.0, anchorIfAlone = false)
            delay(900)
            val st = native.readState()
            native.pause(1)
            delay(100); native.unload(1); native.releaseTrack(h.id)
            val rendered = st.framesRendered - before
            when {
                rendered <= 0 -> "DJ engine: FAILED – the audio callback isn't running"
                st.peak < 0.01f -> "DJ engine: stream runs (${st.sampleRate} Hz) but output was silent (peak ${"%.3f".format(st.peak)})"
                else -> "DJ engine: OK (${st.sampleRate} Hz, ${"%.0f".format(native.latencyMs)} ms latency, peak ${"%.2f".format(st.peak)})"
            }
        }
        lines += djResult
        return lines.joinToString("\n")
    }
}
