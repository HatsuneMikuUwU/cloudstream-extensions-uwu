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
        private const val SITE_URL = "https://animein.net"
        private const val APK_VER = "5.1.2"
        private const val PAGE_SIZE = "100"

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

        private val guestId: Int by lazy { (100000..9999999).random() }

        private fun authParams(): String =
            "id_user=$guestId&key_client=guest&apk_ver=$APK_VER"

        fun mapStatus(s: String?): ShowStatus =
            when (s?.uppercase()) {
                "ONGOING" -> ShowStatus.Ongoing
                "FINISHED", "COMPLETED" -> ShowStatus.Completed
                else -> ShowStatus.Completed
            }

        fun mapType(t: String?): TvType =
            when (t?.uppercase()) {
                "MOVIE" -> TvType.AnimeMovie
                "OVA" -> TvType.OVA
                else -> TvType.Anime
            }

        fun parseQuality(q: String?): Int {
            if (q.isNullOrBlank()) return Qualities.Unknown.value
            val m = Regex("(\\d{3,4})").find(q)
            return m?.groupValues?.getOrNull(1)?.toIntOrNull() ?: Qualities.Unknown.value
        }
    }

    private suspend fun api(path: String, params: Map<String, String> = emptyMap()): JSONObject? {
        val baseQs = authParams()
        val extra = if (params.isEmpty()) "" else "&" + params.entries.joinToString("&") {
            "${it.key}=${java.net.URLEncoder.encode(it.value, "UTF-8")}"
        }
        val qs = "?$baseQs$extra"
        val primary = "$API_BASE/${path.trimStart('/')}$qs"
        val text = try {
            app.get(primary, headers = apiHeaders).text
        } catch (_: Exception) {
            try {
                app.get("$GATE_URL/${path.trimStart('/')}$qs", headers = apiHeaders).text
            } catch (_: Exception) {
                return null
            }
        }
        if (text.isBlank() || text.startsWith("<!DOCTYPE", true) || text.startsWith("403") || text.startsWith("Just a moment")) {
            return null
        }
        return try {
            JSONObject(text)
        } catch (_: Exception) {
            null
        }
    }

    override val mainPage = mainPageOf(
        "data/home/list_new_episode" to "New Episodes",
        "3/2/home/hot" to "Hot",
        "3/2/home/new" to "New Title",
        "schedule/today" to "Today's Schedule",
        "data/home/fyp" to "Just For You",
        "3/2/home/popular" to "Popular",
        "3/2/home/random" to "Random",
        "3/2/explore/movie" to "Explore"
    )

    private fun todayDayName(): String {
        val days = arrayOf("MINGGU", "SENIN", "SELASA", "RABU", "KAMIS", "JUMAT", "SABTU")
        val cal = Calendar.getInstance(TimeZone.getTimeZone("Asia/Jakarta"))
        return days[cal.get(Calendar.DAY_OF_WEEK) - 1]
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val params = mutableMapOf(
            "page" to page.toString(),
            "limit" to PAGE_SIZE
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
            else -> parseMovies(root)
        }
        return newHomePageResponse(
            listOf(HomePageList(request.name, items)),
            hasNext = items.size >= PAGE_SIZE.toInt()
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val params = mapOf(
            "page" to "1",
            "limit" to "30",
            "query" to query,
            "search" to query,
            "keyword" to query
        )
        var items = parseMovies(api("3/2/explore/movie", params))
        if (items.isEmpty()) {
            items = parseMovies(api("data/movie/find", params))
        }
        return items
    }

    override suspend fun load(url: String): LoadResponse? {
        val id = url.substringAfterLast("/").substringBefore("?").trim()
        if (id.isBlank()) return null

        val detailRoot = api("3/2/movie/detail/$id")
        val dataObj = detailRoot?.optJSONObject("data")
        val movieObj = dataObj?.optJSONObject("movie") ?: dataObj

        val title = jStr(movieObj, "title") ?: "Anime $id"
        val poster = fixImageUrl(jStr(movieObj, "image_poster") ?: jStr(movieObj, "image_cover"))
        val coverUrl = fixImageUrl(jStr(movieObj, "image_cover"))
        val plot = jStr(movieObj, "synopsis", "description")
        val year = jStr(movieObj, "year")?.toIntOrNull()
            ?: jStr(movieObj, "aired_start")?.take(4)?.toIntOrNull()
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
        val episodes = parseEpisodes(epRoot, type, title, animeMetaData, backgroundposter, coverUrl, tracker?.image, poster)

        return if (episodes.isNotEmpty()) {
            newAnimeLoadResponse(title, url, type) {
                this.engName = animeMetaData?.titles?.get("en") ?: title
                this.japName = animeMetaData?.titles?.get("ja") ?: animeMetaData?.titles?.get("x-jat")
                this.posterUrl = poster ?: tracker?.image
                this.posterHeaders = imageHeaders
                this.backgroundPosterUrl = backgroundposter
                try { this.logoUrl = logoUrl } catch (_: Throwable) {}
                this.year = year
                this.plot = finalPlot
                this.tags = tags
                showStatus = status
                score?.let { addScore(it.toString(), 10) }
                addEpisodes(DubStatus.Subbed, episodes)
                addMalId(malId)
                addAniListId(aniId)
                try { addKitsuId(kitsuid) } catch (_: Throwable) {}
            }
        } else {
            newMovieLoadResponse(title, url, type, "animein://episode/$id") {
                this.posterUrl = poster ?: tracker?.image
                this.posterHeaders = imageHeaders
                this.backgroundPosterUrl = backgroundposter
                try { this.logoUrl = logoUrl } catch (_: Throwable) {}
                this.year = year
                this.plot = finalPlot
                this.tags = tags
                score?.let { addScore(it.toString(), 10) }
                addMalId(malId)
                addAniListId(aniId)
                try { addKitsuId(kitsuid) } catch (_: Throwable) {}
            }
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
        for (i in 0 until servers.length()) {
            val s = servers.optJSONObject(i) ?: continue
            val link = jStr(s, "link", "url") ?: continue
            if (link.isBlank()) continue
            found = true
            val serverName = jStr(s, "name") ?: "Animein"
            val qualityLabel = jStr(s, "quality")
            val fixed = fixImageUrl(link) ?: link

            if (fixed.contains(".mp4", true) || fixed.contains(".m3u8", true) ||
                fixed.contains("googlevideo", true) || fixed.contains("storages.animein", true) ||
                fixed.contains("assets_xyz", true)
            ) {
                callback(
                    newExtractorLink(serverName, serverName, fixed, INFER_TYPE) {
                        this.referer = API_BASE
                        this.quality = parseQuality(qualityLabel)
                    }
                )
            } else {
                loadExtractor(fixed, API_BASE, subtitleCallback, callback)
            }
        }
        return found
    }

    private fun parseMovies(root: JSONObject?): List<SearchResponse> {
        if (root == null) return emptyList()
        val arr = arrayUnder(root, "movie", "movies", "list", "items", "results")
        return buildList {
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val id = jStr(obj, "id") ?: continue
                val title = jStr(obj, "title") ?: continue
                val poster = fixImageUrl(jStr(obj, "image_poster") ?: jStr(obj, "image_cover"))
                val typeStr = jStr(obj, "type")
                val year = jStr(obj, "year")?.toIntOrNull()
                    ?: jStr(obj, "aired_start")?.take(4)?.toIntOrNull()
                add(
                    newAnimeSearchResponse(title, "$API_BASE/3/2/movie/detail/$id", mapType(typeStr)) {
                        this.posterUrl = poster
                        this.posterHeaders = imageHeaders
                        this.year = year
                    }
                )
            }
        }
    }

    private fun parseFyp(root: JSONObject?): List<SearchResponse> {
        if (root == null) return emptyList()
        val arr = arrayUnder(root, "fyp", "list", "items")
        return buildList {
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val movieId = jStr(obj, "id_movie", "id") ?: continue
                val anime = jStr(obj, "anime", "movie_title", "title") ?: continue
                val epLabel = jStr(obj, "episode", "title")
                val title = if (!epLabel.isNullOrBlank() && epLabel != anime) "$anime — $epLabel" else anime
                val poster = fixImageUrl(
                    jStr(obj, "poster", "url_thumbnail", "episode_poster", "image", "image_poster")
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
        root: JSONObject?,
        type: TvType,
        animeTitle: String,
        meta: MetaAnimeData?,
        backgroundPoster: String?,
        coverUrl: String?,
        trackerImage: String?,
        poster: String?
    ): List<Episode> {
        if (root == null) return emptyList()
        val arr = arrayUnder(root, "episode", "episodes", "list")
        return buildList {
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val epId = jStr(obj, "id") ?: continue
                val epNum = jStr(obj, "index", "episode", "number")?.toIntOrNull() ?: (i + 1)
                val epTitle = jStr(obj, "title") ?: "Episode $epNum"

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
                            ?: meta?.images?.firstOrNull()?.url
                            ?: backgroundPoster
                            ?: coverUrl
                            ?: trackerImage
                            ?: poster
                        this.description = metaEp?.overview?.takeIf { it.isNotBlank() }
                        this.addDate(metaEp?.airDateUtc)
                        this.runTime = metaEp?.runtime
                    }
                )
            }
        }.sortedBy { it.episode }
    }

    private fun arrayUnder(root: JSONObject, vararg keys: String): JSONArray {
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
        return JSONArray()
    }

    private fun jStr(obj: JSONObject?, vararg keys: String): String? {
        if (obj == null) return null
        for (key in keys) {
            val s = obj.optString(key, "").trim()
            if (s.isNotBlank() && s != "null") return s
        }
        return null
    }
}
