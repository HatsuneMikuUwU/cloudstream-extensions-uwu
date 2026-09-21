package com.dubbindo

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import com.dubbindo.BuildConfig
import java.net.URLEncoder

class DubbindoProvider : MainAPI() {
    override var mainUrl = "https://www.dubbindo.site"
    override var name = "Dubbindo"
    override val hasMainPage = true
    override var lang = "id"
    override val hasDownloadSupport = true

    override val supportedTypes = setOf(
        TvType.TvSeries,
        TvType.Movie,
        TvType.Cartoon,
        TvType.Anime,
        TvType.AnimeMovie,
    )

    private val USERNAME = BuildConfig.DUBBINDO_USERNAME
    private val PASSWORD = BuildConfig.DUBBINDO_PASSWORD

    private var sessionCookie = ""

    private val baseHeaders get() = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 10; Mobile) AppleWebKit/537.36 Chrome/124.0 Mobile Safari/537.36",
        "Referer"    to "$mainUrl/"
    )

    private val authedHeaders get() = if (sessionCookie.isNotBlank())
        baseHeaders + mapOf("Cookie" to sessionCookie)
    else baseHeaders

    private fun parseCookiePair(header: String): Pair<String, String>? {
        val part = header.split(";").firstOrNull()?.trim() ?: return null
        val eq   = part.indexOf('=')
        if (eq < 0) return null
        return part.substring(0, eq).trim() to part.substring(eq + 1).trim()
    }

    private suspend fun doLogin(): Boolean {
        val getResp     = app.get("$mainUrl/login", headers = baseHeaders)
        val initCookies = getResp.headers
            .filter { it.first.equals("set-cookie", ignoreCase = true) }
            .mapNotNull { parseCookiePair(it.second) }
            .toMap().toMutableMap()

        val phpSessId = initCookies["PHPSESSID"].orEmpty()

        val postResp = app.post(
            "$mainUrl/login",
            data = mapOf(
                "username"        to USERNAME,
                "password"        to PASSWORD,
                "remember_device" to "on"
            ),
            headers = baseHeaders + mapOf(
                "Cookie"       to if (phpSessId.isNotBlank()) "PHPSESSID=$phpSessId" else "",
                "Content-Type" to "application/x-www-form-urlencoded",
                "Origin"       to mainUrl,
                "Referer"      to "$mainUrl/login"
            ),
            allowRedirects = false
        )

        val allCookies = initCookies + postResp.headers
            .filter { it.first.equals("set-cookie", ignoreCase = true) }
            .mapNotNull { parseCookiePair(it.second) }
            .toMap()

        return if (!allCookies["user_id"].isNullOrBlank()) {
            sessionCookie = allCookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
            true
        } else false
    }

    private suspend fun ensureSession() {
        if (sessionCookie.isNotBlank()) return
        doLogin()
    }

    private suspend fun subscribeChannel(document: Document, pageUrl: String): Boolean {
        val channelId = document
            .selectFirst("button.btn-subscribe[data-id]")?.attr("data-id")?.trim()
            ?: document.selectFirst(".subscribe-btn-container button[data-id]")?.attr("data-id")?.trim()
            ?: document.selectFirst("button[onclick*=PT_Subscribe]")
                ?.attr("onclick")
                ?.let { Regex("""PT_Subscribe\((\d+)""").find(it)?.groupValues?.get(1) }
            ?: document.selectFirst("input#profile-id")?.attr("value")?.trim()
            ?: return false

        if (channelId.isBlank()) return false

        val mainSession = document
            .selectFirst("input.main_session")?.attr("value")?.trim()
            .orEmpty()

        val subscribeUrl = if (mainSession.isNotBlank())
            "$mainUrl/aj/subscribe?hash=$mainSession"
        else
            "$mainUrl/aj/subscribe"

        val resp = app.post(
            subscribeUrl,
            data    = mapOf("user_id" to channelId),
            headers = authedHeaders + mapOf(
                "Content-Type"     to "application/x-www-form-urlencoded",
                "X-Requested-With" to "XMLHttpRequest",
                "Referer"          to pageUrl,
                "Origin"           to mainUrl
            )
        )

        if (!resp.isSuccessful) return false

        val muteUrl = if (mainSession.isNotBlank())
            "$mainUrl/aj/user/notify?hash=$mainSession"
        else
            "$mainUrl/aj/user/notify"

        app.post(
            muteUrl,
            data    = mapOf("user_id" to channelId),
            headers = authedHeaders + mapOf(
                "Content-Type"     to "application/x-www-form-urlencoded",
                "X-Requested-With" to "XMLHttpRequest",
                "Referer"          to pageUrl,
                "Origin"           to mainUrl
            )
        )

        return true
    }

    private fun isSubscribeWall(document: Document): Boolean {
        val playerArea = document.selectFirst("div.video-processing, div.video-player")
            ?.text().orEmpty()
        return playerArea.contains("subscribe to watch", ignoreCase = true) ||
               document.select("video#my-video source, video source").isEmpty()
    }
    
    private fun isVideoInQueue(document: Document): Boolean =
        document.selectFirst("div.pt_video_player div.video-processing") != null

    override val mainPage = mainPageOf(
        "$mainUrl/videos/latest"          to "Latest Update",
        "$mainUrl/videos/top"            to "Most Viewed",
        "$mainUrl/videos/trending"       to "Trending",
        "$mainUrl/videos/category/1"     to "Movie",
        "$mainUrl/videos/category/3"     to "TV Series",
        "$mainUrl/videos/category/5"     to "Anime Series",
        "$mainUrl/videos/category/4"     to "Anime Movie",
        "$mainUrl/videos/category/other" to "Other"
    )

    private val cardSelector = "div.video-list, div.video-wrapper"

    private val invisibleRegex = Regex("""[\u2063\u200B\u200C\u200D\uFEFF]""")
    private val dubbingRegex   = Regex("""(?i)\s*[\[(]?\s*dub(?:b(?:ing)?)?\s+indo(?:o*nesia)?\b\s*[\])]?""")
    private val yearRegex      = Regex("""\((\d{4})\)""")

    private fun String.clean(): String = this
        .replace(invisibleRegex, "")
        .replace(Regex("""&(?:amp;)+"""), "&")
        .replace(Regex("""\s+"""), " ")
        .trim()

    private fun String.cleanTitle(): String {
        val base = clean().replace(" | UVideo", "").trim()
        val cleaned = base
            .replace(dubbingRegex, " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
            .trimEnd('-', '.', ' ')
            .trimStart('-', ' ')
        return cleaned.ifBlank { base }
    }

    private fun parseEpisodeInfo(title: String): Pair<Int?, Int?>? {
        Regex("""(?i)\bS(\d{1,2})\s*E(\d{1,4})\b""").find(title)?.let {
            return it.groupValues[1].toIntOrNull() to it.groupValues[2].toIntOrNull()
        }
        val season = Regex("""(?i)\b(?:season|musim)\s*(\d{1,2})\b""").find(title)
            ?.groupValues?.get(1)?.toIntOrNull()
        val episode = Regex("""(?i)\bep(?:isode|s)?\.?\s*\(?\s*(\d{1,4})\b""").find(title)
            ?.groupValues?.get(1)?.toIntOrNull()
            ?: Regex("""\s*-\s*(\d{1,3})(?=\s*(?:[(\[]|$))""").find(title)
                ?.groupValues?.get(1)?.toIntOrNull()
        if (season != null || episode != null) return season to episode
        if (Regex("""(?i)\b(?:eps?|episode)\b""").containsMatchIn(title)) return null to null
        return null
    }

    private fun typeFromCategory(url: String?): TvType? =
        when (url?.trimEnd('/')?.substringAfterLast('/')) {
            "1"  -> TvType.Movie
            "3"  -> TvType.TvSeries
            "4"  -> TvType.AnimeMovie
            "5"  -> TvType.Anime
            else -> null
        }

    private fun isMovieType(type: TvType) = type == TvType.Movie || type == TvType.AnimeMovie

    private fun Element.toVideoResult(typeHint: TvType? = null): SearchResponse? {
        val anchor = selectFirst("div.video-list-image a, div.video-thumb a, div.ra-thumb a, a[href*='/watch/']")
            ?: selectFirst("a[href]")
            ?: return null
        val href = fixUrlNull(anchor.attr("href")) ?: return null

        val rawTitle = selectFirst("h4[title]")?.attr("title")
            ?: selectFirst("div.video-list-title h4, div.video-title h4, div.video-title a")?.text()
            ?: selectFirst("h4")?.text()
            ?: selectFirst("img[alt]")?.attr("alt")
            ?: return null
        val title = rawTitle.cleanTitle()
        if (title.isEmpty()) return null

        val img = selectFirst("img")
        val poster = fixUrlNull(img?.attr("data-src")?.ifBlank { null } ?: img?.attr("src"))

        val type = typeHint ?: if (parseEpisodeInfo(title) != null) TvType.TvSeries else TvType.Movie

        return if (isMovieType(type)) {
            newMovieSearchResponse(title, href, type) {
                posterUrl     = poster
                posterHeaders = mapOf("Referer" to mainUrl)
            }
        } else {
            newTvSeriesSearchResponse(title, href, type) {
                posterUrl     = poster
                posterHeaders = mapOf("Referer" to mainUrl)
            }
        }
    }

    private fun Element.toRelatedResult(): SearchResponse? =
        toVideoResult(typeFromCategory(selectFirst("div.video-category a")?.attr("href")))

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        ensureSession()
        val hint = typeFromCategory(request.data)
        val document = app.get("${request.data}?page_id=$page", headers = authedHeaders).document
        val home = document.select(cardSelector)
            .mapNotNull { it.toVideoResult(hint) }
            .distinctBy { it.url }
        val hasNext = home.isNotEmpty() &&
            document.select("ul.pagination a[href]").any { a ->
                Regex("""page_id=(\d+)""").find(a.attr("href"))
                    ?.groupValues?.get(1)?.toIntOrNull()
                    ?.let { it > page } == true
            }
        return newHomePageResponse(
            list = HomePageList(name = request.name, list = home, isHorizontalImages = true),
            hasNext = hasNext
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        ensureSession()
        val keyword = URLEncoder.encode(query, "UTF-8")
        val results = mutableListOf<SearchResponse>()
        val seen = mutableSetOf<String>()
        for (i in 1..10) {
            val items = app.get(
                "$mainUrl/search?keyword=$keyword&page_id=$i",
                headers = authedHeaders
            ).document.select(cardSelector).mapNotNull { it.toVideoResult() }
            val fresh = items.filter { seen.add(it.url) }
            if (fresh.isEmpty()) break
            results.addAll(fresh)
        }
        return results
    }

    private val qualityInNameRegex = Regex("""(?i)(?<![0-9])(2160|1440|1080|720|576|480|360|240)p""")

    private fun resolveRes(src: String, el: Element): String {
        qualityInNameRegex.find(src)?.groupValues?.get(1)?.let { return it }

        val label = listOf(el.attr("data-quality"), el.attr("label"), el.attr("title"))
            .firstOrNull { it.isNotBlank() }.orEmpty().trim()
        Regex("""(\d{3,4})""").find(label)?.groupValues?.get(1)?.let { return it }
        when (label.lowercase()) {
            "4k", "uhd"        -> return "2160"
            "fhd", "full hd"   -> return "1080"
            "hd"               -> return "720"
            "sd"               -> return "480"
        }
        return el.attr("res").replace(Regex("[^0-9]"), "")
    }

    private fun parseVideoSources(doc: Document): List<Video> =
        doc.select("video#my-video source, video source").mapNotNull { el ->
            val src = el.attr("src").trim().ifEmpty { return@mapNotNull null }
            Video(
                src  = src,
                res  = resolveRes(src, el),
                type = el.attr("type").ifBlank { "video/mp4" }
            )
        }.distinctBy { it.src }

    private suspend fun fetchVideoSources(url: String, initialDoc: Document? = null): List<Video> {
        var doc    = initialDoc ?: app.get(url, headers = authedHeaders).document
        var videos = parseVideoSources(doc)

        if (videos.isNotEmpty()) return videos

        if (isSubscribeWall(doc)) {
            subscribeChannel(doc, url)
            doc    = app.get(url, headers = authedHeaders).document
            videos = parseVideoSources(doc)
        }

        if (videos.isNotEmpty()) return videos

        sessionCookie = ""
        if (doLogin()) {
            doc    = app.get(url, headers = authedHeaders).document
            videos = parseVideoSources(doc)

            if (videos.isEmpty() && isSubscribeWall(doc)) {
                subscribeChannel(doc, url)
                doc    = app.get(url, headers = authedHeaders).document
                videos = parseVideoSources(doc)
            }
        }

        return videos
    }

    private fun parseTags(doc: Document, title: String): List<String> {
        fun key(s: String) = s.lowercase().filter { it.isLetterOrDigit() }
        val ignore = setOf("bahasa indonesia", "indonesia", "dubbing indonesia", "dub indonesia", "dubbing")
        val keywords = doc.selectFirst("meta[name=keywords]")?.attr("content").orEmpty()
            .split(",")
            .map { it.clean() }
            .filter { it.isNotBlank() && it.lowercase() !in ignore }
        val titleKey = key(title)
        val first = keywords.firstOrNull()?.let { key(it) }
        val dropFirst = first != null && first.isNotEmpty() &&
            (titleKey.contains(first) || first.contains(titleKey))
        return (if (dropFirst) keywords.drop(1) else keywords).distinct()
    }

    private fun parsePlot(doc: Document, selector: String): String? {
        val el = doc.selectFirst(selector)?.clone() ?: return null
        el.select("a").remove()
        return el.text().clean()
            .replace(Regex("""(?i)\s*download\s*:?\s*$"""), "")
            .trim()
            .ifBlank { null }
    }

    override suspend fun load(url: String): LoadResponse? {
        ensureSession()

        val document = app.get(url, headers = authedHeaders).document

        val title = (document.selectFirst("h1[itemprop=title]")?.text()
            ?: document.selectFirst("meta[name=title]")?.attr("content")
            ?: document.selectFirst("meta[property=og:title]")?.attr("content")
            ?: document.title()).cleanTitle()
        if (title.isEmpty()) return null

        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")
            ?: document.selectFirst("video#my-video")?.attr("poster")
        val tags   = parseTags(document, title)
        val year   = yearRegex.find(title)?.groupValues?.get(1)?.toIntOrNull()
        val recommendations = document.select("div.related-video-wrapper")
            .mapNotNull { it.toRelatedResult() }
            .distinctBy { it.url }

        if (url.contains("/articles/read/")) {
            val description = parsePlot(document, "div.read-article-description article")
            val videoLinks  = document.select("div.read-article-text a")
                .map { it.attr("href") }.filter { it.isNotBlank() }
            return newMovieLoadResponse(title, url, TvType.Movie, videoLinks.toJson()) {
                posterUrl = poster; plot = description
                this.tags = tags; this.recommendations = recommendations
            }
        }

        val description = parsePlot(document, "div.watch-video-description p")

        if (isVideoInQueue(document)) {
            return newMovieLoadResponse(title, url, TvType.Movie, "[]") {
                posterUrl = poster
                plot = "⏳ Video ini sedang dalam antrian pemrosesan.\nHarap buka kembali dalam beberapa menit atau jam."
                this.tags = tags
            }
        }

        val videosJson = fetchVideoSources(url, document).toJson()

        val episodeInfo = parseEpisodeInfo(title)
        return if (episodeInfo != null) {
            val (season, episode) = episodeInfo
            newTvSeriesLoadResponse(
                title, url, TvType.TvSeries,
                listOf(
                    newEpisode(videosJson) {
                        this.name    = title
                        this.season  = season
                        this.episode = episode
                    }
                )
            ) {
                this.posterUrl = poster; this.year = year; this.plot = description
                this.tags = tags; this.recommendations = recommendations
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, videosJson) {
                this.posterUrl = poster; this.year = year; this.plot = description
                this.tags = tags; this.recommendations = recommendations
            }
        }
    }

    private fun isPresignedS3(url: String) =
        url.contains("X-Amz-Signature", ignoreCase = true) ||
        url.contains("X-Amz-Credential", ignoreCase = true) ||
        url.contains("wasabisys.com", ignoreCase = true) ||
        url.contains("amazonaws.com", ignoreCase = true)

    private suspend fun resolveVideoUrl(src: String): String {
        if (isPresignedS3(src)) return src
        if (!src.contains("s3.dubbindo.my.id")) return src

        return try {
            val resp = app.get(
                src,
                headers  = authedHeaders,
                allowRedirects = false
            )
            val location = resp.headers
                .firstOrNull { it.first.equals("location", ignoreCase = true) }
                ?.second
            if (!location.isNullOrBlank()) location else src
        } catch (e: Exception) {
            src
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val streamHeaders = authedHeaders + mapOf("Referer" to mainUrl)

        val videos = tryParseJson<List<Video>>(data)
        if (videos != null) {
            videos.forEach { video ->
                val rawSrc = video.src ?: return@forEach
                val src = resolveVideoUrl(rawSrc)

                if (src.endsWith(".m3u8") || video.type.orEmpty().startsWith("video/")
                    || video.type == "application/x-mpegURL") {
                    callback.invoke(
                        newExtractorLink(name, name, src, INFER_TYPE) {
                            quality = video.res?.toIntOrNull() ?: Qualities.Unknown.value
                            headers = if (isPresignedS3(src)) emptyMap() else streamHeaders
                        }
                    )
                } else {
                    loadExtractor(src, mainUrl, subtitleCallback, callback)
                }
            }
            return videos.isNotEmpty()
        }

        val urls = tryParseJson<List<String>>(data)
        if (urls != null) {
            urls.forEach { if (it.isNotBlank()) loadExtractor(it, mainUrl, subtitleCallback, callback) }
            return urls.isNotEmpty()
        }

        return false
    }

    data class Video(
        val src: String? = null,
        val res: String? = null,
        val type: String? = null,
    )
}
