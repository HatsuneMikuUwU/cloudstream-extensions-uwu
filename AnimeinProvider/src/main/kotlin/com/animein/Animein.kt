package com.animein

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addScore
import com.lagradost.cloudstream3.utils.*
import org.json.JSONArray
import org.json.JSONObject

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
        "3/2/home/popular" to "Popular",
        "3/2/home/random" to "Random",
        "3/2/explore/movie" to "Explore"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val root = api(
            request.data,
            mapOf(
                "page" to page.toString(),
                "limit" to PAGE_SIZE
            )
        )
        val items = parseMovies(root)
        val horizontal = request.data.contains("list_new_episode")
        return newHomePageResponse(
            listOf(HomePageList(request.name, items, isHorizontalImages = horizontal)),
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

        val epRoot = api("3/2/movie/episode/$id")
        val episodes = parseEpisodes(epRoot)

        val status = mapStatus(statusStr)
        val type = mapType(typeStr)

        return if (episodes.isNotEmpty()) {
            newAnimeLoadResponse(title, url, type) {
                this.posterUrl = poster
                this.posterHeaders = imageHeaders
                this.year = year
                this.plot = plot
                this.tags = tags
                showStatus = status
                score?.let { addScore(it.toString(), 10) }
                addEpisodes(DubStatus.Subbed, episodes)
            }
        } else {
            newMovieLoadResponse(title, url, type, "animein://episode/$id") {
                this.posterUrl = poster
                this.posterHeaders = imageHeaders
                this.year = year
                this.plot = plot
                this.tags = tags
                score?.let { addScore(it.toString(), 10) }
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

    private fun parseEpisodes(root: JSONObject?): List<Episode> {
        if (root == null) return emptyList()
        val arr = arrayUnder(root, "episode", "episodes", "list")
        return buildList {
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val epId = jStr(obj, "id") ?: continue
                val epNum = jStr(obj, "index", "episode", "number")?.toIntOrNull() ?: (i + 1)
                val title = jStr(obj, "title") ?: "Episode $epNum"
                val thumb = fixImageUrl(jStr(obj, "image_poster") ?: jStr(obj, "image_cover") ?: findAnyImageUrl(obj))
                add(
                    newEpisode("animein://episode/$epId") {
                        this.name = title
                        this.episode = epNum
                        this.posterUrl = thumb
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

    private fun findAnyImageUrl(obj: JSONObject?): String? {
        if (obj == null) return null
        return jStr(
            obj,
            "image_poster", "image_cover", "image", "poster",
            "thumbnail", "url_thumbnail", "episode_poster",
            "episode_cover_new", "episode_cover_old", "image_url"
        )
    }
}
