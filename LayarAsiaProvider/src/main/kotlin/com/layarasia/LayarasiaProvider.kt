package com.layarasia

import com.lagradost.cloudstream3.* import com.lagradost.cloudstream3.LoadResponse.Companion.addActors  
import com.lagradost.cloudstream3.LoadResponse.Companion.addScore  
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer  
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import org.jsoup.nodes.Element
import java.net.URI
import org.jsoup.Jsoup
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class LayarasiaProvider : MainAPI() {
    override var mainUrl = "https://layarasia.lol"
    override var name = "LayarAsia"
    override val hasMainPage = true
    override var lang = "id"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    companion object {  
        fun getStatus(t: String): ShowStatus {
            return when (t) {
                "Completed" -> ShowStatus.Completed
                "Ongoing" -> ShowStatus.Ongoing
                else -> ShowStatus.Completed
            }
        }
    }

    override val mainPage = mainPageOf(
        "series/?order=update" to "Latest Update",
        "series/?type=movie&order=latest" to "Latest Movie",
        "series/?genre%5B%5D=drama-korea&order=update" to "Drama Korea",
        "series/?country%5B%5D=china&order=update" to "Drama China",
        "series/?genre%5B%5D=varshow&order=update" to "Variety Show",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = "$mainUrl/${request.data}".plus("&page=$page")
        val document = app.get(url).document
        val items = document.select("div.listupd article.bs")
                            .mapNotNull { it.toSearchResult() }
        return newHomePageResponse(HomePageList(request.name, items), hasNext = items.isNotEmpty())
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val linkElement = this.selectFirst("a") ?: return null
        val href = fixUrl(linkElement.attr("href"))
        val title = linkElement.attr("title").ifBlank {
            this.selectFirst("div.tt")?.text()
        } ?: return null
        val poster = this.selectFirst("img")?.getImageAttr()?.let { fixUrlNull(it) }

        val isSeries = href.contains("/series/", true) || href.contains("drama", true)

        return if (isSeries) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = poster
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = poster
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/?s=$query", timeout = 50L).document
        val results = document.select("div.listupd article.bs")
            .mapNotNull { it.toSearchResult() }
        return results
    }

    private fun Element.toRecommendResult(): SearchResponse? {
        val title = this.selectFirst("div.tt")?.text()?.trim() ?: return null
        val href = this.selectFirst("a")?.attr("href") ?: return null
        val posterUrl = this.selectFirst("img")?.getImageAttr()?.let { fixUrlNull(it) }
        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val title = document.selectFirst("h1.entry-title")?.text()?.trim().orEmpty()
        val poster = document.selectFirst("div.bigcontent img")?.getImageAttr()?.let { fixUrlNull(it) }
        val description = document.select("div.entry-content p")
            .joinToString("\n") { it.text() }
            .trim()
            
        val year = document.selectFirst("span:matchesOwn(Dirilis:)")?.ownText()
            ?.filter { it.isDigit() }?.take(4)?.toIntOrNull()

        val duration = document.selectFirst("div.spe span:contains(Durasi:)")?.ownText()?.let {
            val h = Regex("(\\d+)\\s*hr").find(it)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            val m = Regex("(\\d+)\\s*min").find(it)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            h * 60 + m
        }
        val country = document.selectFirst("span:matchesOwn(Negara:)")?.ownText()?.trim()
        val type = document.selectFirst("span:matchesOwn(Tipe:)")?.ownText()?.trim()
        val tags = document.select("div.genxed a").map { it.text() }
        val actors = document.select("span:has(b:matchesOwn(Artis:)) a")
            .map { it.text().trim() }
        val rating = document.selectFirst("div.rating strong")
            ?.text()
            ?.replace("Rating", "")
            ?.trim()
            ?.toDoubleOrNull()

        val trailer = document.selectFirst("div.bixbox.trailer iframe")?.attr("src")

        val status = getStatus(
            document.selectFirst("div.info-content div.spe span")
                ?.ownText()
                ?.replace(":", "")
                ?.trim()
                ?: ""
        )

        val recommendations = document.select("div.listupd article.bs")
            .mapNotNull { it.toRecommendResult() }

        val episodeElements = document.select("div.eplister ul li a")

        val episodes = episodeElements
            .reversed()
            .mapIndexed { index, aTag ->
                val href = fixUrl(aTag.attr("href"))

                newEpisode(href) {
                    this.name = "Episode ${index + 1}"
                    this.episode = index + 1
                }
            }

        return if (episodes.size > 1) {
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
                showStatus = status
                this.recommendations = recommendations
                this.duration = duration ?: 0
                if (rating != null) addScore(rating.toString(), 10)
                addActors(actors)
                addTrailer(trailer)
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, episodes.firstOrNull()?.data ?: url) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
                this.recommendations = recommendations
                this.duration = duration ?: 0
                if (rating != null) addScore(rating.toString(), 10)
                addActors(actors)
                addTrailer(trailer)
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
        val iframeLinks = mutableListOf<String>()

        document.selectFirst("div.player-embed iframe")
            ?.getIframeAttr()
            ?.let { iframe ->
                iframeLinks.add(httpsify(iframe))
            }

        val mirrorOptions = document.select("select.mirror option[value]:not([disabled])")
        for (opt in mirrorOptions) {
            val base64 = opt.attr("value")
            if (base64.isBlank()) continue
            try {
                val cleaned = base64.replace("\\s".toRegex(), "")
                val decodedHtml = base64Decode(cleaned)
                val iframeTag = Jsoup.parse(decodedHtml).selectFirst("iframe")
                val mirrorUrl = when {
                    iframeTag?.attr("src")?.isNotBlank() == true -> iframeTag.attr("src")
                    iframeTag?.attr("data-src")?.isNotBlank() == true -> iframeTag.attr("data-src")
                    else -> null
                }
                if (!mirrorUrl.isNullOrBlank()) {
                    iframeLinks.add(httpsify(mirrorUrl))
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        coroutineScope {
            iframeLinks.distinct().forEach { iframeUrl ->
                launch(Dispatchers.IO) {
                    if (iframeUrl.contains("upns.xyz", true) || iframeUrl.contains("strp2p.site", true)) {
                        extractHexPlayer(iframeUrl, callback)
                    } else {
                        loadExtractor(iframeUrl, data, subtitleCallback, callback)
                    }
                }
            }
        }

        val downloadLinks = document.select("div.dlbox li span.e a[href], div.soraurlx a[href]")
        for (a in downloadLinks) {
            val url = a.attr("href").trim()
            if (url.isNotBlank()) {
                loadExtractor(httpsify(url), data, subtitleCallback, callback)
            }
        }

        return true
    }

    private suspend fun extractHexPlayer(iframeSrc: String, callback: (ExtractorLink) -> Unit) {
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
                                name = host ?: "LayarAsia Server",
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

    private fun Element.getImageAttr(): String {
        return when {
            this.hasAttr("data-src") -> this.attr("abs:data-src")
            this.hasAttr("data-lazy-src") -> this.attr("abs:data-lazy-src")
            this.hasAttr("srcset") -> this.attr("abs:srcset").substringBefore(" ")
            else -> this.attr("abs:src")
        }
    }

    private fun Element?.getIframeAttr(): String? {
        return this?.attr("data-litespeed-src").takeIf { it?.isNotEmpty() == true }
                ?: this?.attr("src")
    }

    private fun String?.fixImageQuality(): String? {
        if (this == null) return null
        val regex = Regex("(-\\d*x\\d*)").find(this)?.groupValues?.get(0) ?: return this
        return this.replace(regex, "")
    }
}
