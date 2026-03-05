package com.oppadrama

import com.lagradost.api.Log
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.extractors.VidHidePro
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.ExtractorLinkType



class Smoothpre: VidHidePro() {
    override var name = "EarnVids"
    override var mainUrl = "https://smoothpre.com"
}

class BuzzServer : ExtractorApi() {
    override val name = "BuzzServer"
    override val mainUrl = "https://buzzheavier.com"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val qualityText = app.get(url).documentLarge.selectFirst("div.max-w-2xl > span")?.text()
            val quality = getQualityFromName(qualityText)
            val response = app.get("$url/download", referer = url, allowRedirects = false)
            val redirectUrl = response.headers["hx-redirect"] ?: ""

            if (redirectUrl.isNotEmpty()) {
                callback.invoke(
                    newExtractorLink(
                        "BuzzServer",
                        "BuzzServer",
                        redirectUrl,
                    ) {
                        this.quality = quality
                    }
                )
            } else {
                Log.w("BuzzServer", "No redirect URL found in headers.")
            }
        } catch (e: Exception) {
            Log.e("BuzzServer", "Exception occurred: ${e.message}")
        }
    }
}

open class EmturbovidExtractor : ExtractorApi() {
    override var name = "Emturbovid"
    override var mainUrl = "https://emturbovid.com"
    override val requiresReferer = true

    override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink>? {
        val ref = referer ?: "$mainUrl/"

        val headers = mapOf(
            "Referer" to "$mainUrl/",
            "Origin" to mainUrl,
            "User-Agent" to "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36",
            "Accept" to "*/*"
        )

        val page = app.get(url, referer = ref)

        val playerScript = page.document
            .selectXpath("//script[contains(text(),'var urlPlay')]")
            .html()

        if (playerScript.isBlank()) return null

        var masterUrl = playerScript
            .substringAfter("var urlPlay = '")
            .substringBefore("'")
            .trim()

    
        if (masterUrl.startsWith("//")) masterUrl = "https:$masterUrl"
        if (masterUrl.startsWith("/")) masterUrl = mainUrl + masterUrl

        val masterText = app.get(masterUrl, headers = headers).text
        val lines = masterText.lines()

        val out = mutableListOf<ExtractorLink>()

        for (i in 0 until lines.size) {
            val line = lines[i].trim()
            if (!line.startsWith("#EXT-X-STREAM-INF")) continue

            val height = Regex("RESOLUTION=\\d+x(\\d+)")
                .find(line)
                ?.groupValues
                ?.getOrNull(1)
                ?.toIntOrNull()

            val next = lines.getOrNull(i + 1)?.trim().orEmpty()
            if (next.isBlank() || next.startsWith("#")) continue

            var variantUrl = next
            if (variantUrl.startsWith("//")) variantUrl = "https:$variantUrl"
            else if (variantUrl.startsWith("/")) variantUrl = mainUrl + variantUrl

            val q = height ?: Qualities.Unknown.value

            out += newExtractorLink(
                source = name,
                name = name,
                url = variantUrl,
                type = ExtractorLinkType.M3U8
            ) {
                this.referer = "$mainUrl/"
                this.headers = headers
                this.quality = q
            }
        }

        if (out.isEmpty()) {
            out += newExtractorLink(
                source = name,
                name = name,
                url = masterUrl,
                type = ExtractorLinkType.M3U8
            ) {
                this.referer = "$mainUrl/"
                this.headers = headers
                this.quality = Qualities.Unknown.value
            }
        }

        return out
    }
}