package com.nontonanimeid

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.ObjectMapper
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addAniListId
import com.lagradost.cloudstream3.LoadResponse.Companion.addKitsuId
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import okhttp3.Interceptor
import okhttp3.Response
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI

class TurnstileInterceptor(private val targetCookie: String = "cf_clearance") : Interceptor {
    @SuppressLint("SetJavaScriptEnabled")
    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()
        val url = originalRequest.url.toString()
        
        val domainUrl = "${originalRequest.url.scheme}://${originalRequest.url.host}"
        val cookieManager = CookieManager.getInstance()

        var currentCookies = cookieManager.getCookie(domainUrl) ?: ""
        var userAgent = originalRequest.header("User-Agent") ?: ""

        var needsRefresh = false
        var initialResponse: Response? = null

        if (currentCookies.contains(targetCookie)) {
            val requestBuilder = originalRequest.newBuilder()
                .header("Cookie", currentCookies)
            
            initialResponse = chain.proceed(requestBuilder.build())

            if (initialResponse.code == 403 || initialResponse.code == 503) {
                needsRefresh = true
                initialResponse.close()
            } else {
                return initialResponse
            }
        } else {
            needsRefresh = true
        }

        if (needsRefresh) {
            val context = AcraApplication.context
            if (context != null) {
                val handler = Handler(Looper.getMainLooper())
                var webView: WebView? = null

                handler.post {
                    try {
                        val newWebView = WebView(context)
                        webView = newWebView
                        
                        newWebView.settings.apply {
                            javaScriptEnabled = true
                            domStorageEnabled = true
                            userAgentString = userAgent.ifBlank { userAgentString }
                        }
                        userAgent = newWebView.settings.userAgentString ?: userAgent

                        newWebView.webViewClient = WebViewClient()

                        cookieManager.setCookie(domainUrl, "$targetCookie=; Max-Age=0")
                        cookieManager.flush()

                        newWebView.loadUrl(url)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }

                var attempts = 0
                val maxAttempts = 15
                while (attempts < maxAttempts) {
                    Thread.sleep(1000) 
                    val checkCookies = cookieManager.getCookie(domainUrl) ?: ""
                    
                    if (checkCookies.contains(targetCookie)) {
                        cookieManager.flush()
                        break
                    }
                    attempts++
                }

                handler.post {
                    try {
                        webView?.stopLoading()
                        webView?.destroy()
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }

            currentCookies = cookieManager.getCookie(domainUrl) ?: ""
            val newRequestBuilder = originalRequest.newBuilder()
                .header("User-Agent", userAgent)
                .header("Cookie", currentCookies)
            
            return chain.proceed(newRequestBuilder.build())
        }

        return initialResponse ?: chain.proceed(originalRequest)
    }
}

class NontonAnimeIDProvider : MainAPI() {
    override var mainUrl = "https://s12.nontonanimeid.boats"
    override var name = "NontonAnimeID"
    override val hasQuickSearch = false
    override val hasMainPage = true
    override var lang = "id"
    override val hasDownloadSupport = true

    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.OVA
    )

    private val turnstileInterceptor = TurnstileInterceptor("cf_clearance")

    companion object {
        fun getType(t: String): TvType {
            return when {
                t.contains("TV", true) -> TvType.Anime
                t.contains("Movie", true) -> TvType.AnimeMovie
                else -> TvType.OVA
            }
        }

        fun getStatus(t: String): ShowStatus {
            return when (t) {
                "Finished Airing" -> ShowStatus.Completed
                "Currently Airing" -> ShowStatus.Ongoing
                else -> ShowStatus.Completed
            }
        }
    }

    override val mainPage = mainPageOf(
        "$mainUrl/anime/?mode=&sort=series_tahun_newest&status=Currently+Airing&type=" to "Ongoing Anime",
        "$mainUrl/anime/?mode=&sort=series_tahun_newest&status=Finished+Airing&type=" to "Complete Anime",
        "$mainUrl/anime/?mode=&sort=series_popularity&status=&type=" to "Most Popular",
        "$mainUrl/anime/?mode=&sort=series_skor&status=&type=" to "Top Rating",
        "$mainUrl/anime/?mode=&sort=series_tahun_newest&status=&type=Movie" to "Movie",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val pageUrl = if (page == 1) {
            request.data
        } else {
            request.data.replaceFirst("?", "page/$page/?")
        }

        val document = app.get(pageUrl, interceptor = turnstileInterceptor).document
        
        val home = document.select("article.animeseries, .animeseries, a.as-anime-card").mapNotNull {
            it.toSearchResult()
        }
        
        val hasNext = home.isNotEmpty()
        
        return newHomePageResponse(request.name, home, hasNext = hasNext)
    }

    private fun Element.toSearchResult(): AnimeSearchResponse? {
        val a = if (this.tagName() == "a") this else this.selectFirst("a") ?: return null
        val href = fixUrlNull(a.attr("href")) ?: return null
        
        val title = this.selectFirst("h3.title span, .title span, .as-anime-title, h2")?.text()?.trim()
            ?: this.selectFirst("img")?.attr("alt")?.trim()
            ?: a.attr("title").trim()
            
        if (title.isBlank()) return null
            
        val posterUrl = fixUrlNull(this.selectFirst("img")?.getImageAttr())

        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = posterUrl
            addDubStatus(dubExist = false, subExist = true)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val link = "$mainUrl/?s=$query"
        val document = app.get(link, interceptor = turnstileInterceptor).document

        return document.select("article.animeseries, .animeseries, .result > ul > li").mapNotNull {
            it.toSearchResult()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val fixUrl = if (url.contains("/anime/")) {
            url
        } else {
            app.get(url, interceptor = turnstileInterceptor).document.selectFirst("div.nvs.nvsc a")?.attr("href")
        }

        val req = app.get(fixUrl ?: return null, interceptor = turnstileInterceptor)
        mainUrl = getBaseUrl(req.url)
        val document = req.document

        val title = document.selectFirst("h1.entry-title.cs, h1.entry-title")?.text()
            ?.replace(Regex("^Nonton\\s+", RegexOption.IGNORE_CASE), "")
            ?.replace(Regex("\\s+Sub\\s+Indo$", RegexOption.IGNORE_CASE), "")
            ?.trim()
            ?: return null
        val poster = document.selectFirst(".anime-card__sidebar img, .poster > img, .thumb > img")?.getImageAttr()
        val tags = document.select(".anime-card__genres .genre-tag, .tagline > a, .genxed a").map { it.text() }

        val year = Regex("(19|20)\\d{2}").find(
            document.select(".details-list li:contains(Aired), .info-item.season, .bottomtitle, .info-content").text()
        )?.value?.toIntOrNull()
        val statusText = document.selectFirst(".info-item.status-finish, .info-item.status-airing, span.statusseries, .status")
            ?.text()
            ?.trim()
            .orEmpty()
        val status = getStatus(
            when {
                statusText.contains("airing", true) -> "Currently Airing"
                statusText.contains("currently", true) -> "Currently Airing"
                statusText.contains("finish", true) -> "Finished Airing"
                else -> statusText
            }
        )
        val type = getType(
            document.select("span.typeseries, .anime-card__score, .details-list, .bottomtitle")
                .text()
                .trim()
        )
        val rating = Regex("(\\d+(?:\\.\\d+)?)").find(
            document.selectFirst(".anime-card__score")?.text().orEmpty()
        )?.groupValues?.getOrNull(1)?.toDoubleOrNull()
            ?: document.select("span.nilaiseries, .rating strong").text().trim().toDoubleOrNull()
        val description = document.select(".synopsis-prose > p, .entry-content.seriesdesc > p, .desc > p")
            .text()
            .trim()
        val trailer = document.selectFirst("a.trailerbutton")?.attr("href")

        val tracker = APIHolder.getTracker(listOf(title), TrackerType.getTypes(type), year, true)
        val malId = tracker?.malId

        var animeMetaData: MetaAnimeData? = null
        var tmdbid: Int? = null
        var kitsuid: String? = null

        if (malId != null) {
            try {
                val syncMetaData = app.get("https://api.ani.zip/mappings?mal_id=$malId").text
                animeMetaData = parseAnimeData(syncMetaData)
                tmdbid = animeMetaData?.mappings?.themoviedbId
                kitsuid = animeMetaData?.mappings?.kitsuId
            } catch (e: Exception) {}
        }

        val logoUrl = fetchTmdbLogoUrl(
            tmdbAPI = "https://api.themoviedb.org/3",
            apiKey = "98ae14df2b8d8f8f8136499daf79f0e0",
            type = type,
            tmdbId = tmdbid,
            appLangCode = "en"
        )

        val backgroundposter = animeMetaData?.images?.find { it.coverType == "Fanart" }?.url ?: tracker?.cover

        val extractedEpisodes = if (document.select(".episode-list-items a.episode-item").isNotEmpty()) {
            document.select(".episode-list-items a.episode-item")
                .mapNotNull {
                    val epText = it.selectFirst(".ep-title, .episode-title, span")?.text()?.trim()
                        ?: it.text().trim()
                    val episode = Regex("Episode\\s*(\\d+)", RegexOption.IGNORE_CASE)
                        .find(epText)?.groupValues?.getOrNull(1)
                        ?.toIntOrNull()
                        ?: Regex("(\\d+)").find(epText)?.groupValues?.getOrNull(1)?.toIntOrNull()
                    val link = it.attr("href").ifBlank { it.attr("data-episode-url") }
                        .takeIf { value -> value.isNotBlank() }
                        ?.let { value -> fixUrl(value) }
                        ?: return@mapNotNull null

                    newEpisode(link) { this.episode = episode }
                }
                .sortedBy { it.episode ?: Int.MAX_VALUE }
        } else if (document.select("button.buttfilter").isNotEmpty()) {
            val id = document.select("input[name=series_id]").attr("value")
            val numEp =
                document.selectFirst(".latestepisode > a")?.text()?.replace(Regex("\\D"), "")
                    .toString()
            Jsoup.parse(
                app.post(
                    url = "$mainUrl/wp-admin/admin-ajax.php",
                    interceptor = turnstileInterceptor,
                    data = mapOf(
                        "misha_number_of_results" to numEp,
                        "misha_order_by" to "date-DESC",
                        "action" to "mishafilter",
                        "series_id" to id
                    )
                ).parsed<EpResponse>().content
            ).select("li").map {
                val episode = Regex("Episode\\s?(\\d+)").find(
                    it.selectFirst("a")?.text().toString()
                )?.groupValues?.getOrNull(1) ?: it.selectFirst("a")?.text()
                val link = fixUrl(it.selectFirst("a")!!.attr("href"))
                newEpisode(link) { this.episode = episode?.toIntOrNull() }
            }.reversed()
        } else {
            document.select("ul.misha_posts_wrap2 > li, .lstepi > li, .episodelist > ul > li").map {
                val episode = Regex("Episode\\s?(\\d+)").find(
                    it.selectFirst("a")?.text().toString()
                )?.groupValues?.getOrNull(1) ?: it.selectFirst("a")?.text()
                val link = it.select("a").attr("href")
                newEpisode(link) { this.episode = episode?.toIntOrNull() }
            }.reversed()
        }

        val episodes = extractedEpisodes.amap { ep ->
            var episodeNum = ep.episode
            
            if (type == TvType.AnimeMovie && episodeNum == null) {
                episodeNum = 1
            }

            val episodeKey = episodeNum?.toString()
            val metaEp = if (episodeKey != null) animeMetaData?.episodes?.get(episodeKey) else null

            val epOverview = metaEp?.overview
            val finalOverview = if (!epOverview.isNullOrBlank()) {
                epOverview
            } else {
                "Synopsis not yet available."
            }

            ep.apply {
                this.name = if (type == TvType.AnimeMovie) {
                    animeMetaData?.titles?.get("en") ?: animeMetaData?.titles?.get("ja") ?: title
                } else {
                    metaEp?.title?.get("en") ?: metaEp?.title?.get("ja") ?: ep.name
                }
                this.episode = episodeNum
                this.score = Score.from10(metaEp?.rating)
                this.posterUrl = metaEp?.image ?: animeMetaData?.images?.firstOrNull()?.url ?: ""
                this.description = finalOverview
                this.addDate(metaEp?.airDateUtc)
                this.runTime = metaEp?.runtime
            }
        }

        val recommendations = document.select(".related a.as-anime-card, .result > li, .animeseries").mapNotNull {
            it.toSearchResult()
        }

        val apiDescription = animeMetaData?.description?.replace(Regex("<.*?>"), "")
        val rawPlot = apiDescription ?: animeMetaData?.episodes?.get("1")?.overview
        
        val finalPlot = if (!rawPlot.isNullOrBlank()) {
            rawPlot
        } else {
            description
        }

        return newAnimeLoadResponse(title, url, TvType.Anime) {
            this.engName = animeMetaData?.titles?.get("en") ?: title
            this.japName = animeMetaData?.titles?.get("ja") ?: animeMetaData?.titles?.get("x-jat")
            this.posterUrl = tracker?.image ?: poster
            this.backgroundPosterUrl = backgroundposter
            try { this.logoUrl = logoUrl } catch(_:Throwable){}
            this.year = year
            addEpisodes(DubStatus.Subbed, episodes)
            this.showStatus = status
            this.score = rating?.let { Score.from10(it) } ?: Score.from10(animeMetaData?.episodes?.get("1")?.rating)
            this.plot = finalPlot
            addTrailer(trailer)
            this.tags = tags
            this.recommendations = recommendations
            addMalId(malId)
            addAniListId(tracker?.aniId?.toIntOrNull())
            try { addKitsuId(kitsuid) } catch(_:Throwable){}
        }

    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val document = app.get(data, interceptor = turnstileInterceptor).document
        val iframeLinks = linkedSetOf<String>()

        fun normalizeUrl(value: String?): String? {
            val raw = value
                ?.trim()
                ?.replace("\\/", "/")
                ?.replace("&amp;", "&")
                ?.takeIf { it.isNotBlank() }
                ?: return null
            return when {
                raw.startsWith("about:") -> null
                raw.startsWith("http://") || raw.startsWith("https://") -> raw
                else -> fixUrl(raw)
            }
        }

        fun collectIframes(doc: Document) {
            doc.select("iframe").forEach { iframe ->
                normalizeUrl(iframe.attr("data-src").ifEmpty { iframe.attr("src") })?.let { iframeLinks.add(it) }
            }
        }

        collectIframes(document)

        val ajaxConfigScript = document.selectFirst("script#ajax_video-js-extra")
            ?.attr("src")
            ?.substringAfter("base64,", "")
            ?.takeIf { it.isNotBlank() }
            ?.let { encoded -> base64Decode(encoded) }
            .orEmpty()
            
        val ajaxUrl = Regex("\"url\"\\s*:\\s*\"([^\"]+)\"")
            .find(ajaxConfigScript)
            ?.groupValues
            ?.getOrNull(1)
            ?.replace("\\/", "/")
            ?.let { fixUrl(it) }
            ?: "$mainUrl/wp-admin/admin-ajax.php"
            
        val nonce = Regex("\"nonce\"\\s*:\\s*\"([^\"]+)\"")
            .find(ajaxConfigScript)
            ?.groupValues
            ?.getOrNull(1)

        if (!nonce.isNullOrBlank()) {
            document.select(".serverplayer[data-post][data-type][data-nume], .container1 > ul > li[data-post][data-type][data-nume]")
                .forEach { serverItem ->
                    val dataPost = serverItem.attr("data-post")
                    val dataNume = serverItem.attr("data-nume")
                    val serverName = serverItem.attr("data-type").lowercase()
                    if (dataPost.isBlank() || dataNume.isBlank() || serverName.isBlank()) return@forEach

                    val response = app.post(
                        url = ajaxUrl,
                        interceptor = turnstileInterceptor,
                        data = mapOf(
                            "action" to "player_ajax",
                            "nonce" to nonce,
                            "serverName" to serverName,
                            "nume" to dataNume,
                            "post" to dataPost,
                        ),
                        referer = data,
                        headers = mapOf(
                            "X-Requested-With" to "XMLHttpRequest",
                            "Origin" to mainUrl,
                        )
                    ).text
                    collectIframes(Jsoup.parse(response))
                }
        }

        iframeLinks.toList().amap { link ->
            val nestedLink = if (link.contains("/video-frame/") || link.contains("/video-embed/")) {
                app.get(link, referer = data, interceptor = turnstileInterceptor).document.selectFirst("iframe")?.let { iframe -> 
                    normalizeUrl(iframe.attr("data-src").ifEmpty { iframe.attr("src") })
                }
            } else {
                null
            }
            loadExtractor(nestedLink ?: link, "$mainUrl/", subtitleCallback, callback)
        }
        
        document.select(".listlink a").amap { a ->
            val href = normalizeUrl(a.attr("href"))
            if (href != null && !href.contains("javascript", true)) {
                try {
                    val realUrl = app.get(href, allowRedirects = true, interceptor = turnstileInterceptor).url
                    if (realUrl != href && realUrl.isNotBlank()) {
                        loadExtractor(realUrl, "$mainUrl/", subtitleCallback, callback)
                    }
                } catch (e: Exception) {
                    // Ignore redirect failures
                }
            }
        }

        return true
    }

    private fun getBaseUrl(url: String): String {
        return URI(url).let {
            "${it.scheme}://${it.host}"
        }
    }

    private fun Element.getImageAttr(): String? {
        return when {
            this.hasAttr("data-src") -> this.attr("abs:data-src")
            this.hasAttr("data-lazy-src") -> this.attr("abs:data-lazy-src")
            this.hasAttr("srcset") -> this.attr("abs:srcset").substringBefore(" ")
            else -> this.attr("abs:src")
        }
    }

    private data class EpResponse(
        @JsonProperty("posts") val posts: String?,
        @JsonProperty("max_page") val max_page: Int?,
        @JsonProperty("found_posts") val found_posts: Int?,
        @JsonProperty("content") val content: String
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class MetaImage(
        @JsonProperty("coverType") val coverType: String?,
        @JsonProperty("url") val url: String?
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class MetaEpisode(
        @JsonProperty("episode") val episode: String?,
        @JsonProperty("airDateUtc") val airDateUtc: String?,
        @JsonProperty("runtime") val runtime: Int?,
        @JsonProperty("image") val image: String?,
        @JsonProperty("title") val title: Map<String, String>?,
        @JsonProperty("overview") val overview: String?,
        @JsonProperty("rating") val rating: String?,
        @JsonProperty("finaleType") val finaleType: String?
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class MetaAnimeData(
        @JsonProperty("titles") val titles: Map<String, String>?,
        @JsonProperty("description") val description: String?,
        @JsonProperty("images") val images: List<MetaImage>?,
        @JsonProperty("episodes") val episodes: Map<String, MetaEpisode>?,
        @JsonProperty("mappings") val mappings: MetaMappings? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class MetaMappings(
        @JsonProperty("themoviedb_id") val themoviedbId: Int? = null,
        @JsonProperty("kitsu_id") val kitsuId: String? = null
    )

    private fun parseAnimeData(jsonString: String): MetaAnimeData? {
        return try {
            val objectMapper = ObjectMapper()
            objectMapper.readValue(jsonString, MetaAnimeData::class.java)
        } catch (_: Exception) {
            null
        }
    }
}

suspend fun fetchTmdbLogoUrl(
    tmdbAPI: String,
    apiKey: String,
    type: TvType,
    tmdbId: Int?,
    appLangCode: String?
): String? {
    if (tmdbId == null) return null

    val url = if (type == TvType.AnimeMovie)
        "$tmdbAPI/movie/$tmdbId/images?api_key=$apiKey"
    else
        "$tmdbAPI/tv/$tmdbId/images?api_key=$apiKey"

    val json = runCatching { JSONObject(app.get(url).text) }.getOrNull() ?: return null
    val logos = json.optJSONArray("logos") ?: return null
    if (logos.length() == 0) return null

    val lang = appLangCode?.trim()?.lowercase()

    fun path(o: JSONObject) = o.optString("file_path")
    fun isSvg(o: JSONObject) = path(o).endsWith(".svg", true)
    fun urlOf(o: JSONObject) = "https://image.tmdb.org/t/p/w500${path(o)}"

    var svgFallback: JSONObject? = null

    for (i in 0 until logos.length()) {
        val logo = logos.optJSONObject(i) ?: continue
        val p = path(logo)
        if (p.isBlank()) continue

        val l = logo.optString("iso_639_1").trim().lowercase()
        if (l == lang) {
            if (!isSvg(logo)) return urlOf(logo)
            if (svgFallback == null) svgFallback = logo
        }
    }
    svgFallback?.let { return urlOf(it) }

    var best: JSONObject? = null
    var bestSvg: JSONObject? = null

    fun voted(o: JSONObject) = o.optDouble("vote_average", 0.0) > 0 && o.optInt("vote_count", 0) > 0
    fun better(a: JSONObject?, b: JSONObject): Boolean {
        if (a == null) return true
        val aAvg = a.optDouble("vote_average", 0.0)
        val aCnt = a.optInt("vote_count", 0)
        val bAvg = b.optDouble("vote_average", 0.0)
        val bCnt = b.optInt("vote_count", 0)
        return bAvg > aAvg || (bAvg == aAvg && bCnt > aCnt)
    }

    for (i in 0 until logos.length()) {
        val logo = logos.optJSONObject(i) ?: continue
        if (!voted(logo)) continue

        if (isSvg(logo)) {
            if (better(bestSvg, logo)) bestSvg = logo
        } else {
            if (better(best, logo)) best = logo
        }
    }

    best?.let { return urlOf(it) }
    bestSvg?.let { return urlOf(it) }

    return null
}
