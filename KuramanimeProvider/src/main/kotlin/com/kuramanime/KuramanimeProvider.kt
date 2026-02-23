package com.kuramanime

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addAniListId
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URI

class KuramanimeProvider : MainAPI() {
    override var mainUrl = "https://v9.kuramanime.tel"
    override var name = "Kuramanime"
    override val hasQuickSearch = false
    override val hasMainPage = true
    override var lang = "id"
    override var sequentialMainPage = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.OVA
    )

    companion object {
        private var cookies: Map<String, String> = mapOf()
        private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

        fun getType(t: String, s: Int): TvType {
            return if (t.contains("OVA", true) || t.contains("Special")) TvType.OVA
            else if (t.contains("Movie", true) && s == 1) TvType.AnimeMovie
            else TvType.Anime
        }

        fun getStatus(t: String): ShowStatus {
            return when (t) {
                "Selesai Tayang" -> ShowStatus.Completed
                "Sedang Tayang" -> ShowStatus.Ongoing
                else -> ShowStatus.Completed
            }
        }
    }

    override val mainPage = mainPageOf(
        "$mainUrl/anime/ongoing?order_by=updated&page=" to "Sedang Tayang",
        "$mainUrl/anime/finished?order_by=updated&page=" to "Selesai Tayang",
        "$mainUrl/properties/season/summer-2022?order_by=most_viewed&page=" to "Dilihat Terbanyak Musim Ini",
        "$mainUrl/anime/movie?order_by=updated&page=" to "Film Layar Lebar",
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val document = app.get(request.data + page, headers = mapOf("User-Agent" to USER_AGENT)).document
        val home = document.select("div#animeList div.product__item").mapNotNull {
            it.toSearchResult()
        }

        return newHomePageResponse(request.name, home)
    }

    private fun getProperAnimeLink(uri: String): String {
        return if (uri.contains("/episode")) {
            Regex("(.*)/episode/.+").find(uri)?.groupValues?.get(1).toString() + "/"
        } else {
            uri
        }
    }

    private fun Element.toSearchResult(): AnimeSearchResponse? {
        val href = getProperAnimeLink(fixUrl(this.selectFirst("a")!!.attr("href")))
        val title = this.selectFirst("h5 a")?.text() ?: return null
        val posterUrl = fixUrl(this.select("div.product__item__pic.set-bg").attr("data-setbg"))
        val episode = this.select("div.ep span").text().let {
            Regex("Ep\\s(\\d+)\\s/").find(it)?.groupValues?.getOrNull(1)?.toIntOrNull()
        }

        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = posterUrl
            addSub(episode)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return app.get(
            "$mainUrl/anime?search=$query&order_by=latest",
            headers = mapOf("User-Agent" to USER_AGENT)
        ).document.select("div#animeList div.product__item").mapNotNull {
            it.toSearchResult()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, headers = mapOf("User-Agent" to USER_AGENT)).document

        val title = document.selectFirst(".anime__details__title > h3")!!.text().trim()
        val poster = document.selectFirst(".anime__details__pic")?.attr("data-setbg")
        val tags = document.select("div.anime__details__widget > div > div:nth-child(2) > ul > li:nth-child(1)")
            .text().trim().replace("Genre: ", "").split(", ")

        val year = Regex("\\D").replace(
            document.select("div.anime__details__widget > div > div:nth-child(1) > ul > li:nth-child(5)")
                .text().trim().replace("Musim: ", ""), ""
        ).toIntOrNull()
        
        val status = getStatus(
            document.select("div.anime__details__widget > div > div:nth-child(1) > ul > li:nth-child(3)")
                .text().trim().replace("Status: ", "")
        )
        val description = document.select(".anime__details__text > p").text().trim()

        val episodes = mutableListOf<Episode>()

        for (i in 1..30) {
            val doc = app.get("$url?page=$i", headers = mapOf("User-Agent" to USER_AGENT)).document
            val eps = Jsoup.parse(doc.select("#episodeLists").attr("data-content"))
                .select("a.btn.btn-sm.btn-danger")
                .mapNotNull {
                    val name = it.text().trim()
                    val episode = Regex("(\\d+[.,]?\\d*)").find(name)?.groupValues?.getOrNull(0)?.toIntOrNull()
                    val link = it.attr("href")
                    newEpisode(link) { this.episode = episode }
                }
            if (eps.isEmpty()) break else episodes.addAll(eps)
        }

        val type = getType(
            document.selectFirst("div.col-lg-6.col-md-6 ul li:contains(Tipe:) a")?.text()?.lowercase() ?: "tv", 
            episodes.size
        )
        val recommendations = document.select("div#randomList > a").mapNotNull {
            val epHref = it.attr("href")
            val epTitle = it.select("h5.sidebar-title-h5.px-2.py-2").text()
            val epPoster = it.select(".product__sidebar__view__item.set-bg").attr("data-setbg")
            newAnimeSearchResponse(epTitle, epHref, TvType.Anime) {
                this.posterUrl = epPoster
                addDubStatus(dubExist = false, subExist = true)
            }
        }

        val tracker = APIHolder.getTracker(listOf(title), TrackerType.getTypes(type), year, true)

        return newAnimeLoadResponse(title, url, type) {
            engName = title
            posterUrl = tracker?.image ?: poster
            backgroundPosterUrl = tracker?.cover
            this.year = year
            addEpisodes(DubStatus.Subbed, episodes)
            showStatus = status
            plot = description
            this.tags = tags
            this.recommendations = recommendations
            addMalId(tracker?.malId)
            addAniListId(tracker?.aniId?.toIntOrNull())
        }
    }

    private suspend fun invokeLocalSource(
        url: String,
        server: String,
        headers: Map<String, String>,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val document = app.get(
            url,
            headers = headers + mapOf("Referer" to mainUrl),
            cookies = cookies
        ).document

        var linkFound = false

        document.select("video#player > source, video > source").forEach { source ->
            val link = fixUrl(source.attr("src"))
            val qualityStr = source.attr("size").ifEmpty { source.attr("res") }
            val quality = qualityStr.toIntOrNull() ?: Qualities.Unknown.value
            val isM3u8 = link.contains(".m3u8")

            if (link.isNotBlank()) {
                linkFound = true
                callback.invoke(
                    ExtractorLink(
                        source = name,
                        name = "Kurama Internal - $server",
                        url = link,
                        referer = url,
                        quality = quality,
                        isM3u8 = isM3u8,
                        headers = headers + mapOf("Origin" to mainUrl, "Referer" to url)
                    )
                )
            }
        }

        if (!linkFound) {
            val scripts = document.select("script").map { it.data() }.joinToString("\n")
            val linkRegex = Regex("[\"'](https?://[^\"']+(?:\\.mp4|\\.m3u8)[^\"']*)[\"']")
            
            linkRegex.findAll(scripts).forEach { match ->
                val link = match.groupValues[1].replace("\\/", "/")
                val isM3u8 = link.contains(".m3u8")
                
                if (!link.contains("popads") && !link.contains("doubleclick")) {
                    callback.invoke(
                        ExtractorLink(
                            source = name,
                            name = "Kurama Extracted - $server",
                            url = link,
                            referer = url,
                            quality = Qualities.Unknown.value,
                            isM3u8 = isM3u8,
                            headers = headers + mapOf("Origin" to mainUrl, "Referer" to url)
                        )
                    )
                }
            }
        }

        document.select("video#player > track, video > track").forEach { track ->
            val subLink = fixUrl(track.attr("src"))
            val lang = track.attr("label").ifEmpty { "Indonesian" }
            if (subLink.isNotBlank()) {
                subtitleCallback.invoke(SubtitleFile(lang, subLink))
            }
        }

        if (server.contains("kuramadrive", true)) {
            document.select("div#animeDownloadLink a, a.btn-download").amap {
                val downloadLink = it.attr("href")
                if (downloadLink.isNotBlank()) {
                    loadExtractor(downloadLink, "$mainUrl/", subtitleCallback, callback)
                }
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val req = app.get(data, headers = mapOf("User-Agent" to USER_AGENT))
        val res = req.document
        cookies = req.cookies

        val token = res.selectFirst("meta[name=csrf-token]")?.attr("content") ?: return false
        val dataKps = res.selectFirst("div[data-kk]")?.attr("data-kk") 
            ?: res.selectFirst("div.col-lg-12.mt-3")?.attr("data-kk") ?: return false

        val assets = getAssets(dataKps)

        val baseHeaders = mapOf(
            "User-Agent" to USER_AGENT,
            "X-CSRF-TOKEN" to token,
            "X-Requested-With" to "XMLHttpRequest",
            "Referer" to data
        )

        val tokenKeyResponse = app.get(
            "$mainUrl/${assets.MIX_PREFIX_AUTH_ROUTE_PARAM}${assets.MIX_AUTH_ROUTE_PARAM}",
            headers = baseHeaders + mapOf(
                "X-Fuck-ID" to "${assets.MIX_AUTH_KEY}:${assets.MIX_AUTH_TOKEN}",
                "X-Request-ID" to randomId(),
                "X-Request-Index" to "0"
            ),
            cookies = cookies
        )
        
        val tokenKey = tokenKeyResponse.text.trim()

        res.select("select#changeServer option").amap { source ->
            val server = source.attr("value")
            val streamingUrl = "$data?${assets.MIX_PAGE_TOKEN_KEY}=$tokenKey&${assets.MIX_STREAM_SERVER_KEY}=$server"
            
            val streamResponse = app.get(
                streamingUrl,
                headers = baseHeaders,
                cookies = cookies
            )

            val streamDoc = streamResponse.document
            
            if (server.contains(Regex("(?i)kuramadrive|archive"))) {
                invokeLocalSource(streamingUrl, server, baseHeaders, subtitleCallback, callback)
            } else {
                val iframeSrc = streamDoc.selectFirst("div.iframe-container iframe")?.attr("src") 
                    ?: streamDoc.selectFirst("iframe")?.attr("src")

                if (iframeSrc != null) {
                    loadExtractor(fixUrl(iframeSrc), "$mainUrl/", subtitleCallback, callback)
                }
            }
        }

        return true
    }

    private suspend fun getAssets(bpjs: String?): Assets {
        val env = app.get("$mainUrl/assets/js/$bpjs.js", headers = mapOf("User-Agent" to USER_AGENT)).text
        val MIX_PREFIX_AUTH_ROUTE_PARAM = env.substringAfter("MIX_PREFIX_AUTH_ROUTE_PARAM: '").substringBefore("',")
        val MIX_AUTH_ROUTE_PARAM = env.substringAfter("MIX_AUTH_ROUTE_PARAM: '").substringBefore("',")
        val MIX_AUTH_KEY = env.substringAfter("MIX_AUTH_KEY: '").substringBefore("',")
        val MIX_AUTH_TOKEN = env.substringAfter("MIX_AUTH_TOKEN: '").substringBefore("',")
        val MIX_PAGE_TOKEN_KEY = env.substringAfter("MIX_PAGE_TOKEN_KEY: '").substringBefore("',")
        val MIX_STREAM_SERVER_KEY = env.substringAfter("MIX_STREAM_SERVER_KEY: '").substringBefore("',")
        
        return Assets(
            MIX_PREFIX_AUTH_ROUTE_PARAM,
            MIX_AUTH_ROUTE_PARAM,
            MIX_AUTH_KEY,
            MIX_AUTH_TOKEN,
            MIX_PAGE_TOKEN_KEY,
            MIX_STREAM_SERVER_KEY
        )
    }

    private fun randomId(length: Int = 6): String {
        val allowedChars = ('a'..'z') + ('A'..'Z') + ('0'..'9')
        return (1..length)
            .map { allowedChars.random() }
            .joinToString("")
    }

    data class Assets(
        val MIX_PREFIX_AUTH_ROUTE_PARAM: String?,
        val MIX_AUTH_ROUTE_PARAM: String?,
        val MIX_AUTH_KEY: String?,
        val MIX_AUTH_TOKEN: String?,
        val MIX_PAGE_TOKEN_KEY: String?,
        val MIX_STREAM_SERVER_KEY: String?,
    )
}
