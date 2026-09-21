package com.winbu

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.nicehttp.NiceResponse
import org.jsoup.nodes.Element

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
        TvType.TvSeries
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
                t.contains("Ongoing", true) || t.contains("Ongoing", true) -> ShowStatus.Ongoing
                else -> ShowStatus.Completed
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
        "$mainUrl/animedonghua/page/" to "Latest Donghua Anime",
        "$mainUrl/film/page/" to "Latest Movies",
        "$mainUrl/tvshow/page/" to "TV Show",
        "$mainUrl/others/page/" to "Other"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) {
            request.data.removeSuffix("page/")
        } else {
            request.data + page
        }
        val document = request(url).document
        val home = document.select("div.ml-item, div.ml-item-anime").mapNotNull {
            it.toSearchResult()
        }
        return newHomePageResponse(request.name, home)
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

        val typeText = this.selectFirst(".mli-mvi")?.text().orEmpty()
        val type = getType(typeText)

        // Prefer anime detail page if possible
        val detailHref = if (href.contains("/anime/") || href.contains("/series/")) {
            href
        } else {
            // episode url -> try to find series from title or keep episode
            href
        }

        return newAnimeSearchResponse(title, detailHref, type) {
            this.posterUrl = posterUrl
            addSub(epNum)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = request("$mainUrl/?s=$query").document
        return document.select("div.ml-item, div.ml-item-anime").mapNotNull {
            it.toSearchResult()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = request(url).document

        // Title
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

        // Year / Season
        val seasonText = document.selectFirst(".m-info a[href*=/season/]")?.text().orEmpty()
        val year = Regex("(\\d{4})").find(seasonText)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: document.selectFirst("meta[property=article:published_time]")?.attr("content")
                ?.take(4)?.toIntOrNull()

        // Episodes
        val episodes = document.select("div.les-content a, div.tvseason a, .movies-list-wrap a[href*=episode]")
            .mapNotNull { a ->
                val epHref = fixUrl(a.attr("href"))
                if (!epHref.contains("episode", ignoreCase = true)) return@mapNotNull null
                val name = a.text().trim().ifBlank { a.attr("title") }
                val epNum = Regex("(?i)Episode\\s*(\\d+)").find(name)?.groupValues?.getOrNull(1)?.toIntOrNull()
                    ?: Regex("(?i)-episode-(\\d+)").find(epHref)?.groupValues?.getOrNull(1)?.toIntOrNull()
                newEpisode(epHref) {
                    this.name = name.ifBlank { "Episode $epNum" }
                    this.episode = epNum
                }
            }
            .distinctBy { it.data }
            .sortedBy { it.episode }

        // If no episodes found and url looks like episode, treat as single
        val finalEpisodes = if (episodes.isEmpty() && url.contains("episode", ignoreCase = true)) {
            listOf(newEpisode(url) {
                this.name = title
                this.episode = 1
            })
        } else {
            episodes
        }

        val type = when {
            url.contains("/film/") || title.contains("Movie", true) -> TvType.AnimeMovie
            url.contains("/tvshow/") || url.contains("/series/") -> TvType.TvSeries
            finalEpisodes.size <= 1 && !url.contains("season", true) -> TvType.AnimeMovie
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

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = request(data).document

        // Collect player options
        val options = document.select("div.east_player_option").mapNotNull { el ->
            val post = el.attr("data-post")
            val nume = el.attr("data-nume")
            val type = el.attr("data-type").ifBlank { "schtml" }
            val name = el.selectFirst("span")?.text()?.trim() ?: "Server $nume"
            if (post.isBlank() || nume.isBlank()) return@mapNotNull null
            Triple(post, nume, name) to type
        }

        if (options.isEmpty()) {
            // Fallback: direct iframe
            document.select("div.pframe iframe, .movieplay iframe").forEach { iframe ->
                val src = iframe.attr("src").ifBlank { iframe.attr("data-src") }
                if (src.isNotBlank()) {
                    loadExtractor(fixUrl(src), data, subtitleCallback, callback)
                }
            }
            // Also check download links
            document.select("a[href*=mp4upload], a[href*=vidhide], a[href*=mega], a[href*=gofile], a[href*=filedon]")
                .forEach { a ->
                    val href = a.attr("href")
                    if (href.isNotBlank()) {
                        loadExtractor(href, data, subtitleCallback, callback)
                    }
                }
            return true
        }

        options.amap { (ids, type) ->
            val (post, nume, serverName) = ids
            try {
                val res = app.post(
                    "$mainUrl/wp-admin/admin-ajax.php",
                    data = mapOf(
                        "action" to "player_ajax",
                        "post" to post,
                        "nume" to nume,
                        "type" to type
                    ),
                    headers = mapOf(
                        "X-Requested-With" to "XMLHttpRequest",
                        "Referer" to data,
                        "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Mobile Safari/537.36"
                    )
                ).text

                // Response is usually an iframe or embed html
                val iframeSrc = Regex("""src=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
                    .find(res)?.groupValues?.getOrNull(1)
                    ?: Regex("""https?://[^\s"'<>]+""", RegexOption.IGNORE_CASE).find(res)?.value

                if (!iframeSrc.isNullOrBlank()) {
                    val fixed = fixUrl(iframeSrc)
                    // Prefer loadExtractor for known hosts
                    loadExtractor(fixed, data, subtitleCallback) { link ->
                        callback.invoke(
                            newExtractorLink(
                                source = "$name - $serverName",
                                name = "$serverName (${link.name})",
                                url = link.url,
                                type = link.type
                            ) {
                                this.referer = link.referer
                                this.quality = link.quality
                                this.headers = link.headers
                            }
                        )
                    }
                    // Also try direct if blogger or unknown
                    if (fixed.contains("blogger.com") || fixed.contains("video.g")) {
                        callback.invoke(
                            newExtractorLink(
                                source = name,
                                name = serverName,
                                url = fixed,
                                type = INFER_TYPE
                            ) {
                                this.referer = data
                                this.quality = Qualities.Unknown.value
                            }
                        )
                    }
                }
            } catch (_: Exception) {
            }
        }

        // Extra: download section links
        document.select("a[href*=mp4upload.com], a[href*=vidhide], a[href*=mega.nz], a[href*=megaup], a[href*=filedon], a[href*=gofile]")
            .amap { a ->
                val href = a.attr("href")
                val qualityHint = a.parent()?.text() ?: a.text()
                try {
                    loadExtractor(href, data, subtitleCallback) { link ->
                        callback.invoke(
                            newExtractorLink(
                                source = name,
                                name = "${link.name} (DL)",
                                url = link.url,
                                type = link.type
                            ) {
                                this.referer = link.referer
                                this.quality = link.quality
                                this.headers = link.headers
                            }
                        )
                    }
                } catch (_: Exception) {
                }
            }

        return true
    }
}
