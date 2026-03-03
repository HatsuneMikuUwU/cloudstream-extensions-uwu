package com.animein

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor

class AnimeinProvider : MainAPI() {
    override var mainUrl = "https://xyz-api.animein.net/3/2"
    override var name = "Animein"
    override val hasMainPage = true
    override var lang = "id"
    override val supportedTypes = setOf(TvType.Anime)

    private val idUser = "388448"
    private val keyClient = "WOcU74yO3OznevT4fju3CS2ovlff9vVFt788u6tZNUjxyrWlqQ"
    private val apkVer = "5.0.2"

    private val apiHeaders = mapOf(
        "User-Agent" to "okhttp/4.12.0",
        "Accept-Encoding" to "gzip"
    )

    private fun getApiUrl(endpoint: String, query: String = ""): String {
        return "$mainUrl/$endpoint?id_user=$idUser&key_client=$keyClient&apk_ver=$apkVer$query"
    }

    override val mainPage = mainPageOf(
        getApiUrl("home/hot", "&limit=12&genre_in=") to "Hot Anime",
        getApiUrl("home/new", "&limit=12&genre_in=") to "New Anime",
        getApiUrl("home/popular", "&limit=12&genre_in=") to "Popular Anime"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val response = app.get(request.data, headers = apiHeaders).parsedSafe<ApiResponse>()
        val homeItems = mutableListOf<SearchResponse>()

        response?.data?.movie?.forEach { item ->
            val title = item.title ?: return@forEach
            val url = item.id ?: return@forEach 

            homeItems.add(newAnimeSearchResponse(title, url, TvType.Anime) {
                this.posterUrl = item.imagePoster
            })
        }

        return newHomePageResponse(request.name, homeItems)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = getApiUrl("search", "&query=$query")
        val response = app.get(searchUrl, headers = apiHeaders).parsedSafe<ApiResponse>()
        val searchResults = mutableListOf<SearchResponse>()

        response?.data?.movie?.forEach { item ->
            val title = item.title ?: return@forEach
            val url = item.id ?: return@forEach
            
            searchResults.add(newAnimeSearchResponse(title, url, TvType.Anime) {
                this.posterUrl = item.imagePoster
            })
        }
        return searchResults
    }

    override suspend fun load(url: String): LoadResponse {
        val episodeApiUrl = getApiUrl("movie/episode/$url", "&search=&page=0")
        val response = app.get(episodeApiUrl, headers = apiHeaders).parsedSafe<ApiEpisodeResponse>()
        val episodes = mutableListOf<Episode>()

        response?.data?.episode?.forEach { ep ->
            val epId = ep.id ?: return@forEach
            val epNum = ep.index?.toIntOrNull()
            val imageUrl = if (ep.image?.startsWith("http") == true) ep.image else "https://xyz-api.animein.net${ep.image}"

            episodes.add(newEpisode(epId) {
                this.name = ep.title ?: "Episode $epNum"
                this.episode = epNum
                this.posterUrl = imageUrl
            })
        }

        episodes.reverse()

        return newAnimeLoadResponse("Anime $url", url, TvType.Anime) {
            this.episodes = mutableMapOf(DubStatus.Subbed to episodes)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val streamUrl = "https://animeinweb.com/api/proxy/3/2/episode/streamnew/$data"
        val response = app.get(streamUrl, headers = apiHeaders).parsedSafe<ApiStreamResponse>()

        response?.data?.server?.forEach { server ->
            val link = server.link ?: return@forEach
            val qualityStr = server.quality ?: ""
            val type = server.type ?: ""
            val serverName = server.name ?: "Animein"

            val videoQuality = when {
                qualityStr.contains("1080") -> Qualities.P1080.value
                qualityStr.contains("720") -> Qualities.P720.value
                qualityStr.contains("480") -> Qualities.P480.value
                qualityStr.contains("360") -> Qualities.P360.value
                else -> Qualities.Unknown.value
            }

            if (type == "direct" || link.endsWith(".mp4") || link.endsWith(".m3u8")) {
                callback(
                    newExtractorLink(
                        source = serverName,
                        name = serverName,
                        url = link,
                        referer = "https://animeinweb.com/",
                        quality = videoQuality,
                        isM3u8 = link.contains(".m3u8")
                    )
                )
            } else {
                loadExtractor(link, "https://animeinweb.com/", subtitleCallback, callback)
            }
        }
        return true
    }

    data class ApiResponse(
        @JsonProperty("data") val data: ApiData? = null
    )

    data class ApiData(
        @JsonProperty("movie") val movie: List<MovieItem>? = null
    )

    data class MovieItem(
        @JsonProperty("id") val id: String? = null,
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("image_poster") val imagePoster: String? = null,
        @JsonProperty("synopsis") val synopsis: String? = null
    )

    data class ApiEpisodeResponse(
        @JsonProperty("data") val data: ApiEpisodeData? = null
    )

    data class ApiEpisodeData(
        @JsonProperty("episode") val episode: List<EpisodeItem>? = null
    )

    data class EpisodeItem(
        @JsonProperty("id") val id: String? = null,
        @JsonProperty("index") val index: String? = null,
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("image") val image: String? = null
    )

    data class ApiStreamResponse(
        @JsonProperty("data") val data: ApiStreamData? = null
    )

    data class ApiStreamData(
        @JsonProperty("server") val server: List<ServerItem>? = null
    )

    data class ServerItem(
        @JsonProperty("link") val link: String? = null,
        @JsonProperty("quality") val quality: String? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("type") val type: String? = null
    )
}
