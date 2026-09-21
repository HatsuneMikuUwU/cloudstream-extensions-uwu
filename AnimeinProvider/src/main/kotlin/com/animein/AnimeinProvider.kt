package com.animein

import com.fasterxml.jackson.databind.JsonNode
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addAniListId
import com.lagradost.cloudstream3.LoadResponse.Companion.addKitsuId
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId
import com.lagradost.cloudstream3.network.CloudflareKiller
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

    private val apkVer = "5.2.2"
    private val cf by lazy { CloudflareKiller() }

    companion object {
        fun getType(t: String?): TvType = when {
            t.isNullOrBlank() -> TvType.Anime
            t.contains("OVA", true) || t.contains("Special", true) -> TvType.OVA
            t.contains("Movie", true) || t.contains("Film", true) -> TvType.AnimeMovie
            else -> TvType.Anime
        }

        fun getStatus(t: String?): ShowStatus = when (t?.lowercase()?.trim()) {
            "ongoing", "on going", "on-going" -> ShowStatus.Ongoing
            else -> ShowStatus.Completed
        }
    }

    private fun headers() = mapOf(
        "Accept" to "application/json",
        "User-Agent" to "okhttp/4.12.0",
        "X-Requested-With" to "com.okeko.animein"
    )

    private fun NiceResponse.safeText(): String {
        val len = headers["Content-Length"]?.toLongOrNull()
        if (len != null && len > 4_500_000L) throw Exception("Response too large")
        val body = try { textLarge } catch (_: Throwable) { text }
        if (body.contains("<html", true) && !body.trimStart().startsWith("{")) {
            throw Exception("Cloudflare / HTML response")
        }
        return body
    }

    private suspend fun apiGet(path: String, extra: Map<String, String> = emptyMap()): String {
        val url = if (path.startsWith("http")) path else "$mainUrl/${path.trimStart('/')}"
        val params = mutableMapOf("apk_ver" to apkVer)
        params.putAll(extra)
        return app.get(url, headers = headers(), params = params, interceptor = cf).safeText()
    }

    override val mainPage = mainPageOf(
        "data/home/list" to "Home",
        "data/home/list_new_episode" to "New Episodes",
        "3/2/home/hot" to "Hot",
        "3/2/home/new" to "Latest",
        "3/2/home/popular" to "Popular",
        "3/2/home/random" to "Random"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val items = enrich(parseList(apiGet(request.data, mapOf("page" to page.toString()))))
        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val raw = runCatching {
            apiGet("data/movie/find", mapOf("q" to query, "search" to query, "page" to "1"))
        }.getOrNull().orEmpty()
        val list = parseList(raw)
        if (list.isNotEmpty()) return enrich(list)
        val fallback = runCatching {
            apiGet("3/2/explore/movie", mapOf("q" to query, "search" to query))
        }.getOrNull().orEmpty()
        return enrich(parseList(fallback))
    }

    override suspend fun load(url: String): LoadResponse? {
        val id = url.substringAfterLast("/").substringBefore("?").ifBlank { return null }

        val detail = runCatching {
            val root = parseJson<JsonNode>(apiGet("3/2/movie/detail/$id"))
            if (root.has("data") && root.path("data").isObject) root.path("data") else root
        }.getOrNull()

        val rawTitle = firstText(detail, "title", "name", "judul") ?: "Anime $id"
        val year = firstText(detail, "year", "tahun")?.toIntOrNull()
        val type = getType(firstText(detail, "type", "tipe"))
        val status = getStatus(firstText(detail, "status"))
        val tags = mutableListOf<String>()
        detail?.path("genre")?.let { g ->
            if (g.isArray) g.forEach { tags.add(it.asText()) }
            else if (g.isTextual) tags.addAll(g.asText().split(",", "|").map { it.trim() }.filter { it.isNotBlank() })
        }

        val tracker = runCatching {
            APIHolder.getTracker(listOf(rawTitle), TrackerType.getTypes(type), year, true)
        }.getOrNull()
        val ids = resolveAnimeIds(listOf(rawTitle), type, year, tracker?.malId, tracker?.aniId?.toIntOrNull())
        val meta = runCatching { fetchAniZipMeta(ids.malId, ids.aniId) }.getOrNull()

        val title = meta?.titles?.get("en") ?: meta?.titles?.get("x-jat") ?: rawTitle
        val poster = tracker?.image ?: meta?.images?.firstOrNull { !it.url.isNullOrBlank() }?.url
        val backdrop = meta?.images?.find { it.coverType == "Fanart" }?.url ?: tracker?.cover ?: poster
        val plot = meta?.description?.replace(Regex("<.*?>"), "")?.takeIf { it.isNotBlank() }
            ?: fetchAniListPlot(ids.malId, ids.aniId)
            ?: firstText(detail, "synopsis", "sinopsis", "description")

        val rawEps = runCatching { parseEpisodes(apiGet("3/2/movie/episode/$id"), id) }.getOrDefault(emptyList())
        val episodes = rawEps.map { ep ->
            val metaEp = ep.episode?.toString()?.let { meta?.episodes?.get(it) }
            newEpisode(ep.data) {
                this.name = metaEp?.title?.get("en") ?: metaEp?.title?.get("ja") ?: ep.name
                this.episode = ep.episode
                this.posterUrl = metaEp?.image?.takeIf { it.isNotBlank() } ?: poster
                this.description = metaEp?.overview
            }
        }

        return newAnimeLoadResponse(title, url, type) {
            this.engName = meta?.titles?.get("en") ?: title
            this.japName = meta?.titles?.get("ja") ?: meta?.titles?.get("x-jat")
            this.posterUrl = poster
            this.backgroundPosterUrl = backdrop
            this.year = year
            this.plot = plot
            this.showStatus = status
            this.tags = tags.distinct()
            addEpisodes(DubStatus.Subbed, episodes)
            addMalId(ids.malId)
            addAniListId(ids.aniId)
            try { addKitsuId(meta?.mappings?.kitsuId) } catch (_: Throwable) {}
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val epId = data.substringAfterLast("|").substringAfterLast("/").ifBlank { data }
        val raw = runCatching { apiGet("3/2/episode/streamnew/$epId") }.getOrNull().orEmpty().ifBlank {
            runCatching {
                apiGet(
                    "data/movie/stream/use_server_user",
                    mapOf("idEpisode" to epId, "id_episode" to epId)
                )
            }.getOrNull().orEmpty()
        }

        val links = mutableListOf<Pair<String, String>>()
        collectLinks(raw, links)
        var found = false
        for ((name, link) in links.distinctBy { it.second }) {
            if (link.contains(".m3u8") || link.contains(".mp4") || link.startsWith("http")) {
                if (link.contains("naniplay", true) || !link.contains(".")) {
                    loadExtractor(link, mainUrl, subtitleCallback, callback)
                } else {
                    callback(
                        newExtractorLink(name, name, link, INFER_TYPE) {
                            this.referer = mainUrl
                            this.quality = qualityFrom(name)
                        }
                    )
                }
                found = true
            }
        }
        return found
    }

    private suspend fun enrich(items: List<SearchResponse>): List<SearchResponse> {
        if (items.isEmpty()) return items
        return items.amap { item ->
            val type = item.type ?: TvType.Anime
            val tracker = runCatching {
                APIHolder.getTracker(listOf(item.name), TrackerType.getTypes(type), null, true)
            }.getOrNull()
            newAnimeSearchResponse(item.name, item.url, type) {
                this.posterUrl = tracker?.image ?: tracker?.cover
            }
        }
    }

    private fun parseList(raw: String): List<SearchResponse> {
        if (raw.isBlank()) return emptyList()
        return try {
            val arrays = findObjectArrays(parseJson<JsonNode>(raw)) { n: JsonNode ->
                n.has("title") || n.has("name") || n.has("id_movie") || n.has("image_poster")
            }
            val list = arrays.firstOrNull() ?: return emptyList()
            list.mapNotNull { item ->
                if (!item.isObject) return@mapNotNull null
                val id = firstText(item, "id_movie", "movie_id", "idMovie", "id") ?: return@mapNotNull null
                val title = firstText(item, "title", "name", "judul") ?: return@mapNotNull null
                newAnimeSearchResponse(title, "$mainUrl/movie/$id", getType(firstText(item, "type", "tipe")))
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun parseEpisodes(raw: String, movieId: String): List<Episode> {
        if (raw.isBlank()) return emptyList()
        return try {
            val arrays = findObjectArrays(parseJson<JsonNode>(raw)) { n: JsonNode ->
                n.has("id") || n.has("id_episode") || n.has("index")
            }
            val list = arrays.firstOrNull() ?: return emptyList()
            list.mapIndexedNotNull { i, item ->
                if (!item.isObject) return@mapIndexedNotNull null
                val epId = firstText(item, "id", "id_episode") ?: (i + 1).toString()
                val num = item.path("index").asInt(0).takeIf { it > 0 }
                    ?: item.path("number").asInt(0).takeIf { it > 0 }
                    ?: (i + 1)
                newEpisode("$movieId|$epId") {
                    this.name = firstText(item, "title", "name") ?: "Episode $num"
                    this.episode = num
                }
            }.sortedBy { it.episode }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun collectLinks(raw: String, out: MutableList<Pair<String, String>>) {
        if (raw.isBlank()) return
        try {
            fun walk(n: JsonNode) {
                when {
                    n.isArray -> n.forEach { walk(it) }
                    n.isObject -> {
                        val link = firstText(n, "link", "url", "file", "src", "stream", "video")
                        val name = firstText(n, "name", "quality", "server", "label") ?: "Server"
                        if (!link.isNullOrBlank() && link.startsWith("http")) out.add(name to link)
                        n.fields().forEachRemaining { (_, v) ->
                            if (v.isTextual && v.asText().startsWith("http")) {
                                val t = v.asText()
                                if (t.contains(".m3u8") || t.contains(".mp4") || t.contains("stream")) {
                                    out.add("Direct" to t)
                                }
                            } else if (v.isObject || v.isArray) walk(v)
                        }
                    }
                }
            }
            walk(parseJson(raw))
        } catch (_: Exception) {
        }
    }

    private fun findObjectArrays(
        node: JsonNode,
        depth: Int = 0,
        pred: (JsonNode) -> Boolean
    ): List<JsonNode> {
        if (depth > 5) return emptyList()
        val found = mutableListOf<JsonNode>()
        when {
            node.isArray && node.size() > 0 && node[0].isObject && pred(node[0]) -> found.add(node)
            node.isArray -> node.forEach { found.addAll(findObjectArrays(it, depth + 1, pred)) }
            node.isObject -> node.fields().forEachRemaining { (_, v) ->
                found.addAll(findObjectArrays(v, depth + 1, pred))
            }
        }
        return found
    }

    private fun firstText(node: JsonNode?, vararg keys: String): String? {
        if (node == null) return null
        for (k in keys) {
            val v = node.path(k)
            if (!v.isMissingNode && !v.isNull) {
                val t = v.asText(null)
                if (!t.isNullOrBlank() && t != "null") return t
            }
        }
        return null
    }

    private fun qualityFrom(name: String?): Int {
        val n = name.orEmpty()
        return when {
            n.contains("1080") -> Qualities.P1080.value
            n.contains("720") -> Qualities.P720.value
            n.contains("480") -> Qualities.P480.value
            n.contains("360") -> Qualities.P360.value
            else -> Qualities.Unknown.value
        }
    }
}
