package com.kuramanime

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

class KuramanimeProvider : MainAPI() {
    override var mainUrl = "https://v17.kuramanime.ink"
    override var name = "Kuramanime"
    override val hasQuickSearch = false
    override val hasMainPage = true
    override var lang = "id"
    override var sequentialMainPage = true
    override val hasDownloadSupport = true
    
    private var authorization: String? = null

    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.OVA
    )

    // --- MAIN PAGE & SEARCH (Dikembalikan lagi) ---

    override val mainPage = mainPageOf(
        "$mainUrl/anime/ongoing?order_by=updated&page=" to "Sedang Tayang",
        "$mainUrl/anime/finished?order_by=updated&page=" to "Selesai Tayang",
        "$mainUrl/anime/movie?order_by=updated&page=" to "Film Layar Lebar",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(request.data + page).document
        val home = document.select("div#animeList div.product__item").mapNotNull {
            it.toSearchResult()
        }
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): AnimeSearchResponse? {
        val a = this.selectFirst("a") ?: return null
        val href = fixUrl(a.attr("href")).removeSuffix("/") + "/"
        val title = this.selectFirst("h5 a")?.text() ?: this.selectFirst("h5")?.text() ?: return null
        val posterUrl = fixUrl(this.select("div.product__item__pic").attr("data-setbg"))

        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return app.get("$mainUrl/anime?search=$query&order_by=latest").document
            .select("div#animeList div.product__item").mapNotNull {
                it.toSearchResult()
            }
    }

    // --- LOAD & LINKS (Sudah Fix Error Compile) ---

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        val title = document.selectFirst("#episodeTitle")?.text()
            ?.substringBefore(" (Episode")?.trim() 
            ?: document.selectFirst(".anime__details__title > h3")?.text()?.trim() ?: ""
            
        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")
        val description = document.select(".anime__details__text > p").text().trim()
        
        val episodes = mutableListOf<Episode>()
        // Pakai selector #animeEpisodes sesuai HTML yang kamu kasih
        document.select("#animeEpisodes a").forEach {
            val name = it.text().trim()
            val epsNum = Regex("(\\d+)").find(name)?.groupValues?.get(1)?.toIntOrNull()
            episodes.add(newEpisode(fixUrl(it.attr("href"))) {
                this.episode = epsNum
                this.name = name
            })
        }
        episodes.sortBy { it.episode }

        return newAnimeLoadResponse(title, url, TvType.Anime) {
            this.posterUrl = poster
            this.plot = description
            addEpisodes(DubStatus.Subbed, episodes)
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
        val cookies = req.cookies

        val token = res.selectFirst("meta[name=csrf-token]")?.attr("content") ?: ""
        val dataKps = res.selectFirst("div[data-kk]")?.attr("data-kk")

        val auth = getAuth()
        val assets = getAssets(dataKps) // Fungsi Assets dipanggil lagi

        val headers = mapOf(
            "X-CSRF-TOKEN" to token,
            "X-Requested-With" to "XMLHttpRequest",
            "Authorization" to "Bearer $auth",
            "Referer" to data
        )

        res.select("select#changeServer option").forEach { source ->
            val serverValue = source.attr("value")
            val serverName = source.text().trim()
            val apiUrl = "$data?server=$serverValue"
            
            val response = app.get(apiUrl, headers = headers, cookies = cookies)
            val videoDoc = Jsoup.parse(response.text)
            
            // Fix parameter mismatch & isM3u8 error
            videoDoc.select("video source").forEach {
                val videoUrl = it.attr("src")
                val quality = it.attr("size").toIntOrNull() ?: Qualities.Unknown.value
                
                callback.invoke(
                    newExtractorLink(
                        source = "Kurama $serverName",
                        name = "Kurama $serverName",
                        url = fixUrl(videoUrl),
                        referer = data,
                        quality = quality,
                        type = if (videoUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    )
                )
            }

            videoDoc.select("iframe").attr("src").let { iframeUrl ->
                if (iframeUrl.isNotEmpty()) {
                    loadExtractor(fixUrl(iframeUrl), data, subtitleCallback, callback)
                }
            }
        }
        return true
    }

    // --- HELPER & AUTH ---

    private suspend fun getAssets(bpjs: String?): Assets {
        if (bpjs == null) return Assets(null, null, null, null, null, null)
        val env = app.get("$mainUrl/assets/js/$bpjs.js").text
        fun String.findKey(key: String) = Regex("$key:\\s*'(.*?)'").find(this)?.groupValues?.get(1)

        return Assets(
            env.findKey("MIX_PREFIX_AUTH_ROUTE_PARAM"),
            env.findKey("MIX_AUTH_ROUTE_PARAM"),
            env.findKey("MIX_AUTH_KEY"),
            env.findKey("MIX_AUTH_TOKEN"),
            env.findKey("MIX_PAGE_TOKEN_KEY"),
            env.findKey("MIX_STREAM_SERVER_KEY")
        )
    }

    private suspend fun getAuth(): String {
        return authorization ?: fetchAuth().also { authorization = it }
    }

    private suspend fun fetchAuth(): String {
        val res = app.get("$mainUrl/storage/leviathan.js").text
        val authArray = Regex("""'(.*?)'""").findAll(res).map { it.groupValues[1] }.toList()
        return if (authArray.size >= 10) {
            "${authArray.last()}${authArray[9]}${authArray[1]}${authArray.first()}i"
        } else {
            "KFhElffuFYZZHAqqBqlGewkwbaaFUtJS"
        }
    }

    data class Assets(
        val MIX_PREFIX_AUTH_ROUTE_PARAM: String?,
        val MIX_AUTH_ROUTE_PARAM: String?,
        val MIX_AUTH_KEY: String?,
        val MIX_AUTH_TOKEN: String?,
        val MIX_PAGE_TOKEN_KEY: String?,
        val MIX_STREAM_SERVER_KEY: String?,
    )
}
