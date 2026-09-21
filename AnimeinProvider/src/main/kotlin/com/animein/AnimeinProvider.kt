package com.animein

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.nicehttp.RequestBodyTypes
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.jsoup.Jsoup

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

    companion object {
        fun getType(t: String?): TvType {
            return when {
                t?.contains("OVA", true) == true || t?.contains("Special", true) == true -> TvType.OVA
                t?.contains("Movie", true) == true -> TvType.AnimeMovie
                else -> TvType.Anime
            }
        }

        fun getStatus(t: String?): ShowStatus {
            return when (t?.lowercase()) {
                "ongoing", "on going" -> ShowStatus.Ongoing
                "completed", "complete" -> ShowStatus.Completed
                else -> ShowStatus.Completed
            }
        }
    }

    private suspend fun apiGet(path: String, params: Map<String, String> = emptyMap()): String {
        val url = if (path.startsWith("http")) path else "$mainUrl/$path".replace("//", "/").replace("https:/", "https://")
        val query = if (params.isNotEmpty()) {
            "?" + params.entries.joinToString("&") { "${it.key}=${it.value}" }
        } else ""
        val fullUrl = url + query

        val headers = mapOf(
            "Accept" to "application/json, text/plain, */*",
            "User-Agent" to "okhttp/4.12.0",
            "Accept-Language" to "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7",
            "X-Requested-With" to "com.okeko.animein",
            "Origin" to "https://animein.net",
            "Referer" to "https://animein.net/"
        )

        return app.get(fullUrl, headers = headers).text
    }

    private suspend fun ensureDomain() {
        try {
            val resp = app.get("$bootUrl/data/setup/data", headers = mapOf(
                "Accept" to "application/json",
                "User-Agent" to "okhttp/4.12.0"
            )).text
            val json = parseJson<Envelope<SetupData>>(resp)
            val domain = json.data?.domainApi?.value
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
        val params = mapOf("page" to page.toString())
        val raw = apiGet(request.data, params)
        val items = parseHomeItems(raw)
        return newHomePageResponse(request.name, items)
    }

    private fun parseHomeItems(raw: String): List<SearchResponse> {
        return try {
            val envelope = parseJson<Envelope<Any>>(raw)
            val dataStr = envelope.data?.toJson() ?: raw
            val list = when {
                dataStr.contains("\"list\"") -> {
                    parseJson<Map<String, Any>>(dataStr)["list"]?.toJson()?.let {
                        parseJson<List<Map<String, Any>>>(it)
                    }
                }
                dataStr.contains("\"data\"") -> {
                    val inner = parseJson<Map<String, Any>>(dataStr)["data"]?.toJson()
                    inner?.let { parseJson<List<Map<String, Any>>>(it) }
                }
                else -> parseJson<List<Map<String, Any>>>(dataStr)
            } ?: emptyList()

            list.mapNotNull { item ->
                val id = (item["id"] ?: item["id_movie"] ?: item["movie_id"] ?: item["idMovie"] ?: item["id_movie_"])?.toString()
                val title = (item["title"] ?: item["name"] ?: item["judul"])?.toString() ?: return@mapNotNull null
                val poster = (item["poster"] ?: item["image"] ?: item["cover"] ?: item["thumbnail"] ?: item["poster_url"])?.toString()
                val typeStr = (item["type"] ?: item["tipe"])?.toString()
                val href = if (id != null) "$mainUrl/movie/$id" else return@mapNotNull null
                newAnimeSearchResponse(title, href, getType(typeStr)) {
                    this.posterUrl = poster?.let { fixUrl(it) }
                }
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        ensureDomain()
        val raw = try {
            apiGet("data/movie/find", mapOf("q" to query, "query" to query, "search" to query, "keyword" to query))
        } catch (_: Exception) {
            apiGet("3/2/explore/movie", mapOf("q" to query, "search" to query))
        }
        return parseHomeItems(raw)
    }

    override suspend fun load(url: String): LoadResponse? {
        ensureDomain()
        val id = url.substringAfterLast("/").substringBefore("?").ifBlank { return null }

        val detailRaw = apiGet("3/2/movie/detail/$id")
        val detail = try {
            parseJson<Envelope<Map<String, Any>>>(detailRaw).data
        } catch (_: Exception) {
            null
        }

        val title = (detail?.get("title") ?: detail?.get("name") ?: detail?.get("judul"))?.toString()
            ?: "Anime $id"
        val poster = (detail?.get("poster") ?: detail?.get("image") ?: detail?.get("cover"))?.toString()
        val plot = (detail?.get("synopsis") ?: detail?.get("description") ?: detail?.get("sinopsis"))?.toString()
        val year = (detail?.get("year") ?: detail?.get("tahun"))?.toString()?.toIntOrNull()
        val status = getStatus((detail?.get("status") ?: detail?.get("status_movie"))?.toString())
        val type = getType((detail?.get("type") ?: detail?.get("tipe"))?.toString())
        val tags = (detail?.get("genre") as? List<*>)?.mapNotNull { it.toString() }
            ?: (detail?.get("genres") as? List<*>)?.mapNotNull { it.toString() }

        val epRaw = apiGet("3/2/movie/episode/$id")
        val episodes = parseEpisodes(epRaw, id)

        return newAnimeLoadResponse(title, url, type) {
            this.posterUrl = poster?.let { fixUrl(it) }
            this.year = year
            this.plot = plot
            this.showStatus = status
            this.tags = tags
            addEpisodes(DubStatus.Subbed, episodes)
        }
    }

    private fun parseEpisodes(raw: String, movieId: String): List<Episode> {
        return try {
            val envelope = parseJson<Envelope<Any>>(raw)
            val dataStr = envelope.data?.toJson() ?: raw
            val list = when {
                dataStr.contains("\"list\"") || dataStr.contains("\"episodes\"") -> {
                    val map = parseJson<Map<String, Any>>(dataStr)
                    (map["list"] ?: map["episodes"] ?: map["data"])?.toJson()?.let {
                        parseJson<List<Map<String, Any>>>(it)
                    }
                }
                else -> parseJson<List<Map<String, Any>>>(dataStr)
            } ?: emptyList()

            list.mapIndexed { index, item ->
                val epId = (item["id"] ?: item["id_episode"] ?: item["episode_id"] ?: item["idEpisode"])?.toString()
                    ?: (index + 1).toString()
                val name = (item["title"] ?: item["name"] ?: item["episode"] ?: "Episode ${index + 1}")?.toString()
                val num = (item["number"] ?: item["episode_number"] ?: item["ep"])?.toString()?.toIntOrNull()
                    ?: (index + 1)
                newEpisode("$mainUrl/episode/$epId") {
                    this.name = name
                    this.episode = num
                    this.data = epId // store id for loadLinks
                }
            }
        } catch (_: Exception) {
            emptyList()
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
            apiGet("data/movie/stream/use_server_user", mapOf("idEpisode" to epId, "id" to epId))
        }

        var found = false
        try {
            val envelope = parseJson<Envelope<Any>>(streamRaw)
            val dataStr = envelope.data?.toJson() ?: streamRaw

            val servers = extractServers(dataStr)
            for (server in servers) {
                val name = server.name.ifBlank { "Server" }
                val link = server.url
                if (link.isBlank()) continue

                if (link.contains("naniplay.com") || link.contains("/api/source/")) {
                    loadNaniplay(link, name, callback)
                    found = true
                } else if (link.contains(".m3u8") || link.contains(".mp4") || link.startsWith("http")) {
                    callback(
                        newExtractorLink(
                            source = name,
                            name = name,
                            url = link,
                            type = INFER_TYPE
                        ) {
                            this.referer = mainUrl
                            this.quality = Qualities.Unknown.value
                        }
                    )
                    found = true
                } else {
                    loadExtractor(link, mainUrl, subtitleCallback, callback)
                    found = true
                }
            }
        } catch (_: Exception) {
        }

        if (!found) {
            try {
                val naniUrl = "$naniplayBase/api/source/$epId"
                loadNaniplay(naniUrl, "Naniplay", callback)
                found = true
            } catch (_: Exception) {}
        }

        return found
    }

    private data class ServerInfo(val name: String, val url: String)

    private fun extractServers(jsonStr: String): List<ServerInfo> {
        val result = mutableListOf<ServerInfo>()
        try {
            val list = try {
                parseJson<List<Map<String, Any>>>(jsonStr)
            } catch (_: Exception) {
                val map = parseJson<Map<String, Any>>(jsonStr)
                val candidates = listOf("list", "servers", "sources", "data", "stream", "server")
                candidates.firstNotNullOfOrNull { key ->
                    map[key]?.toJson()?.let { parseJson<List<Map<String, Any>>>(it) }
                } ?: emptyList()
            }

            for (item in list) {
                val name = (item["name"] ?: item["server"] ?: item["label"] ?: item["quality"] ?: "Server")?.toString() ?: "Server"
                val url = (item["url"] ?: item["file"] ?: item["link"] ?: item["src"] ?: item["stream"] ?: item["video"])?.toString()
                if (!url.isNullOrBlank()) {
                    result.add(ServerInfo(name, url))
                }
            }

            if (result.isEmpty()) {
                val map = parseJson<Map<String, Any>>(jsonStr)
                val url = (map["url"] ?: map["file"] ?: map["link"] ?: map["src"])?.toString()
                if (!url.isNullOrBlank()) {
                    result.add(ServerInfo("Direct", url))
                }
            }
        } catch (_: Exception) {}
        return result
    }

    private suspend fun loadNaniplay(url: String, name: String, callback: (ExtractorLink) -> Unit) {
        try {
            val resp = app.post(
                url,
                headers = mapOf(
                    "User-Agent" to "okhttp/4.12.0",
                    "Referer" to naniplayBase,
                    "Accept" to "application/json"
                ),
                data = mapOf("r" to "", "d" to "www.naniplay.com")
            ).text

            val parsed = parseJson<Map<String, Any>>(resp)
            val dataList = (parsed["data"] as? List<*>)?.mapNotNull { it as? Map<*, *> } ?: emptyList()
            for (src in dataList) {
                val file = src["file"]?.toString() ?: continue
                val label = src["label"]?.toString() ?: name
                callback(
                    newExtractorLink(
                        source = name,
                        name = "$name - $label",
                        url = file,
                        type = INFER_TYPE
                    ) {
                        this.referer = naniplayBase
                        this.quality = when {
                            label.contains("1080") -> Qualities.P1080.value
                            label.contains("720") -> Qualities.P720.value
                            label.contains("480") -> Qualities.P480.value
                            label.contains("360") -> Qualities.P360.value
                            else -> Qualities.Unknown.value
                        }
                    }
                )
            }
        } catch (_: Exception) {}
    }

    data class Envelope<T>(
        @JsonProperty("status") val status: Int? = null,
        @JsonProperty("error") val error: Boolean? = null,
        @JsonProperty("data") val data: T? = null,
        @JsonProperty("message") val message: String? = null
    )

    data class SetupData(
        @JsonProperty("domain_api") val domainApi: DomainApi? = null
    )

    data class DomainApi(
        @JsonProperty("value") val value: String? = null
    )
}
