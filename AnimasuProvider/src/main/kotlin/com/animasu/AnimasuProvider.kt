package com.animasu

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addAniListId
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

class AnimasuProvider : MainAPI() {
    override var mainUrl = "https://v1.animasu.top"
    override var name = "Animasu"
    override val hasMainPage = true
    override var lang = "id"
    override val hasDownloadSupport = true

    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.OVA
    )

    companion object {
        fun getType(t: String?): TvType {
            if (t == null) return TvType.Anime
            return when {
                t.contains("Tv", true) -> TvType.Anime
                t.contains("Movie", true) -> TvType.AnimeMovie
                t.contains("OVA", true) || t.contains("Special", true) -> TvType.OVA
                else -> TvType.Anime
            }
        }

        fun getStatus(t: String?): ShowStatus {
            if (t == null) return ShowStatus.Completed
            return when {
                t.contains("Sedang Tayang", true) -> ShowStatus.Ongoing
                else -> ShowStatus.Completed
            }
        }
    }

    override val mainPage = mainPageOf(
        "urutan=update" to "Just Updated",
        "status=&tipe=&urutan=publikasi" to "Just Added",
        "status=&tipe=&urutan=populer" to "Most Popular",
        "status=&tipe=&urutan=rating" to "Best Rating",
        "status=&tipe=Movie&urutan=update" to "Latest Movies",
        "status=&tipe=Movie&urutan=populer" to "Most Popular Movies",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get("$mainUrl/pencarian/?${request.data}&halaman=$page").document
        val home = document.select("div.listupd div.bs").map {
            it.toSearchResult()
        }
        return newHomePageResponse(request.name, home)
    }

    private fun getProperAnimeLink(uri: String): String {
        return if (uri.contains("/anime/")) {
            uri
        } else {
            var title = uri.substringAfter("$mainUrl/")
            title = when {
                (title.contains("-episode")) && !(title.contains("-movie")) -> title.substringBefore(
                    "-episode"
                )
                (title.contains("-movie")) -> title.substringBefore("-movie")
                else -> title
            }
            "$mainUrl/anime/$title"
        }
    }

    private fun Element.toSearchResult(): AnimeSearchResponse {
        val href = getProperAnimeLink(fixUrlNull(this.selectFirst("a")?.attr("href")).toString())
        val title = this.select("div.tt").text().trim()
        val posterUrl = fixUrlNull(this.selectFirst("img")?.getImageAttr())
        val epNum = this.selectFirst("span.epx")?.text()?.filter { it.isDigit() }?.toIntOrNull()
        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = posterUrl
            addSub(epNum)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return app.get("$mainUrl/?s=$query").document.select("div.listupd div.bs").map {
            it.toSearchResult()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val title = document.selectFirst("div.infox h1")?.text().toString().replace("Sub Indo", "").trim()
        val poster = document.selectFirst("div.bigcontent img")?.getImageAttr()

        val table = document.selectFirst("div.infox div.spe")
        val type = getType(table?.selectFirst("span:contains(Jenis:)")?.ownText())
        val year = table?.selectFirst("span:contains(Rilis:)")?.ownText()?.substringAfterLast(",")?.trim()?.toIntOrNull()
        val status = table?.selectFirst("span:contains(Status:) font")?.text()
        val trailer = document.selectFirst("div.trailer iframe")?.attr("src")
        
        val episodes = document.select("ul#daftarepisode > li").map {
            val link = fixUrl(it.selectFirst("a")!!.attr("href"))
            val name = it.selectFirst("a")?.text() ?: ""
            val episode = Regex("Episode\\s?(\\d+)").find(name)?.groupValues?.getOrNull(1)?.toIntOrNull()
            newEpisode(link) { this.episode = episode }
        }.reversed()

        val tracker = APIHolder.getTracker(listOf(title), TrackerType.getTypes(type), year, true)

        return newAnimeLoadResponse(title, url, type) {
            posterUrl = tracker?.image ?: poster
            backgroundPosterUrl = tracker?.cover
            this.year = year
            addEpisodes(DubStatus.Subbed, episodes)
            showStatus = getStatus(status)
            plot = document.select("div.sinopsis p").text()
            this.tags = table?.select("span:contains(Genre:) a")?.map { it.text() }
            addTrailer(trailer)
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
        val document = app.get(data).document
        
        document.select(".mobius select.mirror option").forEach { option ->
            val rawValue = option.attr("value")
            if (rawValue.isNotEmpty()) {
                val decodedHtml = base64Decode(rawValue)
                val iframeUrl = Jsoup.parse(decodedHtml).select("iframe").attr("src")
                val qualityText = option.text()

                if (iframeUrl.isNotEmpty()) {
                    loadFixedExtractor(
                        fixUrl(iframeUrl), 
                        qualityText, 
                        data, 
                        subtitleCallback, 
                        callback
                    )
                }
            }
        }
        return true
    }

    private suspend fun loadFixedExtractor(
        url: String,
        quality: String?,
        referer: String? = null,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        // 1. Kumpulkan semua link yang didapat ke dalam sebuah list terlebih dahulu
        val extractedLinks = mutableListOf<ExtractorLink>()
        loadExtractor(url, referer, subtitleCallback) { link ->
            extractedLinks.add(link)
        }
        
        val extractedQuality = getIndexQuality(quality)
        
        // 2. Proses link di luar callback agar fungsi 'suspend' bisa dipanggil
        extractedLinks.forEach { link ->
            val newLink = newExtractorLink(
                source = link.source,
                name = link.name,
                url = link.url
            ) {
                this.referer = link.referer
                this.quality = if (link.quality == Qualities.Unknown.value) extractedQuality else link.quality
                this.isM3u8 = link.isM3u8
                this.headers = link.headers
                this.extractorData = link.extractorData
            }
            
            callback.invoke(newLink)
        }
    }

    private fun getIndexQuality(str: String?): Int {
        return Regex("(\\d{3,4})").find(str ?: "")?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: Qualities.Unknown.value
    }

    private fun Element.getImageAttr(): String? {
        return when {
            this.hasAttr("data-src") -> this.attr("abs:data-src")
            this.hasAttr("data-lazy-src") -> this.attr("abs:data-lazy-src")
            this.hasAttr("srcset") -> this.attr("abs:srcset").substringBefore(" ")
            else -> this.attr("abs:src")
        }
    }
}
