package com.nontonanimeid

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

// Metadata from https://api.ani.zip (mapped by MAL id)

@JsonIgnoreProperties(ignoreUnknown = true)
data class MetaImage(
    @JsonProperty("coverType") val coverType: String?,
    @JsonProperty("url") val url: String?
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class MetaEpisode(
    @JsonProperty("episode") val episode: String?,
    @JsonProperty("airDateUtc") val airDateUtc: String?,
    @JsonProperty("runtime") val runtime: Int?,
    @JsonProperty("image") val image: String?,
    @JsonProperty("title") val title: Map<String, String>?,
    @JsonProperty("overview") val overview: String?,
    @JsonProperty("rating") val rating: String?,
    @JsonProperty("finaleType") val finaleType: String?
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class MetaAnimeData(
    @JsonProperty("titles") val titles: Map<String, String>?,
    @JsonProperty("description") val description: String?,
    @JsonProperty("images") val images: List<MetaImage>?,
    @JsonProperty("episodes") val episodes: Map<String, MetaEpisode>?,
    @JsonProperty("mappings") val mappings: MetaMappings? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class MetaMappings(
    @JsonProperty("themoviedb_id") val themoviedbId: Int? = null,
    @JsonProperty("kitsu_id") val kitsuId: String? = null
)

private val metaMapper: ObjectMapper by lazy {
    ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
}

fun parseAnimeData(jsonString: String): MetaAnimeData? {
    return try {
        metaMapper.readValue(jsonString, MetaAnimeData::class.java)
    } catch (_: Exception) {
        null
    }
}

// Fallback when the tracker returns no id (common for donghua, movies and new titles):
// search AniList by title, then query ani.zip by id (ani.zip cannot search by title).

data class AnimeIds(val malId: Int?, val aniId: Int?)

private val animeIdCache = ConcurrentHashMap<String, AnimeIds>()

private class CacheBox<T>(val value: T?)
private val aniZipMetaCache = ConcurrentHashMap<String, CacheBox<MetaAnimeData>>()
private val aniListPlotCache = ConcurrentHashMap<String, CacheBox<String>>()
private val tmdbLogoCache = ConcurrentHashMap<String, CacheBox<String>>()

private const val ANILIST_SEARCH_QUERY =
    "query (\$search: String) { Page(perPage: 8) { media(search: \$search, type: ANIME, sort: SEARCH_MATCH) " +
        "{ id idMal format seasonYear startDate { year } title { romaji english native } synonyms } } }"

private fun titleKey(raw: String?): String =
    (raw ?: "").lowercase()
        .replace(Regex("\\(.*?\\)|\\[.*?]"), " ")
        .replace(Regex("subtitle indonesia|sub indo"), " ")
        .replace(Regex("[^\\p{L}\\p{N}]"), "")

private fun isCloseTitle(a: String, b: String): Boolean {
    val shorter = minOf(a.length, b.length)
    val longer = maxOf(a.length, b.length)
    return shorter >= 5 && shorter * 4 >= longer * 3 && (a.contains(b) || b.contains(a))
}

private suspend fun searchAniList(query: String): JSONArray? {
    return try {
        val res = app.post(
            "https://graphql.anilist.co",
            json = mapOf("query" to ANILIST_SEARCH_QUERY, "variables" to mapOf("search" to query))
        ).text
        JSONObject(res).optJSONObject("data")?.optJSONObject("Page")?.optJSONArray("media")
    } catch (e: Exception) {
        if (e is CancellationException) throw e
        null
    }
}

suspend fun resolveAnimeIds(
    titles: List<String>,
    type: TvType,
    year: Int?,
    malId: Int?,
    aniId: Int?
): AnimeIds {
    if (malId != null || aniId != null) return AnimeIds(malId, aniId)

    val wantedKeys = titles.map { titleKey(it) }.filter { it.length >= 3 }.distinct()
    if (wantedKeys.isEmpty()) return AnimeIds(null, null)

    val isMovie = type == TvType.AnimeMovie || type == TvType.Movie
    val cacheKey = "${wantedKeys.first()}|$year|$isMovie"
    animeIdCache[cacheKey]?.let { return it }

    val queries = titles
        .map { it.replace(Regex("(?i)subtitle indonesia|sub indo"), " ").replace(Regex("\\s+"), " ").trim() }
        .filter { it.isNotBlank() }
        .distinct()
        .take(3)

    var best: AnimeIds? = null
    var bestScore = 0
    var searched = false

    for (query in queries) {
        val media = searchAniList(query) ?: continue
        searched = true

        for (i in 0 until media.length()) {
            val m = media.optJSONObject(i) ?: continue
            if (isMovie != (m.optString("format") == "MOVIE")) continue

            val candYear = m.optInt("seasonYear", 0).takeIf { it > 0 }
                ?: m.optJSONObject("startDate")?.optInt("year", 0)?.takeIf { it > 0 }
            val yearDiff = if (year != null && candYear != null) kotlin.math.abs(year - candYear) else 0
            if (yearDiff > 1) continue

            val candKeys = mutableListOf<String>()
            m.optJSONObject("title")?.let { t ->
                for (field in listOf("romaji", "english", "native")) {
                    if (!t.isNull(field)) candKeys.add(titleKey(t.optString(field, "")))
                }
            }
            m.optJSONArray("synonyms")?.let { s ->
                for (j in 0 until s.length()) candKeys.add(titleKey(s.optString(j)))
            }

            var level = 0
            for (k in candKeys) {
                if (k.isEmpty()) continue
                for (w in wantedKeys) {
                    if (k == w) {
                        level = 2
                    } else if (level < 1 && year != null && candYear != null && isCloseTitle(k, w)) {
                        level = 1
                    }
                }
            }
            if (level == 0) continue

            val score = level * 10 - yearDiff
            if (score > bestScore) {
                bestScore = score
                best = AnimeIds(
                    m.optInt("idMal", 0).takeIf { it > 0 },
                    m.optInt("id", 0).takeIf { it > 0 }
                )
            }
        }
        if (best != null) break
    }

    val result = best ?: AnimeIds(null, null)
    if (best != null || searched) animeIdCache[cacheKey] = result
    return result
}

// AniList synopsis (English): used when ani.zip has no synopsis for the title
suspend fun fetchAniListPlot(malId: Int?, aniId: Int?): String? {
    if (malId == null && aniId == null) return null
    val cacheKey = "$malId|$aniId"
    aniListPlotCache[cacheKey]?.let { return it.value }
    val result = fetchAniListPlotUncached(malId, aniId)
    aniListPlotCache[cacheKey] = CacheBox(result)
    return result
}

private suspend fun fetchAniListPlotUncached(malId: Int?, aniId: Int?): String? {
    val filters = listOfNotNull(aniId?.let { "id: $it" }, malId?.let { "idMal: $it" })
    for (filter in filters) {
        try {
            val res = app.post(
                "https://graphql.anilist.co",
                json = mapOf("query" to "query { Media($filter, type: ANIME) { description(asHtml: false) } }")
            ).text
            val media = JSONObject(res).optJSONObject("data")?.optJSONObject("Media") ?: continue
            if (media.isNull("description")) continue
            val plot = media.optString("description")
                .replace(Regex("(?i)<br\\s*/?>"), "\n")
                .replace(Regex("<.*?>"), "")
                .trim()
            if (plot.isNotBlank()) return plot
        } catch (e: Exception) {
            if (e is CancellationException) throw e
        }
    }
    return null
}

// api.ani.zip lookup: mal_id first, anilist_id as fallback
suspend fun fetchAniZipMeta(malId: Int?, aniId: Int?): MetaAnimeData? {
    if (malId == null && aniId == null) return null
    val cacheKey = "$malId|$aniId"
    aniZipMetaCache[cacheKey]?.let { return it.value }
    val result = fetchAniZipMetaUncached(malId, aniId)
    aniZipMetaCache[cacheKey] = CacheBox(result)
    return result
}

private suspend fun fetchAniZipMetaUncached(malId: Int?, aniId: Int?): MetaAnimeData? {
    suspend fun query(param: String, id: Int): MetaAnimeData? {
        return try {
            parseAnimeData(app.get("https://api.ani.zip/mappings?$param=$id").text)
                ?.takeIf { it.titles != null || it.description != null || it.episodes != null }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            null
        }
    }
    return malId?.let { query("mal_id", it) } ?: aniId?.let { query("anilist_id", it) }
}

// Logo from https://api.themoviedb.org

suspend fun fetchTmdbLogoUrl(
    tmdbAPI: String,
    apiKey: String,
    type: TvType,
    tmdbId: Int?,
    appLangCode: String?
): String? {
    if (tmdbId == null) return null
    val cacheKey = "$tmdbId|$type|$appLangCode"
    tmdbLogoCache[cacheKey]?.let { return it.value }
    val result = fetchTmdbLogoUrlUncached(tmdbAPI, apiKey, type, tmdbId, appLangCode)
    tmdbLogoCache[cacheKey] = CacheBox(result)
    return result
}

private suspend fun fetchTmdbLogoUrlUncached(
    tmdbAPI: String,
    apiKey: String,
    type: TvType,
    tmdbId: Int?,
    appLangCode: String?
): String? {
    if (tmdbId == null) return null

    val url = if (type == TvType.AnimeMovie)
        "$tmdbAPI/movie/$tmdbId/images?api_key=$apiKey"
    else
        "$tmdbAPI/tv/$tmdbId/images?api_key=$apiKey"

    val json = runCatching { JSONObject(app.get(url).text) }.getOrNull() ?: return null
    val logos = json.optJSONArray("logos") ?: return null
    if (logos.length() == 0) return null

    val lang = appLangCode?.trim()?.lowercase()

    fun path(o: JSONObject) = o.optString("file_path")
    fun isSvg(o: JSONObject) = path(o).endsWith(".svg", true)
    fun urlOf(o: JSONObject) = "https://image.tmdb.org/t/p/w500${path(o)}"

    var svgFallback: JSONObject? = null

    for (i in 0 until logos.length()) {
        val logo = logos.optJSONObject(i) ?: continue
        val p = path(logo)
        if (p.isBlank()) continue

        val l = logo.optString("iso_639_1").trim().lowercase()
        if (l == lang) {
            if (!isSvg(logo)) return urlOf(logo)
            if (svgFallback == null) svgFallback = logo
        }
    }
    svgFallback?.let { return urlOf(it) }

    var best: JSONObject? = null
    var bestSvg: JSONObject? = null

    fun voted(o: JSONObject) = o.optDouble("vote_average", 0.0) > 0 && o.optInt("vote_count", 0) > 0
    fun better(a: JSONObject?, b: JSONObject): Boolean {
        if (a == null) return true
        val aAvg = a.optDouble("vote_average", 0.0)
        val aCnt = a.optInt("vote_count", 0)
        val bAvg = b.optDouble("vote_average", 0.0)
        val bCnt = b.optInt("vote_count", 0)
        return bAvg > aAvg || (bAvg == aAvg && bCnt > aCnt)
    }

    for (i in 0 until logos.length()) {
        val logo = logos.optJSONObject(i) ?: continue
        if (!voted(logo)) continue

        if (isSvg(logo)) {
            if (better(bestSvg, logo)) bestSvg = logo
        } else {
            if (better(best, logo)) best = logo
        }
    }

    best?.let { return urlOf(it) }
    bestSvg?.let { return urlOf(it) }

    return null
}
