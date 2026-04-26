package com.kuramanime

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class Kuramanime : MainAPI() {
    override var mainUrl              = "https://v17.kuramanime.ink"
    override var name                 = "Kuramanime"
    override val hasMainPage          = true
    override var lang                 = "id"
    override val hasDownloadSupport   = true
    override val supportedTypes       = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    companion object {
        // Endpoint quick-search: GET /quicksearch/get?search=<query>
        // Returns JSON: { animes: [ { title, slug, poster, ... } ] }
        private val statusMap = mapOf(
            "ongoing"   to ShowStatus.Ongoing,
            "completed" to ShowStatus.Completed,
            "selesai"   to ShowStatus.Completed,
            "sedang"    to ShowStatus.Ongoing,
        )
    }

    //  Main Page 
    // Actual URL patterns from the site navigation:
    //   /quick/ongoing?order_by=updated&page=N
    //   /quick/finished?order_by=updated&page=N
    //   /quick/movie?order_by=updated&page=N
    //   /properties/season/spring-2026?order_by=popular&page=N

    override val mainPage = mainPageOf(
        "$mainUrl/quick/ongoing?order_by=updated&page="  to "Sedang Tayang",
        "$mainUrl/quick/finished?order_by=updated&page=" to "Selesai Tayang",
        "$mainUrl/quick/movie?order_by=updated&page="    to "Film Layar Lebar",
        "$mainUrl/anime?order_by=popular&page="          to "Anime Populer",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(request.data + page).document
        // Actual selector from HTML: div.product__item
        val items = document.select("div.product__item").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, items)
    }

    /**
     * Actual HTML structure for each card:
     *
     * <div class="product__item">
     *   <a href="/anime/{id}/{slug}/episode/{n}">
     *     <div class="product__item__pic set-bg" data-setbg="{poster_url}">
     *       <div class="ep">
     *         <span class="actual-anime-{id}-ongoing">Ep 4 / 12</span>
     *       </div>
     *     </div>
     *   </a>
     *   <div class="product__item__text">
     *     <ul> <li>TV</li> <li>HD</li> </ul>
     *     <h5><a href="/anime/{id}/{slug}/episode/{n}">{Title}</a></h5>
     *   </div>
     * </div>
     */
    private fun Element.toSearchResult(): AnimeSearchResponse? {
        // Title is in h5 > a inside product__item__text
        val title  = selectFirst("div.product__item__text h5 a")?.text()?.trim()
            ?: return null
        // The episode link href – normalize to anime detail page (drop /episode/N)
        val rawHref = selectFirst("a[href]")?.attr("href") ?: return null
        // Convert episode URL to anime detail URL:
        // /anime/4872/ghost-concert-missing-songs/episode/4    /anime/4872/ghost-concert-missing-songs
        val href = Regex("""/episode/\d+$""").replace(rawHref, "")
        val animeUrl = if (href.startsWith("http")) href else "$mainUrl$href"

        // Poster is in data-setbg (NOT src)
        val poster = selectFirst("div.product__item__pic[data-setbg]")
            ?.attr("data-setbg")

        // Episode number from span text: "Ep 4 / 12"
        val epText = selectFirst("div.ep span")?.text() ?: ""
        val ep = Regex("""Ep\s+(\d+)""").find(epText)?.groupValues?.get(1)?.toIntOrNull()

        // Type badges: TV / Movie / ONA / Special etc.
        val typeBadge = selectFirst("div.product__item__text ul a")?.text()?.trim() ?: "TV"
        val tvType = when {
            typeBadge.equals("Movie", ignoreCase = true) -> TvType.AnimeMovie
            else -> TvType.Anime
        }

        return newAnimeSearchResponse(title, animeUrl, tvType) {
            this.posterUrl = poster
            addDubStatus(dubExist = false, subExist = true, subEpisodes = ep)
        }
    }

    //  Search 
    // Search form: GET /anime?search=<query>&order_by=oldest
    // Also quick-search JSON: GET /quicksearch/get?search=<query>

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get(
            "$mainUrl/anime",
            params = mapOf("search" to query, "order_by" to "oldest")
        ).document
        return document.select("div.product__item").mapNotNull { it.toSearchResult() }
    }

    //  Load Anime Detail 
    // URL: /anime/{id}/{slug}
    //
    // IMPORTANT: Episode list is on the EPISODE page (#animeEpisodes a.ep-button),
    // so we fetch the first episode page to grab the full list, then also parse
    // detail info from the anime detail page.

    override suspend fun load(url: String): LoadResponse? {
        // Normalise: strip /episode/N suffix
        val animeUrl = Regex("""/episode/\d+$""").replace(url, "")
        val document = app.get(animeUrl).document

        //  Title 
        // Try multiple fallback selectors for the title
        val title = (
            document.selectFirst("h1.anime-title")
                ?: document.selectFirst("div.anime__details__title h3")
                ?: document.selectFirst("h3.anime__details__title")
                ?: document.selectFirst("h1")
                // Fallback: extract from breadcrumb link text
                ?: document.selectFirst("div.breadcrumb__links a:last-of-type")
        )?.text()?.trim() ?: return null

        //  Poster 
        val poster = (
            document.selectFirst("div.anime__details__pic[data-setbg]")?.attr("data-setbg")
                ?: document.selectFirst("div.anime__details__pic img")?.attr("src")
                ?: document.selectFirst("img.img-thumbnail")?.attr("src")
                // og:image as last resort
                ?: document.selectFirst("meta[property=og:image]")?.attr("content")
        )

        //  Synopsis / Description 
        val desc = (
            document.selectFirst("div.anime__details__text p")
                ?: document.selectFirst("div.synopsis p")
                ?: document.selectFirst(".anime-synopsis p")
        )?.text()?.trim()

        //  Info table (Genre, Status, Year, Score) 
        val tags   = mutableListOf<String>()
        var year   : Int? = null
        var status : ShowStatus? = null
        var score  : Float? = null

        document.select("div.anime__details__widget ul li, table.anime__details__widget tr").forEach { li ->
            val text = li.text()
            when {
                text.startsWith("Genre", ignoreCase = true) ->
                    li.select("a").mapTo(tags) { it.text() }
                text.startsWith("Tahun", ignoreCase = true) ->
                    year = Regex("""\d{4}""").find(text)?.value?.toIntOrNull()
                text.startsWith("Status", ignoreCase = true) ->
                    status = when {
                        text.contains("Ongoing", ignoreCase = true)  -> ShowStatus.Ongoing
                        text.contains("Selesai", ignoreCase = true)  -> ShowStatus.Completed
                        text.contains("Completed", ignoreCase = true)-> ShowStatus.Completed
                        else -> null
                    }
                text.startsWith("Skor", ignoreCase = true) ||
                text.startsWith("Score", ignoreCase = true) ->
                    score = Regex("""[\d.]+""").find(text)?.value?.toFloatOrNull()
            }
        }

        //  Episode List 
        // Episodes appear in #animeEpisodes a.ep-button on the episode pages.
        // If the detail page doesn't have them, we fetch episode/1 to get the list.
        var episodes = parseEpisodeList(document, animeUrl)
        if (episodes.isEmpty()) {
            // Fetch episode 1 page to get the full episode list
            runCatching {
                val ep1Doc = app.get("$animeUrl/episode/1").document
                episodes = parseEpisodeList(ep1Doc, animeUrl)
            }
        }

        //  Type 
        val isMovie = tags.any { it.equals("Movie", ignoreCase = true) }
            || document.text().contains("Film Layar Lebar", ignoreCase = true)
        val tvType = if (isMovie) TvType.AnimeMovie else TvType.Anime

        return newAnimeLoadResponse(title, animeUrl, tvType) {
            this.posterUrl  = poster
            this.plot       = desc
            this.tags       = tags
            this.year       = year
            this.showStatus = status
            this.score      = score
            addEpisodes(DubStatus.Subbed, episodes)
        }
    }

    /**
     * Parse episode list from a page containing:
     *   <div id="animeEpisodes" class="mix episode">
     *     <a href="/anime/{id}/{slug}/episode/{n}" class="ep-button">Ep 1</a>
     *     ...
     *   </div>
     */
    private fun parseEpisodeList(doc: org.jsoup.nodes.Document, animeUrl: String): List<Episode> {
        return doc.select("div#animeEpisodes a.ep-button[href]").mapNotNull { a ->
            val href = a.attr("href").let {
                if (it.startsWith("http")) it else "$mainUrl$it"
            }
            // Text is "Ep 3" or "Ep 03"
            val epText = a.ownText().trim()
            val epNum  = Regex("""Ep\s*0*(\d+)""", RegexOption.IGNORE_CASE)
                .find(epText)?.groupValues?.get(1)?.toIntOrNull()
            Episode(
                data    = href,
                name    = epText,
                episode = epNum
            )
        }
    }

    //  Load Episode / Video Links 
    // URL: /anime/{id}/{slug}/episode/{n}
    //
    // From the episode page HTML, the key data points are:
    //   <input id="animeId"    value="4849">
    //   <input id="postId"     value="48527">
    //   <input id="kdriveServer" value="s1">
    //   <select id="changeServer"> — kuramadrive, doodstream, filemoon, mega, rpmshare, streamp2p
    //
    // Video is loaded via leviathan.js  anime-episode.min.js (both minified).
    // The key API endpoint is:
    //   GET /anime/{animeId}/episode/{epNum}/check-episode
    //    returns JSON like { "url": "https://...", "server": "...", ... }
    //   or redirects to an embed URL for external servers.
    //
    // For external servers, the URL pattern is:
    //   GET /anime/{animeId}/episode/{epNum}/check-episode?server={serverName}
    //    returns JSON { "url": "https://dood...." } — then use loadExtractor

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document

        //  Extract key IDs from hidden inputs 
        val animeId  = document.selectFirst("input#animeId")?.attr("value")
        val postId   = document.selectFirst("input#postId")?.attr("value")
        val csrfToken = document.selectFirst("meta[name=csrf-token]")?.attr("content") ?: ""

        // Episode number is the last path segment after /episode/
        val epNum = Regex("""/episode/(\d+)""").find(data)?.groupValues?.get(1)

        // Available streaming servers from the <select>
        // Values: kuramadrive, doodstream, filemoon, mega, rpmshare, streamp2p
        val servers = document.select("select#changeServer option")
            .map { it.attr("value") to it.text().trim() }

        val headers = mapOf(
            "Referer"           to data,
            "X-Requested-With"  to "XMLHttpRequest",
            "X-CSRF-TOKEN"      to csrfToken,
        )

        //  Strategy A: check-episode API for each server 
        if (animeId != null && epNum != null) {
            val baseCheckUrl = "$mainUrl/anime/$animeId/episode/$epNum/check-episode"

            servers.forEach { (serverKey, serverLabel) ->
                runCatching {
                    val checkUrl = if (serverKey == "kuramadrive") baseCheckUrl
                                   else "$baseCheckUrl?server=$serverKey"

                    val resp = app.get(checkUrl, headers = headers)
                    val text = resp.text

                    // Response may be JSON: {"url":"https://...","server":"..."}
                    val jsonUrl = runCatching {
                        resp.parsedSafe<CheckEpisodeResponse>()?.url
                    }.getOrNull()

                    val videoUrl = jsonUrl
                        ?: Regex("""https?://[^\s"'<>]+\.(?:m3u8|mp4)[^\s"'<>]*""")
                            .find(text)?.value
                        ?: Regex(""""url"\s*:\s*"([^"]+)"""")
                            .find(text)?.groupValues?.get(1)

                    if (videoUrl != null) {
                        when {
                            // Direct HLS / MP4 from Kuramadrive
                            videoUrl.contains(".m3u8") || videoUrl.contains(".mp4") -> {
                                callback(
                                    newExtractorLink(
                                        source  = name,
                                        name    = "[$serverLabel]",
                                        url     = videoUrl,
                                        referer = mainUrl,
                                        type    = if (videoUrl.contains(".m3u8"))
                                                      ExtractorLinkType.M3U8
                                                  else ExtractorLinkType.VIDEO,
                                        quality = when {
                                            videoUrl.contains("1080") -> Qualities.P1080.value
                                            videoUrl.contains("720")  -> Qualities.P720.value
                                            videoUrl.contains("480")  -> Qualities.P480.value
                                            videoUrl.contains("360")  -> Qualities.P360.value
                                            else -> Qualities.Unknown.value
                                        }
                                    )
                                )
                            }
                            // External embed URL  CloudStream built-in extractors
                            videoUrl.startsWith("http") -> {
                                loadExtractor(videoUrl, data, subtitleCallback, callback)
                            }
                        }
                    }
                }
            }
        }

        //  Strategy B: scrape <iframe src> on the rendered page 
        document.select("iframe[src]").forEach { iframe ->
            val src = iframe.attr("src").trim()
            if (src.isNotBlank() && src.startsWith("http")) {
                loadExtractor(src, data, subtitleCallback, callback)
            }
        }

        //  Strategy C: <video><source src> 
        document.select("video source[src]").forEach { source ->
            val videoUrl = source.attr("src")
            if (videoUrl.isNotBlank()) {
                callback(
                    newExtractorLink(
                        source  = name,
                        name    = name,
                        url     = videoUrl,
                        referer = mainUrl,
                        type    = if (videoUrl.contains(".m3u8")) ExtractorLinkType.M3U8
                                  else ExtractorLinkType.VIDEO,
                        quality = getQualityFromName(source.attr("label"))
                    )
                )
            }
        }

        //  Strategy D: inline <script> with plyr/jwplayer `file:` pattern 
        document.select("script:not([src])").map { it.data() }.forEach { js ->
            Regex("""(?:file|src)\s*:\s*["']([^"']+\.(?:m3u8|mp4)[^"']*)["']""")
                .findAll(js).forEach { m ->
                    val videoUrl = m.groupValues[1]
                    if (videoUrl.isNotBlank()) {
                        callback(
                            newExtractorLink(
                                source  = name,
                                name    = name,
                                url     = videoUrl,
                                referer = mainUrl,
                                type    = if (videoUrl.contains(".m3u8")) ExtractorLinkType.M3U8
                                          else ExtractorLinkType.VIDEO,
                                quality = when {
                                    videoUrl.contains("1080") -> Qualities.P1080.value
                                    videoUrl.contains("720")  -> Qualities.P720.value
                                    videoUrl.contains("480")  -> Qualities.P480.value
                                    videoUrl.contains("360")  -> Qualities.P360.value
                                    else -> Qualities.Unknown.value
                                }
                            )
                        )
                    }
                }
        }

        return true
    }

    //  Response Data Classes 

    data class CheckEpisodeResponse(
        val url     : String?,
        val server  : String?,
        val token   : String?,
        val status  : String?,
    )
}
