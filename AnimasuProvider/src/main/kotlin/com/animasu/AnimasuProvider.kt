package com.animasu

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addAniListId
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.base64Decode 
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

class AnimasuProvider : MainAPI() {
    override var mainUrl = "https://v1.animasu.app"
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
                (title.contains("-episode")) && !(title.contains("-movie")) -> title.substringBefore("-episode")
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

        val title = document.selectFirst("div.infox h1")?.text()?.replace("Sub Indo", "")?.trim() ?: ""
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
        
        document.select(".mobius > .mirror > option").amap { option ->
            val encodedValue = option.attr("value")
            val qualityLabel = option.text()

            if (encodedValue.isNotEmpty()) {
                try {
                    val iframeHtml = base64Decode(encodedValue)
                    val iframeUrl = Jsoup.parse(iframeHtml).select("iframe").attr("src")

                    if (iframeUrl.isNotEmpty()) {
                        val fixedUrl = fixUrl(iframeUrl)
                        
                        loadExtractor(fixedUrl, "$mainUrl/", subtitleCallback) { link ->
                            val detectedQuality = if (link.quality == Qualities.Unknown.value) {
                                getIndexQuality(qualityLabel)
                            } else {
                                link.quality
                            }

                            if (detectedQuality == 360 || detectedQuality == 480 || detectedQuality == 720 || detectedQuality == 1080) {
                                callback.invoke(
                                    newExtractorLink(
                                        link.source,
                                        link.name,
                                        link.url,
                                        link.referer,
                                        detectedQuality,
                                        link.type,
                                        link.headers,
                                        link.extractorData
                                    )
                                )
                            }
                        }
                    }
                } catch (e: Exception) {
                    // Ignore errors
                }
            }
        }
        return true
    }

    private fun getIndexQuality(str: String?): Int {
        val raw = Regex("(\\d{3,4})[pP]").find(str ?: "")?.groupValues?.getOrNull(1)?.toIntOrNull()
        return when (raw) {
            1080 -> 1080
            720 -> 720
            480 -> 480
            360 -> 360
            else -> Qualities.Unknown.value
        }
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
