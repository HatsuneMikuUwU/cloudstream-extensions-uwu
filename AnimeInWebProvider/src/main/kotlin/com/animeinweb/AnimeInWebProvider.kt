package com.animeinweb

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

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
    private val baseParams get() = mapOf(
        "id_user"    to idUser,
        "key_client" to keyClient,
        "apk_ver"    to apkVer,
    )

    private suspend fun apiGet(path: String, extra: Map<String, String> = emptyMap()): NiceResponse {
        val params = (baseParams + extra).entries.joinToString("&") { "${it.key}=${it.value}" }
        return app.get("$apiBase/$path?$params", referer = mainUrl, headers = defaultHeaders)
    }

    /** Fix relative image path → absolute URL */
    private fun String?.fixImage(): String? {
        if (this.isNullOrEmpty()) return null
        return if (startsWith("http")) this else "$cdnBase$this"
    }

    // ─── Type / Status ────────────────────────────────────────────────────────
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
        val resp  = apiGet(request.data, extra = mapOf("limit" to "24", "page" to "$page", "genre_in" to ""))
        val items = resp.parsedSafe<ApiListResponse>()?.data?.allItems() ?: emptyList()
        return newHomePageResponse(request.name, items.map { it.toSearchResponse() })
    }

    // ─── Search ───────────────────────────────────────────────────────────────
    override suspend fun search(query: String): List<SearchResponse> {
        val resp = apiGet("search", extra = mapOf("q" to query, "limit" to "30"))
        return resp.parsedSafe<ApiListResponse>()?.data?.allItems()
            ?.map { it.toSearchResponse() } ?: emptyList()
    }

    // ─── Load Detail ──────────────────────────────────────────────────────────
    // Endpoint: GET /3/2/movie/detail/{id}
    // Response: { data: { movie, episode (latest), season[] } }
    override suspend fun load(url: String): LoadResponse? {
        val movieId = url.substringAfterLast("/")

        val detail = apiGet("movie/detail/$movieId")
            .parsedSafe<ApiDetailResponse>()?.data ?: return null
        val movie   = detail.movie   ?: return null
        val seasons = detail.season  ?: emptyList()

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

            movie.studio?.takeIf { it.isNotEmpty() }?.let {
                this.actors = listOf(ActorData(Actor(it)))
            }

            if (seasons.size > 1) {
                this.recommendations = seasons
                    .filter { it.id != movieId }
                    .map { it.toSearchResponse() }
            }

            addEpisodes(DubStatus.Subbed, episodes)
        }
    }

    private suspend fun buildEpisodeList(movieId: String): List<Episode> {
        val resp = apiGet(
            "movie/$movieId/episode",
            extra = mapOf("limit" to "999", "sort" to "asc")
        )
        val list = resp.parsedSafe<ApiEpisodeListResponse>()?.data?.allItems()
            ?: return emptyList()

        return list.mapIndexed { idx, ep ->
            val epNum = ep.index?.toIntOrNull() ?: ep.number ?: (idx + 1)
            // data = episodeId  (that's all we need for streamnew)
            newEpisode(ep.id ?: "") {
                this.name      = ep.title?.takeIf { it.isNotBlank() } ?: "Episode $epNum"
                this.episode   = epNum
                this.posterUrl = ep.image.fixImage()
                this.addDate(ep.key_time ?: ep.aired)
            }
        }
    }

    // ─── Load Links ───────────────────────────────────────────────────────────
    // Confirmed endpoint: GET /3/2/episode/streamnew/{episodeId}
    // Response: { data: { episode, episode_next, server: [ {link, quality, type, name} ] } }
    override suspend fun loadLinks(
        data: String,   // = episodeId  e.g. "313448"
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val episodeId = data.ifEmpty { return false }

        val resp = apiGet("episode/streamnew/$episodeId")
        val servers = resp.parsedSafe<ApiStreamResponse>()?.data?.server
            ?: return false

        servers.forEach { server ->
            val link    = server.link?.ifEmpty { null } ?: return@forEach
            val quality = server.quality ?: ""
            val label   = buildString {
                append(server.name ?: "Server")
                if (quality.isNotEmpty()) append(" $quality")
            }

            when (server.type?.lowercase()) {
                "direct" -> {
                    // Direct MP4 from storages.animein.net — confirmed
                    callback(
                        newExtractorLink(
                            source = name,
                            name   = label,
                            url    = link,
                            type   = ExtractorLinkType.VIDEO,
                        ) {
                            this.quality = when (quality) {
                                "1080p" -> Qualities.P1080.value
                                "720p"  -> Qualities.P720.value
                                "480p"  -> Qualities.P480.value
                                "360p"  -> Qualities.P360.value
                                else    -> Qualities.Unknown.value
                            }
                            this.isM3u8 = false
                        }
                    )
                }
                else -> {
                    // Embed / iframe server
                    loadExtractor(link, mainUrl, subtitleCallback, callback)
                }
            }
        }

        return true
    }
}

// ─── Data Classes ─────────────────────────────────────────────────────────────

// LIST (home/search)
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
    val is_fav:         Any?    = null,
    val total_episode:  Int?    = null,
    val latest_episode: Int?    = null,
) {
    fun toSearchResponse() = newAnimeSearchResponse(
        name = title ?: "Unknown",
        url  = "https://animeinweb.com/anime/$id",
        type = AnimeInWebProvider.getType(type ?: "SERIES")
    ) {
        posterUrl = if (image_poster?.startsWith("http") == true) image_poster
                    else "https://xyz-api.animein.net$image_poster"
        addSub(latest_episode ?: total_episode)
    }
}

// DETAIL  →  { data: { movie, episode, season[] } }
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

// EPISODE LIST  →  { data: { episode[] } }
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
    val index:          String? = null,   // "1", "2", ...
    val number:         Int?    = null,
    val episode_number: Int?    = null,
    val views:          String? = null,
    val id_movie:       String? = null,
    val key_time:       String? = null,   // "12 Jan 2026"
    val aired:          String? = null,
    val image:          String? = null,   // may be relative
    val thumbnail:      String? = null,
)

// STREAM  →  GET /episode/streamnew/{id}
// { data: { episode, episode_next, server: [{id, link, quality, type, name, ...}] } }
data class ApiStreamResponse(
    val status: Int?       = null,
    val error:  Boolean?   = null,
    val data:   StreamData? = null,
)
data class StreamData(
    val episode:      EpisodeItem?       = null,
    val episode_next: EpisodeItem?       = null,
    val server:       List<StreamServer>? = null,
)
data class StreamServer(
    val id:            String? = null,
    val link:          String? = null,   // confirmed: direct MP4 URL
    val quality:       String? = null,   // "360p" | "480p" | "720p" | "1080p"
    val key_file_size: String? = null,
    val name:          String? = null,   // e.g. "RAPSODI"
    val type:          String? = null,   // "direct" | "embed"
    val domain:        String? = null,
    val username:      String? = null,
    val server_id:     String? = null,
)
