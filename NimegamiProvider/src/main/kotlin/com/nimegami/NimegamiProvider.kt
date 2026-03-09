package com.nimegami

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addAniListId
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import kotlinx.coroutines.runBlocking
import org.jsoup.nodes.Element
import org.jsoup.select.Elements

class NimegamiProvider : MainAPI() {
    override var mainUrl = "https://nimegami.id"
    override var name = "Nimegami"
    override val hasMainPage = true
    override var lang = "id"
    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.OVA
    )

    companion object {
        fun getType(t: String): TvType {
            return when {
                t.contains("Tv", true) -> TvType.Anime
                t.contains("Movie", true) -> TvType.AnimeMovie
                t.contains("OVA", true) || t.contains("Special", true) -> TvType.OVA
                else -> TvType.Anime
            }
        }

        fun getStatus(t: String?): ShowStatus {
            return when {
                t?.contains("On-Going", true) == true -> ShowStatus.Ongoing
                else -> ShowStatus.Completed
            }
        }
    }

    override val mainPage = mainPageOf(
        ""                       to "Latest Anime",
        "anime-terbaru-sub-indo" to "Updated Anime",
        "/type/tv"               to "Anime",
        "/type/movie"            to "Movie",
        "/type/ona"              to "ONA",
        "/type/live-action"      to "Live Action",
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val isOngoing  = request.data == "anime-terbaru-sub-indo"
        val isHomepage = request.data.isEmpty()

        val url = when {
            isOngoing  -> "$mainUrl/${request.data}/"
            isHomepage -> if (page == 1) "$mainUrl/" else "$mainUrl/page/$page/"
            else       -> "$mainUrl${request.data}/page/$page"
        }

        val document = app.get(url).document

        val selector = if (isOngoing) {
            "div.wrapper-3-a article"
        } else {
            "div.post-article article, div.archive article"
        }

        val home = document.select(selector).mapNotNull {
            it.toSearchResult()
        }

        return newHomePageResponse(
            list = HomePageList(
                name = request.name,
                list = home,
                isHorizontalImages = !isOngoing
            ),
            hasNext = !isOngoing
        )
    }

    private fun Element.toSearchResult(): AnimeSearchResponse? {
        val href = fixUrl(this.selectFirst("a")!!.attr("href"))

        val title = (this.selectFirst("h2 a") ?: this.selectFirst("h3 a"))
            ?.text() ?: return null

        val posterUrl = (this.selectFirst("noscript img") ?: this.selectFirst("img"))
            ?.attr("src")

        val episode = this
            .selectFirst("ul li:contains(Episode), div.eps-archive, div.eps_ongo")
            ?.ownText()
            ?.filter { it.isDigit() }
            ?.toIntOrNull()

        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = posterUrl
            addSub(episode)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchResponse = mutableListOf<SearchResponse>()
        for (i in 1..2) {
            val res =
                app.get("$mainUrl/page/$i/?s=$query&post_type=post").document.select("div.archive article")
                    .mapNotNull {
                        it.toSearchResult()
                    }
            searchResponse.addAll(res)
        }
        return searchResponse
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val table = document.select("div#Info table tbody")
        val title = table.getContent("Judul :").text()
        val poster = document.selectFirst("div.coverthumbnail img")?.attr("src")
        val bgPoster = document.selectFirst("div.thumbnail-a img")?.attr("src")
        val tags = table.getContent("Kategori").select("a").map { it.text() }

        val year = table.getContent("Musim / Rilis").text().filter { it.isDigit() }.toIntOrNull()
        val status = getStatus(document.selectFirst("h1[itemprop=headline]")?.text())
        val type = getType(table.getContent("Type").text())
        val description = document.select("div#Sinopsis p").text().trim()
        val trailer = document.selectFirst("div#Trailer iframe")?.attr("src")

        val episodes = document.select("div.list_eps_stream li")
            .mapNotNull {
                val episode = Regex("Episode\\s?(\\d+)").find(it.text())?.groupValues?.getOrNull(1)
                    ?.toIntOrNull()
                val link = it.attr("data")
                newEpisode(url = link, initializer = { this.episode = episode }, fix = false)
            }

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
            backgroundPosterUrl = tracker?.cover ?: bgPoster
            this.year = year
            addEpisodes(DubStatus.Subbed, episodes)
            showStatus = status
            plot = description
            this.tags = tags
            this.recommendations = recommendations
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
        tryParseJson<ArrayList<Sources>>(base64Decode(data))?.forEach { sources ->
            sources.url?.amap { rawUrl ->
                val resolvedUrl = try {
                    app.get(
                        rawUrl,
                        referer = "$mainUrl/",
                        headers = mapOf("User-Agent" to "Mozilla/5.0")
                    ).url
                } catch (e: Exception) {
                    rawUrl
                }
                callback.invoke(
                    newExtractorLink(
                        name,
                        "$name (${sources.format ?: ""})",
                        resolvedUrl,
                        ExtractorLinkType.VIDEO
                    ) {
                        this.referer = "$mainUrl/"
                        this.quality = getQualityFromName(sources.format)
                    }
                )
            }
        }
        return true
    }

    private fun Elements.getContent(css: String): Elements {
        return this.select("tr:contains($css) td:last-child")
    }

    data class Sources(
        @JsonProperty("format") val format: String? = null,
        @JsonProperty("url") val url: ArrayList<String>? = arrayListOf(),
    )

}
