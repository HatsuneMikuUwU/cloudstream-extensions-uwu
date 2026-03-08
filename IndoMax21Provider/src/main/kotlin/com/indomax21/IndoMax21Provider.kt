package com.indomax21

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import java.net.URI
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class IndoMax21Provider : MainAPI() {
    
    override var name = "IndoMax21"
    override var mainUrl = "https://homecookingrocks.com"
    override var lang = "id"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.NSFW, TvType.Movie, TvType.TvSeries, TvType.Anime)
    
    override val mainPage = mainPageOf(
        "$mainUrl/category/anime/" to "Anime",
        "$mainUrl/category/donghua/" to "Donghua",
        "$mainUrl/category/hentai/" to "Hentai",
        "$mainUrl/category/serial-tv/" to "TV Show",
        "$mainUrl/category/asia-m/" to "Asia",
        "$mainUrl/category/vivamax/" to "VivaMax",
        "$mainUrl/category/jav/" to "JAV",
        "$mainUrl/category/kelas-bintang/" to "Porn Star",
        "$mainUrl/category/semi-barat/" to "Western",
        "$mainUrl/category/bokep-indo/" to "Indonesia",
        "$mainUrl/category/bokep-vietnam/" to "Vietnam"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val url = if (page == 1) request.data else "${request.data}page/$page/"
        val document = app.get(url).document
        
        val elements = document.select("#gmr-main-load article, .gmr-item-modulepost")
        
        val home = elements.mapNotNull { element ->
            val titleElement = element.selectFirst(".entry-title a, h2 a")
            val title = titleElement?.text() ?: return@mapNotNull null
            val link = titleElement.attr("href")
            
            val img = element.selectFirst("img")
            var image = img?.attr("data-src")?.takeIf { it.isNotBlank() && !it.startsWith("data:") }
                ?: img?.attr("src")?.takeIf { it.isNotBlank() && !it.startsWith("data:") }
            image = image?.replace(Regex("-\\d+x\\d+\\."), ".")
            
            val isSeries = element.selectFirst(".gmr-numbeps") != null || 
                           link.contains("/anime/") || 
                           link.contains("/donghua/") || 
                           link.contains("/hentai/") || 
                           link.contains("/serial-tv/") || 
                           link.contains("/tv/")
                           
            val type = if (isSeries) TvType.TvSeries else TvType.Movie
            
            if (type == TvType.TvSeries) {
                newTvSeriesSearchResponse(title, link, type) {
                    this.posterUrl = image
                }
            } else {
                newMovieSearchResponse(title, link, type) {
                    this.posterUrl = image
                }
            }
        }
        return newHomePageResponse(request.name, home, hasNext = elements.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        val searchUrl = "$mainUrl/?s=$query"
        val document = app.get(searchUrl).document
        
        val elements = document.select("#gmr-main-load article, .gmr-item-modulepost")
        return elements.mapNotNull { element ->
            val titleElement = element.selectFirst(".entry-title a, h2 a")
            val title = titleElement?.text() ?: return@mapNotNull null
            val link = titleElement.attr("href")
            
            val img = element.selectFirst("img")
            var image = img?.attr("data-src")?.takeIf { it.isNotBlank() && !it.startsWith("data:") }
                ?: img?.attr("src")?.takeIf { it.isNotBlank() && !it.startsWith("data:") }
            image = image?.replace(Regex("-\\d+x\\d+\\."), ".")
            
            val isSeries = element.selectFirst(".gmr-numbeps") != null || 
                           link.contains("/anime/") || 
                           link.contains("/donghua/") || 
                           link.contains("/hentai/") || 
                           link.contains("/serial-tv/") || 
                           link.contains("/tv/")
                           
            val type = if (isSeries) TvType.TvSeries else TvType.Movie

            if (type == TvType.TvSeries) {
                newTvSeriesSearchResponse(title, link, type) {
                    this.posterUrl = image
                }
            } else {
                newMovieSearchResponse(title, link, type) {
                    this.posterUrl = image
                }
            }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        val title = document.selectFirst("h1.entry-title")?.text() ?: return null
        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")
        val plot = document.select(".entry-content p").joinToString("\n") { it.text() }.trim()
        val year = document.selectFirst(".gmr-moviedata:contains(Tahun:) a")?.text()?.toIntOrNull()
        val ratingString = document.selectFirst(".gmr-meta-rating span[itemprop=ratingValue]")?.text()
        val tags = document.select(".gmr-moviedata:contains(Genre:) a").map { it.text() }

        val episodesElements = document.select(".gmr-listseries a, .gmr-eps-list a, .button-seasons a, ul.gmr-episodes li a")
        
        val isSeries = episodesElements.isNotEmpty() || 
                       url.contains("/anime/") || 
                       url.contains("/donghua/") || 
                       url.contains("/hentai/") || 
                       url.contains("/serial-tv/") || 
                       url.contains("/tv/")

        if (isSeries) {
            val episodes = episodesElements.mapNotNull { epsElement ->
                val epsUrl = epsElement.attr("href")
                val epsName = epsElement.text().trim()
                
                if (epsUrl.isNotBlank()) {
                    val epsNum = Regex("(?i)(?:episode|eps|ep)\\s*(\\d+)").find(epsName)?.groupValues?.get(1)?.toIntOrNull() 
                        ?: Regex("\\d+").find(epsName)?.value?.toIntOrNull()
                        
                    val seasonNum = Regex("(?i)s(\\d+)").find(epsName)?.groupValues?.get(1)?.toIntOrNull()
                        
                    newEpisode(data = epsUrl) {
                        this.name = epsName
                        this.season = seasonNum
                        this.episode = epsNum
                    }
                } else null
            }

            val finalEpisodes = if (episodes.isNotEmpty()) episodes else listOf(
                newEpisode(data = url) {
                    this.name = "Episode 1"
                    this.episode = 1
                }
            )

            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, finalEpisodes.distinctBy { it.data }) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
                this.tags = tags
                this.score = Score.from10(ratingString)
            }
        } else {
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
                this.tags = tags
                this.score = Score.from10(ratingString)
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document

        val rawServerUrls = document.select(".muvipro-player-tabs a")
            .mapNotNull { it.attr("href").takeIf { href -> href.isNotBlank() }?.let { url -> fixUrl(url) } }
            .distinct()
            .toMutableList()

        if (rawServerUrls.isEmpty()) {
            rawServerUrls.add(data)
        }

        val sortedUrls = rawServerUrls.sortedBy { if (it == data) 0 else 1 }

        coroutineScope {
            sortedUrls.forEach { serverUrl ->
                launch(Dispatchers.IO) {
                    try {
                        val serverDoc = if (serverUrl == data) document else app.get(serverUrl, referer = data).document
                        
                        val iframes = serverDoc.select("iframe").mapNotNull { 
                            it.attr("src").takeIf { src -> src.isNotBlank() }?.let { src -> fixUrl(src) } 
                        }

                        iframes.forEach { iframeSrc ->
                            when {
                                iframeSrc.contains("pyrox", ignoreCase = true) || iframeSrc.contains("embedpyrox", ignoreCase = true) -> {
                                    extractPyrox(iframeSrc, data, callback)
                                }
                                iframeSrc.contains("4meplayer", ignoreCase = true) -> {
                                    extract4MePlayer(iframeSrc, callback)
                                }
                                iframeSrc.contains("imaxstreams", ignoreCase = true) -> {
                                    extractImaxStreams(iframeSrc, serverUrl, callback)
                                }
                                else -> {
                                    loadExtractor(iframeSrc, data, subtitleCallback, callback)
                                }
                            }
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }
        }
        return true
    }

    private suspend fun extractPyrox(iframeSrc: String, referer: String, callback: (ExtractorLink) -> Unit) {
        try {
            val iframeId = iframeSrc.split("/").last().substringBefore("?")
            val host = URI(iframeSrc).host
            val apiUrl = "https://$host/player/index.php?data=$iframeId&do=getVideo"

            val response = app.post(
                url = apiUrl,
                headers = mapOf(
                    "X-Requested-With" to "XMLHttpRequest", 
                    "Referer" to iframeSrc
                ),
                data = mapOf("hash" to iframeId, "r" to referer)
            ).text

            Regex("""(https:\\?\/\\?\/[^"]+\.(?:m3u8|mp4|txt)[^"]*)""").findAll(response).forEach { match ->
                val videoUrl = match.groupValues[1].replace("\\/", "/")
                val isM3u8 = videoUrl.contains(".m3u8", ignoreCase = true) || videoUrl.contains(".txt", ignoreCase = true)
                
                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = "Pyrox",
                        url = videoUrl,
                        type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    ) {
                        this.referer = iframeSrc
                        this.quality = Qualities.Unknown.value
                    }
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private suspend fun extract4MePlayer(iframeSrc: String, callback: (ExtractorLink) -> Unit) {
        try {
            val videoId = iframeSrc.substringAfterLast("#")
            if (videoId.isEmpty() || videoId == iframeSrc) return

            val host = URI(iframeSrc).host
            val endpoints = listOf(
                "https://$host/api/v1/video?id=$videoId",
                "https://$host/api/v1/info?id=$videoId"
            )

            for (apiUrl in endpoints) {
                try {
                    val hexResponse = app.get(apiUrl, referer = iframeSrc).text.trim()
                    if (hexResponse.isEmpty() || !hexResponse.matches(Regex("^[0-9a-fA-F]+$"))) continue

                    val secretKey = "kiemtienmua911ca".toByteArray(Charsets.UTF_8)
                    val ivBytes = ByteArray(16) { i -> if (i < 9) i.toByte() else 32.toByte() }

                    val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
                    cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(secretKey, "AES"), IvParameterSpec(ivBytes))

                    val decodedHex = hexResponse.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
                    val decryptedText = String(cipher.doFinal(decodedHex), Charsets.UTF_8)

                    Regex(""""([^"]+\.m3u8[^"]*)"""").find(decryptedText)?.groupValues?.get(1)?.let { match ->
                        val m3u8Url = match.replace("\\/", "/").let { 
                            if (it.startsWith("/")) "https://$host$it" else it 
                        }

                        callback.invoke(
                            newExtractorLink(
                                source = name,
                                name = "4MePlayer",
                                url = m3u8Url,
                                type = ExtractorLinkType.M3U8
                            ) {
                                this.referer = iframeSrc
                                this.quality = Qualities.Unknown.value
                            }
                        )
                        return
                    }
                } catch (e: Exception) {
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private suspend fun extractImaxStreams(iframeSrc: String, serverUrl: String, callback: (ExtractorLink) -> Unit) {
        try {
            val iframeHtml = app.get(iframeSrc, referer = serverUrl).text
            
            val unpackedText = getAndUnpack(iframeHtml)
            val m3u8Regex = """"([^"]+\.m3u8[^"]*)"""".toRegex()

            (m3u8Regex.find(unpackedText) ?: m3u8Regex.find(iframeHtml))?.let { match ->
                val m3u8Url = match.groupValues[1].replace("\\/", "/")
                
                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = "ImaxStreams",
                        url = m3u8Url,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = iframeSrc
                        this.quality = Qualities.Unknown.value
                    }
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
