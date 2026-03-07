package com.animeindo

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addAniListId
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.httpsify
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element

class AnimeIndoProvider : MainAPI() {
    override var mainUrl = "https://animeindo.skin"
    override var name = "AnimeIndo"
    override val hasMainPage = true
    override var lang = "id"
    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.OVA
    )

    override val mainPage = mainPageOf(
        "browse" to "Browse",
        "trending" to "Trending",
        "movies" to "Movies",
        "tv-shows" to "TV Shows",
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = if (page == 1) "$mainUrl/${request.data}" else "$mainUrl/${request.data}?page=$page"
        val document = app.get(url).document
        
        val home = document.select("div.grid > div.relative.group:not(.animate-pulse)").mapNotNull {
            it.toSearchResult()
        }
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val aTag = this.selectFirst("a") ?: return null
        val href = fixUrl(aTag.attr("href"))
        
        val title = this.selectFirst("h3")?.text()?.trim() ?: aTag.selectFirst("img")?.attr("alt") ?: ""
        
        val posterUrl = aTag.selectFirst("img")?.let {
            it.attr("data-src").ifEmpty { it.attr("src") }
        }
        
        val typeBadge = this.select("div.pt-4 span.bg-gray-800").text().trim()
        val type = if (typeBadge.contains("Movie", true)) TvType.AnimeMovie else TvType.Anime

        return newAnimeSearchResponse(title, href, type) {
            this.posterUrl = fixUrlNull(posterUrl)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/browse?search=$query").document
        
        return document.select("div.grid > div.relative.group:not(.animate-pulse)").mapNotNull {
            it.toSearchResult()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val title = document.select("h1.text-3xl, h3.text-xl").text().trim()
        if (title.isEmpty()) return null

        val poster = document.selectFirst("div.max-w-\\[16rem\\] img, div.max-w-\\[6rem\\] img")?.let {
            it.attr("data-src").ifEmpty { it.attr("src") }
        }

        val description = document.selectFirst("div.flex-1 > p.text-gray-400.mt-3")?.text()?.trim()

        val yearStr = document.selectFirst("div.flex-1 > div.text-gray-400 > span:first-child")?.text()?.trim()
        val year = yearStr?.toIntOrNull()

        val genres = document.select("div.grid.sm\\:flex a[href*=/genre/]").map { it.text().trim() }
        val tags = document.select("div.flex-wrap.gap-2 a[href*=/tag/]").map { it.text().trim() }
        val allTags = (genres + tags).distinct()

        val type = if (url.contains("/movie/", true)) TvType.AnimeMovie else TvType.Anime

        val episodes = document.select("div.grid-cols-2 > div.relative.group").mapNotNull { epElement ->
            val aTag = epElement.selectFirst("a") ?: return@mapNotNull null
            val link = fixUrl(aTag.attr("href"))
            
            val epName = epElement.selectFirst("h3")?.text()?.trim()
            val epNumText = epElement.selectFirst("div.text-xs > span:nth-child(2)")?.text()
            val episodeNumber = epNumText?.replace(Regex("\\D"), "")?.toIntOrNull()

            val epPoster = aTag.selectFirst("img")?.let {
                it.attr("data-src").ifEmpty { it.attr("src") }
            }

            newEpisode(link) {
                this.name = epName
                this.episode = episodeNumber
                this.posterUrl = fixUrlNull(epPoster)
            }
        }.reversed()

        val recommendations = document.select("div.grid-cols-2.xl\\:grid-cols-6 > div.relative.group:not(.animate-pulse)").mapNotNull {
            it.toSearchResult()
        }

        val tracker = APIHolder.getTracker(listOf(title), TrackerType.getTypes(type), year, true)

        return newAnimeLoadResponse(title, url, type) {
            engName = title
            posterUrl = tracker?.image ?: poster
            backgroundPosterUrl = tracker?.cover ?: poster
            this.year = year
            addEpisodes(DubStatus.Subbed, episodes)
            showStatus = ShowStatus.Completed 
            plot = description
            this.tags = allTags
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

        val document = app.get(data).document

        val snapshotDiv = document.selectFirst("div[wire:snapshot][wire:id]")
        if (snapshotDiv != null) {
            val wireSnapshotAttr = snapshotDiv.attr("wire:snapshot")
            
            val regex = Regex(""""link":"(.*?)"""")
            val matches = regex.findAll(wireSnapshotAttr).toList()

            matches.amap { matchResult ->
                try {
                    val embedUrl = matchResult.groupValues[1].replace("\\/", "/")
                    
                    val response = app.get(embedUrl, referer = data)
                    val embedDoc = response.document

                    val iframeSrc = embedDoc.selectFirst("iframe")?.attr("src")
                    if (!iframeSrc.isNullOrBlank()) {
                        loadExtractor(httpsify(iframeSrc), embedUrl, subtitleCallback, callback)
                    }

                    val sourceSrc = embedDoc.selectFirst("video source, video")?.attr("src")
                    if (!sourceSrc.isNullOrBlank()) {
                        callback.invoke(
                            ExtractorLink(
                                source = name,
                                name = name,
                                url = httpsify(sourceSrc),
                                referer = embedUrl,
                                quality = Qualities.Unknown.value,
                                isM3u8 = sourceSrc.contains(".m3u8", true)
                            )
                        )
                    }

                    val finalUrl = response.url
                    if (finalUrl != embedUrl && !finalUrl.contains("animeindo")) {
                        loadExtractor(finalUrl, data, subtitleCallback, callback)
                    }

                } catch (e: Exception) {
                }
            }
        }

        return true
    }
}
