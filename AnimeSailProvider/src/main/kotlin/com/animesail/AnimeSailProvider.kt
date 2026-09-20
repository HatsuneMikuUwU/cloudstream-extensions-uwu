package com.animesail

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addAniListId
import com.lagradost.cloudstream3.LoadResponse.Companion.addKitsuId
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId
import com.lagradost.cloudstream3.mvvm.safeApiCall
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.nicehttp.NiceResponse
import kotlinx.coroutines.runBlocking
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

class AnimeSailProvider : MainAPI() {
    override var mainUrl = "https://anisail.com"
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
            headers = mapOf(
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
                "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Mobile Safari/537.36",
            ),
            cookies = mapOf(
                "_as_ipin_ct" to "ID",
                "_as_ipin_tz" to "Asia/Jakarta",
                "_as_ipin_lc" to "id-ID"
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
            var title = uri.substringAfter("$mainUrl/").removeSuffix("/")
            title = when {
                title.contains("-episode-") -> title.substringBefore("-episode")
                Regex("-movie-\\d+$", RegexOption.IGNORE_CASE).containsMatchIn(title) ->
                    title.replace(Regex("-movie-\\d+$", RegexOption.IGNORE_CASE), "-movie")
                else -> title
            }
            "$mainUrl/anime/$title"
        }
    }

    private fun Element.toSearchResult(): AnimeSearchResponse {
        val rawHref = fixUrlNull(this.selectFirst("a")?.attr("href")).orEmpty()
        val href = getProperAnimeLink(rawHref)

        val rawTitle = this.selectFirst(".tt > h2")?.text() ?: ""

        val title = rawTitle.replace(Regex("(?i)Episode\\s?\\d+"), "")
            .replace(Regex("(?i)Subtitle Indonesia"), "")
            .replace(Regex("(?i)Sub Indo"), "")
            .trim()
            .removeSuffix("-")
            .trim()

        val posterUrl = fixUrlNull(this.selectFirst("div.limit img")?.attr("src"))

        val epNum = Regex("(?i)Episode\\s?(\\d+)").find(rawTitle)?.groupValues?.getOrNull(1)?.toIntOrNull()

        val typeText = this.selectFirst(".tt > span")?.text() ?: ""
        val type = if (typeText.contains("Movie", ignoreCase = true)) TvType.AnimeMovie else TvType.Anime

        return newAnimeSearchResponse(title, href, type) {
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

        val title = (
            document.selectFirst("h1.entry-title")?.text()
                ?: document.selectFirst("meta[property=og:title]")?.attr("content")
                ?: document.title()
            ).replace("Subtitle Indonesia", "").trim()
        val poster = document.selectFirst("div.entry-content > img")?.attr("src")
        val type = getType(document.select("tbody th:contains(Tipe)").next().text().lowercase())
        val year = document.select("tbody th:contains(Dirilis)").next().text().trim().toIntOrNull()
        val statusText = document.select("tbody th:contains(Status)").next().text().trim()
        val plotText = document.selectFirst("div.entry-content > p")?.text()
        val tagsList = document.select("tbody th:contains(Genre)").next().select("a").map { it.text() }
        val durationText = document.select("tbody th:contains(Durasi)").next().text().trim()

        val tracker = APIHolder.getTracker(listOf(title), TrackerType.getTypes(type), year, true)
        val ids = resolveAnimeIds(listOf(title), type, year, tracker?.malId, tracker?.aniId?.toIntOrNull())
        val malId = ids.malId
        val aniId = ids.aniId

        var animeMetaData: MetaAnimeData? = null
        var tmdbid: Int? = null
        var kitsuid: String? = null

        if (malId != null || aniId != null) {
            try {
                animeMetaData = fetchAniZipMeta(malId, aniId)
                tmdbid = animeMetaData?.mappings?.themoviedbId
                kitsuid = animeMetaData?.mappings?.kitsuId
            } catch (e: Exception) {
            }
        }

        val logoUrl = fetchTmdbLogoUrl(
            tmdbAPI = "https://api.themoviedb.org/3",
            apiKey = "98ae14df2b8d8f8f8136499daf79f0e0",
            type = type,
            tmdbId = tmdbid,
            appLangCode = "en"
        )

        val backgroundposter = animeMetaData?.images?.find { it.coverType == "Fanart" }?.url ?: tracker?.cover

        val episodes = document.select("ul.daftar > li").amap {
            val link = fixUrl(it.select("a").attr("href"))
            val name = it.select("a").text()

            var episodeNum = Regex("Episode\\s?(\\d+)").find(name)?.groupValues?.getOrNull(1)?.toIntOrNull()

            if (type == TvType.AnimeMovie && episodeNum == null) {
                episodeNum = 1
            }

            val episodeKey = episodeNum?.toString()
            val metaEp = if (episodeKey != null) animeMetaData?.episodes?.get(episodeKey) else null

            val epOverview = metaEp?.overview
            val finalOverview = epOverview?.takeIf { it.isNotBlank() }

            newEpisode(link) {
                this.name = if (type == TvType.AnimeMovie) {
                    animeMetaData?.titles?.get("en") ?: animeMetaData?.titles?.get("ja") ?: title
                } else {
                    metaEp?.title?.get("en") ?: metaEp?.title?.get("ja") ?: name
                }

                this.episode = episodeNum
                this.score = Score.from10(metaEp?.rating)
                this.posterUrl = metaEp?.image?.takeIf { it.isNotBlank() } ?: animeMetaData?.images?.firstOrNull()?.url ?: backgroundposter ?: tracker?.image ?: poster ?: ""
                this.description = finalOverview
                this.addDate(metaEp?.airDateUtc)
                this.runTime = metaEp?.runtime
            }
        }.reversed()

        val apiDescription = animeMetaData?.description?.replace(Regex("<.*?>"), "")
        val rawPlot = apiDescription?.takeIf { it.isNotBlank() }
            ?: animeMetaData?.episodes?.get("1")?.overview?.takeIf { it.isNotBlank() }
            ?: fetchAniListPlot(malId, aniId)

        val finalPlot = if (!rawPlot.isNullOrBlank()) {
            rawPlot
        } else {
            plotText
        }

        return newAnimeLoadResponse(title, url, TvType.Anime) {
            this.engName = animeMetaData?.titles?.get("en") ?: title
            this.japName = animeMetaData?.titles?.get("ja") ?: animeMetaData?.titles?.get("x-jat")
            this.posterUrl = tracker?.image ?: poster
            this.backgroundPosterUrl = backgroundposter
            try { this.logoUrl = logoUrl } catch (_: Throwable) {}
            this.year = year
            this.duration = getDurationFromString(durationText)
            addEpisodes(DubStatus.Subbed, episodes)
            this.showStatus = getStatus(statusText)
            this.plot = finalPlot
            this.tags = tagsList
            addMalId(malId)
            addAniListId(aniId)
            try { addKitsuId(kitsuid) } catch (_: Throwable) {}
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

                val decodedHtml = base64Decode(encodedData)
                    .replace("v1.animesail.xyz", mainUrl.substringAfter("://"))

                val iframe = fixUrl(Jsoup.parse(decodedHtml).select("iframe").attr("src"))
                if (iframe.contains("statistic") || iframe.isBlank()) return@safeApiCall

                val rawText = element.text().trim()
                val quality = getIndexQuality(rawText)

                val serverName = rawText.split(" ").firstOrNull()?.replaceFirstChar {
                    if (it.isLowerCase()) it.titlecase() else it.toString()
                } ?: name

                when {
                    iframe.endsWith(".mp4", ignoreCase = true) || iframe.endsWith(".m3u8", ignoreCase = true) -> {
                        callback.invoke(
                            newExtractorLink(
                                source = serverName,
                                name = serverName,
                                url = iframe,
                                type = if (iframe.endsWith(".m3u8", ignoreCase = true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
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
                            loadFixedExtractor(realUrl, serverName, quality, mainUrl, subtitleCallback, callback)
                        }
                    }

                    iframe.contains("${playerPath}framezilla") || iframe.contains("uservideo.xyz") -> {
                        val bsrc = Regex("""bsrc=(.*?)(&|$)""").find(iframe)?.groupValues?.getOrNull(1)
                        var innerLink = ""
                        
                        if (!bsrc.isNullOrBlank()) {
                            try {
                                innerLink = base64Decode(bsrc)
                            } catch (e: Exception) {
                            }
                        }
                        
                        if (innerLink.isBlank()) {
                            val doc = request(iframe, ref = data).document
                            innerLink = doc.select("iframe").attr("src")
                        }
                        
                        if (innerLink.isNotBlank()) {
                            innerLink = fixUrl(innerLink)
                            if (innerLink.contains(playerPath) || innerLink.contains("player-kodir")) {
                                extractInternalPlayer(innerLink, data, serverName, quality, callback)
                            } else {
                                loadFixedExtractor(innerLink, serverName, quality, mainUrl, subtitleCallback, callback)
                            }
                        }
                    }

                    iframe.contains("player-kodir") || iframe.contains("${playerPath}kodir2") || iframe.contains("${playerPath}mega") || iframe.contains("${playerPath}gideo") || iframe.contains(playerPath) -> {
                        extractInternalPlayer(iframe, data, serverName, quality, callback)
                    }

                    iframe.contains("aghanim.xyz/tools/redirect/") -> {
                        val id = iframe.substringAfter("id=").substringBefore("&token")
                        val link = "https://rasa-cintaku-semakin-berantai.xyz/v/$id"
                        loadFixedExtractor(link, serverName, quality, mainUrl, subtitleCallback, callback)
                    }

                    else -> {
                        loadFixedExtractor(iframe, serverName, quality, mainUrl, subtitleCallback, callback)
                    }
                }
            }
        }
        return true
    }

    private suspend fun extractInternalPlayer(
        url: String,
        ref: String,
        serverName: String,
        quality: Int,
        callback: (ExtractorLink) -> Unit
    ) {
        val res = request(url, ref = ref).text
        
        var link = Jsoup.parse(res.substringAfter("= `", "").substringBefore("`;", "")).select("source").last()?.attr("src")
        
        if (link.isNullOrBlank()) {
            link = Jsoup.parse(res).select("source").attr("src")
        }
        
        if (link.isNullOrBlank()) {
            link = Jsoup.parse(res).select("video").attr("src")
        }
        
        if (link.isNullOrBlank()) {
            link = Regex("""(?:file|src):\s*["']([^"']+)["']""").find(res)?.groupValues?.getOrNull(1)
        }

        if (!link.isNullOrBlank()) {
            callback.invoke(
                newExtractorLink(
                    source = serverName,
                    name = serverName,
                    url = fixUrl(link),
                    type = INFER_TYPE
                ) {
                    referer = url
                    this.quality = quality
                }
            )
        }
    }

    private suspend fun loadFixedExtractor(
        url: String,
        serverName: String,
        quality: Int?,
        referer: String? = null,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        loadExtractor(url, referer, subtitleCallback) { link ->
            val finalName = if (serverName.equals(link.name, ignoreCase = true)) link.name else "$serverName - ${link.name}"

            runBlocking {
                callback.invoke(
                    newExtractorLink(
                        source = link.name,
                        name = finalName,
                        url = link.url,
                        type = link.type
                    ) {
                        this.referer = referer ?: mainUrl
                        this.quality = if (link.type == ExtractorLinkType.M3U8) link.quality else quality ?: Qualities.Unknown.value
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
