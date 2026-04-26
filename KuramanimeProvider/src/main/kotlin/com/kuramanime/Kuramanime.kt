package com.kuramanime

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import kotlin.random.Random

class Kuramanime : MainAPI() {
    override var mainUrl = "https://v17.kuramanime.ink"
    override var name = "Kuramanime"
    override var lang = "id"
    override val hasMainPage = true
    override val hasDownloadSupport = true

    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.OVA,
    )

    override val mainPage = mainPageOf(
        "quick/ongoing?order_by=updated" to "Ongoing Anime",
        "quick/finished?order_by=updated" to "Complete Anime",
        "quick/movie?order_by=updated" to "Movie"
    )

    fun formatServerName(name: String, quality: Int?): String {
        val lower = name.lowercase()
        return when {
            lower.contains("rpm") -> "RPMShare Kurama-VIP"
            lower.contains("dood") -> "DoodStream"
            lower.contains("direct") -> "Kuramanime Direct"
            else -> "Kuramanime"
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val separator = if (request.data.contains("?")) "&" else "?"
        val document = app.get("$mainUrl/${request.data}$separator&page=$page").document
        val home = document.select("div.product__item").mapNotNull { card ->
            card.toSearchResult()
        }
        return newHomePageResponse(
            HomePageList(request.name, home),
            hasNext = document.selectFirst("a.page__link i.fa-angle-right") != null
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/anime?search=$query&order_by=updated").document
        return document.select("div.product__item").mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse {
        val fixedUrl = normalizeAnimeUrl(url)
        val document = app.get(fixedUrl).document

        val title = document.selectFirst("div.anime__details__title h3")
            ?.text()
            ?.trim()
            ?.ifBlank { null }
            ?: throw ErrorLoadingException("Missing title")
        val altTitle = document.selectFirst("div.anime__details__title > span")
            ?.text()
            ?.trim()
            ?.ifBlank { null }
        val poster = document.selectFirst("div.anime__details__pic")
            ?.attr("data-setbg")
            ?.let(::fixUrlNull)
            ?: document.selectFirst("meta[property=og:image]")?.attr("content")

        val rows = document.select("div.anime__details__widget li")
        fun rowValue(label: String): String? {
            return rows.firstOrNull {
                it.selectFirst("span")?.text()?.equals("$label:", true) == true
            }?.select("div.col-9")
                ?.text()
                ?.replace("\\s+".toRegex(), " ")
                ?.trim()
                ?.ifBlank { null }
        }

        val typeText = rowValue("Tipe")
        val statusText = rowValue("Status")
        val year = rowValue("Tayang")
            ?.let { Regex("(19|20)\\d{2}").find(it)?.value?.toIntOrNull() }
            ?: rowValue("Musim")
                ?.let { Regex("(19|20)\\d{2}").find(it)?.value?.toIntOrNull() }
        val plot = document.selectFirst("#synopsisField")
            ?.text()
            ?.trim()
            ?.ifBlank { null }
        val tags = buildList {
            addAll(
                rows.firstOrNull {
                    it.selectFirst("span")?.text()?.equals("Genre:", true) == true
                }?.select("div.col-9 a")
                    ?.map { it.text().trim().trimEnd(',') }
                    .orEmpty()
            )
            addAll(
                rows.firstOrNull {
                    it.selectFirst("span")?.text()?.equals("Tema:", true) == true
                }?.select("div.col-9 a")
                    ?.map { it.text().trim().trimEnd(',') }
                    .orEmpty()
            )
        }.filter { it.isNotBlank() }.distinct()

        val score = rowValue("Skor")
            ?.replace(",", ".")
            ?.toDoubleOrNull()
            ?.let { Score.from10(it) }
        val showStatus = when {
            statusText?.contains("Sedang Tayang", true) == true -> ShowStatus.Ongoing
            statusText.isNullOrBlank() -> null
            else -> ShowStatus.Completed
        }
        val tvType = getType(typeText)
        val episodes = extractEpisodes(fixedUrl, document)
        val trailer = document.selectFirst("iframe[src*=\"youtube.com\"], iframe[src*=\"youtu.be\"]")
            ?.attr("src")

        return if (tvType == TvType.AnimeMovie && episodes.isNotEmpty()) {
            newMovieLoadResponse(title, episodes.first().data, TvType.AnimeMovie, episodes.first().data) {
                posterUrl = poster
                backgroundPosterUrl = poster
                this.plot = plot
                this.year = year
                this.tags = tags
                this.score = score
                addTrailer(trailer)
            }
        } else {
            newAnimeLoadResponse(title, fixedUrl, tvType) {
                posterUrl = poster
                backgroundPosterUrl = poster
                this.plot = plot
                this.year = year
                this.tags = tags
                this.score = score
                this.showStatus = showStatus
                this.engName = altTitle
                addEpisodes(DubStatus.Subbed, episodes)
                addTrailer(trailer)
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val episodeUrl = data.substringBefore("?")
        val referer = episodeUrl.ifBlank { "$mainUrl/" }
        val emitted = linkedSetOf<String>()

        fun extractQuality(text: String): Int? {
            return Regex("""\b(2160|1440|1080|720|576|480|360|240)\b""")
                .find(text)
                ?.groupValues
                ?.getOrNull(1)
                ?.toIntOrNull()
        }

        suspend fun emitLink(
            url: String,
            name: String = "Kuramanime",
            quality: Int? = null,
            type: ExtractorLinkType? = null,
        ) {
            if (url.isBlank() || !emitted.add(url)) return
            val resolvedType = type ?: if (url.contains(".m3u8", true)) {
                ExtractorLinkType.M3U8
            } else {
                ExtractorLinkType.VIDEO
            }

            val finalName = formatServerName(name, null)

            callback.invoke(
                newExtractorLink(
                    source = finalName,
                    name = finalName,
                    url = url,
                    type = resolvedType
                ) {
                    this.quality = quality ?: Qualities.Unknown.value
                    this.referer = referer
                    headers = mapOf(
                        "Referer" to referer,
                        "Origin" to mainUrl,
                    )
                }
            )
        }

        suspend fun extractFromDocument(document: Document, serverName: String = "Kuramanime") {
            document.select("#player[data-hls-src], video#player[data-hls-src], video[data-hls-src], [data-hls-src]")
                .forEach { player ->
                    val hlsUrl = player.attr("abs:data-hls-src")
                        .ifBlank { fixUrlNull(player.attr("data-hls-src")).orEmpty() }
                    emitLink(
                        url = hlsUrl,
                        name = serverName,
                        type = ExtractorLinkType.M3U8
                    )
                }

            document.select("video#player source[src], video source[src]").forEach { source ->
                val videoUrl = source.attr("abs:src").ifBlank { source.attr("src") }
                val quality = source.attr("size").toIntOrNull()
                    ?: extractQuality("${source.id()} ${source.attr("label")} ${source.attr("title")} ${source.attr("res")}")
                emitLink(
                    url = videoUrl,
                    name = serverName,
                    quality = quality
                )
            }

            document.selectFirst("video#player[src], video[src], #player[src]")?.attr("abs:src")
                ?.takeIf { it.isNotBlank() }
                ?.let { videoUrl ->
                    emitLink(videoUrl, serverName)
                }

            document.select("[data-src], [data-url]").forEach { element ->
                listOf("data-src", "data-url").forEach { attr ->
                    val mediaUrl = element.attr("abs:$attr")
                        .ifBlank { fixUrlNull(element.attr(attr)).orEmpty() }
                    if (!mediaUrl.contains(".m3u8", true) && !mediaUrl.contains(".mp4", true)) return@forEach
                    val quality = extractQuality("${element.text()} ${element.className()} ${element.id()} $mediaUrl")
                    emitLink(
                        url = mediaUrl,
                        name = serverName,
                        quality = quality
                    )
                }
            }

            document.select("a[href]").forEach { anchor ->
                val mediaUrl = anchor.attr("abs:href")
                    .ifBlank { fixUrlNull(anchor.attr("href")).orEmpty() }
                if (
                    !mediaUrl.contains(".m3u8", true) &&
                    !mediaUrl.contains(".mp4", true) &&
                    !mediaUrl.contains("my.id", true) &&
                    !mediaUrl.contains("dropbox", true)
                ) {
                    return@forEach
                }
                val quality = extractQuality("${anchor.text()} ${anchor.className()} $mediaUrl")
                emitLink(
                    url = mediaUrl,
                    name = serverName,
                    quality = quality
                )
            }
        }

        suspend fun extractFromText(body: String, serverName: String = "Kuramanime") {
            val normalized = body.replace("\\/", "/")
            Regex("""https?://[^"'\\s<]+(?:\.m3u8|\.mp4)[^"'\\s<]*""")
                .findAll(normalized)
                .map { it.value }
                .distinct()
                .forEach { mediaUrl ->
                    val quality = extractQuality(mediaUrl)
                    emitLink(
                        url = mediaUrl,
                        name = serverName,
                        quality = quality,
                        type = if (mediaUrl.contains(".m3u8", true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    )
                }
        }

        fun mergeCookies(target: MutableMap<String, String>, cookies: Map<String, String>) {
            if (cookies.isEmpty()) return
            target.putAll(cookies)
        }

        fun buildAuthRoute(prefix: String, route: String): String {
            val cleanPrefix = prefix.trim().trim('/')
            val cleanRoute = route.trim().trimStart('/')
            return when {
                cleanPrefix.isBlank() -> "$mainUrl/$cleanRoute"
                cleanRoute.isBlank() -> "$mainUrl/$cleanPrefix"
                else -> "$mainUrl/$cleanPrefix/$cleanRoute"
            }
        }

        fun randomRequestId(length: Int = 6): String {
            val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
            return buildString(length) {
                repeat(length) {
                    append(alphabet[Random.nextInt(alphabet.length)])
                }
            }
        }

        val nativeResolved = runCatching {
            val pageResponse = app.get(episodeUrl, referer = "$mainUrl/")
            val cookieJar = linkedMapOf<String, String>()
            mergeCookies(cookieJar, pageResponse.cookies)

            val document = runCatching { pageResponse.document }.getOrElse {
                Jsoup.parse(pageResponse.text, episodeUrl)
            }

            val keepAliveUrl = document.selectFirst("#keepAliveTokenRoute")?.attr("value")
                ?.ifBlank { null }
                ?: "$mainUrl/misc/token/keep-alive"
            val checkEpisodeUrl = document.selectFirst("#checkEp")?.attr("value")
                ?.ifBlank { null }
                ?: "${episodeUrl.trimEnd('/')}/check-episode"
            val routeScriptName = document.selectFirst("[data-kk]")?.attr("data-kk")
                ?.trim()
                ?.ifBlank { null }
            val serverOptions = document.select("option[value]")
                .mapNotNull { option ->
                    val value = option.attr("value").trim().ifBlank { return@mapNotNull null }
                    val label = option.text()
                        .substringBefore("(")
                        .replace("\\s+".toRegex(), " ")
                        .trim()
                        .ifBlank { value }
                    ServerOption(value, label)
                }
                .distinctBy { it.value.lowercase() }
                .ifEmpty {
                    listOf(ServerOption("kuramadrive", "Kuramadrive"))
                }

            val jsEnv = when {
                !routeScriptName.isNullOrBlank() -> {
                    app.get(
                        "$mainUrl/assets/js/$routeScriptName.js",
                        referer = episodeUrl,
                        cookies = cookieJar,
                    ).also { mergeCookies(cookieJar, it.cookies) }.text
                }
                else -> {
                    val arcSignalUrl = document.select("script[src]")
                        .firstOrNull { it.attr("src").contains("arc-signal", true) }
                        ?.attr("abs:src")
                        ?.ifBlank { null }
                        ?: throw ErrorLoadingException("Missing arc-signal JS")
                    val arcSignalText = app.get(
                        arcSignalUrl,
                        referer = episodeUrl,
                        cookies = cookieJar,
                    ).also { mergeCookies(cookieJar, it.cookies) }.text
                    val parsedScriptName = Regex("f=\"([A-Za-z0-9]+)\"")
                        .find(arcSignalText)
                        ?.groupValues
                        ?.getOrNull(1)
                        ?: throw ErrorLoadingException("Missing daily JS variable route")
                    app.get(
                        "$mainUrl/assets/js/$parsedScriptName.js",
                        referer = episodeUrl,
                        cookies = cookieJar,
                    ).also { mergeCookies(cookieJar, it.cookies) }.text
                }
            }

            val envMap = parseJsEnv(jsEnv)
            val prefixAuth = envMap["MIX_PREFIX_AUTH_ROUTE_PARAM"]
                ?: throw ErrorLoadingException("Missing auth prefix")
            val authRoute = envMap["MIX_AUTH_ROUTE_PARAM"]
                ?: throw ErrorLoadingException("Missing auth route")
            val authKey = envMap["MIX_AUTH_KEY"]
                ?: throw ErrorLoadingException("Missing auth key")
            val authToken = envMap["MIX_AUTH_TOKEN"]
                ?: throw ErrorLoadingException("Missing auth token")
            val pageTokenKey = envMap["MIX_PAGE_TOKEN_KEY"]
                ?: throw ErrorLoadingException("Missing page token key")
            val streamServerKey = envMap["MIX_STREAM_SERVER_KEY"]
                ?: throw ErrorLoadingException("Missing stream server key")

            runCatching {
                app.post(
                    keepAliveUrl,
                    data = emptyMap(),
                    referer = episodeUrl,
                    cookies = cookieJar,
                ).also { mergeCookies(cookieJar, it.cookies) }
            }

            val pageNumber = app.get(
                checkEpisodeUrl,
                referer = episodeUrl,
                cookies = cookieJar,
            ).also { mergeCookies(cookieJar, it.cookies) }
                .text
                .trim()
                .ifBlank { "1" }

            val pageToken = app.get(
                buildAuthRoute(prefixAuth, authRoute),
                headers = mapOf(
                    "X-Fuck-ID" to "$authKey:$authToken",
                    "X-Request-ID" to randomRequestId(),
                    "X-Request-Index" to "0",
                ),
                referer = episodeUrl,
                cookies = cookieJar,
            ).also { mergeCookies(cookieJar, it.cookies) }
                .text
                .trim()
                .ifBlank { throw ErrorLoadingException("Missing page token") }

            val resolvedPage = Regex("""\d+""").find(pageNumber)?.value ?: "1"

            serverOptions.forEach { server ->
                val secureUrl = buildString {
                    append(episodeUrl)
                    append("?")
                    append(pageTokenKey)
                    append("=")
                    append(pageToken)
                    append("&")
                    append(streamServerKey)
                    append("=")
                    append(server.value)
                    append("&page=")
                    append(resolvedPage)
                }

                val secureResponse = app.post(
                    secureUrl,
                    data = mapOf("authorization" to SECURE_AUTHORIZATION),
                    headers = mapOf(
                        "Origin" to mainUrl,
                        "X-Requested-With" to "XMLHttpRequest",
                    ),
                    referer = episodeUrl,
                    cookies = cookieJar,
                ).also { mergeCookies(cookieJar, it.cookies) }

                val secureDocument = runCatching { secureResponse.document }.getOrElse {
                    Jsoup.parse(secureResponse.text, secureUrl)
                }
                val iframeUrl = secureDocument.selectFirst("iframe[src]")?.attr("abs:src")
                    ?.ifBlank { null }

                if (!iframeUrl.isNullOrBlank()) {
                    KuramanimeExtractors.loadKnownExtractor(
                        url = iframeUrl,
                        serverName = server.label,
                        referer = referer,
                        subtitleCallback = subtitleCallback,
                        callback = callback,
                    )
                }

                extractFromDocument(secureDocument, server.label)
                extractFromText(secureResponse.text, server.label)
            }
            emitted.isNotEmpty()
        }.getOrDefault(false)

        if (nativeResolved) {
            return true
        }

        val fallbackResponse = app.get(episodeUrl, referer = "$mainUrl/")
        val fallbackDocument = runCatching { fallbackResponse.document }.getOrElse {
            Jsoup.parse(fallbackResponse.text, episodeUrl)
        }
        extractFromDocument(fallbackDocument)
        extractFromText(fallbackResponse.text)
        return emitted.isNotEmpty()
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val href = selectFirst("h5 a")?.attr("href")
            ?.let(::fixUrl)
            ?.let(::normalizeAnimeUrl)
            ?: return null
        val title = selectFirst("h5 a")?.text()?.trim()?.ifBlank { null } ?: return null
        val poster = selectFirst("div.product__item__pic")?.attr("data-setbg")?.let(::fixUrlNull)
        val typeLabel = select("div.product__item__text ul li").firstOrNull()?.text()?.trim()
        val badge = selectFirst("div.product__item__pic div.ep")?.text()?.replace("\\s+".toRegex(), " ")?.trim()
        val tvType = getType(typeLabel)

        return newAnimeSearchResponse(title, href, tvType) {
            posterUrl = poster
            if (badge?.contains("Ep", true) == true) {
                addSub(Regex("(\\d+)").find(badge)?.groupValues?.getOrNull(1)?.toIntOrNull())
            }
        }
    }

    private suspend fun extractEpisodes(seriesUrl: String, firstDocument: Document): List<Episode> {
        val episodeLinks = linkedMapOf<String, Pair<String, Int?>>()
        val pendingPages = ArrayDeque<String>()
        val visitedPages = linkedSetOf<String>()
        pendingPages.add(seriesUrl)

        fun addEpisodeAnchor(anchor: Element) {
            val href = fixUrl(anchor.attr("href"))
            val episode = Regex("Ep\\s*(\\d+)", RegexOption.IGNORE_CASE)
                .find(anchor.text())
                ?.groupValues
                ?.getOrNull(1)
                ?.toIntOrNull()
                ?: Regex("/episode/(\\d+)").find(href)?.groupValues?.getOrNull(1)?.toIntOrNull()
            if (!episodeLinks.containsKey(href)) {
                episodeLinks[href] = anchor.text().trim() to episode
            }
        }

        fun collectFromDocument(document: Document) {
            val popoverHtml = document.selectFirst("#episodeLists")?.attr("data-content").orEmpty()
            if (popoverHtml.isNotBlank()) {
                val popoverDoc = Jsoup.parseBodyFragment(popoverHtml)
                popoverDoc.select("a[href*=/episode/]").forEach(::addEpisodeAnchor)
                popoverDoc.select("a[href*='?page=']").forEach { anchor ->
                    val pageUrl = fixUrl(anchor.attr("href"))
                    if (!visitedPages.contains(pageUrl) && !pendingPages.contains(pageUrl)) {
                        pendingPages.add(pageUrl)
                    }
                }
            }

            document.select("a.ep-button[href*=/episode/]").forEach(::addEpisodeAnchor)
        }

        while (pendingPages.isNotEmpty() && visitedPages.size < 64) {
            val pageUrl = pendingPages.removeFirst()
            if (!visitedPages.add(pageUrl)) continue
            val document = if (pageUrl == seriesUrl) {
                firstDocument
            } else {
                app.get(pageUrl).document
            }
            collectFromDocument(document)
        }

        return episodeLinks.map { (href, info) ->
            val episodeNumber = info.second
            newEpisode(href) {
                name = info.first.ifBlank { "Episode ${episodeNumber ?: "?"}" }
                episode = episodeNumber
            }
        }.sortedBy { it.episode ?: Int.MAX_VALUE }
    }

    private fun normalizeAnimeUrl(url: String): String {
        val fixed = fixUrl(url)
        return fixed.substringBefore("/episode/").substringBefore("/batch/")
    }

    private fun parseJsEnv(jsBody: String): Map<String, String> {
        return Regex("""([A-Z_]+)\s*:\s*['"]([^'"]+)['"]""")
            .findAll(jsBody)
            .associate { it.groupValues[1] to it.groupValues[2] }
    }

    private fun getType(typeLabel: String?): TvType {
        return when {
            typeLabel.isNullOrBlank() -> TvType.Anime
            typeLabel.contains("movie", true) -> TvType.AnimeMovie
            typeLabel.contains("ova", true) || typeLabel.contains("special", true) -> TvType.OVA
            else -> TvType.Anime
        }
    }

    private companion object {
        const val SECURE_AUTHORIZATION = "qDBDmoKgQIdP6wmFGUCDo3vuVg9FBfV98"
    }

    private data class ServerOption(
        val value: String,
        val label: String,
    )
}
