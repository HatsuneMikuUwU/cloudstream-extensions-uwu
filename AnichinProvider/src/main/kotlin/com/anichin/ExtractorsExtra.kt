package com.anichin

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.extractors.StreamWishExtractor
import com.lagradost.cloudstream3.extractors.VidStack
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink

class Morencius : StreamWishExtractor() {
    override var name = "VidHide"
    override var mainUrl = "https://morencius.com"
}

class Rpmvid : VidStack() {
    override var name = "RPM Share"
    override var mainUrl = "https://anichin.rpmvid.com"
    override var requiresReferer = true
}

class Turbovidhls : ExtractorApi() {
    override var name = "TurboVid"
    override var mainUrl = "https://turbovidhls.com"
    override val requiresReferer = true

    override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink>? {
        val headers = mapOf("Referer" to "$mainUrl/", "Origin" to mainUrl)

        val script = app.get(url, referer = referer ?: "$mainUrl/").document
            .selectXpath("//script[contains(text(),'var urlPlay')]")
            .html()
        if (script.isBlank()) return null

        var master = script.substringAfter("var urlPlay = '").substringBefore("'").trim()
        if (master.isBlank()) return null
        if (master.startsWith("//")) master = "https:$master"
        else if (master.startsWith("/")) master = mainUrl + master

        return listOf(
            newExtractorLink(name, name, master, ExtractorLinkType.M3U8) {
                this.referer = "$mainUrl/"
                this.headers = headers
                this.quality = Qualities.Unknown.value
            }
        )
    }
}
