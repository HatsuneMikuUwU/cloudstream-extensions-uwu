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

    override val mainPage = mainPageOf(
        "$mainUrl/videos/category/1"     to "Movie",
        "$mainUrl/videos/category/3"     to "TV Series",
        "$mainUrl/videos/category/5"     to "Anime Series",
        "$mainUrl/videos/category/4"     to "Anime Movie",
        "$mainUrl/videos/category/other" to "Other",
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        // Pagination menggunakan ?page_id= (konfirmasi dari HTML)
        val document = app.get("${request.data}?page_id=$page").document

        // FIX: Halaman category menggunakan "div.video-wrapper" bukan "div.video-list"
        val home = document.select("div.video-wrapper")
            .mapNotNull { it.toCategoryResult() }

        return newHomePageResponse(
            list = HomePageList(
                name = request.name,
                list = home,
                isHorizontalImages = true
            ),
            hasNext = home.isNotEmpty()
        )
    }

    // Untuk kartu di halaman category: div.video-wrapper
    // Struktur: div.video-thumb > a[href] + img[src]
    //           div.video-title > a > h4
    private fun Element.toCategoryResult(): TvSeriesSearchResponse? {
        val title = this.selectFirst("div.video-title h4")?.text()?.trim()
            ?: return null
        if (title.isEmpty()) return null

        val href = this.selectFirst("div.video-thumb a")?.attr("href")
            ?: this.selectFirst("a")?.attr("href")
            ?: return null

        val posterUrl = fixUrlNull(
            this.selectFirst("div.video-thumb img")?.attr("src")
        )

        return newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
            this.posterUrl = posterUrl
            this.posterHeaders = mapOf("Referer" to "https://www.dubbindo.site")
        }
    }

    // Untuk kartu di hasil search: div.video-list
    private fun Element.toSearchResult(): TvSeriesSearchResponse? {
        val title = this.selectFirst("div.video-list-title h4")?.text()?.trim()
            ?: this.selectFirst("h4")?.text()?.trim()
            ?: return null
        if (title.isEmpty()) return null

        val href = this.selectFirst("div.video-list-image a")?.attr("href")
            ?: this.selectFirst("a")?.attr("href")
            ?: return null

        val posterUrl = fixUrlNull(this.selectFirst("img")?.attr("src"))

        return newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
            this.posterUrl = posterUrl
            this.posterHeaders = mapOf("Referer" to "https://www.dubbindo.site")
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchResponse = mutableListOf<SearchResponse>()
        for (i in 1..10) {
            val document = app.get("$mainUrl/search?keyword=$query&page_id=$i").document
            val results = document.select("div.video-list")
                .mapNotNull { it.toSearchResult() }
            searchResponse.addAll(results)
            if (results.isEmpty()) break
        }
        return searchResponse
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url, headers = mapOf("Referer" to mainUrl)).document

        val title = (document.selectFirst("meta[name=title]")?.attr("content")
            ?: document.title())
            .replace(" | UVideo", "").trim()
        if (title.isEmpty()) return null

        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")
        val tags = document.select("div.pt_categories li a").map { it.text() }

        return if (url.contains("/articles/read/")) {
            // --- Halaman Artikel ---
            val description = document.selectFirst("div.read-article-description article")?.text()
            val videoLinks = document.select("div.read-article-text a")
                .map { it.attr("href") }
                .filter { it.isNotBlank() }
            val recommendations = document.select("div.related-video-wrapper")
                .mapNotNull { it.toRelatedResult() }

            newMovieLoadResponse(title, url, TvType.Movie, videoLinks.toJson()) {
                posterUrl = poster
                plot = description
                this.tags = tags
                this.recommendations = recommendations
            }
        } else {
            // --- Halaman Watch ---
            val description = document.select("div.watch-video-description p")
                .text().replace("\u2063", "").trim()
            val recommendations = document.select("div.related-video-wrapper")
                .mapNotNull { it.toRelatedResult() }

            // FIX: Video tidak di <source> tag — URL-nya di-inject JS di halaman embed.
            // JS pattern: $('video').attr('src', 'uvideoweb/movie/xxx.mp4')
            // Base URL S3: https://s3.dubbindo.my.id/
            val embedUrl = document.selectFirst("iframe[src*=/embed/]")?.attr("src")
                ?: document.selectFirst("iframe[src*=dubbindo]")?.attr("src")

            val video = if (embedUrl != null) {
                val embedHtml = app.get(
                    embedUrl,
                    headers = mapOf("Referer" to url)
                ).text

                // Regex ekstrak path dari JS: $('video').attr('src', 'PATH')
                val srcRegex = Regex("""[^a-z]src',\s*'([^']+\.(mp4|mkv|avi))'""")
                val match = srcRegex.find(embedHtml)?.groupValues?.getOrNull(1)

                if (match != null) {
                    val fullSrc = if (match.startsWith("http")) match
                                  else "https://s3.dubbindo.my.id/$match"
                    listOf(Video(fullSrc, null, "video/mp4"))
                } else {
                    // Fallback: cari di <source> tag
                    org.jsoup.Jsoup.parse(embedHtml)
                        .select("video source")
                        .map { Video(it.attr("src"), it.attr("res"), it.attr("type")) }
                }
            } else emptyList()

            val dataJson = if (video.isNotEmpty()) video.toJson()
                           else listOfNotNull(embedUrl).toJson()

            newMovieLoadResponse(title, url, TvType.Movie, dataJson) {

                posterUrl = poster
                plot = description
                this.tags = tags
                this.recommendations = recommendations
            }
        }
    }

    // Related video di sidebar: div.related-video-wrapper
    private fun Element.toRelatedResult(): TvSeriesSearchResponse? {
        val title = this.selectFirst("div.video-title a")?.text()?.trim()
            ?: this.selectFirst("a")?.text()?.trim()
            ?: return null
        val href = this.selectFirst("div.ra-thumb a")?.attr("href")
            ?: this.selectFirst("a")?.attr("href")
            ?: return null
        val posterUrl = fixUrlNull(this.selectFirst("img")?.attr("src"))
        return newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
            this.posterUrl = posterUrl
            this.posterHeaders = mapOf("Referer" to "https://www.dubbindo.site")
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Parse sebagai List<Video> → halaman watch (mp4 langsung)
        val videos = tryParseJson<List<Video>>(data)
        if (videos != null) {
            videos.map { video ->
                val src = video.src ?: return@map
                if (video.type == "video/mp4" || video.type == "video/x-msvideo" || video.type == "video/x-matroska") {
                    // FIX: Tambah Referer header — s3.dubbindo.my.id pakai hotlink protection
                    callback.invoke(
                        newExtractorLink(
                            this.name,
                            this.name,
                            src,
                            INFER_TYPE
                        ) {
                            this.quality = video.res?.toIntOrNull() ?: Qualities.Unknown.value
                            this.headers = mapOf("Referer" to "https://www.dubbindo.site")
                        }
                    )
                } else {
                    // Eksternal extractor: kirim referer dubbindo.site
                    loadExtractor(src, "https://www.dubbindo.site", subtitleCallback, callback)
                }
            }
            return true
        }

        // Parse sebagai List<String> → halaman artikel (link extractor)
        val urls = tryParseJson<List<String>>(data)
        if (urls != null) {
            urls.forEach { videoUrl ->
                if (videoUrl.isNotBlank()) {
                    loadExtractor(videoUrl, "https://www.dubbindo.site", subtitleCallback, callback)
                }
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
