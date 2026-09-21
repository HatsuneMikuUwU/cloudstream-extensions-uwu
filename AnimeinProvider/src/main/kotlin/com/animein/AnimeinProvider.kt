package com.animein

import com.fasterxml.jackson.databind.JsonNode
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.nicehttp.NiceResponse

class AnimeinProvider : MainAPI() {
    override var mainUrl = "https://xyz-api.animein.net"
    override var name = "Animein"
    override val hasMainPage = true
    override var lang = "id"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.OVA
    )

    private val bootUrl = "https://gate.nextanimelist.com"
    private val naniplayBase = "https://www.naniplay.com"
    private val apkVer = "5.2.2"

    // Optional: can be filled after device auth / login
    private var keyClient: String = ""
    private var idUser: String = ""

    companion object {
        private const val MAX_SAFE_TEXT_BYTES = 4_500_000L

        fun getType(t: String?): TvType {
            return when {
                t.isNullOrBlank() -> TvType.Anime
                t.contains("OVA", true) || t.contains("Special", true) -> TvType.OVA
                t.contains("Movie", true) || t.contains("Film", true) -> TvType.AnimeMovie
                else -> TvType.Anime
            }
        }

        fun getStatus(t: String?): ShowStatus {
            return when (t?.lowercase()?.trim()) {
                "ongoing", "on going", "on-going" -> ShowStatus.Ongoing
                "completed", "complete", "finished" -> ShowStatus.Completed
                else -> ShowStatus.Completed
            }
        }
    }

    private fun commonParams(extra: Map<String, String> = emptyMap()): Map<String, String> {
        val base = mutableMapOf(
            "apk_ver" to apkVer
        )
        if (keyClient.isNotBlank()) base["key_client"] = keyClient
        if (idUser.isNotBlank()) base["id_user"] = idUser
        base.putAll(extra)
        return base
    }

    private fun headers(): Map<String, String> = mapOf(
        "Accept" to "application/json, text/plain, */*",
        "User-Agent" to "okhttp/4.12.0",
        "Accept-Language" to "id-ID,id;q=0.9,en;q=0.8",
        "X-Requested-With" to "com.okeko.animein",
        "Origin" to "https://animein.net",
        "Referer" to "https://animein.net/"
    )

    private fun NiceResponse.safeText(): String {
        val contentLength = headers["Content-Length"]?.toLongOrNull()
        val contentType = headers["Content-Type"].orEmpty().lowercase()

        if (contentLength != null && contentLength > MAX_SAFE_TEXT_BYTES) {
            throw Exception("Response too large (${contentLength} bytes)")
        }
        if (contentType.startsWith("video/") ||
            contentType.startsWith("audio/") ||
            contentType.contains("octet-stream")
        ) {
            throw Exception("Non-text content-type: $contentType")
        }

        val body = try {
            textLarge
        } catch (_: Throwable) {
            try {
                text
            } catch (e: Throwable) {
                throw Exception("Failed to read response body: ${e.message}")
            }
        }

        if (body.contains("Just a moment", ignoreCase = true) ||
            body.contains("cf-browser-verification", ignoreCase = true) ||
            body.contains("cf-challenge", ignoreCase = true) ||
            (body.contains("<html", ignoreCase = true) && !body.trimStart().startsWith("{"))
        ) {
            throw Exception("Cloudflare challenge or HTML response received")
        }

        return body
    }

    private suspend fun apiGet(path: String, params: Map<String, String> = emptyMap()): String {
        val base = if (path.startsWith("http")) path else {
            val clean = path.trimStart('/')
            "$mainUrl/$clean"
        }
        val allParams = commonParams(params)
        val query = allParams.entries.joinToString("&") {
            "${it.key}=${java.net.URLEncoder.encode(it.value, "UTF-8")}"
        }
        val fullUrl = if (query.isNotEmpty()) "$base?$query" else base
        val response = app.get(fullUrl, headers = headers())
        return response.safeText()
    }

    private suspend fun ensureDomain() {
        try {
            val response = app.get(
                "$bootUrl/data/setup/data",
                headers = headers(),
                params = mapOf("apk_ver" to apkVer)
            )
            val resp = response.safeText()
            val node = parseJson<JsonNode>(resp)
            val domain = node.path("data").path("domain_api").path("value").asText(null)
            if (!domain.isNullOrBlank()) {
                mainUrl = domain.trimEnd('/')
            }
        } catch (_: Exception) {
        }
    }

    override val mainPage = mainPageOf(
        "data/home/list" to "Home",
        "data/home/list_new_episode" to "New Episodes",
        "3/2/home/hot" to "Hot",
        "3/2/home/new" to "Latest",
        "3/2/home/popular" to "Popular",
        "3/2/home/random" to "Random",
        "data/trailer/list" to "Trailer"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        ensureDomain()
        val raw = apiGet(request.data, mapOf("page" to page.toString()))
        val items = parseMovieList(raw)
        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        ensureDomain()
        val raw = try {
            apiGet("data/movie/find", mapOf(
                "q" to query,
                "query" to query,
                "search" to query,
                "keyword" to query,
                "page" to "1"
            ))
        } catch (_: Exception) {
            ""
        }
        val list = parseMovieList(raw)
        if (list.isNotEmpty()) return list

        return try {
            val raw2 = apiGet("3/2/explore/movie", mapOf("q" to query, "search" to query))
            parseMovieList(raw2)
        } catch (_: Exception) {
            emptyList()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        ensureDomain()
        val id = url.substringAfterLast("/").substringBefore("?").ifBlank { return null }

        val detailRaw = try {
            apiGet("3/2/movie/detail/$id")
        } catch (_: Exception) {
            return null
        }
        val detailNode = try {
            parseJson<JsonNode>(detailRaw).path("data")
        } catch (_: Exception) {
            return null
        }

        val title = detailNode.path("title").asText(null)
            ?: detailNode.path("name").asText(null)
            ?: "Anime $id"
        val poster = detailNode.path("image_poster").asText(null)
            ?: detailNode.path("image").asText(null)
            ?: detailNode.path("poster").asText(null)
        val cover = detailNode.path("image_cover").asText(null)
        val plot = detailNode.path("synopsis").asText(null)
            ?: detailNode.path("sinopsis").asText(null)
        val year = detailNode.path("year").asText(null)?.toIntOrNull()
        val status = getStatus(detailNode.path("status").asText(null))
        val type = getType(detailNode.path("type").asText(null))
        val tags = mutableListOf<String>()
        val genreNode = detailNode.path("genre")
        if (genreNode.isArray) {
            genreNode.forEach { tags.add(it.asText()) }
        } else if (genreNode.isTextual) {
            tags.addAll(genreNode.asText().split(",", "|").map { it.trim() }.filter { it.isNotBlank() })
        }
        val studio = detailNode.path("studio").asText(null)
        if (!studio.isNullOrBlank()) tags.add(studio)

        val episodes = try {
            val epRaw = apiGet("3/2/movie/episode/$id")
            parseEpisodes(epRaw)
        } catch (_: Exception) {
            emptyList()
        }

        return newAnimeLoadResponse(title, url, type) {
            this.posterUrl = fixUrlNull(poster)
            this.backgroundPosterUrl = fixUrlNull(cover)
            this.year = year
            this.plot = plot
            this.showStatus = status
            this.tags = tags.distinct()
            addEpisodes(DubStatus.Subbed, episodes)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        ensureDomain()
        val epId = data.substringAfterLast("/").substringBefore("?")

        val streamRaw = try {
            apiGet("3/2/episode/streamnew/$epId")
        } catch (_: Exception) {
            try {
                apiGet("data/movie/stream/use_server_user", mapOf(
                    "idEpisode" to epId,
                    "id_episode" to epId,
                    "id" to epId
                ))
            } catch (_: Exception) {
                ""
            }
        }

        var found = false

        if (streamRaw.isNotBlank()) {
            try {
                val root = parseJson<JsonNode>(streamRaw)
                val dataNode = if (root.has("data")) root.path("data") else root

                val servers = mutableListOf<Pair<String, String>>() // name to link

                fun collect(node: JsonNode) {
                    when {
                        node.isArray -> node.forEach { collect(it) }
                        node.isObject -> {
                            val link = node.path("link").asText(null)
                                ?: node.path("url").asText(null)
                                ?: node.path("file").asText(null)
                                ?: node.path("src").asText(null)
                            val name = node.path("name").asText(null)
                                ?: node.path("quality").asText(null)
                                ?: node.path("server").asText(null)
                                ?: node.path("label").asText(null)
                                ?: "Server"
                            if (!link.isNullOrBlank()) {
                                servers.add(name to link)
                            }
                            listOf("list", "servers", "sources", "data", "stream", "videos").forEach { key ->
                                if (node.has(key)) collect(node.path(key))
                            }
                        }
                    }
                }
                collect(dataNode)

                for ((name, link) in servers.distinctBy { it.second }) {
                    when {
                        link.contains("naniplay", true) || link.contains("/api/source/") -> {
                            if (loadNaniplay(link, name, callback)) found = true
                        }
                        link.contains(".m3u8") || link.contains(".mp4") ||
                        (link.startsWith("http") && !link.contains("naniplay")) -> {
                            callback.invoke(
                                newExtractorLink(
                                    source = this.name,
                                    name = name,
                                    url = link,
                                    type = INFER_TYPE
                                ) {
                                    this.referer = mainUrl
                                    this.quality = qualityFromName(name)
                                }
                            )
                            found = true
                        }
                        else -> {
                            loadExtractor(link, mainUrl, subtitleCallback, callback)
                            found = true
                        }
                    }
                }
            } catch (_: Exception) {
            }
        }

        if (!found) {
            try {
                if (loadNaniplay("$naniplayBase/api/source/$epId", "Naniplay", callback)) {
                    found = true
                }
            } catch (_: Exception) {}
        }

        return found
    }

    private fun parseMovieList(raw: String): List<SearchResponse> {
        if (raw.isBlank()) return emptyList()
        return try {
            val root = parseJson<JsonNode>(raw)
            val dataNode = if (root.has("data")) root.path("data") else root

            val listNode = when {
                dataNode.isArray -> dataNode
                dataNode.has("list") -> dataNode.path("list")
                dataNode.has("data") -> dataNode.path("data")
                dataNode.has("movies") -> dataNode.path("movies")
                dataNode.has("items") -> dataNode.path("items")
                else -> dataNode
            }

            if (!listNode.isArray) return emptyList()

            listNode.mapNotNull { item ->
                val id = item.path("id").asText(null)
                    ?: item.path("id_movie").asText(null)
                    ?: return@mapNotNull null
                val title = item.path("title").asText(null)
                    ?: item.path("name").asText(null)
                    ?: return@mapNotNull null
                val poster = item.path("image_poster").asText(null)
                    ?: item.path("image").asText(null)
                    ?: item.path("poster").asText(null)
                    ?: item.path("cover").asText(null)
                val type = getType(item.path("type").asText(null))
                val href = "$mainUrl/movie/$id"

                newAnimeSearchResponse(title, href, type) {
                    this.posterUrl = fixUrlNull(poster)
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun parseEpisodes(raw: String): List<Episode> {
        if (raw.isBlank()) return emptyList()
        return try {
            val root = parseJson<JsonNode>(raw)
            val dataNode = if (root.has("data")) root.path("data") else root

            val listNode = when {
                dataNode.isArray -> dataNode
                dataNode.has("list") -> dataNode.path("list")
                dataNode.has("episodes") -> dataNode.path("episodes")
                dataNode.has("data") -> dataNode.path("data")
                else -> dataNode
            }

            if (!listNode.isArray) return emptyList()

            listNode.mapIndexed { index, item ->
                val epId = item.path("id").asText(null)
                    ?: item.path("id_episode").asText(null)
                    ?: (index + 1).toString()
                val title = item.path("title").asText(null)
                    ?: item.path("name").asText(null)
                val num = item.path("index").asInt(0).takeIf { it > 0 }
                    ?: item.path("number").asInt(0).takeIf { it > 0 }
                    ?: item.path("episode").asInt(0).takeIf { it > 0 }
                    ?: (index + 1)
                val poster = item.path("image").asText(null)

                newEpisode("$mainUrl/episode/$epId") {
                    this.name = title ?: "Episode $num"
                    this.episode = num
                    this.posterUrl = fixUrlNull(poster)
                    this.data = epId
                }
            }.sortedBy { it.episode }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private suspend fun loadNaniplay(
        url: String,
        sourceName: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val response = app.post(
                url,
                headers = mapOf(
                    "User-Agent" to "okhttp/4.12.0",
                    "Referer" to naniplayBase,
                    "Accept" to "application/json",
                    "Content-Type" to "application/x-www-form-urlencoded"
                ),
                data = mapOf(
                    "r" to "",
                    "d" to "www.naniplay.com"
                )
            )
            val resp = response.safeText()

            val root = parseJson<JsonNode>(resp)
            val dataList = root.path("data")
            if (!dataList.isArray) return false

            var ok = false
            dataList.forEach { src ->
                val file = src.path("file").asText(null) ?: return@forEach
                val label = src.path("label").asText(null) ?: src.path("type").asText(null) ?: "Default"
                callback.invoke(
                    newExtractorLink(
                        source = sourceName,
                        name = "$sourceName - $label",
                        url = file,
                        type = INFER_TYPE
                    ) {
                        this.referer = naniplayBase
                        this.quality = qualityFromName(label)
                    }
                )
                ok = true
            }
            ok
        } catch (_: Exception) {
            false
        }
    }

    private fun qualityFromName(name: String?): Int {
        if (name == null) return Qualities.Unknown.value
        return when {
            name.contains("1080") -> Qualities.P1080.value
            name.contains("720") -> Qualities.P720.value
            name.contains("480") -> Qualities.P480.value
            name.contains("360") -> Qualities.P360.value
            name.contains("240") -> Qualities.P240.value
            else -> Qualities.Unknown.value
        }
    }

    private fun fixUrlNull(url: String?): String? {
        if (url.isNullOrBlank()) return null
        return when {
            url.startsWith("//") -> "https:$url"
            url.startsWith("/") -> "$mainUrl$url"
            url.startsWith("http") -> url
            else -> "$mainUrl/$url"
        }
    }
}
