package com.hexated

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addAniListId
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
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
import org.jsoup.nodes.Element
import java.net.URI

class KuronimeProvider : MainAPI() {
    override var mainUrl = "https://kuronime.moe"
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

        fun String.cleanTitle(): String {
            return this.replace(Regex("(?i)(Subtitle|Sub) Indonesia"), "")
                .replace(Regex("(?i)Episode\\s+\\d+"), "")
                .replace(Regex("\\s-\\s$"), "")
                .trim()
        }
    }

    override val mainPage = mainPageOf(
        "$mainUrl/page/" to "New Episodes",
        "$mainUrl/ongoing-anime/page/" to "Ongoing",
        "$mainUrl/popular-anime/page/" to "Popular",
        "$mainUrl/movies/page/" to "Movies",
        "$mainUrl/genres/donghua/page/" to "Donghua",
        "$mainUrl/live-action/page/" to "Live Action",
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val req = app.get(request.data + page)
        val home = req.document.select("article").mapNotNull {
            it.toSearchResult()
        }
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): AnimeSearchResponse? {
        val titleElement = this.selectFirst(".bsuxtt, .tt > h4, .tt > h2, .title, .entry-title")
        val title = (titleElement?.text() ?: this.selectFirst("img")?.attr("alt"))?.cleanTitle() ?: return null
        
        val href = fixUrl(this.selectFirst("a")?.attr("href") ?: return null)
        val posterUrl = fixUrlNull(this.selectFirst("img")?.attr("src"))
        val epNum = this.select(".ep").text().replace(Regex("\\D"), "").toIntOrNull()
        val tvType = getType(this.select(".bt > span").text())

        return newAnimeSearchResponse(title, href, tvType) {
            this.posterUrl = posterUrl
            addSub(epNum)
        }
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        return app.post(
            "$mainUrl/wp-admin/admin-ajax.php", data = mapOf(
                "action" to "ajaxy_sf",
                "sf_value" to query,
                "search" to "false"
            ), headers = mapOf("X-Requested-With" to "XMLHttpRequest")
        ).parsedSafe<Search>()?.anime?.firstOrNull()?.all?.mapNotNull {
            newAnimeSearchResponse(
                it.postTitle?.cleanTitle() ?: "",
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

        val title = document.selectFirst(".entry-title")?.text()?.cleanTitle() 
            ?: document.selectFirst("h1")?.text()?.cleanTitle() 
            ?: "Unknown Title"

        val poster = document.selectFirst("div.l[itemprop=image] > img, .thumb > img")?.attr("src")
        val tags = document.select(".infodetail ul li:contains(Genre) a, .gencontent a").map { it.text() }
        
        val typeStr = document.selectFirst(".infodetail ul li:contains(Type)")?.ownText()?.replace(":", "")?.trim()
        val type = getType(typeStr ?: "tv")

        val year = Regex("(\\d{4})").find(
            document.select(".infodetail ul li:contains(Released)").text()
        )?.groupValues?.get(1)?.toIntOrNull()

        val status = getStatus(
            document.selectFirst(".infodetail ul li:contains(Status)")?.ownText()?.replace(":", "")?.trim() ?: ""
        )
        
        val description = document.select(".entry-content[itemprop=description] p, .const p").text()
        val trailer = document.selectFirst("iframe.youtube-player")?.attr("src")

        val episodes = document.select("div.bixbox.bxcl ul li").mapNotNull {
            val a = it.selectFirst("a") ?: return@mapNotNull null
            val name = a.text()
            val link = fixUrl(a.attr("href"))
            val episode = Regex("Episode\\s?(\\d+)").find(name)?.groupValues?.get(1)?.toIntOrNull()
            
            newEpisode(link) { 
                this.episode = episode 
                this.name = name
            }
        }.reversed()

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
            addTrailer(trailer)
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
        val document = app.get(data).document
        val id = document.selectFirst("script:containsData(_0xa100d42aa)")?.data()
            ?.substringAfter("_0xa100d42aa = \"")?.substringBefore("\";")
            ?: return false

        val servers = app.post(
            "$animekuUrl/api/v9/sources", 
            requestBody = """{"id":"$id"}""".toRequestBody(RequestBodyTypes.JSON.toMediaTypeOrNull()),
            referer = "$mainUrl/"
        ).parsedSafe<Servers>()

        runAllAsync(
            {
                servers?.src?.let { src ->
                    val decrypt = AesHelper.cryptoAESHandler(base64Decode(src), KEY.toByteArray(), false, "AES/CBC/NoPadding")
                    val sourceUrl = tryParseJson<Sources>(decrypt?.toJsonFormat())?.src?.replace("\\", "")
                    if (sourceUrl != null) {
                        M3u8Helper.generateM3u8(this.name, sourceUrl, animekuUrl, mapOf("Origin" to animekuUrl)).forEach(callback)
                    }
                }
            },
            {
                servers?.mirror?.let { mirror ->
                    val decrypt = AesHelper.cryptoAESHandler(base64Decode(mirror), KEY.toByteArray(), false, "AES/CBC/NoPadding")
                    tryParseJson<Mirrors>(decrypt)?.embed?.forEach { (quality, links) ->
                        links.forEach { (name, url) ->
                            loadFixedExtractor(url, quality.removePrefix("v"), "$mainUrl/", subtitleCallback, callback)
                        }
                    }
                }
            }
        )
        return true
    }

    private fun String.toJsonFormat() = if (this.startsWith("\"")) this.substring(1, this.length - 1).replace("\\\"", "\"") else this

    private suspend fun loadFixedExtractor(url: String, quality: String, referer: String, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        loadExtractor(url, referer, subtitleCallback) { link ->
            runBlocking {
                callback.invoke(newExtractorLink(link.name, link.name, link.url, link.type) {
                    this.referer = link.referer
                    this.headers = link.headers
                    this.quality = getQualityFromName(quality)
                })
            }
        }
    }

    // Data Classes
    data class Mirrors(@JsonProperty("embed") val embed: Map<String, Map<String, String>> = emptyMap())
    data class Sources(@JsonProperty("src") var src: String? = null)
    data class Servers(@JsonProperty("src") var src: String? = null, @JsonProperty("mirror") var mirror: String? = null)
    data class Search(@JsonProperty("anime") var anime: List<Anime>? = null)
    data class Anime(@JsonProperty("all") var all: List<All>? = null)
    data class All(
        @JsonProperty("post_image") var postImage: String? = null,
        @JsonProperty("post_title") var postTitle: String? = null,
        @JsonProperty("post_latest") var postLatest: String? = null,
        @JsonProperty("post_link") var postLink: String? = null
    )
}
