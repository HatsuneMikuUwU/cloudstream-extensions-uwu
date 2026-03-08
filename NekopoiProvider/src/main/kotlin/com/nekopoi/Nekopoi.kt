package com.nekopoi

import android.annotation.SuppressLint
import android.net.http.SslError
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.nicehttp.NiceResponse
import com.lagradost.nicehttp.Requests
import com.lagradost.nicehttp.Session
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.Response
import org.jsoup.nodes.Element
import java.net.URI

class JwtSessionInterceptor(private val targetCookie: String = "sl_jwt_session") : Interceptor {
    @SuppressLint("SetJavaScriptEnabled")
    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()
        val url = originalRequest.url.toString()
        val domainUrl = "${originalRequest.url.scheme}://${originalRequest.url.host}"
        
        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)

        val standardUserAgent = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Mobile Safari/537.36"

        var currentCookies = cookieManager.getCookie(domainUrl) ?: ""
        var needsRefresh = false
        var initialResponse: Response? = null

        if (currentCookies.contains(targetCookie)) {
            val requestBuilder = originalRequest.newBuilder()
                .removeHeader("User-Agent")
                .addHeader("User-Agent", standardUserAgent)
                .removeHeader("Cookie")
                .addHeader("Cookie", currentCookies)

            initialResponse = chain.proceed(requestBuilder.build())

            if (initialResponse.code in listOf(403, 503, 202)) {
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
                var isResolved = false

                handler.post {
                    try {
                        val newWebView = WebView(context)
                        webView = newWebView

                        cookieManager.setAcceptThirdPartyCookies(newWebView, true)

                        newWebView.settings.apply {
                            javaScriptEnabled = true
                            domStorageEnabled = true
                            databaseEnabled = true
                            useWideViewPort = true
                            loadWithOverviewMode = true
                            mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                            cacheMode = android.webkit.WebSettings.LOAD_NO_CACHE
                            userAgentString = standardUserAgent
                        }
                        
                        newWebView.clearCache(true)
                        newWebView.clearHistory()
                        
                        newWebView.webChromeClient = WebChromeClient()
                        newWebView.webViewClient = object : WebViewClient() {
                            @SuppressLint("WebViewClientOnReceivedSslError")
                            override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) {
                                handler?.proceed() 
                            }

                            override fun onPageFinished(view: WebView?, url: String?) {
                                super.onPageFinished(view, url)
                                val checkCookies = cookieManager.getCookie(domainUrl) ?: ""
                                if (checkCookies.contains(targetCookie) && checkCookies.contains("sl_jwt_sign")) {
                                    isResolved = true
                                }
                            }
                        }

                        val safeLineCookies = listOf("sl-challenge-jwt", "sl-challenge-server", "sl-session", "sl_jwt_session", "sl_jwt_sign", "comentario_commenter_session")
                        safeLineCookies.forEach { cookie ->
                            cookieManager.setCookie(domainUrl, "$cookie=; Max-Age=0")
                        }
                        cookieManager.flush()

                        newWebView.loadUrl(url)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }

                var attempts = 0
                val maxAttempts = 25 
                while (attempts < maxAttempts) {
                    Thread.sleep(1000)
                    val checkCookies = cookieManager.getCookie(domainUrl) ?: ""

                    if ((checkCookies.contains(targetCookie) && checkCookies.contains("sl_jwt_sign")) || isResolved) {
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
                .removeHeader("User-Agent")
                .addHeader("User-Agent", standardUserAgent)
                .removeHeader("Cookie")
                .addHeader("Cookie", currentCookies)

            return chain.proceed(newRequestBuilder.build())
        }

        val finalRequest = originalRequest.newBuilder()
            .removeHeader("User-Agent")
            .addHeader("User-Agent", standardUserAgent)
            .build()
            
        return initialResponse ?: chain.proceed(finalRequest)
    }
}

class Nekopoi : MainAPI() {
    override var mainUrl = "https://nekopoi.care"
    override var name = "Nekopoi"
    override val hasMainPage = true
    override var lang = "id"
    
    private val fetch by lazy { 
        Session(app.baseClient.newBuilder().addInterceptor(JwtSessionInterceptor()).build()) 
    }
    
    override val supportedTypes = setOf(
        TvType.NSFW,
    )

    companion object {
        val session = Session(Requests().baseClient.newBuilder().addInterceptor(JwtSessionInterceptor()).build())
        
        val mirrorBlackList = arrayOf(
            "MegaupNet",
            "DropApk",
            "Racaty",
            "ZippyShare",
            "VideobinCo",
            "DropApk",
            "SendCm",
            "GoogleDrive",
        )
        const val mirroredHost = "https://www.mirrored.to"

        fun getStatus(t: String?): ShowStatus {
            return when (t) {
                "Completed" -> ShowStatus.Completed
                "Ongoing" -> ShowStatus.Ongoing
                else -> ShowStatus.Completed
            }
        }
    }

    override val mainPage = mainPageOf(
        "$mainUrl/page/" to "Episode Terbaru",
        "$mainUrl/category/hentai/page/" to "Hentai",
        "$mainUrl/category/jav/page/" to "Jav",
        "$mainUrl/category/3d-hentai/page/" to "3D Hentai",
        "$mainUrl/category/jav-cosplay/page/" to "Jav Cosplay",
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = if (request.name == "Episode Terbaru") {
            if (page == 1) mainUrl else "${mainUrl}/page/$page/"
        } else {
            "${request.data}$page"
        }

        val document = fetch.get(url).document
        val home = document.select("div.nk-post-card, div.nk-hentai-grid ul li, div.result ul li").mapNotNull {
            it.toSearchResult()
        }
        
        return newHomePageResponse(
            list = HomePageList(
                name = request.name,
                list = home,
                isHorizontalImages = true
            ),
            hasNext = true
        )
    }

    private fun getProperAnimeLink(uri: String): String {
        return if (uri.contains("-episode-") && !uri.contains("/hentai/")) {
            val title = uri.substringAfter("$mainUrl/").substringBefore("-episode-")
                .removePrefix("new-release-").removePrefix("uncensored-")
            "$mainUrl/hentai/$title/"
        } else {
            uri
        }
    }

    private fun Element.toSearchResult(): AnimeSearchResponse? {
        val titleElement = this.selectFirst("div.nk-post-meta h2 a, div.title, h2 a") ?: return null
        val title = titleElement.text().trim()
        val rawHref = titleElement.attr("href").takeIf { it.isNotBlank() } ?: this.selectFirst("a")?.attr("href") ?: return null
        val href = getProperAnimeLink(rawHref)
        
        var posterUrl = fixUrlNull(this.selectFirst("img")?.attr("src"))
        if (posterUrl == null) {
            val bgStyle = this.selectFirst("div.nk-thumb-crop, div.nk-hentai-thumb, div.nk-grid-thumb")?.attr("style")
            posterUrl = Regex("""url\('([^']+)'\)""").find(bgStyle ?: "")?.groupValues?.getOrNull(1)
        }

        val epNumStr = Regex("Episode\\s?(\\d+)").find(title)?.groupValues?.getOrNull(1) 
            ?: this.selectFirst("i.dot")?.text()?.filter { it.isDigit() }
            
        val epNum = epNumStr?.toIntOrNull()
        
        return newAnimeSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = posterUrl
            addSub(epNum)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return fetch.get("$mainUrl/?s=$query&post_type=anime").document
            .select("div.nk-post-card, div.nk-hentai-grid ul li, div.result ul li")
            .mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = fetch.get(url).document

        val title = document.selectFirst("div.nk-post-header h1, div.nk-series-header h1, span.desc b, div.eroinfo h1")?.text()?.trim() 
            ?: document.selectFirst("title")?.text()?.substringBefore(" – ")?.trim() 
            ?: ""

        var poster = fixUrlNull(document.selectFirst("div.nk-featured-img img, div.imgdesc img, div.thm img")?.attr("src"))
        if (poster == null) {
            val bgStyle = document.selectFirst("div.nk-thumb-crop, div.nk-post-thumb, div.nk-series-thumb")?.attr("style")
            poster = fixUrlNull(Regex("""url\('([^']+)'\)""").find(bgStyle ?: "")?.groupValues?.getOrNull(1))
        }

        val table = document.select("div.listinfo ul, div.konten")
        
        val tags = table.select("li:contains(Genres) a").map { it.text() }.takeIf { it.isNotEmpty() }
            ?: table.select("p:contains(Genre)").text().substringAfter(":").split(",")
                .map { it.trim() }.filter { it.isNotBlank() }
                
        val year = document.selectFirst("li:contains(Tayang)")?.text()?.substringAfterLast(",")
            ?.filter { it.isDigit() }?.toIntOrNull()
            
        val status = getStatus(
            document.selectFirst("li:contains(Status)")?.text()?.substringAfter(":")?.trim()
        )
        
        val duration = table.select("li:contains(Durasi), p:contains(Duration)").text().substringAfterLast(":")
            .filter { it.isDigit() }.toIntOrNull()
            
        val description = table.select("p:contains(Sinopsis) + p").text().takeIf { it.isNotBlank() } 
            ?: document.selectFirst("span.desc p")?.text()

        val mainContent = document.selectFirst("div.nk-main-content, div#nk-content") ?: document
        val episodeElements = mainContent.select("div.episodelist ul li, div.nk-episode-nav a, ul.nk-episode-list li a, div.nk-post-card")

        var episodes = episodeElements.mapNotNull {
            if (it.hasClass("nk-post-card")) {
                val aTag = it.selectFirst("div.nk-post-meta h2 a") ?: return@mapNotNull null
                newEpisode(aTag.attr("href")) { this.name = aTag.text().trim() }
            } else {
                val name = it.text().trim()
                val link = fixUrlNull(it.attr("href").takeIf { href -> href.isNotBlank() } ?: it.selectFirst("a")?.attr("href"))
                if (link != null) newEpisode(link) { this.name = name } else null
            }
        }.distinctBy { it.url }

        if (episodes.isEmpty()) {
            episodes = listOf(newEpisode(url) { this.name = title })
        }

        return newAnimeLoadResponse(title, url, TvType.NSFW) {
            engName = title
            posterUrl = poster
            this.year = year
            this.duration = duration
            addEpisodes(DubStatus.Subbed, episodes)
            showStatus = status
            plot = description
            this.tags = tags
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val res = fetch.get(data).document

        runAllAsync(
            {
                res.select("div.nk-player-frame iframe, div#show-stream iframe").amap { iframe ->
                    loadExtractor(iframe.attr("src"), "$mainUrl/", subtitleCallback, callback)
                }
            },
            {
                res.select("div.nk-download-row, div.boxdownload div.liner").mapNotNull { ele ->
                    val qualityStr = ele.selectFirst("div.nk-download-name, div.name")?.text()
                    val quality = getIndexQuality(qualityStr)
                    
                    val link = ele.select("a").firstOrNull { it.text().contains("Mirror", true) }?.attr("href")
                        ?: ele.selectFirst("a[href*=ouo]")?.attr("href")
                        
                    if (link != null) quality to link else null
                }.filter { 
                    it.first != Qualities.P360.value 
                }.map { qualityAndLink ->
                    val bypassedAds = bypassMirrored(bypassOuo(qualityAndLink.second))
                    bypassedAds.amap(ads@{ adsLink ->
                                        loadExtractor(
                                            fixEmbed(adsLink) ?: return@ads,
                                            "$mainUrl/",
                                            subtitleCallback,
                                        ) { link ->
                                            runBlocking {
                                                callback.invoke(
                                                    newExtractorLink(
                                                        link.name,
                                                        link.name,
                                                        link.url,
                                                        link.type
                                                    ) {
                                                        referer = link.referer
                                                        quality = if (link.type == ExtractorLinkType.M3U8) link.quality else qualityAndLink.first
                                                        headers = link.headers
                                                        extractorData = link.extractorData
                                                    }
                                                )
                                            }
                                        }
                                    })
                }
            }
        )

        return true
    }

    private fun fixEmbed(url: String?): String? {
        if (url == null) return null
        val host = getBaseUrl(url)
        return when {
            url.contains("streamsb", true) -> url.replace("$host/", "$host/e/")
            else -> url
        }
    }

    private fun getBaseUrl(url: String): String {
        return URI(url).let {
            "${it.scheme}://${it.host}"
        }
    }

    private suspend fun bypassOuo(url: String?): String? {
        var res = session.get(url ?: return null)
        run lit@{
            (1..2).forEach { _ ->
                if (res.headers["location"] != null) return@lit
                val document = res.document
                val nextUrl = document.select("form").attr("action")
                val data = document.select("form input").mapNotNull {
                    it.attr("name") to it.attr("value")
                }.toMap().toMutableMap()
                val captchaKey =
                    document.select("script[src*=https://www.google.com/recaptcha/api.js?render=]")
                        .attr("src").substringAfter("render=")
                val token = APIHolder.getCaptchaToken(url, captchaKey)
                data["x-token"] = token ?: ""
                res = session.post(
                    nextUrl,
                    data = data,
                    headers = mapOf("content-type" to "application/x-www-form-urlencoded"),
                    allowRedirects = false
                )
            }
        }

        return res.headers["location"]
    }

    private fun NiceResponse.selectMirror(): String? {
        return this.document.selectFirst("script:containsData(#passcheck)")?.data()
            ?.substringAfter("\"GET\", \"")?.substringBefore("\"")
    }

    private suspend fun bypassMirrored(url: String?): List<String?> {
        val request = session.get(url ?: return emptyList())
        delay(2000)
        val mirrorUrl = request.selectMirror() ?: run {
            val nextUrl = request.document.select("div.col-sm.centered.extra-top a").attr("href")
            app.get(nextUrl).selectMirror()
        }
        return session.get(
                fixUrl(
                    mirrorUrl ?: return emptyList(),
                    mirroredHost
                )
            ).document.select("table.hoverable tbody tr")
                .filter { mirror ->
                    !mirrorIsBlackList(mirror.selectFirst("img")?.attr("alt"))
                }.amap {
                val fileLink = it.selectFirst("a")?.attr("href")
                session.get(
                    fixUrl(
                        fileLink ?: return@amap null,
                        mirroredHost
                    )
                ).document.selectFirst("div.code_wrap code")?.text()
            }
    }

    private fun mirrorIsBlackList(host: String?): Boolean {
        return mirrorBlackList.any { it.equals(host, true) }
    }

    private fun fixUrl(url: String, domain: String): String {
        if (url.startsWith("http")) {
            return url
        }
        if (url.isEmpty()) {
            return ""
        }

        val startsWithNoHttp = url.startsWith("//")
        if (startsWithNoHttp) {
            return "https:$url"
        } else {
            if (url.startsWith('/')) {
                return domain + url
            }
            return "$domain/$url"
        }
    }

    private fun getIndexQuality(str: String?): Int {
        return when (val quality =
            Regex("""(?i)\[(\d+[pk])]""").find(str ?: "")?.groupValues?.getOrNull(1)?.lowercase()) {
            "2k" -> Qualities.P1440.value
            else -> getQualityFromName(quality)
        }
    }
}
