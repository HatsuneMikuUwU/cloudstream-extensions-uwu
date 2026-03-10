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
        "$mainUrl/videos/category/1" to "Movie",
        "$mainUrl/videos/category/3" to "TV Series",
        "$mainUrl/videos/category/5" to "Anime Series",
        "$mainUrl/videos/category/4" to "Anime Movie",
        "$mainUrl/videos/category/other" to "Other",
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val document = app.get("${request.data}?page_id=$page").document

        // FIX: Selector diperbarui sesuai struktur HTML aktual website.
        // Website menggunakan div.video-list sebagai container kartu video,
        // bukan div.video-wrapper seperti sebelumnya.
        val home = document.select("div.video-list")
            .mapNotNull {
                it.toSearchResult()
            }
        return newHomePageResponse(
            list = HomePageList(
                name = request.name,
                list = home,
                isHorizontalImages = true
            ),
            hasNext = true
        )
    }

    private fun Element.toSearchResult(): TvSeriesSearchResponse? {
        // FIX: Selector title disesuaikan dengan struktur HTML baru.
        // Judul ada di dalam div.video-list-title > a > h4
        val title = this.selectFirst("div.video-list-title h4, h4")?.text()?.trim() ?: return null
        // Ambil href dari link gambar/thumbnail (link pertama di dalam div.video-list-image)
        val href = this.selectFirst("div.video-list-image a")?.attr("href")
            ?: this.selectFirst("a")?.attr("href")
            ?: return null
        val posterUrl = fixUrlNull(this.selectFirst("img")?.attr("src"))
        return newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchResponse = mutableListOf<SearchResponse>()
        for (i in 1..10) {
            val document =
                app.get(
                    "$mainUrl/search?keyword=$query&page_id=$i",
                ).document

            // FIX: Selector search juga diperbarui ke div.video-list
            val results = document.select("div.video-list")
                .mapNotNull {
                    it.toSearchResult()
                }
            searchResponse.addAll(results)
            if (results.isEmpty()) break
        }
        return searchResponse
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        // FIX: Ambil title dari meta tag agar bisa dipakai di kedua jenis halaman,
        // lalu bersihkan nama site-nya.
        val title = (document.selectFirst("meta[name=title]")?.attr("content")
            ?: document.title())
            .replace(" | UVideo", "").trim()
        if (title.isEmpty()) return null

        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")
        val tags = document.select("div.pt_categories li a").map { it.text() }

        // FIX: Tangani dua jenis halaman detail:
        //  1. /articles/read/... → link video ada di div.read-article-text a
        //  2. /watch/...         → sumber video ada di video#my-video source
        return if (url.contains("/articles/read/")) {
            // --- Halaman Artikel ---
            val description = document.selectFirst("div.read-article-description article")?.text()

            // Kumpulkan semua link video/extractor dari body artikel
            val videoLinks = document.select("div.read-article-text a")
                .map { it.attr("href") }
                .filter { it.isNotBlank() }

            // Related videos di artikel menggunakan div.related-video-wrapper
            val recommendations = document.select("div.related-video-wrapper").mapNotNull {
                it.toRelatedResult()
            }

            newMovieLoadResponse(title, url, TvType.Movie, videoLinks.toJson()) {
                posterUrl = poster
                plot = description
                this.tags = tags
                this.recommendations = recommendations
            }
        } else {
            // --- Halaman Watch ---
            val description = document.select("div.watch-video-description p").text().replace("\u2063", "").trim()

            val recommendations = document.select("div.related-video-wrapper").mapNotNull {
                it.toRelatedResult()
            }

            val video = document.select("video#my-video source").map {
                Video(
                    it.attr("src"),
                    it.attr("res"),  // FIX: was "size" — atribut di HTML adalah "res"
                    it.attr("type"),
                )
            }

            newMovieLoadResponse(title, url, TvType.Movie, video.toJson()) {
                posterUrl = poster
                plot = description
                this.tags = tags
                this.recommendations = recommendations
            }
        }
    }

    // FIX: Fungsi khusus untuk mengambil related video dari div.related-video-wrapper
    // (struktur berbeda dari div.video-list di halaman utama)
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
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        // FIX: Coba parse sebagai List<Video> dulu (halaman watch)
        val videos = tryParseJson<List<Video>>(data)
        if (videos != null) {
            videos.map { video ->
                if (video.type == "video/mp4" || video.type == "video/x-msvideo" || video.type == "video/x-matroska") {
                    callback.invoke(
                        newExtractorLink(
                            this.name,
                            this.name,
                            video.src ?: return@map,
                            INFER_TYPE
                        ) {
                            this.quality = video.res?.toIntOrNull() ?: Qualities.Unknown.value
                        }
                    )
                } else {
                    loadExtractor(video.src ?: return@map, "", subtitleCallback, callback)
                }
            }
            return true
        }

        // FIX: Jika bukan List<Video>, coba parse sebagai List<String>
        // (halaman artikel dengan link extractor langsung)
        val urls = tryParseJson<List<String>>(data)
        if (urls != null) {
            urls.forEach { videoUrl ->
                if (videoUrl.isNotBlank()) {
                    loadExtractor(videoUrl, "", subtitleCallback, callback)
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
