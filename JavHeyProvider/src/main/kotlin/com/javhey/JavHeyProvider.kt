package com.javhey

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class JavHeyProvider : MainAPI() {
    override var mainUrl = "https://javhey.com"
    override var name = "JavHey"
    override val hasMainPage = true
    override var lang = "id"
    override val supportedTypes = setOf(TvType.NSFW)
    override val vpnStatus = VPNStatus.MightBeNeeded

    private val headers = mapOf(
        "Authority" to "javhey.com",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8",
        "Accept-Language" to "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7",
        "Sec-Ch-Ua" to "\"Chromium\";v=\"137\", \"Not/A)Brand\";v=\"24\"",
        "Sec-Ch-Ua-Mobile" to "?1",
        "Sec-Ch-Ua-Platform" to "\"Android\"",
        "Upgrade-Insecure-Requests" to "1",
        "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Mobile Safari/537.36"
    )

    override val mainPage = mainPageOf(
        "$mainUrl/videos/paling-baru/page=" to "Latest Update",
        "$mainUrl/videos/paling-dilihat/page=" to "Most Viewed",
        "$mainUrl/videos/top-rating/page=" to "Top Rating"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val url = if (page == 1) request.data.replace("/page=", "") else request.data + page
        val document = app.get(url, headers = headers).document
        val home = document.select("div.article_standard_view > article.item").mapNotNull { toSearchResult(it) }
        return newHomePageResponse(request.name, home)
    }

    private fun toSearchResult(element: Element): SearchResponse? {
        val titleElement = element.selectFirst("div.item_content > h3 > a") ?: return null
        val title = titleElement.text().replace("JAV Subtitle Indonesia - ", "").trim()
        val href = fixUrl(titleElement.attr("href"))
        
        val imgElement = element.selectFirst("div.item_header > a > img")
        val posterUrl = imgElement?.attr("data-src")?.ifEmpty { imgElement.attr("src") }
        
        return newMovieSearchResponse(title, href, TvType.NSFW) { this.posterUrl = posterUrl }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/search?s=$query"
        val searchHeaders = headers + mapOf("Referer" to "$mainUrl/")
        val document = app.get(url, headers = searchHeaders).document
        return document.select("div.article_standard_view > article.item").mapNotNull { toSearchResult(it) }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url, headers = headers).document

        val rawTitle = document.selectFirst("article.post header.post_header h1")?.text()?.trim()
        val title = rawTitle?.replace("JAV Subtitle Indonesia - ", "") ?: "Unknown Title"

        val imgElement = document.selectFirst("div.product div.images img")
        val poster = imgElement?.attr("data-src")?.ifEmpty { imgElement.attr("src") }
            ?: document.selectFirst("meta[property=og:image]")?.attr("content")

        val description = document.selectFirst("p.video-description")?.text()?.replace("Description: ", "")?.trim()
            ?: document.selectFirst("meta[name=description]")?.attr("content")

        val metaDiv = document.select("div.product_meta")
        val actorNames = metaDiv.select("span:contains(Actor) a").map { it.text() }
        val tags = metaDiv.select("span:contains(Category) a, span:contains(Tag) a").map { it.text() }
        
        val releaseDateText = metaDiv.select("span:contains(Release Day)").text()
        val year = Regex("""\d{4}""").find(releaseDateText)?.value?.toIntOrNull()

        val recommendations = document.select("div.article_standard_view > article.item").mapNotNull { toSearchResult(it) }

        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = poster
            this.plot = description
            this.tags = tags
            this.actors = actorNames.map { Actor(it, null) }
            this.year = year
            this.recommendations = recommendations
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean = coroutineScope {
        val html = app.get(data, headers = headers).text
        val rawLinks = mutableSetOf<String>()

        Regex("""id=["']links["'][^>]*value=["']([^"']+)["']""").findAll(html).forEach { match ->
            try {
                val decodedBytes = Base64.getDecoder().decode(match.groupValues[1])
                val decoded = String(decodedBytes)
                decoded.split(",,,").forEach { 
                    if (it.startsWith("http")) rawLinks.add(it.trim()) 
                }
            } catch (e: Exception) { e.printStackTrace() }
        }

        rawLinks.forEach { url ->
            launch(Dispatchers.IO) {
                try {
                    var fixedUrl = url
                    if (url.contains("minochinos.com") || url.contains("terbit2.com")) {
                        fixedUrl = url.replace("minochinos.com", "streamwish.to")
                                      .replace("terbit2.com", "streamwish.to")
                        loadExtractor(fixedUrl, data, subtitleCallback, callback)
                    } else if (url.contains("bysebuho") || url.contains("bysezejataos") || url.contains("bysevepoin")) {
                        ByseSXLocal().getUrl(url, data, subtitleCallback, callback)
                    } else {
                        loadExtractor(fixedUrl, data, subtitleCallback, callback)
                    }
                } catch (e: Exception) { }
            }
        }
        return@coroutineScope true
    }
}

open class ByseSXLocal : ExtractorApi() {
    override var name = "Byse"
    override var mainUrl = "https://byse.sx"
    override val requiresReferer = true

    private fun b64UrlDecode(s: String): ByteArray {
        return try {
            val fixed = s.replace('-', '+').replace('_', '/')
            val pad = when (fixed.length % 4) {
                2 -> "=="
                3 -> "="
                else -> ""
            }
            Base64.getDecoder().decode(fixed + pad)
        } catch (e: Exception) { ByteArray(0) }
    }

    private fun getBaseUrl(url: String): String {
        return try { URI(url).let { "${it.scheme}://${it.host}" } } catch (e: Exception) { url }
    }

    private fun getCodeFromUrl(url: String): String {
        return URI(url).path?.trimEnd('/')?.substringAfterLast('/') ?: ""
    }

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val refererUrl = getBaseUrl(url)
        val code = getCodeFromUrl(url)
        
        val detailsUrl = "$refererUrl/api/videos/$code/embed/details"
        val details = app.get(detailsUrl).parsedSafe<DetailsRoot>() ?: return
        
        val embedFrameUrl = details.embedFrameUrl
        val embedBase = getBaseUrl(embedFrameUrl)
        val embedCode = getCodeFromUrl(embedFrameUrl)
        
        val playbackUrl = "$embedBase/api/videos/$embedCode/embed/playback"
        val playbackHeaders = mapOf("accept" to "*/*", "referer" to embedFrameUrl, "x-embed-parent" to (referer ?: mainUrl))
        val playback = app.get(playbackUrl, headers = playbackHeaders).parsedSafe<PlaybackRoot>()?.playback ?: return

        try {
            val keyBytes = b64UrlDecode(playback.keyParts[0]) + b64UrlDecode(playback.keyParts[1])
            val ivBytes = b64UrlDecode(playback.iv)
            val cipherBytes = b64UrlDecode(playback.payload)
            
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), GCMParameterSpec(128, ivBytes))
            
            var jsonStr = String(cipher.doFinal(cipherBytes), StandardCharsets.UTF_8)
            if (jsonStr.startsWith("\uFEFF")) jsonStr = jsonStr.substring(1)
            
            val streamUrl = tryParseJson<PlaybackDecrypt>(jsonStr)?.sources?.firstOrNull()?.url ?: return
            M3u8Helper.generateM3u8(name, streamUrl, refererUrl, headers = mapOf("Referer" to refererUrl)).forEach(callback)
        } catch (e: Exception) { e.printStackTrace() }
    }
}

data class DetailsRoot(@JsonProperty("embed_frame_url") val embedFrameUrl: String)
data class PlaybackRoot(@JsonProperty("playback") val playback: Playback)
data class Playback(@JsonProperty("iv") val iv: String, @JsonProperty("payload") val payload: String, @JsonProperty("key_parts") val keyParts: List<String>)
data class PlaybackDecrypt(@JsonProperty("sources") val sources: List<PlaybackDecryptSource>)
data class PlaybackDecryptSource(@JsonProperty("url") val url: String)
