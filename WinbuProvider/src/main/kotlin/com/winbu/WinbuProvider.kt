package com.winbu

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.ObjectMapper
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addAniListId
import com.lagradost.cloudstream3.LoadResponse.Companion.addKitsuId
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.httpsify
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.util.Collections

class WinbuProvider : MainAPI() {
    override var mainUrl = "https://winbu.net"
    override var name = "Winbu"
    override val hasMainPage = true
    override var lang = "id"
    override val hasQuickSearch = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.Anime, TvType.TvSeries)

    data class FiledonPage(val props: FiledonProps? = null)
    data class FiledonProps(val url: String? = null, val files: FiledonFile? = null)
    data class FiledonFile(val name: String? = null)

    override val mainPage = mainPageOf(
        "anime-terbaru-animasu/page/%d/" to "New Episodes",
        "daftar-anime-2/page/%d/?status=Currently+Airing&order=latest" to "Ongoing Anime",
        "daftar-anime-2/page/%d/?status=Finished+Airing&order=latest" to "Complete Anime",
        "daftar-anime-2/page/%d/?order=popular" to "Most Popular",
        "daftar-anime-2/page/%d/?type=Movie&order=latest" to "Movie",
        "daftar-anime-2/page/%d/?type=Film&order=latest" to "Film",
        "tvshow/page/%d/" to "TV Show",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val path = if (page == 1) {
            request.data.replace("/page/%d/", "/").replace("page/%d/", "")
        } else {
            request.data.format(page)
        }

        val document = app.get("$mainUrl/$path").documentLarge
        
        val cssSelector = if (request.name in listOf("Ongoing Anime", "Complete Anime", "Most Popular", "Movie")) {
            "#anime .ml-item, .movies-list .ml-item"
        } else {
            "#movies .ml-item, .movies-list .ml-item"
        }
        
        val homeList = document.select(cssSelector)
            .mapNotNull { it.toSearchResult(request.name) }
            .distinctBy { it.url }

        val hasNext = document.selectFirst(".pagination a.next, a.next.page-numbers") != null || 
                document.select(".pagination a[href], #pagination a[href]").any {
                    it.selectFirst("i.fa-caret-right, i.fa-angle-right, i.fa-chevron-right") != null || 
                    it.text().contains("Next", ignoreCase = true)
                }

        return newHomePageResponse(
            listOf(HomePageList(request.name, homeList, isHorizontalImages = request.name == "Movie")),
            hasNext = hasNext || homeList.isNotEmpty()
        )
    }

    private fun parseEpisode(text: String?): Int? {
        return text?.let { 
            Regex("(\\d+[.,]?\\d*)").find(it)?.value?.replace(',', '.')?.toFloatOrNull()?.toInt() 
        }
    }

    private fun Element.toSearchResult(sectionName: String): SearchResponse? {
        val anchor = selectFirst("a.ml-mask, a[href]") ?: return null
        val href = fixUrl(anchor.attr("href"))

        val title = anchor.attr("title").takeIf { it.isNotBlank() }
            ?: selectFirst(".judul")?.text()?.takeIf { it.isNotBlank() }
            ?: selectFirst("img.mli-thumb, img")?.attr("alt").orEmpty()
            
        if (title.isBlank()) return null

        val poster = selectFirst("img.mli-thumb, img")?.getImageAttr()?.let { fixUrlNull(it) }
        val episode = parseEpisode(selectFirst("span.mli-episode")?.text())

        val isMovie = sectionName.contains("Film", true) || sectionName.contains("Movie", true) || href.contains("/film/", true)

        return if (isMovie) {
            newMovieSearchResponse(title.trim(), href, TvType.Movie) { this.posterUrl = poster }
        } else {
            newAnimeSearchResponse(title.trim(), href, TvType.Anime) {
                this.posterUrl = poster
                episode?.let { addSub(it) }
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return app.get("$mainUrl/?s=$query").documentLarge
            .select("#movies .ml-item, .movies-list .ml-item")
            .mapNotNull { it.toSearchResult("Series") }
            .distinctBy { it.url }
    }

    private fun cleanupTitle(rawTitle: String): String {
        return rawTitle
            .replace(Regex("^(Nonton\\s+|Download\\s+)", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s+Sub\\s+Indo.*$", RegexOption.IGNORE_CASE), "")
            .replace(" - Winbu", "", ignoreCase = true)
            .trim()
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).documentLarge
        val infoRoot = document.selectFirst(".m-info .t-item") ?: document

        val rawTitle = infoRoot.selectFirst(".mli-info .judul")?.text()
            ?: document.selectFirst("h1")?.text()
            ?: document.selectFirst("meta[property=og:title]")?.attr("content")
            ?: "No Title"
        val title = cleanupTitle(rawTitle)

        val poster = infoRoot.selectFirst("img.mli-thumb")?.getImageAttr()?.let { fixUrlNull(it) }
            ?: document.selectFirst("meta[property=og:image]")?.attr("content")
            ?: document.selectFirst("img")?.getImageAttr()?.let { fixUrlNull(it) }

        val description = infoRoot.selectFirst(".mli-desc")?.text()?.trim()
            ?: document.selectFirst("meta[name=description]")?.attr("content")

        val tags = infoRoot.select(".mli-mvi a[rel=tag], a[rel=tag]")
            .mapNotNull { it.text().trim().takeIf { t -> t.isNotBlank() } }
            .distinct()

        val scoreValue = infoRoot.selectFirst("span[itemprop=ratingValue]")?.text()?.toDoubleOrNull()

        val recommendations = document.select("#movies .ml-item")
            .mapNotNull { it.toSearchResult("Series") }
            .filterNot { fixUrl(it.url) == fixUrl(url) }
            .distinctBy { it.url }

        val isMovie = url.contains("/film/", true) || url.contains("/movie/", true)
        val tvType = if (isMovie) TvType.AnimeMovie else TvType.Anime

        val tracker = APIHolder.getTracker(listOf(title), TrackerType.getTypes(tvType), null, true)
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
            type = tvType,
            tmdbId = tmdbid,
            appLangCode = "en"
        )

        val backgroundposter = animeMetaData?.images?.find { it.coverType == "Fanart" }?.url ?: tracker?.cover

        val extractedEpisodes = document.select(".tvseason .les-content a[href]")
            .mapNotNull { a ->
                val epText = a.text().trim()
                val epNum = parseEpisode(epText)
                if (epNum == null || !epText.contains("Episode", true)) return@mapNotNull null
                epNum to fixUrl(a.attr("href"))
            }
            .distinctBy { it.second }
            .sortedBy { it.first }

        val finalEpisodes = if (extractedEpisodes.isEmpty() || isMovie) {
            val epOverview = animeMetaData?.episodes?.get("1")?.overview
            val finalOverview = if (!epOverview.isNullOrBlank()) epOverview else ""
            
            listOf(
                newEpisode(url) {
                    this.name = animeMetaData?.titles?.get("en") ?: animeMetaData?.titles?.get("ja") ?: title
                    this.episode = 1
                    this.score = Score.from10(animeMetaData?.episodes?.get("1")?.rating)
                    this.posterUrl = animeMetaData?.episodes?.get("1")?.image ?: animeMetaData?.images?.firstOrNull()?.url ?: ""
                    this.description = finalOverview
                    this.addDate(animeMetaData?.episodes?.get("1")?.airDateUtc)
                }
            )
        } else {
            extractedEpisodes.amap { (num, link) ->
                val episodeKey = num.toString()
                val metaEp = animeMetaData?.episodes?.get(episodeKey)

                val epOverview = metaEp?.overview
                val finalOverview = if (!epOverview.isNullOrBlank()) epOverview else ""

                newEpisode(link) {
                    this.name = metaEp?.title?.get("en") ?: metaEp?.title?.get("ja") ?: "Episode $num"
                    this.episode = num
                    this.score = Score.from10(metaEp?.rating)
                    this.posterUrl = metaEp?.image ?: animeMetaData?.images?.firstOrNull()?.url ?: ""
                    this.description = finalOverview
                    this.addDate(metaEp?.airDateUtc)
                    this.runTime = metaEp?.runtime
                }
            }
        }

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
            addEpisodes(DubStatus.Subbed, finalEpisodes)
            this.plot = finalPlot
            this.tags = tags
            this.recommendations = recommendations
            
            val finalScore = scoreValue?.let { Score.from10(it) } ?: Score.from10(animeMetaData?.episodes?.get("1")?.rating)
            this.score = finalScore

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
        val document = app.get(data).documentLarge
        var found = false
        val seen = Collections.synchronizedSet(hashSetOf<String>())
        
        val subtitleCb: (SubtitleFile) -> Unit = { subtitleCallback.invoke(it) }
        val linkCb: (ExtractorLink) -> Unit = {
            found = true
            callback.invoke(it)
        }

        suspend fun loadUrl(url: String?) {
            val raw = url?.trim().orEmpty()
            if (raw.isBlank()) return
            val fixed = httpsify(raw)
            if (!seen.add(fixed)) return

            if (fixed.contains("filedon.co/embed/", true)) {
                val page = runCatching { app.get(fixed, referer = data).document }.getOrNull() ?: return
                val json = page.selectFirst("#app")?.attr("data-page") ?: return
                val parsed = tryParseJson<FiledonPage>(json) ?: return
                
                val directUrl = parsed.props?.url
                if (!directUrl.isNullOrBlank() && seen.add(directUrl)) {
                    linkCb(newExtractorLink(
                        "$name Filedon", "$name Filedon", directUrl, INFER_TYPE 
                    ) {
                        this.quality = parsed.props.files?.name?.let { getQualityFromName(it) } ?: Qualities.Unknown.value
                        this.headers = mapOf("Referer" to data)
                    })
                    return
                }
            }
            runCatching { loadExtractor(fixed, data, subtitleCb, linkCb) }
        }

        coroutineScope {
            document.select(".movieplay .pframe iframe, .player-embed iframe, .movieplay iframe, #embed_holder iframe")
                .map { frame -> async { loadUrl(frame.getIframeAttr()) } }
                .awaitAll()

            val options = document.select(".east_player_option[data-post][data-nume][data-type]")
            options.map { option ->
                async {
                    val post = option.attr("data-post").trim()
                    val nume = option.attr("data-nume").trim()
                    val type = option.attr("data-type").trim()
                    
                    if (post.isNotBlank() && nume.isNotBlank() && type.isNotBlank()) {
                        val body = runCatching {
                            app.post(
                                "$mainUrl/wp-admin/admin-ajax.php",
                                data = mapOf("action" to "player_ajax", "post" to post, "nume" to nume, "type" to type),
                                headers = mapOf("X-Requested-With" to "XMLHttpRequest", "Referer" to data)
                            ).text
                        }.getOrNull()

                        body?.let {
                            val ajaxDoc = Jsoup.parse(it)
                            ajaxDoc.select("iframe").forEach { frame -> loadUrl(frame.getIframeAttr()) }
                            ajaxDoc.select("video source[src]").forEach { source -> 
                                val src = source.attr("src")
                                val quality = source.attr("size")
                                val serverName = "$name " + (option.text().trim().ifBlank { "Server $nume" })
                                
                                if (src.isNotBlank() && seen.add(src)) {
                                    linkCb(newExtractorLink(
                                        serverName, serverName, src, INFER_TYPE
                                    ) {
                                        this.quality = getQualityFromName(quality)
                                        this.headers = mapOf("Referer" to data)
                                    })
                                }
                            }
                            ajaxDoc.select("a[href^=http]").forEach { a -> loadUrl(a.attr("href")) }
                        }
                    }
                }
            }.awaitAll()

            document.select(".download-eps a[href], #downloadb a[href], .boxdownload a[href], .dlbox a[href]")
                .map { a -> async { loadUrl(a.attr("href")) } }
                .awaitAll()
        }

        return found
    }

    private fun Element.getImageAttr(): String {
        return attr("abs:data-src").takeIf { it.isNotBlank() }
            ?: attr("abs:data-lazy-src").takeIf { it.isNotBlank() }
            ?: attr("abs:srcset").substringBefore(" ").takeIf { it.isNotBlank() }
            ?: attr("abs:src")
    }

    private fun Element.getIframeAttr(): String? {
        return attr("data-litespeed-src").takeIf { it.isNotBlank() }
            ?: attr("data-src").takeIf { it.isNotBlank() }
            ?: attr("src").takeIf { it.isNotBlank() }
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

    val url = if (type == TvType.AnimeMovie || type == TvType.Movie)
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
