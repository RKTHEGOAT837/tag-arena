package com.aidj.app.ui

import android.graphics.BitmapFactory
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aidj.app.AppGraph
import com.aidj.core.model.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

object Neon {
    val Bg = Color(0xFF05040B)
    val Panel = Color(0xFF0E0B1C)
    val Panel2 = Color(0xFF161229)
    val Cyan = Color(0xFF00E5FF)
    val Pink = Color(0xFFFF2D95)
    val Violet = Color(0xFF7C4DFF)
    val Lime = Color(0xFFB6FF3B)
    val Amber = Color(0xFFFFB020)
    val Text = Color(0xFFECEAF6)
    val Dim = Color(0xFF8D88A8)
    val Red = Color(0xFFFF4D5E)
}

@Composable
fun AiDjTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Neon.Cyan, secondary = Neon.Pink, tertiary = Neon.Violet, background = Neon.Bg, surface = Neon.Panel,
            surfaceVariant = Neon.Panel2, onPrimary = Color.Black, onSecondary = Color.Black, onBackground = Neon.Text, onSurface = Neon.Text,
            onSurfaceVariant = Neon.Dim, outline = Color(0xFF2E2850),
        ),
        content = content,
    )
}

@Composable
fun NeonChip(text: String, color: Color, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    Box(
        modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.12f))
            .border(1.dp, color.copy(alpha = 0.6f), RoundedCornerShape(50))
            .let { if (onClick != null) it.clickable { onClick() } else it }
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) { Text(text, color = color, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1) }
}

@Composable
fun EnergyBar(energy: Float, modifier: Modifier = Modifier) {
    val e by animateFloatAsState(energy.coerceIn(0f, 1f), label = "energy")
    Box(modifier.height(6.dp).clip(RoundedCornerShape(3.dp)).background(Neon.Panel2)) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(e).background(Brush.horizontalGradient(listOf(Neon.Cyan, Neon.Violet, Neon.Pink))))
    }
}

@Composable
fun Visualizer(bands: FloatArray, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val n = bands.size
        val w = size.width / n
        for (i in 0 until n) {
            val v = (bands[i] * 6f).coerceIn(0.02f, 1f)
            val h = v * size.height
            val c = if (i < n / 2) Neon.Cyan else Neon.Pink
            drawRect(c.copy(alpha = 0.85f), topLeft = Offset(i * w + w * 0.15f, size.height - h), size = androidx.compose.ui.geometry.Size(w * 0.7f, h))
        }
    }
}

/** Album art: embedded picture for local files, the catalogue image for downloaded tracks. */
@Composable
fun Artwork(track: Track?, modifier: Modifier = Modifier) {
    var bmp by remember(track?.id) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(track?.id) {
        bmp = null
        if (track == null) return@LaunchedEffect
        bmp = withContext(Dispatchers.IO) {
            runCatching {
                val bytes = AppGraph.get().library.artwork(track) ?: track.artworkUrl?.let { url ->
                    OkHttpClient().newCall(Request.Builder().url(url).build()).execute().use { it.body?.bytes() }
                }
                bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() }
            }.getOrNull()
        }
    }
    Box(modifier.clip(RoundedCornerShape(10.dp)).background(Brush.linearGradient(listOf(Neon.Violet, Neon.Pink))), contentAlignment = Alignment.Center) {
        val b = bmp
        if (b != null) Image(b, null, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize())
        else Icon(Icons.Default.MusicNote, null, tint = Color.White.copy(alpha = 0.8f))
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) =
    Text(text.uppercase(), color = Neon.Dim, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp, modifier = modifier.padding(top = 14.dp, bottom = 6.dp))

@Composable
fun StatusDot(color: Color, modifier: Modifier = Modifier) = Box(modifier.size(8.dp).clip(CircleShape).background(color))

@Composable
fun KeyValueRow(k: String, v: String) = Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
    Text(k, color = Neon.Dim, fontSize = 12.sp); Text(v, color = Neon.Text, fontSize = 12.sp)
}

fun fmtTime(sec: Double): String { val s = sec.toInt().coerceAtLeast(0); return "%d:%02d".format(s / 60, s % 60) }

@Suppress("unused") private val keepTheme = MaterialTheme
