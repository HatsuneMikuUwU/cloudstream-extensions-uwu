package com.alqanime

import android.util.Base64
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.LoadResponse.Companion.addAniListId
import com.lagradost.cloudstream3.LoadResponse.Companion.addKitsuId
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLDecoder

class Alqanime : MainAPI() {
    override var mainUrl = "https://alqanime.net"
    override var name = "Alqanime"
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
            t.contains("Movie", true) -> TvType.AnimeMovie
            t.contains("OVA", true) || t.contains("Special", true) -> TvType.OVA
            else -> TvType.Anime
        }

        fun getStatus(t: String): ShowStatus = when {
            t.contains("Completed", true) || t.contains("Tamat", true) -> ShowStatus.Completed
            t.contains("Ongoing", true) -> ShowStatus.Ongoing
            else -> ShowStatus.Completed
        }
    }

    private val commonHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
        "Accept-Language" to "en-US,en;q=0.5",
        "Referer" to mainUrl
    )

    override val mainPage = mainPageOf(
        "$mainUrl/page/%d/" to "Latest Releases",
        "$mainUrl/advanced-search/page/%d/?status=ongoing&order=update" to "Ongoing",
        "$mainUrl/advanced-search/page/%d/?status=completed&order=update" to "Complete",
        "$mainUrl/advanced-search/page/%d/?type[]=movie&order=update" to "Movie",
        "$mainUrl/tag/donghua/page/%d/" to "Donghua"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val home = runCatching {
            val document = app.get(
                request.data.format(page),
                headers = commonHeaders,
                referer = mainUrl,
                timeout = 15000L
            ).document
            document.select("div.listupd:not(.popularslider) article.bs")
                .mapNotNull { it.toSearchResult() }
        }.getOrDefault(emptyList())

        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): AnimeSearchResponse? {
        val href = fixUrlNull(selectFirst("a")?.attr("href")) ?: return null
        val title = selectFirst(".ntitle")?.text()?.trim() ?: return null
        val posterUrl = fixUrlNull(selectFirst("img")?.attr("src"))
        val typeText = selectFirst(".typez")?.text()?.trim() ?: ""
        val epNum = selectFirst("a")?.attr("title")
            ?.let {
                Regex("Episode\\s*\\((\\d+)\\)", RegexOption.IGNORE_CASE)
                    .find(it)?.groupValues?.getOrNull(1)?.toIntOrNull()
            }
        val rating = selectFirst("div.numscore")?.text()?.trim()
        return newAnimeSearchResponse(title, href, getType(typeText)) {
            this.posterUrl = posterUrl
            addDubStatus("Sub Indo", epNum)
            this.score = Score.from10(rating)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/?s=$query", headers = commonHeaders).document
        return document.select("article.bs").mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url, headers = commonHeaders).document

        val rawTitle = document.selectFirst("h1.entry-title")?.text()?.trim() ?: return null
        val title = rawTitle
            .replace(Regex("\\s*\\(Episode[^)]*\\)", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s*Sub Indo\\b.*", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s*\\(BD\\).*", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s*BD Batch.*", RegexOption.IGNORE_CASE), "")
            .trim()

        val poster = document.selectFirst(
            "div.thumb img, div.bigcontent div.thumb img, div.postbody div.thumb img, " +
                "div.infox div.thumb img, div.ime img, div.bigcover img, img.wp-post-image, " +
                "meta[property=og:image], meta[name=twitter:image]"
        )?.imageUrl()
        val coverBg = document.selectFirst(
            "div.ime img, div.bigcover img, div.thumb img, img.wp-post-image, " +
                "meta[property=og:image], meta[name=twitter:image]"
        )?.imageUrl() ?: poster
        val trailerRaw = document.selectFirst("a.trailerbutton")?.attr("href")
        val trailer = trailerRaw?.let { trailerUrl ->
            val videoId = Regex("[?&]v=([^&]+)").find(trailerUrl)?.groupValues?.getOrNull(1)
            if (videoId != null) "https://www.youtube.com/embed/$videoId" else trailerUrl
        }

        val description = document.select("div.entry-content > p")
            .filter { it.text().length > 10 }
            .joinToString("\n\n") { it.text().trim() }
            .ifBlank { null }

        val genres = document.select("div.genxed a").map { it.text() }

        val speMap = document.select("div.spe > span").associate { span ->
            val label = span.selectFirst("b")?.text()?.trim() ?: ""
            val value = span.text().replace(label, "").trim()
            label to value
        }

        val status = getStatus(speMap.entries.find { it.key.contains("Status", true) }?.value ?: "")
        val typeText = speMap.entries.find { it.key.contains("Tipe", true) }?.value ?: ""
        val type = getType(typeText)
        val year = Regex("(\\d{4})").find(
            speMap.entries.find { it.key.contains("Dirilis", true) }?.value ?: ""
        )?.groupValues?.getOrNull(1)?.toIntOrNull()

        val japName = document.selectFirst("span.alter")?.text()?.trim()
            ?.split(",")?.firstOrNull()?.trim()?.trimStart('-')?.trimEnd('-')?.trim()
        val studio = document.selectFirst("div.spe > span:contains(Studio) a")?.text()?.trim()
        val season = document.selectFirst("div.spe > span:contains(Musim) a")?.text()?.trim()
        val duration = Regex("(\\d+)\\s*min").find(
            speMap.entries.find { it.key.contains("Durasi", true) }?.value ?: ""
        )?.groupValues?.getOrNull(1)?.toIntOrNull()
        val actors = document.select("div.spe span:contains(Casts) a.casts").map { Actor(it.text()) }
        val scoreText = document.selectFirst("strong:contains(Score)")?.text()
            ?.replace("Score", "")?.trim()

        val episodes = mutableListOf<Episode>()

        for (col in document.select("div.sorattl.collapsible")) {
            val epTitle = col.selectFirst("h3")?.text()?.trim() ?: continue
            if (epTitle.equals("Batch", ignoreCase = true)) continue

            val epNum = Regex("Episode\\s*(\\d+)", RegexOption.IGNORE_CASE)
                .find(epTitle)?.groupValues?.getOrNull(1)?.toIntOrNull()

            val contentDiv = col.nextElementSibling()
                ?.takeIf { it.hasClass("content") }
                ?: continue

            val pixeldrainFolderIds = mutableListOf<String>()

            for (tr in contentDiv.select("tr")) {
                for (a in tr.select("div.slink a")) {
                    val resolved = resolveUrl(a.attr("href"))
                    val listId = Regex("pixeldrain\\.com/l/([A-Za-z0-9]+)")
                        .find(resolved)?.groupValues?.getOrNull(1)
                    if (listId != null) pixeldrainFolderIds.add(listId)
                }
            }

            if (pixeldrainFolderIds.isNotEmpty()) {
                val epMap = mutableMapOf<Int, MutableList<EpisodeLink>>()
                val epThumbs = mutableMapOf<Int, String>()

                for (listId in pixeldrainFolderIds) {
                    try {
                        val apiJson = app.get("https://pixeldrain.com/api/list/$listId")
                            .parsedSafe<PixeldrainList>()
                        apiJson?.files
                            ?.filter { it.mimeType.startsWith("video/") }
                            ?.sortedBy { it.name }
                            ?.forEach { file ->
                                val fileEpNum = Regex("(?:_|-)0*(\\d+)(?:_|-)")
                                    .find(file.name)?.groupValues?.getOrNull(1)?.toIntOrNull()
                                    ?: return@forEach
                                val fileQuality = Regex("(\\d{3,4})p", RegexOption.IGNORE_CASE)
                                    .find(file.name)?.groupValues?.getOrNull(1) ?: ""
                                val streamUrl = "https://pixeldrain.com/api/file/${file.id}"
                                epMap.getOrPut(fileEpNum) { mutableListOf() }
                                    .add(EpisodeLink(streamUrl, fileQuality))
                                if (!epThumbs.containsKey(fileEpNum)) {
                                    epThumbs[fileEpNum] =
                                        "https://pixeldrain.com/api/file/${file.id}/thumbnail"
                                }
                            }
                    } catch (_: Exception) {
                    }
                }

                for ((episodeNumber, links) in epMap.toSortedMap()) {
                    episodes.add(newEpisode(links.toEpisodeJson()) {
                        this.name = "Episode $episodeNumber"
                        this.episode = episodeNumber
                        this.posterUrl = epThumbs[episodeNumber] ?: coverBg ?: poster
                    })
                }
            }

            if (pixeldrainFolderIds.isEmpty()) {
                val linkList = mutableListOf<EpisodeLink>()
                for (tr in contentDiv.select("tr")) {
                    val quality = tr.selectFirst("div.res")?.text()?.trim() ?: continue
                    for (a in tr.select("div.slink a")) {
                        linkList.add(EpisodeLink(a.attr("href"), quality))
                    }
                }

                if (linkList.isNotEmpty()) {
                    episodes.add(newEpisode(linkList.toEpisodeJson()) {
                        this.name = epTitle
                        this.episode = epNum
                        this.posterUrl = coverBg ?: poster
                    })
                }
            }
        }

        val tracker = APIHolder.getTracker(
            listOfNotNull(title, japName).distinct(),
            TrackerType.getTypes(type),
            year,
            true
        )
        val ids = resolveAnimeIds(listOfNotNull(title, japName).distinct(), type, year, tracker?.malId, tracker?.aniId?.toIntOrNull())
        val malId = ids.malId
        val aniId = ids.aniId

        // api.ani.zip: titles, description, fanart and per-episode metadata
        val animeMetaData = fetchAniZipMeta(malId, aniId)
        val tmdbId = animeMetaData?.mappings?.themoviedbId
        val kitsuId = animeMetaData?.mappings?.kitsuId

        // api.themoviedb.org: title logo
        val tmdbLogoUrl = fetchTmdbLogoUrl(
            tmdbAPI = "https://api.themoviedb.org/3",
            apiKey = "98ae14df2b8d8f8f8136499daf79f0e0",
            type = type,
            tmdbId = tmdbId,
            appLangCode = "en"
        )

        val backgroundPoster = animeMetaData?.images?.find { it.coverType == "Fanart" }?.url
            ?: tracker?.cover
            ?: coverBg

        // Episode data (link JSON) stays untouched, only the display info is enriched.
        episodes.forEach { ep ->
            val episodeNum = ep.episode ?: if (type == TvType.AnimeMovie) 1 else null
            val metaEp = episodeNum?.let { animeMetaData?.episodes?.get(it.toString()) }
            val epOverview = metaEp?.overview

            ep.name = if (type == TvType.AnimeMovie) {
                animeMetaData?.titles?.get("en") ?: animeMetaData?.titles?.get("ja") ?: ep.name
            } else {
                metaEp?.title?.get("en") ?: metaEp?.title?.get("ja") ?: ep.name
            }
            ep.episode = episodeNum
            ep.score = Score.from10(metaEp?.rating)
            ep.posterUrl = metaEp?.image?.takeIf { it.isNotBlank() } ?: ep.posterUrl?.takeIf { it.isNotBlank() } ?: animeMetaData?.images?.firstOrNull()?.url ?: backgroundPoster ?: tracker?.cover
            ep.description = epOverview?.takeIf { it.isNotBlank() }
            ep.addDate(metaEp?.airDateUtc)
            ep.runTime = metaEp?.runtime
        }

        val apiDescription = animeMetaData?.description?.replace(Regex("<.*?>"), "")
        val rawPlot = apiDescription?.takeIf { it.isNotBlank() }
            ?: animeMetaData?.episodes?.get("1")?.overview?.takeIf { it.isNotBlank() }
            ?: fetchAniListPlot(malId, aniId)
        val finalPlot = if (!rawPlot.isNullOrBlank()) rawPlot else description

        return newAnimeLoadResponse(title, url, type) {
            this.japName = animeMetaData?.titles?.get("ja") ?: animeMetaData?.titles?.get("x-jat") ?: japName
            engName = animeMetaData?.titles?.get("en") ?: title
            posterUrl = tracker?.image ?: poster
            backgroundPosterUrl = backgroundPoster
            try { this.logoUrl = tmdbLogoUrl } catch (_: Throwable) {}
            this.year = year
            this.duration = duration
            addEpisodes(DubStatus.Subbed, episodes.reversed())
            showStatus = status
            plot = finalPlot
            addTrailer(trailer, addRaw = true)
            this.tags = listOfNotNull(*genres.toTypedArray(), studio, season)
            addActors(actors)
            this.score = Score.from10(scoreText?.toFloatOrNull())

            addMalId(malId)
            addAniListId(aniId)
            try { addKitsuId(kitsuId) } catch (_: Throwable) {}
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val links = parseEpisodeLinks(data)
        if (links.isEmpty()) return false

        var emitted = false
        val emittedUrls = linkedSetOf<String>()

        fun markEmit(link: ExtractorLink) {
            if (emittedUrls.add(link.url.substringBefore("#"))) {
                callback(link)
                emitted = true
            }
        }

        suspend fun emitDirect(
            source: String,
            url: String,
            quality: Int,
            referer: String,
            headers: Map<String, String> = commonHeaders + mapOf("Referer" to referer)
        ): Boolean {
            val fixedUrl = url.cleanEscaped().replace(".txt", ".m3u8")
            if (!fixedUrl.isPlayableMediaUrl()) return false
            val type = if (fixedUrl.contains(".m3u8", true)) {
                ExtractorLinkType.M3U8
            } else {
                ExtractorLinkType.VIDEO
            }
            markEmit(newExtractorLink(source, source, fixedUrl, type) {
                this.referer = referer
                this.quality = quality
                this.headers = headers
            })
            return true
        }

        for (linkData in links) {
            val resolvedUrl = resolvePlaybackUrl(linkData.url)
            if (resolvedUrl.isBlank()) continue

            val qualityInt = linkData.quality.fixQuality()

            if (resolvedUrl.isArchiveDownloadUrl()) continue

            if (resolvedUrl.contains("pixeldrain.com/api/file/", true)) {
                markEmit(newExtractorLink("Pixeldrain", "Pixeldrain", resolvedUrl) {
                    this.referer = "https://pixeldrain.com/"
                    this.quality = qualityInt
                })
                continue
            }

            if (resolvedUrl.contains("mediafire.com", true)) {
                if (tryMediafire(resolvedUrl, qualityInt, ::markEmit)) continue
                continue
            }

            if (resolvedUrl.contains("acefile.co", true)) {
                if (tryAcefile(resolvedUrl, qualityInt, ::markEmit, subtitleCallback)) continue
                continue
            }

            if (resolvedUrl.contains("resharer.org", true)) {
                if (tryReshare(resolvedUrl, qualityInt, ::markEmit, subtitleCallback)) continue
            }

            if (resolvedUrl.contains("yurinime.com", true)) {
                if (tryExternalStreaming(resolvedUrl, qualityInt, ::markEmit, subtitleCallback)) continue
            }

            if (resolvedUrl.isExternalDownloadHostUrl()) {
                if (tryExternalDownloadHost(resolvedUrl, qualityInt, ::markEmit, subtitleCallback)) continue
            }

            val directFromUrl = emitDirect(
                source = name,
                url = resolvedUrl,
                quality = qualityInt,
                referer = "$mainUrl/"
            )
            if (directFromUrl) continue

            try {
                loadExtractor(resolvedUrl, "$mainUrl/", subtitleCallback) { link ->
                    markEmit(link)
                }
            } catch (_: Exception) {
            }
        }

        return emitted
    }

    private suspend fun tryMediafire(
        url: String,
        quality: Int,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val response = runCatching {
            app.get(
                url,
                headers = commonHeaders + mapOf("Referer" to "$mainUrl/"),
                referer = "$mainUrl/",
                timeout = 20000L
            )
        }.getOrNull() ?: return false

        val document = response.document
        val html = response.text.cleanEscaped()
        val candidates = linkedSetOf<String>()

        document.select(
            "a#downloadButton[href], a.input.popsok[href], a[aria-label*=Download][href], " +
                "a[href*=download][href*=mediafire], a[href*=mediafire][href*=download]"
        ).forEach { element ->
            fixUrlNull(element.attr("href"))?.let { candidates.add(it.cleanEscaped()) }
        }

        Regex("""https?://download[^"'\\\s<>]+""", RegexOption.IGNORE_CASE)
            .findAll(html)
            .map { it.value.cleanEscaped() }
            .forEach { candidates.add(it) }

        Regex("""(?i)(?:href|url)\s*[:=]\s*["']([^"'] *download[^"']*mediafire[^"']*)["']""")
            .findAll(html)
            .mapNotNull { it.groupValues.getOrNull(1)?.cleanEscaped() }
            .forEach { fixUrlNull(it)?.let(candidates::add) }

        for (candidate in candidates) {
            val fixed = candidate.cleanEscaped()
            if (!fixed.contains("mediafire.com", true) && !fixed.contains("download", true)) continue
            callback(newExtractorLink("MediaFire", "MediaFire", fixed, ExtractorLinkType.VIDEO) {
                this.referer = url
                this.quality = quality
                this.headers = commonHeaders + mapOf("Referer" to url)
            })
            return true
        }

        return false
    }

    private suspend fun tryAcefile(
        url: String,
        quality: Int,
        callback: (ExtractorLink) -> Unit,
        subtitleCallback: (SubtitleFile) -> Unit
    ): Boolean {
        val cleanUrl = url.cleanEscaped()
        val id = Regex("""acefile\.co/(?:f|file|player)/([A-Za-z0-9]+)""", RegexOption.IGNORE_CASE)
            .find(cleanUrl)?.groupValues?.getOrNull(1)
            ?: Regex("""[?&](?:id|file)=([A-Za-z0-9]+)""", RegexOption.IGNORE_CASE)
                .find(cleanUrl)?.groupValues?.getOrNull(1)

        val visited = linkedSetOf<String>()
        val pageQueue = mutableListOf<String>()

        fun addPage(page: String, baseUrl: String = cleanUrl) {
            val normalized = normalizeUrl(page, baseUrl)?.cleanEscaped() ?: return
            if (normalized.isBlank() || normalized == "#") return
            if (normalized.isArchiveDownloadUrl()) return
            if (visited.add(normalized)) pageQueue.add(normalized)
        }

        suspend fun emitAcefileDirect(candidate: String, referer: String): Boolean {
            val fixed = candidate.cleanEscaped().replace(".txt", ".m3u8")
            if ((!fixed.isPlayableMediaUrl() && !fixed.isAcefileServicePlayUrl()) ||
                fixed.isArchiveDownloadUrl() ||
                fixed.isAcefileLandingPageUrl()
            ) return false
            val type = if (fixed.contains(".m3u8", true)) {
                ExtractorLinkType.M3U8
            } else {
                ExtractorLinkType.VIDEO
            }
            callback(newExtractorLink("AceFile", "AceFile", fixed, type) {
                this.referer = referer
                this.quality = quality
                this.headers = commonHeaders + mapOf("Referer" to referer)
            })
            return true
        }

        if (id != null) {
            addPage("https://acefile.co/player/$id")
        } else {
            addPage(cleanUrl)
        }

        var emitted = false
        var index = 0

        while (index < pageQueue.size && index < 6) {
            val pageUrl = pageQueue[index++]
            val referer = when {
                pageUrl.contains("/player/", true) && cleanUrl.contains("acefile.co", true) -> cleanUrl
                pageUrl.contains("/local/", true) -> "https://acefile.co/player/${id ?: ""}"
                else -> "$mainUrl/"
            }

            val response = runCatching {
                app.get(
                    pageUrl,
                    headers = commonHeaders + mapOf(
                        "Referer" to referer,
                        "Origin" to "https://acefile.co"
                    ),
                    referer = referer,
                    timeout = 20000L
                )
            }.getOrNull() ?: continue

            val document = response.document
            val html = response.text.cleanEscaped()

            extractAcefileSourceUrls(html, pageUrl).forEach { direct ->
                if (emitAcefileDirect(direct, pageUrl)) emitted = true
            }

            if (emitted) return true

            collectPlayableUrls(document, html, pageUrl)
                .filterNot { it.isAcefileLandingPageUrl() }
                .forEach { direct ->
                    if (emitAcefileDirect(direct, pageUrl)) emitted = true
                }

            if (emitted) return true

            extractAcefileLocalPages(document, html, pageUrl).forEach { localPage ->
                addPage(localPage, pageUrl)
            }

            document.select(
                "iframe[src], embed[src], video[src], video source[src], source[src], " +
                    "[data-url], [data-href], [data-link], [data-file], [data-source], [data-video], [data-src]"
            ).forEach { element ->
                listOf("src", "data-url", "data-href", "data-link", "data-file", "data-source", "data-video", "data-src")
                    .map { element.attr(it) }
                    .map { it.cleanEscaped() }
                    .filter { it.isNotBlank() && !it.isAcefileLandingPageUrl() }
                    .forEach { addPage(it, pageUrl) }
            }

            runCatching {
                loadExtractor(pageUrl, referer, subtitleCallback) { link ->
                    val linkUrl = link.url.cleanEscaped()
                    if ((linkUrl.isPlayableMediaUrl() || linkUrl.isAcefileServicePlayUrl()) &&
                        !linkUrl.isAcefileLandingPageUrl() &&
                        !linkUrl.isArchiveDownloadUrl()
                    ) {
                        callback(link)
                        emitted = true
                    }
                }
            }

            if (emitted) return true
        }

        return false
    }

    private fun extractAcefileLocalPages(
        document: org.jsoup.nodes.Document,
        html: String,
        baseUrl: String
    ): List<String> {
        val pages = linkedSetOf<String>()

        document.select("[data-holder=local][data-video], [data-video*=local]").forEach { element ->
            listOf("data-video", "data-src", "src", "href")
                .map { element.attr(it) }
                .mapNotNull { normalizeUrl(it, baseUrl) }
                .map { it.cleanEscaped() }
                .filter { it.contains("acefile.co/local/", true) }
                .forEach { pages.add(it) }
        }

        Regex("""https?://acefile\.co/local/\d+\?key=[A-Za-z0-9]+""", RegexOption.IGNORE_CASE)
            .findAll(html)
            .map { it.value.cleanEscaped() }
            .forEach { pages.add(it) }

        Regex("""/local/(\d+)\?key=([A-Za-z0-9]+)""", RegexOption.IGNORE_CASE)
            .findAll(html)
            .map { "https://acefile.co/local/${it.groupValues[1]}?key=${it.groupValues[2]}" }
            .forEach { pages.add(it) }

        Regex("""'([^']*)'\.split\('\|'\)""")
            .findAll(html)
            .map { it.groupValues[1].split("|") }
            .forEach { parts ->
                val serverIndex = parts.indexOf("server")
                val keyIndex = parts.indexOf("mirrorHasVideo")
                val localId = parts.getOrNull(serverIndex + 1)?.takeIf { it.matches(Regex("""\d{5,}""")) }
                val key = parts.getOrNull(keyIndex + 1)?.takeIf { it.matches(Regex("""[A-Fa-f0-9]{24,}""")) }
                if (localId != null && key != null) {
                    pages.add("https://acefile.co/local/$localId?key=$key")
                }
            }

        return pages.toList()
    }

    private fun extractAcefileSourceUrls(html: String, baseUrl: String): List<String> {
        val results = linkedSetOf<String>()
        val encodedSources = linkedSetOf<String>()

        Regex(
            """sources\s*:\s*JSON\.parse\s*\(\s*(?:atob|a2b)\s*\(\s*["']([^"']+)["']\s*\)\s*\)""",
            RegexOption.IGNORE_CASE
        ).findAll(html)
            .map { it.groupValues[1] }
            .forEach { encodedSources.add(it) }

        Regex(
            """JSON\.parse\s*\(\s*(?:atob|a2b)\s*\(\s*["']([^"']+)["']\s*\)\s*\)""",
            RegexOption.IGNORE_CASE
        ).findAll(html)
            .map { it.groupValues[1] }
            .forEach { encodedSources.add(it) }

        encodedSources.forEach { encoded ->
            val decoded = decodeAcefileBase64(encoded) ?: return@forEach
            runCatching {
                val array = JSONArray(decoded)
                for (i in 0 until array.length()) {
                    val file = array.optJSONObject(i)?.optString("file").orEmpty()
                    normalizeUrl(file, baseUrl)?.cleanEscaped()?.let { results.add(it) }
                }
            }
        }

        return results.toList()
    }

    private fun decodeAcefileBase64(value: String): String? {
        val clean = value.trim().replace(Regex("""\s+"""), "")
        if (clean.isBlank()) return null
        val padded = clean + "=".repeat((4 - clean.length % 4) % 4)
        return runCatching {
            String(Base64.decode(padded, Base64.DEFAULT), Charsets.UTF_8)
        }.getOrNull()
    }

    private suspend fun tryReshare(
        url: String,
        quality: Int,
        callback: (ExtractorLink) -> Unit,
        subtitleCallback: (SubtitleFile) -> Unit
    ): Boolean {
        val visited = linkedSetOf<String>()
        val pageQueue = mutableListOf<String>()

        fun addPage(page: String) {
            if (page.contains("resharer.org", true) && visited.add(page)) {
                pageQueue.add(page)
            }
        }

        suspend fun emitCandidate(candidate: String, referer: String): Boolean {
            val fixed = candidate.cleanEscaped()
            if (fixed.isBlank() || fixed == referer || fixed == "#") return false

            if (fixed.contains("pixeldrain.com/api/file/", true)) {
                callback(newExtractorLink("ReShare", "ReShare", fixed) {
                    this.referer = referer
                    this.quality = quality
                    this.headers = commonHeaders + mapOf("Referer" to referer)
                })
                return true
            }

            if (fixed.isPlayableMediaUrl()) {
                val type = if (fixed.contains(".m3u8", true)) {
                    ExtractorLinkType.M3U8
                } else {
                    ExtractorLinkType.VIDEO
                }
                callback(newExtractorLink("ReShare", "ReShare", fixed, type) {
                    this.referer = referer
                    this.quality = quality
                    this.headers = commonHeaders + mapOf("Referer" to referer)
                })
                return true
            }

            if (!fixed.isLikelyResolvableUrl()) return false

            var emitted = false
            runCatching {
                loadExtractor(fixed, referer, subtitleCallback) { link ->
                    callback(link)
                    emitted = true
                }
            }
            return emitted
        }

        addPage(url)
        var emitted = false
        var index = 0

        while (index < pageQueue.size && index < 3) {
            val pageUrl = pageQueue[index++]
            val response = runCatching {
                app.get(
                    pageUrl,
                    headers = commonHeaders + mapOf(
                        "Referer" to "$mainUrl/",
                        "Origin" to "https://resharer.org"
                    ),
                    referer = "$mainUrl/",
                    timeout = 20000L
                )
            }.getOrNull() ?: continue

            val document = response.document
            val html = response.text.cleanEscaped()
            val candidates = linkedSetOf<String>()

            normalizeUrl(response.url, pageUrl)
                ?.cleanEscaped()
                ?.takeIf { it != pageUrl }
                ?.let { candidates.add(it) }

            document.select(
                "a[href], form[action], button[data-url], button[data-href], " +
                    "[data-url], [data-href], [data-link], [data-download]"
            ).forEach { element ->
                listOf("href", "action", "data-url", "data-href", "data-link", "data-download")
                    .map { element.attr(it) }
                    .mapNotNull { normalizeUrl(it, pageUrl) }
                    .forEach { candidates.add(it) }
            }

            Regex("""https?://[^"'\\\s<>]+""", RegexOption.IGNORE_CASE)
                .findAll(html)
                .map { it.value.cleanEscaped() }
                .forEach { candidates.add(it) }

            Regex("""https?%3A%2F%2F[^"'\\\s<>]+""", RegexOption.IGNORE_CASE)
                .findAll(html)
                .map { runCatching { URLDecoder.decode(it.value, "UTF-8") }.getOrDefault(it.value) }
                .map { it.cleanEscaped() }
                .forEach { candidates.add(it) }

            collectPlayableUrls(document, html, pageUrl).forEach { candidates.add(it) }

            for (candidate in candidates) {
                val normalized = normalizeUrl(candidate, pageUrl)?.cleanEscaped() ?: continue
                if (normalized.contains("resharer.org", true)) {
                    if (normalized != pageUrl) addPage(normalized)
                    continue
                }
                if (emitCandidate(normalized, pageUrl)) {
                    emitted = true
                }
            }

            if (emitted) return true
        }

        return emitted
    }

    private suspend fun tryExternalStreaming(
        url: String,
        quality: Int,
        callback: (ExtractorLink) -> Unit,
        subtitleCallback: (SubtitleFile) -> Unit
    ): Boolean {
        val visited = linkedSetOf<String>()
        val pageQueue = mutableListOf<String>()

        fun addPage(page: String, baseUrl: String = url) {
            val normalized = normalizeUrl(page, baseUrl)?.cleanEscaped() ?: return
            if (normalized.isBlank() || normalized == "#") return
            if (normalized.isArchiveDownloadUrl()) return
            if (visited.add(normalized)) pageQueue.add(normalized)
        }

        addPage(url)
        var emitted = false
        var index = 0

        while (index < pageQueue.size && index < 5) {
            val pageUrl = pageQueue[index++]
            val response = runCatching {
                app.get(
                    pageUrl,
                    headers = commonHeaders + mapOf("Referer" to "$mainUrl/"),
                    referer = "$mainUrl/",
                    timeout = 20000L
                )
            }.getOrNull() ?: continue

            val document = response.document
            val html = response.text.cleanEscaped()
            val referer = pageUrl

            collectPlayableUrls(document, html, pageUrl)
                .filterNot { it.isArchiveDownloadUrl() }
                .forEach { direct ->
                    val type = if (direct.contains(".m3u8", true)) {
                        ExtractorLinkType.M3U8
                    } else {
                        ExtractorLinkType.VIDEO
                    }
                    callback(newExtractorLink("Alqanime Streaming", "Alqanime Streaming", direct, type) {
                        this.referer = referer
                        this.quality = quality
                        this.headers = commonHeaders + mapOf("Referer" to referer)
                    })
                    emitted = true
                }

            if (emitted) return true

            document.select("iframe[src], embed[src], video[src], video source[src], source[src]")
                .map { it.attr("src") }
                .forEach { addPage(it, pageUrl) }

            document.select("a[href], [data-url], [data-href], [data-link], [data-iframe], [data-src]")
                .forEach { element ->
                    listOf("href", "data-url", "data-href", "data-link", "data-iframe", "data-src")
                        .map { element.attr(it) }
                        .map { it.cleanEscaped() }
                        .filter { it.isLikelyResolvableUrl() || it.contains("yurinime.com", true) }
                        .forEach { addPage(it, pageUrl) }
                }

            runCatching {
                loadExtractor(pageUrl, "$mainUrl/", subtitleCallback) { link ->
                    callback(link)
                    emitted = true
                }
            }

            if (emitted) return true
        }

        return emitted
    }

    private suspend fun tryExternalDownloadHost(
        url: String,
        quality: Int,
        callback: (ExtractorLink) -> Unit,
        subtitleCallback: (SubtitleFile) -> Unit
    ): Boolean {
        val sourceName = url.externalDownloadHostName()
        val visited = linkedSetOf<String>()
        val pageQueue = mutableListOf<String>()

        fun addPage(page: String, baseUrl: String = url) {
            val normalized = normalizeUrl(page, baseUrl)?.cleanEscaped() ?: return
            if (normalized.isBlank() || normalized == "#") return
            if (normalized.isArchiveDownloadUrl()) return
            if (!normalized.isExternalDownloadHostUrl() && !normalized.isLikelyResolvableUrl() && !normalized.isPlayableMediaUrl()) return
            if (visited.add(normalized)) pageQueue.add(normalized)
        }

        suspend fun emitDirect(sourceUrl: String, referer: String) {
            val fixed = sourceUrl.cleanEscaped().replace(".txt", ".m3u8")
            if (!fixed.isPlayableMediaUrl() || fixed.isArchiveDownloadUrl()) return
            val type = if (fixed.contains(".m3u8", true)) {
                ExtractorLinkType.M3U8
            } else {
                ExtractorLinkType.VIDEO
            }
            callback(newExtractorLink(sourceName, sourceName, fixed, type) {
                this.referer = referer
                this.quality = quality
                this.headers = commonHeaders + mapOf("Referer" to referer)
            })
        }

        addPage(url)
        var emitted = false
        var index = 0

        while (index < pageQueue.size && index < 5) {
            val pageUrl = pageQueue[index++]
            val referer = if (pageUrl == url.cleanEscaped()) "$mainUrl/" else url

            if (pageUrl.isPlayableMediaUrl()) {
                emitDirect(pageUrl, referer)
                emitted = true
                continue
            }

            runCatching {
                loadExtractor(pageUrl, referer, subtitleCallback) { link ->
                    callback(link)
                    emitted = true
                }
            }
            if (emitted) return true

            val response = runCatching {
                app.get(
                    pageUrl,
                    headers = commonHeaders + mapOf("Referer" to referer),
                    referer = referer,
                    timeout = 20000L
                )
            }.getOrNull() ?: continue

            val document = response.document
            val html = response.text.cleanEscaped()

            collectPlayableUrls(document, html, pageUrl)
                .filterNot { it.isArchiveDownloadUrl() }
                .forEach { direct ->
                    emitDirect(direct, pageUrl)
                    emitted = true
                }

            if (emitted) return true

            document.select(
                "iframe[src], embed[src], video[src], video source[src], source[src], " +
                    "a[href], form[action], [data-url], [data-href], [data-link], [data-download], [data-src]"
            ).forEach { element ->
                listOf("src", "href", "action", "data-url", "data-href", "data-link", "data-download", "data-src")
                    .map { element.attr(it) }
                    .map { it.cleanEscaped() }
                    .filter { it.isNotBlank() }
                    .forEach { addPage(it, pageUrl) }
            }
        }

        return emitted
    }

    private fun collectPlayableUrls(
        document: org.jsoup.nodes.Document,
        html: String,
        baseUrl: String
    ): List<String> {
        val results = linkedSetOf<String>()

        document.select("video[src], video source[src], source[src], a[href]").forEach { element ->
            val raw = element.attr("src").ifBlank { element.attr("href") }
            normalizeUrl(raw, baseUrl)?.cleanEscaped()?.takeIf { it.isPlayableMediaUrl() && !it.isArchiveDownloadUrl() }?.let(results::add)
        }

        Regex("""https?://[^"'\\\s<>]+?\.(?:m3u8|mp4|webm)(?:\?[^"'\\\s<>]*)?""", RegexOption.IGNORE_CASE)
            .findAll(html)
            .map { it.value.cleanEscaped() }
            .filterNot { it.isArchiveDownloadUrl() }
            .forEach { results.add(it) }

        Regex("""https?%3A%2F%2F[^"'\\\s<>]+?(?:%2Em3u8|%2Emp4|%2Ewebm)[^"'\\\s<>]*""", RegexOption.IGNORE_CASE)
            .findAll(html)
            .map {
                runCatching { URLDecoder.decode(it.value, "UTF-8") }.getOrDefault(it.value)
            }
            .map { it.cleanEscaped() }
            .filterNot { it.isArchiveDownloadUrl() }
            .forEach { results.add(it) }

        Regex("""(?i)(?:file|src|source|url|video)\s*[:=]\s*["']([^"']+)["']""")
            .findAll(html)
            .mapNotNull { it.groupValues.getOrNull(1) }
            .mapNotNull { normalizeUrl(it, baseUrl) }
            .map { it.cleanEscaped() }
            .filter { it.isPlayableMediaUrl() && !it.isArchiveDownloadUrl() }
            .forEach { results.add(it) }

        return results.toList()
    }

    private fun normalizeUrl(url: String?, baseUrl: String = mainUrl): String? {
        val clean = url?.cleanEscaped().orEmpty()
        if (clean.isBlank() || clean == "#" || clean.startsWith("javascript:", true)) return null
        return when {
            clean.startsWith("http", true) -> clean
            clean.startsWith("//") -> "https:$clean"
            clean.startsWith("/") -> {
                val origin = runCatching {
                    val uri = URI(baseUrl)
                    "${uri.scheme}://${uri.host}"
                }.getOrDefault(mainUrl)
                origin + clean
            }
            else -> runCatching { URI(baseUrl).resolve(clean).toString() }
                .getOrElse { fixUrl(clean) }
        }
    }

    private suspend fun resolvePlaybackUrl(url: String): String {
        val resolved = resolveUrl(url)
        if (!resolved.isOuoUrl()) return resolved
        return resolveOuoShortUrl(resolved) ?: resolved
    }

    private suspend fun resolveOuoShortUrl(url: String): String? {
        val cleanUrl = url.cleanEscaped()
        if (!cleanUrl.isOuoUrl()) return cleanUrl

        val sParam = Regex("""[?&]s=([^&]+)""")
            .find(cleanUrl)
            ?.groupValues
            ?.getOrNull(1)
        if (sParam != null) {
            return runCatching { URLDecoder.decode(sParam, "UTF-8") }
                .getOrDefault(sParam)
                .cleanEscaped()
        }

        val visited = linkedSetOf<String>()
        var currentUrl = cleanUrl
        var referer = "$mainUrl/"
        var attempt = 0

        while (attempt < 3 && visited.add(currentUrl)) {
            attempt++
            val response = runCatching {
                app.get(
                    currentUrl,
                    headers = commonHeaders + mapOf("Referer" to referer),
                    referer = referer,
                    timeout = 20000L
                )
            }.getOrNull() ?: return null

            normalizeUrl(response.url, currentUrl)
                ?.cleanEscaped()
                ?.takeIf { it.isResolvedShortlinkTarget() }
                ?.let { return it }

            val document = response.document
            val html = response.text.cleanEscaped()
            val candidates = collectShortlinkCandidates(document, html, currentUrl)

            candidates.firstOrNull { it.isResolvedShortlinkTarget() }?.let { return it }

            val form = document.selectFirst("form[action]")
            if (form != null) {
                val action = normalizeUrl(form.attr("action"), currentUrl)?.cleanEscaped()
                if (action != null) {
                    val formData = form.select("input[name]").associate { input ->
                        input.attr("name") to input.attr("value")
                    }.filterKeys { it.isNotBlank() }

                    val postResponse = runCatching {
                        app.post(
                            action,
                            headers = commonHeaders + mapOf(
                                "Referer" to currentUrl,
                                "Origin" to runCatching {
                                    val uri = URI(action)
                                    "${uri.scheme}://${uri.host}"
                                }.getOrDefault("https://ouo.io")
                            ),
                            referer = currentUrl,
                            data = formData,
                            timeout = 20000L
                        )
                    }.getOrNull()

                    if (postResponse != null) {
                        normalizeUrl(postResponse.url, action)
                            ?.cleanEscaped()
                            ?.takeIf { it.isResolvedShortlinkTarget() }
                            ?.let { return it }

                        collectShortlinkCandidates(postResponse.document, postResponse.text.cleanEscaped(), action)
                            .firstOrNull { it.isResolvedShortlinkTarget() }
                            ?.let { return it }

                        val nextUrl = normalizeUrl(postResponse.url, action)
                            ?.cleanEscaped()
                            ?.takeIf { it.isOuoUrl() && it != currentUrl }
                            ?: collectShortlinkCandidates(postResponse.document, postResponse.text.cleanEscaped(), action)
                                .firstOrNull { it.isOuoUrl() && it != currentUrl }

                        if (nextUrl != null) {
                            referer = action
                            currentUrl = nextUrl
                            continue
                        }
                    }
                }
            }

            val nextOuo = candidates.firstOrNull { it.isOuoUrl() && it != currentUrl }
                ?: normalizeUrl(response.url, currentUrl)
                    ?.cleanEscaped()
                    ?.takeIf { it.isOuoUrl() && it != currentUrl }

            if (nextOuo != null) {
                referer = currentUrl
                currentUrl = nextOuo
                continue
            }

            return null
        }

        return null
    }

    private fun collectShortlinkCandidates(
        document: org.jsoup.nodes.Document,
        html: String,
        baseUrl: String
    ): List<String> {
        val candidates = linkedSetOf<String>()

        document.select(
            "a[href], form[action], iframe[src], script[src], " +
                "[data-url], [data-href], [data-link], [data-download]"
        ).forEach { element ->
            listOf("href", "action", "src", "data-url", "data-href", "data-link", "data-download")
                .map { element.attr(it) }
                .mapNotNull { normalizeUrl(it, baseUrl) }
                .map { it.cleanEscaped() }
                .forEach { candidates.add(it) }
        }

        Regex("""(?i)(?:href|src|url|location)\s*[:=]\s*["']([^"']+)["']""")
            .findAll(html)
            .mapNotNull { it.groupValues.getOrNull(1) }
            .mapNotNull { normalizeUrl(it, baseUrl) }
            .map { it.cleanEscaped() }
            .forEach { candidates.add(it) }

        Regex("""(?i)url=([^"'<>\s]+)""")
            .findAll(html)
            .mapNotNull { it.groupValues.getOrNull(1) }
            .mapNotNull { normalizeUrl(it, baseUrl) }
            .map { it.cleanEscaped() }
            .forEach { candidates.add(it) }

        Regex("""https?://[^"'\\\s<>]+""", RegexOption.IGNORE_CASE)
            .findAll(html)
            .map { it.value.cleanEscaped() }
            .forEach { candidates.add(it) }

        Regex("""https?%3A%2F%2F[^"'\\\s<>]+""", RegexOption.IGNORE_CASE)
            .findAll(html)
            .map { runCatching { URLDecoder.decode(it.value, "UTF-8") }.getOrDefault(it.value) }
            .map { it.cleanEscaped() }
            .forEach { candidates.add(it) }

        return candidates.toList()
    }

    private fun resolveUrl(url: String): String {
        val cleanUrl = url.cleanEscaped()
        if (cleanUrl.isBlank() || cleanUrl == "#") return ""

        if (cleanUrl.contains("ouo.io", true) || cleanUrl.contains("ouo.press", true)) {
            val sParam = Regex("[?&]s=([^&]+)")
                .find(cleanUrl)
                ?.groupValues
                ?.getOrNull(1)
            if (sParam != null) {
                return runCatching { URLDecoder.decode(sParam, "UTF-8") }
                    .getOrDefault(sParam)
                    .cleanEscaped()
            }
        }

        return fixUrl(cleanUrl)
    }

    private fun parseEpisodeLinks(data: String): List<EpisodeLink> {
        return runCatching {
            val array = JSONArray(data)
            buildList {
                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i) ?: continue
                    val rawUrl = item.optString("url").trim()
                    val quality = item.optString("quality").trim()
                    if (rawUrl.isNotBlank()) add(EpisodeLink(rawUrl, quality))
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun List<EpisodeLink>.toEpisodeJson(): String {
        val array = JSONArray()
        forEach { link ->
            array.put(
                JSONObject()
                    .put("url", link.url)
                    .put("quality", link.quality)
            )
        }
        return array.toString()
    }

    private fun Element.imageUrl(): String? {
        val direct = listOf(
            "data-src",
            "data-lazy-src",
            "data-original",
            "data-bg",
            "src",
            "content"
        ).firstNotNullOfOrNull { key ->
            attr(key)
                .trim()
                .takeIf { it.isNotBlank() && !it.startsWith("data:", ignoreCase = true) }
        }

        val srcset = attr("srcset").ifBlank { attr("data-srcset") }
            .split(",")
            .map { it.trim().substringBefore(" ").trim() }
            .firstOrNull { it.isNotBlank() && !it.startsWith("data:", ignoreCase = true) }

        return fixUrlNull(direct ?: srcset)
    }

    private fun String.fixQuality(): Int = when {
        contains("2160", true) -> Qualities.P2160.value
        contains("1080", true) -> Qualities.P1080.value
        contains("720", true) -> Qualities.P720.value
        contains("480", true) -> Qualities.P480.value
        contains("360", true) -> Qualities.P360.value
        else -> Qualities.Unknown.value
    }

    private fun String.cleanEscaped(): String {
        return trim()
            .replace("\\/", "/")
            .replace("\\u002F", "/")
            .replace("\\u003A", ":")
            .replace("\\u0026", "&")
            .replace("\\u003D", "=")
            .replace("&amp;", "&")
            .replace("&#038;", "&")
            .replace("&quot;", "\"")
            .replace("\\\"", "\"")
            .trim('"', '\'', ',', ';')
            .trim()
    }

    private fun String.isArchiveDownloadUrl(): Boolean {
        val lower = substringBefore("?").lowercase()
        return lower.endsWith(".zip") ||
            lower.endsWith(".rar") ||
            lower.endsWith(".7z") ||
            lower.endsWith(".tar") ||
            lower.endsWith(".gz") ||
            lower.endsWith(".apk") ||
            lower.contains("-zip") ||
            lower.contains("/zip")
    }

    private fun String.isOuoUrl(): Boolean {
        val host = runCatching { URI(this).host.orEmpty().lowercase() }.getOrDefault("")
        return host == "ouo.io" || host.endsWith(".ouo.io") || host == "ouo.press" || host.endsWith(".ouo.press")
    }

    private fun String.isResolvedShortlinkTarget(): Boolean {
        val lower = lowercase()
        if (isBlank() || isOuoUrl()) return false
        return isPlayableMediaUrl() ||
            lower.contains("acefile.co") ||
            lower.contains("mediafire.com") ||
            lower.contains("pixeldrain.com") ||
            lower.contains("gofile.io") ||
            lower.contains("resharer.org") ||
            lower.contains("terabox") ||
            lower.contains("uptobox.com") ||
            lower.contains("uptostream.com") ||
            lower.contains("hxdrive") ||
            lower.contains("drive.google.com") ||
            lower.contains("docs.google.com") ||
            lower.contains("onedrive.live.com") ||
            lower.contains("1drv.ms") ||
            lower.contains("mega.nz") ||
            lower.contains("mega.co.nz") ||
            lower.contains("yurinime.com")
    }

    private fun String.isLikelyResolvableUrl(): Boolean {
        val lower = lowercase()
        if (isPlayableMediaUrl()) return true
        return lower.contains("download") ||
            lower.contains("worker") ||
            lower.contains("r2.dev") ||
            lower.contains("workers.dev") ||
            lower.contains("acefile.co") ||
            lower.contains("mediafire.com") ||
            lower.contains("pixeldrain.com") ||
            lower.contains("gofile.io") ||
            lower.contains("yurinime.com") ||
            lower.contains("terabox") ||
            lower.contains("uptobox.com") ||
            lower.contains("uptostream.com") ||
            lower.contains("hxdrive") ||
            lower.contains("drive.google.com") ||
            lower.contains("docs.google.com") ||
            lower.contains("onedrive.live.com") ||
            lower.contains("1drv.ms") ||
            lower.contains("mega.nz") ||
            lower.contains("mega.co.nz")
    }

    private fun String.isExternalDownloadHostUrl(): Boolean {
        val lower = lowercase()
        return lower.contains("drive.google.com") ||
            lower.contains("docs.google.com") ||
            lower.contains("uptobox.com") ||
            lower.contains("uptostream.com") ||
            lower.contains("terabox") ||
            lower.contains("hxdrive") ||
            lower.contains("onedrive.live.com") ||
            lower.contains("1drv.ms") ||
            lower.contains("mega.nz") ||
            lower.contains("mega.co.nz")
    }

    private fun String.externalDownloadHostName(): String {
        val lower = lowercase()
        return when {
            lower.contains("drive.google.com") || lower.contains("docs.google.com") -> "Google Drive"
            lower.contains("uptobox.com") || lower.contains("uptostream.com") -> "Uptobox"
            lower.contains("terabox") -> "TeraBox"
            lower.contains("hxdrive") -> "HxDrive"
            lower.contains("onedrive.live.com") || lower.contains("1drv.ms") -> "OneDrive"
            lower.contains("mega.nz") || lower.contains("mega.co.nz") -> "Mega"
            else -> name
        }
    }

    private fun String.isPlayableMediaUrl(): Boolean {
        val lower = lowercase()
        return lower.contains(".m3u8") ||
            lower.contains(".mp4") ||
            lower.contains(".webm") ||
            lower.contains("/api/file/")
    }

    private fun String.isAcefileServicePlayUrl(): Boolean {
        val lower = lowercase().substringBefore("?")
        return lower.contains("acefile.co/service/play/")
    }

    private fun String.isAcefileLandingPageUrl(): Boolean {
        val lower = lowercase().substringBefore("?")
        if (!lower.contains("acefile.co")) return false
        return lower.contains("/f/") ||
            lower.contains("/file/") ||
            lower.contains("/player/") ||
            lower.contains("/files/copy_page/")
    }

    data class EpisodeLink(
        @param:JsonProperty("url") val url: String,
        @param:JsonProperty("quality") val quality: String
    )

    data class PixeldrainList(
        @param:JsonProperty("files") val files: List<PixeldrainFile> = emptyList()
    )

    data class PixeldrainFile(
        @param:JsonProperty("id") val id: String,
        @param:JsonProperty("name") val name: String,
        @param:JsonProperty("mime_type") val mimeType: String = ""
    )
}