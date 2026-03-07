package com.oploverz

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addAniListId
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.runBlocking

class OploverzProvider : MainAPI() {
    override var mainUrl = "https://coba.oploverz.ltd"
    private val backAPI = "https://backapi.oploverz.ac"
    override var name = "Oploverz"
    override val hasMainPage = true
    override var lang = "id"
    override val hasQuickSearch = true
    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.OVA
    )

    companion object {
        fun getType(t: String?): TvType {
            if (t == null) return TvType.Anime
            return when {
                t.contains("Serial TV", true) || t.contains("TV", true) -> TvType.Anime
                t.contains("OVA", true) -> TvType.OVA
                t.contains("Movie", true) || t.contains("BD", true) -> TvType.AnimeMovie
                else -> TvType.Anime
            }
        }
        
        var context: android.content.Context? = null

        fun getStatus(t: String?): ShowStatus {
            return when {
                t?.contains("Berlangsung", true) == true || t?.contains("Ongoing", true) == true -> ShowStatus.Ongoing
                else -> ShowStatus.Completed
            }
        }
    }

    override val mainPage = mainPageOf(
        "episodes" to "Update Episode Terbaru",
        "score-desc" to "Rating Tertinggi",
        "title-asc" to "Daftar Anime (A-Z)"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val home = mutableListOf<SearchResponse>()
        var hasNext = false

        if (request.data == "episodes") {
            // FULL API: Mengambil Rilis Terbaru langsung dari Endpoint Episodes
            try {
                val apiResponse = app.get("$backAPI/api/episodes?page=$page&pageSize=24").parsedSafe<AnimeResponse>()
                apiResponse?.data?.forEach { ep ->
                    val series = ep.series
                    if (series != null && series.title != null) {
                        home.add(
                            newAnimeSearchResponse(series.title, "$mainUrl/series/${series.slug}", TvType.Anime) {
                                this.posterUrl = series.poster
                                this.score = Score.from10(series.score)
                                addSub(ep.episodeNumber?.toIntOrNull() ?: series.totalEpisodes)
                            }
                        )
                    }
                }
                val currentPage = apiResponse?.meta?.currentPage ?: 1
                val lastPage = apiResponse?.meta?.lastPage ?: 1
                hasNext = currentPage < lastPage
            } catch (e: Exception) {}
            
        } else {
            // FULL API: Mengambil Daftar Anime Sorting
            try {
                val sortParam = request.data.substringBefore("-")
                val orderParam = request.data.substringAfter("-")
                val apiUrl = "$backAPI/api/series?page=$page&sort_by=${request.data}&sort=$sortParam&order=$orderParam"
                
                val apiResponse = app.get(apiUrl).parsedSafe<SeriesResponse>()
                
                apiResponse?.data?.forEach { series ->
                    if (series.title != null) {
                        home.add(
                            newAnimeSearchResponse(series.title, "$mainUrl/series/${series.slug}", TvType.Anime) {
                                this.posterUrl = series.poster
                                this.score = Score.from10(series.score)
                                addSub(series.totalEpisodes)
                            }
                        )
                    }
                }
                val currentPage = apiResponse?.meta?.currentPage ?: 1
                val lastPage = apiResponse?.meta?.lastPage ?: 1
                hasNext = currentPage < lastPage
            } catch (e: Exception) {}
        }

        if (home.isEmpty()) hasNext = false
        return newHomePageResponse(request.name, home.distinctBy { it.url }, hasNext)
    }

    override suspend fun quickSearch(query: String): List<SearchResponse>? = search(query)

    override suspend fun search(query: String): List<SearchResponse>? {
        // FULL API: Menggunakan rute pencarian backend
        val home = mutableListOf<SearchResponse>()
        try {
            app.get("$backAPI/api/series?q=$query")
                .parsedSafe<SeriesResponse>()?.data?.forEach { series ->
                    if (series.title != null) {
                        home.add(
                            newAnimeSearchResponse(series.title, "$mainUrl/series/${series.slug}", TvType.Anime) {
                                this.otherName = series.japaneseTitle
                                this.posterUrl = series.poster
                                this.score = Score.from10(series.score)
                                addSub(series.totalEpisodes)
                            }
                        )
                    }
                }
        } catch (e: Exception) {}
        return home.ifEmpty { null }
    }

    override suspend fun load(url: String): LoadResponse {
        val slug = url.substringAfter("/series/").substringBefore("/").substringBefore("?")
        
        // FULL API: Mengambil Info Detail Anime
        val seriesDetail = app.get("$backAPI/api/series/$slug").parsedSafe<SingleSeriesResponse>()?.data
            ?: throw ErrorLoadingException("Gagal memuat detail dari API")

        val title = seriesDetail.title ?: ""
        val type = getType(seriesDetail.releaseType)
        val year = seriesDetail.releaseDate?.let { Regex("\\d{4}").find(it)?.value?.toIntOrNull() }
        val tracker = APIHolder.getTracker(listOf(title), TrackerType.getTypes(type), year, true)

        // FULL API: Mengambil Daftar Episode
        val episodes = mutableListOf<Episode>()
        try {
            val episodesApi = app.get("$backAPI/api/episodes?series.slug=$slug&pageSize=2000&sort=episodeNumber-asc").parsedSafe<AnimeResponse>()
            episodesApi?.data?.forEach { ep ->
                val epNum = ep.episodeNumber?.toIntOrNull()
                // Menyimpan URL API Episode langsung ke dalam data episode
                val epUrl = "$backAPI/api/episodes/${ep.id}"
                
                episodes.add(
                    newEpisode(epUrl) {
                        this.episode = epNum
                        this.name = ep.title
                    }
                )
            }
        } catch (e: Exception) {}

        return newAnimeLoadResponse(title, url, type) {
            this.engName = title
            this.japName = seriesDetail.japaneseTitle
            this.posterUrl = tracker?.image ?: seriesDetail.poster
            this.backgroundPosterUrl = tracker?.cover ?: seriesDetail.poster
            this.year = year
            addEpisodes(DubStatus.Subbed, episodes.reversed())
            this.showStatus = getStatus(seriesDetail.status)
            this.plot = seriesDetail.description
            this.tags = seriesDetail.genres?.mapNotNull { it.name }
            addMalId(tracker?.malId)
            addAniListId(tracker?.aniId?.toIntOrNull())
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        
        // FULL API: Memuat data episode spesifik (karena data yang dilempar adalah URL API, bukan frontend)
        val episodeDetail = app.get(data).parsedSafe<SingleEpisodeResponse>()?.data ?: return false

        // 1. Ekstraksi Streaming Links dari Response JSON
        episodeDetail.streamUrl?.forEach { stream ->
            stream.url?.let { url ->
                val quality = getQuality(stream.source ?: "")
                loadFixedExtractor(url, quality, data, subtitleCallback, callback)
            }
        }

        // 2. Ekstraksi Download Links dari Response JSON
        episodeDetail.downloadUrl?.forEach { format ->
            format.resolutions?.forEach { res ->
                val quality = getQuality(res.quality ?: "")
                res.downloadLinks?.forEach { link ->
                    val url = link.url
                    if (!url.isNullOrBlank() && url != "#") {
                        loadFixedExtractor(url, quality, data, subtitleCallback, callback)
                    }
                }
            }
        }

        return true
    }

    private suspend fun loadFixedExtractor(
        url: String,
        quality: Int?,
        referer: String? = null,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        loadExtractor(url, referer, subtitleCallback) { link ->
            runBlocking {
                callback.invoke(
                    newExtractorLink(
                        link.name,
                        link.name,
                        link.url,
                        link.type
                    ) {
                        this.referer = link.referer
                        this.quality = quality ?: Qualities.Unknown.value
                        this.headers = link.headers
                        this.extractorData = link.extractorData
                    }
                )
            }
        }
    }

    private fun getQuality(quality: String) : Int {
        return when {
            quality.contains("4k", true) -> Qualities.P2160.value
            quality.contains("1080", true) || quality.equals("FHD", false) -> Qualities.P1080.value
            quality.contains("720", true) || quality.equals("HD", false) -> Qualities.P720.value
            quality.contains("480", true) || quality.equals("Mini", false) -> Qualities.P480.value
            quality.contains("360", true) -> Qualities.P360.value
            else -> getQualityFromName(quality)
        }
    }

    // --- DATA CLASSES UNTUK API OPLOVERZ ---
    data class Meta(
        @JsonProperty("currentPage") val currentPage: Int? = null,
        @JsonProperty("lastPage") val lastPage: Int? = null
    )

    data class SeriesResponse(
        @JsonProperty("data") val data: ArrayList<Series>? = arrayListOf(),
        @JsonProperty("meta") val meta: Meta? = null
    )

    data class AnimeResponse(
        @JsonProperty("data") val data: ArrayList<EpisodeData>? = arrayListOf(),
        @JsonProperty("meta") val meta: Meta? = null
    )

    data class SingleSeriesResponse(
        @JsonProperty("data") val data: Series? = null
    )

    data class SingleEpisodeResponse(
        @JsonProperty("data") val data: EpisodeData? = null
    )

    data class Series(
        @JsonProperty("id") val id: Int? = null,
        @JsonProperty("seriesId") val seriesId: Int? = null,
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("japaneseTitle") val japaneseTitle: String? = null,
        @JsonProperty("slug") val slug: String? = null,
        @JsonProperty("releaseDate") val releaseDate: String? = null,
        @JsonProperty("poster") val poster: String? = null,
        @JsonProperty("score") val score: Double? = null,
        @JsonProperty("totalEpisodes") val totalEpisodes: Int? = null,
        @JsonProperty("description") val description: String? = null,
        @JsonProperty("status") val status: String? = null,
        @JsonProperty("releaseType") val releaseType: String? = null,
        @JsonProperty("genres") val genres: List<Genre>? = null
    )

    data class Genre(
        @JsonProperty("name") val name: String? = null
    )

    data class EpisodeData(
        @JsonProperty("id") val id: Int? = null,
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("episodeNumber") val episodeNumber: String? = null,
        @JsonProperty("subbed") val subbed: String? = null,
        @JsonProperty("series") val series: Series? = null,
        @JsonProperty("streamUrl") val streamUrl: List<StreamSource>? = null,
        @JsonProperty("downloadUrl") val downloadUrl: List<DownloadFormat>? = null
    )

    data class StreamSource(
        @JsonProperty("source") val source: String? = null,
        @JsonProperty("url") val url: String? = null
    )

    data class DownloadFormat(
        @JsonProperty("format") val format: String? = null,
        @JsonProperty("resolutions") val resolutions: List<DownloadResolution>? = null
    )

    data class DownloadResolution(
        @JsonProperty("quality") val quality: String? = null,
        @JsonProperty("download_links") val downloadLinks: List<DownloadLink>? = null
    )

    data class DownloadLink(
        @JsonProperty("host") val host: String? = null,
        @JsonProperty("url") val url: String? = null
    )
}
