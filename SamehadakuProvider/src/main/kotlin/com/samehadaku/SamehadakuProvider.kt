package com.samehadaku

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addAniListId
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.runBlocking
import org.jsoup.nodes.Element

class SamehadakuProvider : MainAPI() {
    override var mainUrl = "https://v1.samehadaku.how"
    override var name = "Samehadaku"
    override val hasMainPage = true
    override var lang = "id"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.OVA
    )

    companion object {
        fun getType(t: String): TvType = when {
            t.contains("OVA", true) || t.contains("Special", true) -> TvType.OVA
            t.contains("Movie", true) -> TvType.AnimeMovie
            else -> TvType.Anime
        }
        fun getStatus(t: String): ShowStatus = when (t) {
            "Completed" -> ShowStatus.Completed
            "Ongoing" -> ShowStatus.Ongoing
            else -> ShowStatus.Completed
        }
    }

    override val mainPage = mainPageOf(
        "anime-terbaru/page/%d/" to "New Episodes",
        "daftar-anime-2/page/%d/?status=Currently+Airing&order=latest" to "Ongoing Anime",
        "daftar-anime-2/page/%d/?status=Finished+Airing&order=latest" to "Complete Anime",
        "daftar-anime-2/page/%d/?order=popular" to "Most Popular",
        "daftar-anime-2/page/%d/?type=Movie&order=latest" to "Movies",
    )
    
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get("$mainUrl/${request.data.format(page)}").document
        
        val items = when (request.name) {
            "New Episodes" -> document.select("li[itemtype='http://schema.org/CreativeWork']")
            "Ongoing Anime", "Complete Anime", "Most Popular" -> document.select("div.animepost")
            else -> document.select("article.animpost")
        }

        val homeList = items.mapNotNull {
            if (request.name == "New Episodes") it.toLatestAnimeResult()
            else it.toSearchResult()
        }

        val isLandscape = request.name == "New Episodes"
        
        return newHomePageResponse(
            listOf(HomePageList(request.name, homeList, isHorizontalImages = isLandscape)),
            hasNext = homeList.isNotEmpty()
        )
    }

    private fun Element.toSearchResult(): AnimeSearchResponse? {
        val a = this.selectFirst("div.animepost a, div.animpost a") ?: return null
        val title = a.selectFirst("div.title h2, div.tt h4")?.text()?.trim() ?: a.attr("title") ?: return null
        val href = fixUrlNull(a.attr("href")) ?: return null
        val posterUrl = fixUrlNull(this.selectFirst("div.content-thumb img, div.limit img")?.attr("src"))
        val statusText = a.selectFirst("div.data > div.type, div.type")?.text()?.trim() ?: ""

        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = posterUrl
            addDubStatus(statusText)
        }
    }

    private fun Element.toLatestAnimeResult(): AnimeSearchResponse? {
        val a = this.selectFirst("div.thumb a") ?: return null
        val title = this.selectFirst("h2.entry-title a")?.text()?.trim() ?: a.attr("title") ?: return null
        val href = fixUrlNull(a.attr("href")) ?: return null
        val posterUrl = fixUrlNull(a.selectFirst("img")?.attr("src"))
        val epNum = this.selectFirst("div.dtla author")?.text()?.toIntOrNull()

        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = posterUrl
            addSub(epNum)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/?s=$query").document
        return document.select("div.animepost, article.animpost").mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse? {
        val fixUrl = if (url.contains("/anime/")) url
        else app.get(url).document.selectFirst("div.nvs.nvsc a")?.attr("href") ?: url
        
        val document = app.get(fixUrl).document
        
        val rawTitle = document.selectFirst("h1.entry-title")?.text() ?: return null
        val title = rawTitle.replace(Regex("(?i)(Nonton|Anime|Subtitle\\s+Indonesia|Sub\\s+Indo|Lengkap|Batch)"), "").trim()
        
        val poster = document.selectFirst("div.thumb > img")?.attr("src")
        val tags = document.select("div.genre-info > a").map { it.text() }
        
        val year = Regex("\\d, (\\d*)").find(
            document.select("div.spe > span:contains(Rilis)").text()
        )?.groupValues?.getOrNull(1)?.toIntOrNull()
        
        val statusStr = document.selectFirst("div.spe > span:contains(Status)")?.ownText()?.replace(":", "")?.trim() ?: "Completed"
        val status = getStatus(statusStr)
        val typeStr = document.selectFirst("div.spe > span:contains(Type)")?.ownText()?.replace(":", "")?.trim()?.lowercase() ?: "tv"
        val type = getType(typeStr)
        
        val rating = document.selectFirst("span.ratingValue, div.rating strong")?.text()?.replace("Rating", "")?.trim()?.toDoubleOrNull()       
        val description = document.select("div.desc p, div.entry-content p").text().trim()
        val trailer = document.selectFirst("div.trailer-anime iframe")?.attr("src")

        val episodes = document.select("div.lstepsiode.listeps ul li").mapNotNull {
            val header = it.selectFirst("span.lchx > a") ?: return@mapNotNull null
            val episode = Regex("Episode\\s?(\\d+)").find(header.text())?.groupValues?.getOrNull(1)?.toIntOrNull()
            val link = fixUrl(header.attr("href"))
            newEpisode(link) { this.episode = episode }
        }.reversed()

        val recommendations = document.select("aside#sidebar ul li, div.relat animepost").mapNotNull { it.toSearchResult() }

        val tracker = APIHolder.getTracker(listOf(title), TrackerType.getTypes(type), year, true)

        return newAnimeLoadResponse(title, url, type) {
            engName = title
            posterUrl = tracker?.image ?: poster
            backgroundPosterUrl = tracker?.cover
            this.year = year
            addEpisodes(DubStatus.Subbed, episodes)
            showStatus = status
            this.score = rating?.let { Score.from10(it) }
            plot = description
            addTrailer(trailer)
            this.tags = tags
            this.recommendations = recommendations
            
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

        document.select("div#downloadb li").amap { el ->
            el.select("a").amap {
                loadFixedExtractor(
                    fixUrl(it.attr("href")),
                    el.select("strong").text(),
                    "$mainUrl/",
                    subtitleCallback,
                    callback
                )
            }
        }
        return true
    }

    private suspend fun loadFixedExtractor(
        url: String,
        name: String,
        referer: String? = null,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        loadExtractor(url, referer, subtitleCallback) { link ->
            runBlocking {
                callback.invoke(
                    newExtractorLink(link.name, link.name, link.url, link.type) {
                        this.referer = link.referer
                        this.quality = name.fixQuality()
                        this.headers = link.headers
                        this.extractorData = link.extractorData
                    }
                )
            }
        }
    }

    private fun String.fixQuality(): Int = when (this.uppercase()) {
        "4K" -> Qualities.P2160.value
        "FULLHD" -> Qualities.P1080.value
        "MP4HD" -> Qualities.P720.value
        else -> this.filter { it.isDigit() }.toIntOrNull() ?: Qualities.Unknown.value
    }
}
