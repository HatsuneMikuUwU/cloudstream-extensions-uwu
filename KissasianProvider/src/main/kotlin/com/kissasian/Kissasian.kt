package com.kissasian

import com.lagradost.cloudstream3.*  
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors  
import com.lagradost.cloudstream3.LoadResponse.Companion.addScore  
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer  
import com.lagradost.cloudstream3.MainAPI  
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.base64Decode 
import com.lagradost.cloudstream3.TvType  
import com.lagradost.cloudstream3.mainPageOf  
import com.lagradost.cloudstream3.newMovieSearchResponse  
import com.lagradost.cloudstream3.newTvSeriesLoadResponse  
import com.lagradost.cloudstream3.newMovieLoadResponse  
import com.lagradost.cloudstream3.newEpisode  
import com.lagradost.cloudstream3.utils.*  
import org.jsoup.nodes.Element
import org.jsoup.Jsoup

class Kissasian : MainAPI() {
    override var mainUrl = "https://kissasian.cam"
    override var name = "Kissasian🥯"
    override val hasMainPage = true
    override var lang = "id"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    companion object {
        fun getStatus(t: String): ShowStatus {
            return when (t) {
                "Completed" -> ShowStatus.Completed
                "Ongoing" -> ShowStatus.Ongoing
                else -> ShowStatus.Completed
            }
        }
    }

    override val mainPage = mainPageOf(
        "series/?order=latest" to "Baru ditambahkan",
        "series/?status=&type=&order=update" to "Update Terbaru",
        "series/?status=&type=Movie&order=latest" to "Movie Terbaru",
        "series/?status=&type=&order=popular" to "Terpopuler",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = "$mainUrl/${request.data}".plus("&page=$page")
        val document = app.get(url).document
        val items = document.select("div.listupd article.bs")
                            .mapNotNull { it.toSearchResult() }
        return newHomePageResponse(HomePageList(request.name, items), hasNext = items.isNotEmpty())
    }

    private fun Element.toSearchResult(): SearchResponse? {
    val linkElement = this.selectFirst("a") ?: return null
    val href = fixUrl(linkElement.attr("href"))
    val title = linkElement.attr("title").ifBlank {
        this.selectFirst("div.tt")?.text()
    } ?: return null
    val poster = this.selectFirst("img")?.getImageAttr()?.let { fixUrlNull(it) }

    val isSeries = href.contains("/series/", true) || href.contains("drama", true)

    return if (isSeries) {
        newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
            this.posterUrl = poster
        }
    } else {
        newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = poster
        }
    }
}

    override suspend fun search(query: String): List<SearchResponse> {
    val document = app.get("$mainUrl/?s=$query", timeout = 50L).document
    val results = document.select("div.listupd article.bs")
        .mapNotNull { it.toSearchResult() }
    return results
}

    private fun Element.toRecommendResult(): SearchResponse? {
    val title = this.selectFirst("div.tt")?.text()?.trim() ?: return null
    val href = this.selectFirst("a")?.attr("href") ?: return null
    val posterUrl = this.selectFirst("img")?.getImageAttr()?.let { fixUrlNull(it) }
    return newMovieSearchResponse(title, href, TvType.Movie) {
        this.posterUrl = posterUrl
    }
}
    override suspend fun load(url: String): LoadResponse {
    val document = app.get(url).document

    // Judul
    val title = document.selectFirst("h1.entry-title")?.text()?.trim().orEmpty()

    // Poster
    val poster = document.selectFirst("div.bigcontent img")?.getImageAttr()?.let { fixUrlNull(it) }

    // Sinopsis (gabungkan semua paragraf)
    val description = document.select("div.entry-content p")
        .joinToString("\n") { it.text() }
        .trim()

    // Tahun rilis (ambil angka dari span Dirilis)
    val year = document.selectFirst("span:matchesOwn(Dirilis:)")?.ownText()
        ?.filter { it.isDigit() }?.take(4)?.toIntOrNull()

    // Status, durasi, negara, tipe
    
    val duration = document.selectFirst("div.spe span:contains(Durasi:)")?.ownText()?.let {
    val h = Regex("(\\d+)\\s*hr").find(it)?.groupValues?.get(1)?.toIntOrNull() ?: 0
    val m = Regex("(\\d+)\\s*min").find(it)?.groupValues?.get(1)?.toIntOrNull() ?: 0
    h * 60 + m
    }
    val country = document.selectFirst("span:matchesOwn(Negara:)")?.ownText()?.trim()
    val type = document.selectFirst("span:matchesOwn(Tipe:)")?.ownText()?.trim()

    // Genre / tags
    val tags = document.select("div.genxed a").map { it.text() }

    // Aktor
    val actors = document.select("span:has(b:matchesOwn(Artis:)) a")
    .map { it.text().trim() }

    val rating = document.selectFirst("div.rating strong")
    ?.text()
    ?.replace("Rating", "")
    ?.trim()
    ?.toDoubleOrNull()

    val trailer = document.selectFirst("div.bixbox.trailer iframe")?.attr("src")

    val status = getStatus(
    document.selectFirst("div.info-content div.spe span")
        ?.ownText()
        ?.replace(":", "")
        ?.trim()
        ?: ""
)

    
    val recommendations = document.select("div.listupd article.bs")
        .mapNotNull { it.toRecommendResult() }

    
val episodeElements = document.select("div.eplister ul li a")

val episodes = episodeElements
    .reversed() // karena biasanya terbaru di atas
    .mapIndexed { index, aTag ->
        val href = fixUrl(aTag.attr("href"))

        newEpisode(href) {
            this.name = "Episode ${index + 1}"
            this.episode = index + 1
        }
    }


    return if (episodes.size > 1) {
    // TV Series
    newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
        this.posterUrl = poster
        this.year = year
        this.plot = description
        this.tags = tags
        showStatus = status
        this.recommendations = recommendations
        this.duration = duration ?: 0
        if (rating != null) addScore(rating.toString(), 10)
        addActors(actors)
        addTrailer(trailer)
    }
} else {
    // Movie
    newMovieLoadResponse(title, url, TvType.Movie, episodes.firstOrNull()?.data ?: url) {
        this.posterUrl = poster
        this.year = year
        this.plot = description
        this.tags = tags
        this.recommendations = recommendations
        this.duration = duration ?: 0
        if (rating != null) addScore(rating.toString(), 10)
        addActors(actors)
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

    
    document.selectFirst("div.player-embed iframe")
        ?.attr("src")
        ?.takeIf { it.isNotBlank() }
        ?.let { iframe ->
            loadExtractor(
                httpsify(iframe),
                data,
                subtitleCallback,
                callback
            )
        }

    val mirrorOptions = document.select("select.mirror option[value]")

    for (opt in mirrorOptions) {
        val mirrorPageUrl = fixUrl(opt.attr("value"))
        if (mirrorPageUrl.isBlank()) continue

        try {
            val mirrorDoc = app.get(mirrorPageUrl).document

            mirrorDoc.selectFirst("div.player-embed iframe")
                ?.attr("src")
                ?.takeIf { it.isNotBlank() }
                ?.let { iframe ->
                    loadExtractor(
                        httpsify(iframe),
                        mirrorPageUrl,
                        subtitleCallback,
                        callback
                    )
                }

        } catch (_: Exception) {
        }
    }

    return true
}


    private fun Element.getImageAttr(): String {
        return when {
            this.hasAttr("data-src") -> this.attr("abs:data-src")
            this.hasAttr("data-lazy-src") -> this.attr("abs:data-lazy-src")
            this.hasAttr("srcset") -> this.attr("abs:srcset").substringBefore(" ")
            else -> this.attr("abs:src")
        }
    }

    private fun Element?.getIframeAttr(): String? {
        return this?.attr("data-litespeed-src").takeIf { it?.isNotEmpty() == true }
                ?: this?.attr("src")
    }

    private fun String?.fixImageQuality(): String? {
        if (this == null) return null
        val regex = Regex("(-\\d*x\\d*)").find(this)?.groupValues?.get(0) ?: return this
        return this.replace(regex, "")
    }
}
