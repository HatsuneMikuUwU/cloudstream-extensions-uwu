package com.winbu

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.nicehttp.NiceResponse
import kotlinx.coroutines.runBlocking
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder

class WinbuProvider : MainAPI() {
    override var mainUrl = "https://winbu.org"
    override var name = "Winbu"
    override val hasMainPage = true
    override var lang = "id"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.OVA,
        TvType.TvSeries,
        TvType.Movie
    )

    companion object {
        fun getType(t: String?): TvType {
            return when {
                t.isNullOrBlank() -> TvType.Anime
                t.contains("Movie", true) || t.contains("Film", true) -> TvType.AnimeMovie
                t.contains("OVA", true) || t.contains("Special", true) -> TvType.OVA
                t.contains("TV Show", true) || t.contains("Series", true) -> TvType.TvSeries
                else -> TvType.Anime
            }
        }

        fun getStatus(t: String?): ShowStatus {
            return when {
                t.isNullOrBlank() -> ShowStatus.Completed
                t.contains("Ongoing", true) -> ShowStatus.Ongoing
                else -> ShowStatus.Completed
            }
        }

        fun parseQuality(text: String?): Int {
            if (text.isNullOrBlank()) return Qualities.Unknown.value
            val q = getQualityFromName(text)
            if (q != Qualities.Unknown.value) return q
            val num = Regex("""(\d{3,4})p?""", RegexOption.IGNORE_CASE).find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()
            return when (num) {
                360 -> Qualities.P360.value
                480 -> Qualities.P480.value
                720 -> Qualities.P720.value
                1080 -> Qualities.P1080.value
                1440 -> Qualities.P1440.value
                2160, 4 -> Qualities.P2160.value
                else -> Qualities.Unknown.value
            }
        }
    }

    private suspend fun request(url: String, ref: String? = null): NiceResponse {
        return app.get(
            url,
            headers = mapOf(
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
                "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Mobile Safari/537.36",
            ),
            referer = ref ?: mainUrl
        )
    }

    override val mainPage = mainPageOf(
        "$mainUrl/daftar-anime-2/?status=Currently+Airing&order=update" to "Ongoing",
        "$mainUrl/daftar-anime-2/?status=Finished+Airing&order=update" to "Completed",
        "$mainUrl/daftar-anime-2/?title=&status=&type=Film&order=update" to "Movie Series"
    )

    private val pageHistory = java.util.concurrent.ConcurrentHashMap<String, MutableMap<Int, Set<String>>>()

    private fun buildPageUrl(data: String, page: Int): String {
        val raw = data.trim()
        val (pathPart, queryPart) = if ('?' in raw) {
            val idx = raw.indexOf('?')
            raw.substring(0, idx).trimEnd('/') to raw.substring(idx)
        } else {
            raw.trimEnd('/') to ""
        }

        val cleanPath = pathPart
            .replace(Regex("""/page/\d+$"""), "")
            .replace(Regex("""/page$"""), "")
            .trimEnd('/')

        return if (page <= 1) {
            "$cleanPath/$queryPart"
        } else {
            "$cleanPath/page/$page/$queryPart"
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = buildPageUrl(request.data, page)

        val document = request(url).document
        val home = document.select("div.ml-item, div.ml-item-anime")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }

        val servedPage = document.selectFirst("ul.pagination li.active")
            ?.text()?.trim()?.toIntOrNull()
        if (page > 1 && servedPage != null && servedPage != page) {
            return newHomePageResponse(request.name, emptyList(), false)
        }

        val history = pageHistory.getOrPut(request.data) { java.util.concurrent.ConcurrentHashMap() }
        if (page <= 1) history.clear()
        val previous = history.filterKeys { it < page }.values.flatten().toSet()
        if (page > 1 && home.isNotEmpty() && home.all { it.url in previous }) {
            return newHomePageResponse(request.name, emptyList(), false)
        }
        history[page] = home.map { it.url }.toSet()

        val hasNext = home.isNotEmpty() && (
            document.select("ul.pagination a[href]").any { a ->
                val href = a.attr("href")
                Regex("""/page/(\d+)""").find(href)
                    ?.groupValues?.getOrNull(1)?.toIntOrNull()
                    ?.let { it > page } == true
            } || document.selectFirst("link[rel=next]") != null
        )

        return newHomePageResponse(request.name, home, hasNext)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val a = this.selectFirst("a.ml-mask") ?: return null
        val href = fixUrl(a.attr("href"))
        val title = a.selectFirst(".judul")?.text()?.trim()
            ?: a.attr("title")?.trim()
            ?: return null

        val posterUrl = fixUrlNull(this.selectFirst("img.mli-thumb")?.attr("src"))
        val epText = this.selectFirst(".mli-episode")?.text()
            ?: this.selectFirst("i.info-hidden")?.attr("data-episode")
        val epNum = Regex("(?i)(?:Episode\\s*)?(\\d+)").find(epText.orEmpty())?.groupValues?.getOrNull(1)?.toIntOrNull()

        val typeText = this.select(".mli-mvi").firstOrNull { it.selectFirst("i.fa") == null }?.text()
        val type = when {
            href.contains("/film/") -> TvType.Movie
            href.contains("/series/") || href.contains("/tvshow/") -> TvType.TvSeries
            else -> getType(typeText)
        }

        return newAnimeSearchResponse(title, href, type) {
            this.posterUrl = posterUrl
            addSub(epNum)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val keyword = URLEncoder.encode(query, "UTF-8")
        val results = LinkedHashMap<String, SearchResponse>()

        for (page in 1..3) {
            val url = if (page == 1) "$mainUrl/?s=$keyword" else "$mainUrl/page/$page/?s=$keyword"
            val document = request(url).document

            val before = results.size
            document.select("div.a-item, div.ml-item, div.ml-item-anime")
                .mapNotNull { it.toSearchResult() }
                .forEach { results.putIfAbsent(it.url, it) }
            if (results.size == before) break

            val hasNext = document.select("ul.pagination a[href]").any { a ->
                Regex("""/page/(\d+)""").find(a.attr("href"))
                    ?.groupValues?.getOrNull(1)?.toIntOrNull()
                    ?.let { it > page } == true
            }
            if (!hasNext) break
        }
        return results.values.toList()
    }

    override suspend fun load(url: String): LoadResponse {
        val document = request(url).document

        val title = document.selectFirst(".m-info .judul")?.text()?.trim()
            ?: document.selectFirst("meta[property=og:title]")?.attr("content")
                ?.replace(Regex("(?i)\\s*-\\s*Winbu.*"), "")
                ?.replace(Regex("(?i)Nonton\\s+"), "")
                ?.trim()
            ?: document.title().replace(Regex("(?i)\\s*-\\s*Winbu.*"), "").trim()

        val poster = fixUrlNull(
            document.selectFirst(".m-info img.mli-thumb")?.attr("src")
                ?: document.selectFirst("meta[property=og:image]")?.attr("content")
        )

        val genres = document.select(".m-info .mli-mvi a[itemprop=genre], .m-info a[rel=tag]").map { it.text() }.distinct()
        val plot = document.selectFirst(".m-info .mli-desc")?.text()?.trim()
            ?: document.selectFirst("meta[name=description]")?.attr("content")

        val ratingText = document.selectFirst(".m-info span[itemprop=ratingValue]")?.text()
        val rating = ratingText?.toFloatOrNull()

        val seasonText = document.selectFirst(".m-info a[href*=/season/]")?.text().orEmpty()
        val year = Regex("(\\d{4})").find(seasonText)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: document.selectFirst("meta[property=article:published_time]")?.attr("content")
                ?.take(4)?.toIntOrNull()
            ?: Regex("""\((\d{4})\)""").find(title)?.groupValues?.getOrNull(1)?.toIntOrNull()

        val episodes = document.select("div.les-content a, div.tvseason a, ul.episod li a, .eplister a")
            .mapNotNull { a ->
                val epHref = fixUrl(a.attr("href"))
                if (epHref.isBlank()) return@mapNotNull null
                if (!epHref.contains("episode", ignoreCase = true) &&
                    !epHref.contains("/eps/", ignoreCase = true)
                ) return@mapNotNull null
                val name = a.text().trim().ifBlank { a.attr("title") }
                val epNum = Regex("(?i)Episode\\s*(\\d+)").find(name)?.groupValues?.getOrNull(1)?.toIntOrNull()
                    ?: Regex("(?i)-episode-(\\d+)").find(epHref)?.groupValues?.getOrNull(1)?.toIntOrNull()
                    ?: Regex("(?i)/eps/(\\d+)").find(epHref)?.groupValues?.getOrNull(1)?.toIntOrNull()
                newEpisode(epHref) {
                    this.name = name.ifBlank { "Episode $epNum" }
                    this.episode = epNum
                }
            }
            .distinctBy { it.data }
            .sortedBy { it.episode }

        val finalEpisodes = if (episodes.isEmpty()) {
            listOf(newEpisode(url) {
                this.name = title
                this.episode = 1
            })
        } else {
            episodes
        }

        val type = when {
            url.contains("/film/") || title.contains("Movie", true) -> TvType.Movie
            url.contains("/tvshow/") || url.contains("/series/") -> TvType.TvSeries
            else -> TvType.Anime
        }

        return newAnimeLoadResponse(title, url, type) {
            this.posterUrl = poster
            this.year = year
            this.plot = plot
            this.tags = genres
            this.score = Score.from10(rating)
            addEpisodes(DubStatus.Subbed, finalEpisodes)
        }
    }

    private data class PlayerOption(
        val post: String,
        val nume: String,
        val type: String,
        val serverName: String,
        val quality: Int,
    )

    private suspend fun loadFixedExtractor(
        url: String,
        serverName: String?,
        quality: Int,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        loadExtractor(url, referer, subtitleCallback) { link ->
            runBlocking {
                callback.invoke(
                    newExtractorLink(
                        source = serverName?.let { "${this@WinbuProvider.name} - $it" } ?: this@WinbuProvider.name,
                        name = serverName?.let { "$it (${link.name})" } ?: "${link.name} (DL)",
                        url = link.url,
                        type = link.type
                    ) {
                        this.referer = link.referer
                        this.quality = if (link.quality == Qualities.Unknown.value) quality else link.quality
                        this.headers = link.headers
                        this.extractorData = link.extractorData
                    }
                )
            }
        }
    }

    private suspend fun loadDownloadLinks(
        document: Document,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        document.select("div.download-eps li a[href], .download-eps a[href], .boxdl a[href]")
            .distinctBy { it.attr("href") }
            .amap { a ->
                val href = a.attr("href")
                if (href.isBlank() || href.startsWith("javascript", true) || href == "#") return@amap
                val qualityText = a.parents().firstOrNull { it.tagName() == "li" }
                    ?.selectFirst("strong")?.text()
                    ?: a.parent()?.selectFirst("strong")?.text()
                val quality = parseQuality(qualityText)
                try {
                    loadFixedExtractor(
                        fixUrl(href),
                        a.text().trim().ifBlank { null },
                        quality,
                        referer,
                        subtitleCallback,
                        callback
                    )
                } catch (_: Exception) {
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

        val options = document.select("div.east_player_option").mapNotNull { el ->
            val post = el.attr("data-post")
            val nume = el.attr("data-nume")
            if (post.isBlank() || nume.isBlank()) return@mapNotNull null

            val serverName = el.selectFirst("span")?.text()?.trim()?.ifBlank { null } ?: "Server $nume"

            val qualityFromServer = parseQuality(serverName)
            val qualityFromDropdown = parseQuality(
                el.parents().firstOrNull { it.hasClass("dropdown") }
                    ?.selectFirst("button.dropdown-toggle")?.text()
            )
            val quality = when {
                qualityFromServer != Qualities.Unknown.value -> qualityFromServer
                qualityFromDropdown != Qualities.Unknown.value -> qualityFromDropdown
                else -> Qualities.Unknown.value
            }

            PlayerOption(
                post = post,
                nume = nume,
                type = el.attr("data-type").ifBlank { "schtml" },
                serverName = serverName,
                quality = quality
            )
        }

        if (options.isEmpty()) {
            document.select("div.pframe iframe, .movieplay iframe, .player iframe, iframe[src]").forEach { iframe ->
                val src = iframe.attr("src").ifBlank { iframe.attr("data-src") }
                if (src.isNotBlank() && !src.contains("youtube.com", true) && !src.contains("youtu.be", true)) {
                    val fixed = fixUrl(src)
                    loadExtractor(fixed, data, subtitleCallback, callback)
                    callback.invoke(
                        newExtractorLink(
                            source = name,
                            name = "Direct",
                            url = fixed,
                            type = INFER_TYPE
                        ) {
                            this.referer = data
                        }
                    )
                }
            }
            loadDownloadLinks(document, data, subtitleCallback, callback)
            return true
        }

        options.amap { option ->
            try {
                val res = app.post(
                    "$mainUrl/wp-admin/admin-ajax.php",
                    data = mapOf(
                        "action" to "player_ajax",
                        "post" to option.post,
                        "nume" to option.nume,
                        "type" to option.type
                    ),
                    headers = mapOf(
                        "X-Requested-With" to "XMLHttpRequest",
                        "Referer" to data,
                        "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Mobile Safari/537.36"
                    )
                ).text

                val iframeSrc = Regex("""src=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
                    .find(res)?.groupValues?.getOrNull(1)
                    ?: Regex("""https?://[^\s"'<>]+""", RegexOption.IGNORE_CASE).find(res)?.value

                if (!iframeSrc.isNullOrBlank()) {
                    val fixed = fixUrl(iframeSrc)

                    loadFixedExtractor(
                        fixed,
                        option.serverName,
                        option.quality,
                        data,
                        subtitleCallback,
                        callback
                    )

                    val looksLikeMedia = fixed.contains(".mp4", true) ||
                            fixed.contains(".m3u8", true) ||
                            fixed.contains("googlevideo.com", true) ||
                            fixed.contains("blogger.googleusercontent", true) ||
                            fixed.contains("videoplayback", true)

                    if (looksLikeMedia) {
                        callback.invoke(
                            newExtractorLink(
                                source = name,
                                name = option.serverName,
                                url = fixed,
                                type = INFER_TYPE
                            ) {
                                this.referer = data
                                this.quality = option.quality
                            }
                        )
                    }
                }
            } catch (_: Exception) {
            }
        }

        loadDownloadLinks(document, data, subtitleCallback, callback)

        return true
    }
}
