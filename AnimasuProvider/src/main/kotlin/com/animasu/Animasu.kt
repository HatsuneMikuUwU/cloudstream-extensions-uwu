package com.animasu

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addAniListId
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.runBlocking
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

class Animasu : MainAPI() {
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
                t.contains("Ongoing", true) -> ShowStatus.Ongoing
                else -> ShowStatus.Completed
            }
        }
    }

    override val mainPage = mainPageOf(
        "status=ongoing&tipe=&urutan=update" to "Ongoing",
        "status=&tipe=&urutan=populer" to "Most Popular",
        "status=&tipe=&urutan=rating" to "Highest Rating",
        "genre%5B%5D=donghua&status=&tipe=&urutan=update" to "Donghua",
        "status=completed&tipe=&urutan=publikasi" to "Completed",
        "status=&tipe=Movie&urutan=update" to "Latest Movies",
        "status=&tipe=Movie&urutan=populer" to "Most Popular Movies"
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

        val title =
            document.selectFirst("div.infox h1")?.text().toString().replace("Sub Indo", "").trim()
        val poster = document.selectFirst("div.bigcontent img")?.getImageAttr()

        val table = document.selectFirst("div.infox div.spe")
        val type = getType(table?.selectFirst("span:contains(Jenis:)")?.ownText())
        val year =
            table?.selectFirst("span:contains(Rilis:)")?.ownText()?.substringAfterLast(",")?.trim()
                ?.toIntOrNull()
        val status = table?.selectFirst("span:contains(Status:) font")?.text()
        val trailer = document.selectFirst("div.trailer iframe")?.attr("src")

        val episodes = document.select("ul#daftarepisode > li").map {
            val link = fixUrl(it.selectFirst("a")!!.attr("href"))
            val name = it.selectFirst("a")?.text() ?: ""
            val episode =
                Regex("Episode\\s?(\\d+)").find(name)?.groupValues?.getOrNull(1)?.toIntOrNull()
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

        document.select(".mobius .mirror option").mapNotNull {
            val value = it.attr("value")
            if (value.isBlank()) return@mapNotNull null

            val iframeSrc = Jsoup.parse(base64Decode(value)).select("iframe").attr("src")
            if (iframeSrc.isBlank()) return@mapNotNull null

            fixUrl(applyPlayerUrlFix(iframeSrc)) to it.text()
        }.amap { (iframe, quality) ->
            loadFixedExtractor(iframe, quality, "$mainUrl/", subtitleCallback, callback)
        }

        return true
    }

    private fun applyPlayerUrlFix(url: String): String {
        var fixed = url

        if (fixed.contains("uservideo.nanime.in"))
            fixed = fixed.replace("uservideo.nanime.in", "uservideo.xyz")
        else if (fixed.contains("uservideo.in"))
            fixed = fixed.replace("uservideo.in", "uservideo.xyz")

        if (fixed.contains("short.ink"))
            fixed = fixed.replace("short.ink", "short.icu")

        if (fixed.contains("nanime.yt"))
            fixed = fixed.replace("nanime.yt", "nanime.in")

        if (fixed.contains("uservideo.xyz") && !fixed.contains("new.uservideo.xyz")) {
            fixed = fixed.replace("uservideo.xyz", "new.uservideo.xyz")
            fixed = fixed.replace("?embed=true", "/embed/?")
        }

        return fixed
    }

    private suspend fun loadFixedExtractor(
        url: String,
        quality: String?,
        referer: String? = null,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        loadExtractor(url, referer, subtitleCallback) { link ->
            runBlocking {
                if (link.type == ExtractorLinkType.M3U8) {
                    val streams = M3u8Helper.generateM3u8(
                        link.name,
                        link.url,
                        link.referer,
                        masterHeaders = link.headers
                    )
                    if (streams.isNotEmpty()) {
                        streams.forEach { stream ->
                            callback.invoke(
                                newExtractorLink(
                                    link.name,
                                    link.name,
                                    stream.url,
                                    ExtractorLinkType.M3U8
                                ) {
                                    this.referer = link.referer
                                    this.quality = stream.quality
                                    this.headers = link.headers
                                    this.extractorData = link.extractorData
                                }
                            )
                        }
                    } else {
                        val parsedQuality = getIndexQuality(quality)
                        callback.invoke(
                            newExtractorLink(
                                link.name,
                                link.name,
                                link.url,
                                link.type
                            ) {
                                this.referer = link.referer
                                this.quality = if (parsedQuality != Qualities.Unknown.value)
                                    parsedQuality
                                else
                                    link.quality
                                this.headers = link.headers
                                this.extractorData = link.extractorData
                            }
                        )
                    }
                } else {
                    val parsedQuality = getIndexQuality(quality)
                    callback.invoke(
                        newExtractorLink(
                            link.name,
                            link.name,
                            link.url,
                            link.type
                        ) {
                            this.referer = link.referer
                            this.quality = if (parsedQuality != Qualities.Unknown.value)
                                parsedQuality
                            else
                                link.quality
                            this.headers = link.headers
                            this.extractorData = link.extractorData
                        }
                    )
                }
            }
        }
    }

    private fun getIndexQuality(str: String?): Int {
        return Regex("(\\d{3,4})[pP]").find(str ?: "")?.groupValues?.getOrNull(1)?.toIntOrNull()
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
