package com.hexated

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addAniListId
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URI

class KuramanimeProvider : MainAPI() {
    override var mainUrl = "https://v9.kuramanime.tel"
    override var name = "Kuramanime"
    override val hasQuickSearch = false
    override val hasMainPage = true
    override var lang = "id"
    override var sequentialMainPage = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.OVA
    )

    companion object {
        private var cookies: Map<String, String> = mapOf()

        fun getType(t: String, s: Int): TvType {
            return if (t.contains("OVA", true) || t.contains("Special")) TvType.OVA
            else if (t.contains("Movie", true) && s == 1) TvType.AnimeMovie
            else TvType.Anime
        }

        fun getStatus(t: String): ShowStatus {
            return when (t) {
                "Selesai Tayang" -> ShowStatus.Completed
                "Sedang Tayang" -> ShowStatus.Ongoing
                else -> ShowStatus.Completed
            }
        }
    }

    override val mainPage = mainPageOf(
        "$mainUrl/anime/ongoing?order_by=updated&page=" to "Sedang Tayang",
        "$mainUrl/anime/finished?order_by=updated&page=" to "Selesai Tayang",
        "$mainUrl/properties/season/summer-2022?order_by=most_viewed&page=" to "Dilihat Terbanyak Musim Ini",
        "$mainUrl/anime/movie?order_by=updated&page=" to "Film Layar Lebar",
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val document = app.get(request.data + page).document
        val home = document.select("div#animeList div.product__item").mapNotNull {
            it.toSearchResult()
        }

        return newHomePageResponse(request.name, home)
    }

    private fun getProperAnimeLink(uri: String): String {
        return if (uri.contains("/episode")) {
            Regex("(.*)/episode/.+").find(uri)?.groupValues?.get(1).toString() + "/"
        } else {
            uri
        }
    }

    private fun Element.toSearchResult(): AnimeSearchResponse? {
        val href = getProperAnimeLink(fixUrl(this.selectFirst("a")!!.attr("href")))
        val title = this.selectFirst("h5 a")?.text() ?: return null
        val posterUrl = fixUrl(this.select("div.product__item__pic.set-bg").attr("data-setbg"))
        val episode = this.select("div.ep span").text().let {
            Regex("Ep\\s(\\d+)\\s/").find(it)?.groupValues?.getOrNull(1)?.toIntOrNull()
        }

        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = posterUrl
            addSub(episode)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return app.get(
            "$mainUrl/anime?search=$query&order_by=latest"
        ).document.select("div#animeList div.product__item").mapNotNull {
            it.toSearchResult()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val title = document.selectFirst(".anime__details__title > h3")!!.text().trim()
        val poster = document.selectFirst(".anime__details__pic")?.attr("data-setbg")
        val tags =
            document.select("div.anime__details__widget > div > div:nth-child(2) > ul > li:nth-child(1)")
                .text().trim().replace("Genre: ", "").split(", ")

        val year = Regex("\\D").replace(
            document.select("div.anime__details__widget > div > div:nth-child(1) > ul > li:nth-child(5)")
                .text().trim().replace("Musim: ", ""), ""
        ).toIntOrNull()
        val status = getStatus(
            document.select("div.anime__details__widget > div > div:nth-child(1) > ul > li:nth-child(3)")
                .text().trim().replace("Status: ", "")
        )
        val description = document.select(".anime__details__text > p").text().trim()

        val episodes = mutableListOf<Episode>()

        for (i in 1..30) {
            val doc = app.get("$url?page=$i").document
            val eps = Jsoup.parse(doc.select("#episodeLists").attr("data-content"))
                .select("a.btn.btn-sm.btn-danger")
                .mapNotNull {
                    val name = it.text().trim()
                    val episode = Regex("(\\d+[.,]?\\d*)").find(name)?.groupValues?.getOrNull(0)
                        ?.toIntOrNull()
                    val link = it.attr("href")
                    newEpisode(link) { this.episode = episode }
                }
            if (eps.isEmpty()) break else episodes.addAll(eps)
        }

        val type = getType(
            document.selectFirst("div.col-lg-6.col-md-6 ul li:contains(Tipe:) a")?.text()
                ?.lowercase() ?: "tv", episodes.size
        )
        val recommendations = document.select("div#randomList > a").mapNotNull {
            val epHref = it.attr("href")
            val epTitle = it.select("h5.sidebar-title-h5.px-2.py-2").text()
            val epPoster = it.select(".product__sidebar__view__item.set-bg").attr("data-setbg")
            newAnimeSearchResponse(epTitle, epHref, TvType.Anime) {
                this.posterUrl = epPoster
                addDubStatus(dubExist = false, subExist = true)
            }
        }

        val tracker = APIHolder.getTracker(listOf(title), TrackerType.getTypes(type), year, true)

        return newAnimeLoadResponse(title, url, type) {
            engName = title
            posterUrl = tracker?.image ?: poster
            backgroundPosterUrl = tracker?.cover
            this.year = year
            addEpisodes(DubStatus.Subbed, episodes)
            showStatus = status
            plot = description
            this.tags = tags
            this.recommendations = recommendations
            addMalId(tracker?.malId)
            addAniListId(tracker?.aniId?.toIntOrNull())
        }
    }

    private suspend fun invokeLocalSource(
        url: String,
        server: String,
        headers: Map<String, String>,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val response = app.get(
            url,
            headers = headers,
            cookies = cookies
        )

        val document = response.document
        val responseText = response.text

        // 1. Metode Standar: Cari tag HTML Video
        document.select("video source").map {
            val link = fixUrl(it.attr("src"))
            val quality = it.attr("size").toIntOrNull()
            callback.invoke(
                newExtractorLink(
                    "$name $server",
                    "$name $server",
                    link,
                    INFER_TYPE
                ) {
                    this.headers = mapOf("Referer" to url)
                    this.quality = quality ?: Qualities.Unknown.value
                }
            )
        }

        // 2. Metode Agresif: Cari URL di dalam Script JS (JWPlayer/Clappr hidden sources)
        // Mencari pola: file: "..." atau src: "..." yang berakhiran m3u8 atau mp4
        val scriptRegex = Regex("""(?i)(file|src|source)\s*:\s*["']([^"']+\.(?:m3u8|mp4))["']""")
        
        scriptRegex.findAll(responseText).forEach { match ->
            val link = fixUrl(match.groupValues[2].replace("\\", "")) // Bersihkan backslash escape
            
            callback.invoke(
                newExtractorLink(
                    "$name $server (Script)",
                    "$name $server (Script)",
                    link,
                    INFER_TYPE
                ) {
                    this.headers = mapOf("Referer" to url)
                    this.quality = Qualities.Unknown.value
                }
            )
        }

        // 3. Fallback Download Link (Khusus KuramaDrive)
        if (server.contains("kuramadrive", true)) {
            document.select("div#animeDownloadLink a, div.download-link a").amap {
                loadExtractor(it.attr("href"), "$mainUrl/", subtitleCallback, callback)
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val req = app.get(data)
        val res = req.document
        cookies = req.cookies

        // 1. Ambil CSRF Token
        val token = res.selectFirst("meta[name=csrf-token]")?.attr("content") ?: return false
        
        // 2. Ambil ID Script (data-kk atau data-kps)
        val scriptData = res.selectFirst("div[data-kk]")?.attr("data-kk") 
            ?: res.selectFirst("div[data-kps]")?.attr("data-kps") 
            ?: return false 

        val assets = getAssets(scriptData)

        // 3. Header Dasar
        val baseHeaders = mapOf(
            "X-CSRF-TOKEN" to token,
            "X-Requested-With" to "XMLHttpRequest",
            "Referer" to data,
            "Origin" to mainUrl
        )

        // Header khusus Auth
        val authHeaders = baseHeaders + mapOf(
            "X-Fuck-ID" to "${assets.MIX_AUTH_KEY}:${assets.MIX_AUTH_TOKEN}",
            "X-Request-ID" to randomId(),
            "X-Request-Index" to "0",
        )

        // 4. Request Page Token Key
        val tokenKeyUrl = "$mainUrl/${assets.MIX_PREFIX_AUTH_ROUTE_PARAM}${assets.MIX_AUTH_ROUTE_PARAM}"
        val tokenKeyResponse = app.get(tokenKeyUrl, headers = authHeaders, cookies = cookies)
        val tokenKey = tokenKeyResponse.text.trim()

        if (tokenKey.isBlank()) return false 

        // 5. Ambil Daftar Server
        val serverElements = res.select("select#changeServer option")
        
        if (serverElements.isEmpty()) {
            // Jika dropdown server kosong, coba cek iframe langsung
            val directIframe = res.select("div.iframe-container iframe").attr("src")
            if (directIframe.isNotBlank()) {
                loadExtractor(fixUrl(directIframe), "$mainUrl/", subtitleCallback, callback)
                return true
            }
            return false
        }

        val streamHeaders = baseHeaders + mapOf(
            "Alt-Used" to URI(mainUrl).host
        )

        // 6. Loop setiap server
        serverElements.amap { source ->
            val server = source.attr("value")
            val link = "$data?${assets.MIX_PAGE_TOKEN_KEY}=$tokenKey&${assets.MIX_STREAM_SERVER_KEY}=$server"
            
            // Server Kurama/Archive biasanya local source, sisanya iframe
            if (server.contains(Regex("(?i)kurama|archive"))) {
                invokeLocalSource(link, server, streamHeaders, subtitleCallback, callback)
            } else {
                try {
                    val iframeReq = app.get(
                        link,
                        referer = data,
                        headers = streamHeaders,
                        cookies = cookies
                    )
                    
                    val iframeSrc = iframeReq.document.select("iframe").attr("src")
                    if (iframeSrc.isNotBlank()) {
                        loadExtractor(fixUrl(iframeSrc), "$mainUrl/", subtitleCallback, callback)
                    }
                } catch (e: Exception) {
                    // Abaikan jika satu server gagal, lanjut ke server lain
                }
            }
        }

        return true
    }

    private suspend fun getAssets(bpjs: String?): Assets {
        val env = app.get("$mainUrl/assets/js/$bpjs.js").text
        
        // Regex robust: menangani variasi spasi dan kutip (' atau ")
        fun extract(key: String): String {
            val regex = Regex("""$key\s*:\s*['"]([^'"]+)['"]""")
            return regex.find(env)?.groupValues?.get(1) ?: ""
        }

        return Assets(
            MIX_PREFIX_AUTH_ROUTE_PARAM = extract("MIX_PREFIX_AUTH_ROUTE_PARAM"),
            MIX_AUTH_ROUTE_PARAM = extract("MIX_AUTH_ROUTE_PARAM"),
            MIX_AUTH_KEY = extract("MIX_AUTH_KEY"),
            MIX_AUTH_TOKEN = extract("MIX_AUTH_TOKEN"),
            MIX_PAGE_TOKEN_KEY = extract("MIX_PAGE_TOKEN_KEY"),
            MIX_STREAM_SERVER_KEY = extract("MIX_STREAM_SERVER_KEY")
        )
    }

    private fun randomId(length: Int = 6): String {
        val allowedChars = ('a'..'z') + ('A'..'Z') + ('0'..'9')
        return (1..length)
            .map { allowedChars.random() }
            .joinToString("")
    }

    data class Assets(
        val MIX_PREFIX_AUTH_ROUTE_PARAM: String,
        val MIX_AUTH_ROUTE_PARAM: String,
        val MIX_AUTH_KEY: String,
        val MIX_AUTH_TOKEN: String,
        val MIX_PAGE_TOKEN_KEY: String,
        val MIX_STREAM_SERVER_KEY: String,
    )
}
