package com.animasu

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addAniListId
import com.lagradost.cloudstream3.LoadResponse.Companion.addKitsuId
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.runBlocking
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

class AnimasuProvider : MainAPI() {
    override var mainUrl = "https://animasu.love"
    override var name = "Animasu"
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
            t.contains("OVA", true) || t.contains("Special", true) -> TvType.OVA
            t.contains("Movie", true) -> TvType.AnimeMovie
            else -> TvType.Anime
        }

        fun getStatus(t: String?): ShowStatus = when {
            t == null -> ShowStatus.Completed
            t.contains("Tayang", true) -> ShowStatus.Ongoing
            t.contains("Completed", true) || t.contains("Tamat", true) || t.contains("Selesai", true) -> ShowStatus.Completed
            else -> ShowStatus.Completed
        }

        fun getQuality(str: String?): Int {
            return Regex("(\\d{3,4})[pP]").find(str ?: "")?.groupValues?.getOrNull(1)?.toIntOrNull()
                ?: Qualities.Unknown.value
        }
    }

    override val mainPage = mainPageOf(
        "$mainUrl/anime-sedang-tayang-terbaru/page/" to "Ongoing",
        "$mainUrl/selesai-tayang/page/" to "Completed",
        "$mainUrl/populer/page/" to "Popular",
        "$mainUrl/anime-movie/page/" to "Anime Movie"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = if (page <= 1) request.data else "${request.data}?halaman=$page"
        val document = app.get(url).document
        val home = document.select("div.listupd div.bsx").mapNotNull {
            it.toSearchResult()
        }.distinctBy { it.url }

        return newHomePageResponse(request.name, home, hasNext = home.isNotEmpty())
    }

    private fun Element.toSearchResult(): AnimeSearchResponse? {
        val anchor = this.selectFirst("a") ?: return null
        val href = fixUrl(anchor.attr("href"))
        val title = this.selectFirst("div.tt")?.text()?.trim()
            ?.ifBlank { null }
            ?: anchor.attr("title").trim()
        if (title.isBlank()) return null

        val posterUrl = fixUrlNull(this.selectFirst("img")?.attr("src"))
        val epNum = this.selectFirst("span.epx")?.text()?.replace(Regex("\\D"), "")?.trim()?.toIntOrNull()
        val typeStr = this.selectFirst("div.typez")?.text()?.trim()

        return newAnimeSearchResponse(title, href, getType(typeStr)) {
            this.posterUrl = posterUrl
            addSub(epNum)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return app.get("$mainUrl/?s=$query").document
            .select("div.listupd div.bsx")
            .mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val rawTitle = document.selectFirst("div.infox h1")?.text()?.trim().orEmpty()
        val title = document.selectFirst("div.infox span.alter")?.text()?.trim()?.ifBlank { null }
            ?: rawTitle.replace(Regex("(?i)\\s*Sub\\s*Indo\\s*$"), "").trim().ifBlank { rawTitle }

        val poster = fixUrlNull(document.selectFirst("div.bigcontent > div.thumb > img")?.attr("src"))

        val spe = document.selectFirst("div.infox div.spe")

        val tags = spe?.select("span:contains(Genre) a")?.map { it.text().trim() } ?: emptyList()

        val typeStr = spe?.selectFirst("span:contains(Jenis)")?.ownText()?.replace(":", "")?.trim()
        val type = getType(typeStr)

        val statusStr = spe?.selectFirst("span:contains(Status)")?.text()?.replace("Status", "")?.replace(":", "")?.trim()
        val status = getStatus(statusStr)

        val year = Regex("(\\d{4})").find(
            spe?.selectFirst("span:contains(Rilis)")?.text().orEmpty()
        )?.groupValues?.getOrNull(1)?.toIntOrNull()

        val studio = spe?.selectFirst("span:contains(Studio) a")?.text()?.trim()

        val rating = document.selectFirst("div.rt div.rating strong")?.text()
            ?.replace(Regex("[^0-9.]"), "")?.toDoubleOrNull()

        val description = document.selectFirst("div.sinopsis span.desc")?.text()?.trim()
            ?: document.selectFirst("div.infox div.sepele")?.text()?.trim().orEmpty()

        val trailer = document.selectFirst("div.bixbox.trailer iframe")?.attr("src")

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

        val backgroundposter = animeMetaData?.images?.find { it.coverType == "Fanart" }?.url ?: tracker?.cover

        val episodes = document.select("ul#daftarepisode > li").mapNotNull { el ->
            val epLink = el.selectFirst("span.lchx a") ?: return@mapNotNull null
            val name = epLink.text().trim()
            val link = fixUrl(epLink.attr("href"))
            var episodeNum = Regex("Episode\\s?(\\d+)").find(name)?.groupValues?.getOrNull(1)?.toIntOrNull()

            if (type == TvType.AnimeMovie && episodeNum == null) {
                episodeNum = 1
            }

            val episodeKey = episodeNum?.toString()
            val metaEp = if (episodeKey != null) animeMetaData?.episodes?.get(episodeKey) else null
            val epOverview = metaEp?.overview?.takeIf { it.isNotBlank() }

            newEpisode(link) {
                this.name = if (type == TvType.AnimeMovie) {
                    animeMetaData?.titles?.get("en") ?: animeMetaData?.titles?.get("ja") ?: title
                } else {
                    metaEp?.title?.get("en") ?: metaEp?.title?.get("ja") ?: name
                }
                this.episode = episodeNum
                this.score = Score.from10(metaEp?.rating)
                this.posterUrl = metaEp?.image?.takeIf { it.isNotBlank() }
                    ?: animeMetaData?.images?.firstOrNull()?.url
                    ?: backgroundposter ?: tracker?.image ?: poster ?: ""
                this.description = epOverview
                this.addDate(metaEp?.airDateUtc)
                this.runTime = metaEp?.runtime
            }
        }.reversed()

        val apiDescription = animeMetaData?.description?.replace(Regex("<.*?>"), "")
        val rawPlot = apiDescription?.takeIf { it.isNotBlank() }
            ?: animeMetaData?.episodes?.get("1")?.overview?.takeIf { it.isNotBlank() }
            ?: fetchAniListPlot(malId, aniId)

        val finalPlot = rawPlot?.takeIf { it.isNotBlank() } ?: description

        return newAnimeLoadResponse(title, url, type) {
            this.engName = animeMetaData?.titles?.get("en") ?: title
            this.japName = animeMetaData?.titles?.get("ja") ?: animeMetaData?.titles?.get("x-jat")
            this.posterUrl = tracker?.image ?: poster
            this.backgroundPosterUrl = backgroundposter
            try {
                this.logoUrl = logoUrl
            } catch (_: Throwable) {
            }
            this.year = year
            addEpisodes(DubStatus.Subbed, episodes)
            this.showStatus = status
            this.score = rating?.let { Score.from10(it) } ?: Score.from10(animeMetaData?.episodes?.get("1")?.rating)
            this.plot = finalPlot
            addTrailer(trailer)
            this.tags = tags + listOfNotNull(studio)
            addMalId(malId)
            addAniListId(aniId)
            try {
                addKitsuId(kitsuid)
            } catch (_: Throwable) {
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document

        var found = false

        document.select("select.mirror option[value]").forEach { option ->
            val encoded = option.attr("value")
            if (encoded.isBlank()) return@forEach

            val decodedHtml = try {
                base64Decode(encoded)
            } catch (_: Exception) {
                return@forEach
            }

            val iframeSrc = Jsoup.parse(decodedHtml).selectFirst("iframe")?.attr("src")
            if (iframeSrc.isNullOrBlank()) return@forEach

            val label = option.text().trim()
            val quality = getQuality(label)
            if (loadCustomExtractor(iframeSrc, "$mainUrl/", subtitleCallback, callback, quality, label)) {
                found = true
            }
        }

        // Fallback to the default embedded iframe if no mirror options were usable.
        if (!found) {
            val fallbackSrc = document.selectFirst("div#pembed iframe")?.attr("src")
            if (!fallbackSrc.isNullOrBlank()) {
                found = loadCustomExtractor(fallbackSrc, "$mainUrl/", subtitleCallback, callback)
            }
        }

        return found
    }

    private suspend fun loadCustomExtractor(
        url: String,
        referer: String? = null,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        quality: Int = Qualities.Unknown.value,
        label: String? = null,
    ): Boolean {
        var success = false
        loadExtractor(fixUrl(url), referer, subtitleCallback) { link ->
            success = true
            runBlocking {
                callback.invoke(
                    newExtractorLink(
                        link.name,
                        if (!label.isNullOrBlank()) "${link.name} ($label)" else link.name,
                        link.url,
                        link.type
                    ) {
                        this.referer = link.referer
                        this.quality = if (link.quality == Qualities.Unknown.value) quality else link.quality
                        this.headers = link.headers
                        this.extractorData = link.extractorData
                    }
                )
            }
        }

        if (!success) {
            success = loadDirectFallback(url, referer, quality, label, callback)
        }

        return success
    }

    private suspend fun loadDirectFallback(
        url: String,
        referer: String?,
        quality: Int,
        label: String?,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val fixed = fixUrl(url)
        val page = try {
            app.get(fixed, referer = referer).text
        } catch (_: Exception) {
            return false
        }

        val streams = Regex("""https?://[^\s"'<>]+\.(?:m3u8|mp4)[^\s"'<>]*""", RegexOption.IGNORE_CASE)
            .findAll(page)
            .map { it.value.replace("\\/", "/").replace("\\u0026", "&") }
            .distinct()
            .toList()

        if (streams.isEmpty()) return false

        streams.forEach { stream ->
            callback.invoke(
                newExtractorLink(
                    name,
                    if (!label.isNullOrBlank()) "$name ($label)" else name,
                    stream,
                    INFER_TYPE
                ) {
                    this.referer = fixed
                    this.quality = quality
                    this.headers = mapOf("Referer" to fixed)
                }
            )
        }
        return true
    }
}
