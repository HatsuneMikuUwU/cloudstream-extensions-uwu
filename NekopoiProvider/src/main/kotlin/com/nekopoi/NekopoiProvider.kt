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
import kotlinx.coroutines.withTimeoutOrNull
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

class NekopoiProvider : MainAPI() {
    override var mainUrl = "https://nekopoi.care"
    override var name = "NekoPoi"
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
            "MegaupNet", "DropApk", "Racaty", "ZippyShare",
            "VideobinCo", "SendCm", "GoogleDrive",
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
        "$mainUrl/category/2d-animation/" to "2D Animation",
        "$mainUrl/category/3d-hentai/" to "3D Hentai",
        "$mainUrl/category/jav/" to "JAV",
        "$mainUrl/category/jav-cosplay/" to "JAV Cosplay"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = if (page <= 1) {
            request.data
        } else {
            "${request.data.removeSuffix("/")}/page/$page/"
        }

        val document = fetch.get(url).document
        val home = document.select(
            "div.nk-post-card, " +
            "div.nk-hentai-grid ul li, " +
            "div.result ul li, " +
            "div.nk-search-results ul li, " +
            "article"
        ).mapNotNull {
            it.toSearchResult()
        }
        
        return newHomePageResponse(
            list = HomePageList(
                name = request.name,
                list = home,
                isHorizontalImages = true
            ),
            hasNext = home.isNotEmpty()
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
        val searchItem = this.selectFirst("a.nk-search-item")
        if (searchItem != null) {
            val title = searchItem.selectFirst("h2, h3")?.text()?.trim() ?: return null
            val rawHref = searchItem.attr("href").takeIf { it.isNotBlank() } ?: return null
            val href = getProperAnimeLink(rawHref)
            val bgStyle = searchItem.selectFirst("div.nk-search-thumb")?.attr("style")
            val posterUrl = Regex("""url\(['"]?([^'"()]+)['"]?\)""").find(bgStyle ?: "")?.groupValues?.getOrNull(1)
            val epNum = Regex("Episode\\s?(\\d+)").find(title)?.groupValues?.getOrNull(1)?.toIntOrNull()
            return newAnimeSearchResponse(title, href, TvType.NSFW) {
                this.posterUrl = posterUrl
                addSub(epNum)
            }
        }

        val seriesLink = this.selectFirst("a.nk-series-link")
        if (seriesLink != null) {
            val title = seriesLink.selectFirst("div.title")?.text()?.trim()
                ?: seriesLink.text().trim().takeIf { it.isNotBlank() }
                ?: return null
            val href = getProperAnimeLink(seriesLink.attr("href").takeIf { it.isNotBlank() } ?: return null)
            val bgStyle = seriesLink.selectFirst("div.nk-hentai-thumb, div.nk-thumb-crop, div.nk-grid-thumb")?.attr("style")
            val posterUrl = Regex("""url\(['"]?([^'"()]+)['"]?\)""").find(bgStyle ?: "")?.groupValues?.getOrNull(1)
            val epNum = Regex("""Episode\s?(\d+)""").find(title)?.groupValues?.getOrNull(1)?.toIntOrNull()
            return newAnimeSearchResponse(title, href, TvType.NSFW) {
                this.posterUrl = posterUrl
                addSub(epNum)
            }
        }

        val titleElement = this.selectFirst("div.nk-post-meta h2 a, div.title a, h2 a, h3 a, .entry-title a")
            ?: return null
        val title = titleElement.text().trim().takeIf { it.isNotBlank() } ?: return null
        val rawHref = titleElement.attr("href").takeIf { it.isNotBlank() }
            ?: this.selectFirst("a")?.attr("href")
            ?: return null
        val href = getProperAnimeLink(rawHref)

        var posterUrl = fixUrlNull(
            this.selectFirst("img")?.attr("data-src")?.takeIf { it.isNotBlank() }
                ?: this.selectFirst("img")?.attr("src")
        )
        if (posterUrl == null) {
            val bgStyle = this.selectFirst("div.nk-thumb-crop, div.nk-hentai-thumb, div.nk-grid-thumb")?.attr("style")
            posterUrl = Regex("""url\(['"]?([^'"()]+)['"]?\)""").find(bgStyle ?: "")?.groupValues?.getOrNull(1)
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
            
        val description =
            document.selectFirst("div.konten p:contains(Sinopsis) + p, div.listinfo p:contains(Sinopsis) + p")?.text()?.takeIf { it.isNotBlank() }
            ?: document.select("div.konten > p:not(.separator)")
                .firstOrNull { p ->
                    val t = p.text().trim()
                    t.isNotBlank()
                        && !p.selectFirst("b") .let { it != null && it.text().length > 2 }
                        && !t.startsWith("Genre") && !t.startsWith("Producer")
                        && !t.startsWith("Duration") && !t.startsWith("Durasi")
                        && !t.startsWith("Size") && !t.startsWith("Catatan")
                }?.text()
            ?: document.selectFirst("meta[property=og:description]")?.attr("content")
                ?.removePrefix("Sinopsis ")?.trim()?.takeIf { it.isNotBlank() }
            ?: document.selectFirst("span.desc p")?.text()

        val mainContent = document.selectFirst("div.nk-main-content, div#nk-content, div#nk-wrap") ?: document

        val episodeGridItems = document.select("div.nk-episode-grid ul li a.nk-episode-card")

        val rawEpisodes = if (episodeGridItems.isNotEmpty()) {
            episodeGridItems.mapNotNull { a ->
                val link = fixUrlNull(a.attr("href").takeIf { it.isNotBlank() }) ?: return@mapNotNull null
                val name = a.selectFirst("span.nk-episode-card-title")?.text()?.trim()
                    ?: a.selectFirst("span.nk-episode-badge")?.text()?.trim()
                    ?: a.text().trim()
                newEpisode(link) { this.name = name }
            }
        } else {
            mainContent.select("div.episodelist ul li, div.nk-episode-nav a, ul.nk-episode-list li a, div.nk-post-card").mapNotNull {
                if (it.hasClass("nk-post-card")) {
                    val aTag = it.selectFirst("div.nk-post-meta h2 a") ?: return@mapNotNull null
                    val rawName = aTag.text().trim()
                        .removePrefix("[NEW Release] ")
                        .removePrefix("[NEW Release]")
                        .replace(Regex("""(?i)^\[(?:NEW Release|3D|L2D|VR)\]\s*"""), "")
                        .replace(Regex("""(?i)\s+Subtitle Indonesia$"""), "")
                        .trim()
                    newEpisode(aTag.attr("href")) { this.name = rawName }
                } else {
                    val name = it.text().trim()
                    val link = fixUrlNull(it.attr("href").takeIf { h -> h.isNotBlank() } ?: it.selectFirst("a")?.attr("href"))
                    if (link != null) newEpisode(link) { this.name = name } else null
                }
            }
        }
        
        val episodes = rawEpisodes.distinctBy { ep -> ep.data }

        val finalEpisodes = if (episodes.isEmpty()) {
            listOf(newEpisode(url) { this.name = title })
        } else episodes

        return newAnimeLoadResponse(title, url, TvType.NSFW) {
            engName = title
            posterUrl = poster
            this.year = year
            this.duration = duration
            addEpisodes(DubStatus.Subbed, finalEpisodes)
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
                res.select("div.nk-player-frame iframe").amap { iframe ->
                    val src = iframe.attr("src").takeIf { it.isNotBlank() } ?: return@amap
                    
                    withTimeoutOrNull(15_000) {
                        val loaded = loadExtractor(src, "$mainUrl/", subtitleCallback) { link ->
                            runBlocking {
                                callback.invoke(
                                    newExtractorLink(link.name, link.name, link.url, link.type) {
                                        referer = link.referer
                                        this.quality = link.quality
                                        headers = link.headers
                                        extractorData = link.extractorData
                                    }
                                )
                            }
                        }
                        
                        if (!loaded) {
                            extractCustomHost(src, subtitleCallback, callback, Qualities.Unknown.value)
                        }
                    }
                }
            },
            {
                res.select("div.nk-download-row").amap { row ->
                    val qualityStr = row.selectFirst("div.nk-download-name")?.text()
                    val quality = getIndexQuality(qualityStr)

                    row.select("div.nk-download-links a").amap { a ->
                        val linkName = a.text().trim()
                        val ouoUrl = a.attr("href")

                        if (ouoUrl.contains("ouo.io") || ouoUrl.contains("ouo.press")) {
                            val realUrl = withTimeoutOrNull(15_000) { bypassOuo(ouoUrl) } ?: return@amap
                            
                            if (linkName.equals("Mirror", ignoreCase = true)) {
                                val bypassedAds = withTimeoutOrNull(15_000) { bypassMirrored(realUrl) } ?: return@amap
                                bypassedAds.amap ads@{ adsLink ->
                                    val fixedEmbed = fixEmbed(adsLink) ?: return@ads
                                    loadExtractor(fixedEmbed, "$mainUrl/", subtitleCallback) { link ->
                                        runBlocking {
                                            callback.invoke(
                                                newExtractorLink(link.name, link.name, link.url, link.type) {
                                                    referer = link.referer
                                                    this.quality = if (link.type == ExtractorLinkType.M3U8) link.quality else quality
                                                    headers = link.headers
                                                    extractorData = link.extractorData
                                                }
                                            )
                                        }
                                    }
                                }
                            } else {
                                loadExtractor(realUrl, "$mainUrl/", subtitleCallback) { link ->
                                    runBlocking {
                                        callback.invoke(
                                            newExtractorLink(link.name, link.name, link.url, link.type) {
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
                }
            }
        )

        return true
    }

    private suspend fun extractCustomHost(
        iframeSrc: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        quality: Int = Qualities.Unknown.value
    ) {
        try {
            val html = fetch.get(
                iframeSrc,
                headers = mapOf(
                    "Referer" to mainUrl,
                    "Accept-Language" to "en-US,en;q=0.5"
                )
            ).text

            val videoUrl = 
                Regex("""(?i)(?:source|file|src|url)["']?\s*[:=]\s*["'](https?://[^"']+\.(?:m3u8|mp4)[^"']*)["']""").find(html)?.groupValues?.get(1)
                ?: Regex("""(https?://[^"']+\.(?:m3u8|mp4)[^"']*)""").find(html)?.groupValues?.get(1)

            if (videoUrl != null) {
                val isM3u8 = videoUrl.contains(".m3u8", ignoreCase = true)
                val hostName = try { URI(iframeSrc).host } catch (e: Exception) { "CustomServer" }
                
                callback.invoke(
                    newExtractorLink(
                        hostName,
                        hostName,
                        videoUrl,
                        if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    ) {
                        referer = iframeSrc
                        this.quality = if (isM3u8) Qualities.Unknown.value else quality
                        headers = mapOf("Referer" to iframeSrc)
                    }
                )
                return
            }

            val nestedSrc = Regex("""<iframe[^>]+src=['"](https?://[^'"]+)['"]""").find(html)?.groupValues?.get(1)
            if (nestedSrc != null && nestedSrc != iframeSrc) {
                loadExtractor(nestedSrc, iframeSrc, subtitleCallback, callback)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
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
        return URI(url).let { "${it.scheme}://${it.host}" }
    }

    private suspend fun bypassOuo(url: String?): String? {
        if (url.isNullOrBlank()) return null
        var res: NiceResponse? = null
        try {
            res = session.get(url)
            
            if (res.headers["location"] != null) return res.headers["location"]

            run lit@{
                (1..2).forEach { _ ->
                    val document = res?.document ?: return@lit
                    val form = document.selectFirst("form") ?: return@lit
                    
                    var nextUrl = form.attr("action")
                    if (!nextUrl.startsWith("http")) {
                        val uri = URI(res?.url ?: url)
                        nextUrl = "${uri.scheme}://${uri.host}$nextUrl"
                    }

                    val data = form.select("input").associate {
                        it.attr("name") to it.attr("value")
                    }.toMutableMap()

                    val captchaKey = document.selectFirst("script[src*=https://www.google.com/recaptcha/api.js?render=]")
                        ?.attr("src")?.substringAfter("render=")
                    
                    if (captchaKey != null) {
                        val token = APIHolder.getCaptchaToken(nextUrl, captchaKey)
                        data["x-token"] = token ?: ""
                    }

                    res = session.post(
                        nextUrl,
                        data = data,
                        headers = mapOf(
                            "Content-Type" to "application/x-www-form-urlencoded",
                            "Referer" to (res?.url ?: url),
                            "Origin" to "https://ouo.io"
                        ),
                        allowRedirects = false
                    )
                    
                    if (res?.headers?.get("location") != null) {
                        return res?.headers?.get("location")
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        
        return res?.headers?.get("location")
    }

    private fun NiceResponse.selectMirror(): String? {
        return this.document.selectFirst("script:containsData(#passcheck)")?.data()
            ?.substringAfter("\"GET\", \"")?.substringBefore("\"")
    }

    private suspend fun bypassMirrored(url: String?): List<String?> {
        val request = session.get(url ?: return emptyList())
        delay(500)
        val mirrorUrl = request.selectMirror() ?: run {
            val nextUrl = request.document.select("div.col-sm.centered.extra-top a").attr("href")
            session.get(nextUrl).selectMirror()
        }
        return session.get(fixUrl(mirrorUrl ?: return emptyList(), mirroredHost)).document.select("table.hoverable tbody tr")
                .filter { mirror ->
                    !mirrorIsBlackList(mirror.selectFirst("img")?.attr("alt"))
                }.amap {
                val fileLink = it.selectFirst("a")?.attr("href")
                session.get(fixUrl(fileLink ?: return@amap null, mirroredHost)).document.selectFirst("div.code_wrap code")?.text()
            }
    }

    private fun mirrorIsBlackList(host: String?): Boolean {
        return mirrorBlackList.any { it.equals(host, true) }
    }

    private fun fixUrl(url: String, domain: String): String {
        if (url.startsWith("http")) return url
        if (url.isEmpty()) return ""
        return if (url.startsWith("//")) "https:$url" else if (url.startsWith('/')) "$domain$url" else "$domain/$url"
    }

    private fun parseStreamQuality(doc: org.jsoup.nodes.Document, streamNum: Int): Int {
        val keyword = "stream $streamNum"
        val nextKeyword = "stream ${streamNum + 1}"

        val noteEl = doc.select("p, h3, h4").firstOrNull { el ->
            el.text().lowercase().contains(keyword)
                && Regex("""\d{3,4}[pP]""").containsMatchIn(el.text())
        } ?: return Qualities.Unknown.value

        val fullText = noteEl.text().lowercase()
        val startIdx = fullText.indexOf(keyword).takeIf { it >= 0 } ?: return Qualities.Unknown.value
        val endIdx = fullText.indexOf(nextKeyword, startIdx).takeIf { it > startIdx } ?: fullText.length
        val segment = noteEl.text().substring(startIdx, endIdx)

        val qualities = Regex("""(\d{3,4})[pP]""").findAll(segment)
            .mapNotNull { it.groupValues[1].toIntOrNull() }
            .filter { it in listOf(360, 480, 720, 1080, 1440, 2160) }
            .toList()

        val best = qualities.maxOrNull() ?: return Qualities.Unknown.value
        return getQualityFromName("${best}p")
    }

    private fun getIndexQuality(str: String?): Int {
        val quality = Regex("""(?i)\[(\d+[pk])]""").find(str ?: "")?.groupValues?.getOrNull(1)?.lowercase()
        return when (quality) {
            "2k" -> Qualities.P1440.value
            else -> getQualityFromName(quality)
        }
    }
}
