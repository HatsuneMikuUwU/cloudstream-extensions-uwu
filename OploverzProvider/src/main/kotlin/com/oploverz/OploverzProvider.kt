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
                t?.contains("Berlangsung", true) == true -> ShowStatus.Ongoing
                else -> ShowStatus.Completed
            }
        }
    }

    // Menggunakan URL yang direquest untuk halaman beranda (Daftar Anime)
    override val mainPage = mainPageOf(
        "releaseDate-desc" to "Daftar Anime (Rilis Terbaru)",
        "score-desc" to "Daftar Anime (Rating Tertinggi)",
        "title-asc" to "Daftar Anime (A-Z)"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val home = mutableListOf<SearchResponse>()
        var hasNext = false

        try {
            // PRIORITAS 1: Gunakan API Backend Oploverz agar DAPAT GAMBAR POSTER dan BISA DI-SCROLL
            val apiResponse = app.get("$backAPI/api/series?page=$page&sort_by=${request.data}").parsedSafe<SearchAnime>()
            
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
            
            // Cek apakah masih ada halaman selanjutnya (Pagination Check)
            val currentPage = apiResponse?.meta?.currentPage ?: 1
            val lastPage = apiResponse?.meta?.lastPage ?: 1
            hasNext = currentPage < lastPage
            
        } catch (e: Exception) {
            // PRIORITAS 2 (FALLBACK): Jika API gagal/diblokir, parse HTML teks A-Z yang kamu berikan
            // Karena isinya langsung ribuan teks A-Z, kita stop di page 1 agar tidak Infinite Loop
            if (page == 1) {
                val document = app.get("$mainUrl/series?sort_by=${request.data}").document
                document.select("div[id^=section-] a").forEach { a ->
                    val href = a.attr("href")
                    if (href.contains("/series/") || href.contains("/movie/")) {
                        val title = a.text().trim()
                        if (title.isNotBlank()) {
                            home.add(newAnimeSearchResponse(title, fixUrl(href), TvType.Anime))
                        }
                    }
                }
            }
            hasNext = false // Menghentikan looping CloudStream secara paksa
        }

        return newHomePageResponse(request.name, home.distinctBy { it.url }, hasNext)
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
            
            newEpisode(fixUrl(href)) { 
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
        val scriptData = doc.select("script:containsData(__sveltekit)").lastOrNull()?.data() ?: ""
        
        // 1. Nonton Online (Streaming Links)
        val streamRegex = Regex("""source\s*:\s*["']([^"']+)["']\s*,\s*url\s*:\s*["']([^"']+)["']""")
        streamRegex.findAll(scriptData).forEach { match ->
            val source = match.groupValues[1]
            val streamUrl = match.groupValues[2]
            val quality = getQuality(source)
            loadFixedExtractor(streamUrl, quality, data, subtitleCallback, callback)
        }

        // 2. Tautan Download Server (GD, Akira, dll)
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

    // Penambahan class Meta untuk sistem Pagination
    data class SearchAnime(
        @JsonProperty("data") val data: ArrayList<Series>? = arrayListOf(),
        @JsonProperty("meta") val meta: Meta? = null
    )

    data class Meta(
        @JsonProperty("currentPage") val currentPage: Int? = null,
        @JsonProperty("lastPage") val lastPage: Int? = null
    )

    data class Anime(
        @JsonProperty("data") val data: ArrayList<Data>? = arrayListOf()
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
