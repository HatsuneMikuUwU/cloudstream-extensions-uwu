package com.donghub

import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup

class DonghubProvider : MainAPI() {
    companion object {
        var context: android.content.Context? = null
    }
    override var mainUrl = "https://donghive.vip"
    override var name = "Donghub"
    override val hasMainPage = true
    override var lang = "id"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.Anime)

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

    override val mainPage = mainPageOf(
        "anime/?order=update" to "Latest Releases",
        "anime/?status=ongoing&order=update" to "Series Ongoing",
        "anime/?status=completed&order=update" to "Series Completed",
        "anime/?type=movie&order=update" to "Movie"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get("$mainUrl/${request.data}&page=$page", referer = "$mainUrl/", interceptor = cloudflareKiller).document
        val items = document.select("div.listupd > article").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(
            HomePageList(request.name, items, isHorizontalImages = false),
            hasNext = true
        )
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val a = selectFirst("div.bsx > a") ?: return null
        val title = a.attr("title").trim().ifEmpty { selectFirst("div.tt h2")?.text()?.trim().orEmpty() }
        val href = fixUrl(a.attr("href"))
        if (title.isEmpty() || href.isEmpty()) return null
        val poster = a.selectFirst("img").getImageAttr()
        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = poster
            this.posterHeaders = this@DonghubProvider.posterHeaders
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val list = mutableListOf<SearchResponse>()
        for (i in 1..3) {
            val document = app.get("$mainUrl/page/$i/?s=$query", referer = "$mainUrl/", interceptor = cloudflareKiller).document
            val result = document.select("div.listupd > article").mapNotNull { it.toSearchResult() }
            if (result.isEmpty()) break
            list.addAll(result)
        }
        return list.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, referer = "$mainUrl/", interceptor = cloudflareKiller).document
        val title = document.selectFirst("h1.entry-title")?.text().orEmpty()
        val description = document.selectFirst("div.entry-content")?.text()?.trim()
        val typeText = document.selectFirst(".spe")?.text().orEmpty()
        val isMovie = typeText.contains("Movie", true)

        val poster = document.selectFirst("div.bigcontent div.thumb img").getImageAttr()
            ?: document.selectFirst("div.thumb img").getImageAttr()
            ?: document.selectFirst("div.ime > img").getImageAttr()
            ?: document.selectFirst("meta[property=og:image]")?.attr("content")?.trim()?.takeIf { it.isNotEmpty() }

        val epBlocks =
            document.select(".eplister li").ifEmpty {
                document.select("div.list-episode .episode-item")
            }.ifEmpty {
                document.select("#episodes a")
            }

        return if (!isMovie) {
            val episodes = epBlocks.map { ep ->
                val link = fixUrl(ep.selectFirst("a")?.attr("href").orEmpty())
                val epTitle = ep.selectFirst(".epl-title")?.text() ?: ep.text()
                newEpisode(link) {
                    this.name = epTitle.trim()
                    this.posterUrl = fixUrlNull(poster)
                }
            }.reversed()

            newTvSeriesLoadResponse(title, url, TvType.Anime, episodes) {
                this.posterUrl = fixUrlNull(poster)
                this.posterHeaders = this@DonghubProvider.posterHeaders
                this.plot = description
            }
        } else {
            val movieLink = document.selectFirst(".eplister li > a")
                ?.attr("href")
                ?.let { fixUrl(it) } ?: url

            newMovieLoadResponse(title, movieLink, TvType.Movie, movieLink) {
                this.posterUrl = fixUrlNull(poster)
                this.posterHeaders = this@DonghubProvider.posterHeaders
                this.plot = description
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data, referer = "$mainUrl/", interceptor = cloudflareKiller).document
        document.select(".mobius option").forEach { item ->
            val base64 = item.attr("value")
            if (base64.isNotBlank()) {
                val decoded = base64Decode(base64)
                val doc = Jsoup.parse(decoded)
                val iframe = doc.select("iframe").attr("src")
                loadExtractor(fixUrl(iframe), subtitleCallback, callback)
            }
        }
        return true
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
}
