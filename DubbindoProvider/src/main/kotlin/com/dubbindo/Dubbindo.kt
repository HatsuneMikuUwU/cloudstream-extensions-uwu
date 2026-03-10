package com.dubbindo

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Element

class Dubbindo : MainAPI() {
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
    )

    // ── Cookies sesi ────────────────────────────────────────────────────────
    // Video source ada langsung di watch page saat request dengan cookie valid.
    // Tidak perlu embed iframe sama sekali.
    private val sessionCookies = mapOf(
        "user_id"        to "f87445d728dc4569f8795891589260aeefc8df0c1773168951fc41dd6bf552a442846634d941ead607",
        "next_up_videos" to "%5B%2227969%22%2C%22142%22%2C%2227988%22%2C%2227943%22%2C%2227976%22%5D",
        "v_shorts"       to "%5B%22bkviD7Qw7omhVRD%22%5D",
        "r"              to "c3RyaW5n",
        "mode"           to "night",
        "_uads"          to "a%3A2%3A%7Bs%3A4%3A%26quot%3Bdate%26quot%3B%3Bi%3A1773242984%3Bs%3A5%3A%26quot%3Buaid_%26quot%3B%3Ba%3A0%3A%7B%7D%7D",
        "auto"           to "",
        "PHPSESSID"      to "t52rria3q613o666lt9g4dtism",
    )

    private val cookieHeader: String
        get() = sessionCookies.entries.joinToString("; ") { "${it.key}=${it.value}" }

    private val defaultHeaders
        get() = mapOf(
            "Cookie"     to cookieHeader,
            "Referer"    to "$mainUrl/",
            "User-Agent" to "Mozilla/5.0 (Linux; Android 10; Mobile) AppleWebKit/537.36 Chrome/124.0 Mobile Safari/537.36"
        )

    // ── Main page ─────────────────────────────────────────────────────────────
    override val mainPage = mainPageOf(
        "$mainUrl/videos/category/1"     to "Movie",
        "$mainUrl/videos/category/3"     to "TV Series",
        "$mainUrl/videos/category/5"     to "Anime Series",
        "$mainUrl/videos/category/4"     to "Anime Movie",
        "$mainUrl/videos/category/other" to "Other",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get("${request.data}?page_id=$page", headers = defaultHeaders).document
        val home = document.select("div.video-wrapper").mapNotNull { it.toCategoryResult() }
        return newHomePageResponse(
            list = HomePageList(name = request.name, list = home, isHorizontalImages = true),
            hasNext = home.isNotEmpty()
        )
    }

    // ── Helpers ───────────────────────────────────────────────────────────────
    private fun Element.toCategoryResult(): TvSeriesSearchResponse? {
        val title = selectFirst("div.video-title h4")?.text()?.trim() ?: return null
        if (title.isEmpty()) return null
        val href = selectFirst("div.video-thumb a")?.attr("href")
            ?: selectFirst("a")?.attr("href") ?: return null
        val posterUrl = fixUrlNull(selectFirst("div.video-thumb img")?.attr("src"))
        return newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
            this.posterUrl = posterUrl
            this.posterHeaders = mapOf("Referer" to mainUrl)
        }
    }

    private fun Element.toSearchResult(): TvSeriesSearchResponse? {
        val title = selectFirst("div.video-list-title h4")?.text()?.trim()
            ?: selectFirst("h4")?.text()?.trim() ?: return null
        if (title.isEmpty()) return null
        val href = selectFirst("div.video-list-image a")?.attr("href")
            ?: selectFirst("a")?.attr("href") ?: return null
        val posterUrl = fixUrlNull(selectFirst("img")?.attr("src"))
        return newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
            this.posterUrl = posterUrl
            this.posterHeaders = mapOf("Referer" to mainUrl)
        }
    }

    private fun Element.toRelatedResult(): TvSeriesSearchResponse? {
        val title = selectFirst("div.video-title a")?.text()?.trim()
            ?: selectFirst("a")?.text()?.trim() ?: return null
        val href = selectFirst("div.ra-thumb a")?.attr("href")
            ?: selectFirst("a")?.attr("href") ?: return null
        val posterUrl = fixUrlNull(selectFirst("img")?.attr("src"))
        return newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
            this.posterUrl = posterUrl
            this.posterHeaders = mapOf("Referer" to mainUrl)
        }
    }

    // ── Search ────────────────────────────────────────────────────────────────
    override suspend fun search(query: String): List<SearchResponse> {
        val results = mutableListOf<SearchResponse>()
        for (i in 1..10) {
            val doc = app.get(
                "$mainUrl/search?keyword=$query&page_id=$i",
                headers = defaultHeaders
            ).document
            val page = doc.select("div.video-list").mapNotNull { it.toSearchResult() }
            results.addAll(page)
            if (page.isEmpty()) break
        }
        return results
    }

    // ── Load ──────────────────────────────────────────────────────────────────
    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url, headers = defaultHeaders).document

        val title = (document.selectFirst("meta[name=title]")?.attr("content")
            ?: document.title()).replace(" | UVideo", "").trim()
        if (title.isEmpty()) return null

        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")
        val tags   = document.select("div.pt_categories li a").map { it.text() }

        return if (url.contains("/articles/read/")) {
            // ── Halaman artikel ───────────────────────────────────────────────
            val description = document.selectFirst("div.read-article-description article")?.text()
            val videoLinks  = document.select("div.read-article-text a")
                .map { it.attr("href") }.filter { it.isNotBlank() }
            val recommendations = document.select("div.related-video-wrapper")
                .mapNotNull { it.toRelatedResult() }

            newMovieLoadResponse(title, url, TvType.Movie, videoLinks.toJson()) {
                posterUrl = poster; plot = description
                this.tags = tags; this.recommendations = recommendations
            }
        } else {
            // ── Halaman watch ─────────────────────────────────────────────────
            val description = document.select("div.watch-video-description p")
                .text().replace("\u2063", "").trim()
            val recommendations = document.select("div.related-video-wrapper")
                .mapNotNull { it.toRelatedResult() }

            // Video source tersedia langsung di <video#my-video source> saat
            // halaman di-request dengan cookie valid.
            // Struktur: <source src="URL" res="720" type="video/mp4">
            val videos = document.select("video#my-video source").mapNotNull { el ->
                val src = el.attr("src").trim()
                if (src.isEmpty()) return@mapNotNull null
                Video(
                    src  = src,
                    res  = el.attr("res").ifBlank { el.attr("data-quality").replace(Regex("[^0-9]"), "") },
                    type = el.attr("type").ifBlank { "video/mp4" }
                )
            }

            // Fallback 1: <video> lain (tanpa id spesifik)
            val finalVideos = if (videos.isNotEmpty()) videos else {
                document.select("video source").mapNotNull { el ->
                    val src = el.attr("src").trim()
                    if (src.isEmpty()) return@mapNotNull null
                    Video(src = src, res = el.attr("res"), type = el.attr("type").ifBlank { "video/mp4" })
                }
            }

            val dataJson = if (finalVideos.isNotEmpty()) finalVideos.toJson()
                           else {
                               // Fallback 2: jika kosong (belum login / wall), coba embed
                               val embedUrl = document.selectFirst("iframe[src*=/embed/]")?.attr("src")
                                   ?: document.selectFirst("iframe[src*=dubbindo]")?.attr("src")
                               listOfNotNull(embedUrl).toJson()
                           }

            newMovieLoadResponse(title, url, TvType.Movie, dataJson) {
                posterUrl = poster; plot = description
                this.tags = tags; this.recommendations = recommendations
            }
        }
    }

    // ── Load links ────────────────────────────────────────────────────────────
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val authedHeaders = mapOf(
            "Cookie"  to cookieHeader,
            "Referer" to mainUrl
        )

        val videos = tryParseJson<List<Video>>(data)
        if (videos != null) {
            videos.forEach { video ->
                val src = video.src ?: return@forEach
                when {
                    src.endsWith(".m3u8") || video.type == "application/x-mpegURL" ->
                        callback.invoke(
                            newExtractorLink(this.name, this.name, src, INFER_TYPE) {
                                this.quality = video.res?.toIntOrNull() ?: Qualities.Unknown.value
                                this.headers = authedHeaders
                            }
                        )
                    video.type.orEmpty().startsWith("video/") ->
                        callback.invoke(
                            newExtractorLink(this.name, this.name, src, INFER_TYPE) {
                                this.quality = video.res?.toIntOrNull() ?: Qualities.Unknown.value
                                this.headers = authedHeaders
                            }
                        )
                    else ->
                        loadExtractor(src, mainUrl, subtitleCallback, callback)
                }
            }
            return true
        }

        // Fallback: List<String> dari halaman artikel atau embed URL
        val urls = tryParseJson<List<String>>(data)
        if (urls != null) {
            urls.forEach { videoUrl ->
                if (videoUrl.isNotBlank())
                    loadExtractor(videoUrl, mainUrl, subtitleCallback, callback)
            }
            return true
        }

        return false
    }

    data class Video(
        val src: String? = null,
        val res: String? = null,
        val type: String? = null,
    )
}
