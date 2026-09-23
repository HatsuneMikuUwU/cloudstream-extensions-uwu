package com.animein

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addAniListId
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId
import com.lagradost.cloudstream3.LoadResponse.Companion.addScore
import com.lagradost.cloudstream3.utils.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

class Animein : MainAPI() {
    override var mainUrl = "https://gate.nextanimelist.com"
    override var name = "Animein"
    override val hasMainPage = true
    override var lang = "id"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    companion object {
        private const val GATE_URL = "https://gate.nextanimelist.com"
        private const val FALLBACK_API = "https://xyz-api.animein.net"
        private const val ASSET_BASE = "https://xyz-api.animein.net/assets/images/"
        private const val APP_UA = "ANIMEIN/5.2.2"
        private const val VERSION = "5.2.2"
        private const val VERSION_CODE = "115"
        private const val PACKAGE_NAME = "com.okeko.animein"

        @Volatile private var apiBase: String? = null
        @Volatile private var accessToken: String? = null
        @Volatile private var deviceId: String = UUID.randomUUID().toString()
    }

    private val defaultHeaders: Map<String, String>
        get() = buildMap {
            put("User-Agent", APP_UA)
            put("Accept", "application/json")
            put("Accept-Language", "id-ID,id;q=0.9,en-US;q=0.8")
            put("X-Requested-With", PACKAGE_NAME)
            put("X-App-Version", VERSION)
            put("X-Version-Code", VERSION_CODE)
            put("X-Package", PACKAGE_NAME)
            accessToken?.let { put("Authorization", "Bearer $it") }
        }

    private suspend fun ensureApiBase(): String {
        apiBase?.let { return it }
        return try {
            val res = app.get(
                "$GATE_URL/data/setup/data",
                headers = defaultHeaders
            ).parsedSafe<ApiResponse<SetupData>>()
            val domain = res?.data?.domainApi?.value?.trimEnd('/')
            val resolved = when {
                !domain.isNullOrBlank() -> domain
                else -> FALLBACK_API
            }
            apiBase = resolved
            resolved
        } catch (_: Exception) {
            apiBase = FALLBACK_API
            FALLBACK_API
        }
    }

    private suspend fun apiGet(path: String, params: Map<String, String> = emptyMap()): String {
        val base = ensureApiBase()
        val url = buildString {
            append(base.trimEnd('/'))
            append('/')
            append(path.trimStart('/'))
            if (params.isNotEmpty()) {
                append('?')
                append(params.entries.joinToString("&") { "${it.key}=${it.value}" })
            }
        }
        // Prefer real API host; fall back to gate if blocked
        val primary = runCatching {
            app.get(url, headers = defaultHeaders).text
        }.getOrNull()
        if (!primary.isNullOrBlank() && !primary.contains("Just a moment", true) && !primary.startsWith("403")) {
            return primary
        }
        val gateUrl = url.replace(base, GATE_URL)
        return app.get(gateUrl, headers = defaultHeaders).text
    }

    private suspend fun apiGetJson(path: String, params: Map<String, String> = emptyMap()): String {
        return apiGet(path, params)
    }

    override val mainPage = mainPageOf(
        "3/2/home/new" to "Terbaru",
        "3/2/home/hot" to "Hot",
        "3/2/home/popular" to "Populer",
        "3/2/home/random" to "Random",
        "3/2/explore/movie" to "Explore"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val path = request.data
        val json = apiGetJson(path, mapOf("page" to page.toString(), "limit" to "20"))
        val items = parseMovieList(json)
        return newHomePageResponse(
            listOf(HomePageList(request.name, items, isHorizontalImages = true)),
            hasNext = items.isNotEmpty()
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val json = apiGetJson(
            "3/2/explore/movie",
            mapOf(
                "page" to "1",
                "limit" to "30",
                "query" to query,
                "search" to query,
                "keyword" to query
            )
        )
        return parseMovieList(json)
    }

    override suspend fun load(url: String): LoadResponse? {
        val id = url.substringAfterLast("/").substringBefore("?").trim()
        if (id.isBlank()) return null

        val detailJson = apiGetJson("3/2/movie/detail/$id")
        val detail = parseMovieDetail(detailJson)
        val episodeJson = apiGetJson("3/2/movie/episode/$id")
        val episodes = parseEpisodeList(episodeJson, id)

        val title = detail?.title ?: "Anime $id"
        val poster = detail?.posterUrl
        val plot = detail?.synopsis
        val tags = detail?.genres.orEmpty()
        val year = detail?.year
        val score = detail?.score
        val status = when {
            detail?.status?.contains("ongoing", true) == true -> ShowStatus.Ongoing
            detail?.status?.contains("completed", true) == true -> ShowStatus.Completed
            else -> null
        }
        val type = when {
            detail?.type?.contains("movie", true) == true -> TvType.AnimeMovie
            detail?.type?.contains("ova", true) == true -> TvType.OVA
            detail?.type?.contains("ona", true) == true -> TvType.OVA
            episodes.size <= 1 && detail?.type?.contains("movie", true) == true -> TvType.AnimeMovie
            else -> TvType.Anime
        }

        return if (episodes.isNotEmpty()) {
            newAnimeLoadResponse(title, url, type) {
                this.posterUrl = poster
                this.year = year
                this.plot = plot
                this.tags = tags
                showStatus = status
                score?.let { addScore(it.toString(), 10) }
                detail?.malId?.let { addMalId(it) }
                detail?.anilistId?.let { addAniListId(it) }
                addEpisodes(DubStatus.Subbed, episodes)
            }
        } else {
            newMovieLoadResponse(title, url, type, "animein://stream/$id") {
                this.posterUrl = poster
                this.year = year
                this.plot = plot
                this.tags = tags
                score?.let { addScore(it.toString(), 10) }
                detail?.malId?.let { addMalId(it) }
                detail?.anilistId?.let { addAniListId(it) }
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val episodeId = when {
            data.startsWith("animein://episode/") -> data.removePrefix("animein://episode/")
            data.startsWith("animein://stream/") -> data.removePrefix("animein://stream/")
            data.contains("/episode/") -> data.substringAfterLast("/")
            else -> data.substringAfterLast("/").substringBefore("?")
        }.trim()
        if (episodeId.isBlank()) return false

        val json = apiGetJson("3/2/episode/streamnew/$episodeId")
        val sources = parseStreamSources(json)
        var found = false
        for (src in sources) {
            val link = src.url ?: continue
            if (link.isBlank()) continue
            found = true
            if (link.contains(".m3u8", true) || link.contains("googlevideo", true) ||
                link.endsWith(".mp4", true) || link.contains("videoplayback")
            ) {
                callback(
                    newExtractorLink(
                        src.name ?: name,
                        src.name ?: name,
                        link,
                        INFER_TYPE
                    ) {
                        this.referer = mainUrl
                        this.quality = getQualityFromName(src.quality)
                        src.headers?.let { this.headers = it }
                    }
                )
            } else {
                loadExtractor(link, mainUrl, subtitleCallback, callback)
            }
        }
        return found
    }

    // ─── parsers ───────────────────────────────────────────────────────────

    private fun tryParseObject(json: String): JSONObject? {
        return try {
            JSONObject(json)
        } catch (_: Exception) {
            null
        }
    }

    private fun jsonArrayToList(arr: JSONArray?): List<JSONObject> {
        if (arr == null) return emptyList()
        return buildList {
            for (i in 0 until arr.length()) {
                arr.optJSONObject(i)?.let { add(it) }
            }
        }
    }

    private fun extractJsonList(root: JSONObject): List<JSONObject> {
        val data = root.opt("data")
        when (data) {
            is JSONArray -> return jsonArrayToList(data)
            is JSONObject -> {
                for (key in listOf("list", "items", "movies", "results", "data", "rows", "content", "records", "episodes", "streams", "sources", "servers")) {
                    val v = data.opt(key)
                    if (v is JSONArray) return jsonArrayToList(v)
                }
                if (data.has("id") || data.has("id_movie") || data.has("title")) {
                    return listOf(data)
                }
            }
        }
        for (key in listOf("list", "items", "movies", "results", "rows", "episodes", "streams", "sources")) {
            val v = root.opt(key)
            if (v is JSONArray) return jsonArrayToList(v)
        }
        return emptyList()
    }

    private fun jStr(obj: JSONObject?, vararg keys: String): String? {
        if (obj == null) return null
        for (key in keys) {
            if (!obj.has(key) || obj.isNull(key)) continue
            val s = obj.optString(key, "").trim()
            if (s.isNotBlank() && s != "null") return s
        }
        return null
    }

    private fun parseMovieList(json: String): List<SearchResponse> {
        val root = tryParseObject(json) ?: return emptyList()
        return extractJsonList(root).mapNotNull { obj ->
            val id = jStr(obj, "id", "id_movie", "idMovie", "movie_id", "movieId") ?: return@mapNotNull null
            val title = jStr(obj, "title", "name", "judul", "movie_title", "title_id", "title_en")
                ?: return@mapNotNull null
            val poster = resolveImage(
                jStr(
                    obj,
                    "poster", "image", "cover", "thumbnail", "thumb",
                    "poster_url", "image_url", "cover_url", "photo", "banner"
                )
            )
            val typeStr = jStr(obj, "type", "movie_type", "tipe")
            val tvType = when {
                typeStr?.contains("movie", true) == true -> TvType.AnimeMovie
                typeStr?.contains("ova", true) == true -> TvType.OVA
                else -> TvType.Anime
            }
            newAnimeSearchResponse(title, "$mainUrl/movie/$id", tvType) {
                this.posterUrl = poster
            }
        }
    }

    private fun parseMovieDetail(json: String): MovieDetail? {
        val root = tryParseObject(json) ?: return null
        val data = root.optJSONObject("data") ?: root.optJSONObject("movie") ?: root
        val id = jStr(data, "id", "id_movie", "idMovie", "movie_id") ?: return null
        val genres = mutableListOf<String>()
        for (key in listOf("genre", "genres", "genre_list")) {
            val arr = data.optJSONArray(key)
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val item = arr.opt(i)
                    when (item) {
                        is String -> if (item.isNotBlank()) genres.add(item)
                        is JSONObject -> jStr(item, "name", "title", "genre", "label")?.let { genres.add(it) }
                    }
                }
                break
            }
            val s = jStr(data, key)
            if (!s.isNullOrBlank()) {
                genres.addAll(s.split(",", "/").map { it.trim() }.filter { it.isNotBlank() })
                break
            }
        }
        return MovieDetail(
            id = id,
            title = jStr(data, "title", "name", "judul", "title_id", "title_en", "movie_title"),
            posterUrl = resolveImage(
                jStr(data, "poster", "image", "cover", "poster_url", "image_url", "cover_url", "photo")
            ),
            synopsis = jStr(data, "synopsis", "description", "sinopsis", "plot", "overview", "story"),
            genres = genres,
            year = jStr(data, "year", "release_year", "tahun")?.toIntOrNull(),
            score = jStr(data, "score", "rating", "rate", "mal_score")?.toDoubleOrNull(),
            status = jStr(data, "status", "movie_status"),
            type = jStr(data, "type", "movie_type", "tipe"),
            malId = jStr(data, "mal_id", "malId", "id_mal")?.toIntOrNull(),
            anilistId = jStr(data, "anilist_id", "anilistId", "id_anilist")?.toIntOrNull()
        )
    }

    private fun parseEpisodeList(json: String, movieId: String): List<Episode> {
        val root = tryParseObject(json) ?: return emptyList()
        return extractJsonList(root).mapIndexedNotNull { index, obj ->
            val epId = jStr(obj, "id", "id_episode", "idEpisode", "episode_id", "episodeId")
                ?: return@mapIndexedNotNull null
            val epNum = jStr(obj, "episode", "episode_number", "number", "ep", "no")
                ?.toIntOrNull()
                ?: (index + 1)
            val title = jStr(obj, "title", "name", "episode_title") ?: "Episode $epNum"
            val thumb = resolveImage(jStr(obj, "image", "thumbnail", "thumb", "poster", "cover"))
            newEpisode("animein://episode/$epId") {
                this.name = title
                this.episode = epNum
                this.posterUrl = thumb
            }
        }.sortedBy { it.episode }
    }

    private fun parseStreamSources(json: String): List<StreamSource> {
        val root = tryParseObject(json) ?: return emptyList()
        val results = mutableListOf<StreamSource>()

        fun consider(obj: JSONObject, prefix: String? = null) {
            val url = jStr(
                obj,
                "url", "stream", "stream_url", "file", "src", "link",
                "video", "video_url", "play_url", "source", "path"
            )
            if (!url.isNullOrBlank() && (url.startsWith("http") || url.startsWith("//"))) {
                results.add(
                    StreamSource(
                        name = jStr(obj, "name", "server", "label", "quality", "host") ?: prefix ?: "Animein",
                        url = if (url.startsWith("//")) "https:$url" else url,
                        quality = jStr(obj, "quality", "res", "resolution", "label"),
                        headers = null
                    )
                )
            }
            val keys = obj.keys()
            while (keys.hasNext()) {
                when (val v = obj.opt(keys.next())) {
                    is JSONObject -> consider(v, prefix)
                    is JSONArray -> {
                        for (i in 0 until v.length()) {
                            v.optJSONObject(i)?.let { consider(it, prefix) }
                        }
                    }
                }
            }
        }
        consider(root)
        return results.distinctBy { it.url }
    }

    private fun resolveImage(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val url = raw.trim()
        return when {
            url.startsWith("http://") || url.startsWith("https://") -> url
            url.startsWith("//") -> "https:$url"
            url.startsWith("/") -> ASSET_BASE.trimEnd('/') + url
            else -> ASSET_BASE + url.trimStart('/')
        }
    }

    private fun getQualityFromName(q: String?): Int {
        if (q.isNullOrBlank()) return Qualities.Unknown.value
        val lower = q.lowercase()
        return when {
            "1080" in lower || "fhd" in lower || "1k" in lower -> Qualities.P1080.value
            "720" in lower || "hd" in lower -> Qualities.P720.value
            "480" in lower -> Qualities.P480.value
            "360" in lower || "sd" in lower -> Qualities.P360.value
            "240" in lower -> Qualities.P240.value
            else -> Qualities.Unknown.value
        }
    }

    // ─── models ────────────────────────────────────────────────────────────

    data class MovieDetail(
        val id: String,
        val title: String?,
        val posterUrl: String?,
        val synopsis: String?,
        val genres: List<String>,
        val year: Int?,
        val score: Double?,
        val status: String?,
        val type: String?,
        val malId: Int?,
        val anilistId: Int?
    )

    data class StreamSource(
        val name: String?,
        val url: String?,
        val quality: String?,
        val headers: Map<String, String>?
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class ApiResponse<T>(
        @JsonProperty("status") val status: Int? = null,
        @JsonProperty("error") val error: Boolean? = null,
        @JsonProperty("data") val data: T? = null,
        @JsonProperty("message") val message: String? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class SetupData(
        @JsonProperty("domain_api") val domainApi: SetupValue? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class SetupValue(
        @JsonProperty("value") val value: String? = null,
        @JsonProperty("code") val code: String? = null
    )
}
