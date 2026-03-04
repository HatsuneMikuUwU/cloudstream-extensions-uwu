package com.animesail

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addAniListId
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId
import com.lagradost.cloudstream3.mvvm.safeApiCall
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.nicehttp.NiceResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

class TurnstileInterceptor(private val targetCookie: String = "_as_turnstile") : Interceptor {
    @SuppressLint("SetJavaScriptEnabled")
    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()
        val url = originalRequest.url.toString()
        val cookieManager = CookieManager.getInstance()

        var currentCookies = cookieManager.getCookie(url) ?: ""
        var userAgent = originalRequest.header("User-Agent") ?: ""

        var response: Response? = null
        var needsRefresh = false

        if (!currentCookies.contains(targetCookie)) {
            needsRefresh = true
        } else {
            val requestBuilder = originalRequest.newBuilder()
                .header("Cookie", currentCookies)
            response = chain.proceed(requestBuilder.build())

            if (response.code == 403 || response.code == 503) {
                needsRefresh = true
                response.close()
            }
        }

        if (needsRefresh) {
            runBlocking(Dispatchers.Main) {
                val context = AcraApplication.context
                if (context != null) {
                    val webView = WebView(context)
                    
                    webView.settings.javaScriptEnabled = true
                    webView.settings.domStorageEnabled = true
                    webView.settings.userAgentString = userAgent.ifBlank { webView.settings.userAgentString }
                    userAgent = webView.settings.userAgentString 

                    webView.webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView?, url: String?) {
                            super.onPageFinished(view, url)
                        }
                    }

                    cookieManager.setCookie(url, "$targetCookie=; Max-Age=0")

                    webView.loadUrl(url)

                    var attempts = 0
                    val maxAttempts = 15
                    while (attempts < maxAttempts) {
                        delay(1000)
                        val checkCookies = cookieManager.getCookie(url) ?: ""
                        if (checkCookies.contains(targetCookie)) {
                            break
                        }
                        attempts++
                    }

                    webView.stopLoading()
                    webView.destroy()
                }
            }

            currentCookies = cookieManager.getCookie(url) ?: ""
            val newRequestBuilder = originalRequest.newBuilder()
                .header("User-Agent", userAgent)
                .header("Cookie", currentCookies)
            
            response = chain.proceed(newRequestBuilder.build())
        }

        return response ?: chain.proceed(originalRequest)
    }
}

class AnimeSailProvider : MainAPI() {
    override var mainUrl = "https://154.26.137.28"
    override var name = "AnimeSail"
    override val hasMainPage = true
    override var lang = "id"
    override val hasDownloadSupport = true

    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.OVA
    )

    companion object {
        fun getType(t: String): TvType {
            return if (t.contains("OVA", true) || t.contains("Special")) TvType.OVA
            else if (t.contains("Movie", true)) TvType.AnimeMovie
            else TvType.Anime
        }

        fun getStatus(t: String): ShowStatus {
            return when (t) {
                "Completed" -> ShowStatus.Completed
                "Ongoing" -> ShowStatus.Ongoing
                else -> ShowStatus.Completed
            }
        }
    }

    private suspend fun request(url: String, ref: String? = null): NiceResponse {
        return app.get(
            url,
            interceptor = TurnstileInterceptor("_as_turnstile"),
            headers = mapOf(
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
                "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Mobile Safari/537.36"
            ),
            referer = ref
        )
    }

    override val mainPage = mainPageOf(
        "$mainUrl/rilisan-anime-terbaru/page/" to "Ongoing Anime",
        "$mainUrl/rilisan-donghua-terbaru/page/" to "Ongoing Donghua",
        "$mainUrl/movie-terbaru/page/" to "Movie"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = request(request.data + page).document
        val home = document.select("article").map {
            it.toSearchResult()
        }
        return newHomePageResponse(request.name, home)
    }

    private fun getProperAnimeLink(uri: String): String {
        return if (uri.contains("/anime/")) {
            uri
        } else {
            var title = uri.substringAfter("$mainUrl/")
            title = when {
                (title.contains("-episode")) && !(title.contains("-movie")) -> title.substringBefore(
                    "-episode"
                )
                (title.contains("-movie")) -> title.substringBefore("-movie")
                else -> title
            }
            "$mainUrl/anime/$title"
        }
    }

    private fun Element.toSearchResult(): AnimeSearchResponse {
        val href = getProperAnimeLink(fixUrlNull(this.selectFirst("a")?.attr("href")).toString())
        val title = this.select(".tt > h2").text().trim()
        val posterUrl = fixUrlNull(this.selectFirst("div.limit img")?.attr("src"))
        val epNum = this.selectFirst(".tt > h2")?.text()?.let {
            Regex("Episode\\s?(\\d+)").find(it)?.groupValues?.getOrNull(1)?.toIntOrNull()
        }
        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = posterUrl
            addSub(epNum)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val link = "$mainUrl/?s=$query"
        val document = request(link).document

        return document.select("div.listupd article").map {
            it.toSearchResult()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = request(url).document

        val title = document.selectFirst("h1.entry-title")?.text().toString()
            .replace("Subtitle Indonesia", "").trim()
        val poster = document.selectFirst("div.entry-content > img")?.attr("src")
        val type = getType(document.select("tbody th:contains(Tipe)").next().text().lowercase())
        val year = document.select("tbody th:contains(Dirilis)").next().text().trim().toIntOrNull()

        val episodes = document.select("ul.daftar > li").map {
            val link = fixUrl(it.select("a").attr("href"))
            val name = it.select("a").text()
            val episode = Regex("Episode\\s?(\\d+)").find(name)?.groupValues?.getOrNull(1)?.toIntOrNull()
            newEpisode(link) { this.episode = episode }
        }.reversed()

        val tracker = APIHolder.getTracker(listOf(title), TrackerType.getTypes(type), year, true)

        return newAnimeLoadResponse(title, url, type) {
            posterUrl = tracker?.image ?: poster
            backgroundPosterUrl = tracker?.cover
            this.year = year
            addEpisodes(DubStatus.Subbed, episodes)
            showStatus = getStatus(document.select("tbody th:contains(Status)").next().text().trim())
            plot = document.selectFirst("div.entry-content > p")?.text()
            this.tags = document.select("tbody th:contains(Genre)").next().select("a").map { it.text() }
            addMalId(tracker?.malId)
            addAniListId(tracker?.aniId?.toIntOrNull())
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = request(data).document
        val playerPath = "$mainUrl/utils/player/"

        document.select(".mobius > .mirror > option").amap { element ->
            safeApiCall {
                val encodedData = element.attr("data-em")
                if (encodedData.isBlank()) return@safeApiCall

                val iframe = fixUrl(Jsoup.parse(base64Decode(encodedData)).select("iframe").attr("src"))
                
                if (iframe.contains("statistic") || iframe.isBlank()) return@safeApiCall

                val quality = getIndexQuality(element.text())

                when {
                    iframe.endsWith(".mp4", ignoreCase = true) || iframe.endsWith(".m3u8", ignoreCase = true) || iframe.contains("yorudrive.com") -> {
                        val isM3u8 = iframe.endsWith(".m3u8", ignoreCase = true)
                        val hostName = try {
                            java.net.URI(iframe).host?.substringBeforeLast(".")?.substringAfterLast(".")
                                ?.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() } 
                                ?: "Direct Link"
                        } catch (e: Exception) {
                            "Direct Link"
                        }

                        callback.invoke(
                            newExtractorLink(
                                source = hostName,
                                name = hostName,
                                url = iframe,
                                type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                            ) {
                                referer = mainUrl
                                this.quality = quality
                            }
                        )
                    }

                    iframe.contains("${playerPath}popup") -> {
                        val encodedUrl = iframe.substringAfter("url=").substringBefore("&")
                        if (encodedUrl.isNotBlank()) {
                            val realUrl = java.net.URLDecoder.decode(encodedUrl, "UTF-8")
                            loadFixedExtractor(realUrl, quality, mainUrl, subtitleCallback, callback)
                        }
                    }

                    iframe.contains("${playerPath}kodir2") -> {
                        val res = request(iframe, ref = data).text
                        val scriptData = res.substringAfter("= `").substringBefore("`;")
                        val link = Jsoup.parse(scriptData).select("source").last()?.attr("src") ?: return@safeApiCall

                        callback.invoke(
                            newExtractorLink(
                                source = name,
                                name = name,
                                url = link,
                                type = INFER_TYPE
                            ) {
                                referer = mainUrl
                                this.quality = quality
                            }
                        )
                    }

                    iframe.contains("${playerPath}framezilla") || iframe.contains("uservideo.xyz") -> {
                        val link = request(iframe, ref = data).document.select("iframe").attr("src")
                        if (link.isNotBlank()) {
                            loadFixedExtractor(fixUrl(link), quality, mainUrl, subtitleCallback, callback)
                        }
                    }
                    
                    iframe.contains("aghanim.xyz/tools/redirect/") -> {
                        val id = iframe.substringAfter("id=").substringBefore("&token")
                        val link = "https://rasa-cintaku-semakin-berantai.xyz/v/$id"
                        loadFixedExtractor(link, quality, mainUrl, subtitleCallback, callback)
                    }

                    iframe.contains(playerPath) -> {
                        val link = request(iframe, ref = data).document.select("source").attr("src")
                        if (link.isBlank()) return@safeApiCall

                        val rawSource = iframe.substringAfter(playerPath).substringBefore("/").substringBefore("?")
                        
                        val sourceName = rawSource.replaceFirstChar { 
                            if (it.isLowerCase()) it.titlecase() else it.toString() 
                        }.ifBlank { name }

                        callback.invoke(
                            newExtractorLink(
                                source = sourceName,
                                name = sourceName,
                                url = link,
                                type = INFER_TYPE
                            ) {
                                referer = mainUrl
                                this.quality = quality
                            }
                        )
                    }

                    else -> {
                        loadFixedExtractor(iframe, quality, mainUrl, subtitleCallback, callback)
                    }
                }
            }
        }

        return true
    }

    private suspend fun loadFixedExtractor(
        url: String,
        quality: Int?,
        referer: String? = null,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        loadExtractor(url, referer, subtitleCallback) { link ->
            runBlocking {
                callback.invoke(
                    newExtractorLink(
                        source = link.name,
                        name = link.name,
                        url = link.url,
                        type = INFER_TYPE
                    ) {
                        this.referer = referer ?: mainUrl
                        this.quality = if (link.type == ExtractorLinkType.M3U8) link.quality else quality ?: Qualities.Unknown.value
                        this.type = link.type
                        this.headers = link.headers
                        this.extractorData = link.extractorData
                    }
                )
            }
        }
    }

    private fun getIndexQuality(str: String): Int {
        return Regex("(\\d{3,4})[pP]").find(str)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: Qualities.Unknown.value
    }
}
