package com.aidj.core.providers

import com.aidj.core.model.SourceType
import com.aidj.core.model.Track
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** Raised for user-facing provider problems (bad key, quota, outage). Message is shown to the user. */
class ProviderException(message: String, val retryable: Boolean = true, cause: Throwable? = null) : IOException(message, cause)

/** A browsable shelf (Home screen rows, genre chips). */
data class Category(val id: String, val label: String, val query: String, val audiusGenre: String? = null)

object Categories {
    val ALL = listOf(
        Category("punjabi", "Punjabi", "punjabi"),
        Category("bollywood", "Bollywood", "bollywood"),
        Category("hindi", "Hindi", "hindi"),
        Category("gujarati", "Gujarati", "gujarati garba"),
        Category("phonk", "Phonk", "phonk"),
        Category("english_pop", "English Pop", "pop", audiusGenre = "Pop"),
        Category("hiphop", "Hip-Hop", "hip hop", audiusGenre = "Hip-Hop/Rap"),
        Category("edm", "EDM", "edm", audiusGenre = "Electronic"),
        Category("house", "House", "house", audiusGenre = "House"),
        Category("chill", "Chill", "lofi chill", audiusGenre = "Lo-Fi"),
    )
    fun byId(id: String) = ALL.firstOrNull { it.id == id }
}

/**
 * A music source. Implementations only return tracks that can genuinely be played by the app
 * (authorised streams or the provider's official embedded player) — never metadata-only results.
 */
interface MusicProvider {
    val source: SourceType
    val name: String
    /** null when it works out of the box; otherwise what the user must configure. */
    val setupHint: String?
    fun isConfigured(): Boolean
    fun search(query: String, limit: Int = 20): List<Track>
    /** Popular/trending tracks for a category (falls back to search ordering by popularity). */
    fun trending(category: Category?, limit: Int = 20): List<Track> = search(category?.query ?: "top hits", limit)
    fun trackDetails(providerTrackId: String): Track? = null
}

/** Shared HTTP + JSON helpers for providers. */
open class HttpProviderBase(protected val http: OkHttpClient) {
    protected val json = Json { ignoreUnknownKeys = true }

    protected fun getJson(url: String, headers: Map<String, String> = emptyMap(), what: String): JsonElement {
        val req = Request.Builder().url(url).apply { headers.forEach { (k, v) -> header(k, v) } }.build()
        val body = try {
            http.newCall(req).execute().use { r ->
                val text = r.body?.string().orEmpty()
                when {
                    r.code == 429 -> throw ProviderException("$what is rate limiting requests – try again in a minute")
                    r.code == 401 || r.code == 403 -> throw ProviderException("$what rejected the request (check its key/settings)", retryable = false)
                    !r.isSuccessful -> throw ProviderException("$what error (HTTP ${r.code})")
                }
                text
            }
        } catch (e: ProviderException) {
            throw e
        } catch (e: IOException) {
            throw ProviderException("Can't reach $what (${e.javaClass.simpleName}${e.message?.let { ": $it" } ?: ""})", cause = e)
        }
        return try { json.parseToJsonElement(body) } catch (e: Exception) { throw ProviderException("$what returned an unreadable response") }
    }

    protected fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
    protected fun JsonObject.num(k: String): Double? = (this[k] as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()

    companion object {
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).callTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true).build()
    }
}

/** Result of a multi-provider search: tracks plus per-provider errors (shown, never hidden). */
data class CatalogResult(val tracks: List<Track>, val errors: Map<String, String>, val searchedProviders: List<String>)

/**
 * Fans a query out to every configured provider in parallel with a per-provider deadline, merges,
 * de-duplicates and keeps per-provider failures so the UI can say exactly what went wrong.
 */
class MusicCatalog(val providers: List<MusicProvider>, private val timeoutSec: Long = 15) {
    private val pool: ExecutorService = Executors.newFixedThreadPool(4) { r -> Thread(r, "catalog").apply { isDaemon = true } }

    fun configured() = providers.filter { it.isConfigured() }

    fun search(query: String, limitPerProvider: Int = 20, only: Set<SourceType>? = null): CatalogResult =
        fanOut(only) { it.search(query, limitPerProvider) }

    fun trending(category: Category?, limitPerProvider: Int = 20, only: Set<SourceType>? = null): CatalogResult =
        fanOut(only) { it.trending(category, limitPerProvider) }

    private fun fanOut(only: Set<SourceType>?, op: (MusicProvider) -> List<Track>): CatalogResult {
        val active = configured().filter { only == null || it.source in only }
        val futures = active.map { p -> p to pool.submit(Callable { op(p) }) }
        val errors = LinkedHashMap<String, String>()
        val perProvider = ArrayList<List<Track>>()
        for ((p, f) in futures) {
            try {
                perProvider += f.get(timeoutSec, TimeUnit.SECONDS)
            } catch (e: TimeoutException) {
                f.cancel(true); errors[p.name] = "${p.name} took too long to answer"
            } catch (e: java.util.concurrent.ExecutionException) {
                errors[p.name] = (e.cause as? ProviderException)?.message ?: "${p.name} failed: ${e.cause?.message ?: e.cause?.javaClass?.simpleName}"
            }
        }
        // Interleave providers (best result of each first) and drop duplicates of the same song.
        val seen = HashSet<String>()
        val merged = ArrayList<Track>()
        val maxLen = perProvider.maxOfOrNull { it.size } ?: 0
        for (i in 0 until maxLen) for (list in perProvider) {
            val t = list.getOrNull(i) ?: continue
            val key = dedupeKey(t)
            if (seen.add(t.id) && seen.add(key)) merged += t
        }
        return CatalogResult(merged, errors, active.map { it.name })
    }

    private fun dedupeKey(t: Track) = (t.artist + "|" + t.title).lowercase().replace(Regex("[^\\p{L}\\p{N}|]+"), "")
}
