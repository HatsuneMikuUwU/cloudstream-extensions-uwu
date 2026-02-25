package com.kuramanime

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addAniListId
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URI

class KuramanimeProvider : MainAPI() {
    override var mainUrl = "https://v15.kuramanime.tel"
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

        fun getType(t: String, s: Int): TvType {
            return if (t.contains("OVA", true) || t.contains("Special")) TvType.OVA
            else if (t.contains("Movie", true) && s == 1) TvType.AnimeMovie
            else TvType.Anime
        }

        fun getStatus(t: String): ShowStatus {
            return when (t) {
                "Complete Anime" -> ShowStatus.Completed
                "Ongoing Anime" -> ShowStatus.Ongoing
                else -> ShowStatus.Completed
            }
        }
    }

    override val mainPage = mainPageOf(
        "$mainUrl/quick/ongoing?order_by=updated&page=" to "Ongoing Anime",
        "$mainUrl/quick/finished?order_by=updated&page=" to "Complete Anime",
        "$mainUrl/quick/movie?order_by=updated&page=" to "Movie",
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val document = app.get(request.data + page).document
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
        val href = getProperAnimeLink(fixUrl(this.selectFirst("a")?.attr("href") ?: return null))
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
            "$mainUrl/anime?search=$query&order_by=latest"
        ).document.select("div#animeList div.product__item").mapNotNull {
            it.toSearchResult()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val title = document.selectFirst(".anime__details__title > h3")?.text()?.trim() ?: "No Title"
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
            val doc = if (i == 1) document else app.get("$url?page=$i").document
            val episodeContainer = doc.selectFirst("#episodeLists")
            
            val rawHtml = episodeContainer?.attr("data-content")?.takeIf { it.isNotBlank() } 
                ?: episodeContainer?.html() 
                ?: ""

            val eps = Jsoup.parse(rawHtml)
                .select("a.btn.btn-sm.btn-danger, a[href*='/episode/']")
                .mapNotNull {
                    val name = it.text().trim()
                    val episodeNum = Regex("(\\d+[.,]?\\d*)").find(name)?.groupValues?.getOrNull(0)
                        ?.toFloatOrNull()?.toInt()
                    val link = fixUrl(it.attr("href"))
                    newEpisode(link) { 
                        this.name = name.ifBlank { "Episode $episodeNum" }
                        this.episode = episodeNum 
                    }
                }
                
            if (eps.isEmpty()) break else episodes.addAll(eps)
            
            if (doc.select(".pagination a:contains(>>), .pagination a[rel='next']").isEmpty()) break
        }

        val type = getType(
            document.selectFirst("div.col-lg-6.col-md-6 ul li:contains(Tipe:) a")?.text()
                ?.lowercase() ?: "tv", episodes.size
        )
        val recommendations = document.select("div#randomList > a").mapNotNull {
            val epHref = fixUrl(it.attr("href"))
            val epTitle = it.select("h5.sidebar-title-h5").text()
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
            addEpisodes(DubStatus.Subbed, episodes.distinctBy { it.data })
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
            headers = headers,
            cookies = cookies
        ).document

        document.select("video#player > source, video source").forEach {
            val link = fixUrl(it.attr("src"))
            if (link.isBlank()) return@forEach

            val quality = it.attr("size").toIntOrNull() ?: it.attr("res").toIntOrNull() ?: Qualities.Unknown.value
            val serverName = server.replaceFirstChar { c -> c.uppercase() }

            if (link.contains("pid=") && link.contains("sid=")) {
                try {
                    val queryParams = link.substringAfter("?").split("&").associate {
                        val parts = it.split("=")
                        parts[0] to (parts.getOrNull(1) ?: "")
                    }
                    val pid = queryParams["pid"] ?: return@forEach
                    val sid = queryParams["sid"] ?: return@forEach
                    val lud = queryParams["lud"] ?: ""

                    val host = URI(mainUrl).let { "${it.scheme}://${it.host}" }
                    val tokenUrl = "$host/misc/token/drive-token"

                    val tokenRes = app.post(
                        tokenUrl,
                        headers = mapOf(
                            "Accept" to "application/json",
                            "Referer" to url,
                            "Origin" to host
                        ),
                        json = mapOf("pid" to pid, "sid" to sid), // Service Worker mengirim via JSON body
                        cookies = cookies
                    ).text

                    val tokenJson = tryParseJson<Map<String, String>>(tokenRes)
                    val accessToken = tokenJson?.get("access_token")
                    val gid = tokenJson?.get("gid")

                    if (accessToken != null && gid != null) {
                        val driveUrl = "https://www.googleapis.com/drive/v3/files/$gid?alt=media&lud=$lud&pid=$pid&sid=$sid"

                        callback.invoke(
                            newExtractorLink(
                                name = "$serverName (G-Drive)",
                                source = "$serverName (G-Drive)",
                                url = driveUrl,
                                type = INFER_TYPE
                            ) {
                                this.headers = mapOf(
                                    "Authorization" to "Bearer $accessToken",
                                    "Accept" to "video/webm,video/ogg,video/*;q=0.9,application/ogg;q=0.7,audio/*;q=0.6,*/*;q=0.5",
                                    "Range" to "bytes=0-",
                                    "Referer" to url
                                )
                                this.quality = quality
                            }
                        )
                        return@forEach
                    }
                } catch (e: Exception) {
                    println("Gagal bypass KuramaDrive: ${e.message}")
                }
            }

            callback.invoke(
                newExtractorLink(
                    name = serverName,
                    source = serverName,
                    url = link,
                    type = INFER_TYPE
                ) {
                    this.headers = mapOf(
                        "Accept" to "video/webm,video/ogg,video/*;q=0.9,application/ogg;q=0.7,audio/*;q=0.6,*/*;q=0.5",
                        "Range" to "bytes=0-",
                        "Sec-Fetch-Dest" to "video",
                        "Sec-Fetch-Mode" to "no-cors",
                        "Referer" to url
                    )
                    this.quality = quality
                }
            )
        }
        
        if (server.contains("kuramadrive", true)) {
            document.select("div#animeDownloadLink a, .download-links a").amap {
                loadExtractor(it.attr("href"), url, subtitleCallback, callback)
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val req = app.get(data)
        val res = req.document
        cookies = req.cookies

        res.select("div.iframe-container iframe, iframe").attr("src").takeIf { it.isNotBlank() }?.let { videoUrl ->
            loadExtractor(fixUrl(videoUrl), "$mainUrl/", subtitleCallback, callback)
        }

        val token = res.selectFirst("meta[name=csrf-token]")?.attr("content") ?: return false
        
        val dataKps = res.selectFirst("[data-kps]")?.attr("data-kps")
            ?: res.selectFirst("[data-kk]")?.attr("data-kk")
            ?: res.selectFirst("[data-pj]")?.attr("data-pj")
            ?: res.selectFirst(".col-lg-12.mt-3")?.attributes()?.firstOrNull { it.key.startsWith("data-") }?.value
            ?: return false

        val assets = getAssets(dataKps) ?: return false

        var headers = mapOf(
            "X-CSRF-TOKEN" to token,
            "X-Fuck-ID" to "${assets.MIX_AUTH_KEY}:${assets.MIX_AUTH_TOKEN}",
            "X-Request-ID" to randomId(),
            "X-Request-Index" to "0",
            "X-Requested-With" to "XMLHttpRequest",
            "Referer" to data,
            "Accept" to "application/json, text/javascript, */*; q=0.01"
        )

        val tokenUrl = "$mainUrl/${assets.MIX_PREFIX_AUTH_ROUTE_PARAM}${assets.MIX_AUTH_ROUTE_PARAM}"
        val tokenKeyRaw = app.get(tokenUrl, headers = headers, cookies = cookies).text

        val tokenKey = if (tokenKeyRaw.contains("{")) {
            tryParseJson<Map<String, String>>(tokenKeyRaw)?.get("token") ?: return false
        } else {
            tokenKeyRaw.trim()
        }

        headers = mapOf(
            "Alt-Used" to URI(mainUrl).host,
            "Authorization" to "Bearer 39F25KMTgDv0EQCqwRF9kBWxcSrHOGKc",
            "X-CSRF-TOKEN" to token,
            "X-Requested-With" to "XMLHttpRequest",
            "Referer" to data
        )

        res.select("div#animeDownloadLink a, .download-links a").amap {
            loadExtractor(it.attr("href"), data, subtitleCallback, callback)
        }

        res.select("select#changeServer option").amap { source ->
            val server = source.attr("value")
            if (server.isBlank()) return@amap

            val link = "$data?${assets.MIX_PAGE_TOKEN_KEY}=$tokenKey&${assets.MIX_STREAM_SERVER_KEY}=$server"
            
            if (server.contains(Regex("(?i)kuramadrive|archive"))) {
                invokeLocalSource(link, server, headers, subtitleCallback, callback)
            } else {
                val doc = app.get(link, referer = data, headers = headers, cookies = cookies).document
                doc.select("div.iframe-container iframe, iframe").attr("src").let { videoUrl ->
                    if (!videoUrl.isNullOrBlank()) {
                        loadExtractor(fixUrl(videoUrl), "$mainUrl/", subtitleCallback, callback)
                    }
                }
            }
        }

        return true
    }

    private suspend fun getAssets(bpjs: String): Assets? {
        val scriptUrl = "$mainUrl/assets/js/$bpjs.js"
        val env = app.get(scriptUrl).text
        
        fun extract(key: String): String {
            val regex = Regex("""$key['"]?\s*[:=]\s*['"]([^'"]+)['"]""")
            return regex.find(env)?.groupValues?.getOrNull(1) ?: ""
        }

        val assets = Assets(
            MIX_PREFIX_AUTH_ROUTE_PARAM = extract("MIX_PREFIX_AUTH_ROUTE_PARAM"),
            MIX_AUTH_ROUTE_PARAM = extract("MIX_AUTH_ROUTE_PARAM"),
            MIX_AUTH_KEY = extract("MIX_AUTH_KEY"),
            MIX_AUTH_TOKEN = extract("MIX_AUTH_TOKEN"),
            MIX_PAGE_TOKEN_KEY = extract("MIX_PAGE_TOKEN_KEY"),
            MIX_STREAM_SERVER_KEY = extract("MIX_STREAM_SERVER_KEY")
        )

        return if (assets.MIX_AUTH_KEY.isNotBlank() && assets.MIX_PAGE_TOKEN_KEY.isNotBlank()) {
            assets
        } else {
            null
        }
    }

    private fun randomId(length: Int = 6): String {
        val allowedChars = ('a'..'z') + ('A'..'Z') + ('0'..'9')
        return (1..length)
            .map { allowedChars.random() }
            .joinToString("")
    }

    data class Assets(
        val MIX_PREFIX_AUTH_ROUTE_PARAM: String,
        val MIX_AUTH_ROUTE_PARAM: String,
        val MIX_AUTH_KEY: String,
        val MIX_AUTH_TOKEN: String,
        val MIX_PAGE_TOKEN_KEY: String,
        val MIX_STREAM_SERVER_KEY: String,
    )
}
