package com.animeinweb

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson

class AnimeInWebProvider : MainAPI() {
    override var mainUrl     = "https://animeinweb.com"
    override var name        = "AnimeInWeb"
    override val hasMainPage = true
    override var lang        = "id"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.OVA,
    )

    // ─── API ──────────────────────────────────────────────────────────────────
    private val apiBase   = "https://xyz-api.animein.net/3/2"
    private val cdnBase   = "https://xyz-api.animein.net"
    private val idUser    = "388448"
    private val keyClient = "WOcU74yO3OznevT4fju3CS2ovlff9vVFt788u6tZNUjxyrWlqQ"
    private val apkVer    = "5.0.2"

    private val defaultHeaders = mapOf(
        "Accept"     to "application/json",
        "User-Agent" to "AnimeInWeb/$apkVer Android",
    )

    private fun buildParams(extra: Map<String, String> = emptyMap()): String {
        val base = mapOf(
            "id_user"    to idUser,
            "key_client" to keyClient,
            "apk_ver"    to apkVer,
        )
        return (base + extra).entries.joinToString("&") { "${it.key}=${it.value}" }
    }

    private suspend fun apiGet(path: String, extra: Map<String, String> = emptyMap()): String {
        return app.get(
            "$apiBase/$path?${buildParams(extra)}",
            referer = mainUrl,
            headers = defaultHeaders,
        ).text
    }

    private fun String?.fixImage(): String? {
        if (this.isNullOrEmpty()) return null
        return if (startsWith("http")) this else "$cdnBase$this"
    }

    // Pindahkan ke dalam MainAPI agar bisa mengakses mainUrl & newAnimeSearchResponse
    private fun AnimeItem.toSearchResponse(): AnimeSearchResponse {
        return newAnimeSearchResponse(
            name = title ?: "Unknown",
            url  = "$mainUrl/anime/$id",
            type = getType(type ?: "SERIES"),
        ) {
            posterUrl = image_poster.fixImage()
            addSub(latest_episode ?: total_episode)
        }
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────
    companion object {
        fun getType(t: String): TvType = when {
            t.equals("OVA",   ignoreCase = true) -> TvType.OVA
            t.equals("MOVIE", ignoreCase = true) -> TvType.AnimeMovie
            else -> TvType.Anime
        }
        fun getStatus(s: String): ShowStatus = when {
            s.equals("ONGOING",  ignoreCase = true) -> ShowStatus.Ongoing
            s.equals("FINISHED", ignoreCase = true) -> ShowStatus.Completed
            else -> ShowStatus.Completed
        }
        fun parseQuality(q: String): Int = when {
            q.contains("1080") -> Qualities.P1080.value
            q.contains("720")  -> Qualities.P720.value
            q.contains("480")  -> Qualities.P480.value
            q.contains("360")  -> Qualities.P360.value
            else               -> Qualities.Unknown.value
        }
    }

    // ─── Main Page ────────────────────────────────────────────────────────────
    override val mainPage = mainPageOf(
        "home/hot"         to "🔥 Populer",
        "home/update"      to "🆕 Terbaru Update",
        "home/ongoing"     to "📺 Sedang Tayang",
        "home/finished"    to "✅ Selesai",
        "home/movie"       to "🎬 Anime Movie",
        "home/recommended" to "⭐ Rekomendasi",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val json  = apiGet(request.data, extra = mapOf("limit" to "24", "page" to "$page", "genre_in" to ""))
        val items = parseJson<ApiListResponse>(json).data?.allItems() ?: emptyList()
        return newHomePageResponse(request.name, items.map { it.toSearchResponse() })
    }

    // ─── Search ───────────────────────────────────────────────────────────────
    override suspend fun search(query: String): List<SearchResponse> {
        val json = apiGet("search", extra = mapOf("q" to query, "limit" to "30"))
        return parseJson<ApiListResponse>(json).data?.allItems()
            ?.map { it.toSearchResponse() } ?: emptyList()
    }

    // ─── Load Detail ──────────────────────────────────────────────────────────
    override suspend fun load(url: String): LoadResponse? {
        val movieId = url.substringAfterLast("/")
        val json    = apiGet("movie/detail/$movieId")
        val detail  = parseJson<ApiDetailResponse>(json).data ?: return null
        val movie   = detail.movie ?: return null
        val seasons = detail.season ?: emptyList()

        val episodes = buildEpisodeList(movieId)

        val tags = movie.genre
            ?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() && it != "-" }

        return newAnimeLoadResponse(
            name = movie.title ?: "Unknown",
            url  = url,
            type = getType(movie.type ?: "SERIES")
        ) {
            posterUrl           = movie.image_poster.fixImage()
            backgroundPosterUrl = movie.image_cover.fixImage()
            plot                = movie.synopsis?.replace("\r\n", "\n")
            this.tags           = tags
            showStatus          = getStatus(movie.status ?: "")
            year                = movie.year?.toIntOrNull()
            duration            = movie.duration?.toIntOrNull()

            if (seasons.size > 1) {
                this.recommendations = seasons
                    .filter { season -> season.id != movieId }
                    .map { season -> season.toSearchResponse() }
            }

            addEpisodes(DubStatus.Subbed, episodes)
        }
    }

    private suspend fun buildEpisodeList(movieId: String): List<Episode> {
        val json = apiGet("movie/$movieId/episode", extra = mapOf("limit" to "999", "sort" to "asc"))
        val list = parseJson<ApiEpisodeListResponse>(json).data?.allItems() ?: return emptyList()

        return list.mapIndexed { idx, ep ->
            val epNum = ep.index?.toIntOrNull() ?: ep.number ?: (idx + 1)
            val epTitle = if (!ep.title.isNullOrBlank()) ep.title else "Episode $epNum"
            newEpisode(ep.id ?: "") {
                this.name      = epTitle
                this.episode   = epNum
                this.posterUrl = ep.image.fixImage()
                this.addDate(ep.key_time ?: ep.aired)
            }
        }
    }

    // ─── Load Links ───────────────────────────────────────────────────────────
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (data.isEmpty()) return false

        val json    = apiGet("episode/streamnew/$data")
        val servers = parseJson<ApiStreamResponse>(json).data?.server ?: return false

        servers.forEach { server ->
            val link    = server.link ?: return@forEach
            val quality = server.quality ?: ""
            val label   = "${server.name ?: "Server"} $quality".trim()

            when (server.type?.lowercase()) {
                "direct" -> {
                    callback(
                        ExtractorLink(
                            source  = name,
                            name    = label,
                            url     = link,
                            referer = mainUrl,
                            quality = parseQuality(quality),
                            isM3u8  = false,
                        )
                    )
                }
                else -> {
                    loadExtractor(link, mainUrl, subtitleCallback, callback)
                }
            }
        }

        return true
    }
}

// ─── Data Classes ─────────────────────────────────────────────────────────────

data class ApiListResponse(
    val status: Int?      = null,
    val error:  Boolean?  = null,
    val data:   ListData? = null,
)

data class ListData(
    val movie: List<AnimeItem>? = null,
    val anime: List<AnimeItem>? = null,
    val list:  List<AnimeItem>? = null,
) { fun allItems() = movie ?: anime ?: list ?: emptyList() }

data class AnimeItem(
    val id:             String? = null,
    val title:          String? = null,
    val synopsis:       String? = null,
    val synonyms:       String? = null,
    val image_poster:   String? = null,
    val image_cover:    String? = null,
    val type:           String? = null,
    val year:           String? = null,
    val day:            String? = null,
    val status:         String? = null,
    val views:          String? = null,
    val favorites:      String? = null,
    val studio:         String? = null,
    val aired_start:    String? = null,
    val aired_end:      String? = null,
    val genre:          String? = null,
    val score:          String? = null,
    val duration:       String? = null,
    val season:         String? = null,
    val total_episode:  Int?    = null,
    val latest_episode: Int?    = null,
)

data class ApiDetailResponse(
    val status: Int?        = null,
    val error:  Boolean?    = null,
    val data:   DetailData? = null,
)

data class DetailData(
    val movie:   AnimeItem?       = null,
    val episode: EpisodeItem?     = null,
    val season:  List<AnimeItem>? = null,
)

data class ApiEpisodeListResponse(
    val status: Int?             = null,
    val data:   EpisodeListData? = null,
)

data class EpisodeListData(
    val episode: List<EpisodeItem>? = null,
    val list:    List<EpisodeItem>? = null,
) { fun allItems() = episode ?: list ?: emptyList() }

data class EpisodeItem(
    val id:             String? = null,
    val title:          String? = null,
    val index:          String? = null,
    val number:         Int?    = null,
    val views:          String? = null,
    val id_movie:       String? = null,
    val key_time:       String? = null,
    val aired:          String? = null,
    val image:          String? = null,
)

data class ApiStreamResponse(
    val status: Int?       = null,
    val error:  Boolean?   = null,
    val data:   StreamData? = null,
)

data class StreamData(
    val episode:      EpisodeItem?        = null,
    val episode_next: EpisodeItem?        = null,
    val server:       List<StreamServer>? = null,
)

data class StreamServer(
    val id:        String? = null,
    val link:      String? = null,
    val quality:   String? = null,
    val name:      String? = null,
    val type:      String? = null,
    val domain:    String? = null,
    val username:  String? = null,
    val server_id: String? = null,
)
