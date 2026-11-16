package com.aidj.app.playback

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import com.aidj.app.data.LibraryRepository
import com.aidj.core.model.PlaybackType
import com.aidj.core.model.Track
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** One entry in the play queue. [error] records why it could not play (never silently skipped). */
data class QueueItem(val uid: Long, val track: Track, val addedBy: String = "You", val error: String? = null)

enum class Engine { NONE, PLAYER, YOUTUBE, DJ }

data class NowPlaying(
    val item: QueueItem? = null,
    val engine: Engine = Engine.NONE,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val error: String? = null,
    val waitingForNetwork: Boolean = false,
)

/**
 * The music player: queue + history + playback for everything that isn't a DJ set.
 * Streams and local files play through Media3 ExoPlayer (buffering, retries, audio focus, becoming-noisy,
 * Bluetooth/headset routing, media session for lock screen / notification / BT buttons). YouTube results
 * play through YouTube's official embedded player (rendered by the UI; foreground only).
 *
 * All methods must be called on the main thread (ExoPlayer's application looper).
 */
class PlayerHub(private val context: Context, private val library: LibraryRepository) {
    private val main = Handler(Looper.getMainLooper())
    private val uidGen = AtomicLong(System.currentTimeMillis())

    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true).build()

    val exo: ExoPlayer = ExoPlayer.Builder(context)
        .setMediaSourceFactory(
            DefaultMediaSourceFactory(DefaultDataSource.Factory(context, OkHttpDataSource.Factory(http).setUserAgent(USER_AGENT)))
                .setLoadErrorHandlingPolicy(DefaultLoadErrorHandlingPolicy(6)),
        )
        .setLoadControl(DefaultLoadControl.Builder().setBufferDurationsMs(20_000, 120_000, 1_500, 3_000).build())
        .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
        .setHandleAudioBecomingNoisy(true)
        .setWakeMode(C.WAKE_MODE_NETWORK)
        .build()

    /** The player the media session exposes: next/previous follow our queue (which may include YouTube items). */
    val sessionPlayer: Player = object : ForwardingPlayer(exo) {
        override fun getAvailableCommands(): Player.Commands = super.getAvailableCommands().buildUpon()
            .addAll(Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM, Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM).build()
        override fun isCommandAvailable(command: Int): Boolean = when (command) {
            // Must call the hub's queue methods explicitly: unqualified next()/hasNext() here would resolve to
            // Player's deprecated next()/hasNext(), which re-enter seekToNext() and recurse forever.
            Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM -> this@PlayerHub.hasNext()
            Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> true
            else -> super.isCommandAvailable(command)
        }
        override fun hasNextMediaItem() = this@PlayerHub.hasNext()
        override fun hasPreviousMediaItem() = true
        override fun seekToNext() = this@PlayerHub.next()
        override fun seekToNextMediaItem() = this@PlayerHub.next()
        override fun seekToPrevious() = this@PlayerHub.previous()
        override fun seekToPreviousMediaItem() = this@PlayerHub.previous()
        override fun play() { if (engine == Engine.DJ) djHandler?.invoke("play") else super.play() }
        override fun pause() { if (engine == Engine.DJ) djHandler?.invoke("pause") else super.pause() }
    }

    private val _queue = MutableStateFlow<List<QueueItem>>(emptyList())
    val queue: StateFlow<List<QueueItem>> = _queue
    private val _index = MutableStateFlow(-1)
    val index: StateFlow<Int> = _index
    private val _now = MutableStateFlow(NowPlaying())
    val now: StateFlow<NowPlaying> = _now
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val messages: SharedFlow<String> = _messages

    /** YouTube embed state, driven by the UI's WebView player. */
    data class YouTubeState(val videoId: String? = null, val playing: Boolean = false, val positionMs: Long = 0, val durationMs: Long = 0, val command: String? = null, val commandSeq: Long = 0)
    private val _youtube = MutableStateFlow(YouTubeState())
    val youtube: StateFlow<YouTubeState> = _youtube

    @Volatile var engine = Engine.NONE
        private set
    /** Set by the DJ integration: routes play/pause from the media session while a DJ set runs. */
    var djHandler: ((String) -> Unit)? = null
    private var waitingForNetwork = false

    val current: QueueItem? get() = _queue.value.getOrNull(_index.value)

    init {
        exo.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO && mediaItem != null) {
                    // ExoPlayer moved gaplessly to the preloaded next item: follow it in our queue.
                    val uid = mediaItem.mediaId.toLongOrNull()
                    val idx = _queue.value.indexOfFirst { it.uid == uid }
                    if (idx >= 0) { _index.value = idx; onStarted(_queue.value[idx]); appendNextIfDirect() }
                }
                publish()
            }
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED && engine == Engine.PLAYER) advance(auto = true)
                publish()
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) = publish()
            override fun onPlayerError(error: PlaybackException) = handleError(error)
        })
        val cm = context.getSystemService(ConnectivityManager::class.java)
        cm?.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { main.post { if (waitingForNetwork) recoverFromNetwork() } }
        })
        main.post(object : Runnable {
            override fun run() { publish(); main.postDelayed(this, 250) }
        })
    }

    // ------------------------------------------------------------------ queue operations

    private fun newItem(t: Track, by: String) = QueueItem(uidGen.incrementAndGet(), t, by)

    /** Play [track] right now (inserted after the current item; the rest of the queue is kept). */
    fun playNow(track: Track) {
        library.remember(track)
        val q = _queue.value.toMutableList()
        val at = (_index.value + 1).coerceIn(0, q.size)
        q.add(at, newItem(track, "You"))
        _queue.value = q
        startAt(at)
    }

    /** Replace the queue with [tracks] and start at [start] (e.g. playing a playlist or a result list). */
    fun playAll(tracks: List<Track>, start: Int = 0) {
        if (tracks.isEmpty()) return
        tracks.forEach(library::remember)
        _queue.value = tracks.map { newItem(it, "You") }
        startAt(start.coerceIn(0, tracks.size - 1))
    }

    fun addToQueue(track: Track, by: String = "You") {
        library.remember(track)
        _queue.value = _queue.value + newItem(track, by)
        if (engine == Engine.NONE && current == null) startAt(_queue.value.size - 1)
        else if (engine == Engine.PLAYER) appendNextIfDirect()
        _messages.tryEmit("Added to queue: ${track.title}")
    }

    fun addNext(track: Track) {
        library.remember(track)
        val q = _queue.value.toMutableList()
        q.add((_index.value + 1).coerceIn(0, q.size), newItem(track, "You"))
        _queue.value = q
        if (engine == Engine.PLAYER) resyncNext()
        _messages.tryEmit("Playing next: ${track.title}")
    }

    fun remove(uid: Long) {
        val q = _queue.value.toMutableList()
        val i = q.indexOfFirst { it.uid == uid }
        if (i < 0) return
        val wasCurrent = i == _index.value
        q.removeAt(i)
        _queue.value = q
        when {
            wasCurrent -> if (i < q.size) startAt(i) else { stopPlayer(); _index.value = q.size - 1 }
            i < _index.value -> _index.value = _index.value - 1
        }
        if (engine == Engine.PLAYER && !wasCurrent) resyncNext()
    }

    fun move(uid: Long, delta: Int) {
        val q = _queue.value.toMutableList()
        val i = q.indexOfFirst { it.uid == uid }
        val j = (i + delta).coerceIn(0, q.size - 1)
        if (i < 0 || i == j) return
        val curUid = current?.uid
        q.add(j, q.removeAt(i))
        _queue.value = q
        _index.value = q.indexOfFirst { it.uid == curUid }
        if (engine == Engine.PLAYER) resyncNext()
    }

    fun clearUpcoming() {
        val cur = current
        _queue.value = listOfNotNull(cur)
        _index.value = if (cur != null) 0 else -1
        if (engine == Engine.PLAYER) resyncNext()
    }

    fun jumpTo(uid: Long) { val i = _queue.value.indexOfFirst { it.uid == uid }; if (i >= 0) startAt(i) }

    fun upcoming(): List<QueueItem> = _queue.value.drop(_index.value + 1)

    fun hasNext() = _index.value + 1 < _queue.value.size

    // ------------------------------------------------------------------ transport

    fun togglePlay() {
        when (engine) {
            Engine.PLAYER -> if (exo.isPlaying) exo.pause() else { if (exo.playbackState == Player.STATE_IDLE) exo.prepare(); exo.play() }
            Engine.YOUTUBE -> youtubeCommand(if (_youtube.value.playing) "pause" else "play")
            Engine.DJ -> djHandler?.invoke("toggle")
            Engine.NONE -> if (_queue.value.isNotEmpty()) startAt(_index.value.coerceAtLeast(0))
        }
    }

    fun pause() { when (engine) { Engine.PLAYER -> exo.pause(); Engine.YOUTUBE -> youtubeCommand("pause"); else -> {} } }

    fun next() { if (engine == Engine.DJ) djHandler?.invoke("next") else advance(auto = false) }

    fun previous() {
        if (engine == Engine.DJ) { djHandler?.invoke("previous"); return }
        val pos = if (engine == Engine.YOUTUBE) _youtube.value.positionMs else exo.currentPosition
        if (pos > 3000) seekTo(0) else if (_index.value > 0) startAt(_index.value - 1) else seekTo(0)
    }

    fun seekTo(ms: Long) {
        when (engine) {
            Engine.PLAYER -> exo.seekTo(ms)
            Engine.YOUTUBE -> youtubeCommand("seek:${ms / 1000.0}")
            else -> {}
        }
    }

    fun setVolume(v: Float) { exo.volume = v.coerceIn(0f, 1f) }

    private fun advance(auto: Boolean) {
        val next = _index.value + 1
        if (next < _queue.value.size) startAt(next)
        else {
            if (auto) _messages.tryEmit("Queue finished – search or let the AI DJ pick more")
            stopPlayer()
            publish()
        }
    }

    private fun startAt(i: Int) {
        val item = _queue.value.getOrNull(i) ?: return
        if (engine == Engine.DJ) {
            // A DJ set is running: never start a second audio path. Mixable songs are mixed in next by the DJ.
            if (item.track.djDecodable) { djHandler?.invoke("mix:${item.track.id}"); _messages.tryEmit("AI DJ will mix in “${item.track.title}” next") }
            else _messages.tryEmit("YouTube videos can't play during a DJ set – stop the AI DJ to play it")
            return
        }
        _index.value = i
        waitingForNetwork = false
        if (item.track.playback == PlaybackType.EMBEDDED_PLAYER) {
            exo.stop(); exo.clearMediaItems()
            engine = Engine.YOUTUBE
            _youtube.value = YouTubeState(videoId = item.track.providerTrackId, commandSeq = _youtube.value.commandSeq)
        } else {
            if (engine == Engine.YOUTUBE) youtubeCommand("stop")
            engine = Engine.PLAYER
            _youtube.value = YouTubeState(commandSeq = _youtube.value.commandSeq)
            exo.setMediaItems(listOf(mediaItem(item)), true)
            exo.prepare()
            exo.play()
            appendNextIfDirect()
            PlaybackService.ensureStarted(context)
        }
        clearError(item.uid)
        onStarted(item)
        publish()
    }

    private fun onStarted(item: QueueItem) {
        library.remember(item.track)
        library.recordPlay(item.track.id, "player")
    }

    /** Preload the next queue item in ExoPlayer for gapless playback (only if it's an ExoPlayer item). */
    private fun appendNextIfDirect() {
        if (engine != Engine.PLAYER) return
        val nextItem = _queue.value.getOrNull(_index.value + 1) ?: return
        if (nextItem.track.playback == PlaybackType.EMBEDDED_PLAYER) return
        while (exo.mediaItemCount > exo.currentMediaItemIndex + 1) exo.removeMediaItem(exo.mediaItemCount - 1)
        exo.addMediaItem(mediaItem(nextItem))
    }

    private fun resyncNext() {
        if (engine != Engine.PLAYER) return
        while (exo.mediaItemCount > exo.currentMediaItemIndex + 1) exo.removeMediaItem(exo.mediaItemCount - 1)
        appendNextIfDirect()
    }

    private fun stopPlayer() {
        exo.stop()
        if (engine == Engine.YOUTUBE) youtubeCommand("stop")
        engine = Engine.NONE
    }

    private fun mediaItem(item: QueueItem): MediaItem = MediaItem.Builder()
        .setMediaId(item.uid.toString())
        .setUri(Uri.parse(item.track.uri))
        .setMediaMetadata(
            MediaMetadata.Builder().setTitle(item.track.title).setArtist(item.track.artist.ifBlank { null })
                .setAlbumTitle(item.track.album.ifBlank { null })
                .setArtworkUri(item.track.artworkUrl?.let(Uri::parse)).build(),
        ).build()

    // ------------------------------------------------------------------ errors & recovery

    private fun handleError(e: PlaybackException) {
        val item = current ?: return
        val network = e.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
            e.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT
        if (network && !isOnline()) {
            // Keep the track; resume from the same position when the connection is back.
            waitingForNetwork = true
            _messages.tryEmit("Connection lost – will resume ${item.track.title} when you're back online")
            publish()
            return
        }
        val reason = when (e.errorCode) {
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> "The source refused the stream (it may have been removed or restricted)"
            PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND -> "File not found (moved or deleted?)"
            PlaybackException.ERROR_CODE_IO_NO_PERMISSION -> "No permission to read this file any more – re-import it"
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED, PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED -> "Unsupported or damaged audio format"
            PlaybackException.ERROR_CODE_DECODING_FAILED, PlaybackException.ERROR_CODE_DECODER_INIT_FAILED -> "This phone couldn't decode the audio"
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> "Network error while streaming (after retries)"
            else -> "Playback failed: ${e.errorCodeName}"
        }
        markFailed(item, reason)
    }

    /** Record the failure on the item and in the error log, tell the user, then continue with the next item. */
    fun markFailed(item: QueueItem, reason: String) {
        Log.w("PlayerHub", "Track failed: ${item.track.displayName}: $reason")
        library.recordPlayError(item.track, reason)
        _queue.value = _queue.value.map { if (it.uid == item.uid) it.copy(error = reason) else it }
        _messages.tryEmit("Couldn't play “${item.track.title}”: $reason. Skipping to the next track.")
        _now.value = _now.value.copy(error = reason)
        if (item.uid == current?.uid) advance(auto = true)
    }

    private fun clearError(uid: Long) { _queue.value = _queue.value.map { if (it.uid == uid && it.error != null) it.copy(error = null) else it } }

    private fun recoverFromNetwork() {
        waitingForNetwork = false
        _messages.tryEmit("Back online – resuming")
        if (engine == Engine.PLAYER) { exo.prepare(); exo.play() }
        publish()
    }

    private fun isOnline(): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        return cm.getNetworkCapabilities(cm.activeNetwork)?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    }

    // ------------------------------------------------------------------ YouTube embed bridge (called by the UI's WebView)

    private fun youtubeCommand(cmd: String) { _youtube.value = _youtube.value.copy(command = cmd, commandSeq = _youtube.value.commandSeq + 1) }

    fun onYouTubeState(playing: Boolean, positionMs: Long, durationMs: Long) {
        _youtube.value = _youtube.value.copy(playing = playing, positionMs = positionMs, durationMs = durationMs)
        publish()
    }

    fun onYouTubeEnded() { if (engine == Engine.YOUTUBE) advance(auto = true) }

    fun onYouTubeError(code: Int) {
        val item = current ?: return
        val reason = when (code) {
            2 -> "YouTube rejected the video id"
            5 -> "YouTube's player couldn't play this video"
            100 -> "Video removed or private"
            101, 150 -> "The owner doesn't allow this video to be played in other apps"
            152, 153 -> "YouTube refused the embedded player on this device"
            else -> "YouTube player error $code"
        }
        markFailed(item, reason)
    }

    /** YouTube's terms don't allow background playback of embeds: pause when the app leaves the foreground. */
    fun onAppBackgrounded() { if (engine == Engine.YOUTUBE && _youtube.value.playing) youtubeCommand("pause") }

    // ------------------------------------------------------------------ DJ hand-over

    /** The DJ engine takes over audio: stop the player (the queue stays shared). */
    fun enterDjMode() {
        if (engine == Engine.PLAYER) exo.pause()
        if (engine == Engine.YOUTUBE) youtubeCommand("pause")
        engine = Engine.DJ
        publish()
    }

    fun exitDjMode() { if (engine == Engine.DJ) { engine = Engine.NONE; publish() } }

    /** The DJ started playing [track]: make it the current queue item (inserting it if the AI picked it). */
    fun djNowPlaying(track: Track) {
        library.remember(track)
        val q = _queue.value
        val ahead = q.withIndex().firstOrNull { it.index > _index.value && it.value.track.id == track.id }
        if (ahead != null) {
            // Anything the DJ passed over on the way (e.g. YouTube videos) is marked with the reason.
            for (i in (_index.value + 1) until ahead.index) {
                val skipped = q[i]
                if (skipped.track.isEmbedded) djSkipped(skipped.uid, "YouTube videos play only in YouTube's own player, so the DJ can't mix them – tap it to play it in player mode")
            }
            _index.value = ahead.index
        }
        else if (current?.track?.id != track.id) {
            val m = q.toMutableList()
            val at = (_index.value + 1).coerceIn(0, m.size)
            m.add(at, newItem(track, "AI DJ"))
            _queue.value = m
            _index.value = at
        }
        publish()
    }

    /** Record a queue item the DJ can't mix (e.g. YouTube) so it's visibly skipped with a reason. */
    fun djSkipped(uid: Long, reason: String) {
        val item = _queue.value.firstOrNull { it.uid == uid } ?: return
        if (item.error == null) {
            library.recordPlayError(item.track, reason)
            _queue.value = _queue.value.map { if (it.uid == uid) it.copy(error = reason) else it }
            _messages.tryEmit("AI DJ skipped “${item.track.title}”: $reason")
        }
    }

    fun publish() {
        val cur = current
        _now.value = when (engine) {
            Engine.PLAYER -> NowPlaying(cur, engine, exo.isPlaying, exo.playbackState == Player.STATE_BUFFERING,
                exo.currentPosition.coerceAtLeast(0), exo.duration.takeIf { it > 0 } ?: (cur?.track?.durationMs ?: 0),
                cur?.error, waitingForNetwork)
            Engine.YOUTUBE -> NowPlaying(cur, engine, _youtube.value.playing, false, _youtube.value.positionMs,
                _youtube.value.durationMs.takeIf { it > 0 } ?: (cur?.track?.durationMs ?: 0), cur?.error)
            Engine.DJ -> _now.value.copy(item = cur, engine = engine)
            Engine.NONE -> NowPlaying(cur, engine, false, false, 0, cur?.track?.durationMs ?: 0, cur?.error)
        }
    }

    /** DJ mode feeds its live state here so the mini-player / notification-less UI stays consistent. */
    fun publishDj(isPlaying: Boolean, positionMs: Long, durationMs: Long) {
        if (engine == Engine.DJ) _now.value = NowPlaying(current, Engine.DJ, isPlaying, false, positionMs, durationMs, current?.error)
    }

    companion object { const val USER_AGENT = "AIDJ/1.1 (Android)" }
}
