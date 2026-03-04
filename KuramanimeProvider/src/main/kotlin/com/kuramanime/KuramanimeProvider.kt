package com.kuramanime

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addAniListId
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

class KuramanimeProvider : MainAPI() {
    override var mainUrl = "https://v16.kuramanime.ink" 
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
        "$mainUrl/quick/ongoing?order_by=updated&page=" to "Sedang Tayang",
        "$mainUrl/quick/finished?order_by=updated&page=" to "Selesai Tayang",
        "$mainUrl/quick/donghua?order_by=updated&page=" to "Donghua",
        "$mainUrl/quick/movie?order_by=updated&page=" to "Film Layar Lebar",
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val document = app.get(request.data + page).document
        val home = document.select("div.product__item").mapNotNull {
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
            "$mainUrl/anime?search=$query&order_by=latest"
        ).document.select("div.product__item").mapNotNull {
            it.toSearchResult()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

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
            val doc = app.get("$url?page=$i").document
            val content = doc.select("#episodeLists").attr("data-content")
            if (content.isBlank()) break

            val eps = Jsoup.parse(content)
                .select("a.btn.btn-sm.btn-danger")
                .mapNotNull {
                    val name = it.text().trim()
                    val episodeNum = Regex("(\\d+[.,]?\\d*)").find(name)?.groupValues?.getOrNull(0)?.toIntOrNull()
                    val link = it.attr("href")
                    newEpisode(link) { this.episode = episodeNum }
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
            addEpisodes(DubStatus.Subbed, episodes.reversed())
            showStatus = status
            plot = description
            this.tags = tags
            this.recommendations = recommendations
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
        val req = app.get(data)
        val res = req.document
        val cookies = req.cookies

        val csrfToken = res.selectFirst("meta[name=csrf-token]")?.attr("content") ?: return false
        val dataKps = res.selectFirst("[data-kk]")?.attr("data-kk") ?: return false

        val assets = getAssets(dataKps)

        val headers = mapOf(
            "X-CSRF-TOKEN" to csrfToken,
            "X-Fuck-ID" to "${assets.MIX_AUTH_KEY}:${assets.MIX_AUTH_TOKEN}",
            "X-Request-ID" to randomId(),
            "X-Request-Index" to "0",
            "X-Requested-With" to "XMLHttpRequest",
        )

        val tokenKeyUrl = "$mainUrl/${assets.MIX_PREFIX_AUTH_ROUTE_PARAM}${assets.MIX_AUTH_ROUTE_PARAM}"
        val tokenKeyReq = app.get(tokenKeyUrl, headers = headers, cookies = cookies)
        val tokenKey = tokenKeyReq.text.trim()

        if (tokenKey.isBlank()) return false

        res.select("select#changeServer option").forEach { source ->
            val serverName = source.attr("value")
            
            val ajaxUrl = "$data?${assets.MIX_PAGE_TOKEN_KEY}=$tokenKey&${assets.MIX_STREAM_SERVER_KEY}=$serverName"
            val ajaxHtml = app.get(ajaxUrl, referer = data, headers = headers, cookies = cookies).document

            if (serverName.contains("kuramadrive", true) || serverName.contains("archive", true)) {
                
                ajaxHtml.select("video#player > source").forEach { vidSource ->
                    val link = fixUrl(vidSource.attr("src"))
                    val quality = vidSource.attr("size").toIntOrNull() ?: Qualities.Unknown.value

                    if (link.contains("pid=") && link.contains("sid=")) {
                        val pid = Regex("pid=([^&]+)").find(link)?.groupValues?.get(1)
                        val sid = Regex("sid=([^&]+)").find(link)?.groupValues?.get(1)

                        if (pid != null && sid != null) {
                            try {
                                val tokenUrl = "$mainUrl/misc/token/drive-token"
                                val driveTokenReq = app.post(
                                    tokenUrl,
                                    headers = mapOf(
                                        "X-CSRF-TOKEN" to csrfToken,
                                        "X-Requested-With" to "XMLHttpRequest",
                                        "Content-Type" to "application/json",
                                        "Accept" to "application/json"
                                    ),
                                    json = mapOf("pid" to pid, "sid" to sid),
                                    cookies = cookies
                                )
                                
                                val accessToken = Regex(""""access_token"\s*:\s*"([^"]+)"""").find(driveTokenReq.text)?.groupValues?.get(1)
                                val gid = Regex(""""gid"\s*:\s*"([^"]+)"""").find(driveTokenReq.text)?.groupValues?.get(1)

                                if (accessToken != null && gid != null) {
                                    val realVideoUrl = "https://www.googleapis.com/drive/v3/files/$gid?alt=media"
                                    
                                    callback.invoke(
                                        newExtractorLink(
                                            "Kuramadrive",
                                            "Kuramadrive",
                                            realVideoUrl,
                                            INFER_TYPE
                                        ) {
                                            this.referer = "$mainUrl/"
                                            this.quality = quality
                                            this.headers = mapOf(
                                                "Authorization" to "Bearer $accessToken",
                                                "Accept" to "video/webm,video/ogg,video/*;q=0.9,application/ogg;q=0.7,audio/*;q=0.6,*/*;q=0.5",
                                                "Range" to "bytes=0-"
                                            )
                                        }
                                    )
                                }
                            } catch (e: Exception) {
                                e.printStackTrace()
                            }
                        }
                    } else {
                        callback.invoke(
                            newExtractorLink(
                                "Kuramadrive Raw",
                                "Kuramadrive Raw",
                                link,
                                INFER_TYPE
                            ) {
                                this.referer = "$mainUrl/"
                                this.quality = quality
                            }
                        )
                    }
                }
                
                ajaxHtml.select("div#animeDownloadLink a").forEach { a ->
                    val dlLink = a.attr("href")
                    if (dlLink.startsWith("http")) {
                        loadExtractor(dlLink, data, subtitleCallback, callback)
                    }
                }

            } else {
                ajaxHtml.selectFirst("iframe")?.attr("src")?.let { videoUrl ->
                    if (videoUrl.isNotBlank()) {
                        loadExtractor(fixUrl(videoUrl), "$mainUrl/", subtitleCallback, callback)
                    }
                }
            }
        }
        return true
    }

    private suspend fun getAssets(bpjs: String?): Assets {
        val env = app.get("$mainUrl/assets/js/$bpjs.js").text
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
        return (1..length).map { allowedChars.random() }.joinToString("")
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
