package com.nekopoi

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.nicehttp.NiceResponse
import org.jsoup.nodes.Element
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.net.URI

class Nekopoi : MainAPI() {
    override var mainUrl = "https://nekopoi.care"
    override var name = "NekoPoi"
    override val hasMainPage = true
    override var lang = "id"
    override val vpnStatus = VPNStatus.MightBeNeeded
    override val supportedTypes = setOf(TvType.NSFW)

    private val cfKiller = CloudflareKiller()

    private suspend fun safeGet(url: String, ref: String? = null): NiceResponse {
        return app.get(
            url,
            headers = mapOf(
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8",
                "Accept-Language" to "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7",
                "Referer" to (ref ?: "$mainUrl/"),
                "Connection" to "keep-alive",
                "Sec-Ch-Ua" to "\"Chromium\";v=\"137\", \"Not/A)Brand\";v=\"24\"",
                "Sec-Ch-Ua-Mobile" to "?1",
                "Sec-Ch-Ua-Platform" to "\"Android\"",
                "Upgrade-Insecure-Requests" to "1",
                "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Mobile Safari/537.36",
            ),
            cookies = mapOf(
                "sl-challenge-server" to "cloud",
                "sl-session" to "AQc7Fk4Lomm4LP/ZOhGYAQ==",
                "sl_jwt_session" to "0jemItSR5f80tPddXCS80Ymv8q3t1oSG+MB4aEp7azV1ZEFQG8mHwfToox2bjmda",
                "sl_jwt_sign" to ""
            ),
            interceptor = cfKiller
        )
    }

    companion object {
        val mirrorBlackList = arrayOf("MegaupNet", "DropApk", "Racaty", "VideobinCo", "SendCm", "GoogleDrive")
        const val mirroredHost = "https://www.mirrored.to"

        fun getStatus(t: String?): ShowStatus = when (t) {
            "Completed" -> ShowStatus.Completed
            "Ongoing" -> ShowStatus.Ongoing
            else -> ShowStatus.Completed
        }
    }

    override val mainPage = mainPageOf(
        "$mainUrl/category/hentai/page/" to "Hentai",
        "$mainUrl/category/jav/page/" to "Jav",
        "$mainUrl/category/3d-hentai/page/" to "3D Hentai",
        "$mainUrl/category/jav-cosplay/page/" to "Jav Cosplay",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = safeGet(request.data + page).document
        val home = document.select("div.result ul li").mapNotNull { it.toSearchResult() }
        
        return newHomePageResponse(
            list = HomePageList(name = request.name, list = home, isHorizontalImages = true),
            hasNext = true
        )
    }

    private fun Element.toSearchResult(): AnimeSearchResponse? {
        val title = this.selectFirst("h2 a")?.text()?.trim() ?: return null
        val rawHref = this.selectFirst("a")?.attr("href") ?: return null
        val href = if (rawHref.contains("-episode-")) {
            val slug = rawHref.substringAfter("$mainUrl/").substringBefore("-episode-")
                .removePrefix("new-release-").removePrefix("uncensored-")
            "$mainUrl/hentai/$slug"
        } else rawHref

        val posterUrl = fixUrlNull(this.selectFirst("img")?.attr("src"))
        val epNum = this.selectFirst("i.dot")?.text()?.filter { it.isDigit() }?.toIntOrNull()

        return newAnimeSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = posterUrl
            addSub(epNum)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return safeGet("$mainUrl/search/$query").document.select("div.result ul li")
            .mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = safeGet(url).document

        val title = document.selectFirst("span.desc b, div.eroinfo h1")?.text()?.trim() ?: ""
        val poster = fixUrlNull(document.selectFirst("div.imgdesc img, div.thm img")?.attr("src"))
        val tags = document.select("div.listinfo ul li:contains(Genres) a").map { it.text() }
            .takeIf { it.isNotEmpty() } ?: document.select("p:contains(Genre)").text()
            .substringAfter(":").split(",").map { it.trim() }

        val year = document.selectFirst("li:contains(Tayang)")?.text()?.substringAfterLast(",")
            ?.filter { it.isDigit() }?.toIntOrNull()
        
        val episodes = document.select("div.episodelist ul li").mapNotNull {
            val name = it.selectFirst("a")?.text()
            val link = fixUrlNull(it.selectFirst("a")?.attr("href")) ?: return@mapNotNull null
            newEpisode(link) { this.name = name }
        }.reversed().takeIf { it.isNotEmpty() } ?: listOf(newEpisode(url) { this.name = title })

        return newAnimeLoadResponse(title, url, TvType.NSFW) {
            posterUrl = poster
            this.year = year
            addEpisodes(DubStatus.Subbed, episodes)
            showStatus = getStatus(document.selectFirst("li:contains(Status)")?.text()?.substringAfter(":"))
            plot = document.selectFirst("span.desc p")?.text()
            this.tags = tags
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val res = safeGet(data).document

        res.select("div#show-stream iframe, div.embed-player iframe").forEach { iframe ->
            loadExtractor(iframe.attr("src"), "$mainUrl/", subtitleCallback, callback)
        }

        res.select("div.boxdownload div.liner").forEach { ele ->
            val quality = getIndexQuality(ele.select("div.name").text())
            val downloadUrl = ele.selectFirst("a[href*=/ouo/], a[href*=ouo.io]")?.attr("href") 
                ?: ele.selectFirst("a")?.attr("href")

            if (downloadUrl != null) {
                val bypassedOuo = bypassOuo(downloadUrl)
                if (bypassedOuo != null) {
                    bypassMirrored(bypassedOuo).forEach { link ->
                        if (!link.isNullOrBlank()) {
                            loadExtractor(link, "$mainUrl/", subtitleCallback) { extracted ->
                                runBlocking {
                                    callback.invoke(
                                        newExtractorLink(
                                            source = extracted.name,
                                            name = extracted.name,
                                            url = extracted.url,
                                            type = extracted.type
                                        ) {
                                            this.referer = extracted.referer
                                            this.quality = if (extracted.type == ExtractorLinkType.M3U8) extracted.quality else quality
                                            this.headers = extracted.headers
                                            this.extractorData = extracted.extractorData
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        return true
    }

    private suspend fun bypassOuo(url: String?): String? {
        if (url == null) return null
        val res = app.get(url, interceptor = cfKiller)
        val doc = res.document
        val nextUrl = doc.select("form").attr("action")
        if (nextUrl.isNullOrBlank()) return url

        val data = doc.select("form input").associate { 
            it.attr("name") to it.attr("value") 
        }.toMutableMap()
        
        delay(2500)
        
        val postRes = app.post(
            nextUrl,
            data = data,
            headers = mapOf("Content-Type" to "application/x-www-form-urlencoded"),
            allowRedirects = false,
            interceptor = cfKiller
        )
        return postRes.headers["location"] ?: url
    }

    private suspend fun bypassMirrored(url: String?): List<String?> {
        if (url == null || !url.contains("mirrored.to")) return listOf(url)
        
        val request = app.get(url, interceptor = cfKiller)
        val mirrorPath = request.document.selectFirst("script:containsData(#passcheck)")?.data()
            ?.substringAfter("\"GET\", \"")?.substringBefore("\"") ?: return emptyList()
        
        val resolvedMirrorPath = URI(mirroredHost).resolve(mirrorPath).toString()
        val apiRes = app.get(resolvedMirrorPath, interceptor = cfKiller).document
        
        return apiRes.select("table.hoverable tbody tr")
            .filter { !mirrorIsBlackList(it.selectFirst("img")?.attr("alt")) }
            .map {
                val directId = it.selectFirst("a")?.attr("href") ?: ""
                
                val resolvedDirectId = URI(mirroredHost).resolve(directId).toString()
                
                app.get(resolvedDirectId, interceptor = cfKiller)
                    .document.selectFirst("div.code_wrap code")?.text()
            }
    }

    private fun mirrorIsBlackList(host: String?): Boolean = mirrorBlackList.any { it.equals(host, true) }

    private fun getIndexQuality(str: String?): Int {
        val qual = Regex("""(\d{3,4})p""").find(str ?: "")?.groupValues?.getOrNull(1)
        return getQualityFromName(qual)
    }
}
