package com.animein

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addAniListId
import com.lagradost.cloudstream3.LoadResponse.Companion.addKitsuId
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId
import com.lagradost.cloudstream3.LoadResponse.Companion.addScore
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import java.util.TimeZone

class Animein : MainAPI() {
    override var mainUrl = API_BASE
    override var name = "Animein"
    override val hasMainPage = true
    override var lang = "id"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    companion object {
        private const val GATE_URL = "https://gate.nextanimelist.com"
        private const val API_BASE = "https://xyz-api.animein.net"
        private const val ALT_API_BASE = "https://api.animein.net"
        private const val APK_VER = "5.2.2"
        private const val PAGE_SIZE = 100
        private const val MAX_SEARCH_PAGES = 10

        private val pagedPaths = setOf(
            "data/home/list_new_episode",
            "3/2/home/hot",
            "3/2/home/new",
            "3/2/home/popular"
        )

        private val apiHeaders = mapOf(
            "User-Agent" to "okhttp/4.12.0",
            "apk_ver" to APK_VER
        )

        private val imageHeaders = mapOf(
            "User-Agent" to "okhttp/4.12.0"
        )

        fun fixImageUrl(u: String?): String? {
            if (u.isNullOrBlank()) return null
            val idx = u.indexOf("://")
            if (idx < 0) return u
            val scheme = u.substring(0, idx + 3)
            val rest = u.substring(idx + 3).replace(Regex("/+"), "/")
            return scheme + rest
        }

        private fun authParams(): String =
            "id_user=0&key_client=guest&apk_ver=$APK_VER"

        fun mapStatus(s: String?): ShowStatus =
            when (s?.uppercase()) {
                "ONGOING" -> ShowStatus.Ongoing
                "FINISHED", "COMPLETED" -> ShowStatus.Completed
                else -> ShowStatus.Completed
            }

        fun mapType(t: String?): TvType =
            when (t?.uppercase()) {
                "MOVIE" -> TvType.AnimeMovie
                "OVA", "SPECIAL", "ONA" -> TvType.OVA
                else -> TvType.Anime
            }

        fun parseQuality(q: String?): Int {
            if (q.isNullOrBlank()) return Qualities.Unknown.value
            val m = Regex("(\\d{3,4})").find(q)
            return m?.groupValues?.getOrNull(1)?.toIntOrNull() ?: Qualities.Unknown.value
        }
    }

    @Volatile
    private var apiBase: String = API_BASE

    @Volatile
    private var baseResolved = false

    private fun normalizeBase(raw: String?): String? {
        val v = raw?.trim()?.trimEnd('/') ?: return null
        if (v.isBlank() || v.equals("null", true)) return null
        return if (v.startsWith("http", true)) v else "https://$v"
    }

    private suspend fun fetchJson(url: String): JSONObject? {
        val text = try {
            app.get(url, headers = apiHeaders).text
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            return null
        }
        if (!text.trimStart().startsWith("{")) return null
        return try {
            JSONObject(text)
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun resolveBase(force: Boolean = false) {
        if (baseResolved && !force) return
        val hosts = listOf(apiBase, API_BASE, ALT_API_BASE, GATE_URL).distinct()
        for (host in hosts) {
            val json = fetchJson("$host/data/setup/data?${authParams()}") ?: continue
            val resolved = normalizeBase(
                json.optJSONObject("data")?.optJSONObject("domain_api")?.opt("value")?.toString()
            )
            if (resolved != null) {
                apiBase = resolved
                break
            }
        }
        baseResolved = true
    }

    private fun buildUrl(base: String, path: String, params: Map<String, String>): String {
        val extra = if (params.isEmpty()) "" else "&" + params.entries.joinToString("&") {
            "${it.key}=${java.net.URLEncoder.encode(it.value, "UTF-8")}"
        }
        return "$base/${path.trimStart('/')}?${authParams()}$extra"
    }

    private suspend fun api(path: String, params: Map<String, String> = emptyMap()): JSONObject? {
        resolveBase()

        val tried = (listOf(apiBase, API_BASE, ALT_API_BASE)).distinct()
        for (base in tried) {
            val json = fetchJson(buildUrl(base, path, params)) ?: continue
            if (json.optBoolean("error", false)) continue
            apiBase = base
            return json
        }

        resolveBase(force = true)
        if (apiBase !in tried) {
            val json = fetchJson(buildUrl(apiBase, path, params))
            if (json != null && !json.optBoolean("error", false)) return json
        }
        return null
    }

    private fun fullUrl(u: String?): String? {
        val s = u?.trim()
        if (s.isNullOrBlank() || s.equals("null", true)) return null
        return when {
            s.startsWith("//") -> "https:$s"
            s.contains("://") -> fixImageUrl(s)
            else -> fixImageUrl("$apiBase/${s.trimStart('/')}")
        }
    }

    override val mainPage = mainPageOf(
        "data/home/list_new_episode" to "New Episodes",
        "3/2/home/hot" to "Hot",
        "3/2/home/new" to "New Title",
        "schedule/today" to "Today's Schedule",
        "data/home/fyp" to "Just For You",
        "3/2/home/popular" to "Popular",
        "data/home/list" to "Upcoming"
    )

    private fun todayDayName(): String {
        val days = arrayOf("MINGGU", "SENIN", "SELASA", "RABU", "KAMIS", "JUMAT", "SABTU")
        val cal = Calendar.getInstance(TimeZone.getTimeZone("Asia/Jakarta"))
        return days[cal.get(Calendar.DAY_OF_WEEK) - 1]
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val params = mutableMapOf(
            "page" to page.toString(),
            "limit" to PAGE_SIZE.toString()
        )
        val path = when (request.data) {
            "schedule/today" -> {
                params["day"] = todayDayName()
                "3/2/schedule/data"
            }
            else -> request.data
        }
        val root = api(path, params)
        val items = when (request.data) {
            "data/home/fyp" -> parseFyp(root)
            "data/home/list" -> parseMovieArray(
                root?.optJSONObject("data")?.optJSONArray("waiting") ?: JSONArray()
            )
            else -> parseMovies(root)
        }
        return newHomePageResponse(
            listOf(
                HomePageList(
                    request.name,
                    items,
                    isHorizontalImages = request.data == "data/home/fyp"
                )
            ),
            hasNext = request.data in pagedPaths && items.size >= PAGE_SIZE
        )
    }

    private val searchPaths = listOf("data/movie/find", "3/2/explore/movie")
    private val searchKeys = listOf("query", "q", "search", "keyword", "title", "name")

    @Volatile
    private var searchHit: Pair<String, String>? = null

    private fun listArray(root: JSONObject?): JSONArray =
        if (root == null) JSONArray()
        else arrayUnder(root, "movie", "movies", "list", "items", "results", anyArray = true)

    private fun idsOf(arr: JSONArray): List<String> =
        (0 until minOf(arr.length(), 10)).mapNotNull { jStr(arr.optJSONObject(it), "id") }

    private suspend fun searchWith(path: String, key: String, query: String, page: Int = 1): JSONArray =
        listArray(api(path, mapOf("page" to page.toString(), key to query)))

    private suspend fun searchAllPages(
        path: String,
        key: String,
        query: String,
        first: JSONArray? = null
    ): List<SearchResponse> {
        val seen = HashSet<String>()
        val all = JSONArray()
        var page = 1
        var arr = first ?: searchWith(path, key, query, page)
        while (true) {
            var added = 0
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val id = jStr(obj, "id") ?: continue
                if (seen.add(id)) {
                    all.put(obj)
                    added++
                }
            }
            if (added == 0 || page >= MAX_SEARCH_PAGES) break
            page++
            arr = searchWith(path, key, query, page)
        }
        return parseMovieArray(all)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.trim()
        if (q.isBlank()) return emptyList()

        searchHit?.let { (path, key) ->
            return searchAllPages(path, key, q)
        }

        for (path in searchPaths) {
            val baseline = idsOf(listArray(api(path, mapOf("page" to "1"))))
            for (key in searchKeys) {
                val arr = searchWith(path, key, q)
                if (arr.length() == 0) continue
                if (baseline.isNotEmpty() && idsOf(arr) == baseline) continue
                searchHit = path to key
                return searchAllPages(path, key, q, first = arr)
            }
        }
        return emptyList()
    }

    override suspend fun load(url: String): LoadResponse? {
        val id = url.substringAfterLast("/").substringBefore("?").trim()
        if (id.isBlank()) return null

        val detailRoot = api("3/2/movie/detail/$id")
        val dataObj = detailRoot?.optJSONObject("data")
        val movieObj = dataObj?.optJSONObject("movie") ?: dataObj
        val title = jStr(movieObj, "title") ?: return null

        val poster = fullUrl(jStr(movieObj, "image_poster") ?: jStr(movieObj, "image_cover"))
        val coverUrl = fullUrl(jStr(movieObj, "image_cover"))
        val plot = cleanText(jStr(movieObj, "synopsis", "description"))
        val year = yearOf(movieObj)
        val statusStr = jStr(movieObj, "status")
        val typeStr = jStr(movieObj, "type")
        val score = jStr(movieObj, "score", "rating")?.toDoubleOrNull()
        val tags = jStr(movieObj, "genre")
            ?.split(",", "/")
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() }
            .orEmpty()

        val status = mapStatus(statusStr)
        val type = mapType(typeStr)

        val seasons = dataObj?.optJSONArray("season")
            ?.let { parseMovieArray(it) }
            ?.filterNot { it.url.substringAfterLast("/") == id }
            .orEmpty()

        val tracker = APIHolder.getTracker(listOf(title), TrackerType.getTypes(type), year, true)
        val ids = resolveAnimeIds(listOf(title), type, year, tracker?.malId, tracker?.aniId?.toIntOrNull())
        val malId = ids.malId
        val aniId = ids.aniId

        var animeMetaData: MetaAnimeData? = null
        var tmdbid: Int? = null
        var kitsuid: String? = null

        if (malId != null || aniId != null) {
            try {
                animeMetaData = fetchAniZipMeta(malId, aniId)
                tmdbid = animeMetaData?.mappings?.themoviedbId
                kitsuid = animeMetaData?.mappings?.kitsuId
            } catch (e: Exception) {
                if (e is CancellationException) throw e
            }
        }

        val logoUrl = fetchTmdbLogoUrl(
            tmdbAPI = "https://api.themoviedb.org/3",
            apiKey = "98ae14df2b8d8f8f8136499daf79f0e0",
            type = type,
            tmdbId = tmdbid,
            appLangCode = "en"
        )

        val backgroundposter = animeMetaData?.images?.find { it.coverType == "Fanart" }?.url ?: tracker?.cover

        val apiDescription = animeMetaData?.description?.replace(Regex("<.*?>"), "")
        val rawPlot = apiDescription?.takeIf { it.isNotBlank() }
            ?: animeMetaData?.episodes?.get("1")?.overview?.takeIf { it.isNotBlank() }
            ?: fetchAniListPlot(malId, aniId)
        val finalPlot = rawPlot?.takeIf { it.isNotBlank() } ?: plot

        val epRoot = api("3/2/movie/episode/$id")
        var epArray = epRoot?.let { arrayUnder(it, "episode", "episodes", "list") } ?: JSONArray()
        if (epArray.length() == 0 && detailRoot != null) {
            epArray = arrayUnder(detailRoot, "episode", "episodes")
        }
        val episodes = parseEpisodes(epArray, type, title, animeMetaData, coverUrl)

        return newAnimeLoadResponse(title, url, type) {
            this.engName = animeMetaData?.titles?.get("en") ?: title
            this.japName = animeMetaData?.titles?.get("ja") ?: animeMetaData?.titles?.get("x-jat")
            this.posterUrl = poster ?: tracker?.image
            this.posterHeaders = imageHeaders
            this.backgroundPosterUrl = backgroundposter ?: coverUrl
            try { this.logoUrl = logoUrl } catch (_: Throwable) {}
            this.year = year
            this.plot = finalPlot
            this.tags = tags
            this.recommendations = seasons
            showStatus = status
            score?.let { addScore(it.toString(), 10) }
            addEpisodes(DubStatus.Subbed, episodes)
            addMalId(malId)
            addAniListId(aniId)
            try { addKitsuId(kitsuid) } catch (_: Throwable) {}
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val episodeId = data
            .removePrefix("animein://episode/")
            .substringAfterLast("/")
            .substringBefore("?")
            .trim()
        if (episodeId.isBlank()) return false

        val root = api("3/2/episode/streamnew/$episodeId") ?: return false
        val dataObj = root.optJSONObject("data") ?: return false
        val servers = dataObj.optJSONArray("server") ?: return false

        var found = false
        val seen = mutableSetOf<String>()
        for (i in 0 until servers.length()) {
            val s = servers.optJSONObject(i) ?: continue
            val link = fullUrl(jStr(s, "link", "url")) ?: continue
            if (!seen.add(link)) continue

            val serverName = jStr(s, "name") ?: "Animein"
            val qualityLabel = jStr(s, "quality")
            val serverType = jStr(s, "type")

            val isDirect = serverType.equals("direct", true) ||
                link.contains(".mp4", true) || link.contains(".m3u8", true) ||
                link.contains("googlevideo", true) || link.contains("storages.animein", true) ||
                link.contains("assets_xyz", true)

            if (isDirect) {
                found = true
                callback(
                    newExtractorLink(serverName, serverName, link, INFER_TYPE) {
                        this.referer = apiBase
                        this.quality = parseQuality(qualityLabel)
                    }
                )
            } else {
                if (loadExtractor(link, apiBase, subtitleCallback, callback)) found = true
            }
        }
        return found
    }

    private fun parseMovies(root: JSONObject?): List<SearchResponse> {
        if (root == null) return emptyList()
        return parseMovieArray(arrayUnder(root, "movie", "movies", "list", "items", "results", anyArray = true))
    }

    private fun parseMovieArray(arr: JSONArray): List<SearchResponse> = buildList {
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val id = jStr(obj, "id", "id_movie") ?: continue
            val title = jStr(obj, "title", "movie_title") ?: continue
            val poster = fullUrl(jStr(obj, "image_poster", "image_cover", "poster", "image"))
            val typeStr = jStr(obj, "type")
            val year = yearOf(obj)
            add(
                newAnimeSearchResponse(title, "$API_BASE/3/2/movie/detail/$id", mapType(typeStr)) {
                    this.posterUrl = poster
                    this.posterHeaders = imageHeaders
                    this.year = year
                }
            )
        }
    }

    private fun parseFyp(root: JSONObject?): List<SearchResponse> {
        if (root == null) return emptyList()
        val arr = arrayUnder(root, "fyp", "list", "items", anyArray = true)
        return buildList {
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val movieId = jStr(obj, "id_movie", "id") ?: continue
                val anime = jStr(obj, "movie_title", "anime", "title") ?: continue
                val epLabel = jStr(obj, "episode_title", "title")
                val title = if (!epLabel.isNullOrBlank() && epLabel != anime) "$anime — $epLabel" else anime
                val poster = fullUrl(
                    jStr(obj, "url_thumbnail", "episode_poster", "image_cover", "poster", "image", "image_poster")
                )
                add(
                    newAnimeSearchResponse(title, "$API_BASE/3/2/movie/detail/$movieId", TvType.Anime) {
                        this.posterUrl = poster
                        this.posterHeaders = imageHeaders
                    }
                )
            }
        }
    }

    private fun parseEpisodes(
        arr: JSONArray,
        type: TvType,
        animeTitle: String,
        meta: MetaAnimeData?,
        coverUrl: String?
    ): List<Episode> {
        return buildList {
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val epId = jStr(obj, "id") ?: continue
                val epNum = jStr(obj, "index", "episode", "number")?.toDoubleOrNull()?.toInt() ?: (i + 1)
                val epTitle = jStr(obj, "title") ?: "Episode $epNum"
                val epImage = fullUrl(jStr(obj, "image"))

                val metaEp = meta?.episodes?.get(epNum.toString())
                add(
                    newEpisode("animein://episode/$epId") {
                        this.name = if (type == TvType.AnimeMovie) {
                            meta?.titles?.get("en") ?: meta?.titles?.get("ja") ?: animeTitle
                        } else {
                            metaEp?.title?.get("en") ?: metaEp?.title?.get("ja") ?: epTitle
                        }
                        this.episode = epNum
                        this.score = Score.from10(metaEp?.rating)
                        this.posterUrl = metaEp?.image?.takeIf { it.isNotBlank() }
                            ?: epImage
                            ?: coverUrl
                        this.description = metaEp?.overview?.takeIf { it.isNotBlank() }
                        this.addDate(metaEp?.airDateUtc)
                        this.runTime = metaEp?.runtime
                    }
                )
            }
        }.sortedBy { it.episode }
    }

    private fun arrayUnder(root: JSONObject, vararg keys: String, anyArray: Boolean = false): JSONArray {
        val data = root.opt("data")
        if (data is JSONArray) return data
        if (data is JSONObject) {
            for (key in keys) {
                val v = data.optJSONArray(key)
                if (v != null) return v
            }
        }
        for (key in keys) {
            val v = root.optJSONArray(key)
            if (v != null) return v
        }
        if (anyArray && data is JSONObject) {
            val names = data.keys()
            while (names.hasNext()) {
                val arr = data.optJSONArray(names.next()) ?: continue
                if (arr.length() > 0 && arr.opt(0) is JSONObject) return arr
            }
        }
        return JSONArray()
    }

    private fun yearOf(obj: JSONObject?): Int? =
        jStr(obj, "aired_start")?.take(4)?.toIntOrNull()?.takeIf { it > 1900 }
            ?: jStr(obj, "year")?.toIntOrNull()
            
    private fun cleanText(s: String?): String? = s
        ?.replace("\u00e2\u20ac\u0153", "\u201c")
        ?.replace("\u00e2\u20ac\u009d", "\u201d")
        ?.replace("\u00e2\u20ac\u2122", "\u2019")
        ?.replace("\u00e2\u20ac\u201d", "\u2014")
        ?.replace("\u00e2\u20ac\u00a6", "\u2026")
        ?.trim()

    private fun jStr(obj: JSONObject?, vararg keys: String): String? {
        if (obj == null) return null
        for (key in keys) {
            val v = obj.opt(key)
            if (v == null || v == JSONObject.NULL || v is Boolean || v is JSONObject || v is JSONArray) continue
            val s = v.toString().trim()
            if (s.isNotBlank() && !s.equals("null", true)) return s
        }
        return null
    }
}
