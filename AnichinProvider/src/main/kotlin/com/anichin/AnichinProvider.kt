package com.anichin

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import kotlinx.coroutines.CancellationException
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import com.lagradost.cloudstream3.toNewSearchResponseList

class AnichinProvider : MainAPI() {
    companion object {
        var context: android.content.Context? = null

        private const val MAX_TOP_LEVEL_CANDIDATES = 12
        private const val MAX_DOWNLOAD_CANDIDATES = 6
        private const val MAX_NESTED_CANDIDATES = 10
        private const val MAX_RESOLVE_DEPTH = 1
        private const val MAX_VISITED_LINKS = 28
        private const val MAX_NESTED_TEXT_BYTES = 1_000_000L
    }

    private val cloudflareKiller by lazy { CloudflareKiller() }

    override var mainUrl = "https://anichin.moe"
    override var name = "Anichin"
    override val hasMainPage = true
    override var lang = "id"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.Anime)

    override val mainPage = mainPageOf(
        "anime/?order=update" to "Latest Update",
        "anime/?status=ongoing&order=update" to "Series Ongoing",
        "anime/?status=completed&order=update" to "Series Completed",
        "anime/?status=hiatus&order=update" to "Series Drop/Hiatus",
        "anime/?type=movie&order=update" to "Movie"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get("${mainUrl}/${request.data}&page=$page", interceptor = cloudflareKiller).document
        val home = document.select("div.listupd > article").mapNotNull { it.toSearchResult() }

        return newHomePageResponse(
            list = HomePageList(
                name = request.name,
                list = home,
                isHorizontalImages = false,
            ),
            hasNext = true,
        )
    }

    private fun Element.toSearchResult(): SearchResponse {
        val title = this.select("div.bsx > a").attr("title").trim()
        val href = fixUrl(this.select("div.bsx > a").attr("href"))
        val posterUrl = fixUrlNull(this.select("div.bsx > a img").attr("src"))

        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String, page: Int): SearchResponseList? {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")

        val url = if (page <= 1) {
            "${mainUrl}/?s=$encodedQuery"
        } else {
            "${mainUrl}/page/$page/?s=$encodedQuery"
        }

        val document = app.get(url, interceptor = cloudflareKiller).document

        val results = document
            .select("div.listupd > article")
            .mapNotNull { it.toSearchResult() }

        val hasNext = document.selectFirst(
            "a.next, a.next.page-numbers, .nav-links a.next, .pagination .next"
        ) != null

        return results.toNewSearchResponseList(
            hasNext = hasNext
        )
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(fixUrl(url), interceptor = cloudflareKiller).document
        val title = document.selectFirst("h1.entry-title")?.text()?.trim().orEmpty()
        var poster = document.select("div.ime > img").attr("src")
        val description = document.selectFirst("div.entry-content")?.text()?.trim()
        val type = document.selectFirst(".spe")?.text().orEmpty()

        val year = Regex("\\b(19|20)\\d{2}\\b")
            .find(document.text())
            ?.value
            ?.toIntOrNull()

        val tags = document.select(".genre a, .genres a, .genxed a")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }

        val tvType = if (type.contains("Movie", true)) TvType.Movie else TvType.TvSeries

        if (poster.isEmpty()) {
            poster = document.selectFirst("meta[property=og:image]")?.attr("content").orEmpty()
        }

        return if (tvType == TvType.TvSeries) {
            val episodes = document.select(".eplister li").mapNotNull { ep ->
                val link = fixUrl(ep.selectFirst("a")?.attr("href").orEmpty()).takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null
                val epTitle = ep.selectFirst(".epl-title")?.text()?.trim().orEmpty()
                val epSub = ep.selectFirst(".epl-sub span")?.text()?.trim().orEmpty()
                val epDate = ep.selectFirst(".epl-date")?.text()?.trim().orEmpty()
                val cleanTitle = epTitle
                    .replace(Regex("Episode\\s*\\d+\\s*Subtitle Indonesia", RegexOption.IGNORE_CASE), "")
                    .replace("Subtitle Indonesia", "")
                    .trim()
                val name = "— $cleanTitle $epSub Indonesia".trim()
                val desc = if (epDate.isNotEmpty()) "Rilis: $epDate" else null

                newEpisode(link) {
                    this.name = name
                    this.posterUrl = fixUrlNull(poster)
                    this.description = desc
                }
            }.reversed()

            newTvSeriesLoadResponse(title, url, TvType.Anime, episodes) {
                this.posterUrl = fixUrlNull(poster)
                this.plot = description
                this.year = year
                this.tags = tags
            }
        } else {
            val movieHref = document.selectFirst(".eplister li > a")?.attr("href")?.let { fixUrl(it) } ?: url

            newMovieLoadResponse(title, movieHref, TvType.Movie, movieHref) {
                this.posterUrl = fixUrlNull(poster)
                this.plot = description
                this.year = year
                this.tags = tags
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val episodeUrl = fixUrl(data)
        val document = app.get(episodeUrl, referer = mainUrl, interceptor = cloudflareKiller).document
        val candidates = linkedSetOf<Pair<String, String>>()
        val visited = linkedSetOf<String>()
        val emitted = linkedSetOf<String>()

        fun addCandidate(value: String?, label: String = "Anichin") {
            if (value.isNullOrBlank()) return
            decodeServerUrls(value).forEach { candidate ->
                candidates.add(candidate to label)
            }
        }

        document.select("#pembed iframe[src], .player-embed iframe[src], .video-content iframe[src], iframe[src], embed[src], source[src], video[src]").forEach { element ->
            addCandidate(element.attr("abs:src").ifBlank { element.attr("src") }, "Anichin")
        }

        document.select(".mobius option[value], select.mirror option[value], select option[value], option[value]").forEach { server ->
            val label = server.text().trim().ifBlank { "Anichin" }
            addCandidate(server.attr("value"), label)
        }

        document.select("[data-src], [data-lazy-src], [data-url], [data-link], [data-video], [data-embed], [data-player], [data-file]").forEach { element ->
            val label = element.text().trim().ifBlank { "Anichin" }
            addCandidate(element.attr("data-src"), label)
            addCandidate(element.attr("data-lazy-src"), label)
            addCandidate(element.attr("data-url"), label)
            addCandidate(element.attr("data-link"), label)
            addCandidate(element.attr("data-video"), label)
            addCandidate(element.attr("data-embed"), label)
            addCandidate(element.attr("data-player"), label)
            addCandidate(element.attr("data-file"), label)
        }

        extractKnownVideoUrls(document.html()).forEach { candidates.add(it to "Anichin") }

        val countedCallback: (ExtractorLink) -> Unit = { link ->
            if (emitted.add(link.url)) callback(link)
        }

        val topLevelCandidates = candidates
            .mapNotNull { (url, label) -> normalizeAnyUrl(url, episodeUrl)?.let { it to label } }
            .filterNot { (url, _) -> isNoiseFrame(url) }
            .filter { (url, label) -> isPrimaryPlaybackHost(url, label) }
            .distinctBy { it.first }
            .sortedWith(
                compareBy<Pair<String, String>> { candidatePriority(it.first, it.second) }
                    .thenBy { it.second.lowercase() }
                    .thenBy { it.first }
            )
            .take(MAX_TOP_LEVEL_CANDIDATES)

        for ((url, label) in topLevelCandidates) {
            try {
                val before = emitted.size
                resolveVideoCandidate(
                    url = url,
                    label = label,
                    referer = episodeUrl,
                    visited = visited,
                    subtitleCallback = subtitleCallback,
                    callback = countedCallback,
                )
                if (emitted.size == before) {
                    Log.d("Anichin", "Server produced no links, trying next: $label -> $url")
                }
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                Log.w("Anichin", "Failed resolving server, trying next: $label -> $url", error)
            }
        }

        if (emitted.isEmpty()) {
            val downloadCandidates = document.select(".soraddlx a[href], .dlbox a[href], .download a[href], .entry-content a[href], a[href*='mirrored.to'], a[href*='apk.miuiku.com']")
                .mapNotNull { element ->
                    element.attr("abs:href").ifBlank { element.attr("href") }
                        .takeIf { it.isNotBlank() }
                        ?.let { normalizeAnyUrl(it, episodeUrl) }
                }
                .filterNot { isNoiseFrame(it) }
                .filter { isPrimaryPlaybackHost(it, "Download") }
                .distinct()
                .sortedBy { candidatePriority(it, "Download") }
                .take(MAX_DOWNLOAD_CANDIDATES)

            for (url in downloadCandidates) {
                try {
                    resolveVideoCandidate(
                        url = url,
                        label = "Download",
                        referer = episodeUrl,
                        visited = visited,
                        subtitleCallback = subtitleCallback,
                        callback = countedCallback,
                    )
                } catch (error: Throwable) {
                    if (error is CancellationException) throw error
                    Log.w("Anichin", "Failed resolving download: $url", error)
                }
            }
        }

        return emitted.isNotEmpty()
    }

    private suspend fun resolveVideoCandidate(
        url: String,
        label: String,
        referer: String,
        visited: MutableSet<String>,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        depth: Int = 0,
    ) {
        val fixed = normalizeAnyUrl(url, referer)
            ?.replace(".txt", ".m3u8")
            ?: return

        if (visited.size >= MAX_VISITED_LINKS || !visited.add(fixed) || isNoiseFrame(fixed)) return

        val labelQuality = getQualityFromName(label)
        val urlQuality = getQualityFromName(fixed)
        val directQuality = when {
            labelQuality != Qualities.Unknown.value -> labelQuality
            urlQuality != Qualities.Unknown.value -> urlQuality
            else -> qualityFromUrl(fixed)
        }

        when {
            fixed.contains(".m3u8", true) -> {
                M3u8Helper.generateM3u8(
                    source = label.ifBlank { "Anichin" },
                    streamUrl = fixed,
                    referer = referer,
                    headers = mapOf("User-Agent" to USER_AGENT, "Referer" to referer),
                ).forEach(callback)
                return
            }
            fixed.contains(".mp4", true) || fixed.contains(".webm", true) -> {
                callback(
                    newExtractorLink(
                        source = label.ifBlank { "Anichin" },
                        name = label.ifBlank { "Anichin" },
                        url = fixed,
                        type = ExtractorLinkType.VIDEO,
                    ) {
                        this.referer = referer
                        this.quality = directQuality
                        this.headers = mapOf("User-Agent" to USER_AGENT, "Referer" to referer)
                    }
                )
                return
            }
        }

        val dailyUrl = normalizeDailymotionUrl(fixed)
        if (dailyUrl != null) {
            val loaded = try {
                loadExtractor(dailyUrl, referer, subtitleCallback, callback)
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                false
            }
            if (loaded) return
        }

        if (shouldUseExtractor(fixed)) {
            try {
                loadExtractor(fixed, referer, subtitleCallback, callback)
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
            }
        }

        if (depth >= MAX_RESOLVE_DEPTH || !shouldReadNestedPage(fixed)) return

        val response = runCatching {
            app.get(
                fixed,
                referer = referer,
                headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Referer" to referer,
                    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                )
            )
        }.getOrNull() ?: return

        val contentType = response.headers["Content-Type"].orEmpty().lowercase()
        val contentLength = response.headers["Content-Length"]?.toLongOrNull()
        if (shouldSkipBodyRead(contentType, contentLength)) return

        val body = runCatching { response.text.cleanEscaped() }.getOrNull() ?: return
        val nested = linkedSetOf<String>()
        nested.addAll(extractKnownVideoUrls(body))

        val nestedDocument = Jsoup.parse(body, fixed)
        nestedDocument.select("iframe[src], iframe[data-src], embed[src], source[src], video[src], a[href]").forEach { element ->
            element.attr("data-src")
                .ifBlank { element.attr("abs:src") }
                .ifBlank { element.attr("src") }
                .ifBlank { element.attr("abs:href") }
                .ifBlank { element.attr("href") }
                .takeIf { it.isNotBlank() }
                ?.let { normalizeAnyUrl(it, fixed) }
                ?.let { nested.add(it) }
        }

        val nestedCandidates = nested.asSequence()
            .filterNot { isNoiseFrame(it) }
            .filter { isPrimaryPlaybackHost(it, label) }
            .distinct()
            .take(MAX_NESTED_CANDIDATES)
            .toList()

        for (nestedUrl in nestedCandidates) {
            resolveVideoCandidate(
                url = nestedUrl,
                label = label,
                referer = fixed,
                visited = visited,
                subtitleCallback = subtitleCallback,
                callback = callback,
                depth = depth + 1,
            )
        }
    }

    private fun decodeServerUrls(value: String): List<String> {
        val decodedValues = linkedSetOf<String>()
        val cleanValue = value.trim().htmlUnescape().cleanEscaped()
        if (cleanValue.isBlank()) return emptyList()

        decodedValues.add(cleanValue)
        runCatching { URLDecoder.decode(cleanValue, "UTF-8") }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?.let { decodedValues.add(it.htmlUnescape().cleanEscaped()) }

        decodeBase64Value(cleanValue)
            ?.takeIf { it.isNotBlank() }
            ?.let { decodedValues.add(it.htmlUnescape().cleanEscaped()) }

        val results = linkedSetOf<String>()
        decodedValues.forEach { decoded ->
            val parsed = Jsoup.parse(decoded)
            parsed.select("iframe[src], iframe[data-src], embed[src], source[src], video[src], a[href]").forEach { element ->
                element.attr("data-src")
                    .ifBlank { element.attr("src") }
                    .ifBlank { element.attr("href") }
                    .takeIf { it.isNotBlank() }
                    ?.let(results::add)
            }
            extractKnownVideoUrls(decoded).forEach(results::add)
            if (results.isEmpty()) results.add(decoded)
        }

        return results.toList()
    }

    private fun decodeBase64Value(value: String): String? {
        val normalized = value.trim()
        if (normalized.length < 8) return null

        return runCatching { base64Decode(normalized) }.getOrNull()
            ?: runCatching {
                val fixed = normalized
                    .replace('-', '+')
                    .replace('_', '/')
                    .let { raw ->
                        val padding = (4 - raw.length % 4) % 4
                        raw + "=".repeat(padding)
                    }

                String(android.util.Base64.decode(fixed, android.util.Base64.DEFAULT))
            }.getOrNull()
    }

    private fun extractKnownVideoUrls(rawText: String): List<String> {
        if (rawText.isBlank()) return emptyList()

        val decodedText = rawText.cleanEscaped()
        val urls = linkedSetOf<String>()

        Jsoup.parse(decodedText).select("iframe[src], iframe[data-src], embed[src], source[src], video[src], a[href]").forEach { element ->
            element.attr("data-src")
                .ifBlank { element.attr("src") }
                .ifBlank { element.attr("href") }
                .takeIf { it.isNotBlank() }
                ?.let { normalizeKnownVideoUrl(it) }
                ?.let { urls.add(it) }
        }

        Regex("""https?:\\?/\\?/[^\"'<>\\\s]+""", RegexOption.IGNORE_CASE)
            .findAll(decodedText)
            .mapNotNull { normalizeKnownVideoUrl(it.value) }
            .forEach { urls.add(it) }

        Regex("""(?i)(?:file|url|src|embed|video|videoUrl|video_url|hls|hlsUrl|embedUrl|embed_url)\s*[:=]\s*["']([^"']+)["']""")
            .findAll(decodedText)
            .mapNotNull { normalizeKnownVideoUrl(it.groupValues[1]) }
            .forEach { urls.add(it) }

        return urls.toList()
    }

    private fun normalizeKnownVideoUrl(url: String): String? {
        val absolute = normalizeAnyUrl(url, mainUrl) ?: return null
        return absolute
            .takeIf { candidate -> supportedHosts.any { candidate.contains(it, ignoreCase = true) } || isDirectMediaUrl(candidate) }
            ?.let { fixUrl(it) }
    }

    private fun normalizeAnyUrl(url: String, baseUrl: String): String? {
        val fixed = url.cleanEscaped().trim('"', '\'', ' ', '\n', '\r', '\t')
        if (fixed.isBlank()) return null

        return when {
            fixed.startsWith("//") -> "https:$fixed"
            fixed.startsWith("http://", true) || fixed.startsWith("https://", true) -> fixed
            fixed.startsWith("/") -> {
                val origin = Regex("""^https?://[^/]+""").find(baseUrl)?.value ?: mainUrl
                origin.trimEnd('/') + fixed
            }
            else -> runCatching { URI(baseUrl).resolve(fixed).toString() }.getOrNull()
        }
    }

    private fun normalizeDailymotionUrl(url: String): String? {
        if (!url.contains("dailymotion.com", true) && !url.contains("dai.ly", true)) return null

        val decoded = runCatching { URLDecoder.decode(url, "UTF-8") }.getOrDefault(url)
        val videoId = listOf(
            Regex("""(?i)[?&]video=([A-Za-z0-9]+)"""),
            Regex("""(?i)dailymotion\.com/(?:embed/)?video/([A-Za-z0-9]+)"""),
            Regex("""(?i)dai\.ly/([A-Za-z0-9]+)"""),
        ).firstNotNullOfOrNull { regex -> regex.find(decoded)?.groupValues?.getOrNull(1) }
            ?: return url

        return if (decoded.contains("geo.dailymotion.com", true)) {
            "https://geo.dailymotion.com/player/xid0t.html?video=$videoId"
        } else {
            "https://www.dailymotion.com/embed/video/$videoId"
        }
    }

    private fun shouldUseExtractor(url: String): Boolean {
        return isPrimaryPlaybackHost(url, "")
    }

    private fun isPrimaryPlaybackHost(url: String, label: String): Boolean {
        val value = "$label $url".lowercase()
        return value.contains("dailymotion.com") ||
            value.contains("geo.dailymotion.com") ||
            value.contains("dai.ly") ||
            value.contains("ok.ru") ||
            value.contains("odnoklassniki.ru") ||
            value.contains("rumble.com") ||
            value.contains("vidguard") ||
            value.contains("vidhide") ||
            value.contains("streamruby") ||
            value.contains("streamruby.com")
    }

    private fun candidatePriority(url: String, label: String): Int {
        val value = "$label $url".lowercase()
        return when {
            value.contains("dailymotion.com") || value.contains("geo.dailymotion.com") || value.contains("dai.ly") -> 0
            value.contains("ok.ru") || value.contains("odnoklassniki.ru") -> 1
            value.contains("streamruby") -> 2
            value.contains("vidguard") || value.contains("vidhide") -> 3
            value.contains("rumble.com") -> 4
            else -> 99
        }
    }


    private fun shouldReadNestedPage(url: String): Boolean {
        val value = url.lowercase()
        return value.contains("/embed", true) ||
            value.contains("/player", true) ||
            value.contains("/video", true) ||
            value.contains("/v/", true) ||
            value.contains("mirrored.to", true) ||
            value.contains("apk.miuiku.com", true)
    }

    private fun shouldSkipBodyRead(contentType: String, contentLength: Long?): Boolean {
        return contentType.startsWith("video/") ||
            contentType.startsWith("audio/") ||
            contentType.contains("octet-stream") ||
            contentType.contains("application/vnd.apple.mpegurl") ||
            contentType.contains("application/x-mpegurl") ||
            contentType.contains("mpegurl") ||
            (contentLength != null && contentLength > MAX_NESTED_TEXT_BYTES)
    }

    private fun isNoiseFrame(url: String): Boolean {
        val value = url.lowercase()
        return value.isBlank() ||
            value.startsWith("#") ||
            value.startsWith("javascript") ||
            value.contains("facebook.com") ||
            value.contains("twitter.com") ||
            value.contains("telegram") ||
            value.contains("whatsapp") ||
            value.contains("youtube.com") ||
            value.contains("youtu.be") ||
            value.contains("trailer") ||
            value.contains("banner") ||
            value.contains("doubleclick") ||
            value.contains("googlesyndication") ||
            value.contains("analytics") ||
            value.contains("tracking") ||
            value.contains("popads")
    }

    private fun isDirectMediaUrl(url: String): Boolean {
        return url.contains(".m3u8", true) ||
            url.contains(".mp4", true) ||
            url.contains(".webm", true)
    }

    private fun qualityFromUrl(url: String): Int {
        return when {
            url.contains("2160", true) || url.contains("4k", true) -> Qualities.P2160.value
            url.contains("1080", true) -> Qualities.P1080.value
            url.contains("720", true) -> Qualities.P720.value
            url.contains("480", true) -> Qualities.P480.value
            url.contains("360", true) -> Qualities.P360.value
            else -> Qualities.Unknown.value
        }
    }

    private fun String.cleanEscaped(): String {
        return this
            .htmlUnescape()
            .replace("\\/", "/")
            .replace("\\u002F", "/")
            .replace("\\u003A", ":")
            .replace("\\u003D", "=")
            .replace("\\u0026", "&")
            .replace("\\\"", "\"")
            .trim()
    }

    private val supportedHosts = listOf(
        "dailymotion.com",
        "geo.dailymotion.com",
        "dai.ly",
        "ok.ru",
        "odnoklassniki.ru",
    )


    private fun String.htmlUnescape(): String {
        return this
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#039;", "'")
            .replace("&apos;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
    }
}