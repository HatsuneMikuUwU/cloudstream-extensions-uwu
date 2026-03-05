package com.anoboy

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink

class BloggerExtractor : ExtractorApi() {
    override val name = "Blogger"
    override val mainUrl = "https://www.blogger.com"
    override val requiresReferer = true

    private val rpcId = "WcwnYd"

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val fixedUrl = if (url.startsWith("//")) "https:$url" else url

        if (fixedUrl.contains("blogger.googleusercontent.com", true)) {
            callback.invoke(
                newExtractorLink(
                    this.name,
                    this.name,
                    fixedUrl,
                    INFER_TYPE
                ) {
                    this.referer = referer ?: "$mainUrl/"
                }
            )
            return
        }

        val token = Regex("[?&]token=([^&]+)")
            .find(fixedUrl)
            ?.groupValues
            ?.getOrNull(1)
            ?: return

        val page = app.get(fixedUrl, referer = referer ?: "$mainUrl/")
        val html = page.text

        val fSid = Regex("FdrFJe\":\"(\\d+)\"")
            .find(html)
            ?.groupValues
            ?.getOrNull(1)
            ?: return
        val bl = Regex("cfb2h\":\"([^\"]+)\"")
            .find(html)
            ?.groupValues
            ?.getOrNull(1)
            ?: return
        val hl = Regex("lang=\"([^\"]+)\"")
            .find(html)
            ?.groupValues
            ?.getOrNull(1)
            ?.ifBlank { null }
            ?: "en-US"
        val reqId = (10000..99999).random()

        val payload = """[[["$rpcId","[\"$token\",\"\",0]",null,"generic"]]]"""
        val apiUrl = "$mainUrl/_/BloggerVideoPlayerUi/data/batchexecute" +
            "?rpcids=$rpcId&source-path=%2Fvideo.g&f.sid=$fSid&bl=$bl&hl=$hl&_reqid=$reqId&rt=c"

        val response = app.post(
            apiUrl,
            data = mapOf("f.req" to payload),
            referer = fixedUrl,
            headers = mapOf(
                "Origin" to mainUrl,
                "Content-Type" to "application/x-www-form-urlencoded;charset=UTF-8",
                "User-Agent" to USER_AGENT
            )
        ).text

        val decoded = decodeUnicodeEscapes(response)
        val urls = Regex("""https://[^\s"']+""")
            .findAll(decoded)
            .map { it.value }
            .filter {
                it.contains("googlevideo.com/videoplayback") ||
                    it.contains("blogger.googleusercontent.com")
            }
            .distinct()
            .toList()

        for (videoUrl in urls) {
            val itag = Regex("[?&]itag=(\\d+)")
                .find(videoUrl)
                ?.groupValues
                ?.getOrNull(1)
                ?.toIntOrNull()
            callback.invoke(
                newExtractorLink(
                    this.name,
                    this.name,
                    videoUrl,
                    INFER_TYPE
                ) {
                    this.referer = "$mainUrl/"
                    this.quality = itagToQuality(itag)
                }
            )
        }
    }

    private fun decodeUnicodeEscapes(input: String): String {
        val unicodeRegex = Regex("""\\\\u([0-9a-fA-F]{4})""")
        var output = unicodeRegex.replace(input) { match ->
            match.groupValues[1].toInt(16).toChar().toString()
        }
        output = output.replace("\\/", "/")
        output = output.replace("\\\\", "\\")
        output = output.replace("\\\"", "\"")
        return output
    }

    private fun itagToQuality(itag: Int?): Int {
        return when (itag) {
            18 -> Qualities.P360.value
            22 -> Qualities.P720.value
            37 -> Qualities.P1080.value
            59 -> Qualities.P480.value
            43 -> Qualities.P360.value
            36 -> Qualities.P240.value
            17 -> Qualities.P144.value
            137 -> Qualities.P1080.value
            136 -> Qualities.P720.value
            135 -> Qualities.P480.value
            134 -> Qualities.P360.value
            133 -> Qualities.P240.value
            160 -> Qualities.P144.value
            else -> Qualities.Unknown.value
        }
    }
}
