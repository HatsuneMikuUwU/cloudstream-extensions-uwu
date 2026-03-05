package com.samehadaku

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.ObjectMapper
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addAniListId
import com.lagradost.cloudstream3.LoadResponse.Companion.addKitsuId
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.jsoup.nodes.Element

class SamehadakuProvider : MainAPI() {
    override var mainUrl = "https://v1.samehadaku.how"
    override var name = "Samehadaku"
    override val hasMainPage = true
    override var lang = "id"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.OVA
    )

    companion object {
        fun getType(t: String): TvType = when {
            t.contains("OVA", true) || t.contains("Special", true) -> TvType.OVA
            t.contains("Movie", true) -> TvType.AnimeMovie
            else -> TvType.Anime
        }
        fun getStatus(t: String): ShowStatus = when (t) {
            "Completed" -> ShowStatus.Completed
            "Ongoing" -> ShowStatus.Ongoing
            else -> ShowStatus.Completed
        }
    }

    override val mainPage = mainPageOf(
        "anime-terbaru/page/%d/" to "New Episodes",
        "daftar-anime-2/page/%d/?status=Currently+Airing&order=latest" to "Ongoing Anime",
        "daftar-anime-2/page/%d/?status=Finished+Airing&order=latest" to "Complete Anime",
        "daftar-anime-2/page/%d/?order=popular" to "Most Popular",
        "daftar-anime-2/page/%d/?type=Movie&order=latest" to "Movies",
    )
    
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get("$mainUrl/${request.data.format(page)}").document
        
        val items = when (request.name) {
            "New Episodes" -> document.select("li[itemtype='http://schema.org/CreativeWork']")
            "Ongoing Anime", "Complete Anime", "Most Popular", "Movies" -> document.select("div.animepost")
            else -> document.select("article.animpost")
        }

        val homeList = items.mapNotNull {
            if (request.name == "New Episodes") it.toLatestAnimeResult()
            else it.toSearchResult()
        }

        val isLandscape = request.name == "New Episodes"
        
        return newHomePageResponse(
            listOf(HomePageList(request.name, homeList, isHorizontalImages = isLandscape)),
            hasNext = homeList.isNotEmpty()
        )
    }

    private fun Element.toSearchResult(): AnimeSearchResponse? {
        val a = this.selectFirst("div.animepost a, div.animpost a") ?: return null
        val title = a.selectFirst("div.title h2, div.tt h4")?.text()?.trim() ?: a.attr("title") ?: return null
        val href = fixUrlNull(a.attr("href")) ?: return null
        val posterUrl = fixUrlNull(this.selectFirst("div.content-thumb img, div.limit img")?.attr("src"))
        val statusText = a.selectFirst("div.data > div.type, div.type")?.text()?.trim() ?: ""

        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = posterUrl
            addDubStatus(statusText)
        }
    }

    private fun Element.toLatestAnimeResult(): AnimeSearchResponse? {
        val a = this.selectFirst("div.thumb a") ?: return null
        val title = this.selectFirst("h2.entry-title a")?.text()?.trim() ?: a.attr("title") ?: return null
        val href = fixUrlNull(a.attr("href")) ?: return null
        val posterUrl = fixUrlNull(a.selectFirst("img")?.attr("src"))
        val epNum = this.selectFirst("div.dtla author")?.text()?.toIntOrNull()

        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = posterUrl
            addSub(epNum)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/?s=$query").document
        return document.select("div.animepost, article.animpost").mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse? {
        val fixUrl = if (url.contains("/anime/")) url
        else app.get(url).document.selectFirst("div.nvs.nvsc a")?.attr("href") ?: url
        
        val document = app.get(fixUrl).document
        
        val rawTitle = document.selectFirst("h1.entry-title")?.text() ?: return null
        val title = rawTitle.replace(Regex("(?i)(Nonton|Anime|Subtitle\\s+Indonesia|Sub\\s+Indo|Lengkap|Batch)"), "").trim()
        
        val poster = document.selectFirst("div.thumb > img")?.attr("src")
        val tags = document.select("div.genre-info > a").map { it.text() }
        
        val year = Regex("\\d, (\\d*)").find(
            document.select("div.spe > span:contains(Rilis)").text()
        )?.groupValues?.getOrNull(1)?.toIntOrNull()
        
        val statusStr = document.selectFirst("div.spe > span:contains(Status)")?.ownText()?.replace(":", "")?.trim() ?: "Completed"
        val status = getStatus(statusStr)
        val typeStr = document.selectFirst("div.spe > span:contains(Type)")?.ownText()?.replace(":", "")?.trim()?.lowercase() ?: "tv"
        val type = getType(typeStr)
        
        val rating = document.selectFirst("span.ratingValue, div.rating strong")?.text()?.replace("Rating", "")?.trim()?.toDoubleOrNull()       
        val description = document.select("div.desc p, div.entry-content p").text().trim()
        val trailer = document.selectFirst("div.trailer-anime iframe")?.attr("src")
        
        val tracker = APIHolder.getTracker(listOf(title), TrackerType.getTypes(type), year, true)
        val malId = tracker?.malId

        var animeMetaData: MetaAnimeData? = null
        var tmdbid: Int? = null
        var kitsuid: String? = null

        if (malId != null) {
            try {
                val syncMetaData = app.get("https://api.ani.zip/mappings?mal_id=$malId").text
                animeMetaData = parseAnimeData(syncMetaData)
                tmdbid = animeMetaData?.mappings?.themoviedbId
                kitsuid = animeMetaData?.mappings?.kitsuId
            } catch (e: Exception) {}
        }

        val logoUrl = fetchTmdbLogoUrl(
            tmdbAPI = "https://api.themoviedb.org/3",
            apiKey = "98ae14df2b8d8f8f8136499daf79f0e0",
            type = type,
            tmdbId = tmdbid,
            appLangCode = "en"
        )

        val backgroundposter = animeMetaData?.images?.find { it.coverType == "Fanart" }?.url ?: tracker?.cover

        val episodes = document.select("div.lstepsiode.listeps ul li").amap { element ->
            val header = element.selectFirst("span.lchx > a") ?: return@amap null
            val name = header.text()
            var episodeNum = Regex("Episode\\s?(\\d+)").find(name)?.groupValues?.getOrNull(1)?.toIntOrNull()
            
            if (type == TvType.AnimeMovie && episodeNum == null) {
                episodeNum = 1
            }

            val link = fixUrl(header.attr("href"))
            val episodeKey = episodeNum?.toString()
            val metaEp = if (episodeKey != null) animeMetaData?.episodes?.get(episodeKey) else null

            val epOverview = metaEp?.overview
            val finalOverview = if (!epOverview.isNullOrBlank()) {
                epOverview
            } else {
                ""
            }

            newEpisode(link) { 
                this.name = if (type == TvType.AnimeMovie) {
                    animeMetaData?.titles?.get("en") ?: animeMetaData?.titles?.get("ja") ?: title
                } else {
                    metaEp?.title?.get("en") ?: metaEp?.title?.get("ja") ?: name
                }
                this.episode = episodeNum 
                this.score = Score.from10(metaEp?.rating)
                this.posterUrl = metaEp?.image ?: animeMetaData?.images?.firstOrNull()?.url ?: ""
                this.description = finalOverview
                this.addDate(metaEp?.airDateUtc)
                this.runTime = metaEp?.runtime
            }
        }.filterNotNull().reversed()

        val recommendations = document.select("aside#sidebar ul li, div.relat animepost").mapNotNull { it.toSearchResult() }

        val apiDescription = animeMetaData?.description?.replace(Regex("<.*?>"), "")
        val rawPlot = apiDescription ?: animeMetaData?.episodes?.get("1")?.overview
        
        val finalPlot = if (!rawPlot.isNullOrBlank()) {
            rawPlot
        } else {
            description
        }

        return newAnimeLoadResponse(title, url, TvType.Anime) {
            this.engName = animeMetaData?.titles?.get("en") ?: title
            this.japName = animeMetaData?.titles?.get("ja") ?: animeMetaData?.titles?.get("x-jat")
            this.posterUrl = tracker?.image ?: poster
            this.backgroundPosterUrl = backgroundposter
            try { this.logoUrl = logoUrl } catch(_:Throwable){}
            this.year = year
            addEpisodes(DubStatus.Subbed, episodes)
            this.showStatus = status
            this.score = rating?.let { Score.from10(it) } ?: Score.from10(animeMetaData?.episodes?.get("1")?.rating)
            this.plot = finalPlot
            addTrailer(trailer)
            this.tags = tags
            this.recommendations = recommendations
            
            addMalId(malId)
            addAniListId(tracker?.aniId?.toIntOrNull())
            try { addKitsuId(kitsuid) } catch(_:Throwable){}
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document

        document.select("div#downloadb li").amap { el ->
            el.select("a").amap {
                loadFixedExtractor(
                    fixUrl(it.attr("href")),
                    el.select("strong").text(),
                    "$mainUrl/",
                    subtitleCallback,
                    callback
                )
            }
        }
        return true
    }

    private suspend fun loadFixedExtractor(
        url: String,
        name: String,
        referer: String? = null,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        loadExtractor(url, referer, subtitleCallback) { link ->
            runBlocking {
                callback.invoke(
                    newExtractorLink(link.name, link.name, link.url, link.type) {
                        this.referer = link.referer
                        this.quality = name.fixQuality()
                        this.headers = link.headers
                        this.extractorData = link.extractorData
                    }
                )
            }
        }
    }

    private fun String.fixQuality(): Int = when (this.uppercase()) {
        "4K" -> Qualities.P2160.value
        "FULLHD" -> Qualities.P1080.value
        "MP4HD" -> Qualities.P720.value
        else -> this.filter { it.isDigit() }.toIntOrNull() ?: Qualities.Unknown.value
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class MetaImage(
        @JsonProperty("coverType") val coverType: String?,
        @JsonProperty("url") val url: String?
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class MetaEpisode(
        @JsonProperty("episode") val episode: String?,
        @JsonProperty("airDateUtc") val airDateUtc: String?,
        @JsonProperty("runtime") val runtime: Int?,
        @JsonProperty("image") val image: String?,
        @JsonProperty("title") val title: Map<String, String>?,
        @JsonProperty("overview") val overview: String?,
        @JsonProperty("rating") val rating: String?,
        @JsonProperty("finaleType") val finaleType: String?
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class MetaAnimeData(
        @JsonProperty("titles") val titles: Map<String, String>?,
        @JsonProperty("description") val description: String?,
        @JsonProperty("images") val images: List<MetaImage>?,
        @JsonProperty("episodes") val episodes: Map<String, MetaEpisode>?,
        @JsonProperty("mappings") val mappings: MetaMappings? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class MetaMappings(
        @JsonProperty("themoviedb_id") val themoviedbId: Int? = null,
        @JsonProperty("kitsu_id") val kitsuId: String? = null
    )

    private fun parseAnimeData(jsonString: String): MetaAnimeData? {
        return try {
            val objectMapper = ObjectMapper()
            objectMapper.readValue(jsonString, MetaAnimeData::class.java)
        } catch (_: Exception) {
            null
        }
    }
}

suspend fun fetchTmdbLogoUrl(
    tmdbAPI: String,
    apiKey: String,
    type: TvType,
    tmdbId: Int?,
    appLangCode: String?
): String? {
    if (tmdbId == null) return null

    val url = if (type == TvType.AnimeMovie)
        "$tmdbAPI/movie/$tmdbId/images?api_key=$apiKey"
    else
        "$tmdbAPI/tv/$tmdbId/images?api_key=$apiKey"

    val json = runCatching { JSONObject(app.get(url).text) }.getOrNull() ?: return null
    val logos = json.optJSONArray("logos") ?: return null
    if (logos.length() == 0) return null

    val lang = appLangCode?.trim()?.lowercase()

    fun path(o: JSONObject) = o.optString("file_path")
    fun isSvg(o: JSONObject) = path(o).endsWith(".svg", true)
    fun urlOf(o: JSONObject) = "https://image.tmdb.org/t/p/w500${path(o)}"

    var svgFallback: JSONObject? = null

    for (i in 0 until logos.length()) {
        val logo = logos.optJSONObject(i) ?: continue
        val p = path(logo)
        if (p.isBlank()) continue

        val l = logo.optString("iso_639_1").trim().lowercase()
        if (l == lang) {
            if (!isSvg(logo)) return urlOf(logo)
            if (svgFallback == null) svgFallback = logo
        }
    }
    svgFallback?.let { return urlOf(it) }

    var best: JSONObject? = null
    var bestSvg: JSONObject? = null

    fun voted(o: JSONObject) = o.optDouble("vote_average", 0.0) > 0 && o.optInt("vote_count", 0) > 0
    fun better(a: JSONObject?, b: JSONObject): Boolean {
        if (a == null) return true
        val aAvg = a.optDouble("vote_average", 0.0)
        val aCnt = a.optInt("vote_count", 0)
        val bAvg = b.optDouble("vote_average", 0.0)
        val bCnt = b.optInt("vote_count", 0)
        return bAvg > aAvg || (bAvg == aAvg && bCnt > aCnt)
    }

    for (i in 0 until logos.length()) {
        val logo = logos.optJSONObject(i) ?: continue
        if (!voted(logo)) continue

        if (isSvg(logo)) {
            if (better(bestSvg, logo)) bestSvg = logo
        } else {
            if (better(best, logo)) best = logo
        }
    }

    best?.let { return urlOf(it) }
    bestSvg?.let { return urlOf(it) }

    return null
}
