package com.idlix

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.extractors.helper.AesHelper
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.jsoup.nodes.Element
import java.net.URI

class IdlixProvider : MainAPI() {
    override var mainUrl = "https://idlixian.com"
    override var name = "Idlix"
    override val hasMainPage = true
    override var lang = "id"
    override val hasDownloadSupport = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.AsianDrama)

    private var baseUrl = mainUrl

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Featured",
        "$mainUrl/trending/page/?get=movies" to "Trending Movies",
        "$mainUrl/trending/page/?get=tv" to "Trending TV Series",
        "$mainUrl/movie/page/" to "Movie",
        "$mainUrl/tvseries/page/" to "TV Series",
        "$mainUrl/network/amazon/page/" to "Amazon Prime",
        "$mainUrl/network/apple-tv/page/" to "Apple TV+ Series",
        "$mainUrl/network/disney/page/" to "Disney+ Series",
        "$mainUrl/network/HBO/page/" to "HBO Series",
        "$mainUrl/network/netflix/page/" to "Netflix Series",
    )

    private fun getBaseUrl(url: String): String {
        return URI(url).let { "${it.scheme}://${it.host}" }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = request.data.split("?")
        val nonPaged = request.name == "Featured" && page <= 1
        val req = if (nonPaged) {
            app.get(request.data)
        } else {
            val basePath = url.first().removeSuffix("/")
            val query = if (url.size > 1) "?${url[1]}" else ""
            app.get("$basePath/$page/$query")
        }
        baseUrl = getBaseUrl(req.url)
        mainUrl = baseUrl
        val document = req.documentLarge
        val home = (if (nonPaged) {
            document.select("div.items.featured article")
        } else {
            document.select("div.items.full article, div#archive-content article")
        }).mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, home)
    }

    private fun getProperLink(uri: String): String {
        return when {
            uri.contains("/episode/") || uri.contains("/season/") -> {
                val match = Regex("""$baseUrl/(?:episode|season)/(.+?)-season""").find(uri)
                match?.groupValues?.get(1)?.let { "$baseUrl/tvseries/$it" } ?: uri
            }
            else -> uri
        }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val linkEl = selectFirst("h3 > a, div.title > a") ?: return null
        val title = linkEl.text().replace(Regex("\\(\\d{4}\\)"), "").trim()
        val href = fixUrlNull(getProperLink(linkEl.attr("href"))) ?: return null
        val posterUrl = select("div.poster > img, img").attr("src").takeIf { it.isNotBlank() }?.let { fixUrlNull(it) }
        val quality = select("span.quality").text().let { getQualityFromString(it) }
        val type = if (href.contains("/tvseries/") || href.contains("/tv-show/")) TvType.TvSeries else TvType.Movie
        return newMovieSearchResponse(title, href, type) {
            this.posterUrl = posterUrl
            this.quality = quality
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val req = app.get("$baseUrl/search/$query")
        baseUrl = getBaseUrl(req.url)
        mainUrl = baseUrl
        val document = req.documentLarge
        return document.select("div.result-item").mapNotNull {
            val titleEl = it.selectFirst("div.title > a") ?: return@mapNotNull null
            val title = titleEl.text().replace(Regex("\\(\\d{4}\\)"), "").trim()
            val href = fixUrlNull(getProperLink(titleEl.attr("href"))) ?: return@mapNotNull null
            val posterUrl = it.selectFirst("img")?.attr("src")?.let { fixUrlNull(it) }
            val type = if (href.contains("/tvseries/")) TvType.TvSeries else TvType.Movie
            newMovieSearchResponse(title, href, type) {
                this.posterUrl = posterUrl
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val request = app.get(url)
        baseUrl = getBaseUrl(request.url)
        mainUrl = baseUrl
        val document = request.documentLarge

        val title = document.selectFirst("div.data > h1")?.text()
            ?.replace(Regex("\\(\\d{4}\\)"), "")?.trim()
            ?: document.selectFirst("meta[property='og:title']")?.attr("content")?.trim() ?: "No title"

        val poster = document.select("div.poster > img").attr("src").takeIf { it.isNotBlank() }
            ?.let { fixUrlNull(it) }
            ?: document.selectFirst("meta[property='og:image']")?.attr("content")?.let { fixUrlNull(it) }

        val tags = document.select("div.sgeneros > a").map { it.text() }.filter { it.isNotBlank() }

        val year = document.select("span.date").text().let { dateText ->
            Regex("""\b(19|20)\d{2}\b""").find(dateText)?.value?.toIntOrNull()
        }

        val tvType = if (document.select("ul#section > li:contains(Episodes)").isNotEmpty() ||
            document.select("ul.episodios").isNotEmpty()
        ) TvType.TvSeries else TvType.Movie

        val description = document.select("div[itemprop=description] p, div.sinopsis p, p:contains(Sinopsis) ~ p")
            .firstOrNull()?.text()?.trim()
            ?: document.select("meta[property='og:description']").attr("content").takeIf { it.isNotBlank() }

        val trailer = document.selectFirst("div.embed iframe, iframe[src*=youtube]")?.attr("src")?.let { fixUrl(it) }

        val rating = document.selectFirst("span.dt_rating_vgs")?.text()?.toFloatOrNull()

        val actors = document.select("div.persons > div[itemprop=actor]").mapNotNull {
            val name = it.select("meta[itemprop=name]").attr("content").takeIf { name -> name.isNotBlank() }
                ?: it.select("span.name").text().takeIf { n -> n.isNotBlank() } ?: return@mapNotNull null
            val image = it.select("img").attr("src").takeIf { img -> img.isNotBlank() }?.let { img -> fixUrlNull(img) }
            Actor(name, image)
        }

        val recommendations = document.select("div.owl-item, div.recommended-item, article.relacionado").mapNotNull { el ->
            val linkEl = el.selectFirst("a[href]") ?: return@mapNotNull null
            val recTitle = linkEl.attr("title").takeIf { it.isNotBlank() }
                ?: el.selectFirst("h3, .title, img")?.attr("alt") ?: return@mapNotNull null
            val recHref = fixUrlNull(linkEl.attr("href")) ?: return@mapNotNull null
            val recPoster = el.selectFirst("img")?.attr("src")?.let { fixUrlNull(it) }
            newMovieSearchResponse(recTitle, recHref, if (recHref.contains("/tvseries/")) TvType.TvSeries else TvType.Movie) {
                this.posterUrl = recPoster
            }
        }.distinctBy { it.url }

        return if (tvType == TvType.TvSeries) {
            val episodes = document.select("ul.episodios > li, div.episodios-list > div").mapNotNull { epEl ->
                val linkEl = epEl.selectFirst("a[href]") ?: return@mapNotNull null
                val href = fixUrlNull(linkEl.attr("href")) ?: return@mapNotNull null
                val name = epEl.select("div.episodiotitle > a, .episode-title").text().trim().takeIf { it.isNotBlank() }
                    ?: "Episode"
                val image = epEl.select("div.imagen > img").attr("src").takeIf { it.isNotBlank() }?.let { fixUrlNull(it) }
                val numText = epEl.select("div.numerando, .episode-number").text().replace(" ", "")
                val season = Regex("""(\d+)-(\d+)""").find(numText)?.groupValues?.get(1)?.toIntOrNull()
                val episode = Regex("""(\d+)-(\d+)""").find(numText)?.groupValues?.get(2)?.toIntOrNull()
                    ?: Regex("""\d+$""").find(numText)?.value?.toIntOrNull()
                newEpisode(href) {
                    this.name = name
                    this.season = season
                    this.episode = episode
                    this.posterUrl = image
                }
            }.sortedBy { it.episode }

            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
                this.rating = rating
                addActors(actors)
                this.recommendations = recommendations
                addTrailer(trailer)
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
                this.rating = rating
                addActors(actors)
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
        val document = app.get(data, referer = baseUrl).documentLarge

        val scriptRegex = """window\.idlixNonce=['"]([a-f0-9]+)['"];.*?window\.idlixTime['"]?=?\s*['"]?(\d+)['"]?""".toRegex(RegexOption.DOT_MATCHES_ALL)
        val scriptText = document.select("script:containsData(window.idlix)").joinToString(" ") { it.data() }
        val match = scriptRegex.find(scriptText)
        val idlixNonce = match?.groupValues?.get(1) ?: ""
        val idlixTime = match?.groupValues?.get(2) ?: ""

        var found = false
        val linkCallback: (ExtractorLink) -> Unit = {
            found = true
            callback(it)
        }

        document.select("ul#playeroptionsul > li").map { li ->
            Triple(
                li.attr("data-post"),
                li.attr("data-nume"),
                li.attr("data-type")
            )
        }.amap { (id, nume, type) ->
            if (id.isEmpty() || nume.isEmpty() || type.isEmpty()) return@amap
            runCatching {
                val response = app.post(
                    url = "$baseUrl/wp-admin/admin-ajax.php",
                    data = mapOf(
                        "action" to "doo_player_ajax",
                        "post" to id,
                        "nume" to nume,
                        "type" to type,
                        "_n" to idlixNonce,
                        "_p" to id,
                        "_t" to idlixTime
                    ),
                    referer = data,
                    headers = mapOf(
                        "Accept" to "*/*",
                        "X-Requested-With" to "XMLHttpRequest",
                        "Origin" to baseUrl
                    )
                ).parsedSafe<ResponseHash>() ?: return@amap

                val aesData = tryParseJson<AesData>(response.embed_url) ?: return@amap
                val key = createKey(response.key, aesData.m)
                if (key.isEmpty()) return@amap

                val decrypted = AesHelper.cryptoAESHandler(response.embed_url, key.toByteArray(), false)
                    ?.replace("\"", "")
                    ?.replace("\\", "")
                    ?.trim()
                    ?.takeIf { it.isNotBlank() } ?: return@amap

                if (!decrypted.contains("youtube", ignoreCase = true)) {
                    loadExtractor(decrypted, baseUrl, subtitleCallback, linkCallback)
                }
            }.onFailure { Log.e("Idlix", "Error loading links: ${it.message}") }
        }

        return found
    }

    private fun createKey(r: String, m: String): String {
        val rList = r.split("\\\\x").filter { it.isNotEmpty() }
        if (rList.isEmpty()) return ""

        var reversedM = m.reversed()
        while (reversedM.length % 4 != 0) reversedM += "="
        val decodedBytes = try {
            base64Decode(reversedM)
        } catch (_: Exception) {
            return ""
        }
        val decodedM = String(decodedBytes)

        val indices = decodedM.split("|").mapNotNull { it.toIntOrNull() }
        val keyBuilder = StringBuilder()
        for (i in indices) {
            if (i in rList.indices) {
                keyBuilder.append("\\x").append(rList[i])
            }
        }
        return keyBuilder.toString()
    }

    data class ResponseHash(
        @JsonProperty("embed_url") val embed_url: String,
        @JsonProperty("key") val key: String
    )

    data class AesData(
        @JsonProperty("m") val m: String
    )
}