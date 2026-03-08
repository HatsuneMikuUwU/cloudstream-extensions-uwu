package com.layarkaca

import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.*
import org.json.JSONObject
import org.jsoup.nodes.Element
import java.net.URI

class LayarKacaProvider : MainAPI() {

    override var mainUrl = "https://tv9.lk21official.cc"
    private var seriesUrl = "https://tv3.nontondrama.my"
    private var searchurl = "https://gudangvape.com"

    override var name = "LayarKaca"
    override val hasMainPage = true
    override var lang = "id"
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.AsianDrama
    )

    override val mainPage = mainPageOf(
        "$mainUrl/populer/page/" to "Most Popular Movies",
        "$mainUrl/rating/page/" to "Movies Based on IMDb Rating",
        "$mainUrl/most-commented/page/" to "Films With the Most Comments",
        "$seriesUrl/latest-series/page/" to "Latest Series",
        "$seriesUrl/series/asian/page/" to "Latest Asian Movies",
        "$mainUrl/latest/page/" to "Latest Uploaded Movies",
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val document = app.get(request.data + page).document
        val home = document.select("li.slider article, article").mapNotNull {
            it.toSearchResult()
        }
        return newHomePageResponse(request.name, home)
    }

    private suspend fun getProperLink(url: String): String {
        if (url.startsWith(seriesUrl)) return url
        val res = app.get(url).document
        return if (res.select("title").text().contains("Nontondrama", true)) {
            res.selectFirst("a#openNow")?.attr("href")
                ?: res.selectFirst("div.links a")?.attr("href")
                ?: url
        } else {
            url
        }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.selectFirst("h3.poster-title, h3")?.text()?.trim() ?: return null
        val href = fixUrl(this.selectFirst("a")?.attr("href") ?: return null)
        val posterUrl = fixUrlNull(this.selectFirst("img")?.getImageAttr())
        
        val isSeries = this.selectFirst("span.episode") != null
        val posterheaders = mapOf("Referer" to getSafeBaseUrl(posterUrl))

        return if (isSeries) {
            val episode = this.selectFirst("span.episode strong")?.text()?.filter { it.isDigit() }?.toIntOrNull()
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = posterUrl
                this.posterHeaders = posterheaders
            }
        } else {
            val quality = this.selectFirst("span.label")?.text()?.trim()
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = posterUrl
                this.posterHeaders = posterheaders
                addQuality(quality)
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val res = app.get("$searchurl/search.php?s=$query").text
        val results = mutableListOf<SearchResponse>()

        try {
            val root = JSONObject(res)
            val arr = root.getJSONArray("data")

            for (i in 0 until arr.length()) {
                val item = arr.getJSONObject(i)
                val title = item.getString("title")
                val slug = item.getString("slug")
                val type = item.getString("type")
                val posterUrl = "https://poster.lk21.party/wp-content/uploads/" + item.optString("poster")
                val posterheaders = mapOf("Referer" to getSafeBaseUrl(posterUrl))

                when (type) {
                    "series" -> results.add(
                        newTvSeriesSearchResponse(title, "$seriesUrl/$slug", TvType.TvSeries) {
                            this.posterUrl = posterUrl
                            this.posterHeaders = posterheaders
                        }
                    )
                    "movie" -> results.add(
                        newMovieSearchResponse(title, "$mainUrl/$slug", TvType.Movie) {
                            this.posterUrl = posterUrl
                            this.posterHeaders = posterheaders
                        }
                    )
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return results
    }

    override suspend fun load(url: String): LoadResponse {
        val fixUrl = getProperLink(url)
        val document = app.get(fixUrl).document
        val baseurl = fetchURL(fixUrl)
        
        val title = document.selectFirst("div.movie-info h1, h1.poster-title")?.text()?.trim() ?: ""
        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")
        val tags = document.select("div.tag-list span, .genre a").map { it.text() }
        val posterheaders = mapOf("Referer" to getSafeBaseUrl(poster))

        val yearRegex = Regex("\\d, (\\d{4})|\\((\\d{4})\\)").find(title)
        val year = yearRegex?.groupValues?.drop(1)?.firstOrNull { it.isNotBlank() }?.toIntOrNull()
        
        val tvType = if (document.selectFirst("#season-data") != null || url.contains(seriesUrl)) TvType.TvSeries else TvType.Movie
        val description = document.selectFirst("div.meta-info, .synopsis")?.text()?.trim()
        val trailer = document.selectFirst("ul.action-left > li:nth-child(3) > a, a.trailer")?.attr("href")
        val rating = document.selectFirst("div.info-tag strong, .rating strong")?.text()

        val recommendations = document.select("li.slider article").mapNotNull {
            it.toSearchResult()
        }

        return if (tvType == TvType.TvSeries) {
            val json = document.selectFirst("script#season-data")?.data()
            val episodes = mutableListOf<Episode>()
            if (json != null) {
                val root = JSONObject(json)
                root.keys().forEach { seasonKey ->
                    val seasonArr = root.getJSONArray(seasonKey)
                    for (i in 0 until seasonArr.length()) {
                        val ep = seasonArr.getJSONObject(i)
                        val href = fixUrl("$baseurl/" + ep.getString("slug"))
                        val episodeNo = ep.optInt("episode_no")
                        val seasonNo = ep.optInt("s")
                        episodes.add(
                            newEpisode(href) {
                                this.name = "Episode $episodeNo"
                                this.season = seasonNo
                                this.episode = episodeNo
                            }
                        )
                    }
                }
            }
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.posterHeaders = posterheaders
                this.year = year
                this.plot = description
                this.tags = tags
                this.score = Score.from10(rating)
                this.recommendations = recommendations
                addTrailer(trailer)
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.posterHeaders = posterheaders
                this.year = year
                this.plot = description
                this.tags = tags
                this.score = Score.from10(rating)
                this.recommendations = recommendations
                addTrailer(trailer)
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

        document.select("ul#player-list > li a, select#player-select option").mapNotNull {
            val link = it.attr("data-url").takeIf { u -> u.isNotBlank() } ?: it.attr("value").takeIf { u -> u.isNotBlank() }
            fixUrlNull(link)
        }.distinct().amap { url ->
            try {
                val referer = getSafeBaseUrl(url)
                Log.d("LayarkacaLinks", url)
                loadExtractor(url, referer, subtitleCallback, callback)
                
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        return true
    }

    private suspend fun fetchURL(url: String): String {
        val res = app.get(url, allowRedirects = false)
        val href = res.headers["location"]

        return if (href != null) {
            try {
                val it = URI(href)
                "${it.scheme}://${it.host}"
            } catch (e: Exception) {
                url
            }
        } else {
            url
        }
    }

    private fun Element.getImageAttr(): String {
        return when {
            this.hasAttr("src") -> this.attr("src")
            this.hasAttr("data-src") -> this.attr("data-src")
            else -> this.attr("src")
        }
    }

    private fun getSafeBaseUrl(url: String?): String {
        if (url.isNullOrBlank()) return mainUrl
        return try {
            val it = URI(url)
            "${it.scheme}://${it.host}"
        } catch (e: Exception) {
            mainUrl
        }
    }
}
