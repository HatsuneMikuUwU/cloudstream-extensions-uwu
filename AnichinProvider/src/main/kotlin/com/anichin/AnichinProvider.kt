package com.anichin

import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import org.jsoup.Jsoup
import java.net.URI

class AnichinProvider : MainAPI() {
    companion object {
        var context: android.content.Context? = null
    }
    private val cloudflareKiller by lazy { CloudflareKiller() }
    override var mainUrl = "https://anichin.moe"
    override var name = "Anichin"
    override val hasMainPage = true
    override var lang = "id"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.Anime)

    override val mainPage = mainPageOf(
        "anime/?order=update" to "Latest Update",
        "anime/?status=ongoing&order=update" to "Series Ongoing",
        "anime/?status=completed&order=update" to "Series Completed",
        "anime/?status=hiatus&order=update" to "Series Drop/Hiatus",
        "anime/?type=movie&order=update" to "Movie"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get("${mainUrl}/${request.data}&page=$page", interceptor = cloudflareKiller).document
        val home = document.select("div.listupd > article").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(
            list = HomePageList(
                name = request.name,
                list = home,
                isHorizontalImages = false
            ),
            hasNext = true
        )
    }

    private fun Element.toSearchResult(): SearchResponse {
        val title = this.select("div.bsx > a").attr("title").trim()
        val href = fixUrl(this.select("div.bsx > a").attr("href"))
        val posterUrl = fixUrlNull(this.select("div.bsx > a img").attr("src"))
        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchResponse = mutableListOf<SearchResponse>()
        for (i in 1..3) {
            val document = app.get("${mainUrl}/page/$i/?s=$query", interceptor = cloudflareKiller).document
            val results = document.select("div.listupd > article").mapNotNull { it.toSearchResult() }
            if (results.isEmpty()) break
            searchResponse.addAll(results)
        }
        return searchResponse.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(fixUrl(url), interceptor = cloudflareKiller).document
        val title = document.selectFirst("h1.entry-title")?.text()?.trim().toString()
        var poster = document.select("div.ime > img").attr("src")
        val description = document.selectFirst("div.entry-content")?.text()?.trim()
        val type = document.selectFirst(".spe")?.text().orEmpty()
        val tvType = if (type.contains("Movie", true)) TvType.Movie else TvType.TvSeries
        if (poster.isEmpty()) {
            poster = document.selectFirst("meta[property=og:image]")?.attr("content").orEmpty()
        }

        return if (tvType == TvType.TvSeries) {
            val episodes = document.select(".eplister li").map { ep ->
                val link = fixUrl(ep.selectFirst("a")?.attr("href").orEmpty())
                val epTitle = ep.selectFirst(".epl-title")?.text()?.trim().orEmpty()
                val epSub = ep.selectFirst(".epl-sub span")?.text()?.trim().orEmpty()
                val epDate = ep.selectFirst(".epl-date")?.text()?.trim().orEmpty()

                val cleanTitle = epTitle
                    .replace(Regex("Episode\\s*\\d+\\s*Subtitle Indonesia", RegexOption.IGNORE_CASE), "")
                    .replace("Subtitle Indonesia", "")
                    .trim()

                val name = "— $cleanTitle $epSub Indonesia".trim()
                val desc = if (epDate.isNotEmpty()) "Rilis: $epDate" else null

                newEpisode(link) {
                    this.name = name
                    this.posterUrl = fixUrlNull(poster)
                    this.description = desc
                }
            }.reversed()

            newTvSeriesLoadResponse(title, url, TvType.Anime, episodes) {
                this.posterUrl = fixUrlNull(poster)
                this.plot = description
            }
        } else {
            val movieHref = document.selectFirst(".eplister li > a")?.attr("href")?.let { fixUrl(it) } ?: url
            newMovieLoadResponse(title, movieHref, TvType.Movie, movieHref) {
                this.posterUrl = fixUrlNull(poster)
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
        val document = app.get(fixUrl(data), interceptor = cloudflareKiller).document

        val sources = mutableListOf<String>()

        document.select(".mobius option").forEach { server ->
            val encoded = server.attr("value").trim()
            if (encoded.isBlank()) return@forEach
            val html = runCatching { base64Decode(encoded) }.getOrNull() ?: return@forEach
            val src = Jsoup.parse(html).selectFirst("iframe")?.attr("src")
            if (!src.isNullOrBlank()) sources.add(src)
        }

        document.select("#pembed iframe[src]").forEach { sources.add(it.attr("src")) }

        sources.map { normalizeEmbed(fixUrl(it)) }
            .distinct()
            .amap { url -> loadExtractor(url, data, subtitleCallback, callback) }

        return true
    }

    private fun normalizeEmbed(url: String): String {
        val host = runCatching { URI(url).host }.getOrNull().orEmpty()
        if (host.endsWith("anichin-player.web.id")) {
            val id = url.substringAfter("video=", "").substringBefore("&")
            if (id.isNotBlank()) return "https://www.dailymotion.com/embed/video/$id"
        }
        return url
    }
}
