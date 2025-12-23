package com.oppadrama

import com.lagradost.api.Log
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.extractors.EmturbovidExtractor
import com.lagradost.cloudstream3.extractors.VidHidePro
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.*



class Smoothpre: VidHidePro() {
    override var name = "EarnVids"
    override var mainUrl = "https://smoothpre.com"
}

class Emturbovid : EmturbovidExtractor() {
    override var name = "Emturbovid"
    override var mainUrl = "https://turbovidhls.com"
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