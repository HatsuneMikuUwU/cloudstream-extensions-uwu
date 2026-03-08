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

        val standardUserAgent =
            "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Mobile Safari/537.36"

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
                            override fun onReceivedSslError(
                                view: WebView?,
                                handler: SslErrorHandler?,
                                error: SslError?
                            ) {
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

                        val safeLineCookies = listOf(
                            "sl-challenge-jwt",
                            "sl-challenge-server",
                            "sl-session",
                            "sl_jwt_session",
                            "sl_jwt_sign",
                            "comentario_commenter_session"
                        )
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
                val maxAttempts = 15
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

    private val jwtInterceptor = JwtSessionInterceptor("sl_jwt_session")
    private val fetch by lazy {
        Session(app.baseClient.newBuilder().addInterceptor(jwtInterceptor).build())
    }

    override val supportedTypes = setOf(
        TvType.NSFW,
    )

    companion object {
        val interceptor = JwtSessionInterceptor("sl_jwt_session")
        val session = Session(Requests().baseClient.newBuilder().addInterceptor(interceptor).build())

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
        "$mainUrl/category/hentai/" to "Hentai",
        "$mainUrl/category/jav/" to "Jav",
        "$mainUrl/category/3d-hentai/" to "3D Hentai",
        "$mainUrl/category/jav-cosplay/" to "Jav Cosplay",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = fetch.get("${request.data}/page/$page").document
        val home = document.select("div.result ul li").mapNotNull {
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
        return if (uri.contains("-episode-")) {
            val title = uri.substringAfter("$mainUrl/").substringBefore("-episode-")
                .removePrefix("new-release-").removePrefix("uncensored-")
            "$mainUrl/hentai/$title"
        } else {
            uri
        }
    }

    private fun Element.toSearchResult(): AnimeSearchResponse? {
        val title = this.selectFirst("h2 a")?.text()?.trim() ?: return null
        val href = getProperAnimeLink(this.selectFirst("a")?.attr("href") ?: return null)
        val posterUrl = fixUrlNull(this.selectFirst("img")?.attr("src"))
        val epNum = this.selectFirst("i.dot")?.text()?.filter { it.isDigit() }?.toIntOrNull()
        return newAnimeSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = posterUrl
            addSub(epNum)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return fetch.get("$mainUrl/search/$query").document.select("div.result ul li")
            .mapNotNull {
                it.toSearchResult()
            }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = fetch.get(url).document

        val title = document.selectFirst("span.desc b, div.eroinfo h1")?.text()?.trim() ?: ""
        val poster = fixUrlNull(document.selectFirst("div.imgdesc img, div.thm img")?.attr("src"))
        val table = document.select("div.listinfo ul, div.konten")
        
        val tags = table.select("li:contains(Genres) a").map { it.text() }.takeIf { it.isNotEmpty() }
            ?: table.select("p:contains(Genre)").text().substringAfter(":").split(",")
                .map { it.trim() }
                
        val year = document.selectFirst("li:contains(Tayang)")?.text()?.substringAfterLast(",")
            ?.filter { it.isDigit() }?.toIntOrNull()
            
        val status = getStatus(
            document.selectFirst("li:contains(Status)")?.text()?.substringAfter(":")?.trim()
        )
        
        val duration = document.selectFirst("li:contains(Durasi)")?.text()?.substringAfterLast(":")
            ?.filter { it.isDigit() }?.toIntOrNull()
            
        val description = document.selectFirst("span.desc p")?.text()

        val episodes = document.select("div.episodelist ul li").mapNotNull {
            val name = it.selectFirst("a")?.text()
            val link = fixUrlNull(it.selectFirst("a")?.attr("href")) ?: return@mapNotNull null
            newEpisode(link) { this.name = name }
        }.takeIf { it.isNotEmpty() } ?: listOf(newEpisode(url) { this.name = title })

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
                res.select("div#show-stream iframe").amap { iframe ->
                    loadExtractor(iframe.attr("src"), "$mainUrl/", subtitleCallback, callback)
                }
            },
            {
                res.select("div.boxdownload div.liner").map { ele ->
                    val quality = getIndexQuality(ele.select("div.name").text())
                    val ouoLink = ele.selectFirst("a:contains(ouo)")?.attr("href")
                    quality to ouoLink
                }.filter { it.first != Qualities.P360.value }.amap { (quality, ouoLink) ->
                    val bypassedAds = bypassMirrored(bypassOuo(ouoLink))
                    
                    bypassedAds.amap { adsLink ->
                        val embedUrl = fixEmbed(adsLink) ?: return@amap
                        
                        loadExtractor(
                            embedUrl,
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
                                        this.quality = if (link.type == ExtractorLinkType.M3U8) link.quality else quality
                                        headers = link.headers
                                        extractorData = link.extractorData
                                    }
                                )
                            }
                        }
                    }
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
        
        for (i in 1..2) {
            if (res.headers["location"] != null) break
            val document = res.document
            val nextUrl = document.select("form").attr("action")
            val data = document.select("form input").mapNotNull {
                it.attr("name") to it.attr("value")
            }.toMap().toMutableMap()
            
            val captchaKey = document.select("script[src*=https://www.google.com/recaptcha/api.js?render=]")
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

        return res.headers["location"]
    }

    private fun NiceResponse.selectMirror(): String? {
        return this.document.selectFirst("script:containsData(#passcheck)")?.data()
            ?.substringAfter("\"GET\", \"")?.substringBefore("\"")
    }

    private suspend fun bypassMirrored(url: String?): List<String> {
        val request = session.get(url ?: return emptyList())
        delay(2000)
        
        val mirrorUrl = request.selectMirror() ?: run {
            val nextUrl = request.document.select("div.col-sm.centered.extra-top a").attr("href")
            app.get(nextUrl).selectMirror()
        }
        
        return session.get(fixUrl(mirrorUrl ?: return emptyList(), mirroredHost))
            .document.select("table.hoverable tbody tr")
            .filter { mirror ->
                !mirrorIsBlackList(mirror.selectFirst("img")?.attr("alt"))
            }.amap {
                val fileLink = it.selectFirst("a")?.attr("href")
                session.get(
                    fixUrl(fileLink ?: return@amap null, mirroredHost)
                ).document.selectFirst("div.code_wrap code")?.text()
            }.filterNotNull()
    }

    private fun mirrorIsBlackList(host: String?): Boolean {
        return mirrorBlackList.any { it.equals(host, true) }
    }

    private fun fixUrl(url: String, domain: String): String {
        if (url.startsWith("http")) return url
        if (url.isEmpty()) return ""

        return if (url.startsWith("//")) {
            "https:$url"
        } else {
            if (url.startsWith('/')) domain + url else "$domain/$url"
        }
    }

    private fun getIndexQuality(str: String?): Int {
        return when (val quality = Regex("""(?i)\[(\d+[pk])]""").find(str ?: "")?.groupValues?.getOrNull(1)?.lowercase()) {
            "2k" -> Qualities.P1440.value
            else -> getQualityFromName(quality)
        }
    }
}
