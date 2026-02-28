package com.idlix

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.extractors.helper.AesHelper
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import org.jsoup.nodes.Element
import java.net.URI

class IdlixProvider : MainAPI() {
    override var mainUrl = "https://idlixian.com"
    private var directUrl = mainUrl
    override var name = "Idlix"
    override val hasMainPage = true
    override var lang = "id"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.AsianDrama
    )

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

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = request.data.split("?")
        val nonPaged = request.name == "Featured" && page <= 1
        val req = if (nonPaged) {
            app.get(request.data)
        } else {
            app.get("${url.first()}$page/?${url.lastOrNull()}")
        }
        mainUrl = getBaseUrl(req.url)
        val document = req.documentLarge
        
        val home = (if (nonPaged) {
            document.select("div.items.featured article")
        } else {
            document.select("div.items.full article, div#archive-content article")
        }).mapNotNull { it.toSearchResult() }
        
        return newHomePageResponse(request.name, home)
    }

    private fun getProperLink(uri: String): String {
        return if (uri.contains("/episode/") || uri.contains("/season/")) {
            val keyword = if (uri.contains("/episode/")) "/episode/" else "/season/"
            val title = uri.substringAfter("$mainUrl$keyword")
            val extractedTitle = Regex("(.+?)-season").find(title)?.groupValues?.get(1) ?: title
            "$mainUrl/tvseries/$extractedTitle"
        } else {
            uri
        }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val titleAnchor = this.selectFirst("h3 > a") ?: return null
        val title = titleAnchor.text().replace(Regex("\\(\\d{4}\\)"), "").trim()
        val href = getProperLink(titleAnchor.attr("href"))
        val posterUrl = this.selectFirst("div.poster > img")?.attr("src")
        val quality = getQualityFromString(this.selectFirst("span.quality")?.text())
        
        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = posterUrl
            this.quality = quality
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val req = app.get("$mainUrl/search/$query")
        mainUrl = getBaseUrl(req.url)
        val document = req.documentLarge
        
        return document.select("div.result-item").mapNotNull {
            val titleAnchor = it.selectFirst("div.title > a") ?: return@mapNotNull null
            val title = titleAnchor.text().replace(Regex("\\(\\d{4}\\)"), "").trim()
            val href = getProperLink(titleAnchor.attr("href"))
            val posterUrl = it.selectFirst("img")?.attr("src")
            
            newMovieSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = posterUrl
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val request = app.get(url)
        directUrl = getBaseUrl(request.url)
        val document = request.documentLarge
        
        val title = document.selectFirst("div.data > h1")?.text()
            ?.replace(Regex("\\(\\d{4}\\)"), "")?.trim().orEmpty()
            
        val poster = document.select("div.g-item").shuffled().firstOrNull()?.selectFirst("a")?.attr("href")
            ?: document.selectFirst("div.poster > img")?.attr("src")
            
        val tags = document.select("div.sgeneros > a").map { it.text() }
        val year = Regex(",\\s?(\\d+)").find(document.selectFirst("span.date")?.text().orEmpty())
            ?.groupValues?.get(1)?.toIntOrNull()
            
        val isTvSeries = document.selectFirst("ul#section > li:nth-child(1)")?.text()?.contains("Episodes") == true
        val tvType = if (isTvSeries) TvType.TvSeries else TvType.Movie
        
        val description = document.selectFirst("p:nth-child(3)")?.text()?.trim()
        val trailer = document.selectFirst("div.embed iframe")?.attr("src")
        val rating = document.selectFirst("span.dt_rating_vgs")?.text()
        
        val actors = document.select("div.persons > div[itemprop=actor]").mapNotNull {
            val name = it.selectFirst("meta[itemprop=name]")?.attr("content") ?: return@mapNotNull null
            val image = it.selectFirst("img")?.attr("src")
            Actor(name, image)
        }

        val recommendations = document.select("div.owl-item").mapNotNull {
            val anchor = it.selectFirst("a") ?: return@mapNotNull null
            val recHref = anchor.attr("href")
            val recName = recHref.removeSuffix("/").split("/").last()
            val recPosterUrl = it.selectFirst("img")?.attr("src")
            
            newTvSeriesSearchResponse(recName, recHref, TvType.TvSeries) {
                this.posterUrl = recPosterUrl
            }
        }

        return if (tvType == TvType.TvSeries) {
            val episodes = document.select("ul.episodios > li").mapNotNull {
                val href = it.selectFirst("a")?.attr("href") ?: return@mapNotNull null
                val name = fixTitle(it.selectFirst("div.episodiotitle > a")?.text()?.trim().orEmpty())
                val image = it.selectFirst("div.imagen > img")?.attr("src")
                val numerandoText = it.selectFirst("div.numerando")?.text()?.replace(" ", "") ?: ""
                val season = numerandoText.substringBefore("-").toIntOrNull()
                val episode = numerandoText.substringAfter("-").toIntOrNull()
                
                newEpisode(href) {
                    this.name = name
                    this.season = season
                    this.episode = episode
                    this.posterUrl = image
                }
            }
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
                this.score = Score.from100(rating)
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
                this.score = Score.from100(rating)
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
        val document = app.get(data).documentLarge
        val scriptRegex = """window\.idlixNonce=['"]([a-f0-9]+)['"].*?window\.idlixTime=(\d+).*?""".toRegex(RegexOption.DOT_MATCHES_ALL)
        val script = document.selectFirst("script:containsData(window.idlix)")?.data().orEmpty()
        val match = scriptRegex.find(script)
        
        val idlixNonce = match?.groups?.get(1)?.value ?: ""
        val idlixTime = match?.groups?.get(2)?.value ?: ""

        document.select("ul#playeroptionsul > li").map {
            Triple(
                it.attr("data-post"),
                it.attr("data-nume"),
                it.attr("data-type")
            )
        }.amap { (id, nume, type) ->
            val json = app.post(
                url = "$directUrl/wp-admin/admin-ajax.php",
                data = mapOf(
                    "action" to "doo_player_ajax", "post" to id, "nume" to nume, "type" to type, "_n" to idlixNonce, "_p" to id, "_t" to idlixTime
                ),
                referer = data,
                headers = mapOf("Accept" to "*/*", "X-Requested-With" to "XMLHttpRequest")
            ).parsedSafe<ResponseHash>() ?: return@amap
            
            val metrix = AppUtils.parseJson<AesData>(json.embed_url).m
            val password = createKey(json.key, metrix)
            val decrypted = AesHelper.cryptoAESHandler(json.embed_url, password.toByteArray(), false)?.fixBloat() ?: return@amap
            Log.d("AdiManu", decrypted.toJson())

            if (!decrypted.contains("youtube")) {
                loadExtractor(decrypted, directUrl, subtitleCallback, callback)
            }
        }
        return true
    }

    private fun createKey(r: String, m: String): String {
        val rList = r.split("\\x").filter { it.isNotEmpty() }
        val paddingLength = (4 - m.length % 4) % 4
        val paddedM = m.reversed().padEnd(m.length + paddingLength, '=')
        
        val decodedM = try {
            String(base64DecodeArray(paddedM))
        } catch (_: Exception) {
            return ""
        }
        
        return decodedM.split("|").mapNotNull { s ->
            s.toIntOrNull()?.let { index ->
                if (index in rList.indices) "\\x${rList[index]}" else null
            }
        }.joinToString("")
    }

    private fun String.fixBloat(): String {
        return this.replace("\"", "").replace("\\", "")
    }

    data class ResponseSource(
        @JsonProperty("hls") val hls: Boolean,
        @JsonProperty("videoSource") val videoSource: String,
        @JsonProperty("securedLink") val securedLink: String?,
    )

    data class Tracks(
        @JsonProperty("kind") val kind: String?,
        @JsonProperty("file") val file: String,
        @JsonProperty("label") val label: String?,
    )

    data class ResponseHash(
        @JsonProperty("embed_url") val embed_url: String,
        @JsonProperty("key") val key: String,
    )

    data class AesData(
        @JsonProperty("m") val m: String,
    )
}
