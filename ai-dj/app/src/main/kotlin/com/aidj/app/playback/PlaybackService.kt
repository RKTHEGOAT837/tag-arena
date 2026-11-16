package com.aidj.app.playback

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.aidj.app.AppGraph
import com.aidj.app.ui.MainActivity

/**
 * Media3 session service for normal playback. Provides the media notification, lock-screen controls,
 * Bluetooth/headset media buttons and keeps playback alive in the background. The player itself lives
 * in [PlayerHub] (process singleton) so the UI talks to it directly.
 */
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val graph = AppGraph.get()
        graph.start()
        session = MediaSession.Builder(this, graph.player.sessionPlayer)
            .setSessionActivity(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        val p = session?.player
        if (p == null || !p.playWhenReady || p.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        session?.release()
        session = null
        super.onDestroy()
    }

    companion object {
        fun ensureStarted(context: Context) {
            runCatching { context.startService(Intent(context, PlaybackService::class.java)) }
        }
    }
}
