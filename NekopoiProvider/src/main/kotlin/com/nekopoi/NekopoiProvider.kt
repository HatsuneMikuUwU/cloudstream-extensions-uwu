package com.nekopoi

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.nicehttp.NiceResponse
import com.lagradost.nicehttp.Requests
import com.lagradost.nicehttp.Session
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.jsoup.nodes.Element
import java.net.URI

class NekopoiProvider : MainAPI() {
    override var mainUrl = "https://nekopoi.care"
    override var name = "NekoPoi"
    override val hasMainPage = true
    override var lang = "id"
    private val fetch by lazy { Session(app.baseClient) }
    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.OVA,
        TvType.NSFW,
    )

    companion object {
        val session = Session(Requests().baseClient)
        val mirrorBlackList = arrayOf(
            "VOE",
            "StreamRuby",
            "LuluStream",
            "KrakenFiles",
            "Buzzheavier",
            "FilesDL",
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

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val baseUrl = request.data.removeSuffix("/") 
        val url = if (page == 1) "$baseUrl/" else "$baseUrl/page/$page/"
        
        val document = fetch.get(url).document
        
        val home = document.select("div.result ul li, div.eropost").mapNotNull {
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
        return if (uri.contains("-episode-")) {
            val regex = Regex("""-episode-\d+.*""")
            uri.replace(regex, "")
        } else {
            uri
        }
    }

    private fun Element.toSearchResult(): AnimeSearchResponse? {
        val title = this.selectFirst("h2 a, h2, h3 a, div.info h2")?.text()?.trim() ?: return null
        val href = getProperAnimeLink(this.selectFirst("a")?.attr("href") ?: return null)
        
        val img = this.selectFirst("img")
        val posterUrl = fixUrlNull(
            img?.attr("data-src").takeIf { !it.isNullOrBlank() }
                ?: img?.attr("data-lazy-src").takeIf { !it.isNullOrBlank() }
                ?: img?.attr("src")
        )
        
        val epNum = this.selectFirst("i.dot, div.ep, span.ep")?.text()?.filter { it.isDigit() }?.toIntOrNull()
        
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

        val title = document.selectFirst("span.desc b, div.eroinfo h1")?.text()?.trim() 
            ?: document.selectFirst("title")?.text()?.substringBefore("-")?.trim() 
            ?: "Unknown Title"

        val posterElement = document.selectFirst("div.imgdesc img, div.thm img")
        val poster = fixUrlNull(
            posterElement?.attr("data-src").takeIf { !it.isNullOrBlank() }
                ?: posterElement?.attr("data-lazy-src").takeIf { !it.isNullOrBlank() }
                ?: posterElement?.attr("src")
        )

        val table = document.select("div.listinfo ul, div.konten")
        
        val tags = table.select("li:contains(Genres) a").map { it.text().trim() }.takeIf { it.isNotEmpty() }
            ?: table.select("p:contains(Genre)").text().substringAfter(":", "").split(",")
                .map { it.trim() }.filter { it.isNotBlank() }

        val year = document.selectFirst("li:contains(Tayang)")?.text()
            ?.substringAfterLast(",")?.filter { it.isDigit() }?.toIntOrNull()
            
        val status = getStatus(
            document.selectFirst("li:contains(Status)")?.text()?.substringAfter(":")?.trim()
        )
        
        val duration = document.selectFirst("li:contains(Durasi)")?.text()
            ?.substringAfterLast(":")?.filter { it.isDigit() }?.toIntOrNull()
            
        val description = document.selectFirst("span.desc p")?.text()?.trim()

        var episodes = document.select("div.episodelist ul li").mapNotNull {
            val name = it.selectFirst("span.leftoff")?.text() ?: it.selectFirst("a")?.text() ?: "Episode"
            val link = fixUrlNull(it.selectFirst("a")?.attr("href")) ?: return@mapNotNull null
            
            newEpisode(link) { 
                this.name = name.trim() 
            }
        }

        if (episodes.isNotEmpty()) {
            episodes = episodes.reversed()
        } else {
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
                res.select("div#show-stream iframe").amap { iframe ->
                    loadExtractor(iframe.attr("src"), "$mainUrl/", subtitleCallback, callback)
                }
            },
            {
                res.select("div.boxdownload div.liner").mapNotNull { ele ->
                    val url = ele.selectFirst("a[href*=ouo]")?.attr("href") 
                        ?: ele.selectFirst("a[href*=mirrored]")?.attr("href")
                    
                    if (url != null) {
                        getIndexQuality(ele.select("div.name").text()) to url
                    } else null
                }.filter { it.first != Qualities.P360.value }.amap { (quality, rawUrl) ->
                    
                    val targetUrl = if (rawUrl.contains("ouo", ignoreCase = true)) {
                        bypassOuo(rawUrl)
                    } else {
                        rawUrl
                    }

                    val bypassedAds = bypassMirrored(targetUrl)
                    
                    bypassedAds.forEach { adsLink ->
                        val embedUrl = fixEmbed(adsLink) ?: return@forEach
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
                                        quality =
                                            if (link.type == ExtractorLinkType.M3U8) link.quality else it.first
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
        if (url == null) return null
        
        var currentUrl: String = url 
        var res = session.get(currentUrl)

        run lit@{
            (1..2).forEach { _ ->
                if (res.headers["location"] != null) return@lit

                val document = res.document
                val form = document.selectFirst("form") ?: return@lit
                
                var nextUrl = form.attr("action") ?: ""
                if (nextUrl.isBlank()) nextUrl = currentUrl

                val data = document.select("form input").associate {
                    (it.attr("name") ?: "") to (it.attr("value") ?: "")
                }.toMutableMap()

                val captchaScript = document.selectFirst("script[src*=/recaptcha/api.js?render=]")
                if (captchaScript != null) {
                    val captchaKey = captchaScript.attr("src").substringAfter("render=")
                    val token = APIHolder.getCaptchaToken(currentUrl, captchaKey)
                    if (token != null) data["x-token"] = token 
                }

                val postHeaders = mapOf(
                    "Content-Type" to "application/x-www-form-urlencoded",
                    "Referer" to currentUrl,
                    "Origin" to getBaseUrl(currentUrl),
                    "Upgrade-Insecure-Requests" to "1"
                )

                res = session.post(
                    nextUrl,
                    data = data,
                    headers = postHeaders,
                    allowRedirects = false
                )
                
                currentUrl = nextUrl 
            }
        }

        return res.headers["location"]
    }

    private fun NiceResponse.selectMirror(): String? {
        val scriptData = this.document.selectFirst("script:containsData(#passcheck)")?.data() 
            ?: return null
        
        val regex = Regex("""type\s*:\s*['"]GET['"]\s*,\s*url\s*:\s*['"]([^'"]+)['"]""")
        return regex.find(scriptData)?.groupValues?.getOrNull(1) 
            ?: scriptData.substringAfter("\"GET\", \"").substringBefore("\"").takeIf { it.isNotBlank() }
    }

    private suspend fun bypassMirrored(url: String?): List<String> { 
        if (url.isNullOrBlank()) return emptyList()
        
        val request = session.get(url)
        delay(2000)

        var mirrorUrl = request.selectMirror()

        if (mirrorUrl.isNullOrBlank()) {
            val nextUrl = request.document.selectFirst("div.col-sm.centered.extra-top a, a.btn-primary")?.attr("href")
            if (!nextUrl.isNullOrBlank()) {
                val fixedNextUrl = fixUrl(nextUrl, mirroredHost)
                mirrorUrl = session.get(fixedNextUrl).selectMirror()
            }
        }

        if (mirrorUrl.isNullOrBlank()) return emptyList()

        val finalMirrorUrl = fixUrl(mirrorUrl, mirroredHost)
        val mirrorPage = session.get(finalMirrorUrl).document

        return mirrorPage.select("table.hoverable tbody tr").mapNotNull { mirror ->
            val hostName = mirror.selectFirst("img")?.attr("alt") ?: mirror.selectFirst("td")?.text()
            if (mirrorIsBlackList(hostName)) return@mapNotNull null
            
            val fileLink = mirror.selectFirst("a")?.attr("href")
            fileLink
        }.amap { fileLink ->
            val fixedFileLink = fixUrl(fileLink ?: return@amap null, mirroredHost)
            val hostPage = session.get(fixedFileLink).document
            
            hostPage.selectFirst("div.code_wrap code, a.btn.btn-primary, a:contains(Continue)")?.let { element ->
                if (element.tagName() == "a") element.attr("href") else element.text()
            }
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
        return when (val quality =
            Regex("""(?i)\[(\d+[pk])]""").find(str ?: "")?.groupValues?.getOrNull(1)?.lowercase()) {
            "2k" -> Qualities.P1440.value
            else -> getQualityFromName(quality)
        }
    }
}
