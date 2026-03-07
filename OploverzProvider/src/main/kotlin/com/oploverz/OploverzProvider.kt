package com.oploverz

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addAniListId
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.runBlocking
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

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
        fun getType(t: String): TvType {
            return when {
                t.contains("Serial TV", true) -> TvType.Anime
                t.contains("OVA", true) -> TvType.OVA
                t.contains("Movie", true) || t.contains("BD", true) -> TvType.AnimeMovie
                else -> TvType.Anime
            }
        }
        
        var context: android.content.Context? = null

        fun getStatus(t: String?): ShowStatus {
            return when {
                t?.contains("Ongoing", true) == true -> ShowStatus.Ongoing
                else -> ShowStatus.Completed
            }
        }
    }

    override val mainPage = mainPageOf(
        "latestEpisodes" to "Rilis Terbaru",
        "trending" to "Latest Release",
        "recently" to "Recently Added"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        if (page > 1 && request.data != "latestEpisodes") {
            return newHomePageResponse(request.name, emptyList())
        }

        val url = if (page == 1) mainUrl else "$mainUrl/?page=$page"
        val document = app.get(url).document
        val home = ArrayList<SearchResponse>()

        val scriptData = document.select("script:containsData(__sveltekit)").lastOrNull()?.data()
        val jsonMatch = Regex("(?s)data:\\s*(\\[\\{.*?\\}\\]),\\s*form:").find(scriptData ?: "")?.groupValues?.getOrNull(1)

        if (jsonMatch != null) {
            val svelteNodes = AppUtils.tryParseJson<List<SvelteRoot>>(jsonMatch)
            svelteNodes?.forEach { node ->
                when (request.data) {
                    "latestEpisodes" -> {
                        node.data?.latestEpisodes?.data?.forEach { ep ->
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
                    }
                    "trending" -> {
                        node.data?.trending?.data?.forEach { series ->
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
                    }
                    "recently" -> {
                        node.data?.recently?.data?.forEach { series ->
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
                    }
                }
            }
        }

        if (home.isEmpty() && request.data == "latestEpisodes") {
            document.select("a[href^=/series/]:has(img)").forEach {
                val rawHref = it.attr("href")
                val href = fixUrl(rawHref.substringBefore("/episode/"))
                val title = it.selectFirst("p")?.text() ?: it.selectFirst("img")?.attr("alt") ?: return@forEach
                val posterUrl = it.selectFirst("img")?.attr("src")

                val epNum = it.select("p:contains(Episode)").text().filter { char -> char.isDigit() }.toIntOrNull()

                home.add(
                    newAnimeSearchResponse(title, href, TvType.Anime) {
                        this.posterUrl = posterUrl
                        addSub(epNum)
                    }
                )
            }
        }

        return newHomePageResponse(request.name, home.distinctBy { it.url })
    }

    override suspend fun quickSearch(query: String): List<SearchResponse>? = search(query)

    override suspend fun search(query: String): List<SearchResponse>? {
        return try {
            app.get("$backAPI/api/series?q=$query")
                .parsedSafe<SearchAnime>()?.data?.map {
                    newAnimeSearchResponse(
                        it.title ?: "",
                        "$mainUrl/series/${it.slug}",
                        TvType.Anime
                    ) {
                        this.otherName = it.japaneseTitle
                        this.posterUrl = it.poster
                        this.score = Score.from10(it.score)
                        addSub(it.totalEpisodes)
                    }
                }
        } catch (e: Exception) {
            val document = app.get("$mainUrl/search?q=$query").document
            document.select("a[href^=/series/]:has(img)")
                .distinctBy { it.attr("href").substringBefore("/episode/") }
                .mapNotNull {
                    val href = fixUrl(it.attr("href").substringBefore("/episode/"))
                    val title = it.selectFirst("p")?.text() ?: it.selectFirst("img")?.attr("alt") ?: return@mapNotNull null
                    val posterUrl = it.selectFirst("img")?.attr("src")
                    newAnimeSearchResponse(title, href, TvType.Anime) {
                        this.posterUrl = posterUrl
                    }
                }
        }
    }

    private fun Document.selectList(selector: String): String {
        return this.select("ul.grid.list-inside li:contains($selector:)").text().substringAfter(":").trim()
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).body.string().let { Jsoup.parse(it) }

        val title = document.selectFirst("p.text-2xl.font-semibold")?.text() 
            ?: document.selectFirst("div[role=heading]")?.text()?.substringBefore(" Episode") ?: ""
        val poster = document.selectFirst("img.h-full.w-full")?.attr("src") 
            ?: document.select("meta[property=og:image]").attr("content")
        
        val tags = document.selectList("Genre").split(",")
            .filter { it.isNotBlank() }
            .map { it.trim() }

        val year = document.selectList("Tanggal Rilis").let {
            Regex("\\d{4}").find(it)?.groupValues?.get(0)?.toIntOrNull()
        }
        val status = getStatus(document.selectList("Status"))
        val type = getType(document.selectList("Tipe"))
        val description = document.select("div.flex.w-full p").text().trim()

        val episodes = document.select("a[href*=/episode/]").mapNotNull { element ->
            val href = element.attr("href")
            val episodeText = element.select("p:first-child").text().ifBlank { element.text() }
            val epNum = episodeText.filter { it.isDigit() }.toIntOrNull() ?: return@mapNotNull null
            
            newEpisode(url = fixUrl(href)) { 
                this.episode = epNum 
            }
        }.distinctBy { it.data }.reversed()

        val tracker = APIHolder.getTracker(listOf(title), TrackerType.getTypes(type), year, true)

        return newAnimeLoadResponse(title, url, type) {
            engName = title
            posterUrl = tracker?.image ?: poster
            backgroundPosterUrl = tracker?.cover ?: poster
            this.year = year
            addEpisodes(DubStatus.Subbed, episodes)
            showStatus = status
            plot = description
            this.tags = tags
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

        val doc = app.get(data).document

        val scriptData = doc.select("script:containsData(__sveltekit)").lastOrNull()?.data()
        val streamUrlRegex = Regex(""""streamUrl"\s*:\s*(\[\{.*?\}\])""")
        val streamMatches = streamUrlRegex.findAll(scriptData ?: "")
        
        val currentEpisodeStreams = streamMatches.lastOrNull()?.groupValues?.get(1)
        if (currentEpisodeStreams != null) {
            AppUtils.tryParseJson<List<StreamSource>>(currentEpisodeStreams)?.forEach { stream ->
                stream.url?.let { url ->
                    val quality = getQuality(stream.source ?: "")
                    loadFixedExtractor(url, quality, data, subtitleCallback, callback)
                }
            }
        }

        doc.select("div.flex.flex-row.items-start").amap { selector ->
            val qualityText = selector.select("div.w-20 > p").text().trim()
            if (qualityText.isNotBlank()) {
                val quality = getQuality(qualityText)

                selector.select("div.flex.flex-row.flex-wrap > a").amap { server ->
                    val link = server.attr("href")
                    if (link.isNotBlank() && link != "#") {
                        loadFixedExtractor(link, quality, data, subtitleCallback, callback)
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

    data class StreamSource(
        @JsonProperty("source") val source: String? = null,
        @JsonProperty("url") val url: String? = null
    )

    data class SvelteRoot(
        @JsonProperty("type") val type: String? = null,
        @JsonProperty("data") val data: SvelteData? = null
    )

    data class SvelteData(
        @JsonProperty("latestEpisodes") val latestEpisodes: SveltePagination<Data>? = null,
        @JsonProperty("trending") val trending: SveltePagination<Series>? = null,
        @JsonProperty("recently") val recently: SveltePagination<Series>? = null
    )

    data class SveltePagination<T>(
        @JsonProperty("data") val data: List<T>? = emptyList()
    )

    data class Sources(
        @JsonProperty("format") val format: String? = null,
        @JsonProperty("url") val url: ArrayList<String>? = arrayListOf(),
    )

    data class Anime(
        @JsonProperty("data") val data: ArrayList<Data>? = arrayListOf(),
    )

    data class SearchAnime(
        @JsonProperty("data") val data: ArrayList<Series>? = arrayListOf(),
    )

    data class Data(
        @JsonProperty("episodeNumber") val episodeNumber: String? = null,
        @JsonProperty("subbed") val subbed: String? = null,
        @JsonProperty("series") val series: Series? = null,
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
    )
}
