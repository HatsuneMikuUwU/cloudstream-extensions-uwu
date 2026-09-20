package com.Animexin

import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup

class Animexin : MainAPI() {
    override var mainUrl              = "https://animexin.dev"
    override var name                 = "Animexin"
    override val hasMainPage          = true
    override var lang                 = "id"
    override val hasDownloadSupport   = true
    override val supportedTypes       = setOf(TvType.Movie,TvType.Anime)

    private val cloudflareKiller by lazy { CloudflareKiller() }

    private val posterHeaders: Map<String, String>
        get() {
            val base = mutableMapOf(
                "Referer" to "$mainUrl/",
                "User-Agent" to "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36",
                "Accept" to "image/avif,image/webp,image/apng,image/*,*/*;q=0.8",
            )
            runCatching { cloudflareKiller.getCookieHeaders(mainUrl).toMap() }
                .getOrNull()
                ?.forEach { (k, v) -> base[k] = v }
            return base
        }

    private fun Element?.getImageAttr(): String? {
        if (this == null) return null
        val attrs = listOf("data-src", "data-lazy-src", "data-original", "data-cfsrc", "data-lazy", "src")
        for (a in attrs) {
            val v = this.attr(a).trim()
            if (v.isNotEmpty() && !v.startsWith("data:")) return fixUrlNull(v)
        }
        val srcset = this.attr("data-srcset").ifBlank { this.attr("srcset") }.trim()
        if (srcset.isNotEmpty()) {
            val first = srcset.split(",").firstOrNull()?.trim()?.substringBefore(" ")
            if (!first.isNullOrBlank() && !first.startsWith("data:")) return fixUrlNull(first)
        }
        return null
    }

    override val mainPage = mainPageOf(
        "anime/?status=ongoing&order=update" to "Recently Updated",
        "anime/?status=ongoing&order&order=popular" to "Popular",
        "anime/?" to "Donghua",
        "anime/?status=&type=movie&page=" to "Movies",
        "anime/?sub=raw" to "Anime (RAW)",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get("$mainUrl/${request.data}&page=$page", referer = "$mainUrl/", interceptor = cloudflareKiller).documentLarge
        val home     = document.select("div.listupd > article").mapNotNull { it.toSearchResult() }

        return newHomePageResponse(
            list    = HomePageList(
                name               = request.name,
                list               = home,
                isHorizontalImages = false
            ),
            hasNext = true
        )
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val a         = this.selectFirst("div.bsx > a") ?: return null
        val title     = a.attr("title").trim().ifEmpty { this.selectFirst("div.tt h2")?.text()?.trim().orEmpty() }
        val href      = fixUrl(a.attr("href"))
        if (title.isEmpty() || href.isEmpty()) return null
        val posterUrl = a.selectFirst("img").getImageAttr()
        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = posterUrl
            this.posterHeaders = this@Animexin.posterHeaders
        }
    }


    override suspend fun search(query: String,page: Int): SearchResponseList {
        val document = app.get("${mainUrl}/page/$page/?s=$query", referer = "$mainUrl/", interceptor = cloudflareKiller).documentLarge
        val results = document.select("div.listupd > article").mapNotNull { it.toSearchResult() }.toNewSearchResponseList()
        return results
    }

    @Suppress("SuspiciousIndentation")
    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, referer = "$mainUrl/", interceptor = cloudflareKiller).documentLarge
        val title = document.selectFirst("h1.entry-title")?.text()?.trim().orEmpty()
        val href=document.selectFirst("div.eplister > ul > li a")?.attr("href") ?:""
        val poster = document.selectFirst("div.bigcontent div.thumb img").getImageAttr()
            ?: document.selectFirst("div.thumb img").getImageAttr()
            ?: document.selectFirst("meta[property=og:image]")?.attr("content")?.trim()?.takeIf { it.isNotEmpty() }
        val description = document.selectFirst("div.entry-content")?.text()?.trim()
        val type=document.selectFirst(".spe")?.text().orEmpty()
        val tvtag=if (type.contains("Movie")) TvType.Movie else TvType.TvSeries
        return if (tvtag == TvType.TvSeries) {
            val episodeRegex = Regex("(\\d+)")

            val episodes = document.select("div.eplister > ul > li").map { info ->
                val href1 = info.select("a").attr("href")
                val posterr = info.selectFirst("a img").getImageAttr()

                val epText = info.selectFirst("div.epl-num")?.text().orEmpty()
                val epnum = episodeRegex.find(epText)?.groupValues?.get(1)?.toIntOrNull()

                newEpisode(href1) {
                    this.episode = epnum
                    this.name = epnum?.let { "Episode $it" } ?: epText
                    this.posterUrl = fixUrlNull(posterr)
                }
            }

            newTvSeriesLoadResponse(title, url, TvType.Anime, episodes.reversed()) {
                this.posterUrl = fixUrlNull(poster)
                this.posterHeaders = this@Animexin.posterHeaders
                this.plot = description
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, href) {
                this.posterUrl = fixUrlNull(poster)
                this.posterHeaders = this@Animexin.posterHeaders
                this.plot = description
            }
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val document = app.get(data, referer = "$mainUrl/", interceptor = cloudflareKiller).documentLarge
        document.select(".mobius option").forEach { server->
            val base64 = server.attr("value")
            val decoded=base64Decode(base64)
            val doc = Jsoup.parse(decoded)
            val href=doc.select("iframe").attr("src")
            val url=Http(href)
            loadExtractor(url,subtitleCallback, callback)
        }
        return true
    }
}
