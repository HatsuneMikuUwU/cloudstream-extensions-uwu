package com.layarkaca

import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.*
import org.json.JSONObject
import org.jsoup.nodes.Element
import java.net.URI
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import com.lagradost.cloudstream3.network.CloudflareKiller
import kotlinx.coroutines.CancellationException

class LayarKacaProvider : MainAPI() {

    override var mainUrl = "https://tv12.lk21official.cc"
    private var seriesUrl = "https://tv9.nontondrama.my"
    private var searchurl= "https://gudangvape.com"

    private val cloudflareKiller by lazy { CloudflareKiller() }

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
        "$seriesUrl/series/asian/page/" to "Latest Asian Series",
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
                quality?.let { addQuality(it) }
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
                val posterUrl = "https://poster.assetsy.de/wp-content/uploads/" + item.optString("poster")
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
                trailer?.let { addTrailer(it) }
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
                trailer?.let { addTrailer(it) }
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val pageUrl = fixUrl(data)
        val document = app.get(pageUrl, referer = "$mainUrl/").document

        val found = AtomicBoolean(false)
        val emit: (ExtractorLink) -> Unit = { link ->
            found.set(true)
            callback(link)
        }

        val servers = linkedSetOf<String>()
        document.select("ul#player-list li a, .player-list a").forEach { a ->
            a.attr("data-url").ifBlank { a.attr("href") }
                .takeIf { it.isNotBlank() && it != "#" }
                ?.let { servers.add(fixUrl(it)) }
        }
        document.select("select#player-select option[value]").forEach { option ->
            option.attr("value").takeIf { it.isNotBlank() }?.let { servers.add(fixUrl(it)) }
        }
        if (servers.isEmpty()) {
            document.selectFirst("iframe#main-player, .main-player iframe")
                ?.attr("src")
                ?.takeIf { it.isNotBlank() }
                ?.let { servers.add(fixUrl(it)) }
        }

        servers.toList().amap { serverUrl ->
            try {
                resolveServer(serverUrl, pageUrl, subtitleCallback, emit)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.e("LayarKaca", "Server failed: $serverUrl (${e.message})")
            }
        }

        return found.get()
    }

    private suspend fun resolveServer(
        serverUrl: String,
        pageUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val server = Regex("""/iframe\d*/([^/?#]+)/""").find(serverUrl)?.groupValues?.get(1)?.lowercase()
        val referer = "${getSafeBaseUrl(pageUrl)}/"

        val withV = if (serverUrl.contains("?")) serverUrl else "$serverUrl?v=1"
        for (candidate in listOf(withV, serverUrl).distinct()) {
            val count = AtomicInteger(0)
            val counting: (ExtractorLink) -> Unit = { count.incrementAndGet(); callback(it) }
            try {
                scanPage(candidate, referer, server, 0, mutableSetOf(), subtitleCallback, counting)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.e("LayarKaca", "Scan failed: $candidate (${e.message})")
            }
            if (count.get() > 0) return
        }
        Log.e("LayarKaca", "No links found for server=$server url=$serverUrl")
    }

    private suspend fun scanPage(
        url: String,
        referer: String,
        server: String?,
        depth: Int,
        seen: MutableSet<String>,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        if (depth > 2 || !seen.add(url)) return

        val response = app.get(url, referer = referer, interceptor = cloudflareKiller)
        var body = response.text.replace("\\/", "/")
        Log.d("LayarKaca", "scan[$depth] ${response.code} $url (${body.length} chars)")

        if (body.contains("eval(function(p,a,c,k,e,d)")) {
            runCatching { getAndUnpack(body) }.getOrNull()?.let { body += "\n" + it.replace("\\/", "/") }
        }

        val media = linkedSetOf<String>()
        Regex("""https?://[^\s"'<>\\]+\.(?:m3u8|mp4)[^\s"'<>\\]*""", RegexOption.IGNORE_CASE)
            .findAll(body).forEach { media.add(it.value) }
        Regex("""urlPlay\s*=\s*["']([^"']+)["']""").find(body)?.groupValues?.get(1)
            ?.let { resolveUrl(it, url) }?.let { media.add(it) }

        val mediaLinks = media.filterNot { isJunkUrl(it) }
        if (mediaLinks.isNotEmpty()) {
            mediaLinks.forEach { link ->
                Log.d("LayarKaca", "Media: $link")
                if (link.contains(".m3u8", true)) {
                    M3u8Helper.generateM3u8(source = name, streamUrl = link, referer = url).forEach(callback)
                } else {
                    callback(
                        newExtractorLink(source = name, name = name, url = link) {
                            this.referer = url
                            this.quality = Qualities.Unknown.value
                        }
                    )
                }
            }
            return
        }

        val nested = linkedSetOf<String>()
        response.document.select("iframe").forEach { frame ->
            frame.attr("data-src").ifBlank { frame.attr("src") }
                .takeIf { it.isNotBlank() }?.let { nested.add(it) }
        }
        if (nested.isEmpty()) {
            Regex("""(?i)(?:iframe|embed|src|file|url)\s*[:=]\s*["']((?:https?:)?//[^"']+)["']""")
                .findAll(body).forEach { nested.add(it.groupValues[1]) }
        }

        nested
            .mapNotNull { resolveUrl(it, url) }
            .filterNot { isJunkUrl(it) || it == url }
            .distinct()
            .forEach { link ->
                Log.d("LayarKaca", "Nested[$depth]: $link")
                val local = AtomicInteger(0)
                val cb: (ExtractorLink) -> Unit = { local.incrementAndGet(); callback(it) }

                try {
                    loadExtractor(link, url, subtitleCallback, cb)
                    if (local.get() == 0) serverFallback(server, link, url)?.forEach(cb)
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    Log.e("LayarKaca", "Extractor failed: $link (${e.message})")
                }
                if (local.get() == 0) {
                    scanPage(link, url, server, depth + 1, seen, subtitleCallback, cb)
                }
            }
    }

    private suspend fun serverFallback(server: String?, link: String, referer: String): List<ExtractorLink>? {
        return when (server) {
            "p2p" -> if (link.contains("id=")) P2PExtractor().getUrl(link, referer) else null
            "cast" -> if (link.contains("/e/")) F16Extractor().getUrl(link, referer) else null
            "turbovip" -> EmturbovidExtractor().getUrl(link, referer)
            else -> null
        }
    }

    private fun resolveUrl(url: String, base: String): String? {
        val value = url.trim()
        return when {
            value.isBlank() -> null
            value.startsWith("//") -> "https:$value"
            value.startsWith("http", true) -> value
            else -> runCatching { URI(base).resolve(value).toString() }.getOrNull()
        }
    }

    private fun isJunkUrl(url: String): Boolean {
        return Regex("""\.(js|css|png|jpe?g|gif|svg|ico|woff2?)(\?|$)""", RegexOption.IGNORE_CASE).containsMatchIn(url) ||
            listOf("histats", "google", "doubleclick", "cdn-cgi", "facebook", "youtube").any { url.contains(it, true) }
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
