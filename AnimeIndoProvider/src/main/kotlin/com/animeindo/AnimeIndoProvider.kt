package com.animeindo

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addAniListId
import com.lagradost.cloudstream3.LoadResponse.Companion.addKitsuId
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class AnimeIndoProvider : MainAPI() {
    override var mainUrl = "https://anime-indo.lol"
    override var name = "AnimeIndo"
    override val hasMainPage = true
    override var lang = "id"
    override val hasDownloadSupport = true

    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.OVA
    )

    companion object {
        fun getType(t: String?): TvType = when {
            t == null -> TvType.Anime
            t.contains("Movie", true) -> TvType.AnimeMovie
            t.contains("OVA", true) || t.contains("Special", true) -> TvType.OVA
            else -> TvType.Anime
        }
    }

    private suspend fun request(url: String, ref: String? = null) = app.get(
        url,
        headers = mapOf(
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
            "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Mobile Safari/537.36"
        ),
        referer = ref ?: "$mainUrl/"
    )

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Latest Updates",
        "$mainUrl/movie/" to "Movies"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = when {
            request.data.contains("/movie/") -> {
                if (page == 1) request.data else "$mainUrl/movie/page/$page/"
            }
            else -> {
                if (page == 1) "$mainUrl/" else "$mainUrl/page/$page/"
            }
        }

        val document = request(url).document

        val items = if (request.name == "Movies") {
            document.select("table.otable").mapNotNull { it.toSearchResult() }
        } else {
            document.select("div.list-anime").mapNotNull { it.toLatestResult() }
        }

        val home = mutableListOf(
            HomePageList(request.name, items, isHorizontalImages = false)
        )

        if (page == 1 && request.name == "Latest Updates") {
            val popular = document.select("div.nganan table.ztable").mapNotNull { it.toPopularResult() }
            if (popular.isNotEmpty()) {
                home.add(HomePageList("Popular", popular, isHorizontalImages = false))
            }
        }

        return newHomePageResponse(home, hasNext = items.isNotEmpty())
    }

    private fun Element.toLatestResult(): SearchResponse? {
        val a = selectFirst("a") ?: return null
        val href = fixUrl(a.attr("href"))
        // Episode links look like /slug-episode-12/ → convert to anime page
        val animeHref = href
            .replace(Regex("-episode-\\d+/?$"), "/")
            .let {
                if (it.contains("/anime/")) it
                else {
                    val slug = it.removePrefix(mainUrl).trim('/')
                        .replace(Regex("-episode-\\d+$"), "")
                        .removeSuffix("/")
                    "$mainUrl/anime/$slug/"
                }
            }

        val title = selectFirst("p")?.text()?.trim()
            ?: a.selectFirst("img")?.attr("alt")?.trim()
            ?: return null

        val poster = selectFirst("img")?.let { img ->
            img.attr("data-original").ifBlank { img.attr("src") }
        }?.let { fixUrlNull(it) }

        val epText = selectFirst("span.eps")?.text()?.trim()
        val isMovie = title.contains("Movie", true) || href.contains("/movie/", true)

        return newAnimeSearchResponse(title, animeHref, if (isMovie) TvType.AnimeMovie else TvType.Anime) {
            this.posterUrl = poster
            addDubStatus(dubExist = false, subExist = true)
        }
    }

    private fun Element.toPopularResult(): SearchResponse? {
        val a = selectFirst("td.zvithumb a, td.zvidesc a") ?: return null
        val href = fixUrl(a.attr("href"))
        val title = selectFirst("td.zvidesc a")?.text()?.trim()
            ?: a.selectFirst("img")?.attr("alt")?.trim()
            ?: return null
        val poster = selectFirst("td.zvithumb img")?.attr("src")?.let { fixUrlNull(it) }
        val genres = selectFirst("td.zvidesc")?.ownText()?.trim()

        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = poster
            addDubStatus(dubExist = false, subExist = true)
        }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val a = selectFirst("td.vithumb a, td.videsc a") ?: return null
        val href = fixUrl(a.attr("href"))
        val title = selectFirst("td.videsc a")?.text()?.trim()
            ?: a.selectFirst("img")?.attr("alt")?.trim()
            ?: return null
        val poster = selectFirst("td.vithumb img")?.attr("src")?.let { fixUrlNull(it) }

        val labels = select("span.label").map { it.text().trim() }
        val type = labels.firstOrNull { it.contains("Movie", true) || it.contains("TV", true) || it.contains("OVA", true) }
        val year = labels.firstOrNull { it.matches(Regex("\\d{4}")) }?.toIntOrNull()

        return newAnimeSearchResponse(title, href, getType(type)) {
            this.posterUrl = poster
            this.year = year
            addDubStatus(dubExist = false, subExist = true)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = request("$mainUrl/search.php?q=${query.replace(" ", "+")}").document
        return document.select("table.otable").mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = request(url).document

        val title = document.selectFirst("h1.title")?.text()?.trim()
            ?: document.selectFirst(".detail h2")?.text()?.trim()
            ?: return null

        val poster = document.selectFirst(".detail img")?.attr("src")?.let { fixUrlNull(it) }
        val sitePlot = document.selectFirst(".detail p")?.text()?.trim()
        val genres = document.select(".detail li a").map { it.text().trim() }.filter { it.isNotBlank() }

        val isMovie = genres.any { it.contains("Movie", true) } ||
                title.contains("Movie", true) ||
                url.contains("/movie/", true)

        val type = when {
            isMovie -> TvType.AnimeMovie
            title.contains("OVA", true) || title.contains("Special", true) -> TvType.OVA
            else -> TvType.Anime
        }

        val tracker = APIHolder.getTracker(listOf(title), TrackerType.getTypes(type), null, true)
        val ids = resolveAnimeIds(listOf(title), type, null, tracker?.malId, tracker?.aniId?.toIntOrNull())
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
            } catch (_: Exception) {
            }
        }

        val logoUrl = fetchTmdbLogoUrl(
            tmdbAPI = "https://api.themoviedb.org/3",
            apiKey = "98ae14df2b8d8f8f8136499daf79f0e0",
            type = type,
            tmdbId = tmdbid,
            appLangCode = "en"
        )

        val backgroundposter = animeMetaData?.images?.find { it.coverType == "Fanart" }?.url
            ?: tracker?.cover

        val rawEpisodes = document.select("div.ep a").mapNotNull { a ->
            val epHref = fixUrl(a.attr("href"))
            val epNum = a.text().trim().toIntOrNull()
                ?: Regex("episode-(\\d+)", RegexOption.IGNORE_CASE).find(epHref)?.groupValues?.getOrNull(1)?.toIntOrNull()
            Triple(epHref, epNum, a.text().trim())
        }.sortedBy { it.second ?: Int.MAX_VALUE }

        val episodes = rawEpisodes.map { (epHref, epNum, epLabel) ->
            var episodeNum = epNum
            if (type == TvType.AnimeMovie && episodeNum == null) {
                episodeNum = 1
            }
            val episodeKey = episodeNum?.toString()
            val metaEp = if (episodeKey != null) animeMetaData?.episodes?.get(episodeKey) else null
            val epOverview = metaEp?.overview?.takeIf { it.isNotBlank() }

            newEpisode(epHref) {
                this.name = if (type == TvType.AnimeMovie) {
                    animeMetaData?.titles?.get("en") ?: animeMetaData?.titles?.get("ja") ?: title
                } else {
                    metaEp?.title?.get("en") ?: metaEp?.title?.get("ja") ?: (
                        if (episodeNum != null) "Episode $episodeNum" else epLabel
                    )
                }
                this.episode = episodeNum
                this.score = Score.from10(metaEp?.rating)
                this.posterUrl = metaEp?.image?.takeIf { it.isNotBlank() }
                    ?: animeMetaData?.images?.firstOrNull()?.url
                    ?: backgroundposter ?: tracker?.image ?: poster
                this.description = epOverview
                this.addDate(metaEp?.airDateUtc)
                this.runTime = metaEp?.runtime
            }
        }

        val apiDescription = animeMetaData?.description?.replace(Regex("<.*?>"), "")
        val rawPlot = apiDescription?.takeIf { it.isNotBlank() }
            ?: animeMetaData?.episodes?.get("1")?.overview?.takeIf { it.isNotBlank() }
            ?: fetchAniListPlot(malId, aniId)
        val finalPlot = rawPlot?.takeIf { it.isNotBlank() } ?: sitePlot

        return if (type == TvType.AnimeMovie && episodes.size <= 1) {
            val movieData = episodes.firstOrNull()?.data ?: url
            newMovieLoadResponse(title, url, type, movieData) {
                this.posterUrl = tracker?.image ?: poster
                this.backgroundPosterUrl = backgroundposter
                try {
                    this.logoUrl = logoUrl
                } catch (_: Throwable) {
                }
                this.plot = finalPlot
                this.tags = genres
                this.score = Score.from10(animeMetaData?.episodes?.get("1")?.rating)
                addMalId(malId)
                addAniListId(aniId)
                try {
                    addKitsuId(kitsuid)
                } catch (_: Throwable) {
                }
            }
        } else {
            newAnimeLoadResponse(title, url, type) {
                this.engName = animeMetaData?.titles?.get("en") ?: title
                this.japName = animeMetaData?.titles?.get("ja") ?: animeMetaData?.titles?.get("x-jat")
                this.posterUrl = tracker?.image ?: poster
                this.backgroundPosterUrl = backgroundposter
                try {
                    this.logoUrl = logoUrl
                } catch (_: Throwable) {
                }
                this.plot = finalPlot
                this.tags = genres
                this.score = Score.from10(animeMetaData?.episodes?.get("1")?.rating)
                addEpisodes(DubStatus.Subbed, episodes)
                addMalId(malId)
                addAniListId(aniId)
                try {
                    addKitsuId(kitsuid)
                } catch (_: Throwable) {
                }
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = request(data).document

        document.select("a.server[data-video], a#allmiror[data-video]").amap { server ->
            val videoUrl = server.attr("data-video").trim()
            if (videoUrl.isBlank()) return@amap
            val serverName = server.text().trim().ifBlank { "Server" }
            try {
                loadExtractor(videoUrl, data, subtitleCallback, callback)
            } catch (_: Exception) {
                if (videoUrl.contains(".mp4") || videoUrl.contains(".m3u8")) {
                    callback.invoke(
                        newExtractorLink(
                            source = serverName,
                            name = serverName,
                            url = videoUrl,
                            type = INFER_TYPE
                        ) {
                            this.referer = mainUrl
                        }
                    )
                }
            }
        }

        document.select("iframe#tontonin, .nonton iframe").amap { iframe ->
            val src = iframe.attr("src").trim()
            if (src.isNotBlank()) {
                loadExtractor(src, data, subtitleCallback, callback)
            }
        }

        return true
    }

    private fun Element?.getImageAttr(): String? {
        if (this == null) return null
        val attrs = listOf("data-original", "data-src", "data-lazy-src", "src")
        for (a in attrs) {
            val v = this.attr(a).trim()
            if (v.isNotEmpty() && !v.startsWith("data:") && !v.contains("loading.gif")) return fixUrlNull(v)
        }
        return null
    }
}
