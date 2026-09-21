package com.animein

import com.fasterxml.jackson.databind.JsonNode
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.nicehttp.NiceResponse
import okhttp3.Interceptor

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
    private val assetBase = "https://xyz-api.animein.net/assets/images"

    private var keyClient: String = ""
    private var idUser: String = ""

    private val cloudflareKiller by lazy { CloudflareKiller() }

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
        val base = mutableMapOf("apk_ver" to apkVer)
        if (keyClient.isNotBlank()) base["key_client"] = keyClient
        if (idUser.isNotBlank()) base["id_user"] = idUser
        base.putAll(extra)
        return base
    }

    private fun headers(): Map<String, String> {
        val h = mutableMapOf(
            "Accept" to "application/json, text/plain, */*",
            "User-Agent" to "okhttp/4.12.0",
            "Accept-Language" to "id-ID,id;q=0.9,en;q=0.8",
            "X-Requested-With" to "com.okeko.animein",
            "Origin" to "https://animein.net",
            "Referer" to "https://animein.net/"
        )
        runCatching {
            cloudflareKiller.getCookieHeaders(mainUrl).forEach { (k, v) -> h[k] = v }
        }
        return h
    }

    private fun interceptor(): Interceptor = cloudflareKiller

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
            (body.contains("<html", ignoreCase = true) &&
                !body.trimStart().startsWith("{") &&
                !body.trimStart().startsWith("["))
        ) {
            throw Exception("Cloudflare challenge or HTML response")
        }

        return body
    }

    private suspend fun apiGet(path: String, params: Map<String, String> = emptyMap()): String {
        val base = if (path.startsWith("http")) path else {
            val clean = path.trimStart('/')
            "$mainUrl/$clean"
        }
        val response = app.get(
            base,
            headers = headers(),
            params = commonParams(params),
            interceptor = interceptor()
        )
        return response.safeText()
    }

    private suspend fun ensureDomain() {
        try {
            val response = app.get(
                "$bootUrl/data/setup/data",
                headers = mapOf(
                    "Accept" to "application/json",
                    "User-Agent" to "okhttp/4.12.0"
                ),
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
        return try {
            val raw = apiGet(request.data, mapOf("page" to page.toString()))
            val items = parseMovieList(raw)
            newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
        } catch (_: Exception) {
            newHomePageResponse(request.name, emptyList(), hasNext = false)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        ensureDomain()
        val attempts = listOf(
            "data/movie/find" to mapOf(
                "q" to query, "query" to query, "search" to query,
                "keyword" to query, "page" to "1"
            ),
            "3/2/explore/movie" to mapOf("q" to query, "search" to query, "page" to "1"),
            "data/home/list" to mapOf("q" to query, "search" to query, "page" to "1")
        )
        for ((path, params) in attempts) {
            try {
                val raw = apiGet(path, params)
                val list = parseMovieList(raw)
                if (list.isNotEmpty()) return list
            } catch (_: Exception) {
            }
        }
        return emptyList()
    }

    override suspend fun load(url: String): LoadResponse? {
        ensureDomain()
        val id = url.substringAfterLast("/").substringBefore("?").ifBlank { return null }

        // Try detail endpoints
        val detailNode = try {
            val raw = apiGet("3/2/movie/detail/$id")
            val root = parseJson<JsonNode>(raw)
            when {
                root.has("data") && root.path("data").isObject -> root.path("data")
                root.isObject && (root.has("title") || root.has("id")) -> root
                else -> null
            }
        } catch (_: Exception) {
            null
        }

        val title = detailNode?.let { firstText(it, "title", "name", "judul") }
            ?: "Anime $id"
        val poster = detailNode?.let {
            firstText(it, "image_poster", "movie_image_poster", "image", "poster", "cover", "thumbnail", "poster_url")
        }
        val cover = detailNode?.let {
            firstText(it, "image_cover", "cover", "banner", "image")
        }
        val plot = detailNode?.let {
            firstText(it, "synopsis", "sinopsis", "description", "plot")
        }
        val year = detailNode?.let { firstText(it, "year", "tahun") }?.toIntOrNull()
        val status = getStatus(detailNode?.let { firstText(it, "status", "status_movie") })
        val type = getType(detailNode?.let { firstText(it, "type", "tipe") })
        val tags = mutableListOf<String>()
        detailNode?.path("genre")?.let { genreNode ->
            when {
                genreNode.isArray -> genreNode.forEach { tags.add(it.asText()) }
                genreNode.isTextual -> tags.addAll(
                    genreNode.asText().split(",", "|", "/").map { it.trim() }.filter { it.isNotBlank() }
                )
            }
        }
        detailNode?.let { firstText(it, "studio") }?.let { tags.add(it) }

        val episodes = try {
            parseEpisodes(apiGet("3/2/movie/episode/$id"), movieId = id)
        } catch (_: Exception) {
            emptyList()
        }

        return newAnimeLoadResponse(title, url, type) {
            this.posterUrl = fixImageUrl(poster)
            this.backgroundPosterUrl = fixImageUrl(cover) ?: fixImageUrl(poster)
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

        // data format: "epId" or "movieId|epId"
        val parts = data.split("|")
        val epId = parts.last().ifBlank { data }
        val movieId = parts.getOrNull(0)?.takeIf { parts.size > 1 }

        var found = false
        val servers = mutableListOf<Pair<String, String>>()

        // 1) Primary stream endpoint
        val streamPaths = listOf(
            "3/2/episode/streamnew/$epId",
            "3/2/episode/streamnew/$epId/"
        )
        for (path in streamPaths) {
            try {
                val raw = apiGet(path)
                collectStreamLinks(raw, servers)
                if (servers.isNotEmpty()) break
            } catch (_: Exception) {
            }
        }

        // 2) Fallback endpoints
        if (servers.isEmpty()) {
            val fallbacks = listOf(
                "data/movie/stream/use_server_user" to mapOf(
                    "idEpisode" to epId,
                    "id_episode" to epId,
                    "id" to epId
                ),
                "data/fyp/server" to mapOf(
                    "idEpisode" to epId,
                    "id_episode" to epId
                )
            )
            for ((path, params) in fallbacks) {
                try {
                    val raw = apiGet(path, params)
                    collectStreamLinks(raw, servers)
                    if (servers.isNotEmpty()) break
                } catch (_: Exception) {
                }
            }
        }

        // 3) Emit collected links
        for ((name, link) in servers.distinctBy { it.second }) {
            when {
                link.contains("naniplay", true) || link.contains("/api/source/") -> {
                    if (loadNaniplay(link, name, callback)) found = true
                }
                link.contains(".m3u8") || link.contains(".mp4") ||
                (link.startsWith("http") && looksLikeVideo(link)) -> {
                    callback(
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
                link.startsWith("http") -> {
                    // Try extractor for known hosts
                    loadExtractor(link, mainUrl, subtitleCallback, callback)
                    found = true
                }
            }
        }

        // 4) Naniplay direct by episode id
        if (!found) {
            try {
                if (loadNaniplay("$naniplayBase/api/source/$epId", "Naniplay", callback)) {
                    found = true
                }
            } catch (_: Exception) {
            }
        }

        // 5) Try naniplay with movie id if available
        if (!found && !movieId.isNullOrBlank()) {
            try {
                if (loadNaniplay("$naniplayBase/api/source/$movieId", "Naniplay", callback)) {
                    found = true
                }
            } catch (_: Exception) {
            }
        }

        return found
    }

    // ---------- helpers ----------

    private fun firstText(node: JsonNode, vararg keys: String): String? {
        for (k in keys) {
            val v = node.path(k)
            if (!v.isMissingNode && !v.isNull) {
                val t = v.asText(null)
                if (!t.isNullOrBlank() && t != "null" && t != "undefined") return t
            }
        }
        return null
    }

    private fun looksLikeVideo(url: String): Boolean {
        val u = url.lowercase()
        return u.contains(".m3u8") || u.contains(".mp4") || u.contains(".mkv") ||
            u.contains("video") || u.contains("stream") || u.contains("play") ||
            u.contains("cdn") || u.contains("hls")
    }

    private fun collectStreamLinks(raw: String, out: MutableList<Pair<String, String>>) {
        if (raw.isBlank()) return
        try {
            val root = parseJson<JsonNode>(raw)
            val dataNode = if (root.has("data")) root.path("data") else root

            fun walk(node: JsonNode, depth: Int = 0) {
                if (depth > 6) return
                when {
                    node.isArray -> node.forEach { walk(it, depth + 1) }
                    node.isObject -> {
                        val link = firstText(
                            node,
                            "link", "url", "file", "src", "stream", "video",
                            "play_url", "video_url", "source", "hls", "dash"
                        )
                        val name = firstText(
                            node,
                            "name", "quality", "server", "label", "type", "title"
                        ) ?: "Server"
                        if (!link.isNullOrBlank() && link.startsWith("http")) {
                            out.add(name to link)
                        }
                        // also string values that look like urls
                        node.fields().forEachRemaining { (key, v) ->
                            if (v.isTextual) {
                                val t = v.asText()
                                if (t.startsWith("http") && looksLikeVideo(t)) {
                                    out.add(key to t)
                                }
                            } else if (v.isArray || v.isObject) {
                                walk(v, depth + 1)
                            }
                        }
                    }
                    node.isTextual -> {
                        val t = node.asText()
                        if (t.startsWith("http") && looksLikeVideo(t)) {
                            out.add("Direct" to t)
                        }
                    }
                }
            }
            walk(dataNode)
            // also walk root in case data is not the only place
            if (out.isEmpty()) walk(root)
        } catch (_: Exception) {
            // Maybe raw is a direct URL
            if (raw.trim().startsWith("http") && looksLikeVideo(raw.trim())) {
                out.add("Direct" to raw.trim())
            }
        }
    }

    private fun parseMovieList(raw: String): List<SearchResponse> {
        if (raw.isBlank()) return emptyList()
        return try {
            val root = parseJson<JsonNode>(raw)
            val candidates = mutableListOf<JsonNode>()

            fun collectArrays(node: JsonNode, depth: Int = 0) {
                if (depth > 5) return
                when {
                    node.isArray && node.size() > 0 -> {
                        val first = node[0]
                        if (first != null && first.isObject &&
                            (first.has("id") || first.has("id_movie") || first.has("title") ||
                                first.has("image_poster") || first.has("name"))
                        ) {
                            candidates.add(node)
                        } else {
                            node.forEach { collectArrays(it, depth + 1) }
                        }
                    }
                    node.isObject -> {
                        listOf("list", "data", "movies", "items", "results", "anime", "home").forEach { key ->
                            if (node.has(key)) collectArrays(node.path(key), depth + 1)
                        }
                        node.fields().forEachRemaining { (_, v) ->
                            if (v.isArray || v.isObject) collectArrays(v, depth + 1)
                        }
                    }
                }
            }
            collectArrays(root)

            val listNode = candidates.firstOrNull() ?: return emptyList()

            listNode.mapNotNull { item ->
                if (!item.isObject) return@mapNotNull null
                val id = firstText(item, "id", "id_movie", "movie_id", "idMovie") ?: return@mapNotNull null
                val title = firstText(item, "title", "name", "judul") ?: return@mapNotNull null
                val poster = firstText(
                    item,
                    "image_poster", "movie_image_poster", "image", "poster",
                    "cover", "thumbnail", "poster_url", "image_url"
                )
                val type = getType(firstText(item, "type", "tipe"))
                val href = "$mainUrl/movie/$id"

                newAnimeSearchResponse(title, href, type) {
                    this.posterUrl = fixImageUrl(poster)
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun parseEpisodes(raw: String, movieId: String? = null): List<Episode> {
        if (raw.isBlank()) return emptyList()
        return try {
            val root = parseJson<JsonNode>(raw)
            val candidates = mutableListOf<JsonNode>()

            fun collectArrays(node: JsonNode, depth: Int = 0) {
                if (depth > 5) return
                when {
                    node.isArray && node.size() > 0 -> {
                        val first = node[0]
                        if (first != null && first.isObject &&
                            (first.has("id") || first.has("id_episode") || first.has("index") ||
                                first.has("title") || first.has("number"))
                        ) {
                            candidates.add(node)
                        } else {
                            node.forEach { collectArrays(it, depth + 1) }
                        }
                    }
                    node.isObject -> {
                        listOf("list", "data", "episodes", "items", "results").forEach { key ->
                            if (node.has(key)) collectArrays(node.path(key), depth + 1)
                        }
                        node.fields().forEachRemaining { (_, v) ->
                            if (v.isArray || v.isObject) collectArrays(v, depth + 1)
                        }
                    }
                }
            }
            collectArrays(root)

            val listNode = candidates.firstOrNull() ?: return emptyList()

            listNode.mapIndexed { index, item ->
                if (!item.isObject) return@mapIndexed null
                val epId = firstText(item, "id", "id_episode", "episode_id") ?: (index + 1).toString()
                val title = firstText(item, "title", "name")
                val num = item.path("index").asInt(0).takeIf { it > 0 }
                    ?: item.path("number").asInt(0).takeIf { it > 0 }
                    ?: item.path("episode").asInt(0).takeIf { it > 0 }
                    ?: (index + 1)
                val poster = firstText(item, "image", "poster", "thumbnail", "image_poster")

                // Store movieId|epId so loadLinks has both
                val dataStr = if (!movieId.isNullOrBlank()) "$movieId|$epId" else epId

                newEpisode("$mainUrl/episode/$epId") {
                    this.name = title ?: "Episode $num"
                    this.episode = num
                    this.posterUrl = fixImageUrl(poster)
                    this.data = dataStr
                }
            }.filterNotNull().sortedBy { it.episode }
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
                data = mapOf("r" to "", "d" to "www.naniplay.com"),
                interceptor = interceptor()
            )
            val resp = response.safeText()
            val root = parseJson<JsonNode>(resp)
            val dataList = root.path("data")
            if (!dataList.isArray) return false

            var ok = false
            dataList.forEach { src ->
                val file = src.path("file").asText(null) ?: return@forEach
                val label = src.path("label").asText(null) ?: src.path("type").asText(null) ?: "Default"
                callback(
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

    /** Fix image URLs from API (relative paths, filenames, double-slash, etc.) */
    private fun fixImageUrl(url: String?): String? {
        if (url.isNullOrBlank() || url == "null") return null
        val trimmed = url.trim()
        return when {
            trimmed.startsWith("//") -> "https:$trimmed"
            trimmed.startsWith("http://") || trimmed.startsWith("https://") -> trimmed
            trimmed.startsWith("/assets/") -> "$mainUrl$trimmed"
            trimmed.startsWith("assets/") -> "$mainUrl/$trimmed"
            trimmed.startsWith("/") -> "$mainUrl$trimmed"
            // bare filename / path → try assets/images
            !trimmed.contains("/") -> "$assetBase/$trimmed"
            else -> "$mainUrl/$trimmed"
        }.replace("://", "://").replace(Regex("(?<!:)/{2,}"), "/")
            // keep protocol double-slash
            .replace("https:/", "https://")
            .replace("http:/", "http://")
    }
}
