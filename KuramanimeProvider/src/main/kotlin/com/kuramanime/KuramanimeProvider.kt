package com.kuramanime

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

class KuramanimeProvider : MainAPI() {
    override var mainUrl            = "https://v17.kuramanime.ink"
    override var name               = "Kuramanime"
    override val hasMainPage        = true
    override var lang               = "id"
    override val hasDownloadSupport = true
    override val supportedTypes     = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    companion object {
        fun getStatus(t: String?): ShowStatus? = when {
            t == null                    -> null
            t.contains("Sedang", true)   -> ShowStatus.Ongoing
            t.contains("Selesai", true)  -> ShowStatus.Completed
            else                         -> null
        }

        private fun toAnimeUrl(href: String): String =
            href.replace(Regex("/episode/\\d+.*$"), "")

        // Maps server option value → human-readable name for ExtractorLink
        private val SERVER_NAMES = mapOf(
            "kuramadrive" to "Kuramadrive",
            "doodstream"  to "DoodStream",
            "filemoon"    to "FileMoon",
            "mega"        to "MEGA",
            "rpmshare"    to "RPMShare",
            "streamp2p"   to "StreamP2P",
        )
    }

    // ─── Main Page ────────────────────────────────────────────────────────────

    override val mainPage = mainPageOf(
        "$mainUrl/quick/ongoing?order_by=updated&page="  to "Sedang Tayang",
        "$mainUrl/quick/finished?order_by=updated&page=" to "Selesai Tayang",
        "$mainUrl/anime?order_by=latest&page="           to "Anime Terbaru",
        "$mainUrl/quick/movie?order_by=updated&page="    to "Film Anime",
        "$mainUrl/quick/donghua?order_by=updated&page="  to "Donghua",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc  = app.get("${request.data}$page").document
        val home = doc.select("div.filter__gallery a, div.product__item").mapNotNull {
            it.toSearchResult()
        }.distinctBy { it.url }
        return newHomePageResponse(request.name, home)
    }

    // ─── Card parser ─────────────────────────────────────────────────────────
    // Confirmed HTML structure:
    // <a href="/anime/4514/slug/episode/10">
    //   <div class="product__sidebar__view__item set-bg" data-setbg="...">
    //     <div class="ep">Ep 10 / 12</div>
    //     <h5 class="sidebar-title-h5 px-2 py-2">Title</h5>
    //   </div>
    // </a>

    private fun Element.toSearchResult(): AnimeSearchResponse? {
        val anchor = when {
            tagName() == "a"   -> this
            tagName() == "div" -> selectFirst("a") ?: return null
            else               -> return null
        }
        val href = anchor.attr("abs:href").ifBlank { anchor.attr("href") }
        if (href.isBlank() || !href.contains("/anime/")) return null

        val animeUrl = toAnimeUrl(href)
        val card     = anchor.selectFirst("div.product__sidebar__view__item, div.product__item__pic") ?: anchor
        val poster   = card.attr("data-setbg").ifBlank {
            card.selectFirst("img")?.attr("abs:src").orEmpty()
        }
        val title = anchor.selectFirst("h5.sidebar-title-h5")?.text()
            ?: anchor.selectFirst("div.product__item__text h5 a")?.text()
            ?: anchor.selectFirst("h5")?.text()
            ?: return null

        val epText  = card.selectFirst("div.ep")?.text().orEmpty()
        val episode = Regex("Ep\\s*(\\d+)").find(epText)?.groupValues?.getOrNull(1)?.toIntOrNull()

        return newAnimeSearchResponse(title, animeUrl, TvType.Anime) {
            this.posterUrl = poster
            addSub(episode)
        }
    }

    // ─── Search ───────────────────────────────────────────────────────────────

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get(
            "$mainUrl/anime",
            params = mapOf("search" to query, "order_by" to "latest")
        ).document
        return doc.select("div.product__item, div.filter__gallery a").mapNotNull {
            it.toSearchResult()
        }
    }

    // ─── Anime Detail Page ────────────────────────────────────────────────────
    // Key findings from HTML scrape:
    //
    // TITLE    → div.anime__details__title h3  (strip inner <span> badges first)
    // POSTER   → div.anime__details__pic__mobile[data-setbg]   ← NOTE "__mobile" suffix
    //            fallback: meta[property=og:image]
    // SYNOPSIS → p#synopsisField
    //
    // WIDGET li structure:
    //   div.col-3 > span  = label (e.g. "Tipe:", "Status:", "Genre:", "Tayang:")
    //   div.col-9 > a(s)  = value(s)
    //
    // EPISODE LIST — two approaches confirmed:
    //   A) On the DETAIL page: embedded in  data-content  of  a#episodeLists
    //      as raw HTML with <a class='btn btn-sm btn-danger' href='…/episode/N'>
    //   B) On the PLAYER page: normal DOM at  div#animeEpisodes a.ep-button

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document

        // ── Title ─────────────────────────────────────────────────────────────
        val h3    = doc.selectFirst("div.anime__details__title h3")
        val title = h3?.apply { select("span").remove() }?.text()?.trim()
            ?: doc.title().substringBefore(" - Kuramanime").trim()

        // ── Poster ────────────────────────────────────────────────────────────
        val poster = doc.selectFirst("div.anime__details__pic__mobile[data-setbg]")
            ?.attr("data-setbg")
            ?: doc.selectFirst("meta[property=og:image]")?.attr("content")

        // ── Synopsis ──────────────────────────────────────────────────────────
        val description = doc.selectFirst("p#synopsisField")?.text()

        // ── Widget helpers ────────────────────────────────────────────────────
        val widgetLis = doc.select("div.anime__details__widget ul li")

        fun widgetText(label: String): String? =
            widgetLis.firstOrNull { li ->
                li.selectFirst("div.col-3 span")
                    ?.text()?.contains(label, ignoreCase = true) == true
            }?.selectFirst("div.col-9")?.let { col ->
                col.select("a").joinToString(", ") { it.text().trim() }
                    .ifBlank { col.text().trim() }
            }?.ifBlank { null }

        // ── Metadata ──────────────────────────────────────────────────────────
        val rawType = widgetText("Tipe")?.lowercase()
        val tvType  = when {
            rawType?.contains("movie")   == true -> TvType.AnimeMovie
            rawType?.contains("ova")     == true -> TvType.OVA
            rawType?.contains("ona")     == true -> TvType.OVA
            rawType?.contains("special") == true -> TvType.OVA
            else                                 -> TvType.Anime
        }
        val status = getStatus(widgetText("Status"))
        val tags   = widgetLis
            .firstOrNull { it.selectFirst("div.col-3 span")?.text()?.contains("Genre", true) == true }
            ?.select("div.col-9 a")?.map { it.text().trim() } ?: emptyList()
        val year   = widgetText("Tayang")
            ?.let { Regex("(\\d{4})").find(it)?.groupValues?.getOrNull(1)?.toIntOrNull() }

        // ── Episode list ──────────────────────────────────────────────────────
        // Strategy A: data-content attribute on a#episodeLists (detail page)
        val dataContent = doc.selectFirst("a#episodeLists")?.attr("data-content").orEmpty()
        val episodes = if (dataContent.isNotBlank()) {
            Jsoup.parseBodyFragment(dataContent)
                .select("a[href*=/episode/]")
                .mapIndexed { idx, el ->
                    val epHref = el.attr("href").let {
                        if (it.startsWith("http")) it else "$mainUrl$it"
                    }
                    val label  = el.text().trim().ifBlank { "Episode ${idx + 1}" }
                    val epNum  = Regex("(\\d+)").find(label)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: (idx + 1)
                    newEpisode(epHref) { name = label; episode = epNum }
                }
        } else {
            // Strategy B: div#animeEpisodes a.ep-button (player page DOM — also works on detail page as fallback)
            doc.select("div#animeEpisodes a.ep-button, a[href*=/episode/][type=episode]")
                .mapIndexed { idx, el ->
                    val epHref = el.attr("abs:href").ifBlank { "$mainUrl${el.attr("href")}" }
                    val label  = el.ownText().trim().ifBlank { "Episode ${idx + 1}" }
                    val epNum  = Regex("(\\d+)").find(label)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: (idx + 1)
                    newEpisode(epHref) { name = label; episode = epNum }
                }
        }

        return newAnimeLoadResponse(title, url, tvType) {
            this.posterUrl  = poster
            this.plot       = description
            this.tags       = tags
            this.showStatus = status
            this.year       = year
            addEpisodes(DubStatus.Subbed, episodes)
        }
    }

    // ─── Load Links (Player Page) ─────────────────────────────────────────────
    //
    // KEY FINDINGS from player page HTML scrape:
    //
    // 1. The #animeVideoPlayer div is EMPTY in static HTML — video is loaded via JS AJAX.
    //    There is NO iframe or direct video URL in the page source.
    //
    // 2. Critical hidden inputs:
    //    <input id="animeId"  value="4513">
    //    <input id="postId"   value="46435">   ← used for API calls (NOT episode ID from URL)
    //    <input id="kdriveServer" value="s1">
    //    <input id="checkEp"  value=".../anime/4513/episode/10/check-episode">
    //    <input id="driveCheckPingRoute"  value=".../drive-check/ping?post_id=46435">
    //    <input id="driveCheckQuotaRoute" value=".../drive-check/quota?post_id=46435">
    //
    // 3. Available servers in <select id="changeServer">:
    //    kuramadrive (default), doodstream, filemoon, mega, rpmshare, streamp2p
    //
    // 4. Episode list on player page IS in normal DOM:
    //    div#animeEpisodes a.ep-button[type=episode]
    //
    // 5. Stream loading strategy:
    //    a) POST to /anime/{animeId}/episode/{epNum}/check-episode  with server + CSRF
    //    b) Fallback: GET /anime/{animeId}/episode/{epNum}/stream?server={name}
    //    c) For external servers: response contains embed URL → loadExtractor()
    //    d) For kuramadrive: response contains direct HLS/MP4 URL → ExtractorLink

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val doc = app.get(data).document

        // ── Read page metadata ────────────────────────────────────────────────
        val csrfToken = doc.selectFirst("meta[name=csrf-token]")?.attr("content").orEmpty()
        val animeId   = doc.selectFirst("input#animeId")?.attr("value").orEmpty()
        val postId    = doc.selectFirst("input#postId")?.attr("value").orEmpty()

        // Episode number from URL: .../episode/10 → "10"
        val epNum = Regex("""/episode/(\d+)""").find(data)?.groupValues?.getOrNull(1).orEmpty()

        // Check-episode endpoint (confirmed from hidden input)
        val checkEpUrl = doc.selectFirst("input#checkEp")?.attr("value")
            ?: "$mainUrl/anime/$animeId/episode/$epNum/check-episode"

        // ── Available servers from <select id="changeServer"> ─────────────────
        // Confirmed options: kuramadrive, doodstream, filemoon, mega, rpmshare, streamp2p
        val servers = doc.select("select#changeServer option").map { it.attr("value") }
            .filter { it.isNotBlank() }
            .ifEmpty { SERVER_NAMES.keys.toList() }

        // ── Fetch stream URL for each server ─────────────────────────────────
        val baseHeaders = mapOf(
            "X-CSRF-TOKEN"     to csrfToken,
            "X-Requested-With" to "XMLHttpRequest",
            "Referer"          to data,
            "Accept"           to "application/json, text/plain, */*",
        )

        for (server in servers) {
            runCatching {
                // Primary: POST to check-episode endpoint
                val resp = app.post(
                    checkEpUrl,
                    headers = baseHeaders,
                    data    = mapOf(
                        "server"   to server,
                        "post_id"  to postId,
                        "_token"   to csrfToken,
                    ),
                ).text

                handleStreamResponse(resp, server, data, subtitleCallback, callback)
            }

            // Secondary: try GET stream endpoint as fallback
            runCatching {
                val streamUrl = "$mainUrl/anime/$animeId/episode/$epNum/stream?server=$server"
                val resp = app.get(streamUrl, headers = baseHeaders).text
                handleStreamResponse(resp, server, data, subtitleCallback, callback)
            }
        }

        // ── Fallback: scan page for any iframe or direct video src ────────────
        // (In case the AJAX loaded something before our static scrape)
        doc.select("iframe[src]").forEach { iframe ->
            val src = iframe.attr("abs:src")
            if (src.isNotBlank()) loadExtractor(src, data, subtitleCallback, callback)
        }
        doc.select("video source[src], video[src]").forEach { vid ->
            val src = vid.attr("abs:src").ifBlank { vid.attr("abs:data-src") }
            if (src.isNotBlank()) callback(
                newExtractorLink(
                    source  = name,
                    name    = name,
                    url     = src,
                ) {
                    this.referer = data
                    this.quality = Qualities.Unknown.value
                    this.isM3u8  = src.contains(".m3u8")
                }
            )
        }

        return true
    }

    // ─── Parse server response ────────────────────────────────────────────────
    // Response is JSON that may contain:
    //   { "url": "https://...", "server": "doodstream" }
    //   { "stream_url": "https://..." }
    //   { "embed": "https://..." }
    //   or an array of source objects: [{"src":"...","label":"720p"}, ...]

    private suspend fun handleStreamResponse(
        resp: String,
        server: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        if (resp.isBlank() || resp.startsWith("<")) return   // HTML error page

        // Try as array of {src, label} (kuramadrive HLS multi-quality)
        tryParseJson<List<StreamSource>>(resp)?.forEach { source ->
            val src = source.src.trim()
            if (src.isBlank()) return@forEach
            if (src.startsWith("http")) {
                if (isDirectMedia(src)) {
                    callback(
                        newExtractorLink(
                            source = name,
                            name   = "$name ${SERVER_NAMES[server] ?: server} ${source.label ?: ""}".trim(),
                            url    = src,
                        ) {
                            this.referer = referer
                            this.quality = source.label.toQuality()
                            this.isM3u8  = src.contains(".m3u8")
                        }
                    )
                } else {
                    loadExtractor(src, referer, subtitleCallback, callback)
                }
            }
        }

        // Try as single JSON object
        tryParseJson<StreamResponse>(resp)?.let { r ->
            val src = (r.url ?: r.stream_url ?: r.embed ?: r.src).orEmpty().trim()
            if (src.startsWith("http")) {
                if (isDirectMedia(src)) {
                    callback(
                        newExtractorLink(
                            source = name,
                            name   = "$name ${SERVER_NAMES[server] ?: server}",
                            url    = src,
                        ) {
                            this.referer = referer
                            this.quality = Qualities.Unknown.value
                            this.isM3u8  = src.contains(".m3u8")
                        }
                    )
                } else {
                    loadExtractor(src, referer, subtitleCallback, callback)
                }
            }
        }

        // Subtitles
        tryParseJson<List<SubSource>>(resp)
            ?.filter { it.kind?.lowercase() == "subtitles" }
            ?.forEach { sub ->
                if (sub.src.isNotBlank())
                    subtitleCallback(SubtitleFile(sub.label ?: sub.srclang ?: "Indonesian", sub.src))
            }
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private fun isDirectMedia(url: String): Boolean =
        url.contains(".m3u8") || url.contains(".mp4") || url.contains(".mkv")

    private fun String?.toQuality(): Int = when {
        this == null     -> Qualities.Unknown.value
        contains("1080") -> Qualities.P1080.value
        contains("720")  -> Qualities.P720.value
        contains("480")  -> Qualities.P480.value
        contains("360")  -> Qualities.P360.value
        else             -> Qualities.Unknown.value
    }

    // ─── Data classes ──────────────────────────────────────────────────────────

    private data class StreamSource(
        val src:   String = "",
        val label: String? = null,
        val type:  String? = null,
    )

    private data class StreamResponse(
        val url:        String? = null,
        val stream_url: String? = null,
        val embed:      String? = null,
        val src:        String? = null,
    )

    private data class SubSource(
        val src:     String = "",
        val kind:    String? = null,
        val srclang: String? = null,
        val label:   String? = null,
    )
}
