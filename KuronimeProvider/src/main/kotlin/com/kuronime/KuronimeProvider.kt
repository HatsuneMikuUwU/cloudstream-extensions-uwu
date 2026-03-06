package com.kuronime

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.ObjectMapper
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addAniListId
import com.lagradost.cloudstream3.LoadResponse.Companion.addKitsuId
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.extractors.helper.AesHelper
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.nicehttp.RequestBodyTypes
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.jsoup.nodes.Element
import java.net.URI
import java.util.ArrayList

class KuronimeProvider : MainAPI() {
    override var mainUrl = "https://kuronime.sbs"
    private var animekuUrl = "https://animeku.org"
    override var name = "Kuronime"
    override val hasQuickSearch = true
    override val hasMainPage = true
    override var lang = "id"
    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.OVA
    )

    companion object {
        const val KEY = "3&!Z0M,VIZ;dZW=="
        fun getType(t: String): TvType {
            return if (t.contains("OVA", true) || t.contains("Special", true)) TvType.OVA
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

   override val mainPage = mainPageOf(
        "$mainUrl/page/" to "New Episodes",
        "$mainUrl/ongoing-anime/page/" to "Ongoing Anime",
        "$mainUrl/popular-anime/page/" to "Most Popular",
        "$mainUrl/movies/page/" to "Movies",
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = "${request.data}$page/" 
        val req = app.get(url)
        
        val currentMainUrl = getBaseUrl(req.url)
        val document = req.document
        
        val home = document.select(".listupd article, .listo .bs").map {
            it.toSearchResult(currentMainUrl)
        }
        
        return newHomePageResponse(
            HomePageList(
                name = request.name,
                list = home
            ),
            hasNext = home.isNotEmpty()
        )
    }


    private fun getProperAnimeLink(uri: String): String {
        if (uri.contains("/anime/")) return uri
        
        val slug = uri.trimEnd('/').substringAfterLast("/")
        val title = when {
            slug.contains("-episode") && !slug.contains("-movie") -> 
                Regex("nonton-(.+)-episode").find(slug)?.groupValues?.get(1) ?: slug
            slug.contains("-movie") -> 
                Regex("nonton-(.+)-movie").find(slug)?.groupValues?.get(1) ?: slug
            else -> slug
        }

        return "$mainUrl/anime/$title"
    }

    private fun Element.getImageAttr(): String? {
        return when {
            this.hasAttr("data-src") -> this.attr("abs:data-src")
            this.hasAttr("data-lazy-src") -> this.attr("abs:data-lazy-src")
            this.hasAttr("srcset") -> this.attr("abs:srcset").substringBefore(" ")
            else -> this.attr("abs:src")
        }
    }

    private fun Element.toSearchResult(): AnimeSearchResponse {
        val href = getProperAnimeLink(fixUrlNull(this.selectFirst("a")?.attr("href")).toString())
        val title = this.selectFirst("h2, .bsuxtt, .tt > h4, .entry-title")?.text()?.trim() ?: "Unknown"
        
        val img = this.selectFirst("img[itemprop=image]") ?: this.select("img").lastOrNull()
        val posterUrl = fixUrlNull(img?.getImageAttr())
        
        val epNum = this.select(".ep").text().replace(Regex("\\D"), "").trim().toIntOrNull()
        val tvType = getType(this.selectFirst(".bt > span, .bt > .type")?.text().toString())
        
        return newAnimeSearchResponse(title, href, tvType) {
            this.posterUrl = posterUrl
            addSub(epNum)
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse>? = search(query)

    override suspend fun search(query: String): List<SearchResponse>? {
        mainUrl = app.get(mainUrl).url
        return app.post(
            "$mainUrl/wp-admin/admin-ajax.php", data = mapOf(
                "action" to "ajaxy_sf",
                "sf_value" to query,
                "search" to "false"
            ), headers = mapOf("X-Requested-With" to "XMLHttpRequest")
        ).parsedSafe<Search>()?.anime?.firstOrNull()?.all?.mapNotNull {
            newAnimeSearchResponse(
                it.postTitle ?: "",
                it.postLink ?: return@mapNotNull null,
                TvType.Anime
            ) {
                this.posterUrl = it.postImage
                addSub(it.postLatest?.toIntOrNull())
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val title = document.selectFirst(".entry-title")?.text().toString().trim()
        val poster = document.selectFirst("div.l[itemprop=image] > img, .l > img")?.getImageAttr()
        val tags = document.select(".infodetail > ul > li:nth-child(2) > a").map { it.text() }
        val typeString = document.selectFirst(".infodetail > ul > li:nth-child(7)")?.ownText()?.removePrefix(":")?.trim() ?: "tv"
        val type = getType(typeString.lowercase())

        val trailer = document.selectFirst("div.tply iframe")?.attr("data-src")
        val year = Regex("\\d, (\\d*)").find(
            document.select(".infodetail > ul > li:nth-child(5)").text()
        )?.groupValues?.get(1)?.toIntOrNull()
        
        val statusElement = document.selectFirst(".infodetail > ul > li:nth-child(3)")
        val statusText = statusElement?.ownText()?.replace(Regex("\\W"), "") ?: ""
        val status = getStatus(statusText)
        
        val description = document.select("span.const > p").text()
        
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

        val episodes = document.select("div.bixbox.bxcl > ul > li").amap { element ->
            val link = element.selectFirst("a")?.attr("href") ?: return@amap null
            val name = element.selectFirst("a")?.text() ?: return@amap null
            var episodeNum = Regex("(\\d+[.,]?\\d*)").find(name)?.groupValues?.getOrNull(0)?.toIntOrNull()
            
            if (type == TvType.AnimeMovie && episodeNum == null) {
                episodeNum = 1
            }

            val episodeKey = episodeNum?.toString()
            val metaEp = if (episodeKey != null) animeMetaData?.episodes?.get(episodeKey) else null

            val epOverview = metaEp?.overview
            val finalOverview = if (!epOverview.isNullOrBlank()) {
                epOverview
            } else {
                "Synopsis not yet available."
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
            this.plot = finalPlot
            addTrailer(trailer)
            this.tags = tags
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
        
        val scriptData = document.select("script").map { it.data() }
            .firstOrNull { it.contains("_0xa100d42aa") }
            ?: throw ErrorLoadingException("No id found in script tags")
            
        val id = scriptData.substringAfter("_0xa100d42aa = \"").substringBefore("\";")

        val servers = app.post(
            "$animekuUrl/api/v9/sources", requestBody = """{"id":"$id"}""".toRequestBody(
                RequestBodyTypes.JSON.toMediaTypeOrNull()
            ), referer = "$mainUrl/"
        ).parsedSafe<Servers>()

        runAllAsync(
            {
                val decrypt = AesHelper.cryptoAESHandler(
                    base64Decode(servers?.src ?: return@runAllAsync),
                    KEY.toByteArray(),
                    false,
                    "AES/CBC/NoPadding"
                )
                val source =
                    tryParseJson<Sources>(decrypt?.toJsonFormat())?.src?.replace("\\", "")
                M3u8Helper.generateM3u8(
                    this.name,
                    source ?: return@runAllAsync,
                    "$animekuUrl/",
                    headers = mapOf("Origin" to animekuUrl)
                ).forEach(callback)
            },
            {
                val decrypt = AesHelper.cryptoAESHandler(
                    base64Decode(servers?.mirror ?: return@runAllAsync),
                    KEY.toByteArray(),
                    false,
                    "AES/CBC/NoPadding"
                )
                tryParseJson<Mirrors>(decrypt)?.embed?.map { embed ->
                    embed.value.forEach { entry ->
                        loadFixedExtractor(
                            entry.value,
                            embed.key.removePrefix("v"),
                            "$mainUrl/",
                            subtitleCallback,
                            callback
                        )
                    }
                }
            }
        )

        return true
    }

    private fun String.toJsonFormat(): String {
        return if (this.startsWith("\"")) this.substringAfter("\"").substringBeforeLast("\"")
            .replace("\\\"", "\"") else this
    }

    private suspend fun loadFixedExtractor(
        url: String? = null,
        quality: String? = null,
        referer: String? = null,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        loadExtractor(url ?: return, referer, subtitleCallback) { link ->
            runBlocking {
                callback.invoke(
                    newExtractorLink(
                        link.name,
                        link.name,
                        link.url,
                        link.type,
                    ) {
                        this.referer = link.referer
                        this.headers = link.headers
                        this.extractorData = link.extractorData
                        this.quality = getQualityFromName(quality)
                    }
                )
            }
        }
    }

    private fun getBaseUrl(url: String): String {
        return URI(url).let {
            "${it.scheme}://${it.host}"
        }
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

    data class Mirrors(
        @JsonProperty("embed") val embed: Map<String, Map<String, String>> = emptyMap(),
    )

    data class Sources(
        @JsonProperty("src") var src: String? = null,
    )

    data class Servers(
        @JsonProperty("src") var src: String? = null,
        @JsonProperty("mirror") var mirror: String? = null,
    )

    data class All(
        @JsonProperty("post_image") var postImage: String? = null,
        @JsonProperty("post_image_html") var postImageHtml: String? = null,
        @JsonProperty("ID") var ID: Int? = null,
        @JsonProperty("post_title") var postTitle: String? = null,
        @JsonProperty("post_genres") var postGenres: String? = null,
        @JsonProperty("post_type") var postType: String? = null,
        @JsonProperty("post_latest") var postLatest: String? = null,
        @JsonProperty("post_sub") var postSub: String? = null,
        @JsonProperty("post_link") var postLink: String? = null
    )

    data class Anime(
        @JsonProperty("all") var all: ArrayList<All> = arrayListOf(),
    )

    data class Search(
        @JsonProperty("anime") var anime: ArrayList<Anime> = arrayListOf()
    )
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
