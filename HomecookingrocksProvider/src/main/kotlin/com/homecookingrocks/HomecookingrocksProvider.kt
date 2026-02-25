package com.homecookingrocks

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class HomecookingrocksProvider : MainAPI() {
    
    override var name = "IndoMax21"
    override var mainUrl = "https://homecookingrocks.com"
    override var lang = "id"
    override val hasMainPage = true
    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.Movie,
        TvType.TvSeries,
        TvType.NSFW
    )
    
    override val mainPage = mainPageOf(
        "$mainUrl/category/anime/" to "Anime",
        "$mainUrl/category/donghua/" to "Donghua",
        "$mainUrl/category/hentai/" to "Hentai",
        "$mainUrl/category/serial-tv/" to "TV Show",
        "$mainUrl/category/asia-m/" to "Asia",
        "$mainUrl/category/vivamax/" to "VivaMax",
        "$mainUrl/category/jav/" to "JAV",
        "$mainUrl/category/kelas-bintang/" to "Porn Start",
        "$mainUrl/category/semi-barat/" to "Western",
        "$mainUrl/category/bokep-indo/" to "Indonesian",
        "$mainUrl/category/bokep-vietnam/" to "Vietnamese"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val url = if (page == 1) request.data else "${request.data}page/$page/"
        val document = app.get(url).document
        val elements = document.select("#gmr-main-load article")
        
        val home = elements.mapNotNull { element ->
            val titleElement = element.selectFirst(".entry-title a, h2 a")
            val title = titleElement?.text() ?: return@mapNotNull null
            val link = titleElement.attr("href")
            val image = element.selectFirst("img")?.attr("src")
            
            newMovieSearchResponse(title, link, TvType.Movie) {
                this.posterUrl = image
            }
        }
        return newHomePageResponse(request.name, home, hasNext = elements.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        val searchUrl = "$mainUrl/?s=$query"
        val document = app.get(searchUrl).document
        return document.select("#gmr-main-load article").mapNotNull { element ->
            val titleElement = element.selectFirst(".entry-title a, h2 a")
            val title = titleElement?.text() ?: return@mapNotNull null
            val url = titleElement.attr("href")
            val image = element.selectFirst("img")?.attr("src")
            
            newMovieSearchResponse(title, url, TvType.Movie) {
                this.posterUrl = image
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

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster
            this.plot = plot
            this.year = year
            this.tags = tags
            this.score = Score.from10(ratingString)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val html = app.get(data).text

        val tabsBlockMatch = Regex("""class=["'][^"']*muvipro-player-tabs[^"']*["'][^>]*>(.*?)</ul>""", RegexOption.DOT_MATCHES_ALL).find(html)
        
        val rawServerUrls = if (tabsBlockMatch != null) {
            Regex("""href=["']([^"']+)["']""").findAll(tabsBlockMatch.groupValues[1])
                .map { fixUrl(it.groupValues[1]) }
                .distinct()
                .toList()
        } else {
            listOf(data)
        }

        val sortedUrls = rawServerUrls.sortedBy { url ->
            if (url == data) 0 else 1
        }

        coroutineScope {
            sortedUrls.forEach { serverUrl ->
                launch(Dispatchers.IO) {
                    try {
                        val serverHtml = if (serverUrl == data) html else app.get(serverUrl, referer = data).text
                        
                        val iframeRegex = Regex(
                            """<iframe[^>]+src=["']([^"']+)["']""", 
                            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL) 
                        )
                        val allIframes = iframeRegex.findAll(serverHtml).map { it.groupValues[1] }.toList()

                        val iframeSrc = allIframes.firstOrNull { src ->
                            src.contains("pyrox", ignoreCase = true) || 
                            src.contains("4meplayer", ignoreCase = true) || 
                            src.contains("imaxstreams", ignoreCase = true)
                        } ?: allIframes.firstOrNull()

                        if (iframeSrc != null) {
                            if (iframeSrc.contains("embedpyrox") || iframeSrc.contains("pyrox")) {
                                val iframeId = iframeSrc.substringAfterLast("/")
                                val host = java.net.URI(iframeSrc).host
                                val apiUrl = "https://$host/player/index.php?data=$iframeId&do=getVideo"

                                val response = app.post(
                                    url = apiUrl,
                                    headers = mapOf("X-Requested-With" to "XMLHttpRequest", "Referer" to iframeSrc),
                                    data = mapOf("hash" to iframeId, "r" to data)
                                ).text

                                val m3u8Url = Regex("""(https:\\?\/\\?\/[^"]+(?:master\.txt|\.m3u8))""")
                                    .find(response)?.groupValues?.get(1)?.replace("\\/", "/")

                                if (m3u8Url != null) {
                                    callback.invoke(
                                        newExtractorLink(
                                            source = name,
                                            name = "Server 1 (Pyrox)",
                                            url = m3u8Url,
                                            type = ExtractorLinkType.M3U8
                                        ) {
                                            this.referer = iframeSrc
                                            this.quality = Qualities.Unknown.value
                                            this.headers = mapOf(
                                                "Origin" to "https://$host",
                                                "Accept" to "*/*"
                                            )
                                        }
                                    )
                                }
                            } 
                            else if (iframeSrc.contains("4meplayer")) {
                                val videoId = iframeSrc.substringAfterLast("#")
                                if (videoId.isNotEmpty() && videoId != iframeSrc) {
                                    val host = java.net.URI(iframeSrc).host
                                    
                                    val endpoints = listOf(
                                        "https://$host/api/v1/video?id=$videoId",
                                        "https://$host/api/v1/info?id=$videoId"
                                    )
                                    
                                    var foundM3u8 = false
                                    for (apiUrl in endpoints) {
                                        if (foundM3u8) break 
                                        
                                        try {
                                            val hexResponse = app.get(apiUrl, referer = iframeSrc).text.trim()
                                            
                                            if (hexResponse.isNotEmpty() && hexResponse.matches(Regex("^[0-9a-fA-F]+$"))) {
                                                val secretKey = "kiemtienmua911ca".toByteArray(Charsets.UTF_8)
                                                val ivBytes = ByteArray(16)
                                                for (i in 0..8) ivBytes[i] = i.toByte() 
                                                for (i in 9..15) ivBytes[i] = 32.toByte() 
                                                
                                                val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
                                                val secretKeySpec = SecretKeySpec(secretKey, "AES")
                                                val ivParameterSpec = IvParameterSpec(ivBytes)
                                                
                                                cipher.init(Cipher.DECRYPT_MODE, secretKeySpec, ivParameterSpec)
                                                
                                                val decodedHex = hexResponse.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
                                                val decryptedBytes = cipher.doFinal(decodedHex)
                                                val decryptedText = String(decryptedBytes, Charsets.UTF_8)
                                                
                                                val m3u8Regex = """"([^"]+\.m3u8[^"]*)"""".toRegex()
                                                val match = m3u8Regex.find(decryptedText)
                                                
                                                if (match != null) {
                                                    var m3u8Url = match.groupValues[1].replace("\\/", "/")
                                                    if (m3u8Url.startsWith("/")) {
                                                        m3u8Url = "https://$host$m3u8Url"
                                                    }
                                                    
                                                    callback.invoke(
                                                        newExtractorLink(
                                                            source = name,
                                                            name = "Server 2 (4MePlayer)",
                                                            url = m3u8Url,
                                                            type = ExtractorLinkType.M3U8
                                                        ) {
                                                            this.referer = iframeSrc
                                                            this.quality = Qualities.Unknown.value
                                                        }
                                                    )
                                                    foundM3u8 = true
                                                }
                                            }
                                        } catch (e: Exception) {
                                        }
                                    }
                                }
                            }
                            else if (iframeSrc.contains("imaxstreams")) {
                                val iframeHtml = app.get(iframeSrc, referer = serverUrl).text
                                
                                val unpackedText = getAndUnpack(iframeHtml)
                                
                                val m3u8Regex = """"([^"]+\.m3u8[^"]*)"""".toRegex()
                                val match = m3u8Regex.find(unpackedText) ?: m3u8Regex.find(iframeHtml)
                                
                                if (match != null) {
                                    val m3u8Url = match.groupValues[1].replace("\\/", "/")
                                    
                                    callback.invoke(
                                        newExtractorLink(
                                            source = name,
                                            name = "Server 3/4 (ImaxStreams)",
                                            url = m3u8Url,
                                            type = ExtractorLinkType.M3U8
                                        ) {
                                            this.referer = iframeSrc
                                            this.quality = Qualities.Unknown.value
                                        }
                                    )
                                }
                            }
                            else {
                                loadExtractor(iframeSrc, data, subtitleCallback, callback)
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
}
