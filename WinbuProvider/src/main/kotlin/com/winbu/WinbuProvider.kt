package com.winbu

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addScore
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.httpsify
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.util.Collections

class WinbuProvider : MainAPI() {
    override var mainUrl = "https://winbu.net"
    override var name = "Winbu"
    override val hasMainPage = true
    override var lang = "id"
    override val hasQuickSearch = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.Anime, TvType.TvSeries)

    data class FiledonPage(val props: FiledonProps? = null)
    data class FiledonProps(val url: String? = null, val files: FiledonFile? = null)
    data class FiledonFile(val name: String? = null)

    override val mainPage = mainPageOf(
        "anime-terbaru-animasu/page/%d/" to "New Episodes",
        "daftar-anime-2/page/%d/?status=Currently+Airing&order=latest" to "Ongoing Anime",
        "daftar-anime-2/page/%d/?status=Finished+Airing&order=latest" to "Complete Anime",
        "daftar-anime-2/page/%d/?order=popular" to "Most Popular",
        "daftar-anime-2/page/%d/?type=Movie&order=latest" to "Movie",
        "daftar-anime-2/page/%d/?type=Film&order=latest" to "Film",
        "tvshow/page/%d/" to "TV Show",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val path = if (page == 1) {
            request.data.replace("/page/%d/", "/").replace("page/%d/", "")
        } else {
            request.data.format(page)
        }

        val document = app.get("$mainUrl/$path").documentLarge
        
        val cssSelector = if (request.name in listOf("Ongoing Anime", "Complete Anime", "Most Popular", "Movie")) {
            "#anime .ml-item, .movies-list .ml-item"
        } else {
            "#movies .ml-item, .movies-list .ml-item"
        }
        
        val homeList = document.select(cssSelector)
            .mapNotNull { it.toSearchResult(request.name) }
            .distinctBy { it.url }

        val hasNext = document.selectFirst(".pagination a.next, a.next.page-numbers") != null || 
                document.select(".pagination a[href], #pagination a[href]").any {
                    it.selectFirst("i.fa-caret-right, i.fa-angle-right, i.fa-chevron-right") != null || 
                    it.text().contains("Next", ignoreCase = true)
                }

        return newHomePageResponse(
            listOf(HomePageList(request.name, homeList, isHorizontalImages = request.name == "Movie")),
            hasNext = hasNext || homeList.isNotEmpty()
        )
    }

    private fun parseEpisode(text: String?): Int? {
        return text?.let { 
            Regex("(\\d+[.,]?\\d*)").find(it)?.value?.replace(',', '.')?.toFloatOrNull()?.toInt() 
        }
    }

    private fun Element.toSearchResult(sectionName: String): SearchResponse? {
        val anchor = selectFirst("a.ml-mask, a[href]") ?: return null
        val href = fixUrl(anchor.attr("href"))

        val title = anchor.attr("title").takeIf { it.isNotBlank() }
            ?: selectFirst(".judul")?.text()?.takeIf { it.isNotBlank() }
            ?: selectFirst("img.mli-thumb, img")?.attr("alt").orEmpty()
            
        if (title.isBlank()) return null

        val poster = selectFirst("img.mli-thumb, img")?.getImageAttr()?.let { fixUrlNull(it) }
        val episode = parseEpisode(selectFirst("span.mli-episode")?.text())

        val isMovie = sectionName.contains("Film", true) || sectionName.contains("Movie", true) || href.contains("/film/", true)

        return if (isMovie) {
            newMovieSearchResponse(title.trim(), href, TvType.Movie) { this.posterUrl = poster }
        } else {
            newAnimeSearchResponse(title.trim(), href, TvType.Anime) {
                this.posterUrl = poster
                episode?.let { addSub(it) }
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return app.get("$mainUrl/?s=$query").documentLarge
            .select("#movies .ml-item, .movies-list .ml-item")
            .mapNotNull { it.toSearchResult("Series") }
            .distinctBy { it.url }
    }

    private fun cleanupTitle(rawTitle: String): String {
        return rawTitle
            .replace(Regex("^(Nonton\\s+|Download\\s+)", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s+Sub\\s+Indo.*$", RegexOption.IGNORE_CASE), "")
            .replace(" - Winbu", "", ignoreCase = true)
            .trim()
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).documentLarge
        val infoRoot = document.selectFirst(".m-info .t-item") ?: document

        val rawTitle = infoRoot.selectFirst(".mli-info .judul")?.text()
            ?: document.selectFirst("h1")?.text()
            ?: document.selectFirst("meta[property=og:title]")?.attr("content")
            ?: "No Title"
        val title = cleanupTitle(rawTitle)

        val poster = infoRoot.selectFirst("img.mli-thumb")?.getImageAttr()?.let { fixUrlNull(it) }
            ?: document.selectFirst("meta[property=og:image]")?.attr("content")
            ?: document.selectFirst("img")?.getImageAttr()?.let { fixUrlNull(it) }

        val description = infoRoot.selectFirst(".mli-desc")?.text()?.trim()
            ?: document.selectFirst("meta[name=description]")?.attr("content")

        val tags = infoRoot.select(".mli-mvi a[rel=tag], a[rel=tag]")
            .mapNotNull { it.text().trim().takeIf { t -> t.isNotBlank() } }
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
                epNum to fixUrl(a.attr("href"))
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
                score?.let { addScore(it.toString(), 10) }
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = description
                this.tags = tags
                this.recommendations = recommendations
                score?.let { addScore(it.toString(), 10) }
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).documentLarge
        var found = false
        val seen = Collections.synchronizedSet(hashSetOf<String>())
        
        val subtitleCb: (SubtitleFile) -> Unit = { subtitleCallback.invoke(it) }
        val linkCb: (ExtractorLink) -> Unit = {
            found = true
            callback.invoke(it)
        }

        suspend fun loadUrl(url: String?) {
            val raw = url?.trim().orEmpty()
            if (raw.isBlank()) return
            val fixed = httpsify(raw)
            if (!seen.add(fixed)) return

            if (fixed.contains("filedon.co/embed/", true)) {
                val page = runCatching { app.get(fixed, referer = data).document }.getOrNull() ?: return
                val json = page.selectFirst("#app")?.attr("data-page") ?: return
                val parsed = tryParseJson<FiledonPage>(json) ?: return
                
                val directUrl = parsed.props?.url
                if (!directUrl.isNullOrBlank() && seen.add(directUrl)) {
                    linkCb(newExtractorLink(
                        "$name Filedon", "$name Filedon", directUrl, INFER_TYPE, 
                        parsed.props.files?.name?.let { getQualityFromName(it) } ?: Qualities.Unknown.value,
                        mapOf("Referer" to data)
                    ))
                    return
                }
            }
            runCatching { loadExtractor(fixed, data, subtitleCb, linkCb) }
        }

        coroutineScope {
            document.select(".movieplay .pframe iframe, .player-embed iframe, .movieplay iframe, #embed_holder iframe")
                .map { frame -> async { loadUrl(frame.getIframeAttr()) } }
                .awaitAll()
                
            val options = document.select(".east_player_option[data-post][data-nume][data-type]")
            options.map { option ->
                async {
                    val post = option.attr("data-post").trim()
                    val nume = option.attr("data-nume").trim()
                    val type = option.attr("data-type").trim()
                    
                    if (post.isNotBlank() && nume.isNotBlank() && type.isNotBlank()) {
                        val body = runCatching {
                            app.post(
                                "$mainUrl/wp-admin/admin-ajax.php",
                                data = mapOf("action" to "player_ajax", "post" to post, "nume" to nume, "type" to type),
                                headers = mapOf("X-Requested-With" to "XMLHttpRequest", "Referer" to data)
                            ).text
                        }.getOrNull()

                        body?.let {
                            val ajaxDoc = Jsoup.parse(it)
                            ajaxDoc.select("iframe").forEach { frame -> loadUrl(frame.getIframeAttr()) }
                            ajaxDoc.select("video source[src]").forEach { source -> 
                                val src = source.attr("src")
                                val quality = source.attr("size")
                                val serverName = "$name " + (option.text().trim().ifBlank { "Server $nume" })
                                
                                if (src.isNotBlank() && seen.add(src)) {
                                    linkCb(newExtractorLink(
                                        serverName, serverName, src, INFER_TYPE,
                                        getQualityFromName(quality), mapOf("Referer" to data)
                                    ))
                                }
                            }
                            ajaxDoc.select("a[href^=http]").forEach { a -> loadUrl(a.attr("href")) }
                        }
                    }
                }
            }.awaitAll()

            document.select(".download-eps a[href], #downloadb a[href], .boxdownload a[href], .dlbox a[href]")
                .map { a -> async { loadUrl(a.attr("href")) } }
                .awaitAll()
        }

        return found
    }

    private fun Element.getImageAttr(): String {
        return attr("abs:data-src").takeIf { it.isNotBlank() }
            ?: attr("abs:data-lazy-src").takeIf { it.isNotBlank() }
            ?: attr("abs:srcset").substringBefore(" ").takeIf { it.isNotBlank() }
            ?: attr("abs:src")
    }

    private fun Element.getIframeAttr(): String? {
        return attr("data-litespeed-src").takeIf { it.isNotBlank() }
            ?: attr("data-src").takeIf { it.isNotBlank() }
            ?: attr("src").takeIf { it.isNotBlank() }
    }
}
