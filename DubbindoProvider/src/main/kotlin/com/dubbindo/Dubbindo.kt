package com.dubbindo

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Document
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

    // ── Kredensial ────────────────────────────────────────────────────────────
    private val USERNAME = "HatsuneMikuUwU"
    private val PASSWORD = "weeabooifyprojects123456789"

    // ── Session ───────────────────────────────────────────────────────────────
    private var sessionCookie = ""

    private val baseHeaders get() = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 10; Mobile) AppleWebKit/537.36 Chrome/124.0 Mobile Safari/537.36",
        "Referer"    to "$mainUrl/"
    )

    private val authedHeaders get() = if (sessionCookie.isNotBlank())
        baseHeaders + mapOf("Cookie" to sessionCookie)
    else baseHeaders

    // ── Login ─────────────────────────────────────────────────────────────────

    private fun parseCookiePair(header: String): Pair<String, String>? {
        val part = header.split(";").firstOrNull()?.trim() ?: return null
        val eq   = part.indexOf('=')
        if (eq < 0) return null
        return part.substring(0, eq).trim() to part.substring(eq + 1).trim()
    }

    private suspend fun doLogin(): Boolean {
        // Step 1 – GET /login untuk PHPSESSID awal
        val getResp     = app.get("$mainUrl/login", headers = baseHeaders)
        val initCookies = getResp.headers
            .filter { it.first.equals("set-cookie", ignoreCase = true) }
            .mapNotNull { parseCookiePair(it.second) }
            .toMap().toMutableMap()

        val phpSessId = initCookies["PHPSESSID"].orEmpty()

        // Step 2 – POST kredensial
        val postResp = app.post(
            "$mainUrl/login",
            data = mapOf(
                "username"        to USERNAME,
                "password"        to PASSWORD,
                "remember_device" to "on"
            ),
            headers = baseHeaders + mapOf(
                "Cookie"       to if (phpSessId.isNotBlank()) "PHPSESSID=$phpSessId" else "",
                "Content-Type" to "application/x-www-form-urlencoded",
                "Origin"       to mainUrl,
                "Referer"      to "$mainUrl/login"
            ),
            allowRedirects = false
        )

        val allCookies = initCookies + postResp.headers
            .filter { it.first.equals("set-cookie", ignoreCase = true) }
            .mapNotNull { parseCookiePair(it.second) }
            .toMap()

        return if (!allCookies["user_id"].isNullOrBlank()) {
            sessionCookie = allCookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
            true
        } else false
    }

    private suspend fun ensureSession() {
        if (sessionCookie.isNotBlank()) return
        doLogin()
    }

    // ── Subscribe ─────────────────────────────────────────────────────────────

    /**
     * Ambil channel ID dari halaman, lalu POST ke /aj/subscribe.
     * Mengambil main_session dari halaman untuk header Referer yang benar.
     * Return true jika berhasil menemukan channel ID dan mengirim request.
     */
    private suspend fun subscribeChannel(document: Document, pageUrl: String): Boolean {
        // Cari channel ID dari tombol subscribe di halaman watch/channel
        val channelId = document
            .selectFirst("button.btn-subscribe[data-id], .subscribe-btn-container button[data-id]")
            ?.attr("data-id")?.trim()
            ?: document.selectFirst("input#profile-id")?.attr("value")?.trim()
            ?: return false

        if (channelId.isBlank()) return false

        val resp = app.post(
            "$mainUrl/aj/subscribe",
            data    = mapOf("user_id" to channelId),
            headers = authedHeaders + mapOf(
                "Content-Type"     to "application/x-www-form-urlencoded",
                "X-Requested-With" to "XMLHttpRequest",
                "Referer"          to pageUrl,
                "Origin"           to mainUrl
            )
        )

        return resp.isSuccessful
    }

    /** Deteksi apakah halaman masih terkunci (subscribe wall). */
    private fun isSubscribeWall(document: Document): Boolean {
        val playerArea = document.selectFirst("div.video-processing, div.video-player")
            ?.text().orEmpty()
        return playerArea.contains("subscribe to watch", ignoreCase = true) ||
               document.select("video#my-video source, video source").isEmpty()
    }

    // ── Main page ─────────────────────────────────────────────────────────────
    override val mainPage = mainPageOf(
        "$mainUrl/videos/category/1"     to "Movie",
        "$mainUrl/videos/category/3"     to "TV Series",
        "$mainUrl/videos/category/5"     to "Anime Series",
        "$mainUrl/videos/category/4"     to "Anime Movie",
        "$mainUrl/videos/category/other" to "Other",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        ensureSession()
        val document = app.get("${request.data}?page_id=$page", headers = authedHeaders).document
        val home = document.select("div.video-wrapper").mapNotNull { it.toCategoryResult() }
        return newHomePageResponse(
            list = HomePageList(name = request.name, list = home, isHorizontalImages = true),
            hasNext = home.isNotEmpty()
        )
    }

    // ── Element helpers ───────────────────────────────────────────────────────
    private fun Element.toCategoryResult(): TvSeriesSearchResponse? {
        val title = selectFirst("div.video-title h4")?.text()?.trim() ?: return null
        if (title.isEmpty()) return null
        val href = selectFirst("div.video-thumb a")?.attr("href")
            ?: selectFirst("a")?.attr("href") ?: return null
        return newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
            posterUrl     = fixUrlNull(selectFirst("div.video-thumb img")?.attr("src"))
            posterHeaders = mapOf("Referer" to mainUrl)
        }
    }

    private fun Element.toSearchResult(): TvSeriesSearchResponse? {
        val title = selectFirst("div.video-list-title h4")?.text()?.trim()
            ?: selectFirst("h4")?.text()?.trim() ?: return null
        if (title.isEmpty()) return null
        val href = selectFirst("div.video-list-image a")?.attr("href")
            ?: selectFirst("a")?.attr("href") ?: return null
        return newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
            posterUrl     = fixUrlNull(selectFirst("img")?.attr("src"))
            posterHeaders = mapOf("Referer" to mainUrl)
        }
    }

    private fun Element.toRelatedResult(): TvSeriesSearchResponse? {
        val title = selectFirst("div.video-title a")?.text()?.trim()
            ?: selectFirst("a")?.text()?.trim() ?: return null
        val href = selectFirst("div.ra-thumb a")?.attr("href")
            ?: selectFirst("a")?.attr("href") ?: return null
        return newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
            posterUrl     = fixUrlNull(selectFirst("img")?.attr("src"))
            posterHeaders = mapOf("Referer" to mainUrl)
        }
    }

    // ── Search ────────────────────────────────────────────────────────────────
    override suspend fun search(query: String): List<SearchResponse> {
        ensureSession()
        val results = mutableListOf<SearchResponse>()
        for (i in 1..10) {
            val page = app.get(
                "$mainUrl/search?keyword=$query&page_id=$i",
                headers = authedHeaders
            ).document.select("div.video-list").mapNotNull { it.toSearchResult() }
            results.addAll(page)
            if (page.isEmpty()) break
        }
        return results
    }

    // ── Load ──────────────────────────────────────────────────────────────────

    private fun parseVideoSources(doc: Document): List<Video> =
        doc.select("video#my-video source, video source").mapNotNull { el ->
            val src = el.attr("src").trim().ifEmpty { return@mapNotNull null }
            Video(
                src  = src,
                res  = el.attr("res").ifBlank { el.attr("data-quality").replace(Regex("[^0-9]"), "") },
                type = el.attr("type").ifBlank { "video/mp4" }
            )
        }

    /**
     * Ambil video sources dari URL, dengan logika:
     * 1. Load halaman
     * 2. Jika kena subscribe wall → subscribe → reload
     * 3. Jika masih wall → sesi expired → login ulang → subscribe → reload
     */
    private suspend fun fetchVideoSources(url: String): Pair<Document, List<Video>> {
        var doc    = app.get(url, headers = authedHeaders).document
        var videos = parseVideoSources(doc)

        if (videos.isNotEmpty()) return doc to videos

        // Kena subscribe wall — coba subscribe lalu reload
        if (isSubscribeWall(doc)) {
            subscribeChannel(doc, url)
            doc    = app.get(url, headers = authedHeaders).document
            videos = parseVideoSources(doc)
        }

        if (videos.isNotEmpty()) return doc to videos

        // Masih kosong — mungkin sesi expired, login ulang
        sessionCookie = ""
        if (doLogin()) {
            // Muat ulang halaman dengan sesi baru
            doc    = app.get(url, headers = authedHeaders).document
            videos = parseVideoSources(doc)

            // Masih kena wall setelah re-login → subscribe ulang dengan sesi baru
            if (videos.isEmpty() && isSubscribeWall(doc)) {
                subscribeChannel(doc, url)
                doc    = app.get(url, headers = authedHeaders).document
                videos = parseVideoSources(doc)
            }
        }

        return doc to videos
    }

    override suspend fun load(url: String): LoadResponse? {
        ensureSession()

        val (document, _) = fetchVideoSources(url).let { it } // load awal

        val title = (document.selectFirst("meta[name=title]")?.attr("content")
            ?: document.title()).replace(" | UVideo", "").trim()
        if (title.isEmpty()) return null

        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")
        val tags   = document.select("div.pt_categories li a").map { it.text() }

        return if (url.contains("/articles/read/")) {
            val description    = document.selectFirst("div.read-article-description article")?.text()
            val videoLinks     = document.select("div.read-article-text a")
                .map { it.attr("href") }.filter { it.isNotBlank() }
            val recommendations = document.select("div.related-video-wrapper")
                .mapNotNull { it.toRelatedResult() }
            newMovieLoadResponse(title, url, TvType.Movie, videoLinks.toJson()) {
                posterUrl = poster; plot = description
                this.tags = tags; this.recommendations = recommendations
            }
        } else {
            val description = document.select("div.watch-video-description p")
                .text().replace("\u2063", "").trim()
            val recommendations = document.select("div.related-video-wrapper")
                .mapNotNull { it.toRelatedResult() }

            // Jalankan fetchVideoSources untuk dapat dokumen + sources final
            val (_, videos) = fetchVideoSources(url)

            newMovieLoadResponse(title, url, TvType.Movie, videos.toJson()) {
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
        val streamHeaders = authedHeaders + mapOf("Referer" to mainUrl)

        val videos = tryParseJson<List<Video>>(data)
        if (videos != null) {
            videos.forEach { video ->
                val src = video.src ?: return@forEach
                if (src.endsWith(".m3u8") || video.type.orEmpty().startsWith("video/")
                    || video.type == "application/x-mpegURL") {
                    callback.invoke(
                        newExtractorLink(name, name, src, INFER_TYPE) {
                            quality = video.res?.toIntOrNull() ?: Qualities.Unknown.value
                            headers = streamHeaders
                        }
                    )
                } else {
                    loadExtractor(src, mainUrl, subtitleCallback, callback)
                }
            }
            return videos.isNotEmpty()
        }

        val urls = tryParseJson<List<String>>(data)
        if (urls != null) {
            urls.forEach { if (it.isNotBlank()) loadExtractor(it, mainUrl, subtitleCallback, callback) }
            return urls.isNotEmpty()
        }

        return false
    }

    data class Video(
        val src: String? = null,
        val res: String? = null,
        val type: String? = null,
    )
}
