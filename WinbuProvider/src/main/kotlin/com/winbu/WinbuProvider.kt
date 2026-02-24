package com.winbu

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addScore
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.base64Decode
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.httpsify
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

class WinbuProvider : MainAPI() {
    override var mainUrl = "https://winbu.net"
    override var name = "Winbu"
    override val hasMainPage = true
    override var lang = "id"
    override val hasQuickSearch = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.Anime, TvType.TvSeries)

    data class FiledonPage(
        val props: FiledonProps? = null,
    )

    data class FiledonProps(
        val url: String? = null,
        val files: FiledonFile? = null,
    )

    data class FiledonFile(
        val name: String? = null,
    )

    override val mainPage = mainPageOf(
        "anime-terbaru-animasu/page/%d/" to "New Episodes",
        "daftar-anime-2/page/%d/?status=Currently+Airing&order=latest" to "Ongoing Anime",
        "daftar-anime-2/page/%d/?status=Finished+Airing&order=latest" to "Complete Anime",
        "daftar-anime-2/page/%d/?order=popular" to "Most Popular",
        "daftar-anime-2/page/%d/?type=Movie&order=latest" to "Movie",
        "daftar-anime-2/page/%d/?type=Film&order=latest" to "Film",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get("$mainUrl/${request.data.format(page)}").document
        val items = document.select("#movies .ml-item, .movies-list .ml-item")
            .mapNotNull { it.toSearchResult(request.name) }
            .distinctBy { it.url }

        val hasNext = document.select("#pagination .pagination a[href]")
            .any { it.selectFirst("i.fa-caret-right") != null }

        return newHomePageResponse(HomePageList(request.name, items), hasNext = hasNext)
    }

    private fun parseEpisode(text: String?): Int? {
        if (text.isNullOrBlank()) return null
        return Regex("(\\d+[\\.,]?\\d*)").find(text)?.groupValues?.getOrNull(1)
            ?.replace(',', '.')
            ?.toFloatOrNull()
            ?.toInt()
    }

    private fun Element.toSearchResult(sectionName: String): SearchResponse? {
        val anchor = selectFirst("a.ml-mask") ?: selectFirst("a[href]") ?: return null
        val href = fixUrl(anchor.attr("href"))

        val title = anchor.attr("title").ifBlank {
            selectFirst(".judul")?.text().orEmpty()
        }.ifBlank {
            selectFirst("img.mli-thumb, img")?.attr("alt").orEmpty()
        }.trim()
        if (title.isBlank()) return null

        val poster = selectFirst("img.mli-thumb, img")?.getImageAttr()?.let { fixUrlNull(it) }
        val episode = parseEpisode(selectFirst("span.mli-episode")?.text())

        val isMovie = sectionName.contains("Film", true) || href.contains("/film/", true)

        return if (isMovie) {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = poster
            }
        } else {
            newAnimeSearchResponse(title, href, TvType.Anime) {
                this.posterUrl = poster
                if (episode != null) addSub(episode)
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/?s=$query").document
        return document.select("#movies .ml-item, .movies-list .ml-item")
            .mapNotNull { it.toSearchResult("Series") }
            .distinctBy { it.url }
    }

    private fun cleanupTitle(rawTitle: String): String {
        return rawTitle
            .replace(Regex("^Nonton\\s+", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s+Sub\\s+Indo.*$", RegexOption.IGNORE_CASE), "")
            .replace(" - Winbu", "", ignoreCase = true)
            .trim()
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        val infoRoot = document.selectFirst(".m-info .t-item") ?: document

        val rawTitle = infoRoot.selectFirst(".mli-info .judul")?.text()
            ?: document.selectFirst("h1")?.text()
            ?: document.selectFirst("meta[property=\"og:title\"]")?.attr("content")
            ?: "No Title"
        val title = cleanupTitle(rawTitle)

        val poster = infoRoot.selectFirst("img.mli-thumb")?.getImageAttr()?.let { fixUrlNull(it) }
            ?: document.selectFirst("meta[property=\"og:image\"]")?.attr("content")
            ?: document.selectFirst("img")?.getImageAttr()?.let { fixUrlNull(it) }

        val description = infoRoot.selectFirst(".mli-desc")?.text()?.trim()
            ?: document.selectFirst("meta[name=\"description\"]")?.attr("content")

        val tags = infoRoot.select(".mli-mvi a[rel=tag], a[rel=tag]")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }
            .distinct()

        val score = infoRoot.selectFirst("span[itemprop=ratingValue]")?.text()?.toIntOrNull()

        val recommendations = document.select("#movies .ml-item")
            .mapNotNull { it.toSearchResult("Series") }
            .filterNot { fixUrl(it.url) == fixUrl(url) }
            .distinctBy { it.url }

        val episodes = document.select(".tvseason .les-content a[href]")
            .mapNotNull { a ->
                val epText = a.text().trim()
                val epNum = parseEpisode(epText)
                if (epNum == null || !epText.contains("Episode", true)) return@mapNotNull null
                Pair(epNum, fixUrl(a.attr("href")))
            }
            .distinctBy { it.second }
            .sortedBy { it.first }
            .map { (num, link) ->
                newEpisode(link) {
                    this.name = "Episode $num"
                    this.episode = num
                }
            }

        val isSeries = episodes.isNotEmpty() && !url.contains("/film/", true)

        return if (isSeries) {
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = description
                this.tags = tags
                this.recommendations = recommendations
                if (score != null) addScore(score.toString(), 10)
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = description
                this.tags = tags
                this.recommendations = recommendations
                if (score != null) addScore(score.toString(), 10)
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
        val seen = hashSetOf<String>()
        val subtitleCb: (SubtitleFile) -> Unit = { subtitleCallback.invoke(it) }
        val linkCb: (ExtractorLink) -> Unit = {
            found = true
            callback.invoke(it)
        }

        suspend fun throwToExtractors(url: String, referer: String = data) {
            runCatching {
                loadExtractor(url, referer, subtitleCb, linkCb)
            }
        }

        suspend fun resolveFiledon(url: String): Pair<String?, String?> {
            val page = runCatching { app.get(url, referer = data).document }.getOrNull() ?: return null to null
            val json = page.selectFirst("#app")?.attr("data-page") ?: return null to null
            val parsed = tryParseJson<FiledonPage>(json) ?: return null to null
            return parsed.props?.url to parsed.props?.files?.name
        }

        suspend fun addDirect(url: String?, sourceName: String, quality: String? = null) {
            val raw = url?.trim().orEmpty()
            if (raw.isBlank()) return
            val fixed = fixUrl(raw)
            if (!seen.add(fixed)) return
            linkCb(
                newExtractorLink(sourceName, sourceName, fixed, INFER_TYPE) {
                    this.quality = quality?.let { getQualityFromName(it) } ?: Qualities.Unknown.value
                    this.headers = mapOf("Referer" to data)
                }
            )
        }

        suspend fun loadUrl(url: String?) {
            val raw = url?.trim().orEmpty()
            if (raw.isBlank()) return
            val fixed = httpsify(raw)
            if (!seen.add(fixed)) return

            throwToExtractors(fixed, data)

            if (fixed.contains("filedon.co/embed/", true)) {
                val (direct, fileName) = resolveFiledon(fixed)
                if (!direct.isNullOrBlank()) {
                    addDirect(
                        url = direct,
                        sourceName = "$name Filedon",
                        quality = fileName
                    )
                    return
                }
            }

            throwToExtractors(fixed, "$mainUrl/")
        }

        for (frame in document.select(".movieplay .pframe iframe, .player-embed iframe, .movieplay iframe, #embed_holder iframe")) {
            loadUrl(frame.getIframeAttr())
        }

        val ajaxUrl = "$mainUrl/wp-admin/admin-ajax.php"
        for (option in document.select(".east_player_option[data-post][data-nume][data-type]")) {
            val post = option.attr("data-post").trim()
            val nume = option.attr("data-nume").trim()
            val type = option.attr("data-type").trim()
            val server = option.text().trim().ifBlank { "Server $nume" }
            if (post.isBlank() || nume.isBlank() || type.isBlank()) continue

            runCatching {
                app.post(
                    ajaxUrl,
                    data = mapOf(
                        "action" to "player_ajax",
                        "post" to post,
                        "nume" to nume,
                        "type" to type
                    ),
                    headers = mapOf("X-Requested-With" to "XMLHttpRequest", "Referer" to data)
                ).text
            }.getOrNull()?.let { body ->
                val ajaxDoc = Jsoup.parse(body)

                for (frame in ajaxDoc.select("iframe")) {
                    loadUrl(frame.getIframeAttr())
                }

                for (source in ajaxDoc.select("video source[src]")) {
                    addDirect(source.attr("src"), "$name $server", source.attr("size"))
                }

                for (a in ajaxDoc.select("a[href]")) {
                    val href = a.attr("href")
                    if (href.startsWith("http", true)) loadUrl(href)
                }
            }
        }

        // 3) Link download (fallback jika server list tidak menghasilkan link playable)
        for (a in document.select(".download-eps a[href], #downloadb a[href], .boxdownload a[href], .dlbox a[href]")) {
            loadUrl(a.attr("href"))
        }

        return found
    }

    private fun Element.getImageAttr(): String {
        return when {
            hasAttr("data-src") -> attr("abs:data-src")
            hasAttr("data-lazy-src") -> attr("abs:data-lazy-src")
            hasAttr("srcset") -> attr("abs:srcset").substringBefore(" ")
            else -> attr("abs:src")
        }
    }

    private fun Element.getIframeAttr(): String? {
        return attr("data-litespeed-src").takeIf { it.isNotBlank() }
            ?: attr("data-src").takeIf { it.isNotBlank() }
            ?: attr("src").takeIf { it.isNotBlank() }
    }
}
