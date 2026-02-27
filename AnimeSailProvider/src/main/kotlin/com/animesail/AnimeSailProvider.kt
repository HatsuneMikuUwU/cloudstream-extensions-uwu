package com.animesail

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addAniListId
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.mvvm.safeApiCall
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.*
import com.lagradost.nicehttp.NiceResponse
import kotlinx.coroutines.runBlocking
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

class AnimeSailProvider : MainAPI() {

    override var mainUrl = "https://154.26.137.28"
    override var name = "AnimeSail"
    override val hasMainPage = true
    override var lang = "id"
    override val hasDownloadSupport = true

    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.OVA
    )

    override val mainPage = mainPageOf(
        "$mainUrl/rilisan-anime-terbaru/page/" to "Ongoing Anime",
        "$mainUrl/rilisan-donghua-terbaru/page/" to "Ongoing Donghua",
        "$mainUrl/movie-terbaru/page/" to "Movie"
    )

    private val cf by lazy { CloudflareKiller() }

    private fun NiceResponse.isCloudflare(): Boolean {
        val t = this.text.lowercase()
        return t.contains("checking your browser") ||
                t.contains("just a moment") ||
                t.contains("cf-browser-verification") ||
                (this.code == 403 && t.contains("cloudflare"))
    }

    private suspend fun fetch(url: String, ref: String? = null): NiceResponse {
        val normal = runCatching {
            app.get(url, referer = ref ?: mainUrl)
        }.getOrNull()

        if (normal != null && !normal.isCloudflare()) return normal

        return app.get(
            url,
            referer = ref ?: mainUrl,
            interceptor = cf
        )
    }

    companion object {
        fun getType(t: String): TvType {
            return when {
                t.contains("ova", true) || t.contains("special", true) -> TvType.OVA
                t.contains("movie", true) -> TvType.AnimeMovie
                else -> TvType.Anime
            }
        }

        fun getStatus(t: String): ShowStatus {
            return when (t.lowercase()) {
                "completed" -> ShowStatus.Completed
                "ongoing" -> ShowStatus.Ongoing
                else -> ShowStatus.Completed
            }
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        runCatching { app.get(mainUrl, interceptor = cf) }

        val document = fetch(request.data + page).document
        val home = document.select("article").map { it.toSearchResult() }
        
        return newHomePageResponse(request.name, home, hasNext = home.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = fetch("$mainUrl/?s=$query").document
        return document.select("div.listupd article").map { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = fetch(url).document

        val title = document.selectFirst("h1.entry-title")
            ?.text()
            ?.replace("Subtitle Indonesia", "")
            ?.trim()
            ?: ""

        val poster = document.selectFirst("div.entry-content > img")?.attr("src")

        val type = getType(
            document.select("tbody th:contains(Tipe)").next().text()
        )

        val year = document.select("tbody th:contains(Dirilis)")
            .next().text().trim().toIntOrNull()

        val episodes = document.select("ul.daftar > li").map {
            val link = fixUrl(it.select("a").attr("href"))
            val name = it.select("a").text()

            val ep = Regex("Episode\\s?(\\d+)")
                .find(name)
                ?.groupValues?.getOrNull(1)
                ?.toIntOrNull()

            newEpisode(link) { episode = ep }
        }.reversed()

        val tracker = APIHolder.getTracker(
            listOf(title),
            TrackerType.getTypes(type),
            year,
            true
        )

        return newAnimeLoadResponse(title, url, type) {
            posterUrl = tracker?.image ?: poster
            backgroundPosterUrl = tracker?.cover
            this.year = year
            addEpisodes(DubStatus.Subbed, episodes)

            showStatus = getStatus(
                document.select("tbody th:contains(Status)")
                    .next().text().trim()
            )

            plot = document.selectFirst("div.entry-content > p")?.text()

            tags = document.select("tbody th:contains(Genre)")
                .next()
                .select("a")
                .map { it.text() }

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

        val document = fetch(data).document

        document.select(".mobius > .mirror > option").amap {
            safeApiCall {
                val iframe = fixUrl(
                    Jsoup.parse(base64Decode(it.attr("data-em")))
                        .select("iframe")
                        .attr("src")
                )

                val quality = getIndexQuality(it.text())

                when {
                    iframe.startsWith("$mainUrl/utils/player/kodir2") -> {
                        fetch(iframe, ref = data).text.substringAfter("= `").substringBefore("`;").let { html ->
                            val link = Jsoup.parse(html).select("source").last()?.attr("src")
                            if (link != null) {
                                callback.invoke(
                                    newExtractorLink(
                                        source = name,
                                        name = name,
                                        url = link,
                                        INFER_TYPE
                                    ) {
                                        referer = mainUrl
                                        this.quality = quality
                                    }
                                )
                            }
                        }
                    }
                    iframe.startsWith("$mainUrl/utils/player/arch/") ||
                    iframe.startsWith("$mainUrl/utils/player/race/") ||
                    iframe.startsWith("$mainUrl/utils/player/hexupload/") ||
                    iframe.startsWith("$mainUrl/utils/player/pomf/") -> {
                        fetch(iframe, ref = data).document.select("source").attr("src").let { link ->
                            val source = when {
                                iframe.contains("/arch/") -> "Arch"
                                iframe.contains("/race/") -> "Race"
                                iframe.contains("/hexupload/") -> "Hexupload"
                                iframe.contains("/pomf/") -> "Pomf"
                                else -> name
                            }
                            callback.invoke(
                                newExtractorLink(
                                    source = source,
                                    name = source,
                                    url = link,
                                    INFER_TYPE
                                ) {
                                    referer = mainUrl
                                    this.quality = quality
                                }
                            )
                        }
                    }
                    iframe.startsWith("https://aghanim.xyz/tools/redirect/") -> {
                        val link = "https://rasa-cintaku-semakin-berantai.xyz/v/${iframe.substringAfter("id=").substringBefore("&token")}"
                        loadFixedExtractor(link, quality, mainUrl, subtitleCallback, callback)
                    }
                    iframe.startsWith("$mainUrl/utils/player/framezilla/") ||
                    iframe.startsWith("https://uservideo.xyz") -> {
                        fetch(iframe, ref = data).document.select("iframe").attr("src").let { link ->
                            loadFixedExtractor(fixUrl(link), quality, mainUrl, subtitleCallback, callback)
                        }
                    }
                    else -> {
                        loadFixedExtractor(iframe, quality, mainUrl, subtitleCallback, callback)
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
                        source = link.name,
                        name = link.name,
                        url = link.url,
                        INFER_TYPE
                    ) {
                        this.referer = mainUrl
                        this.quality = if (link.type == ExtractorLinkType.M3U8) link.quality else quality ?: Qualities.Unknown.value
                        this.type = link.type
                        this.headers = link.headers
                        this.extractorData = link.extractorData
                    }
                )
            }
        }
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
        val title = this.select(".tt > h2").text().trim()
        val posterUrl = fixUrlNull(this.selectFirst("div.limit img")?.attr("src"))
        val epNum = this.selectFirst(".tt > h2")?.text()?.let {
            Regex("Episode\\s?(\\d+)").find(it)?.groupValues?.getOrNull(1)?.toIntOrNull()
        }
        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = posterUrl
            addSub(epNum)
        }
    }

    private fun getIndexQuality(str: String): Int {
        return Regex("(\\d{3,4})[pP]")
            .find(str)
            ?.groupValues?.getOrNull(1)
            ?.toIntOrNull()
            ?: Qualities.Unknown.value
    }
}
