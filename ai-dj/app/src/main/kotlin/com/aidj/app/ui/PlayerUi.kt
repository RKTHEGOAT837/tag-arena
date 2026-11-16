package com.aidj.app.ui

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.aidj.app.AppGraph
import com.aidj.app.playback.Engine
import com.aidj.app.playback.PlayerHub

@Composable
fun MiniPlayer(onOpen: () -> Unit) {
    val hub = AppGraph.get().player
    val now by hub.now.collectAsState()
    val item = now.item ?: return
    Column(Modifier.fillMaxWidth().background(Neon.Panel)) {
        val progress = if (now.durationMs > 0) (now.positionMs.toFloat() / now.durationMs).coerceIn(0f, 1f) else 0f
        LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().height(2.dp), color = Neon.Cyan, trackColor = Neon.Panel2)
        Row(Modifier.fillMaxWidth().clickable { onOpen() }.padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Cover(item.track, 42.dp)
            Column(Modifier.weight(1f).padding(start = 10.dp)) {
                Text(item.track.title, color = Neon.Text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val sub = when {
                    now.waitingForNetwork -> "Waiting for internet…"
                    now.isBuffering -> "Buffering…"
                    now.engine == Engine.DJ -> "AI DJ · ${item.track.artist}"
                    now.engine == Engine.YOUTUBE -> "YouTube · ${item.track.artist}"
                    else -> item.track.artist
                }
                Text(sub, color = if (now.waitingForNetwork) Neon.Amber else Neon.Dim, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (now.isBuffering) CircularProgressIndicator(Modifier.size(22.dp), color = Neon.Cyan, strokeWidth = 2.dp)
            IconButton(onClick = { hub.togglePlay() }) { Icon(if (now.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, "Play/Pause", tint = Neon.Text, modifier = Modifier.size(30.dp)) }
            IconButton(onClick = { hub.next() }) { Icon(Icons.Default.SkipNext, "Next", tint = Neon.Text) }
        }
    }
}

@Composable
fun NowPlayingScreen(onClose: () -> Unit) {
    val g = AppGraph.get()
    val hub = g.player
    val now by hub.now.collectAsState()
    val yt by hub.youtube.collectAsState()
    val item = now.item
    var dragging by remember { mutableStateOf(false) }
    var dragPos by remember { mutableFloatStateOf(0f) }
    var volume by remember { mutableFloatStateOf(hub.exo.volume) }
    val liked = g.library.tracks.collectAsState().value.firstOrNull { it.track.id == item?.track?.id }?.liked == true

    Column(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Neon.Panel2, Neon.Bg))).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) { Icon(Icons.Default.KeyboardArrowDown, "Close", tint = Neon.Text, modifier = Modifier.size(30.dp)) }
            Text(when (now.engine) { Engine.DJ -> "AI DJ SET"; Engine.YOUTUBE -> "PLAYING FROM YOUTUBE"; else -> "NOW PLAYING" },
                color = Neon.Dim, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f), letterSpacing = 1.5.sp)
        }
        if (item == null) {
            Text("Nothing is playing. Search for a song and tap play.", color = Neon.Dim, modifier = Modifier.padding(top = 40.dp))
            return@Column
        }
        Spacer(Modifier.height(12.dp))
        if (now.engine == Engine.YOUTUBE && yt.videoId != null) {
            YouTubePlayer(hub, Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(12.dp)))
            Text("Plays in YouTube's official player while AI DJ is open (YouTube doesn't allow background playback or mixing).", color = Neon.Dim, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp))
        } else {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { Cover(item.track, 300.dp, corner = 16.dp) }
        }
        Spacer(Modifier.height(18.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(item.track.title, color = Neon.Text, fontSize = 22.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(item.track.artist.ifBlank { "Unknown artist" }, color = Neon.Dim, fontSize = 15.sp, maxLines = 1)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 6.dp)) {
                    SmallBadge(sourceLabel(item.track), sourceColor(item.track))
                    item.track.bpmHint?.let { SmallBadge("%.0f BPM".format(it), Neon.Cyan) }
                    item.track.keyHint?.let { SmallBadge(it, Neon.Violet) }
                }
            }
            IconButton(onClick = { g.library.remember(item.track); g.library.setLiked(item.track.id, !liked) }) {
                Icon(if (liked) Icons.Default.Favorite else Icons.Default.FavoriteBorder, "Favourite", tint = if (liked) Neon.Pink else Neon.Dim)
            }
        }
        now.error?.let { Text("⚠ $it", color = Neon.Red, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp)) }
        if (now.waitingForNetwork) Text("Connection lost – resumes automatically when you're back online.", color = Neon.Amber, fontSize = 12.sp)
        // Seek bar
        val dur = now.durationMs.coerceAtLeast(1)
        val pos = if (dragging) (dragPos * dur).toLong() else now.positionMs
        Slider(
            value = if (dragging) dragPos else (now.positionMs.toFloat() / dur).coerceIn(0f, 1f),
            onValueChange = { dragging = true; dragPos = it },
            onValueChangeFinished = { hub.seekTo((dragPos * dur).toLong()); dragging = false },
            enabled = now.engine == Engine.PLAYER || now.engine == Engine.YOUTUBE,
            colors = SliderDefaults.colors(thumbColor = Neon.Cyan, activeTrackColor = Neon.Cyan),
            modifier = Modifier.padding(top = 8.dp),
        )
        Row(Modifier.fillMaxWidth()) {
            Text(fmtMs(pos), color = Neon.Dim, fontSize = 12.sp, modifier = Modifier.weight(1f))
            Text(fmtMs(now.durationMs), color = Neon.Dim, fontSize = 12.sp)
        }
        Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { hub.previous() }, modifier = Modifier.size(56.dp)) { Icon(Icons.Default.SkipPrevious, "Previous", tint = Neon.Text, modifier = Modifier.size(36.dp)) }
            Box(Modifier.size(76.dp).clip(CircleShape).background(Brush.linearGradient(listOf(Neon.Cyan, Neon.Pink))).clickable { hub.togglePlay() }, contentAlignment = Alignment.Center) {
                if (now.isBuffering) CircularProgressIndicator(Modifier.size(40.dp), color = Color.Black)
                else Icon(if (now.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, "Play/Pause", tint = Color.Black, modifier = Modifier.size(44.dp))
            }
            IconButton(onClick = { hub.next() }, modifier = Modifier.size(56.dp)) { Icon(Icons.Default.SkipNext, "Next", tint = Neon.Text, modifier = Modifier.size(36.dp)) }
        }
        if (now.engine == Engine.PLAYER) Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.AutoMirrored.Filled.VolumeUp, "Volume", tint = Neon.Dim, modifier = Modifier.size(18.dp))
            Slider(volume, { volume = it; hub.setVolume(it) }, modifier = Modifier.padding(start = 8.dp), colors = SliderDefaults.colors(thumbColor = Neon.Text, activeTrackColor = Neon.Text))
        }
        Text(item.track.capabilities.note, color = Neon.Dim, fontSize = 11.sp, modifier = Modifier.padding(vertical = 8.dp))
        val upcoming = hub.queue.collectAsState().value.drop(hub.index.collectAsState().value + 1)
        if (upcoming.isNotEmpty()) {
            Text("UP NEXT", color = Neon.Dim, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp, modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
            for (q in upcoming.take(5)) Row(Modifier.fillMaxWidth().clickable { hub.jumpTo(q.uid) }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Cover(q.track, 36.dp)
                Text("${q.track.title} · ${q.track.artist}", color = Neon.Text, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 10.dp).weight(1f))
                Text(q.addedBy, color = Neon.Dim, fontSize = 10.sp)
            }
        }
        Spacer(Modifier.height(40.dp))
    }
}

/**
 * YouTube's official IFrame player in a WebView. Commands flow in from [PlayerHub.youtube]; state, end and
 * errors flow back through a JS bridge. No stream extraction: YouTube serves and controls playback itself.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun YouTubePlayer(hub: PlayerHub, modifier: Modifier = Modifier) {
    val yt by hub.youtube.collectAsState()
    val main = remember { Handler(Looper.getMainLooper()) }
    var web by remember { mutableStateOf<WebView?>(null) }
    var loadedId by remember { mutableStateOf<String?>(null) }
    AndroidView(modifier = modifier, factory = { ctx ->
        WebView(ctx).apply {
            settings.javaScriptEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.domStorageEnabled = true
            webChromeClient = WebChromeClient()
            webViewClient = WebViewClient()
            setBackgroundColor(android.graphics.Color.BLACK)
            addJavascriptInterface(object {
                @JavascriptInterface fun onState(state: Int) { main.post { if (state == 0) hub.onYouTubeEnded() } }
                @JavascriptInterface fun onTime(t: Double, d: Double, state: Int) { main.post { hub.onYouTubeState(state == 1 || state == 3, (t * 1000).toLong(), (d * 1000).toLong()) } }
                @JavascriptInterface fun onError(code: Int) { main.post { hub.onYouTubeError(code) } }
            }, "AIDJ")
            web = this
        }
    })
    LaunchedEffect(yt.videoId, web) {
        val w = web ?: return@LaunchedEffect
        val id = yt.videoId ?: return@LaunchedEffect
        if (id != loadedId) {
            loadedId = id
            w.loadDataWithBaseURL(YOUTUBE_ORIGIN, youtubeHtml(id), "text/html", "utf-8", null)
        }
    }
    LaunchedEffect(yt.commandSeq) {
        val c = yt.command ?: return@LaunchedEffect
        web?.evaluateJavascript("cmd(${org.json.JSONObject.quote(c)})", null)
    }
    DisposableEffect(Unit) { onDispose { web?.destroy(); web = null } }
}

const val YOUTUBE_ORIGIN = "https://com.aidj.app"

fun youtubeHtml(videoId: String): String = """<!DOCTYPE html><html><head><meta name="viewport" content="width=device-width,initial-scale=1">
<style>html,body{margin:0;height:100%;background:#000;overflow:hidden}#p{width:100%;height:100%}</style></head><body><div id="p"></div>
<script>
var player=null, ready=false;
function bridge(){ return (typeof AIDJ!=='undefined') ? AIDJ : null; }
var tag=document.createElement('script'); tag.src='https://www.youtube.com/iframe_api'; document.head.appendChild(tag);
function onYouTubeIframeAPIReady(){
  player=new YT.Player('p',{width:'100%',height:'100%',videoId:${org.json.JSONObject.quote(videoId)},
    playerVars:{autoplay:1,playsinline:1,controls:1,rel:0,origin:'$YOUTUBE_ORIGIN'},
    events:{
      onReady:function(e){ready=true;e.target.playVideo();},
      onStateChange:function(e){var b=bridge(); if(b) b.onState(e.data);},
      onError:function(e){var b=bridge(); if(b) b.onError(e.data);}
    }});
  setInterval(function(){ var b=bridge(); if(ready&&b&&player.getCurrentTime){ b.onTime(player.getCurrentTime()||0, player.getDuration()||0, player.getPlayerState()); } }, 500);
}
function cmd(c){ if(!ready||!player) return;
  if(c==='play') player.playVideo(); else if(c==='pause') player.pauseVideo(); else if(c==='stop') player.stopVideo();
  else if(c.indexOf('seek:')===0) player.seekTo(parseFloat(c.substring(5)), true); }
</script></body></html>"""

@Suppress("unused") private fun unusedW() = Modifier.width(1.dp)
