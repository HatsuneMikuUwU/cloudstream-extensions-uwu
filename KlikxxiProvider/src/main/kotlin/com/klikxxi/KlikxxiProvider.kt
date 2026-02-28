package com.klikxxi

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addScore
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.httpsify
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element
import java.net.URI


class KlikxxiProvider : MainAPI() {
    companion object {
        var context: android.content.Context? = null
    }
    override var mainUrl = "https://klikxxi.me"
    override var name = "KlikXXI"
    override val hasMainPage = true
    override var lang = "id"

    override val supportedTypes =
        setOf(TvType.Movie, TvType.TvSeries, TvType.Anime, TvType.AsianDrama)
    

    override val mainPage = mainPageOf(
        "?s=&search=advanced&post_type=movie&index=&orderby=&genre=&movieyear=&country=&quality=&paged=%d" to "Latest Movie",
        "tv/page/%d/" to "TV Series",
        "category/action/page/%d/" to "Action",
        "category/adventure/page/%d/" to "Adventure",  
        "category/animation/page/%d/" to "Animation",  
        "category/comedy/page/%d/" to "Comedy",  
        "category/crime/page/%d/" to "Crime",  
        "category/drama/page/%d/" to "Drama",  
        "category/family/page/%d/" to "Family",  
        "category/fantasy/page/%d/" to "Fantasy",  
        "category/history/page/%d/" to "History",  
        "category/horror/page/%d/" to "Horror",  
        "category/music/page/%d/" to "Music",  
        "category/mystery/page/%d/" to "Mystery",  
        "category/romance/page/%d/" to "Romance",  
        "category/science-fiction/page/%d/" to "Sci-Fi",  
        "category/thriller/page/%d/" to "Thriller",  
        "category/war/page/%d/" to "War",  
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
    val url = if (page == 1) {
        "$mainUrl/${request.data.replace("page/%d/", "")}"
    } else {
        "$mainUrl/${request.data.format(page)}"
    }.replace("//", "/")
     .replace(":/", "://")

    val document = app.get(url).document

    val items = document.select("article.has-post-thumbnail, article.item, article.item-infinite")
        .mapNotNull { it.toSearchResult() }

    return newHomePageResponse(request.name, items)
}


    private fun Element.toSearchResult(): SearchResponse? {
        val linkElement = selectFirst("a[href][title]") ?: return null

        val href = fixUrl(linkElement.attr("href").ifBlank {
            selectFirst("a")?.attr("href") ?: return null
        })

        val rawTitle = linkElement.attr("title")
        val title = rawTitle
            .removePrefix("Permalink to: ")
            .ifBlank { linkElement.text() }
            .trim()

        if (title.isBlank()) return null

        val posterElement = this.selectFirst("img.wp-post-image, img.attachment-large, img")
        val posterUrl = posterElement?.fixPoster()?.let { fixUrl(it) }

        val quality = this.selectFirst(".gmr-quality-item")?.let { el ->
        val directText = el.text().trim()
        if (directText.isNotEmpty()) {
        directText
        } else {
        val aText = el.selectFirst("a")?.text()?.trim()
        if (!aText.isNullOrBlank()) {
            aText
        } else {
            el.classNames().firstOrNull { cls ->
                cls.matches(
                    Regex(
                        "hd|sd|cam|ts|hdts|hdts2|hdrip|webrip|bluray|brrip|fhd|uhd|4k",
                        RegexOption.IGNORE_CASE
                    )
                )
            }?.uppercase()
        }
    }
}

        val typeText = selectFirst(".gmr-posttype-item")?.text()?.trim()
        val ratingText = this.selectFirst("div.gmr-rating-item")?.ownText()?.trim()
        val isSeries = typeText.equals("TV Show", ignoreCase = true)

        return if (isSeries) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = posterUrl
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = posterUrl
                if (!quality.isNullOrBlank()) addQuality(quality)
                this.score = Score.from10(ratingText?.toDoubleOrNull())
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/?s=$query", timeout = 50L).document
        return document.select("article.item").mapNotNull { it.toSearchResult() }
    }

    /** Kadang rekomendasi punya struktur HTML beda */
    private fun Element.toRecommendResult(): SearchResponse? {
        val title = this.selectFirst("h2.entry-title > a")?.text()?.trim() ?: return null
        val href = this.selectFirst("a")!!.attr("href")
        val posterElement = this.selectFirst("img.wp-post-image, img.attachment-large, img")
        val posterUrl = posterElement?.fixPoster()?.let { fixUrl(it) }
        return newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = posterUrl }
    }


    override suspend fun load(url: String): LoadResponse {
        val fetch = app.get(url)
        val document = fetch.document

        
        val title = document
            .selectFirst("h1.entry-title, div.mvic-desc h3")
            ?.text()
            ?.substringBefore("Season")
            ?.substringBefore("Episode")
            ?.substringBefore("(")
            ?.trim()
            .orEmpty()

        val poster = document
            .selectFirst("figure.pull-left > img, .mvic-thumb img, .poster img")
            .fixPoster()
            ?.let { fixUrl(it) }

        val description = document.selectFirst(
            "div[itemprop=description] > p, " +
                "div.desc p.f-desc, " +
                "div.entry-content > p"
        )
            ?.text()
            ?.trim()

        val tags = document.select("strong:contains(Genre) ~ a").eachText()

        val year = document
            .select("div.gmr-moviedata strong:contains(Year:) > a")
            .text()
            .toIntOrNull()

        val trailer = document
            .selectFirst("ul.gmr-player-nav li a.gmr-trailer-popup")
            ?.attr("href")

        val rating = document
            .selectFirst("span[itemprop=ratingValue]")
            ?.text()
            ?.toDoubleOrNull()

        val actors = document
            .select("div.gmr-moviedata span[itemprop=actors] a")
            .map { it.text() }
            .takeIf { it.isNotEmpty() }

        val recommendations = document
    .select("article.item.col-md-20")
    .mapNotNull { it.toRecommendResult() }

        val seasonBlocks = document.select("div.gmr-season-block")
        val allEpisodes = mutableListOf<Episode>()

        seasonBlocks.forEach { block ->
            val seasonTitle = block.selectFirst("h3.season-title")?.text()?.trim()
            val seasonNumber = Regex("(\\d+)")
                .find(seasonTitle ?: "")
                ?.groupValues
                ?.getOrNull(1)
                ?.toIntOrNull()
                ?: 1

            val eps = block.select("div.gmr-season-episodes a")
                .filter { a ->
                    val t = a.text().lowercase()
                    !t.contains("view all") && !t.contains("batch")
                }
                .mapIndexedNotNull { index, epLink ->
                    val hrefEp = epLink.attr("href")
                        .takeIf { it.isNotBlank() }
                        ?.let { fixUrl(it) }
                        ?: return@mapIndexedNotNull null

                    val name = epLink.text().trim()

                    val episodeNum = Regex("E(p|ps)?(\\d+)", RegexOption.IGNORE_CASE)
                        .find(name)
                        ?.groupValues
                        ?.getOrNull(2)
                        ?.toIntOrNull()
                        ?: (index + 1)

                    newEpisode(hrefEp) {
                        this.name = name
                        this.season = seasonNumber
                        this.episode = episodeNum
                    }
                }

            allEpisodes.addAll(eps)
        }

        val episodes = allEpisodes
            .sortedWith(compareBy({ it.season }, { it.episode }))

        val tvType = if (episodes.isNotEmpty()) TvType.TvSeries else TvType.Movie

        return if (tvType == TvType.TvSeries) {
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = description
                this.tags = tags
                this.year = year
                if (rating != null) addScore(rating.toString(), 10)
                addActors(actors)
                this.recommendations = recommendations
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = description
                this.tags = tags
                this.year = year
                addActors(actors)
                addTrailer(trailer)
                if (rating != null) addScore(rating.toString(), 10)
                this.recommendations = recommendations
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document
        val postId = document
            .selectFirst("div#muvipro_player_content_id")
            ?.attr("data-id")

        if (postId.isNullOrBlank()) return false

        document.select("div.tab-content-ajax").forEach { tab ->
            val tabId = tab.attr("id")
            if (tabId.isNullOrBlank()) return@forEach

            val response = app.post(
                "$mainUrl/wp-admin/admin-ajax.php",
                data = mapOf(
                    "action" to "muvipro_player_content",
                    "tab" to tabId,
                    "post_id" to postId
                )
            ).document

            val iframe = response.selectFirst("iframe")?.getIframeAttr() ?: return@forEach
            val link = httpsify(iframe)

            loadExtractor(link, mainUrl, subtitleCallback, callback)
        }

        return true
    }

    private fun Element?.fixPoster(): String? {
    if (this == null) return null

    if (this.hasAttr("srcset")) {
        val srcset = this.attr("srcset").trim()
        val best = srcset.split(",")
            .map { it.trim().split(" ")[0] }
            .lastOrNull()
        if (!best.isNullOrBlank()) return fixUrl(best.fixImageQuality())
    }

    val dataSrc = when {
        this.hasAttr("data-lazy-src") -> this.attr("data-lazy-src")
        this.hasAttr("data-src") -> this.attr("data-src")
        else -> null
    }
    if (!dataSrc.isNullOrBlank()) return fixUrl(dataSrc.fixImageQuality())

    val src = this.attr("src")
    if (!src.isNullOrBlank()) return fixUrl(src.fixImageQuality())

    return null
}

    private fun Element?.getIframeAttr(): String? {
        return this?.attr("data-litespeed-src").takeIf { !it.isNullOrEmpty() }
            ?: this?.attr("src")
    }

    private fun String?.fixImageQuality(): String {
        if (this == null) return ""
        val regex = Regex("-\\d+x\\d+(?=\\.(webp|jpg|jpeg|png))", RegexOption.IGNORE_CASE)
        return this.replace(regex, "")
    }

    private fun getBaseUrl(url: String): String {
        return URI(url).let { "${it.scheme}://${it.host}" }
    }
}
