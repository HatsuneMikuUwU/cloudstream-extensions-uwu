package com.hexated

import app.cash.quickjs.QuickJs
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addAniListId
import com.lagradost.cloudstream3.LoadResponse.Companion.addKitsuId
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URI
import java.util.Calendar

class KuramanimeProvider : MainAPI() {
    override var mainUrl = "https://v20.kuramanime.ing"
    override var name = "Kuramanime"
    override val hasQuickSearch = true
    override val hasMainPage = true
    override var lang = "id"
    override var sequentialMainPage = true
    override val hasDownloadSupport = true
    
    var authorization: String? = "kJuHHkaqcBFXiGMHQf6bJw8YAyDcwGD8Ur"
    
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
                "Ongoing" -> ShowStatus.Completed
                "Completed" -> ShowStatus.Ongoing
                else -> ShowStatus.Completed
            }
        }
    }

    private fun getCurrentSeason(): String {
        val calendar = Calendar.getInstance()
        val year = calendar.get(Calendar.YEAR)
        val season = when (calendar.get(Calendar.MONTH)) {
            in 0..2 -> "winter"
            in 3..5 -> "spring"
            in 6..8 -> "summer"
            else -> "fall"
        }
        return "$season-$year"
    }

    override val mainPage = mainPageOf(
        "$mainUrl/quick/ongoing?order_by=updated&page=" to "Ongoing",
        "$mainUrl/quick/finished?order_by=updated&page=" to "Completed",
        "$mainUrl/properties/season/${getCurrentSeason()}?order_by=most_viewed&page=" to "Most Viewed This Season",
        "$mainUrl/quick/movie?order_by=updated&page=" to "Movies",
        "$mainUrl/quick/donghua?order_by=updated&page=" to "Donghua"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
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
            Regex("(?i)ep\\s*(\\d+)").find(it)?.groupValues?.getOrNull(1)?.toIntOrNull()
        }

        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = posterUrl
            addSub(episode)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return app.get("$mainUrl/anime?search=$query&order_by=latest").document.select("div.product__item").mapNotNull {
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
            val doc = if (i == 1) document else app.get("$url?page=$i").document
            
            val dataContent = doc.select("#episodeLists").attr("data-content")
            val epsElements = if (dataContent.isNotBlank()) {
                Jsoup.parse(dataContent).select("a.btn.btn-sm.btn-danger")
            } else {
                doc.select("div#animeEpisodes a.ep-button, #episodeLists a.btn.btn-sm.btn-danger")
            }
            
            if (epsElements.isEmpty() && i > 1) break
            
            val eps = epsElements.mapNotNull {
                val name = it.text().trim()
                val episode = Regex("(?i)ep(?:isode)?\\s*(\\d+)").find(name)?.groupValues?.get(1)?.toIntOrNull() 
                    ?: Regex("(\\d+)").find(name)?.groupValues?.get(1)?.toIntOrNull()
                val link = fixUrl(it.attr("href"))
                newEpisode(link) { 
                    this.name = name
                    this.episode = episode 
                }
            }
            if (eps.isEmpty()) break else episodes.addAll(eps)
        }

        val type = getType(
            document.selectFirst("div.col-lg-6.col-md-6 ul li:contains(Tipe:) a")?.text()?.lowercase() ?: "tv", episodes.size
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
        val ids = resolveAnimeIds(listOf(title), type, year, tracker?.malId, tracker?.aniId?.toIntOrNull())
        val malId = ids.malId
        val aniId = ids.aniId

        // api.ani.zip: titles, description, fanart and per-episode metadata
        val animeMetaData = fetchAniZipMeta(malId, aniId)
        val tmdbId = animeMetaData?.mappings?.themoviedbId
        val kitsuId = animeMetaData?.mappings?.kitsuId

        // api.themoviedb.org: title logo
        val tmdbLogoUrl = fetchTmdbLogoUrl(
            tmdbAPI = "https://api.themoviedb.org/3",
            apiKey = "98ae14df2b8d8f8f8136499daf79f0e0",
            type = type,
            tmdbId = tmdbId,
            appLangCode = "en"
        )

        val backgroundPoster = animeMetaData?.images?.find { it.coverType == "Fanart" }?.url ?: tracker?.cover

        val finalEpisodes = episodes.distinctBy { it.data }.map { ep ->
            val episodeNum = ep.episode ?: if (type == TvType.AnimeMovie) 1 else null
            val metaEp = episodeNum?.let { animeMetaData?.episodes?.get(it.toString()) }
            val epOverview = metaEp?.overview

            newEpisode(ep.data) {
                this.name = if (type == TvType.AnimeMovie) {
                    animeMetaData?.titles?.get("en") ?: animeMetaData?.titles?.get("ja") ?: ep.name
                } else {
                    metaEp?.title?.get("en") ?: metaEp?.title?.get("ja") ?: ep.name
                }
                this.episode = episodeNum
                this.score = Score.from10(metaEp?.rating)
                this.posterUrl = metaEp?.image?.takeIf { it.isNotBlank() } ?: animeMetaData?.images?.firstOrNull()?.url ?: backgroundPoster
                this.description = if (!epOverview.isNullOrBlank()) epOverview else "Synopsis not yet available."
                this.addDate(metaEp?.airDateUtc)
                this.runTime = metaEp?.runtime
            }
        }

        val apiDescription = animeMetaData?.description?.replace(Regex("<.*?>"), "")
        val rawPlot = apiDescription?.takeIf { it.isNotBlank() }
            ?: animeMetaData?.episodes?.get("1")?.overview?.takeIf { it.isNotBlank() }
            ?: fetchAniListPlot(malId, aniId)
        val finalPlot = if (!rawPlot.isNullOrBlank()) rawPlot else description

        return newAnimeLoadResponse(title, url, type) {
            engName = animeMetaData?.titles?.get("en") ?: title
            japName = animeMetaData?.titles?.get("ja") ?: animeMetaData?.titles?.get("x-jat")
            posterUrl = tracker?.image ?: poster
            backgroundPosterUrl = backgroundPoster
            try { this.logoUrl = tmdbLogoUrl } catch (_: Throwable) {}
            this.year = year
            addEpisodes(DubStatus.Subbed, finalEpisodes)
            showStatus = status
            plot = finalPlot
            this.tags = tags
            this.recommendations = recommendations
            addMalId(malId)
            addAniListId(aniId)
            try { addKitsuId(kitsuId) } catch (_: Throwable) {}
        }
    }

    private suspend fun invokeLocalSource(url: String, server: String, headers: Map<String, String>, authScriptUrl: String, refererUrl: String, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val request = app.post(url, data = mapOf("authorization" to getAuth(authScriptUrl, refererUrl)), headers = headers, cookies = cookies)
        delay(2000)
        val document = request.document
        document.select("video#player > source").map {
            val link = fixUrl(it.attr("src"))
            val quality = it.attr("size").toIntOrNull()
            callback.invoke(newExtractorLink(fixTitle(server), fixTitle(server), link, INFER_TYPE) {
                this.quality = quality ?: Qualities.Unknown.value
            })
        }
        if (server == "kuramadrive") {
            document.select("div#animeDownloadLink a").amap {
                loadExtractor(it.attr("href"), "$mainUrl/", subtitleCallback, callback)
            }
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val req = app.get(data)
        val res = req.document
        cookies = req.cookies

        val token = res.selectFirst("meta[name=csrf-token]")?.attr("content") ?: return false
        val dataKps = res.selectFirst("[data-kk]")?.attr("data-kk") ?: return false
        val tokenAuthUrl = res.selectFirst("input#tokenAuthJs")?.attr("value")
        val authScriptUrl = if (tokenAuthUrl != null) "$mainUrl$tokenAuthUrl" else "$mainUrl/storage/leviathan.js?v=${System.currentTimeMillis()}"

        val assets = getAssets(dataKps)
        var headers = mapOf(
            "X-CSRF-TOKEN" to token,
            "X-Fuck-ID" to "${assets.MIX_AUTH_KEY}:${assets.MIX_AUTH_TOKEN}",
            "X-Request-ID" to randomId(),
            "X-Request-Index" to "0",
            "X-Requested-With" to "XMLHttpRequest",
        )

        val tokenRes = app.get("$mainUrl/${assets.MIX_PREFIX_AUTH_ROUTE_PARAM}${assets.MIX_AUTH_ROUTE_PARAM}", headers = headers, cookies = cookies)
        val tokenKey = tokenRes.text
        cookies = tokenRes.cookies

        headers = mapOf("X-CSRF-TOKEN" to token, "X-Requested-With" to "XMLHttpRequest")

        res.select("select#changeServer option").amap { source ->
            val server = source.attr("value")
            val link = "$data?${assets.MIX_PAGE_TOKEN_KEY}=$tokenKey&${assets.MIX_STREAM_SERVER_KEY}=$server"
            if (server.contains(Regex("(?i)kuramadrive|archive"))) {
                invokeLocalSource(link, server, headers, authScriptUrl, data, subtitleCallback, callback)
            } else {
                val request = app.post(link, data = mapOf("authorization" to getAuth(authScriptUrl, data)), referer = data, headers = headers, cookies = cookies)
                delay(2000)
                request.document.select("div.iframe-container iframe").attr("src").let { videoUrl ->
                    loadExtractor(fixUrl(videoUrl), "$mainUrl/", subtitleCallback, callback)
                }
            }
        }
        return true
    }

    private suspend fun getAssets(bpjs: String?): Assets {
        val env = app.get("$mainUrl/assets/js/$bpjs.js").text
        return Assets(
            env.substringAfter("MIX_PREFIX_AUTH_ROUTE_PARAM: '").substringBefore("',"),
            env.substringAfter("MIX_AUTH_ROUTE_PARAM: '").substringBefore("',"),
            env.substringAfter("MIX_AUTH_KEY: '").substringBefore("',"),
            env.substringAfter("MIX_AUTH_TOKEN: '").substringBefore("',"),
            env.substringAfter("MIX_PAGE_TOKEN_KEY: '").substringBefore("',"),
            env.substringAfter("MIX_STREAM_SERVER_KEY: '").substringBefore("',")
        )
    }

    suspend fun getAuth(tokenUrl: String, referer: String): String {
        return authorization ?: fetchAuth(tokenUrl, referer).also { authorization = it }
    }

    suspend fun fetchAuth(tokenUrl: String, referer: String): String {
        val jsReqHeaders = mapOf(
            "Accept" to "*/*",
            "Referer" to referer,
            "X-Requested-With" to "XMLHttpRequest"
        )
        
        val jsCode = app.get(tokenUrl, headers = jsReqHeaders, cookies = cookies).text

        if (jsCode.trim().startsWith("<")) {
            throw ErrorLoadingException("Failed: leviathan.js intercepted by Cloudflare. Try disabling your proxy/VPN for a while.")
        }
        
        val host = URI(mainUrl).host

        val script = """
            var window = this;
            var global = this;
            var document = { createElement: function() { return {}; } };
            var navigator = { userAgent: "Mozilla/5.0" };
            var location = { hostname: "$host", href: "$mainUrl" };
            
            var extractedToken = "FAILED_EMPTY";

            var fetch = function(reqUrl, options) {
                if (options && options.headers && options.headers['Authorization']) {
                    extractedToken = options.headers['Authorization'];
                }
            };

            var ${'$'} = function(options) {
                if (options && options.headers && options.headers['Authorization']) {
                    extractedToken = options.headers['Authorization'];
                }
                return { done: function(){ return this; }, fail: function(){ return this; } };
            };
            ${'$'}.ajax = ${'$'};
            window.${'$'} = ${'$'};
            window.jQuery = ${'$'};
            
            try {
                $jsCode
            } catch(e) {
                extractedToken = "ERROR_EVAL: " + e.message;
            }

            if (extractedToken === "FAILED_EMPTY") {
                for (var key in window) {
                    if (typeof window[key] === 'function' && key !== 'fetch' && key !== '${'$'}' && key !== 'evaluate') {
                        try {
                            window[key]('https://dummy', 'GET', "{}");
                        } catch(e) {}
                    }
                }
            }
            
            extractedToken;
        """.trimIndent()

        val authHeader = QuickJs.create().use { ctx ->
            ctx.evaluate(script) as String?
        }

        if (authHeader.isNullOrEmpty() || authHeader.startsWith("FAILED") || authHeader.startsWith("ERROR")) {
            throw ErrorLoadingException("QuickJs failed to extract token: $authHeader")
        }

        return authHeader.replace("Bearer ", "", ignoreCase = true).trim()
    }

    private fun randomId(length: Int = 6): String {
        val allowedChars = ('a'..'z') + ('A'..'Z') + ('0'..'9')
        return (1..length).map { allowedChars.random() }.joinToString("")
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
