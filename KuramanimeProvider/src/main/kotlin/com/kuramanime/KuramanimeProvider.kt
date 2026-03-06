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

    // Selector utama berdasarkan HTML terbaru
    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        
        // Ambil judul dari breadcrumb atau title tag
        val title = document.selectFirst("#episodeTitle")?.text()
            ?.substringBefore(" (Episode")?.trim() 
            ?: document.selectFirst(".anime__details__title > h3")?.text()?.trim() ?: ""
            
        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")
        val description = document.select(".anime__details__text > p").text().trim()
        
        val episodes = mutableListOf<Episode>()
        
        // Perbaikan: Episode sekarang ada di dalam #animeEpisodes
        document.select("#animeEpisodes a").forEach {
            val name = it.text().trim()
            val epsNum = Regex("(\\d+)").find(name)?.groupValues?.get(1)?.toIntOrNull()
            val href = fixUrl(it.attr("href"))
            
            episodes.add(newEpisode(href) {
                this.episode = epsNum
                this.name = name
            })
        }
        
        // Balikkan urutan jika perlu (biasanya Kuramanime urut dari Ep 1)
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
        // Selector kk yang baru berada di div breadcrumb
        val dataKps = res.selectFirst("div[data-kk]")?.attr("data-kk")

        val assets = getAssets(dataKps)
        val auth = getAuth()

        val headers = mapOf(
            "X-CSRF-TOKEN" to token,
            "X-Requested-With" to "XMLHttpRequest",
            "Authorization" to "Bearer $auth",
            "Referer" to data,
            "Accept" to "*/*"
        )

        // Loop server dari dropdown <select id="changeServer">
        res.select("select#changeServer option").forEach { source ->
            val serverValue = source.attr("value")
            val serverName = source.text().trim()
            
            // Request ke endpoint server untuk mendapatkan data player
            val apiUrl = "$data?server=$serverValue"
            
            val response = app.get(
                apiUrl,
                headers = headers,
                cookies = cookies
            )

            val videoDoc = Jsoup.parse(response.text)
            
            // 1. Ambil direct link dari tag <video> jika tersedia (KuramaDrive)
            videoDoc.select("video source").forEach {
                val videoUrl = it.attr("src")
                val quality = it.attr("size").toIntOrNull() ?: Qualities.Unknown.value
                
                callback.invoke(
                    newExtractorLink(
                        "Kurama $serverName",
                        "Kurama $serverName",
                        fixUrl(videoUrl),
                        data,
                        quality,
                        isM3u8 = videoUrl.contains(".m3u8")
                    )
                )
            }

            // 2. Ambil dari Iframe (FileMoon, DoodStream, dll)
            videoDoc.select("iframe").attr("src").let { iframeUrl ->
                if (iframeUrl.isNotEmpty()) {
                    loadExtractor(fixUrl(iframeUrl), data, subtitleCallback, callback)
                }
            }
        }
        
        // 3. Tambahkan support dari Tautan Unduh (Download Links)
        res.select("#animeDownloadLink a").forEach {
            val downloadUrl = it.attr("href")
            if (!downloadUrl.contains("kuramadrive")) { // kuramadrive butuh auth lagi, skip dulu
                loadExtractor(downloadUrl, data, subtitleCallback, callback)
            }
        }

        return true
    }

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
        // Berdasarkan HTML: /storage/leviathan.js?v=1389
        val res = app.get("$mainUrl/storage/leviathan.js").text
        val authArray = Regex("""'(.*?)'""").findAll(res).map { it.groupValues[1] }.toList()
        
        return if (authArray.size >= 10) {
            // Formula Kuramanime: Last + Index 9 + Index 1 + First + "i"
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
