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
        private const val APP_UA = "okhttp/4.12.0"
        private const val PAGE_SIZE = "100"
    }

    private val apiHeaders = mapOf(
        "User-Agent" to APP_UA,
        "Accept" to "application/json",
        "Accept-Language" to "id-ID,id;q=0.9"
    )

    private val posterHeaders = mapOf(
        "Referer" to "$API_BASE/",
        "User-Agent" to APP_UA,
        "Accept" to "image/avif,image/webp,image/apng,image/*,*/*;q=0.8"
    )

    private suspend fun api(path: String, params: Map<String, String> = emptyMap()): JSONObject? {
        val qs = if (params.isEmpty()) "" else "?" + params.entries.joinToString("&") {
            "${it.key}=${java.net.URLEncoder.encode(it.value, "UTF-8")}"
        }
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
        return newHomePageResponse(
            listOf(HomePageList(request.name, items, isHorizontalImages = true)),
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
        val poster = resolveImage(findAnyImageUrl(movieObj))
        val plot = jStr(movieObj, "synopsis", "description")
        val year = jStr(movieObj, "year")?.toIntOrNull()
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

        val status = when {
            statusStr?.contains("ONGOING", true) == true -> ShowStatus.Ongoing
            statusStr?.contains("COMPLETED", true) == true -> ShowStatus.Completed
            else -> null
        }
        val type = when {
            typeStr?.contains("movie", true) == true -> TvType.AnimeMovie
            typeStr?.contains("ova", true) == true -> TvType.OVA
            typeStr?.contains("ona", true) == true -> TvType.OVA
            else -> TvType.Anime
        }

        return if (episodes.isNotEmpty()) {
            newAnimeLoadResponse(title, url, type) {
                this.posterUrl = poster
                this.posterHeaders = posterHeaders
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
                this.posterHeaders = posterHeaders
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
            val fixed = normalizeUrl(link)

            if (fixed.contains(".mp4", true) || fixed.contains(".m3u8", true) ||
                fixed.contains("googlevideo", true) || fixed.contains("storages.animein", true)
            ) {
                callback(
                    newExtractorLink(serverName, serverName, fixed, INFER_TYPE) {
                        this.referer = API_BASE
                        this.quality = qualityFromLabel(qualityLabel)
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
                val poster = resolveImage(findAnyImageUrl(obj))
                val typeStr = jStr(obj, "type")
                val tvType = when {
                    typeStr?.contains("movie", true) == true -> TvType.AnimeMovie
                    typeStr?.contains("ova", true) == true -> TvType.OVA
                    else -> TvType.Anime
                }
                add(
                    newAnimeSearchResponse(title, "$API_BASE/movie/$id", tvType) {
                        this.posterUrl = poster
                        this.posterHeaders = posterHeaders
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
                val thumb = resolveImage(findAnyImageUrl(obj))
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
            if (!obj.has(key) || obj.isNull(key)) continue
            val s = obj.optString(key, "").trim()
            if (s.isNotBlank() && s != "null") return s
        }
        return null
    }

    private fun normalizeUrl(raw: String): String {
        var url = raw.trim()
        if (url.startsWith("//")) url = "https:$url"
        url = url.replace(Regex("(https?://[^/]+)//+"), "$1/")
        return url
    }

    private fun findAnyImageUrl(obj: JSONObject?): String? {
        if (obj == null) return null

        jStr(
            obj,
            "image_poster", "image_cover", "image", "poster",
            "thumbnail", "url_thumbnail", "episode_poster",
            "episode_cover_new", "episode_cover_old", "image_url"
        )?.let { return it }

        val priority = listOf("poster", "cover", "image", "thumb", "banner", "img")
        val candidates = mutableListOf<Pair<String, String>>()
        val keys = obj.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val value = obj.opt(key)
            if (value is String) {
                val v = value.trim()
                if (v.isBlank() || v == "null") continue
                val lower = key.lowercase()
                if (priority.any { lower.contains(it) }) {
                    candidates.add(key to v)
                }
            }
        }
        if (candidates.isEmpty()) return null
        for (p in priority) {
            candidates.firstOrNull { it.first.lowercase().contains(p) }?.let { return it.second }
        }
        return candidates.first().second
    }

    private fun resolveImage(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        var url = raw.trim()
        when {
            url.startsWith("http://") || url.startsWith("https://") -> { /* ok */ }
            url.startsWith("//") -> url = "https:$url"
            url.startsWith("/") -> url = API_BASE + url
            else -> url = "$API_BASE/$url"
        }
        return normalizeUrl(url)
    }

    private fun qualityFromLabel(q: String?): Int {
        if (q.isNullOrBlank()) return Qualities.Unknown.value
        val lower = q.lowercase()
        return when {
            "1080" in lower || "fhd" in lower -> Qualities.P1080.value
            "720" in lower || lower == "hd" -> Qualities.P720.value
            "480" in lower -> Qualities.P480.value
            "360" in lower || lower == "sd" -> Qualities.P360.value
            "240" in lower -> Qualities.P240.value
            else -> Qualities.Unknown.value
        }
    }
}
