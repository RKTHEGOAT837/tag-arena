package com.aidj.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.media.MediaBrowserServiceCompat
import androidx.media.session.MediaButtonReceiver
import com.aidj.app.AppGraph
import com.aidj.app.R
import com.aidj.app.brain.BrainStatus
import com.aidj.app.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Foreground media service: keeps the party running with the screen off or the app in the background,
 * provides notification / lock-screen / headset / Bluetooth media controls, and handles audio focus
 * and "becoming noisy" (headphones unplugged).
 */
class PartyService : MediaBrowserServiceCompat() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var session: MediaSessionCompat
    private lateinit var audioManager: AudioManager
    private var focusRequest: AudioFocusRequest? = null
    private var pausedByFocus = false
    private var foreground = false
    private val graph get() = AppGraph.get()

    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) graph.brain.pause()
        }
    }

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS -> { pausedByFocus = false; graph.brain.pause() }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> { if (graph.brain.status.value == BrainStatus.PLAYING) { pausedByFocus = true; graph.brain.pause() } }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> graph.performer.native.setParam(com.aidj.core.planning.Param.DUCK, -1, 0.6f)
            AudioManager.AUDIOFOCUS_GAIN -> {
                graph.performer.native.setParam(com.aidj.core.planning.Param.DUCK, -1, 0f)
                if (pausedByFocus) { pausedByFocus = false; graph.brain.resume() }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        AppGraph.get().start()
        audioManager = getSystemService(AudioManager::class.java)
        createChannel()
        session = MediaSessionCompat(this, "AiDj").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() { if (requestFocus()) graph.brain.resume() }
                override fun onPause() = graph.brain.pause()
                override fun onSkipToNext() { graph.brain.skip() }
                override fun onSkipToPrevious() = graph.brain.previous()
                override fun onStop() = graph.brain.stopParty()
            })
            setSessionActivity(PendingIntent.getActivity(this@PartyService, 0, Intent(this@PartyService, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
            isActive = true
        }
        sessionToken = session.sessionToken
        ContextCompat.registerReceiver(this, noisyReceiver, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY), ContextCompat.RECEIVER_NOT_EXPORTED)

        scope.launch {
            combine(graph.brain.status, graph.performer.live.map { it.current?.track?.id to it.phase }.distinctUntilChanged()) { s, _ -> s }.collect { status ->
                updateSession(status)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        MediaButtonReceiver.handleIntent(session, intent)
        when (intent?.action) {
            ACTION_START_PARTY -> { goForeground(); if (requestFocus()) graph.brain.startParty() }
            ACTION_FOREGROUND -> goForeground()
        }
        return START_STICKY
    }

    private fun requestFocus(): Boolean {
        val req = focusRequest ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setOnAudioFocusChangeListener(focusListener).setWillPauseWhenDucked(false).build().also { focusRequest = it }
        return audioManager.requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    private fun goForeground() {
        if (foreground) return
        foreground = true
        ServiceCompat.startForeground(this, NOTIF_ID, buildNotification(), if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0)
    }

    private fun updateSession(status: BrainStatus) {
        val live = graph.performer.live.value
        val t = live.current?.track
        val playing = status == BrainStatus.PLAYING
        session.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(PlaybackStateCompat.ACTION_PLAY or PlaybackStateCompat.ACTION_PAUSE or PlaybackStateCompat.ACTION_PLAY_PAUSE or
                    PlaybackStateCompat.ACTION_SKIP_TO_NEXT or PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or PlaybackStateCompat.ACTION_STOP)
                .setState(if (playing) PlaybackStateCompat.STATE_PLAYING else if (status == BrainStatus.PAUSED) PlaybackStateCompat.STATE_PAUSED else PlaybackStateCompat.STATE_STOPPED,
                    (live.positionSec * 1000).toLong(), 1f)
                .build(),
        )
        if (t != null) session.setMetadata(
            MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, t.title)
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, t.artist)
                .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, graph.settings.current.partyName)
                .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, t.durationMs)
                .build(),
        )
        if (playing) goForeground()
        if (foreground) getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification())
        if (status == BrainStatus.IDLE && foreground) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE); foreground = false
            focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        }
    }

    private fun buildNotification(): Notification {
        val live = graph.performer.live.value
        val playing = graph.brain.status.value == BrainStatus.PLAYING
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_dj)
            .setContentTitle(live.current?.track?.title ?: "AI DJ")
            .setContentText(live.current?.track?.artist?.ifBlank { null } ?: live.activity)
            .setSubText(graph.settings.current.partyName)
            .setContentIntent(session.controller.sessionActivity)
            .setDeleteIntent(MediaButtonReceiver.buildMediaButtonPendingIntent(this, PlaybackStateCompat.ACTION_STOP))
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(playing)
            .addAction(android.R.drawable.ic_media_previous, "Previous", MediaButtonReceiver.buildMediaButtonPendingIntent(this, PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS))
            .addAction(if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play, if (playing) "Pause" else "Play",
                MediaButtonReceiver.buildMediaButtonPendingIntent(this, PlaybackStateCompat.ACTION_PLAY_PAUSE))
            .addAction(android.R.drawable.ic_media_next, "Next", MediaButtonReceiver.buildMediaButtonPendingIntent(this, PlaybackStateCompat.ACTION_SKIP_TO_NEXT))
            .setStyle(androidx.media.app.NotificationCompat.MediaStyle().setMediaSession(session.sessionToken).setShowActionsInCompactView(0, 1, 2))
            .build()
    }

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Party playback", NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) })
    }

    override fun onGetRoot(clientPackageName: String, clientUid: Int, rootHints: Bundle?) = BrowserRoot("aidj_root", null)
    override fun onLoadChildren(parentId: String, result: Result<MutableList<MediaBrowserCompat.MediaItem>>) { result.sendResult(mutableListOf()) }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Keep playing when the app is swiped away during a party; stop if idle.
        if (graph.brain.status.value != BrainStatus.PLAYING) stopSelf()
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(noisyReceiver) }
        session.release()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val CHANNEL = "party"
        const val NOTIF_ID = 42
        const val ACTION_START_PARTY = "com.aidj.START_PARTY"
        const val ACTION_FOREGROUND = "com.aidj.FOREGROUND"

        fun startParty(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, PartyService::class.java).setAction(ACTION_START_PARTY))
        }
        fun ensureRunning(context: Context) {
            context.startService(Intent(context, PartyService::class.java))
        }
    }
}
