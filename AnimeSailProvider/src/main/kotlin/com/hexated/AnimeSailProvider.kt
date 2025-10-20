package com.hexated

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addAniListId
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId
import com.lagradost.cloudstream3.mvvm.safeApiCall
import com.lagradost.cloudstream3.utils.*
import com.lagradost.nicehttp.NiceResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import kotlin.text.Regex

class AnimeSailProvider : MainAPI() {
    override var mainUrl = "https://154.26.137.28"
    override var name = "AnimeSail"
    override val hasMainPage = true
    override var lang = "id"
    override val hasDownloadSupport = true

    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    companion object {
        fun getType(t: String): TvType {
            return if (t.contains("OVA", true) || t.contains("Special")) TvType.OVA
            else if (t.contains("Movie", true)) TvType.AnimeMovie else TvType.Anime
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
            headers =
                mapOf(
                    "Accept" to
                            "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8"
                ),
            cookies = mapOf("_as_ipin_ct" to "ID"),
            referer = ref
        )
    }

    override val mainPage =
        mainPageOf(
            "$mainUrl/rilisan-anime-terbaru/page/" to "Episode Anime Terbaru",
            "$mainUrl/rilisan-donghua-terbaru/page/" to "Episode Donghua Terbaru",
            "$mainUrl/movie-terbaru/page/" to "Movie Terbaru",
        )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = request(request.data + page).document
        val home = document.select("article").map { it.toSearchResult() }
        return newHomePageResponse(request.name, home)
    }

    private fun getProperAnimeLink(uri: String): String {
        return if (uri.contains("/anime/")) {
            uri
        } else {
            var title = uri.substringAfter("$mainUrl/")
            title =
                when {
                    (title.contains("-episode")) && !(title.contains("-movie")) ->
                        title.substringBefore("-episode")

                    (title.contains("-movie")) -> title.substringBefore("-movie")
                    else -> title
                }

            "$mainUrl/anime/$title"
        }
    }

    private fun Element.toSearchResult(): AnimeSearchResponse {
        val href = getProperAnimeLink(fixUrlNull(this.selectFirst("a")?.attr("href")).toString())
        val title = this.select(".tt > h2").text().trim()
        val posterUrl = fixUrl(this.selectFirst("div.limit img")?.attr("src") ?: "")
        val epNum =
            this.selectFirst(".tt > h2")?.text()?.let {
                Regex("Episode\\s?(\\d+)").find(it)?.groupValues?.getOrNull(1)?.toIntOrNull()
            }
        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = posterUrl
            addSub(epNum)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val link = "$mainUrl/?s=$query"
        val document = request(link).document

        return document.select("div.listupd article").map { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = request(url).document

        val title =
            document.selectFirst("h1.entry-title")
                ?.text()
                .toString()
                .replace("Subtitle Indonesia", "")
                .trim()
        val poster = document.selectFirst("div.entry-content > img")?.attr("src")
        val type = getType(document.select("tbody th:contains(Tipe)").next().text().lowercase())
        val year = document.select("tbody th:contains(Dirilis)").next().text().trim().toIntOrNull()

        // --- Corrected Episode Mapping ---
        val episodes =
            document.select("ul.daftar > li") // Assuming this is your episode list selector
                .mapNotNull { episodeElement -> // Use mapNotNull to safely skip if data is missing
                    val anchor = episodeElement.selectFirst("a") ?: return@mapNotNull null
                    val episodeLink = fixUrl(anchor.attr("href"))
                    val episodeName = anchor.text()

                    val episodeNumber =
                        // Renamed from 'episode' to avoid confusion with property name
                        Regex("Episode\\s?(\\d+)")
                            .find(episodeName)
                            ?.groupValues
                            ?.getOrNull(1) // IMPORTANT: Group 1 for the number
                            ?.toIntOrNull()

                    newEpisode(episodeLink) { // 'episodeLink' is the 'data' argument
                        this.name = episodeName       // Set the 'name' property
                        this.episode = episodeNumber  // Set the 'episode' property (the number)
                    }
                }
                .reversed()
        // --- End Corrected Episode Mapping ---

        val tracker = APIHolder.getTracker(listOf(title), TrackerType.getTypes(type), year, true)

        return newAnimeLoadResponse(title, url, type) {
            posterUrl = tracker?.image ?: poster
            backgroundPosterUrl = tracker?.cover
            this.year = year
            addEpisodes(DubStatus.Subbed, episodes)
            showStatus =
                getStatus(document.select("tbody th:contains(Status)").next().text().trim())
            plot = document.selectFirst("div.entry-content > p")?.text()
            this.tags =
                document.select("tbody th:contains(Genre)").next().select("a").map { it.text() }
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

        val document = request(data).document

        // Replace blocking apmap with coroutine-friendly concurrent processing
        coroutineScope {
            val jobs = document.select(".mobius > .mirror > option").map { element ->
                async {
                    safeApiCall {
                        val iframe =
                            fixUrl(
                                Jsoup.parse(base64Decode(element.attr("data-em")))
                                    .select("iframe")
                                    .attr("src")
                            )
                        val quality = getIndexQuality(element.text())
                        when {
                            iframe.startsWith("$mainUrl/utils/player/arch/") ||
                                    iframe.startsWith("$mainUrl/utils/player/race/") ->
                                request(iframe, ref = data).document.select("source").attr("src")
                                    .let { link ->
                                        val source =
                                            when {
                                                iframe.contains("/arch/") -> "Arch"
                                                iframe.contains("/race/") -> "Race"
                                                else -> this@AnimeSailProvider.name
                                            }
                                        callback.invoke(
                                            newExtractorLink(
                                                source = source,
                                                name = source,
                                                url = link,
                                                this.referer = mainUrl,
                                                this.quality = getIndexQuality(element.text()),
                                                type = ExtractorLinkType.VIDEO
                                            )
                                        )
                                    }
                            iframe.startsWith("https://aghanim.xyz/tools/redirect/") -> {
                                val link =
                                    "https://rasa-cintaku-semakin-berantai.xyz/v/${
                                        iframe.substringAfter("id=").substringBefore("&token")
                                    }"
                                loadFixedExtractor(link, quality, mainUrl, subtitleCallback, callback)
                            }

                            iframe.startsWith("$mainUrl/utils/player/framezilla/") ||
                                    iframe.startsWith("https://uservideo.xyz") -> {
                                request(iframe, ref = data).document.select("iframe").attr("src").let { link
                                    ->
                                    loadFixedExtractor(
                                        fixUrl(link),
                                        quality,
                                        mainUrl,
                                        subtitleCallback,
                                        callback
                                    )
                                }
                            }

                            else -> {
                                loadFixedExtractor(iframe, quality, mainUrl, subtitleCallback, callback)
                            }
                        }
                    }
                }
            }
            jobs.awaitAll()
        }

        return true
    }

    private fun getIndexQuality(str: String?): Int {
        return Regex("(\\d{3,4})[pP]").find(str ?: "")?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: Qualities.Unknown.value
    }

    private suspend fun loadFixedExtractor(
        url: String,
        quality: Int,
        referer: String? = null,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        loadExtractor(url, referer, subtitleCallback) { link ->
            CoroutineScope(Dispatchers.IO).launch {
                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = name,
                        url = link.url,
                        this.referer = link.referer,
                        this.quality = link.quality,
                        type = link.type,
                    )
                )
            }
        }

        fun getIndexQuality(str: String): Int {
            return Regex("(\\d{3,4})[pP]").find(str)?.groupValues?.getOrNull(1)?.toIntOrNull()
                ?: Qualities.Unknown.value
        }
    }
}
