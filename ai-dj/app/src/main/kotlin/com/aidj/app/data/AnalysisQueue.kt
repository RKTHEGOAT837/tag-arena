package com.aidj.app.data

import android.content.Context
import android.net.Uri
import android.util.Log
import com.aidj.core.analysis.AudioAnalyzer
import com.aidj.core.model.TrackAnalysis
import com.aidj.engine.AudioDecoder
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * Analyses library tracks in the background (one at a time, low priority) and caches results in
 * the database, so BPM/key/structure are ready before the DJ needs them. Urgent requests jump the queue.
 */
class AnalysisQueue(private val context: Context, private val library: LibraryRepository) {
    data class Progress(val trackId: String?, val title: String?, val fraction: Float, val remaining: Int)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val analyzer = AudioAnalyzer()
    private val decoder = AudioDecoder(context)
    private val urgent = Channel<String>(Channel.UNLIMITED)
    private val waiters = ConcurrentHashMap<String, MutableList<CompletableDeferred<TrackAnalysis?>>>()
    private val _progress = MutableStateFlow(Progress(null, null, 0f, 0))
    val progress: StateFlow<Progress> = _progress
    @Volatile var paused = false
    @Volatile private var started = false

    fun start() {
        if (started) return
        started = true
        scope.launch {
            Thread.currentThread().priority = Thread.MIN_PRIORITY
            while (true) {
                val id = urgent.tryReceive().getOrNull() ?: nextPending()
                if (id == null || paused) {
                    // Wait for urgent work or new tracks.
                    val r = kotlinx.coroutines.withTimeoutOrNull(5000) { urgent.receive() }
                    if (r != null) analyseOne(r)
                    continue
                }
                analyseOne(id)
            }
        }
    }

    private fun nextPending(): String? = library.tracks.value.firstOrNull {
        it.analysis == null && it.analysisError == null && it.track.capabilities.analysis
    }?.track?.id

    /** Analyse now (or return cached). */
    suspend fun analyseNow(id: String): TrackAnalysis? {
        library.get(id)?.analysis?.let { return it }
        val d = CompletableDeferred<TrackAnalysis?>()
        waiters.getOrPut(id) { mutableListOf() }.add(d)
        urgent.trySend(id)
        return d.await()
    }

    private suspend fun analyseOne(id: String) = withContext(Dispatchers.Default) {
        val lt = library.get(id)
        if (lt == null) { complete(id, null); return@withContext }
        lt.analysis?.let { complete(id, it); return@withContext }
        val remaining = library.tracks.value.count { it.analysis == null && it.analysisError == null }
        _progress.value = Progress(id, lt.track.title, 0f, remaining)
        val result = try {
            if (!lt.track.capabilities.analysis) throw IllegalStateException("Source does not permit analysis")
            val dec = decoder.decode(Uri.parse(lt.track.uri), engine = null, wantAnalysis = true,
                progress = { _progress.value = _progress.value.copy(fraction = it * 0.5f) })
            val mono = dec.analysisMono ?: throw IllegalStateException("No audio")
            val a = analyzer.analyze(mono, dec.analysisRate) { _progress.value = _progress.value.copy(fraction = 0.5f + it * 0.5f) }
            library.saveAnalysis(id, a, null)
            a
        } catch (e: Throwable) {
            Log.w("AnalysisQueue", "Analysis failed for ${lt.track.title}: ${e.message}")
            library.saveAnalysis(id, null, e.message ?: e.javaClass.simpleName)
            null
        }
        _progress.value = Progress(null, null, 0f, library.tracks.value.count { it.analysis == null && it.analysisError == null })
        complete(id, result)
    }

    private fun complete(id: String, a: TrackAnalysis?) {
        waiters.remove(id)?.forEach { it.complete(a) }
    }

    /** Feed analysis computed elsewhere (e.g. while decoding a track for playback). */
    fun store(id: String, a: TrackAnalysis) { library.saveAnalysis(id, a, null); complete(id, a) }
}
